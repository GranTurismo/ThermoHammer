import Foundation
import SQLite3

// ─────────────────────────────────────────────────────────────────────────────
// MEM TESTS — RAM and storage through BenchCore (the same C++ as on Android),
// in short slices so the stage shows a live rate; the database test drives the
// system SQLite exactly as the Android test drives Android's.
// ─────────────────────────────────────────────────────────────────────────────

/** What the stage draws for the measurement running now. */
enum MemAccess { case read, write, copy, randomRead, randomWrite, chase }

struct MemProgress { let measure: String; let fraction: Double; let rate: Double; let unit: String; let access: MemAccess; let detail: String }

struct MemError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

final class MemBench {
    private static let slice = 0.25
    private static let mb = 1_000_000.0
    private static let randomThreads: Int32 = 4
    private static let chats = 40
    private static let history = 20_000

    /** Read, write and copy bandwidth with every core streaming, then the latency of one random access. */
    func ram(isActive: () -> Bool, onProgress: @escaping (MemProgress) -> Void) throws -> RamResult {
        let threads = ProcessInfo.processInfo.activeProcessorCount
        let bytes: Int64 = 256 << 20
        guard let ram = bc_ram_open(bytes, Int32(threads)) else { throw MemError(message: String(cString: bc_mem_error())) }
        defer { bc_ram_close(ram) }
        let detail = "\(bytes >> 20) MB BUFFER · \(threads) THREADS"
        let plan = Plan(test: .ram, seconds: [2, 2, 2, 2.5], onProgress: onProgress)
        let read = plan.run(0, "GB/S", .read, detail, isActive) { bc_ram_run(ram, Int32(BC_RAM_READ), $0) / 1e9 }
        let write = plan.run(1, "GB/S", .write, detail, isActive) { bc_ram_run(ram, Int32(BC_RAM_WRITE), $0) / 1e9 }
        let copy = plan.run(2, "GB/S", .copy, detail, isActive) { bc_ram_run(ram, Int32(BC_RAM_COPY), $0) / 1e9 }
        let latency = plan.run(3, "NS", .chase, "\(bytes >> 20) MB · RANDOM ACCESS · 1 THREAD", isActive) {
            bc_ram_run(ram, Int32(BC_RAM_LATENCY), $0)
        }
        return RamResult(read: read, write: write, copy: copy, latencyNs: latency)
    }

    /**
     * A test file of up to 512 MB (a quarter of the free space at most), written sequentially until it has been
     * written whole at least once, then read back, then random 4 KB reads and writes. Deleted afterwards.
     */
    func storage(isActive: () -> Bool, onProgress: @escaping (MemProgress) -> Void) throws -> StorageResult {
        let dir = FileManager.default.temporaryDirectory
        let free = (try? dir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? 0
        let block: Int64 = 4 << 20
        let bytes = min(max(free / 4, 64 << 20), 512 << 20) / block * block
        let path = dir.appendingPathComponent("membench.bin").path
        guard let disk = bc_disk_open(path, bytes) else { throw MemError(message: String(cString: bc_mem_error())) }
        defer { bc_disk_close(disk) }
        let fileMb = Int(bytes >> 20)
        func io() -> String { bc_disk_direct(disk) != 0 ? "DIRECT I/O" : "CACHED I/O" }   // known once reading starts
        var failure: String?
        func rate(_ v: Double) -> Double {
            if v < 0, failure == nil { failure = String(cString: bc_mem_error()) }
            return max(v, 0)
        }
        let plan = Plan(test: .storage, seconds: [3, 2.5, 2.5, 2.5], onProgress: onProgress)
        // at least one whole pass, so every block read later really is on the flash
        let seqWrite = plan.run(0, "MB/S", .write, "\(fileMb) MB FILE · 4 MB BLOCKS · SYNCED", isActive,
                                more: { el in bc_disk_passes(disk) < 1 || (el < 3 && bc_disk_passes(disk) < 2) }) {
            rate(bc_disk_run(disk, Int32(BC_DISK_SEQ_WRITE), 1, $0)) / Self.mb
        }
        let seqRead = plan.run(1, "MB/S", .read, "\(fileMb) MB FILE · 4 MB BLOCKS", isActive) {
            rate(bc_disk_run(disk, Int32(BC_DISK_SEQ_READ), 1, $0)) / Self.mb
        }
        let randomRead = plan.run(2, "IOPS", .randomRead, "4 KB · \(Self.randomThreads) THREADS · \(io())", isActive) {
            rate(bc_disk_run(disk, Int32(BC_DISK_RANDOM_READ), Self.randomThreads, $0))
        }
        let randomWrite = plan.run(3, "IOPS", .randomWrite, "4 KB · \(Self.randomThreads) THREADS · \(io())", isActive) {
            rate(bc_disk_run(disk, Int32(BC_DISK_RANDOM_WRITE), Self.randomThreads, $0))
        }
        if let failure { throw MemError(message: failure) }
        return StorageResult(seqRead: seqRead, seqWrite: seqWrite, randomRead: randomRead, randomWrite: randomWrite,
                             direct: bc_disk_direct(disk) != 0, fileMb: fileMb)
    }

    /**
     * SQLite like a messaging app (WAL, synchronous NORMAL — Android's own settings): a chat history of 20,000
     * messages, then new messages saved one transaction each, messages marked read one transaction each, and chats
     * opened (the latest 30 messages of a random chat, read row by row).
     */
    func database(isActive: () -> Bool, onProgress: @escaping (MemProgress) -> Void) throws -> DbResult {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent("membench.db").path
        func remove() { for s in ["", "-wal", "-shm", "-journal"] { try? FileManager.default.removeItem(atPath: path + s) } }
        remove()
        var db: OpaquePointer?
        guard sqlite3_open(path, &db) == SQLITE_OK, let db else { throw MemError(message: "SQLite open failed") }
        defer { sqlite3_close(db); remove() }
        func exec(_ sql: String) throws {
            if sqlite3_exec(db, sql, nil, nil, nil) != SQLITE_OK { throw MemError(message: String(cString: sqlite3_errmsg(db))) }
        }
        func prepare(_ sql: String) throws -> OpaquePointer {
            var st: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK, let st else { throw MemError(message: String(cString: sqlite3_errmsg(db))) }
            return st
        }
        try exec("PRAGMA journal_mode=WAL")
        try exec("PRAGMA synchronous=NORMAL")
        try exec("CREATE TABLE messages(id INTEGER PRIMARY KEY, chat INTEGER NOT NULL, sent INTEGER NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, unread INTEGER NOT NULL)")
        try exec("CREATE INDEX messages_chat_sent ON messages(chat, sent)")
        var rnd = SplitMix(seed: 7)
        let words = ["see", "you", "at", "the", "station", "tomorrow", "photos", "from", "trip", "are", "great", "call", "me",
                     "when", "free", "dinner", "tonight", "running", "late", "sorry", "meeting", "moved", "to", "five"]
        let bodies = (0..<64).map { _ in (0..<(8 + rnd.below(24))).map { _ in words[rnd.below(words.count)] }.joined(separator: " ") }
        let insert = try prepare("INSERT INTO messages(chat, sent, sender, body, unread) VALUES (?, ?, ?, ?, 1)")
        defer { sqlite3_finalize(insert) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        var sent: Int64 = 1_700_000_000_000
        func add() {
            sqlite3_reset(insert)
            sqlite3_bind_int64(insert, 1, Int64(rnd.below(Self.chats)))
            sqlite3_bind_int64(insert, 2, sent); sent += 1
            sqlite3_bind_text(insert, 3, "contact \(rnd.below(200))", -1, transient)
            sqlite3_bind_text(insert, 4, bodies[rnd.below(bodies.count)], -1, transient)
            sqlite3_step(insert)
        }
        try exec("BEGIN")
        for _ in 0..<Self.history { add() }
        try exec("COMMIT")
        let detail = "SQLITE · WAL · SYSTEM SETTINGS"
        let plan = Plan(test: .database, seconds: [2.5, 2, 2], onProgress: onProgress)
        let inserts = plan.run(0, "TX/S", .randomWrite, detail, isActive) { s in Self.perSecond(s) { add() } }
        let update = try prepare("UPDATE messages SET unread = 0 WHERE id = ?")
        defer { sqlite3_finalize(update) }
        let updates = plan.run(1, "TX/S", .randomWrite, detail, isActive) { s in
            Self.perSecond(s) {
                sqlite3_reset(update)
                sqlite3_bind_int64(update, 1, 1 + Int64(rnd.below(Self.history)))
                sqlite3_step(update)
            }
        }
        let query = try prepare("SELECT id, sender, body, sent FROM messages WHERE chat = ? ORDER BY sent DESC LIMIT 30")
        defer { sqlite3_finalize(query) }
        let queries = plan.run(2, "CHATS/S", .randomRead, "SQLITE · LATEST 30 MESSAGES OF A CHAT", isActive) { s in
            Self.perSecond(s) {
                sqlite3_reset(query)
                sqlite3_bind_int64(query, 1, Int64(rnd.below(Self.chats)))
                while sqlite3_step(query) == SQLITE_ROW { _ = sqlite3_column_text(query, 2) }
            }
        }
        return DbResult(insert: inserts, update: updates, query: queries)
    }

    private static func perSecond(_ seconds: Double, _ op: () -> Void) -> Double {
        let t0 = DispatchTime.now().uptimeNanoseconds
        let limit = UInt64(seconds * 1e9)
        var n = 0
        while DispatchTime.now().uptimeNanoseconds - t0 < limit { op(); n += 1 }
        return Double(n) * 1e9 / Double(DispatchTime.now().uptimeNanoseconds - t0)
    }

    /** A test's measurements, each given its share of the progress bar by its planned seconds. */
    private struct Plan {
        let test: MemTest
        let seconds: [Double]
        let onProgress: (MemProgress) -> Void
        var total: Double { seconds.reduce(0, +) }

        /// Runs `slice` (seconds → its rate) until the planned time is up, or for as long as `more` says; returns
        /// the time-weighted mean rate.
        func run(_ index: Int, _ unit: String, _ access: MemAccess, _ detail: String, _ isActive: () -> Bool,
                 more: ((Double) -> Bool)? = nil, slice: (Double) -> Double) -> Double {
            let planned = seconds[index]
            let before = seconds.prefix(index).reduce(0, +)
            var weighted = 0.0, elapsed = 0.0
            onProgress(MemProgress(measure: test.labels[index], fraction: before / total, rate: -1, unit: unit, access: access, detail: detail))
            while isActive() && (more.map { $0(elapsed) } ?? (elapsed < planned - 1e-6)) {
                let s = more != nil ? MemBench.slice : min(MemBench.slice, planned - elapsed)
                let t0 = DispatchTime.now().uptimeNanoseconds
                let r = slice(s)
                let took = Double(DispatchTime.now().uptimeNanoseconds - t0) / 1e9
                weighted += r * took
                elapsed += took
                onProgress(MemProgress(measure: test.labels[index], fraction: (before + min(elapsed, planned)) / total,
                                       rate: weighted / elapsed, unit: unit, access: access, detail: detail))
            }
            return elapsed > 0 ? weighted / elapsed : 0
        }
    }
}

/** splitmix64 — seeded inputs, like BenchCore's. */
struct SplitMix {
    var s: UInt64
    init(seed: UInt64) { s = seed }
    mutating func next() -> UInt64 {
        s &+= 0x9E3779B97F4A7C15
        var z = s
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
    mutating func below(_ n: Int) -> Int { Int(next() % UInt64(n)) }
}
