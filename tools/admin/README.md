# Administrative operator commands

## Bootstrap the first system administrator

`bootstrap-system-admin.ps1` grants the initial `system_admin` role to one existing active account. It is a one-time database operation, not an application endpoint. It does not create an account, choose an account for you, grant reviewer roles, or enable publication.

### Before running it

1. Use a controlled database where Liquibase changesets `identity-001-users` and `security-044-role-assignments` have already been applied.
2. Identify the intended account from a trusted source and verify its UUID and active status in that same database. The script requires the UUID twice as a typo check.
3. Confirm there is no active `system_admin` assignment and no earlier `BOOTSTRAPPED` event. The database enforces both conditions again while holding a transaction-level advisory lock.
4. Obtain an incident/change ticket and ensure the operator is authorized for this database change. The script records the Windows account/computer, database username, ticket, target account and database timestamp in the append-only audit event.
5. Install the PostgreSQL command-line client (`psql`) on the operator host and make it available on `PATH`. Configure libpq credentials without placing a password in the command line. Since the command uses `psql -w`, authentication must be available non-interactively, for example through a correctly ACL-restricted `%APPDATA%\postgresql\pgpass.conf` or `PGPASSFILE`.

Read-only preflight queries, run against the intended database:

```sql
SELECT id, display_name, status
FROM users
WHERE id = '<verified-account-uuid>'::uuid;

SELECT count(*) AS active_system_admins
FROM security_role_assignments
WHERE role_code = 'system_admin' AND revoked_at IS NULL;

SELECT count(*) AS previous_bootstrap_events
FROM security_role_assignment_events
WHERE role_code = 'system_admin' AND action_code = 'BOOTSTRAPPED';
```

The target row must have `status = 'active'`; both counts must be zero. Do not copy real user identifiers into tickets or shared logs.

### Run

From the repository root in PowerShell, substitute the verified UUID, database coordinates and approved ticket:

```powershell
$targetUserId = '00000000-0000-0000-0000-000000000000'
.\tools\admin\bootstrap-system-admin.ps1 `
  -UserId $targetUserId `
  -ConfirmUserId $targetUserId `
  -DatabaseHost 'localhost' `
  -DatabasePort 5432 `
  -Database 'novelweb' `
  -DatabaseUser 'novelweb_operator' `
  -Ticket 'CHG-12345'
```

The example UUID and ticket are placeholders. The script will fail without `psql`, for a UUID mismatch, if the target is not active, if an administrator/bootstrap event already exists, or if the database connection fails. Do not retry blindly: inspect the role projection and append-only audit event first. A successful result prints the database event and the command cannot be used to bootstrap again.

### Recovery and feature boundaries

The command is intentionally one-time and has no rollback option. If all active administrators are lost, use a separately approved database-operator recovery procedure; that break-glass procedure is not implemented by this script. Keep publication and public discovery disabled until reviewer decision authorization, rights policy, takedown enforcement and release approvals are complete. Never run this command against production as a smoke test.

### Disposable verification

The source launcher was exercised through a temporary `psql` adapter forwarding stdin to a disposable PostgreSQL 17 Docker container. The adapter, database and synthetic accounts were temporary; the persistent `novel-web-local` database and production were not used. See `.ai/evidence/first-admin-bootstrap-2026-11-16/result.md` for cases and results.

To repeat the isolated integration check after changing the bootstrap command, run `pwsh -NoProfile -File tools/admin/test-bootstrap-system-admin.ps1` from the repository root. It requires Docker Desktop and pulls `postgres:17-alpine` if needed. The test uses a unique `--rm` container, applies the repository migrations and synthetic users, forwards the actual launcher stdin through a temporary `psql` adapter, then verifies success, mismatched UUID, replay and inactive-target behavior. It restores the caller's environment and removes only its verified temporary adapter directory.
