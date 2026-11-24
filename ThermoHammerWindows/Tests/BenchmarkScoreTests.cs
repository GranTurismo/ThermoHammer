using ThermoHammer.Engine;

namespace ThermoHammer.Tests;

/// <summary>Scoring version 5 and the signed payload — Android's BenchmarkScoreTest, with the same numbers.</summary>
public class BenchmarkScoreTests
{
    [Fact]
    public void Points_scale_linearly_with_fps_and_the_tests_land_on_the_same_scale()
    {
        // the Snapdragon 8 Elite reference (Performance mode): 129.85, 28.98 and 14.12 fps
        Assert.Equal(6000, BenchmarkScore.Points(GpuTest.LakeValley, 129.85));
        Assert.Equal(5999, BenchmarkScore.Points(GpuTest.NeonCity, 28.98));
        Assert.Equal(6000, BenchmarkScore.Points(GpuTest.NeonCityRT, 14.12));
        Assert.Equal(12000, BenchmarkScore.Points(GpuTest.NeonCity, 6000 * 2 / GpuTest.NeonCity.Weight()));
        Assert.Equal(6000, BenchmarkScore.Overall(6000, 6000, 6000));
    }

    /// <summary>
    /// The reference phone (Honor VKP-NX9, Snapdragon 8 Elite, Performance mode): the median of every measurement over its 11 runs of
    /// 2026-11-23 scores 6000 on each test — the same numbers pin Benchmark.kt, BenchmarkScoring.cs and BenchmarkModels.swift, so the
    /// copies of the references can't drift apart.
    /// </summary>
    [Fact]
    public void The_reference_phones_medians_score_6000_on_every_test()
    {
        static void Near(int points, string what) => Assert.True(points is >= 5970 and <= 6030, $"{what} scored {points}");
        foreach (var (t, v) in new[] { (CpuTest.Math, 3039.31), (CpuTest.Data, 1533.32), (CpuTest.Crypto, 1184.12), (CpuTest.Multi, 8884.08) })
            Near(CpuScore.Points(t, v), t.ToString());
        Near(MemScore.Ram(63.51, 28.26, 19.71, 133.94), "RAM");
        Near(MemScore.Storage(1780.0, 1309.0, 24722.0, 13375.0), "storage");
        Near(MemScore.Db(15700.0, 389000.0, 43800.0), "database");
        Near(BenchmarkScore.Points(GpuTest.LakeValley, 129.85), "Lake Valley");
        Near(BenchmarkScore.Points(GpuTest.NeonCity, 28.98), "Neon City");
        Near(BenchmarkScore.Points(GpuTest.NeonCityRT, 14.12), "Neon City RT");
        Near(UxScore.WebPoints(new WebResult(3061.0, 4818.0, 3061.0, 4898.0, 4001.7)), "web");
        Near(UxScore.ScrollPoints(66558.0), "scroll");
        Near(UxScore.GalleryPoints(new GalleryResult(229.26, 167.78, 173.55)), "gallery");
        Near(UxScore.PhotoPoints(349.95), "photo");
        Near(UxScore.VideoPoints(new VideoResult(
        [
            new(VideoClip.Avc4k, 269.0, "hw", true), new(VideoClip.Hevc4k, 331.4, "hw", true), new(VideoClip.Vp94k, 309.6, "hw", true),
            new(VideoClip.Av14k, 274.2, "hw", true), new(VideoClip.Hevc8k, 91.13, "hw", true), new(VideoClip.Av18k, 75.02, "hw", true),
        ])), "video");
        Near(UxScore.QrPoints(1171.51), "QR");
        // export: the reference phone has two of the three 4K encoders and no 8K one — two thirds of the 4K part
        Assert.Equal(3000, UxScore.ExportPoints(new ExportResult(
        [
            new(ExportJob.Avc4k, 136.3, 40, "hw", true), new(ExportJob.Hevc4k, 136.3, 40, "hw", true),
            new(ExportJob.Av14k, 0, 0, null, false), new(ExportJob.Hevc8k, 0, 0, null, false),
        ])));
        Near(AiScore.Accel(AiAccel.Cpu, [543.63, 28.67, 7.73, 4.59, 64.54, 3967.59]), "AI CPU");
        Near(AiScore.Accel(AiAccel.Gpu, [280.7, 36.7, 16.6, 38.8, 74.2, 582.0]), "AI GPU");
        Near(AiScore.Accel(AiAccel.Npu, [1725.64, 485.03, 214.56, 689.25, 986.01, 2154.38]), "AI NPU");
        Near(LlmScore.Engine(LlmAccel.Cpu, 160.0, 55.0), "LLM CPU");
        Near(LlmScore.Engine(LlmAccel.Gpu, 890.0, 32.0), "LLM GPU");
    }

    [Fact]
    public void LLM_version_5_the_CPU_and_the_GPU_against_their_own_references_each_half()
    {
        // the reference phone's rates on the CPU and the GPU: 6000 each, 6000 in all (the NPU isn't scored on any platform)
        var reference = new LlmResult([new LlmRun(LlmAccel.Cpu, LlmScore.RefCpuPrefill, LlmScore.RefCpuDecode, 6),
            new LlmRun(LlmAccel.Gpu, LlmScore.RefGpuPrefill, LlmScore.RefGpuDecode)], "Test GPU", 9, 8);
        Assert.Equal(6000, reference.Points(LlmAccel.Cpu));
        Assert.Equal(6000, reference.Points(LlmAccel.Gpu));
        Assert.Equal(6000, reference.Score);                     // (6000 + 6000) / 2
        // an engine is the geometric mean of its two phases: twice the prompt rate and half the reply rate is still 6000
        Assert.Equal(6000, LlmScore.Engine(LlmAccel.Cpu, 2 * LlmScore.RefCpuPrefill, LlmScore.RefCpuDecode / 2));
        // no usable GPU: the CPU's half only
        var cpuOnly = new LlmResult([new LlmRun(LlmAccel.Cpu, LlmScore.RefCpuPrefill / 2, LlmScore.RefCpuDecode / 2),
            new LlmRun(LlmAccel.Gpu, 0, 0, Error: "llama.cpp found no Vulkan GPU it can run on")], null, 5, 4);
        Assert.Equal(3000, cpuOnly.Points(LlmAccel.Cpu));
        Assert.Equal(1500, cpuOnly.Score);                       // (3000 + 0) / 2
        Assert.Equal(0, LlmScore.Engine(LlmAccel.Gpu, 120, 0));  // no reply: 0
        // shown, not scored: the best engine's points at each phase, each against its own engine's reference
        var mixed = new LlmResult([new LlmRun(LlmAccel.Cpu, LlmScore.RefCpuPrefill / 2, LlmScore.RefCpuDecode),
            new LlmRun(LlmAccel.Gpu, LlmScore.RefGpuPrefill, LlmScore.RefGpuDecode / 2)], "Test GPU", 9, 8);
        Assert.Equal(6000, mixed.PrefillPoints);
        Assert.Equal(6000, mixed.DecodePoints);
        Assert.Equal(LlmScore.RefGpuPrefill, mixed.Prefill);
        Assert.Equal(LlmScore.RefCpuDecode, mixed.Decode);
        var total = new BenchmarkResult { Llm = cpuOnly };
        Assert.Equal(1500, total.Total);
    }

    [Fact]
    public void A_saved_run_is_scored_by_the_same_rule()
    {
        var r = Fixtures.Result();
        r.Llm = new LlmResult([new LlmRun(LlmAccel.Cpu, 160, 55, 8), new LlmRun(LlmAccel.Gpu, 445, 32)], "Test GPU", 12.5, 8);
        var rec = BenchmarkRecord.From(r, "11 24H2", "26100.1", "ThinkPad X1");
        Assert.Equal(r.LlmScore, rec.Sections().Llm);
        Assert.Equal(LlmScore.Overall(6000, BenchmarkScore.Round(6000 / Math.Sqrt(2))), rec.Sections().Llm);   // the GPU at half the prompt rate
    }

    /// <summary>Android's "canonical string is what the server re-derives": the same payload gives the same string, field for field.</summary>
    [Fact]
    public void Canonical_string_is_what_the_server_re_derives()
    {
        var p = new BenchmarkPayload
        {
            SessionId = 7, Hash = "", BenchmarkVersion = 5, DeviceManufacturer = "Honor", DeviceModel = "Honor 600 Pro",
            OsVersion = "16", GpuName = "Adreno (TM) 830", Test1Fps = BenchmarkScore.Round2(123.456),
            Test1LowFps = 98.0, Test2Fps = BenchmarkScore.Round2(29.994), Test2LowFps = 24.5,
            Test3Fps = 21.5, Test3LowFps = 17.25, RayTracing = true,
            WebLayout = 180.0, WebCanvas = 1400.5, WebCss = 700.0, WebSvg = 900.0, WebJs = 912.34,
            QrRate = 75.2, MlkitQrRate = null, CpuMath = 61.5, CpuData = 40.25, CpuCrypto = 58.0, CpuMulti = 301.75,
            ScrollSpeed = 9100.5, ScrollDropped = 3.4, GalleryJpeg = 61.2, GalleryWebp = 38.75, GalleryAvif = 0.0,
            PhotoEdit = 118.3, VideoAvc = 241.5, VideoHevc = 180.25, VideoVp9 = 0.0, VideoAv1 = 33.1,
            Video8kHevc = 64.5, Video8kAv1 = 0.0, VideoHardware = 19,
            ExportAvc = 150.25, ExportHevc = 140.0, ExportAv1 = 0.0, Export8kHevc = 31.5,
            ExportAvcPsnr = 39.87, ExportHevcPsnr = 41.2, ExportAv1Psnr = 0.0, Export8kHevcPsnr = 40.05, ExportHardware = 11,
            AiClassifyCpu = 301.5, AiClassifyGpu = 640.25, AiClassifyNpu = 4100.0, AiNpuRuntime = "Qualcomm QNN",
            MemRead = 52.31, MemWrite = 41.0, MemCopy = 26.5, MemLatency = 142.75, StorageSeqRead = 3120.4,
            StorageSeqWrite = 2050.0, StorageRandomRead = 61234.5, StorageRandomWrite = 48000.0, StorageDirect = true,
            DbInsert = 812.25, DbUpdate = 790.0, DbQuery = 5120.5,
            LlmPrefillCpu = 0.0, LlmDecodeCpu = 0.0, LlmPrefillGpu = 0.0, LlmDecodeGpu = 0.0, LlmGpu = null, LlmFreeGb = 0.0,
            ValidityFlags = 0,
        };
        Assert.Equal("bench|5|Honor|Honor 600 Pro|16|Adreno (TM) 830|123.46|98.00|29.99|24.50|21.50|17.25|1|" +
            "180.00|1400.50|700.00|900.00|912.34|75.20|0.00|61.50|40.25|58.00|301.75|" +
            "9100.50|3.40|61.20|38.75|0.00|118.30|241.50|180.25|0.00|33.10|64.50|0.00|19|" +
            "150.25|140.00|0.00|31.50|39.87|41.20|0.00|40.05|11|" +
            "301.50|640.25|4100.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|Qualcomm QNN|" +
            "52.31|41.00|26.50|142.75|3120.40|2050.00|61234.50|48000.00|1|812.25|790.00|5120.50|" +
            "0.00|0.00|0.00|0.00||0.00|0.00|0.00||0", p.Canonical());
        // the NPU's three fields sit after the free memory and before the validity flags, as on Android and the server
        p.LlmPrefillCpu = 118.4; p.LlmDecodeCpu = 21.35; p.LlmPrefillGpu = 402.0; p.LlmDecodeGpu = 19.1;
        p.LlmGpu = "QUALCOMM Adreno(TM) 830"; p.LlmFreeGb = 9.42; p.LlmPrefillNpu = 5723.514; p.LlmDecodeNpu = 52.86;
        p.LlmNpu = "LiteRT-LM 0.19.1 · Gemma3-1B-IT_q4_ekv1280_sm8750";
        Assert.EndsWith("|5120.50|118.40|21.35|402.00|19.10|QUALCOMM Adreno(TM) 830|9.42|" +
            "5723.51|52.86|LiteRT-LM 0.19.1 · Gemma3-1B-IT_q4_ekv1280_sm8750|0", p.Canonical());
    }
}
