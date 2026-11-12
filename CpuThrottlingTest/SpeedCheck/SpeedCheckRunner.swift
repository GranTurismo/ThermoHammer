import Foundation
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// THE SPEED CHECK — 30 seconds of the benchmark's multi-core CPU work (CPU 4's
// BenchCore kernels, one instance per core, the path CpuBench uses), counted
// per 1-s window. One work unit = one pass through CPU 4's six kernels. The
// latest check (and the one after a change) is kept, so "check again" works
// after a trip to Settings, even across a restart; a first check expires
// after an hour.
// ─────────────────────────────────────────────────────────────────────────────

final class SpeedCheckRunner: ObservableObject {
    static let shared = SpeedCheckRunner()

    @Published private(set) var running = false
    @Published private(set) var again = false               // the running check is the one after a change
    @Published private(set) var elapsed = 0.0
    @Published private(set) var live = 0.0                   // work units/s right now
    @Published private(set) var perSecond: [Double] = []     // the running check's finished seconds
    @Published private(set) var stopped: String?             // why the last check ended early
    @Published private(set) var first: SpeedCheck.Measurement?
    @Published private(set) var second: SpeedCheck.Measurement?

    private let lock = NSLock()
    private var active = false
    private var stopReason: String?
    private var idleWasDisabled = false
    private var backgroundObserver: NSObjectProtocol?
    private static let savedKey = "speedCheck.saved"

    private struct Saved: Codable { var first: SpeedCheck.Measurement?; var second: SpeedCheck.Measurement? }

    private init() {
        if let data = UserDefaults.standard.data(forKey: Self.savedKey),
           let saved = try? JSONDecoder().decode(Saved.self, from: data) {
            first = saved.first
            second = saved.second
        }
        dropStale()
    }

    /// A first check older than an hour no longer describes the phone: start over.
    func dropStale() {
        guard first != nil, SpeedCheck.fresh(first, now: Date().timeIntervalSince1970) == nil else { return }
        first = nil
        second = nil
        save()
    }

    /// Forget both checks (the flow is done).
    func reset() {
        first = nil
        second = nil
        stopped = nil
        save()
    }

    /** Starts a check: the first one (`again` false, forgetting any earlier pair) or the one after a change. */
    func start(again: Bool) {
        guard !running else { return }
        dropStale()
        let isSecond = again && first != nil
        if isSecond { second = nil } else { first = nil; second = nil }
        save()
        self.again = isSecond
        running = true
        elapsed = 0
        live = 0
        perSecond = []
        stopped = nil
        lock.withLock { active = true; stopReason = nil }
        idleWasDisabled = UIApplication.shared.isIdleTimerDisabled
        UIApplication.shared.isIdleTimerDisabled = true
        // iOS suspends a backgrounded app: the seconds after that would measure nothing
        backgroundObserver = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification,
                                                                    object: nil, queue: .main) { [weak self] _ in
            self?.cancel("The check stopped when the app left the screen. Start it again.")
        }
        let lowPowerAtStart = ProcessInfo.processInfo.isLowPowerModeEnabled
        let t = Thread { [weak self] in self?.measure(isSecond: isSecond, lowPowerAtStart: lowPowerAtStart) }
        t.qualityOfService = .userInitiated
        t.stackSize = 4 << 20
        t.start()
    }

    func cancel(_ reason: String? = nil) {
        lock.withLock {
            if active, stopReason == nil { stopReason = reason }
            active = false
        }
    }

    private var isActive: Bool { lock.withLock { active } }

    /** Shared by the worker threads: work done so far, a stop flag, the first failure. */
    private final class Tally {
        private let lock = NSLock()
        private var units = 0.0
        private var stop = false
        private var failure: String?
        func add(_ u: Double) { lock.withLock { units += u } }
        var total: Double { lock.withLock { units } }
        var stopped: Bool { lock.withLock { stop } }
        func halt() { lock.withLock { stop = true } }
        func fail(_ why: String) { lock.withLock { if failure == nil { failure = why }; stop = true } }
        var error: String? { lock.withLock { failure } }
    }

    private func measure(isSecond: Bool, lowPowerAtStart: Bool) {
        let ids = CpuTest.multi.kernelIds
        let threads = ProcessInfo.processInfo.activeProcessorCount
        let tally = Tally()
        let ready = DispatchGroup(), done = DispatchGroup(), go = DispatchSemaphore(value: 0)
        for _ in 0..<threads {
            ready.enter()
            done.enter()
            let worker = Thread {
                defer { done.leave() }
                // inputs built and every kernel run once before the clock starts (as CpuBench does): the burst is real
                let kernels = ids.map { CoreKernel($0) }
                let expected: [Int64]
                do { expected = try kernels.map { try $0.run() } } catch {
                    tally.fail(error.localizedDescription)
                    ready.leave()
                    return
                }
                ready.leave()
                go.wait()
                let share = 1 / Double(kernels.count)
                while !tally.stopped {
                    for (k, kernel) in kernels.enumerated() {
                        do {
                            guard try kernel.run() == expected[k] else { tally.fail("\(kernel.name) result changed"); break }
                        } catch { tally.fail(error.localizedDescription); break }
                        tally.add(share)
                    }
                }
            }
            worker.qualityOfService = .userInitiated
            worker.start()
        }
        ready.wait()
        let t0 = DispatchTime.now().uptimeNanoseconds
        for _ in 0..<threads { go.signal() }
        var windows: [Double] = []
        var lastUnits = 0.0, lastT = t0
        while windows.count < SpeedCheck.seconds && isActive && tally.error == nil {
            Thread.sleep(forTimeInterval: 0.05)
            let t = DispatchTime.now().uptimeNanoseconds
            let units = tally.total
            let el = Double(t - t0) / 1e9
            var now = (units - lastUnits) / max(1e-3, Double(t - lastT) / 1e9)
            if el >= Double(windows.count + 1) {
                windows.append(now)
                lastUnits = units
                lastT = t
            } else if t - lastT < 300_000_000, let last = windows.last {
                now = last                   // too early in this second to say: the last full one
            }
            let shown = windows
            DispatchQueue.main.async {
                self.elapsed = min(Double(SpeedCheck.seconds), el)
                self.live = now
                if self.perSecond.count != shown.count { self.perSecond = shown }
            }
        }
        tally.halt()
        done.wait()
        let failure = tally.error
        let reason = lock.withLock { stopReason }
        DispatchQueue.main.async {
            if windows.count >= SpeedCheck.seconds {
                let m = SpeedCheck.measurement(windows,
                                               powerSaver: lowPowerAtStart || ProcessInfo.processInfo.isLowPowerModeEnabled,
                                               charging: deviceCharging(),
                                               thermalEnd: ProcessInfo.processInfo.thermalState.rawValue,
                                               timestamp: Date().timeIntervalSince1970)
                if isSecond { self.second = m } else { self.first = m }
                self.save()
            } else {
                self.stopped = failure.map { "The check failed: \($0)." } ?? reason ?? "The check was stopped."
            }
            self.lock.withLock { self.active = false }
            self.running = false
            UIApplication.shared.isIdleTimerDisabled = self.idleWasDisabled
            if let o = self.backgroundObserver { NotificationCenter.default.removeObserver(o) }
            self.backgroundObserver = nil
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(Saved(first: first, second: second)) {
            UserDefaults.standard.set(data, forKey: Self.savedKey)
        }
    }
}
