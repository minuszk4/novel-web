# OpenAPI contract checks

This tooling lints implemented read-only status/health, PostgreSQL-profile Google bootstrap/callback, session/policy routes, actor-scoped library/work listing and personal library state/collection; import-rights statement/receipt, import-job creation/upload/cancel, EPUB 3, strict UTF-8 TXT, bounded single-document HTML and CommonMark Markdown parse/status, owner-scoped private preview retrieval/edit and atomic commit routes; source-only Reader manifest, chapter-content, progress, preferences, private bookmark, reading-history and annotation routes; and feature-gated cultivation profile/settings, private daily XP activity pages, plus bounded reading-session start/heartbeat/close. Activity pages contain only UTC date and XP total, use an HMAC-signed account-bound cursor and never join or return story metadata. Reader-session activity atomically awards 1 XP per complete active minute, caps at 120 XP per UTC date, keeps sub-minute remainder within that date only, and updates an immutable ledger plus rebuildable daily projection. Heartbeat/close responses report awarded XP; the cultivation profile reports lifetime and current-day XP. The global cultivation flag remains off pending the rank-threshold pilot, abuse-boundary review and release decision. UTF-8 text/HTML and bounded EPUB ZIP validation run at upload; the durable worker stores bounded private parse preview/status. TXT imports become one `Nội dung` chapter and private commit stores `plain_text` revisions with stable `txt-*` anchors; HTML becomes one chapter, removes active/non-content markup without running or fetching it, and commits `xhtml` revisions with stable `html-*` anchors; EPUB commit transfers `epub3-v2` source segments as `epub_xhtml` revisions transactionally. Markdown preview data remains private until commit; conversion uses CommonMark core, discards raw HTML and link destinations, keeps visible labels/image alt text and commits markdown revisions with md-* anchors. Preview pages expose metadata and chapter titles only, use owner/import/revision-bound signed cursors, and support optimistic edits with `If-Match`. Cancellation requires the session-bound CSRF token, hides foreign jobs as 404, is idempotent after success, and defers janitor deletion while a parser lease is active; completed/expired jobs return 409. Reader manifests for private/workspace or public works never return body text. Public manifests expose the server-calculated `readable` or `locked` state; chapter content rechecks current library/workspace access or a public chapter entitlement, returns only bounded normalized segments from a readable revision, and uses actor/resource/revision-bound cursors with `no-store`. Reader preferences, progress and bookmarks are persisted per account in the PostgreSQL profile. Bookmark create uses a 24-hour actor-scoped idempotency record, checks stable anchors against retained revisions, and rechecks current access before returning a replay; list pages use a signed actor/work-bound cursor and delete is owner-scoped. Paid-credit chapter unlock quote/debit routes are MoMo TEST-gated and implemented; preview ranges, production commerce and rights-approved sale remain outside the current contract. Lint verifies OpenAPI structure; it does not prove that a deployment has owner-approved rights text, production object storage or provider credentials. Cultivation FE wiring/activity UI and glossary revision diff/lint/sample-match preview (including term order and context notes) are implemented. Cultivation remains feature-gated pending rank/release review; per-term CRUD/export and translation remain incomplete.

The fake-local Translation Desk contract includes writer-only candidate segment corrections as private append-only revisions. Corrections preserve generated placeholders, use `If-Match` and idempotency, and do not approve, publish, grant Reader access or call an external translation provider.

## Run locally

Requires Node.js 22.12+ and npm 10.9.2+. From this directory, install the exact locked tool version and lint the contract:

```powershell
npm ci
$env:REDOCLY_TELEMETRY = 'off'
npm run lint
npm audit --audit-level=high
```

Redocly CLI is pinned in `package.json` and `package-lock.json`. The same commands run in the OpenAPI job in `.github/workflows/ci.yml`.

## Check a proposed contract change

Compare the current contract with the version from the target branch:

```powershell
npm run diff -- <path-to-base-openapi.yaml> ../../apps/api/src/main/resources/openapi.yaml --fail-on=none --check-version
```

The pull-request workflow fetches the base contract and runs this check automatically. `--check-version` fails when `info.version` does not meet the semantic-version bump implied by the change. The Google credential callback intentionally returns `303` on success, so Redocly's generic `operation-2xx-response` rule reports one warning for that redirect-only operation. The Redocly `diff` command is currently experimental, so reviewers must still verify that a breaking change has a route/migration plan and that repository branch protection requires the OpenAPI job before merge.
