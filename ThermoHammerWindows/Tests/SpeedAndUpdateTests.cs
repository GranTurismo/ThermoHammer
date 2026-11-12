using ThermoHammer.Engine;

namespace ThermoHammer.Tests;

public class SpeedMathTests
{
    /// <summary>30 seconds: [burst] for the first 10, then [sustained].</summary>
    private static List<double> Rates(double burst, double sustained) =>
        Enumerable.Range(1, 30).Select(s => s <= 10 ? burst : sustained).ToList();

    private static SpeedResult Check(double burst, double sustained, bool saver = false, bool onAc = true, bool battery = true,
        string mode = "BEST PERFORMANCE", ThermalLevel thermal = ThermalLevel.Nominal) =>
        new(1_731_420_000, Rates(burst, sustained), saver, onAc, battery, mode, thermal);

    [Fact]
    public void Burst_is_the_mean_of_seconds_1_to_5_sustained_the_median_of_11_to_30()
    {
        var rates = new List<double> { 100, 110, 120, 130, 140, 1, 1, 1, 1, 1 };
        rates.AddRange(Enumerable.Range(0, 20).Select(i => i < 10 ? 60.0 : 80.0));
        Assert.Equal(120, SpeedMath.Burst(rates), 6);
        Assert.Equal(70, SpeedMath.Sustained(rates), 6);
        Assert.Equal(1 - 70.0 / 120, SpeedMath.Drop(120, 70), 6);
        Assert.Equal(0, SpeedMath.Drop(0, 5));
    }

    [Fact]
    public void A_fast_pc_on_best_performance_has_nothing_to_fix()
    {
        var d = SpeedMath.Diagnose(Check(1000, 970));
        var f = Assert.Single(d.Findings);
        Assert.Equal(SpeedCause.NoLostSpeed, f.Cause);
        Assert.Equal("NO LOST SPEED FOUND", f.Title);
        Assert.Equal("Your PC kept 97% of its burst speed. Nothing is holding it back.", f.Text);
        Assert.False(d.PowerSettingsHelp);
        Assert.Null(d.Note);
        Assert.Equal(100, SpeedMath.Kept(Check(1000, 1100)));   // never more than all of it
    }

    [Fact]
    public void Every_cause_that_applies_strongest_first()
    {
        var d = SpeedMath.Diagnose(Check(1000, 500, saver: true, onAc: false, mode: "BALANCED"));
        Assert.Equal([SpeedCause.PowerSaving, SpeedCause.PowerMode, SpeedCause.OnBattery, SpeedCause.HeldBackWhileCool], d.Findings.Select(f => f.Cause));
        Assert.Equal("POWER SAVING IS ON", d.Findings[0].Title);
        Assert.Equal("Battery saver caps the CPU. Settings › System › Power & battery › Battery saver — turn it off.", d.Findings[0].Text);
        Assert.Equal("POWER MODE: BALANCED", d.Findings[1].Title);
        Assert.Equal("Settings › System › Power & battery › Power mode — choose Best performance.", d.Findings[1].Text);
        Assert.Equal("ON BATTERY", d.Findings[2].Title);
        Assert.Equal("Many laptops slow the CPU on battery. Plug in and check again.", d.Findings[2].Text);
        Assert.StartsWith("Your PC slows down after a short burst even though it isn't hot", d.Findings[3].Text);
        Assert.EndsWith("Power mode — choose Best performance.", d.Findings[3].Text);
        Assert.True(d.PowerSettingsHelp);
    }

    [Fact]
    public void A_desktop_has_no_battery_to_blame()
    {
        var d = SpeedMath.Diagnose(Check(1000, 990, onAc: false, battery: false, mode: "BEST POWER EFFICIENCY"));
        Assert.Equal([SpeedCause.PowerMode], d.Findings.Select(f => f.Cause));
        Assert.Equal("POWER MODE: BEST POWER EFFICIENCY", d.Findings[0].Title);
    }

    [Fact]
    public void A_drop_of_a_quarter_is_heat_when_hot_and_a_setting_when_cool()
    {
        Assert.Equal(SpeedCause.Heat, SpeedMath.Diagnose(Check(1000, 750, thermal: ThermalLevel.Serious)).Findings.Single().Cause);
        Assert.Equal(SpeedCause.HeldBackWhileCool, SpeedMath.Diagnose(Check(1000, 750, thermal: ThermalLevel.Fair)).Findings.Single().Cause);
        Assert.Equal(SpeedCause.NoLostSpeed, SpeedMath.Diagnose(Check(1000, 760, thermal: ThermalLevel.Critical)).Findings.Single().Cause);
    }

    [Fact]
    public void The_last_benchmark_adds_a_note()
    {
        var d = SpeedMath.Diagnose(Check(1000, 990), new HealthNote(23, "Lenovo ThinkPad X1"));
        Assert.Equal("Your last benchmark was 23% below the median Lenovo ThinkPad X1.", d.Note);
    }

    [Fact]
    public void Two_checks_compared()
    {
        var before = Check(1000, 500);
        var faster = SpeedMath.Compare(before, Check(1000, 660));
        Assert.Equal(SpeedChange.Faster, faster.Change);
        Assert.Equal("1.32× FASTER", faster.Title);
        Assert.Equal("You got back 32% speed.", faster.Text);
        var slower = SpeedMath.Compare(before, Check(1000, 450));
        Assert.Equal(("SLOWER", "It's slower now — change the setting back."), (slower.Title, slower.Text));
        var same = SpeedMath.Compare(before, Check(1000, 540));
        Assert.Equal(("NO CHANGE", "That setting wasn't holding your PC back."), (same.Title, same.Text));
        Assert.Equal(SpeedChange.Faster, SpeedMath.Compare(before, Check(1000, 550)).Change);    // exactly 1.10
        Assert.Equal(SpeedChange.Slower, SpeedMath.Compare(before, Check(1000, 450)).Change);    // exactly 0.90
    }

    [Fact]
    public void A_saved_check_is_compared_with_for_an_hour()
    {
        var r = Check(1, 1);
        Assert.True(SpeedMath.IsFresh(r, r.Timestamp + 59 * 60));
        Assert.False(SpeedMath.IsFresh(r, r.Timestamp + 61 * 60));
    }

    [Fact]
    public void A_stress_run_that_lost_speed_while_cool()
    {
        List<(int, int)> stamps = [(1000, 0), (2000, 0), (60000, 1), (61000, 2)];
        Assert.True(SpeedMath.SlowedWhileCool(70, 60, stamps));      // fair at the onset
        Assert.False(SpeedMath.SlowedWhileCool(70, 61, stamps));     // serious
        Assert.False(SpeedMath.SlowedWhileCool(85, 60, stamps));     // kept enough
        Assert.False(SpeedMath.SlowedWhileCool(70, null, stamps));   // never throttled
    }
}

public class BenchmarkRecordTests
{
    [Fact]
    public void Section_scores_worked_out_again_from_the_record_match_the_run()
    {
        var r = Fixtures.Result();
        var rec = BenchmarkRecord.From(r, "11 24H2", "26100.6584", "ThinkPad X1");
        Assert.Equal(SectionScores.Of(r), rec.Sections());
        Assert.Equal(r.Total, rec.Sections().Total);
        Assert.Equal(r.Total, rec.Score);
        Assert.All(SectionScores.Keys, k => Assert.True(rec.Sections()[k] > 0, k));
        Assert.Equal(("11 24H2", "26100.6584", "ThinkPad X1"), (rec.OsVersion, rec.OsBuild, rec.DeviceModel));
    }

    [Fact]
    public void A_run_without_ray_tracing_or_an_llm()
    {
        var r = Fixtures.Result();
        r.Gpu[2] = GpuTestResult.Unsupported(GpuTest.NeonCityRT);
        r.Llm = null;
        var rec = BenchmarkRecord.From(r, null, null, null);
        Assert.Equal(SectionScores.Of(r), rec.Sections());
        Assert.Equal(0, rec.Sections().Llm);
    }
}

public class BenchmarkUpdateImpactTests
{
    private static readonly DateTime T0 = new(2026, 10, 1, 12, 0, 0);
    private const string Model = "ThinkPad X1";

    [Fact]
    public void Nothing_before_any_update()
    {
        var h = new[] { Fixtures.Record("11 24H2", "26100.1", T0), Fixtures.Record("11 24H2", "26100.1", T0.AddDays(1)) };
        Assert.Null(BenchmarkUpdateImpact.For(h, Model, "11 24H2", "26100.1"));
        Assert.Null(BenchmarkUpdateImpact.For([], Model, "11 24H2", "26100.1"));
    }

    [Fact]
    public void An_update_with_no_run_since_prompts()
    {
        var h = new[] { Fixtures.Record("11 24H2", "26100.1", T0) };
        var p = Assert.IsType<BenchmarkUpdateImpact.Prompt>(BenchmarkUpdateImpact.For(h, Model, "11 24H2", "26100.2"));
        Assert.Equal("Windows 11 24H2 (26100.1) → 11 24H2 (26100.2). Run the benchmark again to see if the update changed your PC.", p.Text);
        Assert.Equal("prompt:11 24H2|26100.1->11 24H2|26100.2", p.DismissKey);
    }

    [Fact]
    public void A_run_saved_without_a_build_is_the_same_os_when_the_version_matches()
    {
        var h = new[] { Fixtures.Record("11 24H2", null, T0) };
        Assert.Null(BenchmarkUpdateImpact.For(h, Model, "11 24H2", "26100.2"));
        Assert.IsType<BenchmarkUpdateImpact.Prompt>(BenchmarkUpdateImpact.For(h, Model, "11 25H2", "26200.1"));
    }

    [Fact]
    public void Only_verified_complete_runs_of_this_model_and_scoring_version_count()
    {
        var unverified = Fixtures.Record("11 24H2", "26100.1", T0, flags: BenchFlag.Interference);
        var other = Fixtures.Record("11 24H2", "26100.1", T0, model: "Another PC");
        var oldVersion = Fixtures.Record("11 24H2", "26100.1", T0);
        oldVersion.Version = BenchmarkScore.Version - 1;
        var incomplete = Fixtures.Record("11 24H2", "26100.1", T0);
        incomplete.Complete = false;
        Assert.Null(BenchmarkUpdateImpact.For([unverified, other, oldVersion, incomplete], Model, "11 24H2", "26100.2"));
        var noModel = Fixtures.Record("11 24H2", "26100.1", T0, model: null);   // saved before the model was kept: this PC's
        Assert.NotNull(BenchmarkUpdateImpact.For([noModel], Model, "11 24H2", "26100.2"));
    }

    [Fact]
    public void Slower_after_the_update_with_each_sections_change()
    {
        var h = new List<BenchmarkRecord>();
        for (int i = 0; i < 4; i++) h.Add(Fixtures.Record("11 24H2", "26100.1", T0.AddDays(i)));
        h.Add(Fixtures.Record("11 24H2", "26100.2", T0.AddDays(10), cpuScale: 0.5));
        h.Add(Fixtures.Record("11 24H2", "26100.2", T0.AddDays(11), cpuScale: 0.5));
        var c = Assert.IsType<BenchmarkUpdateImpact.Comparison>(BenchmarkUpdateImpact.For(h, Model, "11 24H2", "26100.2"));
        Assert.Equal(3, c.Before.Runs.Count);   // the newest 3 a side
        Assert.Equal(2, c.After.Runs.Count);
        Assert.Equal(BenchmarkUpdateImpact.Verdict.Slower, c.Verdict);
        double expected = ((double)h[^1].Score / h[0].Score - 1) * 100;
        Assert.Equal(expected, c.DeltaPct, 6);
        Assert.Equal($"{BenchmarkUpdateImpact.Pct(-expected)}% slower than before the update", c.Text);
        Assert.Equal(["cpu", "mem", "gpu", "ux", "ai", "llm"], c.Sections.Select(s => s.Key));
        Assert.True(c.Sections[0].DeltaPct < -40);
        Assert.All(c.Sections.Skip(1), s => Assert.Equal(0, s.DeltaPct, 6));
        Assert.Equal("result:11 24H2|26100.1->11 24H2|26100.2", c.DismissKey);
    }

    [Fact]
    public void Under_three_percent_is_no_clear_change()
    {
        var before = Fixtures.Record("11 24H2", "26100.1", T0);
        var after = Fixtures.Record("11 24H2", "26100.2", T0.AddDays(1));
        after.Score = (int)(before.Score * 1.02);
        var c = Assert.IsType<BenchmarkUpdateImpact.Comparison>(BenchmarkUpdateImpact.For([before, after], Model, "11 24H2", "26100.2"));
        Assert.Equal(BenchmarkUpdateImpact.Verdict.NoClearChange, c.Verdict);
        Assert.Equal("NO CLEAR CHANGE", c.Title);
        after.Score = (int)(before.Score * 1.05);
        var f = Assert.IsType<BenchmarkUpdateImpact.Comparison>(BenchmarkUpdateImpact.For([before, after], Model, "11 24H2", "26100.2"));
        Assert.Equal(BenchmarkUpdateImpact.Verdict.Faster, f.Verdict);
        Assert.EndsWith("% faster than before the update", f.Text);
    }

    [Fact]
    public void The_card_changes_when_a_run_is_added()
    {
        var before = Fixtures.Record("11 24H2", "26100.1", T0);
        var after = Fixtures.Record("11 24H2", "26100.2", T0.AddDays(1));
        var one = BenchmarkUpdateImpact.For([before, after], Model, "11 24H2", "26100.2")!;
        var two = BenchmarkUpdateImpact.For([before, after, Fixtures.Record("11 24H2", "26100.2", T0.AddDays(2))], Model, "11 24H2", "26100.2")!;
        Assert.Equal(one.DismissKey, two.DismissKey);
        Assert.NotEqual(one.Signature, two.Signature);
    }
}
