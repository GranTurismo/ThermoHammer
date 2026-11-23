import Foundation
import CryptoKit
import AppleArchive
import System

/**
 * The Neural Engine LLM's model (scoring version 5, AneLlm): Gemma 3 1B for Core ML at 4 bits, ~0.7 GB, downloaded once
 * from ThermoHammer's own site — over Wi-Fi only, checked against its SHA-256, unpacked (Apple Archive) into Application
 * Support and kept out of the iCloud backup. The benchmark asks `readyDirectory`; without it the run is incomplete
 * (ValidityFlag.npuModelMissing) where the phone could run the model, the same rule as Android's Play-delivered files.
 *
 * Gemma is provided under and subject to the Gemma Terms of Use (ai.google.dev/gemma/terms); the archive carries the
 * notice and says what was changed (the conversion to Core ML).
 */
final class LlmNpuModel: NSObject, ObservableObject, URLSessionDownloadDelegate, @unchecked Sendable {
    static let shared = LlmNpuModel()

    /** The archive's name doubles as its version: a new conversion gets a new name, never a changed file. */
    static let archiveName = "gemma3-1b-it-lut4-ctx1280-v1.aar"
    static let downloadURL = URL(string: "https://thermohammer.gtgroup.dev/models/\(archiveName)")!
    static let sha256 = "__SHA256__"
    static let sizeBytes: Int64 = 0

    enum State: Equatable {
        case unknown
        case ready
        case downloading(Double)       // 0…1
        case waitingForWifi
        case failed(String)
    }

    @Published private(set) var state: State = .unknown

    private let queue = DispatchQueue(label: "llm-npu-model")
    private var session: URLSession?
    private var task: URLSessionDownloadTask?

    /** Where the unpacked model lives: Application Support/npu-llm/<model name>/. */
    static var directory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("npu-llm", isDirectory: true).appendingPathComponent(AneLlm.modelName, isDirectory: true)
    }
    private static var marker: URL { directory.appendingPathComponent(".complete-\(archiveName)") }

    /** The model's folder once it's complete on the device, else nil. */
    static var readyDirectory: URL? { FileManager.default.fileExists(atPath: marker.path) ? directory : nil }

    override init() {
        super.init()
        state = Self.readyDirectory != nil ? .ready : .unknown
    }

    /** Starts the download if the model isn't here and none is running. Harmless to call again. */
    func start() {
        queue.async { [self] in
            guard Self.readyDirectory == nil, task == nil else { if Self.readyDirectory != nil { publish(.ready) }; return }
            let config = URLSessionConfiguration.default
            // Wi-Fi (or Ethernet on a Mac) only: 0.7 GB on someone's data plan isn't ours to spend
            config.allowsExpensiveNetworkAccess = false
            config.allowsConstrainedNetworkAccess = false
            config.waitsForConnectivity = true
            config.timeoutIntervalForResource = 6 * 3600
            let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
            self.session = session
            let task = session.downloadTask(with: Self.downloadURL)
            self.task = task
            publish(.downloading(0))
            task.resume()
        }
    }

    private func publish(_ s: State) { DispatchQueue.main.async { self.state = s } }

    // MARK: URLSessionDownloadDelegate

    func urlSession(_ session: URLSession, taskIsWaitingForConnectivity task: URLSessionTask) {
        publish(.waitingForWifi)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let total = totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : Self.sizeBytes
        publish(.downloading(min(1, Double(totalBytesWritten) / Double(total))))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // the file at `location` is gone once this returns: check and unpack it here
        let outcome: State
        do {
            if let http = downloadTask.response as? HTTPURLResponse, http.statusCode != 200 {
                throw Failure("the server answered \(http.statusCode)")
            }
            try Self.install(archive: location)
            outcome = .ready
        } catch {
            outcome = .failed(String(describing: error))
        }
        queue.async { [self] in self.task = nil; session.finishTasksAndInvalidate(); self.session = nil; publish(outcome) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        queue.async { [self] in
            self.task = nil; session.invalidateAndCancel(); self.session = nil
            publish(.failed(error.localizedDescription))
        }
    }

    // MARK: install

    struct Failure: Error, CustomStringConvertible { let description: String; init(_ d: String) { description = d } }

    /** Checks the archive's SHA-256, unpacks it beside the final folder, then moves it into place and marks it complete. */
    static func install(archive: URL) throws {
        let digest = try sha256(of: archive)
        guard digest == sha256 else { throw Failure("the download is damaged (SHA-256 \(digest.prefix(12))…)") }
        let fm = FileManager.default
        let parent = directory.deletingLastPathComponent()
        try fm.createDirectory(at: parent, withIntermediateDirectories: true)
        let staging = parent.appendingPathComponent(".staging-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: staging) }
        try extract(archive: archive, into: staging)
        // the archive holds one folder, the model's
        let unpacked = staging.appendingPathComponent(AneLlm.modelName, isDirectory: true)
        guard fm.fileExists(atPath: unpacked.appendingPathComponent("vocab.txt").path) else { throw Failure("the archive has no \(AneLlm.modelName)/") }
        try? fm.removeItem(at: directory)
        try fm.moveItem(at: unpacked, to: directory)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var dir = directory; try? dir.setResourceValues(values)
        try Data().write(to: marker)
    }

    static func sha256(of file: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 8 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func extract(archive: URL, into dest: URL) throws {
        guard let file = ArchiveByteStream.fileStream(path: FilePath(archive.path), mode: .readOnly, options: [],
                                                      permissions: FilePermissions(rawValue: 0o644)) else { throw Failure("can't open the archive") }
        defer { try? file.close() }
        guard let decompress = ArchiveByteStream.decompressionStream(readingFrom: file) else { throw Failure("can't decompress the archive") }
        defer { try? decompress.close() }
        guard let decode = ArchiveStream.decodeStream(readingFrom: decompress) else { throw Failure("can't read the archive") }
        defer { try? decode.close() }
        guard let extract = ArchiveStream.extractStream(extractingTo: FilePath(dest.path), flags: [.ignoreOperationNotPermitted]) else {
            throw Failure("can't unpack the archive")
        }
        defer { try? extract.close() }
        _ = try ArchiveStream.process(readingFrom: decode, writingTo: extract)
    }
}
