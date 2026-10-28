using System.Numerics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;
using Vortice.Mathematics;

namespace ThermoHammer.Engine.Gpu;

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY — GPU Test 2, the Direct3D 11 port of the Android renderer
// (engine/gpu/NeonCityRenderer.kt) and its iPhone port (NeonCityRenderer.swift): the same asset, simulation, light
// grid, passes and knobs.
//
//   Per frame:  light grid (≈870 static + ≈300 moving lights) → searchlight
//               shadow map → mirrored street reflection (½ res, HDR, mips) →
//               4× MSAA HDR main pass (≈2.7M triangles: buildings with
//               ray-traced rooms behind the windows, signs, traffic, people,
//               trains; light halos, steam, rain, splashes) → resolve →
//               volumetric fog march (½ res) → combine → 6-level bloom +
//               anamorphic streaks → motion blur, tonemap, grade.
// ─────────────────────────────────────────────────────────────────────────────

/// <summary>NCFrame in NeonCity.hlsl (512 bytes): the animation groups are eight float4s, as the cbuffer packs 32 floats.</summary>
[StructLayout(LayoutKind.Sequential)]
public struct NcFrame
{
    public Mat4 ViewProj, InvViewProj, PrevViewProj, SearchMat;
    public Vector4 CamPos, CamRight, CamUp, CamFwd, Origin;
    public Vector4 G0, G1, G2, G3, G4, G5, G6, G7;
    public Vector2 InvViewport;
    public float Time, Frame;
    public int MaxMoving, MaxStatic, ReflTaps, FogSteps;
    public int FogLights, BlurSamples;
    public float Exposure, Pad;

    public static NcFrame Create() => new()
    {
        ViewProj = Mat4.Identity, InvViewProj = Mat4.Identity, PrevViewProj = Mat4.Identity, SearchMat = Mat4.Identity,
        MaxMoving = 48, MaxStatic = 48, ReflTaps = 8, FogSteps = 24, FogLights = 6, BlurSamples = 8, Exposure = 1,
    };
}

[StructLayout(LayoutKind.Sequential)]
public struct NcDraw { public int Actor, Instanced; public float Seed, Pad; }

[StructLayout(LayoutKind.Sequential)]
public struct NcPost { public Vector2 Texel, Step; public float Weight, Pad0, Pad1, Pad2; }

public sealed class NeonCityRenderer : IGpuScene
{
    private const int MirrorMoving = 4, MirrorStatic = 8;
    private const int Rain = 36000, Splashes = 3000, SteamPuffs = 24;
    private const int SearchShadow = 1024;
    private const int BloomLevels = 6;
    private const int MaxDynamicLights = 400;
    private const float SearchCos = 0.982f;           // searchlight cone, cos of the half-angle
    private const int InstanceBytes = NeonCitySim.InstanceFloats * 4;

    private sealed record GpuMesh(ID3D11Buffer Vertices, ID3D11Buffer Indices, int IndexCount, Format IndexFormat);

    private sealed class Batch(GpuMesh mesh, ID3D11Buffer instances, int count, int actor, bool instanced, float seed, bool shadow, NeonCitySim.ActorBatch? sim = null)
    {
        public readonly GpuMesh Mesh = mesh;
        public readonly ID3D11Buffer Instances = instances;
        public readonly ID3D11Buffer[] Buffers = [mesh.Vertices, instances];
        public int Count = count;
        public readonly int Actor = actor;
        public readonly bool Instanced = instanced, Shadow = shadow;
        public readonly float Seed = seed;
        public readonly NeonCitySim.ActorBatch? Sim = sim;
    }

    private readonly GpuBench bench;
    private readonly ID3D11Device dev;
    private readonly ID3D11DeviceContext ctx;
    private readonly int width, height;
    private readonly NeonCitySim sim;
    private readonly NeonLightGrid grid;
    private readonly List<IDisposable> owned = [];

    // pipelines
    private Pipe objMain = null!, objMirror = null!, depthOnly = null!, sky = null!, skyMirror = null!;
    private Pipe rain = null!, splash = null!, glow = null!, steam = null!;
    private Pipe fog = null!, combine = null!, downFirst = null!, down = null!, up = null!, streak = null!, final = null!, depthResolve = null!;
    private ID3D11DepthStencilState depthLess = null!, depthLessEqual = null!, depthTestOnly = null!, depthOff = null!, depthAlways = null!;
    private ID3D11RasterizerState rasterMain = null!, rasterShadow = null!;
    private ID3D11SamplerState linear = null!, shadowSampler = null!, point = null!, mipLinear = null!;
    private ID3D11Buffer frameCb = null!, drawCb = null!, postCb = null!;

    // geometry and the light grid
    private readonly List<Batch> batches = [];
    private readonly List<Vector4> steamOrigins = [];
    private const int GridTexWidth = 4096;       // the grid's textures are this wide; element n is texel (n % 4096, n / 4096)
    private ID3D11Texture2D lightsGrid = null!, cellsGrid = null!, indexGrid = null!;
    private ID3D11ShaderResourceView lightsSrv = null!, cellsSrv = null!, indexSrv = null!;
    private int indexGridWords;

    // targets
    private ID3D11Texture2D searchTex = null!, reflTex = null!, reflDepth = null!, msaaColor = null!, msaaDepth = null!, sceneTex = null!, depthTex = null!;
    private ID3D11Texture2D fogTex = null!, hdrTex = null!;
    private ID3D11DepthStencilView searchDsv = null!, reflDsv = null!, msaaDsv = null!, depthDsv = null!;
    private ID3D11RenderTargetView reflRtv = null!, msaaRtv = null!, sceneRtv = null!, fogRtv = null!, hdrRtv = null!, outputRtv = null!;
    private ID3D11ShaderResourceView searchSrv = null!, reflSrv = null!, msaaDepthSrv = null!, sceneSrv = null!, depthSrv = null!, fogSrv = null!, hdrSrv = null!;
    private readonly List<(ID3D11Texture2D tex, ID3D11RenderTargetView rtv, ID3D11ShaderResourceView srv, int w, int h)> bloom = [], streaks = [];
    private bool msaa;
    public ID3D11Texture2D Output { get; private set; } = null!;
    public int TrianglesPerFrame { get; private set; }

    private NcFrame frame = NcFrame.Create(), mirrorFrame = NcFrame.Create(), searchFrame = NcFrame.Create();
    private Mat4? prevViewProj;
    private int frameIndex;
    private static readonly ID3D11ShaderResourceView?[] NoViews = new ID3D11ShaderResourceView?[11];
    private static readonly uint[] Strides = [NeonCityAsset.VertexBytes, InstanceBytes], Offsets = [0, 0];
    private ID3D11SamplerState[] samplers = null!;

    public NeonCityRenderer(GpuBench bench, NeonCityAsset asset, int width, int height)
    {
        this.bench = bench; dev = bench.Device; ctx = bench.Ctx;
        this.width = width; this.height = height;
        sim = new NeonCitySim(asset);
        grid = new NeonLightGrid(asset.GetTable("lights"), MaxDynamicLights);
        BuildTargets();
        BuildPipelines();
        BuildGeometry(asset);
        BuildGridBuffers();
    }

    private T Own<T>(T d) where T : IDisposable { owned.Add(d); return d; }

    // ── setup ──

    private static InputElementDescription[] ObjectLayout() =>
    [
        new("POSITION", 0, Format.R32G32B32_Float, 0, 0, InputClassification.PerVertexData, 0),
        new("NORMAL", 0, Format.R8G8B8A8_SNorm, 12, 0, InputClassification.PerVertexData, 0),
        new("COLOR", 0, Format.R8G8B8A8_UNorm, 16, 0, InputClassification.PerVertexData, 0),
        new("INSTROW", 0, Format.R32G32B32A32_Float, 0, 1, InputClassification.PerInstanceData, 1),
        new("INSTROW", 1, Format.R32G32B32A32_Float, 16, 1, InputClassification.PerInstanceData, 1),
        new("INSTROW", 2, Format.R32G32B32A32_Float, 32, 1, InputClassification.PerInstanceData, 1),
        new("EXTRA", 0, Format.R32G32B32A32_Float, 48, 1, InputClassification.PerInstanceData, 1),
        new("EXTRA", 1, Format.R32G32B32A32_Float, 64, 1, InputClassification.PerInstanceData, 1),
    ];

    private ID3D11BlendState AdditiveBlend()
    {
        var d = new BlendDescription();
        d.RenderTarget[0] = new RenderTargetBlendDescription
        {
            BlendEnable = true, SourceBlend = Blend.One, DestinationBlend = Blend.One, BlendOperation = BlendOperation.Add,
            SourceBlendAlpha = Blend.One, DestinationBlendAlpha = Blend.One, BlendOperationAlpha = BlendOperation.Add,
            RenderTargetWriteMask = ColorWriteEnable.All,
        };
        return Own(dev.CreateBlendState(d));
    }

    private void BuildPipelines()
    {
        var opaque = bench.OpaqueBlend();
        var premul = bench.PremultipliedBlend();
        var additive = AdditiveBlend();
        var obj = ObjectLayout();
        const string F = "NeonCity";
        objMain = bench.Pipeline(F, "ncObjectVS", "ncObjectFS", obj, opaque);
        objMirror = bench.Pipeline(F, "ncObjectVS", "ncObjectFS", obj, opaque, "MIRROR");
        depthOnly = bench.Pipeline(F, "ncDepthVS", null, obj, opaque, "DEPTH_ONLY");
        sky = bench.Pipeline(F, "ncSkyVS", "ncSkyFS", null, opaque);
        skyMirror = bench.Pipeline(F, "ncSkyVS", "ncSkyFS", null, opaque, "MIRROR");
        rain = bench.Pipeline(F, "ncRainVS", "ncRainFS", null, premul);
        splash = bench.Pipeline(F, "ncSplashVS", "ncSplashFS", null, premul);
        glow = bench.Pipeline(F, "ncGlowVS", "ncGlowFS", null, premul);
        steam = bench.Pipeline(F, "ncSteamVS", "ncSteamFS", null, premul);
        fog = bench.Pipeline(F, "ncPostVS", "ncFogFS", null, opaque);
        combine = bench.Pipeline(F, "ncPostVS", "ncCombineFS", null, opaque);
        downFirst = bench.Pipeline(F, "ncPostVS", "ncDownFS", null, opaque, "THRESHOLD");
        down = bench.Pipeline(F, "ncPostVS", "ncDownFS", null, opaque);
        up = bench.Pipeline(F, "ncPostVS", "ncUpFS", null, additive);
        streak = bench.Pipeline(F, "ncPostVS", "ncStreakFS", null, opaque);
        final = bench.Pipeline(F, "ncPostVS", "ncFinalFS", null, opaque);
        depthResolve = bench.Pipeline(F, "ncPostVS", "ncDepthResolveFS", null, opaque);

        ID3D11DepthStencilState Depth(bool enable, ComparisonFunction f, bool write) =>
            Own(dev.CreateDepthStencilState(new DepthStencilDescription(enable, write ? DepthWriteMask.All : DepthWriteMask.Zero, f)));
        depthLess = Depth(true, ComparisonFunction.Less, true);
        depthLessEqual = Depth(true, ComparisonFunction.LessEqual, true);
        depthTestOnly = Depth(true, ComparisonFunction.Less, false);
        depthOff = Depth(false, ComparisonFunction.Always, false);
        depthAlways = Depth(true, ComparisonFunction.Always, true);

        rasterMain = Own(dev.CreateRasterizerState(new RasterizerDescription(CullMode.None, FillMode.Solid) { DepthClipEnable = true, MultisampleEnable = true }));
        rasterShadow = Own(dev.CreateRasterizerState(new RasterizerDescription(CullMode.None, FillMode.Solid)
            { DepthBias = 4, SlopeScaledDepthBias = 2, DepthClipEnable = true }));
        ID3D11SamplerState Sampler(Filter f, ComparisonFunction c = ComparisonFunction.Never) => Own(dev.CreateSamplerState(new SamplerDescription(f, TextureAddressMode.Clamp,
            TextureAddressMode.Clamp, TextureAddressMode.Clamp, 0, 1, c, new Color4(1, 1, 1, 1), 0, float.MaxValue)));
        linear = Sampler(Filter.MinMagMipLinear);
        shadowSampler = Sampler(Filter.ComparisonMinMagLinearMipPoint, ComparisonFunction.LessEqual);
        point = Sampler(Filter.MinMagMipPoint);
        mipLinear = Sampler(Filter.MinMagMipLinear);
        samplers = [linear, shadowSampler, point, mipLinear];
        frameCb = Own(bench.Device.CreateBuffer(new BufferDescription((uint)Unsafe.SizeOf<NcFrame>(), BindFlags.ConstantBuffer, ResourceUsage.Default)));
        drawCb = Own(bench.Device.CreateBuffer(new BufferDescription((uint)Unsafe.SizeOf<NcDraw>(), BindFlags.ConstantBuffer, ResourceUsage.Default)));
        postCb = Own(bench.Device.CreateBuffer(new BufferDescription((uint)Unsafe.SizeOf<NcPost>(), BindFlags.ConstantBuffer, ResourceUsage.Default)));
    }

    private unsafe ID3D11Buffer StaticBuffer(ReadOnlySpan<byte> data, BindFlags bind)
    {
        int size = Math.Max((data.Length + 15) & ~15, 16);
        byte[] tmp = new byte[size];
        data.CopyTo(tmp);
        fixed (byte* p = tmp)
            return Own(dev.CreateBuffer(new BufferDescription((uint)size, bind, ResourceUsage.Immutable), new SubresourceData((IntPtr)p)));
    }

    private ID3D11Buffer DynamicBuffer(int bytes, BindFlags bind) =>
        Own(dev.CreateBuffer(new BufferDescription((uint)bytes, bind, ResourceUsage.Dynamic, CpuAccessFlags.Write)));

    /// <summary>{pos, euler XYZ, scale} → instance records (Blender XYZ euler: R = Rz·Ry·Rx), extras zero.</summary>
    private static float[] InstanceRows(float[] t, int count)
    {
        const int stride = NeonCitySim.InstanceFloats;
        var o = new float[count * stride];
        for (int i = 0; i < count; i++)
        {
            int k = i * 9;
            float cx = MathF.Cos(t[k + 3]), sx = MathF.Sin(t[k + 3]), cy = MathF.Cos(t[k + 4]), sy = MathF.Sin(t[k + 4]), cz = MathF.Cos(t[k + 5]), sz = MathF.Sin(t[k + 5]);
            float kx = t[k + 6], ky = t[k + 7], kz = t[k + 8];
            int b = i * stride;
            o[b] = cz * cy * kx; o[b + 1] = (cz * sy * sx - sz * cx) * ky; o[b + 2] = (cz * sy * cx + sz * sx) * kz; o[b + 3] = t[k];
            o[b + 4] = sz * cy * kx; o[b + 5] = (sz * sy * sx + cz * cx) * ky; o[b + 6] = (sz * sy * cx - cz * sx) * kz; o[b + 7] = t[k + 1];
            o[b + 8] = -sy * kx; o[b + 9] = cy * sx * ky; o[b + 10] = cy * cx * kz; o[b + 11] = t[k + 2];
        }
        return o;
    }

    private void BuildGeometry(NeonCityAsset asset)
    {
        var meshes = asset.Meshes.Select(m => new GpuMesh(StaticBuffer(m.Vertices, BindFlags.VertexBuffer), StaticBuffer(m.Indices, BindFlags.IndexBuffer),
            m.IndexCount, m.WideIndices ? Format.R32_UInt : Format.R16_UInt)).ToList();
        var identity = new float[NeonCitySim.InstanceFloats];
        identity[0] = 1; identity[5] = 1; identity[10] = 1;
        var identityBuf = StaticBuffer(MemoryMarshal.AsBytes(identity.AsSpan()), BindFlags.VertexBuffer);
        foreach (int i in asset.ChunkMeshes)
            batches.Add(new Batch(meshes[i], identityBuf, 1, 0, false, 0, true));
        // small street props would only speckle the searchlight's shadow map
        var noShadow = new HashSet<string> { "bollard", "bin", "hydrant", "bench", "lantern", "ac_unit" };
        for (int gi = 0; gi < asset.Groups.Count; gi++)
        {
            var g = asset.Groups[gi];
            bool far = g.Name.StartsWith("tower_");
            var rows = InstanceRows(g.Transforms, g.Count);
            batches.Add(new Batch(meshes[g.Mesh], StaticBuffer(MemoryMarshal.AsBytes(rows.AsSpan()), BindFlags.VertexBuffer), g.Count, 0, true, gi * 7.31f,
                !far && !noShadow.Contains(g.Name)));
        }
        var actors = sim.CarBatches.Select(b => (b, 1)).Concat(sim.PedBatches.Select(b => (b, 2)))
            .Concat([(sim.TrainHeads, 3), (sim.TrainCarsBatch, 3), (sim.DroneBatch, 3), (sim.PoliceBatch, 3)]);
        foreach (var (ab, kind) in actors)
        {
            int capacity = ab.Data.Length / NeonCitySim.InstanceFloats;
            int mi = asset.MeshIndex(ab.Mesh);
            if (capacity == 0 || mi < 0) continue;
            batches.Add(new Batch(meshes[mi], DynamicBuffer(ab.Data.Length * 4, BindFlags.VertexBuffer), 0, kind, true, 0, kind != 2, ab));
        }
        var st = asset.GetTable("steam");
        for (int r = 0; r < st.Rows; r++) steamOrigins.Add(new Vector4(st[r, 0], st[r, 1], st[r, 2], 1));
        long main = batches.Sum(b => (long)(b.Mesh.IndexCount / 3) * Math.Max(b.Count, (b.Sim?.Data.Length ?? 0) / NeonCitySim.InstanceFloats));
        long shadow = batches.Where(b => b.Shadow).Sum(b => (long)(b.Mesh.IndexCount / 3) * Math.Max(b.Count, 1));
        TrianglesPerFrame = (int)(main * 2 + shadow) + 2 * (Rain + Splashes);
    }

    /// <summary>A dynamic texture <see cref="GridTexWidth"/> wide holding at least `elements` texels of `format`.</summary>
    private (ID3D11Texture2D, ID3D11ShaderResourceView) GridTexture(Format format, int elements)
    {
        int rows = (elements + GridTexWidth - 1) / GridTexWidth;
        var t = Own(dev.CreateTexture2D(new Texture2DDescription(format, GridTexWidth, (uint)rows, 1, 1, BindFlags.ShaderResource, ResourceUsage.Dynamic, CpuAccessFlags.Write)));
        return (t, Own(dev.CreateShaderResourceView(t)));
    }

    /// <summary>The light list (4 float4 per light), the 4 m cells and the u16 indices the shaders read — all textures, like the Android renderer's.</summary>
    private void BuildGridBuffers()
    {
        (lightsGrid, lightsSrv) = GridTexture(Format.R32G32B32A32_Float, grid.Lights.Length / 4);
        (cellsGrid, cellsSrv) = GridTexture(Format.R32_UInt, grid.Cells.Length);
        EnsureIndexGrid();
    }

    private void EnsureIndexGrid()
    {
        int words = grid.Indices.Length / 2;
        if (words <= indexGridWords) return;
        indexGridWords = words;
        (indexGrid, indexSrv) = GridTexture(Format.R32_UInt, words);
    }

    /// <summary>Writes `data` into the texture row by row (the driver's row pitch), `texelBytes` per texel.</summary>
    private unsafe void UploadGrid(ID3D11Texture2D tex, ReadOnlySpan<byte> data, int texelBytes)
    {
        var map = ctx.Map(tex, 0, MapMode.WriteDiscard);
        int rowBytes = GridTexWidth * texelBytes;
        for (int row = 0, at = 0; at < data.Length; row++, at += rowBytes)
            data.Slice(at, Math.Min(rowBytes, data.Length - at)).CopyTo(new Span<byte>((byte*)map.DataPointer + (long)row * map.RowPitch, rowBytes));
        ctx.Unmap(tex, 0);
    }

    private ID3D11Texture2D MakeTexture(Format format, int w, int h, BindFlags bind, int samples = 1, int mips = 1, ResourceOptionFlags misc = ResourceOptionFlags.None) =>
        Own(dev.CreateTexture2D(new Texture2DDescription(format, (uint)w, (uint)h, 1, (uint)mips, bind, ResourceUsage.Default, CpuAccessFlags.None, (uint)samples, 0, misc)));

    private void BuildTargets()
    {
        const Format Hdr = Format.R16G16B16A16_Float;
        const BindFlags RtSrv = BindFlags.RenderTarget | BindFlags.ShaderResource;
        msaa = bench.SupportsMultisample4;
        int w = width, h = height;

        searchTex = MakeTexture(Format.R32_Typeless, SearchShadow, SearchShadow, BindFlags.DepthStencil | BindFlags.ShaderResource);
        searchDsv = Own(dev.CreateDepthStencilView(searchTex, new DepthStencilViewDescription(searchTex, DepthStencilViewDimension.Texture2D, Format.D32_Float)));
        searchSrv = Own(dev.CreateShaderResourceView(searchTex, new ShaderResourceViewDescription(searchTex, ShaderResourceViewDimension.Texture2D, Format.R32_Float)));

        reflTex = MakeTexture(Hdr, w / 2, h / 2, RtSrv, 1, 0, ResourceOptionFlags.GenerateMips);
        reflRtv = Own(dev.CreateRenderTargetView(reflTex));
        reflSrv = Own(dev.CreateShaderResourceView(reflTex));
        reflDepth = MakeTexture(Format.D32_Float, w / 2, h / 2, BindFlags.DepthStencil);
        reflDsv = Own(dev.CreateDepthStencilView(reflDepth));

        sceneTex = MakeTexture(Hdr, w, h, RtSrv);
        sceneRtv = Own(dev.CreateRenderTargetView(sceneTex));
        sceneSrv = Own(dev.CreateShaderResourceView(sceneTex));
        depthTex = MakeTexture(Format.R32_Typeless, w, h, BindFlags.DepthStencil | BindFlags.ShaderResource);
        depthDsv = Own(dev.CreateDepthStencilView(depthTex, new DepthStencilViewDescription(depthTex, DepthStencilViewDimension.Texture2D, Format.D32_Float)));
        depthSrv = Own(dev.CreateShaderResourceView(depthTex, new ShaderResourceViewDescription(depthTex, ShaderResourceViewDimension.Texture2D, Format.R32_Float)));
        if (msaa)
        {
            msaaColor = MakeTexture(Hdr, w, h, BindFlags.RenderTarget, 4);
            msaaRtv = Own(dev.CreateRenderTargetView(msaaColor));
            msaaDepth = MakeTexture(Format.R32_Typeless, w, h, BindFlags.DepthStencil | BindFlags.ShaderResource, 4);
            msaaDsv = Own(dev.CreateDepthStencilView(msaaDepth, new DepthStencilViewDescription { Format = Format.D32_Float, ViewDimension = DepthStencilViewDimension.Texture2DMultisampled }));
            msaaDepthSrv = Own(dev.CreateShaderResourceView(msaaDepth, new ShaderResourceViewDescription { Format = Format.R32_Float, ViewDimension = ShaderResourceViewDimension.Texture2DMultisampled }));
        }
        else
        {
            msaaColor = sceneTex; msaaRtv = sceneRtv; msaaDepth = depthTex; msaaDsv = depthDsv;
        }
        fogTex = MakeTexture(Hdr, w / 2, h / 2, RtSrv);
        fogRtv = Own(dev.CreateRenderTargetView(fogTex));
        fogSrv = Own(dev.CreateShaderResourceView(fogTex));
        hdrTex = MakeTexture(Hdr, w, h, RtSrv);
        hdrRtv = Own(dev.CreateRenderTargetView(hdrTex));
        hdrSrv = Own(dev.CreateShaderResourceView(hdrTex));
        for (int l = 0; l < BloomLevels; l++) bloom.Add(HdrTarget(Math.Max(1, w >> (l + 1)), Math.Max(1, h >> (l + 1))));
        for (int i = 0; i < 2; i++) streaks.Add(HdrTarget(w / 4, h / 4));
        Output = MakeTexture(Format.R8G8B8A8_UNorm, w, h, RtSrv);
        outputRtv = Own(dev.CreateRenderTargetView(Output));
    }

    private (ID3D11Texture2D, ID3D11RenderTargetView, ID3D11ShaderResourceView, int, int) HdrTarget(int w, int h)
    {
        var t = MakeTexture(Format.R16G16B16A16_Float, w, h, BindFlags.RenderTarget | BindFlags.ShaderResource);
        return (t, Own(dev.CreateRenderTargetView(t)), Own(dev.CreateShaderResourceView(t)), w, h);
    }

    // ── per frame ──

    public void Encode(float t)
    {
        sim.Update(t);
        Camera(t);
        Lights(t);
        UploadActors();
        SearchShadowPass();
        ReflectionPass();
        MainPass();
        FogPass();
        PostPass();
        ctx.OMSetRenderTargets((ID3D11RenderTargetView?)null);
        UnbindViews();
        prevViewProj = frame.ViewProj;
        frameIndex++;
    }

    private void SetGroups(ref NcFrame f) => sim.Groups.CopyTo(MemoryMarshal.CreateSpan(ref Unsafe.As<Vector4, float>(ref f.G0), 32));

    private void Camera(float t)
    {
        var e = new Vector3(sim.Eye[0], sim.Eye[1], sim.Eye[2]);
        var g = new Vector3(sim.Target[0], sim.Target[1], sim.Target[2]);
        var view = Mat4.LookAt(e, g, new Vector3(0, 0, 1));
        var proj = Mat4.Perspective(sim.FovDeg, (float)width / height, 0.25f, 4000);
        var viewProj = proj * view;
        frame.ViewProj = viewProj;
        frame.InvViewProj = viewProj.Inverse();
        frame.PrevViewProj = prevViewProj ?? viewProj;
        frame.CamPos = new Vector4(e, 1);
        frame.CamRight = new Vector4(view.Row0, 0);
        frame.CamUp = new Vector4(view.Row1, 0);
        frame.CamFwd = new Vector4(-view.C0.Z, -view.C1.Z, -view.C2.Z, 0);
        frame.Time = t;
        frame.Frame = frameIndex % 64;
        frame.InvViewport = new Vector2(1f / width, 1f / height);
        SetGroups(ref frame);

        // the street's mirror image: z' = −z
        mirrorFrame = frame;
        mirrorFrame.ViewProj = proj * view * Mat4.MirrorZ(0);
        mirrorFrame.CamPos = new Vector4(e.X, e.Y, -e.Z, 1);
        mirrorFrame.MaxMoving = MirrorMoving;
        mirrorFrame.MaxStatic = MirrorStatic;

        // searchlight frustum (the cone plus a margin), biased into texture space for shadow lookups
        var p = new Vector3(sim.SearchPos[0], sim.SearchPos[1], sim.SearchPos[2]);
        var a = new Vector3(sim.SearchAim[0], sim.SearchAim[1], sim.SearchAim[2]);
        var searchView = Mat4.LookAt(p, a, new Vector3(0, 1, 0));
        float fov = (float)(2 * Math.Acos(SearchCos) * 180 / Math.PI) + 6;
        var searchViewProj = Mat4.Perspective(fov, 1, 2, 140) * searchView;
        frame.SearchMat = Mat4.ShadowBias * searchViewProj;
        mirrorFrame.SearchMat = frame.SearchMat;
        searchFrame = frame;
        searchFrame.ViewProj = searchViewProj;
    }

    private unsafe void Upload<T>(ID3D11Buffer buffer, ReadOnlySpan<T> data) where T : unmanaged
    {
        var bytes = MemoryMarshal.AsBytes(data);
        var map = ctx.Map(buffer, 0, MapMode.WriteDiscard);
        bytes.CopyTo(new Span<byte>((void*)map.DataPointer, bytes.Length));
        ctx.Unmap(buffer, 0);
    }

    /// <summary>Moving lights for this frame, then the merged grid copied to its buffers.</summary>
    private void Lights(float t)
    {
        grid.SetMovingLights(sim, t, SearchCos);
        UploadGrid(lightsGrid, MemoryMarshal.AsBytes(grid.Lights.AsSpan()), 16);
        UploadGrid(cellsGrid, MemoryMarshal.AsBytes(grid.Cells.AsSpan()), 4);
        EnsureIndexGrid();
        UploadGrid(indexGrid, MemoryMarshal.AsBytes(grid.Indices.AsSpan(0, (grid.IndexCount + 1) & ~1)), 4);
    }

    private void UploadActors()
    {
        foreach (var b in batches)
        {
            if (b.Sim is not { } ab) continue;
            b.Count = ab.Count;
            if (ab.Count > 0) Upload<float>(b.Instances, ab.Data.AsSpan(0, ab.Count * NeonCitySim.InstanceFloats));
        }
    }

    // ── binding helpers ──

    private void UnbindViews()
    {
        ctx.PSSetShaderResources(0, NoViews);
        ctx.VSSetShaderResources(0, NoViews);
    }

    private void BindFrame(in NcFrame f)
    {
        ctx.UpdateSubresource(in f, frameCb);
        ctx.VSSetConstantBuffer(0, frameCb);
        ctx.PSSetConstantBuffer(0, frameCb);
        ctx.VSSetConstantBuffer(1, drawCb);
        ctx.PSSetConstantBuffer(1, drawCb);
        ctx.PSSetConstantBuffer(2, postCb);
        ctx.PSSetSamplers(0, samplers);
        ctx.VSSetSamplers(0, samplers);
    }

    private void BindGrid(bool vertexStage)
    {
        ctx.PSSetShaderResource(0, searchSrv);
        ctx.PSSetShaderResource(8, lightsSrv);
        ctx.PSSetShaderResource(9, cellsSrv);
        ctx.PSSetShaderResource(10, indexSrv);
        if (!vertexStage) return;
        ctx.VSSetShaderResource(0, searchSrv);
        ctx.VSSetShaderResource(8, lightsSrv);
        ctx.VSSetShaderResource(9, cellsSrv);
        ctx.VSSetShaderResource(10, indexSrv);
    }

    private void SetPipe(Pipe p)
    {
        ctx.IASetInputLayout(p.Layout);
        ctx.VSSetShader(p.VS);
        ctx.PSSetShader(p.PS);
        ctx.OMSetBlendState(p.Blend);
    }

    private void DrawBatches(bool shadowOnly)
    {
        ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        foreach (var b in batches)
        {
            if (b.Count == 0 || (shadowOnly && !b.Shadow)) continue;
            var d = new NcDraw { Actor = b.Actor, Instanced = b.Instanced ? 1 : 0, Seed = b.Seed };
            ctx.UpdateSubresource(in d, drawCb);
            ctx.IASetVertexBuffers(0, b.Buffers, Strides, Offsets);
            ctx.IASetIndexBuffer(b.Mesh.Indices, b.Mesh.IndexFormat, 0);
            ctx.DrawIndexedInstanced((uint)b.Mesh.IndexCount, (uint)b.Count, 0, 0, 0);
        }
    }

    private void DrawSky(Pipe p)
    {
        SetPipe(p);
        ctx.OMSetDepthStencilState(depthLessEqual);
        ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        ctx.Draw(3, 0);
    }

    // ── passes ──

    private void SearchShadowPass()
    {
        UnbindViews();
        ctx.OMSetRenderTargets((ID3D11RenderTargetView?)null, searchDsv);
        ctx.RSSetViewport(0, 0, SearchShadow, SearchShadow);
        ctx.RSSetState(rasterShadow);
        ctx.ClearDepthStencilView(searchDsv, DepthStencilClearFlags.Depth, 1, 0);
        SetPipe(depthOnly);
        ctx.OMSetDepthStencilState(depthLess);
        BindFrame(searchFrame);
        DrawBatches(true);
    }

    private void ReflectionPass()
    {
        UnbindViews();
        ctx.OMSetRenderTargets(reflRtv, reflDsv);
        ctx.RSSetViewport(0, 0, width / 2, height / 2);
        ctx.RSSetState(rasterMain);
        ctx.ClearRenderTargetView(reflRtv, new Color4(0, 0, 0, 0));
        ctx.ClearDepthStencilView(reflDsv, DepthStencilClearFlags.Depth, 1, 0);
        SetPipe(objMirror);
        ctx.OMSetDepthStencilState(depthLess);
        BindFrame(mirrorFrame);
        BindGrid(false);
        DrawBatches(false);
        DrawSky(skyMirror);
        ctx.OMSetRenderTargets((ID3D11RenderTargetView?)null);
        UnbindViews();
        ctx.GenerateMips(reflSrv);
    }

    private void MainPass()
    {
        ctx.OMSetRenderTargets(msaaRtv, msaaDsv);
        ctx.RSSetViewport(0, 0, width, height);
        ctx.RSSetState(rasterMain);
        ctx.ClearRenderTargetView(msaaRtv, new Color4(0, 0, 0, 0));
        ctx.ClearDepthStencilView(msaaDsv, DepthStencilClearFlags.Depth, 1, 0);
        SetPipe(objMain);
        ctx.OMSetDepthStencilState(depthLess);
        BindFrame(frame);
        BindGrid(true);
        ctx.PSSetShaderResource(1, reflSrv);
        DrawBatches(false);
        DrawSky(sky);

        // translucent: premultiplied alpha (alpha 0 = additive), depth-tested, not written
        ctx.OMSetDepthStencilState(depthTestOnly);
        SetPipe(glow);
        ctx.DrawInstanced(6, (uint)grid.LightCount, 0, 0);
        SetPipe(steam);
        foreach (var o in steamOrigins)
        {
            var f = frame;
            f.Origin = o;
            BindFrame(f);
            ctx.DrawInstanced(6, SteamPuffs, 0, 0);
        }
        BindFrame(frame);
        SetPipe(rain);
        ctx.DrawInstanced(6, Rain, 0, 0);
        SetPipe(splash);
        ctx.DrawInstanced(6, Splashes, 0, 0);

        if (!msaa) return;
        ctx.OMSetRenderTargets((ID3D11RenderTargetView?)null);
        UnbindViews();
        ctx.ResolveSubresource(sceneTex, 0, msaaColor, 0, Format.R16G16B16A16_Float);
        // Direct3D can't resolve depth: write sample 0 of the multisampled depth into the plain one
        ctx.OMSetRenderTargets((ID3D11RenderTargetView?)null, depthDsv);
        SetPipe(depthResolve);
        ctx.OMSetDepthStencilState(depthAlways);
        ctx.PSSetShaderResource(3, msaaDepthSrv);
        ctx.Draw(3, 0);
        ctx.PSSetShaderResource(3, null);
    }

    private void Fullscreen(Pipe p, ID3D11RenderTargetView target, int w, int h, NcPost post, params (int slot, ID3D11ShaderResourceView view)[] sources)
    {
        UnbindViews();
        ctx.OMSetRenderTargets(target);
        ctx.RSSetViewport(0, 0, w, h);
        SetPipe(p);
        ctx.RSSetState(rasterMain);
        ctx.OMSetDepthStencilState(depthOff);
        ctx.UpdateSubresource(in post, postCb);
        foreach (var (slot, view) in sources) ctx.PSSetShaderResource((uint)slot, view);
        ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        ctx.Draw(3, 0);
    }

    private void FogPass()
    {
        UnbindViews();
        ctx.OMSetRenderTargets((ID3D11RenderTargetView?)null);
        BindFrame(frame);
        BindGrid(false);
        ctx.OMSetRenderTargets(fogRtv);
        ctx.RSSetViewport(0, 0, width / 2, height / 2);
        SetPipe(fog);
        ctx.RSSetState(rasterMain);
        ctx.OMSetDepthStencilState(depthOff);
        ctx.PSSetShaderResource(2, depthSrv);
        ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        ctx.Draw(3, 0);
    }

    private void PostPass()
    {
        BindFrame(frame);
        Fullscreen(combine, hdrRtv, width, height, default, (4, sceneSrv), (5, fogSrv));
        // bloom: thresholded 13-tap chain down, streaks off the ¼-res level, tent chain back up
        var src = (view: hdrSrv, w: width, h: height);
        for (int l = 0; l < BloomLevels; l++)
        {
            Fullscreen(l == 0 ? downFirst : down, bloom[l].rtv, bloom[l].w, bloom[l].h, new NcPost { Texel = new(1f / src.w, 1f / src.h) }, (4, src.view));
            src = (bloom[l].srv, bloom[l].w, bloom[l].h);
        }
        var sSrc = bloom[1].srv;
        float[] strides = [1, 3, 8];
        for (int i = 0; i < strides.Length; i++)
        {
            var s = streaks[i % 2];
            Fullscreen(streak, s.rtv, s.w, s.h, new NcPost { Step = new(strides[i] * 4 / width, 0) }, (4, sSrc));
            sSrc = s.srv;
        }
        for (int l = BloomLevels - 1; l >= 1; l--)
            Fullscreen(up, bloom[l - 1].rtv, bloom[l - 1].w, bloom[l - 1].h,
                new NcPost { Texel = new(1f / bloom[l].w, 1f / bloom[l].h), Weight = 1 }, (4, bloom[l].srv));
        Fullscreen(final, outputRtv, width, height, default, (2, depthSrv), (4, hdrSrv), (5, bloom[0].srv), (6, sSrc));
    }

    public void Dispose()
    {
        ctx.ClearState();
        ctx.Flush();
        foreach (var o in Enumerable.Reverse(owned)) o.Dispose();
        owned.Clear();
    }
}
