$ErrorActionPreference = 'Stop'

$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if (-not $dockerCommand) {
    throw 'Docker CLI was not found. Install and start Docker Desktop before running this disposable integration check.'
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$bootstrapScript = Join-Path $PSScriptRoot 'bootstrap-system-admin.ps1'
$identityMigration = Join-Path $repositoryRoot 'apps/api/src/main/resources/db/changelog/changes/001-identity-policy.sql'
$roleMigration = Join-Path $repositoryRoot 'apps/api/src/main/resources/db/changelog/changes/044-security-role-assignments.sql'
foreach ($requiredPath in @($bootstrapScript, $identityMigration, $roleMigration)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required bootstrap test input was not found: $requiredPath"
    }
}

$containerName = 'novelweb-admin-bootstrap-test-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$databasePassword = [Guid]::NewGuid().ToString('N')
$databaseName = 'bootstrap_test'
$activeUserId = [Guid]::NewGuid().ToString()
$inactiveUserId = [Guid]::NewGuid().ToString()
$operatorTicket = 'TEST-BOOTSTRAP-' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$temporaryDirectory = Join-Path $temporaryRoot ('novelweb-admin-bootstrap-test-' + [Guid]::NewGuid().ToString('N'))
$resolvedTemporaryDirectory = [IO.Path]::GetFullPath($temporaryDirectory)
if (-not $resolvedTemporaryDirectory.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to create test support files outside the current user temporary directory.'
}

$previousPath = $env:PATH
$previousContainer = $env:NOVELWEB_BOOTSTRAP_TEST_CONTAINER
$previousPassword = $env:NOVELWEB_BOOTSTRAP_TEST_PASSWORD
$containerStarted = $false
New-Item -ItemType Directory -Path $resolvedTemporaryDirectory | Out-Null

try {
    $env:NOVELWEB_BOOTSTRAP_TEST_CONTAINER = $containerName
    $env:NOVELWEB_BOOTSTRAP_TEST_PASSWORD = $databasePassword
    $adapterPath = Join-Path $resolvedTemporaryDirectory 'psql.ps1'
    @'
$dockerArguments = @(
    'exec',
    '-i',
    '-e', "PGPASSWORD=$env:NOVELWEB_BOOTSTRAP_TEST_PASSWORD",
    $env:NOVELWEB_BOOTSTRAP_TEST_CONTAINER,
    'psql'
) + $args
$input | & docker @dockerArguments
exit $LASTEXITCODE
'@ | Set-Content -LiteralPath $adapterPath -Encoding utf8
    $env:PATH = "$resolvedTemporaryDirectory;$previousPath"

    & $dockerCommand.Source run --rm -d --name $containerName `
        -e "POSTGRES_PASSWORD=$databasePassword" `
        -e "POSTGRES_DB=$databaseName" postgres:17-alpine | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not start the disposable PostgreSQL 17 container.'
    }
    $containerStarted = $true

    $databaseReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & $dockerCommand.Source exec $containerName pg_isready -U postgres -d $databaseName *> $null
        if ($LASTEXITCODE -eq 0) {
            $databaseReady = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $databaseReady) {
        throw 'Disposable PostgreSQL did not become ready within 30 seconds.'
    }

    & $dockerCommand.Source cp $identityMigration "${containerName}:/tmp/001-identity-policy.sql" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not copy the identity migration into the disposable database.'
    }
    & $dockerCommand.Source cp $roleMigration "${containerName}:/tmp/044-security-role-assignments.sql" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not copy the role-assignment migration into the disposable database.'
    }

    & $dockerCommand.Source exec $containerName psql -X -v ON_ERROR_STOP=1 -U postgres -d $databaseName -f /tmp/001-identity-policy.sql | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'The identity migration failed in the disposable database.'
    }
    & $dockerCommand.Source exec $containerName psql -X -v ON_ERROR_STOP=1 -U postgres -d $databaseName -f /tmp/044-security-role-assignments.sql | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'The role-assignment migration failed in the disposable database.'
    }

    $seedSql = "INSERT INTO users (id, display_name, status) VALUES ('$activeUserId', 'Synthetic bootstrap account', 'active'), ('$inactiveUserId', 'Synthetic suspended account', 'suspended')"
    & $dockerCommand.Source exec $containerName psql -X -v ON_ERROR_STOP=1 -U postgres -d $databaseName -c $seedSql | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not seed the synthetic test accounts.'
    }

    $commonArguments = @(
        '-DatabaseHost', '127.0.0.1',
        '-DatabasePort', '5432',
        '-Database', $databaseName,
        '-DatabaseUser', 'postgres',
        '-Ticket', $operatorTicket
    )
    $powershellPath = (Get-Process -Id $PID).Path
    $validArguments = @('-UserId', $activeUserId, '-ConfirmUserId', $activeUserId) + $commonArguments
    $successOutput = & $powershellPath -NoLogo -NoProfile -File $bootstrapScript @validArguments 2>&1
    if ($LASTEXITCODE -ne 0 -or ($successOutput -join "`n") -notmatch 'First system administrator recorded') {
        throw "The valid bootstrap invocation failed: $($successOutput -join ' ')"
    }

    $successCounts = & $dockerCommand.Source exec $containerName psql -X -A -t -U postgres -d $databaseName -c "SELECT (SELECT count(*) FROM security_role_assignments WHERE user_id = '$activeUserId' AND role_code = 'system_admin' AND revoked_at IS NULL)::text || '|' || (SELECT count(*) FROM security_role_assignment_events WHERE target_user_id = '$activeUserId' AND role_code = 'system_admin' AND action_code = 'BOOTSTRAPPED')::text"
    if ($LASTEXITCODE -ne 0 -or ($successCounts -join '').Trim() -ne '1|1') {
        throw "Expected one active assignment and one bootstrap event; observed: $($successCounts -join '')"
    }

    $mismatchArguments = @('-UserId', $activeUserId, '-ConfirmUserId', $inactiveUserId) + $commonArguments
    & $powershellPath -NoLogo -NoProfile -File $bootstrapScript @mismatchArguments *> $null
    if ($LASTEXITCODE -eq 0) {
        throw 'A mismatched confirmation UUID was unexpectedly accepted.'
    }

    & $powershellPath -NoLogo -NoProfile -File $bootstrapScript @validArguments *> $null
    if ($LASTEXITCODE -eq 0) {
        throw 'A repeated first-admin bootstrap was unexpectedly accepted.'
    }

    $inactiveArguments = @('-UserId', $inactiveUserId, '-ConfirmUserId', $inactiveUserId) + $commonArguments
    & $powershellPath -NoLogo -NoProfile -File $bootstrapScript @inactiveArguments *> $null
    if ($LASTEXITCODE -eq 0) {
        throw 'A suspended target account was unexpectedly accepted.'
    }

    $finalCounts = & $dockerCommand.Source exec $containerName psql -X -A -t -U postgres -d $databaseName -c "SELECT (SELECT count(*) FROM security_role_assignments WHERE role_code = 'system_admin' AND revoked_at IS NULL)::text || '|' || (SELECT count(*) FROM security_role_assignment_events WHERE role_code = 'system_admin' AND action_code = 'BOOTSTRAPPED')::text"
    if ($LASTEXITCODE -ne 0 -or ($finalCounts -join '').Trim() -ne '1|1') {
        throw "Rejected requests changed the final state; expected 1|1, observed $($finalCounts -join '')"
    }

    Write-Output 'PASS: valid bootstrap creates exactly one active admin and audit event.'
    Write-Output 'PASS: mismatched UUID, replay, and suspended target are rejected.'
    Write-Output 'PASS: final database state remains one active admin and one bootstrap event.'
    Write-Output 'The disposable PostgreSQL 17 container contains synthetic accounts only.'
}
finally {
    if ($containerStarted) {
        & $dockerCommand.Source stop $containerName *> $null
    }

    $env:PATH = $previousPath
    if ($null -eq $previousContainer) {
        Remove-Item Env:NOVELWEB_BOOTSTRAP_TEST_CONTAINER -ErrorAction SilentlyContinue
    }
    else {
        $env:NOVELWEB_BOOTSTRAP_TEST_CONTAINER = $previousContainer
    }
    if ($null -eq $previousPassword) {
        Remove-Item Env:NOVELWEB_BOOTSTRAP_TEST_PASSWORD -ErrorAction SilentlyContinue
    }
    else {
        $env:NOVELWEB_BOOTSTRAP_TEST_PASSWORD = $previousPassword
    }

    if (Test-Path -LiteralPath $resolvedTemporaryDirectory -PathType Container) {
        $resolvedForCleanup = [IO.Path]::GetFullPath($resolvedTemporaryDirectory)
        if (-not $resolvedForCleanup.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolvedForCleanup) -notlike 'novelweb-admin-bootstrap-test-*') {
            throw 'Refusing to remove test files outside their verified unique temporary directory.'
        }
        $adapterForCleanup = Join-Path $resolvedForCleanup 'psql.ps1'
        if (Test-Path -LiteralPath $adapterForCleanup -PathType Leaf) {
            Remove-Item -LiteralPath $adapterForCleanup -Force
        }
        Remove-Item -LiteralPath $resolvedForCleanup -Force
    }
}
