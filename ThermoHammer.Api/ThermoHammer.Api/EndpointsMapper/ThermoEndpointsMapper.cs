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

            hammerRequest.DeviceModel = await modelResolver.ResolveModelAsync(hammerRequest.Os, hammerRequest.DeviceModel, hammerRequest.DeviceManufacturer);

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
            request.DeviceModel = await modelResolver.ResolveModelAsync(request.Os, request.DeviceModel, request.DeviceManufacturer);
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

        // one run opened from the leaderboard: every section's numbers as text (verified runs only, like the board)
        app.MapGet("/benchmark/{id:int}", async (int id, ThermoDbContext db) =>
        {
            var run = await db.BenchmarkRuns.AsNoTracking().FirstOrDefaultAsync(r => r.Id == id && r.ValidityFlags == 0);
            return run is null ? Results.NotFound() : Results.Ok(BenchmarkDetail.From(run));
        });

        // Phone Health: how a model does — its verified runs, matched by the name submissions are stored under
        app.MapGet("/benchmark/model", async (string model, OsPlatform os, string? manufacturer, ThermoDbContext db, DeviceModelResolver modelResolver) =>
        {
            if (string.IsNullOrWhiteSpace(model)) return Results.BadRequest("Missing model");
            string resolved = await modelResolver.ResolveModelAsync(os, model, manufacturer);
            var runs = await db.BenchmarkRuns
                .Where(r => r.BenchmarkVersion == BenchmarkScoring.CurrentVersion && r.ValidityFlags == 0
                         && r.DeviceModel == resolved)
                .ToListAsync();
            return Results.Ok(BenchmarkScoring.ModelStats(resolved, runs));
        });

        // Stores the best-known name again for every Android run, after the name table or the device-specs service improved.
        // Safe to repeat: a name that is already right resolves to itself.
        app.MapGet("modelfix", async (ThermoDbContext db, DeviceModelResolver modelResolver) =>
        {
            int stress = 0, benchmark = 0;

            foreach (var item in await db.Hammers.ToListAsync())
            {
                var fixedModel = await modelResolver.ResolveModelAsync(item.Os, item.DeviceModel, item.DeviceManufacturer);
                if (fixedModel == item.DeviceModel) continue;
                item.DeviceModel = fixedModel;
                stress++;
            }

            foreach (var run in await db.BenchmarkRuns.ToListAsync())
            {
                var fixedModel = await modelResolver.ResolveModelAsync(run.Os, run.DeviceModel, run.DeviceManufacturer);
                if (fixedModel == run.DeviceModel) continue;
                run.DeviceModel = fixedModel;
                benchmark++;
            }

            await db.SaveChangesAsync();
            return Results.Ok($"Models updated successfully ({stress} stress runs, {benchmark} benchmark runs)");
        });
    }
}