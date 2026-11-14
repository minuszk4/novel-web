# Secret scanning

CI and local scans use the official Gitleaks container pinned to version `v8.30.1` and its Linux AMD64 image digest. The workflow runs it on pushes, pull requests, and manual dispatches without granting a GitHub token to the scanner.

Run the same checks locally from the repository root:

```powershell
pwsh -File .\tools\security\scan-secrets.ps1
```

The script first creates a temporary synthetic canary and verifies that Gitleaks detects it while redacting it from output. It then scans Git-visible working-tree files and, after the repository has an initial commit, all reachable Git history. `git ls-files --exclude-standard` honors `.gitignore`, so local `.env`, `node_modules`, build output, and the ignored `.ai/` planning folder are not copied into the working-tree scan. A file that is force-added to Git is scanned.

Both scans use the checked-in `.gitleaksignore`. It contains nine fingerprints for fixed test-only HMAC/idempotency fixtures found in older commits; it stores no matched or secret values. The corresponding current test fixtures use deterministic low-entropy values, so the working-tree scan stays clean. The ignore list suppresses only those exact historical fingerprints; newly introduced findings remain scan failures. Review additions as security-sensitive changes and do not use it for live credentials.

Scanner output uses Gitleaks `--redact`. Treat any finding as a credential incident: revoke or rotate the credential, remove it from source and history as appropriate, then rerun the scan. Do not paste an unredacted secret into an issue, commit, or chat.

The container tag and digest are intentionally explicit. Review the upstream release and platform digest before updating them.

## Dependency vulnerability scanning

The backend CI job runs the official Trivy `0.76.2` container pinned by digest after Maven verification and dependency resolution for the optional PostgreSQL profile. The regular filesystem scan covers the Maven POM and npm lockfiles, including npm development dependencies. Trivy's Maven POM analyzer does not include Maven `test` scope, so CI also generates a CycloneDX SBOM with `includeTestScope=true` and runs Trivy's SBOM scanner over the complete Maven test classpath. Both scans fail on HIGH or CRITICAL vulnerabilities. Maven resolves its dependency graph before Trivy starts; the scanner receives only the runner's read-only `~/.m2/repository` mount, not Maven settings or credentials. Trivy's filesystem pass uses `--offline-scan`, and its vulnerability database is stored in the runner's temporary cache directory. The container gives `/tmp` a 1 GiB tmpfs because the current Trivy database download exceeds the previous 256 MiB limit.

At the 2026-11-11 dependency snapshot at code HEAD `ae4001c`, a fresh Trivy `0.76.2` database reported zero HIGH/CRITICAL findings in the API POM, web lockfile and OpenAPI lockfile. A CycloneDX Maven SBOM included 121 components across compile, provided, runtime, system and test scopes, including the `org.liquibase:liquibase-core:5.0.4` test dependency; Trivy's SBOM scan also reported zero HIGH/CRITICAL findings. Both npm audits report zero vulnerabilities, `.trivyignore` validates with zero active exceptions, and Maven PostgreSQL-profile dependency resolution succeeds. These dependency results remain tied to that manifest snapshot. A separate Gitleaks v8.30.1 run at current code HEAD `a32211d` passed both detection/redaction canaries, the Git-visible working tree and all reachable local history. Evidence: `.ai/evidence/security-current-head-ae4001c-2026-11-11/` for dependency scans and `.ai/evidence/security-current-head-a32211d-2026-11-11/gitleaks.log` for the current secret scan. The earlier `daef08e`/`61a6fe9` results remain historical. Scanning the entire `apps/web` directory with Trivy still traverses `node_modules`, Angular caches, build output, coverage and Playwright reports; the dependency scan uses a manifest-only snapshot and does not claim a whole-root Trivy scan. Gitleaks separately scans the Git-visible source and reachable history. Hosted CI and remote history remain unverified because no Git remote is configured. Rerun dependency scans when manifests change or the vulnerability database snapshot needs refreshing; rerun the secret scan after source/history changes. The repository currently has no Dockerfile or supported IaC configuration for Trivy misconfiguration scanning. Trivy's Docker coverage does not include Compose files, so the local PostgreSQL Compose file is not represented as a successful IaC scan.

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
