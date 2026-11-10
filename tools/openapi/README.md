# OpenAPI contract checks

This tooling lints implemented read-only status/health, PostgreSQL-profile Google bootstrap/callback, session/policy routes, actor-scoped library/work listing, personal library state/collection, import-rights statement/receipt, import-job creation/upload, and EPUB 3 parse/status routes. UTF-8 text/HTML and bounded EPUB ZIP validation run at upload; the durable worker stores bounded private parse preview/status. Preview-content retrieval/edit, commit, cancel and source-only Reader operations remain tagged `planned`. Lint verifies OpenAPI structure; it does not prove that a deployment has owner-approved rights text, production object storage or provider credentials. Progression, translation, publishing and commerce contracts are not yet included.

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
