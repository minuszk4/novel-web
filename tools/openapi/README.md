# OpenAPI contract checks

This tooling lints the implemented read-only endpoints (`GET /api/v1/system/status` and `GET /actuator/health`) plus the planned G2 Auth/session/policy, private library/import and source-only Reader operations tagged `x-implementation-status: planned`. Lint verifies OpenAPI structure; it does not prove that a planned endpoint exists in Spring Boot. These business operations remain unavailable until their API slices and acceptance tests are implemented. Progression, translation, publishing and commerce contracts are not yet included.

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
