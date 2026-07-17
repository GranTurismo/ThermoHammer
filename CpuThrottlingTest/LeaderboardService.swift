import Foundation
import CryptoKit
#if canImport(UIKit)
import UIKit
#endif

struct DeviceHammerStamp: Codable {
    let elapsedMs: Int
    let score: Int
    let thermalState: Int // 0 = Nominal, 1 = Fair, 2 = Serious, 3 = Critical
}

struct HammerPayload: Codable {
    let stamps: [DeviceHammerStamp]
    let type: Int // 0 = 5 min, 1 = 15 min, 2 = 30 min (matching HammerType enum)
    let deviceManufacturer: String
    let deviceModel: String
    let os: Int // 1 = iOS (matching OsPlatform enum)
    let osVersion: String
    let sessionId: Int
    let hash: String
}

struct SessionResponse: Codable {
    let id: Int
    let encryptionKey: String
}

struct ThermoHasher {
    static func baseize(_ txt: String) -> String {
        var bytes = [UInt8]()
        for scalar in txt.unicodeScalars {
            let val = scalar.value
            bytes.append(UInt8(val & 0xFF))
            bytes.append(UInt8((val >> 8) & 0xFF))
            bytes.append(UInt8((val >> 16) & 0xFF))
            bytes.append(UInt8((val >> 24) & 0xFF))
        }
        return Data(bytes).base64EncodedString()
    }
    
    static func computeHash(encryptionKey: String, stamps: [DeviceHammerStamp]) -> String {
        var sb = ""
        for stamp in stamps {
            let thermalStateStr: String
            switch stamp.thermalState {
            case 0: thermalStateStr = "Nominal"
            case 1: thermalStateStr = "Fair"
            case 2: thermalStateStr = "Serious"
            case 3: thermalStateStr = "Critical"
            default: thermalStateStr = "Nominal"
            }
            
            sb += baseize(String(stamp.elapsedMs))
            sb += baseize(String(stamp.score))
            sb += baseize(thermalStateStr)
        }
        
        let keyData = Data(encryptionKey.utf8)
        let messageData = Data(sb.utf8)
        
        let symmetricKey = SymmetricKey(data: keyData)
        let signature = HMAC<SHA256>.authenticationCode(for: messageData, using: symmetricKey)
        
        return Data(signature).base64EncodedString()
    }
}

class LeaderboardService {
    static let shared = LeaderboardService()
    
    private let baseURL = URL(string: "https://thapi.gtgroup.dev")!
    
    private init() {}
    
    func createSession() async throws -> SessionResponse {
        let url = baseURL.appendingPathComponent("session")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            throw NSError(domain: "LeaderboardService", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to initialize server session"])
        }
        
        let decoder = JSONDecoder()
        return try decoder.decode(SessionResponse.self, from: data)
    }
    
    func submitScore(payload: HammerPayload) async throws {
        let url = baseURL.appendingPathComponent("hammer")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let encoder = JSONEncoder()
        request.httpBody = try encoder.encode(payload)
        
        let (_, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            throw NSError(domain: "LeaderboardService", code: 2, userInfo: [NSLocalizedDescriptionKey: "Server rejected score submission"])
        }
    }
    
    func getDeviceModelName() -> String {
        #if os(iOS)
        var systemInfo = utsname()
        uname(&systemInfo)
        let machineMirror = Mirror(reflecting: systemInfo.machine)
        let identifier = machineMirror.children.reduce("") { identifier, element in
            guard let value = element.value as? Int8, value != 0 else { return identifier }
            return identifier + String(UnicodeScalar(UInt8(value)))
        }
        
        // Map common codes to user-friendly names
        switch identifier {
        // iPhone SE
        case "iPhone8,4": return "iPhone SE (1st Gen)"
        case "iPhone12,8": return "iPhone SE (2nd Gen)"
        case "iPhone14,6": return "iPhone SE (3rd Gen)"
        
        // iPhone X / 8 / 8 Plus
        case "iPhone10,1", "iPhone10,4": return "iPhone 8"
        case "iPhone10,2", "iPhone10,5": return "iPhone 8 Plus"
        case "iPhone10,3", "iPhone10,6": return "iPhone X"
        
        // iPhone XS / XR
        case "iPhone11,2": return "iPhone XS"
        case "iPhone11,4", "iPhone11,6": return "iPhone XS Max"
        case "iPhone11,8": return "iPhone XR"
        
        // iPhone 11 Series
        case "iPhone12,1": return "iPhone 11"
        case "iPhone12,3": return "iPhone 11 Pro"
        case "iPhone12,5": return "iPhone 11 Pro Max"
        
        // iPhone 12 Series
        case "iPhone13,1": return "iPhone 12 mini"
        case "iPhone13,2": return "iPhone 12"
        case "iPhone13,3": return "iPhone 12 Pro"
        case "iPhone13,4": return "iPhone 12 Pro Max"
        
        // iPhone 13 Series
        case "iPhone14,2": return "iPhone 13 Pro"
        case "iPhone14,3": return "iPhone 13 Pro Max"
        case "iPhone14,4": return "iPhone 13 mini"
        case "iPhone14,5": return "iPhone 13"
        
        // iPhone 14 Series
        case "iPhone14,7": return "iPhone 14"
        case "iPhone14,8": return "iPhone 14 Plus"
        case "iPhone15,2": return "iPhone 14 Pro"
        case "iPhone15,3": return "iPhone 14 Pro Max"
        
        // iPhone 15 Series
        case "iPhone15,4": return "iPhone 15"
        case "iPhone15,5": return "iPhone 15 Plus"
        case "iPhone16,1": return "iPhone 15 Pro"
        case "iPhone16,2": return "iPhone 15 Pro Max"
        
        // iPhone 16 Series
        case "iPhone17,1": return "iPhone 16 Pro"
        case "iPhone17,2": return "iPhone 16 Pro Max"
        case "iPhone17,3": return "iPhone 16"
        case "iPhone17,4": return "iPhone 16 Plus"
        
        // iPhone 17 Series
        case "iPhone18,1": return "iPhone 17 Pro"
        case "iPhone18,2": return "iPhone 17 Pro Max"
        case "iPhone18,3": return "iPhone 17"
        case "iPhone18,4": return "iPhone 17 Plus"
        case "iPhone18,5": return "iPhone 17e"
        case "iPhone18,6": return "iPhone Air"
        
        // Simulator / Generic Fallbacks
        case "i386", "x86_64", "arm64":
            #if targetEnvironment(simulator)
            return "iPhone Simulator"
            #else
            return "iOS Device"
            #endif
        default:
            if identifier.hasPrefix("iPhone") {
                return "iPhone (\(identifier))"
            } else if identifier.hasPrefix("iPad") {
                return "iPad (\(identifier))"
            }
            return identifier
        }
        #else
        return "macOS Device"
        #endif
    }
    
    func fetchLeaderboard() async throws -> [HammerDto] {
        let url = baseURL.appendingPathComponent("leaderboard")
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            throw NSError(domain: "LeaderboardService", code: 3, userInfo: [NSLocalizedDescriptionKey: "Failed to fetch leaderboard from server"])
        }
        
        let decoder = JSONDecoder()
        return try decoder.decode([HammerDto].self, from: data)
    }
    
    func fetchStamps(for hammerId: Int) async throws -> [DeviceHammerStamp] {
        let url = baseURL.appendingPathComponent("stamps/\(hammerId)")
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let (data, response) = try await URLSession.shared.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            throw NSError(domain: "LeaderboardService", code: 4, userInfo: [NSLocalizedDescriptionKey: "Failed to fetch stamps detail from server"])
        }
        
        let decoder = JSONDecoder()
        return try decoder.decode([DeviceHammerStamp].self, from: data)
    }
}

struct HammerDto: Codable, Identifiable {
    let id: Int
    let stamps: [DeviceHammerStamp]?
    let type: Int
    let deviceManufacturer: String
    let deviceModel: String
    let os: Int
    let osVersion: String
    let stabilityPercentage: Double
}
