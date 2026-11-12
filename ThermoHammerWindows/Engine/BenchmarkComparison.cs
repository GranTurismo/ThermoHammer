using System.Globalization;

namespace ThermoHammer.Engine;

/// <summary>Which of the two compared runs leads on one number.</summary>
public enum Lead { A, B, Even, None }

/// <summary>
/// Who leads on one number of two runs, and by how much: <see cref="Advantage"/> is how many percent the better result beats
/// the other (null when only one run has a result, or the other one is zero). Results closer than <see cref="EvenWithin"/>
/// are even. The same rules as the phones' BenchmarkComparison (Android .kt, iOS .swift).
/// </summary>
public readonly record struct Duel(Lead Winner, double? Advantage = null)
{
    public const double EvenWithin = 0.01;
    public static readonly Duel None = new(Lead.None);

    /// <summary>[better] is the server's "higher" or "lower"; anything else is text or context, not a contest. A missing number
    /// means the test didn't run on that device: the other one leads, but not by "100 %".</summary>
    public static Duel Of(double? a, double? b, string? better)
    {
        if (better is not ("higher" or "lower")) return None;
        bool lower = better == "lower";
        bool Has(double? v) => v is { } x && !double.IsNaN(x) && (lower ? x >= 0 : x > 0);
        bool hasA = Has(a), hasB = Has(b);
        if (!hasA && !hasB) return None;
        if (!hasB) return new(Lead.A);
        if (!hasA) return new(Lead.B);
        double x = a!.Value, y = b!.Value;
        if (x == y) return new(Lead.Even, 0);
        bool aLeads = lower ? x < y : x > y;
        double hi = Math.Max(x, y), lo = Math.Min(x, y);
        if (lo <= 0) return new(aLeads ? Lead.A : Lead.B);          // 0 % dropped against 2 %: ahead, no ratio
        double pct = (hi / lo - 1) * 100;
        return hi / lo - 1 < EvenWithin ? new(Lead.Even, pct) : new(aLeads ? Lead.A : Lead.B, pct);
    }

    /// <summary>Points and scores: more is better, 0 means nothing was scored.</summary>
    public static Duel Scores(int? a, int? b) => Of(a, b, "higher");
}

public sealed record ComparedRow(string Label, string? A, string? B, Duel Duel);

public sealed record ComparedGroup(string Title, int? PointsA, int? PointsB, Duel Duel, List<ComparedRow> Rows)
{
    public double ShareA => BenchmarkComparison.Share(PointsA, PointsB);
    public double ShareB => BenchmarkComparison.Share(PointsB, PointsA);
}

public sealed record ComparedSection(string Key, string Title, int? ScoreA, int? ScoreB, Duel Duel, List<ComparedGroup> Groups)
{
    /// <summary>Each side's score as a share of the larger one: what the bars and the radar draw.</summary>
    public double ShareA => BenchmarkComparison.Share(ScoreA, ScoreB);
    public double ShareB => BenchmarkComparison.Share(ScoreB, ScoreA);
    public string Short => Key switch { "cpu" => "CPU", "mem" => "MEM", "gpu" => "GPU", "ux" => "UX", "ai" => "AI", "llm" => "LLM", _ => Title };
}

/// <summary>
/// Two leaderboard runs side by side: every section, group and row of both, matched by the server's keys, titles and labels
/// (so a measurement the server adds later is compared too), each with who leads and by how much.
/// </summary>
public sealed class BenchmarkComparison
{
    public BenchmarkDetail A { get; }
    public BenchmarkDetail B { get; }
    public Duel Total { get; }
    public List<ComparedSection> Sections { get; }

    private BenchmarkComparison(BenchmarkDetail a, BenchmarkDetail b, Duel total, List<ComparedSection> sections)
    {
        A = a; B = b; Total = total; Sections = sections;
    }

    public int WinsA => Sections.Count(s => s.Duel.Winner == Lead.A);
    public int WinsB => Sections.Count(s => s.Duel.Winner == Lead.B);
    public int Even => Sections.Count(s => s.Duel.Winner == Lead.Even);

    /// <summary>Each run's total as a share of both together: the split bar.</summary>
    public double TotalShareA
    {
        get
        {
            int sum = Math.Max(A.TotalScore, 0) + Math.Max(B.TotalScore, 0);
            return sum == 0 ? 0.5 : (double)Math.Max(A.TotalScore, 0) / sum;
        }
    }

    /// <summary>The same two runs the other way round.</summary>
    public BenchmarkComparison Swapped() => Make(B, A);

    /// <summary>One line on the overall result, e.g. "HONOR 600 Pro scores 23% higher overall".</summary>
    public string Headline(string nameA, string nameB) => Total.Winner switch
    {
        Lead.Even => "Dead heat: within 1% of each other overall",
        Lead.A => Total.Advantage is { } pa ? $"{nameA} scores {PercentText(pa)} higher overall" : $"{nameA} wins: the other has no total",
        Lead.B => Total.Advantage is { } pb ? $"{nameB} scores {PercentText(pb)} higher overall" : $"{nameB} wins: the other has no total",
        _ => "Neither run has a total score",
    };

    /// <summary>How the sections went, from the side of whoever leads, e.g. "Ahead in 4 of 6 sections · 1 even".</summary>
    public string SectionsLine()
    {
        int counted = Sections.Count(s => s.Duel.Winner != Lead.None);
        string line = WinsA == WinsB ? $"Sections split {WinsA}–{WinsB}" : $"Ahead in {Math.Max(WinsA, WinsB)} of {counted} sections";
        return Even > 0 ? $"{line} · {Even} even" : line;
    }

    public static BenchmarkComparison Make(BenchmarkDetail a, BenchmarkDetail b)
    {
        var sections = Union(a.Sections, b.Sections, s => s.Key).Select(p =>
        {
            var (sa, sb) = p;
            var groups = Union(sa?.Groups ?? [], sb?.Groups ?? [], g => g.Title).Select(q =>
            {
                var (ga, gb) = q;
                var rows = Union(ga?.Rows ?? [], gb?.Rows ?? [], r => r.Label).Select(w =>
                {
                    var (ra, rb) = w;
                    return new ComparedRow((ra ?? rb)!.Label, ra?.Value, rb?.Value, Duel.Of(ra?.Number, rb?.Number, ra?.Better ?? rb?.Better));
                }).ToList();
                return new ComparedGroup((ga ?? gb)!.Title, ga?.Points, gb?.Points, Duel.Scores(ga?.Points, gb?.Points), rows);
            }).ToList();
            var s = (sa ?? sb)!;
            return new ComparedSection(s.Key, s.Title, sa?.Score, sb?.Score, Duel.Scores(sa?.Score, sb?.Score), groups);
        }).ToList();
        return new BenchmarkComparison(a, b, Duel.Scores(a.TotalScore, b.TotalScore), sections);
    }

    /// <summary>Both lists matched by [key]: A's order first, then whatever only B has.</summary>
    private static List<(T? a, T? b)> Union<T>(List<T> a, List<T> b, Func<T, string> key) where T : class
    {
        var byB = new Dictionary<string, T>();
        foreach (var y in b) byB[key(y)] = y;
        var seen = new HashSet<string>();
        var result = new List<(T?, T?)>();
        foreach (var x in a) if (seen.Add(key(x))) result.Add((x, byB.GetValueOrDefault(key(x))));
        foreach (var y in b) if (seen.Add(key(y))) result.Add((null, y));
        return result;
    }

    internal static double Share(int? x, int? y)
    {
        int top = Math.Max(x ?? 0, y ?? 0);
        return top <= 0 ? 0 : (double)Math.Max(x ?? 0, 0) / top;
    }

    /// <summary>"23%" or "4.2%": one decimal while it's small.</summary>
    public static string PercentText(double pct) =>
        pct >= 9.95 ? pct.ToString("0", CultureInfo.InvariantCulture) + "%" : pct.ToString("0.0", CultureInfo.InvariantCulture) + "%";

    /// <summary>"+23%": the badge next to the better result.</summary>
    public static string AdvantageText(double pct) => "+" + PercentText(pct);
}
