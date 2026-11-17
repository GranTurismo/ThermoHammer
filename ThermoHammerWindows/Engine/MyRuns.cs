using System.Globalization;

namespace ThermoHammer.Engine;

// ─────────────────────────────────────────────────────────────────────────────
// MY RUNS — this PC's own benchmark history, as the screen lists it. Pure logic
// with no Windows calls (the unit tests compile it as it is); the phones follow
// the same rules (Android data/MyRuns.kt):
//  · only runs of the current benchmark version (older versions scored different
//    work, so they don't compare)
//  · only runs made on this PC's model; a run saved without a model (before 2.1)
//    counts as this PC's, like BenchmarkUpdateImpact — a copied history must not
//    mix two machines into one
//  · newest first, each with its change from the run before it
//  · best = the highest total (the earlier run on a tie)
// ─────────────────────────────────────────────────────────────────────────────

public static class MyRuns
{
    /// <summary>The kept history's length (BenchmarkHistoryStore).</summary>
    public const int Kept = 100;

    public enum RunStatus { Submitted, NotSubmitted, Unverified }

    /// <summary>One run as listed: its change from the run before it (null for the first), whether it's the best, and where it stands.</summary>
    public sealed record Row(BenchmarkRecord Record, double? ChangePct, bool IsBest, RunStatus Status)
    {
        public bool CanSubmit => Status == RunStatus.NotSubmitted;
    }

    /// <summary>[Rows] newest first; [OlderVersion] and [OtherPc]: the runs left out, and why.</summary>
    public sealed record History(IReadOnlyList<Row> Rows, int OlderVersion, int OtherPc)
    {
        public BenchmarkRecord? Best => Rows.FirstOrDefault(r => r.IsBest)?.Record;
        public BenchmarkRecord? Latest => Rows.FirstOrDefault()?.Record;
        public int Verified => Rows.Count(r => r.Record.ValidityFlags == 0);
        /// <summary>Oldest first: what the chart draws, left to right.</summary>
        public IReadOnlyList<Row> Chronological => Rows.Reverse().ToList();
    }

    public static History Build(IEnumerable<BenchmarkRecord> all, string deviceModel, int version = BenchmarkScore.Version)
    {
        var every = all.ToList();
        var current = every.Where(r => r.Version == version).ToList();
        var mine = current.Where(r => r.DeviceModel == null || r.DeviceModel == deviceModel).OrderBy(r => r.Timestamp).ToList();
        BenchmarkRecord? best = null;
        foreach (var r in mine) if (best == null || r.Score > best.Score) best = r;   // oldest first: a tie keeps the earlier run
        var rows = mine.Select((r, i) =>
        {
            var before = i > 0 ? mine[i - 1] : null;
            double? change = before is { Score: > 0 } b ? (r.Score - b.Score) * 100.0 / b.Score : null;
            var status = r.Submitted ? RunStatus.Submitted : r.ValidityFlags != 0 ? RunStatus.Unverified : RunStatus.NotSubmitted;
            return new Row(r, change, best != null && r.Id == best.Id, status);
        }).Reverse().ToList();
        return new History(rows, every.Count - current.Count, current.Count - mine.Count);
    }

    /// <summary>"+2.1%", "−4.0%", "±0.0%" — the change from the run before, one decimal (half up, as the phones round).</summary>
    public static string ChangeText(double pct)
    {
        double rounded = Math.Floor(pct * 10 + 0.5) / 10;
        return rounded > 0 ? "+" + rounded.ToString("F1", CultureInfo.InvariantCulture) + "%"
            : rounded < 0 ? "−" + (-rounded).ToString("F1", CultureInfo.InvariantCulture) + "%"
            : "±0.0%";
    }

    /// <summary>The notes under the list: what is not shown, and where the history lives.</summary>
    public static List<string> Footnotes(History h, string device = ShareLinks.Device)
    {
        var notes = new List<string>();
        if (h.OlderVersion > 0) notes.Add(h.OlderVersion == 1
            ? "1 earlier run used an older version of the benchmark, so it isn't shown: its score doesn't compare."
            : $"{h.OlderVersion} earlier runs used an older version of the benchmark, so they aren't shown: their scores don't compare.");
        if (h.OtherPc > 0) notes.Add(h.OtherPc == 1
            ? $"1 run was made on another {device} (restored from a backup), so it isn't shown."
            : $"{h.OtherPc} runs were made on another {device} (restored from a backup), so they aren't shown.");
        notes.Add($"Your runs are kept only on this {device}: the last {Kept}.");
        return notes;
    }
}
