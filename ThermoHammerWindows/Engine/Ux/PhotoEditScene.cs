using System.Numerics;
using System.Runtime.InteropServices;
using StbImageSharp;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;
using Vortice.Mathematics;
using ThermoHammer.Engine.Gpu;

namespace ThermoHammer.Engine.Ux;

// ─────────────────────────────────────────────────────────────────────────────
// PHOTO EDITING — UX Test 4, the Direct3D 11 port of the phones' PhotoEditScene:
// a photo editor's filter chain over the 12 MP Lake Valley photo (4000×3000)
// every frame at full resolution — fisheye lens with bicubic resampling and
// colour fringing, a 129-texel Gaussian blur, portrait bokeh, then unsharp mask
// + 33³ LUT grade + vignette + grain. Each stage's result is shown in its own
// panel of a 2×2 preview. One frame = one full edit; the score is megapixels
// through the chain per second.
// ─────────────────────────────────────────────────────────────────────────────

[StructLayout(LayoutKind.Sequential)]
public struct PhotoParams
{
    public Vector2 Size, Dir, Texel;
    public float K, Radius, FocusY, Amount, Grade, Time;
}

public sealed class PhotoEditScene : IGpuScene
{
    public static readonly string[] Panels = ["FISHEYE LENS", "GAUSSIAN BLUR", "PORTRAIT BOKEH", "SHARPEN + COLOUR GRADE"];
    private const int LutSize = 33;
    private const int BlurRadius = 64;                 // texels each side
    private const int BlurTaps = BlurRadius / 2 + 1;   // centre + bilinear pairs
    private const float BokehRadius = 60;              // px at full strength
    public const int PanelW = 704, PanelH = 528;       // each panel: the 4:3 photo inside a 960×540 quadrant

    private readonly GpuBench bench;
    private readonly ID3D11Device dev;
    private readonly ID3D11DeviceContext ctx;
    private readonly int outW, outH;
    private readonly List<IDisposable> owned = [];
    private readonly ID3D11ShaderResourceView srcSrv, lutSrv;
    private readonly ID3D11Texture2D[] tex = new ID3D11Texture2D[3];          // A, B, C
    private readonly ID3D11RenderTargetView[] texRtv = new ID3D11RenderTargetView[3];
    private readonly ID3D11ShaderResourceView[] texSrv = new ID3D11ShaderResourceView[3];
    private readonly ID3D11RenderTargetView outputRtv;
    private readonly Pipe lens, blur, bokeh, finish, show;
    private readonly ID3D11DepthStencilState depthOff;
    private readonly ID3D11RasterizerState raster;
    private readonly ID3D11SamplerState linear;
    private readonly ID3D11Buffer paramsCb, blurCb;
    private static readonly ID3D11ShaderResourceView?[] NoViews = new ID3D11ShaderResourceView?[3];

    public ID3D11Texture2D Output { get; }
    public int TrianglesPerFrame => 6;
    public int PhotoW { get; }
    public int PhotoH { get; }
    public double Megapixels => PhotoW * (double)PhotoH / 1e6;

    private T Own<T>(T d) where T : IDisposable { owned.Add(d); return d; }

    /// <param name="jpeg">The bundled photo (assets\media\photo_lake.jpg, the Lake Valley photo of the phones' photo_lake.webp).</param>
    public PhotoEditScene(GpuBench bench, byte[] jpeg, int width, int height)
    {
        this.bench = bench; dev = bench.Device; ctx = bench.Ctx;
        outW = width; outH = height;
        var image = ImageResult.FromMemory(jpeg, ColorComponents.RedGreenBlueAlpha);
        PhotoW = image.Width; PhotoH = image.Height;
        unsafe
        {
            fixed (byte* p = image.Data)
            {
                var src = Own(dev.CreateTexture2D(new Texture2DDescription(Format.R8G8B8A8_UNorm, (uint)PhotoW, (uint)PhotoH, 1, 1, BindFlags.ShaderResource, ResourceUsage.Immutable),
                    new SubresourceData((IntPtr)p, (uint)(PhotoW * 4))));
                srcSrv = Own(dev.CreateShaderResourceView(src));
            }
        }
        for (int i = 0; i < 3; i++)
        {
            tex[i] = Own(dev.CreateTexture2D(new Texture2DDescription(Format.R8G8B8A8_UNorm, (uint)PhotoW, (uint)PhotoH, 1, 1, BindFlags.RenderTarget | BindFlags.ShaderResource)));
            texRtv[i] = Own(dev.CreateRenderTargetView(tex[i]));
            texSrv[i] = Own(dev.CreateShaderResourceView(tex[i]));
        }
        Output = Own(dev.CreateTexture2D(new Texture2DDescription(Format.R8G8B8A8_UNorm, (uint)width, (uint)height, 1, 1, BindFlags.RenderTarget | BindFlags.ShaderResource)));
        outputRtv = Own(dev.CreateRenderTargetView(Output));
        lutSrv = GradeLut();
        var opaque = Own(dev.CreateBlendState(BlendDescription.Opaque));
        const string F = "PhotoEdit";
        lens = bench.Pipeline(F, "photoVS", "photoLensFS", null, opaque);
        blur = bench.Pipeline(F, "photoVS", "photoBlurFS", null, opaque);
        bokeh = bench.Pipeline(F, "photoVS", "photoBokehFS", null, opaque);
        finish = bench.Pipeline(F, "photoVS", "photoFinishFS", null, opaque);
        show = bench.Pipeline(F, "photoVS", "photoShowFS", null, opaque);
        depthOff = Own(dev.CreateDepthStencilState(new DepthStencilDescription(false, DepthWriteMask.Zero, ComparisonFunction.Always)));
        raster = Own(dev.CreateRasterizerState(new RasterizerDescription(CullMode.None, FillMode.Solid) { DepthClipEnable = true }));
        linear = Own(dev.CreateSamplerState(new SamplerDescription(Filter.MinMagMipLinear, TextureAddressMode.Clamp, TextureAddressMode.Clamp,
            TextureAddressMode.Clamp, 0, 1, ComparisonFunction.Never, new Color4(0, 0, 0, 0), 0, float.MaxValue)));
        paramsCb = Own(dev.CreateBuffer(new BufferDescription((uint)((Marshal.SizeOf<PhotoParams>() + 15) & ~15), BindFlags.ConstantBuffer, ResourceUsage.Default)));
        blurCb = Own(dev.CreateBuffer(new BufferDescription(9 * 16 * 2, BindFlags.ConstantBuffer, ResourceUsage.Default)));
        Gaussian();
    }

    /// <summary>σ ≈ 21 texels over ±64, folded into 32 bilinear pairs a side (+ the centre).</summary>
    private void Gaussian()
    {
        double sigma = BlurRadius / 3.0;
        var g = Enumerable.Range(0, BlurRadius + 1).Select(i => Math.Exp(-(double)(i * i) / (2 * sigma * sigma))).ToArray();
        double norm = g[0] + 2 * g.Skip(1).Sum();
        var table = new float[9 * 4 * 2];          // 36 offsets, then 36 weights (the first 33 used)
        table[36] = (float)(g[0] / norm);
        for (int k = 1; k < BlurTaps; k++)
        {
            int a = 2 * k - 1, b = 2 * k;
            double w = g[a] + g[b];
            table[36 + k] = (float)(w / norm);
            table[k] = (float)((a * g[a] + b * g[b]) / w);
        }
        ctx.UpdateSubresource(table, blurCb);
    }

    /// <summary>A filmic grade: gentle S-curve, teal shadows, warm highlights, a little more saturation.</summary>
    private ID3D11ShaderResourceView GradeLut()
    {
        int n = LutSize;
        var data = new byte[n * n * n * 4];
        static double Curve(double x) => x + 0.35 * (x * x * (3 - 2 * x) - x);
        int o = 0;
        for (int b = 0; b < n; b++)
            for (int g = 0; g < n; g++)
                for (int r = 0; r < n; r++)
                {
                    double cr = Curve(r / (double)(n - 1)), cg = Curve(g / (double)(n - 1)), cb = Curve(b / (double)(n - 1));
                    double l = 0.2126 * cr + 0.7152 * cg + 0.0722 * cb;
                    double sh = (1 - l) * (1 - l), hi = l * l;
                    cr += -0.03 * sh + 0.07 * hi; cg += 0.03 * sh + 0.025 * hi; cb += 0.06 * sh - 0.05 * hi;
                    double l2 = 0.2126 * cr + 0.7152 * cg + 0.0722 * cb;
                    cr = l2 + (cr - l2) * 1.15; cg = l2 + (cg - l2) * 1.15; cb = l2 + (cb - l2) * 1.15;
                    foreach (double v in new[] { cr, cg, cb }) data[o++] = (byte)(int)(Math.Max(0, Math.Min(1, v)) * 255 + 0.5);
                    data[o++] = 255;
                }
        unsafe
        {
            fixed (byte* p = data)
            {
                var t = Own(dev.CreateTexture3D(new Texture3DDescription(Format.R8G8B8A8_UNorm, (uint)n, (uint)n, (uint)n, 1, BindFlags.ShaderResource, ResourceUsage.Immutable),
                    new SubresourceData((IntPtr)p, (uint)(n * 4), (uint)(n * n * 4))));
                return Own(dev.CreateShaderResourceView(t));
            }
        }
    }

    public void Encode(float t)
    {
        const int A = 0, B = 1, C = 2;
        var p = new PhotoParams { Size = new(PhotoW, PhotoH), Texel = new(1f / PhotoW, 1f / PhotoH) };
        ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        ctx.IASetInputLayout(null);
        // LENS: src → A
        p.K = 0.75f + 0.2f * MathF.Sin(t * 0.7f);
        Pass(lens, A, [srcSrv], p);
        Panel(0, texSrv[A], clear: true);
        // BLUR: A → B (horizontal) → C (vertical)
        p.Dir = new(p.Texel.X, 0); Pass(blur, B, [texSrv[A]], p);
        p.Dir = new(0, p.Texel.Y); Pass(blur, C, [texSrv[B]], p);
        Panel(1, texSrv[C]);
        // BOKEH: A (+ its blur C) → B
        p.Radius = BokehRadius * PhotoW / 4000;
        p.FocusY = 0.62f + 0.14f * MathF.Sin(t * 0.45f);
        Pass(bokeh, B, [texSrv[A], texSrv[C]], p);
        Panel(2, texSrv[B]);
        // FINISH: B + C + LUT → A
        p.Amount = 0.55f + 0.25f * MathF.Sin(t * 1.1f);
        p.Grade = 0.8f + 0.2f * MathF.Sin(t * 0.5f);
        p.Time = t;
        Pass(finish, A, [texSrv[B], texSrv[C], lutSrv], p);
        Panel(3, texSrv[A]);
        ctx.PSSetShaderResources(0, NoViews);
        ctx.OMSetRenderTargets((ID3D11RenderTargetView?)null);
    }

    private void Setup(Pipe pipe)
    {
        ctx.VSSetShader(pipe.VS);
        ctx.PSSetShader(pipe.PS);
        ctx.OMSetBlendState(pipe.Blend);
        ctx.RSSetState(raster);
        ctx.OMSetDepthStencilState(depthOff);
        ctx.PSSetSampler(0, linear);
    }

    private void Pass(Pipe pipe, int target, ID3D11ShaderResourceView[] sources, in PhotoParams p)
    {
        ctx.PSSetShaderResources(0, NoViews);
        ctx.OMSetRenderTargets(texRtv[target]);
        ctx.RSSetViewport(0, 0, PhotoW, PhotoH);
        Setup(pipe);
        ctx.UpdateSubresource(in p, paramsCb);
        ctx.PSSetConstantBuffer(0, paramsCb);
        ctx.PSSetConstantBuffer(1, blurCb);
        ctx.PSSetShaderResources(0, sources);
        ctx.Draw(3, 0);
    }

    /// <summary>Draws a stage's result, scaled down, into its quadrant of the 16:9 output.</summary>
    private void Panel(int index, ID3D11ShaderResourceView view, bool clear = false)
    {
        ctx.PSSetShaderResources(0, NoViews);
        ctx.OMSetRenderTargets(outputRtv);
        if (clear) ctx.ClearRenderTargetView(outputRtv, new Color4(0.043f, 0.055f, 0.071f, 1));
        int qw = outW / 2, qh = outH / 2, pw = PanelW * outW / 1920, ph = PanelH * outH / 1080;
        ctx.RSSetViewport((index % 2) * qw + (qw - pw) / 2, (index / 2) * qh + (qh - ph) / 2, pw, ph);
        Setup(show);
        ctx.PSSetShaderResources(0, [view]);
        ctx.Draw(3, 0);
    }

    public void Dispose()
    {
        ctx.ClearState();
        ctx.Flush();
        foreach (var o in Enumerable.Reverse(owned)) o.Dispose();
        owned.Clear();
    }
}
