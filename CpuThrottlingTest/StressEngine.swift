import Foundation
import SwiftUI
import Combine
import UIKit
import Metal

enum TestDuration: Hashable {
    case minutes5
    case minutes15
    case minutes30
    
    var timeInterval: TimeInterval? {
        switch self {
        case .minutes5: return 5 * 60
        case .minutes15: return 15 * 60
        case .minutes30: return 30 * 60
        }
    }
    
    var displayName: String {
        switch self {
        case .minutes5: return "5 Min"
        case .minutes15: return "15 Min"
        case .minutes30: return "30 Min"
        }
    }
}

enum StressThreadingType: Int, Codable, Hashable, CaseIterable {
    case single = 0
    case multi = 1
    
    var displayName: String {
        switch self {
        case .single: return "1 Thread"
        case .multi: return "Multi Thread"
        }
    }
}

class SafeCounter {
    private var lock = os_unfair_lock_s()
    private var value: UInt64 = 0
    
    func add(_ amount: UInt64) {
        os_unfair_lock_lock(&lock)
        value &+= amount
        os_unfair_lock_unlock(&lock)
    }
    
    func get() -> UInt64 {
        os_unfair_lock_lock(&lock)
        let result = value
        os_unfair_lock_unlock(&lock)
        return result
    }
    
    func reset() {
        os_unfair_lock_lock(&lock)
        value = 0
        os_unfair_lock_unlock(&lock)
    }
}

class StressEngine: ObservableObject {
    static let shared = StressEngine()
    
    @Published var isRunning = false
    @Published var elapsedTime: TimeInterval = 0
    @Published var overallStability: Double = 100.0
    @Published var coreImpacts: [Double] = [] // relative impact (0 to 100)
    @Published var gpuImpact: Double = 0.0 // GPU impact percentage (0 to 100)
    @Published var chartPoints: [StabilityPoint] = []
    @Published var thermalEvents: [ThermalEvent] = []
    @Published var currentThermalState: ProcessInfo.ThermalState = .nominal
    @Published var wasCancelledDueToBackground = false
    @Published var wasCompleted = false
    @Published var testThreadingType: StressThreadingType = .multi
    
    private var metalDevice: MTLDevice? = MTLCreateSystemDefaultDevice()
    private var metalCommandQueue: MTLCommandQueue?
    private var metalComputePipelineState: MTLComputePipelineState?

    private func setupMetalPipeline() {
        guard let device = metalDevice else { return }
        metalCommandQueue = device.makeCommandQueue()

        let shaderSource = """
        #include <metal_stdlib>
        using namespace metal;

        kernel void gpu_heavy_stress(device float *outBuf [[buffer(0)]],
                                    uint id [[thread_position_in_grid]]) {
            float4 a = float4(float(id), float(id + 1), float(id + 2), float(id + 3));
            float4 b = float4(1.0001f, 1.0002f, 1.0003f, 1.0004f);

            for (int i = 0; i < 50000; i++) {
                a = fma(a, b, float4(0.0001f));
                b = fma(b, a, float4(0.0002f));
            }

            outBuf[id] = a.x + b.y;
        }
        """

        do {
            let library = try device.makeLibrary(source: shaderSource, options: nil)
            if let kernelFunction = library.makeFunction(name: "gpu_heavy_stress") {
                metalComputePipelineState = try device.makeComputePipelineState(function: kernelFunction)
            }
        } catch {
            print("Metal compilation error: \(error)")
        }
    }
    
    var recordedStamps: [DeviceHammerStamp] = []
    private var statsSampleCount = 0
    
    var sessionId: Int? = nil
    var encryptionKey: String? = nil
    
    var testDuration: TestDuration = .minutes5
    
    let coreCount: Int
    private var counters: [SafeCounter] = []
    private var prevCounterValues: [UInt64] = []
    private var coreBaselines: [Double] = []
    private var overallBaseline: Double = 1.0
    
    private var gpuCounter = SafeCounter()
    private var prevGpuCounter: UInt64 = 0
    private var gpuBaseline: Double = 1.0
    
    private var statsTimer: Timer?
    private var timeTimer: Timer?
    private var threadKeepAlive = false
    
    private var thermalMonitorCancel: AnyCancellable?
    private var backgroundCancel: AnyCancellable?
    
    private init() {
        self.coreCount = ProcessInfo.processInfo.processorCount
        self.coreImpacts = Array(repeating: 100.0, count: coreCount)
        self.coreBaselines = Array(repeating: 1.0, count: coreCount)
        
        for _ in 0..<coreCount {
            counters.append(SafeCounter())
            prevCounterValues.append(0)
        }
        
        setupThermalMonitoring()
        setupBackgroundMonitoring()
    }
    
    private func setupBackgroundMonitoring() {
        backgroundCancel = NotificationCenter.default
            .publisher(for: UIApplication.didEnterBackgroundNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self = self, self.isRunning else { return }
                self.wasCancelledDueToBackground = true
                self.stopTest()
            }
    }
    
    private func setupThermalMonitoring() {
        currentThermalState = ProcessInfo.processInfo.thermalState
        thermalMonitorCancel = NotificationCenter.default
            .publisher(for: ProcessInfo.thermalStateDidChangeNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self = self else { return }
                let newState = ProcessInfo.processInfo.thermalState
                self.currentThermalState = newState
                if self.isRunning {
                    self.addThermalEvent(state: newState)
                }
            }
    }
    
    func startTest(duration: TestDuration, threadingType: StressThreadingType = .multi) {
        guard !isRunning else { return }
        
        wasCancelledDueToBackground = false
        wasCompleted = false
        recordedStamps = []
        statsSampleCount = 0
        testDuration = duration
        testThreadingType = threadingType
        elapsedTime = 0
        overallStability = 100.0
        
        let activeThreadCount = (threadingType == .single) ? 1 : coreCount
        if threadingType == .single {
            var impacts = Array(repeating: 0.0, count: coreCount)
            impacts[0] = 100.0
            coreImpacts = impacts
        } else {
            coreImpacts = Array(repeating: 100.0, count: coreCount)
        }
        chartPoints = [StabilityPoint(time: 0, score: 100.0)]
        thermalEvents = []
        
        // Reset counters and baselines
        for i in 0..<coreCount {
            counters[i].reset()
            prevCounterValues[i] = 0
            coreBaselines[i] = 1.0
        }
        overallBaseline = 1.0
        gpuCounter.reset()
        prevGpuCounter = 0
        gpuBaseline = 1.0
        
        // Add initial event
        addThermalEvent(state: currentThermalState)
        
        isRunning = true
        threadKeepAlive = true
        
        // Spawn stress threads
        for coreIndex in 0..<activeThreadCount {
            let counter = counters[coreIndex]
            
            Thread.detachNewThread { [weak self] in
                var v1: UInt64 = 0xAAAAAAAAAAAAAAAA
                var v2: UInt64 = 0x5555555555555555
                var v3: UInt64 = 0x3333333333333333
                var v4: UInt64 = 0x7777777777777777

                var f1: Double = 1.0000001
                var f2: Double = 2.0000002
                var f3: Double = 3.0000003
                var f4: Double = 4.0000004

                var l1Cache = [UInt64](repeating: 0x123456789ABCDEF0, count: 4096)
                var cacheIdx = 0

                // Set thread properties to indicate maximum system priority
                Thread.current.qualityOfService = .userInteractive

                // Repetitive work loop
                while let self = self, self.threadKeepAlive {
                    for _ in 0..<50_000 {
                        v1 = (v1 ^ (v2 &+ 7)) &* 3
                        f1 = f1.addingProduct(1.0000001, 0.0000001)

                        v2 = (v2 ^ (v3 &+ 13)) &* 5
                        f2 = f2.addingProduct(1.0000002, 0.0000002)

                        v3 = (v3 ^ (v4 &+ 17)) &* 7
                        f3 = f3.addingProduct(1.0000003, 0.0000003)

                        v4 = (v4 ^ (v1 &+ 19)) &* 11
                        f4 = f4.addingProduct(1.0000004, 0.0000004)

                        l1Cache[cacheIdx] = l1Cache[cacheIdx] ^ v1
                        cacheIdx = (cacheIdx + 1) & 4095
                    }
                    counter.add(50_000)
                }

                // Prevent dead code elimination
                let sink = v1 &+ v2 &+ v3 &+ v4 &+ UInt64(bitPattern: Int64(f1))
                if sink == 0 {
                    print("Optimizer fallback: \(sink)")
                }
            }
        }
        
        // Spawn Heavy Metal GPU Compute Workload Thread
        if let device = metalDevice {
            if metalComputePipelineState == nil {
                setupMetalPipeline()
            }

            if let queue = metalCommandQueue, let pipelineState = metalComputePipelineState {
                let bufferSize = 1024 * MemoryLayout<Float>.size
                let outBuffer = device.makeBuffer(length: bufferSize, options: .storageModeShared)

                Thread.detachNewThread { [weak self] in
                    while let self = self, self.threadKeepAlive {
                        if let cmdBuffer = queue.makeCommandBuffer(),
                           let encoder = cmdBuffer.makeComputeCommandEncoder() {
                            encoder.setComputePipelineState(pipelineState)
                            encoder.setBuffer(outBuffer, offset: 0, index: 0)

                            let gridSize = MTLSize(width: 1024, height: 1, depth: 1)
                            let threadGroupSize = MTLSize(width: min(pipelineState.maxTotalThreadsPerThreadgroup, 256), height: 1, depth: 1)
                            encoder.dispatchThreads(gridSize, threadsPerThreadgroup: threadGroupSize)
                            encoder.endEncoding()

                            cmdBuffer.commit()
                            cmdBuffer.waitUntilCompleted()
                            self.gpuCounter.add(1)
                        }
                    }
                }
            }
        }

        // Start timers in common runloop mode to prevent starvation during scroll gestures
        let stats = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            self?.gatherStats()
        }
        RunLoop.main.add(stats, forMode: .common)
        self.statsTimer = stats
        
        let time = Timer(timeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.tickTimer()
        }
        RunLoop.main.add(time, forMode: .common)
        self.timeTimer = time

        // Prevent screen lock
        DispatchQueue.main.async {
            UIApplication.shared.isIdleTimerDisabled = true
        }
    }
    
    func stopTest() {
        guard isRunning else { return }
        
        isRunning = false
        threadKeepAlive = false
        gpuImpact = 0.0
        
        statsTimer?.invalidate()
        timeTimer?.invalidate()
        statsTimer = nil
        timeTimer = nil

        // Re-enable screen lock
        DispatchQueue.main.async {
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }
    
    private func gatherStats() {
        var currentCoreSpeeds: [Double] = []
        var totalSpeed: Double = 0
        
        for i in 0..<coreCount {
            let totalIterations = counters[i].get()
            let delta = Double(totalIterations.subtractingReportingOverflow(prevCounterValues[i]).partialValue)
            prevCounterValues[i] = totalIterations
            
            currentCoreSpeeds.append(delta)
            totalSpeed += delta
        }
        
        // Dynamic baseline calibration
        // If the speed exceeds the baseline, raise the baseline.
        // This handles cases where thread scheduler or CPU cache warm-up makes a core run faster initially.
        for i in 0..<coreCount {
            if currentCoreSpeeds[i] > coreBaselines[i] {
                coreBaselines[i] = currentCoreSpeeds[i]
            }
        }
        if totalSpeed > overallBaseline {
            overallBaseline = totalSpeed
        }
        
        // Calculate core impacts: speed relative to its own baseline
        var newImpacts: [Double] = []
        for i in 0..<coreCount {
            let baseline = coreBaselines[i]
            let speed = currentCoreSpeeds[i]
            let impact = (speed / baseline) * 100.0
            // Cap it at 100% max
            newImpacts.append(min(100.0, max(0.0, impact)))
        }
        
        // Measure real dynamic GPU throughput & relative impact
        let gpuTotal = gpuCounter.get()
        let gpuDelta = Double(gpuTotal.subtractingReportingOverflow(prevGpuCounter).partialValue)
        prevGpuCounter = gpuTotal
        if gpuDelta > gpuBaseline {
            gpuBaseline = gpuDelta
        }
        let measuredGpuImpact = (gpuBaseline > 0) ? (gpuDelta / gpuBaseline) * 100.0 : 100.0

        // Calculate overall stability score
        let newStability = (totalSpeed / overallBaseline) * 100.0

        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.isRunning else { return }
            self.coreImpacts = newImpacts
            self.overallStability = min(100.0, max(0.0, newStability))
            self.gpuImpact = min(100.0, max(0.0, measuredGpuImpact))
            
            self.statsSampleCount += 1
            let elapsed = self.statsSampleCount * 250
            let scoreVal = Int(totalSpeed * 4)
            
            let thermalVal: Int
            switch self.currentThermalState {
            case .nominal: thermalVal = 0
            case .fair: thermalVal = 1
            case .serious: thermalVal = 2
            case .critical: thermalVal = 3
            @unknown default: thermalVal = 0
            }
            
            let stamp = DeviceHammerStamp(elapsedMs: elapsed, score: scoreVal, thermalState: thermalVal)
            self.recordedStamps.append(stamp)
        }
    }
    
    private func tickTimer() {
        elapsedTime += 1
        
        // Append point to stability chart
        let newPoint = StabilityPoint(time: elapsedTime, score: overallStability)
        chartPoints.append(newPoint)
        
        // Check test duration limit
        if let limit = testDuration.timeInterval, elapsedTime >= limit {
            wasCompleted = true
            stopTest()
        }
    }
    
    private func addThermalEvent(state: ProcessInfo.ThermalState) {
        let event = ThermalEvent(time: elapsedTime, state: state)
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.thermalEvents.append(event)
        }
    }
}

struct StabilityPoint: Identifiable, Equatable {
    let id = UUID()
    let time: TimeInterval // in seconds
    let score: Double // 0 to 100
}

struct ThermalEvent: Identifiable, Equatable {
    let id = UUID()
    let time: TimeInterval
    let state: ProcessInfo.ThermalState
    
    var name: String {
        switch state {
        case .nominal: return "NOMINAL"
        case .fair: return "FAIR"
        case .serious: return "SERIOUS"
        case .critical: return "CRITICAL"
        @unknown default: return "UNKNOWN"
        }
    }
    
    var iconName: String {
        switch state {
        case .nominal: return "thermometer.snowflake"
        case .fair: return "thermometer.low"
        case .serious: return "thermometer.medium"
        case .critical: return "thermometer.high"
        @unknown default: return "thermometer"
        }
    }
    
    var color: Color {
        switch state {
        case .nominal: return .green
        case .fair: return .yellow
        case .serious: return .orange
        case .critical: return .red
        @unknown default: return .gray
        }
    }
}
