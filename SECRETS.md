# Secrets

This repository **and** the published Docker image (`ghcr.io/granturismo/thapi`) are public.
Nothing secret may be committed here or baked into the image.

## Where each secret lives

| Secret | Lives in | Used by |
|---|---|---|
| Production database login (`thermo_app`, read/write on the `thermo` database only) | GitHub Actions secret `DB_APP_PASSWORD` | The deploy step in `.github/workflows/docker-publish.yml` writes it to `~/docker/thapi/docker-compose.override.yaml` on the server |
| Database admin login (`sa`) for migrations and maintenance | `dotnet user-secrets` on the developer machine (and your password manager) | `dotnet ef`, local development, the endpoint tests |
| Android release-signing password and keystore path | `~/.gradle/gradle.properties` on each build machine (or environment variables) | `ThermoHammerAndroid/app/build.gradle.kts` |

The server reads `ConnectionStrings__DefaultConnection` from its environment; it refuses to start with a
clear message if it is missing.

## Common tasks

**Local development / migrations** (once per machine):

```
dotnet user-secrets set "ConnectionStrings:DefaultConnection" "<connection string>" \
    --project ThermoHammer.Api/ThermoHammer.Api/ThermoHammer.Api.csproj
ASPNETCORE_ENVIRONMENT=Development dotnet ef database update \
    --project ThermoHammer.Api/ThermoHammer.Api --startup-project ThermoHammer.Api/ThermoHammer.Api
```

Show what is stored: `dotnet user-secrets list --project ThermoHammer.Api/ThermoHammer.Api/ThermoHammer.Api.csproj`

**Rotate the production app password**

1. `ALTER LOGIN thermo_app WITH PASSWORD = '<new password>';` (as `sa`)
2. `gh secret set DB_APP_PASSWORD` and paste the new password.
3. Re-run the latest deploy (`gh run rerun <id>`, or push). Use letters and digits only so it is safe in a
   connection string.

**Rotate `sa`**: `ALTER LOGIN sa WITH PASSWORD = '<new password>';`, then update the user-secret above.
Production does not use `sa`, so nothing else needs to change.

**New Android build machine**: add these lines to `~/.gradle/gradle.properties` (without them the release
build still works but is left unsigned):

```
THERMOHAMMER_STORE_FILE=/path/to/GTech.keystore
THERMOHAMMER_STORE_PASSWORD=...
THERMOHAMMER_KEY_ALIAS=gtech
THERMOHAMMER_KEY_PASSWORD=...
```

## If a secret leaks

Rotate it first (steps above), then remove it from the code. Deleting it from the repository does not help
on its own: it stays in git history and in anyone's clone.

## Recommended hardening

SQL Server is reachable from the internet on its published port. Restrict that port to the server itself
(or to known addresses) in the server's firewall or `docker-compose` port mapping.
