package com.example.thermohammer.engine

/**
 * Crash records for the optional ways of running the AI and LLM tests (an AI setting, the i8mm CPU kernels): native
 * code can't be caught, so a marker "install:WHAT:name" is written before such a run and removed after it. A marker
 * still there at the next start means the run took the app down: that one setting joins a crashed set (for this
 * install only — an app update retries it) and is never tried again, while the accelerator or test it belongs to
 * keeps running with its defaults. The tests' own markers (StressEngine's KEY_AI_RUNNING, KEY_LLM_RUNNING) are what
 * skip a whole accelerator; these skip only the optional extras.
 */
object ProbeCrashes {
    /** `crashed` plus the marker `running` (null: none) when it is this install's; one of another shape is ignored. */
    fun after(running: String?, crashed: Set<String>, install: Long): Set<String> {
        val parts = running?.split(':') ?: return crashed
        return if (parts.size == 3 && parts[0] == install.toString()) crashed + running else crashed
    }

    /** "WHAT:name" for every crashed entry of this install. */
    fun keys(crashed: Set<String>, install: Long): Set<String> =
        crashed.mapNotNull { e -> e.split(':').takeIf { it.size == 3 && it[0] == install.toString() }?.let { "${it[1]}:${it[2]}" } }.toSet()
}
