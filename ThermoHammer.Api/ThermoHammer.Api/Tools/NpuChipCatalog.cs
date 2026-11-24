using System.Text.Json;
using ThermoHammer.Api.Models;

namespace ThermoHammer.Api.Tools;

/// <summary>
/// The Android chips that have a Gemma NPU model (scoring version 5): the ones the apps' Play AI pack carries
/// (<see cref="BenchmarkScoring.NpuLlmChips"/>) plus the ones the site lists in models/android/manifest.json — a chip added
/// since a release, whose file the app downloads from the site (NpuServerModels.kt). A run from one of them must carry the
/// NPU part. Refreshed every 30 minutes by <see cref="NpuChipCatalogRefresher"/>, so adding a chip needs no deploy.
/// </summary>
public static class NpuChipCatalog
{
    private static volatile HashSet<string> _fromManifest = [];

    public static bool Contains(string? socModel)
    {
        var soc = (socModel ?? "").Trim().ToLowerInvariant();
        return soc.Length > 0 && (BenchmarkScoring.NpuLlmChips.Contains(soc) || _fromManifest.Contains(soc));
    }

    /// <summary>Every chip with a model right now (the pack's and the manifest's).</summary>
    public static IReadOnlyCollection<string> All => BenchmarkScoring.NpuLlmChips.Union(_fromManifest).OrderBy(s => s).ToList();

    /// <summary>The chips a manifest lists; throws on a manifest that doesn't parse (the old list stays).</summary>
    public static HashSet<string> Parse(string json)
    {
        using var doc = JsonDocument.Parse(json);
        return doc.RootElement.GetProperty("chips").EnumerateObject().Select(p => p.Name.Trim().ToLowerInvariant())
            .Where(s => s.Length > 0).ToHashSet();
    }

    public static void Set(HashSet<string> chips) => _fromManifest = chips;
}

/// <summary>Reads the site's NPU model manifest every 30 minutes (TH_NPU_MANIFEST_URL; the public site by default).</summary>
public sealed class NpuChipCatalogRefresher(IHttpClientFactory http, IConfiguration config, ILogger<NpuChipCatalogRefresher> log)
    : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stop)
    {
        var url = config["TH_NPU_MANIFEST_URL"] ?? "https://thermohammer.gtgroup.dev/models/android/manifest.json";
        while (!stop.IsCancellationRequested)
        {
            try
            {
                var client = http.CreateClient();
                client.Timeout = TimeSpan.FromSeconds(20);
                using var res = await client.GetAsync(url, stop);
                if (res.IsSuccessStatusCode)
                {
                    var chips = NpuChipCatalog.Parse(await res.Content.ReadAsStringAsync(stop));
                    NpuChipCatalog.Set(chips);
                }
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                log.LogWarning("NPU model manifest not read: {Message}", e.Message);    // the last list stays
            }
            await Task.Delay(TimeSpan.FromMinutes(30), stop);
        }
    }
}
