import Foundation
import UIKit
#if !DEBUG
import FirebaseAnalytics
import FirebaseCore
import FirebaseCrashlytics
#endif

/// Production-only Firebase Analytics and Crashlytics wrapper.
/// Never called from worker loops or measured ticks — only at run boundaries and uploads.
enum AppTelemetry {
    private static var configured = false

    private static var isAutomatedOrTestLaunch: Bool {
        let env = ProcessInfo.processInfo.environment
        if env["XCTestConfigurationFilePath"] != nil { return true }
        let args = ProcessInfo.processInfo.arguments
        return args.contains("-autoBenchmark") || args.contains("-benchFrom") ||
               args.contains("-welcomePage") || args.contains("-welcomeTour") ||
               args.contains("-lostSpeed")
    }

    private static func clip(_ s: String, maxLen: Int = 100) -> String {
        s.count <= maxLen ? s : String(s.prefix(maxLen))
    }

    static func configureIfProduction() {
        #if !DEBUG
        guard !configured, !isAutomatedOrTestLaunch else { return }
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else { return }
        FirebaseApp.configure()
        configured = true

        let platform = LeaderboardService.isMac ? "ipados_on_mac" : "ios"
        let model = LeaderboardService.shared.getDeviceModelName()
        let soc = StressEngine.socModel()
        let ramGb = Int((Double(ProcessInfo.processInfo.physicalMemory) / 1e9).rounded())
        let osVer = "\(LeaderboardService.isMac ? "macOS/iPadOS" : "iOS") \(UIDevice.current.systemVersion)"
        let appVer = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"

        Analytics.setUserProperty(platform, forName: "app_platform")
        Analytics.setUserProperty(clip(model, maxLen: 36), forName: "device_model")
        if !soc.isEmpty { Analytics.setUserProperty(clip(soc, maxLen: 36), forName: "soc_or_cpu") }
        Analytics.setUserProperty("\(ramGb)", forName: "ram_gb")
        Analytics.setUserProperty(clip(osVer, maxLen: 36), forName: "os_version")
        Analytics.setUserProperty(clip(appVer, maxLen: 36), forName: "app_version")

        let crash = Crashlytics.crashlytics()
        crash.setCustomValue(platform, forKey: "app_platform")
        crash.setCustomValue(model, forKey: "device_model")
        if !soc.isEmpty { crash.setCustomValue(soc, forKey: "soc_or_cpu") }
        setPhaseContext(mode: "idle", section: "none")
        #endif
    }

    static func setPhaseContext(mode: String, section: String) {
        #if !DEBUG
        guard configured else { return }
        let crash = Crashlytics.crashlytics()
        crash.setCustomValue(mode, forKey: "active_mode")
        crash.setCustomValue(section, forKey: "active_section")
        #endif
    }

    static func clearPhaseContext() {
        setPhaseContext(mode: "idle", section: "none")
    }

    static func logStressStarted(mode: String, durationMin: Int, threading: String) {
        setPhaseContext(mode: "stress", section: "stress_\(mode)")
        #if !DEBUG
        guard configured else { return }
        Analytics.logEvent("stress_started", parameters: [
            "mode": clip(mode),
            "duration_min": durationMin,
            "threads": clip(threading),
        ])
        #endif
    }

    static func logStressFinished(mode: String, durationMin: Int, status: String, verified: Bool, capacity: Double) {
        clearPhaseContext()
        #if !DEBUG
        guard configured else { return }
        Analytics.logEvent("stress_finished", parameters: [
            "mode": clip(mode),
            "duration_min": durationMin,
            "status": clip(status),
            "verified": verified ? 1 : 0,
            "delivered_capacity": capacity,
        ])
        #endif
    }

    static func logBenchmarkStarted(partial: Bool) {
        setPhaseContext(mode: "benchmark", section: "preparing")
        #if !DEBUG
        guard configured else { return }
        Analytics.logEvent("benchmark_started", parameters: [
            "mode": partial ? "partial" : "full",
        ])
        #endif
    }

    static func logBenchmarkFinished(status: String, totalScore: Int, verified: Bool, durationSec: Int) {
        clearPhaseContext()
        #if !DEBUG
        guard configured else { return }
        Analytics.logEvent("benchmark_finished", parameters: [
            "status": clip(status),
            "total_score": totalScore,
            "verified": verified ? 1 : 0,
            "duration_s": durationSec,
        ])
        #endif
    }

    static func logResultUploaded(kind: String, success: Bool) {
        #if !DEBUG
        guard configured else { return }
        Analytics.logEvent("result_uploaded", parameters: [
            "kind": clip(kind),
            "success": success ? 1 : 0,
        ])
        #endif
    }
}
