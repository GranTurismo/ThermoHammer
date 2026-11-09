using System.Net;
using ThermoHammer.Api.Models;
using ThermoHammer.Api.Tools;
using Xunit;

namespace ThermoHammer.Api.Tests;

/// <summary>Phone codes become the name on the box. None of this touches the database: run it with
/// <c>dotnet test --filter FullyQualifiedName~DeviceNamesTests</c>.</summary>
public class DeviceNamesTests
{
    // a small table in the shape of Google's list, so the rules don't depend on what Google lists today
    private static readonly string[] SmallList =
    [
        "# comment lines and blank lines are skipped",
        "",
        "VKP-NX9\tHonor\tHONOR 600 Pro",
        "SM-S918B\tSamsung\tGalaxy S23 Ultra",
        "2201123G\tXiaomi\tXiaomi 12",
        "CPH2581\tOnePlus\tOnePlus 12",
        "2311DRK48G\tPOCO\tPOCO X6 Pro 5G",
        "LM-G900\tLG\tLG Velvet",
        "A10\tBlackview\tBlackview A10",
        "A10\tOdea\tA10_EEA",
        "211033MI\tPOCO\tPOCO C31",
        "C31\tPOCO\tPOCO C31_EEA",
        "90\tFoo\tFoo Ninety",
        "KFTT\tAmazon\tKindle Fire HD",
        "short line without enough fields",
    ];

    private static DeviceNames Table(string[]? list = null, string[]? overrides = null) =>
        new(new StringReader(string.Join('\n', list ?? SmallList)), overrides is null ? null : new StringReader(string.Join('\n', overrides)));

    private static string? Resolve(DeviceNames names, string? manufacturer, string? model) =>
        names.TryResolve(model, manufacturer, out string name) ? name : null;

    [Theory]
    [InlineData("HONOR", "HONOR VKP-NX9", "HONOR 600 Pro")]                // how the apps send it: the manufacturer in front
    [InlineData("HONOR", "VKP-NX9", "HONOR 600 Pro")]                      // the bare code
    [InlineData(null, "HONOR VKP-NX9", "HONOR 600 Pro")]                   // the caller doesn't know the manufacturer: the first word is the brand
    [InlineData(null, "VKP-NX9", "HONOR 600 Pro")]                         // only the code: Google's brand is used
    [InlineData("Samsung", "Samsung SM-S918B", "Samsung Galaxy S23 Ultra")]
    [InlineData("Samsung", "sm-s918b", "Samsung Galaxy S23 Ultra")]        // codes match in any case
    [InlineData("Xiaomi", "Xiaomi 2201123G", "Xiaomi 12")]
    [InlineData("OnePlus", "OnePlus CPH2581", "OnePlus 12")]               // not the same phone as the Xiaomi 12
    [InlineData("Xiaomi", "Xiaomi 2311DRK48G", "Xiaomi POCO X6 Pro 5G")]   // a Xiaomi brand reported as Xiaomi: the reported manufacturer leads
    [InlineData("LGE", "LGE LM-G900", "LGE LG Velvet")]                    // "LGE" and "LG" are the same company
    [InlineData("Amazon", "KFTT", "Amazon Kindle Fire HD")]                // letters only: believed because the brand agrees
    [InlineData("Blackview", "A10", "Blackview A10")]                      // one code, many phones: the brand picks
    [InlineData("Odea", "Odea A10", "Odea A10_EEA")]
    [InlineData("POCO", "POCO 211033MI", "POCO C31")]
    [InlineData("Foo", "90", "Foo Ninety")]                                // a plain number is a code when the brand agrees
    public void ACodeBecomesTheNameOnTheBox(string? manufacturer, string model, string expected) =>
        Assert.Equal(expected, Resolve(Table(), manufacturer, model));

    [Theory]
    [InlineData("Google", "Google Pixel 9")]      // not in the table: stays as it came
    [InlineData("HONOR", "HONOR 600 Pro")]        // already the name on the box
    [InlineData("Blackview", "Blackview A10")]    // the same, and its tail "A10" is also a code
    [InlineData("POCO", "POCO C31")]              // the same: "C31" is also the code of the EEA variant, which must not take over
    [InlineData("Xiaomi", "Xiaomi POCO X6 Pro 5G")]
    [InlineData("HONOR", "90")]                   // a name, not a code: must not turn into Foo's phone because that code is "90" too
    [InlineData(null, "90")]
    [InlineData(null, "A10")]                     // two phones share the code and nothing says which
    [InlineData("HONOR", "")]
    [InlineData("HONOR", "   ")]
    [InlineData(null, null)]
    [InlineData("Nokia", "Nokia XYZ-123")]
    public void ANameThatIsNotAKnownCodeIsLeftAlone(string? manufacturer, string? model) =>
        Assert.Null(Resolve(Table(), manufacturer, model));

    [Fact]
    public void CorrectionsWinOverTheList()
    {
        var names = Table(overrides:
        [
            "# a correction replaces everything the list says about its code",
            "VKP-NX9\tHonor\tHONOR 600 Pro (China)",
            "NEW-001\tHonor\tHONOR 700",           // a phone the list doesn't have yet
        ]);
        Assert.Equal("HONOR 600 Pro (China)", Resolve(names, "HONOR", "HONOR VKP-NX9"));
        Assert.Equal("HONOR 700", Resolve(names, "HONOR", "HONOR NEW-001"));
        Assert.Equal("Samsung Galaxy S23 Ultra", Resolve(names, "Samsung", "SM-S918B"));   // the rest of the list is untouched
    }

    [Fact]
    public void TwoCorrectionsForOneCodeAreBothKept()
    {
        var names = Table(overrides: ["A10\tBlackview\tBlackview A10 Pro", "A10\tOdea\tOdea A10"]);
        Assert.Equal("Blackview A10 Pro", Resolve(names, "Blackview", "A10"));
        Assert.Equal("Odea A10", Resolve(names, "Odea", "A10"));
    }

    [Theory]
    [InlineData("HONOR", "HONOR VKP-NX9", "VKP-NX9")]
    [InlineData("HONOR", "VKP-NX9", "VKP-NX9")]
    [InlineData(null, "HONOR VKP-NX9", "VKP-NX9")]
    [InlineData(null, "Nobody bar", "Nobody bar")]   // an unknown first word is not a manufacturer
    [InlineData("Xiaomi", "Xiaomi 2201123G", "2201123G")]
    public void TheCodeAloneIsWhatTheModelNumberLookupGets(string? manufacturer, string model, string expected) =>
        Assert.Equal(expected, Table().CodeOf(model, manufacturer));

    // ── the table that ships with the API ────────────────────────────────────────────────────────────────────────────

    [Fact]
    public void TheShippedTableIsFoundAndKnowsTheHonor600Pro()
    {
        Assert.True(DeviceNames.Shipped.Count > 20_000, $"only {DeviceNames.Shipped.Count} codes: is Data/play_devices.tsv embedded?");
        Assert.Contains("600 Pro", Resolve(DeviceNames.Shipped, "HONOR", "HONOR VKP-NX9"));
        Assert.StartsWith("HONOR", Resolve(DeviceNames.Shipped, "HONOR", "HONOR VKP-NX9"));
    }

    /// <summary>/modelfix runs the resolver over names that were resolved before: it must never turn a right name into another one.</summary>
    [Fact]
    public void ANameThatIsAlreadyResolvedNeverChangesAgain()
    {
        var shipped = DeviceNames.Shipped;
        using var list = new StreamReader(typeof(DeviceNames).Assembly.GetManifestResourceStream("play_devices.tsv")!);
        var flips = new List<string>();
        int resolved = 0;
        for (string? line; (line = list.ReadLine()) is not null;)
        {
            if (line.Length == 0 || line[0] == '#') continue;
            var f = line.Split('\t');
            foreach (string sent in new[] { $"{f[1]} {f[0]}", f[0] })
            {
                if (!shipped.TryResolve(sent, f[1], out string once)) continue;
                resolved++;
                if (shipped.TryResolve(once, f[1], out string twice) && twice != once)
                    flips.Add($"{f[1]} / {sent}: {once} -> {twice}");
            }
        }
        Assert.True(resolved > 20_000, $"only {resolved} lookups resolved");
        Assert.True(flips.Count == 0, $"{flips.Count} names change on a second pass, e.g.\n{string.Join('\n', flips.Take(10))}");
    }

    // ── the resolver: the device-specs service first, then the table ─────────────────────────────────────────────────

    private sealed class StubHandler(HttpStatusCode status, string body = "") : HttpMessageHandler
    {
        public List<string> Urls { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Urls.Add(request.RequestUri!.AbsoluteUri);
            return Task.FromResult(new HttpResponseMessage(status) { Content = new StringContent(body) });
        }
    }

    [Fact]
    public async Task WhenTheServiceKnowsNothingTheTableAnswers_AndTheServiceWasAskedForTheBareCode()
    {
        var handler = new StubHandler(HttpStatusCode.NotFound);
        var resolver = new DeviceModelResolver(new HttpClient(handler));

        string name = await resolver.ResolveModelAsync(OsPlatform.Android, "HONOR VKP-NX9", "HONOR");

        Assert.Equal("HONOR 600 Pro", name);
        Assert.EndsWith("/api/values/devicebymodelnumber/VKP-NX9", Assert.Single(handler.Urls));
    }

    [Fact]
    public async Task AnAnswerFromTheServiceStillWins()
    {
        var handler = new StubHandler(HttpStatusCode.OK, """{"manufacturer":"Honor","model":"Honor Magic Test"}""");
        var resolver = new DeviceModelResolver(new HttpClient(handler));

        Assert.Equal("Magic Test", await resolver.ResolveModelAsync(OsPlatform.Android, "HONOR VKP-NX9", "HONOR"));
    }

    [Fact]
    public async Task ANameNobodyKnowsStaysAsItCame_AndOtherSystemsAreNotTouched()
    {
        var handler = new StubHandler(HttpStatusCode.NotFound);
        var resolver = new DeviceModelResolver(new HttpClient(handler));

        Assert.Equal("Nobody Z-1", await resolver.ResolveModelAsync(OsPlatform.Android, "Nobody Z-1", "Nobody"));
        Assert.Equal("iPhone 17 Pro", await resolver.ResolveModelAsync(OsPlatform.iOS, "iPhone 17 Pro", "Apple"));
        Assert.Single(handler.Urls);   // only the Android one asked the service
    }
}
