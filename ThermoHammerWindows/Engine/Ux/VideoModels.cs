namespace ThermoHammer.Engine.Ux;

public enum VideoCodec { Avc, Hevc, Vp9, Av1 }

public static class VideoCodecs
{
    public static string Label(this VideoCodec c) => c switch { VideoCodec.Avc => "H.264", VideoCodec.Hevc => "HEVC 10-BIT", VideoCodec.Vp9 => "VP9", _ => "AV1 10-BIT" };
    public static string Short(this VideoCodec c) => c switch { VideoCodec.Avc => "H.264", VideoCodec.Hevc => "HEVC", VideoCodec.Vp9 => "VP9", _ => "AV1" };
    /// <summary>What a PC without the codec needs (Windows ships H.264 only; the rest are Microsoft Store extensions).</summary>
    public static string Hint(this VideoCodec c) => c switch
    {
        VideoCodec.Hevc => "install Microsoft's HEVC Video Extensions",
        VideoCodec.Vp9 => "install Microsoft's VP9 Video Extensions",
        VideoCodec.Av1 => "install Microsoft's AV1 Video Extension",
        _ => "Windows has no H.264 decoder for it",
    };
}

/// <summary>The clips of UX Test 5 (assets\media): Neon City, a different three seconds in each — 4K60 in every codec, 8K30 in HEVC and AV1.</summary>
public static class VideoClips
{
    public static readonly VideoClip[] All = [VideoClip.Avc4k, VideoClip.Hevc4k, VideoClip.Vp94k, VideoClip.Av14k, VideoClip.Hevc8k, VideoClip.Av18k];
    public static VideoCodec Codec(this VideoClip c) => c switch
    {
        VideoClip.Avc4k => VideoCodec.Avc, VideoClip.Hevc4k or VideoClip.Hevc8k => VideoCodec.Hevc, VideoClip.Vp94k => VideoCodec.Vp9, _ => VideoCodec.Av1,
    };
    public static bool K8(this VideoClip c) => c is VideoClip.Hevc8k or VideoClip.Av18k;
    public static string Asset(this VideoClip c) => c switch
    {
        VideoClip.Avc4k => "clip_avc.mp4", VideoClip.Hevc4k => "clip_hevc10.mp4", VideoClip.Vp94k => "clip_vp9.webm", VideoClip.Av14k => "clip_av1.mp4",
        VideoClip.Hevc8k => "clip8k_hevc10.mp4", _ => "clip8k_av1.mp4",
    };
    public static int Width(this VideoClip c) => c.K8() ? 7680 : 3840;
    public static int Height(this VideoClip c) => c.K8() ? 4320 : 2160;
    public static int Fps(this VideoClip c) => c.K8() ? 30 : 60;
    public static int Mbps(this VideoClip c) => c switch { VideoClip.Avc4k => 30, VideoClip.Hevc4k => 18, VideoClip.Vp94k => 16, VideoClip.Av14k => 10, VideoClip.Hevc8k => 40, _ => 24 };
    public static string Res(this VideoClip c) => c.K8() ? "8K" : "4K";
    public static string Label(this VideoClip c) => $"{c.Codec().Label()} {c.Res()}";
}

/// <summary>The jobs of UX Test 6: the H.264 4K clip graded and encoded at a fixed bitrate — 4K60 H.264, HEVC, AV1 and 8K30 HEVC (from the 8K HEVC clip).</summary>
public static class ExportJobs
{
    public static readonly ExportJob[] All = [ExportJob.Avc4k, ExportJob.Hevc4k, ExportJob.Av14k, ExportJob.Hevc8k];
    public static VideoCodec Codec(this ExportJob j) => j switch { ExportJob.Avc4k => VideoCodec.Avc, ExportJob.Av14k => VideoCodec.Av1, _ => VideoCodec.Hevc };
    public static bool K8(this ExportJob j) => j == ExportJob.Hevc8k;
    public static string Label(this ExportJob j) => j switch { ExportJob.Avc4k => "H.264", ExportJob.Av14k => "AV1", _ => "HEVC" };
    public static VideoClip Source(this ExportJob j) => j.K8() ? VideoClip.Hevc8k : VideoClip.Avc4k;
    public static int Width(this ExportJob j) => j.K8() ? 7680 : 3840;
    public static int Height(this ExportJob j) => j.K8() ? 4320 : 2160;
    public static int Fps(this ExportJob j) => j.K8() ? 30 : 60;
    public static int Mbps(this ExportJob j) => j switch { ExportJob.Avc4k => 40, ExportJob.Hevc4k => 30, ExportJob.Av14k => 20, _ => 80 };
    public static string Res(this ExportJob j) => j.K8() ? "8K" : "4K";
    /// <summary>The timeline exported start to finish: 8 s of 4K60, 4 s of 8K30.</summary>
    public static int Seconds(this ExportJob j) => j.K8() ? 4 : 8;
    public static int Frames(this ExportJob j) => j.Seconds() * j.Fps();
    public static string FileName(this ExportJob j) => $"Neon City · Night Run · {j.Res()} {j.Label()}.mp4";
}

/// <summary>The stage's player overlay during UX Test 5.</summary>
public sealed record VideoOsd(string Format, int Mbps, double PositionS, double DurationS, double Speed);

/// <summary>UX Test 6 on the stage: an editor's export dialog — the job in hand, its progress, the render queue.</summary>
public sealed record ExportStage(ExportJob Job, string? Encoder, bool Hardware, int FramesDone, double ElapsedS, double Fps, string Step, List<ExportStage.Item> Queue)
{
    public enum State { Done, Active, Pending, Skipped }
    public sealed record Item(ExportJob Job, State State, double Fps = 0, double Psnr = 0);

    public double Progress => Math.Min(1, Math.Max(0, (double)FramesDone / Job.Frames()));
    public double? LeftS => Fps > 0 ? Math.Max(0, Job.Frames() - FramesDone) / Fps : null;

    /// <summary>"×1.2 REAL TIME" — two decimals below 1, so a slow export doesn't read ×0.0.</summary>
    public string SpeedText
    {
        get
        {
            if (Fps <= 0) return "×— REAL TIME";
            double x = Fps / Job.Fps();
            return x < 1 ? $"×{x:0.00} REAL TIME" : $"×{x:0.0} REAL TIME";
        }
    }

    /// <summary>"~2.4 S LEFT · " while encoding — or, when it won't finish in time, where it'll be cut off.</summary>
    public string TimeLeftText => Step != "ENCODING" || LeftS is not { } left ? ""
        : ElapsedS + left > Ux.ExportBench.CapS ? $"STOPS AT {Ux.ExportBench.CapS:0} S · " : $"~{left:0.0} S LEFT · ";
}

/// <summary>
/// What a speed means in playback terms: real time at a tier = at least 10 % faster than its frame rate. Derived from the
/// measured speed, so a faster future chip fills in the higher tiers by itself.
/// </summary>
public static class VideoGrid
{
    public sealed record Tier(bool K8, int Fps) { public string Label => (K8 ? "8K" : "4K") + Fps; }
    public enum Cell { Yes, No, Unsupported, NotTested }

    public static readonly Tier[] Tiers = [new(false, 30), new(false, 60), new(false, 120), new(false, 240), new(true, 30), new(true, 60), new(true, 120), new(true, 240)];
    public const double Margin = 1.1;

    public static string Mark(this Cell c) => c switch { Cell.Yes => "✓", Cell.No => "–", Cell.Unsupported => "✗", _ => "·" };

    public static Cell Of(Tier t, double? fps4k, double? fps8k)
    {
        double? f = t.K8 ? fps8k : fps4k;
        if (f == null) return Cell.NotTested;
        return f <= 0 ? Cell.Unsupported : f >= t.Fps * Margin ? Cell.Yes : Cell.No;
    }

    public static Cell[] Row(double? fps4k, double? fps8k) => Tiers.Select(t => Of(t, fps4k, fps8k)).ToArray();

    public static Cell[] Playback(VideoResult v, VideoCodec c)
    {
        VideoClip? k4 = VideoClips.All.Cast<VideoClip?>().FirstOrDefault(x => x!.Value.Codec() == c && !x.Value.K8());
        VideoClip? k8 = VideoClips.All.Cast<VideoClip?>().FirstOrDefault(x => x!.Value.Codec() == c && x.Value.K8());
        return Row(k4 is { } a ? v.Fps(a) : null, k8 is { } b ? v.Fps(b) : null);
    }

    public static Cell[] Export(ExportResult e, VideoCodec c)
    {
        ExportJob? k4 = ExportJobs.All.Cast<ExportJob?>().FirstOrDefault(x => x!.Value.Codec() == c && !x.Value.K8());
        ExportJob? k8 = ExportJobs.All.Cast<ExportJob?>().FirstOrDefault(x => x!.Value.Codec() == c && x.Value.K8());
        return Row(k4 is { } a ? e.Counted(a) : null, k8 is { } b ? e.Counted(b) : null);
    }
}
