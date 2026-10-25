package com.example.thermohammer.engine.gpu

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY SIMULATION — everything that moves in GPU Test 2, on the CPU.
//
//   A port of the timelines in BenchmarkScene/build_neon_city.py: the camera
//   spline, the car-following traffic model with its signal cycle, people,
//   trains, drones, and the animation groups that pulse and chase signs. Fixed
//   1/30 s traffic step, so every device sees the same streets at the same t;
//   cars are drawn between the last two steps, so they glide at any frame rate.
//   Pure JVM (no GL) — unit-tested.
// ─────────────────────────────────────────────────────────────────────────────

internal class NeonCitySim(asset: NeonCityAsset) {

    companion object {
        const val DURATION = 60.0              // camera timeline, seconds
        const val DT = 1.0 / 30.0
        const val WARMUP = 40.0
        const val SIGNAL_PERIOD = 30.0
        const val SIGNAL_OFFSET = 4.0
        const val RAIL_Z = 11f
        const val TRAIN_CAR_L = 19.5f
        const val TRAIN_GAP = 0.8f
        const val STRIDE = 1.5f                // metres per walk cycle
        const val INSTANCE_FLOATS = 20         // 3 transform rows + 2 extra vec4

        // Intelligent Driver Model: max accel, comfortable braking, headway, jam gap
        private const val IDM_A = 1.6
        private const val IDM_B = 3.0
        private const val IDM_T = 1.3
        private const val IDM_S0 = 2.4

        val CAR_LENGTH = doubleArrayOf(4.65, 4.10, 4.75, 4.70, 5.10, 11.5)

        /** 0 green, 1 yellow, 2 red for signal 0 (avenue) or 1 (cross street). */
        fun signalState(group: Int, t: Double): Int {
            val u = (t + SIGNAL_OFFSET).mod(SIGNAL_PERIOD)
            return if (group == 0) {
                if (u < 14.0) 0 else if (u < 17.0) 1 else 2
            } else {
                if (u < 18.0) 2 else if (u < 27.0) 0 else if (u < 29.5) 1 else 2
            }
        }
    }

    /** Instances of one actor mesh, refreshed every frame (rows + paint/brake or walk data). */
    class ActorBatch(val mesh: String, capacity: Int) {
        val data = FloatArray(capacity * INSTANCE_FLOATS)
        var count = 0
    }

    // ── tables ──────────────────────────────────────────────────────────────

    private val camera = asset.table("camera")
    private val lanes = asset.table("lanes")
    private val cars = asset.table("cars")
    private val peds = asset.table("peds")
    private val trains = asset.table("trains")
    private val drones = asset.table("drones")

    // ── state ───────────────────────────────────────────────────────────────

    private val carS = DoubleArray(cars.rows)
    private val prevS = DoubleArray(cars.rows)          // positions one step back, for drawing between steps
    private val carV = DoubleArray(cars.rows)
    private val carA = DoubleArray(cars.rows)
    private val acc = DoubleArray(cars.rows)
    private val byLane: List<IntArray> = (0 until lanes.rows).map { li ->
        (0 until cars.rows).filter { cars[it, 0].toInt() == li }.toIntArray()
    }
    private var simTime = -WARMUP

    val carBatches = NeonCityAsset.CAR_KINDS.map { k ->
        ActorBatch("car_$k", (0 until cars.rows).count { NeonCityAsset.CAR_KINDS[cars[it, 2].toInt()] == k })
    }
    val pedBatches = (0 until 4).map { v -> ActorBatch("ped_$v", (0 until peds.rows).count { peds[it, 6].toInt() == v }) }
    private val trainCars = (0 until trains.rows).sumOf { trains[it, 4].toInt() }
    val trainHeads = ActorBatch("train_head", trains.rows)
    val trainCarsBatch = ActorBatch("train_car", trainCars)
    val droneBatch = ActorBatch("drone", drones.rows)
    val policeBatch = ActorBatch("drone_police", 1)

    val eye = FloatArray(3)
    val target = FloatArray(3)
    var fovDeg = 52f; private set
    val groups = FloatArray(32)                 // animation-group intensity multipliers

    /** Searchlight of the police drone: position, aim point. */
    val searchPos = FloatArray(3)
    val searchAim = FloatArray(3)

    /** Car lamps for dynamic lights: x y z, fwd x y, length, brake (0/1) per car. */
    val carLamps = FloatArray(cars.rows * 7)
    val carCount get() = cars.rows

    /** Distance along its lane and speed of car `i` (for tests and lights). */
    fun carProgress(i: Int) = carS[i]
    fun carSpeed(i: Int) = carV[i]
    fun carLane(i: Int) = cars[i, 0].toInt()

    /** Train car centres (x, y, z) currently on the viaduct, for interior light spill. */
    val trainLamps = FloatArray(trainCars * 3)
    var trainLampCount = 0; private set

    init {
        for (i in 0 until cars.rows) {
            carS[i] = cars[i, 1].toDouble()
            carV[i] = lanes[cars[i, 0].toInt(), 5] * cars[i, 6] * 0.9
        }
        stepTraffic(0.0)                       // warm up so traffic is already flowing at t = 0
    }

    // ── per frame ───────────────────────────────────────────────────────────

    /** Advances everything to timeline time `t` (seconds, ≥ 0; the camera loops every DURATION). */
    fun update(t: Double) {
        stepTraffic(t)
        cameraAt(t.mod(DURATION))
        animateGroups(t)
        poseCars(((t - (simTime - DT)) / DT).coerceIn(0.0, 1.0))
        posePeople(t)
        poseTrains(t)
        poseDrones(t)
    }

    private fun stepTraffic(until: Double) {
        while (simTime < until - 1e-9) {
            for (li in byLane.indices) {
                val idx = byLane[li]
                if (idx.isEmpty()) continue
                val length = lanes[li, 4].toDouble()
                val v0l = lanes[li, 5].toDouble()
                val stopS = lanes[li, 6].toDouble()
                val sig = signalState(lanes[li, 7].toInt(), simTime)
                val order = idx.sortedBy { carS[it] }
                for (n in order.indices) {
                    val i = order[n]
                    val s = carS[i]; val v = carV[i]
                    val v0 = v0l * cars[i, 6]
                    val lead = order[(n + 1) % order.size]
                    val half = CAR_LENGTH[cars[i, 2].toInt()] / 2
                    var gap = (carS[lead] - s).mod(length) - CAR_LENGTH[cars[lead, 2].toInt()] / 2 - half
                    var dv = v - carV[lead]
                    if (sig != 0) {                                    // stop line ahead?
                        val toLine = stopS - (s + half)
                        if (toLine > 0.0 && toLine < 90.0 && (sig == 2 || toLine > v * v / (2 * IDM_B))) {
                            if (toLine < gap) { gap = toLine; dv = v }
                        }
                    }
                    gap = max(gap, 0.1)
                    val sStar = IDM_S0 + max(0.0, v * IDM_T + v * dv / (2 * sqrt(IDM_A * IDM_B)))
                    val a = IDM_A * (1.0 - (v / v0).pow(4) - (sStar / gap).pow(2))
                    acc[i] = max(a, -9.0)
                }
            }
            System.arraycopy(carS, 0, prevS, 0, carS.size)
            for (i in carS.indices) {
                val length = lanes[cars[i, 0].toInt(), 4].toDouble()
                val v = max(0.0, carV[i] + acc[i] * DT)
                carS[i] = (carS[i] + v * DT).mod(length)
                carV[i] = v
                carA[i] = acc[i]
            }
            simTime += DT
        }
    }

    /** Uniform Catmull-Rom through the camera keys (eye and target), linear FOV. */
    private fun cameraAt(tIn: Double) {
        val n = camera.rows
        val t = tIn.coerceIn(camera[0, 0].toDouble(), camera[n - 1, 0].toDouble())
        var i = 0
        while (i < n - 2 && camera[i + 1, 0] < t) i++
        val t0 = camera[i, 0]; val t1 = camera[i + 1, 0]
        val u = ((t - t0) / (t1 - t0)).toFloat()
        val r0 = max(i - 1, 0); val r3 = minOf(i + 2, n - 1)
        for ((out, col) in listOf(eye to 1, target to 4)) for (c in 0 until 3) {
            val p0 = camera[r0, col + c]; val p1 = camera[i, col + c]
            val p2 = camera[i + 1, col + c]; val p3 = camera[r3, col + c]
            out[c] = 0.5f * (2 * p1 + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u +
                (-p0 + 3 * p1 - 3 * p2 + p3) * u * u * u)
        }
        var k = 0
        while (k < n - 1 && camera[k + 1, 0] <= t + 1e-9) k++
        val j = minOf(k + 1, n - 1)
        val span = (camera[j, 0] - camera[k, 0]).takeIf { it > 0f } ?: 1f
        fovDeg = camera[k, 7] + (camera[j, 7] - camera[k, 7]) * ((t - camera[k, 0]) / span).toFloat()
    }

    private fun animateGroups(t: Double) {
        val tf = t.toFloat()
        groups.fill(1f)
        // groups 1-8 ("faulty tubes" in the scene file) stay lit: blinking signs read as a rendering glitch
        for (g in 9..12) groups[g] = 1f                    // chase pattern is spatial (shader)
        for (g in 13..16) groups[g] = 0.62f + 0.38f * sin(tf * (0.9f + 0.35f * (g - 13)) + g)
        for (sigGroup in 0..1) {
            val lit = when (signalState(sigGroup, t)) { 0 -> 2; 1 -> 1; else -> 0 }
            for (lamp in 0..2) groups[24 + lamp + 3 * sigGroup] = if (lamp == lit) 1f else 0.02f
        }
        groups[30] = if ((t / 1.6).mod(1.0) < 0.14) 1f else 0.04f       // aviation beacons
        val phase = (t * 2.2).mod(1.0)
        groups[31] = if ((t * 11.0).mod(1.0) < 0.5 && phase < 0.6) 1f else 0f   // police strobe
    }

    private fun writeRows(b: ActorBatch, x: Float, y: Float, z: Float, yaw: Float,
                          e0: Float = 0f, e1: Float = 0f, e2: Float = 0f, e3: Float = 0f,
                          f0: Float = 0f, f1: Float = 0f) {
        val o = b.count * INSTANCE_FLOATS
        val c = cos(yaw); val s = sin(yaw)
        val d = b.data
        d[o] = c; d[o + 1] = -s; d[o + 2] = 0f; d[o + 3] = x
        d[o + 4] = s; d[o + 5] = c; d[o + 6] = 0f; d[o + 7] = y
        d[o + 8] = 0f; d[o + 9] = 0f; d[o + 10] = 1f; d[o + 11] = z
        d[o + 12] = e0; d[o + 13] = e1; d[o + 14] = e2; d[o + 15] = e3
        d[o + 16] = f0; d[o + 17] = f1; d[o + 18] = 0f; d[o + 19] = 0f
        b.count++
    }

    /** `alpha` 0…1: how far `t` lies between the previous traffic step and the current one. */
    private fun poseCars(alpha: Double) {
        for (b in carBatches) b.count = 0
        for (i in 0 until cars.rows) {
            val li = cars[i, 0].toInt()
            val dx = lanes[li, 2]; val dy = lanes[li, 3]
            val length = lanes[li, 4].toDouble()
            val s = (prevS[i] + (carS[i] - prevS[i]).mod(length) * alpha).mod(length).toFloat()   // cars only move forward
            val x = lanes[li, 0] + dx * s
            val y = lanes[li, 1] + dy * s
            val kind = cars[i, 2].toInt()
            val brake = if (carA[i] < -0.6 || carV[i] < 0.3) 1f else 0f
            writeRows(carBatches[kind], x, y, 0f, atan2(-dx, dy), cars[i, 3], cars[i, 4], cars[i, 5], brake)
            val o = i * 7
            carLamps[o] = x; carLamps[o + 1] = y; carLamps[o + 2] = 0f
            carLamps[o + 3] = dx; carLamps[o + 4] = dy
            carLamps[o + 5] = CAR_LENGTH[kind].toFloat(); carLamps[o + 6] = brake
        }
    }

    private fun posePeople(t: Double) {
        for (b in pedBatches) b.count = 0
        for (i in 0 until peds.rows) {
            val dir = peds[i, 2]; val seg = peds[i, 3].toDouble(); val speed = peds[i, 4].toDouble()
            val walked = peds[i, 5] + dir * speed * t
            val y = peds[i, 1] + walked.mod(seg).toFloat()
            val cycle = (speed * t / STRIDE * 2.0 * PI + i * 1.7).toFloat()
            writeRows(pedBatches[peds[i, 6].toInt()], peds[i, 0], y, 0.15f, if (dir > 0f) 0f else PI.toFloat(),
                peds[i, 7], peds[i, 8], peds[i, 9], peds[i, 10],
                cycle, if (speed > 0.0) 0.42f else 0f)
        }
    }

    private fun poseTrains(t: Double) {
        trainHeads.count = 0; trainCarsBatch.count = 0; trainLampCount = 0
        for (r in 0 until trains.rows) {
            val ty = trains[r, 0]; val d = trains[r, 1]; val tCross = trains[r, 2]; val speed = trains[r, 3]
            val yaw = if (d > 0f) -PI.toFloat() / 2 else PI.toFloat() / 2
            for (ci in 0 until trains[r, 4].toInt()) {
                val off = -(ci * (TRAIN_CAR_L + TRAIN_GAP) + TRAIN_CAR_L / 2)
                val x = d * (speed * (t.toFloat() - tCross) + off)
                writeRows(if (ci == 0) trainHeads else trainCarsBatch, x, ty, RAIL_Z, yaw)
                if (kotlin.math.abs(x) < 520f) {
                    val o = trainLampCount++ * 3
                    trainLamps[o] = x; trainLamps[o + 1] = ty; trainLamps[o + 2] = RAIL_Z + 2.6f
                }
            }
        }
    }

    private fun poseDrones(t: Double) {
        droneBatch.count = 0; policeBatch.count = 0
        val tf = t.toFloat()
        for (r in 0 until drones.rows) {
            if (drones[r, 6] > 0.5f) {
                // police drone: holds station ahead of the camera, sweeping its searchlight over the street
                val x = 5f * sin(tf * 0.13f)
                val y = eye[1] + 48f + 8f * sin(tf * 0.21f)
                val z = 24f + 1.5f * sin(tf * 0.5f)
                writeRows(policeBatch, x, y, z, 0.25f * sin(tf * 0.3f))
                searchPos[0] = x; searchPos[1] = y + 0.3f; searchPos[2] = z - 0.6f
                searchAim[0] = 7f * sin(tf * 0.7f)
                searchAim[1] = y - 14f + 9f * sin(tf * 0.33f)
                searchAim[2] = 0f
            } else {
                val a = drones[r, 4] * tf + drones[r, 5]
                val rad = drones[r, 3]
                val x = drones[r, 0] + rad * cos(a)
                val y = drones[r, 1] + rad * sin(a)
                val z = drones[r, 2] + 0.6f * sin(tf * 1.3f + drones[r, 5])
                val sign = if (drones[r, 4] >= 0f) 1f else -1f
                writeRows(droneBatch, x, y, z, atan2(sin(a) * sign, cos(a) * sign))
            }
        }
    }
}
