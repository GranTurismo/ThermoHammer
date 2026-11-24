package com.example.thermohammer.engine

import java.io.File
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// CPU TOPOLOGY — which cores are the fast ones, from what the kernel says of them.
//
//   Phones mix core designs: a Dimensity 9500 has 1 C1-Ultra (4.21 GHz), 3 C1-Premium (3.5) and 4 C1-Pro (2.7); a
//   Snapdragon 8 Elite has 2 prime (4.32) and 6 performance (3.53) cores and no efficiency cluster at all. A test that
//   splits work evenly over its threads waits for the slowest, so on the first phone a thread on a C1-Pro holds the
//   other seven back; on the second every core is a big one and nothing should be left out.
//
//   The kernel's cpufreq files give each core's top frequency (cpuinfo_max_freq) and some give cpu_capacity (its speed
//   scaled to the fastest = 1024). Cores are ranked by frequency and grouped into clusters of the same frequency; the
//   AI CPU test uses the fastest clusters and leaves a cluster out only when it is clearly slower than the
//   top one ([EXCLUDE_BELOW]). Where the files can't be read (some OEM builds) the topology is unknown and nothing is
//   pinned. Whether pinning to these cores actually helps is never assumed: the tests measure it.
//
//   Plain Kotlin over file contents handed in as text, so the rules run on the JVM with any phone's layout.
// ─────────────────────────────────────────────────────────────────────────────

/** What the ranking is based on. */
enum class TopologyBasis(val label: String) {
    FREQUENCY("cpuinfo_max_freq"), CAPACITY("cpu_capacity"), UNKNOWN("unknown")
}

/** Cores of about the same speed: `value` is the top one's kHz (or capacity), `cores` ascending by number. */
data class CpuCluster(val value: Long, val cores: List<Int>)

class CpuTopology(
    val basis: TopologyBasis,
    /** Every ranked core's cluster, fastest first; empty when the topology is unknown. */
    val clusters: List<CpuCluster>,
    /** How many of the fastest clusters are kept. */
    val kept: Int,
    /** Why the topology is unknown (nothing readable, only part of it, one core). */
    val note: String? = null
) {
    val known: Boolean get() = basis != TopologyBasis.UNKNOWN && clusters.isNotEmpty()

    /** The cores the CPU tests may use, fastest first (cores of a cluster ascending). */
    val big: List<Int> get() = clusters.take(kept).flatMap { it.cores }

    /** The cores left out: the clusters clearly slower than the top. */
    val excluded: List<Int> get() = clusters.drop(kept).flatMap { it.cores }

    /** True when pinning has something to place: a known topology with at least two cores to choose from. */
    val canPin: Boolean get() = known && big.size >= 2

    /** The `n` fastest cores, fastest first (fewer when there aren't n). */
    fun fastest(n: Int): List<Int> = big.take(n)

    /** One report line: the clusters with their speed, which are used, and the pinning order. */
    fun line(): String {
        if (!known) return "CPU topology · unknown${note?.let { " ($it)" } ?: ""}: no thread is pinned"
        val top = clusters.first().value
        val parts = clusters.mapIndexed { i, c ->
            val speed = if (basis == TopologyBasis.FREQUENCY) "%.2f GHz".format(Locale.US, c.value / 1e6) else "capacity ${c.value}"
            "$speed: ${ranges(c.cores)}" + if (i >= kept) " (left out: %.0f%% of the fastest)".format(Locale.US, 100.0 * c.value / top) else ""
        }
        return "CPU topology · by ${basis.label} · ${parts.joinToString(" · ")} · pinning order ${big.joinToString(",")}"
    }

    companion object {
        val UNKNOWN = CpuTopology(TopologyBasis.UNKNOWN, emptyList(), 0)

        /** "cpu0-3 cpu7": consecutive core numbers as a range. */
        fun ranges(cores: List<Int>): String {
            val sorted = cores.sorted()
            val out = ArrayList<String>()
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
                out += if (j - i >= 2) "cpu${sorted[i]}-${sorted[j]}" else (i..j).joinToString(" ") { "cpu${sorted[it]}" }
                i = j + 1
            }
            return out.joinToString(" ")
        }
    }
}

object CpuTopologyParser {
    /** Cores whose speed is within this fraction of a cluster's fastest belong to that cluster (bins of one chip differ by a percent or two). */
    const val MERGE = 0.97
    /** A cluster slower than this fraction of the fastest is left out: C1-Pro at 2.7 GHz of 4.21 is 0.64, the 8 Elite's second cluster 0.82. */
    const val EXCLUDE_BELOW = 0.72
    /** A phone is never left with fewer than this many cores by the rule above (the next clusters stay in). */
    const val MIN_KEPT = 4

    /** "0-3,6,7" → [0, 1, 2, 3, 6, 7]; anything unreadable gives an empty list. */
    fun parseList(text: String?): List<Int> {
        if (text == null) return emptyList()
        val out = sortedSetOf<Int>()
        for (part in text.trim().split(',')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val ends = p.split('-')
            val a = ends[0].trim().toIntOrNull() ?: return emptyList()
            val b = if (ends.size == 2) ends[1].trim().toIntOrNull() ?: return emptyList() else if (ends.size == 1) a else return emptyList()
            if (a < 0 || b < a || b - a > 4096) return emptyList()
            for (c in a..b) out += c
        }
        return out.toList()
    }

    /** The "Cpus_allowed_list:" line of /proc/self/status, parsed. */
    fun allowedFromStatus(status: String?): String? =
        status?.lineSequence()?.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim()

    /**
     * `online` is the kernel's list of online cores ("0-7"; null: unreadable), `allowed` the cores this process may
     * run on (cpuset, Cpus_allowed_list; null: not restricted); `maxKhz(i)` and `capacity(i)` are the contents of
     * cpu<i>/cpufreq/cpuinfo_max_freq and cpu<i>/cpu_capacity (null: not readable). Cores are ranked by frequency, or
     * by capacity when some core has no frequency; when neither covers every usable core the topology is unknown.
     */
    fun parse(online: String?, allowed: String?, maxKhz: (Int) -> String?, capacity: (Int) -> String?): CpuTopology {
        var cores = parseList(online)
        if (cores.isEmpty()) return CpuTopology(TopologyBasis.UNKNOWN, emptyList(), 0, "the list of online cores isn't readable")
        val limit = parseList(allowed)
        if (limit.isNotEmpty()) cores = cores.filter { it in limit }
        if (cores.size < 2) return CpuTopology(TopologyBasis.UNKNOWN, emptyList(), 0, "only ${cores.size} core to choose from")

        fun number(text: String?): Long? = text?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        val freq = cores.associateWith { number(maxKhz(it)) }
        val cap = cores.associateWith { number(capacity(it)) }
        val (basis, values) = when {
            freq.values.all { it != null } -> TopologyBasis.FREQUENCY to freq.mapValues { it.value!! }
            cap.values.all { it != null } -> TopologyBasis.CAPACITY to cap.mapValues { it.value!! }
            else -> return CpuTopology(TopologyBasis.UNKNOWN, emptyList(), 0,
                if (freq.values.all { it == null } && cap.values.all { it == null }) "cpufreq and cpu_capacity aren't readable"
                else "only some cores report a frequency or capacity")
        }

        val ranked = cores.sortedWith(compareByDescending<Int> { values.getValue(it) }.thenBy { it })
        val clusters = ArrayList<CpuCluster>()
        for (c in ranked) {
            val v = values.getValue(c)
            val last = clusters.lastOrNull()
            if (last != null && v >= last.value * MERGE) clusters[clusters.size - 1] = last.copy(cores = last.cores + c)
            else clusters += CpuCluster(v, listOf(c))
        }
        val top = clusters.first().value
        var kept = clusters.indexOfFirst { it.value < top * EXCLUDE_BELOW }.let { if (it < 0) clusters.size else it }
        // never fewer than MIN_KEPT cores when there are more: the next clusters stay in
        while (kept < clusters.size && clusters.take(kept).sumOf { it.cores.size } < MIN_KEPT) kept++
        return CpuTopology(basis, clusters, kept)
    }
}

/** Reads this phone's topology from sysfs and /proc; a file that can't be read is just missing (never an exception). */
object CpuTopologyReader {
    private const val ROOT = "/sys/devices/system/cpu"

    private fun text(path: String): String? = try { File(path).readText() } catch (_: Exception) { null }

    fun read(file: (String) -> String? = ::text): CpuTopology = try {
        CpuTopologyParser.parse(
            online = file("$ROOT/online") ?: file("$ROOT/present"),
            allowed = CpuTopologyParser.allowedFromStatus(file("/proc/self/status")),
            maxKhz = { file("$ROOT/cpu$it/cpufreq/cpuinfo_max_freq") },
            capacity = { file("$ROOT/cpu$it/cpu_capacity") })
    } catch (e: Exception) {
        CpuTopology(TopologyBasis.UNKNOWN, emptyList(), 0, e.message ?: e.javaClass.simpleName)
    }
}
