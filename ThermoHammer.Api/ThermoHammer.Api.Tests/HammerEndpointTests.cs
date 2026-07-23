using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using ThermoHammer.Api.Models;
using ThermoHammer.Api.Tools;
using Xunit;

namespace ThermoHammer.Api.Tests;

public class HammerEndpointTests : IClassFixture<WebApplicationFactory<Program>>
{
    private readonly WebApplicationFactory<Program> _factory;

    public HammerEndpointTests(WebApplicationFactory<Program> factory)
    {
        _factory = factory.WithWebHostBuilder(builder =>
        {
            builder.ConfigureTestServices(services =>
            {
                services.AddScoped(sp =>
                {
                    var mockHandler = new MockGcamatorHandler();
                    var httpClient = new HttpClient(mockHandler);
                    return new DeviceModelResolver(httpClient);
                });
            });
        });
    }

    [Fact]
    public async Task CleanupExistingTestRecordsFromDatabase()
    {
        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<ThermoDbContext>();

        var testDeviceModels = new[] { "17 Pro max", "13 Pro", "14 Ultra", "Galaxy S23 Ultra", "Galaxy S24 Ultra" };

        var testHammers = await db.Hammers
            .Include(h => h.Stamps)
            .Where(h => testDeviceModels.Contains(h.DeviceModel))
            .ToListAsync();

        if (testHammers.Count > 0)
        {
            var sessionIds = testHammers.Select(h => h.SessionId).ToList();
            db.Hammers.RemoveRange(testHammers);

            var testSessions = await db.Sessions
                .Where(s => sessionIds.Contains(s.Id))
                .ToListAsync();

            db.Sessions.RemoveRange(testSessions);

            await db.SaveChangesAsync();
        }
    }

    [Theory]
    [InlineData("Xiaomi", "25031111C", "17 Pro max")]
    [InlineData("Xiaomi", "2210132C", "13 Pro")]
    [InlineData("Xiaomi", "24031PN0DC", "14 Ultra")]
    [InlineData("Samsung", "SM-S918B", "Galaxy S23 Ultra")]
    [InlineData("Samsung", "SM-S928B", "Galaxy S24 Ultra")]
    public async Task PostHammer_WithMultipleDeviceModels_StripsManufacturerInDb_AndReturnsSeparatedInDto(
        string manufacturer, string inputModelNumber, string expectedCleanModel)
    {
        // Arrange
        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<ThermoDbContext>();
        var encryptor = scope.ServiceProvider.GetRequiredService<ThermoEncryptor>();

        // 1. Create a session in DB
        string encryptionKey = encryptor.GenerateKey();
        var session = new ThermoSession
        {
            EncryptionKey = encryptionKey,
            State = SessionState.Open
        };
        await db.Sessions.AddAsync(session);
        await db.SaveChangesAsync();

        int createdSessionId = session.Id;
        int? createdHammerId = null;

        try
        {
            // 2. Prepare HammerRequest with stamps and compute HMAC signature
            var stamps = new List<HammerStamp>
            {
                new HammerStamp { ElapsedMs = 1000, Score = 5200000, ThermalState = ThermalState.Nominal },
                new HammerStamp { ElapsedMs = 2000, Score = 5000000, ThermalState = ThermalState.Fair },
                new HammerStamp { ElapsedMs = 3000, Score = 4900000, ThermalState = ThermalState.Serious }
            };

            var sb = new StringBuilder();
            foreach (var stamp in stamps)
            {
                sb.Append(Convert.ToBase64String(Encoding.UTF32.GetBytes(stamp.ElapsedMs.ToString())));
                sb.Append(Convert.ToBase64String(Encoding.UTF32.GetBytes(stamp.Score.ToString())));
                sb.Append(Convert.ToBase64String(Encoding.UTF32.GetBytes(stamp.ThermalState.ToString())));
            }
            byte[] keyBytes = Encoding.UTF8.GetBytes(encryptionKey);
            byte[] dataBytes = Encoding.UTF8.GetBytes(sb.ToString());
            byte[] computedHmacBytes = HMACSHA256.HashData(keyBytes, dataBytes);
            string hash = Convert.ToBase64String(computedHmacBytes);

            var hammerRequest = new HammerRequest
            {
                SessionId = createdSessionId,
                Os = OsPlatform.Android,
                DeviceManufacturer = manufacturer,
                DeviceModel = inputModelNumber,
                OsVersion = "15",
                Type = HammerType.FiveMinutes,
                TestThreadingType = StressThreadingType.Multi,
                Stamps = stamps,
                Hash = hash
            };

            var client = _factory.CreateClient();

            // Act
            var response = await client.PostAsJsonAsync("/hammer", hammerRequest);

            // Assert Response
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);

            // 3. Verify record in DB has manufacturer stripped from DeviceModel
            using var verifyScope = _factory.Services.CreateScope();
            var verifyDb = verifyScope.ServiceProvider.GetRequiredService<ThermoDbContext>();
            var savedHammer = await verifyDb.Hammers.FirstOrDefaultAsync(h => h.SessionId == createdSessionId);

            Assert.NotNull(savedHammer);
            createdHammerId = savedHammer.Id;
            Assert.Equal(expectedCleanModel, savedHammer.DeviceModel);
            Assert.Equal(manufacturer, savedHammer.DeviceManufacturer);

            // 4. Verify ToDto() / API response returns separated properties
            var dto = savedHammer.ToDto();
            Assert.Equal(manufacturer, dto.DeviceManufacturer);
            Assert.Equal(expectedCleanModel, dto.DeviceModel);
        }
        finally
        {
            // Cleanup: remove created test records after test completes
            using var cleanupScope = _factory.Services.CreateScope();
            var cleanupDb = cleanupScope.ServiceProvider.GetRequiredService<ThermoDbContext>();

            if (createdHammerId.HasValue)
            {
                var hammerRecord = await cleanupDb.Hammers.Include(h => h.Stamps).FirstOrDefaultAsync(h => h.Id == createdHammerId.Value);
                if (hammerRecord != null)
                {
                    cleanupDb.Hammers.Remove(hammerRecord);
                }
            }

            var sessionRecord = await cleanupDb.Sessions.FirstOrDefaultAsync(s => s.Id == createdSessionId);
            if (sessionRecord != null)
            {
                cleanupDb.Sessions.Remove(sessionRecord);
            }

            await cleanupDb.SaveChangesAsync();
        }
    }
}

public class MockGcamatorHandler : HttpMessageHandler
{
    private static readonly Dictionary<string, (string manufacturer, string model)> ModelMappings = new(StringComparer.OrdinalIgnoreCase)
    {
        { "25031111C", ("Xiaomi", "Xiaomi 17 Pro max") },
        { "2210132C", ("Xiaomi", "13 Pro") },
        { "24031PN0DC", ("Xiaomi", "14 Ultra") },
        { "SM-S918B", ("Samsung", "Samsung Galaxy S23 Ultra") },
        { "SM-S928B", ("Samsung", "Galaxy S24 Ultra") }
    };

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        string requestUrl = request.RequestUri?.ToString() ?? string.Empty;
        string modelNumber = Uri.UnescapeDataString(requestUrl.Substring(requestUrl.LastIndexOf('/') + 1));

        var (manufacturer, model) = ModelMappings.TryGetValue(modelNumber, out var tuple) ? tuple : ("Generic", modelNumber);

        var jsonResponse = JsonSerializer.Serialize(new { manufacturer, model });
        var response = new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent(jsonResponse, Encoding.UTF8, "application/json")
        };
        return Task.FromResult(response);
    }
}
