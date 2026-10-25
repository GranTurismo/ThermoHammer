import Foundation
import Metal

/** The GPU tests, in order: Lake Valley, Neon City, Neon City RT (ray tracing where the GPU has it, else 0 points). */
struct GpuSection: BenchmarkSection {
    func run(_ engine: BenchmarkEngine, _ r: inout BenchmarkResult) throws {
        guard let bench = GpuBench.shared else { throw GpuError(message: "Metal is unavailable") }
        r.gpuName = bench.name
        for test in GpuTest.allCases where engine.startFrom != "rt" || test.rayTracing {   // `-benchFrom rt`: simulator checks
            guard engine.isActive else { return }
            if test.rayTracing, let problem = Self.rayTracingProblem(bench.device) {
                r.gpu.append(.unsupported(test))
                r.rayTracingProblem = problem
                continue
            }
            let scene = try makeScene(test, bench)
            let seconds = Float(test.seconds)
            engine.publishGpu(test, fps: -1, progress: 0)
            let (starts, durs) = try bench.run(scene, seconds: seconds, isActive: { engine.isActive }) { t, fps in
                engine.publishGpu(test, fps: fps, progress: Double(t / seconds))
            }
            guard engine.isActive else { return }
            guard let result = BenchmarkScore.summarize(test, starts: starts, durNs: durs, startTempC: nil, endTempC: nil) else {
                throw GpuError(message: "\(test.title): too few frames rendered")
            }
            r.gpu.append(result)
        }
        engine.publishGpu(nil, fps: -1, progress: 0)
    }

    /** Why this GPU can't run Neon City RT (its rays are cast from fragment shaders), or nil. */
    static func rayTracingProblem(_ device: MTLDevice) -> String? {
        if !device.supportsRaytracing { return "the GPU has no ray tracing" }
        if !device.supportsRaytracingFromRender { return "the GPU can't ray trace while rendering (needs A14 or later)" }
        return nil
    }

    private func makeScene(_ test: GpuTest, _ bench: GpuBench) throws -> GpuScene {
        switch test {
        case .lakeValley:
            guard let url = Bundle.main.url(forResource: "lake_valley", withExtension: "bin") else { throw GpuError(message: "lake_valley.bin missing") }
            let asset = try LakeValleyAsset(Data(contentsOf: url))
            return try LakeValleyRenderer(device: bench.device, library: bench.library, asset: asset,
                                          width: GpuBench.width, height: GpuBench.height)
        case .neonCity, .neonCityRT:
            guard let url = Bundle.main.url(forResource: "neon_city", withExtension: "bin") else { throw GpuError(message: "neon_city.bin missing") }
            let asset = try NeonCityAsset(Data(contentsOf: url))
            return try NeonCityRenderer(device: bench.device, library: bench.library, asset: asset,
                                        width: GpuBench.width, height: GpuBench.height, rayTracing: test == .neonCityRT)
        }
    }
}
