import Foundation
import CoreML

/**
 * The LLM test's NPU engine on iPhones and Macs (scoring version 5): Gemma 3 1B on the Apple Neural Engine through
 * ANEMLL's Core ML runtime (LLM/ANEMLL, MIT) — the same task as the CPU and GPU runs and as Android's NPU engine: a
 * 512-token prompt, a 32-token reply, then one fixed question whose reply must name the expected word.
 *
 * The model: Gemma 3 1B converted by ANEMLL's converter at 4 bits (LUT4 layers and head, 8-bit embeddings), a 1,280-token
 * context — the precision and context of Google's Android NPU files (Gemma3-1B-IT_q4_ekv1280_<soc>). It comes as an
 * App Store background asset pack (`gemma_npu`, LlmNpuModel) and is compiled for the Neural Engine on its first load
 * (minutes; Core ML keeps the result). Chips whose Neural Engine can't hold a 1,280-token chunked model (A14, M1 —
 * ANEMLL's limit) fail to load it: their NPU third counts 0, like a phone chip Google builds no file for.
 *
 * No tokenizer runs here: the prompt and the question are Gemma's own token ids (computed once with its tokenizer),
 * and the reply is read through the model's vocabulary file (vocab.txt beside the model, one piece per line).
 */
enum AneLlm {
    static let engineName = "ANEMLL 0.3.5"
    /** The model folder's name in the asset pack (and in a test copy's Documents/npu-llm). */
    static let modelName = "gemma3-1b-it-lut4-ctx1280"

    struct Run {
        var prefill = 0.0, decode = 0.0, right = false
        var model: String? = nil, error: String? = nil
        var lines: [String] = []
    }

    // the converted model's settings (its meta.yaml)
    private static let contextLength = 1280, batchSize = 64, slidingWindow = 512, vocabSize = 262_144
    private static let lmHeadChunks = Array(repeating: 16_384, count: 16)

    private static let prefillTokens = 512, decodeTokens = 32
    private static let expected = "paris"
    /** Gemma's end of turn and end of text. */
    private static let eos = [106, 1]
    /** "What is the capital of France? Answer with one word." in Gemma's chat format (Android's NPU engine asks the same). */
    static let question: [Int] = [
        2, 105, 2364, 107, 3689, 563, 506, 5279, 529, 7001, 236881, 25685, 607, 886, 3658, 236761, 106, 107, 105, 4368, 107
    ]
    /** 512 tokens of plain English (the bos token, then a fixed paragraph repeated): the speed run's prompt. */
    static let prompt: [Int] = [
        2, 818, 8858, 11536, 3068, 506, 2255, 2612, 236764, 1298, 506, 7683, 12280, 1546, 9137, 531, 4526, 506, 2214, 29625, 1024, 506, 6192, 236761,
        53705, 528, 506, 23844, 25159, 1056, 506, 11228, 1053, 1774, 6812, 236764, 840, 1418, 1919, 7261, 506, 3925, 529, 506, 18226, 600, 16689, 625,
        236764, 532, 529, 506, 161533, 1015, 61485, 506, 11825, 10810, 684, 10810, 1680, 506, 1171, 7613, 3588, 236761, 669, 8858, 11536, 3068, 506, 2255,
        2612, 236764, 1298, 506, 7683, 12280, 1546, 9137, 531, 4526, 506, 2214, 29625, 1024, 506, 6192, 236761, 53705, 528, 506, 23844, 25159, 1056, 506,
        11228, 1053, 1774, 6812, 236764, 840, 1418, 1919, 7261, 506, 3925, 529, 506, 18226, 600, 16689, 625, 236764, 532, 529, 506, 161533, 1015, 61485,
        506, 11825, 10810, 684, 10810, 1680, 506, 1171, 7613, 3588, 236761, 669, 8858, 11536, 3068, 506, 2255, 2612, 236764, 1298, 506, 7683, 12280, 1546,
        9137, 531, 4526, 506, 2214, 29625, 1024, 506, 6192, 236761, 53705, 528, 506, 23844, 25159, 1056, 506, 11228, 1053, 1774, 6812, 236764, 840, 1418,
        1919, 7261, 506, 3925, 529, 506, 18226, 600, 16689, 625, 236764, 532, 529, 506, 161533, 1015, 61485, 506, 11825, 10810, 684, 10810, 1680, 506,
        1171, 7613, 3588, 236761, 669, 8858, 11536, 3068, 506, 2255, 2612, 236764, 1298, 506, 7683, 12280, 1546, 9137, 531, 4526, 506, 2214, 29625, 1024,
        506, 6192, 236761, 53705, 528, 506, 23844, 25159, 1056, 506, 11228, 1053, 1774, 6812, 236764, 840, 1418, 1919, 7261, 506, 3925, 529, 506, 18226,
        600, 16689, 625, 236764, 532, 529, 506, 161533, 1015, 61485, 506, 11825, 10810, 684, 10810, 1680, 506, 1171, 7613, 3588, 236761, 669, 8858, 11536,
        3068, 506, 2255, 2612, 236764, 1298, 506, 7683, 12280, 1546, 9137, 531, 4526, 506, 2214, 29625, 1024, 506, 6192, 236761, 53705, 528, 506, 23844,
        25159, 1056, 506, 11228, 1053, 1774, 6812, 236764, 840, 1418, 1919, 7261, 506, 3925, 529, 506, 18226, 600, 16689, 625, 236764, 532, 529, 506,
        161533, 1015, 61485, 506, 11825, 10810, 684, 10810, 1680, 506, 1171, 7613, 3588, 236761, 669, 8858, 11536, 3068, 506, 2255, 2612, 236764, 1298, 506,
        7683, 12280, 1546, 9137, 531, 4526, 506, 2214, 29625, 1024, 506, 6192, 236761, 53705, 528, 506, 23844, 25159, 1056, 506, 11228, 1053, 1774, 6812,
        236764, 840, 1418, 1919, 7261, 506, 3925, 529, 506, 18226, 600, 16689, 625, 236764, 532, 529, 506, 161533, 1015, 61485, 506, 11825, 10810, 684,
        10810, 1680, 506, 1171, 7613, 3588, 236761, 669, 8858, 11536, 3068, 506, 2255, 2612, 236764, 1298, 506, 7683, 12280, 1546, 9137, 531, 4526, 506,
        2214, 29625, 1024, 506, 6192, 236761, 53705, 528, 506, 23844, 25159, 1056, 506, 11228, 1053, 1774, 6812, 236764, 840, 1418, 1919, 7261, 506, 3925,
        529, 506, 18226, 600, 16689, 625, 236764, 532, 529, 506, 161533, 1015, 61485, 506, 11825, 10810, 684, 10810, 1680, 506, 1171, 7613, 3588, 236761,
        669, 8858, 11536, 3068, 506, 2255, 2612, 236764, 1298, 506, 7683, 12280, 1546, 9137, 531, 4526, 506, 2214, 29625, 1024, 506, 6192, 236761, 53705,
        528, 506, 23844, 25159, 1056, 506, 11228, 1053, 1774, 6812, 236764, 840, 1418, 1919, 7261, 506, 3925, 529, 506, 18226, 600, 16689, 625, 236764,
        532, 529, 506, 161533, 1015, 61485, 506, 11825
    ]

    private static func why(_ e: Error) -> String { String(String(describing: e).prefix(240)) }

    private static func config(_ dir: URL) -> YAMLConfig {
        let path = dir.path
        return YAMLConfig(contextLength: contextLength,
                          embedPath: "\(path)/gemma3_embeddings_lut8.mlmodelc",
                          ffnPath: "\(path)/gemma3_FFN_PF_lut4_chunk_01of01.mlmodelc",
                          lmheadPath: "\(path)/gemma3_lm_head_lut4.mlmodelc",
                          numChunks: 1, isMonolithic: false, monolithicModelPath: nil, slidingWindow: slidingWindow)
    }

    /**
     * Loads the model once and lets it go: the first load on a device compiles it for its Neural Engine (minutes), and
     * Core ML keeps the result — so done after the download (LlmNpuModel), the benchmark's own load takes seconds.
     * Returns how long it took, or why it failed.
     */
    @available(iOS 18.0, macOS 15.0, *)
    static func prepare(modelDir dir: URL) async -> Result<Double, Error> {
        let t0 = CFAbsoluteTimeGetCurrent()
        do {
            _ = try await ModelLoader().loadModel(from: config(dir), configuration: .init(computeUnits: .cpuAndNeuralEngine))
            return .success(CFAbsoluteTimeGetCurrent() - t0)
        } catch {
            return .failure(error)
        }
    }

    /** Runs the task on the Neural Engine with the model in `dir`. `progress(label, fraction)`. Never throws. */
    static func run(modelDir dir: URL, progress: @escaping (String, Double) -> Void) async -> Run {
        guard #available(iOS 18.0, macOS 15.0, *) else {
            return Run(error: "the Neural Engine engine needs iOS 18", lines: ["LLM NPU · \(engineName) · not run: needs iOS 18"])
        }
        var run = Run(model: modelName)
        let head = "LLM NPU · \(engineName) · \(modelName)"
        let config = config(dir)
        let pieces = (try? String(contentsOf: dir.appendingPathComponent("vocab.txt"), encoding: .utf8))?
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.replacingOccurrences(of: "\\n", with: "\n").replacingOccurrences(of: "\\\\", with: "\\") } ?? []
        let tokenizer = Tokenizer(padTokenId: 0, pieces: pieces)

        // load: the first time on a device, Core ML compiles the model for its Neural Engine (minutes), then it's cached
        progress("NPU · NEURAL ENGINE · GEMMA 3 1B · PREPARING", 0.05)
        let t0 = CFAbsoluteTimeGetCurrent()
        let manager: InferenceManager
        do {
            let models = try await ModelLoader().loadModel(from: config, configuration: .init(computeUnits: .cpuAndNeuralEngine))
            manager = try InferenceManager(models: models, contextLength: contextLength, batchSize: batchSize,
                                           splitLMHead: lmHeadChunks.count, debugLevel: 0, argmaxInModel: true,
                                           slidingWindow: slidingWindow, prefillDynamicSlice: true, modelPrefix: "gemma3",
                                           vocabSize: vocabSize, lmHeadChunkSizes: lmHeadChunks)
        } catch {
            run.error = "load: \(why(error))"
            run.lines = ["\(head) · not run: the Neural Engine couldn't load it: \(why(error))"]
            return run
        }
        let load = CFAbsoluteTimeGetCurrent() - t0
        run.lines.append(String(format: "%@ · Apple Neural Engine (Core ML) · load %.1f s", head, load))

        do {
            // one short untimed pass: the first prediction sets up the Neural Engine's buffers
            progress("NPU · NEURAL ENGINE · GEMMA 3 1B · WARM-UP", 0.15)
            _ = try await manager.generateResponse(initialTokens: Array(prompt.prefix(batchSize)), temperature: 0, maxTokens: 4,
                                                   eosTokens: [], tokenizer: tokenizer)
            progress("NPU · NEURAL ENGINE · GEMMA 3 1B · SPEED", 0.3)
            let s0 = CFAbsoluteTimeGetCurrent()
            let (tokens, prefillTime, _) = try await manager.generateResponse(initialTokens: prompt, temperature: 0,
                                                                              maxTokens: decodeTokens, eosTokens: [], tokenizer: tokenizer)
            let total = CFAbsoluteTimeGetCurrent() - s0
            if prefillTime > 0 && total > prefillTime && !tokens.isEmpty {
                run.prefill = Double(prefillTokens) / prefillTime
                run.decode = Double(tokens.count) / (total - prefillTime)
            }
            run.lines.append(String(format: "  speed · prompt %.2f tok/s (%d tokens) · reply %.2f tok/s (%d tokens)",
                                         run.prefill, prefillTokens, run.decode, tokens.count))
        } catch {
            run.error = "speed: \(why(error))"
            run.lines.append("  speed · failed: \(why(error))")
        }

        progress("NPU · NEURAL ENGINE · GEMMA 3 1B · ANSWER CHECK", 0.75)
        do {
            let (tokens, _, _) = try await manager.generateResponse(initialTokens: question, temperature: 0, maxTokens: 16,
                                                                    eosTokens: eos, tokenizer: tokenizer)
            let reply = tokenizer.decode(tokens: tokens.filter { !eos.contains($0) }).trimmingCharacters(in: .whitespacesAndNewlines)
            run.right = !pieces.isEmpty && reply.lowercased().contains(expected)
            run.lines.append("  answer · \"What is the capital of France? Answer with one word.\" → \"\(reply.replacingOccurrences(of: "\n", with: " ").prefix(80))\" · "
                + (pieces.isEmpty ? "unread (no vocab.txt)" : run.right ? "right" : "WRONG (expected Paris)"))
        } catch {
            run.lines.append("  answer · failed: \(why(error))")
            if run.error == nil { run.error = "answer: \(why(error))" }
        }
        return run
    }
}
