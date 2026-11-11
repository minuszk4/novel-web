# Secret scanning

CI and local scans use the official Gitleaks container pinned to version `v8.30.1` and its Linux AMD64 image digest. The workflow runs it on pushes, pull requests, and manual dispatches without granting a GitHub token to the scanner.

Run the same checks locally from the repository root:

```powershell
pwsh -File .\tools\security\scan-secrets.ps1
```

The script first creates a temporary synthetic canary and verifies that Gitleaks detects it while redacting it from output. It then scans Git-visible working-tree files and, after the repository has an initial commit, all reachable Git history. `git ls-files --exclude-standard` honors `.gitignore`, so local `.env`, `node_modules`, build output, and the ignored `.ai/` planning folder are not copied into the working-tree scan. A file that is force-added to Git is scanned.

Scanner output uses Gitleaks `--redact`. Treat any finding as a credential incident: revoke or rotate the credential, remove it from source and history as appropriate, then rerun the scan. Do not paste an unredacted secret into an issue, commit, or chat.

The container tag and digest are intentionally explicit. Review the upstream release and platform digest before updating them.

## Dependency vulnerability scanning

The backend CI job runs the official Trivy `0.76.2` container pinned by digest after Maven verification and an explicit dependency resolution for the optional PostgreSQL profile. It scans the Maven and npm lock/manifests, includes development dependencies, and fails on HIGH or CRITICAL vulnerabilities. Maven dependency resolution is offline inside Trivy and uses only the runner's read-only `~/.m2/repository`; the vulnerability database is stored in the runner's temporary directory. This avoids exposing Maven settings or credentials to the scanner.

At the 2026-11-11 local manifest snapshot (`daef08e`), Trivy `0.76.2` scanned the API Maven project, `apps/web/package-lock.json`, and the OpenAPI npm project with development dependencies enabled and found zero HIGH or CRITICAL vulnerabilities. The API scan used a freshly downloaded local vulnerability database and mounted the Maven repository read-only; the web and OpenAPI scans reused that database offline. Scanning the entire `apps/web` directory traversed `node_modules`, Angular caches, build output, coverage, and Playwright reports without finishing promptly on the Windows Docker bind mount, so that attempt was stopped and the dependency lockfile was scanned directly. The Trivy result covers dependency manifests and does not claim a completed whole-root/source scan. The scanned manifests (`apps/api/pom.xml`, `apps/web/package-lock.json`, and `tools/openapi/package-lock.json`) are unchanged from `daef08e` through code HEAD `0e44841`, so the vulnerability results remain the earlier snapshot rather than a refreshed database assessment. `npm audit --audit-level=high` found zero vulnerabilities in both npm projects and `.trivyignore` validates with zero active exceptions. The follow-up Gitleaks v8.30.1 scan at code HEAD `61a6fe9` (after the prior runbook refresh) passed its canary/redaction self-tests, Git-visible source scan, and all reachable local history. Evidence: `.ai/evidence/security-current-head-61a6fe9-2026-11-11/gitleaks.log`. Hosted CI and remote history remain unverified because no Git remote is configured. Rerun dependency scans when manifests change or the vulnerability database snapshot needs refreshing; rerun the secret scan after source/history changes. The repository currently has no Dockerfile or supported IaC configuration for Trivy misconfiguration scanning. Trivy's Docker coverage does not include Compose files, so the local PostgreSQL Compose file is not represented as a successful IaC scan.

## Temporary vulnerability exceptions

`.trivyignore` is empty of active exceptions by default. CI runs `validate-trivy-ignores.ps1` before scanning and passes that same file explicitly to Trivy. An exception is allowed only for a HIGH or CRITICAL CVE/GHSA finding and must include:

- ticket/reference, accountable owner, severity, reason, and compensating mitigation in preceding comments;
- a distinct approver (not the owner);
- an `exp:YYYY-MM-DD` on the finding line, no more than 30 days ahead.

The finding must be re-scanned after the dependency is fixed or the exception expires. Renewals require a new review and expiry. Do not use wildcard IDs, unbounded ignores, or exceptions for secret findings. The validator rejects missing metadata, malformed or expired dates, long expiries, non-HIGH/CRITICAL severity, and self-approval. No current exception is active.

Example review entry (do not copy the placeholder as an active exception):

```text
# Ticket: SEC-123
# Owner: backend-maintainer
# Severity: HIGH
# Reason: patched dependency is temporarily incompatible with the current framework baseline
# Mitigation: endpoint is not exposed; dependency upgrade is scheduled and tracked
# Approved-by: security-reviewer
CVE-2026-12345 exp:2026-11-30
```
