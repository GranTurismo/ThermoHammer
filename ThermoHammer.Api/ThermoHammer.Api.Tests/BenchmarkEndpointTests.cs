using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using ThermoHammer.Api.Models;

namespace ThermoHammer.Api.Tests;

/// <summary>
/// POST /benchmark end to end on an in-memory database (never SQL Server): the app's whole submit — a session, the signed
/// run — as the apps send it. Mac runs, so no device name is looked up on the network.
/// </summary>
public class BenchmarkEndpointTests : IClassFixture<WebApplicationFactory<Program>>
{
    private readonly WebApplicationFactory<Program> _factory;

    public BenchmarkEndpointTests(WebApplicationFactory<Program> factory)
    {
        string name = "benchmark-" + Guid.NewGuid();
        _factory = factory.WithWebHostBuilder(b =>
        {
            b.UseSetting("ConnectionStrings:DefaultConnection", "unused: the tests replace the database");
            b.ConfigureTestServices(s =>
            {
                s.RemoveAll<DbContextOptions<ThermoDbContext>>();
                s.RemoveAll<IDbContextOptionsConfiguration<ThermoDbContext>>();
                s.AddDbContext<ThermoDbContext>(o => o.UseInMemoryDatabase(name));
            });
        });
    }

    private static BenchmarkRequest Mac(Action<BenchmarkRequest>? tweak = null) => BenchmarkMappingTests.Request(r =>
    {
        (r.Os, r.DeviceManufacturer, r.DeviceModel, r.GpuName) = (OsPlatform.macOS, "Apple", "Mac (M5)", "Apple M5");
        tweak?.Invoke(r);
    });

    /// <summary>One send as the apps do it: a new session, the run signed with its key.</summary>
    private static async Task<HttpResponseMessage> Send(HttpClient client, BenchmarkRequest r)
    {
        var session = (await (await client.PostAsync("/session", null)).Content.ReadFromJsonAsync<ThermoSession>())!;
        r.SessionId = session.Id;
        r.Hash = BenchmarkMappingTests.Sign(session.EncryptionKey, r);
        return await client.PostAsJsonAsync("/benchmark", r);
    }

    [Fact]
    public async Task TheSameRunSentTwiceIsStoredOnce()
    {
        using (var scope = _factory.Services.CreateScope())
            Assert.True(scope.ServiceProvider.GetRequiredService<ThermoDbContext>().Database.IsInMemory());
        var client = _factory.CreateClient();

        var first = await Send(client, Mac());
        Assert.Equal(HttpStatusCode.OK, first.StatusCode);

        // a second tap on Submit: the same run, a new session and hash
        var again = await Send(client, Mac(r => r.AppVersion = "2.1.8"));
        Assert.Equal(HttpStatusCode.Conflict, again.StatusCode);
        Assert.Contains("already on the leaderboard", await again.Content.ReadAsStringAsync());

        // the same Mac's next real run still counts, and so does another model with these numbers
        Assert.Equal(HttpStatusCode.OK, (await Send(client, Mac(r => r.CpuMulti = 17194.5))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Send(client, Mac(r => r.DeviceModel = "Mac (M4)"))).StatusCode);

        using var check = _factory.Services.CreateScope();
        var db = check.ServiceProvider.GetRequiredService<ThermoDbContext>();
        Assert.Equal(3, await db.BenchmarkRuns.CountAsync());
        // the refused send leaves its session open, like every other refusal
        Assert.Equal(1, await db.Sessions.CountAsync(s => s.State == SessionState.Open));
    }
}
