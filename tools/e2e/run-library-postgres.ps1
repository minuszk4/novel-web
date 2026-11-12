param(
    [int]$PostgresPortStart = 55432,
    [int]$PostgresPortEnd = 55480
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apiDirectory = Join-Path $repoRoot 'apps\api'
$webDirectory = Join-Path $repoRoot 'apps\web'
$evidenceDirectory = Join-Path $repoRoot (Join-Path '.ai\evidence' ("library-live-postgres-{0}" -f (Get-Date -Format 'yyyy-MM-dd')))
New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null

$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$docker = if ($dockerCommand) { $dockerCommand.Source } else {
    Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
}
if (-not (Test-Path -LiteralPath $docker)) {
    throw 'Docker CLI not found. Add Docker Desktop to PATH or install its Windows CLI.'
}

$project = 'novel-web-disposable-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$composeArguments = @('compose', '-f', (Join-Path $apiDirectory 'compose.yaml'), '-p', $project, '--profile', 'database')
$hostPort = $null
for ($port = $PostgresPortStart; $port -le $PostgresPortEnd; $port++) {
    if (@(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).Count -eq 0) {
        $hostPort = $port
        break
    }
}
if (-not $hostPort) { throw "No free PostgreSQL port in range $PostgresPortStart-$PostgresPortEnd." }

foreach ($port in @(8081, 4300)) {
    if (@(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).Count -gt 0) {
        throw "Port $port is already listening. Stop that process before running this isolated FE/API journey."
    }
}

$environmentNames = @(
    'POSTGRES_PORT', 'POSTGRES_PASSWORD', 'DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD',
    'GOOGLE_AUTH_RATE_LIMIT_HMAC_KEY', 'NOVEL_CSRF_HMAC_KEY', 'NOVEL_LIBRARY_CURSOR_HMAC_KEY',
    'NOVEL_WEB_CORS_ALLOWED_ORIGINS', 'LIBRARY_LIVE_TEST_SESSION_TOKEN', 'LIBRARY_LIVE_TEST_LIBRARY_ID',
    'LIBRARY_LIVE_TEST_WORK_IDS', 'LIBRARY_LIVE_TEST_SCREENSHOT', 'READER_LIVE_TEST_WORK_ID',
    'READER_LIVE_TEST_CHAPTER_ID', 'READER_LIVE_TEST_SCREENSHOT'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$composeStarted = $false
$apiProcess = $null
$result = 'FAIL'
$failure = $null
$cleanup = 'Not started'
$appliedChangesets = 0
$startedAt = Get-Date
$postgresPassword = [guid]::NewGuid().ToString('N')
$sessionBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($sessionBytes)
$sessionToken = [Convert]::ToBase64String($sessionBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$sessionHash = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData($sessionBytes))
$userId = [guid]::NewGuid()
$libraryId = [guid]::NewGuid()
$firstWorkId = [guid]::NewGuid()
$secondWorkId = [guid]::NewGuid()
$readerChapterId = [guid]::NewGuid()
$readerVolumeId = [guid]::NewGuid()
$readerRevisionId = [guid]::NewGuid()
$readerSegmentId = [guid]::NewGuid()
$readerSourceText = 'Live E2E source text is served from PostgreSQL.'
$readerSourceHash = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($readerSourceText))
).ToLowerInvariant()
$screenshotPath = Join-Path $evidenceDirectory 'account-library-live.png'
$readerScreenshotPath = Join-Path $evidenceDirectory 'account-reader-live.png'
$buildLog = Join-Path $evidenceDirectory 'api-build.log'
$apiStdout = Join-Path $evidenceDirectory 'api-stdout.log'
$apiStderr = Join-Path $evidenceDirectory 'api-stderr.log'
$e2eLog = Join-Path $evidenceDirectory 'playwright.log'

try {
    $env:POSTGRES_PORT = [string]$hostPort
    $env:POSTGRES_PASSWORD = $postgresPassword
    $env:DATABASE_URL = "jdbc:postgresql://localhost:$hostPort/novelweb"
    $env:DATABASE_USERNAME = 'novelweb'
    $env:DATABASE_PASSWORD = $postgresPassword
    $env:GOOGLE_AUTH_RATE_LIMIT_HMAC_KEY = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
    $env:NOVEL_CSRF_HMAC_KEY = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
    $env:NOVEL_LIBRARY_CURSOR_HMAC_KEY = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
    $env:NOVEL_WEB_CORS_ALLOWED_ORIGINS = 'http://localhost:4300'

    Write-Output "Disposable Compose project: $project"
    Write-Output "PostgreSQL host port: $hostPort"
    Push-Location $apiDirectory
    try {
        & .\mvnw.cmd -B -ntp -Ppostgres -DskipTests package *> $buildLog
        $buildExit = $LASTEXITCODE
    } finally { Pop-Location }
    if ($buildExit -ne 0) {
        Get-Content -LiteralPath $buildLog -Tail 50
        throw "API package build failed (exit $buildExit)."
    }

    $composeStarted = $true
    & $docker @composeArguments up -d postgres
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL failed to start.' }
    $databaseReady = $false
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        & $docker @composeArguments exec -T postgres pg_isready -U novelweb -d novelweb *> $null
        if ($LASTEXITCODE -eq 0) { $databaseReady = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $databaseReady) { throw 'Disposable PostgreSQL did not become ready within 60 seconds.' }

    $javaCommand = Get-Command java -ErrorAction Stop
    $apiJar = Join-Path $apiDirectory 'target\api-0.0.1-SNAPSHOT.jar'
    $apiArguments = @('-jar', ('"{0}"' -f $apiJar), '--spring.profiles.active=postgres', '--server.port=8081')
    $apiProcess = Start-Process -FilePath $javaCommand.Source -ArgumentList $apiArguments `
        -WorkingDirectory $apiDirectory -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $apiStdout -RedirectStandardError $apiStderr

    $apiReady = $false
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        $apiProcess.Refresh()
        if ($apiProcess.HasExited) { throw "API exited during startup (code $($apiProcess.ExitCode))." }
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8081/actuator/health' -TimeoutSec 2
            if ($health.status -eq 'UP') { $apiReady = $true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $apiReady) {
        Get-Content -LiteralPath $apiStdout -Tail 35 -ErrorAction SilentlyContinue
        Get-Content -LiteralPath $apiStderr -Tail 35 -ErrorAction SilentlyContinue
        throw 'API did not report healthy within 120 seconds.'
    }

    $schemaReady = $false
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        $changeCount = & $docker @composeArguments exec -T postgres psql -tA -U novelweb -d novelweb -c 'SELECT COUNT(*) FROM databasechangelog' 2>$null
        if ($LASTEXITCODE -eq 0 -and ([string]$changeCount).Trim() -eq '73') {
            $schemaReady = $true
            $appliedChangesets = 73
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $schemaReady) { throw 'The disposable PostgreSQL database did not reach the 73-changeset schema.' }

    $fixtureSql = @"
BEGIN;
INSERT INTO users (id, display_name, contact_email, email_verified_at, status)
VALUES ('$userId', 'Live E2E Reader', 'live-$userId@example.test', clock_timestamp(), 'active');
INSERT INTO policy_documents (policy_type, version, effective_at, document_markdown) VALUES
('TERMS', 'terms-live-e2e-v1', clock_timestamp() - INTERVAL '10 minutes', '# Test terms`nSynthetic document for a disposable integration test.'),
('PRIVACY_NOTICE', 'privacy-live-e2e-v1', clock_timestamp() - INTERVAL '10 minutes', '# Test privacy notice`nSynthetic document for a disposable integration test.');
INSERT INTO policy_acceptances (id, user_id, policy_type, version, decision, recorded_at, source) VALUES
(gen_random_uuid(), '$userId', 'TERMS', 'terms-live-e2e-v1', 'TERMS_ACCEPTED', clock_timestamp(), 'e2e-fixture'),
(gen_random_uuid(), '$userId', 'PRIVACY_NOTICE', 'privacy-live-e2e-v1', 'PRIVACY_NOTICE_ACKNOWLEDGED', clock_timestamp(), 'e2e-fixture');
INSERT INTO sessions (id_hash, user_id, expires_at)
VALUES (decode('$sessionHash', 'hex'), '$userId', clock_timestamp() + INTERVAL '1 day');
INSERT INTO libraries (id, owner_user_id, name, visibility, is_default)
VALUES ('$libraryId', '$userId', 'Live E2E Library', 'private', TRUE);
INSERT INTO works (id, library_id, owner_user_id, title, author, source_language, visibility, status)
VALUES
('$firstWorkId', '$libraryId', '$userId', 'Live E2E Novel One', 'E2E Author', 'vi', 'private', 'active'),
('$secondWorkId', '$libraryId', '$userId', 'Live E2E Novel Two', 'E2E Author', 'en', 'private', 'active');
INSERT INTO volumes (id, work_id, ordinal, title)
VALUES ('$readerVolumeId', '$firstWorkId', 1, 'Live E2E Volume');
INSERT INTO chapters (id, work_id, volume_id, ordinal, title, source_language, source_revision_id, status)
VALUES ('$readerChapterId', '$firstWorkId', '$readerVolumeId', 1, 'Live Reader Chapter', 'en', '$readerRevisionId', 'ready');
INSERT INTO chapter_revisions (id, chapter_id, revision_no, source_format, normalized_content, content_hash, created_by_user_id)
VALUES ('$readerRevisionId', '$readerChapterId', 1, 'plain_text', '$readerSourceText', decode('$readerSourceHash', 'hex'), '$userId');
INSERT INTO segments (id, chapter_revision_id, ordinal, segment_type, source_text, stable_anchor, checksum)
VALUES ('$readerSegmentId', '$readerRevisionId', 1, 'paragraph', '$readerSourceText', 'live-reader-p1', decode('$readerSourceHash', 'hex'));
COMMIT;
"@
    & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -U novelweb -d novelweb -c $fixtureSql
    if ($LASTEXITCODE -ne 0) { throw 'Disposable browser-session fixtures could not be inserted.' }

    $env:LIBRARY_LIVE_TEST_SESSION_TOKEN = $sessionToken
    $env:LIBRARY_LIVE_TEST_LIBRARY_ID = [string]$libraryId
    $env:LIBRARY_LIVE_TEST_WORK_IDS = "$firstWorkId,$secondWorkId"
    $env:LIBRARY_LIVE_TEST_SCREENSHOT = $screenshotPath
    $env:READER_LIVE_TEST_WORK_ID = [string]$firstWorkId
    $env:READER_LIVE_TEST_CHAPTER_ID = [string]$readerChapterId
    $env:READER_LIVE_TEST_SCREENSHOT = $readerScreenshotPath
    Push-Location $webDirectory
    try {
        & npm.cmd run test:e2e:postgres -- e2e/library-live-postgres.spec.ts e2e/reader-live-postgres.spec.ts *>&1 | Tee-Object -FilePath $e2eLog
        $e2eExit = $LASTEXITCODE
    } finally { Pop-Location }
    if ($e2eExit -ne 0) { throw "Live FE/API/PostgreSQL Playwright journey failed (exit $e2eExit)." }
    $result = 'PASS'
} catch {
    $failure = $_.Exception.Message
    Write-Output "TASK ERROR: $failure"
    if (Test-Path -LiteralPath $e2eLog) { Get-Content -LiteralPath $e2eLog -Tail 50 }
    if (Test-Path -LiteralPath $apiStderr) { Get-Content -LiteralPath $apiStderr -Tail 30 }
} finally {
    if ($apiProcess) {
        $apiProcess.Refresh()
        if (-not $apiProcess.HasExited) {
            Stop-Process -Id $apiProcess.Id -Force -ErrorAction SilentlyContinue
            $apiProcess.WaitForExit(5000) | Out-Null
        }
    }
    if ($composeStarted) {
        if ($project -notmatch '^novel-web-disposable-[a-f0-9]{8}$') { throw "Cleanup guard rejected project name: $project" }
        $ownedContainers = @(& $docker ps -a --filter "label=com.docker.compose.project=$project" --format '{{.Names}}')
        if ($ownedContainers.Count -gt 0) {
            & $docker @composeArguments down --volumes
            if ($LASTEXITCODE -ne 0) { $cleanup = 'FAILED'; $result = 'FAIL' }
            else { $cleanup = 'Removed only the disposable container, network and volume' }
        } else { $cleanup = 'No disposable container remained' }
    }

    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    $duration = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)
    @(
        '# Browser journey: frontend to PostgreSQL library and Reader'
        ''
        "- Date: $($startedAt.ToString('yyyy-MM-dd HH:mm:ss zzz'))"
        "- Result: $result"
        "- Compose project: $project"
        "- PostgreSQL host port: $hostPort"
        '- API: built from the current workspace and started with the PostgreSQL profile'
        "- Schema: $appliedChangesets Liquibase changesets applied before fixture setup"
        '- Browser: Playwright Chromium, Angular dev-server proxy to the real API'
        '- Journey: session cookie/policy → libraries/works/per-work state → Reader manifest/content → persisted bookmark and reload'
        '- API route mocks: none'
        "- Elapsed: $duration seconds"
        "- Cleanup: $cleanup"
        "- Screenshots: $([System.IO.Path]::GetFileName($screenshotPath)), $([System.IO.Path]::GetFileName($readerScreenshotPath))"
        '- Logs: api-build.log, api-stdout.log, api-stderr.log, playwright.log'
        $(if ($failure) { "- Failure: $failure" } else { '- Failure: none' })
        ''
        'The synthetic user, accepted test-only policy rows, session hash, library and works existed only in this randomly named disposable PostgreSQL project. The persistent novel-web-local project was not targeted.'
    ) | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'result.md') -Encoding utf8
    Write-Output "Cleanup: $cleanup"
    Write-Output "Evidence: $evidenceDirectory"
}

if ($result -ne 'PASS') { exit 1 }
