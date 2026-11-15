using ThermoHammer.Engine;

namespace ThermoHammer.Tests;

/// <summary>The store-rating ask: only after a verified, complete run, with a gap between asks and a cap — the phones' rule.</summary>
public class ReviewPolicyTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);

    private static bool Ask(double? daysAgo = null, int asks = 0, bool rated = false, bool verified = true, bool complete = true) =>
        ReviewPolicy.ShouldAsk(Now, daysAgo is { } d ? Now - TimeSpan.FromDays(d) : null, asks, rated, verified, complete);

    [Fact]
    public void Asks_after_the_first_good_run() => Assert.True(Ask());

    [Fact]
    public void Never_after_an_unverified_run() => Assert.False(Ask(verified: false));

    [Fact]
    public void Never_after_a_run_with_a_part_missing() => Assert.False(Ask(complete: false));

    [Fact]
    public void Never_again_once_the_user_rated() => Assert.False(Ask(daysAgo: 400, asks: 1, rated: true));

    [Fact]
    public void Waits_out_the_gap()
    {
        Assert.False(Ask(daysAgo: 1, asks: 1));
        Assert.False(Ask(daysAgo: 119, asks: 1));
        Assert.False(ReviewPolicy.ShouldAsk(Now, Now - ReviewPolicy.MinGap + TimeSpan.FromSeconds(1), 1, false, true, true));
    }

    [Fact]
    public void Asks_again_once_the_gap_is_over()
    {
        Assert.True(ReviewPolicy.ShouldAsk(Now, Now - ReviewPolicy.MinGap, 1, false, true, true));
        Assert.True(Ask(daysAgo: 200, asks: 2));
    }

    [Fact]
    public void Stops_after_three_asks()
    {
        Assert.False(Ask(daysAgo: 400, asks: ReviewPolicy.MaxAsks));
        Assert.False(Ask(daysAgo: 400, asks: ReviewPolicy.MaxAsks + 1));
    }

    [Fact]
    public void A_clock_set_back_counts_as_too_soon() => Assert.False(Ask(daysAgo: -5, asks: 1));

    [Fact]
    public void The_gap_is_four_months() => Assert.Equal(TimeSpan.FromDays(120), ReviewPolicy.MinGap);
}
