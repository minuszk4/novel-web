# PostgreSQL backup and restore

This procedure verifies a PostgreSQL custom-format logical dump against a **new, empty database** in a uniquely named disposable Compose project. It does not overwrite the source database. Never point this drill at the persistent `novel-web-local` project or production.

The 2026-11-11 local drill used PostgreSQL 17, ran the API's PostgreSQL-profile `verify` suite (138/138), added one synthetic user marker, created a custom-format dump, restored it to `novelweb_restore`, and compared every public table's row count. The restored schema had 69 Liquibase changesets and 29 application tables (31 public base tables including Liquibase metadata); the marker and row counts matched. Evidence is under `.ai/evidence/postgres-restore-drill-2026-11-11/` and is intentionally Git-ignored.

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

Expected output: `69`, `29`, and the marker UUID followed by `restore-drill-marker`. Compare source and restored row counts for all 31 public tables; do not treat schema counts alone as proof of a complete restore. Keep the dump outside source control and retain it only for the approved evidence window.

After recording results, remove only this project's container, network, and volume. Run this cleanup even if a verification step fails:

```powershell
docker compose -p $project --profile database down --volumes
Remove-Item Env:POSTGRES_PORT,Env:DATABASE_URL,Env:DATABASE_USERNAME,Env:DATABASE_PASSWORD -ErrorAction SilentlyContinue
```

## Scope and limits

- The completed drill restored into a newly created empty database on the **same local PostgreSQL container**. It proves the custom-format dump can reconstruct this schema and its synthetic data; it is not a clean-host, remote-storage, or disaster-recovery certification.
- PostgreSQL roles and cluster-level objects are not part of a normal per-database `pg_dump`. Provision target roles and required extensions before restoring to a different environment, and review ownership/privileges there.
- This drill does not test point-in-time recovery/WAL archiving, encryption at rest or in transit, backup retention, RPO/RTO, credential rotation, storage loss, or an overwrite-in-place recovery.
- Liquibase changesets are formatted SQL. No migration rollback was exercised in this drill. Review and test explicit rollback or forward-fix scripts on a disposable database before production migration use.
