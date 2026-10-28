using System.Runtime.InteropServices;
using Vortice.Direct3D;
using Vortice.Direct3D12;
using Vortice.Dxc;
using Vortice.DXGI;
using Vortice.Mathematics;

namespace ThermoHammer.Engine.Gpu.Rt;

/// <summary>A texture and the state it is in: passes name the state they need and transition from wherever it is.</summary>
internal sealed class Tex(ID3D12Resource res, Format format, int w, int h, ResourceStates state) : IDisposable
{
    public readonly ID3D12Resource Res = res;
    public readonly Format Format = format;
    public readonly int W = w, H = h;
    public ResourceStates State = state;
    public CpuDescriptorHandle Rtv, Dsv;
    public void Dispose() => Res.Dispose();
}

/// <summary>
/// Direct3D 12 for the ray-tracing test: a device on the best DXR-capable adapter, one direct queue run frame by frame
/// (the benchmark waits for each frame), descriptor heaps filled once at start-up, a per-frame ring for constant data,
/// DXC for the shaders and the small helpers the renderer uses.
/// </summary>
internal sealed unsafe class Dx12 : IDisposable
{
    public readonly ID3D12Device5 Device;
    public readonly ID3D12CommandQueue Queue;
    public readonly ID3D12CommandAllocator Allocator;
    public readonly ID3D12GraphicsCommandList4 List;
    public readonly string AdapterName;
    public readonly RaytracingTier Tier;
    public readonly ResourceBindingTier Binding;

    private readonly ID3D12Fence fence;
    private ulong fenceValue;
    private readonly EventWaitHandle fenceEvent = new(false, EventResetMode.AutoReset);
    private readonly List<IDisposable> owned = [];

    public ID3D12DescriptorHeap SrvHeap { get; }
    private readonly ID3D12DescriptorHeap rtvHeap, dsvHeap;
    public readonly uint SrvInc, RtvInc, DsvInc;
    private int srvNext, rtvNext, dsvNext;
    public const int SrvCapacity = 4096;

    // constant data for this frame: one upload buffer, handed out 256 bytes at a time
    private readonly ID3D12Resource ring;
    private readonly byte* ringPtr;
    private int ringAt;
    public const int RingBytes = 12 << 20;

    private Dx12(ID3D12Device5 device, string name, RaytracingTier tier, ResourceBindingTier binding)
    {
        Device = device; AdapterName = name; Tier = tier; Binding = binding;
        Queue = Own(device.CreateCommandQueue(new CommandQueueDescription(CommandListType.Direct)));
        Allocator = Own(device.CreateCommandAllocator(CommandListType.Direct));
        List = Own(device.CreateCommandList<ID3D12GraphicsCommandList4>(0, CommandListType.Direct, Allocator, null));
        List.Close();
        fence = Own(device.CreateFence(0));
        SrvHeap = Own(device.CreateDescriptorHeap(new DescriptorHeapDescription(DescriptorHeapType.ConstantBufferViewShaderResourceViewUnorderedAccessView, SrvCapacity, DescriptorHeapFlags.ShaderVisible, 0)));
        rtvHeap = Own(device.CreateDescriptorHeap(new DescriptorHeapDescription(DescriptorHeapType.RenderTargetView, 64, DescriptorHeapFlags.None, 0)));
        dsvHeap = Own(device.CreateDescriptorHeap(new DescriptorHeapDescription(DescriptorHeapType.DepthStencilView, 16, DescriptorHeapFlags.None, 0)));
        SrvInc = device.GetDescriptorHandleIncrementSize(DescriptorHeapType.ConstantBufferViewShaderResourceViewUnorderedAccessView);
        RtvInc = device.GetDescriptorHandleIncrementSize(DescriptorHeapType.RenderTargetView);
        DsvInc = device.GetDescriptorHandleIncrementSize(DescriptorHeapType.DepthStencilView);
        ring = Own(device.CreateCommittedResource(HeapType.Upload, ResourceDescription.Buffer((ulong)RingBytes), ResourceStates.GenericRead));
        void* p;
        ring.Map(0, null, &p);
        ringPtr = (byte*)p;
    }

    private T Own<T>(T d) where T : IDisposable { owned.Add(d); return d; }

    /// <summary>The adapter with the most video memory that does DXR 1.1 (ray queries); failing that, the one with the most memory (`problem` says what's missing).</summary>
    public static Dx12? TryCreate(bool needRays, out string? problem)
    {
        problem = null;
        try
        {
            using var factory = DXGI.CreateDXGIFactory1<IDXGIFactory4>();
            var adapters = new List<(IDXGIAdapter1 a, string name, ulong mem)>();
            for (uint i = 0; factory.EnumAdapters1(i, out var a).Success; i++)
            {
                var d = a.Description1;
                if ((d.Flags & AdapterFlags.Software) != 0) { a.Dispose(); continue; }
                adapters.Add((a, d.Description.Trim(), d.DedicatedVideoMemory));
            }
            string? lastProblem = "no graphics adapter";
            foreach (var (a, name, _) in adapters.OrderByDescending(x => x.mem))
            {
                var r = D3D12.D3D12CreateDevice<ID3D12Device5>(a, needRays ? FeatureLevel.Level_12_0 : FeatureLevel.Level_11_0, out var dev);
                if (r.Failure || dev == null) { lastProblem = $"{name}: no Direct3D 12.0"; continue; }
                var o5 = dev.CheckFeatureSupport<FeatureDataD3D12Options5>(Vortice.Direct3D12.Feature.Options5);
                var o = dev.CheckFeatureSupport<FeatureDataD3D12Options>(Vortice.Direct3D12.Feature.Options);
                if (needRays && o5.RaytracingTier < RaytracingTier.Tier1_1)
                {
                    lastProblem = $"{name} has no DirectX Raytracing 1.1 (ray queries)";
                    dev.Dispose();
                    continue;
                }
                if (needRays && o.ResourceBindingTier < ResourceBindingTier.Tier3) { lastProblem = $"{name}: resource binding tier {o.ResourceBindingTier}"; dev.Dispose(); continue; }
                foreach (var x in adapters) x.a.Dispose();
                return new Dx12(dev, name, o5.RaytracingTier, o.ResourceBindingTier);
            }
            foreach (var x in adapters) x.a.Dispose();
            problem = lastProblem;
            return null;
        }
        catch (Exception e) { problem = e.Message; return null; }
    }

    // ── frames ──

    public void Begin()
    {
        Allocator.Reset();
        List.Reset(Allocator, null);
        ringAt = 0;
        List.SetDescriptorHeaps(1, [SrvHeap]);
    }

    public void Submit()
    {
        List.Close();
        Queue.ExecuteCommandList(List);
    }

    /// <summary>Blocks until everything submitted so far has run.</summary>
    public void Wait()
    {
        Queue.Signal(fence, ++fenceValue);
        if (fence.CompletedValue >= fenceValue) return;
        fence.SetEventOnCompletion(fenceValue, fenceEvent);
        if (!fenceEvent.WaitOne(30_000)) throw new GpuError("the GPU stopped answering");
        var reason = Device.DeviceRemovedReason;
        if (reason.Failure) throw new GpuError($"the GPU was reset ({reason.Code:X8})");
    }

    /// <summary>Copies `value` into this frame's constant ring; returns its GPU address (256-byte aligned).</summary>
    public ulong Constants<T>(in T value) where T : unmanaged
    {
        int size = (sizeof(T) + 255) & ~255;
        if (ringAt + size > RingBytes) throw new GpuError("out of constant space");
        *(T*)(ringPtr + ringAt) = value;
        ulong va = ring.GPUVirtualAddress + (ulong)ringAt;
        ringAt += size;
        return va;
    }

    /// <summary>Copies bytes into this frame's constant ring; returns the offset of the copy (256-byte aligned) in <see cref="Ring"/>.</summary>
    public int RingWrite(void* src, int bytes)
    {
        int size = (bytes + 255) & ~255;
        if (ringAt + size > RingBytes) throw new GpuError("out of constant space");
        System.Buffer.MemoryCopy(src, ringPtr + ringAt, bytes, bytes);
        int at = ringAt;
        ringAt += size;
        return at;
    }

    public ID3D12Resource Ring => ring;

    // ── descriptors ──

    public int AllocSrv(int count)
    {
        int at = srvNext;
        srvNext += count;
        if (srvNext > SrvCapacity) throw new GpuError("out of descriptors");
        return at;
    }

    public CpuDescriptorHandle SrvCpu(int index) => new(SrvHeap.GetCPUDescriptorHandleForHeapStart(), index, SrvInc);
    public GpuDescriptorHandle SrvGpu(int index) => new(SrvHeap.GetGPUDescriptorHandleForHeapStart(), index, SrvInc);

    public void NullSrv(int index) =>
        Device.CreateShaderResourceView(null!, new ShaderResourceViewDescription
        {
            Format = Format.R8G8B8A8_UNorm, ViewDimension = Vortice.Direct3D12.ShaderResourceViewDimension.Texture2D, Shader4ComponentMapping = ShaderComponentMapping.Default,
            Texture2D = new Texture2DShaderResourceView { MipLevels = 1 },
        }, SrvCpu(index));

    // ── resources ──

    public ID3D12Resource Buffer(ulong bytes, HeapType heap, ResourceStates state, ResourceFlags flags = ResourceFlags.None)
    {
        var r = Device.CreateCommittedResource(heap, ResourceDescription.Buffer(Math.Max(bytes, 256), flags), state);
        return Own(r);
    }

    public Tex Texture(Format format, int w, int h, ResourceFlags flags, ResourceStates state, int samples = 1, ClearValue? clear = null, Format? clearFormat = null)
    {
        var desc = ResourceDescription.Texture2D(format, (uint)w, (uint)h, 1, 1, (uint)samples, 0, flags);
        var r = Device.CreateCommittedResource(new HeapProperties(HeapType.Default), HeapFlags.None, desc, state, clear);
        var t = new Tex(r, format, w, h, state);
        if ((flags & ResourceFlags.AllowRenderTarget) != 0)
        {
            t.Rtv = new CpuDescriptorHandle(rtvHeap.GetCPUDescriptorHandleForHeapStart(), rtvNext++, RtvInc);
            Device.CreateRenderTargetView(r, null, t.Rtv);
        }
        if ((flags & ResourceFlags.AllowDepthStencil) != 0)
        {
            t.Dsv = new CpuDescriptorHandle(dsvHeap.GetCPUDescriptorHandleForHeapStart(), dsvNext++, DsvInc);
            Device.CreateDepthStencilView(r, new DepthStencilViewDescription
            {
                Format = Format.D32_Float, ViewDimension = samples > 1 ? DepthStencilViewDimension.Texture2DMultisampled : DepthStencilViewDimension.Texture2D,
            }, t.Dsv);
        }
        return Own(t);
    }

    public void Transition(Tex t, ResourceStates to)
    {
        if (t.State == to) return;
        List.ResourceBarrier(ResourceBarrier.BarrierTransition(t.Res, t.State, to));
        t.State = to;
    }

    public void Transition(ID3D12Resource r, ResourceStates from, ResourceStates to) => List.ResourceBarrier(ResourceBarrier.BarrierTransition(r, from, to));

    public void UavBarrier(ID3D12Resource r) => List.ResourceBarrier(ResourceBarrier.BarrierUnorderedAccessView(r));

    /// <summary>A default-heap buffer holding `data`, left readable by the shaders and the acceleration-structure builds; the staging copy goes into `staging` (freed after the next Wait).</summary>
    public ID3D12Resource StaticBuffer(ReadOnlySpan<byte> data, List<IDisposable> staging)
    {
        ulong size = (ulong)Math.Max(data.Length, 16);
        var up = Device.CreateCommittedResource(HeapType.Upload, ResourceDescription.Buffer(size), ResourceStates.GenericRead);
        staging.Add(up);
        void* p;
        up.Map(0, null, &p);
        data.CopyTo(new Span<byte>(p, data.Length));
        up.Unmap(0);
        var dst = Own(Device.CreateCommittedResource(HeapType.Default, ResourceDescription.Buffer(size), ResourceStates.CopyDest));
        List.CopyBufferRegion(dst, 0, up, 0, size);
        List.ResourceBarrier(ResourceBarrier.BarrierTransition(dst, ResourceStates.CopyDest, ResourceStates.GenericRead));
        return dst;
    }

    // ── shaders ──

    private readonly Dictionary<string, byte[]> shaders = [];

    /// <summary>One entry point of a shader file, compiled to DXIL (shader model 6.5) with its defines.</summary>
    public byte[] Shader(string file, string entry, DxcShaderStage stage, params string[] defines)
    {
        string key = $"{file}|{entry}|{string.Join(",", defines)}";
        if (shaders.TryGetValue(key, out var cached)) return cached;
        var macros = defines.Select(d => new DxcDefine { Name = d.Split('=')[0], Value = d.Contains('=') ? d.Split('=')[1] : "1" }).ToArray();
        var options = new DxcCompilerOptions { ShaderModel = DxcShaderModel.Model6_5, OptimizationLevel = 3 };   // 6.5: ray queries, nothing newer needed
        using var result = DxcCompiler.Compile(stage, GpuBench.Source(file), entry, options, file + ".hlsl", macros, null, []);
        if (!result.GetStatus().Success) throw new GpuError($"{file} {entry}: {result.GetErrors()}");
        return shaders[key] = result.GetObjectBytecodeArray();
    }

    public void Dispose()
    {
        try { Wait(); } catch (Exception) { }
        foreach (var o in Enumerable.Reverse(owned)) { try { o.Dispose(); } catch (Exception) { } }
        owned.Clear();
        fenceEvent.Dispose();
    }
}
