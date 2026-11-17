param(
    [int]$PostgresPortStart = 55432,
    [int]$PostgresPortEnd = 55480,
    [switch]$IncludeTranslationPreview,
    [switch]$IncludeTranslationReviewWorkspace,
    [switch]$IncludeAuthoringOutline,
    [switch]$IncludeAuthoringChapterEditor,
    [switch]$IncludeAuthoringDraftApi,
    [switch]$IncludeAuthoringEmptyChapterDelete,
    [switch]$IncludePublicationRightsAcceptance,
    [switch]$IncludePublicationReviewWorkspace,
    [switch]$IncludePaymentReturn,
    [switch]$IncludeCommerceSettlement,
    [switch]$IncludeCultivationRankUp,
    [switch]$IncludeNotificationInbox,
    [switch]$IncludeDatabaseRestartRecovery
)

$ErrorActionPreference = 'Stop'
if ($IncludeNotificationInbox) { $IncludePublicationReviewWorkspace = $true }
if ($IncludeTranslationReviewWorkspace) {
    $IncludeTranslationPreview = $true
    $IncludePublicationReviewWorkspace = $true
}
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apiDirectory = Join-Path $repoRoot 'apps\api'
$webDirectory = Join-Path $repoRoot 'apps\web'
$evidenceRunId = Get-Date -Format 'yyyy-MM-dd-HHmmss'
$evidenceDirectory = Join-Path $repoRoot (Join-Path '.ai\evidence' ("library-live-postgres-{0}" -f $evidenceRunId))
New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null
$importStagingRoot = Join-Path $evidenceDirectory 'import-staging'
$changelogDirectory = Join-Path $apiDirectory 'src\main\resources\db\changelog\changes'
$expectedChangesets = @(Select-String -Path (Join-Path $changelogDirectory '*.sql') -Pattern '^\s*--changeset\s+').Count
if ($expectedChangesets -lt 1) { throw 'No Liquibase changesets were found in the API source.' }

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
    'NOVEL_WEB_CORS_ALLOWED_ORIGINS', 'NOVEL_FEATURE_CULTIVATION_ENABLED',
    'NOVEL_FEATURE_COMMERCE_SANDBOX_ENABLED', 'NOVEL_NOTIFICATIONS_DELIVERY_ENABLED',
    'NOVEL_NOTIFICATIONS_DELIVERY_POLL_DELAY',
    'NOVEL_TRANSLATION_FAKE_ENABLED', 'NOVEL_TRANSLATION_FAKE_API_ENABLED',
    'NOVEL_TRANSLATION_FAKE_WORKER_ENABLED', 'NOVEL_TRANSLATION_REVIEW_ENABLED',
    'NOVEL_TRANSLATION_PUBLICATION_ENABLED',
    'NOVEL_MOMO_TEST_ENABLED', 'NOVEL_MOMO_TEST_RECONCILIATION_ENABLED', 'MOMO_ENV',
    'MOMO_TEST_PARTNER_CODE', 'MOMO_TEST_ACCESS_KEY', 'MOMO_TEST_SECRET_KEY',
    'MOMO_TEST_BASE_URL', 'MOMO_TEST_IPN_URL', 'MOMO_TEST_REDIRECT_URL',
    'LIBRARY_LIVE_TEST_SESSION_TOKEN', 'LIBRARY_LIVE_TEST_LIBRARY_ID',
    'MOMO_PAYMENT_RETURN_LIVE_TEST_SESSION_TOKEN', 'MOMO_PAYMENT_RETURN_LIVE_TEST_ORDER_ID',
    'MOMO_PAYMENT_RETURN_LIVE_TEST_SCREENSHOT',
    'MOMO_COMMERCE_LIVE_TEST_SESSION_TOKEN', 'MOMO_COMMERCE_LIVE_TEST_OTHER_SESSION_TOKEN',
    'MOMO_COMMERCE_LIVE_TEST_ORDER_ID',
    'MOMO_COMMERCE_LIVE_TEST_REQUEST_ID', 'MOMO_COMMERCE_LIVE_TEST_WORK_ID',
    'MOMO_COMMERCE_LIVE_TEST_PRIVATE_WORK_ID', 'MOMO_COMMERCE_LIVE_TEST_PRIVATE_CHAPTER_ID',
    'MOMO_COMMERCE_LIVE_TEST_CHAPTER_ID', 'MOMO_COMMERCE_LIVE_TEST_SUCCESS_SCREENSHOT',
    'MOMO_COMMERCE_LIVE_TEST_UNLOCK_SCREENSHOT',
    'NOVEL_IMPORT_STAGING_ROOT', 'LIBRARY_LIVE_TEST_WORK_IDS', 'LIBRARY_LIVE_TEST_SCREENSHOT', 'READER_LIVE_TEST_WORK_ID',
    'AUTHORING_LIVE_TEST_TITLE', 'AUTHORING_LIVE_TEST_SCREENSHOT',
    'AUTHORING_OUTLINE_LIVE_TEST_WORK_ID', 'AUTHORING_OUTLINE_LIVE_TEST_SCREENSHOT',
    'AUTHORING_OUTLINE_LIVE_TEST_EXPECTED_TITLE',
    'PUBLICATION_RIGHTS_LIVE_TEST_WORK_ID', 'PUBLICATION_RIGHTS_LIVE_TEST_SCREENSHOT',
    'PUBLICATION_RIGHTS_LIVE_TEST_VERSION', 'PUBLICATION_RIGHTS_LIVE_TEST_TEXT', 'PUBLICATION_RIGHTS_LIVE_TEST_SHA256',
    'PUBLICATION_REVIEW_LIVE_TEST_REVIEWER_SESSION_TOKEN', 'PUBLICATION_REVIEW_LIVE_TEST_WORK_ID',
    'PUBLICATION_REVIEW_LIVE_TEST_SCREENSHOT', 'PUBLICATION_REVIEW_LIVE_TEST_CHAPTER_SCREENSHOT',
    'NOVEL_PUBLICATION_RELEASE_ENABLED',
    'AUTHORING_EDITOR_LIVE_TEST_WORK_ID', 'AUTHORING_EDITOR_LIVE_TEST_CHAPTER_ID',
    'AUTHORING_EDITOR_LIVE_TEST_SCREENSHOT',
    'TRANSLATION_LIVE_TEST_WORK_ID', 'TRANSLATION_LIVE_TEST_CHAPTER_ID', 'TRANSLATION_LIVE_TEST_SCREENSHOT',
    'TRANSLATION_REVIEW_LIVE_TEST_REVIEWER_SESSION_TOKEN', 'TRANSLATION_REVIEW_LIVE_TEST_STAGE',
    'TRANSLATION_REVIEW_LIVE_TEST_SUBMISSION_ID', 'TRANSLATION_REVIEW_LIVE_TEST_SNAPSHOT_DIGEST',
    'READER_LIVE_TEST_CHAPTER_ID', 'READER_LIVE_TEST_SCREENSHOT',
    'CULTIVATION_LIVE_TEST_RANK_UP', 'CULTIVATION_LIVE_TEST_SCREENSHOT', 'CULTIVATION_LIVE_TEST_TRACE',
    'WORK_DETAIL_LIVE_TEST_SCREENSHOT', 'PRIVATE_IMPORT_LIVE_TEST_SCREENSHOT', 'NOTIFICATION_LIVE_TEST_SCREENSHOT',
    'GLOSSARY_LIVE_TEST_GLOSSARY_ID', 'GLOSSARY_LIVE_TEST_SCREENSHOT', 'GLOSSARY_EDITOR_LIVE_TEST_SCREENSHOT'
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
$stagingCleanup = 'Not created'
$appliedChangesets = 0
$cultivationLedgerXp = 0
$cultivationLedgerEntries = 0
$glossaryParts = @()
$publicationRightsParts = @()
$publicationReviewParts = @()
$translationReviewParts = @()
$notificationParts = @()
$authoringEditorParts = @()
$databaseRestartSummary = @()
$startedAt = Get-Date
$postgresPassword = [guid]::NewGuid().ToString('N')
$sessionBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($sessionBytes)
$sessionToken = [Convert]::ToBase64String($sessionBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$sessionHash = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData($sessionBytes))
$commerceOtherSessionBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($commerceOtherSessionBytes)
$commerceOtherSessionToken = [Convert]::ToBase64String($commerceOtherSessionBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$commerceOtherSessionHash = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData($commerceOtherSessionBytes))
$paymentReturnSessionBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($paymentReturnSessionBytes)
$paymentReturnSessionToken = [Convert]::ToBase64String($paymentReturnSessionBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$paymentReturnSessionHash = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData($paymentReturnSessionBytes))
$publicationReviewSessionBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($publicationReviewSessionBytes)
$publicationReviewSessionToken = [Convert]::ToBase64String($publicationReviewSessionBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$publicationReviewSessionHash = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData($publicationReviewSessionBytes))
$userId = [guid]::NewGuid()
$paymentReturnUserId = [guid]::NewGuid()
$libraryId = [guid]::NewGuid()
$firstWorkId = [guid]::NewGuid()
$secondWorkId = [guid]::NewGuid()
$readerChapterId = [guid]::NewGuid()
$readerVolumeId = [guid]::NewGuid()
$readerRevisionId = [guid]::NewGuid()
$readerSegmentId = [guid]::NewGuid()
$authoringEditorChapterId = [guid]::NewGuid()
$translationChapterId = [guid]::NewGuid()
$translationVolumeId = [guid]::NewGuid()
$translationRevisionId = [guid]::NewGuid()
$translationSegmentId = [guid]::NewGuid()
$glossaryId = [guid]::NewGuid()
$paymentReturnOrderId = [guid]::NewGuid()
$commerceOrderId = [guid]::NewGuid()
$commerceRequestId = [guid]::NewGuid()
$commerceOtherUserId = [guid]::NewGuid()
$commerceAuthorId = [guid]::NewGuid()
$commerceReviewerId = [guid]::NewGuid()
$commerceLibraryId = [guid]::NewGuid()
$commerceWorkId = [guid]::NewGuid()
$commerceVolumeId = [guid]::NewGuid()
$commerceChapterId = [guid]::NewGuid()
$commerceRevisionId = [guid]::NewGuid()
$commerceSegmentId = [guid]::NewGuid()
$publicationReviewAdminId = [guid]::NewGuid()
$publicationReviewReviewerId = [guid]::NewGuid()
$publicationReviewAssignmentId = [guid]::NewGuid()
$glossaryActiveRevisionId = [guid]::NewGuid()
$glossaryDraftRevisionId = [guid]::NewGuid()
$glossaryTermId = [guid]::NewGuid()
$authoringTitle = "Live E2E Authored Draft $userId"
$authoringOutlineTitle = 'Live E2E Blank Outline Chapter'
$authoringEditorScreenshotPath = Join-Path $evidenceDirectory 'authoring-chapter-editor-live.png'
$publicationRightsPolicyVersion = 'rights-live-e2e-v1'
$publicationRightsPolicyText = 'Synthetic integration fixture only. No real legal terms are represented.'
$publicationRightsPolicySha256 = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($publicationRightsPolicyText))
).ToLowerInvariant()
$publicationRightsScreenshotPath = Join-Path $evidenceDirectory 'publication-rights-policy-live.png'
$publicationReviewScreenshotPath = Join-Path $evidenceDirectory 'publication-review-workspace-live.png'
$publicationChapterReleaseScreenshotPath = Join-Path $evidenceDirectory 'publication-chapter-release-live.png'
$notificationInboxScreenshotPath = Join-Path $evidenceDirectory 'notification-inbox-live.png'
$secondWorkSourceKind = if ($IncludeTranslationPreview -or $IncludeAuthoringOutline -or $IncludeCommerceSettlement -or $IncludePublicationRightsAcceptance -or $IncludePublicationReviewWorkspace) { 'authored' } else { 'imported' }
$readerSourceText = 'Live E2E source text is served from PostgreSQL.'
$readerSourceHash = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($readerSourceText))
).ToLowerInvariant()
$translationSourceText = 'Live fake-local source segment from PostgreSQL.'
$translationSourceHash = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($translationSourceText))
).ToLowerInvariant()
$commerceSourceText = 'This paid chapter is released only after a server-confirmed credit unlock.'
$commerceSourceHash = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($commerceSourceText))
).ToLowerInvariant()
$commerceRightsPolicyVersion = 'rights-commerce-' + [guid]::NewGuid().ToString('N')
$commerceRightsPolicyText = 'Synthetic disposable commerce rights fixture only; not legal terms.'
$commerceRightsPolicySha256 = [Convert]::ToHexString(
    [System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($commerceRightsPolicyText))
).ToLowerInvariant()
$screenshotPath = Join-Path $evidenceDirectory 'account-library-live.png'
$readerScreenshotPath = Join-Path $evidenceDirectory 'account-reader-live.png'
$cultivationRankUpScreenshotPath = Join-Path $evidenceDirectory 'cultivation-rank-up-live.png'
$cultivationRankUpTracePath = Join-Path $evidenceDirectory 'cultivation-rank-up-trace.json'
$workDetailScreenshotPath = Join-Path $evidenceDirectory 'account-work-detail-live.png'
$privateImportScreenshotPath = Join-Path $evidenceDirectory 'private-import-commit-live.png'
$glossaryImportScreenshotPath = Join-Path $evidenceDirectory 'glossary-import-live.png'
$glossaryEditorScreenshotPath = Join-Path $evidenceDirectory 'glossary-editor-live.png'
$authoringScreenshotPath = Join-Path $evidenceDirectory 'authoring-create-live.png'
$commerceSuccessScreenshotPath = Join-Path $evidenceDirectory 'momo-commerce-success-live.png'
$commerceUnlockScreenshotPath = Join-Path $evidenceDirectory 'momo-commerce-unlocked-chapter-live.png'
$authoringOutlineScreenshotPath = Join-Path $evidenceDirectory 'authoring-outline-live.png'
$buildLog = Join-Path $evidenceDirectory 'api-build.log'
$apiStdout = Join-Path $evidenceDirectory 'api-stdout.log'
$apiStderr = Join-Path $evidenceDirectory 'api-stderr.log'
$e2eLog = Join-Path $evidenceDirectory 'playwright.log'

try {
    if ($IncludeAuthoringChapterEditor -and -not $IncludeAuthoringOutline) {
        throw '-IncludeAuthoringChapterEditor requires -IncludeAuthoringOutline to provide the authored-work fixture.'
    }
    if ($IncludeAuthoringEmptyChapterDelete -and -not $IncludeAuthoringOutline) {
        throw '-IncludeAuthoringEmptyChapterDelete requires -IncludeAuthoringOutline to provide the authored-work fixture.'
    }
    if ($IncludePublicationRightsAcceptance -and $IncludeAuthoringOutline) {
        throw 'Run -IncludePublicationRightsAcceptance separately from -IncludeAuthoringOutline because those journeys require different policy fixtures.'
    }
    if ($IncludePublicationReviewWorkspace -and ($IncludePublicationRightsAcceptance -or $IncludeAuthoringOutline)) {
        throw 'Run -IncludePublicationReviewWorkspace separately from publication-rights acceptance and authoring-outline journeys because each mutates the same synthetic manuscript/policy fixtures.'
    }
    if ($IncludePublicationReviewWorkspace -and $IncludeCommerceSettlement) {
        throw 'Run -IncludePublicationReviewWorkspace separately from -IncludeCommerceSettlement because each seeds a different effective synthetic publication-rights policy.'
    }

    $env:POSTGRES_PORT = [string]$hostPort
    $env:POSTGRES_PASSWORD = $postgresPassword
    $env:DATABASE_URL = "jdbc:postgresql://localhost:$hostPort/novelweb"
    $env:DATABASE_USERNAME = 'novelweb'
    $env:DATABASE_PASSWORD = $postgresPassword
    $env:GOOGLE_AUTH_RATE_LIMIT_HMAC_KEY = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
    $env:NOVEL_CSRF_HMAC_KEY = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
    $env:NOVEL_LIBRARY_CURSOR_HMAC_KEY = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
    $env:NOVEL_FEATURE_CULTIVATION_ENABLED = 'true'
    $env:NOVEL_FEATURE_COMMERCE_SANDBOX_ENABLED = 'false'
    $env:NOVEL_NOTIFICATIONS_DELIVERY_ENABLED = 'false'
    $env:NOVEL_TRANSLATION_FAKE_ENABLED = 'false'
    $env:NOVEL_TRANSLATION_FAKE_API_ENABLED = 'false'
    $env:NOVEL_TRANSLATION_FAKE_WORKER_ENABLED = 'false'
    $env:NOVEL_TRANSLATION_REVIEW_ENABLED = 'false'
    $env:NOVEL_TRANSLATION_PUBLICATION_ENABLED = 'false'
    $env:NOVEL_MOMO_TEST_ENABLED = 'false'
    $env:NOVEL_MOMO_TEST_RECONCILIATION_ENABLED = 'false'
    if ($IncludeNotificationInbox) {
        # Enable only inside the random disposable API process used by this end-to-end journey.
        $env:NOVEL_NOTIFICATIONS_DELIVERY_ENABLED = 'true'
        $env:NOVEL_NOTIFICATIONS_DELIVERY_POLL_DELAY = 'PT1S'
    }
    if ($IncludeTranslationPreview) {
        $env:NOVEL_TRANSLATION_FAKE_ENABLED = 'true'
        $env:NOVEL_TRANSLATION_FAKE_API_ENABLED = 'true'
        $env:NOVEL_TRANSLATION_FAKE_WORKER_ENABLED = 'true'
    }
    if ($IncludeTranslationReviewWorkspace) {
        # These feature gates are enabled only for this random disposable PostgreSQL API process.
        $env:NOVEL_TRANSLATION_REVIEW_ENABLED = 'true'
        $env:NOVEL_TRANSLATION_PUBLICATION_ENABLED = 'true'
    }
    if ($IncludePaymentReturn -or $IncludeCommerceSettlement) {
        # Synthetic secrets exist only for the disposable process; this journey issues no provider request.
        $env:NOVEL_MOMO_TEST_ENABLED = 'true'
        $env:NOVEL_FEATURE_COMMERCE_SANDBOX_ENABLED = 'true'
        $env:MOMO_ENV = 'test'
        $env:MOMO_TEST_PARTNER_CODE = 'SYNTHETIC-TEST-PARTNER'
        $env:MOMO_TEST_ACCESS_KEY = 'synthetic-test-access-only'
        $env:MOMO_TEST_SECRET_KEY = 'synthetic-test-secret-only'
        $env:MOMO_TEST_BASE_URL = 'https://test-payment.momo.vn'
        $env:MOMO_TEST_IPN_URL = 'https://localhost:4300/api/v1/payments/momo/ipn'
        $env:MOMO_TEST_REDIRECT_URL = 'https://localhost:4300/app/payment/result'
    }
    $env:NOVEL_IMPORT_STAGING_ROOT = $importStagingRoot
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
    $activeProfiles = if ($IncludeTranslationPreview) { 'postgres,translation-fake' } else { 'postgres' }
    $apiArguments = @('-jar', ('"{0}"' -f $apiJar), "--spring.profiles.active=$activeProfiles", '--server.port=8081')
    if ($IncludePublicationReviewWorkspace) {
        # This opt-in affects only the disposable API process launched below.
        $env:NOVEL_PUBLICATION_RELEASE_ENABLED = 'true'
    }
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
        if ($LASTEXITCODE -eq 0) {
            $appliedChangesets = [int]([string]$changeCount).Trim()
            if ($appliedChangesets -eq $expectedChangesets) {
                $schemaReady = $true
                break
            }
        }
        Start-Sleep -Seconds 1
    }
    if (-not $schemaReady) { throw "The disposable PostgreSQL database did not reach the source schema with $expectedChangesets changesets." }

    if ($IncludeAuthoringDraftApi) {
        $draftApiTestLog = Join-Path $evidenceDirectory 'authoring-draft-api-test.log'
        Push-Location $apiDirectory
        try {
            & .\mvnw.cmd -B -ntp -Ppostgres '-Dpostgres.integration=true' `
                '-Dtest=PostgresSchemaIntegrationTests#authoringDraftRevisionsArePrivateVersionedIdempotentAndReaderHidden' test *> $draftApiTestLog
            $draftApiTestExit = $LASTEXITCODE
        } finally { Pop-Location }
        if ($draftApiTestExit -ne 0) {
            Get-Content -LiteralPath $draftApiTestLog -Tail 80
            throw "Private authoring draft API integration test failed (exit $draftApiTestExit)."
        }
    }

    if ($IncludeAuthoringEmptyChapterDelete) {
        $deleteApiTestLog = Join-Path $evidenceDirectory 'authoring-empty-chapter-delete-api-test.log'
        Push-Location $apiDirectory
        try {
            & .\mvnw.cmd -B -ntp -Ppostgres '-Dpostgres.integration=true' `
                '-Dtest=PostgresSchemaIntegrationTests#authoringEmptyDraftChapterDeleteIsPrivateIdempotentAndPreservesSavedContent+authoringEmptyDraftDeleteAndFirstSaveHaveOneTransactionalWinner' test *> $deleteApiTestLog
            $deleteApiTestExit = $LASTEXITCODE
        } finally { Pop-Location }
        if ($deleteApiTestExit -ne 0) {
            Get-Content -LiteralPath $deleteApiTestLog -Tail 80
            throw "PostgreSQL empty chapter deletion integration tests failed (exit $deleteApiTestExit)."
        }
    }

    $authoringOutlineFixtureSql = ''
    $authoringOutlineCreationFixtureSql = if ($IncludeAuthoringOutline -or $IncludePublicationRightsAcceptance -or $IncludePublicationReviewWorkspace) {
        "INSERT INTO authoring_manuscript_creations (actor_user_id, idempotency_key, request_sha256, work_id) VALUES ('$userId', 'live-outline-fixture-0001', decode(repeat('00', 32), 'hex'), '$secondWorkId');"
    } else { '' }
    $publicationRightsPolicyFixtureSql = if ($IncludePublicationRightsAcceptance -or $IncludePublicationReviewWorkspace) {
        @"
INSERT INTO publication_rights_policy_documents (version, effective_at, display_text, policy_sha256, status)
VALUES ('$publicationRightsPolicyVersion', clock_timestamp() - INTERVAL '10 minutes', '$publicationRightsPolicyText', decode('$publicationRightsPolicySha256', 'hex'), 'DRAFT');
UPDATE publication_rights_policy_documents
   SET status = 'APPROVED', approved_at = clock_timestamp(), approved_by = '$userId'
 WHERE version = '$publicationRightsPolicyVersion';
"@
    } else { '' }
    $publicationReviewReviewerFixtureSql = if ($IncludePublicationReviewWorkspace) {
        @"
INSERT INTO users (id, display_name, contact_email, email_verified_at, status)
VALUES
    ('$publicationReviewAdminId', 'Synthetic E2E System Admin', 'review-admin-$publicationReviewAdminId@example.test', clock_timestamp(), 'active'),
    ('$publicationReviewReviewerId', 'Live E2E Assigned Reviewer', 'review-reviewer-$publicationReviewReviewerId@example.test', clock_timestamp(), 'active');
INSERT INTO policy_acceptances (id, user_id, policy_type, version, decision, recorded_at, source) VALUES
(gen_random_uuid(), '$publicationReviewReviewerId', 'TERMS', 'terms-live-e2e-v1', 'TERMS_ACCEPTED', clock_timestamp(), 'e2e-fixture'),
(gen_random_uuid(), '$publicationReviewReviewerId', 'PRIVACY_NOTICE', 'privacy-live-e2e-v1', 'PRIVACY_NOTICE_ACKNOWLEDGED', clock_timestamp(), 'e2e-fixture');
INSERT INTO sessions (id_hash, user_id, expires_at)
VALUES (decode('$publicationReviewSessionHash', 'hex'), '$publicationReviewReviewerId', clock_timestamp() + INTERVAL '1 day');
INSERT INTO security_role_assignments (id, user_id, role_code, assigned_by_user_id, reason_code)
VALUES
    (gen_random_uuid(), '$publicationReviewAdminId', 'system_admin', NULL, 'INITIAL_ADMIN_BOOTSTRAP'),
    ('$publicationReviewAssignmentId', '$publicationReviewReviewerId', 'publication_reviewer', '$publicationReviewAdminId', 'REVIEW_CAPACITY');
INSERT INTO security_role_assignment_events
    (id, assignment_id, target_user_id, role_code, action_code, actor_user_id,
     operator_identity, ticket_reference, reason_code)
VALUES
    (gen_random_uuid(), (SELECT id FROM security_role_assignments WHERE user_id = '$publicationReviewAdminId' AND role_code = 'system_admin'),
     '$publicationReviewAdminId', 'system_admin', 'BOOTSTRAPPED', NULL,
     'disposable E2E fixture operator', 'E2E-$publicationReviewAdminId', 'INITIAL_ADMIN_BOOTSTRAP'),
    (gen_random_uuid(), '$publicationReviewAssignmentId', '$publicationReviewReviewerId', 'publication_reviewer',
     'GRANTED', '$publicationReviewAdminId', NULL, NULL, 'REVIEW_CAPACITY');
"@
    } else { '' }
    if ($IncludeAuthoringOutline -and $IncludeAuthoringChapterEditor) {
        $authoringOutlineFixtureSql = "INSERT INTO chapters (id, work_id, volume_id, ordinal, title, source_language, source_revision_id, status) SELECT gen_random_uuid(), '$secondWorkId', NULL, seed.ordinal, 'Live Outline Seed ' || seed.ordinal, 'en', NULL, 'draft' FROM generate_series(2, 25) AS seed(ordinal);`nINSERT INTO chapters (id, work_id, volume_id, ordinal, title, source_language, source_revision_id, status) VALUES ('$authoringEditorChapterId', '$secondWorkId', NULL, 26, 'Live Outline Seed 26', 'en', NULL, 'draft');"
    } elseif ($IncludeAuthoringOutline) {
        $authoringOutlineFixtureSql = "INSERT INTO chapters (id, work_id, volume_id, ordinal, title, source_language, source_revision_id, status) SELECT gen_random_uuid(), '$secondWorkId', NULL, seed.ordinal, 'Live Outline Seed ' || seed.ordinal, 'en', NULL, 'draft' FROM generate_series(2, 26) AS seed(ordinal);"
    }
    $paymentReturnFixtureSql = ''
    if ($IncludePaymentReturn) {
        $paymentReturnFixtureSql = @"
INSERT INTO users (id, display_name, contact_email, email_verified_at, status)
VALUES ('$paymentReturnUserId', 'Live Pending Payment Reader', 'payment-return-$paymentReturnUserId@example.test', clock_timestamp(), 'active');
INSERT INTO policy_acceptances (id, user_id, policy_type, version, decision, recorded_at, source) VALUES
(gen_random_uuid(), '$paymentReturnUserId', 'TERMS', 'terms-live-e2e-v1', 'TERMS_ACCEPTED', clock_timestamp(), 'e2e-fixture'),
(gen_random_uuid(), '$paymentReturnUserId', 'PRIVACY_NOTICE', 'privacy-live-e2e-v1', 'PRIVACY_NOTICE_ACKNOWLEDGED', clock_timestamp(), 'e2e-fixture');
INSERT INTO sessions (id_hash, user_id, expires_at)
VALUES (decode('$paymentReturnSessionHash', 'hex'), '$paymentReturnUserId', clock_timestamp() + INTERVAL '1 day');
INSERT INTO credit_top_up_orders
    (id, user_id, request_id, idempotency_key, pack_id, pack_version, provider, environment,
     currency, amount_vnd, paid_credits, bonus_credits, status)
VALUES
    ('$paymentReturnOrderId', '$paymentReturnUserId', gen_random_uuid(), 'payment-return-$paymentReturnOrderId',
     'momo-test-credits-100', 1, 'MOMO', 'TEST', 'VND', 1000, 100, 0, 'PENDING');
"@
    }
    $commerceSettlementFixtureSql = ''
    if ($IncludeCommerceSettlement) {
        $commerceSettlementFixtureSql = @"
INSERT INTO users (id, display_name, contact_email, email_verified_at, status)
VALUES ('$commerceOtherUserId', 'Live Commerce Other Reader', 'commerce-other-$commerceOtherUserId@example.test', clock_timestamp(), 'active');
INSERT INTO policy_acceptances (id, user_id, policy_type, version, decision, recorded_at, source) VALUES
(gen_random_uuid(), '$commerceOtherUserId', 'TERMS', 'terms-live-e2e-v1', 'TERMS_ACCEPTED', clock_timestamp(), 'e2e-fixture'),
(gen_random_uuid(), '$commerceOtherUserId', 'PRIVACY_NOTICE', 'privacy-live-e2e-v1', 'PRIVACY_NOTICE_ACKNOWLEDGED', clock_timestamp(), 'e2e-fixture');
INSERT INTO sessions (id_hash, user_id, expires_at)
VALUES (decode('$commerceOtherSessionHash', 'hex'), '$commerceOtherUserId', clock_timestamp() + INTERVAL '1 day');
INSERT INTO users (id, display_name, contact_email, email_verified_at, status)
VALUES ('$commerceAuthorId', 'Live Commerce Author', 'commerce-author-$commerceAuthorId@example.test', clock_timestamp(), 'active');
INSERT INTO users (id, display_name, contact_email, email_verified_at, status)
VALUES ('$commerceReviewerId', 'Live Synthetic Commerce Reviewer', 'commerce-reviewer-$commerceReviewerId@example.test', clock_timestamp(), 'active');
INSERT INTO security_role_assignments
    (id, user_id, role_code, assigned_by_user_id, reason_code)
VALUES
    (gen_random_uuid(), '$commerceReviewerId', 'publication_reviewer', '$commerceAuthorId', 'REVIEW_CAPACITY');
INSERT INTO publication_rights_policy_documents
    (version, effective_at, display_text, policy_sha256)
VALUES
    ('$commerceRightsPolicyVersion', clock_timestamp() - INTERVAL '10 minutes', '$commerceRightsPolicyText', decode('$commerceRightsPolicySha256', 'hex'));
UPDATE publication_rights_policy_documents
   SET status = 'APPROVED', approved_at = clock_timestamp(), approved_by = '$commerceReviewerId'
 WHERE version = '$commerceRightsPolicyVersion';
INSERT INTO libraries (id, owner_user_id, name, visibility, is_default)
VALUES ('$commerceLibraryId', '$commerceAuthorId', 'Live Public Commerce Shelf', 'private', FALSE);
INSERT INTO works (id, library_id, owner_user_id, title, author, source_language, source_kind, visibility, status)
VALUES ('$commerceWorkId', '$commerceLibraryId', '$commerceAuthorId', 'Live Public Commerce Story', 'Commerce Author', 'en', 'authored', 'public', 'active');
INSERT INTO authoring_manuscript_creations
    (actor_user_id, idempotency_key, request_sha256, work_id)
VALUES
    ('$commerceAuthorId', 'commerce-manuscript-$commerceWorkId', decode(repeat('11', 32), 'hex'), '$commerceWorkId');
INSERT INTO publication_rights_policy_acceptances
    (id, user_id, work_id, idempotency_key, policy_version, policy_sha256, source)
VALUES
    (gen_random_uuid(), '$commerceAuthorId', '$commerceWorkId', gen_random_uuid(),
     '$commerceRightsPolicyVersion', decode('$commerceRightsPolicySha256', 'hex'), 'web-authoring-publication');
INSERT INTO volumes (id, work_id, ordinal, title)
VALUES ('$commerceVolumeId', '$commerceWorkId', 1, 'Live Commerce Volume');
INSERT INTO chapters (id, work_id, volume_id, ordinal, title, source_language, source_revision_id, status)
VALUES ('$commerceChapterId', '$commerceWorkId', '$commerceVolumeId', 1, 'Live Paid Chapter', 'en', '$commerceRevisionId', 'ready');
INSERT INTO chapter_revisions (id, chapter_id, revision_no, source_format, normalized_content, content_hash, created_by_user_id)
VALUES ('$commerceRevisionId', '$commerceChapterId', 1, 'plain_text', '$commerceSourceText', decode('$commerceSourceHash', 'hex'), '$commerceAuthorId');
INSERT INTO segments (id, chapter_revision_id, ordinal, segment_type, source_text, stable_anchor, checksum)
VALUES ('$commerceSegmentId', '$commerceRevisionId', 1, 'paragraph', '$commerceSourceText', 'live-commerce-p1', decode('$commerceSourceHash', 'hex'));
INSERT INTO publication_scope_access_approvals
    (id, work_id, scope_code, approved_snapshot, policy_version, policy_sha256, approved_by_user_id, reason_code)
SELECT gen_random_uuid(), '$commerceWorkId', scope.scope_code,
       publication_work_scope_snapshot('$commerceWorkId', scope.scope_code),
       '$commerceRightsPolicyVersion', decode('$commerceRightsPolicySha256', 'hex'),
       '$commerceReviewerId', 'RIGHTS_REVIEWED'
FROM (VALUES ('PUBLIC_METADATA'), ('CHAPTER_TEXT'), ('COMMERCIAL_SALE')) AS scope(scope_code);
INSERT INTO credit_top_up_orders
    (id, user_id, request_id, idempotency_key, pack_id, pack_version, provider, environment,
     currency, amount_vnd, paid_credits, bonus_credits, status)
VALUES
    ('$commerceOrderId', '$userId', '$commerceRequestId', 'commerce-settlement-$commerceOrderId',
     'momo-test-credits-100', 1, 'MOMO', 'TEST', 'VND', 1000, 100, 0, 'PENDING');
"@
    }

    $cultivationRankUpFixtureSql = ''
    if ($IncludeCultivationRankUp) {
        $cultivationRankSeedSessionId = [guid]::NewGuid()
        $cultivationRankUpFixtureSql = @"
INSERT INTO cultivation_profiles (user_id, enabled, catalog_version)
VALUES ('$userId', TRUE, 'cultivation-v1');
INSERT INTO cultivation_daily_active_time
    (user_id, activity_date, eligible_microseconds, catalog_version, policy_version)
VALUES
    ('$userId', (transaction_timestamp() AT TIME ZONE 'UTC')::date, 5940000000, 'cultivation-v1', 'xp-v1');
INSERT INTO cultivation_reading_sessions
    (id, user_id, work_id, chapter_id, catalog_version, policy_version,
     start_idempotency_key, start_request_sha256, state, started_at, last_heartbeat_at)
VALUES
    ('$cultivationRankSeedSessionId', '$userId', '$firstWorkId', '$readerChapterId',
     'cultivation-v1', 'xp-v1', 'cultivation-rank-up-seed-$cultivationRankSeedSessionId',
     decode(repeat('00', 32), 'hex'), 'active', transaction_timestamp() - INTERVAL '2 minutes',
     transaction_timestamp() - INTERVAL '1 minute');
INSERT INTO cultivation_xp_ledger
    (user_id, source_session_id, active_minute_index, activity_at,
     xp_delta, catalog_version, policy_version)
SELECT '$userId', '$cultivationRankSeedSessionId', minute_index,
       transaction_timestamp() - INTERVAL '1 second', 1, 'cultivation-v1', 'xp-v1'
FROM generate_series(0, 98) AS minutes(minute_index);
UPDATE cultivation_reading_sessions
SET state = 'closed', closed_at = clock_timestamp(), last_heartbeat_at = clock_timestamp()
WHERE id = '$cultivationRankSeedSessionId';
SELECT rebuild_cultivation_daily_xp_aggregate(
    '$userId', (transaction_timestamp() AT TIME ZONE 'UTC')::date);
"@
    }

$notificationSubscriptionFixtureSql = ''
if ($IncludeNotificationInbox) {
    $notificationSubscriptionFixtureSql = "INSERT INTO work_subscriptions (id, actor_user_id, work_id) VALUES (gen_random_uuid(), '$userId', '$secondWorkId');"
}

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
INSERT INTO import_rights_statements
    (version, effective_at, display_text, status, approved_at, approved_by)
VALUES
    ('private-live-e2e-v1', clock_timestamp() - INTERVAL '10 minutes',
     'Disposable test statement: synthetic TXT, HTML, Markdown and EPUB files may be parsed and committed to a private test library.',
     'APPROVED', clock_timestamp(), '$userId');
INSERT INTO sessions (id_hash, user_id, expires_at)
VALUES (decode('$sessionHash', 'hex'), '$userId', clock_timestamp() + INTERVAL '1 day');
$publicationReviewReviewerFixtureSql
INSERT INTO libraries (id, owner_user_id, name, visibility, is_default)
VALUES ('$libraryId', '$userId', 'Live E2E Library', 'private', TRUE);
INSERT INTO works (id, library_id, owner_user_id, title, author, source_language, source_kind, visibility, status)
VALUES
('$firstWorkId', '$libraryId', '$userId', 'Live E2E Novel One', 'E2E Author', 'vi', 'imported', 'private', 'active'),
('$secondWorkId', '$libraryId', '$userId', 'Live E2E Novel Two', 'E2E Author', 'en', '$secondWorkSourceKind', 'private', 'active');
$notificationSubscriptionFixtureSql
$authoringOutlineCreationFixtureSql
$publicationRightsPolicyFixtureSql
INSERT INTO volumes (id, work_id, ordinal, title)
VALUES
('$readerVolumeId', '$firstWorkId', 1, 'Live E2E Volume'),
('$translationVolumeId', '$secondWorkId', 1, 'Live Translation Volume');
INSERT INTO chapters (id, work_id, volume_id, ordinal, title, source_language, source_revision_id, status)
VALUES
('$readerChapterId', '$firstWorkId', '$readerVolumeId', 1, 'Live Reader Chapter', 'en', '$readerRevisionId', 'ready'),
('$translationChapterId', '$secondWorkId', '$translationVolumeId', 1, 'Live Translation Chapter', 'en', '$translationRevisionId', 'ready');
$authoringOutlineFixtureSql
INSERT INTO chapter_revisions (id, chapter_id, revision_no, source_format, normalized_content, content_hash, created_by_user_id)
VALUES
('$readerRevisionId', '$readerChapterId', 1, 'plain_text', '$readerSourceText', decode('$readerSourceHash', 'hex'), '$userId'),
('$translationRevisionId', '$translationChapterId', 1, 'plain_text', '$translationSourceText', decode('$translationSourceHash', 'hex'), '$userId');
INSERT INTO segments (id, chapter_revision_id, ordinal, segment_type, source_text, stable_anchor, checksum)
VALUES
('$readerSegmentId', '$readerRevisionId', 1, 'paragraph', '$readerSourceText', 'live-reader-p1', decode('$readerSourceHash', 'hex')),
('$translationSegmentId', '$translationRevisionId', 1, 'paragraph', '$translationSourceText', 'live-translation-p1', decode('$translationSourceHash', 'hex'));
$cultivationRankUpFixtureSql
$paymentReturnFixtureSql
$commerceSettlementFixtureSql
INSERT INTO glossaries (id, owner_user_id, name, scope_type, source_language, target_language, version)
VALUES ('$glossaryId', '$userId', 'E2E Glossary Import', 'USER_GLOBAL', 'zh-Hans', 'vi', 4);
INSERT INTO glossary_revisions (id, glossary_id, revision_no, created_by_user_id)
VALUES ('$glossaryActiveRevisionId', '$glossaryId', 1, '$userId');
INSERT INTO glossary_revisions (id, glossary_id, revision_no, parent_revision_id, created_by_user_id)
VALUES ('$glossaryDraftRevisionId', '$glossaryId', 2, '$glossaryActiveRevisionId', '$userId');
INSERT INTO glossary_terms (id, revision_id, ordinal, source_term, target_term, case_policy, match_mode)
VALUES
    ('$glossaryTermId', '$glossaryActiveRevisionId', 1, '金丹', 'Kim Đan', 'INSENSITIVE', 'WHOLE_PHRASE'),
    ('$glossaryTermId', '$glossaryDraftRevisionId', 1, '金丹', 'Kim Đan', 'INSENSITIVE', 'WHOLE_PHRASE');
INSERT INTO glossary_aliases (revision_id, term_id, ordinal, alias_text)
VALUES
    ('$glossaryActiveRevisionId', '$glossaryTermId', 1, 'Golden Core'),
    ('$glossaryDraftRevisionId', '$glossaryTermId', 1, 'Golden Core');
UPDATE glossaries SET active_revision_id = '$glossaryActiveRevisionId' WHERE id = '$glossaryId';
COMMIT;
"@
    & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -U novelweb -d novelweb -c $fixtureSql
    if ($LASTEXITCODE -ne 0) { throw 'Disposable browser-session fixtures could not be inserted.' }

    $env:LIBRARY_LIVE_TEST_SESSION_TOKEN = $sessionToken
    $env:LIBRARY_LIVE_TEST_LIBRARY_ID = [string]$libraryId
    $env:LIBRARY_LIVE_TEST_WORK_IDS = "$firstWorkId,$secondWorkId"
    $env:LIBRARY_LIVE_TEST_SCREENSHOT = $screenshotPath
    $paymentReturnScreenshotPath = Join-Path $evidenceDirectory 'momo-payment-return-pending-live.png'
    if ($IncludePaymentReturn) {
        $env:MOMO_PAYMENT_RETURN_LIVE_TEST_SESSION_TOKEN = $paymentReturnSessionToken
        $env:MOMO_PAYMENT_RETURN_LIVE_TEST_ORDER_ID = [string]$paymentReturnOrderId
        $env:MOMO_PAYMENT_RETURN_LIVE_TEST_SCREENSHOT = $paymentReturnScreenshotPath
    }
    if ($IncludeCommerceSettlement) {
        $env:MOMO_COMMERCE_LIVE_TEST_SESSION_TOKEN = $sessionToken
        $env:MOMO_COMMERCE_LIVE_TEST_OTHER_SESSION_TOKEN = $commerceOtherSessionToken
        $env:MOMO_COMMERCE_LIVE_TEST_ORDER_ID = [string]$commerceOrderId
        $env:MOMO_COMMERCE_LIVE_TEST_REQUEST_ID = [string]$commerceRequestId
        $env:MOMO_COMMERCE_LIVE_TEST_WORK_ID = [string]$commerceWorkId
        $env:MOMO_COMMERCE_LIVE_TEST_CHAPTER_ID = [string]$commerceChapterId
        $env:MOMO_COMMERCE_LIVE_TEST_PRIVATE_WORK_ID = [string]$secondWorkId
        $env:MOMO_COMMERCE_LIVE_TEST_PRIVATE_CHAPTER_ID = [string]$translationChapterId
        $env:MOMO_COMMERCE_LIVE_TEST_SUCCESS_SCREENSHOT = $commerceSuccessScreenshotPath
        $env:MOMO_COMMERCE_LIVE_TEST_UNLOCK_SCREENSHOT = $commerceUnlockScreenshotPath
    }
    $env:READER_LIVE_TEST_WORK_ID = [string]$firstWorkId
    $env:READER_LIVE_TEST_CHAPTER_ID = [string]$readerChapterId
    $env:READER_LIVE_TEST_SCREENSHOT = $readerScreenshotPath
    if ($IncludeNotificationInbox) {
        $env:NOTIFICATION_LIVE_TEST_SCREENSHOT = $notificationInboxScreenshotPath
    }
    if ($IncludeCultivationRankUp) {
        $env:CULTIVATION_LIVE_TEST_RANK_UP = 'true'
        $env:CULTIVATION_LIVE_TEST_SCREENSHOT = $cultivationRankUpScreenshotPath
        $env:CULTIVATION_LIVE_TEST_TRACE = $cultivationRankUpTracePath
    }
    $env:WORK_DETAIL_LIVE_TEST_SCREENSHOT = $workDetailScreenshotPath
    $env:PRIVATE_IMPORT_LIVE_TEST_SCREENSHOT = $privateImportScreenshotPath
    $env:GLOSSARY_LIVE_TEST_GLOSSARY_ID = [string]$glossaryId
    $env:GLOSSARY_LIVE_TEST_SCREENSHOT = $glossaryImportScreenshotPath
    $env:GLOSSARY_EDITOR_LIVE_TEST_SCREENSHOT = $glossaryEditorScreenshotPath
    $env:AUTHORING_LIVE_TEST_TITLE = $authoringTitle
    $env:AUTHORING_LIVE_TEST_SCREENSHOT = $authoringScreenshotPath
    if ($IncludeAuthoringOutline) {
        $env:AUTHORING_OUTLINE_LIVE_TEST_WORK_ID = [string]$secondWorkId
        $env:AUTHORING_OUTLINE_LIVE_TEST_SCREENSHOT = $authoringOutlineScreenshotPath
        $env:AUTHORING_OUTLINE_LIVE_TEST_EXPECTED_TITLE = 'My explicit title'
    }
    if ($IncludePublicationRightsAcceptance) {
        $env:PUBLICATION_RIGHTS_LIVE_TEST_WORK_ID = [string]$secondWorkId
        $env:PUBLICATION_RIGHTS_LIVE_TEST_SCREENSHOT = $publicationRightsScreenshotPath
        $env:PUBLICATION_RIGHTS_LIVE_TEST_VERSION = $publicationRightsPolicyVersion
        $env:PUBLICATION_RIGHTS_LIVE_TEST_TEXT = $publicationRightsPolicyText
        $env:PUBLICATION_RIGHTS_LIVE_TEST_SHA256 = $publicationRightsPolicySha256
    }
    if ($IncludePublicationReviewWorkspace) {
        # Enable only in this disposable process so the browser can exercise owner-confirmed release.
        $env:NOVEL_PUBLICATION_RELEASE_ENABLED = 'true'
        $env:PUBLICATION_REVIEW_LIVE_TEST_REVIEWER_SESSION_TOKEN = $publicationReviewSessionToken
        $env:PUBLICATION_REVIEW_LIVE_TEST_WORK_ID = [string]$secondWorkId
        $env:PUBLICATION_REVIEW_LIVE_TEST_SCREENSHOT = $publicationReviewScreenshotPath
        $env:PUBLICATION_REVIEW_LIVE_TEST_CHAPTER_SCREENSHOT = $publicationChapterReleaseScreenshotPath
    }
    if ($IncludeTranslationReviewWorkspace) {
        $env:TRANSLATION_REVIEW_LIVE_TEST_REVIEWER_SESSION_TOKEN = $publicationReviewSessionToken
    }
    if ($IncludeAuthoringChapterEditor) {
        $env:AUTHORING_EDITOR_LIVE_TEST_WORK_ID = [string]$secondWorkId
        $env:AUTHORING_EDITOR_LIVE_TEST_CHAPTER_ID = [string]$authoringEditorChapterId
        $env:AUTHORING_EDITOR_LIVE_TEST_SCREENSHOT = $authoringEditorScreenshotPath
    }
    $translationJobScreenshot = Join-Path $evidenceDirectory 'translation-job-live.png'
    if ($IncludeTranslationPreview) {
        $env:TRANSLATION_LIVE_TEST_WORK_ID = [string]$secondWorkId
        $env:TRANSLATION_LIVE_TEST_CHAPTER_ID = [string]$translationChapterId
        $env:TRANSLATION_LIVE_TEST_SCREENSHOT = $translationJobScreenshot
    }

    if ($IncludeDatabaseRestartRecovery) {
        $probePath = Join-Path $evidenceDirectory 'database-restart-probes.txt'
        $apiUri = 'http://127.0.0.1:8081'
        $sessionCookie = "__Host-novel_session=$sessionToken"
        $httpClient = [System.Net.Http.HttpClient]::new()
        $httpClient.Timeout = [TimeSpan]::FromSeconds(5)

        function Invoke-LiveApiProbe {
            param(
                [Parameter(Mandatory = $true)][System.Net.Http.HttpClient]$Client,
                [Parameter(Mandatory = $true)][string]$Uri,
                [string]$Cookie
            )

            $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $Uri)
            if ($Cookie) { [void]$request.Headers.TryAddWithoutValidation('Cookie', $Cookie) }
            $response = $null
            try {
                $response = $Client.SendAsync($request).GetAwaiter().GetResult()
                $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                $correlationId = ''
                if ($response.Headers.Contains('X-Correlation-Id')) {
                    $correlationId = ($response.Headers.GetValues('X-Correlation-Id') | Select-Object -First 1)
                }
                return [pscustomobject]@{
                    StatusCode = [int]$response.StatusCode
                    Body = $body
                    CorrelationId = $correlationId
                }
            }
            finally {
                if ($response) { $response.Dispose() }
                $request.Dispose()
            }
        }

        try {
            $manifestUri = "$apiUri/api/v1/works/$firstWorkId/reader-manifest?limit=1"
            $beforeManifest = Invoke-LiveApiProbe -Client $httpClient -Uri $manifestUri -Cookie $sessionCookie
            if ($beforeManifest.StatusCode -ne 200 -or $beforeManifest.Body -notmatch [regex]::Escape([string]$readerChapterId)) {
                throw "Authenticated Reader manifest baseline failed before PostgreSQL restart (HTTP $($beforeManifest.StatusCode))."
            }

            $stopOutput = & $docker @composeArguments stop postgres 2>&1
            $stopOutput | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'postgres-stop.log')
            if ($LASTEXITCODE -ne 0) { throw 'Could not stop only the disposable PostgreSQL service.' }

            $healthDown = $null
            for ($attempt = 1; $attempt -le 20; $attempt++) {
                $apiProcess.Refresh()
                if ($apiProcess.HasExited) { throw "API exited while PostgreSQL was stopped (code $($apiProcess.ExitCode))." }
                try {
                    $healthDown = Invoke-LiveApiProbe -Client $httpClient -Uri "$apiUri/actuator/health"
                    if ($healthDown.StatusCode -eq 503 -and $healthDown.Body -match '"status"\s*:\s*"DOWN"') { break }
                } catch { }
                Start-Sleep -Milliseconds 750
            }
            if (-not $healthDown -or $healthDown.StatusCode -ne 503 -or $healthDown.Body -notmatch '"status"\s*:\s*"DOWN"') {
                throw 'API health did not report PostgreSQL DOWN while the disposable database service was stopped.'
            }

            $startOutput = & $docker @composeArguments start postgres 2>&1
            $startOutput | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'postgres-start.log')
            if ($LASTEXITCODE -ne 0) { throw 'Could not restart the disposable PostgreSQL service.' }

            $databaseReadyAfterRestart = $false
            for ($attempt = 1; $attempt -le 40; $attempt++) {
                & $docker @composeArguments exec -T postgres pg_isready -U novelweb -d novelweb *> $null
                if ($LASTEXITCODE -eq 0) { $databaseReadyAfterRestart = $true; break }
                Start-Sleep -Seconds 1
            }
            if (-not $databaseReadyAfterRestart) { throw 'PostgreSQL did not become ready again within 40 seconds.' }

            $healthUp = $null
            for ($attempt = 1; $attempt -le 40; $attempt++) {
                $apiProcess.Refresh()
                if ($apiProcess.HasExited) { throw "API exited after PostgreSQL restart (code $($apiProcess.ExitCode))." }
                try {
                    $healthUp = Invoke-LiveApiProbe -Client $httpClient -Uri "$apiUri/actuator/health"
                    if ($healthUp.StatusCode -eq 200 -and $healthUp.Body -match '"status"\s*:\s*"UP"') { break }
                } catch { }
                Start-Sleep -Milliseconds 750
            }
            if (-not $healthUp -or $healthUp.StatusCode -ne 200 -or $healthUp.Body -notmatch '"status"\s*:\s*"UP"') {
                throw 'API health did not recover to UP after PostgreSQL restarted.'
            }

            $afterManifest = Invoke-LiveApiProbe -Client $httpClient -Uri $manifestUri -Cookie $sessionCookie
            if ($afterManifest.StatusCode -ne 200 -or $afterManifest.Body -notmatch [regex]::Escape([string]$readerChapterId)) {
                throw "Authenticated Reader manifest did not recover after PostgreSQL restart (HTTP $($afterManifest.StatusCode))."
            }

            $databaseRestartSummary = @(
                "before_manifest_http=$($beforeManifest.StatusCode); chapter_present=$($beforeManifest.Body -match [regex]::Escape([string]$readerChapterId)); correlation_id=$($beforeManifest.CorrelationId)"
                "during_restart_health_http=$($healthDown.StatusCode); status=DOWN; API_process_alive=$(-not $apiProcess.HasExited)"
                "after_restart_health_http=$($healthUp.StatusCode); status=UP; correlation_id=$($healthUp.CorrelationId)"
                "after_manifest_http=$($afterManifest.StatusCode); chapter_present=$($afterManifest.Body -match [regex]::Escape([string]$readerChapterId)); correlation_id=$($afterManifest.CorrelationId)"
            )
            $databaseRestartSummary | Set-Content -LiteralPath $probePath -Encoding utf8
        }
        finally {
            $httpClient.Dispose()
        }
    }

    Push-Location $webDirectory
    try {
        $translationBeforeOutline = $IncludeTranslationPreview -and $IncludeAuthoringOutline
        $hasPriorE2eLog = $false
        $e2eExit = 0
        if ($translationBeforeOutline) {
            & npm.cmd run test:e2e:postgres -- 'e2e/translation-live-postgres.spec.ts' *>&1 |
                Tee-Object -FilePath $e2eLog
            $e2eExit = $LASTEXITCODE
            if ($e2eExit -ne 0) { throw "Fake-local translation journey failed (exit $e2eExit)." }
            $hasPriorE2eLog = $true
        }

        $e2eSpecs = @(
            'e2e/authoring-create-live-postgres.spec.ts'
        )
        # The outline journey updates manuscript metadata consumed by later Library/commerce checks.
        if ($IncludeAuthoringOutline) { $e2eSpecs += 'e2e/authoring-outline-live-postgres.spec.ts' }
        $e2eSpecs += @(
            'e2e/library-live-postgres.spec.ts',
            'e2e/reader-live-postgres.spec.ts',
            'e2e/work-detail-live-postgres.spec.ts',
            'e2e/private-import-live-postgres.spec.ts',
            'e2e/private-import-formats-live-postgres.spec.ts',
            'e2e/glossary-import-live-postgres.spec.ts',
            'e2e/cultivation-live-postgres.spec.ts'
        )
        if ($IncludePublicationRightsAcceptance) { $e2eSpecs += 'e2e/publication-rights-policy-live-postgres.spec.ts' }
        if ($IncludePublicationReviewWorkspace -and -not $IncludeTranslationReviewWorkspace) { $e2eSpecs += 'e2e/publication-review-live-postgres.spec.ts' }
        if ($IncludeNotificationInbox -and -not $IncludeTranslationReviewWorkspace) { $e2eSpecs += 'e2e/publication-review-notifications-live-postgres.spec.ts' }
        if ($IncludeTranslationPreview -and -not $translationBeforeOutline -and -not $IncludeTranslationReviewWorkspace) {
            $e2eSpecs += 'e2e/translation-live-postgres.spec.ts'
        }
        if ($IncludeAuthoringChapterEditor) { $e2eSpecs += 'e2e/authoring-chapter-editor-live-postgres.spec.ts' }
        if ($IncludePaymentReturn) { $e2eSpecs += 'e2e/momo-payment-return-live-postgres.spec.ts' }
        if ($IncludeCommerceSettlement) { $e2eSpecs += 'e2e/momo-commerce-settlement-live-postgres.spec.ts' }

        & npm.cmd run test:e2e:postgres -- $e2eSpecs *>&1 |
            Tee-Object -FilePath $e2eLog -Append:$hasPriorE2eLog
        $e2eExit = $LASTEXITCODE
    } finally { Pop-Location }
    if ($e2eExit -ne 0) { throw "Live FE/API/PostgreSQL Playwright journey failed (exit $e2eExit)." }

    if ($IncludeTranslationReviewWorkspace) {
        # Review a private submitted snapshot before the work is public. The publication journey
        # then grants current rights, after which a separate pass confirms the owner release.
        $env:TRANSLATION_REVIEW_LIVE_TEST_STAGE = 'accept'
        Push-Location $webDirectory
        try {
            & npm.cmd run test:e2e:postgres -- 'e2e/translation-live-postgres.spec.ts' *>&1 |
                Tee-Object -FilePath $e2eLog -Append
            $translationAcceptE2eExit = $LASTEXITCODE
        } finally { Pop-Location }
        if ($translationAcceptE2eExit -ne 0) {
            throw "Manual translation submission/reviewer acceptance live journey failed (exit $translationAcceptE2eExit)."
        }

        Push-Location $webDirectory
        try {
            $rightsJourneySpecs = @('e2e/publication-review-live-postgres.spec.ts')
            if ($IncludeNotificationInbox) {
                $rightsJourneySpecs += 'e2e/publication-review-notifications-live-postgres.spec.ts'
            }
            & npm.cmd run test:e2e:postgres -- $rightsJourneySpecs *>&1 |
                Tee-Object -FilePath $e2eLog -Append
            $translationRightsE2eExit = $LASTEXITCODE
        } finally { Pop-Location }
        if ($translationRightsE2eExit -ne 0) {
            throw "Publication-rights and staged-chapter live journey failed before translation release (exit $translationRightsE2eExit)."
        }
    }

    if ($IncludePublicationRightsAcceptance) {
        $publicationRightsSummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT (SELECT COUNT(*) FROM publication_rights_policy_acceptances WHERE user_id = '$userId' AND work_id = '$secondWorkId')::text || '|' || (SELECT COUNT(*) FROM publication_rights_policy_acceptance_idempotency WHERE user_id = '$userId' AND work_id = '$secondWorkId')::text || '|' || COALESCE((SELECT encode(policy_sha256, 'hex') FROM publication_rights_policy_acceptances WHERE user_id = '$userId' AND work_id = '$secondWorkId' AND policy_version = '$publicationRightsPolicyVersion'), 'missing');"
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the publication-rights acceptance and idempotency rows in disposable PostgreSQL.' }
        $publicationRightsParts = ([string]$publicationRightsSummary).Trim() -split '\|'
        if ($publicationRightsParts.Count -ne 3 -or $publicationRightsParts[0] -ne '1' -or $publicationRightsParts[1] -ne '1' -or $publicationRightsParts[2] -ne $publicationRightsPolicySha256) {
            throw "Publication-rights acceptance invariants failed in disposable PostgreSQL: $publicationRightsSummary"
        }
    }

    if ($IncludePublicationReviewWorkspace) {
        $publicationReviewSummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT state.current_state || '|' || (SELECT COUNT(*) FROM publication_review_events event WHERE event.request_id = request.id)::text || '|' || (SELECT COUNT(*) FROM publication_scope_access_approvals approval WHERE approval.work_id = request.work_id)::text || '|' || work.visibility || '|' || (SELECT event.action_code || ':' || event.reason_code FROM publication_review_events event WHERE event.request_id = request.id AND event.actor_user_id = '$publicationReviewReviewerId' ORDER BY event.event_sequence DESC LIMIT 1) FROM publication_requests request JOIN publication_request_current_state state ON state.request_id = request.id JOIN works work ON work.id = request.work_id WHERE request.owner_user_id = '$userId' AND request.work_id = '$secondWorkId' AND request.target_chapter_id IS NULL;"
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the reviewer decision in disposable PostgreSQL.' }
        $publicationReviewParts = ([string]$publicationReviewSummary).Trim() -split '\|'
        if ($publicationReviewParts.Count -ne 5 -or $publicationReviewParts[0] -ne 'APPROVED_UNPUBLISHED' -or $publicationReviewParts[1] -ne '2' -or $publicationReviewParts[2] -ne '8' -or $publicationReviewParts[3] -ne 'public' -or $publicationReviewParts[4] -ne 'APPROVED:RIGHTS_REVIEWED') {
            throw "PostgreSQL reviewer workspace invariants failed after the browser decision: $publicationReviewSummary"
        }

        $chapterReleaseSummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT state.current_state || '|' || chapter.status || '|' || (SELECT COUNT(*) FROM publication_review_events event WHERE event.request_id = request.id)::text || '|' || (SELECT COUNT(*) FROM chapter_publication_release_events released WHERE released.request_id = request.id)::text || '|' || (SELECT COUNT(*) FROM chapter_published_outbox_events outbox WHERE outbox.work_id = request.work_id AND outbox.chapter_id = chapter.id)::text || '|' || work.visibility || '|' || (SELECT COUNT(*) FROM publication_scope_access_approvals approval WHERE approval.work_id = request.work_id)::text FROM publication_requests request JOIN publication_request_current_state state ON state.request_id = request.id JOIN chapters chapter ON chapter.id = request.target_chapter_id AND chapter.work_id = request.work_id JOIN works work ON work.id = request.work_id WHERE request.owner_user_id = '$userId' AND request.work_id = '$secondWorkId' AND chapter.title = 'Live E2E Staged Chapter' ORDER BY request.submitted_at DESC LIMIT 1;"
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the staged chapter review and release in disposable PostgreSQL.' }
        $chapterReleaseParts = ([string]$chapterReleaseSummary).Trim() -split '\|'
        if ($chapterReleaseParts.Count -ne 7 -or $chapterReleaseParts[0] -ne 'APPROVED_UNPUBLISHED' -or $chapterReleaseParts[1] -ne 'ready' -or $chapterReleaseParts[2] -ne '2' -or $chapterReleaseParts[3] -ne '1' -or $chapterReleaseParts[4] -ne '1' -or $chapterReleaseParts[5] -ne 'public' -or $chapterReleaseParts[6] -ne '8') {
            throw "PostgreSQL staged chapter publication invariants failed after the browser journey: $chapterReleaseSummary"
        }
    }

    if ($IncludeNotificationInbox) {
        $notificationSummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT COUNT(*)::text || '|' || COALESCE(SUM((notification.read_at IS NULL)::integer), 0)::text || '|' || COALESCE(MAX(outbox.status), 'missing') || '|' || COALESCE(MAX(outbox.attempts), 0)::text FROM notifications notification JOIN chapters chapter ON chapter.id = notification.chapter_id AND chapter.work_id = notification.work_id JOIN chapter_publication_release_events released ON released.id = notification.event_id AND released.work_id = notification.work_id AND released.chapter_id = notification.chapter_id JOIN chapter_published_outbox_events outbox ON outbox.release_event_id = released.id WHERE notification.actor_user_id = '$userId' AND notification.work_id = '$secondWorkId' AND chapter.title = 'Live E2E Staged Chapter';"
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify delivery and persisted read state in disposable PostgreSQL.' }
        $notificationParts = ([string]$notificationSummary).Trim() -split '\|'
        if ($notificationParts.Count -ne 4 -or $notificationParts[0] -ne '1' -or $notificationParts[1] -ne '0' -or $notificationParts[2] -ne 'completed' -or $notificationParts[3] -ne '1') {
            throw "Notification delivery/read persistence invariants failed in disposable PostgreSQL: $notificationSummary"
        }
    }

    if ($IncludePaymentReturn) {
        $paymentReturnSummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT o.status || '|' || o.paid_credits::text || '|' || (SELECT COUNT(*) FROM credit_ledger_entries e WHERE e.source_type = 'PAYMENT_ORDER' AND e.source_id = o.id)::text || '|' || COALESCE((SELECT SUM(p.balance) FROM credit_balance_projection p WHERE p.user_id = o.user_id), 0)::text FROM credit_top_up_orders o WHERE o.id = '$paymentReturnOrderId' AND o.user_id = '$paymentReturnUserId';"
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the pending MoMo TEST order and credit ledger.' }
        $paymentReturnParts = ([string]$paymentReturnSummary).Trim() -split '\|'
        if ($paymentReturnParts.Count -ne 4 -or $paymentReturnParts[0] -ne 'PENDING' -or $paymentReturnParts[1] -ne '100' -or $paymentReturnParts[2] -ne '0' -or $paymentReturnParts[3] -ne '0') {
            throw "Pending payment return unexpectedly granted credit: $paymentReturnSummary"
        }
    }

    if ($IncludeCommerceSettlement) {
        $commerceSummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT o.status || '|' || COALESCE((SELECT SUM(p.balance) FROM credit_balance_projection p WHERE p.user_id = o.user_id), 0)::text || '|' || COALESCE((SELECT COUNT(*) FROM credit_ledger_entries e WHERE e.source_type = 'PAYMENT_ORDER' AND e.source_id = o.id), 0)::text || '|' || COALESCE((SELECT COUNT(*) FROM credit_ledger_entries e WHERE e.source_type = 'CHAPTER_UNLOCK' AND e.user_id = o.user_id), 0)::text || '|' || COALESCE((SELECT COUNT(*) FROM content_entitlements e WHERE e.user_id = o.user_id AND e.chapter_id = '$commerceChapterId'), 0)::text || '|' || COALESCE((SELECT SUM(e.amount_delta) FROM credit_ledger_entries e WHERE e.source_type = 'PAYMENT_ORDER' AND e.source_id = o.id), 0)::text || '|' || COALESCE((SELECT SUM(e.amount_delta) FROM credit_ledger_entries e WHERE e.source_type = 'CHAPTER_UNLOCK' AND e.user_id = o.user_id), 0)::text FROM credit_top_up_orders o WHERE o.id = '$commerceOrderId' AND o.user_id = '$userId';"
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify signed synthetic settlement, credit ledger and chapter entitlement.' }
        $commerceParts = ([string]$commerceSummary).Trim() -split '\|'
        if ($commerceParts.Count -ne 7 -or $commerceParts[0] -ne 'SUCCEEDED' -or $commerceParts[1] -ne '90' -or $commerceParts[2] -ne '1' -or $commerceParts[3] -ne '1' -or $commerceParts[4] -ne '1' -or $commerceParts[5] -ne '100' -or $commerceParts[6] -ne '-10') {
            throw "Commerce settlement invariants failed in disposable PostgreSQL: $commerceSummary"
        }
    }

    if ($IncludeTranslationReviewWorkspace) {
        $acceptedTranslation = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT submission.id::text || '|' || encode(submission.snapshot_sha256, 'hex') FROM translation_candidate_review_submissions submission WHERE submission.submitted_by_user_id = '$userId' AND submission.chapter_id = '$translationChapterId' AND submission.status = 'accepted' ORDER BY submission.submitted_at DESC, submission.id DESC LIMIT 1;"
        if ($LASTEXITCODE -ne 0) { throw 'Could not read the accepted immutable translation snapshot from disposable PostgreSQL.' }
        $acceptedTranslationParts = ([string]$acceptedTranslation).Trim() -split '\|'
        if ($acceptedTranslationParts.Count -ne 2 -or $acceptedTranslationParts[0] -notmatch '^[0-9a-f-]{36}$' -or $acceptedTranslationParts[1] -notmatch '^[0-9a-f]{64}$') {
            throw "Expected one accepted translation snapshot before owner release; found $acceptedTranslation."
        }
        $env:TRANSLATION_REVIEW_LIVE_TEST_SUBMISSION_ID = $acceptedTranslationParts[0]
        $env:TRANSLATION_REVIEW_LIVE_TEST_SNAPSHOT_DIGEST = $acceptedTranslationParts[1]
        $env:TRANSLATION_REVIEW_LIVE_TEST_STAGE = 'release'

        Push-Location $webDirectory
        try {
            & npm.cmd run test:e2e:postgres -- 'e2e/translation-live-postgres.spec.ts' *>&1 |
                Tee-Object -FilePath $e2eLog -Append
            $translationReleaseE2eExit = $LASTEXITCODE
        } finally { Pop-Location }
        if ($translationReleaseE2eExit -ne 0) {
            throw "Rights-bound owner translation release and Reader journey failed (exit $translationReleaseE2eExit)."
        }
    }

    $authoringSummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT COUNT(*)::text || '|' || COALESCE(bool_and(w.source_kind = 'authored' AND w.visibility = 'private' AND w.status = 'active' AND w.author = 'E2E Pen Name' AND w.source_language = 'vi' AND l.owner_user_id = w.owner_user_id AND l.visibility = 'private' AND l.workspace_id IS NULL), FALSE)::text || '|' || COALESCE(SUM((SELECT COUNT(*) FROM authoring_manuscript_creations c WHERE c.actor_user_id = '$userId' AND c.work_id = w.id)), 0)::text || '|' || COALESCE(SUM((SELECT COUNT(*) FROM chapters ch WHERE ch.work_id = w.id)), 0)::text || '|' || COALESCE(SUM((SELECT COUNT(*) FROM volumes v WHERE v.work_id = w.id)), 0)::text FROM works w JOIN libraries l ON l.id = w.library_id WHERE w.owner_user_id = '$userId' AND w.title = '$authoringTitle';"
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify the authored private draft in disposable PostgreSQL.' }
    $authoringParts = ([string]$authoringSummary).Trim() -split '\|'
    if ($authoringParts.Count -ne 5 -or $authoringParts[0] -ne '1' -or $authoringParts[1] -notin @('t', 'true') -or $authoringParts[2] -ne '1' -or $authoringParts[3] -ne '0' -or $authoringParts[4] -ne '0') {
        throw "PostgreSQL authoring invariants failed after browser journey: $authoringSummary"
    }

    $ledgerSummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT COALESCE(SUM(xp_delta), 0)::text || '|' || COUNT(*)::text FROM cultivation_xp_ledger WHERE user_id = '$userId';"
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify the cultivation XP ledger in disposable PostgreSQL.' }
    $ledgerParts = ([string]$ledgerSummary).Trim() -split '\|'
    if ($ledgerParts.Count -ne 2) { throw "Unexpected cultivation ledger summary: $ledgerSummary" }
    $cultivationLedgerXp = [int]$ledgerParts[0]
    $cultivationLedgerEntries = [int]$ledgerParts[1]
    if ($cultivationLedgerXp -lt 1 -or $cultivationLedgerEntries -lt 1) {
        throw "Expected at least one XP award in the disposable PostgreSQL ledger; found $cultivationLedgerXp XP across $cultivationLedgerEntries entries."
    }
    $glossarySummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT g.version::text || '|' || active.revision_no::text || '|' || latest.revision_no::text || '|' || (SELECT COUNT(*) FROM glossary_terms t JOIN glossary_revisions r ON r.id = t.revision_id WHERE r.glossary_id = g.id)::text || '|' || (SELECT COUNT(*) FROM glossary_aliases a JOIN glossary_revisions r ON r.id = a.revision_id WHERE r.glossary_id = g.id)::text || '|' || (SELECT COUNT(*) FROM glossary_import_apply_idempotency i WHERE i.glossary_id = g.id)::text || '|' || (SELECT COUNT(*) FROM glossary_import_apply_rows i WHERE i.glossary_id = g.id)::text || '|' || (SELECT COUNT(*) FROM glossary_terms t WHERE t.revision_id = latest.id AND t.source_term = '丹田' AND t.target_term = 'Đan điền khí hải') FROM glossaries g JOIN glossary_revisions active ON active.glossary_id = g.id AND active.id = g.active_revision_id JOIN LATERAL (SELECT id, revision_no FROM glossary_revisions WHERE glossary_id = g.id ORDER BY revision_no DESC LIMIT 1) latest ON TRUE WHERE g.id = '$glossaryId';"
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify the imported glossary draft in disposable PostgreSQL.' }
    $glossaryParts = ([string]$glossarySummary).Trim() -split '\|'
    if ($glossaryParts.Count -ne 8 -or $glossaryParts[0] -ne '6' -or $glossaryParts[1] -ne '1' -or $glossaryParts[2] -ne '4' -or $glossaryParts[3] -ne '6' -or $glossaryParts[4] -ne '6' -or $glossaryParts[5] -ne '1' -or $glossaryParts[6] -ne '1' -or $glossaryParts[7] -ne '1') {
        throw "PostgreSQL glossary import/editor invariants failed after browser journeys: $glossarySummary"
    }
    if ($IncludeTranslationPreview) {
        $translationSummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT COUNT(*)::text || '|' || COALESCE(bool_and(j.provider_id = 'fake-local' AND j.status = 'review_required' AND j.completed_units = 1 AND j.failed_units = 0 AND c.status = 'ready_for_review' AND s.translated_text = '[fake en->vi] $translationSourceText'), FALSE)::text || '|' || COUNT(DISTINCT a.id)::text || '|' || COUNT(DISTINCT s.candidate_id)::text FROM translation_jobs j JOIN translation_units u ON u.job_id = j.id JOIN translation_attempts a ON a.unit_id = u.id JOIN translation_candidates c ON c.job_id = j.id JOIN translation_candidate_segments s ON s.candidate_id = c.id WHERE j.chapter_id = '$translationChapterId' AND j.target_language = 'vi' AND a.provider_id = 'fake-local' AND a.outcome = 'succeeded';"
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the fake-local translation candidate in disposable PostgreSQL.' }
        $translationParts = ([string]$translationSummary).Trim() -split '\|'
        if ($translationParts.Count -ne 4 -or $translationParts[0] -ne '1' -or $translationParts[1] -notin @('t', 'true') -or $translationParts[2] -ne '1' -or $translationParts[3] -ne '1') {
            throw "PostgreSQL fake-local translation invariants failed after browser journey: $translationSummary"
        }
    }

    if ($IncludeTranslationReviewWorkspace) {
        $translationReviewSummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT submission.status || '|' || decision.action_code || '|' || version.status || '|' || event.action_code || '|' || (SELECT COUNT(*) FROM translation_version_segments segment WHERE segment.translation_version_id = version.id)::text || '|' || approval.scope_code || '|' || publication_scope_access_is_current(approval.work_id, approval.scope_code)::text FROM translation_candidate_review_submissions submission JOIN translation_candidate_review_decisions decision ON decision.submission_id = submission.id JOIN translation_versions version ON version.review_submission_id = submission.id JOIN translation_release_events event ON event.translation_version_id = version.id JOIN publication_scope_access_approvals approval ON approval.id = event.rights_approval_id WHERE submission.submitted_by_user_id = '$userId' AND submission.chapter_id = '$translationChapterId' AND version.status = 'current';"
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the manual translation submission, reviewer decision and owner release in disposable PostgreSQL.' }
        $translationReviewParts = ([string]$translationReviewSummary).Trim() -split '\|'
        if ($translationReviewParts.Count -ne 7 -or $translationReviewParts[0] -ne 'accepted' -or $translationReviewParts[1] -ne 'ACCEPT' -or $translationReviewParts[2] -ne 'current' -or $translationReviewParts[3] -ne 'PUBLISH' -or $translationReviewParts[4] -ne '1' -or $translationReviewParts[5] -ne 'TRANSLATION_ADAPTATION' -or $translationReviewParts[6] -notin @('t', 'true')) {
            throw "PostgreSQL translation review/publication invariants failed: $translationReviewSummary"
        }
    }
    if ($IncludeAuthoringOutline) {
        $authoringOutlineSummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT COUNT(*)::text || '|' || COALESCE(bool_and(w.source_kind = 'authored' AND w.visibility = 'private' AND w.status = 'active' AND ch.ordinal = 27 AND ch.volume_id IS NULL AND ch.status = 'deleted' AND ch.deleted_at IS NOT NULL AND ch.source_revision_id IS NULL), FALSE)::text || '|' || COALESCE(SUM((SELECT COUNT(*) FROM chapter_revisions r WHERE r.chapter_id = ch.id)), 0)::text || '|' || COALESCE(SUM((SELECT COUNT(*) FROM authoring_chapter_creations c WHERE c.actor_user_id = '$userId' AND c.work_id = w.id AND c.chapter_id = ch.id)), 0)::text || '|' || COALESCE(bool_and(w.title = 'My explicit title' AND w.author = 'E2E Edited Pen' AND w.source_language = 'ja' AND w.authoring_metadata_version = 4), FALSE)::text FROM chapters ch JOIN works w ON w.id = ch.work_id WHERE w.id = '$secondWorkId' AND ch.title = '$authoringOutlineTitle';"
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the authored metadata and blank chapter in disposable PostgreSQL.' }
        $authoringOutlineParts = ([string]$authoringOutlineSummary).Trim() -split '\|'
        if ($authoringOutlineParts.Count -ne 5 -or $authoringOutlineParts[0] -ne '1' -or $authoringOutlineParts[1] -notin @('t', 'true') -or $authoringOutlineParts[2] -ne '0' -or $authoringOutlineParts[3] -ne '1' -or $authoringOutlineParts[4] -notin @('t', 'true')) {
            throw "PostgreSQL authoring outline/metadata invariants failed after browser journey: $authoringOutlineSummary"
        }
    }
    if ($IncludeAuthoringChapterEditor) {
        $authoringEditorSummary = & $docker @composeArguments exec -T postgres psql -v ON_ERROR_STOP=1 -tA -U novelweb -d novelweb -c "SELECT COUNT(*)::text || '|' || COALESCE(MAX(r.revision_no), 0)::text || '|' || COALESCE((SELECT latest.normalized_content = 'Bản người viết sau xung đột.' FROM chapter_revisions latest WHERE latest.chapter_id = ch.id ORDER BY latest.revision_no DESC LIMIT 1), FALSE)::text || '|' || (SELECT COUNT(*) FROM authoring_chapter_revision_writes wr WHERE wr.actor_user_id = '$userId' AND wr.chapter_id = '$authoringEditorChapterId')::text || '|' || ch.status || '|' || COALESCE(ch.source_revision_id = (SELECT latest.id FROM chapter_revisions latest WHERE latest.chapter_id = ch.id ORDER BY latest.revision_no DESC LIMIT 1), FALSE)::text FROM chapters ch LEFT JOIN chapter_revisions r ON r.chapter_id = ch.id WHERE ch.id = '$authoringEditorChapterId' GROUP BY ch.id;"
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the authored chapter editor revisions in disposable PostgreSQL.' }
        $authoringEditorParts = ([string]$authoringEditorSummary).Trim() -split '\|'
        if ($authoringEditorParts.Count -ne 6 -or $authoringEditorParts[0] -ne '3' -or $authoringEditorParts[1] -ne '3' -or $authoringEditorParts[2] -notin @('t', 'true') -or $authoringEditorParts[3] -ne '3' -or $authoringEditorParts[4] -ne 'draft' -or $authoringEditorParts[5] -notin @('t', 'true')) {
            throw "PostgreSQL authoring editor invariants failed after browser journey: $authoringEditorSummary"
        }
    }
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
    if (Test-Path -LiteralPath $importStagingRoot) {
        $resolvedEvidenceRoot = [System.IO.Path]::GetFullPath($evidenceDirectory).TrimEnd('\') + '\'
        $resolvedStagingRoot = [System.IO.Path]::GetFullPath($importStagingRoot)
        if (-not $resolvedStagingRoot.StartsWith($resolvedEvidenceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'Cleanup guard rejected staging path outside this run evidence directory.'
        }
        Remove-Item -LiteralPath $resolvedStagingRoot -Recurse -Force
        $stagingCleanup = 'Removed only this run private staging directory'
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
        '# Browser journey: frontend to PostgreSQL import, Library, Reader, authoring, cultivation and fake-local translation'
        ''
        "- Date: $($startedAt.ToString('yyyy-MM-dd HH:mm:ss zzz'))"
        "- Result: $result"
        "- Compose project: $project"
        "- PostgreSQL host port: $hostPort"
        "- API: built from the current workspace and started with the $activeProfiles profile"
        "- Schema: $appliedChangesets/$expectedChangesets Liquibase changesets applied before fixture setup"
        '- Browser: Playwright Chromium, Angular dev-server proxy to the real API'
        '- Authoring: real authenticated metadata create, same-key replay, changed-payload 409, private authored work persisted, one idempotency receipt, zero volumes/chapters'
        $(if ($IncludeAuthoringDraftApi) { '- Authoring draft API integration: 1/1 PostgreSQL-backed test passed for policy/CSRF/owner gates, draft ETags, immutable revisions, exact replay, key collision, stale-version response, transactional row counts, and Reader/publication isolation' } else { '- Authoring draft API integration: not requested' })
        $(if ($IncludeAuthoringEmptyChapterDelete) { '- Empty chapter deletion API integration: 2/2 PostgreSQL-backed tests passed for owner/policy/CSRF gates, soft delete, idempotent replay, saved-content conflict, privacy, Reader isolation, and concurrent delete/save winner' } else { '- Empty chapter deletion API integration: not requested' })
        $(if ($IncludePublicationRightsAcceptance) { '- Publication-rights browser/API: disposable synthetic approved policy only; the real acceptance endpoint committed the first confirmation, the browser received a synthetic 503, and same-key retry replayed the same acceptance' } else { '- Publication-rights browser/API: not requested' })
        $(if ($IncludePublicationReviewWorkspace) { '- Reviewer workspace + staged chapter browser/API: owner submitted a whole-work snapshot, assigned reviewer approved it, and owner explicitly released the work; owner then created/saved a private chapter on that public work, submitted an exact chapter snapshot in Author Studio, reviewer approved without making it public, and owner separately released it; PostgreSQL confirms the ready chapter, APPROVED_UNPUBLISHED request receipt, one immutable chapter-release event, one identifier-only outbox row, public work visibility and six immutable scope approval receipts' } else { '- Reviewer workspace browser/API: not requested' })
        $(if ($IncludeNotificationInbox -and $result -eq 'PASS') { "- Notification inbox: enabled only in the disposable API process; the owner-confirmed release outbox was consumed, the signed-in follower saw the real chapter metadata, read state survived reload, and a second actor could not list or mark the row. PostgreSQL summary: $($notificationParts -join '|')" } elseif ($IncludeNotificationInbox) { '- Notification inbox end-to-end: requested but not verified because the browser journey or PostgreSQL invariants failed' } else { '- Notification inbox end-to-end: not requested' })
        "- Browser journey: session cookie/policy → rights receipt → upload/parse/preview/private commit → refreshed Library → Work Detail → Reader source/bookmark, then opt-in → server-timed Reader session → XP award → private profile/activity → glossary import/draft edit/revision preview$(if ($IncludeTranslationPreview) { ' → fake-local Reader translation → redacted job status → unpublished PostgreSQL candidate' } else { '' })$(if ($IncludeAuthoringOutline) { ' → private authoring chapter outline → CSRF/idempotent blank-chapter creation' } else { '' })$(if ($IncludeAuthoringChapterEditor) { ' → private chapter editor → immutable save → competing writer → explicit conflict resolution' } else { '' })$(if ($IncludePublicationRightsAcceptance) { ' → exact synthetic rights policy → explicit author acknowledgement → lost-response same-key retry' } else { '' })$(if ($IncludePublicationReviewWorkspace) { ' → whole-work rights review/release → public-work draft edit → chapter-scoped rights request/reviewer approval → owner-confirmed chapter release' } else { '' })$(if ($IncludeNotificationInbox -and $result -eq 'PASS') { ' → worker delivery → actor inbox → persisted read state → second-actor isolation' } elseif ($IncludeNotificationInbox) { ' → notification delivery journey requested but not verified' } else { '' })$(if ($IncludeCommerceSettlement) { ' → synthetic signed TEST settlement → paid-credit ledger/projection → explicit Reader unlock/entitlement' } else { '' })"
        $(if ($IncludeAuthoringDraftApi) { '- Separate PostgreSQL authoring draft API test: 1/1 passed for owner/policy/CSRF gates, ETags, immutable writes, exact replay, stale-version response, outline/Reader isolation, and persisted row counts; see authoring-draft-api-test.log' } else { '- Separate PostgreSQL authoring draft API test: not requested' })
        $(if ($IncludeAuthoringEmptyChapterDelete) { '- Separate PostgreSQL empty chapter deletion API tests: 2/2 passed for soft delete/replay/security and transactional save/delete race; see authoring-empty-chapter-delete-api-test.log' } else { '- Separate PostgreSQL empty chapter deletion API tests: not requested' })
        '- Cultivation feature flag: enabled only in this random disposable API process; prior process value restored during cleanup'
        $(if ($IncludeCultivationRankUp) { '- Cultivation rank-up: synthetic enabled profile started at 99 XP / Phàm nhân, one real Reader award reached 100 XP / Luyện Khí tầng 1; fixture session closed before browser journey; response correlation IDs saved in cultivation-rank-up-trace.json' } else { '- Cultivation rank-up boundary journey: not requested' })
        $(if ($IncludeDatabaseRestartRecovery) { '- PostgreSQL restart recovery: live API health reported DOWN while its disposable PostgreSQL service was stopped, then returned UP after restart; an authenticated Reader manifest returned HTTP 200 with the same persisted chapter before and after; probes and stop/start logs are saved in database-restart-probes.txt and postgres-*.log' } else { '- PostgreSQL restart recovery: not requested' })
        $(if ($IncludeTranslationPreview) { '- Translation feature/provider/API/worker flags: fake-local only, enabled only in this random disposable API process; no external NMT provider was configured or called; prior process values restored during cleanup' } else { '- Translation fake-local flags: forced false for this disposable API process; prior process values restored during cleanup' })
        $(if ($translationReviewParts.Count -eq 7) { "- Manual translation review/publication: real readiness snapshot, author submission, assigned reviewer acceptance, owner-confirmed release and translated Reader access passed; PostgreSQL confirms $($translationReviewParts[0])/$($translationReviewParts[1])/$($translationReviewParts[3]) with one immutable segment and current TRANSLATION_ADAPTATION approval" } else { '- Manual translation review/publication: not requested' })
        "- PostgreSQL cultivation ledger: $cultivationLedgerXp XP across $cultivationLedgerEntries entries for the synthetic user"
        $(if ($glossaryParts.Count -eq 8) { "- PostgreSQL glossary import/editor after revision preview: version $($glossaryParts[0]), active revision $($glossaryParts[1]), latest draft $($glossaryParts[2]), $($glossaryParts[3]) immutable term rows, $($glossaryParts[4]) aliases, one idempotency receipt and one apply row; preview did not create a revision or change the active pointer" } else { '- PostgreSQL glossary import/editor: not verified' })
        $(if ($authoringOutlineParts.Count -eq 5) { "- PostgreSQL authoring outline and metadata: blank chapter at ordinal 27 soft-deleted after UI confirmation, zero content revisions, one idempotency receipt after create replay, and persisted title/byline/language at metadata version 4 after explicit stale-conflict recovery" } else { '- PostgreSQL authoring outline and metadata: not requested' })
        $(if ($authoringEditorParts.Count -eq 6) { '- PostgreSQL authoring editor: three immutable revisions, three idempotency receipts, explicit local-version conflict resolution, latest revision pointer matches the saved content and chapter remains draft/unpublished' } else { '- PostgreSQL authoring editor: not requested' })
        $(if ($publicationRightsParts.Count -eq 3) { "- PostgreSQL policy acknowledgement: $($publicationRightsParts[0]) immutable acceptance and $($publicationRightsParts[1]) idempotency receipt; digest matches the exact synthetic display text" } else { '- PostgreSQL policy acknowledgement: not requested' })
        $(if ($translationParts.Count -eq 4) { "- PostgreSQL fake-local translation: $($translationParts[0]) job, $($translationParts[2]) successful attempt and $($translationParts[3]) unpublished candidate segment verified with the fake marker" } else { '- PostgreSQL fake-local translation: not requested' })
        $(if ($IncludePaymentReturn) { '- MoMo TEST return: isolated synthetic actor/order remained PENDING after refresh; payment ledger and balance projection remained zero; browser made no create/IPN request; reconciliation worker was disabled; no MoMo network request was made' } else { '- MoMo TEST return: not requested' })
        $(if ($IncludeCommerceSettlement) { '- MoMo TEST commerce: a locally signed synthetic IPN was delivered twice to the real API; actor-owned order settled once, 100 paid credits were granted once, the Reader debited exactly 10 paid credits after explicit confirmation, one entitlement unlocked the public chapter and the saved balance is 90; a second actor received 404 for the order, empty histories/zero balance and no paid chapter body; no MoMo network request or reconciliation query was made' } else { '- MoMo TEST commerce settlement/unlock: not requested' })
        '- API route mocks: none'
        "- Elapsed: $duration seconds"
        "- Cleanup: $cleanup"
        "- Staging cleanup: $stagingCleanup"
        "- Screenshots: $([System.IO.Path]::GetFileName($screenshotPath)), $([System.IO.Path]::GetFileName($workDetailScreenshotPath)), $([System.IO.Path]::GetFileName($readerScreenshotPath))$(if ($IncludeCultivationRankUp) { ', cultivation-rank-up-live.png' } else { '' }), $([System.IO.Path]::GetFileName($glossaryImportScreenshotPath)), $([System.IO.Path]::GetFileName($glossaryEditorScreenshotPath)), $([System.IO.Path]::GetFileName($authoringScreenshotPath))$(if ($IncludeAuthoringOutline) { ', authoring-outline-live.png' } else { '' })$(if ($IncludeAuthoringChapterEditor) { ', authoring-chapter-editor-live.png' } else { '' })$(if ($IncludePublicationRightsAcceptance) { ', publication-rights-policy-live.png' } else { '' })$(if ($IncludePublicationReviewWorkspace) { ', publication-review-workspace-live.png, publication-chapter-release-live.png' } else { '' })$(if ($IncludeNotificationInbox) { ', notification-inbox-live.png' } else { '' })$(if ($IncludeTranslationPreview) { ', translation-job-live.png' } else { '' })$(if ($IncludePaymentReturn) { ', momo-payment-return-pending-live.png, momo-payment-history-zero-live.png' } else { '' })$(if ($IncludeCommerceSettlement) { ', momo-commerce-success-live.png, momo-commerce-unlocked-chapter-live.png' } else { '' }), private-import-commit-live.png, private-import-detail-live.png, private-import-reader-live.png, private-import-html-commit-live.png, private-import-html-detail-live.png, private-import-html-reader-live.png, private-import-markdown-commit-live.png, private-import-markdown-detail-live.png, private-import-markdown-reader-live.png, private-import-epub-commit-live.png, private-import-epub-detail-live.png, private-import-epub-reader-live.png"
        $(if ($IncludeCultivationRankUp) { '- Cultivation response trace: cultivation-rank-up-trace.json' } else { '- Cultivation response trace: not requested' })
        $(if ($IncludeDatabaseRestartRecovery) { '- Database restart probes: database-restart-probes.txt, postgres-stop.log, postgres-start.log' } else { '- Database restart probes: not requested' })
        "- Logs: api-build.log, api-stdout.log, api-stderr.log, playwright.log$(if ($IncludeAuthoringDraftApi) { ', authoring-draft-api-test.log' } else { '' })$(if ($IncludeAuthoringEmptyChapterDelete) { ', authoring-empty-chapter-delete-api-test.log' } else { '' })"
        $(if ($failure) { "- Failure: $failure" } else { '- Failure: none' })
        ''
        'Synthetic users, accepted test-only policy rows, session hashes, library and works existed only in this randomly named disposable PostgreSQL project. The persistent novel-web-local project was not targeted.'
    ) | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'result.md') -Encoding utf8
    Write-Output "Cleanup: $cleanup"
    Write-Output "Evidence: $evidenceDirectory"
}

if ($result -ne 'PASS') { exit 1 }
