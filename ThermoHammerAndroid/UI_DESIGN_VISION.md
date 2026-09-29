# ThermoHammer — Design Vision

**A creative-direction document for turning the diagnostics tool into an instrument people want to stare at.**

This is not a reskin. The app already owns a strong latent identity — a thermal torture instrument with a forge name and a monospace soul. This document defines how that identity becomes *insane aesthetics*: a coherent visual system, signature components nobody else has, motion choreography, and the details that separate "nice dark UI" from "what app is that?"

Guiding constraint: **aesthetics must never cost measurement integrity.** The UI renders while the SoC is being tortured. Every effect has a render budget and a thermal-degraded fallback.

---

## 1. North Star

> **"A foundry instrument, not a utility app."**

ThermoHammer's product truth: we put a device in a forge and measure what the metal does. The UI should feel like the control panel of that forge — part spacecraft telemetry, part laboratory oscilloscope, part master blacksmith's eye on the steel.

One-line test for every design decision: *"Does this look like it measures something real?"* Decoration that implies data we didn't measure is forbidden. Ornament that celebrates real data is the entire point.

## 2. Design Pillars

1. **Heat is the color.** The app's single accent system is a thermal ramp — cyan → amber → white-hot. Color temperature literally encodes temperature and load. When the phone heats, the UI heats.
2. **Monospace is the voice.** All data lives in a tabular mono face. Labels are small, letterspaced, uppercase — instrument engraving, not marketing copy.
3. **Phases are a narrative.** Pre-flight → warm-up → calibration → measured → verdict is a story arc. The UI is the narrator: each phase has its own color, motion grammar, and sound.
4. **Honesty is beautiful.** Validity flags, confidence, and "cannot verify" states are shown as instrument-grade stamps and seals — not apology text. A `NOT VERIFIED` stamp should look as considered as a `VERIFIED` seal.
5. **Glow with a budget.** Bloom is expensive on a throttled GPU. All luminous effects degrade gracefully: full glow → flat gradient → solid color.

## 3. Visual Language

### 3.1 Color — "The Forge Palette"

Dark-only. The background is not black; it's the inside of a cold forge — deep blue-green-black that lets heat tones physically read as *light*.

| Token | Hex | Role |
|---|---|---|
| `forge.0` | `#06090B` | App background — deepest layer |
| `forge.1` | `#0B1117` | Card surfaces |
| `forge.2` | `#111A21` | Raised panels, overlay cards |
| `forge.3` | `#1A2630` | Interactive elements, selected states |
| `hairline` | `#FFFFFF` @ 8% | 1 px borders — instrument face edges |
| `ink.0` | `#EAF4F4` | Primary data text |
| `ink.1` | `#9FB4BC` | Secondary text |
| `ink.2` | `#5A6B73` | Engraved labels, annotations |

**The Thermal Ramp (primary accent — continuous, not stepped):**

```
COLD        ACTIVE        STRESSED       CRITICAL      WHITE-HOT
#3DE8C2  →  #9EE86A   →   #FFB545    →   #FF6A3D   →   #FF3D5E  →  #FFE9D6
 teal        green         amber         ember         alarm        molten
```

The ramp maps to *delivered capacity* (100% = teal, degrading toward ember/alarm) AND to thermal zones simultaneously — it is the app's one true signature. Anywhere the old UI used green/orange/red jumps, the new UI uses position on this ramp.

**Phase accents** — each test phase owns a hue so a glance at the status bar or chart tells you where you are:

| Phase | Accent | Hex |
|---|---|---|
| Pre-flight | signal blue | `#4A9EFF` |
| Warm-up | dormant steel | `#5A6B73` (desaturated, 60% alpha) |
| Calibration | arc violet | `#9D7BFF` |
| Measured | forge teal | `#3DE8C2` |
| Verdict | thermal ramp position of result | dynamic |

**Thermal-state tokens** (system status reporting, unchanged semantics):

`Nominal #3DE8C2 · Fair #9EE86A · Serious #FFB545 · Critical #FF3D5E`

### 3.2 Typography — two voices only

| Voice | Face | Usage |
|---|---|---|
| **Display** | Space Grotesk (or Clash Display) | Hero numerals, verdicts, screen titles. Geometric, slightly condensed, cold. |
| **Instrument** | JetBrains Mono | Everything else. Tabular figures (`tnum`), all data, all labels. |

Scale (compact — instruments whisper):

```
HERO      56–72sp  Space Grotesk Medium    verdict numerals
TITLE     13sp     JetBrains Mono Black    +8% tracking, uppercase — engraved panel titles
LABEL     10sp     JetBrains Mono Bold     +12% tracking, uppercase — field labels
DATA      13–15sp  JetBrains Mono Medium   readings, table rows
ANNOTATION 8–9sp   JetBrains Mono          +10% tracking — technical footnotes ("src: scaling_max_freq")
```

Rules: numbers are always tabular. Labels are always uppercase-spaced. No rounded friendly fonts. `°C`, GHz, % symbols at 70% size of their numerals.

### 3.3 Spatial system

- 4 dp grid. Cards: 20 dp radius, `forge.1`, 1 px `hairline` border, 16–20 dp padding.
- **Corner ticks**: every primary panel gets 8 dp L-shaped corner marks (like instrument bezels / camera viewfinders) instead of full borders — instantly "precision device."
- Generous dead space. Instrument panels breathe; cramped = cheap.
- Cards never touch screen edge: 16 dp page margin, 12–16 dp gutters between cards.

### 3.4 Materials & light

- **Subtle grain**: 2–3% monochrome noise overlay on `forge.0` — kills banding, adds filmic depth.
- **Inner glow, not drop shadows**: elevation is expressed as a soft top inner-light + darker bottom edge (like machined metal), never Android-style casts.
- **Glass overlays**: dialogs/overlays sit on `forge.2` @ 82% + backdrop blur (RenderEffect, 20–30 px) + hairline. Blur disabled in degraded mode.
- **Bloom**: accent-colored radial glow behind active elements — implemented as pre-rendered gradient sprites, not real-time shaders during measurement.

### 3.5 Iconography

Minimal geometric glyphs, 1.5 px stroke, square caps — think aviation symbology: `◇` throttle onset, `▲` peak, `◆` calibration marker, `✓/⚠/⛔` for gates, `⌖` for verified seal. No emoji in instrument surfaces; emoji allowed only in casual empty states.

---

## 4. The Narrative — phases as UI story

The test is a five-act play. The UI acts it out.

```
ACT I   PRE-FLIGHT     "Clearing the pad"      — checklist scan, blue
ACT II  WARM-UP        "Waking the metal"      — dim steel, slow breathing pulse
ACT III CALIBRATION    "Striking the arc"      — violet, fast lock-in animation
ACT IV  MEASURED RUN   "In the forge"          — teal→ember live ramp, the chart hero
ACT V   VERDICT        "The reading"           — scorecard stamp, reveal choreography
```

A persistent **phase rail** — a thin horizontal strip above the main content showing `PRE-FLIGHT ▸ WARM-UP ▸ CALIBRATION ▸ MEASURED ▸ VERDICT` with the current segment lit — keeps the user oriented. This single element makes the app feel designed, because it exposes the honest structure of the methodology.

## 5. Signature Components

These are the components that become *the ThermoHammer look*. If we build nothing else custom, build these.

### 5.1 The Thermal Reactor (hero gauge)

Replaces the plain % readout. A 220 dp concentric-ring instrument:

```
              ╭───────────────────────╮
            ╱    · · · · · · · · ·      ╲     outer ticks = per-cluster freq
           │    ╭─────────────────╮      │    ratios (live dashes)
           │   │                   │     │
           │   │     87.4%         │     │   inner numeral = delivered vs
           │   │  DELIVERED CAP     │     │   baseline (ticker-animated)
           │   │   ◇ onset 02:14    │     │
           │    ╰─────────────────╯      │   middle ring = thermal headroom
            ╲     THERMAL_STATUS: FAIR  ╱     (fills/drains)
              ╰───────────────────────╯
```

- Outer ring: N tick segments, one per detected cluster, length = current/max freq — clusters visibly shrink as caps drop.
- Middle ring: `getThermalHeadroom()` arc — the kernel's own forecast, draining like coolant.
- Center: delivered-capacity numeral on the thermal ramp + throttle-onset diamond marker once detected.
- During WARM-UP/CALIBRATION the reactor is at 40% alpha with a slow scanline sweep — "instrument arming."
- Idle state: reactor rendered as wireframe outline only (`hairline` alpha) — dormant.

### 5.2 The Throughput Ribbon (chart)

The stability chart becomes the emotional centerpiece:

- **Ribbon, not line**: throughput drawn as a filled band with vertical thickness = per-cluster spread (max-min worker ratio). Wide ribbon = uneven cluster behavior (migration suspicion); thin = uniform.
- **Phase bands behind data**: warmup gray, calibration violet, measured clear. The baseline renders as a dashed `potential` line at 100% — the void between potential and ribbon IS the throttle loss, shaded in a heat-gradient underfill that intensifies with depth of loss.
- **Throttle onset**: a pulsing `◇` marker + the curve region after onset gets a subtle ember tint — the chart literally scorches.
- **Attribution coloring**: the ribbon's edge stroke takes the color of the dominant attribution (teal = clean, blue-gray = contention/interference, ember = thermal cap) — the *why* is visible without opening a legend.
- X-axis: hairline with sparse engraved ticks; thermal events as small diamond notches on the axis.
- Draws left-to-right live at 60 fps (precomputed path, incremental invalidation only).

### 5.3 Core Constellation (worker meters)

Current 4-column ring grid is fine data, weak form. Replace with a **cluster-aware constellation**:

- Workers grouped by actual cpufreq topology: cluster cards labeled `LITTLE ×4` / `BIG ×3` / `PRIME ×1` with the cluster's max MHz engraved.
- Each worker = a small radial gauge whose arc is its throughput vs its own calibration baseline, colored by ramp. Inactive workers render as faint wireframes.
- No affinity claimed — a footnote in 8sp mono: `scheduler-managed, not pinned`. Honesty visible in the design itself.
- In SINGLE mode: the constellation collapses to one large gauge — dramatic by contrast.

### 5.4 The Thermal Horizon (sensor strip)

Current temperature list → a horizontal "horizon" strip:

- Left: SoC temp as a large numeral on the ramp. Right of it, a minimal sparkline of the run's temp history.
- Below: a segmented strip of every thermal zone as tiny labeled cells (`cpu-0 51.2°` …), each cell tinted by its own position on a muted thermal ramp — a sensor-map feel, like engine monitoring in aviation.
- Skin temp & battery temp get their own engraved chips; battery current (µA→mA) displayed as a live "draw" readout with perf/W beneath it when available — else an engraved `n/a` (honest gaps, no fake data).

### 5.5 Gate Checklist (pre-flight overlay)

Redesigned as a **launch sequence**:

- Each gate is a checklist row with a status LED (hollow → checking spin → solid pass/fail).
- While `runPreflight()` measures background load (3 s), a fine scanline sweeps the panel and rows resolve one by one, staggered 80 ms — the check feels *performed*, not instant.
- Blocking failures = `⛔` red LED + reason; advisory fails = amber. PROCEED button stays locked (`disabled, 15% alpha`) until `canRun`.
- A final line in 8sp: `warm start / interference / missing telemetry will be flagged on the verdict`.

### 5.6 The Verdict Card (summary overlay)

Completion = a stamp of authority:

- Big `DELIVERED CAPACITY` numeral (Space Grotesk 64sp) count-up animated on the ramp color of the result.
- Secondary row: `SUSTAINED` · `ONSET` · `MIN SUSTAINED` as engraved data columns.
- **The Seal**: a circular `VERIFIED` ring-stamp (teal, slight rotation, stamped-in with a scale+fade bounce) — or `FLAGGED`/`NOT VERIFIED` rectangular warning stamp in amber/red listing which validity bits fired (`WARM_STARTED`, `INTERFERENCE`, …). Both are *designed objects*, not text.
- Confidence renders as a 5-segment gauge `▮▮▮▮▯`.
- Battery/temperature deltas as small delta-columns `+4.2°C` `−3%`.
- CTA hierarchy: `SUBMIT TO LEADERBOARD` = solid ramp button; `SAVE PENDING` = ghost; dismiss = plain text.

### 5.7 Leaderboard

- Top-3 **podium**: rank 1 device card enlarged with the molten `#FFE9D6` accent; ranks 2–3 ember/teal.
- Each row: rank numeral (mono black), device name, cluster topology string engraved under it (`4×1.8G | 3×2.4G | 1×2.9G`), capacity bar on the ramp, `VERIFIED` seal glyph inline.
- Own-device rows get a `forge.3` highlight + left accent bar.
- Pending-results section styled as "cold storage" — desaturated, queued, upload affordance per row.
- Comparison mode: two ribbons overlaid (one teal, one violet), shared baseline dashed line, delta annotations at max-divergence points.

---

## 6. Motion System

Motion is the second signature. Grammar: **instruments respond, they don't bounce.**

| Class | Duration | Curve | Usage |
|---|---|---|---|
| Micro | 120 ms | linear-out | LED states, selection, ticks |
| State | 280 ms | `emphasized` decelerate | panels, chips, meter arcs |
| Phase | 450 ms | custom spring (low bounce) | phase transitions, reactor arm |
| Reveal | 700 ms | staged cascade | verdict card (numeral → seal → rows) |

Signature motions:

- **Number ticker**: every major numeral rolls to its value on change (implement via `AnimatedContent` digit roll or canvas lerp — but *lerped*, not slot-machine).
- **Phase wipe**: on phase change, a thin vertical scanline sweeps left→right across the screen once and the new phase accent colors the phase rail segment. One sweep, 450 ms, never looping.
- **Throttle pulse**: when onset is confirmed, the whole screen edge glows ember for a single heartbeat (two pulses, 600 ms), then settles — the event feels discovered, not nagged.
- **Verdict stamp**: seal scales 1.4→1.0 with slight overshoot + a soft "thunk" haptic.
- **Idle breathing**: reactor wireframe breathes at 8% alpha oscillation, 4 s period — asleep but alive.
- All loops pause when data doesn't change; nothing animates purely decoratively during MEASURED (render budget).

## 7. Haptics & Sound

- `tick` (light `View.performHapticFeedback CLOCK_TICK`-equivalent) on every confirmed stamp — subtle, satisfying "instrument counting".
- `heavy thud` on throttle onset confirmation.
- `success triplet` on VERIFIED verdict; `double low` on FLAGGED.
- Optional sound design: near-silent analog clicks (12–15 dB below UI norm), arc-strike "thoom" at calibration, foundry-room ambient drone at −30 dB during measured phase. Off by default; toggle in settings — but the *option* is what makes it feel premium.

## 8. Screen-by-screen

### 8.1 Diagnostics (idle)

```
┌────────────────────────────────────────────┐
│ ⌖ THERMOHAMMER          ● NOMINAL  34.2°C  │  header: brand + live status
│                                            │
│        ╭───── dormant reactor ─────╮       │  wireframe breathing
│        │       STANDBY            │       │
│        ╰──────────────────────────╯       │
│                                            │
│  PHASE RAIL  ○──○──○──○──○                 │
│                                            │
│  ┌ DURATION ──────┐  ┌ THREADS ─────────┐  │  segmented pickers, mono
│  │ 5m │15m │30m   │  │ 1 │ MULTI        │  │
│  └────────────────┘  └──────────────────┘  │
│                                            │
│  ┌ THERMAL HORIZON ────────────────────┐   │  zones map (always live)
│  │ 51.2° ▁▂▃▅▃   cpu-0·51  gpu·44 …    │   │
│  └─────────────────────────────────────┘   │
│                                            │
│        ▸ INITIATE STRESS TEST ◂            │  solid ramp edge→edge
└────────────────────────────────────────────┘
```

### 8.2 Running

- Phase rail segment lit + pulsing; header shows live countdown in measured, phase name otherwise.
- Reactor active (§5.1), ribbon chart drawing live (§5.2), constellation below (§5.3).
- A persistent **attribution chip** under the reactor: `CLEAN RUN` / `CAP LIMIT` / `CONTENTION` with its accent — the honest verdict-in-progress.
- STOP button = ghost-bordered (destructive but not red until held — hold-to-stop with 600 ms press-and-hold progress ring prevents accidental kills).

### 8.3 Verdict & overlays

Verdict card per §5.6. All overlays = glass cards with corner ticks, entered by scale 0.96→1.0 + fade, dismissed by the inverse.

### 8.4 Connection-lost / empty states

Casual layer allowed: friendly mono illustration, one emoji, one line. Instruments are for data; empty states are for humans.

## 9. The "Insane" Moments — the five things people screenshot

1. **The reactor heating**: as the run cooks, the center numeral physically migrates up the thermal ramp — teal → ember — and the ring's glow deepens. You *watch* your phone get hot in UI color temperature.
2. **The scorch**: post-onset ribbon region tinted ember — the chart literally burns behind the curve.
3. **The stamp**: verdict seal punches in with a thud — VERIFIED in teal, FLAGGED in warning-frame amber with the bit-flag names engraved.
4. **The checklist scan**: pre-flight rows resolving one-by-one under a sweep line — launch-day energy.
5. **The constellation by topology**: worker gauges grouped into actual LITTLE/BIG/PRIME clusters — nobody else draws the truth of the SoC like this.

## 10. Accessibility & the Honest-Instrument Rules

- **Color is never the only channel**: ramp position always paired with numeral or glyph. Thermal states have distinct shapes (`● ▲ ◆ ■` system option).
- Contrast: all data text ≥ 4.5:1 on its surface; engraved annotations may go to 3:1 (supplementary only).
- **Reduced-motion mode** (respect `animatorDurationScale` + system setting): tickers → fades, pulses → single flash, scanlines → none.
- Min touch target 48 dp; gates and CTAs ≥ 44 sp readable at arm's length.
- **Thermal degraded mode**: when `thermalStatus ≥ SEVERE`, the UI sheds cost — bloom sprites off, ribbon antialiasing down, ticker → direct set. The app must never be the reason the phone throttles; that's the founding ethic of this design system.
- Honesty audit: every number on screen traces to a `StressProbes` source or is labeled `n/a`. No decorative "fake data."

## 11. Compose Implementation Notes

- **Reactor & constellation**: single `Canvas` per component, `drawArc`/`drawIntoCanvas`; cluster geometry computed once in `remember`. Glow = pre-baked radial `Brush` sprites (no `RenderNode` blur in-run).
- **Ribbon chart**: path built incrementally per stamp into a retained `Path`; phase bands + underfill gradient are static `Brush` lookups keyed by worst-ratio — cheap.
- **Phase rail**: `Row` of segments, `animateColorAsState` per segment + one shared `InfiniteTransition` for the active pulse — pause when not running.
- **Scanline sweep**: a 1 dp animated offset in `drawBehind` during pre-flight only.
- **Glass overlays**: `Modifier.blur` on background snapshot or `RenderEffect` — flag-degrade to 92% opaque card when device reports `SEVERE+`.
- **Grain**: static tiled noise bitmap at 3% alpha in `drawBehind` — zero GPU cost.
- **Typography**: bundle JetBrains Mono + Space Grotesk via downloadable fonts (`GoogleFont`), `fontFamily` tokens in a `ThermoType.kt` file.
- **Haptics**: `LocalView.current.performHapticFeedback()` — `CLOCK_TICK` stamps, `LONG_PRESS` onset, `CONFIRM`/`REJECT` verdict (API 30+ constants, guarded).
- **Tokens**: single `ThermoTheme.kt` — `object Forge`, `object Ramp` (with `ramp(t: Float): Color` lerp), `object Type` — so the system is enforceable.
- **Perf guard**: cap Compose recompositions during MEASURED to stamp-tick (1 Hz) for data panels; only the ribbon ticker may run at frame rate — and even it degrades.

## 12. Anti-patterns — what kills the aesthetic

- ❌ Emoji in instrument surfaces (✓⚠⛔ glyph set replaces them)
- ❌ Gradient text on data (ramp is for *state*, not decoration of numbers)
- ❌ Skeleton shimmer on a screen that has real telemetry
- ❌ Card-in-card nesting (max 2 surfaces deep)
- ❌ Rainbow of unrelated accents (one ramp + phase hues only)
- ❌ Animation loops that don't encode data (except idle breathing)
- ❌ Claiming "GPU" or "CORE n" we don't measure — typography must say WORKER

---

## Appendix A — Token quick reference

```
Colors   forge.0 #06090B · forge.1 #0B1117 · forge.2 #111A21 · forge.3 #1A2630
         ink.0 #EAF4F4 · ink.1 #9FB4BC · ink.2 #5A6B73
         ramp: #3DE8C2 → #9EE86A → #FFB545 → #FF6A3D → #FF3D5E → #FFE9D6
         phases: blue #4A9EFF · steel #5A6B73 · violet #9D7BFF · teal #3DE8C2
Type     Display: Space Grotesk Med 56–72 · Title: JB Mono Black 13 +8%
         Label: JB Mono Bold 10 +12% · Data: JB Mono Med 13–15 · Annot: 8–9 +10%
Space    4dp grid · 20dp card radius · 16dp margins · 8dp corner ticks
Motion   120 micro / 280 state / 450 phase / 700 reveal · ramp-lerped numerals
Haptics  stamp tick · onset thud · verdict confirm/reject
```

*The forge is dark. The metal glows. The instrument tells the truth beautifully.*
