# PostgreSQL backup and restore

This procedure verifies a PostgreSQL custom-format logical dump against a **new, empty database** in a uniquely named disposable Compose project. It does not overwrite the source database. Never point this drill at the persistent `novel-web-local` project or production.

The 2026-11-11 figures below are historical checkpoints. The latest source-level drill (2026-11-13) uses PostgreSQL 17 and verifies all 74 current Liquibase changesets, 34 application tables, and 36 public base tables including Liquibase metadata. It passes 75/75 PostgreSQL migration/integration tests, dumps a synthetic marker row, fully rolls back the scratch schema, restores into a second empty database, and matches the marker and every public table row count. Cleanup removes only its random Compose project; `novel-web-local` remains healthy. Evidence: `.ai/evidence/postgres-data-recovery-2026-11-13-025506/` (Git-ignored).

## One-command disposable recovery drill

From the repository root, run:

```powershell
.\tools\e2e\run-postgres-recovery.ps1
```

The harness selects a free loopback port and unique Compose project, generates process-only test credentials (no `.env` edits), applies the current source migrations, and requires both PostgreSQL test classes to pass with no failures or skipped cases. It refuses to roll back if the schema does not match the source changeset count or any application table still contains integration fixtures. It then creates a synthetic marker and custom-format dump, reads the rollback count from `databasechangelog`, rolls back only the scratch database, restores to a separate empty database and compares every public table's row count. It verifies container/network/volume cleanup and reports the health of `novel-web-local` afterward. Logs, the synthetic backup and a result report are written under `.ai/evidence/postgres-data-recovery-<timestamp>/`.

This command is destructive by design only inside its disposable project. Do not use Liquibase full rollback against a live or user-data database. The one-command test uses synthetic local data and does not prove production recovery, offsite/clean-host restore, PITR, retention or RPO/RTO.

## Disposable local drill (PowerShell)

Run from `apps/api`. Ensure the local `.env` exists and contains the same local-only password in `POSTGRES_PASSWORD` and `DATABASE_PASSWORD`. The project name and port below are examples; choose a unique project and unused loopback port for each drill.

```powershell
$project = 'novelweb-restore-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$env:POSTGRES_PORT = '55433'
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:55433/novelweb'
foreach ($line in Get-Content .env) {
  if ($line -match '^(DATABASE_USERNAME|DATABASE_PASSWORD)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
  }
}

docker compose -p $project --profile database up -d --wait --wait-timeout 90 postgres
docker compose -p $project --profile database exec -T postgres pg_isready -U novelweb -d novelweb
.\mvnw.cmd -B -ntp -Ppostgres '-Dpostgres.integration=true' '-Dspring.profiles.active=postgres' verify
```

Only continue after `verify` passes. The test fixture teardown must leave the application tables empty. For a real backup drill, first provision the intended synthetic or approved data in this disposable source database. Never copy production data into this local evidence bundle.

Create a marker so the drill proves data restoration as well as schema restoration, then create and copy the dump:

```powershell
$markerId = '64ad8216-d1a0-4b7f-8cab-0df665936543'
docker compose -p $project --profile database exec -T postgres psql -v ON_ERROR_STOP=1 -U novelweb -d novelweb -c "INSERT INTO public.users (id, display_name, contact_email, status) VALUES ('$markerId', 'restore-drill-marker', NULL, 'active');"
docker compose -p $project --profile database exec -T postgres pg_dump -Fc -U novelweb -d novelweb -f /tmp/novelweb-restore-drill.dump
New-Item -ItemType Directory -Path '../../.ai/evidence/postgres-restore-drill' -Force | Out-Null
$container = "$project-postgres-1"
docker cp "${container}:/tmp/novelweb-restore-drill.dump" '../../.ai/evidence/postgres-restore-drill/backup.dump'
Get-FileHash '../../.ai/evidence/postgres-restore-drill/backup.dump' -Algorithm SHA256
```

Restore into a separate empty database in the same disposable PostgreSQL server, then validate the expected schema and marker:

```powershell
docker compose -p $project --profile database exec -T postgres createdb -U novelweb novelweb_restore
docker compose -p $project --profile database exec -T postgres pg_restore --exit-on-error -U novelweb -d novelweb_restore /tmp/novelweb-restore-drill.dump
docker compose -p $project --profile database exec -T postgres psql -U novelweb -d novelweb_restore -Atc "SELECT count(*) FROM public.databasechangelog;"
docker compose -p $project --profile database exec -T postgres psql -U novelweb -d novelweb_restore -Atc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name NOT IN ('databasechangelog','databasechangeloglock');"
docker compose -p $project --profile database exec -T postgres psql -U novelweb -d novelweb_restore -Atc "SELECT id || '|' || display_name FROM public.users WHERE id='$markerId';"
```

For the latest source checkpoint, expected output is `74` changesets, `34` application tables, and the marker UUID followed by its synthetic marker name. Re-read the source changelog count after migrations rather than hardcoding it as the project evolves. Compare source and restored row counts for all 36 public tables at this checkpoint; do not treat schema counts alone as proof of a complete restore. Keep the dump outside source control and retain it only for the approved evidence window.

After recording results, remove only this project's container, network, and volume. Run this cleanup even if a verification step fails:

```powershell
docker compose -p $project --profile database down --volumes
Remove-Item Env:POSTGRES_PORT,Env:POSTGRES_DB,Env:POSTGRES_USER,Env:POSTGRES_PASSWORD,Env:DATABASE_URL,Env:DATABASE_USERNAME,Env:DATABASE_PASSWORD -ErrorAction SilentlyContinue
```

## Synthetic data-bearing rollback and recovery drill

This advanced drill runs the destructive Liquibase rollback only against a clean, uniquely named Compose project. It starts from the synthetic custom-format dump above; it does not use the persistent local database or production data. Keep the dump and all evidence in the ignored `.ai/evidence/` directory.

On 2026-11-11, project `novelweb-data-rollback-20261111-870c21` used loopback port `55434` and its own Compose volume. The dump was restored into its clean `novelweb` database. Before rollback, checks confirmed 69 Liquibase changesets, 29 application tables, one synthetic marker user, and the expected counts in all 31 public tables (69 changelog rows, one changelog-lock row, one user row, zero other application rows). A one-off Liquibase runner then ran `rollbackCount=69` against that disposable copy. The rollback removed all 69 changelog entries and all 29 application tables, including the synthetic user. The original dump was then restored into a second empty database in the same disposable container; the marker, schema counts and every public-table row count matched the pre-rollback snapshot exactly. Cleanup removed only the uniquely named project, network and volume; the regular `novel-web-local` database remained healthy and port `55434` was released. This is a historical 69-changeset result; the current 74-changeset repeat is recorded in the evidence linked at the top and produced by `tools/e2e/run-postgres-recovery.ps1`.

The drill's exact row-count snapshots, command logs, runner compile/runtime logs, dump hash and cleanup verification are in `.ai/evidence/postgres-data-bearing-recovery-2026-11-11/`. The dump itself remains in `.ai/evidence/postgres-restore-drill-2026-11-11/` and is Git-ignored. The rollback runner used for this local drill is preserved with its evidence in `.ai/evidence/postgres-full-migration-rollback-2026-11-11/`.

This result demonstrates **destructive rollback and subsequent recovery for a synthetic copy with one marker row**. It does not establish safe rollback semantics for real user data or a production release. Never use the drill's full rollback count as an operational rollback against a populated/live database; prefer a forward fix or restore into a separately provisioned target after an approved recovery decision.

## Scope and limits

- The completed drills restored into newly created empty databases on **local disposable PostgreSQL containers**. They prove the custom-format dump can reconstruct this schema and its synthetic data, including after the scratch schema was fully rolled back; they are not a clean-host, remote-storage, or disaster-recovery certification.
- PostgreSQL roles and cluster-level objects are not part of a normal per-database `pg_dump`. Provision target roles and required extensions before restoring to a different environment, and review ownership/privileges there.
- This drill does not test point-in-time recovery/WAL archiving, encryption at rest or in transit, backup retention, RPO/RTO, credential rotation, storage loss, or an overwrite-in-place recovery.
- A 2026-11-11 one-change probe used Liquibase 5.0.4 `rollbackCount=1` on `imports-069-commit-idempotency`; `databasechangelog` decreased from 69 to 68 and `public.import_commits` disappeared.
- The first full-schema rollback exposed that Liquibase splits rollback SQL on semicolons by default, even when the changeset forward body has `splitStatements:false`. Three PL/pgSQL rollback restorations need their own `rollbackSplitStatements:false`: changesets 065, 067, and 068. After this fix, `rollbackCount=69` removed all 69 changesets; `databasechangelog` contained zero change rows, and the application schema/function count was zero.
- A PostgreSQL-profile Maven `verify` then reapplied the complete schema and passed 138/138. Final state was 69 changesets, 29 application tables and zero integration fixture rows. Runner source and logs are in `.ai/evidence/postgres-full-migration-rollback-2026-11-11/`.
- Current 2026-11-13 repeat against source code: 75/75 PostgreSQL migration/integration tests pass; 74 changesets and 34 application tables are applied. A synthetic marker survives a full `rollbackCount=74` only through restore to a second blank database, and row counts match exactly across all 36 public base tables. The test database is disposable and removed; persistent `novel-web-local` health remains `healthy`. Evidence and generated runner source are under `.ai/evidence/postgres-data-recovery-74-2026-11-13/`.
- The full empty-schema rollback/re-apply ran with no business rows; a separate full rollback ran with one synthetic user row, followed by restore from the preserved dump into a second database with an exact 31-table count match. This does not prove rollback preserves or safely transforms real accumulated rows, cover point-in-time recovery, or restore on a clean host. Review data-loss impact and verify each data-bearing release path on a disposable copy before production use.
