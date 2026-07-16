using System.Buffers.Text;
using System.Text;
using Microsoft.EntityFrameworkCore;
using ThermoHammer.Api.Models;

namespace ThermoHammer.Api.EndpointsMapper;

public static class ThermoEndpointsMapper
{
    public static void MapEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/health", () => "OK");

        app.MapPost("/session", async (ThermoDbContext db, ThermoEncryptor encryptor) =>
        {
            string encryptionKey = encryptor.GenerateKey();
            ThermoSession ts = new()
            {
                EncryptionKey = encryptionKey,
            };

            await db.Sessions.AddAsync(ts);
            await db.SaveChangesAsync();

            return Results.Ok(ts);
        });

        app.MapPost("/hammer", async (HammerRequest hammerRequest, ThermoDbContext db, ThermoEncryptor encryptor) =>
        {
            var session = await db.Sessions.FirstOrDefaultAsync(s => s.Id == hammerRequest.SessionId);
            if (session is null)
                return Results.NotFound("Session not found");

            bool isValid = encryptor.IsValid(session.EncryptionKey, hammerRequest);

            if (!isValid)
                return Results.BadRequest("Invalid data");

            session.State = SessionState.Closed;

            await db.Hammers.AddAsync(hammerRequest.ToDao());
            await db.SaveChangesAsync();

            return Results.Ok("Data saved successfully");
        });

        app.MapGet("/leaderboard", async (ThermoDbContext db) =>
        {
            var hammers = await db.Hammers.Select(o => o.ToDto())
            .OrderByDescending(o => o.StabilityPercentage)
            .ToListAsync();

            return Results.Ok(hammers);
        });

        app.MapGet("/stamps/{id:int}", async (int id, ThermoDbContext db) =>
        {
            var hammer = await db.Hammers.Include(h => h.Stamps).FirstOrDefaultAsync(h => h.Id == id);
            if (hammer is null)
                return Results.NotFound("Hammer not found");
            return Results.Ok(hammer.Stamps);
        });
    }
}