namespace ThermoHammer.Engine;

/// <summary>
/// When to ask for a Microsoft Store rating. The app asks as the result of a run that just finished is closed, and only
/// after a run worth being pleased with: verified, with every part scored. Windows shows its rating dialog every time
/// it is asked, so the limits are the app's own: a gap between asks, a cap on them, and never again once the user rated.
/// The same rule as Android's ReviewPolicy.kt and iOS's ReviewPrompt.swift (which cannot know whether the user rated).
/// </summary>
public static class ReviewPolicy
{
    public static readonly TimeSpan MinGap = TimeSpan.FromDays(120);
    public const int MaxAsks = 3;

    /// <summary><paramref name="lastAskedAt"/> is null when the app never asked. A clock set back (now earlier than the last ask) counts as too soon.</summary>
    public static bool ShouldAsk(DateTimeOffset now, DateTimeOffset? lastAskedAt, int asks, bool rated, bool verified, bool complete) =>
        verified && complete && !rated && asks < MaxAsks && (lastAskedAt is not { } last || now - last >= MinGap);
}
