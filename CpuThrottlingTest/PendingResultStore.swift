import Foundation

struct PendingTestResult: Codable, Identifiable {
    let id: UUID
    let timestamp: Double
    let durationSeconds: Int
    let testDurationType: Int // 0 = 5 min, 1 = 15 min, 2 = 30 min
    let minStability: Double
    let finalStability: Double
    let worstThermalState: Int // 0 = Nominal, 1 = Fair, 2 = Serious, 3 = Critical
    let stamps: [DeviceHammerStamp]
    let deviceModel: String
    let deviceManufacturer: String
    let osVersion: String
    let sessionId: Int
    let encryptionKey: String
}

class PendingResultStore: ObservableObject {
    static let shared = PendingResultStore()
    
    @Published var results: [PendingTestResult] = []
    
    private let fileURL: URL = {
        let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        return paths[0].appendingPathComponent("pending_results.json")
    }()
    
    private init() {
        loadResults()
    }
    
    func saveResult(_ result: PendingTestResult) {
        results.append(result)
        persist()
    }
    
    func deleteResult(id: UUID) {
        results.removeAll { $0.id == id }
        persist()
    }
    
    private func loadResults() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            let data = try Data(contentsOf: fileURL)
            let decoder = JSONDecoder()
            self.results = try decoder.decode([PendingTestResult].self, from: data)
        } catch {
            print("Failed to load pending results: \(error)")
        }
    }
    
    private func persist() {
        do {
            let encoder = JSONEncoder()
            let data = try encoder.encode(results)
            try data.write(to: fileURL, options: [.atomic, .completeFileProtection])
        } catch {
            print("Failed to persist pending results: \(error)")
        }
    }
}
