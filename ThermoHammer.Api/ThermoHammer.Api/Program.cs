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
builder.Services.AddDbContext<ThermoDbContext>(o =>
o.UseSqlServer(
    builder.Configuration.GetConnectionString("DefaultConnection")
    ));
var app = builder.Build();

// Configure the HTTP request pipeline.
if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
}

//app.UseHttpsRedirection();
app.MapEndpoints();
app.Run();