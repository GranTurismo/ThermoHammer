namespace ThermoHammer.Engine.Gpu;

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY — the asset (neon_city.bin, the Android app's file) and everything
// that moves in GPU Test 2, on the CPU: a port of the phones' NeonCityAsset and
// NeonCitySim with the same arithmetic (Kotlin's floored `mod`, the same
// Float/Double mix), so every device sees the same streets at the same t.
// ─────────────────────────────────────────────────────────────────────────────

public sealed class NeonCityAsset
{
    public sealed record Mesh(string Name, int VertexCount, int IndexCount, bool WideIndices, bool Chunk, byte[] Vertices, byte[] Indices);

    /// <summary>Instances of one mesh: count × {x, y, z, euler x, y, z, scale x, y, z}.</summary>
    public sealed record Group(string Name, int Mesh, int Count, float[] Transforms);

    public sealed record Table(string Name, int Rows, int Cols, float[] Data)
    {
        public float this[int r, int c] => Data[r * Cols + c];
    }

    public const int VertexBytes = 20;     // f32 xyz · i8 normal + channel · u8 sRGB rgb + class
    public static readonly string[] CarKinds = ["sedan", "hatch", "suv", "taxi", "van", "bus"];

    public List<Mesh> Meshes = [];
    public List<Group> Groups = [];
    public Dictionary<string, Table> Tables = [];

    public NeonCityAsset(byte[] data)
    {
        var r = new ByteReader(data);
        if (r.Ascii(4) != "NCSC") throw new SceneError("not a Neon City scene");
        if (r.I32() != 1) throw new SceneError("Neon City asset version");
        for (int i = r.I32(); i > 0; i--)
        {
            var name = r.Name16();
            int vc = r.I32(), ic = r.I32(), flags = r.I32();
            r.F32();   // max z
            bool wide = (flags & 1) != 0;
            var v = r.Bytes(vc * VertexBytes);
            var idx = r.Bytes(ic * (wide ? 4 : 2));
            if (!wide && ic % 2 == 1) r.Skip(2);
            Meshes.Add(new Mesh(name, vc, ic, wide, (flags & 2) != 0, v, idx));
        }
        for (int i = r.I32(); i > 0; i--)
        {
            var name = r.Name16();
            int mesh = r.I32(), count = r.I32();
            var t = new float[count * 9];
            for (int k = 0; k < t.Length; k++) t[k] = r.F32();
            Groups.Add(new Group(name, mesh, count, t));
        }
        for (int i = r.I32(); i > 0; i--)
        {
            var name = r.Name16();
            int rows = r.I32(), cols = r.I32();
            var d = new float[rows * cols];
            for (int k = 0; k < d.Length; k++) d[k] = r.F32();
            Tables[name] = new Table(name, rows, cols, d);
        }
        if (!r.AtEnd) throw new SceneError("Neon City asset has trailing bytes");
    }

    public int MeshIndex(string name) => Meshes.FindIndex(m => m.Name == name);
    public Table GetTable(string name) => Tables.TryGetValue(name, out var t) ? t : new Table(name, 0, 0, []);
    public List<int> ChunkMeshes => Enumerable.Range(0, Meshes.Count).Where(i => Meshes[i].Chunk).ToList();
}

public sealed class NeonCitySim
{
    public const double Duration = 60.0;              // camera timeline, seconds
    public const double Dt = 1.0 / 30.0;
    public const double Warmup = 40.0;
    public const double SignalPeriod = 30.0;
    public const double SignalOffset = 4.0;
    public const float RailZ = 11;
    public const float TrainCarL = 19.5f;
    public const float TrainGap = 0.8f;
    public const float Stride = 1.5f;                 // metres per walk cycle
    public const int InstanceFloats = 20;             // 3 transform rows + 2 extra vec4
    private const double IdmA = 1.6, IdmB = 3.0, IdmT = 1.3, IdmS0 = 2.4;
    public static readonly double[] CarLength = [4.65, 4.10, 4.75, 4.70, 5.10, 11.5];

    /// <summary>Kotlin's Double.mod: the remainder with the divisor's sign.</summary>
    public static double KMod(double a, double b)
    {
        double r = a % b;   // C#'s % truncates like Swift's truncatingRemainder
        return r != 0 && (r < 0) != (b < 0) ? r + b : r;
    }

    /// <summary>0 green, 1 yellow, 2 red for signal 0 (avenue) or 1 (cross street).</summary>
    public static int SignalState(int group, double t)
    {
        double u = KMod(t + SignalOffset, SignalPeriod);
        if (group == 0) return u < 14 ? 0 : u < 17 ? 1 : 2;
        return u < 18 ? 2 : u < 27 ? 0 : u < 29.5 ? 1 : 2;
    }

    /// <summary>Instances of one actor mesh, refreshed every frame (rows + paint/brake or walk data).</summary>
    public sealed class ActorBatch(string mesh, int capacity)
    {
        public readonly string Mesh = mesh;
        public readonly float[] Data = new float[Math.Max(capacity, 1) * InstanceFloats];
        public int Count;
    }

    private readonly NeonCityAsset.Table camera, lanes, cars, peds, trains, drones;
    private readonly double[] carS, carV, carA, acc;
    private double[] prevS;
    private readonly List<int>[] byLane;
    private double simTime = -Warmup;

    public readonly ActorBatch[] CarBatches;
    public readonly ActorBatch[] PedBatches;
    public readonly ActorBatch TrainHeads, TrainCarsBatch, DroneBatch, PoliceBatch;

    public float[] Eye = new float[3];
    public float[] Target = new float[3];
    public float FovDeg { get; private set; } = 52;
    /// <summary>Animation-group intensity multipliers.</summary>
    public float[] Groups = Enumerable.Repeat(1f, 32).ToArray();
    /// <summary>Searchlight of the police drone: position, aim point.</summary>
    public float[] SearchPos = new float[3], SearchAim = new float[3];
    /// <summary>Car lamps for dynamic lights: x y z, fwd x y, length, brake (0/1) per car.</summary>
    public float[] CarLamps;
    public int CarCount => cars.Rows;
    /// <summary>Train car centres (x, y, z) currently on the viaduct, for interior light spill.</summary>
    public float[] TrainLamps;
    public int TrainLampCount { get; private set; }

    public NeonCitySim(NeonCityAsset asset)
    {
        camera = asset.GetTable("camera"); lanes = asset.GetTable("lanes"); cars = asset.GetTable("cars");
        peds = asset.GetTable("peds"); trains = asset.GetTable("trains"); drones = asset.GetTable("drones");
        int n = cars.Rows;
        carS = new double[n]; prevS = new double[n]; carV = new double[n]; carA = new double[n]; acc = new double[n];
        byLane = Enumerable.Range(0, lanes.Rows).Select(li => Enumerable.Range(0, n).Where(i => (int)cars[i, 0] == li).ToList()).ToArray();
        CarBatches = NeonCityAsset.CarKinds.Select((kind, k) => new ActorBatch($"car_{kind}", Enumerable.Range(0, n).Count(i => (int)cars[i, 2] == k))).ToArray();
        PedBatches = Enumerable.Range(0, 4).Select(v => new ActorBatch($"ped_{v}", Enumerable.Range(0, peds.Rows).Count(i => (int)peds[i, 6] == v))).ToArray();
        int trainCars = Enumerable.Range(0, trains.Rows).Sum(i => (int)trains[i, 4]);
        TrainHeads = new ActorBatch("train_head", trains.Rows);
        TrainCarsBatch = new ActorBatch("train_car", trainCars);
        DroneBatch = new ActorBatch("drone", drones.Rows);
        PoliceBatch = new ActorBatch("drone_police", 1);
        CarLamps = new float[n * 7];
        TrainLamps = new float[trainCars * 3];
        for (int i = 0; i < n; i++)
        {
            carS[i] = cars[i, 1];
            carV[i] = (double)(lanes[(int)cars[i, 0], 5] * cars[i, 6]) * 0.9;
        }
        StepTraffic(0);                       // warm up so traffic is already flowing at t = 0
    }

    /// <summary>Advances everything to timeline time `t` (seconds, ≥ 0; the camera loops every duration).</summary>
    public void Update(double t)
    {
        StepTraffic(t);
        CameraAt(KMod(t, Duration));
        AnimateGroups(t);
        PoseCars(Math.Min(1, Math.Max(0, (t - (simTime - Dt)) / Dt)));
        PosePeople(t);
        PoseTrains(t);
        PoseDrones(t);
    }

    private void StepTraffic(double until)
    {
        while (simTime < until - 1e-9)
        {
            for (int li = 0; li < byLane.Length; li++)
            {
                var idx = byLane[li];
                if (idx.Count == 0) continue;
                double length = lanes[li, 4], v0l = lanes[li, 5], stopS = lanes[li, 6];
                int sig = SignalState((int)lanes[li, 7], simTime);
                var order = idx.OrderBy(i => carS[i]).ToList();
                for (int n = 0; n < order.Count; n++)
                {
                    int i = order[n];
                    double s = carS[i], v = carV[i];
                    double v0 = v0l * cars[i, 6];
                    int lead = order[(n + 1) % order.Count];
                    double half = CarLength[(int)cars[i, 2]] / 2;
                    double gap = KMod(carS[lead] - s, length) - CarLength[(int)cars[lead, 2]] / 2 - half;
                    double dv = v - carV[lead];
                    if (sig != 0)                                    // stop line ahead?
                    {
                        double toLine = stopS - (s + half);
                        if (toLine > 0 && toLine < 90 && (sig == 2 || toLine > v * v / (2 * IdmB)))
                            if (toLine < gap) { gap = toLine; dv = v; }
                    }
                    gap = Math.Max(gap, 0.1);
                    double sStar = IdmS0 + Math.Max(0, v * IdmT + v * dv / (2 * Math.Sqrt(IdmA * IdmB)));
                    double a = IdmA * (1 - Math.Pow(v / v0, 4) - Math.Pow(sStar / gap, 2));
                    acc[i] = Math.Max(a, -9);
                }
            }
            Array.Copy(carS, prevS, carS.Length);
            for (int i = 0; i < carS.Length; i++)
            {
                double length = lanes[(int)cars[i, 0], 4];
                double v = Math.Max(0, carV[i] + acc[i] * Dt);
                carS[i] = KMod(carS[i] + v * Dt, length);
                carV[i] = v;
                carA[i] = acc[i];
            }
            simTime += Dt;
        }
    }

    /// <summary>Uniform Catmull-Rom through the camera keys (eye and target), linear FOV.</summary>
    private void CameraAt(double tIn)
    {
        int n = camera.Rows;
        double t = Math.Min(Math.Max(tIn, camera[0, 0]), camera[n - 1, 0]);
        int i = 0;
        while (i < n - 2 && camera[i + 1, 0] < t) i++;
        float t0 = camera[i, 0], t1 = camera[i + 1, 0];
        float u = (float)((t - t0) / (t1 - t0));
        int r0 = Math.Max(i - 1, 0), r3 = Math.Min(i + 2, n - 1);
        foreach (var (col, isEye) in new[] { (1, true), (4, false) })
            for (int c = 0; c < 3; c++)
            {
                float p0 = camera[r0, col + c], p1 = camera[i, col + c], p2 = camera[i + 1, col + c], p3 = camera[r3, col + c];
                float a = 2 * p1 + (-p0 + p2) * u;
                float b = (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u;
                float d = (-p0 + 3 * p1 - 3 * p2 + p3) * u * u * u;
                float v = 0.5f * (a + b + d);
                if (isEye) Eye[c] = v; else Target[c] = v;
            }
        int k = 0;
        while (k < n - 1 && camera[k + 1, 0] <= t + 1e-9) k++;
        int j = Math.Min(k + 1, n - 1);
        float sp = camera[j, 0] - camera[k, 0];
        float span = sp > 0 ? sp : 1;
        FovDeg = camera[k, 7] + (camera[j, 7] - camera[k, 7]) * (float)((t - camera[k, 0]) / span);
    }

    private void AnimateGroups(double t)
    {
        float tf = (float)t;
        for (int g = 0; g < Groups.Length; g++) Groups[g] = 1;
        // groups 1-8 ("faulty tubes" in the scene file) stay lit: blinking signs read as a rendering glitch
        for (int g = 13; g <= 16; g++) Groups[g] = 0.62f + 0.38f * MathF.Sin(tf * (0.9f + 0.35f * (g - 13)) + g);
        for (int sigGroup = 0; sigGroup <= 1; sigGroup++)
        {
            int lit = SignalState(sigGroup, t) switch { 0 => 2, 1 => 1, _ => 0 };
            for (int lamp = 0; lamp <= 2; lamp++) Groups[24 + lamp + 3 * sigGroup] = lamp == lit ? 1 : 0.02f;
        }
        Groups[30] = KMod(t / 1.6, 1) < 0.14 ? 1 : 0.04f;                         // aviation beacons
        double phase = KMod(t * 2.2, 1);
        Groups[31] = KMod(t * 11, 1) < 0.5 && phase < 0.6 ? 1 : 0;               // police strobe
    }

    private static void WriteRows(ActorBatch b, float x, float y, float z, float yaw, float e0 = 0, float e1 = 0, float e2 = 0, float e3 = 0, float f0 = 0, float f1 = 0)
    {
        int o = b.Count * InstanceFloats;
        float c = MathF.Cos(yaw), s = MathF.Sin(yaw);
        var d = b.Data;
        d[o] = c; d[o + 1] = -s; d[o + 2] = 0; d[o + 3] = x;
        d[o + 4] = s; d[o + 5] = c; d[o + 6] = 0; d[o + 7] = y;
        d[o + 8] = 0; d[o + 9] = 0; d[o + 10] = 1; d[o + 11] = z;
        d[o + 12] = e0; d[o + 13] = e1; d[o + 14] = e2; d[o + 15] = e3;
        d[o + 16] = f0; d[o + 17] = f1; d[o + 18] = 0; d[o + 19] = 0;
        b.Count++;
    }

    /// <summary>`alpha` 0…1: how far `t` lies between the previous traffic step and the current one.</summary>
    private void PoseCars(double alpha)
    {
        foreach (var b in CarBatches) b.Count = 0;
        for (int i = 0; i < cars.Rows; i++)
        {
            int li = (int)cars[i, 0];
            float dx = lanes[li, 2], dy = lanes[li, 3];
            double length = lanes[li, 4];
            float s = (float)KMod(prevS[i] + KMod(carS[i] - prevS[i], length) * alpha, length);   // cars only move forward
            float x = lanes[li, 0] + dx * s;
            float y = lanes[li, 1] + dy * s;
            int kind = (int)cars[i, 2];
            float brake = carA[i] < -0.6 || carV[i] < 0.3 ? 1 : 0;
            WriteRows(CarBatches[kind], x, y, 0, MathF.Atan2(-dx, dy), cars[i, 3], cars[i, 4], cars[i, 5], brake);
            int o = i * 7;
            CarLamps[o] = x; CarLamps[o + 1] = y; CarLamps[o + 2] = 0;
            CarLamps[o + 3] = dx; CarLamps[o + 4] = dy;
            CarLamps[o + 5] = (float)CarLength[kind]; CarLamps[o + 6] = brake;
        }
    }

    private void PosePeople(double t)
    {
        foreach (var b in PedBatches) b.Count = 0;
        for (int i = 0; i < peds.Rows; i++)
        {
            float dir = peds[i, 2];
            double seg = peds[i, 3], speed = peds[i, 4];
            double walked = peds[i, 5] + dir * speed * t;
            float y = peds[i, 1] + (float)KMod(walked, seg);
            float cycle = (float)(speed * t / Stride * 2 * Math.PI + i * 1.7);
            WriteRows(PedBatches[(int)peds[i, 6]], peds[i, 0], y, 0.15f, dir > 0 ? 0 : MathF.PI,
                peds[i, 7], peds[i, 8], peds[i, 9], peds[i, 10], cycle, speed > 0 ? 0.42f : 0);
        }
    }

    private void PoseTrains(double t)
    {
        TrainHeads.Count = 0; TrainCarsBatch.Count = 0; TrainLampCount = 0;
        for (int r = 0; r < trains.Rows; r++)
        {
            float ty = trains[r, 0], d = trains[r, 1], tCross = trains[r, 2], speed = trains[r, 3];
            float yaw = d > 0 ? -MathF.PI / 2 : MathF.PI / 2;
            for (int ci = 0; ci < (int)trains[r, 4]; ci++)
            {
                float off = -(ci * (TrainCarL + TrainGap) + TrainCarL / 2);
                float x = d * (speed * ((float)t - tCross) + off);
                WriteRows(ci == 0 ? TrainHeads : TrainCarsBatch, x, ty, RailZ, yaw);
                if (MathF.Abs(x) < 520)
                {
                    int o = TrainLampCount * 3;
                    TrainLampCount++;
                    TrainLamps[o] = x; TrainLamps[o + 1] = ty; TrainLamps[o + 2] = RailZ + 2.6f;
                }
            }
        }
    }

    private void PoseDrones(double t)
    {
        DroneBatch.Count = 0; PoliceBatch.Count = 0;
        float tf = (float)t;
        for (int r = 0; r < drones.Rows; r++)
        {
            if (drones[r, 6] > 0.5)
            {
                // police drone: holds station ahead of the camera, sweeping its searchlight over the street
                float x = 5 * MathF.Sin(tf * 0.13f);
                float y = Eye[1] + 48 + 8 * MathF.Sin(tf * 0.21f);
                float z = 24 + 1.5f * MathF.Sin(tf * 0.5f);
                WriteRows(PoliceBatch, x, y, z, 0.25f * MathF.Sin(tf * 0.3f));
                SearchPos = [x, y + 0.3f, z - 0.6f];
                SearchAim = [7 * MathF.Sin(tf * 0.7f), y - 14 + 9 * MathF.Sin(tf * 0.33f), 0];
            }
            else
            {
                float a = drones[r, 4] * tf + drones[r, 5];
                float rad = drones[r, 3];
                float x = drones[r, 0] + rad * MathF.Cos(a);
                float y = drones[r, 1] + rad * MathF.Sin(a);
                float z = drones[r, 2] + 0.6f * MathF.Sin(tf * 1.3f + drones[r, 5]);
                float sign = drones[r, 4] >= 0 ? 1 : -1;
                WriteRows(DroneBatch, x, y, z, MathF.Atan2(MathF.Sin(a) * sign, MathF.Cos(a) * sign));
            }
        }
    }
}
