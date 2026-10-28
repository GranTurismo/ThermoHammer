using System.Runtime.InteropServices;
using Vortice;
using Vortice.Direct3D11;
using Vortice.DXGI;
using Vortice.MediaFoundation;

namespace ThermoHammer.Engine.Ux;

// ─────────────────────────────────────────────────────────────────────────────
// VIDEO PLAYBACK — UX Test 5: the same six clips as the phones (assets\media\clip*: 4K60 H.264, HEVC, VP9, AV1, then
// 8K30 HEVC and AV1), decoded flat out by Media Foundation — 0.6 s warm-up, then 3.6 s counted per clip, each clip
// read again from its start when it ends. Hardware decoders (DXVA) are used where the PC has them. A clip the PC can't
// decode (HEVC, VP9 and AV1 need Microsoft's Store extensions on a PC without them) scores 0.
// ─────────────────────────────────────────────────────────────────────────────

public sealed class VideoBench
{
    public const double WarmupS = 0.6, MeasureS = 3.6;
    private const int PreviewW = 960;

    /// <summary>A small BGRA preview of a decoded frame.</summary>
    public sealed record Preview(byte[] Bgra, int Width, int Height);

    /// <summary>
    /// Decodes every clip in turn; `onProgress` gets (clip index + fraction, fps so far, the clip, a preview frame, where in
    /// the clip the decoder is — seconds — or null when the clip didn't run).
    /// </summary>
    public VideoResult Run(Func<bool> isActive, Action<double, double, VideoClip, Preview?, double?> onProgress)
    {
        var results = new List<VideoCodecResult>();
        MediaDevice? media = null;
        try
        {
            try { media = new MediaDevice(); }
            catch (Exception e)
            {
                // no usable device: every clip scores 0, and the stage says why
                foreach (var clip in VideoClips.All)
                {
                    results.Add(new VideoCodecResult(clip, 0, null, false, $"Media Foundation is unavailable: {e.Message}"));
                    onProgress(results.Count, 0, clip, null, null);
                }
                Thread.Sleep(1000);
                return new VideoResult(results);
            }
            var crashed = Crashed();
            for (int i = 0; i < VideoClips.All.Length; i++)
            {
                var clip = VideoClips.All[i];
                if (!isActive()) break;
                if (crashed.Contains(clip))
                {
                    results.Add(new VideoCodecResult(clip, 0, null, false, "crashed the app here last time"));
                    onProgress(i + 1, 0, clip, null, null);
                    Thread.Sleep(1000);
                    continue;
                }
                Settings.Set(RunningKey, new[] { $"{Build}:{clip}" });
                try { results.Add(Decode(media, clip, isActive, (f, fps, img, pos) => onProgress(i + f, fps, clip, img, pos))); }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    results.Add(new VideoCodecResult(clip, 0, null, false, Explain(clip, e)));
                    onProgress(i + 1, 0, clip, null, null);
                    Thread.Sleep(1000);   // the stage says why for a moment
                }
                finally { Settings.Set(RunningKey, null); }
            }
        }
        finally { media?.Dispose(); }
        return new VideoResult(results);
    }

    private static string Explain(VideoClip clip, Exception e)
    {
        uint hr = (uint)e.HResult;
        // MF_E_TOPO_CODEC_NOT_FOUND, MF_E_INVALIDMEDIATYPE, MF_E_UNSUPPORTED_BYTESTREAM_TYPE, MF_E_CANNOT_PARSE_BYTESTREAM
        if (hr is 0xC00D5212 or 0xC00D36B4 or 0xC00D36C4 or 0xC00D36E5 or 0xC00D5211)
            return $"no {clip.Codec().Label()} decoder for {clip.Width()}×{clip.Height()} — {clip.Codec().Hint()}";
        // a codec Windows doesn't ship fails in other ways too (no demuxer for WebM, no decoder behind the type…)
        if (clip.Codec() != VideoCodec.Avc) return $"{clip.Label()} didn't open ({hr:X8}) — {clip.Codec().Hint()}";
        return $"{clip.Label()}: {e.Message}";
    }

    private const string RunningKey = "benchmark.videoRunning", CrashedKey = "benchmark.videoCrashed";
    private static string Build => System.Diagnostics.FileVersionInfo.GetVersionInfo(Environment.ProcessPath ?? "").FileVersion ?? "?";

    /// <summary>Clips whose decode never finished in this build (a decoder's crash can't be caught): a marker was left behind.</summary>
    private static HashSet<VideoClip> Crashed()
    {
        var crashed = Settings.Strings(CrashedKey);
        var running = Settings.Strings(RunningKey);
        if (running.Count > 0)
        {
            foreach (var r in running) if (!crashed.Contains(r)) crashed.Add(r);
            Settings.Set(CrashedKey, crashed);
            Settings.Set(RunningKey, null);
        }
        return crashed.Select(s => s.Split(':')).Where(p => p.Length == 2 && p[0] == Build)
            .Select(p => VideoClips.All.Cast<VideoClip?>().FirstOrDefault(c => c.ToString() == p[1])).OfType<VideoClip>().ToHashSet();
    }

    private static Guid Subtype(string fourCC) => VideoFormatGuids.FromFourCC(new FourCC(fourCC));

    private VideoCodecResult Decode(MediaDevice media, VideoClip clip, Func<bool> isActive, Action<double, double, Preview?, double> onProgress)
    {
        string path = Path.Combine(AppContext.BaseDirectory, "assets", "media", clip.Asset());
        if (Environment.GetEnvironmentVariable("THERMOHAMMER_SHADER_DEBUG") == "1") Console.Error.WriteLine($"video {clip}: opening {path}");
        if (!File.Exists(path)) throw new BenchException($"{clip.Asset()} missing");
        using var attrs = media.ReaderAttributes();
        using var reader = MediaFactory.MFCreateSourceReaderFromURL(path, attrs);
        reader.SetStreamSelection(SourceReaderIndex.AllStreams, false);
        reader.SetStreamSelection(SourceReaderIndex.FirstVideoStream, true);
        // 10-bit clips come out as P010, the rest as NV12 — the decoders' own formats, no conversion in between
        using (var type = MediaFactory.MFCreateMediaType())
        {
            type.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
            type.Set(MediaTypeAttributeKeys.Subtype, Subtype(clip.Codec() is VideoCodec.Hevc or VideoCodec.Av1 ? "P010" : "NV12"));
            reader.SetCurrentMediaType(SourceReaderIndex.FirstVideoStream, type);
        }
        int frames = 0, framesAtMeasure = 0;
        long start = Clock.Ns(), measuredFrom = 0, lastPreview = 0, lastReport = 0;
        double at = 0;
        bool hardware = false;
        Preview? preview = null;
        bool done = false;
        int rewinds = 0;
        while (!done && isActive())
        {
            using var sample = reader.ReadSample(SourceReaderIndex.FirstVideoStream, SourceReaderControlFlag.None, out _, out var flags, out long ts);
            if ((flags & SourceReaderFlag.Error) != 0) throw new BenchException($"{clip.Label()} decode failed");
            if ((flags & SourceReaderFlag.EndOfStream) != 0)
            {
                if (++rewinds > 400) throw new BenchException("the clip has no frames");
                reader.SetCurrentPosition(0);
                continue;
            }
            if (sample == null) continue;
            frames++;
            at = ts / 1e7;
            long now = Clock.Ns();
            double el = (now - start) / 1e9;
            if (frames == 1) hardware = IsHardware(sample);
            if (measuredFrom == 0 && el >= WarmupS) { measuredFrom = now; framesAtMeasure = frames; }
            if (now - lastPreview > 300_000_000) { lastPreview = now; preview = Grab(media, sample, clip) ?? preview; }
            if (now - lastReport > 100_000_000)
            {
                lastReport = now;
                double fps = measuredFrom > 0 && now > measuredFrom ? (frames - framesAtMeasure) * 1e9 / (now - measuredFrom) : 0;
                onProgress(Math.Min(1, el / (WarmupS + MeasureS)), fps, preview, at);
            }
            done = el >= WarmupS + MeasureS;
        }
        long end = Clock.Ns();
        double rate = measuredFrom > 0 ? (frames - framesAtMeasure) * 1e9 / (end - measuredFrom) : 0;
        if (frames == 0) throw new BenchException($"{clip.Label()} produced no frames");
        return new VideoCodecResult(clip, BenchmarkScore.Round2(rate), hardware ? "Media Foundation" : "Media Foundation (software)", hardware);
    }

    /// <summary>Hardware decoders hand over frames as Direct3D textures; software ones as memory.</summary>
    private static bool IsHardware(IMFSample sample)
    {
        try
        {
            using var buffer = sample.GetBufferByIndex(0);
            using var dxgi = buffer.QueryInterfaceOrNull<IMFDXGIBuffer>();
            return dxgi != null;
        }
        catch (Exception) { return false; }
    }

    /// <summary>The frame at 960 wide as BGRA: nearest-pixel picks from its planes, converted from BT.709 — a few ms, inside the timed span (as on the phones).</summary>
    private unsafe Preview? Grab(MediaDevice media, IMFSample sample, VideoClip clip)
    {
        try
        {
            using var buffer = sample.GetBufferByIndex(0);
            int w = clip.Width(), h = clip.Height();
            bool p010 = clip.Codec() is VideoCodec.Hevc or VideoCodec.Av1;
            using var dxgi = buffer.QueryInterfaceOrNull<IMFDXGIBuffer>();
            if (dxgi != null)
            {
                using var tex = new ID3D11Texture2D(dxgi.GetResource(typeof(ID3D11Texture2D).GUID));
                uint index = dxgi.SubresourceIndex;
                var d = tex.Description;
                using var staging = media.Device.CreateTexture2D(new Texture2DDescription(d.Format, d.Width, d.Height, 1, 1, BindFlags.None, ResourceUsage.Staging, CpuAccessFlags.Read));
                media.Context.CopySubresourceRegion(staging, 0, 0, 0, 0, tex, index);
                var map = media.Context.Map(staging, 0, MapMode.Read);
                try { return Convert((byte*)map.DataPointer, (int)map.RowPitch, (int)d.Height, w, h, p010); }
                finally { media.Context.Unmap(staging, 0); }
            }
            using var buf2d = buffer.QueryInterfaceOrNull<IMF2DBuffer>();
            if (buf2d != null)
            {
                buf2d.Lock2D(out var scan0, out int pitch);
                try { return Convert((byte*)scan0, pitch, h, w, h, p010); }
                finally { buf2d.Unlock2D(); }
            }
            return null;
        }
        catch (Exception) { return null; }
    }

    /// <summary>`plane`: the Y plane then the interleaved UV plane, `rowPitch` bytes a row, `rows` rows of Y (the texture may be padded).</summary>
    private static unsafe Preview Convert(byte* plane, int rowPitch, int rows, int width, int height, bool p010)
    {
        int ow = PreviewW, oh = Math.Max(2, PreviewW * height / width & ~1);
        var px = new byte[ow * oh * 4];
        int bytes = p010 ? 2 : 1;
        byte* uv = plane + (long)rowPitch * rows;
        for (int y = 0; y < oh; y++)
        {
            int sy = y * height / oh;
            for (int x = 0; x < ow; x++)
            {
                int sx = x * width / ow;
                int Y = plane[(long)sy * rowPitch + (long)sx * bytes + (p010 ? 1 : 0)];
                long uvAt = (long)(sy / 2) * rowPitch + (long)(sx / 2 * 2) * bytes;
                int U = uv[uvAt + (p010 ? 1 : 0)], V = uv[uvAt + bytes + (p010 ? 1 : 0)];
                double yy = (Y - 16) / 219.0, cb = (U - 128) / 224.0, cr = (V - 128) / 224.0;
                int o = (y * ow + x) * 4;
                px[o] = (byte)Math.Clamp((int)((yy + 1.8556 * cb) * 255), 0, 255);
                px[o + 1] = (byte)Math.Clamp((int)((yy - 0.1873 * cb - 0.4681 * cr) * 255), 0, 255);
                px[o + 2] = (byte)Math.Clamp((int)((yy + 1.5748 * cr) * 255), 0, 255);
                px[o + 3] = 255;
            }
        }
        return new Preview(px, ow, oh);
    }
}
