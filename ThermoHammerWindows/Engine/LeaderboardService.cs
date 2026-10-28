using System.Net.Http;
using System.Net.Http.Json;
using System.Net.NetworkInformation;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace ThermoHammer.Engine;

public sealed record DeviceHammerStamp(int ElapsedMs, long Score, int ThermalState);

/// <summary>POST /hammer — the same fields as the phone apps (HammerPayload).</summary>
public sealed record HammerPayload
{
    public required List<DeviceHammerStamp> Stamps { get; init; }
    public int Type { get; init; }
    public int TestThreadingType { get; init; }
    public required string DeviceManufacturer { get; init; }
    public required string DeviceModel { get; init; }
    public int Os { get; init; } = DeviceInfo.OsPlatform;
    public required string OsVersion { get; init; }
    public int SessionId { get; init; }
    public required string Hash { get; init; }
    public int? SchemaVersion { get; init; }
    public long? BaselineScore { get; init; }
    public double? DeliveredCapacity { get; init; }
    public double? SustainedRatio { get; init; }
    public int? ThrottleOnsetSec { get; init; }
    public int? Confidence { get; init; }
    public int? ValidityFlags { get; init; }
    public string? SocModel { get; init; }
    public string? ClusterTopology { get; init; }
    public string? Governor { get; init; }
    public string? HashV2 { get; init; }
    public string? OsBuild { get; init; }
}

public sealed record SessionResponse(int Id, string EncryptionKey);

/// <summary>One stress run on the board (GET /leaderboard).</summary>
public sealed record HammerDto(int Id, int Type, string DeviceManufacturer, string DeviceModel, int Os, string OsVersion,
    double StabilityPercentage, int? TestThreadingType);

public static class ThermoHasher
{
    /// <summary>Each Unicode scalar as 4 little-endian bytes, base64 — the phones' "baseize".</summary>
    public static string Baseize(string text)
    {
        var bytes = new List<byte>();
        foreach (var r in text.EnumerateRunes())
        {
            int v = r.Value;
            bytes.Add((byte)v); bytes.Add((byte)(v >> 8)); bytes.Add((byte)(v >> 16)); bytes.Add((byte)(v >> 24));
        }
        return Convert.ToBase64String(bytes.ToArray());
    }

    private static string StateName(int s) => s switch { 1 => "Fair", 2 => "Serious", 3 => "Critical", _ => "Nominal" };

    private static string Stamps(IEnumerable<DeviceHammerStamp> stamps)
    {
        var sb = new StringBuilder();
        foreach (var s in stamps)
            sb.Append(Baseize(s.ElapsedMs.ToString(System.Globalization.CultureInfo.InvariantCulture)))
              .Append(Baseize(s.Score.ToString(System.Globalization.CultureInfo.InvariantCulture))).Append(Baseize(StateName(s.ThermalState)));
        return sb.ToString();
    }

    public static string Hmac(string key, string message) =>
        Convert.ToBase64String(HMACSHA256.HashData(Encoding.UTF8.GetBytes(key), Encoding.UTF8.GetBytes(message)));

    public static string ComputeHash(string key, IEnumerable<DeviceHammerStamp> stamps) => Hmac(key, Stamps(stamps));

    /// <summary>v2 — the whole submission: run metadata + every stamp ("v2|type|threading|manufacturer|model|osVersion|baselineScore|deliveredCapacity|validityFlags").</summary>
    public static string ComputeHashV2(string key, string metaCanonical, IEnumerable<DeviceHammerStamp> stamps) =>
        Hmac(key, Baseize(metaCanonical) + Stamps(stamps));
}

public static class Api
{
    public static readonly Uri BaseUrl = new("https://thapi.gtgroup.dev/");

    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        IncludeFields = true,
    };

    public static readonly HttpClient Http = new() { BaseAddress = BaseUrl, Timeout = TimeSpan.FromSeconds(30) };

    public static async Task<SessionResponse> CreateSession()
    {
        using var r = await Http.PostAsync("session", JsonContent.Create(new { }, options: Json));
        if (!r.IsSuccessStatusCode) throw new BenchException("Failed to initialize server session");
        return (await r.Content.ReadFromJsonAsync<SessionResponse>(Json))!;
    }

    public static async Task SubmitScore(HammerPayload payload)
    {
        using var r = await Http.PostAsJsonAsync("hammer", payload, Json);
        if (!r.IsSuccessStatusCode) throw new BenchException("Server rejected score submission");
    }

    public static async Task<List<HammerDto>> FetchLeaderboard()
    {
        using var r = await Http.GetAsync("leaderboard");
        if (!r.IsSuccessStatusCode) throw new BenchException("Failed to fetch leaderboard from server");
        return (await r.Content.ReadFromJsonAsync<List<HammerDto>>(Json)) ?? [];
    }

    public static async Task<List<DeviceHammerStamp>> FetchStamps(int id)
    {
        using var r = await Http.GetAsync($"stamps/{id}");
        if (!r.IsSuccessStatusCode) throw new BenchException("Failed to fetch stamps detail from server");
        return (await r.Content.ReadFromJsonAsync<List<DeviceHammerStamp>>(Json)) ?? [];
    }

    /// <summary>The stress run's v2 canonical metadata — byte for byte the phones' metaCanonical.</summary>
    public static string MetaCanonical(int type, int threading, string manufacturer, string model, string osVersion, long baseline,
        double delivered, int flags) =>
        FormattableString.Invariant($"v2|{type}|{threading}|{manufacturer}|{model}|{osVersion}|{baseline}|{delivered:F4}|{flags}");

    /// <summary>Submits a finished (or pending) stress run.</summary>
    public static async Task SubmitStress(PendingTestResult p)
    {
        var session = await CreateSession();
        string hash = ThermoHasher.ComputeHash(session.EncryptionKey, p.Stamps);
        string meta = MetaCanonical(p.TestDurationType, p.TestThreadingType ?? 1, p.DeviceManufacturer, p.DeviceModel, p.OsVersion,
            p.BaselineScore ?? 0, p.DeliveredCapacity ?? 0, p.ValidityFlags ?? 0);
        bool v2 = p.BaselineScore != null;
        await SubmitScore(new HammerPayload
        {
            Stamps = p.Stamps,
            Type = p.TestDurationType,
            TestThreadingType = p.TestThreadingType ?? 1,
            DeviceManufacturer = p.DeviceManufacturer,
            DeviceModel = p.DeviceModel,
            OsVersion = p.OsVersion,
            SessionId = session.Id,
            Hash = hash,
            SchemaVersion = v2 ? 2 : null,
            BaselineScore = p.BaselineScore,
            DeliveredCapacity = p.DeliveredCapacity,
            SustainedRatio = p.SustainedRatio,
            ThrottleOnsetSec = p.ThrottleOnsetSec,
            Confidence = p.Confidence,
            ValidityFlags = p.ValidityFlags,
            SocModel = p.SocModel,
            ClusterTopology = p.ClusterTopology,
            HashV2 = v2 ? ThermoHasher.ComputeHashV2(session.EncryptionKey, meta, p.Stamps) : null,
            OsBuild = p.OsBuild,   // what was recorded at test time, never today's build
        });
    }
}

/// <summary>Online or not, and whether the connection is a cellular one (a PC with a SIM).</summary>
public sealed class NetworkMonitor
{
    public static readonly NetworkMonitor Shared = new();
    public bool IsConnected { get; private set; }
    public event Action? Changed;

    private NetworkMonitor()
    {
        IsConnected = NetworkInterface.GetIsNetworkAvailable();
        NetworkChange.NetworkAvailabilityChanged += (_, e) =>
        {
            IsConnected = e.IsAvailable;
            System.Windows.Application.Current?.Dispatcher.BeginInvoke(() => Changed?.Invoke());
        };
    }

    /// <summary>The traffic goes over a mobile-broadband adapter: only that one is up (Wi-Fi and Ethernet are down).</summary>
    public static bool OnMobileData()
    {
        try
        {
            var up = NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback
                            && n.NetworkInterfaceType != NetworkInterfaceType.Tunnel && n.GetIPProperties().GatewayAddresses.Count > 0)
                .ToList();
            return up.Count > 0 && up.All(n => n.NetworkInterfaceType is NetworkInterfaceType.Wwanpp or NetworkInterfaceType.Wwanpp2);
        }
        catch (Exception) { return false; }
    }
}
