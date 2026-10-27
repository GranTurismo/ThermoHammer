using System.Buffers.Text;
using System.Text;
using Microsoft.EntityFrameworkCore;
using ThermoHammer.Api.Models;
using ThermoHammer.Api.Tools;

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

        app.MapPost("/hammer", async (HammerRequest hammerRequest, ThermoDbContext db, ThermoEncryptor encryptor, DeviceModelResolver modelResolver) =>
        {
            var session = await db.Sessions.FirstOrDefaultAsync(s => s.Id == hammerRequest.SessionId);
            if (session is null)
                return Results.NotFound("Session not found");

            bool isValid = encryptor.IsValid(session.EncryptionKey, hammerRequest);

            if (!isValid)
                return Results.BadRequest("Invalid data");
            if (!HammerExtensions.IsVerified(hammerRequest.ValidityFlags))
                return Results.BadRequest("Only verified runs are accepted");

            session.State = SessionState.Closed;

            hammerRequest.DeviceModel = await modelResolver.ResolveModelAsync(hammerRequest.Os, hammerRequest.DeviceModel);

            await db.Hammers.AddAsync(hammerRequest.ToDao());
            await db.SaveChangesAsync();

            return Results.Ok("Data saved successfully");
        });

        app.MapGet("/leaderboard", async (ThermoDbContext db) =>
        {
            var hammers = await db.Hammers
            .Where(o => o.ValidityFlags == null || (o.ValidityFlags & ~HammerExtensions.FreqMissing) == 0)
            .OrderByDescending(o => o.StabilityPercentage)
            .Select(o => o.ToDto())
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

        // ── GPU benchmark ───────────────────────────────────────────────────

        app.MapPost("/benchmark", async (BenchmarkRequest request, ThermoDbContext db, ThermoEncryptor encryptor, DeviceModelResolver modelResolver) =>
        {
            var session = await db.Sessions.FirstOrDefaultAsync(s => s.Id == request.SessionId);
            if (session is null)
                return Results.NotFound("Session not found");
            if (session.State == SessionState.Closed)
                return Results.BadRequest("Session already used");
            if (!encryptor.IsValidBenchmark(session.EncryptionKey, request))
                return Results.BadRequest("Invalid data");
            if (BenchmarkScoring.Problem(request) is { } problem)
                return Results.BadRequest(problem);

            session.State = SessionState.Closed;
            request.DeviceModel = await modelResolver.ResolveModelAsync(request.Os, request.DeviceModel);
            var run = request.ToDao();
            await db.BenchmarkRuns.AddAsync(run);
            await db.SaveChangesAsync();

            // rank among phones: devices whose best verified run beats this one, plus one
            int better = await db.BenchmarkRuns
                .Where(r => r.BenchmarkVersion == run.BenchmarkVersion && r.ValidityFlags == 0
                         && r.TotalScore > run.TotalScore && r.DeviceModel != run.DeviceModel)
                .Select(r => r.DeviceModel).Distinct().CountAsync();
            return Results.Ok(new { id = run.Id, score = run.TotalScore, rank = better + 1 });
        });

        // verified runs only (BenchmarkScoring.IsVerified): no charger, battery saver, warm start or busy phone
        app.MapGet("/benchmark/leaderboard", async (ThermoDbContext db) =>
        {
            var runs = await db.BenchmarkRuns
                .Where(r => r.BenchmarkVersion == BenchmarkScoring.CurrentVersion && r.ValidityFlags == 0)
                .ToListAsync();
            return Results.Ok(BenchmarkScoring.Leaderboard(runs).Take(500));
        });

        // Phone Health: how a model does — its verified runs, matched by the name submissions are stored under
        app.MapGet("/benchmark/model", async (string model, OsPlatform os, ThermoDbContext db, DeviceModelResolver modelResolver) =>
        {
            if (string.IsNullOrWhiteSpace(model)) return Results.BadRequest("Missing model");
            string resolved = await modelResolver.ResolveModelAsync(os, model);
            var runs = await db.BenchmarkRuns
                .Where(r => r.BenchmarkVersion == BenchmarkScoring.CurrentVersion && r.ValidityFlags == 0
                         && r.DeviceModel == resolved)
                .ToListAsync();
            return Results.Ok(BenchmarkScoring.ModelStats(resolved, runs));
        });

        app.MapGet("modelfix", async (ThermoDbContext db, DeviceModelResolver modelResolver) =>
        {
            var models = db.Hammers;

            foreach (var item in models)
            {
                var fixedModel = await modelResolver.ResolveModelAsync(item.Os, item.DeviceModel);
                if (fixedModel != item.DeviceModel)
                {
                    item.DeviceModel = fixedModel;
                    db.Hammers.Update(item);
                }
            }

            await db.SaveChangesAsync();
            return Results.Ok("Models updated successfully");
        });
    }
}