import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// CPU TESTS — BenchCore's kernels (the same C++ as on Android), each warmed up
// untimed, then timed for its share of the test. Every call's checksum must
// equal the first one's.
// ─────────────────────────────────────────────────────────────────────────────

/** Live state for the stage. */
struct CpuProgress { let kernel: String; let fraction: Double; let rate: Double; let threads: Int }

struct CpuKernelError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/** One BenchCore kernel instance (one per thread). */
final class CoreKernel {
    let name: String
    private let handle: OpaquePointer

    init(_ id: Int32) {
        name = String(cString: bc_kernel_name(id))
        handle = bc_kernel_create(id)
    }
    deinit { bc_kernel_destroy(handle) }

    func run() throws -> Int64 {
        var ok: Int32 = 1
        let v = bc_kernel_run(handle, &ok)
        if ok == 0 { throw CpuKernelError(message: "\(name): \(String(cString: bc_kernel_error(handle)))") }
        return v
    }
}

final class CpuBench {
    static let warmupS = 0.3
    let threads = ProcessInfo.processInfo.activeProcessorCount

    func run(_ test: CpuTest, isActive: () -> Bool, onProgress: (CpuProgress) -> Void) throws -> CpuTestRun {
        var rates: [(String, Double)] = []
        let ids = test.kernelIds
        for (k, id) in ids.enumerated() {
            guard isActive() else { break }
            let name = test.labels[k]
            let rate: Double
            if test.multiCore {
                rate = try multi(id, seconds: test.secondsPerKernel, isActive: isActive) { done, r in
                    onProgress(CpuProgress(kernel: name, fraction: (Double(k) + done) / Double(ids.count), rate: r, threads: threads))
                }
            } else {
                rate = try single(CoreKernel(id), seconds: test.secondsPerKernel, isActive: isActive) { done, r in
                    onProgress(CpuProgress(kernel: name, fraction: (Double(k) + done) / Double(ids.count), rate: r, threads: 1))
                }
            }
            rates.append((name, rate))
        }
        return CpuTestRun(test: test, rates: rates)
    }

    private func single(_ kernel: CoreKernel, seconds: Double, isActive: () -> Bool, report: (Double, Double) -> Void) throws -> Double {
        let expected = try kernel.run()
        var t = DispatchTime.now().uptimeNanoseconds
        while Double(DispatchTime.now().uptimeNanoseconds - t) / 1e9 < Self.warmupS {
            guard try kernel.run() == expected else { throw CpuKernelError(message: "\(kernel.name) result changed") }
        }
        let limit = UInt64(seconds * 1e9)
        var n = 0
        t = DispatchTime.now().uptimeNanoseconds
        var lastReport = t
        while DispatchTime.now().uptimeNanoseconds - t < limit && isActive() {
            guard try kernel.run() == expected else { throw CpuKernelError(message: "\(kernel.name) result changed") }
            n += 1
            let now = DispatchTime.now().uptimeNanoseconds
            if now - lastReport > 150_000_000 {
                lastReport = now
                report(Double(now - t) / Double(limit), Double(n) * 1e9 / Double(now - t))
            }
        }
        return Double(n) * 1e9 / Double(DispatchTime.now().uptimeNanoseconds - t)
    }

    /** Every core runs its own instance at once; the rate is all of them together. */
    private func multi(_ id: Int32, seconds: Double, isActive: () -> Bool, report: (Double, Double) -> Void) throws -> Double {
        let lock = NSLock()
        var ops = 0
        var failure: Error?
        var stopAt = UInt64.max
        let ready = DispatchGroup()
        let done = DispatchGroup()
        let go = DispatchSemaphore(value: 0)
        for _ in 0..<threads {
            ready.enter()
            done.enter()
            let worker = Thread {
                defer { done.leave() }
                let kernel = CoreKernel(id)
                do {
                    let expected = try kernel.run()
                    let w = DispatchTime.now().uptimeNanoseconds
                    while Double(DispatchTime.now().uptimeNanoseconds - w) / 1e9 < Self.warmupS { _ = try kernel.run() }
                    ready.leave()
                    go.wait()
                    while DispatchTime.now().uptimeNanoseconds < lock.withLock({ stopAt }) {
                        guard try kernel.run() == expected else { throw CpuKernelError(message: "\(kernel.name) result changed") }
                        lock.withLock { ops += 1 }
                    }
                } catch {
                    lock.withLock { if failure == nil { failure = error } }
                    ready.leave()
                }
            }
            worker.qualityOfService = .userInitiated
            worker.start()
        }
        ready.wait()
        let limit = UInt64(seconds * 1e9)
        let t0 = DispatchTime.now().uptimeNanoseconds
        lock.withLock { stopAt = t0 + limit }
        for _ in 0..<threads { go.signal() }
        while DispatchTime.now().uptimeNanoseconds - t0 < limit && isActive() {
            Thread.sleep(forTimeInterval: 0.15)
            let el = DispatchTime.now().uptimeNanoseconds - t0
            report(min(1, Double(el) / Double(limit)), Double(lock.withLock { ops }) * 1e9 / Double(el))
        }
        lock.withLock { stopAt = 0 }
        done.wait()
        if let failure { throw failure }
        return Double(ops) * 1e9 / Double(DispatchTime.now().uptimeNanoseconds - t0)
    }
}

// MARK: - Is the phone quiet?

enum QuietProbe {
    /**
     * Share of the CPU this app gets right now, 0..1: one spinning thread per core, each timing its own CPU time
     * against the wall clock (the first 150 ms skipped while parked cores wake). ≈1 on a quiet phone.
     */
    static func cpuShare() -> Float {
        let n = ProcessInfo.processInfo.activeProcessorCount
        let lock = NSLock()
        var shares = [Double](repeating: 1, count: n)
        let done = DispatchGroup()
        let go = DispatchSemaphore(value: 0)
        for i in 0..<n {
            done.enter()
            Thread {
                defer { done.leave() }
                go.wait()
                let t0 = DispatchTime.now().uptimeNanoseconds
                var x: UInt64 = 0
                while DispatchTime.now().uptimeNanoseconds - t0 < 150_000_000 { x = x &* 31 &+ 7 }
                let w1 = DispatchTime.now().uptimeNanoseconds, c1 = clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID)
                while DispatchTime.now().uptimeNanoseconds - t0 < 550_000_000 { x = x &* 31 &+ 7 }
                let w2 = DispatchTime.now().uptimeNanoseconds, c2 = clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID)
                if c2 > c1 { lock.withLock { shares[i] = Double(c2 - c1) / Double(w2 - w1) } }
                if x == 42 { print("") }   // keep the loop
            }.start()
        }
        for _ in 0..<n { go.signal() }
        done.wait()
        return Float(min(1, max(0, shares.reduce(0, +) / Double(n))))
    }
}
