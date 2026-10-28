using System.Numerics;
using System.Runtime.InteropServices;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;
using Vortice.Mathematics;

namespace ThermoHammer.Engine.Gpu;

// ─────────────────────────────────────────────────────────────────────────────
// LAKE VALLEY — GPU Test 1, the Direct3D 11 port of the Android renderer
// (engine/gpu/LakeValleyRenderer.kt) and its iPhone port (LakeValleyRenderer.swift): the same asset, passes and workload.
//
//   Per frame:  sun shadow map 2048² → planar water reflection (½ res) →
//               4× MSAA main pass (terrain, ~14k instanced trees/rocks/reeds,
//               props, water, sky + clouds, birds, smoke, pollen) → resolve →
//               3-level bloom → composite.
//
//   Everything is drawn every frame — no culling, no LOD switching — and the
//   camera circles the lake looking outward, so the workload stays flat.
// ─────────────────────────────────────────────────────────────────────────────

[StructLayout(LayoutKind.Sequential)]
public struct LvFrame
{
    public Mat4 ViewProj = Mat4.Identity, InvViewProj = Mat4.Identity, ShadowMat = Mat4.Identity;
    public Vector4 SunDir, SunColor, CamPos, Wind, Lake, CamRight, CamUp, Origin;
    public Vector2 InvViewport;
    public float Time;
    public float Exposure = 1.05f;
    public LvFrame() { }
}

[StructLayout(LayoutKind.Sequential)]
public struct LvDraw { public float MeshHeight, Sway, Flutter, Tint, Seed, Pad0, Pad1, Pad2; }

[StructLayout(LayoutKind.Sequential)]
public struct LvPost { public Vector2 Texel, Step; public float Time, Pad0, Pad1, Pad2; }

public sealed class LakeValleyRenderer : IGpuScene
{
    public const float LoopS = 50;              // camera loop — one lap of the lake
    private const int ShadowSize = 2048;
    private const float ShadowExtent = 620;
    private const int Birds = 80, Smoke = 48, Pollen = 600;
    private const int WaterGridX = 160, WaterGridY = 100;

    private sealed record GpuMesh(ID3D11Buffer Vertices, ID3D11Buffer Indices, int IndexCount, Format IndexFormat, float Height);

    private sealed class Batch(GpuMesh mesh, ID3D11Buffer instances, int count)
    {
        public readonly GpuMesh Mesh = mesh;
        public readonly ID3D11Buffer Instances = instances;
        public readonly int Count = count;
        public float Sway, Flutter, Tint, Seed;
        public bool Cull, IsTerrain;
    }

    private readonly GpuBench bench;
    private readonly ID3D11Device dev;
    private readonly ID3D11DeviceContext ctx;
    private readonly LakeValleyAsset asset;
    private readonly int width, height;

    // pipelines
    private Pipe objMain = null!, objMirror = null!, terrainMain = null!, terrainMirror = null!, depthOnly = null!;
    private Pipe water = null!, sky = null!, skyMirror = null!, birds = null!, smoke = null!, pollen = null!;
    private Pipe bright = null!, down = null!, blur = null!, composite = null!;
    private ID3D11DepthStencilState depthLess = null!, depthLessEqual = null!, depthTestOnly = null!, depthOff = null!;
    // rasterizer states: [front-face CCW?][cull back?]
    private readonly ID3D11RasterizerState[,] raster = new ID3D11RasterizerState[2, 2];
    private ID3D11RasterizerState shadowRaster = null!, shadowRasterCull = null!;
    private ID3D11SamplerState linear = null!, shadowSampler = null!;
    private ID3D11Buffer frameCb = null!, drawCb = null!, postCb = null!;

    // geometry
    private readonly List<Batch> mainBatches = [], shadowBatches = [], mirrorBatches = [];
    private ID3D11Buffer boatInstances = null!, waterXY = null!, waterIndices = null!, birdVerts = null!;
    private int waterIndexCount;

    // targets
    private ID3D11Texture2D shadowTex = null!, reflTex = null!, reflDepth = null!, msaaColor = null!, msaaDepth = null!, sceneTex = null!;
    private ID3D11DepthStencilView shadowDsv = null!, reflDsv = null!, msaaDsv = null!;
    private ID3D11RenderTargetView reflRtv = null!, msaaRtv = null!, sceneRtv = null!, outputRtv = null!;
    private ID3D11ShaderResourceView shadowSrv = null!, reflSrv = null!, sceneSrv = null!;
    private readonly List<ID3D11Texture2D> bloomTex = [], tmpTex = [];
    private readonly List<ID3D11RenderTargetView> bloomRtv = [], tmpRtv = [];
    private readonly List<ID3D11ShaderResourceView> bloomSrv = [], tmpSrv = [];
    private bool msaa;
    public ID3D11Texture2D Output { get; private set; } = null!;
    public int TrianglesPerFrame { get; private set; }

    private LvFrame frame = new(), mirrorFrame = new(), lightFrame = new();
    private readonly Vector3 chimney, boatHome;

    public LakeValleyRenderer(GpuBench bench, LakeValleyAsset asset, int width, int height)
    {
        this.bench = bench; dev = bench.Device; ctx = bench.Ctx;
        this.asset = asset; this.width = width; this.height = height;
        chimney = asset.Anchors.TryGetValue("chimney", out var c) ? c : new(asset.LakeCenterX, asset.LakeCenterY + 80, 8);
        boatHome = asset.Anchors.TryGetValue("boat", out var b) ? b : new(asset.LakeCenterX, asset.LakeCenterY, asset.WaterZ);
        BuildTargets();
        BuildPipelines();
        BuildGeometry();
    }

    // ── setup ──

    private static InputElementDescription[] ObjectLayout() =>
    [
        new("POSITION", 0, Format.R32G32B32_Float, 0, 0, InputClassification.PerVertexData, 0),
        new("NORMAL", 0, Format.R8G8B8A8_SNorm, 12, 0, InputClassification.PerVertexData, 0),
        new("COLOR", 0, Format.R8G8B8A8_UNorm, 16, 0, InputClassification.PerVertexData, 0),
        new("INSTROW", 0, Format.R32G32B32A32_Float, 0, 1, InputClassification.PerInstanceData, 1),
        new("INSTROW", 1, Format.R32G32B32A32_Float, 16, 1, InputClassification.PerInstanceData, 1),
        new("INSTROW", 2, Format.R32G32B32A32_Float, 32, 1, InputClassification.PerInstanceData, 1),
    ];

    private void BuildPipelines()
    {
        var opaque = bench.OpaqueBlend();
        var premul = bench.PremultipliedBlend();
        var obj = ObjectLayout();
        InputElementDescription[] xy = [new("POSITION", 0, Format.R32G32_Float, 0, 0, InputClassification.PerVertexData, 0)];
        InputElementDescription[] bird = [new("POSITION", 0, Format.R32G32B32A32_Float, 0, 0, InputClassification.PerVertexData, 0)];
        const string F = "LakeValley";
        objMain = bench.Pipeline(F, "lvObjectVS", "lvObjectFS", obj, opaque);
        terrainMain = bench.Pipeline(F, "lvObjectVS", "lvObjectFS", obj, opaque, "TERRAIN");
        objMirror = bench.Pipeline(F, "lvObjectVS", "lvObjectFS", obj, opaque, "MIRROR");
        terrainMirror = bench.Pipeline(F, "lvObjectVS", "lvObjectFS", obj, opaque, "MIRROR", "TERRAIN");
        depthOnly = bench.Pipeline(F, "lvDepthVS", null, obj, opaque);
        water = bench.Pipeline(F, "lvWaterVS", "lvWaterFS", xy, opaque);
        sky = bench.Pipeline(F, "lvSkyVS", "lvSkyFS", null, opaque);
        skyMirror = bench.Pipeline(F, "lvSkyVS", "lvSkyFS", null, opaque, "MIRROR");
        birds = bench.Pipeline(F, "lvBirdVS", "lvBirdFS", bird, opaque);
        smoke = bench.Pipeline(F, "lvParticleVS", "lvParticleFS", null, premul, "SMOKE");
        pollen = bench.Pipeline(F, "lvParticleVS", "lvParticleFS", null, premul);
        bright = bench.Pipeline(F, "lvPostVS", "lvDownFS", null, opaque, "THRESHOLD");
        down = bench.Pipeline(F, "lvPostVS", "lvDownFS", null, opaque);
        blur = bench.Pipeline(F, "lvPostVS", "lvBlurFS", null, opaque);
        composite = bench.Pipeline(F, "lvPostVS", "lvCompositeFS", null, opaque);

        ID3D11DepthStencilState Depth(ComparisonFunction f, bool write) => bench.Own(dev.CreateDepthStencilState(new DepthStencilDescription(true, write ? DepthWriteMask.All : DepthWriteMask.Zero, f)));
        depthLess = Depth(ComparisonFunction.Less, true);
        depthLessEqual = Depth(ComparisonFunction.LessEqual, true);
        depthTestOnly = Depth(ComparisonFunction.Less, false);
        depthOff = bench.Own(dev.CreateDepthStencilState(new DepthStencilDescription(false, DepthWriteMask.Zero, ComparisonFunction.Always)));

        for (int ccw = 0; ccw < 2; ccw++)
            for (int cull = 0; cull < 2; cull++)
                raster[ccw, cull] = bench.Own(dev.CreateRasterizerState(new RasterizerDescription(cull == 1 ? CullMode.Back : CullMode.None, FillMode.Solid)
                    { FrontCounterClockwise = ccw == 1, DepthClipEnable = true, MultisampleEnable = true }));
        shadowRaster = bench.Own(dev.CreateRasterizerState(new RasterizerDescription(CullMode.None, FillMode.Solid)
            { FrontCounterClockwise = true, DepthBias = 4, SlopeScaledDepthBias = 2, DepthClipEnable = true }));
        shadowRasterCull = bench.Own(dev.CreateRasterizerState(new RasterizerDescription(CullMode.Back, FillMode.Solid)
            { FrontCounterClockwise = true, DepthBias = 4, SlopeScaledDepthBias = 2, DepthClipEnable = true }));
        linear = bench.Own(dev.CreateSamplerState(new SamplerDescription(Filter.MinMagMipLinear, TextureAddressMode.Clamp, TextureAddressMode.Clamp,
            TextureAddressMode.Clamp, 0, 1, ComparisonFunction.Never, new Color4(0, 0, 0, 0), 0, float.MaxValue)));
        shadowSampler = bench.Own(dev.CreateSamplerState(new SamplerDescription(Filter.ComparisonMinMagLinearMipPoint, TextureAddressMode.Clamp,
            TextureAddressMode.Clamp, TextureAddressMode.Clamp, 0, 1, ComparisonFunction.LessEqual, new Color4(1, 1, 1, 1), 0, float.MaxValue)));
        frameCb = bench.ConstantBuffer(Marshal.SizeOf<LvFrame>());
        drawCb = bench.ConstantBuffer(Marshal.SizeOf<LvDraw>());
        postCb = bench.ConstantBuffer(Marshal.SizeOf<LvPost>());
    }

    private GpuMesh Upload(LakeValleyAsset.Mesh m)
    {
        var v = bench.Buffer(m.Vertices, BindFlags.VertexBuffer);
        if (m.IsPolar)
        {
            var idx = PolarIndices(m.PolarSegments, m.PolarRings);
            return new GpuMesh(v, bench.Buffer(idx, BindFlags.IndexBuffer), idx.Length, Format.R32_UInt, m.Height);
        }
        return new GpuMesh(v, bench.Buffer(m.Indices!, BindFlags.IndexBuffer), m.IndexCount, Format.R16_UInt, m.Height);
    }

    /// <summary>Centre vertex + rings × segments, matching the exporter's vertex order.</summary>
    private static uint[] PolarIndices(int n, int rings)
    {
        var o = new List<uint>(3 * n + 6 * n * (rings - 1));
        for (int j = 0; j < n; j++) { o.Add(0); o.Add((uint)(1 + j)); o.Add((uint)(1 + (j + 1) % n)); }
        for (int r = 0; r < rings - 1; r++)
        {
            int a = 1 + r * n, b = a + n;
            for (int j = 0; j < n; j++)
            {
                int j1 = (j + 1) % n;
                o.Add((uint)(a + j)); o.Add((uint)(b + j)); o.Add((uint)(b + j1)); o.Add((uint)(a + j)); o.Add((uint)(b + j1)); o.Add((uint)(a + j1));
            }
        }
        return o.ToArray();
    }

    /// <summary>{pos, euler XYZ, scale} → transform rows (Blender XYZ euler: R = Rz·Ry·Rx).</summary>
    private static float[] InstanceRows(float[] t, int count)
    {
        var o = new float[count * 12];
        for (int i = 0; i < count; i++)
        {
            int k = i * 9;
            float cx = MathF.Cos(t[k + 3]), sx = MathF.Sin(t[k + 3]), cy = MathF.Cos(t[k + 4]), sy = MathF.Sin(t[k + 4]), cz = MathF.Cos(t[k + 5]), sz = MathF.Sin(t[k + 5]);
            float kx = t[k + 6], ky = t[k + 7], kz = t[k + 8];
            int b = i * 12;
            o[b] = cz * cy * kx; o[b + 1] = (cz * sy * sx - sz * cx) * ky; o[b + 2] = (cz * sy * cx + sz * sx) * kz; o[b + 3] = t[k];
            o[b + 4] = sz * cy * kx; o[b + 5] = (sz * sy * sx + cz * cx) * ky; o[b + 6] = (sz * sy * cx - cz * sx) * kz; o[b + 7] = t[k + 1];
            o[b + 8] = -sy * kx; o[b + 9] = cy * sx * ky; o[b + 10] = cy * cx * kz; o[b + 11] = t[k + 2];
        }
        return o;
    }

    private void BuildGeometry()
    {
        var meshes = asset.Meshes.Select(Upload).ToList();
        GpuMesh Mesh(string name) => meshes[asset.MeshIndex(name)];
        var identity = bench.Buffer(new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 }, BindFlags.VertexBuffer);
        var terrain = new Batch(Mesh("terrain"), identity, 1) { Cull = true, IsTerrain = true };
        // instanced groups: sway (m at the top) · flutter · tint · casts shadow · culls back faces
        var looks = new Dictionary<string, (float sway, float flutter, float tint, bool shadow, bool cull)>
        {
            ["pine_a"] = (0.45f, 0.06f, 0.18f, true, false), ["pine_b"] = (0.45f, 0.06f, 0.18f, true, false),
            ["broadleaf"] = (0.55f, 0.10f, 0.25f, true, false), ["pine_far"] = (0.30f, 0, 0.15f, false, false),
            ["reeds"] = (0.25f, 0.03f, 0.20f, false, false),
        };
        var farMesh = Mesh("pine_far");
        var trees = new HashSet<string> { "pine_a", "pine_b", "broadleaf", "pine_far" };
        mainBatches.Add(terrain); shadowBatches.Add(terrain); mirrorBatches.Add(terrain);
        for (int gi = 0; gi < asset.Groups.Count; gi++)
        {
            var g = asset.Groups[gi];
            var look = looks.TryGetValue(g.Name, out var l) ? l : (0, 0, 0.12f, true, true);   // rocks
            var inst = bench.Buffer(InstanceRows(g.Transforms, g.Count), BindFlags.VertexBuffer);
            var b = new Batch(meshes[g.Mesh], inst, g.Count) { Sway = look.sway, Flutter = look.flutter, Tint = look.tint, Seed = gi * 17.3f, Cull = look.cull };
            mainBatches.Add(b);
            if (look.shadow) shadowBatches.Add(b);
            // the water only needs silhouettes: every tree is reflected with the distant-pine mesh
            if (trees.Contains(g.Name))
                mirrorBatches.Add(new Batch(farMesh, inst, g.Count) { Sway = look.sway, Tint = look.tint, Seed = gi * 17.3f });
        }
        foreach (var name in new[] { "cabin", "dock" })
        {
            var b = new Batch(Mesh(name), identity, 1);
            mainBatches.Add(b); shadowBatches.Add(b); mirrorBatches.Add(b);
        }
        boatInstances = bench.Own(dev.CreateBuffer(new BufferDescription(48u, BindFlags.VertexBuffer, ResourceUsage.Default)));
        var boat = new Batch(Mesh("boat"), boatInstances, 1);
        mainBatches.Add(boat); shadowBatches.Add(boat); mirrorBatches.Add(boat);

        // water grid
        float x0 = asset.LakeCenterX - asset.LakeRadiusX * 1.3f, y0 = asset.LakeCenterY - asset.LakeRadiusY * 1.35f;
        float dx = asset.LakeRadiusX * 2.6f / WaterGridX, dy = asset.LakeRadiusY * 2.7f / WaterGridY;
        var xy = new List<float>();
        for (int j = 0; j <= WaterGridY; j++) for (int i = 0; i <= WaterGridX; i++) { xy.Add(x0 + i * dx); xy.Add(y0 + j * dy); }
        var idx = new List<ushort>();
        int row = WaterGridX + 1;
        for (int j = 0; j < WaterGridY; j++)
            for (int i = 0; i < WaterGridX; i++)
            {
                int a = j * row + i;
                idx.AddRange([(ushort)a, (ushort)(a + 1), (ushort)(a + row + 1), (ushort)a, (ushort)(a + row + 1), (ushort)(a + row)]);
            }
        waterXY = bench.Buffer(xy.ToArray(), BindFlags.VertexBuffer);
        waterIndices = bench.Buffer(idx.ToArray(), BindFlags.IndexBuffer);
        waterIndexCount = idx.Count;
        // birds: body + two wings; w = how much the vertex flaps
        birdVerts = bench.Buffer(new float[]
        {
            0, 0.45f, 0, 0, -0.07f, -0.35f, 0, 0, 0.07f, -0.35f, 0, 0,
            -0.04f, 0.15f, 0, 0, -0.04f, -0.12f, 0, 0, -0.95f, -0.05f, 0, 1,
            0.04f, 0.15f, 0, 0, 0.95f, -0.05f, 0, 1, 0.04f, -0.12f, 0, 0,
        }, BindFlags.VertexBuffer);

        static int Tris(List<Batch> list) => list.Sum(b => b.Mesh.IndexCount / 3 * b.Count);
        TrianglesPerFrame = Tris(mainBatches) + Tris(shadowBatches) + Tris(mirrorBatches) + 2 * WaterGridX * WaterGridY + Birds * 3;
    }

    private void BuildTargets()
    {
        msaa = bench.SupportsMultisample4;
        int ss = msaa ? 4 : 1;
        shadowTex = bench.Texture(Format.R32_Typeless, ShadowSize, ShadowSize, BindFlags.DepthStencil | BindFlags.ShaderResource);
        shadowDsv = bench.Own(dev.CreateDepthStencilView(shadowTex, new DepthStencilViewDescription(shadowTex, DepthStencilViewDimension.Texture2D, Format.D32_Float)));
        shadowSrv = bench.Own(dev.CreateShaderResourceView(shadowTex, new ShaderResourceViewDescription(shadowTex, ShaderResourceViewDimension.Texture2D, Format.R32_Float)));
        reflTex = bench.Texture(Format.R8G8B8A8_UNorm, width / 2, height / 2, BindFlags.RenderTarget | BindFlags.ShaderResource);
        reflRtv = bench.Own(dev.CreateRenderTargetView(reflTex));
        reflSrv = bench.Own(dev.CreateShaderResourceView(reflTex));
        reflDepth = bench.Texture(Format.D32_Float, width / 2, height / 2, BindFlags.DepthStencil);
        reflDsv = bench.Own(dev.CreateDepthStencilView(reflDepth));
        sceneTex = bench.Texture(Format.R8G8B8A8_UNorm, width, height, BindFlags.RenderTarget | BindFlags.ShaderResource);
        sceneRtv = bench.Own(dev.CreateRenderTargetView(sceneTex));
        sceneSrv = bench.Own(dev.CreateShaderResourceView(sceneTex));
        msaaColor = bench.Texture(Format.R8G8B8A8_UNorm, width, height, BindFlags.RenderTarget, ss);
        msaaRtv = bench.Own(dev.CreateRenderTargetView(msaaColor));
        msaaDepth = bench.Texture(Format.D32_Float, width, height, BindFlags.DepthStencil, ss);
        msaaDsv = bench.Own(dev.CreateDepthStencilView(msaaDepth));
        for (int l = 0; l < 3; l++)
        {
            int w = width >> (l + 1), h = height >> (l + 1);
            var a = bench.Texture(Format.R8G8B8A8_UNorm, w, h, BindFlags.RenderTarget | BindFlags.ShaderResource);
            var b = bench.Texture(Format.R8G8B8A8_UNorm, w, h, BindFlags.RenderTarget | BindFlags.ShaderResource);
            bloomTex.Add(a); tmpTex.Add(b);
            bloomRtv.Add(bench.Own(dev.CreateRenderTargetView(a))); tmpRtv.Add(bench.Own(dev.CreateRenderTargetView(b)));
            bloomSrv.Add(bench.Own(dev.CreateShaderResourceView(a))); tmpSrv.Add(bench.Own(dev.CreateShaderResourceView(b)));
        }
        Output = bench.Texture(Format.R8G8B8A8_UNorm, width, height, BindFlags.RenderTarget | BindFlags.ShaderResource);
        outputRtv = bench.Own(dev.CreateRenderTargetView(Output));
    }

    // ── per frame ──

    public void Encode(float t)
    {
        Animate(t);
        ShadowPass();
        ReflectionPass();
        MainPass();
        PostPass();
    }

    private void Animate(float t)
    {
        float th = t % LoopS / LoopS * 2 * MathF.PI;
        float cx = asset.LakeCenterX, cy = asset.LakeCenterY, wz = asset.WaterZ;
        // camera: a lap of the lake, skimming 1–12 m above the water, looking out at the shore
        float rx = asset.LakeRadiusX * 0.58f, ry = asset.LakeRadiusY * 0.53f;
        var cam = new Vector3(cx + rx * MathF.Cos(th), cy + ry * MathF.Sin(th), wz + 6.5f + 5.0f * MathF.Sin(2 * th + 0.7f) + 1.0f * MathF.Sin(5 * th));
        var tang = Vector2.Normalize(new Vector2(-rx * MathF.Sin(th), ry * MathF.Cos(th)));
        var outv = Vector2.Normalize(new Vector2(ry * MathF.Cos(th), rx * MathF.Sin(th)));
        float phi = (float)((38.0 + 18.0 * Math.Sin(th)) * Math.PI / 180);
        var d = tang * MathF.Cos(phi) + outv * MathF.Sin(phi);
        float pitch = (float)((3.0 + 3.0 * Math.Sin(3.0 * th)) * Math.PI / 180);
        float roll = (float)(4.0 * Math.Sin(2.0 * th) * Math.PI / 180);
        // up = Z tilted toward the camera's right by the bank angle
        var up = new Vector3(-d.Y * MathF.Sin(roll), d.X * MathF.Sin(roll), MathF.Cos(roll));
        var view = Mat4.LookAt(cam, cam + new Vector3(d.X * 300, d.Y * 300, 300 * MathF.Tan(pitch)), up);
        var proj = Mat4.Perspective(50, (float)width / height, 0.5f, 6000);
        var viewProj = proj * view;

        // sun: late afternoon, drifting a little over the lap so shadows move
        float el = (float)((24.0 + 5.0 * Math.Sin(th + 1.0)) * Math.PI / 180);
        float az = (float)((-105.0 + 20.0 * Math.Sin(th)) * Math.PI / 180);
        var sun = new Vector3(MathF.Sin(az) * MathF.Cos(el), MathF.Cos(az) * MathF.Cos(el), MathF.Sin(el));
        // wind: veering, gusty
        float wa = 0.6f + 0.3f * MathF.Sin(th);
        var wind = new Vector4(MathF.Cos(wa), MathF.Sin(wa), 0.55f + 0.3f * MathF.Sin(3 * th + 0.4f), 0);
        // sun shadow: ortho box over the lake, refit as the sun moves
        float fz = wz + 30;
        var lightView = Mat4.LookAt(new Vector3(cx + sun.X * 1200, cy + sun.Y * 1200, fz + sun.Z * 1200), new Vector3(cx, cy, fz), new Vector3(0, 0, 1));
        float e = ShadowExtent;
        var lightViewProj = Mat4.Ortho(-e, e, -e, e, 200, 2400) * lightView;

        frame.ViewProj = viewProj;
        frame.InvViewProj = viewProj.Inverse();
        frame.ShadowMat = Mat4.ShadowBias * lightViewProj;
        frame.SunDir = new Vector4(sun, 0);
        frame.SunColor = new Vector4(1.0f * 3.1f, 0.83f * 3.1f, 0.64f * 3.1f, 0);
        frame.CamPos = new Vector4(cam, 1);
        frame.Wind = wind;
        frame.Lake = new Vector4(cx, cy, wz, 0);
        frame.CamRight = new Vector4(view.Row0, 0);
        frame.CamUp = new Vector4(view.Row1, 0);
        frame.InvViewport = new Vector2(1f / width, 1f / height);
        frame.Time = t;
        mirrorFrame = frame;
        mirrorFrame.ViewProj = proj * view * Mat4.MirrorZ(wz);   // the world mirrored in the water plane
        mirrorFrame.InvViewport = new Vector2(2f / width, 2f / height);
        lightFrame = frame;
        lightFrame.ViewProj = lightViewProj;

        // rowboat: drifts a slow circle off its mooring, bobbing and rolling on the waves
        float ba = th + 1.3f;
        float bx = boatHome.X + 16 * MathF.Cos(ba), by = boatHome.Y + 10 * MathF.Sin(ba);
        float heading = MathF.Atan2(10 * MathF.Cos(ba), -16 * MathF.Sin(ba)) - MathF.PI / 2;
        float bob = 0.06f * MathF.Sin(t * 1.3f), rollB = 0.05f * MathF.Sin(t * 0.9f), pitchB = 0.035f * MathF.Sin(t * 1.1f + 1);
        float ch = MathF.Cos(heading), sh = MathF.Sin(heading), cr = MathF.Cos(rollB), sr = MathF.Sin(rollB), cp = MathF.Cos(pitchB), sp = MathF.Sin(pitchB);
        // R = Rz(heading) · Rx(pitch) · Ry(roll)
        float[] rows =
        [
            ch * cr - sh * sp * sr, -sh * cp, ch * sr + sh * sp * cr, bx,
            sh * cr + ch * sp * sr, ch * cp, sh * sr - ch * sp * cr, by,
            -cp * sr, sp, cp * cr, wz - 0.06f + bob,
        ];
        ctx.UpdateSubresource(rows, boatInstances);
    }

    private void SetFrame(in LvFrame f)
    {
        ctx.UpdateSubresource(in f, frameCb);
        ctx.VSSetConstantBuffer(0, frameCb);
        ctx.PSSetConstantBuffer(0, frameCb);
    }

    private void SetPipe(Pipe p)
    {
        ctx.IASetInputLayout(p.Layout);
        ctx.VSSetShader(p.VS);
        ctx.PSSetShader(p.PS);
        ctx.OMSetBlendState(p.Blend);
    }

    private void Draw(Batch b, bool ccw, bool shadow = false)
    {
        var d = new LvDraw { MeshHeight = Math.Max(b.Mesh.Height, 0.1f), Sway = b.Sway, Flutter = b.Flutter, Tint = b.Tint, Seed = b.Seed };
        ctx.UpdateSubresource(in d, drawCb);
        ctx.VSSetConstantBuffer(1, drawCb);
        ctx.RSSetState(shadow ? (b.Cull ? shadowRasterCull : shadowRaster) : raster[ccw ? 1 : 0, b.Cull ? 1 : 0]);
        ctx.IASetVertexBuffers(0, [b.Mesh.Vertices, b.Instances], [LakeValleyAsset.VertexBytes, 48], [0, 0]);
        ctx.IASetIndexBuffer(b.Mesh.Indices, b.Mesh.IndexFormat, 0);
        ctx.DrawIndexedInstanced((uint)b.Mesh.IndexCount, (uint)b.Count, 0, 0, 0);
    }

    private void DrawScene(Pipe terrain, Pipe obj, in LvFrame f, List<Batch> list, bool ccw)
    {
        SetFrame(f);
        ctx.PSSetShaderResource(1, shadowSrv);
        ctx.PSSetSampler(0, linear);
        ctx.PSSetSampler(1, shadowSampler);
        ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        ctx.OMSetDepthStencilState(depthLess);
        SetPipe(terrain);
        foreach (var b in list) if (b.IsTerrain) Draw(b, ccw);
        SetPipe(obj);
        foreach (var b in list) if (!b.IsTerrain) Draw(b, ccw);
    }

    private void DrawSky(Pipe p)
    {
        SetPipe(p);
        ctx.RSSetState(raster[1, 0]);
        ctx.OMSetDepthStencilState(depthLessEqual);
        ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        ctx.Draw(3, 0);
    }

    private void ShadowPass()
    {
        ctx.PSSetShaderResource(1, null);   // the map is about to be written
        ctx.OMSetRenderTargets((ID3D11RenderTargetView?)null, shadowDsv);
        ctx.RSSetViewport(0, 0, ShadowSize, ShadowSize);
        ctx.ClearDepthStencilView(shadowDsv, DepthStencilClearFlags.Depth, 1, 0);
        ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        SetPipe(depthOnly);
        ctx.OMSetDepthStencilState(depthLess);
        SetFrame(lightFrame);
        foreach (var b in shadowBatches) Draw(b, true, shadow: true);
    }

    private void ReflectionPass()
    {
        ctx.OMSetRenderTargets(reflRtv, reflDsv);
        ctx.RSSetViewport(0, 0, width / 2, height / 2);
        ctx.ClearRenderTargetView(reflRtv, new Color4(0, 0, 0, 0));
        ctx.ClearDepthStencilView(reflDsv, DepthStencilClearFlags.Depth, 1, 0);
        DrawScene(terrainMirror, objMirror, mirrorFrame, mirrorBatches, false);   // mirroring flips the winding
        DrawSky(skyMirror);
    }

    private void MainPass()
    {
        ctx.PSSetShaderResource(2, null);
        ctx.OMSetRenderTargets(msaaRtv, msaaDsv);
        ctx.RSSetViewport(0, 0, width, height);
        ctx.ClearRenderTargetView(msaaRtv, new Color4(0, 0, 0, 0));
        ctx.ClearDepthStencilView(msaaDsv, DepthStencilClearFlags.Depth, 1, 0);
        DrawScene(terrainMain, objMain, frame, mainBatches, true);

        SetPipe(water);
        ctx.RSSetState(raster[1, 0]);
        ctx.PSSetShaderResource(2, reflSrv);
        ctx.IASetVertexBuffers(0, [waterXY], [8], [0]);
        ctx.IASetIndexBuffer(waterIndices, Format.R16_UInt, 0);
        ctx.DrawIndexed((uint)waterIndexCount, 0, 0);

        DrawSky(sky);

        SetPipe(birds);
        ctx.RSSetState(raster[1, 0]);
        ctx.OMSetDepthStencilState(depthLess);
        ctx.IASetVertexBuffers(0, [birdVerts], [16], [0]);
        ctx.DrawInstanced(9, Birds, 0, 0);

        // translucent last: premultiplied alpha, depth-tested but not written
        ctx.OMSetDepthStencilState(depthTestOnly);
        ctx.IASetInputLayout(null);
        foreach (var (p, origin, count) in new[] { (smoke, chimney, Smoke), (pollen, new Vector3(frame.CamPos.X, frame.CamPos.Y, frame.CamPos.Z), Pollen) })
        {
            var f = frame;
            f.Origin = new Vector4(origin, 1);
            SetFrame(f);
            SetPipe(p);
            ctx.DrawInstanced(6, (uint)count, 0, 0);
        }
        ctx.ResolveSubresource(sceneTex, 0, msaaColor, 0, Format.R8G8B8A8_UNorm);
    }

    private void Fullscreen(Pipe p, ID3D11RenderTargetView target, int w, int h, ID3D11ShaderResourceView[] sources, LvPost post)
    {
        ctx.PSSetShaderResources(0, new ID3D11ShaderResourceView[4]);   // unbind before the target changes
        ctx.OMSetRenderTargets(target);
        ctx.RSSetViewport(0, 0, w, h);
        SetPipe(p);
        ctx.RSSetState(raster[1, 0]);
        ctx.OMSetDepthStencilState(depthOff);
        ctx.UpdateSubresource(in post, postCb);
        ctx.PSSetConstantBuffer(2, postCb);
        ctx.PSSetShaderResources(0, sources);
        ctx.PSSetSampler(0, linear);
        ctx.IASetPrimitiveTopology(PrimitiveTopology.TriangleList);
        ctx.Draw(3, 0);
    }

    private void PostPass()
    {
        ctx.IASetInputLayout(null);
        var src = sceneSrv;
        int sw = width, sh = height;
        for (int l = 0; l < 3; l++)
        {
            int w = (int)bloomTex[l].Description.Width, h = (int)bloomTex[l].Description.Height;
            Fullscreen(l == 0 ? bright : down, bloomRtv[l], w, h, [src], new LvPost { Texel = new(0.5f / sw, 0.5f / sh) });
            Fullscreen(blur, tmpRtv[l], w, h, [bloomSrv[l]], new LvPost { Step = new(1f / w, 0) });
            Fullscreen(blur, bloomRtv[l], w, h, [tmpSrv[l]], new LvPost { Step = new(0, 1f / h) });
            src = bloomSrv[l]; sw = w; sh = h;
        }
        Fullscreen(composite, outputRtv, width, height, [sceneSrv, bloomSrv[0], bloomSrv[1], bloomSrv[2]], new LvPost { Time = frame.Time });
        ctx.PSSetShaderResources(0, new ID3D11ShaderResourceView[4]);
        ctx.OMSetRenderTargets((ID3D11RenderTargetView?)null);
    }

    public void Dispose() { /* every resource is owned by the bench and released with it */ }
}
