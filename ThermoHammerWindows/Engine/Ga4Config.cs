namespace ThermoHammer.Engine;

/// <summary>
/// Google Analytics 4 Measurement Protocol credentials for Windows release builds (the GA4 property of Firebase project
/// thermohammer-gt, a Web data stream and its Measurement Protocol API secret). The values live in Ga4Secrets.cs next to
/// this file, which git ignores: the repository is public, and an api_secret in it would let anyone send events into the
/// property. Without that file the Windows app sends nothing. Ga4Secrets.cs:
/// <code>
/// namespace ThermoHammer.Engine;
/// internal static partial class Ga4Config
/// {
///     static partial void Load(ref string measurementId, ref string apiSecret)
///     {
///         measurementId = "G-XXXXXXXXXX";
///         apiSecret = "…";
///     }
/// }
/// </code>
/// </summary>
internal static partial class Ga4Config
{
    public static string MeasurementId { get; }
    public static string ApiSecret { get; }

    static Ga4Config()
    {
        string id = "", secret = "";
        Load(ref id, ref secret);
        MeasurementId = id;
        ApiSecret = secret;
    }

    /// <summary>Implemented in the git-ignored Ga4Secrets.cs; without it both values stay empty.</summary>
    static partial void Load(ref string measurementId, ref string apiSecret);
}
