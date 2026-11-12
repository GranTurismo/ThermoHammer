import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SHARE LINKS — the website's addresses for a run, the texts that go with a
// shared card, and the names of the files the app writes for sharing. Pure
// logic, Foundation only: the same URLs and words as the Android and Windows
// apps ("PC" there, "Mac" here on a Mac, where phones say "phone").
// ─────────────────────────────────────────────────────────────────────────────

enum ShareLinks {
    static let site = "https://thermohammer.gtgroup.dev"
    static let playStore = "https://play.google.com/store/apps/details?id=com.granturismo.thermohammer"
    static let appStore = "https://apps.apple.com/app/thermohammer/id6791439615"

    /// A benchmark run's page; the site's front page while the run has no leaderboard id (never a broken link).
    static func run(_ serverId: Int?) -> String { serverId.map { "\(site)/run/\($0)/" } ?? "\(site)/" }
    /// A stress run's page, or the front page.
    static func stress(_ serverId: Int?) -> String { serverId.map { "\(site)/stress/\($0)/" } ?? "\(site)/" }
    /// The verified health certificate of a submitted benchmark run.
    static func certificate(_ serverId: Int) -> String { "\(site)/cert/\(serverId)/" }

    /// How a link is printed on an image: "thermohammer.gtgroup.dev/run/123" — no scheme, no trailing slash.
    static func display(_ url: String) -> String {
        var s = url
        for scheme in ["https://", "http://"] where s.hasPrefix(scheme) { s = String(s.dropFirst(scheme.count)) }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    /// "My iPhone 17 Pro scored 31,245 on the ThermoHammer benchmark. Can your phone beat it? …" — `total` already
    /// formatted the way the app shows it; `mine` false for a leaderboard run that isn't this device's.
    static func benchmarkText(device: String, total: String, url: String, mine: Bool, noun: String) -> String {
        mine ? "My \(device) scored \(total) on the ThermoHammer benchmark. Can your \(noun) beat it? \(url)"
             : "\(device) scored \(total) on ThermoHammer. Can your \(noun) beat it? \(url)"
    }

    /// "My iPhone 17 Pro kept 87% of its speed through a 5-minute ThermoHammer stress test. …" — capacity rounded.
    static func stressText(device: String, capacity: Double, minutes: Int, url: String, mine: Bool, noun: String) -> String {
        let kept = "kept \(Int(capacity.rounded()))% of its speed through a \(minutes)-minute ThermoHammer stress test."
        return mine ? "My \(device) \(kept) Can your \(noun) do better? \(url)" : "\(device) \(kept) \(url)"
    }

    static func certificateText(device: String, url: String) -> String { "Health certificate for my \(device): \(url)" }

    /// The share card's footer: "CAN YOUR PHONE BEAT THIS?"
    static func challenge(noun: String) -> String { "CAN YOUR \(noun.uppercased()) BEAT THIS?" }

    /// "iPhone 17 Pro" → "iphone-17-pro": lowercase letters and digits, everything else one dash.
    static func slug(_ device: String) -> String {
        var out = "", dash = false
        for ch in device.lowercased() {
            if ch.isASCII && (ch.isLetter || ch.isNumber) { out.append(ch); dash = false }
            else if !dash && !out.isEmpty { out.append("-"); dash = true }
        }
        while out.hasSuffix("-") { out.removeLast() }
        return out.isEmpty ? "device" : out
    }

    /// "20261112-1530" (local time, Gregorian, whatever the phone's calendar).
    static func fileStamp(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.dateFormat = "yyyyMMdd-HHmm"
        return f.string(from: date)
    }

    /// thermohammer-{benchmark|stress}-{device slug}-{yyyyMMdd-HHmm}.csv
    static func csvFileName(stress: Bool, device: String, date: Date) -> String {
        "thermohammer-\(stress ? "stress" : "benchmark")-\(slug(device))-\(fileStamp(date)).csv"
    }

    /// thermohammer-{run|stress}-{id or timestamp}.png
    static func imageFileName(stress: Bool, serverId: Int?, date: Date) -> String {
        "thermohammer-\(stress ? "stress" : "run")-\(serverId.map(String.init) ?? String(Int(date.timeIntervalSince1970))).png"
    }

    /// "12 Nov 2026" on every card, in English like the rest of it.
    static func cardDate(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "d MMM yyyy"
        return f.string(from: date)
    }
}
