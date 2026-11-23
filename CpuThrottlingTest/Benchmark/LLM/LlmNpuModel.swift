import Foundation
import CryptoKit
import AppleArchive
import System

/**
 * The Neural Engine LLM's model (scoring version 5, AneLlm): Gemma 3 1B for Core ML at 4 bits, ~1 GB, downloaded once
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
    /** Where it comes from; TH_NPU_MODEL_URL overrides it for a local test server (the SHA-256 check still applies). */
    static var downloadURL: URL {
        if let s = ProcessInfo.processInfo.environment["TH_NPU_MODEL_URL"], let url = URL(string: s) { return url }
        return URL(string: "https://thermohammer.gtgroup.dev/models/\(archiveName)")!
    }
    static let sha256 = "af5e93c140f1a3b991b50062776b49626094213630a995f78c33b8d610e4ad2c"
    static let sizeBytes: Int64 = 996_866_911

    enum State: Equatable {
        case unknown
        case ready                     // downloaded and compiled for this device's Neural Engine
        case downloading(Double)       // 0…1
        case waitingForWifi
        case preparing                 // downloaded; compiling for the Neural Engine (once, minutes)
        case failed(String)            // the download or its check failed; tried again after a pause
        case cannotRun(String)         // this device's Neural Engine can't run it: the NPU third counts 0
    }

    @Published private(set) var state: State = .unknown

    private let queue = DispatchQueue(label: "llm-npu-model")
    private var session: URLSession?
    private var task: URLSessionDownloadTask?
    /** A download that stopped picks up from here (URLSession's resume data), after a pause (`retryAfter`). */
    private var resumeData: Data?
    private var lastFailureAt: Date?
    private static let retryAfter: TimeInterval = 600

    /** Where the unpacked model lives: Application Support/npu-llm/<model name>/. */
    static var directory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("npu-llm", isDirectory: true).appendingPathComponent(AneLlm.modelName, isDirectory: true)
    }
    private static var marker: URL { directory.appendingPathComponent(".complete-\(archiveName)") }
    /** Written once the model has been compiled for this device's Neural Engine (AneLlm.prepare). */
    private static var preparedMarker: URL { directory.appendingPathComponent(".prepared") }
    /** Compile attempts so far (written before each, so one the app died in counts too). */
    private static var attemptsFile: URL { directory.appendingPathComponent(".prepare-attempts") }
    /** Why this device's Neural Engine couldn't compile it (after two attempts): it counts 0 from then on. */
    private static var unsupportedMarker: URL { directory.appendingPathComponent(".unsupported") }
    private static let maxAttempts = 2

    /** The model's folder once it's complete on the device, else nil. */
    static var readyDirectory: URL? { FileManager.default.fileExists(atPath: marker.path) ? directory : nil }
    /** Downloaded and compiled: what a benchmark needs at its start to run the NPU part. */
    static var isReadyForBenchmark: Bool {
        readyDirectory != nil && FileManager.default.fileExists(atPath: preparedMarker.path)
    }
    /** Why the NPU part can't run on this device at all (it counts 0), or nil. */
    static var cannotRunReason: String? {
        if let limit = NeuralEngineGate.hardwareLimit { return limit }
        if let why = try? String(contentsOf: unsupportedMarker, encoding: .utf8) {
            return "the Neural Engine couldn't compile the model (\(why.prefix(80)))"
        }
        return nil
    }

    override init() {
        super.init()
        if let why = Self.cannotRunReason { state = .cannotRun(why) }
        else if Self.isReadyForBenchmark { state = .ready }
        else if Self.readyDirectory != nil { state = .preparing }
    }

    private var preparing = false

    /**
     * Compiles the model for this device's Neural Engine in the background, once (it takes minutes the first time), so
     * the benchmark's own load takes seconds. Two failed attempts — an error, or the app dying in one — mean this device
     * can't: it counts 0 from then on. Runs on `queue`.
     */
    private func prepareIfNeeded() {
        guard #available(iOS 18.0, macOS 15.0, *), let dir = Self.readyDirectory, !Self.isReadyForBenchmark,
              Self.cannotRunReason == nil, !preparing else { return }
        let attempts = Int((try? String(contentsOf: Self.attemptsFile, encoding: .utf8)) ?? "") ?? 0
        if attempts >= Self.maxAttempts {
            try? "it failed or stopped the app \(attempts) times".write(to: Self.unsupportedMarker, atomically: true, encoding: .utf8)
            publish(.cannotRun(Self.cannotRunReason ?? "it couldn't compile"))
            return
        }
        try? String(attempts + 1).write(to: Self.attemptsFile, atomically: true, encoding: .utf8)
        preparing = true
        publish(.preparing)
        Task.detached(priority: .utility) { [self] in
            let outcome = await AneLlm.prepare(modelDir: dir)
            queue.async { [self] in
                preparing = false
                switch outcome {
                case .success:
                    try? Data().write(to: Self.preparedMarker)
                    try? FileManager.default.removeItem(at: Self.attemptsFile)
                    publish(.ready)
                case .failure(let error):
                    if attempts + 1 >= Self.maxAttempts {
                        try? String(String(describing: error).prefix(200)).write(to: Self.unsupportedMarker, atomically: true, encoding: .utf8)
                        publish(.cannotRun(Self.cannotRunReason ?? "it couldn't compile"))
                    } else {
                        publish(.failed("compiling for the Neural Engine failed; it tries once more at the next launch"))
                    }
                }
            }
        }
    }

    /**
     * Gets the model ready: downloads it if it isn't here (resuming a stopped download, never sooner than `retryAfter`
     * after a failure), or compiles it if it hasn't been. Harmless to call again — the checklist calls it every 1.5 s.
     */
    func start() {
        queue.async { [self] in
            if let why = Self.cannotRunReason { publish(.cannotRun(why)); return }
            if Self.readyDirectory != nil {
                if Self.isReadyForBenchmark { publish(.ready) } else { prepareIfNeeded() }
                return
            }
            guard task == nil else { return }
            if let at = lastFailureAt, Date().timeIntervalSince(at) < Self.retryAfter { return }
            Self.removeStaging()
            let config = URLSessionConfiguration.default
            // Wi-Fi (or Ethernet on a Mac) only: 1 GB on someone's data plan isn't ours to spend
            config.allowsExpensiveNetworkAccess = false
            config.allowsConstrainedNetworkAccess = false
            config.waitsForConnectivity = true
            config.timeoutIntervalForResource = 6 * 3600
            let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
            self.session = session
            let task = resumeData.map { session.downloadTask(withResumeData: $0) } ?? session.downloadTask(with: Self.downloadURL)
            resumeData = nil
            self.task = task
            publish(.downloading(0))
            task.resume()
        }
    }

    private func publish(_ s: State) { DispatchQueue.main.async { self.state = s } }

    /** Half-unpacked folders of an install the app died in (each ~1 GB). */
    private static func removeStaging() {
        let parent = directory.deletingLastPathComponent()
        for item in (try? FileManager.default.contentsOfDirectory(at: parent, includingPropertiesForKeys: nil)) ?? []
        where item.lastPathComponent.hasPrefix(".staging-") {
            try? FileManager.default.removeItem(at: item)
        }
    }

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
        var failure: String?
        do {
            if let http = downloadTask.response as? HTTPURLResponse, http.statusCode != 200 {
                throw Failure("the server answered \(http.statusCode)")
            }
            try Self.install(archive: location)
        } catch {
            failure = String(describing: error)
        }
        queue.async { [self] in
            self.task = nil; session.finishTasksAndInvalidate(); self.session = nil
            if let failure {
                lastFailureAt = Date()
                publish(.failed(failure))
            } else {
                prepareIfNeeded()
            }
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        let resume = (error as NSError).userInfo[NSURLSessionDownloadTaskResumeData] as? Data
        queue.async { [self] in
            self.task = nil; session.invalidateAndCancel(); self.session = nil
            resumeData = resume
            lastFailureAt = Date()
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
        var noBackup = URLResourceValues(); noBackup.isExcludedFromBackup = true
        var parentURL = parent; try? parentURL.setResourceValues(noBackup)       // staging and model: never in iCloud
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
