# Build stage using Ubuntu-based .NET 10 SDK image
FROM mcr.microsoft.com/dotnet/sdk:10.0-noble AS build
WORKDIR /src

# Copy solution and project files first to cache restore layers
COPY ["ThermoHammer.Api.sln", "./"]
COPY ["ThermoHammer.Api/ThermoHammer.Api.csproj", "ThermoHammer.Api/"]
COPY ["ThermoHammer.Api.Tests/ThermoHammer.Api.Tests.csproj", "ThermoHammer.Api.Tests/"]

# Restore dependencies
RUN dotnet restore

# Copy remaining source code files
COPY . .

# Build the project
WORKDIR "/src/ThermoHammer.Api"
RUN dotnet build -c Release -o /app/build

# Publish the project to a folder
FROM build AS publish
RUN dotnet publish -c Release -o /app/publish /p:UseAppHost=false

# Final runtime stage using Ubuntu-based .NET 10 ASP.NET runtime image
FROM mcr.microsoft.com/dotnet/aspnet:10.0-noble AS final
WORKDIR /app
COPY --from=publish /app/publish .

# Expose standard container port
EXPOSE 5000
ENV ASPNETCORE_URLS=http://+:5000

ENTRYPOINT ["dotnet", "ThermoHammer.Api.dll"]
