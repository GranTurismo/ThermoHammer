namespace ThermoHammer.Engine.Ux;

// ─────────────────────────────────────────────────────────────────────────────
// QR SCANNING — UX Test 7: the phones' frames and decoder, the same C++ (BenchCore/src/qr.cpp, zxing-cpp): 32
// camera-like 1280×720 frames of QR codes, scanned on one thread for 14 s (scored). The phones then run their system
// scanner (Vision, ML Kit) for 8 s, reported but not scored; Windows has none, so that part is left out.
// ─────────────────────────────────────────────────────────────────────────────

public sealed unsafe class QrBench : IDisposable
{
    public const double ZxingS = 14;
    public const int Width = BenchCore.QrWidth, Height = BenchCore.QrHeight;

    private readonly IntPtr qr;
    public int Count { get; }

    /// <summary>`Found`: where the scanner placed the code's corners in the frame (pixels), or null when it missed.</summary>
    public sealed record Progress(string Phase, double Fraction, double Rate, int Frame, (float x, float y)[]? Found);

    public QrBench()
    {
        qr = BenchCore.bc_qr_create(BenchCore.QrFrames, BenchCore.QrSeed);
        if (qr == IntPtr.Zero) throw new BenchException("couldn't prepare the QR frames");
        Count = BenchCore.bc_qr_count(qr);
    }

    public void Dispose() => BenchCore.bc_qr_destroy(qr);

    public string Text(int i) => BenchCore.QrText(qr, i);

    /// <summary>Frame `i` as half-resolution BGRA (640×360 greys) for the stage.</summary>
    public byte[] Preview(int i)
    {
        const int w = Width / 2, h = Height / 2;
        byte* luma = (byte*)BenchCore.bc_qr_luma(qr, i);
        var px = new byte[w * h * 4];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
            {
                byte v = luma[y * 2 * Width + x * 2];
                int o = (y * w + x) * 4;
                px[o] = px[o + 1] = px[o + 2] = v;
                px[o + 3] = 255;
            }
        return px;
    }

    public QrResult Run(Func<bool> isActive, Action<Progress> onProgress)
    {
        int decoded = 0, missed = 0, i = 0;
        float* found = stackalloc float[8];
        long t0 = Clock.Ns(), lastReport = 0, limit = (long)(ZxingS * 1e9);
        while (Clock.Ns() - t0 < limit && isActive())
        {
            bool ok = BenchCore.bc_qr_scan(qr, i % Count, found) != 0;
            if (ok) decoded++; else missed++;
            long now = Clock.Ns();
            if (now - lastReport > 120_000_000)
            {
                lastReport = now;
                onProgress(new Progress("ZXING", Math.Min(1, (double)(now - t0) / limit), decoded * 1e9 / (now - t0), i % Count,
                    ok ? Enumerable.Range(0, 4).Select(k => (found[k * 2], found[k * 2 + 1])).ToArray() : null));
            }
            i++;
        }
        double rate = decoded * 1e9 / (Clock.Ns() - t0);
        return new QrResult(rate, decoded, missed, null, "Windows", "Windows has no QR scanner of its own");
    }
}
