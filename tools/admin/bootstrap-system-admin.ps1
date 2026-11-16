param(
    [Parameter(Mandatory = $true)]
    [Guid]$UserId,

    [Parameter(Mandatory = $true)]
    [Guid]$ConfirmUserId,

    [Parameter(Mandatory = $true)]
    [ValidateLength(1, 255)]
    [string]$DatabaseHost,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 65535)]
    [int]$DatabasePort,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{1,63}$')]
    [string]$Database,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_.@-]{1,128}$')]
    [string]$DatabaseUser,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._/-]{3,128}$')]
    [string]$Ticket
)

$ErrorActionPreference = 'Stop'

$psqlCommand = Get-Command psql -ErrorAction SilentlyContinue
if (-not $psqlCommand) {
    throw 'PostgreSQL psql was not found. Install the PostgreSQL client and configure its normal connection environment.'
}

$operatorName = if ($env:USERNAME) { $env:USERNAME } else { [Environment]::UserName }
$computerName = if ($env:COMPUTERNAME) { $env:COMPUTERNAME } else { [Environment]::MachineName }
$operatorIdentity = "windows:$computerName\$operatorName,database-user:$databaseUser"
if ($operatorIdentity.Length -gt 160) {
    throw 'The local operator identity is too long to record in the bootstrap audit.'
}

if ($ConfirmUserId -ne $UserId) {
    throw 'Confirmation UUID did not match the target account. No database changes were made.'
}

$assignmentId = [guid]::NewGuid().ToString()
$eventId = [guid]::NewGuid().ToString()
$sql = @'
BEGIN;
WITH bootstrap_lock AS MATERIALIZED (
    SELECT pg_advisory_xact_lock(hashtextextended('novelweb:first-system-admin-bootstrap', 0))
),
target_user AS MATERIALIZED (
    SELECT id
    FROM users
    WHERE id = :'target_user_id'::uuid AND status = 'active'
    FOR UPDATE
),
eligible_target AS (
    SELECT target_user.id
    FROM target_user
    CROSS JOIN bootstrap_lock
    WHERE NOT EXISTS (
        SELECT 1 FROM security_role_assignments
        WHERE role_code = 'system_admin' AND revoked_at IS NULL
    )
      AND NOT EXISTS (
        SELECT 1 FROM security_role_assignment_events
        WHERE action_code = 'BOOTSTRAPPED' AND role_code = 'system_admin'
    )
),
created_assignment AS (
    INSERT INTO security_role_assignments
        (id, user_id, role_code, assigned_by_user_id, reason_code)
    SELECT :'assignment_id'::uuid, eligible_target.id, 'system_admin', NULL,
           'INITIAL_ADMIN_BOOTSTRAP'
    FROM eligible_target
    RETURNING id, user_id
),
created_event AS (
    INSERT INTO security_role_assignment_events
        (id, assignment_id, target_user_id, role_code, action_code,
         operator_identity, ticket_reference, reason_code)
    SELECT :'event_id'::uuid, created_assignment.id, created_assignment.user_id,
           'system_admin', 'BOOTSTRAPPED', :'operator_identity',
           :'ticket_reference', 'INITIAL_ADMIN_BOOTSTRAP'
    FROM created_assignment
    RETURNING target_user_id, occurred_at
)
SELECT 'BOOTSTRAPPED|' || target_user_id::text || '|' || occurred_at::text
FROM created_event;
COMMIT;
'@

$psqlArguments = @(
    '-X',
    '-q',
    '-A',
    '-t',
    '-w',
    '-v',
    'ON_ERROR_STOP=1',
    '-h',
    $DatabaseHost,
    '-p',
    [string]$DatabasePort,
    '-U',
    $DatabaseUser,
    '-d',
    $Database,
    '-v',
    "target_user_id=$($UserId.ToString())",
    '-v',
    "assignment_id=$assignmentId",
    '-v',
    "event_id=$eventId",
    '-v',
    "operator_identity=$operatorIdentity",
    '-v',
    "ticket_reference=$Ticket"
)
$output = $sql | & $psqlCommand.Source @psqlArguments 2>&1
if ($LASTEXITCODE -ne 0) {
    throw 'First-admin bootstrap failed. Verify the account is active, the ticket is valid, and bootstrap has not already been used. No success is recorded.'
}

$result = ($output -join [Environment]::NewLine).Trim()
if ($result -notmatch "^BOOTSTRAPPED\|$([regex]::Escape($UserId.ToString()))\|") {
    throw 'No administrator was created. The account may be inactive, an administrator may already exist, or first-admin bootstrap may have been used before.'
}

Write-Output "First system administrator recorded for account $UserId."
Write-Output "Database: $($DatabaseHost):$DatabasePort/$Database (user $DatabaseUser)"
Write-Output "Ticket: $Ticket"
Write-Output "Operator: $operatorIdentity"
Write-Output "Database audit result: $result"
Write-Output 'This one-time bootstrap cannot be repeated after its event has been recorded.'
