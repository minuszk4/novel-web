[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$image = 'ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:b109bc5f8f76a38196a3e413704fc5b9e3c32360bce4e4b603bd6f45b3721dbb'
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue |
    Where-Object CommandType -eq 'Application' |
    Select-Object -First 1

if (-not $dockerCommand -and $env:LOCALAPPDATA) {
    $dockerBin = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin'
    $dockerCandidate = Join-Path $dockerBin 'docker.exe'
    if (Test-Path -LiteralPath $dockerCandidate -PathType Leaf) {
        $env:PATH = "$dockerBin;$env:PATH"
        $dockerCommand = Get-Command $dockerCandidate -ErrorAction SilentlyContinue
    }
}

if (-not $dockerCommand) {
    throw 'Docker CLI was not found. Install Docker Desktop or add its resources\bin directory to PATH.'
}

$dockerExecutable = $dockerCommand.Source
$dockerBinDirectory = Split-Path -Parent $dockerExecutable
if (-not (($env:PATH -split [IO.Path]::PathSeparator) -contains $dockerBinDirectory)) {
    $env:PATH = "$dockerBinDirectory$([IO.Path]::PathSeparator)$env:PATH"
}

function Invoke-GitleaksContainer {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = @(& $dockerExecutable @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    [pscustomobject]@{
        ExitCode = $exitCode
        Output = ($output | Out-String)
    }
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('novel-web-secret-scan-' + [guid]::NewGuid().ToString('N'))
$fixtureRoot = Join-Path $tempRoot 'fixture'
$snapshotRoot = Join-Path $tempRoot 'snapshot'
New-Item -ItemType Directory -Path $fixtureRoot, $snapshotRoot | Out-Null

try {
    # Prove the pinned image detects a synthetic secret and masks it in output.
    $canary = 'NOVEL_WEB_SECRET_CANARY_' + [guid]::NewGuid().ToString('N').ToUpperInvariant()
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'sample.txt') -Value "TOKEN=$canary" -NoNewline
    @'
title = "Novel Web secret scanner self-test"
[extend]
useDefault = true
[[rules]]
id = "novel-web-ci-canary"
description = "Detects only the synthetic scanner self-test value"
regex = '''NOVEL_WEB_SECRET_CANARY_[A-F0-9]{32}'''
secretGroup = 0
keywords = ["NOVEL_WEB_SECRET_CANARY_"]
'@ | Set-Content -LiteralPath (Join-Path $fixtureRoot 'gitleaks.toml') -NoNewline

    $fixtureMount = "type=bind,source=$fixtureRoot,target=/fixture,readonly"
    $fixtureResult = Invoke-GitleaksContainer @(
        'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--mount', $fixtureMount,
        $image, 'dir', '--config', '/fixture/gitleaks.toml', '--redact',
        '--no-banner', '/fixture/sample.txt'
    )
    if ($fixtureResult.ExitCode -ne 1) {
        throw 'Gitleaks self-test failed: the synthetic canary was not detected.'
    }
    if (-not $fixtureResult.Output.ToLowerInvariant().Contains('leaks found: 1')) {
        throw 'Gitleaks self-test failed: scanner did not report exactly one canary finding.'
    }
    if ($fixtureResult.Output.Contains($canary)) {
        throw 'Gitleaks self-test failed: scanner output did not redact the synthetic canary.'
    }
    Write-Output 'Secret scanner directory self-test passed: canary detected and redacted.'

    # Exercise history mode in a disposable Git repository before scanning a project repository.
    & git -C $fixtureRoot init --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Could not initialize the temporary scanner history fixture.' }
    & git -C $fixtureRoot config user.name 'Novel Web Secret Scan'
    if ($LASTEXITCODE -ne 0) { throw 'Could not configure the temporary scanner history fixture.' }
    & git -C $fixtureRoot config user.email 'secret-scan-fixture@example.invalid'
    if ($LASTEXITCODE -ne 0) { throw 'Could not configure the temporary scanner history fixture.' }
    & git -C $fixtureRoot add -- sample.txt
    if ($LASTEXITCODE -ne 0) { throw 'Could not stage the temporary scanner history fixture.' }
    & git -C $fixtureRoot commit --quiet -m 'synthetic secret scanner fixture'
    if ($LASTEXITCODE -ne 0) { throw 'Could not commit the temporary scanner history fixture.' }

    $historyFixtureResult = Invoke-GitleaksContainer @(
        'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--mount', $fixtureMount,
        $image, 'git', '--config', '/fixture/gitleaks.toml', '--redact',
        '--no-banner', '--log-opts=--all', '/fixture'
    )
    if ($historyFixtureResult.ExitCode -ne 1 -or
        -not $historyFixtureResult.Output.ToLowerInvariant().Contains('leaks found: 1')) {
        throw 'Gitleaks history self-test failed: the synthetic canary was not detected.'
    }
    if ($historyFixtureResult.Output.Contains($canary)) {
        throw 'Gitleaks history self-test failed: scanner output did not redact the synthetic canary.'
    }
    Write-Output 'Secret scanner history self-test passed: canary detected and redacted.'

    # Stage only Git-visible files. This honors .gitignore, including .ai and local .env files.
    $gitFiles = @(& git -C $repoRoot ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not enumerate Git-visible files for the secret scan.'
    }
    $rootPrefix = $repoRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    foreach ($relativePath in $gitFiles) {
        if ([string]::IsNullOrWhiteSpace($relativePath)) { continue }
        $sourcePath = [IO.Path]::GetFullPath((Join-Path $repoRoot $relativePath))
        if (-not $sourcePath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Git returned a path outside the repository: $relativePath"
        }
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) { continue }

        $targetPath = Join-Path $snapshotRoot $relativePath
        $targetDirectory = Split-Path -Parent $targetPath
        New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
        Copy-Item -LiteralPath $sourcePath -Destination $targetPath -Force
    }

    $snapshotMount = "type=bind,source=$snapshotRoot,target=/scan,readonly"
    $snapshotResult = Invoke-GitleaksContainer @(
        'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--mount', $snapshotMount,
        $image, 'dir', '--gitleaks-ignore-path', '/scan/.gitleaksignore',
        '--redact', '--no-banner', '/scan'
    )
    if ($snapshotResult.ExitCode -ne 0) {
        Write-Output $snapshotResult.Output
        throw 'Secret scan found a finding or failed. Review only the redacted scanner output.'
    }
    Write-Output 'Secret scan passed for Git-visible working-tree files.'

    # Scan all reachable Git history once an initial commit exists.
    & git -C $repoRoot rev-parse --verify HEAD *> $null
    $hasHead = $LASTEXITCODE -eq 0
    if ($hasHead) {
        $historyMount = "type=bind,source=$repoRoot,target=/repo,readonly"
        $historyResult = Invoke-GitleaksContainer @(
            'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--mount', $historyMount,
            $image, 'git', '--gitleaks-ignore-path', '/repo/.gitleaksignore',
            '--redact', '--no-banner', '--log-opts=--all', '/repo'
        )
        if ($historyResult.ExitCode -ne 0) {
            Write-Output $historyResult.Output
            throw 'Secret scan found a finding or failed in Git history. Review only the redacted scanner output.'
        }
        Write-Output 'Secret scan passed for reachable Git history.'
    }
    else {
        Write-Output 'Git-history scan skipped because this repository has no initial commit yet.'
    }
}
finally {
    $resolvedTempRoot = [IO.Path]::GetFullPath($tempRoot).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $resolvedSystemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($resolvedTempRoot.StartsWith($resolvedSystemTemp, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedTempRoot).StartsWith('novel-web-secret-scan-', [StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolvedTempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
