import Foundation

/// On-device history of finished runs, used only for the "before / after an OS update"
/// comparison. Nothing here is uploaded. Unreadable data degrades to "no history".
final class RunHistoryStore: ObservableObject {
    static let shared = RunHistoryStore()

    @Published private(set) var records: [RunRecord] = []
    @Published private(set) var dismissed: Set<String> = []

    private let fileURL: URL = {
        let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        return paths[0].appendingPathComponent("run_history.json")
    }()
    private let dismissedKey = "updateImpact.dismissed"

    private init() {
        if let data = try? Data(contentsOf: fileURL),
           let decoded = try? JSONDecoder().decode([RunRecord].self, from: data) {
            records = decoded
        }
        dismissed = Set(UserDefaults.standard.stringArray(forKey: dismissedKey) ?? [])
    }

    func add(_ run: RunRecord) {
        records = UpdateImpact.appendCapped(records, run)
        // .atomic writes to a temp file then renames — a crash cannot leave half a file.
        try? JSONEncoder().encode(records).write(to: fileURL, options: .atomic)
    }

    /// The card to show for this phone and OS right now, or nil.
    func insight(deviceModel: String, osVersion: String, osBuild: String?) -> UpdateImpact.Insight? {
        guard let i = UpdateImpact.insight(history: records, deviceModel: deviceModel,
                                           osVersion: osVersion, osBuild: osBuild),
              !dismissed.contains(i.dismissKey) else { return nil }
        return i
    }

    func dismiss(_ insight: UpdateImpact.Insight) {
        dismissed.insert(insight.dismissKey)
        UserDefaults.standard.set(Array(dismissed.suffix(50)), forKey: dismissedKey)
    }
}
