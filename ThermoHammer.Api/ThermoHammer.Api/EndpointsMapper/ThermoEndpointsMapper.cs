static class ThermoEndpointsMapper
{
    static void MapEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/health", () => "OK");
    }
}