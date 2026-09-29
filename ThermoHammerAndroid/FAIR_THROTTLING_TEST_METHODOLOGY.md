# Fair, Accurate & Reasonable CPU Throttling Testing — Methodology Specification

> Companion document to `ANDROID_THROTTLING_TESTS_DEEP_DIVE.md`.
> The current implementation measures *"throughput of the app's own threads
> relative to the best second of the same run."* This document specifies what
> the multicore test **should** measure instead, how to attribute slowdowns to
> real thermal causes, how to score it, and what environmental gates make runs
> comparable across devices — i.e. *fair, accurate, and reasonable*.

---

## Table of Contents

1. [Design Goals — What "Fair, Accurate, Reasonable" Means](#1-design-goals)
2. [Signals to Measure — The Complete Instrument Set](#2-signals-to-measure--the-complete-instrument-set)
3. [Measurement Architecture — Clock, Windows, Coherence](#3-measurement-architecture)
4. [Workload Design — What the Workers Should Do](#4-workload-design)
5. [Baseline Establishment — Replacing the Ratchet-Max](#5-baseline-establishment)
6. [Throttling Attribution — Why Did Throughput Drop?](#6-throttling-attribution)
7. [Scoring Formulas — A Rebuilt Scorecard](#7-scoring-formulas)
8. [Environmental Gating — Blocking Checks, Not Advice](#8-environmental-gating)
9. [Result Data Model v2](#9-result-data-model-v2)
10. [Leaderboard Fairness Rules](#10-leaderboard-fairness-rules)
11. [Validity & Confidence Scoring](#11-validity--confidence-scoring)
12. [Reference Implementation — Engine Pseudocode](#12-reference-implementation-pseudocode)
13. [Mapping — Current Code → Proposed Change](#13-mapping--current-code--proposed-change)
14. [Residual Limitations — What Still Can't Be Fixed](#14-residual-limitations)

---

## 1. Design Goals

A throttling test that deserves the name must satisfy five criteria:

| Criterion | Meaning | Current app |
|---|---|---|
| **Causality** | A "throttle" reading must be traceable to reduced CPU capacity (DVFS cap, core offline, cluster migration) — not to the app being starved by other processes or its own JIT | ✗ conflates all slowdowns |
| **Absolute reference** | The 100% baseline must be measured under known conditions (cool, calibrated window) — not "best second of this same run" | ✗ in-run ratchet max |
| **Evidence** | Every report must persist *why*: frequencies, temps, scheduled-time, per-sample — not just a scalar score + 4-bucket thermal flag | ✗ stamps carry no temp/freq |
| **Reproducibility** | Same device, same conditions → same score ± noise; runs with contaminated conditions are flagged, not silently scored | ✗ no validity gating |
| **Comparability** | Leaderboard comparisons must be same-workload, same-conditions, same-class — with interference transparently reported | ✗ raw % ranking only |

"Reasonable" additionally means: no root required, no kernel hacks, no dangerous
permissions, works within what Android legitimately exposes to a foreground app —
and honest about the remaining uncertainty.

---

## 2. Signals to Measure — The Complete Instrument Set

The engine already touches some of these; a fair test needs **all of them,
every sample, persisted into the result**.

### 2.1 Work output (primary metric)

| Signal | Source | Rate |
|---|---|---|
| Per-worker completed iterations | `AtomicLong` per worker | continuously updated by workers |
| Per-sample wall time | `System.nanoTime()` (monotonic, immune to clock changes) | per sample |

**Fix batch quantization:** today a worker publishes only at the end of each
50,000-iteration batch — a batch straddling the sample boundary lands entirely in
the next window, creating fake ±% oscillation. Publish progress every **~4,096
iterations** instead (volatile/atomic store once per 4 k iters ≈ zero cost), and
compute score as `Δiterations / Δnanos` → a true *iterations-per-second* rather
than "iterations per nominal second of coroutine drift."

### 2.2 CPU frequency — observed *and imposed* (the core throttle evidence)

Per core `n`, read every sample from `/sys/devices/system/cpu/cpu<n>/cpufreq/`:

| File | Meaning | Why it matters |
|---|---|---|
| `scaling_cur_freq` | current clock (kHz) | actual delivered frequency |
| `scaling_max_freq` | **user/thermal-imposed ceiling** | **the single best throttle signal** — vendor thermal daemons (thermal-engine, thermald) implement CPU mitigation primarily by *lowering this value*. When `scaling_max < cpuinfo_max`, the kernel is explicitly capping the core |
| `cpuinfo_max_freq` | hardware max | denominator for both |
| `related_cpus` | cores sharing a clock domain | gives true **cluster topology** (e.g. `0-3`, `4-6`, `7`) without parsing device trees |
| `scaling_governor` | `schedutil`/`performance`/etc | context for interpreting freq behavior; some OEMs switch governor when hot |
| `online` (in `cpu<n>/`, not cpufreq) | 0/1 | hotplug detection — a core taken offline under heat is the most severe throttle state |

Two derived ratios per core per sample:

```
freqRatio_n   = scaling_cur_freq_n / cpuinfo_max_freq_n     (delivered)
capRatio_n    = scaling_max_freq_n / cpuinfo_max_freq_n     (permitted)
```

`capRatio < 1.0` is **imposed throttling** — it can even be observed while the
app is idle, proving the cap is system-imposed, not workload-dependent.
`freqRatio < capRatio` under full load additionally suggests residency limits,
migration, or governor ramp behavior.

### 2.3 Scheduled time — separating "slower" from "starved"

Per worker thread, read `/proc/self/task/<tid>/stat` (fields: `utime` (14),
`stime` (15), `processor` (39)):

```
scheduledFraction_w = (Δutime_w + Δstime_w) / (Δwall_jiffies × clockTicks)
```

* ≈ 1.0 → the worker got a core whenever it wanted one.
* < 1.0 → the worker was descheduled (external load, N+1 oversubscription,
  cpuset restriction) — throughput dips are then **contention, not throttle**.

Field 39 (`processor` = last CPU run on) sampled per worker yields a **residency
histogram**: which physical cores/clusters each worker lived on. A residency
shift from a big cluster to a little cluster *is* a throttle mechanism on
heterogeneous SoCs (the commonest one, in fact) — and the current app is blind
to it because worker index ≠ physical core.

(Use `CLK_TCK = 100` jiffies/sec; process-level cross-check via `/proc/self/stat`
and system-wide load via `/proc/stat` totals.)

### 2.4 Thermal signals

| Signal | Source | Purpose |
|---|---|---|
| Per-zone temperature | `/sys/class/thermal/thermal_zone*/{type,temp}` | cluster/skin/battery temps — feed the warmest *CPU-adjacent* zones into the sample |
| `PowerManager.getThermalHeadroom()` (API 29+) | continuous 0.0–1.0+ predicted headroom | **far better than the 6-level `currentThermalStatus`** — a graded early-warning signal that starts dropping before hard throttle |
| `OnThermalStatusChangedListener` | callback, not polling | replace the 5 s polling loop — transitions timestamped at occurrence, not ±5 s late |
| `scaling_max_freq` (again) | kernel | where no zone is readable, the *imposed cap itself* is the thermal telemetry |

### 2.5 Power — required for "efficiency"

| Signal | Source |
|---|---|
| `BATTERY_PROPERTY_CURRENT_NOW` | instantaneous µA on most devices (sign convention varies — calibrate sign at idle vs load) |
| `BATTERY_PROPERTY_ENERGY_COUNTER` | nWh cumulative where supported — integrates power directly |
| `BATTERY_PROPERTY_CHARGE_COUNTER` + `CAPACITY` | µAh + % cross-check |
| `ACTION_BATTERY_CHANGED` | voltage (mV) to convert current → mW |

Efficiency metric becomes real: `perfPerWatt = iterationsPerSec / mW`, and
**thermal efficiency index** = `sustainedRatio / ΔT` (see §7).

### 2.6 Environment flags (recorded per run AND per sample where they can change)

Screen brightness (`Settings.System.SCREEN_BRIGHTNESS`), charging state (polled
each sample — plugging in mid-test currently goes undetected), battery level,
battery saver, airplane/radio state, foreground status, device idle-load
pre-check result.

---

## 3. Measurement Architecture

### 3.1 Timing — real clocks, not synthetic counts

* Sampler records `nanoTime()` at the **start and end** of each sampling window;
  `elapsedNs` is the *measured* window, never assumed 1000 ms.
* Every stamp carries `timestampNs` (monotonic, from run start) — so coroutine
  drift under load is captured in the data instead of silently compressing the
  time axis. Two samples drifting 5% apart show as `t=1000ms, 2051ms, ...`
  with correct per-sample rates, not as fake 1-second buckets.
* One coroutine does **sample collection**; the UI/chart reads from state. The
  current two independent 1 Hz coroutines (sampler + timer with diverging
  clocks) collapse into a single clock source.

### 3.2 Sampling rates

| Stream | Rate | Note |
|---|---|---|
| Counters + freqs + scheduled fraction + temps + current | **2–4 Hz internal** | cheap sysfs/proc reads; finer resolution catches throttle *onset* precisely |
| Stamp emission (persisted + uploaded) | **1 Hz aggregated** | mean/min of the 2–4 sub-samples — payload size unchanged (~1800 stamps @ 30 min) |
| Thermal headroom/status | event-driven + 5 s floor | listener + periodic backstop |
| Battery level/charging | per stamp | coarse anyway |

### 3.3 Snapshot coherence

Read order inside each window: `nanoTime` → all counters → all freqs → all
task stats → temps → battery → `nanoTime`. The whole snapshot is a few
hundred KB of file reads / ms — coherent within ~2–5 ms, plenty for 1 Hz data.

---

## 4. Workload Design

The existing inner loop is *fine* as a heat source (dense integer + FP + L1).
What must change is the **thread topology** and **phases**:

### 4.1 Thread count: exactly N workers on N cores — no +1

* MULTI = `clusterCoreCount` workers where the count comes from **`related_cpus`
  topology** (or `availableProcessors()` capped by cpuset), and **no separate
  GPU thread**. The pseudo-GPU worker creates N+1-on-N oversubscription: ~12%
  permanent capacity loss on 8 cores plus a randomly rotating "starved" worker
  that masquerades as per-core throttling on the impact meters.
* If GPU-simulated heat is desired, it's a *separate test type* (a third
  threading mode, e.g. `HYBRID`), honestly labeled — not silently added to a
  "CPU" test. And the "1 Thread" test must be **literally one thread** —
  today it silently runs two.
* Workers get `Process.setThreadPriority(THREAD_PRIORITY_DEFAULT)`; pinning
  needs JNI `sched_setaffinity` (worth adding via a tiny native lib — but if
  not, residency histograms from field 39 reconstruct placement anyway).

### 4.2 Run phases — the key structural change

```
PRE-FLIGHT → WARM-UP → CALIBRATION → MEASURED RUN → REPORT
   (gates)    (not      (boost window,   (recorded      (scores +
              recorded)   basis of 100%)   stamps)        validity flags)
```

* **PRE-FLIGHT** (§8): hard gates; test cannot start while conditions invalid.
* **WARM-UP** (~10–15 s, not recorded): runs the real workers — settles ART JIT
  (OSR compilation), charges branch predictors/caches, lets the governor ramp.
  Eliminates today's guaranteed fake "dip" at t=1–3 s that `minStability`
  reports as throttling.
* **CALIBRATION** (~15–30 s, recorded but flagged): captures the boost window.
  Baseline (§5) is derived from this window only.
* **MEASURED RUN**: the timed test proper (5/15/30 min of *measured* time on
  top of warm-up+calibration). Total session length = duration + ~45 s.
* **REPORT**: compute scores, attach validity flags.

---

## 5. Baseline Establishment

Replace `overallBaseline = max(totalSpeed ever seen)` with:

```
baseline = p95 of per-sample totalIPS over the CALIBRATION window
```

Rationale:

* **Percentile, not max** — one freak 1 s boost spike (or a lucky scheduler
  moment) no longer permanently distorts every subsequent reading. p95 of a
  15–30 s window is a "representative boost," robust to ±1-sample outliers.
* **Fixed once, never re-raised mid-run.** If the device later exceeds the
  calibration baseline (e.g. warming into a better bin), instantaneous values
  can legitimately read 102% — that's *true information*, clamp only for
  display, keep raw in the stamp.
* **Per-cluster baselines too**: `baselineCluster_k = p95 of
  Σ(IPS of workers resident on cluster k)` — needed for heterogeneous
  attribution (a big core's IPS and a little core's IPS are different units).
* Persist `baseline`, the calibration-window raw samples, ambient/skin temp at
  calibration, and governor into the result — the baseline is auditable, not
  a hidden internal variable.

**Optional absolute anchor:** additionally record the calibration-window
`avg freqRatio` (e.g. "peak held 98% of max clock") so a device that was
*already capped during calibration* is detectable — if `capRatio` < 1 during
the boost window, flag the run **warm-started** (baseline measured on a
throttled machine → scores inflated).

---

## 6. Throttling Attribution

Each sample classifies slowdowns instead of declaring "throttled":

| Observed combination | Diagnosis |
|---|---|
| `capRatio` < 1 **and** freqRatio tracks it, scheduled ≈ 1 | **Imposed DVFS throttle** (thermal daemon ceiling) — true thermal throttle |
| `capRatio` = 1, `freqRatio` < ~0.9, scheduled ≈ 1 | **Governor/EAS down-clock** — usually thermal-adjacent; confirm against temp/headroom trend |
| Throughput ↓, freqs flat, scheduled fraction < 1 | **Contention** — external CPU load or oversubscription. **Not throttle** → mark interval invalid, not "low stability" |
| Residency shift big→little + throughput ↓ | **Cluster migration throttle** — thermal mitigation on heterogeneous SoC (the dominant real-world mechanism the current app can't see) |
| `online` = 0 for a core | **Hotplug** — severest throttle; count as core contributed 0 capacity |
| Nothing dropped, throughput noise < ~3% | normal variance |

Rules encoded per sample produce an `attribution` enum on each stamp:
`NONE / DVFS_CAP / GOVERNOR / MIGRATION / HOTPLUG / CONTENTION / UNKNOWN`.
The chart colors dips by *cause*; "MIN STABILITY" only counts samples with a
real thermal attribution. **This is the single biggest correctness fix.**

Contention detection also guards integrity: if >10% of measured samples are
`CONTENTION`, the run is flagged `interference` and excluded from verified
leaderboards.

---

## 7. Scoring Formulas

One number was always going to be a lie — produce a small scorecard instead:

| Metric | Formula | Meaning |
|---|---|---|
| `peakPerf` | baseline = p95(calibration IPS) + avg calibration freqRatio | what the silicon can do cold |
| `sustainedRatio` | **median** (not mean — robust to single dips) of IPS in last 25% of run ÷ baseline | the headline "% sustained" |
| `deliveredCapacity` | Σ(IPS·dt) over run ÷ (baseline × duration) | *area under curve* — "how much of the cold-machine's work did you actually get" — a fairer single number than final-stability; a device throttling to 70% for half the run scores 85%, not 70% |
| `throttleOnset_s` | first time `ipsRatio < 0.9` **sustained ≥3 s** (debounced), else null | today: any single <90% sample triggers — noise-prone |
| `timeInThrottle` | % of measured seconds attributed DVFS_CAP/MIGRATION/HOTPLUG | severity separate from depth |
| `minSustained` | min over a **5 s sliding mean**, thermal-attributed samples only | replaces the JIT-artifact `minStability` |
| `recoveryIndex` | max IPS ratio in the 60 s *after* workers stop (optional tail phase) | cooling/restore behavior |
| `perfPerWatt` | mean IPS ÷ mean mW during run (from `CURRENT_NOW × voltage`) | real efficiency |
| `thermalEfficiency` | `deliveredCapacity / max(1°C, ΔTemp_max)` where ΔT = hottest CPU-adjacent zone rise | "how much sustained work per degree of heating" — the honest answer to 'rate its thermal efficiency' |
| `validity` | bitfield: warmStarted, interference, pluggedIn, freqDataMissing, shortRun | every leaderboard number carries it |

Final "stability" for display continuity = `deliveredCapacity` (rename honestly
in UI: "SUSTAINED CAPACITY"), with `sustainedRatio` shown beside it.

---

## 8. Environmental Gating

Convert advisory checklist items into **blocking pre-flight gates** and
**in-run monitors**:

### Hard gates (test refuses to start)

| Gate | Threshold | Why |
|---|---|---|
| Starting thermal state | warmest CPU-adjacent zone ≤ ~40 °C AND `getThermalHeadroom()` ≥ ~0.5 | kills warm-start inflation — the #1 fairness bug today |
| Charging | must be unplugged (hard) | charger heat invalidates everything |
| Battery level | ≥ 15–20% | many devices impose power caps at low battery |
| Background load pre-check | sample `/proc/stat` for 3–5 s; system non-idle CPU ≤ ~15% | detects updates/backups that would contaminate the run |
| Frequency data readable | `scaling_cur_freq` readable on ≥1 core/cluster | without it the run can't be *verified* → degrade to "unverified" mode or refuse |

### Soft advisories (may proceed, flagged in result)

Case removal suggestion, airplane-mode recommendation (cellular **state**
recorded in metadata either way), battery saver (if on → flag + auto-flag
score `envCapped`), screen brightness (record level; optionally lock to fixed
% during run for repeatability).

### In-run monitors (each sample)

Charging plugged mid-run → flag `powerEvent` on subsequent stamps (or offer
abort-and-keep-partial); brightness change; app-backgrounding (already aborts
— keep); `getThermalHeadroom()` hitting 0.

---

## 9. Result Data Model v2

Stamp v2 (1 Hz, replaces `DeviceHammerStamp`):

```kotlin
data class StampV2(
    val tNs: Long,                  // monotonic ns since measured-run start
    val ipsTotal: Long,             // Δiters/Δns → true rate
    val ipsPerCluster: List<Long>,  // attributed by residency
    val capRatioAvg: Float,         // mean over cores of scaling_max/cpuinfo_max
    val freqRatioAvg: Float,        // mean of scaling_cur/cpuinfo_max
    val freqRatioPerCore: List<Int>,  // % of max per core (0 = offline)
    val schedFracAvg: Float,        // mean worker scheduled fraction
    val cpuTempMilliC: Int,         // warmest CPU-adjacent zone
    val skinTempMilliC: Int?,       // if identifiable
    val battTempMilliC: Int,
    val battCurrentUa: Int?,        // for perf/W
    val thermalHeadroom: Float?,    // getThermalHeadroom()
    val thermalStatus: Int,         // 0–3 (keep for continuity)
    val attribution: Int,           // enum ordinal from §6
    val envFlags: Int               // bitfield: charging, saver, brightnessChanged...
)
```

Run metadata v2: device model/manufacturer, SoC (`Build.SOC_MODEL`, API 31+),
cluster topology (`related_cpus` map), governor, OS build fingerprint, app
version, test type, threading mode, **calibration stats** (baseline, window
temps), env gates result, battery/energy endpoints, validity bitfield,
schema version. Hash covers the *whole* payload — today only stamps are
hashed, so metadata is tamperable.

---

## 10. Leaderboard Fairness Rules

1. **Only `verified` runs rank.** Verified = no `warmStarted`, no
   `interference`, no `powerEvent`, freq data present, ran ≥90% of target
   duration. Unverified runs show on the device owner's history with a ⚠ badge,
   never on global boards.
2. **Rank by `deliveredCapacity`, with `sustainedRatio` and `perfPerWatt` as
   secondary axes.** Optionally split boards by duration AND threading (already
   tagged) *and* by condition class (`envCapped` runs separated).
3. **Comparisons require matching threading mode AND schema version** —
   extend today's threading check to schema.
4. **Show the evidence**: detail view renders the dual panel — IPS curve +
   freq/cap curve + temp curve on one time axis. A user should *see* the cap
   drop, not just the score line.
5. **Anti-cheat honesty**: hash the whole payload v2; OEMs that whitelist
   benchmark apps can't be fully defeated — mitigate by detecting the classic
   signature (calibration IPS ≫ any steady-state IPS with capRatio == 1 while
   headroom claims 0) and flagging `suspiciousBoost`.

---

## 11. Validity & Confidence Scoring

Every run computes a `confidence` 0–100 shown on the summary:

```
confidence = 100
  − 30 if freq sysfs unreadable (attribution degraded → UNKNOWN)
  − 25 if >10% contention samples
  − 25 if warmStarted (capRatio<1 during calibration)
  − 15 if powerEvent / brightnessChange mid-run
  − 10 if headroom API unavailable (API <29 path)
  − 10 if thermal zones unreadable
  −  5 if battery current unavailable (no perf/W)
```

The summary overlay shows the score **with** its confidence — "82% sustained,
confidence 95" is honest; "82% sustained" alone on a meter that can't prove
causality is not.

---

## 12. Reference Implementation — Engine Pseudocode

```kotlin
// PRE-FLIGHT (blocking)
val gates = preflight()          // temp, charging, battery%, bg load, freq-readable
if (!gates.ok) showGateFailures(gates); return

// WARM-UP (not recorded)
spawnWorkers(nWorkers = topology.totalCores)   // no gpu thread
delay(12_000)

// CALIBRATION (~20 s, recorded with phase=CAL)
resetCounters(); var calSamples = mutableListOf<Sample>()
repeat(20) { calSamples += sampleOnce(); delay(1000) }
baseline          = p95(calSamples.map { it.ipsTotal })
baselinePerCluster = p95 per cluster
val warmStarted   = calSamples.count { it.capRatioAvg < 0.99f } > calSamples.size / 2

// MEASURED RUN
var tPrev = nanoTime(); var prevCounters = snapshot()
while (elapsed < durationNs && isActive) {
    delay(250)                                   // 4 Hz internal
    val now = nanoTime(); val cur = snapshot()   // counters, freqs, taskstat, temps, battery
    val win = Window(cur - prev, now - tPrev)    // TRUE window size
    val attr = classify(win)                     // §6 decision table
    emit(StampV2(win, attr)); prev = cur; tPrev = now
}

// REPORT
scores = scorecard(stamps, baseline, meta)       // §7
validity = validityFlags(stamps, meta)           // §8/§11
saveAndMaybeSubmit(StampV2 list, meta, hash over full payload)
```

`sampleOnce()`/`snapshot()` read, in order: `nanoTime` → worker counters →
`cpufreq` (cur, max, online, related_cpus once) → `/proc/self/task/*/stat`
(utime+stime+processor) → thermal zones → `getThermalHeadroom()` → battery
intent extras + `CURRENT_NOW`.

---

## 13. Mapping — Current Code → Proposed Change

| Current (`StressEngine.kt` etc.) | Proposed |
|---|---|
| `overallBaseline` ratchet-max, in-run | Fixed p95 baseline from dedicated calibration window; `warmStarted` flag if capped during calibration |
| `counters[i].addAndGet(50_000)` per batch | progress published every ~4 k iters; rate = Δiters/Δns over *measured* window |
| `elapsedMs = sampleCount × 1000` (synthetic) | `tNs` from `nanoTime()` deltas |
| `coreImpacts` = per-worker, labeled "CORE n" | per-cluster impact via `related_cpus` + residency histogram from task stat field 39; labeled truthfully ("WORKER n" if unpinned) |
| `gpuCounter`/`gpuThread` always-on in every mode | removed from CPU tests; optional separate `HYBRID` mode; "1 Thread" becomes actually 1 thread |
| `DeviceHammerStamp(elapsedMs, score, thermalInt)` | `StampV2` with freq/cap/sched/temp/headroom/attribution/env fields |
| `currentThermalStatus` polled every 5 s | `OnThermalStatusChangedListener` + `getThermalHeadroom()` per stamp |
| `minStability = min(chartPoints)` incl. JIT dip | `minSustained` over 5 s sliding mean, thermal-attributed samples only, warmup excluded |
| `finalStability = avg(2nd half)/max` | `deliveredCapacity` (AUC) primary, `sustainedRatio` (median last-quartile/baseline) secondary — both computable from same stamps |
| Pre-test overlay advisory-only | Blocking pre-flight gates + per-sample env monitors + validity bitfield |
| Battery % integer endpoints only | `CURRENT_NOW`/`ENERGY_COUNTER` sampled → real mW → `perfPerWatt` |
| Hash covers stamps only | Hash covers entire payload v2 (incl. conditions + topology) |
| No interference detection | `scheduledFraction` + contention flag + run exclusion |
| `thermalEvents` = every-poll snapshot | True event log on state *transitions* only, timestamped by listener |

---

## 14. Residual Limitations

Even a perfect implementation should publish these caveats:

1. **No root, no guarantees**: `cpufreq` sysfs and thermal zones are
   SELinux-gated per device/build; some devices expose neither → confidence
   degrades to "throughput-only," which must be *displayed*, not hidden.
2. **`scaling_cur_freq` is itself approximate** (some platforms report policy
   values rather than measured clocks) — cross-check with IPS trends.
3. **OEM benchmark whitelisting** is undetectable by definition; the
   `suspiciousBoost` heuristic only catches the blatant pattern.
4. **Ambient temperature** can't be measured by the phone — fairness across a
   20 °C vs 35 °C room is imperfect; skin-temp-at-start gating is the
   available proxy.
5. **Screen is a required heat source** (foreground-only design) — its power
   contribution varies by panel; locking brightness narrows but doesn't
   eliminate it.
6. **IPS is workload-relative** — a "iteration" is this loop's work, not a
   standardized unit; cross-device absolute comparisons remain approximate.
   Stability ratios are meaningful; absolute IPS ranking is not.
7. **Battery current sensors vary** in sign convention, resolution, and update
   rate — `perfPerWatt` is a proxy, marked as such in results.

---

*This spec keeps the app's architecture (ViewModel + coroutines + Compose +
Retrofit) unchanged — every fix lands inside `StressEngine` sampling logic,
the stamp/data models, pre-flight flow, and scoring math. No new permissions,
no root, no NDK requirement (affinity pinning optional via JNI).*
