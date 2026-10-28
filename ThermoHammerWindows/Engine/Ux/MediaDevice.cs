using System.Runtime.InteropServices;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;
using Vortice.MediaFoundation;

namespace ThermoHammer.Engine.Ux;

/// <summary>
/// A Direct3D 11 device for Media Foundation: video-capable, multithread-protected, behind a DXGI device manager the
/// source reader and the encoder use so that hardware decoders and encoders can keep frames on the GPU.
/// </summary>
public sealed class MediaDevice : IDisposable
{
    private static int started;

    public readonly ID3D11Device Device;
    public readonly ID3D11DeviceContext Context;
    public readonly IMFDXGIDeviceManager Manager;
    public readonly string AdapterName;

    public MediaDevice()
    {
        if (Interlocked.Exchange(ref started, 1) == 0) MediaFactory.MFStartup().CheckError();
        using var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();
        IDXGIAdapter1? best = null;
        string name = "";
        for (uint i = 0; factory.EnumAdapters1(i, out var a).Success; i++)
        {
            var d = a.Description1;
            if ((d.Flags & AdapterFlags.Software) != 0) { a.Dispose(); continue; }
            if (best == null || d.DedicatedVideoMemory > best.Description1.DedicatedVideoMemory) { best?.Dispose(); best = a; name = d.Description.Trim(); }
            else a.Dispose();
        }
        AdapterName = name;
        var levels = new[] { FeatureLevel.Level_11_1, FeatureLevel.Level_11_0 };
        var flags = DeviceCreationFlags.VideoSupport | DeviceCreationFlags.BgraSupport;
        D3D11.D3D11CreateDevice(best, best == null ? DriverType.Warp : DriverType.Unknown, flags, levels, out Device, out _, out Context).CheckError();
        best?.Dispose();
        using (var mt = Device.QueryInterface<ID3D11Multithread>()) mt.SetMultithreadProtected(true);
        Manager = MediaFactory.MFCreateDXGIDeviceManager();
        Manager.ResetDevice(Device);
    }

    public void Dispose()
    {
        Manager.Dispose();
        Context.Dispose();
        Device.Dispose();
    }

    /// <summary>Source reader attributes: this device's manager, hardware decoders allowed.</summary>
    public IMFAttributes ReaderAttributes()
    {
        var attrs = MediaFactory.MFCreateAttributes(4);
        // THERMOHAMMER_MF_SOFTWARE=1: no Direct3D manager, so decoders hand over memory (checks under Wine)
        if (Environment.GetEnvironmentVariable("THERMOHAMMER_MF_SOFTWARE") != "1") attrs.Set(SourceReaderAttributeKeys.D3DManager, Manager);
        attrs.Set(SinkWriterAttributeKeys.ReadwriteEnableHardwareTransforms, 1u);
        return attrs;
    }
}
