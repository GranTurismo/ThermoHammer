using System.Reflection;
using System.Runtime.InteropServices;
using Vortice.D3DCompiler;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;
using Vortice.Mathematics;

namespace ThermoHammer.Engine.Gpu;

// ─────────────────────────────────────────────────────────────────────────────
// GPU BENCHMARK — renders a scene offscreen at 1920×1080 (comparable across
// screens) as fast as it goes, every frame timed from encode to GPU completion
// (Android: render + glFinish; iPhone: encode to completed command buffer). The
// stage only gets a small copy of a finished frame now and then, outside the timing.
// ─────────────────────────────────────────────────────────────────────────────

public interface IGpuScene : IDisposable
{
    /// <summary>The finished frame (RGBA8, <see cref="GpuBench.Width"/> × <see cref="GpuBench.Height"/>).</summary>
    ID3D11Texture2D Output { get; }
    int TrianglesPerFrame { get; }
    /// <summary>Records one frame at timeline time <paramref name="t"/> (s).</summary>
    void Encode(float t);
}

public sealed class GpuError(string message) : Exception(message);

/// <summary>A pixel shader and vertex shader pair with its input layout and blend state — one draw configuration.</summary>
public sealed class Pipe(ID3D11VertexShader vs, ID3D11PixelShader? ps, ID3D11InputLayout? layout, ID3D11BlendState blend) : IDisposable
{
    public readonly ID3D11VertexShader VS = vs;
    public readonly ID3D11PixelShader? PS = ps;
    public readonly ID3D11InputLayout? Layout = layout;
    public readonly ID3D11BlendState Blend = blend;

    public void Dispose() { VS.Dispose(); PS?.Dispose(); Layout?.Dispose(); Blend.Dispose(); }
}

public sealed class GpuBench : IDisposable
{
    public const int Width = 1920, Height = 1080;
    public const uint ViewW = 960, ViewH = 540;

    public readonly ID3D11Device Device;
    public readonly ID3D11DeviceContext Ctx;
    /// <summary>The adapter's name, e.g. "NVIDIA GeForce RTX 4070".</summary>
    public readonly string Name;
    public readonly FeatureLevel Level;

    private readonly Pipe present;
    private readonly ID3D11SamplerState linear;
    private readonly ID3D11RasterizerState viewRaster;
    private readonly ID3D11Texture2D viewTex, staging;
    private readonly ID3D11RenderTargetView viewRtv;
    private readonly ID3D11Query done;
    private readonly List<IDisposable> owned = [];

    /// <summary>Receives a small BGRA copy of a finished frame (960×540) on the benchmark thread; never part of a measurement.</summary>
    public Action<byte[]>? FrameSink;

    public static GpuBench? TryCreate(out string? problem)
    {
        try { problem = null; return new GpuBench(); }
        catch (Exception e) { problem = e.Message; return null; }
    }

    private readonly bool ownsDevice;

    public GpuBench() : this(CreateDevice(out var ctx, out var name, out var level), ctx, name, level, true) { }

    /// <summary>The same helpers and run loop on a device someone else owns (the media tests', shared with Media Foundation).</summary>
    public GpuBench(Ux.MediaDevice media) : this(media.Device, media.Context, media.AdapterName, media.Device.FeatureLevel, false) { }

    private static ID3D11Device CreateDevice(out ID3D11DeviceContext ctx, out string name, out FeatureLevel level)
    {
        using var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();
        IDXGIAdapter1? best = null;
        name = "";
        for (uint i = 0; factory.EnumAdapters1(i, out var a).Success; i++)
        {
            var d = a.Description1;
            if ((d.Flags & AdapterFlags.Software) != 0) { a.Dispose(); continue; }
            // the adapter with the most video memory (a laptop's discrete GPU over its integrated one)
            if (best == null || d.DedicatedVideoMemory > best.Description1.DedicatedVideoMemory) { best?.Dispose(); best = a; name = d.Description.Trim(); }
            else a.Dispose();
        }
        if (best == null) throw new GpuError("no graphics adapter");
        var levels = new[] { FeatureLevel.Level_11_1, FeatureLevel.Level_11_0, FeatureLevel.Level_10_1 };
        D3D11.D3D11CreateDevice(best, DriverType.Unknown, DeviceCreationFlags.BgraSupport, levels, out var device, out level, out ctx).CheckError();
        best.Dispose();
        if (level < FeatureLevel.Level_10_1) throw new GpuError("the graphics driver supports only Direct3D " + level);
        return device;
    }

    private GpuBench(ID3D11Device device, ID3D11DeviceContext ctx, string name, FeatureLevel level, bool owns)
    {
        Device = device; Ctx = ctx; Name = name; Level = level; ownsDevice = owns;
        linear = Device.CreateSamplerState(new SamplerDescription(Filter.MinMagMipLinear, TextureAddressMode.Clamp, TextureAddressMode.Clamp,
            TextureAddressMode.Clamp, 0, 1, ComparisonFunction.Never, new Color4(0, 0, 0, 0), 0, float.MaxValue));
        present = Pipeline("Present", "presentVS", "presentFS", null, OpaqueBlend());
        viewRaster = Device.CreateRasterizerState(RasterizerDescription.CullNone);     // the full-screen triangle winds counter-clockwise: the default state culls it
        viewTex = Device.CreateTexture2D(new Texture2DDescription(Format.B8G8R8A8_UNorm, ViewW, ViewH, 1, 1, BindFlags.RenderTarget));
        viewRtv = Device.CreateRenderTargetView(viewTex);
        staging = Device.CreateTexture2D(new Texture2DDescription(Format.B8G8R8A8_UNorm, ViewW, ViewH, 1, 1, BindFlags.None, ResourceUsage.Staging,
            CpuAccessFlags.Read));
        done = Device.CreateQuery(new QueryDescription(QueryType.Event, QueryFlags.None));
    }

    // ── shader and state helpers (shared by every scene) ──

    private static readonly Dictionary<string, string> sources = [];

    public static string Source(string file)
    {
        lock (sources)
        {
            if (sources.TryGetValue(file, out var s)) return s;
            using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream($"Shaders.{file}.hlsl")
                ?? throw new GpuError($"shader {file}.hlsl missing");
            using var reader = new StreamReader(stream);
            // ASCII only: the comments' "—", "×", "·" are several bytes, and Windows' d3dcompiler reads as many bytes as the
            // string has characters, so it cut the source short ("unexpected end of file"); Wine's doesn't count
            return sources[file] = new string(reader.ReadToEnd().Select(c => c < 128 ? c : '?').ToArray());
        }
    }

    /// <summary>Compiles one entry point of a shader file (with its defines) to bytecode.</summary>
    public static byte[] Compile(string file, string entry, string profile, params string[] defines)
    {
        var macros = defines.Select(d => new ShaderMacro(d, "1")).Append(new ShaderMacro(null!, null!)).ToArray();
        // THERMOHAMMER_SHADER_DEBUG=1: no optimisation, and a line per shader (for compilers that choke, e.g. Wine's)
        bool debug = Environment.GetEnvironmentVariable("THERMOHAMMER_SHADER_DEBUG") == "1";
        var flags = debug ? ShaderFlags.SkipOptimization : ShaderFlags.OptimizationLevel3;
        if (debug) Console.Error.WriteLine($"compiling {file} {entry} {profile} {string.Join(' ', defines)}");
        Compiler.Compile(Source(file), macros, null!, entry, $"{file}.hlsl", profile, flags, EffectFlags.None, out var blob, out var errors);
        if (blob == null) throw new GpuError($"{file} {entry}: {errors?.AsString()}");
        var bytes = blob.AsBytes().ToArray();
        blob.Dispose();
        errors?.Dispose();
        return bytes;
    }

    /// <summary>A vertex + pixel shader pair; <paramref name="layout"/> null = no vertex buffers (full-screen triangles, particles).</summary>
    public Pipe Pipeline(string file, string vs, string? ps, InputElementDescription[]? layout, ID3D11BlendState blend, params string[] defines)
    {
        var vsCode = Compile(file, vs, "vs_5_0", defines);
        var vsObj = Device.CreateVertexShader(vsCode);
        var psObj = ps == null ? null : Device.CreatePixelShader(Compile(file, ps, "ps_5_0", defines));
        var il = layout == null ? null : Device.CreateInputLayout(layout, vsCode);
        var p = new Pipe(vsObj, psObj, il, blend);
        owned.Add(p);
        return p;
    }

    public ID3D11BlendState OpaqueBlend() { var b = Device.CreateBlendState(BlendDescription.Opaque); owned.Add(b); return b; }

    /// <summary>Premultiplied alpha: source added, destination scaled by what the source leaves.</summary>
    public ID3D11BlendState PremultipliedBlend()
    {
        var d = new BlendDescription();
        d.RenderTarget[0] = new RenderTargetBlendDescription
        {
            BlendEnable = true, SourceBlend = Blend.One, DestinationBlend = Blend.InverseSourceAlpha, BlendOperation = BlendOperation.Add,
            SourceBlendAlpha = Blend.One, DestinationBlendAlpha = Blend.InverseSourceAlpha, BlendOperationAlpha = BlendOperation.Add,
            RenderTargetWriteMask = ColorWriteEnable.All,
        };
        var b = Device.CreateBlendState(d);
        owned.Add(b);
        return b;
    }

    public ID3D11Buffer Buffer(ReadOnlySpan<byte> data, BindFlags bind, int minBytes = 16)
    {
        int size = Math.Max(data.Length, minBytes);
        unsafe
        {
            byte[] tmp = new byte[(size + 15) & ~15];
            data.CopyTo(tmp);
            fixed (byte* p = tmp)
            {
                var b = Device.CreateBuffer(new BufferDescription((uint)tmp.Length, bind, ResourceUsage.Default), new SubresourceData((IntPtr)p));
                owned.Add(b);
                return b;
            }
        }
    }

    public ID3D11Buffer Buffer<T>(T[] values, BindFlags bind) where T : unmanaged => Buffer(MemoryMarshal.AsBytes(values.AsSpan()), bind);

    public ID3D11Buffer ConstantBuffer(int bytes)
    {
        var b = Device.CreateBuffer(new BufferDescription((uint)((bytes + 15) & ~15), BindFlags.ConstantBuffer, ResourceUsage.Default));
        owned.Add(b);
        return b;
    }

    public ID3D11Texture2D Texture(Format format, int w, int h, BindFlags bind, int samples = 1)
    {
        var t = Device.CreateTexture2D(new Texture2DDescription(format, (uint)w, (uint)h, 1, 1, bind, ResourceUsage.Default, CpuAccessFlags.None, (uint)samples, 0));
        owned.Add(t);
        return t;
    }

    public T Own<T>(T d) where T : IDisposable { owned.Add(d); return d; }

    public bool SupportsMultisample4 => Device.CheckMultisampleQualityLevels(Format.R8G8B8A8_UNorm, 4) > 0
        && Device.CheckMultisampleQualityLevels(Format.D32_Float, 4) > 0;

    // ── the run ──

    /// <summary>
    /// Renders `seconds` of the scene's timeline (wall time, like the phones) and returns every frame's timeline start and
    /// render time. <paramref name="onFrame"/> gets the timeline position and the fps of the last ~¼ s.
    /// </summary>
    public (float[] starts, long[] durNs) Run(IGpuScene scene, float seconds, Func<bool> isActive, Action<float, double> onFrame)
    {
        var starts = new List<float>(8192);
        var durs = new List<long>(8192);
        long start = Clock.Ns(), lastPresent = 0, windowStart = start;
        int windowFrames = 0;
        double fps = 0;
        while (isActive())
        {
            long t0 = Clock.Ns();
            float sceneT = (float)((t0 - start) / 1e9);
            if (sceneT >= seconds) break;
            scene.Encode(sceneT);
            Ctx.End(done);
            Ctx.Flush();
            // true frame completion — not just API enqueue
            WaitForGpu();
            long end = Clock.Ns();
            starts.Add(sceneT);
            durs.Add(end - t0);
            windowFrames++;
            if (end - windowStart > 250_000_000)
            {
                fps = windowFrames * 1e9 / (end - windowStart);
                windowStart = end; windowFrames = 0;
            }
            if (end - lastPresent > 66_000_000)   // ~15 fps on screen, never timed
            {
                lastPresent = end;
                Show(scene.Output);
                onFrame(sceneT, fps);
            }
        }
        return (starts.ToArray(), durs.ToArray());
    }

    /// <summary>Blocks until the GPU has run everything submitted so far (S_OK from the event query; S_FALSE means not yet).</summary>
    private unsafe void WaitForGpu()
    {
        int busy = 0;
        while (true)
        {
            var r = Ctx.GetData(done, (IntPtr)(&busy), 4, AsyncGetDataFlags.None);
            if (r.Code == 0 && busy != 0) return;
            if (r.Failure) throw new GpuError($"the GPU stopped answering ({r.Code:X8})");
            Thread.Yield();
        }
    }

    private readonly byte[] viewBytes = new byte[ViewW * ViewH * 4];

    /// <summary>Scales a finished frame into the small view target and hands it to the stage.</summary>
    private void Show(ID3D11Texture2D frame)
    {
        if (FrameSink == null) return;
        using var srv = Device.CreateShaderResourceView(frame);
        Ctx.OMSetRenderTargets(viewRtv);
        Ctx.RSSetViewport(0, 0, ViewW, ViewH);
        Ctx.RSSetState(viewRaster);
        Ctx.OMSetBlendState(present.Blend);
        Ctx.OMSetDepthStencilState(null);
        Ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        Ctx.IASetInputLayout(null);
        Ctx.VSSetShader(present.VS);
        Ctx.PSSetShader(present.PS);
        Ctx.PSSetShaderResource(0, srv);
        Ctx.PSSetSampler(0, linear);
        Ctx.Draw(3, 0);
        Ctx.PSSetShaderResource(0, null);
        Ctx.OMSetRenderTargets((ID3D11RenderTargetView?)null);
        Ctx.CopyResource(staging, viewTex);
        var map = Ctx.Map(staging, 0, MapMode.Read);
        try
        {
            for (int y = 0; y < (int)ViewH; y++)
                Marshal.Copy(map.DataPointer + y * (int)map.RowPitch, viewBytes, y * (int)ViewW * 4, (int)ViewW * 4);
        }
        finally { Ctx.Unmap(staging, 0); }
        FrameSink(viewBytes);
    }

    /// <summary>Reads a full-size RGBA8 texture back and writes it as a PNG (checks only).</summary>
    public void SavePng(ID3D11Texture2D texture, string path)
    {
        var d = texture.Description;
        using var tmp = Device.CreateTexture2D(new Texture2DDescription(d.Format, d.Width, d.Height, 1, 1, BindFlags.None, ResourceUsage.Staging, CpuAccessFlags.Read));
        Ctx.CopyResource(tmp, texture);
        var map = Ctx.Map(tmp, 0, MapMode.Read);
        var pixels = new byte[d.Width * d.Height * 4];
        for (int y = 0; y < (int)d.Height; y++) Marshal.Copy(map.DataPointer + y * (int)map.RowPitch, pixels, y * (int)d.Width * 4, (int)d.Width * 4);
        Ctx.Unmap(tmp, 0);
        for (int i = 3; i < pixels.Length; i += 4) pixels[i] = 255;
        PngWriter.Save(path, (int)d.Width, (int)d.Height, pixels);
    }

    public void Dispose()
    {
        foreach (var o in Enumerable.Reverse(owned)) o.Dispose();
        owned.Clear();
        present.Dispose();
        linear.Dispose(); viewRaster.Dispose(); viewRtv.Dispose(); viewTex.Dispose(); staging.Dispose(); done.Dispose();
        if (ownsDevice) { Ctx.Dispose(); Device.Dispose(); }
    }
}
