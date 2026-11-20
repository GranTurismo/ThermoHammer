import Foundation

/// Keeps macOS from slowing a measured run. A Mac puts an app it judges idle (its window covered, hidden, or behind
/// another app) into App Nap: its threads are throttled and its timers coalesced, and a score taken then would be low
/// for a reason that has nothing to do with the hardware. While a run holds this, the run is "user initiated" work
/// that macOS leaves at full speed, and the display and the system stay awake. Only on a Mac: the iPhone app is left
/// exactly as it was.
final class RunActivity {
    private let lock = NSLock()
    private var token: NSObjectProtocol?

    func begin(_ reason: String) {
        guard LeaderboardService.isMac else { return }
        lock.withLock {
            guard token == nil else { return }
            token = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .idleDisplaySleepDisabled], reason: reason)
        }
    }

    func end() {
        lock.withLock {
            guard let token else { return }
            ProcessInfo.processInfo.endActivity(token)
            self.token = nil
        }
    }

    deinit { end() }
}
