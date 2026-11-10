# Novel Web API foundation

Java 21 / Spring Boot 4.1.2 API foundation. Use the Maven Wrapper; a system-wide Maven installation is not required.

## Prerequisites

- Java 21 and Node.js 24 for the API and web application.
- Docker Desktop with its Linux engine running for the PostgreSQL Compose profile. On a per-user Windows installation, if `docker` is not recognized, add its CLI directory to this PowerShell session before running Compose:

  ```powershell
  $dockerBin = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin'
  $env:PATH = "$dockerBin;$env:PATH"
  ```

  If Docker Desktop was installed elsewhere, use that installation's `resources\bin` directory.
- A local `.env` file in this directory. It is ignored by Git and must contain the same locally generated password in `POSTGRES_PASSWORD` and `DATABASE_PASSWORD`.

Create the local env file, then replace both placeholder passwords with the same local-only value:

```powershell
Copy-Item .env.example .env
```

Never commit `.env` or use its password outside local development.

Before Google bootstrap is enabled, configure `GOOGLE_AUTH_WEB_CLIENT_ID` with the environment's Google Web client ID and replace `GOOGLE_AUTH_RATE_LIMIT_HMAC_KEY` with a unique random 32-byte Base64URL value. The PostgreSQL profile contains a persistent token-bucket limiter primitive (capacity 10, one token per configured refill interval; default `6s`) and stores only a keyed HMAC of the client IP, never the address itself. `GOOGLE_AUTH_RATE_LIMIT_REFILL_INTERVAL` can tune the interval. No controller invokes these primitives yet: the Google bootstrap and credential routes remain unavailable until the complete sign-in/session flow is implemented.

## Run the API without a database

From `apps/api`:

```powershell
.\mvnw.cmd spring-boot:run
```

The default profile is database-free and exposes only read-only system status and health. It never enables Google, import, cultivation or MoMo Test functionality.

## Run PostgreSQL and apply the baseline migration

From `apps/api`:

```powershell
docker compose --profile database up -d postgres
docker compose --profile database ps
docker compose exec postgres pg_isready -U novelweb -d novelweb
```

Start the API with the opt-in PostgreSQL profile. Quote the system property in PowerShell so Maven receives it as one argument:

```powershell
.\mvnw.cmd -Ppostgres spring-boot:run '-Dspring-boot.run.profiles=postgres'
```

Liquibase applies the checked-in changesets during startup. Verify readiness and the migrated schema from another terminal:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
docker compose exec postgres psql -U novelweb -d novelweb -c "SELECT COUNT(*) FROM DATABASECHANGELOG;"
docker compose exec postgres psql -U novelweb -d novelweb -c "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name;"
```

The host `psql` binary is optional; the PostgreSQL image already includes the client. If host `psql` is installed, connect with `psql -h localhost -p 5432 -U novelweb -d novelweb`.

The `postgres` Maven profile is opt-in. It reads `.env` for local connection values and uses a five-connection Hikari pool. If Docker is unavailable, point the same `.env` values at an existing PostgreSQL 17 server. The sample password is local-only; never reuse it outside development.

Stop PostgreSQL while keeping the local data volume with `docker compose --profile database stop postgres`. Do not add `-v` unless you explicitly intend to delete that volume.

### Reset a disposable PostgreSQL project

Use a unique Compose project name and a separate host port so a reset cannot target the normal `novel-web-local` project. Run these commands in one PowerShell session from `apps/api`:

```powershell
$disposableProject = 'novel-web-disposable-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$env:POSTGRES_PORT = '55432'
docker compose -p $disposableProject --profile database up -d postgres
docker compose -p $disposableProject ps
docker compose -p $disposableProject exec postgres pg_isready -U novelweb -d novelweb
```

After verifying the project name and service, remove only that disposable project's container and volume:

```powershell
docker compose -p $disposableProject --profile database down --volumes
Remove-Item Env:POSTGRES_PORT
```

Keep `$disposableProject` in the same session until cleanup. Do not substitute `novel-web-local` in the cleanup command unless you intend to erase the main development database volume.

## Endpoints and current implementation boundary

- `GET http://localhost:8080/api/v1/system/status`
- `GET http://localhost:8080/actuator/health`

The status response exposes API version, deployment environment and feature availability only. Every integration is disabled by default. Security allows only the status and health GET routes; every other route is denied. CORS defaults to the Angular dev origins on port 4200 and does not allow credentials. Denied requests use a safe `application/problem+json` response with a server-generated `X-Correlation-Id`; internal exception details are kept in server logs only.

The current PostgreSQL profile applies 12 Liquibase changesets and creates 9 public tables (7 application tables plus 2 Liquibase metadata tables); the earlier 2026-11-10 recovery smoke restored the then-current 11 changesets/8 tables. Migration rollback, point-in-time recovery, backup encryption and clean-host restore remain unverified. A PostgreSQL-profile-only Google auth foundation generates a 32-byte nonce, stores only its SHA-256 hash, assigns expiry using the database clock, sanitizes the return path, and atomically consumes a live attempt once. It also provides a persistent 10-token bootstrap limiter that refills at a configurable interval (default one token per six seconds), keys client addresses using a configured 32-byte HMAC secret, and purges inactive buckets hourly after 24 hours. A verifier service validates Google JWKS RS256 signatures, issuer, audience/authorized party, timestamps, nonce presence and verified email; a credential verification service checks GIS cookie/form CSRF before decoding and consumes the nonce only after valid claims. These are service primitives only: no bootstrap or credential endpoint invokes them, identity/session and policy endpoints remain unimplemented, all business routes stay denied, and the Google status flag stays false. Import, cultivation ledger and MoMo Test checkout are also unimplemented. Do not set a status flag to true until the corresponding integration is implemented and configured.

See the local [PostgreSQL pivot plan](../../.ai/novel-web-postgresql-pivot-plan.md), [backend specification](../../.ai/novel-web-backend-spec.md), [early phase readiness pack](../../.ai/novel-web-early-phase-readiness.md) and [SDLC WBS](../../.ai/novel-web-sdlc-wbs.md).

The OpenAPI file `src/main/resources/openapi.yaml` contains the implemented read-only status/health operations plus planned G2 Auth, private library/import and source-only Reader contracts marked `x-implementation-status: planned`. These business operations are not live routes; the default API still denies them and all integrations remain disabled. Lint and semantic-version diff commands are documented in [`tools/openapi/README.md`](../../tools/openapi/README.md). Progression, translation, publishing and commerce contracts remain future feature-gate work.

## Test reports and coverage

Run the local test and report lifecycle from `apps/api`:

```powershell
.\mvnw.cmd -B -ntp verify
```

JUnit XML/text reports are written to `target/surefire-reports/`; JaCoCo HTML/XML coverage is written to `target/site/jacoco/`. CI uploads both report folders for 14 days. Coverage is recorded as an initial foundation baseline; no threshold is enforced while the business API and feature test suite are still absent.

### PostgreSQL 17 integration tests

The default `mvnw verify` remains database-free; all 11 PostgreSQL integration tests are skipped unless `postgres.integration=true` is explicitly set. To execute them locally, use the **disposable Compose project** procedure above, keep it separate from `novel-web-local`, and provide the temporary connection settings before running Maven:

```powershell
$pgTestPassword = [guid]::NewGuid().ToString('N')
$env:POSTGRES_PASSWORD = $pgTestPassword
$env:DATABASE_PASSWORD = $pgTestPassword
$env:DATABASE_USERNAME = 'novelweb'
$env:DATABASE_URL = 'jdbc:postgresql://localhost:55432/novelweb'
.\mvnw.cmd -B -ntp -Ppostgres '-Dpostgres.integration=true' '-Dspring.profiles.active=postgres' verify
```

Use the same disposable project name and port from startup through cleanup; remove only that project with `docker compose -p $disposableProject --profile database down --volumes`. The suite verifies all 12 Liquibase changesets and 7 application tables, identity constraints, auth-attempt expiry/replay/one-winner concurrency, and rate-limit refill/concurrent capacity/inactive-bucket cleanup. The default suite also verifies the GIS CSRF/claim checks and synthetic RSA/JWKS key rotation without contacting Google. Repository transactions beyond these primitives and business endpoints remain future integration coverage. CI runs the PostgreSQL suite against a PostgreSQL 17 service container.
