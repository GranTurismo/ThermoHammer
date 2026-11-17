using ThermoHammer.Engine;
using static ThermoHammer.Engine.MyRuns;

namespace ThermoHammer.Tests;

/// <summary>MY RUNS' history — the same rules and words as Android's MyRunsTest (PC where the phones say phone).</summary>
public class MyRunsTests
{
    private const string Pc = "Lenovo ThinkPad X1";
    private int clock;

    private BenchmarkRecord Run(int total, string? model = Pc, int flags = 0, bool submitted = false, int version = 4, int? serverId = null) => new()
    {
        Timestamp = new DateTime(2026, 11, 1).AddMinutes(++clock), Version = version, Score = total, GpuName = "Test GPU",
        ValidityFlags = flags, Submitted = submitted, ServerId = serverId, DeviceModel = model,
    };

    private static History Build(params BenchmarkRecord[] all) => MyRuns.Build(all, Pc, version: 4);

    [Fact]
    public void No_runs()
    {
        var h = Build();
        Assert.Empty(h.Rows);
        Assert.Null(h.Best);
        Assert.Null(h.Latest);
        Assert.Equal(["Your runs are kept only on this PC: the last 100."], Footnotes(h));
    }

    [Fact]
    public void Newest_first_with_the_change_from_the_run_before()
    {
        var a = Run(30000); var b = Run(33000); var c = Run(31350);
        var h = Build(a, b, c);
        Assert.Equal([c, b, a], h.Rows.Select(r => r.Record));
        Assert.Equal(-5.0, h.Rows[0].ChangePct!.Value, 9);
        Assert.Equal(10.0, h.Rows[1].ChangePct!.Value, 9);
        Assert.Null(h.Rows[2].ChangePct);
        Assert.Equal([a, b, c], h.Chronological.Select(r => r.Record));
        Assert.Same(c, h.Latest);
    }

    [Fact]
    public void Stored_out_of_order_still_lists_by_time()
    {
        var a = Run(30000); var b = Run(31000);
        Assert.Equal([b, a], Build(b, a).Rows.Select(r => r.Record));
    }

    [Fact]
    public void Best_is_the_highest_and_the_earlier_on_a_tie()
    {
        var a = Run(30000); var b = Run(32000); var c = Run(32000);
        var h = Build(a, b, c);
        Assert.Same(b, h.Best);
        Assert.Equal([false, true, false], h.Chronological.Select(r => r.IsBest));
    }

    [Fact]
    public void An_unverified_run_can_be_the_best_but_is_counted_apart()
    {
        var a = Run(30000); var b = Run(34000, flags: 4);
        var h = Build(a, b);
        Assert.Same(b, h.Best);
        Assert.Equal(1, h.Verified);
    }

    [Fact]
    public void Status_and_what_can_be_submitted()
    {
        var sent = Run(30000, submitted: true, serverId: 12);
        var unsent = Run(30000);
        var unverified = Run(30000, flags: 1);
        var unverifiedButSent = Run(30000, flags: 1, submitted: true);   // an older app's submit: what the server took
        var h = Build(sent, unsent, unverified, unverifiedButSent).Chronological;
        Assert.Equal([RunStatus.Submitted, RunStatus.NotSubmitted, RunStatus.Unverified, RunStatus.Submitted], h.Select(r => r.Status));
        Assert.Equal([false, true, false, false], h.Select(r => r.CanSubmit));
    }

    [Fact]
    public void Older_versions_and_other_pcs_are_left_out_and_counted()
    {
        var old = Run(9000, version: 3);
        var other = Run(40000, model: "Dell XPS 13");
        var unnamed = Run(30000, model: null);              // saved before 2.1: made on this PC
        var mine = Run(31000);
        var h = Build(old, other, unnamed, mine);
        Assert.Equal([mine, unnamed], h.Rows.Select(r => r.Record));
        Assert.Equal(1, h.OlderVersion);
        Assert.Equal(1, h.OtherPc);
        Assert.Same(mine, h.Best);                          // the other PC's 40,000 is not this PC's best
        Assert.Equal([
            "1 earlier run used an older version of the benchmark, so it isn't shown: its score doesn't compare.",
            "1 run was made on another PC (restored from a backup), so it isn't shown.",
            "Your runs are kept only on this PC: the last 100."], Footnotes(h));
    }

    [Fact]
    public void Plural_footnotes_and_the_phone_wording()
    {
        var h = Build(Run(1, version: 3), Run(2, version: 2), Run(3, model: "A"), Run(4, model: "B"));
        Assert.Equal([
            "2 earlier runs used an older version of the benchmark, so they aren't shown: their scores don't compare.",
            "2 runs were made on another phone (restored from a backup), so they aren't shown.",
            "Your runs are kept only on this phone: the last 100."], Footnotes(h, "phone"));
    }

    [Fact]
    public void A_zero_score_before_has_no_change() => Assert.Null(Build(Run(0), Run(30000)).Rows[0].ChangePct);

    [Fact]
    public void Change_text()
    {
        Assert.Equal("+2.1%", ChangeText(2.06));
        Assert.Equal("−4.0%", ChangeText(-3.96));
        Assert.Equal("±0.0%", ChangeText(0.04));
        Assert.Equal("±0.0%", ChangeText(-0.04));
    }

    [Fact]
    public void The_store_keeps_what_the_screen_promises() => Assert.Equal(100, Kept);

    [Fact]
    public void A_run_need_not_be_complete_to_be_listed() => Assert.NotEmpty(Build(Run(5000)).Rows);
}
