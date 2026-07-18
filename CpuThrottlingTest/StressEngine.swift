import Foundation
import SwiftUI
import Combine
import UIKit

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
    @Published var chartPoints: [StabilityPoint] = []
    @Published var thermalEvents: [ThermalEvent] = []
    @Published var currentThermalState: ProcessInfo.ThermalState = .nominal
    @Published var wasCancelledDueToBackground = false
    @Published var wasCompleted = false
    
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
    
    func startTest(duration: TestDuration) {
        guard !isRunning else { return }
        
        wasCancelledDueToBackground = false
        wasCompleted = false
        recordedStamps = []
        statsSampleCount = 0
        testDuration = duration
        elapsedTime = 0
        overallStability = 100.0
        coreImpacts = Array(repeating: 100.0, count: coreCount)
        chartPoints = [StabilityPoint(time: 0, score: 100.0)]
        thermalEvents = []
        
        // Reset counters and baselines
        for i in 0..<coreCount {
            counters[i].reset()
            prevCounterValues[i] = 0
            coreBaselines[i] = 1.0
        }
        overallBaseline = 1.0
        
        // Add initial event
        addThermalEvent(state: currentThermalState)
        
        isRunning = true
        threadKeepAlive = true
        
        // Spawn stress threads
        for coreIndex in 0..<coreCount {
            let counter = counters[coreIndex]
            
            Thread.detachNewThread { [weak self] in
                var a: UInt64 = 0xAAAAAAAAAAAA
                var b: UInt64 = 0x555555555555
                
                // Set thread properties to indicate maximum system priority
                Thread.current.qualityOfService = .userInteractive
                
                // Repetitive work loop
                while let self = self, self.threadKeepAlive {
                    for _ in 0..<50_000 {
                        a = a ^ b
                        b = b &+ 1
                        a = a &* 3
                        b = b ^ a
                        a = a &+ 7
                    }
                    counter.add(50_000)
                }
                
                // Prevent dead code elimination
                let result = a &+ b
                if result == 0 {
                    print("Optimizer fallback: \(result)")
                }
            }
        }
        
        // Start timers
        // 1. Stats gatherer: updates every 250ms
        statsTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            self?.gatherStats()
        }
        
        // 2. Stopwatch: ticks every 1 second
        timeTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.tickTimer()
        }

        // Prevent screen lock
        DispatchQueue.main.async {
            UIApplication.shared.isIdleTimerDisabled = true
        }
    }
    
    func stopTest() {
        guard isRunning else { return }
        
        isRunning = false
        threadKeepAlive = false
        
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
        
        // Calculate overall stability score
        let newStability = (totalSpeed / overallBaseline) * 100.0
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.isRunning else { return }
            self.coreImpacts = newImpacts
            self.overallStability = min(100.0, max(0.0, newStability))
            
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
