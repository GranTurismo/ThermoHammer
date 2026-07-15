using System.Buffers.Text;
using System.Text;
using ThermoHammer.Api.Models;

static class ThermoEndpointsMapper
{
    static void MapEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/health", () => "OK");

        app.MapPost("/session", () =>
        {
            long currentUnixTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            int randomNumber1 = Random.Shared.Next(10000, 999999);
            int randomNumber2 = Random.Shared.Next(10000, 999999);
            string keyParts = (randomNumber1 + currentUnixTime + randomNumber2).ToString();
            Span<byte> buffer = Encoding.UTF8.GetBytes(keyParts).AsSpan();
            string encryptionKey = Convert.ToBase64String(buffer);

            return Results.Ok(encryptionKey);
        });

        app.MapPost("/hammer", async (Hammer hammer) =>
        {

            return Results.Ok();
        });
    }
}