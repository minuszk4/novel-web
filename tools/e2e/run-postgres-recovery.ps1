$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apiDirectory = Join-Path $repoRoot 'apps\api'
$composeFile = Join-Path $apiDirectory 'compose.yaml'
$evidenceRoot = Join-Path $repoRoot '.ai\evidence'
$runId = Get-Date -Format 'yyyy-MM-dd-HHmmss'
$evidenceDirectory = Join-Path $evidenceRoot "postgres-data-recovery-$runId"
New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null
$docker = 'docker'
$databaseUser = 'novelweb'
$databaseName = 'novelweb'
$restoredDatabaseName = 'novelweb_recovered'
$project = 'novelweb-recovery-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$container = "$project-postgres-1"
$markerId = [guid]::NewGuid().ToString()
$markerName = 'recovery-marker-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$dumpName = 'novelweb-recovery.dump'
$dumpContainerPath = "/tmp/$dumpName"
$dumpEvidencePath = Join-Path $evidenceDirectory $dumpName
$runnerSourcePath = Join-Path $evidenceDirectory 'LiquibaseRollbackAllDrill.java'
$resultPath = Join-Path $evidenceDirectory 'result.md'
$composeStarted = $false
$result = 'FAIL'
$failure = $null
$cleanup = 'Not started'
$databaseChangesets = 0
$applicationTables = 0
$publicTableCount = 0
$restoredTableCount = 0
$rollbackChangesets = -1
$rollbackApplicationTables = -1
$markerRestored = $false
$rowCountsMatch = $false
$dumpHash = 'Not created'
$mavenExitCode = -1
$mavenTestSummary = 'Not available'
$sourceSnapshot = [System.Collections.Generic.List[string]]::new()
$restoredSnapshot = [System.Collections.Generic.List[string]]::new()

$environmentNames = @(
    'POSTGRES_PORT', 'POSTGRES_PASSWORD', 'POSTGRES_DB', 'POSTGRES_USER',
    'DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Invoke-ScratchPsql {
    param(
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string]$Sql
    )

    $output = & $docker exec $container psql -v ON_ERROR_STOP=1 -U $databaseUser -d $Database -Atc $Sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL query failed in disposable database '$Database': $($output -join ' ')"
    }
    return ($output -join "`n").Trim()
}

try {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()

    $databasePassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    $env:POSTGRES_PORT = [string]$port
    $env:POSTGRES_PASSWORD = $databasePassword
    $env:POSTGRES_DB = $databaseName
    $env:POSTGRES_USER = $databaseUser
    $env:DATABASE_URL = "jdbc:postgresql://127.0.0.1:$port/$databaseName"
    $env:DATABASE_USERNAME = $databaseUser
    $env:DATABASE_PASSWORD = $databasePassword

    $sourceChangesets = @(
        Get-ChildItem (Join-Path $apiDirectory 'src\main\resources\db\changelog\changes') -Filter '*.sql' |
            Select-String -Pattern '^\s*--changeset\s+'
    ).Count
    if ($sourceChangesets -lt 1) { throw 'No source Liquibase changesets were found.' }

    $composeArguments = @('-p', $project, '-f', $composeFile, '--profile', 'database')
    $composeStarted = $true
    $upOutput = & $docker compose @composeArguments up -d --wait --wait-timeout 90 postgres 2>&1
    $upOutput | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'compose-up.log')
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL Compose startup failed.' }

    $readyOutput = & $docker exec $container pg_isready -U $databaseUser -d $databaseName 2>&1
    $readyOutput | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'pg-is-ready.log')
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL did not become ready.' }

    Push-Location $apiDirectory
    try {
        & .\mvnw.cmd -B -ntp -Ppostgres '-Dpostgres.integration=true' '-Dspring.profiles.active=postgres' '-Dtest=PostgresSchemaIntegrationTests,PostgresLegacyImportMigrationTests,PostgresTranslationJobSchemaTests,PostgresTranslationJobEnqueueTests,PostgresTranslationUnitLeaseTests,PostgresQueueMetricsIntegrationTests' clean verify 2>&1 |
            Tee-Object -FilePath (Join-Path $evidenceDirectory 'postgres-maven-verify.log') | Out-Null
        $mavenExitCode = $LASTEXITCODE
    }
    finally { Pop-Location }
    if ($mavenExitCode -ne 0) { throw "PostgreSQL migration/integration verification failed with exit code $mavenExitCode." }
    $testReportPaths = @(
        (Join-Path $apiDirectory 'target\surefire-reports\com.novelweb.platform.postgres.PostgresLegacyImportMigrationTests.txt'),
        (Join-Path $apiDirectory 'target\surefire-reports\com.novelweb.platform.postgres.PostgresQueueMetricsIntegrationTests.txt'),
        (Join-Path $apiDirectory 'target\surefire-reports\com.novelweb.platform.postgres.PostgresSchemaIntegrationTests.txt'),
        (Join-Path $apiDirectory 'target\surefire-reports\com.novelweb.platform.postgres.PostgresTranslationJobSchemaTests.txt'),
        (Join-Path $apiDirectory 'target\surefire-reports\com.novelweb.platform.postgres.PostgresTranslationJobEnqueueTests.txt'),
        (Join-Path $apiDirectory 'target\surefire-reports\com.novelweb.platform.postgres.PostgresTranslationUnitLeaseTests.txt')
    )
    $testSummaries = @()
    foreach ($testReportPath in $testReportPaths) {
        if (-not (Test-Path -LiteralPath $testReportPath)) { throw "Expected Maven test report was not created: $testReportPath" }
        $testSummaryLine = Get-Content -LiteralPath $testReportPath | Where-Object { $_ -match '^Tests run:' } | Select-Object -Last 1
        if ($testSummaryLine -match '^Tests run: (?<run>\d+), Failures: (?<failures>\d+), Errors: (?<errors>\d+), Skipped: (?<skipped>\d+)') {
            $testSummaries += [pscustomobject]@{
                Run = [int]$Matches.run
                Failures = [int]$Matches.failures
                Errors = [int]$Matches.errors
                Skipped = [int]$Matches.skipped
            }
        }
        else {
            throw "Could not parse Maven test summary from $testReportPath."
        }
    }
    $totalTests = ($testSummaries | Measure-Object -Property Run -Sum).Sum
    $totalFailures = ($testSummaries | Measure-Object -Property Failures -Sum).Sum
    $totalErrors = ($testSummaries | Measure-Object -Property Errors -Sum).Sum
    $totalSkipped = ($testSummaries | Measure-Object -Property Skipped -Sum).Sum
    $mavenTestSummary = "$totalTests tests: $($totalTests - $totalFailures - $totalErrors - $totalSkipped) passed, $totalFailures failed, $totalErrors errors, $totalSkipped skipped"
    if ($totalTests -lt 1 -or $totalFailures -ne 0 -or $totalErrors -ne 0 -or $totalSkipped -ne 0) {
        throw "PostgreSQL migration/integration tests did not all pass: $mavenTestSummary"
    }

    $databaseChangesets = [int](Invoke-ScratchPsql -Database $databaseName -Sql 'SELECT count(*) FROM public.databasechangelog;')
    $applicationTables = [int](Invoke-ScratchPsql -Database $databaseName -Sql "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name NOT IN ('databasechangelog','databasechangeloglock');")
    if ($databaseChangesets -ne $sourceChangesets) {
        throw "Disposable schema has $databaseChangesets changesets; source contains $sourceChangesets."
    }

    $tablesOutput = Invoke-ScratchPsql -Database $databaseName -Sql "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;"
    $tables = @($tablesOutput -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $publicTableCount = $tables.Count
    if ($publicTableCount -lt 3) { throw 'The migrated source schema has too few public tables for a restore drill.' }

    $rankCatalogSql = "SELECT count(*) FROM cultivation_rank_catalogs WHERE version = 'cultivation-v1' AND policy_version = 'xp-v1';"
    $rankEntrySql = "SELECT count(*) FROM cultivation_rank_catalog_entries WHERE catalog_version = 'cultivation-v1' AND locale = 'vi-VN';"
    $rankCatalogSeeds = [int](Invoke-ScratchPsql -Database $databaseName -Sql $rankCatalogSql)
    $rankEntrySeeds = [int](Invoke-ScratchPsql -Database $databaseName -Sql $rankEntrySql)
    if ($rankCatalogSeeds -ne 1 -or $rankEntrySeeds -ne 18) {
        throw "Expected the immutable cultivation-v1 seed (one catalog and 18 Vietnamese ranks); found $rankCatalogSeeds catalogs/$rankEntrySeeds ranks."
    }

    $seededDataTables = @('databasechangelog', 'databasechangeloglock', 'cultivation_rank_catalogs', 'cultivation_rank_catalog_entries')
    foreach ($table in $tables) {
        if ($table -notmatch '^[a-z0-9_]+$') { throw "Unexpected public table name '$table'; stopping before destructive operations." }
        $count = Invoke-ScratchPsql -Database $databaseName -Sql "SELECT count(*) FROM public.`"$table`";"
        $sourceSnapshot.Add("$table|$count")
        if ($table -notin $seededDataTables -and [int]$count -ne 0) {
            throw "Integration fixtures remain in public.$table; stopping before destructive operations."
        }
    }

    $insertSql = "INSERT INTO public.users (id, display_name, contact_email, status) VALUES ('$markerId', '$markerName', NULL, 'active');"
    [void](Invoke-ScratchPsql -Database $databaseName -Sql $insertSql)
    $sourceSnapshot.Clear()
    foreach ($table in $tables) {
        $count = Invoke-ScratchPsql -Database $databaseName -Sql "SELECT count(*) FROM public.`"$table`";"
        $sourceSnapshot.Add("$table|$count")
    }
    $sourceSnapshot | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'source-table-counts.txt') -Encoding utf8

    $dumpOutput = & $docker exec $container pg_dump -Fc -U $databaseUser -d $databaseName -f $dumpContainerPath 2>&1
    $dumpOutput | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'dump.log')
    if ($LASTEXITCODE -ne 0) { throw 'Custom-format source backup failed.' }
    $copyOutput = & $docker cp "${container}:$dumpContainerPath" $dumpEvidencePath 2>&1
    $copyOutput | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'docker-copy-dump.log')
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the synthetic backup into the ignored evidence folder.' }
    $dumpHash = (Get-FileHash -LiteralPath $dumpEvidencePath -Algorithm SHA256).Hash

    $createdDbOutput = & $docker exec $container createdb -U $databaseUser $restoredDatabaseName 2>&1
    $createdDbOutput | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'create-recovered-db.log')
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the empty recovery database.' }

    Push-Location $apiDirectory
    try {
        & .\mvnw.cmd -B -ntp -Ppostgres dependency:build-classpath '-Dmdep.outputFile=target/recovery-classpath.txt' '-Dmdep.includeScope=runtime' 2>&1 |
            Tee-Object -FilePath (Join-Path $evidenceDirectory 'maven-classpath.log') | Out-Null
        $classpathExitCode = $LASTEXITCODE
        if ($classpathExitCode -ne 0) { throw 'Could not resolve the Liquibase runtime classpath.' }

        $runtimeClasspath = [System.IO.File]::ReadAllText((Join-Path $apiDirectory 'target\recovery-classpath.txt')).Trim()
        $runnerSource = @'
import java.sql.DriverManager;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

public final class LiquibaseRollbackAllDrill {
    public static void main(String[] args) throws Exception {
        int count = Integer.parseInt(args[0]);
        try (var connection = DriverManager.getConnection(
                System.getenv("DATABASE_URL"),
                System.getenv("DATABASE_USERNAME"),
                System.getenv("DATABASE_PASSWORD"))) {
            var databaseConnection = new JdbcConnection(connection);
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(databaseConnection);
            var accessor = new ClassLoaderResourceAccessor(Thread.currentThread().getContextClassLoader());
            var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", accessor, database);
            liquibase.rollback(count, (String) null);
            System.out.println("Liquibase rollbackCount=" + count + " completed");
        }
    }
}
'@
        [System.IO.File]::WriteAllText($runnerSourcePath, $runnerSource, [System.Text.UTF8Encoding]::new($false))
        $runnerClasspath = "target/classes;$runtimeClasspath"
        & javac -cp $runnerClasspath -d $evidenceDirectory $runnerSourcePath 2>&1 |
            Tee-Object -FilePath (Join-Path $evidenceDirectory 'runner-compile.log')
        if ($LASTEXITCODE -ne 0) { throw 'Liquibase rollback runner compilation failed.' }
        $runnerClasspath = "$evidenceDirectory;$runnerClasspath"
        & java -cp $runnerClasspath LiquibaseRollbackAllDrill $databaseChangesets 2>&1 |
            Tee-Object -FilePath (Join-Path $evidenceDirectory 'rollback-data-bearing.log') | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Full rollback of the disposable source database failed.' }
    }
    finally { Pop-Location }

    $rollbackChangesets = [int](Invoke-ScratchPsql -Database $databaseName -Sql 'SELECT count(*) FROM public.databasechangelog;')
    $rollbackApplicationTables = [int](Invoke-ScratchPsql -Database $databaseName -Sql "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name NOT IN ('databasechangelog','databasechangeloglock');")
    if ($rollbackChangesets -ne 0 -or $rollbackApplicationTables -ne 0) {
        throw "Rollback left $rollbackChangesets changesets and $rollbackApplicationTables application tables."
    }

    $restoreOutput = & $docker exec $container pg_restore --exit-on-error -U $databaseUser -d $restoredDatabaseName $dumpContainerPath 2>&1
    $restoreOutput | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'restore-recovered.log')
    if ($LASTEXITCODE -ne 0) { throw 'Restoring the synthetic backup after rollback failed.' }

    $restoredChangesets = [int](Invoke-ScratchPsql -Database $restoredDatabaseName -Sql 'SELECT count(*) FROM public.databasechangelog;')
    $restoredApplicationTables = [int](Invoke-ScratchPsql -Database $restoredDatabaseName -Sql "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name NOT IN ('databasechangelog','databasechangeloglock');")
    $restoredTableOutput = Invoke-ScratchPsql -Database $restoredDatabaseName -Sql "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;"
    $restoredTables = @($restoredTableOutput -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $restoredTableCount = $restoredTables.Count
    foreach ($table in $restoredTables) {
        $count = Invoke-ScratchPsql -Database $restoredDatabaseName -Sql "SELECT count(*) FROM public.`"$table`";"
        $restoredSnapshot.Add("$table|$count")
    }
    $restoredSnapshot | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'recovered-table-counts.txt') -Encoding utf8
    $difference = @(Compare-Object -ReferenceObject @($sourceSnapshot) -DifferenceObject @($restoredSnapshot))
    $rowCountsMatch = $difference.Count -eq 0 -and $restoredTables.Count -eq $tables.Count
    $markerRestored = (Invoke-ScratchPsql -Database $restoredDatabaseName -Sql "SELECT id || '|' || display_name FROM public.users WHERE id='$markerId';") -eq "$markerId|$markerName"
    if ($restoredChangesets -ne $sourceChangesets -or $restoredApplicationTables -ne $applicationTables) {
        throw "Restored schema is $restoredChangesets changesets/$restoredApplicationTables application tables; expected source $sourceChangesets/$applicationTables."
    }
    if (-not $markerRestored -or -not $rowCountsMatch) { throw 'Restored synthetic marker or one-or-more public table row counts did not match.' }

    $result = 'PASS'
}
catch {
    $failure = $_.Exception.Message
}
finally {
    if ($composeStarted) {
        if ($project -notmatch '^novelweb-recovery-[a-f0-9]{8}$') {
            $cleanup = 'Rejected: project name did not match the disposable-run guard'
        }
        else {
            $downOutput = & $docker compose -p $project -f $composeFile --profile database down --volumes --remove-orphans 2>&1
            $downOutput | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'compose-cleanup.log')
            $downExitCode = $LASTEXITCODE
            $remainingContainers = @(& $docker ps -aq --filter "label=com.docker.compose.project=$project")
            $remainingVolumes = @(& $docker volume ls -q --filter "label=com.docker.compose.project=$project")
            $remainingNetworks = @(& $docker network ls -q --filter "label=com.docker.compose.project=$project")
            if ($downExitCode -eq 0 -and $remainingContainers.Count -eq 0 -and $remainingVolumes.Count -eq 0 -and $remainingNetworks.Count -eq 0) {
                $cleanup = 'Removed and verified only this disposable Compose project/container/network/volume'
            }
            else {
                $cleanup = "FAILED: down=$downExitCode containers=$($remainingContainers.Count) volumes=$($remainingVolumes.Count) networks=$($remainingNetworks.Count)"
                if (-not $failure) { $failure = $cleanup }
                $result = 'FAIL'
            }
        }
    }

    foreach ($name in $environmentNames) {
        if ($null -eq $previousEnvironment[$name]) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        }
        else {
            Set-Item "Env:$name" $previousEnvironment[$name]
        }
    }

    $localContainerStatus = (& $docker inspect --format '{{.State.Health.Status}}' novel-web-local-postgres-1 2>$null)
    if (-not $localContainerStatus) { $localContainerStatus = 'not found' }
    @(
        "# PostgreSQL data-bearing recovery drill — $(Get-Date -Format 'yyyy-MM-dd')"
        ''
        "- Result: $result"
        "- Compose project: $project"
        "- Container: $container"
        "- Loopback port: $port"
        "- Source changesets: $sourceChangesets"
        "- Applied source changesets before backup: $databaseChangesets"
        "- Application tables before rollback: $applicationTables"
        "- Public base tables compared including Liquibase metadata: $publicTableCount"
        "- Liquibase rollback count: $databaseChangesets"
        "- Schema after rollback: $rollbackChangesets changesets / $rollbackApplicationTables application tables"
        "- Restored schema: $restoredTableCount public tables; changesets/application tables verified"
        "- Synthetic marker restored: $markerRestored"
        "- Every public table row count matched: $rowCountsMatch"
        "- Backup SHA-256: $dumpHash"
        "- PostgreSQL integration tests: $mavenTestSummary"
        "- Maven PostgreSQL migration/integration verification exit: $mavenExitCode"
        "- Cleanup: $cleanup"
        "- Persistent novel-web-local health after drill: $localContainerStatus"
        $(if ($failure) { "- Failure: $failure" } else { '- Failure: none' })
        ''
        'The backup contains only synthetic disposable data. No persistent local or production database was targeted.'
        'This drill does not prove rollback safety for real user data, clean-host/offsite recovery, PITR/WAL, retention, RPO/RTO, or release approval.'
    ) | Set-Content -LiteralPath $resultPath -Encoding utf8
}

Get-Content -LiteralPath $resultPath
Write-Output "Evidence: $evidenceDirectory"
if ($result -ne 'PASS') { exit 1 }
