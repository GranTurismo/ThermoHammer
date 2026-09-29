# ThermoHammer (Android) — CPU Throttling Test Suite: Complete Technical Analysis

> Deep-dive documentation of every stress/throttling test implemented in the Android
> version of the app ("ThermoHammer"), how the workload is generated, how performance
> is measured, how thermal state is tracked, and how results are scored, stored,
> submitted, and compared.
>
> Source root analyzed: `ThermoHammerAndroid/app/src/main/java/com/example/thermohammer/`

---

## Table of Contents

1. [Application Identity & Build Configuration](#1-application-identity--build-configuration)
2. [Codebase Map — Where Everything Lives](#2-codebase-map--where-everything-lives)
3. [The Test Matrix — All Six Test Configurations](#3-the-test-matrix--all-six-test-configurations)
4. [The Stress Workload — What the CPU Actually Executes](#4-the-stress-workload--what-the-cpu-actually-executes)
5. [The "GPU" Worker — Pseudo-GPU Load](#5-the-gpu-worker--pseudo-gpu-load)
6. [Measurement Pipeline — Counters, Sampling, Baselines](#6-measurement-pipeline--counters-sampling-baselines)
7. [Scoring & Stability Math — Every Formula](#7-scoring--stability-math--every-formula)
8. [Thermal Telemetry Subsystem](#8-thermal-telemetry-subsystem)
9. [Test Lifecycle — The Complete State Machine](#9-test-lifecycle--the-complete-state-machine)
10. [The `StressState` Data Model — Field-by-Field](#10-the-stressstate-data-model--field-by-field)
11. [Pre-Test Diagnostics Gate](#11-pre-test-diagnostics-gate)
12. [Result Persistence — Pending Offline Runs](#12-result-persistence--pending-offline-runs)
13. [Server Submission — Session, Hashing, Payload Contract](#13-server-submission--session-hashing-payload-contract)
14. [Server-Side Scoring (ThermoHammer.Api)](#14-server-side-scoring-thermohammerapi)
15. [Leaderboard & Filtering](#15-leaderboard--filtering)
16. [Run Comparison Engine](#16-run-comparison-engine)
17. [UI Instrumentation During a Test](#17-ui-instrumentation-during-a-test)
18. [Edge Cases, Quirks & Methodology Limitations](#18-edge-cases-quirks--methodology-limitations)
19. [Appendix A — Numeric Encoding Tables](#appendix-a--numeric-encoding-tables)
20. [Appendix B — Exact Worker Loop Source](#appendix-b--exact-worker-loop-source)

---

## 1. Application Identity & Build Configuration

| Property | Value | Location |
|---|---|---|
| `applicationId` | `com.granturismo.thermohammer` | `app/build.gradle.kts` |
| `namespace` | `com.example.thermohammer` | `app/build.gradle.kts` |
| `versionCode` / `versionName` | `8` / `1.7` | `app/build.gradle.kts` |
| `compileSdk` / `targetSdk` | `36` / `36` | `app/build.gradle.kts` |
| `minSdk` | `24` (Android 7.0) | `app/build.gradle.kts` |
| UI toolkit | Jetpack Compose (BOM `2026.03.01`), Material 3, dark theme only | `MainActivity.kt`, `libs.versions.toml` |
| Language / JVM | Kotlin `2.3.20`, Java 17 toolchain | `libs.versions.toml` |
| Networking | Retrofit 2.11 + OkHttp 4.12 + Gson | `network/ApiService.kt` |
| Backend base URL | `https://thapi.gtgroup.dev/` | `ApiService.kt` → `ApiClient.BASE_URL` |
| Release build | minify + shrinkResources + ProGuard, signed with `GTech.keystore` | `app/build.gradle.kts` |
| Manifest permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `BATTERY_STATS` | `AndroidManifest.xml` |
| Activity | single `MainActivity`, `configChanges="orientation|screenSize|smallestScreenSize|screenLayout"` | `AndroidManifest.xml` |

Notes on config:

* **`BATTERY_STATS`** is a signature/privileged-level permission — a normal app never
  actually receives it. It is harmless but effectively a no-op; battery data is in fact
  obtained through the `ACTION_BATTERY_CHANGED` sticky broadcast, which requires no
  permission at all.
* **`configChanges=orientation|...`** means rotations do **not** recreate the Activity.
  This is load-bearing: the stress test survives screen rotation because the ViewModel
  and the Activity are never destroyed.
* **Signing credentials are committed in plaintext** in `app/build.gradle.kts`
  (`storePassword`/`keyPassword` + absolute keystore paths for macOS and Windows).
  Flagged as a security concern in §18.
* No `Application` subclass, no Dependency Injection framework, no WorkManager, no
  foreground Service — the whole engine is a single `ViewModel`.

---

## 2. Codebase Map — Where Everything Lives

| File | Role |
|---|---|
| `MainActivity.kt` | Activity, theme, `FLAG_KEEP_SCREEN_ON` wiring, lifecycle observer registration |
| `engine/StressEngine.kt` | **The entire test engine**: data models, worker threads, samplers, telemetry, lifecycle cancel, device checks — 602 lines |
| `engine/StressEngineFactory.kt` | `ViewModelProvider.Factory` injecting `applicationContext` into `StressEngine` |
| `ui/screens/MainScreen.kt` | Tab host + **`DiagnosticsScreenWrapper`** — the *active* test UI flow (pickers, overlays, auto-save, submit) |
| `ui/screens/DiagnosticsScreen.kt` | **Legacy/dead screen** — an older flow that created a server session *before* the test; no longer referenced (see §18) |
| `ui/screens/LeaderboardScreen.kt` | Global leaderboard, pending-runs upload, filters, detail overlay, compare-selection state |
| `ui/components/StabilityChart.kt` | `StabilityChart` (single run, thermal markers, touch inspector) + `DualStabilityChart` + color helpers |
| `ui/components/CoreMeter.kt` | `CoreMeter`, `GpuMeter`, `CoreStatusView` — per-worker radial impact rings |
| `ui/components/ThermalPanel.kt` | `ThermalMonitoringPanel` — CPU/battery temps, per-core frequency bars, expandable sensor list |
| `ui/overlays/Overlays.kt` | `PreTestOverlay`, `SummaryOverlay`, `BackgroundAbortedOverlay`, `ManualCancelledOverlay`, `ServerInitOverlay`, `ServerErrorOverlay`, `ConnectionRequestOverlay` + shared chrome |
| `ui/overlays/ComparisonOverlay.kt` | Side-by-side run comparison UI, lazy stamp fetching, metric cards |
| `data/PendingResultStore.kt` | `PendingTestResult` model + JSON-file persistence (`pending_results.json` in `filesDir`) |
| `data/ComparisonModels.kt` | `ComparisonEngine.analyze()` — cross-run analytics + winner scoring + summary text |
| `network/ApiService.kt` | DTOs (`DeviceHammerStamp`, `HammerPayload`, `SessionResponse`, `HammerDto`), `ThermoApi` Retrofit interface, `ApiClient` singleton |
| `network/ThermoHasher.kt` | `baseize()` (UTF-32LE→Base64) + HMAC-SHA256 result hash, cross-compatible with iOS |

---

## 3. The Test Matrix — All Six Test Configurations

Two independent axes define a test run. They are modelled as enums in
`engine/StressEngine.kt`:

### Axis 1 — `TestDuration` (target length)

| Enum | UI label | `seconds` | Type code sent to server |
|---|---|---|---|
| `MINUTES_5` | `"5 Min"` | 300 | `0` |
| `MINUTES_15` | `"15 Min"` | 900 | `1` |
| `MINUTES_30` | `"30 Min"` | 1800 | `2` |

The `seconds` field is `Int?` and every entry is non-null — the `?.let` in the timer
is defensive, not a real "infinite" mode. There is **no unlimited/free-run test**.

### Axis 2 — `StressThreadingType` (parallelism)

| Enum | UI label | `value` | Workers spawned |
|---|---|---|---|
| `SINGLE` | `"1 Thread"` | `0` | exactly **1** worker thread |
| `MULTI` | `"Multi Thread"` | `1` | **`coreCount`** worker threads, where `coreCount = Runtime.getRuntime().availableProcessors()` |

The UI explicitly marks **Multi Thread** as "RECOMMENDED" (green badge); Single Thread
shows an invisible badge placeholder so both buttons stay the same height
(`ThreadingPicker` in `DiagnosticsScreen.kt`).

### The six concrete tests

| # | Duration | Mode | Server `type` | Server `testThreadingType` |
|---|---|---|---|---|
| 1 | 5 min | Single | 0 | 0 |
| 2 | 5 min | Multi | 0 | 1 |
| 3 | 15 min | Single | 1 | 0 |
| 4 | 15 min | Multi | 1 | 1 |
| 5 | 30 min | Single | 2 | 0 |
| 6 | 30 min | Multi | 2 | 1 |

**Semantics of each mode:**

* **Multi Thread** — the flagship test. Saturates *every logical core* the Linux
  kernel exposes to the app (`availableProcessors()` — typically 8 on modern ARM
  SoCs: e.g. 4 efficiency + 4 performance, or 1+3+4 on tri-cluster chips). This is
  the "hammer the whole SoC until the thermal envelope forces DVFS down-clocking"
  scenario. The resulting score curve directly shows aggregate throttling.
* **Single Thread** — one busy thread only. Measures *single-core sustained
  frequency* under heat with minimal total package power. Because only one core is
  loaded, the SoC can often migrate the thread to a prime/performance core and hold
  boost clocks far longer; it isolates per-core thermal limits rather than
  package-level power budget. The UI renders all `coreCount` meters but inactive
  worker indices sit at 0% → displayed as **IDLE**.

Important implementation detail: worker threads are **not** pinned to physical
cores (no `sched_setaffinity`/JNI). `counters[i]` tracks worker *i*, and the UI
labels it "CORE i+1", but the Linux scheduler may migrate a worker across cores.
So a per-"core" meter is really *per-worker throughput*, which on a saturated
multi-threaded run approximates per-core residency well enough.

---

## 4. The Stress Workload — What the CPU Actually Executes

Inside `StressEngine.startTest()` each worker `Thread` runs this loop
(`StressEngine.kt` lines ~337–382):

```kotlin
Thread {
    var v1 = -6148914691236517206L // 0xAAAAAAAAAAAAAAAA
    var v2 = 6148914691236517205L  // 0x5555555555555555
    var v3 = 3689348814741910323L  // 0x3333333333333333
    var v4 = 8608480567731124087L  // 0x7777777777777777

    var f1 = 1.0000001
    var f2 = 2.0000002
    var f3 = 3.0000003
    var f4 = 4.0000004

    val l1Cache = LongArray(4096) { it.toLong() }   // 32 KiB — sized for L1d
    var cacheIdx = 0

    while (threadAlive.get()) {
        var i = 0
        while (i < 50_000) {
            v1 = (v1 xor (v2 + 7L)) * 3L
            f1 = f1 * 1.0000001 + 0.0000001

            v2 = (v2 xor (v3 + 13L)) * 5L
            f2 = f2 * 1.0000002 + 0.0000002

            v3 = (v3 xor (v4 + 17L)) * 7L
            f3 = f3 * 1.0000003 + 0.0000003

            v4 = (v4 xor (v1 + 19L)) * 11L
            f4 = f4 * 1.0000004 + 0.0000004

            l1Cache[cacheIdx] = l1Cache[cacheIdx] xor v1
            cacheIdx = (cacheIdx + 1) and 4095

            i++
        }
        counters[idx].addAndGet(50_000)     // publish throughput
    }
    if (v1 + v2 + v3 + v4 + f1.toLong() == 0L) println("noop: $v1 $f1")
}
```

### Why the loop is built this way

* **Integer pipeline stress:** four interdependent XOR→add→multiply chains
  (`v1`–`v4`) with distinct multipliers (3, 5, 7, 11) and distinct add constants
  (7, 13, 17, 19). The cross-dependency (`v4` reads `v1` updated two statements
  earlier) creates a serial dependency chain that defeats naive ILP extraction,
  keeping the integer ALU/multiplier ports saturated.
* **Floating-point pipeline stress:** four FMUL+FADD pairs on `Double`s
  (`f1`–`f4`) run in parallel with the integer work, hitting the FP/NEON units.
  The small increments prevent convergence to a fixed point that an optimizer
  could shortcut.
* **L1 cache pressure:** a `LongArray(4096)` = 32 KiB — exactly the L1d size of
  many ARM Cortex cores — is XOR-written round-robin with `and 4095` masking.
  This keeps the data cache port busy without ever spilling to L2/DRAM, so the
  workload measures *core* throughput rather than memory bandwidth.
* **Batch accounting:** work is chunked in blocks of **50,000 inner iterations**;
  only after a full block does the worker bump `counters[idx]` (an `AtomicLong`).
  The sampler reads these atomics once per second — so "score" is literally
  *inner-loop iterations completed in the last ~1 s*, summed across workers.
* **Jit-/AOT-safety:** the trailing `if (v1+v2+v3+v4+f1.toLong() == 0L) println(...)`
  consumes the accumulators so ART cannot eliminate the computation as dead code.
* **Priority:** `Thread.NORM_PRIORITY` (5) — deliberately not maxed; the app
  measures what the *scheduler* gives a normal app under thermal pressure.
* **Cooperative stop:** `threadAlive` is an `AtomicBoolean` polled once per outer
  block (every 50 k iterations — sub-millisecond cadence on a modern phone), so a
  stop request lands almost instantly. `stopTest()` also calls `interrupt()`,
  though the loop contains no blocking calls to interrupt — the flag does the work.

### Heat profile produced

Per inner iteration the core executes roughly: 4×(xor + add + mul) integer ops,
4×(mul + add) double ops, one array read-xor-write + masked index update — i.e.
~25–30 instructions of dense ALU/FPU work with one L1 hit. That is a near-worst-case
**power-virus-style** workload for in-order and OoO ARM cores alike: max IPC, both
integer and FP pipes busy, minimal stalls. Temperature rises fast (usually within
30–90 s), after which the SoC's thermal daemon starts dropping
`scaling_max_freq`/migrating load to little cores — exactly what the test is
designed to detect as a throughput decay.

---

## 5. The "GPU" Worker — Pseudo-GPU Load

`startGpuWorker()` (`StressEngine.kt` lines ~250–273) spawns **one** extra thread:

```kotlin
gpuThread = Thread {
    var a = 1.0f
    var b = 2.0f
    while (threadAlive.get()) {
        var i = 0
        while (i < 20_000) {
            a = a * b + 0.0001f
            b = b * a + 0.0002f
            i++
        }
        gpuCounter.addAndGet(1L)   // counts *batches*, not iterations
    }
    if (a + b == 0f) println("noop gpu: $a")
}
```

Key facts:

* **It is CPU work, not GPU work.** The code comment says it plainly: *"mobile GPUs
  can't preempt heavy fragment shaders, which blocks Compose's RenderThread and
  freezes the UI on stop. CPU math generates equivalent thermal load and exits
  instantly."* A real GL/Vulkan shader burn would starve the compositor and make
  the STOP button unresponsive — a UX-breaking tradeoff.
* It runs **in both Single and Multi modes** (it is outside the `activeThreadCount`
  branch) — so even the "1 Thread" test actually runs **2** OS threads total
  (1 integer worker + 1 float worker). This is a subtle but real footnote on the
  purity of the "single-threaded" measurement.
* Its counter increments by **1 per 20,000-iteration batch**, whereas CPU workers
  increment by **50,000 per batch** — different units, only used for the relative
  `gpuImpact` percentage, never mixed into the CPU score.
* `gpuImpact` uses the same ratchet-baseline math as CPU cores: highest observed
  batch-delta becomes the 100% reference.

---

## 6. Measurement Pipeline — Counters, Sampling, Baselines

A running test is driven by **N+3 concurrent flows** (N = active workers):

| Flow | Thread/dispatcher | Cadence | Job |
|---|---|---|---|
| Worker threads ×N | `Thread` (priority 5) | continuous | burn CPU, `counters[i].addAndGet(50_000)` |
| GPU worker ×1 | `Thread` (priority 5) | continuous | float burn, `gpuCounter.addAndGet(1)` |
| Stats sampler | `viewModelScope` + `Dispatchers.Default` | every **1000 ms** | `gatherStats()` |
| Test timer | `viewModelScope` (main) | every **1000 ms** | `tickTimer()` |
| Thermal status poll | `LaunchedEffect` in `MainScreen` | every **5000 ms** | `engine.setThermalState(getThermalStateFromSystem())` |
| Live telemetry | `viewModelScope` + `Dispatchers.IO` (started in `init`) | every **1000 ms** | `updateLiveTemperatures()` — runs **always**, even idle |

### `gatherStats()` — the heart of measurement (lines ~463–519)

Executed once per second on `Dispatchers.Default`:

1. **Delta computation.** For every core index `i` in `0 until coreCount`:
   `coreSpeeds[i] = counters[i].get() - prevCounterValues[i]`, then the snapshot is
   stored back to `prevCounterValues`. `totalSpeed = Σ coreSpeeds`.
   *Because `counters` always has `coreCount` entries even in single mode, inactive
   workers contribute a flat 0 delta.*
2. **Ratchet baseline calibration (upward only).**
   `if (coreSpeeds[i] > coreBaselines[i]) coreBaselines[i] = coreSpeeds[i]` and
   `if (totalSpeed > overallBaseline) overallBaseline = totalSpeed`.
   Baselines start at `1.0` at test start and can **only grow** — the 100% reference
   is *the best second this device has ever produced during this run*. This makes
   the metric self-calibrating across wildly different SoCs without hardcoding an
   absolute ops/sec number.
3. **Per-worker impact:** `impact_i = clamp(coreSpeeds[i] / coreBaselines[i] × 100, 0, 100)`.
4. **Overall stability (live):** `stability = clamp(totalSpeed / overallBaseline × 100, 0, 100)`.
5. **Stamp recording:** `statsSampleCount++`, then a `DeviceHammerStamp` is appended:
   * `elapsedMs = statsSampleCount × 1000` (i.e. 1000, 2000, 3000, …)
   * `score = totalSpeed.toLong()` — **raw** total iterations for that second
     (not the normalized percentage — the server re-derives stability itself)
   * `thermalState = 0|1|2|3` mapped from the current `ThermalState`
     (NOMINAL/FAIR/SERIOUS/CRITICAL) — the value is whatever the 5 s system-thermal
     poller last set, so stamps have ±5 s thermal accuracy.
6. **GPU impact:** same delta + ratchet math over `gpuCounter`/`gpuBaseline`;
   written to state only `if (it.isRunning)` else forced to 0.

### `tickTimer()` — test clock (lines ~521–545)

Runs on the main dispatcher every second:

* `elapsedSeconds += 1`
* Appends `StabilityPoint(elapsed, overallStability)` to `chartPoints` — the chart
  therefore stores **stability %**, while `recordedStamps` stores **raw score**.
  Two parallel histories of the same run.
* When `elapsedSeconds >= testDuration.seconds`: captures final battery level/temp,
  sets `wasCompleted = true`, then calls `stopTest()`.

### Worker/index accounting subtleties

* `counters`, `prevCounterValues`, and `coreBaselines` arrays are sized `coreCount`
  — allocated once at ViewModel init, **not** per test; they're zeroed/reset at the
  top of `startTest`.
* In **single-thread mode** all `coreImpacts` are still emitted (list of
  `coreCount` floats); only index 0 is pre-seeded at 100f and the rest 0f, and after
  the first sample the inactive ones settle to exactly 0 (0 delta / baseline 1.0)
  → rendered as "IDLE".
* Sampling and timing are **independent 1 s coroutines** — they can drift by up to
  ~1 s relative to each other and to wall time under load. Stamps are numbered by
  sample count (×1000 ms), chart points by timer ticks; on a heavily loaded device
  the two can skew slightly because `delay(1000)` measures from coroutine resume.

---

## 7. Scoring & Stability Math — Every Formula

There are **three different stability numbers** in the app — a common source of
confusion:

### 7.1 Live stability (per second, in `gatherStats`)

```
overallStability(t) = clamp( totalSpeed(t) / overallBaseline , 0..1 ) × 100
```

where `overallBaseline = max over all τ≤t of totalSpeed(τ)` (starts at 1.0).
This is the value on the big "STABILITY SCORE" stat card and every `StabilityPoint`.

### 7.2 Min stability (report metric)

```
minStability = min( chartPoints[].score )
```

The lowest instantaneous per-second reading of the whole run — "how deep was the
worst dip". Used in `SummaryOverlay` ("MIN STABILITY") and saved into
`PendingTestResult.minStability`.

### 7.3 Final stability (the *official* score — client AND server)

```
finalStability = average( second half of stamps[].score ) / max( stamps[].score ) × 100
```

Computed identically in three places:

* `MainScreen.kt` (lines ~141–146) when auto-saving the pending result;
* `DiagnosticsScreen.kt`/`MainScreen.kt` when showing `SummaryOverlay`
  ("FINAL STABILITY");
* **`Hammer.cs → ToDao()` on the server** — `StabilityPercentage = avg(secondHalf)/max × 100`,
  which is what the leaderboard sorts by.

**Why the second half:** the first half includes the cold boost period; averaging
only the latter 50% of samples measures *sustained* throughput — the number that
actually characterizes the thermal solution. A phone that peaks high but collapses
scores low; a phone that sags early then plateaus still scores decently.

### 7.4 Per-core impact

```
impact_i(t) = clamp( coreSpeed_i(t) / max_τ(coreSpeed_i(τ)) , 0..1 ) × 100
```

Displayed as rings: "100%", "-N% IMPACT", or "IDLE". Ring color: ≥95 green,
≥80 yellow, otherwise orange-red; ≤0.5 → IDLE.

### 7.5 Throttle onset (comparison engine only)

```
throttleOnset = elapsedMs of first stamp where score < 0.9 × peakScore   (in seconds)
```

`null` when the run never dropped below 90% of its own peak → UI shows
"No Throttle". Defined in `ComparisonEngine.analyze()` (`ComparisonModels.kt`).

### 7.6 Battery-derived metrics

* `batteryDrain = max(0, initialLevel − finalLevel) / durationMinutes` — % per min
* `tempRise = max(0, finalBatteryTemp − initialBatteryTemp) / durationMinutes` — °C per min

Used by the comparison engine; the raw start/end values also appear in the
summary overlay ("BATTERY TEMP RISE", "BATTERY DRAIN" with colored thresholds).

### 7.7 Color/severity thresholds used across the UI

| Metric | Green | Yellow/Orange | Red |
|---|---|---|---|
| Stability score | ≥ 90 | ≥ 75 | < 75 |
| Core ring impact | ≥ 95 | ≥ 80 | < 80 |
| CPU temp | < 45 °C | ≥ 45 °C | ≥ 60 °C |
| Battery temp | < 36 °C | ≥ 36 °C | ≥ 42 °C |
| Per-core freq % of max | < 60% | ≥ 60% | ≥ 90% (red = *near max*, not "bad") |
| Battery temp rise (summary) | < 2 °C | ≥ 2 °C | ≥ 5 °C |
| Battery drain (summary) | < 2 % | ≥ 2 % | ≥ 5 % |

---

## 8. Thermal Telemetry Subsystem

`updateLiveTemperatures()` runs every second for the **whole ViewModel lifetime**
(started in `init`, loops while `isActive`) — the panel shows live temps before a
test even starts.

### Battery temperature & level

* Source: `registerReceiver(null, IntentFilter(ACTION_BATTERY_CHANGED))` — the
  sticky broadcast; no permission needed.
* Temperature: `EXTRA_TEMPERATURE / 10f` (tenths of °C → °C).
* Level: `EXTRA_LEVEL / EXTRA_SCALE × 100`.

### Kernel thermal zones — `/sys/class/thermal`

`getSystemThermalZones()` scans `thermal_zone*` directories, reads `type` + `temp`:

* **Unit auto-detection:** raw values with |v| > 1000 are treated as
  **milli-degrees** and divided by 1000 (kernel convention); smaller values are
  assumed already in °C.
* **Sanity filter:** only `-40 … +150 °C` kept — garbage lines are dropped.
* **CPU temperature heuristic:** `max(temp of zones whose name contains "cpu")`;
  if none match, falls back to the first zone containing `"soc"` or `"tsens"`;
  else `0` → UI shows `"-- °C"`.
* The full list lands in `state.thermalSensors` for the expandable
  "SHOW ALL SENSORS" list, sorted descending by temperature.
* **Availability caveat:** reading sysfs thermal nodes is SELinux-gated on some
  devices/Android versions; every read is wrapped in try/catch → empty list, so
  the panel degrades gracefully.

### System thermal status — `PowerManager.currentThermalStatus` (API 29+)

Polled every **5 s** while a test runs (a `LaunchedEffect` in `MainScreen`,
*not* inside the engine), mapped:

| `PowerManager` status | App `ThermalState` |
|---|---|
| `THERMAL_STATUS_NONE` | `NOMINAL` |
| `THERMAL_STATUS_LIGHT` | `FAIR` |
| `THERMAL_STATUS_MODERATE` | `FAIR` |
| `THERMAL_STATUS_SEVERE` | `SERIOUS` |
| `THERMAL_STATUS_CRITICAL` | `CRITICAL` |
| `THERMAL_STATUS_EMERGENCY` | `CRITICAL` |
| `THERMAL_STATUS_SHUTDOWN` | `CRITICAL` |
| (API < 29 / unknown) | `NOMINAL` |

Every call to `setThermalState()` while `isRunning` appends a `ThermalEvent(time,
state)` — note it appends **even when the state didn't change**, because the poller
fires unconditionally. Wait — it appends on *every poll tick*, not only on
transitions; `thermalEvents` can therefore contain many duplicate consecutive
states, and the chart draws a dashed vertical marker for each (they stack
invisibly on the same x). `worstThermal` is computed as `maxByOrNull { ordinal }`.

### Per-core frequencies — `/sys/devices/system/cpu/cpu<N>/cpufreq`

`getCpuFrequencies()` reads per core `0 .. coreCount-1`:

* `scaling_cur_freq` → `currentKHz` (0 = offline/unreadable)
* `cpuinfo_max_freq` → `maxKHz` (0 = unreadable)

`CpuCoreFreq` derived props: `currentGHz`, `maxGHz`, `percentOfMax`
(clamped 0–100, `null` if max unknown), `isOnline = currentKHz > 0`.
Rendered as 2-column `CoreFreqCard`s: `CPU<n>`, GHz value, % of max, colored bar,
"OFFLINE"/"-- GHz" when unreadable. Like thermal zones, this depends on SELinux
policy — on locked-down builds the whole section silently disappears.

---

## 9. Test Lifecycle — The Complete State Machine

```
IDLE ──tap INITIATE──▶ PreTestOverlay ──PROCEED──▶ RUNNING ──timer hits limit──▶ COMPLETED
                          │                        │                                │
                          └──CANCEL──▶ IDLE        ├──STOP button──▶ MANUAL_CANCELLED
                                                   │                                │
                                                   └──app onStop()──▶ BACKGROUND_ABORTED
```

### 9.1 Starting (`startTest`, lines ~286–401)

Guarded by `if (_state.value.isRunning) return` — double-start impossible.

1. `activeThreadCount = 1` for SINGLE else `coreCount`.
2. `coreImpacts` initialized: single → `[100, 0, 0, …]`; multi → all 100.
3. Snapshots battery level & temp into `initial*`/`final*`/`current*` fields.
4. Resets all counters, `prevCounterValues`, `coreBaselines=1.0`,
   `overallBaseline=1.0`, `gpuBaseline=1.0`, `statsSampleCount=0`,
   clears `chartPoints`/`thermalEvents`/`recordedStamps`, sets
   `chartPoints=[StabilityPoint(0, 100)]`.
5. `addThermalEvent(currentThermalState, 0)` — seeds the event list at t=0.
6. `threadAlive=true` → spawn workers → spawn GPU worker → launch stats job →
   launch timer job.

### 9.2 Stopping (`stopTest`, lines ~403–425)

* `threadAlive=false`; cancels stats & timer jobs; snapshots
  `finalBatteryLevel`/`finalBatteryTemp` from the *last live telemetry values*;
  `isRunning=false`.
* Worker interruption is dispatched asynchronously on `Dispatchers.Default`
  (`forEach interrupt()` + `gpuThread.interrupt()`). Threads never `join()`ed —
  they die on their next 50 k-iteration boundary check.
* `MainActivity` observes `isRunning` and toggles
  `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` — the screen is kept awake
  *only* during runs (no `WakeLock` anywhere; nothing keeps the CPU busy in
  background, by design the test aborts instead).

### 9.3 Completion path

`tickTimer` detects `elapsedSeconds >= duration.seconds`:
`wasCompleted=true` + final battery snapshot → `stopTest()`.

Then in `DiagnosticsScreenWrapper`'s `LaunchedEffect(state.isRunning)`:
when `!isRunning && elapsedSeconds > 0`:

* **`wasCompleted`** →
  1. **Auto-save:** a `PendingTestResult` is built (UUID id, timestamp,
     durationType, threadingType, min/final stability, worst thermal, full stamp
     list, device model/manufacturer, OS version, battery deltas — `sessionId=0`,
     `encryptionKey=""`) and appended to `pending_results.json` on the IO
     dispatcher. `currentPendingResultId` remembered so a successful submit can
     delete it.
  2. Online → `SummaryOverlay`; offline → `ConnectionRequestOverlay`
     ("I TURNED IT ON" → summary; "SUBMIT LATER" → summary anyway).
* **`wasCancelledByBackground`** → `BackgroundAbortedOverlay` — *result discarded*.
* **otherwise (manual stop)** → `ManualCancelledOverlay` — *result discarded*.

So: **only fully-completed runs are saved/submittable.** Partial data of cancelled
tests is thrown away (though it still lives in state until `resetTestResult()`).

### 9.4 Background abort

`StressEngine` implements `DefaultLifecycleObserver` and is registered via
`lifecycle.addObserver(engine)` in `MainActivity`. `onStop()` — fired when the app
is minimized or another activity covers it — sets `wasCancelledByBackground=true`
and stops the test. Foreground requirement is also communicated to the user in the
pre-test checklist ("KEEP APP IN FOREGROUND").

*Caveat:* `onStop` is **not** called on mere configuration change (rotation is
handled by `configChanges`, no recreation) — correct. But it *is* triggered by
things like the notification shade in some OEM builds? No — a translucent overlay
only triggers `onPause`. `onStop` requires the window to be fully obscured/hidden.
Split-screen (activity remains visible → `onPause` only) lets the test keep
running.

### 9.5 `resetTestResult()`

Clears run-specific state back to defaults (`elapsedSeconds=0`,
`wasCompleted=false`, `overallStability=100`, empties all lists, zeroes battery
fields) — invoked when overlays dismiss or when `DiagnosticsScreenWrapper`
composes away (`DisposableEffect.onDispose` guards on any meaningful run).

### 9.6 Session lifecycle (`setSession`/`clearSession`)

`sessionId`/`encryptionKey` in state are vestiges of the *old* flow (create session
before test). In the **current** flow, `clearSession()` is called on PROCEED and a
session is minted lazily at submit time. `setSession()` is only called by the dead
`DiagnosticsScreen`.

---

## 10. The `StressState` Data Model — Field-by-Field

```kotlin
data class StressState(
    val isRunning: Boolean = false,                    // master run flag
    val elapsedSeconds: Int = 0,                       // timer ticks
    val overallStability: Float = 100f,                // live % (§7.1)
    val coreImpacts: List<Float> = emptyList(),        // per-worker % (§7.4)
    val chartPoints: List<StabilityPoint> = emptyList(),// (sec, stability%) history
    val thermalEvents: List<ThermalEvent> = emptyList(),// every 5 s poll while running
    val currentThermalState: ThermalState = NOMINAL,   // last system status
    val wasCancelledByBackground: Boolean = false,     // abort flag
    val wasCompleted: Boolean = false,                 // natural finish flag
    val testDuration: TestDuration = MINUTES_5,        // chosen axis-1
    val testThreadingType: StressThreadingType = MULTI,// chosen axis-2
    val sessionId: Int? = null,                        // legacy pre-test session
    val encryptionKey: String? = null,                 // legacy pre-test key
    val recordedStamps: List<DeviceHammerStamp> = emptyList(), // 1 Hz raw samples → upload payload
    val batteryTemp: Float = 0f,                       // live, °C
    val cpuTemp: Float = 0f,                           // live heuristic, °C
    val thermalSensors: List<Pair<String, Float>> = emptyList(), // all zones
    val cpuFrequencies: List<CpuCoreFreq> = emptyList(),// per-core clocks
    val initialBatteryLevel: Int = 0,
    val initialBatteryTemp: Float = 0f,
    val finalBatteryLevel: Int = 0,
    val finalBatteryTemp: Float = 0f,
    val currentBatteryLevel: Int = 0,                  // live %
    val gpuImpact: Float = 0f                          // pseudo-GPU %
)
```

Auxiliary models:

* `StabilityPoint(time: Float, score: Float)` — chart sample (seconds, percent).
* `ThermalEvent(time: Float, state: ThermalState)` + `name` getter →
  "NOMINAL"/"FAIR"/"SERIOUS"/"CRITICAL".
* `CpuCoreFreq(core, currentKHz, maxKHz)` + derived `currentGHz`, `maxGHz`,
  `percentOfMax: Int?`, `isOnline`.
* `DeviceHammerStamp(elapsedMs: Int, score: Long, thermalState: Int)` — the unit
  of uploadable truth; produced at 1 Hz, so a 30 min test yields **1800 stamps**.

---

## 11. Pre-Test Diagnostics Gate

`PreTestOverlay` (shown on every INITIATE tap) displays five advisory checks —
**none of them block** "PROCEED":

| Row | Check | Source | Verdict logic |
|---|---|---|---|
| `☐ REMOVE PHONE CASE` | static advice | — | always shown (orange) |
| `⚡ BATTERY SAVER` | `PowerManager.isPowerSaveMode` | system | ON → orange warning; OFF → green ✓ |
| `⚡ CHARGER CONNECTED!` | `EXTRA_STATUS` ∈ {`BATTERY_STATUS_CHARGING`, `BATTERY_STATUS_FULL`} | sticky intent | connected → **red** "CRITICAL" warning |
| `📡 CELLULAR DETECTED` | `TelephonyManager.networkType != UNKNOWN && simState == READY` | system | active → orange; else green |
| `ℹ KEEP APP IN FOREGROUND` | static advice | — | always shown (blue info) |

Rationale baked into the copy: charging adds heat that "forces thermal
throttling"; battery saver caps clocks; cellular radio "generates extra
background heat"; a case traps heat. PROCEED → `engine.clearSession()` +
`engine.startTest(selectedDuration, selectedThreadingType)` — no server call
happens at test start in the current flow (fully offline-capable test).

Device-context helpers also exposed on the engine: `getAndroidVersion()`
(`Build.VERSION.RELEASE`), `getDeviceModel()` (dedupes manufacturer prefix),
`isCharging()`, `isBatterySaverOn()`, `isCellularActive()`.

---

## 12. Result Persistence — Pending Offline Runs

`PendingResultStore` — the simplest possible store: Gson-serialized
`List<PendingTestResult>` in `filesDir/pending_results.json`.

```kotlin
data class PendingTestResult(
    val id: String,                    // UUID (or "online_<id>" for fetched runs)
    val timestamp: Long,
    val durationSeconds: Int,          // actual elapsed
    val testDurationType: Int,         // 0/1/2
    val testThreadingType: Int = 1,    // 0 single / 1 multi
    val minStability: Float,
    val finalStability: Float,         // 2nd-half-avg / max formula
    val worstThermalState: Int,        // ordinal 0–3
    val stamps: List<DeviceHammerStamp>,
    val deviceModel: String,
    val deviceManufacturer: String,
    val osVersion: String,
    val sessionId: Int,                // 0 for locally-saved runs
    val encryptionKey: String,         // "" for locally-saved runs
    val initialBatteryLevel: Int = 0,
    val finalBatteryLevel: Int = 0,
    val initialBatteryTemp: Float = 0f,
    val finalBatteryTemp: Float = 0f
)
```

Properties:

* `saveResult` = read-all → append → rewrite file. `deleteResult(id)` = filter →
  rewrite. No locking — all callers funnel through the IO dispatcher or main
  thread; risk of interleaved writes is theoretical, not practical here.
* Every completed run is **auto-saved** (§9.3) — the pending list doubles as a
  local history, even for online tests until their submit succeeds.
* Surfaced in two places: `PendingRunsBanner` on the diagnostics tab ("UNSUBMITTED
  RESULTS DETECTED", tap → leaderboard) and the "PENDING OFFLINE RUNS" section at
  the top of `LeaderboardScreen`, where each row shows duration badge, threading
  badge (orange `1 THREAD` / green `MULTI`), device name, final stability, a
  compare toggle, SUBMIT (only while online) and DELETE.
* Submitting a pending run mints a **fresh session at upload time** — the hash is
  computed over stored stamps with the new key, so deferring upload is safe.
  On success the local row is deleted and the leaderboard refreshes.

---

## 13. Server Submission — Session, Hashing, Payload Contract

### API surface (`ThermoApi`, base `https://thapi.gtgroup.dev/`)

| Method | Path | Body / Params | Returns |
|---|---|---|---|
| `POST` | `session` | — | `SessionResponse { id: Int, encryptionKey: String }` |
| `POST` | `hammer` | `HammerPayload` | 200 "Data saved" / 404 session / 400 bad hash |
| `GET` | `leaderboard` | — | `List<HammerDto>` sorted desc by `stabilityPercentage` |
| `GET` | `stamps/{id}` | hammer id | `List<DeviceHammerStamp>` |
| `GET` | `modelfix` | (server-side housekeeping) | rewrites model names via resolver |

### `HammerPayload` (what a submit sends)

```kotlin
HammerPayload(
    stamps             = state.recordedStamps,      // full 1 Hz series
    type               = 0|1|2,                     // duration code
    testThreadingType  = 0|1,                       // single|multi
    deviceManufacturer = Build.MANUFACTURER.capitalized,
    deviceModel        = engine.getDeviceModel(),
    os                 = 2,                         // 1=iOS, 2=Android
    osVersion          = Build.VERSION.RELEASE,
    sessionId          = session.id,
    hash               = ThermoHasher.computeHash(session.encryptionKey, stamps)
)
```

### Anti-cheat hash (`ThermoHasher`)

Intended to prove the stamps were produced by a genuine client run:

1. **`baseize(txt)`** — each UTF-16 code unit → **4 little-endian bytes**
   (UTF-32LE per `Char.code`) → Base64 (no wrap). Documented as matching the iOS
   `baseize()` exactly. ⚠️ For characters outside the BMP (emoji etc.) a Kotlin
   `Char` is a surrogate *half* — each surrogate is baseized as its own
   "code point". As long as iOS does the same (per-`unichar`), they stay
   compatible; stamp fields are digits + ASCII names anyway.
2. For every stamp, concatenate `baseize(elapsedMs) + baseize(score) +
   baseize(thermalName)` where thermalName ∈ {"Nominal","Fair","Serious","Critical"}.
3. **HMAC-SHA256** the concatenated Base64 string with `encryptionKey` (UTF-8);
   Base64-encode the signature → `hash` field.

Server side (`ThermoEndpointsMapper`): `POST /hammer` looks up the session,
runs `encryptor.IsValid(session.EncryptionKey, request)`, on success **closes the
session** (one-shot key — replay prevention), resolves the model name, persists
the hammer + stamps. A closed/reused or forged session → `NotFound`/`BadRequest`.

*Security note:* it's integrity/anti-tamper theatre rather than strong
attestation (the key is in the client's hands), but it raises the bar for
leaderboard graffiti and guarantees stamps can't be edited *after* hashing by a
proxy — the hash covers every timestamp/score/thermal tuple.

---

## 14. Server-Side Scoring (ThermoHammer.Api)

`HammerExtensions.ToDao()` (`ThermoHammer.Api/Models/Hammer.cs`):

```csharp
double maxScore = request.Stamps.Max(s => s.Score);
int secondHalfStart = request.Stamps.Count / 2;
double averageSecondHalf = request.Stamps.Skip(secondHalfStart).Average(s => s.Score);
stability = (averageSecondHalf / maxScore) * 100;
```

→ `Hammer.StabilityPercentage`, returned in `HammerDto`, and `/leaderboard` orders
`OrderByDescending(o => o.StabilityPercentage)`.

**The official leaderboard number = (mean throughput of the second half of the
run) ÷ (peak single-second throughput) × 100** — identical to the client's
`finalStability` (§7.3), so the app can display locally exactly what the server
will rank. `HammerType`/`StressThreadingType`/`OsPlatform` enums mirror the app's
type codes (0/1/2 durations; 0/1 threading; os 1=iOS, 2=Android).

---

## 15. Leaderboard & Filtering

`LeaderboardScreen` (tab index 1):

* **Data:** `fetchLeaderboard()` on network availability change + manual ↻.
  Ranked client-side by `stabilityPercentage` desc; top-3 get gold/silver/bronze
  gradient rank discs, the rest `#n` chips.
* **Row content:** rank badge · full device name (manufacturer prefix deduped) ·
  manufacturer · `iOS`/`Android` + OS version · threading badge (`1 THREAD`
  orange / `MULTI` green) · stability % (color-coded).
* **Search:** matches model, manufacturer, or OS version substring.
* **Filter chips:** `📱 My Model` (exact `Build.MODEL`), `⚙ My OS` (OS version
  substring), `🍎 iOS` / `🤖 Android` (`os==1`/`os==2`), `⏱ 5/15/30 Min`
  (`type==0/1/2`). Combinable (AND).
* **Detail overlay** (tap a row): `GET /stamps/{id}` → rebuilds
  `StabilityPoint`s normalized to the run's own max (score/max×100 vs seconds),
  shows TEST TYPE / STABILITY / PEAK IPS / MIN IPS / SAMPLES + the performance
  curve via `StabilityChart`.
* **Connectivity:** `MainScreen` polls `ConnectivityManager.NET_CAPABILITY_INTERNET`
  every 3 s → ONLINE/OFFLINE pill in the header; leaderboard no-ops when offline.

---

## 16. Run Comparison Engine

Two entry points: select 2 runs via the ⚖️ toggles (pending or online rows —
enforced *same threading mode* with a Toast otherwise), or open
`ComparisonOverlay` standalone (mixes local pending + online leaderboard runs,
filtered to the preselected run's threading type).

* Stamp series are fetched lazily per run (`fetchedStampsMap` cache) when an
  online entry's DTO lacks them.
* Chart data normalized per-run to its own max → `DualStabilityChart` draws cyan
  (A) vs amber (B) smoothed curves on a shared time axis with a drag inspector
  showing both readings.
* `ComparisonEngine.analyze()` computes:

| Metric | Formula |
|---|---|
| `peakScore*` | `max(stamps.score)` (fallback `max(100, finalStability×2500)`) |
| `avgScore*` | `mean(stamps.score)` (fallback `max(90, finalStability×2200)`) |
| `*DeltaPct` | `(B − A)/A × 100` |
| `throttleOnsetTime*` | first stamp `< 0.9×peak` → seconds, else `null` |
| `batteryDrain*` | `max(0, Δlevel%) / durationMin` |
| `tempRise*` | `max(0, ΔbattTemp) / durationMin` |
| `worstThermal*` | `ThermalState.entries[worstThermalState]` (defaults NOMINAL for A, CRITICAL for B on bad index — asymmetric quirk) |

* **Winner scoring:** +2 for higher final stability, +2 for higher avg score,
  +1 for lower battery drain, +1 for lower temp rise → `RUN_A`/`RUN_B`/`EQUAL`.
* **Auto-summary text:** winner sentence + throttle-onset comparison
  ("Run X delayed thermal throttling by Ns", or "…maintained sustained
  performance without significant throttling").
* Rendered as: run dropdowns → dual chart → 4 metric cards (FINAL STABILITY,
  PEAK SCORE w/ Δ%, THROTTLE ONSET, AVG SCORE w/ Δ%) → "ANALYTICAL ENGINE
  INSIGHTS" card.

---

## 17. UI Instrumentation During a Test

While `isRunning` the diagnostics tab shows, top to bottom:

1. **Header** — THERMOHAMMER + ONLINE/OFFLINE pill + pulsing red recording dot.
2. **StatsPanel** — `TEST DURATION` counts **down** (`duration − elapsed`; shows
   raw elapsed when idle) and live `STABILITY SCORE`; thermal bar with
   state name + "STRESS ACTIVE" chip.
3. **Pickers hidden** while running (duration & threading lock in).
4. **ControlButton** → red "◼ STOP STRESS TEST".
5. **StabilityChart** — smoothed cubic (Catmull-Rom-flavored `cubicTo` with
   midpoint x-control) stability curve on a vertical heat gradient
   (green→yellow→orange→red top-to-bottom), translucent fill, horizontal grid
   at 25/50/75 %, **dashed vertical markers per thermal event** in state colors,
   and a touch inspector (cyan dashed cursor + glow node + `m:ss` + score badge).
   Bottom axis labels `0m:00s` … `end`.
6. **CoreStatusView** — 4-column `LazyVerticalGrid` of rings: `GpuMeter` first
   (purple), then `CoreMeter` per worker (CORE 1..N; -N% IMPACT / 100% / IDLE).
7. **ThermalMonitoringPanel** — CPU CHIPSET & BATTERY PACK temp cards, CPU CORE
   FREQUENCIES grid (GHz + %-of-max bar per core, OFFLINE state), expandable
   all-sensors list sorted hottest-first.

Overlays: `PreTestOverlay` → `SummaryOverlay` (test time, min/final stability,
worst thermal, battery temp rise & drain with color deltas, submit/save-pending
states) / `BackgroundAbortedOverlay` / `ManualCancelledOverlay` /
`ServerInitOverlay` / `ServerErrorOverlay` / `ConnectionRequestOverlay`.

---

## 18. Edge Cases, Quirks & Methodology Limitations

Honest assessment — things to know before trusting/extending the numbers:

**Dead & vestigial code**

* `DiagnosticsScreen.kt` is **unreferenced** — superseded by
  `DiagnosticsScreenWrapper` inside `MainScreen.kt`. It still compiles and shows
  the *old* session-before-test flow. Its private `DurationPicker(state)` is an
  empty stub.
* In the wrapper, `showServerInit`/`showServerError`/`showComparison` are never
  set → dead overlay branches. The `ServerErrorOverlay.onRunOffline` there calls
  `engine.startTest(selectedDuration)` **dropping the threading selection**
  (defaults to MULTI) — a latent bug if that path were ever wired up.
* `sessionId`/`encryptionKey`/`setSession()` in engine state are leftovers from
  the pre-test session flow; the live flow mints sessions at submit time.

**Measurement methodology caveats**

* **Workers aren't pinned to cores** — "CORE n" = worker n. On heterogeneous SoCs
  (big.LITTLE/DynamIQ) the scheduler can park a worker on a little core; the
  per-"core" impact partially reflects *scheduling decisions*, not just thermal
  capping. The aggregate score remains valid.
* **Score unit = 50 k-iteration batches/second**, summed — a *relative*
  throughput proxy, not true IPC/MIPS. Fine for stability curves, not comparable
  to benchmarks across workloads. ART JIT/AOT state, GC pauses, and dalvik-level
  scheduling noise all ride along in it (that's partly the point — it measures
  delivered app performance).
* **Baselines are ratchet-max of the run itself** — a device that starts already
  hot/throttled will record a *low baseline* and can show ~100 % "stability"
  forever. There is no cold-start enforcement; running two tests back-to-back
  hides degradation. (The pre-test overlay advises, but doesn't measure,
  starting temperature.)
* **`Single Thread` isn't single** — the pseudo-GPU float worker runs too
  (2 busy threads). The GPU counter isn't part of `totalSpeed`, so the score is
  unaffected, but package power isn't truly "one core".
* **Sampler/timer skew** — two independent `delay(1000)` coroutines; under heavy
  load each can drift; stamps index by sample number, chart by timer — they can
  desync by a second or two over 30 min.
* **Thermal events are noisy** — appended every 5 s poll regardless of change, so
  `thermalEvents` mostly holds duplicates; `worstThermal` via max-ordinal is
  still correct.
* **`addThermalEvent(state, 0f)` at start** plus 5 s polling → the first real
  system status may arrive up to ~5 s late; `THERMAL_STATUS_*` granularity is
  coarse (6 levels → 4 buckets).
* **SELinux-dependent telemetry** — thermal zones & cpufreq sysfs reads fail
  silently on some devices (all try/catch → empty). The app then shows `-- °C`
  /no freq section while the test still measures throughput normally.
* **`onStop` abort ≠ `onPause`** — split-screen/PIP keeps running (probably
  intended); also any *full-screen* covering activity (incoming call UI on some
  OEMs) aborts the run — results discarded.
* **No wakelock** — screen kept on via `FLAG_KEEP_SCREEN_ON` only while running;
  if the display times out due to OEM quirks the app stays foreground-visible
  anyway, but there is no partial wakelock guaranteeing worker scheduling.
* **30 min run = 1800 stamps ≈ tens of KB JSON** — trivial payload; the whole
  file rewrites on each save are fine at this scale.
* **Security: release keystore credentials are committed in cleartext** in
  `app/build.gradle.kts` (`strelok222`). Anyone with repo access can sign
  updates — should be moved to env/CI secrets.
* **`BATTERY_STATS` permission is inert** — granted only to privileged apps;
  kept harmlessly.
* Comparison engine quirks: fallback fake scores (`stability×2500`/`×2200`)
  for stamp-less runs are rough guesses; `worstThermalB` defaults to CRITICAL on
  out-of-range index vs NOMINAL for A (asymmetric); onset uses `< 90 % of *own*
  peak` so a low-peak device can appear "never throttled".

---

## Appendix A — Numeric Encoding Tables

**Duration codes (`type` / `testDurationType`)**: `0` = 5 min (300 s) ·
`1` = 15 min (900 s) · `2` = 30 min (1800 s)

**Threading codes (`testThreadingType`)**: `0` = Single (1 worker) ·
`1` = Multi (N workers)

**Platform codes (`os`)**: `1` = iOS · `2` = Android

**Stamp thermal codes (`thermalState`)**: `0` = Nominal · `1` = Fair ·
`2` = Serious · `3` = Critical (ordinal order = severity order; `maxByOrNull
{ ordinal }` = worst)

**`DeviceHammerStamp` JSON**: `{ "elapsedMs": int, "score": long,
"thermalState": int }` — 1 Hz sampling.

## Appendix B — Exact Worker Loop Source

See `StressEngine.kt` lines ~337–382 (CPU workers) and ~250–273 (GPU worker);
reproduced verbatim in §4–5. Inner batch: **50,000 iterations** CPU,
**20,000 iterations** GPU; counters are `AtomicLong`s read by the 1 Hz sampler.

---

*Generated from static analysis of the ThermoHammer Android source tree
(`ThermoHammerAndroid/`), with cross-reference to `ThermoHammer.Api` for
server-side scoring semantics and the iOS `CpuThrottlingTest` for hashing parity.*
