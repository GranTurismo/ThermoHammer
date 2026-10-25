using System.Security.Cryptography;
using System.Text;
using ThermoHammer.Api.Models;

namespace ThermoHammer.Api.Tests;

/// <summary>
/// Pure in-memory tests of the GPU benchmark submission: hashing, validation, server-side
/// scoring and the per-device leaderboard. No database — safe to run anywhere.
/// </summary>
public class BenchmarkMappingTests
{
    private static BenchmarkRequest Request(Action<BenchmarkRequest>? tweak = null)
    {
        var r = new BenchmarkRequest
        {
            SessionId = 7,
            Hash = "",
            BenchmarkVersion = 2,
            DeviceManufacturer = "Honor",
            DeviceModel = "Honor 600 Pro",
            Os = OsPlatform.Android,
            OsVersion = "16",
            GpuName = "Adreno (TM) 830",
            Test1Fps = 123.46,
            Test1LowFps = 98.0,
            Test2Fps = 29.99,
            Test2LowFps = 24.5,
            Test3Fps = 21.5,
            Test3LowFps = 17.25,
            RayTracing = true,
            WebLayout = 180,
            WebCanvas = 1400.5,
            WebCss = 700,
            WebSvg = 900,
            WebJs = 912.34,
            QrRate = 75.2,
            MlKitQrRate = null,
            CpuMath = 61.5,
            CpuData = 40.25,
            CpuCrypto = 58,
            CpuMulti = 301.75,
            ScrollSpeed = 9100.5,
            ScrollDropped = 3.4,
            GalleryJpeg = 61.2,
            GalleryWebp = 38.75,
            GalleryAvif = 0,
            PhotoEdit = 118.3,
            VideoAvc = 241.5,
            VideoHevc = 180.25,
            VideoVp9 = 0,
            VideoAv1 = 33.1,
            VideoHardware = 3,
            AiClassifyCpu = 301.5,
            AiClassifyGpu = 640.25,
            AiClassifyNpu = 4100.0,
            AiNpuRuntime = "Qualcomm QNN",
            MemRead = 52.31,
            MemWrite = 41,
            MemCopy = 26.5,
            MemLatency = 142.75,
            StorageSeqRead = 3120.4,
            StorageSeqWrite = 2050,
            StorageRandomRead = 61234.5,
            StorageRandomWrite = 48000,
            StorageDirect = true,
            DbInsert = 812.25,
            DbUpdate = 790,
            DbQuery = 5120.5,
            ValidityFlags = 0,
        };
        tweak?.Invoke(r);
        return r;
    }

    private static string Sign(string key, BenchmarkRequest r) =>
        Convert.ToBase64String(HMACSHA256.HashData(Encoding.UTF8.GetBytes(key), Encoding.UTF8.GetBytes(BenchmarkScoring.Canonical(r))));

    [Fact]
    public void CanonicalStringMatchesTheApp()
    {
        // Same expectation as BenchmarkScoreTest.`canonical string is what the server re-derives` in the app.
        Assert.Equal("bench|2|Honor|Honor 600 Pro|16|Adreno (TM) 830|123.46|98.00|29.99|24.50|21.50|17.25|1|" +
                     "180.00|1400.50|700.00|900.00|912.34|75.20|0.00|61.50|40.25|58.00|301.75|" +
                     "9100.50|3.40|61.20|38.75|0.00|118.30|241.50|180.25|0.00|33.10|3|" +
                     "301.50|640.25|4100.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|0.00|Qualcomm QNN|" +
                     "52.31|41.00|26.50|142.75|3120.40|2050.00|61234.50|48000.00|1|812.25|790.00|5120.50|0",
            BenchmarkScoring.Canonical(Request()));
    }

    [Fact]
    public void SignedRunVerifiesAndTamperingIsCaught()
    {
        var encryptor = new ThermoEncryptor();
        string key = encryptor.GenerateKey();
        var r = Request();
        r.Hash = Sign(key, r);
        Assert.True(encryptor.IsValidBenchmark(key, r));

        r.Test2Fps = 59.99;                                    // doubled Neon City fps after signing
        Assert.False(encryptor.IsValidBenchmark(key, r));
        Assert.False(encryptor.IsValidBenchmark("other-key", Request(x => x.Hash = Sign(key, x))));
    }

    [Fact]
    public void ScoreIsRecomputedFromFps()
    {
        var run = Request(r => { r.Test1Fps = 120; r.Test2Fps = 30; r.Test3Fps = 24; r.Test3LowFps = 20; }).ToDao();
        Assert.Equal(6000, run.Test1Score);
        Assert.Equal(6000, run.Test2Score);
        Assert.Equal(6000, run.Test3Score);
        Assert.Equal(6000, run.Score);

        var uneven = Request(r => { r.Test1Fps = 160; r.Test2Fps = 10; r.Test3Fps = 40; r.Test3LowFps = 30; }).ToDao();
        Assert.Equal(8000, uneven.Test1Score);
        Assert.Equal(2000, uneven.Test2Score);
        Assert.Equal(10000, uneven.Test3Score);
        Assert.Equal(6000, uneven.Score);                      // (2 · geomean 4000 + 10000) / 3
    }

    private static void ReferenceUx(BenchmarkRequest r)
    {
        r.WebLayout = BenchmarkScoring.RefLayout; r.WebCanvas = BenchmarkScoring.RefCanvas;
        r.WebCss = BenchmarkScoring.RefCss; r.WebSvg = BenchmarkScoring.RefSvg; r.WebJs = BenchmarkScoring.RefJs;
        r.ScrollSpeed = BenchmarkScoring.RefScroll;
        r.GalleryJpeg = BenchmarkScoring.RefJpeg; r.GalleryWebp = BenchmarkScoring.RefWebp; r.GalleryAvif = BenchmarkScoring.RefAvif;
        r.PhotoEdit = BenchmarkScoring.RefPhoto;
        r.VideoAvc = r.VideoHevc = r.VideoVp9 = r.VideoAv1 = BenchmarkScoring.RefVideo;
        r.QrRate = 6000 / BenchmarkScoring.QrWeight;
    }

    [Fact]
    public void UxIsScoredFromRawResultsAndAddedToTheTotal()
    {
        var reference = Request(r => { r.Test1Fps = 120; r.Test2Fps = 30; r.Test3Fps = 24; r.Test3LowFps = 20; ReferenceUx(r); }).ToDao();
        Assert.Equal(6000, reference.WebScore);
        Assert.Equal(6000, reference.ScrollScore);
        Assert.Equal(6000, reference.GalleryScore);
        Assert.Equal(6000, reference.PhotoScore);
        Assert.Equal(6000, reference.VideoScore);
        Assert.Equal(6000, reference.QrScore);
        Assert.Equal(6000, reference.UxScore);

        var run = Request(r =>
        {
            r.Test1Fps = 120; r.Test2Fps = 30; r.Test3Fps = 24; r.Test3LowFps = 20;   // GPU 6000
            ReferenceUx(r);
            r.WebLayout = BenchmarkScoring.RefLayout * 32;                              // web 12000: 32^(1/5) = 2
            r.QrRate = 2000 / BenchmarkScoring.QrWeight; r.MlKitQrRate = 40;            // 2000 points; ML Kit unscored
        }).ToDao();
        Assert.Equal(12000, run.WebScore);
        Assert.Equal(2000, run.QrScore);
        Assert.Equal(5608, run.UxScore);                         // 6000 · (2 · ⅓)^(1/6)
        Assert.Equal(run.CpuScore + run.MemScore + 6000 + 5608 + run.AiScore, run.TotalScore);
        Assert.Equal(40, run.MlKitQrRate);
    }

    [Fact]
    public void MissingCodecsAndFormatsCostAShareNotEverything()
    {
        var noAv1 = Request(r => { ReferenceUx(r); r.VideoAv1 = 0; r.VideoHardware = 0b0111; }).ToDao();
        Assert.Equal(4500, noAv1.VideoScore);                    // three codecs of four
        Assert.Equal(0b0111, noAv1.VideoHardware);
        var noAvif = Request(r => { ReferenceUx(r); r.GalleryAvif = 0; }).ToDao();
        Assert.Equal(4000, noAvif.GalleryScore);                 // two formats of three
        Assert.True(noAv1.UxScore > 0 && noAvif.UxScore > 0);
        Assert.Equal(0, Request(r => { ReferenceUx(r); r.PhotoEdit = 0; }).ToDao().UxScore);   // a failed test zeroes UX
    }

    private static void ReferenceAi(BenchmarkRequest r, bool npu)
    {
        double[] c = BenchmarkScoring.RefAiCpu, g = BenchmarkScoring.RefAiGpu, n = BenchmarkScoring.RefAiNpu;
        double N(int i) => npu ? n[i] : 0;
        (r.AiClassifyCpu, r.AiDetectCpu, r.AiSegmentCpu, r.AiUpscaleCpu, r.AiDepthCpu, r.AiSoundCpu) = (c[0], c[1], c[2], c[3], c[4], c[5]);
        (r.AiClassifyGpu, r.AiDetectGpu, r.AiSegmentGpu, r.AiUpscaleGpu, r.AiDepthGpu, r.AiSoundGpu) = (g[0], g[1], g[2], g[3], g[4], g[5]);
        (r.AiClassifyNpu, r.AiDetectNpu, r.AiSegmentNpu, r.AiUpscaleNpu, r.AiDepthNpu, r.AiSoundNpu) = (N(0), N(1), N(2), N(3), N(4), N(5));
        r.AiNpuRuntime = npu ? "Qualcomm QNN" : null;
    }

    [Fact]
    public void AiCountsCpuGpuAndNpuAThirdEach()
    {
        var withNpu = Request(r => ReferenceAi(r, npu: true)).ToDao();
        Assert.Equal(6000, withNpu.AiCpuScore);
        Assert.Equal(6000, withNpu.AiNpuScore);
        Assert.Equal(6000, withNpu.AiScore);
        var noNpu = Request(r => ReferenceAi(r, npu: false)).ToDao();
        Assert.Equal(4000, noNpu.AiScore);
        Assert.Equal(noNpu.CpuScore + noNpu.MemScore + noNpu.Score + noNpu.UxScore + 4000, noNpu.TotalScore);
        Assert.Equal(5000, Request(r => { ReferenceAi(r, npu: true); r.AiSoundCpu = 0; }).ToDao().AiCpuScore);
        Assert.Equal(4000, Request(r => { ReferenceAi(r, npu: true); r.AiClassifyGpu = r.AiDetectGpu = r.AiSegmentGpu =
            r.AiUpscaleGpu = r.AiDepthGpu = r.AiSoundGpu = 0; }).ToDao().AiScore);
        Assert.NotNull(BenchmarkScoring.Problem(Request(r => r.AiDepthNpu = -3)));
    }

    [Theory]
    [InlineData(-1, 0, 0, 0)]
    [InlineData(0, 101, 0, 0)]
    [InlineData(0, 0, -2, 0)]
    [InlineData(0, 0, 0, 6000)]
    public void ImplausibleUxResultsAreRejected(double scroll, double dropped, double jpeg, double hevc)
    {
        Assert.NotNull(BenchmarkScoring.Problem(Request(r =>
        {
            if (scroll != 0) r.ScrollSpeed = scroll;
            if (dropped != 0) r.ScrollDropped = dropped;
            if (jpeg != 0) r.GalleryJpeg = jpeg;
            if (hevc != 0) r.VideoHevc = hevc;
        })));
        Assert.NotNull(BenchmarkScoring.Problem(Request(r => r.VideoHardware = 16)));
    }

    [Fact]
    public void CpuIsTheGeometricMeanOfItsFourTests()
    {
        var run = Request(r =>
        {
            r.CpuMath = BenchmarkScoring.RefCpuMath * 4 / 3; r.CpuData = BenchmarkScoring.RefCpuData / 3;   // 8000, 2000
            r.CpuCrypto = BenchmarkScoring.RefCpuCrypto * 4 / 3; r.CpuMulti = BenchmarkScoring.RefCpuMulti / 3;
        }).ToDao();
        Assert.Equal(4000, run.CpuScore);
        Assert.Equal(0, Request(r => r.CpuMulti = 0).ToDao().CpuScore);
        Assert.Equal(run.CpuScore + run.MemScore + run.Score + run.UxScore + run.AiScore, run.TotalScore);
        Assert.NotNull(BenchmarkScoring.Problem(Request(r => r.CpuMath = -5)));
    }

    private static void ReferenceMem(BenchmarkRequest r)
    {
        (r.MemRead, r.MemWrite, r.MemCopy, r.MemLatency) =
            (BenchmarkScoring.RefMemRead, BenchmarkScoring.RefMemWrite, BenchmarkScoring.RefMemCopy, BenchmarkScoring.RefMemLatency);
        (r.StorageSeqRead, r.StorageSeqWrite, r.StorageRandomRead, r.StorageRandomWrite) =
            (BenchmarkScoring.RefSeqRead, BenchmarkScoring.RefSeqWrite, BenchmarkScoring.RefRandomRead, BenchmarkScoring.RefRandomWrite);
        (r.DbInsert, r.DbUpdate, r.DbQuery) = (BenchmarkScoring.RefDbInsert, BenchmarkScoring.RefDbUpdate, BenchmarkScoring.RefDbQuery);
    }

    [Fact]
    public void MemIsTheGeometricMeanOfRamStorageAndDatabase()
    {
        // Same expectations as BenchmarkScoreTest.`MEM is the geometric mean …` in the app.
        var reference = Request(ReferenceMem).ToDao();
        Assert.Equal(6000, reference.RamScore);
        Assert.Equal(6000, reference.StorageScore);
        Assert.Equal(6000, reference.DbScore);
        Assert.Equal(6000, reference.MemScore);
        Assert.Equal(reference.CpuScore + 6000 + reference.Score + reference.UxScore + reference.AiScore, reference.TotalScore);
        Assert.Equal(7135, Request(r => { ReferenceMem(r); r.MemLatency /= 2; }).ToDao().RamScore);   // half the latency
        Assert.Equal(0, Request(r => { ReferenceMem(r); r.MemLatency = 0; }).ToDao().MemScore);
        Assert.Equal(4000, BenchmarkScoring.Mem(8000, 2000, 4000));
        Assert.True(Request(ReferenceMem).ToDao().StorageDirect);
    }

    [Theory]
    [InlineData(-1, 0, 0, 0)]
    [InlineData(5000, 0, 0, 0)]                               // GB/s beyond any RAM
    [InlineData(0, -3, 0, 0)]
    [InlineData(0, 0, 2e7, 0)]
    [InlineData(0, 0, 0, -1)]
    public void ImplausibleMemResultsAreRejected(double read, double latency, double randomRead, double query)
    {
        Assert.NotNull(BenchmarkScoring.Problem(Request(r =>
        {
            if (read != 0) r.MemRead = read;
            if (latency != 0) r.MemLatency = latency;
            if (randomRead != 0) r.StorageRandomRead = randomRead;
            if (query != 0) r.DbQuery = query;
        })));
    }

    [Fact]
    public void FailedWebViewTestZeroesUx()
    {
        var run = Request(r => { r.WebLayout = 0; r.WebCanvas = 0; r.WebCss = 0; r.WebSvg = 0; r.WebJs = 0; }).ToDao();
        Assert.Null(BenchmarkScoring.Problem(Request(r => r.WebCss = 0)));
        Assert.Equal(0, run.WebScore);
        Assert.Equal(0, run.UxScore);
        Assert.Equal(run.CpuScore + run.MemScore + run.Score + run.AiScore, run.TotalScore);
        Assert.NotNull(BenchmarkScoring.Problem(Request(r => r.QrRate = -1)));
    }

    [Fact]
    public void PhoneWithoutRayTracingScoresZeroInTestThree()
    {
        var r = Request(x => { x.Test1Fps = 120; x.Test2Fps = 30; x.RayTracing = false; x.Test3Fps = 0; x.Test3LowFps = 0; });
        Assert.Null(BenchmarkScoring.Problem(r));
        var run = r.ToDao();
        Assert.Equal(0, run.Test3Score);
        Assert.Equal(4000, run.Score);                         // raster keeps two thirds
        Assert.NotNull(BenchmarkScoring.Problem(Request(x => { x.RayTracing = false; x.Test3Fps = 30; })));
    }

    [Theory]
    [InlineData(3, 120, 100, 30, 25)]                         // future scene version
    [InlineData(1, 120, 100, 30, 25)]                         // the old version (before the four media tests)
    [InlineData(2, 0, 0, 30, 25)]                             // no frames
    [InlineData(2, 9000, 100, 30, 25)]                        // beyond any phone
    [InlineData(2, 120, 200, 30, 25)]                         // 1% low above the average
    [InlineData(2, 120, 100, 30, 40)]                         // 1% low above the average, Test 2
    public void ImplausibleRunsAreRejected(int version, double f1, double l1, double f2, double l2)
    {
        var r = Request(x =>
        {
            x.BenchmarkVersion = version; x.Test1Fps = f1; x.Test1LowFps = l1; x.Test2Fps = f2; x.Test2LowFps = l2;
        });
        Assert.NotNull(BenchmarkScoring.Problem(r));
        Assert.Null(BenchmarkScoring.Problem(Request()));
    }

    [Fact]
    public void LeaderboardKeepsEachPhonesBestRun()
    {
        BenchmarkRun Run(int id, string model, double f1, double f2) =>
            Request(r => { r.DeviceModel = model; r.Test1Fps = f1; r.Test2Fps = f2; }).ToDao() is var run
                ? Set(run, id) : null!;
        static BenchmarkRun Set(BenchmarkRun r, int id) { r.Id = id; return r; }

        var board = BenchmarkScoring.Leaderboard(new[]
        {
            Run(1, "Honor 600 Pro", 120, 30),
            Run(2, "honor 600 pro", 125, 31),                  // same phone, better run, other casing
            Run(3, "Pixel 9 Pro", 90, 20),
            Run(4, "Honor 600 Pro", 110, 28),
        });

        Assert.Equal(2, board.Count);
        Assert.Equal(2, board[0].Id);
        Assert.True(board[0].TotalScore >= board[0].Score);
        Assert.Equal(3, board[0].Runs);
        Assert.Equal(3, board[1].Id);
        Assert.True(board[0].Score > board[1].Score);
    }
}
