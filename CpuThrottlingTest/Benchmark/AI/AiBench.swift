import CoreML
import Foundation
import TensorFlowLiteC
import TensorFlowLiteCMetal

// ─────────────────────────────────────────────────────────────────────────────
// AI BENCHMARK — the Android app's six 8-bit models (Qualcomm AI Hub w8a8,
// the same .tflite files) on the CPU, the GPU and the Neural Engine.
//
//   CPU: TensorFlow Lite with XNNPACK, 4 threads, in INT8 — as on Android.
//   GPU: TensorFlow Lite's Metal delegate (FP16, the weights dequantized) —
//        Android's LiteRT GPU does the same.
//   NPU: the Neural Engine, which only Core ML reaches: the same models
//        converted op for op (BenchmarkScene/build_coreml_models.py) — the
//        same int8 weights and activation quantization.
//   A run loads/compiles the model (not timed), warms up, then counts
//   inferences for a fixed time. Outputs come back as TFLite's 8-bit tensors,
//   so the stage decodes every accelerator's result the same way.
// ─────────────────────────────────────────────────────────────────────────────

struct AiError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

final class AiBench {
    static let warmupRuns = 3
    static let cpuThreads: Int32 = 4

    /** Inferences per second, and the outputs of the last inference (TFLite's 8-bit tensors). */
    struct Measured { let ips: Double; let outputs: [[UInt8]] }

    /** Why the Neural Engine can't run here, or nil when it can. */
    static var npuProblem: String? {
        if #available(iOS 17.0, *) { return nil }
        return "the Neural Engine models need iOS 17"
    }

    static var npuRuntime: String {
        #if targetEnvironment(simulator)
        return "Core ML (simulator: no Neural Engine)"
        #else
        return "Apple Neural Engine (Core ML)"
        #endif
    }

    func run(_ test: AiTest, _ accel: AiAccel, input: [UInt8], seconds: Double,
             isActive: () -> Bool, onProgress: (Double, Double) -> Void) throws -> Measured {
        let runner: Runner = accel == .npu ? try CoreMLRunner(test) : try TFLiteRunner(test, gpu: accel == .gpu)
        try runner.set(input)
        for _ in 0..<Self.warmupRuns { try runner.invoke() }
        var n = 0
        let t0 = DispatchTime.now().uptimeNanoseconds
        let limit = UInt64(seconds * 1e9)
        var lastReport = t0
        while DispatchTime.now().uptimeNanoseconds - t0 < limit && isActive() {
            try runner.invoke()
            n += 1
            let now = DispatchTime.now().uptimeNanoseconds
            if now - lastReport > 150_000_000 {
                lastReport = now
                onProgress(min(1, Double(now - t0) / Double(limit)), Double(n) * 1e9 / Double(now - t0))
            }
        }
        let ips = Double(n) * 1e9 / Double(DispatchTime.now().uptimeNanoseconds - t0)
        return Measured(ips: ips, outputs: try runner.outputs())
    }
}

private protocol Runner {
    func set(_ input: [UInt8]) throws
    func invoke() throws
    func outputs() throws -> [[UInt8]]
}

/** TensorFlow Lite: XNNPACK on the CPU or the Metal delegate on the GPU. */
private final class TFLiteRunner: Runner {
    private let model: OpaquePointer
    private let options: OpaquePointer
    private var delegate: UnsafeMutablePointer<TfLiteDelegate>?
    private let gpu: Bool
    private let interpreter: OpaquePointer

    init(_ test: AiTest, gpu: Bool) throws {
        guard let url = Bundle.main.url(forResource: "ai/\(test.asset).tflite", withExtension: nil),
              let model = TfLiteModelCreateFromFile(url.path) else { throw AiError(message: "\(test.asset).tflite missing") }
        self.model = model
        self.gpu = gpu
        options = TfLiteInterpreterOptionsCreate()
        if gpu {
            var o = TFLGpuDelegateOptionsDefault()
            o.allow_precision_loss = true                  // FP16, as LiteRT's GPU on Android
            o.wait_type = TFLGpuDelegateWaitTypePassive    // an inference ends when the GPU is done
            o.enable_quantization = true
            delegate = TFLGpuDelegateCreate(&o)
        } else {
            var o = TfLiteXNNPackDelegateOptionsDefault()
            o.num_threads = AiBench.cpuThreads
            o.flags |= UInt32(TFLITE_XNNPACK_DELEGATE_FLAG_QS8 | TFLITE_XNNPACK_DELEGATE_FLAG_QU8)
            delegate = TfLiteXNNPackDelegateCreate(&o)
            TfLiteInterpreterOptionsSetNumThreads(options, AiBench.cpuThreads)
        }
        guard let delegate else {
            TfLiteInterpreterOptionsDelete(options); TfLiteModelDelete(model)
            throw AiError(message: gpu ? "no Metal delegate" : "no XNNPACK")
        }
        TfLiteInterpreterOptionsAddDelegate(options, delegate)
        guard let interpreter = TfLiteInterpreterCreate(model, options) else {
            Self.free(delegate, gpu: gpu); TfLiteInterpreterOptionsDelete(options); TfLiteModelDelete(model)
            throw AiError(message: gpu ? "the Metal delegate can't run this model" : "TensorFlow Lite can't run this model")
        }
        self.interpreter = interpreter
        guard TfLiteInterpreterAllocateTensors(interpreter) == kTfLiteOk else { throw AiError(message: "can't allocate the tensors") }
    }

    deinit {
        TfLiteInterpreterDelete(interpreter)
        if let delegate { Self.free(delegate, gpu: gpu) }
        TfLiteInterpreterOptionsDelete(options)
        TfLiteModelDelete(model)
    }

    private static func free(_ d: UnsafeMutablePointer<TfLiteDelegate>, gpu: Bool) {
        if gpu { TFLGpuDelegateDelete(d) } else { TfLiteXNNPackDelegateDelete(d) }
    }

    func set(_ input: [UInt8]) throws {
        let t = TfLiteInterpreterGetInputTensor(interpreter, 0)
        guard TfLiteTensorByteSize(t) == input.count,
              input.withUnsafeBytes({ TfLiteTensorCopyFromBuffer(t, $0.baseAddress, input.count) }) == kTfLiteOk else {
            throw AiError(message: "input is \(input.count) bytes, the model wants \(TfLiteTensorByteSize(t))")
        }
    }

    func invoke() throws {
        guard TfLiteInterpreterInvoke(interpreter) == kTfLiteOk else { throw AiError(message: "inference failed") }
    }

    func outputs() throws -> [[UInt8]] {
        (0..<TfLiteInterpreterGetOutputTensorCount(interpreter)).map { k in
            let t = TfLiteInterpreterGetOutputTensor(interpreter, k)!
            var out = [UInt8](repeating: 0, count: TfLiteTensorByteSize(t))
            _ = out.withUnsafeMutableBytes { TfLiteTensorCopyToBuffer(t, $0.baseAddress, $0.count) }
            return out
        }
    }
}

/**
 * Core ML on the Neural Engine (and the CPU for what it can't run). The model takes TFLite's input values
 * dequantized, planar, as float16 and returns float16 values on TFLite's output grid — turned back into
 * TFLite's 8-bit tensors here (outside the timing).
 */
private final class CoreMLRunner: Runner {
    private struct Meta: Decodable {
        struct Output: Decodable { let shape: [Int]; let scale: Double; let zero: Int }
        let input_shape: [Int]
        let input_scale: Double
        let input_zero: Int
        let outputs: [Output]
    }

    private let model: MLModel
    private let meta: Meta
    private var input: MLFeatureProvider?
    private var last: MLFeatureProvider?

    init(_ test: AiTest) throws {
        guard #available(iOS 17.0, *) else { throw AiError(message: AiBench.npuProblem ?? "") }
        guard let url = Bundle.main.url(forResource: test.asset, withExtension: "mlmodelc") else {
            throw AiError(message: "\(test.asset).mlmodelc missing")
        }
        let config = MLModelConfiguration()
        config.computeUnits = .cpuAndNeuralEngine
        model = try MLModel(contentsOf: url, configuration: config)        // compiles for the Neural Engine
        guard let json = model.modelDescription.metadata[.creatorDefinedKey].flatMap({ ($0 as? [String: String])?["thermohammer"] }),
              let meta = try? JSONDecoder().decode(Meta.self, from: Data(json.utf8)) else {
            throw AiError(message: "\(test.asset): no quantization metadata")
        }
        self.meta = meta
    }

    func set(_ bytes: [UInt8]) throws {
        let s = meta.input_shape                        // NHWC
        let (h, w, c) = (s[1], s[2], s[3])
        let array = try MLMultiArray(shape: [1, c, h, w].map { NSNumber(value: $0) }, dataType: .float16)
        let scale = Float(meta.input_scale), zero = Float(meta.input_zero)
        array.withUnsafeMutableBufferPointer(ofType: Float16.self) { p, strides in
            for y in 0..<h {
                for x in 0..<w {
                    for k in 0..<c {
                        p[k * strides[1] + y * strides[2] + x * strides[3]] = Float16((Float(bytes[(y * w + x) * c + k]) - zero) * scale)
                    }
                }
            }
        }
        input = try MLDictionaryFeatureProvider(dictionary: ["x": MLFeatureValue(multiArray: array)])
    }

    func invoke() throws {
        guard let input else { throw AiError(message: "no input") }
        last = try model.prediction(from: input)
    }

    func outputs() throws -> [[UInt8]] {
        guard let last else { throw AiError(message: "no output") }
        return try meta.outputs.enumerated().map { k, o in
            guard let a = last.featureValue(for: "out\(k)")?.multiArrayValue else { throw AiError(message: "no out\(k)") }
            var out = [UInt8]()
            out.reserveCapacity(a.count)
            a.withUnsafeBufferPointer(ofType: Float16.self) { p in
                // in TFLite's (row-major) order — the Neural Engine may pad rows — and back onto the 8-bit grid
                forEachOffset(shape: a.shape.map(\.intValue), strides: a.strides.map(\.intValue)) { i in
                    let v = Double(p[i])
                    let q = o.scale > 0 ? (v / o.scale).rounded() + Double(o.zero) : v.rounded()
                    out.append(UInt8(max(0, min(255, q))))
                }
            }
            return out
        }
    }
}

/** Calls `body` with every element's offset, in row-major order of `shape`. */
private func forEachOffset(shape: [Int], strides: [Int], _ body: (Int) -> Void) {
    guard !shape.isEmpty, !shape.contains(0) else { return }
    let last = shape.count - 1
    var index = [Int](repeating: 0, count: shape.count)
    var base = 0
    while true {
        for j in 0..<shape[last] { body(base + j * strides[last]) }
        var d = last - 1
        while d >= 0 {
            index[d] += 1
            base += strides[d]
            if index[d] < shape[d] { break }
            base -= strides[d] * shape[d]
            index[d] = 0
            d -= 1
        }
        if d < 0 { return }
    }
}
