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

In the PostgreSQL profile, Google sign-in requires `GOOGLE_AUTH_WEB_CLIENT_ID`, the exact `GOOGLE_AUTH_CREDENTIAL_CALLBACK_URI` authorized for the GIS client, a unique random 32-byte Base64URL `GOOGLE_AUTH_RATE_LIMIT_HMAC_KEY`, and the web origin in `novel.web.cors.allowed-origins`. The callback URI must use HTTPS except for `http://localhost` during local development. Bootstrap returns the public client ID, fixed callback URI, one-use nonce and expiry; it never accepts a callback host from the browser. A persistent token bucket allows 10 starts and refills one token per configured interval (default `6s`); it stores only a keyed HMAC of the client IP. `GOOGLE_AUTH_RATE_LIMIT_REFILL_INTERVAL` tunes the interval. The credential POST accepts only the configured exact `Origin`, UTF-8 `application/x-www-form-urlencoded`, and bodies up to 24 KiB. It checks GIS double-submit CSRF before JWT verification, consumes the nonce once, maps the Google subject, creates or rotates the opaque first-party session cookie, then sends a no-store 303 to the server-stored safe path. For authenticated API use, configure a separate random 32-byte Base64URL `NOVEL_CSRF_HMAC_KEY`; `GET /api/v1/auth/csrf` returns an HMAC-derived token tied to the current session, which the frontend keeps in memory and sends as `X-CSRF-Token` on mutations. The token changes when the session rotates. `GET /api/v1/auth/me` returns the current user's persisted profile, linked providers, recorded Terms/Privacy versions, personal scope and fail-closed feature flags; it requires an active session and is no-store. `POST /api/v1/auth/logout` revokes the current session and clears the cookie idempotently. To support an explicitly confirmed all-session sign-out, `POST /api/v1/auth/logout-all/confirmation` returns a five-minute, one-use token bound to the current session; only its SHA-256 hash is stored. `POST /api/v1/auth/logout-all` consumes the token and revokes all of that user's active sessions atomically. Set `NOVEL_LOGOUT_ALL_CONFIRMATION_TTL` within 1–15 minutes (default `5m`). `GET /api/v1/auth/policies/current` returns the latest effective versions and same-origin document URLs; versioned Markdown documents are served by type/version and only after their effective date. `POST /api/v1/auth/policies/accept` records explicit Terms acceptance and Privacy Notice acknowledgment in one transaction, with server timestamps and a fixed source label; stale versions are rejected with no writes. The current-policy and acceptance routes fail closed with `POLICY_NOT_CONFIGURED` until the database contains two legally approved documents, one per type; unavailable or future document versions return 404. Legal copy and effective dates must enter through a reviewed database migration after owner approval; no placeholder policy text is published. Google status remains false until real Google credentials and the FE sign-in flow are configured.

## Run the API without a database

From `apps/api`:

```powershell
.\mvnw.cmd spring-boot:run
```

The default profile is database-free and exposes only read-only system status and health. The opt-in PostgreSQL profile includes server sessions, policy, library/work and import-rights statement/receipt, gated import-job creation, local upload staging, scheduled expiry cleanup, and an owner-scoped EPUB 3 parse queue/status API. No approved import-rights text is provisioned locally, so statement retrieval and create-import fail closed with 503. The local filesystem adapter is single-node development only. UTF-8 text/HTML checks, bounded EPUB ZIP safety checks, package parsing, durable parser status and an atomically persisted bounded preview are implemented; retrieving preview content, editing it and committing it into a private work remain open. Google GIS, cultivation and MoMo Test remain disabled until their separate requirements and configuration are complete.

Local staging uses `NOVEL_IMPORT_STAGING_ROOT` (default `${java.io.tmpdir}/novel-web-import-staging`), `NOVEL_IMPORT_MAX_UPLOAD_BYTES` (default 52,428,800 bytes), `NOVEL_IMPORT_TARGET_TTL` (default `15m`) and `NOVEL_IMPORT_JOB_TTL` (default `24h`). In the PostgreSQL profile, the janitor checks every `NOVEL_IMPORT_CLEANUP_DELAY` (default `1m`) and processes up to `NOVEL_IMPORT_CLEANUP_BATCH_SIZE` jobs (default 100). It expires due pending/uploaded jobs using the database clock, removes their `.upload` and per-job `.part` files, and retains job/rights audit metadata. File and marker failures remain eligible for the next cleanup pass. EPUB validation limits are configurable with `NOVEL_IMPORT_MAX_ARCHIVE_ENTRIES` (1000), `NOVEL_IMPORT_MAX_ARCHIVE_ENTRY_BYTES` (52,428,800), `NOVEL_IMPORT_MAX_ARCHIVE_EXPANDED_BYTES` (104,857,600), `NOVEL_IMPORT_MAX_ARCHIVE_COMPRESSION_RATIO` (100), `NOVEL_IMPORT_MAX_ARCHIVE_DEPTH` (32) and `NOVEL_IMPORT_VALIDATION_TIMEOUT` (`15s`). This filesystem adapter is single-node development only; production must configure a private object-storage adapter before enabling import.

Per-user staging defaults are five active jobs and 100 MiB of declared bytes (`NOVEL_IMPORT_MAX_ACTIVE_JOBS_PER_USER`, `NOVEL_IMPORT_MAX_ACTIVE_DECLARED_BYTES_PER_USER`). Active jobs must not be expired; byte reservations remain until cleanup deletes the staged file and records its marker, including for expired rows waiting for the janitor. Create requests have a 10-token burst with one token refilled every six seconds; uploads have a five-token burst with one token every ten seconds. Configure `NOVEL_IMPORT_CREATE_RATE_LIMIT_CAPACITY`, `NOVEL_IMPORT_CREATE_RATE_LIMIT_REFILL_INTERVAL`, `NOVEL_IMPORT_UPLOAD_RATE_LIMIT_CAPACITY` and `NOVEL_IMPORT_UPLOAD_RATE_LIMIT_REFILL_INTERVAL`; inactive buckets are purged after `NOVEL_IMPORT_RATE_LIMIT_RETENTION` (24h). Quota rejects return `429 IMPORT_STAGING_QUOTA_EXCEEDED` without consuming the rights receipt; request throttles return operation-specific 429 codes with `Retry-After`. Upload throttle rejection leaves the one-time target intact and creates no staged file.

The parser worker polls the durable queue every `NOVEL_IMPORT_PARSER_POLL_DELAY` (default `1s`). It parses EPUB 3 outside database transactions, then stores the complete preview, warnings and SHA-256 ETag together in PostgreSQL; the preview JSON limit is 1 MiB. `POST /api/v1/imports/{id}/parse` returns `202` with `Location` and `Retry-After`; poll `GET /api/v1/imports/{id}` for `queued`, `parsing`, `ready_to_commit` or `failed`. The status API returns a title/author/chapter-count summary, not chapter bodies. Expiry cleanup deletes the private parse result with the staged file. TXT/HTML/Markdown may pass upload validation but are not parseable by this EPUB-only worker yet.

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
- `GET /api/v1/imports/rights-statement/current` and `POST /api/v1/imports/rights-statement/acceptance`
- `POST /api/v1/imports` creates a policy/rights/library-gated staging job and no-store one-time target
- `PUT /api/v1/imports/{importId}/content` streams bytes to private local staging with `X-Import-Upload-Token`

Every unsafe import request also needs the actor session and session-bound `X-CSRF-Token`. Create-import validates a supported extension/MIME hint and declared size before consuming the receipt; the upload route independently enforces the configured maximum and exact streamed length and records SHA-256. Actual format/signature and archive validation must run before parsing; no parse route is implemented yet.

The status response exposes API version, deployment environment and feature availability only. Provider and commerce integrations remain disabled by default. The default profile allows only the status and health GET routes. The PostgreSQL profile also allows the rate-limited Google bootstrap GET, credential POST, session-bound `GET /api/v1/auth/csrf`, active-session `GET /api/v1/auth/me`, idempotent `POST /api/v1/auth/logout`, the CSRF-protected logout-all confirmation/revocation routes, policy-gated library/work listing, personal library-state, import-rights, create-import and local staging routes below. The credential path is the only route exempted from app-session CSRF because it validates Google's double-submit token and a configured browser Origin itself. Other unsafe methods with a usable session cookie require a valid session-bound `X-CSRF-Token`; logout with no usable session may only clear the cookie. CORS allows credentialed requests only from exact origins in `novel.web.cors.allowed-origins`, and permits the CSRF and one-time upload-token headers; actuator CORS stays non-credentialed. Denied requests use a safe `application/problem+json` response with a server-generated `X-Correlation-Id`; internal exception details are not returned to clients.

Library catalog reads and personal reading controls are available only with the PostgreSQL profile and a current Terms/Privacy acceptance:

- `GET /api/v1/libraries` returns personal libraries and libraries in workspaces where the caller is an active member. Actor identity comes from the session; response DTOs omit owner and workspace identifiers.
- `GET /api/v1/works?libraryId={uuid}` lists works visible in that library and accepts optional `q`, `tag`, `status`, `cursor` and `limit` filters. An inaccessible library returns `404`.
- Both list routes use stable keyset pagination and version-2 HMAC-signed cursors bound to the actor, filters and current workspace access scope. Scope changes invalidate prior cursors. Responses use `Cache-Control: no-store` and `Pragma: no-cache`; work responses omit owner IDs and source hashes.

- `GET/POST /api/v1/me/library/collections` lists or creates up to 12 actor-owned collections. Names are 1–40 characters and case-insensitively unique per actor.
- `DELETE /api/v1/me/library/collections/{collectionId}` soft-deletes the actor's collection, removes its memberships and advances affected work-state ETags.
- `GET/PUT /api/v1/me/works/{workId}/library-state` reads or atomically replaces one actor's status and collection IDs for a work the actor can currently read. Reading status is independent of work publishing status. No actor ID is accepted from the client.
- The initial state is `want-to-read`, version `0`; save with the strong `ETag` in `If-Match`. A matching retry is safe; a divergent stale update returns `409` with the latest state. All mutations require the session-bound CSRF header.

The current PostgreSQL profile applies 63 Liquibase changesets and creates 30 public tables (28 application tables plus 2 Liquibase metadata tables); the earlier 2026-11-10 recovery smoke restored the then-current 11 changesets/8 tables. The private library data foundation includes owner-scoped libraries, works, volumes, chapters, revision records, ordered source segments and asset metadata. Database constraints enforce private visibility, owner/library consistency, unique order and per-owner source/asset hashes. Repository/service operations add volumes and chapters, append revisions with segments transactionally, register asset metadata and actor-assigned tags within accessible library scopes, and return stable keyset pages. Version-2 HMAC-signed cursors bind actor, library, filter, sort and the current access-scope fingerprint; library-list cursors fingerprint all active workspace memberships, while work cursors fingerprint the library scope and its workspace version/role. Membership and scope changes invalidate prior pages. Configure the separate NOVEL_LIBRARY_CURSOR_HMAC_KEY before enabling listing. Workspace and member lifecycle persistence is implemented internally: an active workspace has exactly one owner and supports owner/admin/editor/reader roles. Owners can transfer ownership; owner/admin roles manage membership; revocation and role changes increment the workspace scope version. Library, work, chapter, revision, asset, tag and search repository operations now use the active actor's scope: personal libraries remain owner-only; workspace members can read; owner/admin/editor can write; reader and outsiders cannot write or see the workspace. Owner/admin can create workspace libraries and move a non-default personal library into a workspace; default libraries stay personal. Tags retain work ownership while recording the assigning member. Membership-bound cursor invalidation is implemented and verified. Personal reading-state/collection and library/work listing routes are implemented in the PostgreSQL profile; workspace/member management HTTP routes and production object storage remain future tasks; local upload staging is for single-node development only. Migration rollback, point-in-time recovery, backup encryption and clean-host restore remain unverified. A PostgreSQL-profile-only Google auth foundation generates a 32-byte nonce, stores only its SHA-256 hash, assigns expiry using the database clock, sanitizes the return path, and atomically consumes a live attempt once. It also provides a persistent 10-token bootstrap limiter that refills at a configurable interval (default one token per six seconds), keys client addresses using a configured 32-byte HMAC secret, and purges inactive buckets hourly after 24 hours. The bootstrap GET invokes the rate limiter and attempt store. The credential POST enforces configured Origin/content-type/body-size gates, GIS cookie/form CSRF before JWT decode, RS256/JWKS/claim/nonce verification, and identity lookup by canonical `(issuer, sub)`. It atomically creates a new user/provider identity when needed, rejects suspended/deleted accounts and reports verified-email collision generically without auto-linking. Success creates a session or rotates the same account's existing session, revokes a different account's browser session, sets only an opaque `__Host-novel_session` cookie and responds with a no-store 303 to the database-stored safe path. The session service stores only SHA-256 token hashes, checks active-user state, applies database-clock absolute/idle expiry, throttles `last_seen_at` writes, and supports transactional rotation/revoke/revoke-all. The session authentication filter resolves the secure cookie into a principal and touches the session only after its throttle window. The CSRF token is HMAC-derived from the opaque session secret and a separate server key; it is not stored or sent as a cookie, and rotation changes it. All unsafe methods with a usable session cookie except the Google credential route require the `X-CSRF-Token` header; logout without a usable cookie can only clear that cookie. `/auth/me` reads persisted profile/provider/policy state; required policy status compares the latest acceptance rows against the newest effective version records, while product feature flags remain false and `avatarUrl` is null until persisted profile support exists. `/auth/logout` revokes the current session idempotently. Logout-all issues a 256-bit token tied to the current session, stores only its SHA-256 hash, expires it after five minutes, and consumes it atomically with revocation of all sessions for the current user; a token from another user/session, an expired token, or a replay revokes nothing. Policy APIs return active version/effective-date metadata and same-origin immutable Markdown; acceptance requires both explicit choices and exact active versions, persists separate decisions and server timestamps atomically, and returns stale-version errors without writes. No legal-approved rows are seeded in the local database; both policy APIs fail closed until content is supplied through a reviewed migration. CORS credentials are enabled only for the configured exact app-origin allowlist; actuator CORS stays non-credentialed. Rights-gated private import-job creation and local streamed upload are implemented in the PostgreSQL profile. Import staging now atomically caps active jobs and reserved declared bytes per user, including expired staging rows still awaiting janitor cleanup; create and upload requests use separate persistent actor token buckets and return typed 429 responses with Retry-After. Bucket rows are purged after configurable inactivity. Quota rejection leaves the rights receipt unconsumed, matching receipt retries still rotate their target, and upload throttling runs before file writes. EPUB 3 parsing now uses a durable private queue and persists a bounded preview/status atomically; preview-content retrieval/edits, commit and production object storage remain open; text/HTML and bounded EPUB ZIP validation and expiry cleanup are implemented; cultivation ledger and MoMo Test checkout are unimplemented. Google status remains false because no live Google client/credential or FE sign-in flow has been configured. Do not set a status flag to true until the corresponding integration is implemented and configured.

See the local [PostgreSQL pivot plan](../../.ai/novel-web-postgresql-pivot-plan.md), [backend specification](../../.ai/novel-web-backend-spec.md), [early phase readiness pack](../../.ai/novel-web-early-phase-readiness.md) and [SDLC WBS](../../.ai/novel-web-sdlc-wbs.md).

The OpenAPI v4.0.0 file `src/main/resources/openapi.yaml` marks status/health, PostgreSQL Auth/session/policy, library/work listing, personal library-state/collection, import-rights, import creation/upload, and EPUB parse/status routes according to runtime status. Preview-content retrieval/edits, commit, production storage and source-only Reader contracts remain planned; the default API remains database-free and denies PostgreSQL business routes. Version 3.1.0 documents actor quotas, per-action rate limits, conditional `Retry-After`, the same-origin one-time upload target, raw-byte PUT contract and bounded content checks before an upload is accepted. Lint and semantic-version diff commands are documented in [`tools/openapi/README.md`](../../tools/openapi/README.md). Progression, translation, publishing and commerce contracts remain future feature-gate work.

## Test reports and coverage

Run the local test and report lifecycle from `apps/api`:

```powershell
.\mvnw.cmd -B -ntp verify
```

JUnit XML/text reports are written to `target/surefire-reports/`; JaCoCo HTML/XML coverage is written to `target/site/jacoco/`. CI uploads both report folders for 14 days. Coverage is recorded as an initial foundation baseline; no threshold is enforced while the business API and feature test suite are still absent.

### PostgreSQL 17 integration tests

The default `mvnw verify` remains database-free; all 62 PostgreSQL integration tests are skipped unless `postgres.integration=true` is explicitly set. To run the complete 133-test suite, use the **disposable Compose project** procedure above, keep it separate from `novel-web-local`, and provide the temporary connection settings before running Maven:

```powershell
$pgTestPassword = [guid]::NewGuid().ToString('N')
$env:POSTGRES_PASSWORD = $pgTestPassword
$env:DATABASE_PASSWORD = $pgTestPassword
$env:DATABASE_USERNAME = 'novelweb'
$env:DATABASE_URL = 'jdbc:postgresql://localhost:55432/novelweb'
.\mvnw.cmd -B -ntp -Ppostgres '-Dpostgres.integration=true' '-Dspring.profiles.active=postgres' verify
```

Use the same disposable project name and port from startup through cleanup; remove only that project with `docker compose -p $disposableProject --profile database down --volumes`. The current suite verifies all 63 Liquibase changesets and 28 application tables, identity constraints, auth-attempt expiry/replay/one-winner concurrency, rate-limit refill/concurrent capacity/inactive-bucket cleanup, Google identity create/update/collision/same-sub concurrency, bootstrap GET response/no-store/return-path persistence/10-request cap, GIS credential POST origin/content-type/body-size/duplicate-field/CSRF gates, 303 safe redirect, secure cookie issuance, hash-only session storage, same-account rotation and account-switch revocation, session-cookie resolution, CSRF token issuance/rotation, `/auth/me` owner/profile/provider/policy/feature handling, current-policy selection based on server effective dates, immutable same-origin Markdown retrieval, explicit current-version acceptance, missing-choice and stale-version rejection without partial writes, server-side acceptance timestamps, idempotent retry and correct `/auth/me` completion, idempotent logout and cookie clearing, one-use logout-all confirmation issuance/replacement/hash-only storage, wrong-user/session and expired-token rejection, all-session revocation and concurrent replay rejection, private library/work owner isolation, private defaults, workspace owner/admin/editor/reader membership lifecycle, ownership transfer/revocation and scope-version changes, duplicate import/asset hash rejection, ordered volume/chapter constraints, transactional chapter revision/segment persistence and asset metadata registration, actor-assigned tag search in personal/workspace scope, role-gated workspace library/content reads and writes, workspace creation and personal-library transfer, stable library/work keyset pagination, tampered and mismatched cursor rejection, membership-bound cursor rejection after role change, ownership transfer, revocation/rejoin, member addition and personal-to-workspace library move, status filters and insert-during-pagination stability, actor-private reading status/collection CRUD, policy and CSRF gates, idempotent ETag retry and stale-write conflict, collection limit and case-insensitive name uniqueness, workspace reader state isolation and revocation, plus exact-origin credentialed CORS. Import coverage now proves concurrent requests cannot overshoot the active-job quota, quota rejects leave the receipt unconsumed, expired staging bytes reserve quota until deletion succeeds, separate create/upload token buckets return Retry-After, and upload throttling preserves the pending job without writing a file; inactive action buckets are purged after retention. Session tests cover active-user gating, idle/absolute expiry, throttled touch, revoke/revoke-all, expiry-preserving rotation and one-winner concurrent rotation. At the 2026-11-10 personal library-state checkpoint, the default run passed 44 and skipped 54 opt-in PostgreSQL tests. Focused cursor-codec tests pass 4/4; the disposable PostgreSQL cursor/membership matrix passes 3/3, and full PostgreSQL verify passed 98/98 with zero fixture rows in all 23 application tables at that historical checkpoint; the cursor, workspace membership and personal library-state changes are verified. An `@AfterAll` assertion verified that all 23 application tables had zero test fixture rows at that historical checkpoint. The default suite also verifies GIS CSRF/claim checks, synthetic RSA/JWKS key rotation, session filter and CSRF token primitives, and cookie attributes without contacting Google. The current database-free Maven verify passes 71 tests and skips 62 opt-in PostgreSQL tests. The full disposable PostgreSQL-profile verify passes 133/133 across the default and PostgreSQL integration classes, including rights receipts, import creation/upload, token rotation, content validation, archive limits, cleanup retries, quota/rate-limit controls, and the EPUB parse queue/worker/status, owner isolation, idempotency, bounded preview persistence and expiry cleanup. Four focused MockMvc controller tests pass; the added PostgreSQL HTTP integration test covers session/policy gates, personal and workspace visibility, query/tag/status filters, keyset continuation, actor/filter-bound and tampered cursors, revoked membership and DTO privacy. The historical library-query test applied 55 changesets and cleaned all 26 application tables; its evidence is ../../.ai/evidence/library-query-http-2026-11-10/result.md and postgres-http-test.log. The staging-cleanup checkpoint applied 59 changesets, left zero fixtures across all 26 application tables and an empty staging directory, then removed its disposable container and volume without targeting novel-web-local. Evidence: ../../.ai/evidence/import-staging-cleanup-postgres-2026-11-10/result.md. CI runs the PostgreSQL suite against a PostgreSQL 17 service container.


Import-rights slice (2026-11-10, checkpoint before import creation): GET /api/v1/imports/rights-statement/current returns only an effective approved statement; none is seeded locally, so it returns 503. POST /api/v1/imports/rights-statement/acceptance requires a session, session-bound CSRF, current Terms/Privacy acceptance, explicit confirmation and a UUID Idempotency-Key; it records the exact-text SHA-256 and server timestamp, returns the same receipt on retry, and exposes no upload target. Approved statement and acceptance audit fields are protected by PostgreSQL triggers; the acceptance row permits only a one-time consumed_at update for a future atomic import-create flow. The rights-only checkpoint passed 110/110 tests with 51 changesets/25 application tables. The import-create/upload checkpoint before content hardening passed 116/116 tests with 55 changesets/26 application tables; it implements transaction-gated `POST /api/v1/imports`, one-use same-origin upload targets, local streamed storage, actor/token binding and exact-byte SHA-256. Evidence: `../../.ai/evidence/import-create-postgres-2026-11-10/result.md`. A later content-validation checkpoint passed 122/122 tests: UTF-8 text/HTML and bounded EPUB ZIP checks run before uploaded state and rejected bytes are removed while the target remains retryable. Staging cleanup and per-user quotas/rate controls are now implemented and tested; quota reservation remains until terminal staging bytes are deleted. EPUB 3 parse/status and bounded-preview persistence are implemented; preview-content retrieval/edit, atomic private-work commit and production object storage remain open. Evidence: ../../.ai/evidence/import-content-validation-postgres-2026-11-10/result.md, ../../.ai/evidence/import-staging-cleanup-postgres-2026-11-10/result.md, and ../../.ai/evidence/import-staging-quota-rate-limit-postgres-2026-11-11/result.md; parse-job evidence: ../../.ai/evidence/import-parse-job-2026-11-11/result.md.
