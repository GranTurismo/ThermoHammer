import Foundation
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// BENCHMARK ENGINE — the run, in Android's order: getting ready (cool and
// quiet), then CPU → MEM → GPU → UX → AI → LLM. The work runs on its own thread;
// the stage reads the published state.
// ─────────────────────────────────────────────────────────────────────────────

/** A section the iPhone port can't run yet reports why here; the run is then incomplete (not submittable). */
protocol BenchmarkSection {
    func run(_ engine: BenchmarkEngine, _ result: inout BenchmarkResult) throws
}

final class BenchmarkEngine: ObservableObject {
    static let shared = BenchmarkEngine()

    enum Phase { case idle, preparing, running, done }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var preparingBusy = false
    @Published private(set) var preparingElapsed = 0
    @Published private(set) var cpuTest: CpuTest?
    @Published private(set) var cpuProgress: CpuProgress?
    @Published private(set) var memTest: MemTest?
    @Published private(set) var memProgress: MemProgress?
    @Published private(set) var gpuTest: GpuTest?
    @Published private(set) var gpuFps = -1.0
    @Published private(set) var gpuProgress = 0.0     // 0..1 through the test's timeline
    @Published private(set) var uxTest: UxTest?
    @Published private(set) var uxProgress = 0.0
    @Published private(set) var uxDetail = ""
    @Published private(set) var uxValue = -1.0
    @Published private(set) var uxUnit = ""
    @Published private(set) var webPage: String?    // the page the stage shows (web tests)
    @Published private(set) var videoFrame: UIImage?  // a decoded (or exported) frame now and then (UX 5–6)
    @Published private(set) var videoOsd: VideoOsd?   // the player overlay (UX 5)
    @Published private(set) var exportStage: ExportStage?   // the export dialog (UX 6)
    @Published private(set) var qrFrame: UIImage?     // the frame under the scanner (QR test)
    @Published private(set) var qrFound: [CGPoint]?   // where the scanner found the code in it, frame pixels
    @Published var stageTitle = ""                 // set by later sections (AI)
    @Published private(set) var aiTest: AiTest?
    @Published private(set) var aiAccel: AiAccel?
    @Published private(set) var aiProgress = 0.0     // 0..1 through the model's three accelerators
    @Published private(set) var aiRate = -1.0
    @Published private(set) var aiDetail = ""
    @Published private(set) var aiVisual: AiVisual?
    @Published private(set) var llmAccel: LlmAccel?     // where the model runs now — the stage is up while set
    @Published private(set) var llmPhase: LlmPhase = .build
    @Published private(set) var llmProgress = 0.0       // 0..1 through this accelerator
    @Published private(set) var llmRate = -1.0          // tokens/s so far
    @Published private(set) var llmDetail = ""
    @Published private(set) var llmReply: [Int] = []    // the reply being written, token ids
    @Published private(set) var llmPrompts = 0          // prompts read so far on this accelerator
    @Published private(set) var inBackground = false  // on a Mac: another app is in front of the run
    @Published private(set) var result: BenchmarkResult?
    @Published private(set) var error: String?
    @Published private(set) var record: BenchmarkRecord?

    /// Sections after MEM, in run order (GPU, UX, AI, LLM).
    var laterSections: [BenchmarkSection] = [GpuSection(), UxSection(), AiSection(), LlmSection()]

    static let cooldownMaxS = 30.0
    static let quietShare: Float = 0.85        // starts once it gets ≥ 85 % of the CPU…
    static let quietSteady: Float = 0.05       // …or 3 checks in a row agree within 5 points (the phone's normal)
    static let quietPollS = 3.0
    static let quietMaxS = 60.0

    private let lock = NSLock()
    private var active = false
    private var lostFront = false       // the app wasn't in front at some point of the run (Mac)
    private var focusObservers: [NSObjectProtocol] = []
    var isActive: Bool { lock.withLock { active } }
    var isRunning: Bool { phase == .preparing || phase == .running }

    private init() {
        // quitting mid-run (⌘Q on a Mac) isn't a crash: the AI and LLM tests' "running" markers (AiSection, LlmSection)
        // mustn't outlive a normal exit, or the next run would skip those tests as crashing ones
        NotificationCenter.default.addObserver(forName: UIApplication.willTerminateNotification, object: nil, queue: nil) { _ in
            for key in ["benchmark.aiRunning", "benchmark.llmRunning"] { UserDefaults.standard.removeObject(forKey: key) }
        }
    }

    /** `-benchFrom gpu|rt|ux|photo|qr|ai|llm` (simulator checks) skips everything before that test. */
    let startFrom: String? = ProcessInfo.processInfo.arguments.firstIndex(of: "-benchFrom").flatMap { i in
        ProcessInfo.processInfo.arguments.dropFirst(i + 1).first }

    func start() {
        guard !isRunning else { return }
        UIDevice.current.isBatteryMonitoringEnabled = true
        lock.withLock { active = true }
        result = nil; error = nil; record = nil; cpuTest = nil; memTest = nil; cpuProgress = nil; memProgress = nil
        phase = .preparing
        watchFront()
        UIApplication.shared.isIdleTimerDisabled = true
        let t = Thread { [weak self] in self?.runAll() }
        t.qualityOfService = .userInitiated
        t.stackSize = 8 << 20
        t.start()
    }

    func stop() {
        lock.withLock { active = false }
    }

    func dismissResult() { phase = .idle; result = nil; record = nil; error = nil }

    /**
     * On a Mac the run shares the screen: a window covered by another app gets its web content throttled by
     * macOS (the browser tests stall), and whatever is in front competes for the chip. Losing the front during
     * the run flags it unverified, and the stage says so while it lasts. (A phone runs the app full screen.)
     */
    private func watchFront() {
        lock.withLock { lostFront = false }
        inBackground = false
        guard LeaderboardService.isMac, focusObservers.isEmpty else { return }
        let nc = NotificationCenter.default
        focusObservers = [
            nc.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
                guard let self, self.isRunning else { return }
                if self.phase == .running { self.lock.withLock { self.lostFront = true } }   // getting ready doesn't count
                self.inBackground = true
            },
            nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                self?.inBackground = false
            },
        ]
    }

    // MARK: - the run (worker thread)

    private func ui(_ block: @escaping () -> Void) { DispatchQueue.main.async(execute: block) }

    func publishVideoFrame(_ image: UIImage?) { if let image { ui { self.videoFrame = image } } }

    func publishVideoOsd(_ osd: VideoOsd?) { ui { self.videoOsd = osd } }

    func publishExportStage(_ stage: ExportStage?) { ui { self.exportStage = stage } }

    func publishQr(_ frame: UIImage?, found: [CGPoint]?) { ui { self.qrFrame = frame; self.qrFound = found } }

    /** The AI model on the stage now, on which accelerator (nil between them), and its live rate. */
    func publishAi(_ test: AiTest?, accel: AiAccel?, progress: Double, rate: Double, detail: String) {
        ui { self.uxTest = nil; self.aiTest = test; self.aiAccel = accel; self.aiProgress = progress; self.aiRate = rate; self.aiDetail = detail }
    }

    func publishAiVisual(_ v: AiVisual?) { ui { self.aiVisual = v } }

    /** The LLM test on the stage now: the accelerator (nil when the section is over), its phase and live rate. */
    func publishLlm(_ accel: LlmAccel?, phase: LlmPhase, progress: Double, rate: Double, detail: String, reply: [Int] = [], prompts: Int = 0) {
        ui {
            self.uxTest = nil; self.aiTest = nil; self.aiVisual = nil
            self.llmAccel = accel; self.llmPhase = phase; self.llmProgress = progress; self.llmRate = rate
            self.llmDetail = detail; self.llmReply = reply; self.llmPrompts = prompts
        }
    }

    /** The UX test on the stage now: its progress, what it's doing and its live number. */
    func publishUx(_ test: UxTest?, progress: Double = 0, detail: String = "", value: Double = -1, unit: String = "") {
        ui { self.uxTest = test; self.uxProgress = progress; self.uxDetail = detail; self.uxValue = value; self.uxUnit = unit }
    }

    // MARK: web tests — the stage's WebKit view reports here

    private let webLock = NSLock()
    private var webResult: Result<String, Error>?
    private var webWait: DispatchSemaphore?

    /** From the page: Web browsing reports each ramping scene; scrolling and gallery send their HUD line and big number. */
    func webProgress(scene: String, fraction: Double, complexity: Int, value: Double, index: Int) {
        switch uxTest {
        case .web?:
            let unit = ["CARDS", "SHAPES", "TILES", "PATHS"].indices.contains(index) ? ["CARDS", "SHAPES", "TILES", "PATHS"][index] : "OPS"
            uxProgress = min(1, max(0, (Double(index) + fraction) / 5))
            uxDetail = "\(scene.uppercased()) · \(complexity.formatted()) \(unit)"
            uxValue = value > 0 ? value : -1
            uxUnit = value > 0 ? "FPS" : ""
        case .scroll?, .gallery?:
            uxProgress = min(1, max(0, fraction))
            uxDetail = scene.uppercased()
            uxValue = value > 0 ? value : -1
            uxUnit = uxTest == .scroll ? "PX/S" : "MP/S"
        default: break
        }
    }

    func webFinished(_ result: Result<String, Error>) {
        webLock.withLock {
            guard webResult == nil else { return }
            webResult = result
            webWait?.signal()
        }
    }

    /** Shows `test`'s page on the stage and waits for its JSON (worker thread). Nil if the run was stopped. */
    func runWebPage(_ test: UxTest) -> Result<[String: Any], Error>? {
        let wait = DispatchSemaphore(value: 0)
        webLock.withLock { webResult = nil; webWait = wait }
        ui { self.uxTest = test; self.uxProgress = 0; self.uxDetail = "LOADING"; self.uxValue = -1; self.webPage = test.page }
        let deadline = Date().addingTimeInterval(Double(test.seconds) * 4 + 60)
        var outcome: Result<String, Error>?
        while outcome == nil {
            if !isActive { break }
            if wait.wait(timeout: .now() + 0.25) == .success { outcome = webLock.withLock { webResult } }
            else if Date() > deadline { outcome = .failure(GpuError(message: "timed out")) }
        }
        ui { self.webPage = nil }
        webLock.withLock { webWait = nil }
        guard isActive, let outcome else { return nil }
        return outcome.flatMap { json in
            guard let o = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] else {
                return .failure(GpuError(message: "unreadable result"))
            }
            return .success(o)
        }
    }

    /** The GPU test on the stage now (nil when the GPU section is over). */
    func publishGpu(_ test: GpuTest?, fps: Double, progress: Double) {
        ui { self.gpuTest = test; self.gpuFps = fps; self.gpuProgress = progress }
    }

    private func charging() -> Bool {
        // a Mac: UIDevice's battery reading there is made up (1 %, charging, whatever the Mac's own says), and a Mac on
        // its charger is how it's normally used — the phone's charger rule doesn't apply
        if LeaderboardService.isMac { return false }
        return DispatchQueue.main.sync { [.charging, .full].contains(UIDevice.current.batteryState) }
    }

    private func runAll() {
        var r = BenchmarkResult()
        r.gpuName = BenchmarkUpload.gpuName()
        if ProcessInfo.processInfo.isLowPowerModeEnabled { r.validityFlags |= BenchFlag.envCapped }
        if ProcessInfo.processInfo.thermalState != .nominal { r.validityFlags |= BenchFlag.warmStarted }

        // getting ready: cool, and nothing else eating the CPU
        var cooled = coolDown()
        let quiet = waitUntilQuiet()
        if cooled && quiet.waited >= 20 { cooled = coolDown() }   // it had cooled; the busy spell may have warmed it
        if !cooled { r.validityFlags |= BenchFlag.cooldownTimeout }
        if !quiet.quiet { r.validityFlags |= BenchFlag.interference }
        r.startCpuShare = quiet.share
        r.quietWaitSec = quiet.waited
        guard isActive else { return finish(nil, error: nil) }
        ui {
            self.phase = .running
            if self.inBackground { self.lock.withLock { self.lostFront = true } }
        }

        do {
            let from = startFrom
            if from == nil {
                r.cpu = try runCpu()
                guard isActive else { return finish(nil, error: nil) }
                if charging() { r.validityFlags |= BenchFlag.powerEvent }
                r.mem = try runMem()
                guard isActive else { return finish(nil, error: nil) }
            }
            for section in laterSections where !(["ux", "photo", "video", "export", "qr", "ai"].contains(from) && section is GpuSection)
                && !(from == "ai" && section is UxSection) && !(from == "rt" && !(section is GpuSection))
                && !(from == "llm" && !(section is LlmSection)) {
                if charging() { r.validityFlags |= BenchFlag.powerEvent }
                try section.run(self, &r)
                guard isActive else { return finish(nil, error: nil) }
            }
            if lock.withLock({ lostFront }) { r.validityFlags |= BenchFlag.interference }
            finish(r, error: nil)
        } catch {
            finish(nil, error: isActive ? error.localizedDescription : nil)   // a stop is not an error
        }
    }

    private func finish(_ r: BenchmarkResult?, error: String?) {
        lock.withLock { active = false }
        let record = r.map(BenchmarkRecord.init)
        ui {
            UIApplication.shared.isIdleTimerDisabled = false
            self.cpuTest = nil; self.memTest = nil; self.gpuTest = nil; self.uxTest = nil; self.webPage = nil
            self.videoFrame = nil; self.qrFrame = nil; self.qrFound = nil; self.stageTitle = ""
            self.aiTest = nil; self.aiAccel = nil; self.aiVisual = nil
            self.llmAccel = nil; self.llmReply = []
            self.inBackground = false
            self.error = error
            self.result = r
            self.record = record
            if let record { BenchmarkHistoryStore.shared.add(record) }
            self.phase = r != nil || error != nil ? .done : .idle
        }
        // simulator checks (`simctl launch --console … -autoBenchmark`): the report on the console
        if ProcessInfo.processInfo.arguments.contains("-autoBenchmark") {
            print(r.map { BenchmarkReport.text($0, device: "simulator", os: UIDevice.current.systemVersion, soc: "?", appVersion: "?", time: "") }
                  ?? "benchmark ended: \(error ?? "stopped")")
        }
    }

    /** Waits (up to 30 s) for the thermal state to return to nominal — iOS shows apps no temperatures. */
    private func coolDown() -> Bool {
        let t0 = Date()
        while isActive {
            if ProcessInfo.processInfo.thermalState == .nominal { return true }
            let waited = Date().timeIntervalSince(t0)
            if waited >= Self.cooldownMaxS { return false }
            ui { self.preparingBusy = false; self.preparingElapsed = Int(waited) }
            Thread.sleep(forTimeInterval: 1)
        }
        return true
    }

    /**
     * Holds the run while something else is eating the CPU. A share that holds steady below the bar is the phone's
     * normal (parked cores, its own UI), not work that will pass, so the run starts. Not quiet if it never settled
     * within a minute (flagged).
     */
    private func waitUntilQuiet() -> (quiet: Bool, share: Float, waited: Int) {
        let t0 = Date()
        var recent: [Float] = []
        var share: Float = 1
        defer { ui { self.preparingBusy = false } }
        while isActive {
            share = QuietProbe.cpuShare()
            let waited = Date().timeIntervalSince(t0)
            if share >= Self.quietShare { return (true, share, Int(waited)) }
            recent.append(share)
            if recent.count > 3 { recent.removeFirst() }
            if recent.count == 3, recent.max()! - recent.min()! <= Self.quietSteady { return (true, share, Int(waited)) }
            if waited >= Self.quietMaxS { return (false, share, Int(waited)) }
            ui { self.preparingBusy = true; self.preparingElapsed = Int(waited) }
            Thread.sleep(forTimeInterval: Self.quietPollS)
        }
        return (true, share, Int(Date().timeIntervalSince(t0)))
    }

    private func runCpu() throws -> CpuResult {
        let bench = CpuBench()
        var runs: [CpuTestRun] = []
        var last = DispatchTime.now().uptimeNanoseconds
        for test in CpuTest.allCases {
            guard isActive else { break }
            ui { self.cpuTest = test; self.cpuProgress = CpuProgress(kernel: test.labels[0], fraction: 0, rate: -1, threads: test.multiCore ? bench.threads : 1) }
            let run = try bench.run(test, isActive: { self.isActive }) { p in
                let now = DispatchTime.now().uptimeNanoseconds
                guard now - last > 120_000_000 else { return }
                last = now
                self.ui { self.cpuProgress = p }
            }
            runs.append(run)
        }
        ui { self.cpuTest = nil }
        return CpuResult(runs: runs)
    }

    private func runMem() throws -> MemResult {
        let bench = MemBench()
        var ram: RamResult?, storage: StorageResult?, db: DbResult?
        for test in MemTest.allCases {
            guard isActive else { break }
            ui { self.memTest = test; self.memProgress = nil }
            let report: (MemProgress) -> Void = { p in self.ui { self.memProgress = p } }
            switch test {
            case .ram: ram = try bench.ram(isActive: { self.isActive }, onProgress: report)
            case .storage: storage = try bench.storage(isActive: { self.isActive }, onProgress: report)
            case .database: db = try bench.database(isActive: { self.isActive }, onProgress: report)
            }
        }
        ui { self.memTest = nil }
        guard let ram, let storage, let db else { throw MemError(message: "stopped") }
        return MemResult(ram: ram, storage: storage, db: db)
    }
}
