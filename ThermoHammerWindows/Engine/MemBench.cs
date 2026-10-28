using System.Runtime.InteropServices;
using System.Text;

namespace ThermoHammer.Engine;

// ─────────────────────────────────────────────────────────────────────────────
// MEM TESTS — RAM and storage through BenchCore (the same C++ as the phones),
// in short slices so the stage shows a live rate; the database test drives
// Windows' own SQLite (winsqlite3.dll) as the phone tests drive theirs.
// ─────────────────────────────────────────────────────────────────────────────

/// <summary>What the stage draws for the measurement running now.</summary>
public enum MemAccess { Read, Write, Copy, RandomRead, RandomWrite, Chase }

public sealed record MemProgress(string Measure, double Fraction, double Rate, string Unit, MemAccess Access, string Detail);

public sealed class MemBench
{
    private const double Slice = 0.25, Mb = 1_000_000;
    private const int RandomThreads = 4, Chats = 40, History = 20_000;

    /// <summary>Read, write and copy bandwidth with every core streaming, then the latency of one random access.</summary>
    public RamResult Ram(Func<bool> isActive, Action<MemProgress> onProgress)
    {
        int threads = Environment.ProcessorCount;
        long bytes = 256L << 20;
        IntPtr ram = BenchCore.bc_ram_open(bytes, threads);
        if (ram == IntPtr.Zero) throw new BenchException(BenchCore.MemError);
        try
        {
            string detail = $"{bytes >> 20} MB BUFFER · {threads} THREADS";
            var plan = new Plan(MemTest.Ram, [2, 2, 2, 2.5], onProgress);
            double read = plan.Run(0, "GB/S", MemAccess.Read, detail, isActive, s => BenchCore.bc_ram_run(ram, BenchCore.RamRead, s) / 1e9);
            double write = plan.Run(1, "GB/S", MemAccess.Write, detail, isActive, s => BenchCore.bc_ram_run(ram, BenchCore.RamWrite, s) / 1e9);
            double copy = plan.Run(2, "GB/S", MemAccess.Copy, detail, isActive, s => BenchCore.bc_ram_run(ram, BenchCore.RamCopy, s) / 1e9);
            double latency = plan.Run(3, "NS", MemAccess.Chase, $"{bytes >> 20} MB · RANDOM ACCESS · 1 THREAD", isActive,
                s => BenchCore.bc_ram_run(ram, BenchCore.RamLatency, s));
            return new RamResult(read, write, copy, latency);
        }
        finally { BenchCore.bc_ram_close(ram); }
    }

    /// <summary>
    /// A test file of up to 512 MB (a quarter of the free space at most), written sequentially until it has been
    /// written whole at least once, then read back, then random 4 KB reads and writes. Deleted afterwards. Windows opens
    /// it unbuffered, so every transfer reaches the drive.
    /// </summary>
    public StorageResult Storage(Func<bool> isActive, Action<MemProgress> onProgress)
    {
        string dir = Path.GetTempPath();
        long free = 0;
        try { free = new DriveInfo(Path.GetPathRoot(dir)!).AvailableFreeSpace; } catch (Exception) { }
        long block = 4L << 20;
        long bytes = Math.Min(Math.Max(free / 4, 64L << 20), 512L << 20) / block * block;
        string path = Path.Combine(dir, "thermohammer-membench.bin");
        IntPtr disk = BenchCore.bc_disk_open(path, bytes);
        if (disk == IntPtr.Zero) throw new BenchException(BenchCore.MemError);
        try
        {
            int fileMb = (int)(bytes >> 20);
            string Io() => BenchCore.bc_disk_direct(disk) != 0 ? "DIRECT I/O" : "CACHED I/O";
            string? failure = null;
            double Rate(double v)
            {
                if (v < 0 && failure == null) failure = BenchCore.MemError;
                return Math.Max(v, 0);
            }
            var plan = new Plan(MemTest.Storage, [3, 2.5, 2.5, 2.5], onProgress);
            // at least one whole pass, so every block read later really is on the drive
            double seqWrite = plan.Run(0, "MB/S", MemAccess.Write, $"{fileMb} MB FILE · 4 MB BLOCKS · SYNCED", isActive,
                s => Rate(BenchCore.bc_disk_run(disk, BenchCore.DiskSeqWrite, 1, s)) / Mb,
                el => BenchCore.bc_disk_passes(disk) < 1 || (el < 3 && BenchCore.bc_disk_passes(disk) < 2));
            double seqRead = plan.Run(1, "MB/S", MemAccess.Read, $"{fileMb} MB FILE · 4 MB BLOCKS", isActive,
                s => Rate(BenchCore.bc_disk_run(disk, BenchCore.DiskSeqRead, 1, s)) / Mb);
            double randomRead = plan.Run(2, "IOPS", MemAccess.RandomRead, $"4 KB · {RandomThreads} THREADS · {Io()}", isActive,
                s => Rate(BenchCore.bc_disk_run(disk, BenchCore.DiskRandomRead, RandomThreads, s)));
            double randomWrite = plan.Run(3, "IOPS", MemAccess.RandomWrite, $"4 KB · {RandomThreads} THREADS · {Io()}", isActive,
                s => Rate(BenchCore.bc_disk_run(disk, BenchCore.DiskRandomWrite, RandomThreads, s)));
            if (failure != null) throw new BenchException(failure);
            return new StorageResult(seqRead, seqWrite, randomRead, randomWrite, BenchCore.bc_disk_direct(disk) != 0, fileMb);
        }
        finally { BenchCore.bc_disk_close(disk); }
    }

    /// <summary>
    /// SQLite like a messaging app (WAL, synchronous NORMAL — the phones' system settings): a chat history of 20,000
    /// messages, then new messages saved one transaction each, messages marked read one transaction each, and chats
    /// opened (the latest 30 messages of a random chat, read row by row).
    /// </summary>
    public DbResult Database(Func<bool> isActive, Action<MemProgress> onProgress)
    {
        string path = Path.Combine(Path.GetTempPath(), "thermohammer-membench.db");
        void Remove() { foreach (var s in new[] { "", "-wal", "-shm", "-journal" }) try { File.Delete(path + s); } catch (Exception) { } }
        Remove();
        using var db = new Sqlite(path);
        try
        {
            db.Exec("PRAGMA journal_mode=WAL");
            db.Exec("PRAGMA synchronous=NORMAL");
            db.Exec("CREATE TABLE messages(id INTEGER PRIMARY KEY, chat INTEGER NOT NULL, sent INTEGER NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, unread INTEGER NOT NULL)");
            db.Exec("CREATE INDEX messages_chat_sent ON messages(chat, sent)");
            var rnd = new SplitMix(7);
            string[] words = ["see", "you", "at", "the", "station", "tomorrow", "photos", "from", "trip", "are", "great", "call", "me",
                "when", "free", "dinner", "tonight", "running", "late", "sorry", "meeting", "moved", "to", "five"];
            var bodies = Enumerable.Range(0, 64).Select(_ =>
                string.Join(" ", Enumerable.Range(0, 8 + rnd.Below(24)).Select(_ => words[rnd.Below(words.Length)]))).ToArray();
            using var insert = db.Prepare("INSERT INTO messages(chat, sent, sender, body, unread) VALUES (?, ?, ?, ?, 1)");
            long sent = 1_700_000_000_000;
            void Add()
            {
                insert.Reset();
                insert.Bind(1, rnd.Below(Chats));
                insert.Bind(2, sent++);
                insert.Bind(3, $"contact {rnd.Below(200)}");
                insert.Bind(4, bodies[rnd.Below(bodies.Length)]);
                insert.Step();
            }
            db.Exec("BEGIN");
            for (int i = 0; i < History; i++) Add();
            db.Exec("COMMIT");
            string detail = "SQLITE · WAL · SYSTEM SETTINGS";
            var plan = new Plan(MemTest.Database, [2.5, 2, 2], onProgress);
            double inserts = plan.Run(0, "TX/S", MemAccess.RandomWrite, detail, isActive, s => PerSecond(s, Add));
            using var update = db.Prepare("UPDATE messages SET unread = 0 WHERE id = ?");
            double updates = plan.Run(1, "TX/S", MemAccess.RandomWrite, detail, isActive, s => PerSecond(s, () =>
            {
                update.Reset();
                update.Bind(1, 1 + rnd.Below(History));
                update.Step();
            }));
            using var query = db.Prepare("SELECT id, sender, body, sent FROM messages WHERE chat = ? ORDER BY sent DESC LIMIT 30");
            double queries = plan.Run(2, "CHATS/S", MemAccess.RandomRead, "SQLITE · LATEST 30 MESSAGES OF A CHAT", isActive, s => PerSecond(s, () =>
            {
                query.Reset();
                query.Bind(1, rnd.Below(Chats));
                while (query.Step()) query.ColumnText(2);
            }));
            return new DbResult(inserts, updates, queries);
        }
        finally
        {
            db.Dispose();
            Remove();
        }
    }

    private static double PerSecond(double seconds, Action op)
    {
        long t0 = Clock.Ns(), limit = (long)(seconds * 1e9);
        int n = 0;
        while (Clock.Ns() - t0 < limit) { op(); n++; }
        return n * 1e9 / (Clock.Ns() - t0);
    }

    /// <summary>A test's measurements, each given its share of the progress bar by its planned seconds.</summary>
    private sealed class Plan(MemTest test, double[] seconds, Action<MemProgress> onProgress)
    {
        private double Total => seconds.Sum();

        /// <summary>Runs `slice` (seconds → its rate) until the planned time is up, or for as long as `more` says; returns the time-weighted mean rate.</summary>
        public double Run(int index, string unit, MemAccess access, string detail, Func<bool> isActive, Func<double, double> slice,
            Func<double, bool>? more = null)
        {
            double planned = seconds[index], before = seconds.Take(index).Sum();
            double weighted = 0, elapsed = 0;
            var labels = test.Labels();
            onProgress(new MemProgress(labels[index], before / Total, -1, unit, access, detail));
            while (isActive() && (more?.Invoke(elapsed) ?? elapsed < planned - 1e-6))
            {
                double s = more != null ? Slice : Math.Min(Slice, planned - elapsed);
                long t0 = Clock.Ns();
                double r = slice(s);
                double took = (Clock.Ns() - t0) / 1e9;
                weighted += r * took;
                elapsed += took;
                onProgress(new MemProgress(labels[index], (before + Math.Min(elapsed, planned)) / Total, weighted / elapsed, unit, access, detail));
            }
            return elapsed > 0 ? weighted / elapsed : 0;
        }
    }
}

/// <summary>splitmix64 — seeded inputs, like BenchCore's.</summary>
public struct SplitMix(ulong seed)
{
    private ulong s = seed;
    public ulong Next()
    {
        s += 0x9E3779B97F4A7C15;
        ulong z = s;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EB;
        return z ^ (z >> 31);
    }
    public int Below(int n) => (int)(Next() % (ulong)n);
}

/// <summary>Windows' own SQLite (winsqlite3.dll, in every Windows 10 and 11) — the system database, as on the phones.</summary>
internal sealed partial class Sqlite : IDisposable
{
    private const string Dll = "winsqlite3.dll";
    private IntPtr db;

    [LibraryImport(Dll, StringMarshalling = StringMarshalling.Utf8)] private static partial int sqlite3_open(string path, out IntPtr db);
    [LibraryImport(Dll)] private static partial int sqlite3_close(IntPtr db);
    [LibraryImport(Dll, StringMarshalling = StringMarshalling.Utf8)] private static partial int sqlite3_exec(IntPtr db, string sql, IntPtr cb, IntPtr arg, IntPtr err);
    [LibraryImport(Dll, StringMarshalling = StringMarshalling.Utf8)] private static partial int sqlite3_prepare_v2(IntPtr db, string sql, int len, out IntPtr stmt, IntPtr tail);
    [LibraryImport(Dll)] private static partial IntPtr sqlite3_errmsg(IntPtr db);
    [LibraryImport(Dll)] internal static partial int sqlite3_finalize(IntPtr stmt);
    [LibraryImport(Dll)] internal static partial int sqlite3_reset(IntPtr stmt);
    [LibraryImport(Dll)] internal static partial int sqlite3_step(IntPtr stmt);
    [LibraryImport(Dll)] internal static partial int sqlite3_bind_int64(IntPtr stmt, int index, long value);
    [LibraryImport(Dll)] internal static partial int sqlite3_bind_text(IntPtr stmt, int index, IntPtr text, int bytes, IntPtr destructor);
    [LibraryImport(Dll)] internal static partial IntPtr sqlite3_column_text(IntPtr stmt, int col);

    public Sqlite(string path)
    {
        if (sqlite3_open(path, out db) != 0) throw new BenchException("SQLite open failed");
    }

    private string Error => Marshal.PtrToStringUTF8(sqlite3_errmsg(db)) ?? "SQLite error";

    public void Exec(string sql)
    {
        if (sqlite3_exec(db, sql, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero) != 0) throw new BenchException(Error);
    }

    public Statement Prepare(string sql) =>
        sqlite3_prepare_v2(db, sql, -1, out var st, IntPtr.Zero) == 0 ? new Statement(st) : throw new BenchException(Error);

    public void Dispose()
    {
        if (db != IntPtr.Zero) sqlite3_close(db);
        db = IntPtr.Zero;
    }

    internal sealed class Statement(IntPtr st) : IDisposable
    {
        private static readonly IntPtr Transient = new(-1);
        public void Reset() => sqlite3_reset(st);
        public void Bind(int i, long v) => sqlite3_bind_int64(st, i, v);
        public unsafe void Bind(int i, string v)
        {
            int n = Encoding.UTF8.GetByteCount(v);
            byte* buf = stackalloc byte[n + 1];
            Encoding.UTF8.GetBytes(v, new Span<byte>(buf, n));
            sqlite3_bind_text(st, i, (IntPtr)buf, n, Transient);
        }
        /// <summary>One step: true while it returns a row.</summary>
        public bool Step() => sqlite3_step(st) == 100;   // SQLITE_ROW
        public IntPtr ColumnText(int col) => sqlite3_column_text(st, col);
        public void Dispose() => sqlite3_finalize(st);
    }
}
