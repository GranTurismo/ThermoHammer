import Foundation

/**
 * The AI tests: each model on the CPU, the GPU and the Neural Engine in turn, its result on the stage — Android's
 * runAi. An accelerator that took the app down last time (TensorFlow Lite's and Core ML's crashes can't be
 * caught) is skipped in this build and scores 0.
 */
struct AiSection: BenchmarkSection {
    static let seconds = 1.6                    // measured per model and accelerator, as on Android
    static let showS = 3.5                      // each model's result stays up at least this long
    private static let runningKey = "benchmark.aiRunning"
    private static let crashedKey = "benchmark.aiCrashed"

    private static var build: String {
        let info = Bundle.main.infoDictionary
        return "\(info?["CFBundleShortVersionString"] as? String ?? "?")(\(info?["CFBundleVersion"] as? String ?? "?"))"
    }

    /** Accelerators whose run never finished in this build: a marker was left behind. */
    private static func crashedAccels() -> Set<AiAccel> {
        let d = UserDefaults.standard
        var crashed = Set(d.stringArray(forKey: crashedKey) ?? [])
        if let running = d.string(forKey: runningKey) {
            crashed.insert(running)
            d.set(Array(crashed), forKey: crashedKey)
            d.removeObject(forKey: runningKey)
        }
        return Set(crashed.compactMap { s in
            let p = s.split(separator: ":")
            return p.count == 3 && p[0] == build ? AiAccel.allCases.first { "\($0)" == p[2] } : nil
        })
    }

    func run(_ engine: BenchmarkEngine, _ r: inout BenchmarkResult) throws {
        engine.publishAi(.classify, accel: nil, progress: 0, rate: -1, detail: "LOADING MODELS")
        let bench = AiBench()
        let inputs = try AiInputs()
        let crashed = Self.crashedAccels()
        let npuProblem = AiBench.npuProblem
        var runs: [AiRun] = []
        for test in AiTest.allCases {
            guard engine.isActive else { return }
            let (input, picture) = try inputs.input(test)
            engine.publishAi(test, accel: nil, progress: 0, rate: -1, detail: test.model.uppercased())
            engine.publishAiVisual(AiVisual(test: test, picture: picture))
            var decodedAt: UInt64 = 0
            for (k, accel) in AiAccel.allCases.enumerated() {
                guard engine.isActive else { return }
                let skip = crashed.contains(accel) ? "crashed the app here last time" : accel == .npu ? npuProblem : nil
                if let skip { runs.append(AiRun(test: test, accel: accel, ips: 0, error: skip)); continue }
                let label = accel == .npu ? "NPU · \(AiBench.npuRuntime)" : accel == .gpu ? "GPU · METAL" : "CPU · XNNPACK"
                let precision = accel == .gpu ? "FP16" : "INT8"
                engine.publishAi(test, accel: accel, progress: Double(k) / 3, rate: -1, detail: "\(label) · \(precision) · \(test.model.uppercased()) · COMPILING")
                UserDefaults.standard.set("\(Self.build):\(test.asset):\(accel)", forKey: Self.runningKey)
                defer { UserDefaults.standard.removeObject(forKey: Self.runningKey) }
                let measured: AiBench.Measured
                do {
                    measured = try bench.run(test, accel, input: input, seconds: Self.seconds, isActive: { engine.isActive }) { f, ips in
                        engine.publishAi(test, accel: accel, progress: (Double(k) + f) / 3, rate: ips, detail: "\(label) · \(precision) · \(test.model.uppercased())")
                    }
                } catch {
                    runs.append(AiRun(test: test, accel: accel, ips: 0, error: error.localizedDescription))
                    continue
                }
                guard engine.isActive else { return }
                runs.append(AiRun(test: test, accel: accel, ips: BenchmarkScore.round2(measured.ips)))
                if decodedAt == 0 {
                    decodedAt = DispatchTime.now().uptimeNanoseconds
                    engine.publishAiVisual(inputs.visual(test, picture: picture, outputs: measured.outputs))
                }
            }
            // where accelerators were skipped the model would flash by: leave its result up long enough to read
            if decodedAt != 0 {
                let left = Self.showS - Double(DispatchTime.now().uptimeNanoseconds - decodedAt) / 1e9
                if left > 0 { engine.publishAi(test, accel: nil, progress: 1, rate: -1, detail: test.model.uppercased()); Thread.sleep(forTimeInterval: left) }
            }
        }
        r.ai = AiResult(runs: Self.fallbackChecked(runs), npuRuntime: npuProblem == nil ? AiBench.npuRuntime : nil, npuProblem: npuProblem)
    }

    /**
     * A "Neural Engine" no faster than the CPU across the models means Core ML ran it on the CPU after all (its
     * fallback is silent): those runs score 0 instead of passing for an NPU — Android's rule.
     */
    private static func fallbackChecked(_ runs: [AiRun]) -> [AiRun] {
        func ips(_ t: AiTest, _ a: AiAccel) -> Double { runs.first { $0.test == t && $0.accel == a }?.ips ?? 0 }
        let ratios = AiTest.allCases.compactMap { t -> Double? in
            let c = ips(t, .cpu), n = ips(t, .npu)
            return c > 0 && n > 0 ? n / c : nil
        }
        guard !ratios.isEmpty else { return runs }
        let speedup = exp(ratios.map(log).reduce(0, +) / Double(ratios.count))
        if speedup >= 1.2 { return runs }
        return runs.map { run in
            guard run.accel == .npu, run.ips > 0 else { return run }
            return AiRun(test: run.test, accel: run.accel, ips: 0,
                         error: String(format: "no faster than the CPU (%.2f×): Core ML fell back to it", speedup))
        }
    }
}
