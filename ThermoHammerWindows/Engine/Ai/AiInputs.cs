using StbImageSharp;

namespace ThermoHammer.Engine.Ai;

public static class AiTestInfo
{
    public static int Number(this AiTest t) => (int)t;
    public static string Title(this AiTest t) => t switch
    {
        AiTest.Classify => "IMAGE CLASSIFICATION", AiTest.Detect => "OBJECT DETECTION", AiTest.Segment => "STREET SEGMENTATION",
        AiTest.Upscale => "SUPER RESOLUTION", AiTest.Depth => "DEPTH ESTIMATION", _ => "SOUND RECOGNITION",
    };
    public static string Model(this AiTest t) => t switch
    {
        AiTest.Classify => "MobileNet-v2 · 224×224", AiTest.Detect => "YOLOX · 640×640", AiTest.Segment => "FFNet-40S · Cityscapes · 2048×1024",
        AiTest.Upscale => "Real-ESRGAN ×4 · 128 → 512", AiTest.Depth => "MiDaS v2 · 256×256", _ => "YAMNet · 0.96 s of audio",
    };
    public static int Seconds(this AiTest t) => t switch { AiTest.Classify => 7, AiTest.Sound => 7, _ => 8 };
    /// <summary>The model's files: `ai/&lt;asset&gt;.q.onnx` (CPU, NPU) and `.f16.onnx` (GPU), and `&lt;asset&gt;.json`.</summary>
    public static string Asset(this AiTest t) => t switch
    {
        AiTest.Classify => "mobilenet_v2", AiTest.Detect => "yolox", AiTest.Segment => "ffnet_40s", AiTest.Upscale => "real_esrgan_general_x4v3",
        AiTest.Depth => "midas", _ => "yamnet",
    };
}

/// <summary>An 8-bit RGBA picture, rows from the top.</summary>
public sealed class Img(int w, int h, byte[]? rgba = null)
{
    public readonly int W = w, H = h;
    public readonly byte[] Rgba = rgba ?? new byte[w * h * 4];

    public static Img Decode(byte[] file)
    {
        var r = ImageResult.FromMemory(file, ColorComponents.RedGreenBlueAlpha);
        return new Img(r.Width, r.Height, r.Data);
    }

    public Img Crop(int x, int y, int w, int h)
    {
        var o = new Img(w, h);
        for (int row = 0; row < h; row++) Buffer.BlockCopy(Rgba, ((y + row) * W + x) * 4, o.Rgba, row * w * 4, w * 4);
        return o;
    }

    public Img Square()
    {
        int s = Math.Min(W, H);
        return Crop((W - s) / 2, (H - s) / 2, s, s);
    }

    /// <summary>Separable resample with a tent filter widened by the shrink factor, so a downscale averages (no aliasing).</summary>
    public Img Scale(int nw, int nh)
    {
        if (nw == W && nh == H) return this;
        var tmp = new float[nw * H * 4];
        Pass(Rgba, W, H, tmp, nw, nh: H, horizontal: true);
        var o = new Img(nw, nh);
        var bytes = new float[nw * nh * 4];
        Pass(tmp, nw, H, bytes, nw, nh, horizontal: false);
        for (int i = 0; i < bytes.Length; i++) o.Rgba[i] = (byte)Math.Clamp((int)(bytes[i] + 0.5f), 0, 255);
        return o;
    }

    private static void Pass(Array src, int sw, int sh, float[] dst, int dw, int nh, bool horizontal)
    {
        int from = horizontal ? sw : sh, to = horizontal ? dw : nh;
        double scale = (double)from / to, support = Math.Max(1, scale);
        var s8 = src as byte[];
        var sf = src as float[];
        float Get(int i) => s8 != null ? s8[i] : sf![i];
        for (int o = 0; o < to; o++)
        {
            double centre = (o + 0.5) * scale;
            int lo = Math.Max(0, (int)Math.Floor(centre - support)), hi = Math.Min(from - 1, (int)Math.Ceiling(centre + support));
            var weights = new float[hi - lo + 1];
            float sum = 0;
            for (int k = lo; k <= hi; k++) { weights[k - lo] = (float)Math.Max(0, 1 - Math.Abs((k + 0.5 - centre) / support)); sum += weights[k - lo]; }
            if (sum <= 0) { weights[Math.Min(hi, Math.Max(lo, (int)centre)) - lo] = 1; sum = 1; }
            if (horizontal)
                for (int y = 0; y < sh; y++)
                    for (int c = 0; c < 4; c++)
                    {
                        float acc = 0;
                        for (int k = lo; k <= hi; k++) acc += Get((y * sw + k) * 4 + c) * weights[k - lo];
                        dst[(y * dw + o) * 4 + c] = acc / sum;
                    }
            else
                for (int x = 0; x < sw; x++)
                    for (int c = 0; c < 4; c++)
                    {
                        float acc = 0;
                        for (int k = lo; k <= hi; k++) acc += Get((k * sw + x) * 4 + c) * weights[k - lo];
                        dst[(o * sw + x) * 4 + c] = acc / sum;
                    }
        }
    }

    /// <summary>RGB bytes, row by row from the top.</summary>
    public byte[] Rgb()
    {
        var o = new byte[W * H * 3];
        for (int i = 0; i < W * H; i++) { o[i * 3] = Rgba[i * 4]; o[i * 3 + 1] = Rgba[i * 4 + 1]; o[i * 3 + 2] = Rgba[i * 4 + 2]; }
        return o;
    }

    /// <summary>Premultiplied BGRA, as WPF's Pbgra32 takes it.</summary>
    public byte[] Pbgra()
    {
        var o = new byte[W * H * 4];
        for (int i = 0; i < W * H; i++)
        {
            int a = Rgba[i * 4 + 3];
            o[i * 4] = (byte)(Rgba[i * 4 + 2] * a / 255); o[i * 4 + 1] = (byte)(Rgba[i * 4 + 1] * a / 255); o[i * 4 + 2] = (byte)(Rgba[i * 4] * a / 255); o[i * 4 + 3] = (byte)a;
        }
        return o;
    }

    /// <summary>From 0xAARRGGBB words (as on Android).</summary>
    public static Img FromArgb(uint[] argb, int w, int h)
    {
        var o = new Img(w, h);
        for (int i = 0; i < w * h; i++)
        {
            uint p = argb[i];
            o.Rgba[i * 4] = (byte)(p >> 16); o.Rgba[i * 4 + 1] = (byte)(p >> 8); o.Rgba[i * 4 + 2] = (byte)p; o.Rgba[i * 4 + 3] = (byte)(p >> 24);
        }
        return o;
    }
}

/// <summary>A detection: box in the shown picture's pixels.</summary>
public sealed record AiBox(float Left, float Top, float Right, float Bottom, string Label, float Score);

/// <summary>What the stage shows for a model: what it saw and what it made of it.</summary>
public sealed record AiVisual(AiTest Test, Img Picture, Img? Result = null, List<AiBox>? Boxes = null, List<(string name, float p)>? Labels = null);

/// <summary>
/// The models' inputs, prepared once, and their outputs turned into pictures — the phones' AiInputs: the benchmark's own
/// renders (media\) cut and scaled the same way, and the synthesized piano phrase.
/// </summary>
public sealed class AiInputs
{
    private readonly Img lake, city;          // each at half size (Android decodes with inSampleSize 2): 2000×1500
    private readonly string dir = Path.Combine(AppContext.BaseDirectory, "assets");

    public AiInputs()
    {
        lake = Photo("photo_lake.jpg");
        city = Photo("photo_city.jpg");
    }

    private Img Photo(string name)
    {
        var full = Img.Decode(File.ReadAllBytes(Path.Combine(dir, "media", name)));
        return full.Scale(full.W / 2, full.H / 2);
    }

    private string[] Labels(string name)
    {
        string path = Path.Combine(dir, "ai", $"{name}_labels.txt");
        return File.Exists(path) ? File.ReadAllText(path).Split('\n').Select(s => s.Trim()).ToArray() : [];
    }

    /// <summary>The model's input bytes (NHWC RGB, 8-bit) and the picture shown while it runs.</summary>
    public (byte[] input, Img picture) Input(AiTest test)
    {
        switch (test)
        {
            case AiTest.Classify:
            {
                var sq = lake.Square();
                return (sq.Scale(224, 224).Rgb(), sq);
            }
            case AiTest.Detect:
            {
                // letterboxed into 640×640 (grey bars), as YOLOX was trained
                var shown = city.Scale(640, 480);
                var canvas = new Img(640, 640);
                for (int i = 0; i < 640 * 640; i++) { canvas.Rgba[i * 4] = canvas.Rgba[i * 4 + 1] = canvas.Rgba[i * 4 + 2] = 0x72; canvas.Rgba[i * 4 + 3] = 255; }
                Buffer.BlockCopy(shown.Rgba, 0, canvas.Rgba, 80 * 640 * 4, 640 * 480 * 4);
                return (canvas.Rgb(), shown);
            }
            case AiTest.Segment:
            {
                var wide = city.Crop(0, city.H / 6, city.W, city.W / 2);
                return (wide.Scale(2048, 1024).Rgb(), wide.Scale(1024, 512));
            }
            case AiTest.Upscale:
            {
                var small = city.Scale(1000, 750);
                var crop = small.Crop(720, 330, 128, 128);          // the SPA sign and shop lettering
                return (crop.Rgb(), crop);
            }
            case AiTest.Depth:
            {
                var sq = city.Square();
                return (sq.Scale(256, 256).Rgb(), sq.Scale(512, 512));
            }
            default:
            {
                var q = File.ReadAllBytes(Path.Combine(dir, "ai", "yamnet_input.bin"));
                return (q, Spectrogram(q));
            }
        }
    }

    /// <summary>Decodes the outputs (TFLite's 8-bit tensors) of `test`'s last inference into what the stage draws.</summary>
    public AiVisual Visual(AiTest test, Img picture, byte[][] outputs) => test switch
    {
        AiTest.Classify => new AiVisual(test, picture, Labels: Top(Dequant(outputs[0], 0.13067922f, 76), Labels("mobilenet_v2"), 3, softmax: true)),
        AiTest.Detect => new AiVisual(test, picture, Boxes: Detections(outputs[0], outputs[1], outputs[2], Labels("yolox"))),
        AiTest.Segment => new AiVisual(test, picture, Result: Segmentation(outputs[0])),
        AiTest.Upscale => new AiVisual(test, picture, Result: Upscaled(outputs[0])),
        AiTest.Depth => new AiVisual(test, picture, Result: Depth(outputs[0])),
        _ => new AiVisual(test, picture, Labels: Top(Dequant(outputs[0], 0.7587276f, 190), Labels("yamnet"), 3, softmax: false)),
    };

    // ── decoding ──

    private static float[] Dequant(byte[] q, float scale, int zero) => q.Select(v => (v - zero) * scale).ToArray();

    private static List<(string, float)> Top(float[] v, string[] names, int k, bool softmax)
    {
        float[] p;
        if (softmax)
        {
            float m = v.Max();
            var e = v.Select(x => MathF.Exp(x - m)).ToArray();
            float s = e.Sum();
            p = e.Select(x => x / s).ToArray();
        }
        else p = v.Select(x => 1 / (1 + MathF.Exp(-x))).ToArray();
        return Enumerable.Range(0, p.Length).OrderByDescending(i => p[i]).Take(k).Select(i => (i < names.Length ? names[i] : $"#{i}", p[i])).ToList();
    }

    /// <summary>YOLOX: 8400 candidate boxes (x1 y1 x2 y2 in the 640×640 input), scores and classes → kept after NMS.</summary>
    private static List<AiBox> Detections(byte[] boxesQ, byte[] scoresQ, byte[] classes, string[] names)
    {
        var b = Dequant(boxesQ, 4.416495f, 51);
        var scores = Dequant(scoresQ, 0.0038126425f, 0);
        var candidates = Enumerable.Range(0, scores.Length).Where(i => scores[i] > 0.35f).OrderByDescending(i => scores[i]).ToList();
        float Iou(int i, int j)
        {
            float x1 = Math.Max(b[i * 4], b[j * 4]), y1 = Math.Max(b[i * 4 + 1], b[j * 4 + 1]);
            float x2 = Math.Min(b[i * 4 + 2], b[j * 4 + 2]), y2 = Math.Min(b[i * 4 + 3], b[j * 4 + 3]);
            float inter = Math.Max(0, x2 - x1) * Math.Max(0, y2 - y1);
            float a = (b[i * 4 + 2] - b[i * 4]) * (b[i * 4 + 3] - b[i * 4 + 1]);
            float c = (b[j * 4 + 2] - b[j * 4]) * (b[j * 4 + 3] - b[j * 4 + 1]);
            return inter / Math.Max(1e-6f, a + c - inter);
        }
        var kept = new List<int>();
        foreach (int i in candidates)
        {
            if (kept.Count >= 25) break;
            if (!kept.Any(k => classes[k] == classes[i] && Iou(i, k) > 0.45f)) kept.Add(i);
        }
        return kept.Select(i =>
        {
            int c = classes[i];
            return new AiBox(b[i * 4], b[i * 4 + 1] - 80, b[i * 4 + 2], b[i * 4 + 3] - 80, c < names.Length ? names[c] : "object", scores[i]);
        }).ToList();
    }

    /// <summary>FFNet: 128 × 256 × 19 Cityscapes class scores → a coloured, half-transparent class map.</summary>
    private static Img Segmentation(byte[] q)
    {
        const int h = 128, w = 256, c = 19;
        var px = new uint[w * h];
        for (int i = 0; i < w * h; i++)
        {
            int best = 0, bestV = -1;
            for (int k = 0; k < c; k++) { int v = q[i * c + k]; if (v > bestV) { bestV = v; best = k; } }
            px[i] = (0xA0u << 24) | Cityscapes[best];
        }
        return Img.FromArgb(px, w, h);
    }

    private static Img Upscaled(byte[] q)
    {
        const int n = 512;
        var px = new uint[n * n];
        for (int i = 0; i < n * n; i++)
        {
            uint Ch(int k) => (uint)Math.Clamp((int)((q[i * 3 + k] - 25) * 0.004972266f * 255), 0, 255);
            px[i] = (0xFFu << 24) | (Ch(0) << 16) | (Ch(1) << 8) | Ch(2);
        }
        return Img.FromArgb(px, n, n);
    }

    /// <summary>MiDaS: relative inverse depth (bigger = nearer) → a heat map, near warm.</summary>
    private static Img Depth(byte[] q)
    {
        const int n = 256;
        int lo = q.Min(), hi = q.Max();
        var px = new uint[n * n];
        for (int i = 0; i < n * n; i++) px[i] = Magma((float)(q[i] - lo) / Math.Max(1, hi - lo));
        return Img.FromArgb(px, n, n).Scale(512, 512);
    }

    /// <summary>YAMNet's 96 × 64 log-mel patch as a picture: time across, pitch up.</summary>
    private static Img Spectrogram(byte[] q)
    {
        const int frames = 96, bands = 64;
        var px = new uint[frames * bands];
        for (int t = 0; t < frames; t++)
            for (int m = 0; m < bands; m++)
                px[(bands - 1 - m) * frames + t] = Magma(Math.Max(0, Math.Min(180, q[t * bands + m] - 60)) / 180f);
        return Img.FromArgb(px, frames, bands).Scale(960, 512);
    }

    /// <summary>A magma-like ramp: black → purple → orange → pale yellow.</summary>
    private static uint Magma(float t)
    {
        float x = Math.Clamp(t, 0, 1);
        uint r = (uint)(255 * Math.Min(1, x * 1.6f));
        uint g = (uint)Math.Min(255, 255 * Math.Max(0, x * x * 1.1f - 0.05f));
        uint b = (uint)Math.Max(0, Math.Min(255, 255 * (0.35f + 0.5f * MathF.Sin(x * 3.1f) - 0.2f * x)));
        return (0xFFu << 24) | (r << 16) | (g << 8) | b;
    }

    /// <summary>Cityscapes' standard class colours (road, sidewalk, building, … bicycle).</summary>
    private static readonly uint[] Cityscapes =
    [
        0x804080, 0xF423E8, 0x464646, 0x66669C, 0xBE9999, 0x999999, 0xFAAA1E, 0xDCDC00, 0x6B8E23, 0x98FB98,
        0x4682B4, 0xDC143C, 0xFF0000, 0x00008E, 0x000046, 0x003C64, 0x005064, 0x0000E6, 0x770B20,
    ];
}
