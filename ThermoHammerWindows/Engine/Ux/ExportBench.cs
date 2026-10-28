using System.Numerics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Vortice;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;
using Vortice.Mathematics;
using Vortice.MediaFoundation;
using ThermoHammer.Engine.Gpu;

namespace ThermoHammer.Engine.Ux;

// ─────────────────────────────────────────────────────────────────────────────
// VIDEO EXPORT — UX Test 6, as on the phones (VideoExportScene.kt, ExportBench.swift): what an editor's "export" does,
// encoder by encoder. The source clip loops through Media Foundation's decoder; every frame gets the edit on the GPU (grade,
// vignette, a dip to black where the clip loops, an opaque lower third with a timecode that changes every frame) and goes
// to Media Foundation's encoder — hardware where the PC has one — not real time, so it runs as fast as the encoder takes
// frames — and the encoder writes a real MP4 (temporary, deleted after). A job is a fixed timeline (ExportJob.Frames: 8 s
// of 4K60, 4 s of 8K30) exported start to finish, so the stage's progress bar is the export's own; the rate is the frames
// after the first `WarmupFrames` over the time until the file is finished. One that can't finish within `CapS` is
// measured on what it did.
//
// Quality check: at `Samples` the lower third is also copied from the GPU at full resolution and kept; after the job
// the file is decoded again and the same window compared — luma PSNR after a linear fit, so a different colour range
// doesn't count against the encoder. Below UxScore.MinPsnr the job's speed doesn't count.
// ─────────────────────────────────────────────────────────────────────────────

public sealed class ExportBench
{
    public const int WarmupFrames = 30;
    public const double CapS = 15;
    /// <summary>Exported frames compared with what was sent in (those reached).</summary>
    public static readonly HashSet<int> Samples = [6, 18, 30, 42];
    /// <summary>The checked window, a fraction of the frame from the bottom left (the phones' origin).</summary>
    private const double WinX = 0.0677, WinY = 0.1523, WinW = 0.375, WinH = 0.1037;
    private const int Ring = 6;
    private const int PreviewW = 1280;

    public sealed record Progress(double Fraction, double Fps, int FramesDone, double ElapsedS, string Stage, VideoBench.Preview? Image, string? Encoder, string? Error = null);

    /// <summary>Every job in turn; `onProgress` gets the job, its index, how it's going and the jobs finished before it.</summary>
    public ExportResult Run(Func<bool> isActive, Action<ExportJob, int, Progress, List<ExportJobResult>> onProgress)
    {
        var results = new List<ExportJobResult>();
        MediaDevice? media = null;
        try
        {
            try { media = new MediaDevice(); }
            catch (Exception e)
            {
                foreach (var job in ExportJobs.All)
                {
                    results.Add(new ExportJobResult(job, 0, 0, null, false, $"Media Foundation is unavailable: {e.Message}"));
                    onProgress(job, results.Count - 1, new Progress(1, 0, 0, 0, "", null, null, results[^1].Error), results);
                }
                Thread.Sleep(1000);
                return new ExportResult(results);
            }
            var art = TitleArt.Render();
            var crashed = Crashed();
            for (int i = 0; i < ExportJobs.All.Length; i++)
            {
                var job = ExportJobs.All[i];
                if (!isActive()) break;
                var done = results.ToList();
                if (crashed.Contains(job))
                {
                    results.Add(new ExportJobResult(job, 0, 0, null, false, "crashed the app here last time"));
                    onProgress(job, i, new Progress(1, 0, 0, 0, "", null, null, results[^1].Error), results);
                    Thread.Sleep(1000);
                    continue;
                }
                Settings.Set(RunningKey, new[] { $"{Build}:{job}" });
                try { results.Add(Export(media, art, job, isActive, p => onProgress(job, i, p, done))); }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    string why = Explain(job, e);
                    results.Add(new ExportJobResult(job, 0, 0, null, false, why));
                    onProgress(job, i, new Progress(1, 0, 0, 0, "", null, null, why), results);
                    Thread.Sleep(1000);   // the stage says why for a moment
                }
                finally { Settings.Set(RunningKey, null); }
            }
        }
        finally { media?.Dispose(); }
        return new ExportResult(results);
    }

    private const string RunningKey = "benchmark.exportRunning", CrashedKey = "benchmark.exportCrashed";
    private static string Build => System.Diagnostics.FileVersionInfo.GetVersionInfo(Environment.ProcessPath ?? "").FileVersion ?? "?";

    private static HashSet<ExportJob> Crashed()
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
            .Select(p => ExportJobs.All.Cast<ExportJob?>().FirstOrDefault(j => j.ToString() == p[1])).OfType<ExportJob>().ToHashSet();
    }

    private static string Explain(ExportJob job, Exception e)
    {
        uint hr = (uint)e.HResult;
        if (hr is 0xC00D5212 or 0xC00D36B4 or 0xC00D36B2 or 0xC00D3E8C)   // no codec / invalid type / not accepting / no encoder transform
            return $"no {job.Label()} encoder for {job.Width()}×{job.Height()}";
        return e.Message;
    }

    // ── one export ──

    private ExportJobResult Export(MediaDevice media, TitleArt art, ExportJob job, Func<bool> isActive, Action<Progress> onProgress)
    {
        int w = job.Width(), h = job.Height(), fps = job.Fps();
        var dev = media.Device;
        var ctx = media.Context;
        using var bench = new GpuBench(media);
        var owned = new List<IDisposable>();
        T Own<T>(T d) where T : IDisposable { owned.Add(d); return d; }
        string file = Path.Combine(Path.GetTempPath(), $"thermohammer_export_{job}.mp4");
        try { File.Delete(file); } catch (IOException) { }
        try
        {
            var source = Own(new Source(media, job.Source()));
            var proc = Own(new VideoProc(media, w, h));
            // frames: decoded → BGRA → edited → NV12 (a ring: the encoder may still hold the last ones)
            var bgra = Own(dev.CreateTexture2D(new Texture2DDescription(Format.B8G8R8A8_UNorm, (uint)w, (uint)h, 1, 1, BindFlags.RenderTarget | BindFlags.ShaderResource)));
            var edited = Own(dev.CreateTexture2D(new Texture2DDescription(Format.B8G8R8A8_UNorm, (uint)w, (uint)h, 1, 1, BindFlags.RenderTarget | BindFlags.ShaderResource)));
            var bgraSrv = Own(dev.CreateShaderResourceView(bgra));
            var editedRtv = Own(dev.CreateRenderTargetView(edited));
            var editedSrv = Own(dev.CreateShaderResourceView(edited));
            var nv12 = Enumerable.Range(0, Ring).Select(_ => Own(dev.CreateTexture2D(new Texture2DDescription(Format.NV12, (uint)w, (uint)h, 1, 1, BindFlags.RenderTarget | BindFlags.ShaderResource)))).ToArray();
            var titleSrv = Own(dev.CreateShaderResourceView(Own(art.TitleTexture(dev))));
            var glyphSrv = Own(dev.CreateShaderResourceView(Own(art.GlyphTexture(dev))));
            int ph = PreviewW * h / w & ~1;
            var preview = Own(dev.CreateTexture2D(new Texture2DDescription(Format.B8G8R8A8_UNorm, PreviewW, (uint)ph, 1, 1, BindFlags.RenderTarget | BindFlags.ShaderResource)));
            var previewRtv = Own(dev.CreateRenderTargetView(preview));
            var previewStaging = Own(dev.CreateTexture2D(new Texture2DDescription(Format.B8G8R8A8_UNorm, PreviewW, (uint)ph, 1, 1, BindFlags.None, ResourceUsage.Staging, CpuAccessFlags.Read)));
            int wx = (int)Math.Round(WinX * w), ww = (int)Math.Round(WinW * w), wh = (int)Math.Round(WinH * h), wy = h - (int)Math.Round(WinY * h) - wh;
            var winStaging = Own(dev.CreateTexture2D(new Texture2DDescription(Format.B8G8R8A8_UNorm, (uint)ww, (uint)wh, 1, 1, BindFlags.None, ResourceUsage.Staging, CpuAccessFlags.Read)));
            var opaque = Own(dev.CreateBlendState(BlendDescription.Opaque));
            var edit = bench.Pipeline("Export", "exportVS", "exportEditFS", null, opaque);
            var blit = bench.Pipeline("Present", "presentVS", "presentFS", null, opaque);
            var raster = Own(dev.CreateRasterizerState(new RasterizerDescription(CullMode.None, FillMode.Solid) { DepthClipEnable = true }));
            var depthOff = Own(dev.CreateDepthStencilState(new DepthStencilDescription(false, DepthWriteMask.Zero, ComparisonFunction.Always)));
            var linear = Own(dev.CreateSamplerState(new SamplerDescription(Filter.MinMagMipLinear, TextureAddressMode.Clamp, TextureAddressMode.Clamp,
                TextureAddressMode.Clamp, 0, 1, ComparisonFunction.Never, new Color4(0, 0, 0, 0), 0, float.MaxValue)));
            var cb = Own(dev.CreateBuffer(new BufferDescription((uint)((Marshal.SizeOf<EditParams>() + 15) & ~15), BindFlags.ConstantBuffer, ResourceUsage.Default)));

            // the encoder
            var writer = Own(new Writer(media, file, job, w, h));
            string encoder = writer.EncoderName;
            bool hardware = writer.Hardware;

            var refs = new Dictionary<int, byte[]>();
            long start = Clock.Ns(), measuredFrom = 0, lastPreview = 0, lastReport = 0;
            int framesAtMeasure = 0, k = 0;
            double rateNow = 0;
            VideoBench.Preview? image = null;
            bool capped = false;
            double Rate(long now, int written)
            {
                if (measuredFrom > 0 && now > measuredFrom + 50_000_000) return (written - framesAtMeasure) * 1e9 / (now - measuredFrom);
                double el = (now - start) / 1e9;
                return el > 0.05 ? written / el : 0;
            }
            while (isActive() && k < job.Frames())
            {
                long now = Clock.Ns();
                double el = (now - start) / 1e9;
                if (measuredFrom == 0 && k >= WarmupFrames) { measuredFrom = now; framesAtMeasure = k; }
                rateNow = Rate(now, k);
                if (el >= CapS) { capped = true; break; }          // too slow to finish: measured on what it did

                using var decoded = source.Next();
                // the decoded frame (a decoder surface) → BGRA, the edit → BGRA, then → NV12 for the encoder
                proc.Convert(decoded.Texture, decoded.Index, bgra, toRgb: true);
                var p = new EditParams { Size = new Vector2(w, h), Fade = Fade(k, source.Frames), Box = new Vector4(0.06f, 0.74f, 0.42f, 0.112f) };
                p.SetDigits(k, fps);
                ctx.UpdateSubresource(in p, cb);
                ctx.OMSetRenderTargets(editedRtv);
                ctx.RSSetViewport(0, 0, w, h);
                ctx.RSSetState(raster);
                ctx.OMSetDepthStencilState(depthOff);
                ctx.IASetInputLayout(null);
                ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
                ctx.VSSetShader(edit.VS); ctx.PSSetShader(edit.PS); ctx.OMSetBlendState(edit.Blend);
                ctx.PSSetConstantBuffer(0, cb);
                ctx.PSSetSampler(0, linear);
                ctx.PSSetShaderResources(0, [bgraSrv, titleSrv, glyphSrv]);
                ctx.Draw(3, 0);
                ctx.PSSetShaderResources(0, new ID3D11ShaderResourceView[3]);
                ctx.OMSetRenderTargets((ID3D11RenderTargetView?)null);
                if (Samples.Contains(k)) refs[k] = ReadWindow(ctx, edited, winStaging, wx, wy, ww, wh);
                var frame = nv12[k % Ring];
                proc.Convert(edited, 0, frame, toRgb: false);
                ctx.Flush();
                writer.Write(frame, k, fps);
                k++;

                if (now - lastPreview > 300_000_000)    // a small preview every 0.3 s (a few ms, inside the timed span)
                {
                    lastPreview = now;
                    image = Preview(ctx, editedSrv, preview, previewRtv, previewStaging, blit, linear, raster, depthOff, ph) ?? image;
                }
                if (now - lastReport > 100_000_000)
                {
                    lastReport = now;
                    onProgress(new Progress(0.88 * k / job.Frames(), rateNow, k, el, "ENCODING", image, encoder));
                }
            }
            if (!isActive()) throw new OperationCanceledException();
            onProgress(new Progress(0.88, rateNow, k, (Clock.Ns() - start) / 1e9, "FINISHING THE FILE", image, encoder));
            writer.Finish();
            // the file is done: the export's whole time (unless it was cut off, then the rate at the cut stands)
            long doneAt = Clock.Ns();
            double fpsResult = capped ? rateNow : Rate(doneAt, k);
            onProgress(new Progress(0.92, fpsResult, k, (doneAt - start) / 1e9, "CHECKING QUALITY", image, encoder));
            double psnr = Verify(file, refs, w, h, fps, wx, wy, ww, wh);
            return new ExportJobResult(job, BenchmarkScore.Round2(fpsResult), BenchmarkScore.Round2(psnr), encoder, hardware);
        }
        finally
        {
            foreach (var o in Enumerable.Reverse(owned)) { try { o.Dispose(); } catch (Exception) { } }
            try { File.Delete(file); } catch (IOException) { }
        }
    }

    /// <summary>A dip where the clip loops.</summary>
    private static float Fade(int k, int loop)
    {
        int pos = k % loop;
        return (float)Math.Min(1, Math.Max(0.25, Math.Min(pos + 1, loop - pos) / 8.0));
    }

    private static unsafe byte[] ReadWindow(ID3D11DeviceContext ctx, ID3D11Texture2D src, ID3D11Texture2D staging, int x, int y, int w, int h)
    {
        ctx.CopySubresourceRegion(staging, 0, 0, 0, 0, src, 0, new Box(x, y, 0, x + w, y + h, 1));
        var map = ctx.Map(staging, 0, MapMode.Read);
        var bytes = new byte[w * h * 4];
        try { for (int r = 0; r < h; r++) Marshal.Copy(map.DataPointer + r * (int)map.RowPitch, bytes, r * w * 4, w * 4); }
        finally { ctx.Unmap(staging, 0); }
        return bytes;
    }

    private static unsafe VideoBench.Preview? Preview(ID3D11DeviceContext ctx, ID3D11ShaderResourceView edited, ID3D11Texture2D preview, ID3D11RenderTargetView rtv,
        ID3D11Texture2D staging, Pipe blit, ID3D11SamplerState linear, ID3D11RasterizerState raster, ID3D11DepthStencilState depthOff, int height)
    {
        try
        {
            ctx.OMSetRenderTargets(rtv);
            ctx.RSSetViewport(0, 0, PreviewW, height);
            ctx.RSSetState(raster);
            ctx.OMSetDepthStencilState(depthOff);
            ctx.VSSetShader(blit.VS); ctx.PSSetShader(blit.PS); ctx.OMSetBlendState(blit.Blend);
            ctx.PSSetShaderResource(0, edited);
            ctx.PSSetSampler(0, linear);
            ctx.Draw(3, 0);
            ctx.PSSetShaderResources(0, new ID3D11ShaderResourceView[1]);
            ctx.OMSetRenderTargets((ID3D11RenderTargetView?)null);
            ctx.CopyResource(staging, preview);
            var map = ctx.Map(staging, 0, MapMode.Read);
            var bytes = new byte[PreviewW * height * 4];
            try { for (int r = 0; r < height; r++) Marshal.Copy(map.DataPointer + r * (int)map.RowPitch, bytes, r * PreviewW * 4, PreviewW * 4); }
            finally { ctx.Unmap(staging, 0); }
            for (int i = 3; i < bytes.Length; i += 4) bytes[i] = 255;
            return new VideoBench.Preview(bytes, PreviewW, height);
        }
        catch (Exception) { return null; }
    }

    // ── the quality check ──

    /// <summary>Decodes the exported file's first frames again; luma PSNR of the window against what was sent in.</summary>
    private static unsafe double Verify(string file, Dictionary<int, byte[]> refs, int w, int h, int fps, int wx, int wy, int ww, int wh)
    {
        if (refs.Count == 0 || !File.Exists(file)) throw new BenchException("the encoder produced nothing");
        using var attrs = MediaFactory.MFCreateAttributes(2);
        attrs.Set(SourceReaderAttributeKeys.EnableVideoProcessing, 1u);     // software conversion to RGB: not timed
        using var reader = MediaFactory.MFCreateSourceReaderFromURL(file, attrs);
        reader.SetStreamSelection(SourceReaderIndex.AllStreams, false);
        reader.SetStreamSelection(SourceReaderIndex.FirstVideoStream, true);
        using (var type = MediaFactory.MFCreateMediaType())
        {
            type.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
            type.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.FromFourCC(new FourCC(0x00000016)));   // MFVideoFormat_RGB32
            reader.SetCurrentMediaType(SourceReaderIndex.FirstVideoStream, type);
        }
        int last = refs.Keys.Max();
        var pairs = new List<(byte[] reference, byte[] exported)>();
        for (int n = 0; n < last + 40; n++)
        {
            using var sample = reader.ReadSample(SourceReaderIndex.FirstVideoStream, SourceReaderControlFlag.None, out _, out var flags, out long ts);
            if ((flags & (SourceReaderFlag.EndOfStream | SourceReaderFlag.Error)) != 0) break;
            if (sample == null) continue;
            int k = (int)Math.Round(ts / 1e7 * fps);
            if (!refs.TryGetValue(k, out var reference)) continue;
            using var buffer = sample.ConvertToContiguousBuffer();
            using var b2d = buffer.QueryInterfaceOrNull<IMF2DBuffer>();
            var got = new byte[ww * wh * 4];
            if (b2d != null)
            {
                b2d.Lock2D(out var scan0, out int pitch);      // top row first; a negative pitch walks a bottom-up image
                try { for (int r = 0; r < wh; r++) Marshal.Copy(scan0 + (wy + r) * pitch + wx * 4, got, r * ww * 4, ww * 4); }
                finally { b2d.Unlock2D(); }
            }
            else
            {
                buffer.Lock(out var ptr, out _, out _);
                try { for (int r = 0; r < wh; r++) Marshal.Copy(ptr + (wy + r) * w * 4 + wx * 4, got, r * ww * 4, ww * 4); }
                finally { buffer.Unlock(); }
            }
            pairs.Add((reference, got));
            if (pairs.Count == refs.Count) break;
        }
        if (pairs.Count == 0) throw new BenchException("the export didn't decode");
        return Psnr(pairs);
    }

    /// <summary>Luma PSNR over (reference, exported) after fitting exported ≈ a·reference + b — the phones' VideoExportScene.psnr (BGRA bytes).</summary>
    public static double Psnr(List<(byte[] reference, byte[] exported)> pairs)
    {
        static double Luma(byte[] b, int i) => (77 * b[i + 2] + 150 * b[i + 1] + 29 * b[i]) / 256.0;
        double n = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
        foreach (var (r, e) in pairs)
            for (int i = 0; i + 3 < Math.Min(r.Length, e.Length); i += 4)
            {
                double x = Luma(r, i), y = Luma(e, i);
                n++; sx += x; sy += y; sxx += x * x; sxy += x * y;
            }
        double varX = sxx - sx * sx / n;
        double a = varX > 1e-6 ? (sxy - sx * sy / n) / varX : 1;
        double bb = (sy - a * sx) / n;
        double se = 0;
        foreach (var (r, e) in pairs)
            for (int i = 0; i + 3 < Math.Min(r.Length, e.Length); i += 4) { double d = Luma(e, i) - (a * Luma(r, i) + bb); se += d * d; }
        double mse = se / n;
        return mse < 1e-6 ? 99 : Math.Round(10 * Math.Log10(255.0 * 255.0 / mse) * 100) / 100;
    }

    // ── the pieces ──

    [StructLayout(LayoutKind.Sequential)]
    private struct EditParams
    {
        public Vector2 Size;
        public float Fade, Pad0;
        public Vector4 Box;
        public int D0, D1, D2, D3, D4, D5, D6, D7, D8, D9, D10, D11;

        /// <summary>The timecode's eleven glyphs: 00:MM:SS:FF in the digits 0-9 and ':' (10).</summary>
        public void SetDigits(int k, int fps)
        {
            int sec = k / fps, ff = k % fps;
            (D0, D1, D2, D3, D4, D5, D6, D7, D8, D9, D10, D11) = (0, 0, 10, sec / 60 / 10, sec / 60 % 10, 10, sec % 60 / 10, sec % 60 % 10, 10, ff / 10, ff % 10, 0);
        }
    }

    /// <summary>A decoded frame: its decoder surface (a texture and the slice in it), kept alive while the sample is.</summary>
    private sealed class Decoded(IMFSample sample, IMFMediaBuffer buffer, IMFDXGIBuffer dxgi, ID3D11Texture2D texture, uint index) : IDisposable
    {
        public readonly ID3D11Texture2D Texture = texture;
        public readonly uint Index = index;
        public void Dispose() { Texture.Dispose(); dxgi.Dispose(); buffer.Dispose(); sample.Dispose(); }
    }

    /// <summary>The source clip decoded by Media Foundation into decoder surfaces, looping.</summary>
    private sealed class Source : IDisposable
    {
        private readonly IMFSourceReader reader;
        private readonly IMFAttributes attrs;
        public readonly int Frames;      // per pass of the clip

        public Source(MediaDevice media, VideoClip clip)
        {
            string path = Path.Combine(AppContext.BaseDirectory, "assets", "media", clip.Asset());
            if (!File.Exists(path)) throw new BenchException($"{clip.Asset()} missing");
            attrs = media.ReaderAttributes();
            reader = MediaFactory.MFCreateSourceReaderFromURL(path, attrs);
            reader.SetStreamSelection(SourceReaderIndex.AllStreams, false);
            reader.SetStreamSelection(SourceReaderIndex.FirstVideoStream, true);
            using var type = MediaFactory.MFCreateMediaType();
            type.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
            type.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.FromFourCC(new FourCC(clip.Codec() is VideoCodec.Hevc or VideoCodec.Av1 ? "P010" : "NV12")));
            reader.SetCurrentMediaType(SourceReaderIndex.FirstVideoStream, type);
            Frames = clip.Fps() * 3;       // the clips are three seconds
        }

        public Decoded Next()
        {
            for (int tries = 0; tries < 400; tries++)
            {
                var sample = reader.ReadSample(SourceReaderIndex.FirstVideoStream, SourceReaderControlFlag.None, out _, out var flags, out _);
                if ((flags & SourceReaderFlag.Error) != 0) throw new BenchException("can't decode the source clip");
                if ((flags & SourceReaderFlag.EndOfStream) != 0) { sample?.Dispose(); reader.SetCurrentPosition(0); continue; }
                if (sample == null) continue;
                var buffer = sample.GetBufferByIndex(0);
                var dxgi = buffer.QueryInterfaceOrNull<IMFDXGIBuffer>();
                if (dxgi == null)
                {
                    buffer.Dispose(); sample.Dispose();
                    throw new BenchException("the decoder doesn't deliver GPU frames (no hardware decoder for the source clip)");
                }
                var tex = new ID3D11Texture2D(dxgi.GetResource(typeof(ID3D11Texture2D).GUID));
                return new Decoded(sample, buffer, dxgi, tex, dxgi.SubresourceIndex);
            }
            throw new BenchException("the source clip has no frames");
        }

        public void Dispose() { reader.Dispose(); attrs.Dispose(); }
    }

    /// <summary>The D3D11 video processor: YUV decoder surfaces → BGRA, and the edited BGRA → NV12 for the encoder (BT.709).</summary>
    private sealed class VideoProc : IDisposable
    {
        private readonly ID3D11VideoDevice vdev;
        private readonly ID3D11VideoContext vctx;
        private readonly ID3D11VideoContext1? vctx1;
        private readonly ID3D11VideoProcessorEnumerator en;
        private readonly ID3D11VideoProcessor vp;
        private readonly Dictionary<ID3D11Texture2D, ID3D11VideoProcessorOutputView> outputs = [];

        public VideoProc(MediaDevice media, int w, int h)
        {
            vdev = media.Device.QueryInterface<ID3D11VideoDevice>();
            vctx = media.Context.QueryInterface<ID3D11VideoContext>();
            vctx1 = media.Context.QueryInterfaceOrNull<ID3D11VideoContext1>();
            var desc = new VideoProcessorContentDescription
            {
                InputFrameFormat = VideoFrameFormat.Progressive, InputFrameRate = new Rational(60, 1), InputWidth = (uint)w, InputHeight = (uint)h,
                OutputFrameRate = new Rational(60, 1), OutputWidth = (uint)w, OutputHeight = (uint)h, Usage = VideoUsage.PlaybackNormal,
            };
            en = vdev.CreateVideoProcessorEnumerator(desc);
            vp = vdev.CreateVideoProcessor(en, 0);
            vctx.VideoProcessorSetStreamFrameFormat(vp, 0, VideoFrameFormat.Progressive);
        }

        public void Convert(ID3D11Texture2D src, uint srcIndex, ID3D11Texture2D dst, bool toRgb)
        {
            if (vctx1 != null)
            {
                vctx1.VideoProcessorSetStreamColorSpace1(vp, 0, toRgb ? ColorSpaceType.YcbcrStudioG22LeftP709 : ColorSpaceType.RgbFullG22NoneP709);
                vctx1.VideoProcessorSetOutputColorSpace1(vp, toRgb ? ColorSpaceType.RgbFullG22NoneP709 : ColorSpaceType.YcbcrStudioG22LeftP709);
            }
            using var input = vdev.CreateVideoProcessorInputView(src, en, new VideoProcessorInputViewDescription
            {
                FourCC = 0, ViewDimension = VideoProcessorInputViewDimension.Texture2D,
                Texture2D = new Texture2DVideoProcessorInputView { MipSlice = 0, ArraySlice = srcIndex },
            });
            if (!outputs.TryGetValue(dst, out var output))
                outputs[dst] = output = vdev.CreateVideoProcessorOutputView(dst, en,
                    new VideoProcessorOutputViewDescription { ViewDimension = VideoProcessorOutputViewDimension.Texture2D });
            var stream = new VideoProcessorStream { Enable = true, InputSurface = input };
            vctx.VideoProcessorBlt(vp, output, 0, [stream]).CheckError();
        }

        public void Dispose()
        {
            foreach (var o in outputs.Values) o.Dispose();
            vp.Dispose(); en.Dispose(); vctx1?.Dispose(); vctx.Dispose(); vdev.Dispose();
        }
    }

    /// <summary>Media Foundation's sink writer: frames in as NV12 textures, an MP4 out (hardware encoders where the PC has them).</summary>
    private sealed class Writer : IDisposable
    {
        private readonly IMFSinkWriter writer;
        private readonly IMFAttributes attrs;
        private readonly int stream;
        private readonly int frameBytes;
        public readonly string EncoderName = "Media Foundation";
        public readonly bool Hardware;

        private static long Pack(int a, int b) => ((long)a << 32) | (uint)b;

        public Writer(MediaDevice media, string path, ExportJob job, int w, int h)
        {
            int fps = job.Fps();
            frameBytes = w * h * 3 / 2;
            attrs = MediaFactory.MFCreateAttributes(4);
            attrs.Set(SinkWriterAttributeKeys.ReadwriteEnableHardwareTransforms, 1u);
            attrs.Set(SinkWriterAttributeKeys.D3DManager, media.Manager);
            attrs.Set(SinkWriterAttributeKeys.DisableThrottling, 1u);
            writer = MediaFactory.MFCreateSinkWriterFromURL(path, null, attrs);
            using (var output = MediaFactory.MFCreateMediaType())
            {
                output.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
                output.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.FromFourCC(new FourCC(job.Codec() switch { VideoCodec.Avc => "H264", VideoCodec.Hevc => "HEVC", _ => "AV01" })));
                output.Set(MediaTypeAttributeKeys.AvgBitrate, (uint)(job.Mbps() * 1_000_000));
                output.Set(MediaTypeAttributeKeys.InterlaceMode, 2u);                       // progressive
                output.Set(MediaTypeAttributeKeys.FrameSize, (ulong)Pack(w, h));
                output.Set(MediaTypeAttributeKeys.FrameRate, (ulong)Pack(fps, 1));
                output.Set(MediaTypeAttributeKeys.PixelAspectRatio, (ulong)Pack(1, 1));
                output.Set(MediaTypeAttributeKeys.MaxKeyframeSpacing, (uint)fps);
                if (job.Codec() == VideoCodec.Avc) output.Set(MediaTypeAttributeKeys.Mpeg2Profile, 100u);   // High
                else if (job.Codec() == VideoCodec.Hevc) output.Set(MediaTypeAttributeKeys.Mpeg2Profile, 1u);   // Main
                stream = writer.AddStream(output);
            }
            using (var input = MediaFactory.MFCreateMediaType())
            {
                input.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
                input.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.FromFourCC(new FourCC("NV12")));
                input.Set(MediaTypeAttributeKeys.InterlaceMode, 2u);
                input.Set(MediaTypeAttributeKeys.FrameSize, (ulong)Pack(w, h));
                input.Set(MediaTypeAttributeKeys.FrameRate, (ulong)Pack(fps, 1));
                input.Set(MediaTypeAttributeKeys.PixelAspectRatio, (ulong)Pack(1, 1));
                writer.SetInputMediaType(stream, input, null);
            }
            writer.BeginWriting();
            try   // which encoder the writer chose: hardware ones carry a hardware URL
            {
                using var ex = writer.QueryInterfaceOrNull<IMFSinkWriterEx>();
                if (ex != null)
                {
                    ex.GetTransformForStream(stream, 0, out _, out var transform);
                    using (transform)
                    {
                        try { Hardware = !string.IsNullOrEmpty(transform.Attributes.GetAllocatedString(TransformAttributeKeys.MftEnumHardwareUrlAttribute)); } catch (Exception) { }
                        try { EncoderName = transform.Attributes.GetAllocatedString(TransformAttributeKeys.MftFriendlyNameAttribute) ?? EncoderName; } catch (Exception) { }
                    }
                }
            }
            catch (Exception) { }
            if (!Hardware && EncoderName == "Media Foundation") EncoderName = "Media Foundation (software)";
        }

        /// <summary>One frame (an NV12 texture) at frame `k`; blocks while the encoder is behind.</summary>
        public void Write(ID3D11Texture2D frame, int k, int fps)
        {
            using var buffer = MediaFactory.MFCreateDXGISurfaceBuffer(typeof(ID3D11Texture2D).GUID, frame, 0, false);
            buffer.CurrentLength = frameBytes;
            using var sample = MediaFactory.MFCreateSample();
            sample.AddBuffer(buffer);
            sample.SampleTime = k * 10_000_000L / fps;
            sample.SampleDuration = 10_000_000L / fps;
            writer.WriteSample(stream, sample);
        }

        public void Finish() => writer.Finalize();

        public void Dispose() { writer.Dispose(); attrs.Dispose(); }
    }

    /// <summary>The lower third: a 1600×240 title card and the eleven glyphs of the timecode, drawn once with WPF.</summary>
    private sealed class TitleArt
    {
        public const int CardW = 1600, CardH = 240, GlyphW = 64, GlyphH = 96;
        private byte[] card = new byte[CardW * CardH * 4], glyphs = new byte[11 * GlyphW * GlyphH * 4];

        public static TitleArt Render()
        {
            var art = new TitleArt();
            var t = new Thread(() =>
            {
                try { art.Draw(); } catch (Exception) { /* no fonts or no drawing: the edit goes without its title */ }
            });
            t.SetApartmentState(ApartmentState.STA);
            t.Start();
            t.Join();
            return art;
        }

        private static byte[] Pixels(DrawingVisual v, int w, int h)
        {
            var bmp = new RenderTargetBitmap(w, h, 96, 96, PixelFormats.Pbgra32);
            bmp.Render(v);
            var px = new byte[w * h * 4];
            bmp.CopyPixels(px, w * 4, 0);
            return px;
        }

        private void Draw()
        {
            var teal = new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x3D, 0xE8, 0xC2));
            var face = new Typeface(new System.Windows.Media.FontFamily("Consolas"), FontStyles.Normal, FontWeights.Bold, FontStretches.Normal);
            var regular = new Typeface(new System.Windows.Media.FontFamily("Consolas"), FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            FormattedText Text(string s, Typeface f, double size, System.Windows.Media.Brush b) => new(s, System.Globalization.CultureInfo.InvariantCulture, FlowDirection.LeftToRight, f, size, b, 1.0);
            var v = new DrawingVisual();
            using (var dc = v.RenderOpen())
            {
                dc.DrawRoundedRectangle(new SolidColorBrush(System.Windows.Media.Color.FromRgb(8, 12, 16)), null, new System.Windows.Rect(0, 0, CardW, CardH), 24, 24);
                dc.DrawRectangle(teal, null, new System.Windows.Rect(0, 0, 14, CardH));
                var title = Text("NEON CITY · NIGHT RUN", face, 92, new SolidColorBrush(System.Windows.Media.Color.FromRgb(242, 242, 242)));
                dc.DrawText(title, new System.Windows.Point(56, 112 - title.Baseline));
                var tc = Text("TC", regular, 64, teal);
                dc.DrawText(tc, new System.Windows.Point(58, 206 - tc.Baseline));
            }
            card = Pixels(v, CardW, CardH);
            var g = new DrawingVisual();
            using (var dc = g.RenderOpen())
                for (int i = 0; i < 11; i++)
                {
                    var t = Text("0123456789:"[i].ToString(), face, 80, teal);
                    dc.DrawText(t, new System.Windows.Point(i * GlyphW + (GlyphW - t.Width) / 2, GlyphH * 0.78 - t.Baseline));
                }
            glyphs = Pixels(g, 11 * GlyphW, GlyphH);
        }

        private static unsafe ID3D11Texture2D Texture(ID3D11Device dev, byte[] px, int w, int h)
        {
            fixed (byte* p = px)
                return dev.CreateTexture2D(new Texture2DDescription(Format.B8G8R8A8_UNorm, (uint)w, (uint)h, 1, 1, BindFlags.ShaderResource, ResourceUsage.Immutable),
                    new SubresourceData((IntPtr)p, (uint)(w * 4)));
        }

        public ID3D11Texture2D TitleTexture(ID3D11Device dev) => Texture(dev, card, CardW, CardH);
        public ID3D11Texture2D GlyphTexture(ID3D11Device dev) => Texture(dev, glyphs, 11 * GlyphW, GlyphH);
    }
}
