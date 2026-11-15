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

In the PostgreSQL profile, Google sign-in requires `GOOGLE_AUTH_WEB_CLIENT_ID`, the exact `GOOGLE_AUTH_CREDENTIAL_CALLBACK_URI` authorized for the GIS client, a unique random 32-byte Base64URL `GOOGLE_AUTH_RATE_LIMIT_HMAC_KEY`, and the web origin in `novel.web.cors.allowed-origins`. The callback URI must use HTTPS except for `http://localhost` during local development. Bootstrap returns the public client ID, fixed callback URI, one-use nonce and expiry; it never accepts a callback host from the browser. A persistent token bucket allows 10 starts and refills one token per configured interval (default `6s`); it stores only a keyed HMAC of the client IP. `GOOGLE_AUTH_RATE_LIMIT_REFILL_INTERVAL` tunes the interval. The credential POST accepts only the configured exact `Origin`, UTF-8 `application/x-www-form-urlencoded`, and bodies up to 24 KiB. It checks GIS double-submit CSRF before JWT verification, consumes the nonce once, maps the Google subject, creates or rotates the opaque first-party session cookie, then sends a no-store 303 to the server-stored safe path. For authenticated API use, configure a separate random 32-byte Base64URL `NOVEL_CSRF_HMAC_KEY`; `GET /api/v1/auth/csrf` returns an HMAC-derived token tied to the current session, which the frontend keeps in memory and sends as `X-CSRF-Token` on mutations. The token changes when the session rotates. `GET /api/v1/auth/me` returns the current user's persisted profile, linked providers, recorded Terms/Privacy versions, personal scope and fail-closed feature flags; it requires an active session and is no-store. `POST /api/v1/auth/logout` revokes the current session and clears the cookie idempotently. To support an explicitly confirmed all-session sign-out, `POST /api/v1/auth/logout-all/confirmation` returns a five-minute, one-use token bound to the current session; only its SHA-256 hash is stored. `POST /api/v1/auth/logout-all` consumes the token and revokes all of that user's active sessions atomically. Set `NOVEL_LOGOUT_ALL_CONFIRMATION_TTL` within 1–15 minutes (default `5m`). `GET /api/v1/auth/policies/current` returns the latest effective versions and same-origin document URLs; versioned Markdown documents are served by type/version and only after their effective date. `POST /api/v1/auth/policies/accept` records explicit Terms acceptance and Privacy Notice acknowledgment in one transaction, with server timestamps and a fixed source label; stale versions are rejected with no writes. The current-policy and acceptance routes fail closed with `POLICY_NOT_CONFIGURED` until the database contains two legally approved documents, one per type; unavailable or future document versions return 404. Legal copy and effective dates must enter through a reviewed database migration after owner approval; no placeholder policy text is published. Google status remains false until real provider credentials and redirect/origin configuration are supplied; the Angular GIS sign-in route is wired to bootstrap, but real provider session verification remains open.

For bootstrap throttling behind a reverse proxy, optionally set `GOOGLE_AUTH_TRUSTED_PROXIES` to a comma-separated list of exact proxy IP addresses or IPv4/IPv6 CIDRs. When unset, all forwarding headers are ignored and the socket peer is rate-limited. When set, `X-Forwarded-For` is used only if the socket peer matches the allowlist; the resolver walks the chain from the nearest hop toward the client and stops at the first untrusted address. Malformed or oversized chains fall back to the socket peer. Configure only ingress proxy networks (`/0` is rejected), firewall the API so clients cannot impersonate those peers, and configure each proxy to discard client-supplied forwarding headers before appending its observed peer. The app does not trust `Forwarded`, `X-Real-IP`, or forwarding headers from unlisted peers.

## Run the API without a database

From `apps/api`:

```powershell
.\mvnw.cmd spring-boot:run
```

The default profile is database-free and exposes only read-only system status and health. The opt-in PostgreSQL profile includes server sessions, policy, library/work and import-rights statement/receipt, gated import-job creation, local upload staging, scheduled expiry cleanup, an owner-scoped EPUB 3/TXT/HTML/Markdown parse queue/status API and source-only Reader manifest/chapter-content APIs. No approved import-rights text is provisioned locally, so statement retrieval and create-import fail closed with 503. The local filesystem adapter is single-node development only. UTF-8 text/HTML/Markdown checks, bounded EPUB ZIP safety checks, EPUB package parsing, bounded UTF-8 TXT, HTML and Markdown parsing, durable parser status and bounded preview persistence are implemented; private preview retrieval, metadata/chapter-order edits and atomic private commit for EPUB, TXT, HTML and Markdown parser versions `epub3-v2`, `txt-v1`, `html-v1` and `markdown-v1` are implemented. HTML becomes one chapter and commits only normalized text as `xhtml` with stable `html-*` anchors; scripts, styles, hidden text and remote resources are not executed or fetched. Markdown accepts md/markdown extensions, strict UTF-8 with an optional BOM, text/markdown, text/x-markdown, text/plain or octet-stream, and a 16 MiB source cap. CommonMark 0.32.0 core selects the shallowest repeated top-level heading level for chapters; the leading shallower heading supplies the work title and preface text forms an opening chapter. Link destinations and raw markup are discarded while visible labels/image alt text remain. Output is bounded to 50,000 AST nodes/depth 128, 2,000 chapters, 5,000 segments, 4,000 code points/16 KiB per segment and 384 KiB normalized text; commit stores markdown revisions with stable md-* anchors. Google GIS, cultivation and MoMo Test remain disabled until their separate requirements and configuration are complete.

Local staging uses `NOVEL_IMPORT_STAGING_ROOT` (default `${java.io.tmpdir}/novel-web-import-staging`), `NOVEL_IMPORT_MAX_UPLOAD_BYTES` (default 52,428,800 bytes), `NOVEL_IMPORT_TARGET_TTL` (default `15m`) and `NOVEL_IMPORT_JOB_TTL` (default `24h`). In the PostgreSQL profile, the janitor checks every `NOVEL_IMPORT_CLEANUP_DELAY` (default `1m`) and processes up to `NOVEL_IMPORT_CLEANUP_BATCH_SIZE` jobs (default 100). It expires due pending/uploaded jobs using the database clock, removes their `.upload` and per-job `.part` files, and retains job/rights audit metadata. File and marker failures remain eligible for the next cleanup pass. EPUB validation limits are configurable with `NOVEL_IMPORT_MAX_ARCHIVE_ENTRIES` (1000), `NOVEL_IMPORT_MAX_ARCHIVE_ENTRY_BYTES` (52,428,800), `NOVEL_IMPORT_MAX_ARCHIVE_EXPANDED_BYTES` (104,857,600), `NOVEL_IMPORT_MAX_ARCHIVE_COMPRESSION_RATIO` (100), `NOVEL_IMPORT_MAX_ARCHIVE_DEPTH` (32) and `NOVEL_IMPORT_VALIDATION_TIMEOUT` (`15s`). This filesystem adapter is single-node development only; production must configure a private object-storage adapter before enabling import.

Per-user staging defaults are five active jobs and 100 MiB of declared bytes (`NOVEL_IMPORT_MAX_ACTIVE_JOBS_PER_USER`, `NOVEL_IMPORT_MAX_ACTIVE_DECLARED_BYTES_PER_USER`). Active jobs must not be expired; byte reservations remain until cleanup deletes the staged file and records its marker, including for expired rows waiting for the janitor. Create requests have a 10-token burst with one token refilled every six seconds; uploads have a five-token burst with one token every ten seconds. Configure `NOVEL_IMPORT_CREATE_RATE_LIMIT_CAPACITY`, `NOVEL_IMPORT_CREATE_RATE_LIMIT_REFILL_INTERVAL`, `NOVEL_IMPORT_UPLOAD_RATE_LIMIT_CAPACITY` and `NOVEL_IMPORT_UPLOAD_RATE_LIMIT_REFILL_INTERVAL`; inactive buckets are purged after `NOVEL_IMPORT_RATE_LIMIT_RETENTION` (24h). Quota rejects return `429 IMPORT_STAGING_QUOTA_EXCEEDED` without consuming the rights receipt; request throttles return operation-specific 429 codes with `Retry-After`. Upload throttle rejection leaves the one-time target intact and creates no staged file.

The parser worker polls the durable queue every `NOVEL_IMPORT_PARSER_POLL_DELAY` (default `1s`). It parses EPUB 3, UTF-8 TXT, HTML and Markdown outside database transactions, then stores the complete preview, warnings and SHA-256 ETag together in PostgreSQL; the preview JSON limit is 1 MiB. TXT creates one `Nội dung` chapter because a plain-text file has no reliable chapter structure; non-empty lines become normalized paragraph segments and the preview carries `TXT_SINGLE_CHAPTER`. TXT accepts strict UTF-8 (optional initial BOM), LF/CRLF/CR line endings and `text/plain` or `application/octet-stream`; each segment is limited to 4,000 code points/16 KiB UTF-8, 5,000 segments and 384 KiB of normalized text. HTML accepts `.html`/`.htm`, `text/html`, `application/xhtml+xml` or `application/octet-stream`, strict UTF-8, and at most 4 MiB raw source. It extracts one document into one chapter, keeps h1–h6 and blockquote semantics, drops script/style/media/navigation/hidden content, never executes markup or fetches referenced resources, and emits `HTML_SINGLE_DOCUMENT`. The HTML parser caps output at 4,000 code points/16 KiB per segment, 5,000 segments, 384 KiB normalized text, 50,000 parsed nodes and depth 128. All UTF-8 text parsers validate their decoder and extraction work against a 30-second deadline; the HTML DOM and Markdown AST builds are synchronous; raw-source/nesting limits bound those calls, and traversal checks the deadline after construction. HTML commits as `xhtml` revisions with stable `html-*` anchors; no schema migration is needed. Markdown accepts md/markdown extensions, strict UTF-8 with an optional BOM, text/markdown, text/x-markdown, text/plain or octet-stream, and a 16 MiB source cap. CommonMark 0.32.0 core selects the shallowest repeated top-level heading level for chapters; the leading shallower heading supplies the work title and preface text forms an opening chapter. Link destinations and raw markup are discarded while visible labels/image alt text remain. Output is bounded to 50,000 AST nodes/depth 128, 2,000 chapters, 5,000 segments, 4,000 code points/16 KiB per segment and 384 KiB normalized text; commit stores markdown revisions with stable md-* anchors. `POST /api/v1/imports/{id}/parse` returns `202` with `Location` and `Retry-After`; poll `GET /api/v1/imports/{id}` for `queued`, `parsing`, `ready_to_commit` or `failed`. The status API returns a title/author/chapter-count summary, not chapter bodies. `GET /api/v1/imports/{id}/preview` returns owner-only metadata and chapter titles in pages of up to 100; signed cursors bind to the actor, import and current ETag. `PATCH /api/v1/imports/{id}/preview` uses the strong ETag in `If-Match` to update title, author, language or a complete chapter-order permutation. A stale ETag returns `412`; invalid mappings return `422`; edits update only private staging and never create library rows. Configure `NOVEL_LIBRARY_CURSOR_HMAC_KEY` (32-byte canonical Base64URL) to sign preview and library cursors. Preview ETags also identify the current edit revision. Expiry cleanup deletes the private parse result with the staged file.

## Run Java quality checks

From `apps/api`, run the default verification lifecycle:

```powershell
.\mvnw.cmd -B -ntp verify
```

`verify` runs the Maven compile/test/package lifecycle, checks that aggregate line coverage is at least 20%, and checks Google Java Format plus Checkstyle against main and test Java sources. Spotless `3.10.4` pins Google Java Format `1.30.0`; Checkstyle uses plugin `3.6.0`, engine `14.4.0` and [`config/checkstyle.xml`](./config/checkstyle.xml). The 20% floor is a regression guard against the current 26.27% default-profile baseline across all 230 classes, not a release-readiness target; the PostgreSQL profile also runs the same check with database integration tests enabled. The Checkstyle rules catch tabs, missing final newlines, lines over 160 characters (excluding package/import/URL lines), wildcard or unused imports, redundant modifiers, whitespace/padding defects and common Java naming errors. To apply Java formatting locally:

```powershell
.\mvnw.cmd -B -ntp spotless:apply
```

To verify formatting without applying changes:

```powershell
.\mvnw.cmd -B -ntp spotless:check
```

To run only the Checkstyle gate:

```powershell
.\mvnw.cmd -B -ntp checkstyle:check
```

The default profile skips PostgreSQL integration tests; use the documented PostgreSQL profile when validating database-backed behavior.

## Run PostgreSQL and apply the current schema

From `apps/api`:

```powershell
docker compose --profile database config --quiet
docker compose --profile database up -d --wait --wait-timeout 90 postgres
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

**Use an existing PostgreSQL 17 server instead of Docker:** create/choose a database and role on that server, then set `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD` in `.env` to that host/role. For example, keep the JDBC form `jdbc:postgresql://<host>:<port>/<database>` and use the exact credentials of the role you created. Do not start the Compose service in this mode. `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` and `POSTGRES_PORT` configure only the Compose container; the official image applies its initialization password only when its data volume is empty. Changing `POSTGRES_PASSWORD` later does not rotate a role in an existing volume; the [official PostgreSQL image documentation](https://hub.docker.com/_/postgres) confirms initialization variables are applied only to an empty data directory.

Starting the PostgreSQL-profile API runs Liquibase and may change the selected database schema. The persistent local `novel-web-local` database was last checked at 55 changesets/26 application tables while current source has 103/60. Do not point the API at that volume until you have a private backup and have reviewed the schema gap. Validate recovery against a fresh disposable database first by running `tools/e2e/run-postgres-recovery.ps1` from the repository root; never reset the persistent project with `down --volumes`.

The `postgres` Maven profile is opt-in. It reads `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD` from `.env` and uses a five-connection Hikari pool. The Compose container additionally reads `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` and `POSTGRES_PORT`. The sample password is local-only; never reuse it outside development.

Stop PostgreSQL while keeping the local data volume with `docker compose --profile database stop postgres`. Do not add `-v` unless you explicitly intend to delete that volume.

### Reset a disposable PostgreSQL project

Use a unique Compose project name and a separate host port so a reset cannot target the normal `novel-web-local` project. Run these commands in one PowerShell session from `apps/api`:

```powershell
$disposableProject = 'novel-web-disposable-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$env:POSTGRES_PORT = '55432'
docker compose -p $disposableProject --profile database up -d --wait --wait-timeout 90 postgres
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
- `POST /api/v1/imports/{importId}/parse` queues an owner-scoped EPUB 3 or UTF-8 TXT parse
- `GET /api/v1/imports/{importId}` reads safe parse status and metadata summary
- `GET/PATCH /api/v1/imports/{importId}/preview` pages and edits the private preview with ETag concurrency

Every unsafe import request also needs the actor session and session-bound `X-CSRF-Token`. Create-import validates a supported extension/MIME hint and declared size before consuming the receipt; the upload route independently enforces the configured maximum and exact streamed length and records SHA-256. Actual format/signature and archive validation run before parsing; the EPUB 3/TXT parser persists a bounded preview for private review before any commit operation is available. TXT commits use `plain_text` source format and `txt-*` stable segment anchors; EPUB commits use `epub_xhtml` and `epub-*` anchors.

The status response exposes API version, deployment environment and feature availability only. Provider and commerce integrations remain disabled by default. The default profile allows only the status and health GET routes. The PostgreSQL profile also allows the rate-limited Google bootstrap GET, credential POST, session-bound `GET /api/v1/auth/csrf`, active-session `GET /api/v1/auth/me`, idempotent `POST /api/v1/auth/logout`, the CSRF-protected logout-all confirmation/revocation routes, policy-gated library/work listing, source-only Reader manifest/content, personal library-state, import-rights, create-import and local staging routes below. The credential path is the only route exempted from app-session CSRF because it validates Google's double-submit token and a configured browser Origin itself. Other unsafe methods with a usable session cookie require a valid session-bound `X-CSRF-Token`; logout with no usable session may only clear the cookie. CORS allows credentialed requests only from exact origins in `novel.web.cors.allowed-origins`, and permits the CSRF, one-time upload-token and `If-Match` headers; actuator CORS stays non-credentialed. Denied requests use a safe `application/problem+json` response with a server-generated `X-Correlation-Id`; internal exception details are not returned to clients.

### Optional local API metrics

The default API profile continues to expose health only. For local diagnostics, start with the additional `observability` Spring profile. It serves Actuator health and Micrometer metrics on the separate `127.0.0.1:9090` management listener (or the local-only `MANAGEMENT_PORT` from `.env`), not on the public API listener. For PostgreSQL pool metrics, combine it with the `postgres` profile:

```powershell
.\mvnw.cmd -Ppostgres spring-boot:run '-Dspring-boot.run.profiles=postgres,observability'
```

From the same machine, inspect `http://127.0.0.1:9090/actuator/metrics`, `http://127.0.0.1:9090/actuator/metrics/http.server.requests`, or `http://127.0.0.1:9090/actuator/metrics/hikaricp.connections.active`. The metrics route is not included in the OpenAPI contract, is unavailable on the application port, and has no browser CORS permission. Keep the management listener on loopback; do not reverse-proxy or publish its port. HTTP timing and Hikari pool gauges are framework-provided; queue depth/age, provider latency, reconciliation backlog and actionable alert policies still need implementation and operator review.

Library catalog reads and personal reading controls are available only with the PostgreSQL profile and a current Terms/Privacy acceptance:

- `GET /api/v1/libraries` returns personal libraries and libraries in workspaces where the caller is an active member. Actor identity comes from the session; response DTOs omit owner and workspace identifiers.
- `GET /api/v1/works?libraryId={uuid}` lists works visible in that library and accepts optional `q`, `tag`, `status`, `cursor` and `limit` filters. An inaccessible library returns `404`.
- Both list routes use stable keyset pagination and version-2 HMAC-signed cursors bound to the actor, filters and current workspace access scope. Scope changes invalidate prior cursors. Responses use `Cache-Control: no-store` and `Pragma: no-cache`; work responses omit owner IDs and source hashes.

- `GET/POST /api/v1/me/library/collections` lists or creates up to 12 actor-owned collections. Names are 1–40 characters and case-insensitively unique per actor.
- `DELETE /api/v1/me/library/collections/{collectionId}` soft-deletes the actor's collection, removes its memberships and advances affected work-state ETags.
- `GET/PUT /api/v1/me/works/{workId}/library-state` reads or atomically replaces one actor's status and collection IDs for a work the actor can currently read. Reading status is independent of work publishing status. No actor ID is accepted from the client.
- The initial state is `want-to-read`, version `0`; save with the strong `ETag` in `If-Match`. A matching retry is safe; a divergent stale update returns `409` with the latest state. All mutations require the session-bound CSRF header.

Source-only account Reader routes are available only with the PostgreSQL profile and a current Terms/Privacy acceptance:

- `GET /api/v1/works/{workId}/reader-manifest?cursor=&limit=` returns work metadata and a bounded ordered chapter list with `readable` or `unavailable` state. It never includes chapter text.
- `GET /api/v1/chapters/{chapterId}/content?revision=&cursor=&limit=` rechecks current work access and returns bounded normalized source segments for a readable revision. Draft, missing and inaccessible chapters return no source body; inaccessible resources use `404` to avoid resource enumeration.
- `GET /api/v1/works/{workId}/progress` returns the actor's progress with an ETag; a work with no saved position returns version `0`.
- `PUT /api/v1/works/{workId}/progress` requires session-bound CSRF and `expectedVersion`. Matching retries are idempotent. Divergent stale writes return `409 READER_PROGRESS_VERSION_STALE` and `latestProgress`; clients reconcile before a new write. Chapter and retained-revision anchor membership are validated; `scrollRatio` is the fallback when the current revision no longer contains an older anchor. `updatedAt` is server-owned.
- `GET /api/v1/works/{workId}/bookmarks?cursor=&limit=` returns the current user's private bookmarks newest-first, with a signed actor/work-bound cursor and a maximum page size of 100.
- `POST /api/v1/works/{workId}/bookmarks` requires session-bound CSRF, an `Idempotency-Key` and `{ chapterId, segmentAnchor, label }`. The chapter must currently be readable; a non-null anchor must exist in a retained chapter revision. Repeating the same key/body returns the original response for 24 hours. A different key at an already-bookmarked chapter/anchor returns the existing bookmark; reusing a key with different body content returns `409 BOOKMARK_IDEMPOTENCY_KEY_REUSED`. Replays recheck current access before returning the stored response.
- `DELETE /api/v1/bookmarks/{bookmarkId}` removes only the current user's bookmark; foreign and missing IDs both return `404`. All Reader bookmark responses are `no-store`; chapter/work deletion removes related bookmarks and idempotency snapshots.
- Reader cursors are HMAC-signed and bind the actor and work/chapter/revision. Each page rechecks current library/workspace access. Responses are `no-store`; output excludes unsupported image references.

These endpoints only deliver private/workspace source content the actor can already read. The account Reader UI consumes these source-only APIs and exposes persisted bookmarks. Paid chapter entitlement and preview/locked content remain unimplemented; private annotations, reading history, synchronized Reader progress and per-account Reader preferences are implemented; the API does not infer payment or unlock state.

The current PostgreSQL profile applies 74 Liquibase changesets and creates 35 public base tables (33 application tables plus 2 Liquibase metadata tables); the earlier 2026-11-10 recovery smoke restored the then-current 11 changesets/8 tables. The private library data foundation includes owner-scoped libraries, works, volumes, chapters, revision records, ordered source segments and asset metadata. Database constraints enforce private visibility, owner/library consistency, unique order and per-owner source/asset hashes. Repository/service operations add volumes and chapters, append revisions with segments transactionally, register asset metadata and actor-assigned tags within accessible library scopes, and return stable keyset pages. Version-2 HMAC-signed cursors bind actor, library, filter, sort and the current access-scope fingerprint; Reader bookmark cursors also bind actor/work and sort by creation timestamp plus ID. Library-list cursors fingerprint all active workspace memberships, while work cursors fingerprint the library scope and its workspace version/role. Membership and scope changes invalidate prior pages. Configure the separate NOVEL_LIBRARY_CURSOR_HMAC_KEY before enabling listing, paginated import previews or bookmark pagination. Workspace and member lifecycle persistence is implemented internally: an active workspace has exactly one owner and supports owner/admin/editor/reader roles. Owners can transfer ownership; owner/admin roles manage membership; revocation and role changes increment the workspace scope version. Library, work, chapter, revision, asset, tag and search repository operations now use the active actor's scope: personal libraries remain owner-only; workspace members can read; owner/admin/editor can write; reader and outsiders cannot write or see the workspace. Owner/admin can create workspace libraries and move a non-default personal library into a workspace; default libraries stay personal. Tags retain work ownership while recording the assigning member. Membership-bound cursor invalidation is implemented and verified. Personal reading-state/collection and library/work listing routes are implemented in the PostgreSQL profile; workspace/member management HTTP routes and production object storage remain future tasks; local upload staging is for single-node development only. Data-bearing migration rollback, point-in-time recovery, backup encryption and clean-host restore remain unverified; a disposable PostgreSQL 17 drill rolled back all 69 empty-schema changesets and re-applied them with `verify` 138/138. A PostgreSQL-profile-only Google auth foundation generates a 32-byte nonce, stores only its SHA-256 hash, assigns expiry using the database clock, sanitizes the return path, and atomically consumes a live attempt once. It also provides a persistent 10-token bootstrap limiter that refills at a configurable interval (default one token per six seconds), keys client addresses using a configured 32-byte HMAC secret, and purges inactive buckets hourly after 24 hours. The bootstrap GET invokes the rate limiter and attempt store. The credential POST enforces configured Origin/content-type/body-size gates, GIS cookie/form CSRF before JWT decode, RS256/JWKS/claim/nonce verification, and identity lookup by canonical `(issuer, sub)`. It atomically creates a new user/provider identity when needed, rejects suspended/deleted accounts and reports verified-email collision generically without auto-linking. Success creates a session or rotates the same account's existing session, revokes a different account's browser session, sets only an opaque `__Host-novel_session` cookie and responds with a no-store 303 to the database-stored safe path. The session service stores only SHA-256 token hashes, checks active-user state, applies database-clock absolute/idle expiry, throttles `last_seen_at` writes, and supports transactional rotation/revoke/revoke-all. The session authentication filter resolves the secure cookie into a principal and touches the session only after its throttle window. The CSRF token is HMAC-derived from the opaque session secret and a separate server key; it is not stored or sent as a cookie, and rotation changes it. All unsafe methods with a usable session cookie except the Google credential route require the `X-CSRF-Token` header; logout without a usable cookie can only clear that cookie. `/auth/me` reads persisted profile/provider/policy state; required policy status compares the latest acceptance rows against the newest effective version records, while product feature flags remain false and `avatarUrl` is null until persisted profile support exists. `/auth/logout` revokes the current session idempotently. Logout-all issues a 256-bit token tied to the current session, stores only its SHA-256 hash, expires it after five minutes, and consumes it atomically with revocation of all sessions for the current user; a token from another user/session, an expired token, or a replay revokes nothing. Policy APIs return active version/effective-date metadata and same-origin immutable Markdown; acceptance requires both explicit choices and exact active versions, persists separate decisions and server timestamps atomically, and returns stale-version errors without writes. No legal-approved rows are seeded in the local database; both policy APIs fail closed until content is supplied through a reviewed migration. CORS credentials are enabled only for the configured exact app-origin allowlist; actuator CORS stays non-credentialed. Rights-gated private import-job creation and local streamed upload are implemented in the PostgreSQL profile. Import staging now atomically caps active jobs and reserved declared bytes per user, including expired staging rows still awaiting janitor cleanup; create and upload requests use separate persistent actor token buckets and return typed 429 responses with Retry-After. Bucket rows are purged after configurable inactivity. Quota rejection leaves the rights receipt unconsumed, matching receipt retries still rotate their target, and upload throttling runs before file writes. EPUB 3, strict UTF-8 TXT, sanitized HTML and bounded CommonMark Markdown parsing use a durable private queue and persist preview/status atomically; owner-scoped preview pages expose metadata and chapter titles only, signed cursors bind owner/import/ETag, and edits use `If-Match` compare-and-set, increment a preview version and remain private. Atomic private commit accepts `epub3-v2`/`txt-v1`/`html-v1`/`markdown-v1`; TXT uses `plain_text` and `txt-*` anchors, HTML uses `xhtml` and `html-*` anchors, and Markdown uses `markdown` and `md-*`. Production object storage remains open; bounded upload validation and expiry cleanup are implemented. Owner-scoped POST /api/v1/imports/{importId}/cancel is idempotent and CSRF-protected; it revokes pending upload tokens, clears queued parse state, retains an active parser lease, and leaves staging cleanup to the janitor after the worker releases its input. Parser, preview and commit requests report IMPORT_CANCELLED afterward; completed/expired jobs return 409. Cultivation ledger and MoMo Test checkout are unimplemented. The Angular sign-in flow is wired to the GIS bootstrap and credential routes, but Google remains disabled until a real client ID, credentials and deployment configuration are supplied. Do not set a status flag to true until the corresponding integration is implemented and configured.

See the local [PostgreSQL pivot plan](../../.ai/novel-web-postgresql-pivot-plan.md), [backend specification](../../.ai/novel-web-backend-spec.md), [early phase readiness pack](../../.ai/novel-web-early-phase-readiness.md) and [SDLC WBS](../../.ai/novel-web-sdlc-wbs.md).

The OpenAPI 3.1 contract v6.3.0 marks status/health, PostgreSQL Auth/session/policy, library/work listing, personal library-state/collection, import-rights/create/upload/cancel, EPUB 3/TXT/HTML/Markdown parsing, preview/edit/atomic private commit, and source-only Reader manifest/content/progress/preferences/bookmark, reading-history and annotation routes according to runtime status. The private commit supports `epub3-v2`, `txt-v1`, `html-v1` and `markdown-v1`. Production storage remains planned; the default API remains database-free and denies PostgreSQL business routes. The contract also documents actor quotas, per-action rate limits, conditional `Retry-After`, one-time same-origin upload targets, raw-byte PUT and bounded content validation. Lint and semantic-version diff commands are documented in [`tools/openapi/README.md`](../../tools/openapi/README.md). Reader progress, preferences, bookmarks, reading history and annotations persist per account in the PostgreSQL profile; progression, translation, publishing and commerce remain future feature-gate work.

## Test reports and coverage

Run the local test and report lifecycle from `apps/api`:

```powershell
.\mvnw.cmd -B -ntp verify
```

JUnit XML/text reports are written to `target/surefire-reports/`; JaCoCo HTML/XML coverage is written to `target/site/jacoco/`. CI retains both report folders for 14 days. Maven `verify` enforces a 20% aggregate line-coverage floor; this is a regression guard, not a release-readiness target. The current default database-free run passes 123 tests and skips 75 PostgreSQL integration cases (198 discovered).

### PostgreSQL 17 integration tests

For safe backup/restore drills, use [PostgreSQL backup and restore](docs/postgresql-recovery.md). Keep the normal `mvnw verify` run database-free: it passes 123 tests and skips the 75 PostgreSQL integration cases (198 discovered). The PostgreSQL CI job runs only `PostgresSchemaIntegrationTests` and `PostgresLegacyImportMigrationTests`; running every test under the PostgreSQL profile also puts the optional JDBC driver on the classpath for `ApiFoundationTests`, which are designed for the default profile.

Run the PostgreSQL integration class against a uniquely named disposable Compose project, separate from `novel-web-local`. Choose an unused loopback port. This example supplies local-only test credentials so it works from a clean clone without an `.env` file:

```powershell
$disposableProject = 'novel-web-pg-test-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$envNames = 'POSTGRES_PORT', 'POSTGRES_PASSWORD', 'DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD'
$previousEnv = @{}
foreach ($name in $envNames) { $previousEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$env:POSTGRES_PORT = '55432'
$env:POSTGRES_PASSWORD = 'phase4-local-only'
$env:DATABASE_URL = 'jdbc:postgresql://localhost:55432/novelweb'
$env:DATABASE_USERNAME = 'novelweb'
$env:DATABASE_PASSWORD = $env:POSTGRES_PASSWORD
try {
    docker compose -p $disposableProject --profile database up -d --wait --wait-timeout 90 postgres
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the disposable PostgreSQL service.' }
    docker compose -p $disposableProject --profile database exec postgres pg_isready -U novelweb -d novelweb
    if ($LASTEXITCODE -ne 0) { throw 'The disposable PostgreSQL service is not ready.' }
    .\mvnw.cmd -B -ntp -Ppostgres '-Dpostgres.integration=true' '-Dspring.profiles.active=postgres' '-Dtest=PostgresSchemaIntegrationTests,PostgresLegacyImportMigrationTests' verify
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL integration verification failed.' }
}
finally {
    docker compose -p $disposableProject --profile database down --volumes
    foreach ($name in $envNames) {
        if ($null -eq $previousEnv[$name]) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        }
        else {
            Set-Item "Env:$name" $previousEnv[$name]
        }
    }
}
```

This focused command passes 75/75 PostgreSQL integration tests and verifies all 74 Liquibase changesets / 33 application tables, owner and policy gates, concurrency/idempotency, import parse/preview/cancel/commit lifecycles, Reader progress/preferences/bookmark access, fixture/staging cleanup, and a data-bearing forward migration from changeset 55 to 74 that preserves pending, uploaded, failed, cancelled and expired import jobs. Reader preference tests cover defaults, account isolation, reload persistence, CSRF/policy gates and rejection of unsupported fields/ranges. Bookmark tests cover replay, conflicts, same-position dedupe, retained-revision anchors, signed cursor pagination, access rechecks, CSRF/policy gates, deletion and cleanup. Run it only against its disposable database; never target `novel-web-local`. CI retains Surefire and JaCoCo reports for 14 days.

Import-rights slice (2026-11-10, checkpoint before import creation): GET /api/v1/imports/rights-statement/current returns only an effective approved statement; none is seeded locally, so it returns 503. POST /api/v1/imports/rights-statement/acceptance requires a session, session-bound CSRF, current Terms/Privacy acceptance, explicit confirmation and a UUID Idempotency-Key; it records the exact-text SHA-256 and server timestamp, returns the same receipt on retry, and exposes no upload target. Approved statement and acceptance audit fields are protected by PostgreSQL triggers; the acceptance row permits only a one-time consumed_at update for a future atomic import-create flow. The rights-only checkpoint passed 110/110 tests with 51 changesets/25 application tables. The import-create/upload checkpoint before content hardening passed 116/116 tests with 55 changesets/26 application tables; it implements transaction-gated `POST /api/v1/imports`, one-use same-origin upload targets, local streamed storage, actor/token binding and exact-byte SHA-256. Evidence: `../../.ai/evidence/import-create-postgres-2026-11-10/result.md`. A later content-validation checkpoint passed 122/122 tests: UTF-8 text/HTML and bounded EPUB ZIP checks run before uploaded state and rejected bytes are removed while the target remains retryable. Staging cleanup and per-user quotas/rate controls are now implemented and tested; quota reservation remains until terminal staging bytes are deleted. EPUB 3 parse/status and bounded-preview persistence are implemented; private preview GET/PATCH is implemented; atomic private-work commit and production object storage remain open. Evidence: ../../.ai/evidence/import-content-validation-postgres-2026-11-10/result.md, ../../.ai/evidence/import-staging-cleanup-postgres-2026-11-10/result.md, and ../../.ai/evidence/import-staging-quota-rate-limit-postgres-2026-11-11/result.md; parse-job evidence: ../../.ai/evidence/import-parse-job-2026-11-11/result.md.
