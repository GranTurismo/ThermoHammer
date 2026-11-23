import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// LLM TEST · THE NPU ENGINE — version 5's third engine (rule B): Gemma 3 1B on
// the Neural Engine, the same task as llama.cpp's CPU and GPU runs (a 512-token
// prompt, 32-token replies), then one fixed question whose reply must name the
// expected word. Planned: ANEMLL (AnemllCore, Core ML), the model delivered as
// an App Store background asset pack (BENCHMARK_V5_PLAN.md). Android's
// counterpart is engine/llm/NpuLlmProbe.kt (LiteRT-LM).
//
//   This file is the whole hook: LlmSection awaits `run(progress:)` right after
//   the GPU run, and everything else — the score, the report, the result card,
//   MY RUNS, the CSV and the payload — reads only the LlmNpuRun it returns. So
//   filling in `run`'s body (and `engineName`) is all an engine needs.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * What the NPU engine did (Android's LlmNpuRun). It counts (LlmResult.npuPoints) only when it ran without an `error`
 * and answered `right` — a fast wrong answer scores 0. Rates are tokens per second, 0 where that phase didn't run.
 */
struct LlmNpuRun {
    let prefill: Double          // reading the 512-token prompt
    let decode: Double           // writing the reply, token by token
    let right: Bool              // the fixed question's reply named the expected word
    var model: String? = nil     // the model file it ran — recorded as "<engineName> · <model>"; nil when none ran
    var error: String? = nil     // why it didn't run or failed (the result card and the report show it)
    var lines: [String] = []     // its own report lines, printed after the report's "LLM NPU" line
    /// The device could run the model but it wasn't downloaded yet: the run is incomplete (BenchFlag.npuModelMissing)
    var missingModel = false
}

enum LlmNpuEngine {
    /// The engine and its version as a run records it (the payload's `llmNpu`, "<engineName> · <model>"): an engine
    /// update can move NPU scores, so name the version once there is one (Android's is "LiteRT-LM 0.19.1").
    static let engineName = AneLlm.engineName

    /**
     * Runs the task on the Neural Engine and returns what it measured. LlmSection calls it once per benchmark, after
     * llama.cpp's GPU run (whose model is freed by then), from the benchmark's worker thread. `progress(label, fraction)`
     * puts a line such as "NPU · ANEMLL · PREFILL · 512-TOKEN PROMPT" and how far it is (0…1) on the stage, from any
     * thread. When the user stops the benchmark the task is cancelled (`Task.isCancelled`): return soon after that.
     */
    /** Whether the model was downloaded and compiled when this benchmark started (noteRunStart): only then does it run. */
    private static let readyLock = NSLock()
    nonisolated(unsafe) private static var readyAtRunStart = false

    /**
     * BenchmarkEngine.start: the NPU part runs only if the model was ready when the run began. A download, its unpacking
     * or the minutes-long compile finishing mid-run would otherwise have loaded the phone during tests that still count.
     */
    static func noteRunStart() {
        readyLock.withLock { readyAtRunStart = LlmNpuModel.isReadyForBenchmark }
    }

    static func run(progress: @escaping (String, Double) -> Void) async -> LlmNpuRun {
        // after a stop, nothing more reaches the stage (the next run may be on it already)
        let progress: (String, Double) -> Void = { label, f in if !Task.isCancelled { progress(label, f) } }
        func notRun(_ why: String, missing: Bool = false) -> LlmNpuRun {
            progress("NPU · NOT RUN", 1)
            return LlmNpuRun(prefill: 0, decode: 0, right: false, model: nil, error: why,
                             lines: ["LLM NPU · \(engineName) · not run: \(why)"], missingModel: missing)
        }
        #if targetEnvironment(simulator)
        return notRun("the simulator has no Neural Engine")
        #else
        guard #available(iOS 18.0, macOS 15.0, *) else {
            return notRun("the Neural Engine engine needs iOS 18 or later (Core ML's model state)")
        }
        // a Neural Engine that can't hold the model (A14/M1, Intel Macs, or two failed compiles): the hardware's limit, 0
        if let why = LlmNpuModel.cannotRunReason { return notRun(why) }
        guard readyLock.withLock({ readyAtRunStart }), let dir = LlmNpuModel.readyDirectory else {
            LlmNpuModel.shared.start()            // for the next run: it downloads once over Wi-Fi, then compiles once
            return notRun("its model (Gemma 3 1B for the Neural Engine, ~1 GB) wasn't ready on this device when the run "
                          + "started — it downloads once over Wi-Fi, then compiles once; this run can't be submitted", missing: true)
        }
        let r = await AneLlm.run(modelDir: dir, progress: progress)
        return LlmNpuRun(prefill: r.prefill, decode: r.decode, right: r.right, model: r.model, error: r.error, lines: r.lines)
        #endif
    }
}
