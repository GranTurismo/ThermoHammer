import Foundation
import SwiftUI
import Combine
import UIKit
import Darwin.Mach

enum TestDuration: Hashable {
    case minutes5
    case minutes15
    case minutes30

    var timeInterval: TimeInterval? {
        switch self {
        case .minutes5: return 5 * 60
        case .minutes15: return 15 * 60
        case .minutes30: return 30 * 60
        }
    }

    var displayName: String {
        switch self {
        case .minutes5: return "5 Min"
        case .minutes15: return "15 Min"
        case .minutes30: return "30 Min"
        }
    }
}

enum StressThreadingType: Int, Codable, Hashable, CaseIterable {
    case single = 0
    case multi = 1

    var displayName: String {
        switch self {
        case .single: return "1 Thread"
        case .multi: return "Multi Thread"
        }
    }
}

// MARK: - Run phases
// A test is a sequence of phases, not a single loop:
// PRE-FLIGHT gates → COOLDOWN → WARM-UP (unrecorded) → CALIBRATION (boost window) → MEASURED.

enum RunPhase: String {
    case idle = "IDLE"
    case cooldown = "COOLDOWN"
    case warmup = "WARM-UP"
    case calibration = "CALIBRATING"
    case measured = "STRESS ACTIVE"
}

// MARK: - Per-sample throttling attribution
// iOS does not expose cpufreq/thermal zones, so the honest evidence set is:
// thermalState escalations, worker scheduled-time fraction, and ambient system load.

enum Attribution: Int {
    case none = 0
    case thermal = 1      // thermalState escalated — system DVFS engaged
    case contention = 2   // workers starved by external load — NOT thermal throttling
    case unknown = 3      // cannot attribute from available telemetry

    var isThermal: Bool { self == .thermal }
}

// MARK: - Validity bitfield (persisted with every run)

enum ValidityFlag {
    static let warmStarted      = 1 << 0  // thermalState already elevated during calibration
    static let interference     = 1 << 1  // >10% of samples starved by other load
    static let powerEvent       = 1 << 2  // charger state changed mid-run
    static let freqMissing      = 1 << 3  // cpufreq telemetry unavailable — always true on iOS
    static let shortRun         = 1 << 4  // ended before 90% of target duration
    static let envCapped        = 1 << 5  // Low Power Mode active during run
    static let suspiciousBoost  = 1 << 6  // huge peak with no state change — possible benchmark whitelisting
    static let cooldownTimeout  = 1 << 7  // cooldown ended without reaching nominal thermal state
    static let baselineExceeded = 1 << 8  // measured run sustained >105% of calibration baseline
}

// MARK: - Per-stamp environment bitfield

enum EnvFlag {
    static let charging        = 1 << 0
    static let lowPowerMode    = 1 << 1
    static let thermalElevated = 1 << 2
}

// MARK: - Stamp v2 — the unit of recorded truth (emitted ~1 Hz during MEASURED)

struct StampV2: Codable {
    let tNs: Int64                    // monotonic ns since measured-run start
    let windowNs: Int64               // actual measured window length
    let ipsTotal: Int64               // true iterations/sec across all workers
    let ipsPerWorker: [Int64]         // per-worker throughput
    let schedFracAvg: Double          // mean worker scheduled-time fraction
    let thermalState: Int             // ProcessInfo.ThermalState raw 0..3
    let systemLoad: Double            // host-wide CPU busy fraction 0..1
    let attribution: Int              // Attribution raw
    let envFlags: Int                 // EnvFlag bitmask

    var elapsedMs: Int { Int(tNs / 1_000_000) }
}

// MARK: - Pre-flight gates

struct GateResult {
    let id: String
    let title: String
    let blocking: Bool
    var passed: Bool
    var detail: String
}

struct PreflightReport {
    var gates: [GateResult]
    var canRun: Bool
    var validityHints: Int
}

// MARK: - CPU cluster topology (sysctl hw.perflevel*)

struct ClusterTopology {
    let clusters: [[Int]]             // worker indices per perf level
    let names: [String]               // "PERF" / "EFF"

    var totalCores: Int { clusters.reduce(0) { $0 + $1.count } }

    var slowestClusterIdx: Int { names.firstIndex(where: { !$0.uppercased().contains("PERF") }) ?? (names.count - 1) }

    func describe() -> String {
        clusters.enumerated().map { "\($1.count)x\(names[$0])" }.joined(separator: "|")
    }
}

// MARK: - Final scorecard — replaces the single "stability" number

struct Scorecard {
    var baselineIps: Double = 0        // p95 of calibration-window IPS
    var sustainedRatio: Double = 100   // median(last quartile) / baseline
    var deliveredCapacity: Double = 100 // AUC: Σ(ips·dt) / (baseline·T)
    var minSustained: Double? = nil    // min 5s-mean over thermal-attributed samples
    var throttleOnsetSec: Int? = nil   // first <90% sustained ≥3s
    var timeInThrottlePct: Double = 0  // % of stamps with thermal attribution
    var confidence: Int = 100
    var validityFlags: Int = 0
    var warmStarted: Bool = false

    var isVerified: Bool { validityFlags == 0 }
}

// MARK: - Live state published to UI

struct StressState {
    var phase: RunPhase = .idle
    var elapsed: TimeInterval = 0
    var duration: TimeInterval = 0
    var stamps: [StampV2] = []
    var preRunIps: [(phase: RunPhase, ips: Double)] = []
    var liveIps: Double = 0
    var baselineIps: Double = 0
    var attribution: Attribution = .none
    var workerRatios: [Double] = []      // per-worker IPS / per-worker baseline
    var thermalState: ProcessInfo.ThermalState = .nominal
    var systemLoad: Double = 0
    var cooldownFromState: ProcessInfo.ThermalState = .nominal
    var cooldownElapsed: TimeInterval = 0
    var scorecard: Scorecard? = nil
}

// MARK: - SafeCounter

final class SafeCounter {
    private var lock = os_unfair_lock_s()
    private var value: UInt64 = 0
    func add(_ amount: UInt64) {
        os_unfair_lock_lock(&lock); value &+= amount; os_unfair_lock_unlock(&lock)
    }
    func get() -> UInt64 {
        os_unfair_lock_lock(&lock); defer { os_unfair_lock_unlock(&lock) }; return value
    }
    func reset() {
        os_unfair_lock_lock(&lock); value = 0; os_unfair_lock_unlock(&lock)
    }
}

// MARK: - Mach helpers

enum MachProbe {
    /// Host-wide CPU busy fraction between two samples (includes our own workers).
    static func hostCpuTicks() -> (user: UInt64, system: UInt64, idle: UInt64, nice: UInt64)? {
        var count = mach_msg_type_number_t(MemoryLayout<processor_info_array_t>.stride / MemoryLayout<integer_t>.stride)
        var info: processor_info_array_t?
        var numProcessors = natural_t(0)
        let kr = host_processor_info(mach_host_self(), PROCESSOR_CPU_LOAD_INFO, &numProcessors, &info, &count)
        guard kr == KERN_SUCCESS, let array = info else { return nil }
        var user: UInt64 = 0, system: UInt64 = 0, idle: UInt64 = 0, nice: UInt64 = 0
        let cpuCount = Int(numProcessors)
        for i in 0..<cpuCount {
            let base = Int(CPU_STATE_MAX) * i
            user &+= UInt64(array[base + Int(CPU_STATE_USER)])
            system &+= UInt64(array[base + Int(CPU_STATE_SYSTEM)])
            idle &+= UInt64(array[base + Int(CPU_STATE_IDLE)])
            nice &+= UInt64(array[base + Int(CPU_STATE_NICE)])
        }
        let size = vm_size_t(count) * vm_size_t(MemoryLayout<integer_t>.stride)
        vm_deallocate(mach_task_self_, vm_address_t(bitPattern: array), size)
        return (user, system, idle, nice)
    }

    static func busyFraction(between a: (UInt64, UInt64, UInt64, UInt64), and b: (UInt64, UInt64, UInt64, UInt64)) -> Double {
        let busy = Double((b.0 &- a.0) &+ (b.1 &- a.1) &+ (b.3 &- a.3))
        let total = busy + Double(b.2 &- a.2)
        return total > 0 ? busy / total : 0
    }

    /// Scheduled-CPU fraction of our worker threads since last sample.
    static func threadCpuTimes(_ threads: [thread_act_t]) -> [UInt64] {
        threads.map { th in
            var info = thread_basic_info_data_t()
            var count = mach_msg_type_number_t(THREAD_INFO_MAX)
            let kr = withUnsafeMutablePointer(to: &info) {
                $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                    thread_info(th, thread_flavor_t(THREAD_BASIC_INFO), $0, &count)
                }
            }
            guard kr == KERN_SUCCESS else { return 0 }
            // total user+system time in µs
            return UInt64(info.user_time.seconds) &* 1_000_000 &+ UInt64(info.user_time.microseconds)
                 &+ UInt64(info.system_time.seconds) &* 1_000_000 &+ UInt64(info.system_time.microseconds)
        }
    }
}

// MARK: - StressEngine

final class StressEngine: ObservableObject {
    static let shared = StressEngine()

    // Published UI state
    @Published var state = StressState()
    @Published var isRunning = false
    @Published var wasCancelledDueToBackground = false
    @Published var wasCompleted = false
    @Published var abortedByUser = false
    @Published var testThreadingType: StressThreadingType = .multi

    var testDuration: TestDuration = .minutes5
    let coreCount: Int
    let topology: ClusterTopology

    private var counters: [SafeCounter] = []
    private var workerMachThreads: [thread_act_t] = []
    private var keepAlive = false

    private var loopTask: Task<Void, Never>? = nil
    private var thermalCancel: AnyCancellable?
    private var batteryCancel: AnyCancellable?
    private var backgroundCancel: AnyCancellable?

    // phase bookkeeping
    private var prevCounters: [UInt64] = []
    private var prevWorkerTimes: [UInt64] = []
    private var prevHostTicks: (UInt64, UInt64, UInt64, UInt64)? = nil
    private var measuredStartNs: UInt64 = 0
    private var calIps: [Double] = []
    private var workerBaselines: [Double] = []
    private var displayBaselines: [Double] = []
    private var chargingAtStart = false
    private var prevCharging = false
    private var powerEventSeen = false
    private var thermalEscalatedAtStart = false
    private var lastThermalChangeNs: UInt64 = 0
    private var currentAttribution: Attribution = .none

    // cooldown
    private static let cooldownMaxS: TimeInterval = 120
    private static let cooldownStallS: TimeInterval = 45
    private static let warmupS: TimeInterval = 12
    private static let calibrationS: TimeInterval = 20

    private init() {
        let n = ProcessInfo.processInfo.processorCount
        self.coreCount = n
        self.topology = StressEngine.readTopology(coreCount: n)
        for _ in 0..<n { counters.append(SafeCounter()); prevCounters.append(0); prevWorkerTimes.append(0) }
        workerBaselines = Array(repeating: 1, count: n)
        UIDevice.current.isBatteryMonitoringEnabled = true
        setupMonitors()
    }

    // MARK: sysctl topology

    private static func sysctlString(_ key: String) -> String? {
        var size = 0
        sysctlbyname(key, nil, &size, nil, 0)
        guard size > 0 else { return nil }
        var buf = [CChar](repeating: 0, count: size)
        sysctlbyname(key, &buf, &size, nil, 0)
        return String(cString: buf)
    }
    private static func sysctlInt(_ key: String) -> Int? {
        var v = 0; var size = MemoryLayout<Int>.stride
        return sysctlbyname(key, &v, &size, nil, 0) == 0 ? v : nil
    }

    static func readTopology(coreCount: Int) -> ClusterTopology {
        let levels = sysctlInt("hw.nperflevels") ?? 1
        var names: [String] = []
        var counts: [Int] = []
        for i in 0..<levels {
            let name = sysctlString("hw.perflevel\(i).name") ?? "LEVEL\(i)"
            let cnt = sysctlInt("hw.perflevel\(i).physicalcpu") ?? 0
            names.append(name.uppercased().contains("PERF") ? "PERF" : "EFF")
            counts.append(cnt)
        }
        var clusters: [[Int]] = []
        var idx = 0
        for c in counts {
            clusters.append(Array(idx..<(idx + c)))
            idx += c
        }
        if clusters.reduce(0, { $0 + $1.count }) != coreCount {
            return ClusterTopology(clusters: [Array(0..<coreCount)], names: ["CPU"])
        }
        return ClusterTopology(clusters: clusters, names: names)
    }

    static func socModel() -> String {
        sysctlString("hw.model") ?? sysctlString("machdep.cpu.brand_string") ?? "Apple SoC"
    }

    // MARK: monitors

    private func setupMonitors() {
        state.thermalState = ProcessInfo.processInfo.thermalState
        thermalCancel = NotificationCenter.default
            .publisher(for: ProcessInfo.thermalStateDidChangeNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self else { return }
                let s = ProcessInfo.processInfo.thermalState
                self.state.thermalState = s
                self.lastThermalChangeNs = DispatchTime.now().uptimeNanoseconds
            }
        batteryCancel = NotificationCenter.default
            .publisher(for: UIDevice.batteryStateDidChangeNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self, self.isRunning else { return }
                let charging = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
                if self.state.phase == .measured, charging != self.prevCharging { self.powerEventSeen = true }
                self.prevCharging = charging
            }
        backgroundCancel = NotificationCenter.default
            .publisher(for: UIApplication.didEnterBackgroundNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self, self.isRunning else { return }
                self.wasCancelledDueToBackground = true
                self.stopTest()
            }
    }

    // MARK: preflight

    @MainActor
    private func batterySnapshot() -> (state: UIDevice.BatteryState, level: Float, charging: Bool) {
        let s = UIDevice.current.batteryState
        return (s, UIDevice.current.batteryLevel,
                s == .charging || s == .full)
    }

    /// Runs off-main except the UIKit battery reads (bounced to main).
    func runPreflight() async -> PreflightReport {
        var gates: [GateResult] = []
        var hints = 0

        // Battery state / charger (UIKit — main thread)
        let batt = await batterySnapshot()
        let charging = batt.charging
        gates.append(GateResult(id: "charger", title: "CHARGER", blocking: true,
                                passed: !charging,
                                detail: charging ? "UNPLUG — charging heat skews the run" : "on battery"))
        // Battery level
        let level = batt.level
        let battOK = level < 0 || level >= 0.20
        gates.append(GateResult(id: "battery", title: "BATTERY", blocking: true,
                                passed: battOK,
                                detail: level < 0 ? "level unknown" : "\(Int(level * 100))% \(battOK ? "" : "— charge first")"))
        // Thermal state
        let ts = ProcessInfo.processInfo.thermalState
        // Informational only — cooldown handles elevated states, never blocks.
        gates.append(GateResult(id: "thermal", title: "THERMAL", blocking: false,
                                passed: true,
                                detail: ts == .nominal ? "nominal" : "\(ts.name.lowercased()) — cooldown will run first"))
        if ts != .nominal { hints |= ValidityFlag.warmStarted }
        // Low power mode
        let lpm = ProcessInfo.processInfo.isLowPowerModeEnabled
        gates.append(GateResult(id: "lowpower", title: "LOW POWER MODE", blocking: false,
                                passed: !lpm,
                                detail: lpm ? "active — run flagged ENV CAPPED" : "off"))
        if lpm { hints |= ValidityFlag.envCapped }
        // Ambient CPU load (workers not yet started → everything is external)
        var loadOK = true
        var loadPct = 0
        if let a = MachProbe.hostCpuTicks() {
            try? await Task.sleep(nanoseconds: 800_000_000)
            if let b = MachProbe.hostCpuTicks() {
                let f = MachProbe.busyFraction(between: a, and: b)
                loadPct = Int((f * 100).rounded())
                loadOK = f < 0.35
            }
        }
        gates.append(GateResult(id: "bgload", title: "BACKGROUND LOAD", blocking: true,
                                passed: loadOK,
                                detail: loadOK ? "\(loadPct)% ambient" : "\(loadPct)% busy — close other apps"))

        return PreflightReport(gates: gates,
                               canRun: gates.allSatisfy { !$0.blocking || $0.passed },
                               validityHints: hints)
    }

    // MARK: lifecycle

    func startTest(duration: TestDuration, threadingType: StressThreadingType = .multi) {
        guard !isRunning else { return }
        testDuration = duration
        testThreadingType = threadingType
        wasCancelledDueToBackground = false
        wasCompleted = false
        abortedByUser = false
        powerEventSeen = false
        thermalEscalatedAtStart = false
        calIps = []
        workerBaselines = Array(repeating: 1, count: coreCount)
        displayBaselines = Array(repeating: 1, count: coreCount)
        chargingAtStart = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
        prevCharging = chargingAtStart
        for c in counters { c.reset() }
        prevCounters = Array(repeating: 0, count: coreCount)
        prevHostTicks = nil
        measuredStartNs = 0

        state = StressState(phase: .cooldown,
                            duration: duration.timeInterval ?? 0,
                            workerRatios: Array(repeating: 0, count: coreCount),
                            thermalState: ProcessInfo.processInfo.thermalState,
                            cooldownFromState: ProcessInfo.processInfo.thermalState)

        isRunning = true
        keepAlive = true
        UIApplication.shared.isIdleTimerDisabled = true
        loopTask = Task.detached(priority: .userInitiated) { [weak self] in
            await self?.runSession()
        }
    }

    func abort() {
        abortedByUser = true
        keepAlive = false
        loopTask?.cancel()
    }

    func stopTest() {
        guard isRunning else { return }
        isRunning = false
        keepAlive = false
        loopTask?.cancel()
        DispatchQueue.main.async { UIApplication.shared.isIdleTimerDisabled = false }
        if state.phase != .idle { state.phase = .idle }
    }

    // MARK: worker

    private func startWorkers() {
        keepAlive = true
        workerMachThreads = []
        let active = testThreadingType == .single ? 1 : coreCount
        for i in 0..<active {
            let counter = counters[i]
            let t = Thread { [weak self] in
                var v1: UInt64 = 0xAAAAAAAAAAAAAAAA, v2: UInt64 = 0x5555555555555555
                var v3: UInt64 = 0x3333333333333333, v4: UInt64 = 0x7777777777777777
                var f1 = 1.0000001, f2 = 2.0000002, f3 = 3.0000003, f4 = 4.0000004
                var l1Cache = [UInt64](repeating: 0x123456789ABCDEF0, count: 4096)
                var cacheIdx = 0
                Thread.current.qualityOfService = .userInteractive
                while let self, self.keepAlive {
                    for _ in 0..<50_000 {
                        v1 = (v1 ^ (v2 &+ 7)) &* 3;  f1 = f1.addingProduct(1.0000001, 0.0000001)
                        v2 = (v2 ^ (v3 &+ 13)) &* 5; f2 = f2.addingProduct(1.0000002, 0.0000002)
                        v3 = (v3 ^ (v4 &+ 17)) &* 7; f3 = f3.addingProduct(1.0000003, 0.0000003)
                        v4 = (v4 ^ (v1 &+ 19)) &* 11; f4 = f4.addingProduct(1.0000004, 0.0000004)
                        l1Cache[cacheIdx] = l1Cache[cacheIdx] ^ v1
                        cacheIdx = (cacheIdx + 1) & 4095
                    }
                    counter.add(50_000)
                }
                let sink = v1 &+ v2 &+ v3 &+ v4 &+ UInt64(bitPattern: Int64(f1))
                if sink == 0 { print("optimizer fallback \(sink)") }
            }
            t.name = "th-worker-\(i)"
            t.qualityOfService = .userInteractive
            t.start()
        }
        // Resolve mach thread ports for our named workers — retry while they spin up
        var resolved: [thread_act_t] = []
        for _ in 0..<10 {
            resolved = StressEngine.machThreadsForWorkers()
            if !resolved.isEmpty { break }
            Thread.sleep(forTimeInterval: 0.05)
        }
        workerMachThreads = resolved
        if workerMachThreads.isEmpty { prevWorkerTimes = [] }
    }

    private static func machThreadsForWorkers() -> [thread_act_t] {
        var list: thread_act_array_t?
        var count = mach_msg_type_number_t(0)
        guard task_threads(mach_task_self_, &list, &count) == KERN_SUCCESS, let arr = list else { return [] }
        var result: [thread_act_t] = []
        for i in 0..<Int(count) {
            var ext = thread_extended_info_data_t()
            var c = mach_msg_type_number_t(THREAD_INFO_MAX)
            let kr = withUnsafeMutablePointer(to: &ext) {
                $0.withMemoryRebound(to: integer_t.self, capacity: Int(c)) {
                    thread_info(arr[i], thread_flavor_t(THREAD_EXTENDED_INFO), $0, &c)
                }
            }
            if kr == KERN_SUCCESS {
                let name = withUnsafePointer(to: &ext.pth_name) { ptr in
                    ptr.withMemoryRebound(to: CChar.self, capacity: 64) { String(cString: $0) }
                }
                if name.hasPrefix("th-worker") { result.append(arr[i]) }
            }
        }
        let size = vm_size_t(count) * vm_size_t(MemoryLayout<thread_act_t>.stride)
        vm_deallocate(mach_task_self_, vm_address_t(bitPattern: arr), size)
        return result
    }

    // MARK: session loop

    private func runSession() async {
        // ── COOLDOWN — no load, wait for nominal thermal state ──
        var cooldownTimedOut = false
        let cdStart = DispatchTime.now().uptimeNanoseconds
        var stallStart = cdStart
        var lastStateAtStart = ProcessInfo.processInfo.thermalState

        while !Task.isCancelled && keepAlive {
            let ts = ProcessInfo.processInfo.thermalState
            if ts == .nominal { break }
            if ts.rawValue < lastStateAtStart.rawValue { // improved
                lastStateAtStart = ts
                stallStart = DispatchTime.now().uptimeNanoseconds
            }
            let now = DispatchTime.now().uptimeNanoseconds
            let elapsed = TimeInterval(now - cdStart) / 1e9
            let stalled = TimeInterval(now - stallStart) / 1e9
            if elapsed >= StressEngine.cooldownMaxS || stalled >= StressEngine.cooldownStallS {
                cooldownTimedOut = true
                break
            }
            await MainActor.run { self.state.cooldownElapsed = elapsed }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
        guard keepAlive && !Task.isCancelled else { finishAbort(); return }

        // ── WORKERS ON — WARM-UP + CALIBRATION + MEASURED ──
        await MainActor.run {
            self.startWorkers()
            self.state.phase = .warmup
        }
        prevHostTicks = MachProbe.hostCpuTicks()
        prevWorkerTimes = MachProbe.threadCpuTimes(workerMachThreads)
        var prevTickNs = DispatchTime.now().uptimeNanoseconds
        let workersOnNs = prevTickNs
        var thermalAtCalStart: ProcessInfo.ThermalState? = nil
        var contentionStamps = 0
        var thermalStamps = 0
        var above105 = 0
        var envAcc: Int = 0

        var stampAccumWork: Double = 0
        var stampAccumNs: UInt64 = 0
        var stampLastNs = DispatchTime.now().uptimeNanoseconds
        var lastAttr: Attribution = .none
        var lastSched = 1.0
        var lastLoad = 0.0
        var lastTs: ProcessInfo.ThermalState = .nominal

        while keepAlive && !Task.isCancelled {
            let now = DispatchTime.now().uptimeNanoseconds
            let dt = now - prevTickNs
            prevTickNs = now

            // throughput
            var deltas: [Double] = []
            var total: Double = 0
            for i in 0..<coreCount {
                let cur = counters[i].get()
                let d = Double(cur.subtractingReportingOverflow(prevCounters[i]).partialValue)
                prevCounters[i] = cur
                deltas.append(d)
                total += d
            }
            let ips = total * 1e9 / Double(dt)
            let perWorkerIps = deltas.map { $0 * 1e9 / Double(dt) }

            // sched fraction
            let wTimes = MachProbe.threadCpuTimes(workerMachThreads)
            var schedFrac = 1.0
            if workerMachThreads.count == wTimes.count, !wTimes.isEmpty {
                var fracs: [Double] = []
                for i in 0..<wTimes.count {
                    let dtUs = Double(wTimes[i].subtractingReportingOverflow(prevWorkerTimes[i]).partialValue)
                    fracs.append(min(1, dtUs / (Double(dt) / 1000.0)))
                }
                prevWorkerTimes = wTimes
                schedFrac = fracs.reduce(0, +) / Double(fracs.count)
            }
            // ambient load
            var sysLoad = 0.0
            if let b = MachProbe.hostCpuTicks(), let a = prevHostTicks {
                sysLoad = MachProbe.busyFraction(between: a, and: b)
                prevHostTicks = b
            }
            let ts = ProcessInfo.processInfo.thermalState

            // phase machine
            let sinceWorkersOn = TimeInterval(now - workersOnNs) / 1e9
            var phase: RunPhase
            if sinceWorkersOn < StressEngine.warmupS { phase = .warmup }
            else if sinceWorkersOn < StressEngine.warmupS + StressEngine.calibrationS { phase = .calibration }
            else { phase = .measured }

            switch phase {
            case .warmup:
                await publish(phase: .warmup, ips: ips, workerIps: perWorkerIps, pre: true)
            case .calibration:
                if thermalAtCalStart == nil {
                    thermalAtCalStart = ts
                    thermalEscalatedAtStart = (ts != .nominal)
                }
                calIps.append(ips)
                await publish(phase: .calibration, ips: ips, workerIps: perWorkerIps, pre: true)
            case .measured:
                if measuredStartNs == 0 {
                    measuredStartNs = now
                    stampLastNs = now
                    stampAccumWork = 0
                    stampAccumNs = 0
                    await MainActor.run {
                        self.state.phase = .measured
                        self.state.baselineIps = Self.p95(self.calIps)
                        self.state.stamps = []
                        self.state.elapsed = 0
                    }
                    let wb = Self.p95(self.calIps) / Double(max(1, self.testThreadingType == .single ? 1 : self.coreCount))
                    workerBaselines = Array(repeating: max(wb, 1), count: coreCount)
                    for i in 0..<coreCount { prevCounters[i] = counters[i].get() }
                    continue
                }

                // attribution — iOS evidence: thermal-state level + worker sched fraction + ambient load
                let baseline = Self.p95(self.calIps)
                let attr: Attribution
                if ts == .serious || ts == .critical {
                    attr = .thermal
                } else if schedFrac < 0.75 {
                    attr = .contention   // workers starved — external load, NOT thermal
                } else if ips < baseline * 0.90 {
                    attr = .thermal      // fully scheduled but throughput down → system DVFS
                } else { attr = .none }
                lastAttr = attr; lastSched = schedFrac; lastLoad = sysLoad; lastTs = ts
                if attr == .contention { contentionStamps += 1 }
                if attr == .thermal { thermalStamps += 1 }
                if ips > baseline * 1.05 { above105 += 1 }

                // accumulate this 250ms sub-window into the 1s stamp
                stampAccumWork += total // iterations done this sub-window
                stampAccumNs += dt

                let stampWindowNs = now - stampLastNs
                if stampWindowNs >= 1_000_000_000 {
                    var env: Int = 0
                    let charging = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
                    if charging { env |= EnvFlag.charging }
                    if ProcessInfo.processInfo.isLowPowerModeEnabled { env |= EnvFlag.lowPowerMode }
                    if ts != .nominal { env |= EnvFlag.thermalElevated }
                    envAcc |= env

                    let stampIps = Int64(stampAccumWork * 1e9 / Double(stampWindowNs))
                    let stamp = StampV2(tNs: Int64(now - measuredStartNs),
                                        windowNs: Int64(stampWindowNs),
                                        ipsTotal: stampIps,
                                        ipsPerWorker: perWorkerIps.map { Int64($0) },
                                        schedFracAvg: schedFrac,
                                        thermalState: ts.rawValue,
                                        systemLoad: sysLoad,
                                        attribution: lastAttr.rawValue,
                                        envFlags: env)
                    stampLastNs = now
                    stampAccumWork = 0
                    stampAccumNs = 0
                    await MainActor.run {
                        self.state.stamps.append(stamp)
                        self.state.elapsed = TimeInterval(now - self.measuredStartNs) / 1e9
                    }
                }
                await MainActor.run {
                    self.state.liveIps = ips
                    self.state.attribution = attr
                    self.state.systemLoad = sysLoad
                    self.state.workerRatios = perWorkerIps.enumerated().map { min(1.10, $1 / max(1, self.workerBaselines[$0])) }
                }

                if TimeInterval(now - measuredStartNs) / 1e9 >= (self.testDuration.timeInterval ?? .infinity) {
                    break
                }
            default: break
            }
            try? await Task.sleep(nanoseconds: 250_000_000)
        }

        // ── VERDICT ──
        keepAlive = false
        let scorecard = buildScorecard(cooldownTimedOut: cooldownTimedOut,
                                       contentionStamps: contentionStamps,
                                       thermalStamps: thermalStamps,
                                       above105: above105,
                                       envAcc: envAcc)
        let reachedEnd = TimeInterval(DispatchTime.now().uptimeNanoseconds - measuredStartNs) / 1e9 >= (testDuration.timeInterval ?? .infinity) * 0.9
        await MainActor.run {
            self.state.scorecard = scorecard
            self.wasCompleted = reachedEnd && !self.abortedByUser && !self.wasCancelledDueToBackground
            self.isRunning = false
            self.state.phase = .idle
            UIApplication.shared.isIdleTimerDisabled = false
        }
        _ = lastSched; _ = lastLoad; _ = lastTs
    }

    private func finishAbort() {
        Task { @MainActor in
            self.keepAlive = false
            self.isRunning = false
            self.state.phase = .idle
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    private func publish(phase: RunPhase, ips: Double, workerIps: [Double], pre: Bool) async {
        // pre-run gauges: normalize against each worker's running peak
        for (i, w) in workerIps.enumerated() where w > displayBaselines[i] { displayBaselines[i] = w }
        await MainActor.run {
            self.state.phase = phase
            self.state.liveIps = ips
            if pre { self.state.preRunIps.append((phase, ips)) }
            self.state.workerRatios = workerIps.enumerated().map {
                min(1.10, $1 / max(1, self.displayBaselines[$0]))
            }
        }
    }

    private static func p95(_ xs: [Double]) -> Double {
        guard !xs.isEmpty else { return 1 }
        let s = xs.sorted()
        return s[min(s.count - 1, Int(Double(s.count) * 0.95))]
    }

    private func buildScorecard(cooldownTimedOut: Bool, contentionStamps: Int, thermalStamps: Int, above105: Int, envAcc: Int) -> Scorecard {
        var sc = Scorecard()
        let stamps = state.stamps
        let baseline = Self.p95(calIps)
        sc.baselineIps = baseline
        guard !stamps.isEmpty, baseline > 0 else {
            sc.validityFlags |= ValidityFlag.shortRun | ValidityFlag.freqMissing
            sc.confidence = 20
            return sc
        }

        // AUC delivered capacity with real dt
        var work = 0.0, totalNs = 0.0
        for s in stamps {
            work += Double(s.ipsTotal) * Double(s.windowNs)
            totalNs += Double(s.windowNs)
        }
        sc.deliveredCapacity = min(150, work / (baseline * totalNs) * 100)

        // sustained ratio: median last quartile
        let q = stamps.suffix(max(1, stamps.count / 4)).map { Double($0.ipsTotal) }.sorted()
        sc.sustainedRatio = min(150, (q[q.count / 2] / baseline) * 100)

        // throttle onset debounced: first <90% held ≥3 stamps
        var onset: Int? = nil, streak = 0
        for s in stamps {
            if Double(s.ipsTotal) < baseline * 0.9 { streak += 1 } else { streak = 0 }
            if streak >= 3 { onset = Int(Double(s.elapsedMs) / 1000) - 2; break }
        }
        sc.throttleOnsetSec = onset

        // min sustained 5s mean over thermal-attributed stamps
        var mins: [Double] = []
        var window: [Double] = []
        for s in stamps where s.attribution == Attribution.thermal.rawValue {
            window.append(Double(s.ipsTotal) / baseline * 100)
            if window.count > 5 { window.removeFirst() }
            if window.count == 5 { mins.append(window.reduce(0, +) / 5) }
        }
        sc.minSustained = mins.min()
        sc.timeInThrottlePct = Double(thermalStamps) / Double(stamps.count) * 100
        sc.warmStarted = thermalEscalatedAtStart

        // validity flags
        var flags = ValidityFlag.freqMissing // iOS exposes no cpufreq — always true here
        if thermalEscalatedAtStart { flags |= ValidityFlag.warmStarted }
        if cooldownTimedOut { flags |= ValidityFlag.cooldownTimeout }
        if Double(contentionStamps) / Double(stamps.count) > 0.10 { flags |= ValidityFlag.interference }
        if powerEventSeen { flags |= ValidityFlag.powerEvent }
        if envAcc & EnvFlag.lowPowerMode != 0 { flags |= ValidityFlag.envCapped }
        if state.elapsed < state.duration * 0.9 { flags |= ValidityFlag.shortRun }
        if above105 >= 3 { flags |= ValidityFlag.baselineExceeded }
        sc.validityFlags = flags

        // confidence
        var conf = 100
        if flags & ValidityFlag.warmStarted != 0 { conf -= 15 }
        if flags & ValidityFlag.interference != 0 { conf -= 20 }
        if flags & ValidityFlag.shortRun != 0 { conf -= 25 }
        if flags & ValidityFlag.envCapped != 0 { conf -= 10 }
        if flags & ValidityFlag.cooldownTimeout != 0 { conf -= 15 }
        if flags & ValidityFlag.baselineExceeded != 0 { conf -= 10 }
        conf -= 10 // freq telemetry unavailable — permanent honesty tax on iOS
        sc.confidence = max(0, conf)
        return sc
    }
}

extension ProcessInfo.ThermalState {
    var name: String {
        switch self {
        case .nominal: return "NOMINAL"
        case .fair: return "FAIR"
        case .serious: return "SERIOUS"
        case .critical: return "CRITICAL"
        @unknown default: return "UNKNOWN"
        }
    }
    var level: Int { rawValue }
}

struct StabilityPoint: Identifiable, Equatable {
    let id = UUID()
    let time: TimeInterval
    let score: Double
}

struct ThermalEvent: Identifiable, Equatable {
    let id = UUID()
    let time: TimeInterval
    let state: ProcessInfo.ThermalState

    var name: String { state.name }
    var iconName: String {
        switch state {
        case .nominal: return "thermometer.snowflake"
        case .fair: return "thermometer.low"
        case .serious: return "thermometer.medium"
        case .critical: return "thermometer.high"
        @unknown default: return "thermometer"
        }
    }
    var color: Color { thermalStateColor(state) }
}
