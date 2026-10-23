using Microsoft.EntityFrameworkCore;
using ThermoHammer.Api.EndpointsMapper;
using ThermoHammer.Api.Models;
using ThermoHammer.Api.Tools;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.
// Learn more about configuring OpenAPI at https://aka.ms/aspnet/openapi
builder.Services.AddOpenApi();
builder.Services.AddScoped<ThermoEncryptor>();
builder.Services.AddHttpClient<DeviceModelResolver>();
// The database login is never stored in the repo or the (public) image.
// Production: the deploy workflow writes the ConnectionStrings__DefaultConnection environment
// variable from the DB_APP_PASSWORD GitHub secret. Local development: dotnet user-secrets.
var connectionString = builder.Configuration.GetConnectionString("DefaultConnection");
if (string.IsNullOrWhiteSpace(connectionString))
    throw new InvalidOperationException(
        "ConnectionStrings:DefaultConnection is not configured. See SECRETS.md in the repository root.");

builder.Services.AddDbContext<ThermoDbContext>(o => o.UseSqlServer(connectionString));
var app = builder.Build();

// Configure the HTTP request pipeline.
if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
}

//app.UseHttpsRedirection();
app.MapEndpoints();
app.Run();

public partial class Program { }