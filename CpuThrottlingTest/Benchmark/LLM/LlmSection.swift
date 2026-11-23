import Foundation
import os

// ─────────────────────────────────────────────────────────────────────────────
// LLM BENCHMARK — version 5: Gemma 3 1B on three engines, each scored against
// its own reference (rule B) — Android's engine/llm/LlmBench.kt and runLlm.
//
//   CPU and GPU (Metal): llama.cpp with Gemma 3 1B's exact shape in 4-bit
//   (Q4_0, 0.56 GB), its weights generated from a seed when the test starts
//   (BenchCore/llm, bc_llm_set_model): nothing is downloaded, the app carries
//   no weights, and every op takes the real model's time. Building it is not
//   timed. Then:
//     prefill — the 512-token prompt in one batch, again until 3 s are measured;
//     decode  — 32-token replies, token by token, until 4 s are measured.
//   The CPU first tries thread counts; the GPU first runs a prompt and a reply
//   to build its pipelines.
//   NPU: the real model on the Neural Engine, through LlmNpuEngine (the hook
//   its engine fills in), the same task and a question it must answer right.
// ─────────────────────────────────────────────────────────────────────────────

enum LlmPhase { case build, warmup, prefill, decode }

struct LlmSection: BenchmarkSection {
    static let prefillS = 3.0
    static let decodeS = 4.0
    static let reply = 32
    static let warmupTokens = 4
    // Gemma 3 1B has 0.56 GB of weights (Llama 3.2 3B's shape had 1.8 GB): an iPhone sold with 4 GB (about 3.7 GB
    // reported) has room, as on Android (3 GB)
    static let needRam: UInt64 = 3_000_000_000
    // the model, its context and its buffers: a CPU run's footprint peaked 0.91 GB above the app's (simulator,
    // 2026-11-23) where Llama 3.2 3B's shape took 2.26 GB, which had 2.6 GB here — the same margin
    static let needFree = 1_100_000_000.0
    static let noticeS = 3.0                        // how long "not run" stays up when the test can't run
    static let model = "GEMMA 3 1B · Q4_0"
    static let npuSeconds = 16                      // the stage's estimate of the NPU engine's run (CPU 20 s, GPU 16 s)
    #if targetEnvironment(simulator)
    static let simulator = true
    #else
    static let simulator = false
    #endif
    private static let runningKey = "benchmark.llmRunning"
    private static let crashedKey = "benchmark.llmCrashed"

    private static var build: String {
        let info = Bundle.main.infoDictionary
        return "\(info?["CFBundleShortVersionString"] as? String ?? "?")(\(info?["CFBundleVersion"] as? String ?? "?"))"
    }

    /**
     * Engines ("cpu", "gpu", "npu") whose run never finished twice in this build — llama.cpp's and the Neural Engine's
     * crashes can't be caught, so BenchmarkEngine's marker was left behind — as in AiSection: one unfinished run could
     * still be chance.
     */
    private static func crashedEngines() -> Set<String> {
        let d = UserDefaults.standard
        var strikes = d.stringArray(forKey: crashedKey) ?? []
        if let running = d.string(forKey: runningKey) {
            strikes.append(running)
            d.set(strikes, forKey: crashedKey)
            d.removeObject(forKey: runningKey)
        }
        var count: [String: Int] = [:]
        for s in strikes {
            let p = s.split(separator: ":")
            if p.count == 2, p[0] == build { count[String(p[1]), default: 0] += 1 }
        }
        return Set(count.filter { $0.value >= 2 }.map(\.key))
    }

    /** This engine ran to the end: its earlier unfinished runs were something else. */
    private static func clearStrikes(_ engine: String) {
        let d = UserDefaults.standard
        d.set((d.stringArray(forKey: crashedKey) ?? []).filter { !$0.hasSuffix(":\(engine)") }, forKey: crashedKey)
    }

    /** What iOS still lets this app allocate (the simulator has no such limit: half the Mac's memory there). */
    static var freeBytes: Double {
        let avail = os_proc_available_memory()
        return avail > 0 ? Double(avail) : Double(ProcessInfo.processInfo.physicalMemory) / 2
    }

    private static func sysctlFlag(_ name: String) -> Bool? {
        var v: Int32 = 0
        var size = MemoryLayout<Int32>.size
        return sysctlbyname(name, &v, &size, nil, 0) == 0 ? v != 0 : nil
    }

    /** Why the model can't run on this phone at all, or nil. */
    static func problem(free: Double) -> String? {
        let ram = ProcessInfo.processInfo.physicalMemory
        if ram < needRam { return String(format: "needs an iPhone with 4 GB of RAM (this one has %.1f GB)", Double(ram) / 1e9) }
        if sysctlFlag("hw.optional.arm.FEAT_DotProd") == false || sysctlFlag("hw.optional.arm.FEAT_FP16") == false {
            return "the CPU lacks the ARMv8.2 dot-product instructions the model's kernels use"
        }
        if free < needFree { return String(format: "iOS lets the app take only %.1f GB now; the model needs %.1f", free / 1e9, needFree / 1e9) }
        return nil
    }

    func run(_ engine: BenchmarkEngine, _ r: inout BenchmarkResult) throws {
        engine.publishLlm(.cpu, phase: .build, progress: 0, rate: -1, detail: "PREPARING")
        let free = Self.freeBytes
        let freeGb = BenchmarkScore.round2(free / 1e9)
        let largest = Int(bc_llm_largest_model(free))
        if let problem = Self.problem(free: free) {
            // nothing can run: say why for a moment, every accelerator scores 0
            engine.publishLlm(.cpu, phase: .build, progress: 0, rate: -1, detail: "NOT RUN · \(problem.uppercased())")
            Thread.sleep(forTimeInterval: Self.noticeS)
            r.llm = LlmResult(runs: LlmAccel.allCases.map { LlmRun(accel: $0, prefill: 0, decode: 0, error: problem) },
                              gpuName: nil, freeGb: freeGb, largestModel: largest, error: problem)
            engine.publishLlm(nil, phase: .build, progress: 0, rate: -1, detail: "")
            return
        }
        let crashed = Self.crashedEngines()
        let gpuName = String(cString: bc_llm_gpu_name())
        var runs: [LlmRun] = []
        var threads: Int32 = 4
        // version 5's model on llama.cpp's engines; process-wide in BenchCore, so put back when done (bench_llm.h)
        bc_llm_set_model(Int32(BC_LLM_MODEL_GEMMA3_1B))
        defer { bc_llm_set_model(Int32(BC_LLM_MODEL_LLAMA32_3B)) }
        for accel in LlmAccel.allCases {
            guard engine.isActive else { return }
            let skip = crashed.contains("\(accel)") ? "crashed the app twice in this version"
                : accel == .gpu && gpuName.isEmpty ? "llama.cpp found no Metal GPU it can run on"
                : accel == .gpu && Self.simulator ? "not measured in the simulator" : nil
            if let skip { runs.append(LlmRun(accel: accel, prefill: 0, decode: 0, error: skip)); continue }
            BenchmarkEngine.setRunningMarker("\(Self.build):\(accel)", forKey: Self.runningKey)
            defer { BenchmarkEngine.clearRunningMarker(Self.runningKey) }
            guard let run = Self.measure(accel, threads: threads, engine: engine) else { return }   // stopped
            Self.clearStrikes("\(accel)")
            if run.threads > 0 { threads = Int32(run.threads) }
            runs.append(run)
        }
        // the third engine, the Neural Engine: after llama.cpp's runs, whose models are freed by now
        let npu: LlmNpuRun
        if crashed.contains("npu") {
            let why = "crashed the app twice in this version"
            npu = LlmNpuRun(prefill: 0, decode: 0, right: false, error: why, lines: ["LLM NPU · \(LlmNpuEngine.engineName) · not run: \(why)"])
        } else {
            BenchmarkEngine.setRunningMarker("\(Self.build):npu", forKey: Self.runningKey)
            defer { BenchmarkEngine.clearRunningMarker(Self.runningKey) }
            guard let ran = Self.runNpu(engine) else { return }   // stopped
            Self.clearStrikes("npu")
            npu = ran
        }
        // a device that can run the model without it yet: incomplete, never submitted as "no NPU"
        if npu.missingModel { r.validityFlags |= BenchFlag.npuModelMissing }
        let gpuRan = runs.contains { $0.accel == .gpu && $0.decode > 0 }
        r.llm = LlmResult(runs: runs, gpuName: gpuRan && !gpuName.isEmpty ? gpuName : nil, freeGb: freeGb, largestModel: largest, npu: npu)
        engine.publishLlm(nil, phase: .build, progress: 0, rate: -1, detail: "")
    }

    /**
     * The NPU engine (LlmNpuEngine.run, async), awaited from this worker thread. Nil if the benchmark was stopped
     * meanwhile: the engine's task is cancelled and its result, whenever it comes, dropped.
     */
    private static func runNpu(_ engine: BenchmarkEngine) -> LlmNpuRun? {
        final class Outcome: @unchecked Sendable { var run: LlmNpuRun? }   // written once, before `done` is signalled
        let outcome = Outcome(), done = DispatchSemaphore(value: 0)
        engine.publishLlmNpu(progress: 0, detail: "NPU · GEMMA 3 1B · PREPARING")
        let task = Task.detached(priority: .userInitiated) {
            outcome.run = await LlmNpuEngine.run { label, fraction in engine.publishLlmNpu(progress: fraction, detail: label) }
            done.signal()
        }
        while done.wait(timeout: .now() + 0.1) == .timedOut {
            if !engine.isActive { task.cancel(); return nil }
        }
        return outcome.run
    }

    /** Prefill and decode tokens/s on `accel`; a run that fails keeps its reason. Nil if the run was stopped. */
    private static func measure(_ accel: LlmAccel, threads: Int32, engine: BenchmarkEngine) -> LlmRun? {
        let gpu = accel == .gpu
        let whereText = gpu ? "GPU · METAL" : "CPU"
        func show(_ phase: LlmPhase, _ progress: Double, _ rate: Double, _ detail: String, reply: [Int] = [], prompts: Int = 0) {
            engine.publishLlm(accel, phase: phase, progress: progress, rate: rate, detail: detail, reply: reply, prompts: prompts)
        }
        show(.build, 0, -1, "\(whereText) · BUILDING THE MODEL · 0.6 GB OF WEIGHTS FROM A SEED")
        var err = [CChar](repeating: 0, count: 512)
        guard let h = bc_llm_create(gpu ? 1 : 0, threads, UInt64(BC_LLM_SEED), &err, Int32(err.count)) else {
            return LlmRun(accel: accel, prefill: 0, decode: 0, error: String(cString: err).isEmpty ? "the model could not be built" : String(cString: err))
        }
        // a prompt on a slow CPU takes seconds: a stopped run cancels it from here
        let done = NSLock()
        var finished = false
        let watcher = Thread {
            while !done.withLock({ finished }) {
                if !engine.isActive { bc_llm_cancel(h); return }
                Thread.sleep(forTimeInterval: 0.1)
            }
        }
        watcher.start()
        defer {
            done.withLock { finished = true }
            bc_llm_destroy(h)
        }
        func failed() -> LlmRun? {
            engine.isActive ? LlmRun(accel: accel, prefill: 0, decode: 0, error: String(cString: bc_llm_error(h)).isEmpty ? "failed" : String(cString: bc_llm_error(h))) : nil
        }

        var decodeThreads: Int32 = threads, batchThreads: Int32 = threads
        if gpu {
            show(.warmup, 0, -1, "\(whereText) · BUILDING AND WARMING THE PIPELINES")
            guard bc_llm_prefill(h, Int32(BC_LLM_PROMPT)) >= 0 else { return failed() }
            bc_llm_reset(h)
            for _ in 0..<warmupTokens { guard bc_llm_next(h, nil) >= 0 else { return failed() } }
        } else {
            show(.warmup, 0, -1, "\(whereText) · FINDING THE FASTEST THREAD COUNT")
            var t: Int32 = 0, b: Int32 = 0
            // each trial run takes seconds on a slow CPU: say which one is running
            let trials = TuneReporter { phase, n, trial, cores in
                let text = trial == 0 ? "FINDING THE FASTEST THREAD COUNT · WARMING UP"
                    : "TUNING \(phase == 0 ? "DECODE" : "PREFILL") · TRYING \(n) OF \(cores) THREADS"
                show(.warmup, 0, -1, "\(whereText) · \(text)")
            }
            let tuned = withExtendedLifetime(trials) {
                bc_llm_tune_threads_progress(h, &t, &b, { user, phase, n, trial, cores in
                    Unmanaged<TuneReporter>.fromOpaque(user!).takeUnretainedValue().report(phase, n, trial, cores)
                }, Unmanaged.passUnretained(trials).toOpaque())
            }
            guard tuned != 0 else { return failed() }
            decodeThreads = t
            batchThreads = b
            show(.warmup, 0, -1, "\(whereText) · \(t) THREADS DECODE · \(b) PREFILL")
        }
        let threadsNote = gpu ? "" : " · \(decodeThreads) THREADS"
        let prompt = Int(BC_LLM_PROMPT)

        var promptS = 0.0, prompts = 0
        while prompts == 0 || promptS < Self.prefillS {
            guard engine.isActive else { return nil }
            show(.prefill, min(0.5, promptS / Self.prefillS / 2), prompts > 0 ? Double(prompts * prompt) / promptS : -1,
                 "\(whereText) · PREFILL · \(prompt)-TOKEN PROMPT", prompts: prompts)
            let s = bc_llm_prefill(h, Int32(prompt))
            guard s >= 0 else { return failed() }
            promptS += s
            prompts += 1
        }
        let prefill = Double(prompts * prompt) / promptS
        show(.prefill, 0.5, prefill, "\(whereText) · PREFILL · \(prompt)-TOKEN PROMPT", prompts: prompts)

        var decodeS = 0.0, tokens = 0
        var shown: UInt64 = 0
        while tokens == 0 || decodeS < Self.decodeS {
            guard engine.isActive else { return nil }
            bc_llm_reset(h)
            var replyIds: [Int] = []
            for i in 0..<reply {
                var token: Int32 = 0
                let s = bc_llm_next(h, &token)
                guard s >= 0 else { return failed() }
                decodeS += s
                tokens += 1
                replyIds.append(Int(token))
                let now = DispatchTime.now().uptimeNanoseconds
                if now - shown > 80_000_000 || i == reply - 1 {
                    shown = now
                    show(.decode, 0.5 + min(0.5, decodeS / Self.decodeS / 2), Double(tokens) / decodeS,
                         "\(whereText) · DECODE · TOKEN BY TOKEN\(threadsNote)", reply: replyIds, prompts: prompts)
                }
            }
        }
        return LlmRun(accel: accel, prefill: BenchmarkScore.round2(prefill), decode: BenchmarkScore.round2(Double(tokens) / decodeS),
                      threads: gpu ? 0 : Int(decodeThreads), batchThreads: gpu ? 0 : Int(batchThreads))
    }
}

/** Carries bc_llm_tune_threads_progress's per-trial callback (a C function pointer, so no captures) back into Swift. */
private final class TuneReporter {
    let report: (_ phase: Int32, _ threads: Int32, _ trial: Int32, _ cores: Int32) -> Void
    init(_ report: @escaping (Int32, Int32, Int32, Int32) -> Void) { self.report = report }
}
