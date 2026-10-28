using ThermoHammer.Engine.Gpu;

namespace ThermoHammer.Engine.Ux;

/// <summary>The UX tests in order: web browsing, web scrolling, photo gallery, photo editing, video playback, video export, QR scanning.</summary>
public sealed class UxSection : IBenchmarkSection
{
    public void Run(BenchmarkEngine engine, BenchmarkResult r)
    {
        WebResult? web = null; ScrollResult? scroll = null; GalleryResult? gallery = null;
        VideoResult? video = null; ExportResult? export = null; QrResult? qr = null;
        double photo = 0;
        var errors = new List<string>();
        T? Noted<T>(UxTest test, Func<T?> body)
        {
            try { return body(); }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                errors.Add($"{test.Title().ToLowerInvariant()}: {e.Message}");
                return default;
            }
        }

        // UX 1–3: the browser tests, each a page in the stage's WebView2
        foreach (var test in new[] { UxTest.Web, UxTest.Scroll, UxTest.Gallery })
        {
            var outcome = engine.RunWebPage(test);
            if (outcome == null) return;
            switch (test)
            {
                case UxTest.Web:
                    web = Noted(test, () => new WebResult(outcome.Number("layout"), outcome.Number("canvas"), outcome.Number("css"), outcome.Number("svg"), outcome.Number("js")));
                    break;
                case UxTest.Scroll:
                    scroll = Noted(test, () => new ScrollResult(outcome.Number("speed"), outcome.Number("dropped")));
                    break;
                default:
                    gallery = Noted(test, () => new GalleryResult(outcome.Number("jpeg"), outcome.Number("webp"), outcome.Number("avif")));
                    break;
            }
        }

        // UX 4: the editing chain on the GPU — megapixels per second through all of it
        if (!engine.IsActive) return;
        photo = Noted(UxTest.Photo, () => Photo(engine));

        // UX 5: each clip's decode rate — 4K60 in four codecs, 8K30 in two
        if (!engine.IsActive) return;
        engine.PublishUx(UxTest.Video, detail: "OPENING DECODERS", unit: "FPS");
        video = Noted(UxTest.Video, () => new VideoBench().Run(() => engine.IsActive, (f, fps, clip, image, position) =>
        {
            engine.PublishUx(UxTest.Video, f / VideoClips.All.Length,
                position == null ? $"{clip.Label()} · NOT SUPPORTED · 0 POINTS" : $"{clip.Label()} · MEDIA FOUNDATION", fps > 0 ? fps : -1, "FPS");
            engine.PublishVideoFrame(image);
            engine.PublishVideoOsd(position is { } p
                ? new VideoOsd($"{clip.Codec().Label()} · {clip.Width()}×{clip.Height()} · {clip.Fps()} FPS", clip.Mbps(), p, 3, fps / clip.Fps()) : null);
        }));
        engine.PublishVideoOsd(null);

        // UX 6: a graded edit exported by each encoder, its quality checked
        if (!engine.IsActive) return;
        engine.PublishUx(UxTest.Export, detail: "OPENING ENCODERS", unit: "FPS");
        export = Noted(UxTest.Export, () => new ExportBench().Run(() => engine.IsActive, (job, i, p, done) =>
        {
            bool skipped = p.Encoder == null;
            string step = skipped ? (p.Error ?? "not supported").ToUpperInvariant() : p.Stage;
            engine.PublishUx(UxTest.Export, (i + p.Fraction) / ExportJobs.All.Length,
                skipped ? $"{job.Label()} {job.Res()} · {step} · 0 POINTS" : $"{job.Label()} {job.Res()} · {job.Mbps()} MBPS · {p.Encoder}",
                p.Fps > 0 ? p.Fps : -1, "FPS");
            engine.PublishVideoFrame(p.Image);
            var queue = ExportJobs.All.Select(e =>
            {
                var r = done.FirstOrDefault(x => x.Job == e);
                if (r != null) return r.Fps > 0 ? new ExportStage.Item(e, ExportStage.State.Done, r.Fps, r.Psnr) : new ExportStage.Item(e, ExportStage.State.Skipped);
                return new ExportStage.Item(e, e == job ? (skipped ? ExportStage.State.Skipped : ExportStage.State.Active) : ExportStage.State.Pending);
            }).ToList();
            engine.PublishExportStage(new ExportStage(job, p.Encoder, p.Encoder is { } enc && !enc.Contains("software"), p.FramesDone, p.ElapsedS, p.Fps, step, queue));
        }));
        engine.PublishExportStage(null);

        // UX 7: the shared QR frames, zxing-cpp on one thread
        if (!engine.IsActive) return;
        engine.PublishUx(UxTest.Qr, detail: "PREPARING FRAMES", unit: "SCANS/S");
        qr = Noted(UxTest.Qr, () =>
        {
            using var bench = new QrBench();
            var previews = Enumerable.Range(0, bench.Count).Select(bench.Preview).ToArray();
            return bench.Run(() => engine.IsActive, p =>
            {
                engine.PublishUx(UxTest.Qr, p.Fraction, p.Phase, p.Rate, "SCANS/S");
                engine.PublishQr(previews[p.Frame], p.Found);
            });
        });
        if (!engine.IsActive) return;
        engine.PublishUx(null);
        r.Ux = new UxResult(web, scroll, gallery, photo, video, export, qr, errors.Count == 0 ? null : string.Join("; ", errors));
    }

    /// <summary>UX 4 on the GPU; megapixels per second through the whole chain.</summary>
    public static double Photo(BenchmarkEngine? engine, GpuBench? shared = null, double? seconds = null, Action<float, double>? progress = null, string? png = null)
    {
        using var own = shared == null ? GpuBench.TryCreate(out var problem) ?? throw new GpuError($"Direct3D is unavailable: {problem}") : null;
        var bench = shared ?? own!;
        if (engine != null) bench.FrameSink = engine.PublishGpuFrame;
        string path = System.IO.Path.Combine(AppContext.BaseDirectory, "assets", "media", "photo_lake.jpg");
        using var scene = new PhotoEditScene(bench, File.ReadAllBytes(path), GpuBench.Width, GpuBench.Height);
        float secs = (float)(seconds ?? UxTest.Photo.Seconds());
        string detail = $"LENS · BLUR · BOKEH · SHARPEN · GRADE · {Math.Round(scene.Megapixels)} MP";
        engine?.PublishUx(UxTest.Photo, detail: detail, unit: "MP/S");
        var (starts, durs) = bench.Run(scene, secs, () => engine?.IsActive ?? true, (t, fps) =>
        {
            engine?.PublishUx(UxTest.Photo, t / secs, detail, fps * scene.Megapixels, "MP/S");
            progress?.Invoke(t, fps * scene.Megapixels);
        });
        if (png != null) bench.SavePng(scene.Output, png);
        var idx = Enumerable.Range(0, starts.Length).Where(i => starts[i] >= BenchmarkScore.WarmupS).ToList();
        if (idx.Count < 3) throw new GpuError("too few frames");
        return BenchmarkScore.Round2(idx.Count * 1e9 / idx.Sum(i => durs[i]) * scene.Megapixels);
    }
}

/// <summary>`--uxTest photo|qr [--seconds N] [--png file]`: one UX test without the window, its numbers to %LOCALAPPDATA%\ThermoHammer\ux-test.txt.</summary>
public static class UxCheck
{
    public static void Run(string which, double seconds, string? png)
    {
        var log = new System.Text.StringBuilder();
        try
        {
            switch (which)
            {
                case "photo":
                {
                    double mps = UxSection.Photo(null, null, seconds, null, png);
                    log.AppendLine($"PHOTO EDITING · {mps:0.00} MP/s · {UxScore.PhotoPoints(mps)} points");
                    break;
                }
                case "qr":
                {
                    using var bench = new QrBench();
                    var res = bench.Run(() => true, _ => { });
                    log.AppendLine($"QR · {bench.Count} frames · {res.Rate:0.0} scans/s · decoded {res.Decoded} missed {res.Missed} · {UxScore.QrPoints(res.Rate)} points");
                    break;
                }
                case "video":
                {
                    var res = new VideoBench().Run(() => true, (_, _, _, _, _) => { });
                    foreach (var c in res.Codecs)
                        log.AppendLine($"{c.Clip.Label()} · {c.Fps:0.0} fps · {(c.Decoder ?? "—")}{(c.Hardware ? " (hardware)" : "")}{(c.Error != null ? " · " + c.Error : "")}");
                    log.AppendLine($"{UxScore.VideoPoints(res)} points");
                    break;
                }
                default: log.AppendLine($"unknown test {which}"); break;
            }
        }
        catch (Exception e) { log.AppendLine($"FAILED: {e}"); }
        AppData.SaveText("ux-test.txt", log.ToString());
    }
}
