import Foundation

/**
 When to ask for an App Store rating. The app asks as the result of a run that just finished is closed, and only after
 a run worth being pleased with: verified, with every part scored. Apple shows its rating sheet at most three times a
 year and tells the app nothing about it, so the app keeps its own gap between asks and a cap on them.
 The same rule as Android's ReviewPolicy.kt and Windows' Engine/ReviewPolicy.cs.
 */
enum ReviewPolicy {
    static let minGap: TimeInterval = 120 * 24 * 60 * 60
    static let maxAsks = 3

    /// `lastAskedAt` is nil when the app never asked. A clock set back (now earlier than the last ask) counts as too soon.
    static func shouldAsk(now: Date, lastAskedAt: Date?, asks: Int, verified: Bool, complete: Bool) -> Bool {
        guard verified, complete, asks < maxAsks else { return false }
        guard let last = lastAskedAt else { return true }
        return now.timeIntervalSince(last) >= minGap
    }
}

/** When the app last asked for a rating and how many times so far, and the one call that asks. */
enum ReviewPrompt {
    private static let lastKey = "review.lastAskedAt"
    private static let asksKey = "review.asks"

    static func lastAskedAt(_ defaults: UserDefaults = .standard) -> Date? {
        let t = defaults.double(forKey: lastKey)
        return t > 0 ? Date(timeIntervalSince1970: t) : nil
    }

    static func asks(_ defaults: UserDefaults = .standard) -> Int { defaults.integer(forKey: asksKey) }

    /**
     Call as the result of a run that just finished is closed. `request` is SwiftUI's `requestReview`: it is called,
     and the ask counted, only when `ReviewPolicy` allows it. Returns whether it asked.
     */
    @discardableResult
    static func onResultClosed(verified: Bool, complete: Bool, now: Date = Date(), defaults: UserDefaults = .standard,
                               request: () -> Void) -> Bool {
        guard ReviewPolicy.shouldAsk(now: now, lastAskedAt: lastAskedAt(defaults), asks: asks(defaults),
                                     verified: verified, complete: complete) else { return false }
        defaults.set(now.timeIntervalSince1970, forKey: lastKey)
        defaults.set(asks(defaults) + 1, forKey: asksKey)
        request()
        return true
    }
}
