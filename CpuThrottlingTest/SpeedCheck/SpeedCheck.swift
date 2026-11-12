import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// FIND MY LOST SPEED — the 30-second CPU check's arithmetic, what it says
// about the result, and the before/after comparison. Pure logic, Foundation
// only (SpeedCheckRunner measures, LostSpeedView shows). The same thresholds
// and words as the Android and Windows apps; iOS and the app on a Mac have
// their own settings paths.
// ─────────────────────────────────────────────────────────────────────────────

enum SpeedCheck {
    static let seconds = 30
    /// The CPU lost this much from its burst to its sustained speed: something held it back.
    static let dropThreshold = 0.25
    /// A first measurement older than this is stale: the phone has cooled or warmed since.
    static let expiry: TimeInterval = 60 * 60

    /** One 30-second check. */
    struct Measurement: Codable, Equatable {
        var perSecond: [Double]     // work units/s in each 1-s window
        var burst: Double           // mean of seconds 1–5
        var sustained: Double       // median of seconds 11–30
        var drop: Double            // 1 − sustained / burst
        var powerSaver: Bool        // Low Power Mode on during the check
        var charging: Bool          // on the charger (never on a Mac: its battery isn't readable here)
        var thermalEnd: Int         // ProcessInfo.ThermalState raw at the end: 0 nominal … 3 critical
        var timestamp: Double       // seconds since 1970, at the end

        /// "kept 92% of its burst speed" — a sustained speed above the burst counts as all of it.
        var keptPercent: Int { Int(((1 - max(0, drop)) * 100).rounded()) }
    }

    /// Mean of seconds 1–5.
    static func burst(_ w: [Double]) -> Double {
        let s = w.prefix(5)
        return s.isEmpty ? 0 : s.reduce(0, +) / Double(s.count)
    }

    /// Median of seconds 11–30 (of what there is, for a shorter run).
    static func sustained(_ w: [Double]) -> Double {
        let tail = w.count > 10 ? Array(w[10..<min(w.count, seconds)]) : w
        return StressCurve.median(tail)
    }

    static func drop(burst: Double, sustained: Double) -> Double { burst > 0 ? 1 - sustained / burst : 0 }

    static func measurement(_ perSecond: [Double], powerSaver: Bool, charging: Bool, thermalEnd: Int, timestamp: Double) -> Measurement {
        let b = burst(perSecond), s = sustained(perSecond)
        return Measurement(perSecond: perSecond, burst: b, sustained: s, drop: drop(burst: b, sustained: s),
                           powerSaver: powerSaver, charging: charging, thermalEnd: thermalEnd, timestamp: timestamp)
    }

    /// iOS's "light" end of the scale: nominal or fair.
    static func isCool(_ thermalState: Int) -> Bool { thermalState <= 1 }

    // MARK: diagnosis

    enum Cause: Equatable { case powerSaving, heldBackWhileCool, heat, nothingFound }

    struct Finding: Equatable {
        let cause: Cause
        let title: String
        let text: String
        var steps: String? = nil
    }

    struct Diagnosis: Equatable {
        let findings: [Finding]     // strongest first; NO LOST SPEED FOUND when nothing applies
        let note: String?           // the last benchmark's Phone Health, when it was slow

        /// Something to change and check again; false for NO LOST SPEED FOUND (the screen then just offers DONE).
        var foundCause: Bool { findings.contains { $0.cause != .nothingFound } }
    }

    /// The last benchmark's Phone Health verdict, when it said slower than the model's median.
    struct HealthNote: Equatable {
        let percentBelow: Int       // 100 − % of the median
        let model: String
    }

    /** Every cause that applies, strongest first. `mac`: the app on a Mac (its own settings paths); `noun`: "phone". */
    static func diagnose(_ m: Measurement, mac: Bool, noun: String, health: HealthNote? = nil) -> Diagnosis {
        var out: [Finding] = []
        if m.powerSaver {
            out.append(Finding(cause: .powerSaving, title: "POWER SAVING IS ON",
                               text: mac ? "System Settings › Battery › Low Power Mode — set it to Never."
                                         : "Low Power Mode caps the CPU. Settings › Battery › Low Power Mode — turn it off."))
        }
        if m.drop >= dropThreshold {
            if isCool(m.thermalEnd) {
                out.append(Finding(cause: .heldBackWhileCool, title: "HELD BACK WHILE COOL",
                                   text: "Your \(noun) slows down after a short burst even though it isn't hot — a power setting is holding it back, not heat.",
                                   steps: mac ? "System Settings › Battery › Low Power Mode — set it to Never. On Macs with Energy Mode, choose High Power."
                                              : "Settings › Battery › Low Power Mode — off. Then let the phone cool and check again."))
            } else {
                out.append(Finding(cause: .heat, title: "HEAT",
                                   text: "It slowed down because it got hot. Take it out of its case, unplug it, let it cool, and check again."))
            }
        }
        if out.isEmpty {
            out.append(Finding(cause: .nothingFound, title: "NO LOST SPEED FOUND",
                               text: "Your \(noun) kept \(m.keptPercent)% of its burst speed. Nothing is holding it back."))
        }
        let note = health.map { "Your last benchmark was \($0.percentBelow)% below the median \($0.model)." }
        return Diagnosis(findings: out, note: note)
    }

    // MARK: before / after

    enum Verdict: Equatable { case faster, slower, noChange }

    struct Comparison: Equatable {
        let ratio: Double           // sustained after / sustained before
        let verdict: Verdict
        let title: String
        let text: String
    }

    static func compare(before: Measurement, after: Measurement, noun: String) -> Comparison {
        let ratio = before.sustained > 0 ? after.sustained / before.sustained : 1
        if ratio >= 1.10 {
            return Comparison(ratio: ratio, verdict: .faster, title: String(format: "%.2f× FASTER", ratio),
                              text: "You got back \(Int(((ratio - 1) * 100).rounded()))% speed.")
        }
        if ratio <= 0.90 {
            return Comparison(ratio: ratio, verdict: .slower, title: "SLOWER", text: "It's slower now — change the setting back.")
        }
        return Comparison(ratio: ratio, verdict: .noChange, title: "NO CHANGE", text: "That setting wasn't holding your \(noun) back.")
    }

    // MARK: the stress verdict's hint

    /// A stress run that lost speed while cool: under 80 % kept, and still nominal/fair when it began to throttle —
    /// likely a power setting, which the check can find. `stamps`: (ms into the run, thermal state).
    static func slowedWhileCool(capacity: Double, onsetSec: Int?, stamps: [(elapsedMs: Int, thermalState: Int)]) -> Bool {
        guard capacity < 80, let onset = onsetSec,
              let atOnset = stamps.first(where: { $0.elapsedMs / 1000 >= onset }) ?? stamps.last else { return false }
        return isCool(atOnset.thermalState)
    }

    /// The saved first check, if it isn't stale yet.
    static func fresh(_ m: Measurement?, now: Double) -> Measurement? {
        guard let m, now - m.timestamp < expiry else { return nil }
        return m
    }
}
