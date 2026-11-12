using ThermoHammer.Engine;

namespace ThermoHammer.Tests;

/// <summary>A complete, realistic benchmark run with every number at 2 decimals (what a record keeps).</summary>
internal static class Fixtures
{
    public static BenchmarkResult Result(double cpuScale = 1)
    {
        static GpuTestResult Gpu(GpuTest t, double fps) => new(t, true, fps, Math.Round(fps * 0.8, 2), 6000, 97.5f, BenchmarkScore.Points(t, fps));
        var ai = new List<AiRun>();
        double[] ips = [412.5, 21.25, 5.5, 3.12, 50.75, 3100.5];
        foreach (var (t, i) in AiTests.All.Select((t, i) => (t, i)))
        {
            ai.Add(new AiRun(t, AiAccel.Cpu, ips[i]));
            ai.Add(new AiRun(t, AiAccel.Gpu, Math.Round(ips[i] * 2.5, 2)));
            ai.Add(new AiRun(t, AiAccel.Npu, Math.Round(ips[i] * 4.25, 2)));
        }
        return new BenchmarkResult
        {
            Cpu = new CpuResult(
            [
                new(CpuTest.Math, [("MATRIX MULTIPLY", Math.Round(1600.25 * cpuScale, 2))]),
                new(CpuTest.Data, [("JSON", Math.Round(700.5 * cpuScale, 2))]),
                new(CpuTest.Crypto, [("ZLIB", Math.Round(620.75 * cpuScale, 2))]),
                new(CpuTest.Multi, [("SHA-256", Math.Round(9000.5 * cpuScale, 2))]),
            ]),
            Mem = new MemResult(new RamResult(61.25, 30.5, 20.75, 230.5), new StorageResult(2100.5, 1500.25, 31000, 14000, true, 512),
                new DbResult(10100.5, 37000.25, 21000.75)),
            Gpu = [Gpu(GpuTest.LakeValley, 140.25), Gpu(GpuTest.NeonCity, 31.5), Gpu(GpuTest.NeonCityRT, 15.75)],
            GpuName = "Test GPU",
            Ux = new UxResult(new WebResult(1400.5, 2800.25, 2000, 3200.75, 2200.5), new ScrollResult(70000, 0.5),
                new GalleryResult(130.25, 100.5, 110.75), 400.25,
                new VideoResult(
                [
                    new(VideoClip.Avc4k, 310.5, "hw", true), new(VideoClip.Hevc4k, 290.25, "hw", true), new(VideoClip.Vp94k, 280, "hw", true),
                    new(VideoClip.Av14k, 260.75, "hw", true), new(VideoClip.Hevc8k, 80.5, "hw", true), new(VideoClip.Av18k, 0, null, false, "unsupported"),
                ]),
                new ExportResult(
                [
                    new(ExportJob.Avc4k, 120.5, 31.25, "hw", true), new(ExportJob.Hevc4k, 110.25, 30.5, "hw", true),
                    new(ExportJob.Av14k, 90.75, 24.5, "hw", true), new(ExportJob.Hevc8k, 35.5, 29.75, "hw", true),
                ]),
                new QrResult(520.5, 32, 0, null, "none")),
            Ai = new AiResult(ai, "QNN"),
            Llm = new LlmResult([new LlmRun(LlmAccel.Cpu, 300.5, 15.25, 8), new LlmRun(LlmAccel.Gpu, 900.75, 40.5)], "Test GPU", 12.5, 8),
        };
    }

    public static BenchmarkRecord Record(string? os, string? build, DateTime at, double cpuScale = 1, string? model = "ThinkPad X1", int flags = 0)
    {
        var r = Result(cpuScale);
        r.ValidityFlags = flags;
        var rec = BenchmarkRecord.From(r, os, build, model);
        rec.Timestamp = at;
        return rec;
    }
}
