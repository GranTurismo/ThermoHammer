using System.Numerics;
using System.Runtime.InteropServices;
using Vortice;
using Vortice.Direct3D;
using Vortice.Direct3D12;
using Vortice.Dxc;
using Vortice.DXGI;
using Vortice.Mathematics;

namespace ThermoHammer.Engine.Gpu.Rt;

// ─────────────────────────────────────────────────────────────────────────────
// NEON CITY RT — GPU Test 3, the Direct3D 12 port of the phones' ray-tracing renderer (NeonCityRenderer.swift's `rayTracing`
// path, the Android app's Vulkan renderer): the same scene as Neon City with rays instead of the mirror render and the
// searchlight's shadow map — reflections on the wet street, glass, paint and chrome, shadows of the searchlight and of
// each point's two strongest lights, and searchlight shafts in the fog. The rays are DXR 1.1 ray queries in the pixel
// shaders (NeonCity.hlsl, NC_RT=1): one bottom-level structure per mesh (built once), the city-wide top-level one (static
// instances + the moving cars, people and trains) rebuilt every frame.
//
//   Per frame:  light grid → top-level structure → 4× MSAA HDR main pass (every object with its rays, sky, light halos,
//               steam, rain, splashes) → resolve → fog march with shadow rays (½ res) → combine → bloom + streaks → final.
//
// NC_RT=2 (THERMOHAMMER_RT_PLUMBING=1) runs everything but the rays: for checking the renderer on a GPU without ray tracing.
// ─────────────────────────────────────────────────────────────────────────────

public sealed unsafe class NeonCityRt : IDisposable
{
    public const int Width = 1920, Height = 1080;
    private const int Rain = 36000, Splashes = 3000, SteamPuffs = 24, BloomLevels = 6, MaxDynamicLights = 400;
    private const float SearchCos = 0.982f;
    private const Format Hdr = Format.R16G16B16A16_Float;
    private const int Slots = 13;                   // t0 … t12: what a pass can read
    private const int InstanceBytes = NeonCitySim.InstanceFloats * 4;

    [StructLayout(LayoutKind.Sequential)]
    private struct RtInstance { public Vector4 Row0, Row1, Row2, Extra; public int Mesh, Actor, Instanced; public float Seed; }

    private sealed class GpuMesh(ID3D12Resource vb, ID3D12Resource ib, int vertices, int indices, bool wide, int index)
    {
        public readonly ID3D12Resource Vb = vb, Ib = ib;
        public readonly int Vertices = vertices, Indices = indices, Index = index;
        public readonly bool Wide = wide;
        public ulong BlasVa;
    }

    private sealed class Batch(GpuMesh mesh, ID3D12Resource instances, int count, int actor, bool instanced, float seed, NeonCitySim.ActorBatch? sim = null)
    {
        public readonly GpuMesh Mesh = mesh;
        public readonly ID3D12Resource Instances = instances;
        public int Count = count;
        public readonly int Actor = actor;
        public readonly bool Instanced = instanced;
        public readonly float Seed = seed;
        public readonly NeonCitySim.ActorBatch? Sim = sim;
        public byte* Upload;                        // actors: the persistently mapped instance buffer
        public float[]? Rows;                       // static batches: their records, for the ray-tracing instances
    }

    private readonly Dx12 dx;
    private readonly bool rays;
    private readonly string[] defines;
    private readonly NeonCitySim sim;
    private readonly NeonLightGrid grid;
    private readonly List<GpuMesh> meshes = [];
    private readonly List<Batch> batches = [];
    private readonly List<Vector4> steamOrigins = [];
    private readonly List<IDisposable> staging = [];
    private readonly List<IDisposable> psos = [];

    private ID3D12RootSignature root = null!;
    private ID3D12PipelineState psoObject = null!, psoSky = null!, psoGlow = null!, psoSteam = null!, psoRain = null!, psoSplash = null!;
    private ID3D12PipelineState psoFog = null!, psoCombine = null!, psoDownFirst = null!, psoDown = null!, psoUp = null!, psoStreak = null!, psoFinal = null!, psoDepth = null!;

    private Tex msaaColor = null!, msaaDepth = null!, scene = null!, depth = null!, fog = null!, hdr = null!, output = null!;
    private readonly List<Tex> bloom = [], streaks = [];
    private ID3D12Resource lightsBuf = null!, cellsBuf = null!, indexBuf = null!;
    private int indexCapacity;
    private ID3D12Resource readback = null!;
    private readonly uint readPitch = (uint)((Width * 4 + 255) & ~255);

    // acceleration structures
    private ID3D12Resource tlas = null!, tlasScratch = null!, instanceDescs = null!, rtInstances = null!, rtMeshWide = null!;
    private byte* instanceDescPtr, rtInstancePtr;
    private int staticInstances, instanceCapacity;
    private ulong tlasVa;

    // descriptor blocks (13 SRVs each)
    private int blockMain, blockFog, blockCombine, blockFinal, blockDepth, blockVertices, blockIndices;
    private readonly int[] blockDown = new int[BloomLevels], blockUp = new int[BloomLevels], blockStreak = new int[3];

    private NcFrame frame = NcFrame.Create();
    private Mat4? prevViewProj;
    private int frameIndex;
    private bool msaaOn = true;
    private int samples = 4;

    public string Name => dx.AdapterName;
    public int TrianglesPerFrame { get; private set; }
    /// <summary>Receives a small BGRA copy of a finished frame (960×540) on the benchmark thread; never part of a measurement.</summary>
    public Action<byte[]>? FrameSink;

    /// <summary>Why this PC can't run the test (no DirectX Raytracing 1.1), or null.</summary>
    public static string? Problem()
    {
        if (Environment.GetEnvironmentVariable("THERMOHAMMER_RT_PLUMBING") == "1") return null;
        using var d = Dx12.TryCreate(true, out var why);
        return d == null ? why ?? "no DirectX 12 adapter" : null;
    }

    public NeonCityRt(NeonCityAsset asset)
    {
        rays = Environment.GetEnvironmentVariable("THERMOHAMMER_RT_PLUMBING") != "1";
        dx = Dx12.TryCreate(rays, out var why) ?? throw new GpuError(why ?? "no DirectX 12 adapter");
        defines = [rays ? "NC_RT=1" : "NC_RT=2", "NC_BUFFERS=1"];
        sim = new NeonCitySim(asset);
        grid = new NeonLightGrid(asset.GetTable("lights"), MaxDynamicLights);
        if (sizeof(RaytracingInstanceDescription) != 64 || sizeof(RtInstance) != 80) throw new GpuError("unexpected structure sizes");
        dx.Begin();
        BuildTargets();
        BuildRootAndPipelines();
        BuildGeometry(asset);
        BuildGridBuffers();
        if (rays) BuildAccelerationStructures();
        BuildBlocks();
        dx.Submit();
        dx.Wait();
        foreach (var s in staging) s.Dispose();
        staging.Clear();
    }

    // ── setup ──

    private void BuildTargets()
    {
        const ResourceFlags Rt = ResourceFlags.AllowRenderTarget;
        samples = 4;
        var color = new ClearValue(Hdr, new Color4(0, 0, 0, 0));
        msaaColor = dx.Texture(Hdr, Width, Height, Rt, ResourceStates.RenderTarget, samples, color);
        msaaDepth = dx.Texture(Format.R32_Typeless, Width, Height, ResourceFlags.AllowDepthStencil, ResourceStates.DepthWrite, samples, new ClearValue(Format.D32_Float, new DepthStencilValue(1, 0)));
        scene = dx.Texture(Hdr, Width, Height, Rt, ResourceStates.ResolveDest);
        depth = dx.Texture(Format.R32_Typeless, Width, Height, ResourceFlags.AllowDepthStencil, ResourceStates.DepthWrite, 1, new ClearValue(Format.D32_Float, new DepthStencilValue(1, 0)));
        fog = dx.Texture(Hdr, Width / 2, Height / 2, Rt, ResourceStates.RenderTarget);
        hdr = dx.Texture(Hdr, Width, Height, Rt, ResourceStates.RenderTarget);
        for (int l = 0; l < BloomLevels; l++) bloom.Add(dx.Texture(Hdr, Math.Max(1, Width >> (l + 1)), Math.Max(1, Height >> (l + 1)), Rt, ResourceStates.RenderTarget));
        for (int i = 0; i < 2; i++) streaks.Add(dx.Texture(Hdr, Width / 4, Height / 4, Rt, ResourceStates.RenderTarget));
        output = dx.Texture(Format.R8G8B8A8_UNorm, Width, Height, Rt, ResourceStates.RenderTarget);
        readback = dx.Buffer(readPitch * Height, HeapType.Readback, ResourceStates.CopyDest);
    }

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

    private void BuildRootAndPipelines()
    {
        RootParameter1 Cbv(uint reg) => new(RootParameterType.ConstantBufferView, new RootDescriptor1(reg, 0, RootDescriptorFlags.None), ShaderVisibility.All);
        var ps = new List<RootParameter1>
        {
            Cbv(0), Cbv(1), Cbv(2),
            new(new RootDescriptorTable1(new DescriptorRange1(DescriptorRangeType.ShaderResourceView, Slots, 0, 0, 0, DescriptorRangeFlags.None)), ShaderVisibility.All),
        };
        if (rays)
        {
            ps.Add(new(new RootDescriptorTable1(new DescriptorRange1(DescriptorRangeType.ShaderResourceView, uint.MaxValue, 0, 1, 0, DescriptorRangeFlags.None)), ShaderVisibility.Pixel));
            ps.Add(new(new RootDescriptorTable1(new DescriptorRange1(DescriptorRangeType.ShaderResourceView, uint.MaxValue, 0, 2, 0, DescriptorRangeFlags.None)), ShaderVisibility.Pixel));
        }
        var samplers = new[]
        {
            new StaticSamplerDescription(0, Vortice.Direct3D12.Filter.MinMagMipLinear, Vortice.Direct3D12.TextureAddressMode.Clamp, Vortice.Direct3D12.TextureAddressMode.Clamp, Vortice.Direct3D12.TextureAddressMode.Clamp, 0, 1, ComparisonFunction.Never, StaticBorderColor.TransparentBlack, 0, float.MaxValue, ShaderVisibility.All, 0),
            new StaticSamplerDescription(1, Vortice.Direct3D12.Filter.ComparisonMinMagLinearMipPoint, Vortice.Direct3D12.TextureAddressMode.Clamp, Vortice.Direct3D12.TextureAddressMode.Clamp, Vortice.Direct3D12.TextureAddressMode.Clamp, 0, 1, ComparisonFunction.LessEqual, StaticBorderColor.OpaqueWhite, 0, float.MaxValue, ShaderVisibility.All, 0),
            new StaticSamplerDescription(2, Vortice.Direct3D12.Filter.MinMagMipPoint, Vortice.Direct3D12.TextureAddressMode.Clamp, Vortice.Direct3D12.TextureAddressMode.Clamp, Vortice.Direct3D12.TextureAddressMode.Clamp, 0, 1, ComparisonFunction.Never, StaticBorderColor.TransparentBlack, 0, float.MaxValue, ShaderVisibility.All, 0),
            new StaticSamplerDescription(3, Vortice.Direct3D12.Filter.MinMagMipLinear, Vortice.Direct3D12.TextureAddressMode.Clamp, Vortice.Direct3D12.TextureAddressMode.Clamp, Vortice.Direct3D12.TextureAddressMode.Clamp, 0, 1, ComparisonFunction.Never, StaticBorderColor.TransparentBlack, 0, float.MaxValue, ShaderVisibility.All, 0),
        };
        var desc = new VersionedRootSignatureDescription(new RootSignatureDescription1(RootSignatureFlags.AllowInputAssemblerInputLayout, ps.ToArray(), samplers));
        root = dx.Device.CreateRootSignature(0, desc);

        var opaque = BlendDescription.Opaque;
        var premultiplied = new BlendDescription(Blend.One, Blend.InverseSourceAlpha);
        var additive = new BlendDescription(Blend.One, Blend.One);
        var less = new DepthStencilDescription(true, DepthWriteMask.All, ComparisonFunction.Less);
        var lessEqual = new DepthStencilDescription(true, DepthWriteMask.All, ComparisonFunction.LessEqual);
        var testOnly = new DepthStencilDescription(true, DepthWriteMask.Zero, ComparisonFunction.Less);
        var off = new DepthStencilDescription(false, DepthWriteMask.Zero, ComparisonFunction.Always);
        var always = new DepthStencilDescription(true, DepthWriteMask.All, ComparisonFunction.Always);
        var layout = ObjectLayout();
        Format[] one = [Hdr];
        psoObject = Pso("ncObjectVS", "ncObjectFS", layout, opaque, less, one, true, samples);
        psoSky = Pso("ncSkyVS", "ncSkyFS", null, opaque, lessEqual, one, true, samples);
        psoGlow = Pso("ncGlowVS", "ncGlowFS", null, premultiplied, testOnly, one, true, samples);
        psoSteam = Pso("ncSteamVS", "ncSteamFS", null, premultiplied, testOnly, one, true, samples);
        psoRain = Pso("ncRainVS", "ncRainFS", null, premultiplied, testOnly, one, true, samples);
        psoSplash = Pso("ncSplashVS", "ncSplashFS", null, premultiplied, testOnly, one, true, samples);
        psoFog = Pso("ncPostVS", "ncFogFS", null, opaque, off, one, false, 1);
        psoCombine = Pso("ncPostVS", "ncCombineFS", null, opaque, off, one, false, 1);
        psoDownFirst = Pso("ncPostVS", "ncDownFS", null, opaque, off, one, false, 1, "THRESHOLD");
        psoDown = Pso("ncPostVS", "ncDownFS", null, opaque, off, one, false, 1);
        psoUp = Pso("ncPostVS", "ncUpFS", null, additive, off, one, false, 1);
        psoStreak = Pso("ncPostVS", "ncStreakFS", null, opaque, off, one, false, 1);
        psoFinal = Pso("ncPostVS", "ncFinalFS", null, opaque, off, [Format.R8G8B8A8_UNorm], false, 1);
        psoDepth = Pso("ncPostVS", "ncDepthResolveFS", null, opaque, always, [], true, 1);
    }

    private ID3D12PipelineState Pso(string vs, string ps, InputElementDescription[]? layout, BlendDescription blend, DepthStencilDescription ds, Format[] rtv, bool hasDepth, int count, params string[] extra)
    {
        var defs = defines.Concat(extra).ToArray();
        var d = new GraphicsPipelineStateDescription
        {
            RootSignature = root,
            VertexShader = dx.Shader("NeonCity", vs, DxcShaderStage.Vertex, defs),
            PixelShader = dx.Shader("NeonCity", ps, DxcShaderStage.Pixel, defs),
            BlendState = blend,
            SampleMask = uint.MaxValue,
            RasterizerState = RasterizerDescription.CullNone,
            DepthStencilState = ds,
            InputLayout = layout == null ? new InputLayoutDescription() : new InputLayoutDescription(layout),
            PrimitiveTopologyType = PrimitiveTopologyType.Triangle,
            RenderTargetFormats = rtv,
            DepthStencilFormat = hasDepth ? Format.D32_Float : Format.Unknown,
            SampleDescription = new SampleDescription((uint)count, 0),
        };
        try { var pso = dx.Device.CreateGraphicsPipelineState(d); psos.Add(pso); return pso; }
        catch (Exception e)
        {
            if (Environment.GetEnvironmentVariable("THERMOHAMMER_SHADER_DEBUG") == "1") { Console.Error.WriteLine($"PSO FAILED {vs} + {ps}: {e.Message.Trim()}"); return null!; }
            throw new GpuError($"pipeline {vs} + {ps}: {e.Message.Trim()}");
        }
    }

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
        for (int i = 0; i < asset.Meshes.Count; i++)
        {
            var m = asset.Meshes[i];
            meshes.Add(new GpuMesh(dx.StaticBuffer(m.Vertices, staging), dx.StaticBuffer(m.Indices, staging), m.VertexCount, m.IndexCount, m.WideIndices, i));
        }
        var identity = new float[NeonCitySim.InstanceFloats];
        identity[0] = 1; identity[5] = 1; identity[10] = 1;
        var identityBuf = dx.StaticBuffer(MemoryMarshal.AsBytes(identity.AsSpan()), staging);
        foreach (int i in asset.ChunkMeshes) batches.Add(new Batch(meshes[i], identityBuf, 1, 0, false, 0) { Rows = identity });
        for (int gi = 0; gi < asset.Groups.Count; gi++)
        {
            var g = asset.Groups[gi];
            var rows = InstanceRows(g.Transforms, g.Count);
            batches.Add(new Batch(meshes[g.Mesh], dx.StaticBuffer(MemoryMarshal.AsBytes(rows.AsSpan()), staging), g.Count, 0, true, gi * 7.31f) { Rows = rows });
        }
        var actors = sim.CarBatches.Select(b => (b, 1)).Concat(sim.PedBatches.Select(b => (b, 2)))
            .Concat([(sim.TrainHeads, 3), (sim.TrainCarsBatch, 3), (sim.DroneBatch, 3), (sim.PoliceBatch, 3)]);
        foreach (var (ab, kind) in actors)
        {
            int capacity = ab.Data.Length / NeonCitySim.InstanceFloats;
            int mi = asset.MeshIndex(ab.Mesh);
            if (capacity == 0 || mi < 0) continue;
            var buf = dx.Buffer((ulong)ab.Data.Length * 4, HeapType.Upload, ResourceStates.GenericRead);
            void* p;
            buf.Map(0, null, &p);
            batches.Add(new Batch(meshes[mi], buf, 0, kind, true, 0, ab) { Upload = (byte*)p });
        }
        var st = asset.GetTable("steam");
        for (int r = 0; r < st.Rows; r++) steamOrigins.Add(new Vector4(st[r, 0], st[r, 1], st[r, 2], 1));
        long main = batches.Sum(b => (long)(b.Mesh.Indices / 3) * Math.Max(b.Count, (b.Sim?.Data.Length ?? 0) / NeonCitySim.InstanceFloats));
        TrianglesPerFrame = (int)main + 2 * (Rain + Splashes);
    }

    private void BuildGridBuffers()
    {
        lightsBuf = dx.Buffer((ulong)grid.Lights.Length * 4, HeapType.Default, ResourceStates.NonPixelShaderResource | ResourceStates.PixelShaderResource);
        cellsBuf = dx.Buffer((ulong)grid.Cells.Length * 4, HeapType.Default, ResourceStates.NonPixelShaderResource | ResourceStates.PixelShaderResource);
        indexCapacity = Math.Max(grid.Indices.Length, 1 << 20);
        indexBuf = dx.Buffer((ulong)indexCapacity * 2, HeapType.Default, ResourceStates.NonPixelShaderResource | ResourceStates.PixelShaderResource);
    }

    private void BuildAccelerationStructures()
    {
        // one bottom-level structure per mesh
        var infos = new List<(RaytracingGeometryDescription geo, RaytracingAccelerationStructurePrebuildInfo info)>();
        ulong scratchMax = 0;
        foreach (var m in meshes)
        {
            var geo = new RaytracingGeometryDescription(new RaytracingGeometryTrianglesDescription(
                new GpuVirtualAddressAndStride(m.Vb.GPUVirtualAddress, NeonCityAsset.VertexBytes), Format.R32G32B32_Float, (uint)m.Vertices, 0,
                m.Ib.GPUVirtualAddress, m.Wide ? Format.R32_UInt : Format.R16_UInt, (uint)m.Indices), RaytracingGeometryFlags.Opaque);
            var inputs = new BuildRaytracingAccelerationStructureInputs
            {
                Type = RaytracingAccelerationStructureType.BottomLevel, Flags = RaytracingAccelerationStructureBuildFlags.PreferFastTrace,
                DescriptorsCount = 1, Layout = ElementsLayout.Array, GeometryDescriptions = [geo],
            };
            var info = dx.Device.GetRaytracingAccelerationStructurePrebuildInfo(inputs);
            infos.Add((geo, info));
            scratchMax = Math.Max(scratchMax, info.ScratchDataSizeInBytes);
        }
        var scratch = dx.Buffer(scratchMax, HeapType.Default, ResourceStates.UnorderedAccess, ResourceFlags.AllowUnorderedAccess);
        for (int i = 0; i < meshes.Count; i++)
        {
            var (geo, info) = infos[i];
            var result = dx.Buffer((info.ResultDataMaxSizeInBytes + 255) & ~255UL, HeapType.Default, ResourceStates.RaytracingAccelerationStructure, ResourceFlags.AllowUnorderedAccess);
            meshes[i].BlasVa = result.GPUVirtualAddress;
            var inputs = new BuildRaytracingAccelerationStructureInputs
            {
                Type = RaytracingAccelerationStructureType.BottomLevel, Flags = RaytracingAccelerationStructureBuildFlags.PreferFastTrace,
                DescriptorsCount = 1, Layout = ElementsLayout.Array, GeometryDescriptions = [geo],
            };
            dx.List.BuildRaytracingAccelerationStructure(new BuildRaytracingAccelerationStructureDescription(result.GPUVirtualAddress, inputs, 0, scratch.GPUVirtualAddress));
            dx.UavBarrier(scratch);        // the next build reuses the scratch
        }

        // the city-wide structure: every static instance, then room for every actor
        int actorCapacity = batches.Where(b => b.Sim != null).Sum(b => b.Sim!.Data.Length / NeonCitySim.InstanceFloats);
        staticInstances = batches.Where(b => b.Sim == null).Sum(b => b.Count);
        instanceCapacity = staticInstances + actorCapacity;
        instanceDescs = dx.Buffer((ulong)instanceCapacity * (ulong)sizeof(RaytracingInstanceDescription), HeapType.Upload, ResourceStates.GenericRead);
        rtInstances = dx.Buffer((ulong)instanceCapacity * (ulong)sizeof(RtInstance), HeapType.Upload, ResourceStates.GenericRead);
        void* p;
        instanceDescs.Map(0, null, &p); instanceDescPtr = (byte*)p;
        rtInstances.Map(0, null, &p); rtInstancePtr = (byte*)p;
        int slot = 0;
        foreach (var b in batches.Where(b => b.Sim == null))
            for (int k = 0; k < b.Count; k++) WriteInstance(slot++, b, b.Rows!, k * NeonCitySim.InstanceFloats);
        var tinputs = new BuildRaytracingAccelerationStructureInputs
        {
            Type = RaytracingAccelerationStructureType.TopLevel, Flags = RaytracingAccelerationStructureBuildFlags.PreferFastBuild,
            DescriptorsCount = (uint)instanceCapacity, Layout = ElementsLayout.Array, InstanceDescriptions = instanceDescs.GPUVirtualAddress,
        };
        var tinfo = dx.Device.GetRaytracingAccelerationStructurePrebuildInfo(tinputs);
        tlas = dx.Buffer((tinfo.ResultDataMaxSizeInBytes + 255) & ~255UL, HeapType.Default, ResourceStates.RaytracingAccelerationStructure, ResourceFlags.AllowUnorderedAccess);
        tlasScratch = dx.Buffer(tinfo.ScratchDataSizeInBytes, HeapType.Default, ResourceStates.UnorderedAccess, ResourceFlags.AllowUnorderedAccess);
        tlasVa = tlas.GPUVirtualAddress;
        // what the shaders read about each mesh: whether its indices are 32-bit
        var wide = dx.Buffer((ulong)Math.Max(meshes.Count, 4) * 4, HeapType.Upload, ResourceStates.GenericRead);
        wide.Map(0, null, &p);
        for (int i = 0; i < meshes.Count; i++) ((uint*)p)[i] = meshes[i].Wide ? 1u : 0u;
        rtMeshWide = wide;
    }

    /// <summary>Instance `slot` of the city-wide structure: batch `b`'s record at `o` in `rows` (3 transform rows, then extras).</summary>
    private void WriteInstance(int slot, Batch b, ReadOnlySpan<float> rows, int o)
    {
        var d = new RaytracingInstanceDescription
        {
            Transform = new Matrix3x4(rows[o], rows[o + 1], rows[o + 2], rows[o + 3], rows[o + 4], rows[o + 5], rows[o + 6], rows[o + 7], rows[o + 8], rows[o + 9], rows[o + 10], rows[o + 11]),
            InstanceID = (UInt24)(uint)slot, InstanceMask = 0xFF, InstanceContributionToHitGroupIndex = (UInt24)0u, Flags = RaytracingInstanceFlags.None,
            AccelerationStructure = b.Mesh.BlasVa,
        };
        ((RaytracingInstanceDescription*)instanceDescPtr)[slot] = d;
        var i = new RtInstance
        {
            Row0 = new Vector4(rows[o], rows[o + 1], rows[o + 2], rows[o + 3]), Row1 = new Vector4(rows[o + 4], rows[o + 5], rows[o + 6], rows[o + 7]),
            Row2 = new Vector4(rows[o + 8], rows[o + 9], rows[o + 10], rows[o + 11]),
            Extra = b.Sim != null ? new Vector4(rows[o + 12], rows[o + 13], rows[o + 14], rows[o + 15]) : default,
            Mesh = b.Mesh.Index, Actor = b.Actor, Instanced = b.Instanced ? 1 : 0, Seed = b.Seed,
        };
        ((RtInstance*)rtInstancePtr)[slot] = i;
    }

    // ── descriptors ──

    private ShaderResourceViewDescription TexSrv(Format f, bool ms = false) => new()
    {
        Format = f, ViewDimension = ms ? Vortice.Direct3D12.ShaderResourceViewDimension.Texture2DMultisampled : Vortice.Direct3D12.ShaderResourceViewDimension.Texture2D,
        Shader4ComponentMapping = ShaderComponentMapping.Default, Texture2D = new Texture2DShaderResourceView { MipLevels = 1 },
    };

    private void SrvTex(int block, int slot, Tex t, Format f, bool ms = false) => dx.Device.CreateShaderResourceView(t.Res, TexSrv(f, ms), dx.SrvCpu(block + slot));

    private void SrvStructured(int block, int slot, ID3D12Resource r, uint count, uint stride) => dx.Device.CreateShaderResourceView(r, new ShaderResourceViewDescription
    {
        Format = Format.Unknown, ViewDimension = Vortice.Direct3D12.ShaderResourceViewDimension.Buffer, Shader4ComponentMapping = ShaderComponentMapping.Default,
        Buffer = new BufferShaderResourceView { FirstElement = 0, NumElements = count, StructureByteStride = stride },
    }, dx.SrvCpu(block + slot));

    private void SrvRaw(int index, ID3D12Resource r, uint dwords) => dx.Device.CreateShaderResourceView(r, new ShaderResourceViewDescription
    {
        Format = Format.R32_Typeless, ViewDimension = Vortice.Direct3D12.ShaderResourceViewDimension.Buffer, Shader4ComponentMapping = ShaderComponentMapping.Default,
        Buffer = new BufferShaderResourceView { FirstElement = 0, NumElements = dwords, Flags = BufferShaderResourceViewFlags.Raw },
    }, dx.SrvCpu(index));

    private void SrvTlas(int block, int slot) => dx.Device.CreateShaderResourceView(null!, new ShaderResourceViewDescription
    {
        Format = Format.Unknown, ViewDimension = Vortice.Direct3D12.ShaderResourceViewDimension.RaytracingAccelerationStructure, Shader4ComponentMapping = ShaderComponentMapping.Default,
        RaytracingAccelerationStructure = new RaytracingAccelerationStructureShaderResourceView { Location = tlasVa },
    }, dx.SrvCpu(block + slot));

    private int Block()
    {
        int at = dx.AllocSrv(Slots);
        for (int i = 0; i < Slots; i++) dx.NullSrv(at + i);
        return at;
    }

    private void GridSrvs(int block)
    {
        SrvStructured(block, 8, lightsBuf, (uint)(grid.Lights.Length / 4), 16);
        SrvStructured(block, 9, cellsBuf, (uint)grid.Cells.Length, 4);
        SrvRaw(block + 10, indexBuf, (uint)(indexCapacity / 2));
    }

    private void BuildBlocks()
    {
        blockMain = Block();
        GridSrvs(blockMain);
        if (rays)
        {
            SrvTlas(blockMain, 7);
            SrvStructured(blockMain, 11, rtInstances, (uint)instanceCapacity, (uint)sizeof(RtInstance));
            SrvStructured(blockMain, 12, rtMeshWide, (uint)meshes.Count, 4);
        }
        blockFog = Block();
        SrvTex(blockFog, 2, depth, Format.R32_Float);
        GridSrvs(blockFog);
        if (rays) SrvTlas(blockFog, 7);
        blockCombine = Block();
        SrvTex(blockCombine, 4, scene, Hdr);
        SrvTex(blockCombine, 5, fog, Hdr);
        for (int l = 0; l < BloomLevels; l++)
        {
            blockDown[l] = Block();
            SrvTex(blockDown[l], 4, l == 0 ? hdr : bloom[l - 1], Hdr);
            blockUp[l] = Block();
            SrvTex(blockUp[l], 4, bloom[l], Hdr);
        }
        for (int i = 0; i < 3; i++)
        {
            blockStreak[i] = Block();
            SrvTex(blockStreak[i], 4, i == 0 ? bloom[1] : streaks[(i - 1) % 2], Hdr);
        }
        blockFinal = Block();
        SrvTex(blockFinal, 2, depth, Format.R32_Float);
        SrvTex(blockFinal, 4, hdr, Hdr);
        SrvTex(blockFinal, 5, bloom[0], Hdr);
        SrvTex(blockFinal, 6, streaks[0], Hdr);
        blockDepth = Block();
        SrvTex(blockDepth, 3, msaaDepth, Format.R32_Float, ms: true);
        if (rays)
        {
            // every mesh's vertices and indices as raw buffers, for the shaders that shade what a ray hits
            blockVertices = dx.AllocSrv(meshes.Count);
            blockIndices = dx.AllocSrv(meshes.Count);
            for (int i = 0; i < meshes.Count; i++)
            {
                SrvRaw(blockVertices + i, meshes[i].Vb, (uint)(meshes[i].Vertices * NeonCityAsset.VertexBytes / 4));
                SrvRaw(blockIndices + i, meshes[i].Ib, (uint)((meshes[i].Indices * (meshes[i].Wide ? 4 : 2) + 3) / 4));
            }
        }
    }

    // ── per frame ──

    private void Camera(float t)
    {
        var e = new Vector3(sim.Eye[0], sim.Eye[1], sim.Eye[2]);
        var g = new Vector3(sim.Target[0], sim.Target[1], sim.Target[2]);
        var view = Mat4.LookAt(e, g, new Vector3(0, 0, 1));
        var proj = Mat4.Perspective(sim.FovDeg, (float)Width / Height, 0.25f, 4000);
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
        frame.InvViewport = new Vector2(1f / Width, 1f / Height);
        sim.Groups.CopyTo(MemoryMarshal.CreateSpan(ref System.Runtime.CompilerServices.Unsafe.As<Vector4, float>(ref frame.G0), 32));
    }

    private const ResourceStates Srv = ResourceStates.PixelShaderResource | ResourceStates.NonPixelShaderResource;
    private const ResourceStates GridState = ResourceStates.PixelShaderResource | ResourceStates.NonPixelShaderResource;
    private int tlasCount;

    /// <summary>Moving lights for this frame, then the merged grid copied into its buffers.</summary>
    private void LightGrid(float t)
    {
        grid.SetMovingLights(sim, t, SearchCos);
        if (grid.IndexCount > indexCapacity) throw new GpuError("the light grid outgrew its buffer");
        var l = dx.List;
        dx.Transition(lightsBuf, GridState, ResourceStates.CopyDest);
        dx.Transition(cellsBuf, GridState, ResourceStates.CopyDest);
        dx.Transition(indexBuf, GridState, ResourceStates.CopyDest);
        fixed (float* lp = grid.Lights) l.CopyBufferRegion(lightsBuf, 0, dx.Ring, (ulong)dx.RingWrite(lp, grid.Lights.Length * 4), (ulong)grid.Lights.Length * 4);
        fixed (uint* cp = grid.Cells) l.CopyBufferRegion(cellsBuf, 0, dx.Ring, (ulong)dx.RingWrite(cp, grid.Cells.Length * 4), (ulong)grid.Cells.Length * 4);
        int bytes = (grid.IndexCount + 1) & ~1;
        fixed (ushort* ip = grid.Indices) l.CopyBufferRegion(indexBuf, 0, dx.Ring, (ulong)dx.RingWrite(ip, bytes * 2), (ulong)bytes * 2);
        dx.Transition(lightsBuf, ResourceStates.CopyDest, GridState);
        dx.Transition(cellsBuf, ResourceStates.CopyDest, GridState);
        dx.Transition(indexBuf, ResourceStates.CopyDest, GridState);
    }

    /// <summary>The moving actors' instance records (the draws') and their ray-tracing instances.</summary>
    private void UploadActors()
    {
        int slot = staticInstances;
        foreach (var b in batches)
        {
            if (b.Sim is not { } ab) continue;
            b.Count = ab.Count;
            if (ab.Count == 0) continue;
            fixed (float* p = ab.Data) Buffer.MemoryCopy(p, b.Upload, ab.Count * InstanceBytes, ab.Count * InstanceBytes);
            if (rays) for (int k = 0; k < ab.Count; k++) WriteInstance(slot++, b, ab.Data, k * NeonCitySim.InstanceFloats);
        }
        tlasCount = slot;
    }

    private void BuildTlas()
    {
        var inputs = new BuildRaytracingAccelerationStructureInputs
        {
            Type = RaytracingAccelerationStructureType.TopLevel, Flags = RaytracingAccelerationStructureBuildFlags.PreferFastBuild,
            DescriptorsCount = (uint)tlasCount, Layout = ElementsLayout.Array, InstanceDescriptions = instanceDescs.GPUVirtualAddress,
        };
        dx.List.BuildRaytracingAccelerationStructure(new BuildRaytracingAccelerationStructureDescription(tlasVa, inputs, 0, tlasScratch.GPUVirtualAddress));
        dx.UavBarrier(tlas);
    }

    // ── passes ──

    private void Bind(in NcFrame f, in NcPost post, int block)
    {
        var l = dx.List;
        l.SetGraphicsRootConstantBufferView(0, dx.Constants(f));
        l.SetGraphicsRootConstantBufferView(1, dx.Constants(new NcDraw()));
        l.SetGraphicsRootConstantBufferView(2, dx.Constants(post));
        l.SetGraphicsRootDescriptorTable(3, dx.SrvGpu(block));
        if (rays)
        {
            l.SetGraphicsRootDescriptorTable(4, dx.SrvGpu(blockVertices));
            l.SetGraphicsRootDescriptorTable(5, dx.SrvGpu(blockIndices));
        }
    }

    private void Area(int w, int h)
    {
        dx.List.RSSetViewport(new Viewport(0, 0, w, h, 0, 1));
        dx.List.RSSetScissorRect(w, h);
    }

    private void Full(ID3D12PipelineState pso, Tex target, int w, int h, int block, in NcPost post, params Tex[] reads)
    {
        var l = dx.List;
        foreach (var r in reads) dx.Transition(r, Srv);
        dx.Transition(target, ResourceStates.RenderTarget);
        l.OMSetRenderTargets(target.Rtv, null);
        Area(w, h);
        l.SetPipelineState(pso);
        Bind(frame, post, block);
        l.DrawInstanced(3, 1, 0, 0);
    }

    private void MainPass()
    {
        var l = dx.List;
        dx.Transition(msaaColor, ResourceStates.RenderTarget);
        dx.Transition(msaaDepth, ResourceStates.DepthWrite);
        l.OMSetRenderTargets(msaaColor.Rtv, msaaDepth.Dsv);
        l.ClearRenderTargetView(msaaColor.Rtv, new Color4(0, 0, 0, 0));
        l.ClearDepthStencilView(msaaDepth.Dsv, ClearFlags.Depth, 1, 0);
        Area(Width, Height);
        l.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        l.SetPipelineState(psoObject);
        Bind(frame, default, blockMain);
        foreach (var b in batches)
        {
            if (b.Count == 0) continue;
            l.SetGraphicsRootConstantBufferView(1, dx.Constants(new NcDraw { Actor = b.Actor, Instanced = b.Instanced ? 1 : 0, Seed = b.Seed }));
            views[0] = new VertexBufferView(b.Mesh.Vb.GPUVirtualAddress, (uint)(b.Mesh.Vertices * NeonCityAsset.VertexBytes), NeonCityAsset.VertexBytes);
            views[1] = new VertexBufferView(b.Instances.GPUVirtualAddress, (uint)(b.Count * InstanceBytes), InstanceBytes);
            l.IASetVertexBuffers(0, views);
            l.IASetIndexBuffer(new IndexBufferView(b.Mesh.Ib.GPUVirtualAddress, (uint)(b.Mesh.Indices * (b.Mesh.Wide ? 4 : 2)), b.Mesh.Wide ? Format.R32_UInt : Format.R16_UInt));
            l.DrawIndexedInstanced((uint)b.Mesh.Indices, (uint)b.Count, 0, 0, 0);
        }
        l.SetPipelineState(psoSky);
        l.DrawInstanced(3, 1, 0, 0);
        // translucent: premultiplied alpha (alpha 0 = additive), depth-tested, not written
        l.SetPipelineState(psoGlow);
        l.DrawInstanced(6, (uint)grid.LightCount, 0, 0);
        l.SetPipelineState(psoSteam);
        foreach (var o in steamOrigins)
        {
            var f = frame;
            f.Origin = o;
            l.SetGraphicsRootConstantBufferView(0, dx.Constants(f));
            l.DrawInstanced(6, SteamPuffs, 0, 0);
        }
        l.SetGraphicsRootConstantBufferView(0, dx.Constants(frame));
        l.SetPipelineState(psoRain);
        l.DrawInstanced(6, Rain, 0, 0);
        l.SetPipelineState(psoSplash);
        l.DrawInstanced(6, Splashes, 0, 0);
    }

    private readonly VertexBufferView[] views = new VertexBufferView[2];

    private void ResolvePass()
    {
        var l = dx.List;
        dx.Transition(msaaColor, ResourceStates.ResolveSource);
        dx.Transition(scene, ResourceStates.ResolveDest);
        l.ResolveSubresource(scene.Res, 0, msaaColor.Res, 0, Hdr);
        // Direct3D can't resolve depth: write sample 0 of the multisampled depth into the plain one
        dx.Transition(msaaDepth, Srv);
        dx.Transition(depth, ResourceStates.DepthWrite);
        l.OMSetRenderTargets(0, (CpuDescriptorHandle*)null, false, depth.Dsv);
        Area(Width, Height);
        l.SetPipelineState(psoDepth);
        Bind(frame, default, blockDepth);
        l.DrawInstanced(3, 1, 0, 0);
    }

    private void PostPass()
    {
        Full(psoFog, fog, Width / 2, Height / 2, blockFog, default, depth);
        Full(psoCombine, hdr, Width, Height, blockCombine, default, scene, fog);
        // bloom: thresholded 13-tap chain down, streaks off the ¼-res level, tent chain back up
        Tex src = hdr;
        for (int l = 0; l < BloomLevels; l++)
        {
            Full(l == 0 ? psoDownFirst : psoDown, bloom[l], bloom[l].W, bloom[l].H, blockDown[l], new NcPost { Texel = new(1f / src.W, 1f / src.H) }, src);
            src = bloom[l];
        }
        float[] strides = [1, 3, 8];
        Tex sSrc = bloom[1];
        for (int i = 0; i < 3; i++)
        {
            var target = streaks[i % 2];
            Full(psoStreak, target, target.W, target.H, blockStreak[i], new NcPost { Step = new(strides[i] * 4 / Width, 0) }, sSrc);
            sSrc = target;
        }
        for (int l = BloomLevels - 1; l >= 1; l--)
            Full(psoUp, bloom[l - 1], bloom[l - 1].W, bloom[l - 1].H, blockUp[l], new NcPost { Texel = new(1f / bloom[l].W, 1f / bloom[l].H), Weight = 1 }, bloom[l]);
        Full(psoFinal, output, Width, Height, blockFinal, default, depth, hdr, bloom[0], streaks[0]);
    }

    /// <summary>One frame of the timeline recorded and submitted (not waited for).</summary>
    public void Encode(float t)
    {
        sim.Update(t);
        Camera(t);
        UploadActors();
        dx.Begin();
        dx.List.SetGraphicsRootSignature(root);
        LightGrid(t);
        if (rays) BuildTlas();
        MainPass();
        ResolvePass();
        PostPass();
        dx.Submit();
        prevViewProj = frame.ViewProj;
        frameIndex++;
    }

    // ── the run ──

    /// <summary>
    /// Renders `seconds` of the scene's timeline (wall time, like the phones) and returns every frame's timeline start and
    /// render time. <paramref name="onFrame"/> gets the timeline position and the fps of the last ~¼ s.
    /// </summary>
    public (float[] starts, long[] durNs) Run(float seconds, Func<bool> isActive, Action<float, double> onFrame)
    {
        var starts = new List<float>(4096);
        var durs = new List<long>(4096);
        long start = Clock.Ns(), lastPresent = 0, windowStart = start;
        int windowFrames = 0;
        double fps = 0;
        while (isActive())
        {
            long t0 = Clock.Ns();
            float sceneT = (float)((t0 - start) / 1e9);
            if (sceneT >= seconds) break;
            Encode(sceneT);
            dx.Wait();
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
                Show();
                onFrame(sceneT, fps);
            }
        }
        return (starts.ToArray(), durs.ToArray());
    }

    /// <summary>The finished frame as tightly packed RGBA8 (1920×1080).</summary>
    private byte[] ReadOutput()
    {
        dx.Begin();
        dx.Transition(output, ResourceStates.CopySource);
        var dst = new TextureCopyLocation(readback, new PlacedSubresourceFootPrint
        {
            Offset = 0, Footprint = new SubresourceFootPrint(Format.R8G8B8A8_UNorm, Width, Height, 1, readPitch),
        });
        dx.List.CopyTextureRegion(dst, 0, 0, 0, new TextureCopyLocation(output.Res, 0));
        dx.Transition(output, ResourceStates.RenderTarget);
        dx.Submit();
        dx.Wait();
        var px = new byte[Width * Height * 4];
        void* p;
        readback.Map(0, null, &p);
        for (int y = 0; y < Height; y++) Marshal.Copy((IntPtr)((byte*)p + (long)y * readPitch), px, y * Width * 4, Width * 4);
        readback.Unmap(0);
        return px;
    }

    private readonly byte[] viewBytes = new byte[960 * 540 * 4];

    /// <summary>Hands the stage a small BGRA copy of the finished frame.</summary>
    private void Show()
    {
        if (FrameSink == null) return;
        var px = ReadOutput();
        for (int y = 0; y < 540; y++)
            for (int x = 0; x < 960; x++)
            {
                int a = (y * 2 * Width + x * 2) * 4, o = (y * 960 + x) * 4;
                for (int c = 0; c < 3; c++)
                    viewBytes[o + 2 - c] = (byte)((px[a + c] + px[a + 4 + c] + px[a + Width * 4 + c] + px[a + Width * 4 + 4 + c] + 2) >> 2);
                viewBytes[o + 3] = 255;
            }
        FrameSink(viewBytes);
    }

    /// <summary>The last frame as a PNG (checks).</summary>
    public void SavePng(string path) => PngWriter.Save(path, Width, Height, ReadOutput().Select((b, i) => i % 4 == 3 ? (byte)255 : b).ToArray());

    public void Dispose()
    {
        dx.Wait();
        foreach (var s in staging) s.Dispose();
        foreach (var p in psos) p.Dispose();
        root?.Dispose();
        dx.Dispose();
    }
}
