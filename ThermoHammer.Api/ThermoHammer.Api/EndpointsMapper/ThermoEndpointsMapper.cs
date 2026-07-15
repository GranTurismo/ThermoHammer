using System.Buffers.Text;
using System.Text;
using Microsoft.EntityFrameworkCore;
using ThermoHammer.Api.Models;

namespace ThermoHammer.Api.EndpointsMapper;

public static class ThermoEndpointsMapper
{
    public static void MapEndpoints(this IEndpointRouteBuilder app)
    {
        var db = app.ServiceProvider.GetRequiredService<ThermoDbContext>();
        var encryptor = app.ServiceProvider.GetRequiredService<ThermoEncryptor>();

        app.MapGet("/health", () => "OK");

        app.MapPost("/session", async () =>
        {
            var encryptor = new ThermoEncryptor();
            string encryptionKey = encryptor.GenerateKey();
            ThermoSession ts = new()
            {
                EncryptionKey = encryptionKey,
            };

            await db.Sessions.AddAsync(ts);
            await db.SaveChangesAsync();

            return Results.Ok(ts);
        });

        app.MapPost("/hammer", async (Hammer hammer) =>
        {
            var session = await db.Sessions.FirstOrDefaultAsync(s => s.Id == hammer.SessionId);
            if (session is null)
                return Results.NotFound("Session not found");

            bool isValid = encryptor.IsValid(session.EncryptionKey, hammer);

            if (!isValid)
                return Results.BadRequest("Invalid data");

            session.State = SessionState.Closed;

            await db.Hammers.AddAsync(hammer);
            await db.SaveChangesAsync();

            return Results.Ok("Data saved successfully");
        });
    }
}