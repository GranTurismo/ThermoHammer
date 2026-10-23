using System.Text.Json;
using ThermoHammer.Api.Models;

namespace ThermoHammer.Api.Tests;

/// <summary>
/// Pure in-memory tests of request → entity mapping. Deliberately no database and no
/// WebApplicationFactory — safe to run anywhere (the endpoint tests talk to a real DB).
/// </summary>
public class HammerMappingTests
{
    private static HammerRequest Request(Action<HammerRequest>? tweak = null)
    {
        var r = new HammerRequest
        {
            Stamps =
            [
                new HammerStamp { ElapsedMs = 1000, Score = 100, ThermalState = ThermalState.Nominal },
                new HammerStamp { ElapsedMs = 2000, Score = 100, ThermalState = ThermalState.Nominal },
                new HammerStamp { ElapsedMs = 3000, Score = 50, ThermalState = ThermalState.Fair },
                new HammerStamp { ElapsedMs = 4000, Score = 50, ThermalState = ThermalState.Serious },
            ],
            DeviceManufacturer = "Google",
            DeviceModel = "Pixel 9 Pro",
            OsVersion = "16",
            Os = OsPlatform.Android,
            Hash = "h",
        };
        tweak?.Invoke(r);
        return r;
    }

    // ── New fields are stored ────────────────────────────────────────────────

    [Fact]
    public void RunQualityAndOsBuildAreCopiedToTheEntity()
    {
        var h = Request(r =>
        {
            r.SchemaVersion = 2;
            r.ValidityFlags = 0;
            r.Confidence = 90;
            r.StressMode = 0;
            r.SocModel = "Tensor G4";
            r.OsBuild = "BP4A.251205.006";
            r.SecurityPatch = "2026-10-05";
        }).ToDao();

        Assert.Equal(2, h.SchemaVersion);
        Assert.Equal(0, h.ValidityFlags);          // 0 = clean — must survive, not collapse to null
        Assert.Equal(90, h.Confidence);
        Assert.Equal(0, h.StressMode);
        Assert.Equal("Tensor G4", h.SocModel);
        Assert.Equal("BP4A.251205.006", h.OsBuild);
        Assert.Equal("2026-10-05", h.SecurityPatch);
    }

    [Fact]
    public void DirtyRunKeepsItsFlagsSoItCanBeFilteredOut()
    {
        var h = Request(r => r.ValidityFlags = 0b101).ToDao();   // e.g. WARM_STARTED | POWER_EVENT
        Assert.Equal(5, h.ValidityFlags);
    }

    // ── Old app versions keep working ────────────────────────────────────────

    [Fact]
    public void LegacyRequestLeavesNewFieldsNull()
    {
        var h = Request().ToDao();

        Assert.Null(h.SchemaVersion);
        Assert.Null(h.ValidityFlags);   // null = "unknown", distinct from 0 = "clean"
        Assert.Null(h.Confidence);
        Assert.Null(h.StressMode);
        Assert.Null(h.SocModel);
        Assert.Null(h.OsBuild);
        Assert.Null(h.SecurityPatch);
    }

    [Fact]
    public void OldPayloadWithoutNewFieldsStillDeserialises()
    {
        const string json = """
            {"stamps":[{"elapsedMs":1000,"score":5,"thermalState":0}],"type":0,
             "deviceManufacturer":"Apple","deviceModel":"iPhone 16","os":1,"osVersion":"26.4",
             "sessionId":7,"hash":"abc"}
            """;
        var r = JsonSerializer.Deserialize<HammerRequest>(json, JsonSerializerOptions.Web)!;
        Assert.Null(r.OsBuild);
        Assert.Null(r.SecurityPatch);
        Assert.Equal("26.4", r.OsVersion);
    }

    [Fact]
    public void NewFieldsBindByTheirCamelCaseNames_AndUnknownFieldsAreIgnored()
    {
        const string json = """
            {"stamps":[{"elapsedMs":1000,"score":5,"thermalState":0}],"type":0,
             "deviceManufacturer":"Google","deviceModel":"Pixel 9","os":2,"osVersion":"16",
             "sessionId":7,"hash":"abc","validityFlags":0,"confidence":88,
             "osBuild":"BP4A.1","securityPatch":"2026-10-05","someFutureField":123}
            """;
        var r = JsonSerializer.Deserialize<HammerRequest>(json, JsonSerializerOptions.Web)!;
        Assert.Equal(0, r.ValidityFlags);
        Assert.Equal(88, r.Confidence);
        Assert.Equal("BP4A.1", r.OsBuild);
        Assert.Equal("2026-10-05", r.SecurityPatch);
    }

    // ── Client input is sanitised ────────────────────────────────────────────

    [Theory]
    [InlineData(-1)]
    [InlineData(101)]
    [InlineData(9999)]
    public void OutOfRangeConfidenceIsDropped(int confidence)
        => Assert.Null(Request(r => r.Confidence = confidence).ToDao().Confidence);

    [Theory]
    [InlineData(-1)]
    [InlineData(3)]
    public void UnknownStressModeIsDropped(int mode)
        => Assert.Null(Request(r => r.StressMode = mode).ToDao().StressMode);

    [Fact]
    public void NegativeValidityFlagsAreDropped()
        => Assert.Null(Request(r => r.ValidityFlags = -4).ToDao().ValidityFlags);

    [Fact]
    public void OversizedTextIsClippedToTheColumnLength()
    {
        var h = Request(r =>
        {
            r.SocModel = new string('s', 500);
            r.OsBuild = new string('b', 500);
            r.SecurityPatch = new string('p', 500);
        }).ToDao();

        Assert.Equal(Hammer.MaxShortText, h.SocModel!.Length);
        Assert.Equal(Hammer.MaxShortText, h.OsBuild!.Length);
        Assert.Equal(Hammer.MaxPatchText, h.SecurityPatch!.Length);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    public void BlankTextBecomesNull(string blank)
    {
        var h = Request(r => { r.OsBuild = blank; r.SecurityPatch = blank; r.SocModel = blank; }).ToDao();
        Assert.Null(h.OsBuild);
        Assert.Null(h.SecurityPatch);
        Assert.Null(h.SocModel);
    }

    [Fact]
    public void TextIsTrimmed()
        => Assert.Equal("23F77", Request(r => r.OsBuild = "  23F77 \n").ToDao().OsBuild);

    // ── Existing behaviour is unchanged ──────────────────────────────────────

    [Fact]
    public void LegacyStabilityIsSecondHalfAverageOverPeak()
        => Assert.Equal(50.0, Request().ToDao().StabilityPercentage, 6);   // (50+50)/2 ÷ 100

    [Fact]
    public void DeliveredCapacityIsPreferredForStability()
        => Assert.Equal(87.5, Request(r => r.DeliveredCapacity = 87.5).ToDao().StabilityPercentage, 6);
}
