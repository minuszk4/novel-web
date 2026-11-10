[CmdletBinding()]
param(
  [string]$IgnoreFilePath
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($IgnoreFilePath)) {
  $repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
  $IgnoreFilePath = Join-Path $repositoryRoot '.trivyignore'
}

if (-not (Test-Path -LiteralPath $IgnoreFilePath -PathType Leaf)) {
  throw "Trivy ignore file does not exist: $IgnoreFilePath"
}

$requiredFields = @('Ticket', 'Owner', 'Severity', 'Reason', 'Mitigation', 'Approved-by')
$metadata = [ordered]@{}
$exceptionCount = 0
$lineNumber = 0
$todayUtc = [DateTime]::UtcNow.Date
$maximumExceptionDays = 30
$invariantCulture = [Globalization.CultureInfo]::InvariantCulture

foreach ($line in Get-Content -LiteralPath $IgnoreFilePath) {
  $lineNumber++
  $entry = $line.Trim()
  if ([string]::IsNullOrWhiteSpace($entry)) {
    continue
  }

  if ($entry.StartsWith('#')) {
    $comment = $entry.Substring(1).Trim()
    if ($comment -match '^(?<key>Ticket|Owner|Severity|Reason|Mitigation|Approved-by)\s*:\s*(?<value>.*)$') {
      $key = $Matches.key
      $value = $Matches.value.Trim()
      if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Missing '$key' value before exception on line $lineNumber."
      }
      if ($metadata.Contains($key)) {
        throw "Duplicate '$key' metadata before exception on line $lineNumber."
      }
      $metadata[$key] = $value
    }
    continue
  }

  if ($entry -notmatch '^(?<id>(?:CVE-\d{4}-\d+|GHSA-[A-Za-z0-9-]+))\s+exp:(?<expiry>\d{4}-\d{2}-\d{2})$') {
    throw "Invalid Trivy exception on line $lineNumber. Use a CVE/GHSA ID with exp:YYYY-MM-DD."
  }

  $vulnerabilityId = $Matches.id
  $expiryText = $Matches.expiry
  $expiryDate = [DateTime]::MinValue
  $dateParsed = [DateTime]::TryParseExact(
    $expiryText,
    'yyyy-MM-dd',
    $invariantCulture,
    [Globalization.DateTimeStyles]::None,
    [ref]$expiryDate
  )
  if (-not $dateParsed) {
    throw "Invalid expiry date for $vulnerabilityId on line $lineNumber."
  }
  if ($expiryDate -le $todayUtc) {
    throw "Exception for $vulnerabilityId is expired or expires today."
  }
  if ($expiryDate -gt $todayUtc.AddDays($maximumExceptionDays)) {
    throw "Exception for $vulnerabilityId exceeds the $maximumExceptionDays-day maximum."
  }

  foreach ($field in $requiredFields) {
    if (-not $metadata.Contains($field) -or [string]::IsNullOrWhiteSpace([string]$metadata[$field])) {
      throw "Exception for $vulnerabilityId is missing required '$field' metadata."
    }
  }
  if ($metadata.Severity -notmatch '^(HIGH|CRITICAL)$') {
    throw "Exception for $vulnerabilityId must have HIGH or CRITICAL severity."
  }
  if ($metadata.Owner -eq $metadata['Approved-by']) {
    throw "Exception for $vulnerabilityId must be approved by someone other than its owner."
  }

  $exceptionCount++
  $metadata = [ordered]@{}
}

if ($metadata.Count -gt 0) {
  throw 'Trivy exception metadata is present without a following vulnerability ID.'
}

Write-Output "Validated $exceptionCount Trivy vulnerability exception(s)."
