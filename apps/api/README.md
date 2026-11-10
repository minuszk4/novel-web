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

In the PostgreSQL profile, Google sign-in requires `GOOGLE_AUTH_WEB_CLIENT_ID`, the exact `GOOGLE_AUTH_CREDENTIAL_CALLBACK_URI` authorized for the GIS client, a unique random 32-byte Base64URL `GOOGLE_AUTH_RATE_LIMIT_HMAC_KEY`, and the web origin in `novel.web.cors.allowed-origins`. The callback URI must use HTTPS except for `http://localhost` during local development. Bootstrap returns the public client ID, fixed callback URI, one-use nonce and expiry; it never accepts a callback host from the browser. A persistent token bucket allows 10 starts and refills one token per configured interval (default `6s`); it stores only a keyed HMAC of the client IP. `GOOGLE_AUTH_RATE_LIMIT_REFILL_INTERVAL` tunes the interval. The credential POST accepts only the configured exact `Origin`, UTF-8 `application/x-www-form-urlencoded`, and bodies up to 24 KiB. It checks GIS double-submit CSRF before JWT verification, consumes the nonce once, maps the Google subject, creates or rotates the opaque first-party session cookie, then sends a no-store 303 to the server-stored safe path. For authenticated API use, configure a separate random 32-byte Base64URL `NOVEL_CSRF_HMAC_KEY`; `GET /api/v1/auth/csrf` returns an HMAC-derived token tied to the current session, which the frontend keeps in memory and sends as `X-CSRF-Token` on mutations. The token changes when the session rotates. `GET /api/v1/auth/me` returns the current user's persisted profile, linked providers, recorded Terms/Privacy versions, personal scope and fail-closed feature flags; it requires an active session and is no-store. `POST /api/v1/auth/logout` revokes the current session and clears the cookie idempotently. To support an explicitly confirmed all-session sign-out, `POST /api/v1/auth/logout-all/confirmation` returns a five-minute, one-use token bound to the current session; only its SHA-256 hash is stored. `POST /api/v1/auth/logout-all` consumes the token and revokes all of that user's active sessions atomically. Set `NOVEL_LOGOUT_ALL_CONFIRMATION_TTL` within 1–15 minutes (default `5m`). Set `NOVEL_TERMS_VERSION` and `NOVEL_PRIVACY_NOTICE_VERSION` only to approved active versions; policy completion remains false unless both match the latest recorded acceptance. These routes are available only with the PostgreSQL profile; real Google credentials are not configured here and the Google status flag remains false.

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

The status response exposes API version, deployment environment and feature availability only. Every integration is disabled by default. The default profile allows only the status and health GET routes. The PostgreSQL profile also allows the rate-limited Google bootstrap GET, credential POST, session-bound `GET /api/v1/auth/csrf`, active-session `GET /api/v1/auth/me`, idempotent `POST /api/v1/auth/logout`, and the CSRF-protected logout-all confirmation/revocation routes; other routes remain denied. The credential path is the only route exempted from app-session CSRF because it validates Google's double-submit token and a configured browser Origin itself. Other unsafe methods with a usable session cookie require a valid session-bound `X-CSRF-Token`; logout with no usable session may only clear the cookie. CORS allows credentialed requests only from exact origins in `novel.web.cors.allowed-origins`, and permits the CSRF header; actuator CORS stays non-credentialed. Denied requests use a safe `application/problem+json` response with a server-generated `X-Correlation-Id`; internal exception details are not returned to clients.

The current PostgreSQL profile applies 15 Liquibase changesets and creates 10 public tables (8 application tables plus 2 Liquibase metadata tables); the earlier 2026-11-10 recovery smoke restored the then-current 11 changesets/8 tables. Migration rollback, point-in-time recovery, backup encryption and clean-host restore remain unverified. A PostgreSQL-profile-only Google auth foundation generates a 32-byte nonce, stores only its SHA-256 hash, assigns expiry using the database clock, sanitizes the return path, and atomically consumes a live attempt once. It also provides a persistent 10-token bootstrap limiter that refills at a configurable interval (default one token per six seconds), keys client addresses using a configured 32-byte HMAC secret, and purges inactive buckets hourly after 24 hours. The bootstrap GET invokes the rate limiter and attempt store. The credential POST enforces configured Origin/content-type/body-size gates, GIS cookie/form CSRF before JWT decode, RS256/JWKS/claim/nonce verification, and identity lookup by canonical `(issuer, sub)`. It atomically creates a new user/provider identity when needed, rejects suspended/deleted accounts and reports verified-email collision generically without auto-linking. Success creates a session or rotates the same account's existing session, revokes a different account's browser session, sets only an opaque `__Host-novel_session` cookie and responds with a no-store 303 to the database-stored safe path. The session service stores only SHA-256 token hashes, checks active-user state, applies database-clock absolute/idle expiry, throttles `last_seen_at` writes, and supports transactional rotation/revoke/revoke-all. The session authentication filter resolves the secure cookie into a principal and touches the session only after its throttle window. The CSRF token is HMAC-derived from the opaque session secret and a separate server key; it is not stored or sent as a cookie, and rotation changes it. All unsafe methods with a usable session cookie except the Google credential route require the `X-CSRF-Token` header; logout without a usable cookie can only clear that cookie. `/auth/me` reads profile/provider/policy state from the current principal; product feature flags remain false and `avatarUrl` is null until persisted profile support exists. `/auth/logout` revokes the current session idempotently. Logout-all issues a 256-bit token tied to the current session, stores only its SHA-256 hash, expires it after five minutes, and consumes it atomically with revocation of all sessions for the current user; a token from another user/session, an expired token, or a replay revokes nothing. CORS credentials are enabled only for the configured exact app-origin allowlist; actuator CORS stays non-credentialed. Current-policy and acceptance routes, import, cultivation ledger and MoMo Test checkout are not implemented; Google status remains false because no live Google client/credential or FE sign-in flow has been configured. Do not set a status flag to true until the corresponding integration is implemented and configured.

See the local [PostgreSQL pivot plan](../../.ai/novel-web-postgresql-pivot-plan.md), [backend specification](../../.ai/novel-web-backend-spec.md), [early phase readiness pack](../../.ai/novel-web-early-phase-readiness.md) and [SDLC WBS](../../.ai/novel-web-sdlc-wbs.md).

The OpenAPI file `src/main/resources/openapi.yaml` contains the implemented read-only status/health operations plus planned G2 Auth, private library/import and source-only Reader contracts marked `x-implementation-status: planned`. These business operations are not live routes; the default API still denies them and all integrations remain disabled. Lint and semantic-version diff commands are documented in [`tools/openapi/README.md`](../../tools/openapi/README.md). Progression, translation, publishing and commerce contracts remain future feature-gate work.

## Test reports and coverage

Run the local test and report lifecycle from `apps/api`:

```powershell
.\mvnw.cmd -B -ntp verify
```

JUnit XML/text reports are written to `target/surefire-reports/`; JaCoCo HTML/XML coverage is written to `target/site/jacoco/`. CI uploads both report folders for 14 days. Coverage is recorded as an initial foundation baseline; no threshold is enforced while the business API and feature test suite are still absent.

### PostgreSQL 17 integration tests

The default `mvnw verify` remains database-free; all 44 PostgreSQL integration tests are skipped unless `postgres.integration=true` is explicitly set. To execute them locally, use the **disposable Compose project** procedure above, keep it separate from `novel-web-local`, and provide the temporary connection settings before running Maven:

```powershell
$pgTestPassword = [guid]::NewGuid().ToString('N')
$env:POSTGRES_PASSWORD = $pgTestPassword
$env:DATABASE_PASSWORD = $pgTestPassword
$env:DATABASE_USERNAME = 'novelweb'
$env:DATABASE_URL = 'jdbc:postgresql://localhost:55432/novelweb'
.\mvnw.cmd -B -ntp -Ppostgres '-Dpostgres.integration=true' '-Dspring.profiles.active=postgres' verify
```

Use the same disposable project name and port from startup through cleanup; remove only that project with `docker compose -p $disposableProject --profile database down --volumes`. The suite verifies all 15 Liquibase changesets and 8 application tables, identity constraints, auth-attempt expiry/replay/one-winner concurrency, rate-limit refill/concurrent capacity/inactive-bucket cleanup, Google identity create/update/collision/same-sub concurrency, bootstrap GET response/no-store/return-path persistence/10-request cap, GIS credential POST origin/content-type/body-size/duplicate-field/CSRF gates, 303 safe redirect, secure cookie issuance, hash-only session storage, same-account rotation and account-switch revocation, session-cookie resolution, CSRF token issuance/rotation, `/auth/me` owner/profile/provider/policy/feature handling, idempotent logout and cookie clearing, one-use logout-all confirmation issuance/replacement/hash-only storage, wrong-user/session and expired-token rejection, all-session revocation and concurrent replay rejection, plus exact-origin credentialed CORS. Session tests cover active-user gating, idle/absolute expiry, throttled touch, revoke/revoke-all, expiry-preserving rotation and one-winner concurrent rotation. The full PostgreSQL-profile `verify` currently passes 81/81 tests; the default test run passes 37 and skips the 44 database integration tests. An `@AfterAll` assertion verifies that users, identities, sessions, attempts, rate-limit buckets, policy acceptances, auth events and confirmations all have zero fixture rows. The default suite also verifies GIS CSRF/claim checks, synthetic RSA/JWKS key rotation, session filter and CSRF token primitives, and cookie attributes without contacting Google. CI runs the PostgreSQL suite against a PostgreSQL 17 service container.
