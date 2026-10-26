import Foundation
import os

// ─────────────────────────────────────────────────────────────────────────────
// LLM BENCHMARK — a 3B language model through llama.cpp, on the CPU and the
// GPU (Metal) — Android's engine/llm/LlmBench.kt and runLlm.
//
//   The model has Llama 3.2 3B's exact shape in 4-bit (Q4_0, 1.8 GB), its
//   weights generated from a seed when the test starts (BenchCore/llm): nothing
//   is downloaded, the app carries no weights, and every op takes the real
//   model's time. Building it is not timed. Then:
//     prefill — the 512-token prompt in one batch, again until 3 s are measured;
//     decode  — 32-token replies, token by token, until 4 s are measured.
//   The CPU first tries thread counts; the GPU first runs a prompt and a reply
//   to build its pipelines.
// ─────────────────────────────────────────────────────────────────────────────

enum LlmPhase { case build, warmup, prefill, decode }

struct LlmSection: BenchmarkSection {
    static let prefillS = 3.0
    static let decodeS = 4.0
    static let reply = 32
    static let warmupTokens = 4
    static let needRam: UInt64 = 5_000_000_000      // iPhones sold with 6 GB report about 5.6 GB
    static let needFree = 2_600_000_000.0           // the model, its context and its buffers
    static let noticeS = 3.0                        // how long "not run" stays up when the test can't run
    static let model = "LLAMA 3.2 3B · Q4_0"
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

    /** Accelerators whose run never finished in this build (llama.cpp's crashes can't be caught): a marker was left behind. */
    private static func crashedAccels() -> Set<LlmAccel> {
        let d = UserDefaults.standard
        var crashed = Set(d.stringArray(forKey: crashedKey) ?? [])
        if let running = d.string(forKey: runningKey) {
            crashed.insert(running)
            d.set(Array(crashed), forKey: crashedKey)
            d.removeObject(forKey: runningKey)
        }
        return Set(crashed.compactMap { s in
            let p = s.split(separator: ":")
            return p.count == 2 && p[0] == build ? LlmAccel.allCases.first { "\($0)" == p[1] } : nil
        })
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
        if ram < needRam { return String(format: "needs an iPhone with 6 GB of RAM (this one has %.1f GB)", Double(ram) / 1e9) }
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
        let crashed = Self.crashedAccels()
        let gpuName = String(cString: bc_llm_gpu_name())
        var runs: [LlmRun] = []
        var threads: Int32 = 4
        for accel in LlmAccel.allCases {
            guard engine.isActive else { return }
            let skip = crashed.contains(accel) ? "crashed the app here last time"
                : accel == .gpu && gpuName.isEmpty ? "llama.cpp found no Metal GPU it can run on"
                : accel == .gpu && Self.simulator ? "the simulator's Metal can't take the model's 1.8 GB of buffers" : nil
            if let skip { runs.append(LlmRun(accel: accel, prefill: 0, decode: 0, error: skip)); continue }
            UserDefaults.standard.set("\(Self.build):\(accel)", forKey: Self.runningKey)
            defer { UserDefaults.standard.removeObject(forKey: Self.runningKey) }
            guard let run = Self.measure(accel, threads: threads, engine: engine) else { return }   // stopped
            if run.threads > 0 { threads = Int32(run.threads) }
            runs.append(run)
        }
        let gpuRan = runs.contains { $0.accel == .gpu && $0.decode > 0 }
        r.llm = LlmResult(runs: runs, gpuName: gpuRan && !gpuName.isEmpty ? gpuName : nil, freeGb: freeGb, largestModel: largest)
        engine.publishLlm(nil, phase: .build, progress: 0, rate: -1, detail: "")
    }

    /** Prefill and decode tokens/s on `accel`; a run that fails keeps its reason. Nil if the run was stopped. */
    private static func measure(_ accel: LlmAccel, threads: Int32, engine: BenchmarkEngine) -> LlmRun? {
        let gpu = accel == .gpu
        let whereText = gpu ? "GPU · METAL" : "CPU"
        func show(_ phase: LlmPhase, _ progress: Double, _ rate: Double, _ detail: String, reply: [Int] = [], prompts: Int = 0) {
            engine.publishLlm(accel, phase: phase, progress: progress, rate: rate, detail: detail, reply: reply, prompts: prompts)
        }
        show(.build, 0, -1, "\(whereText) · BUILDING THE MODEL · 1.8 GB OF WEIGHTS FROM A SEED")
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
            guard bc_llm_tune_threads(h, &t, &b) != 0 else { return failed() }
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
