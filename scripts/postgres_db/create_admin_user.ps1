param(
    [string]$UserId = ([guid]::NewGuid().ToString()),
    [string]$Name = "Dashboard Admin",
    [string]$DeviceId = "dashboard-admin-device",
    [string]$DeviceName = "Dashboard Admin Device",
    [string]$DeviceType = "desktop",
    [string]$Platform = "windows",
    [string]$PreferredLanguage = "en",
    [string]$DbUser = "postgres",
    [string]$DbName = "mainDatabase",
    [switch]$ApplyToDb,
    [switch]$UpdateDotEnv,
    [switch]$KeepSqlFile,
    [string]$EnvFilePath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Convert-BytesToHexLower([byte[]]$bytes) {
    return ([BitConverter]::ToString($bytes)).Replace("-", "").ToLowerInvariant()
}

function Escape-SqlLiteral([string]$value) {
    return $value.Replace("'", "''")
}

function New-RandomBytes([int]$length) {
    $bytes = New-Object byte[] $length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }
    return $bytes
}

function New-Sha256Hex([string]$value) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($value)
        $hashBytes = $sha.ComputeHash($bytes)
        return ([BitConverter]::ToString($hashBytes)).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Read-Password([string]$prompt) {
    $secure = Read-Host $prompt -AsSecureString
    $plain = ([System.Net.NetworkCredential]::new("", $secure)).Password

    if ([string]::IsNullOrWhiteSpace($plain)) {
        Write-Host "Secure input returned empty in this terminal. Falling back to visible input." -ForegroundColor Yellow
        $plain = Read-Host "$prompt (input visible)"
    }

    if ($null -eq $plain) {
        return ""
    }

    return $plain.Trim()
}

function Set-Or-AppendEnvVar {
    param(
        [string]$FilePath,
        [string]$Key,
        [string]$Value
    )

    $escapedValue = $Value
    if ($escapedValue.Contains(' ') -or $escapedValue.Contains('#')) {
        $escapedValue = '"' + ($escapedValue -replace '"', '\\"') + '"'
    }

    $newLine = "${Key}=${escapedValue}"

    if (-not (Test-Path $FilePath)) {
        Set-Content -Path $FilePath -Value $newLine -Encoding UTF8
        return
    }

    $content = Get-Content -Path $FilePath -Raw -Encoding UTF8
    if ($null -eq $content) { $content = "" }
    $pattern = "(?m)^" + [Regex]::Escape($Key) + "=.*$"

    if ([Regex]::IsMatch($content, $pattern)) {
        $updated = [Regex]::Replace($content, $pattern, $newLine)
        Set-Content -Path $FilePath -Value $updated -Encoding UTF8
    } else {
        if ($content.Length -gt 0 -and -not $content.EndsWith("`n")) {
            $content += "`r`n"
        }
        $content += $newLine + "`r`n"
        Set-Content -Path $FilePath -Value $content -Encoding UTF8
    }
}

# Generate secp256r1 (nistP256) keypair - compatible with Flutter CryptoService(curveName: secp256r1)
# Support both newer .NET APIs and older Windows PowerShell/.NET runtime.
$ecdsa = $null

try {
    $curve = [System.Security.Cryptography.ECCurve]::NamedCurves.nistP256
    $ecdsa = [System.Security.Cryptography.ECDsa]::Create($curve)
}
catch {
    try {
        $ecdsa = New-Object System.Security.Cryptography.ECDsaCng(256)
        $ecdsa.KeySize = 256
    }
    catch {
        throw "Failed to create secp256r1 keypair on this runtime: $($_.Exception.Message)"
    }
}

$keyParams = $ecdsa.ExportParameters($true)

$rawPublicKey = New-Object byte[] 65
$rawPublicKey[0] = 0x04
[Array]::Copy($keyParams.Q.X, 0, $rawPublicKey, 1, 32)
[Array]::Copy($keyParams.Q.Y, 0, $rawPublicKey, 33, 32)
$publicKeyBase64 = [Convert]::ToBase64String($rawPublicKey)

$privateKeyHex = Convert-BytesToHexLower $keyParams.D
$privateKeyHex = $privateKeyHex -replace '^0+(?!$)', ''

$deviceKeyId = [guid]::NewGuid().ToString()

$escUserId = Escape-SqlLiteral $UserId
$escName = Escape-SqlLiteral $Name
$escDeviceId = Escape-SqlLiteral $DeviceId
$escDeviceName = Escape-SqlLiteral $DeviceName
$escDeviceType = Escape-SqlLiteral $DeviceType.ToLowerInvariant()
$escPlatform = Escape-SqlLiteral $Platform.ToLowerInvariant()
$escPreferredLanguage = Escape-SqlLiteral $PreferredLanguage.ToLowerInvariant()
$escPublicKey = Escape-SqlLiteral $publicKeyBase64
$escDeviceKeyId = Escape-SqlLiteral $deviceKeyId

$sql = @"
BEGIN;

INSERT INTO user_registration_data (id, public_key, created_at, updated_at)
VALUES ('$escUserId', '$escPublicKey', NOW(), NOW())
ON CONFLICT (id)
DO UPDATE SET
    public_key = EXCLUDED.public_key,
    updated_at = NOW();

INSERT INTO user_profiles (
    user_id,
    name,
    profile_keywords_with_weights,
    preferred_language,
    account_type,
    updated_at
)
VALUES (
    '$escUserId',
    '$escName',
    '{}'::jsonb,
    '$escPreferredLanguage',
    'ADMIN',
    NOW()
)
ON CONFLICT (user_id)
DO UPDATE SET
    name = EXCLUDED.name,
    preferred_language = EXCLUDED.preferred_language,
    account_type = 'ADMIN',
    updated_at = NOW();

INSERT INTO user_device_keys (
    id,
    user_id,
    device_id,
    public_key,
    device_name,
    device_type,
    platform,
    is_active,
    last_used_at,
    created_at,
    deactivated_at,
    deactivated_reason
)
VALUES (
    '$escDeviceKeyId',
    '$escUserId',
    '$escDeviceId',
    '$escPublicKey',
    '$escDeviceName',
    '$escDeviceType',
    '$escPlatform',
    TRUE,
    NOW(),
    NOW(),
    NULL,
    NULL
)
ON CONFLICT (user_id, device_id)
DO UPDATE SET
    public_key = EXCLUDED.public_key,
    device_name = EXCLUDED.device_name,
    device_type = EXCLUDED.device_type,
    platform = EXCLUDED.platform,
    is_active = TRUE,
    last_used_at = NOW(),
    deactivated_at = NULL,
    deactivated_reason = NULL;

COMMIT;
"@

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$outFile = Join-Path $PSScriptRoot "create_admin_user_${timestamp}.sql"
Set-Content -Path $outFile -Value $sql -Encoding UTF8

Write-Host "=== Admin bootstrap generated ==="
Write-Host "User ID:           $UserId"
Write-Host "Device ID:         $DeviceId"
Write-Host "Public Key (B64):  $publicKeyBase64"
Write-Host "Private Key (hex): $privateKeyHex"
Write-Host "SQL file:          $outFile"
Write-Host ""
Write-Host "IMPORTANT: Store the private key securely; it is required for client-side signing."

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$targetEnvFile = if ([string]::IsNullOrWhiteSpace($EnvFilePath)) {
    Join-Path $repoRoot ".env"
} else {
    if ([System.IO.Path]::IsPathRooted($EnvFilePath)) { $EnvFilePath } else { Join-Path $repoRoot $EnvFilePath }
}

if ($UpdateDotEnv) {
    $dashboardSessionEncryptionKeyB64 = [Convert]::ToBase64String((New-RandomBytes 16))
    $dashboardSessionSigningKeyB64 = [Convert]::ToBase64String((New-RandomBytes 32))

    Write-Host ""
    Write-Host "Configure dashboard login credential"
    $dashboardAdminUsername = Read-Host "Dashboard admin username"

    if ([string]::IsNullOrWhiteSpace($dashboardAdminUsername)) {
        throw "Dashboard admin username cannot be empty when -UpdateDotEnv is used."
    }

    $dashboardAdminPassword = Read-Password "Dashboard admin password"
    $dashboardAdminPasswordConfirm = Read-Password "Confirm dashboard admin password"

    if ($dashboardAdminPassword -ne $dashboardAdminPasswordConfirm) {
        throw "Dashboard admin password confirmation does not match."
    }

    if ([string]::IsNullOrWhiteSpace($dashboardAdminPassword)) {
        throw "Dashboard admin password cannot be empty."
    }

    $dashboardPasswordSha256 = New-Sha256Hex -value $dashboardAdminPassword

    if ($dashboardPasswordSha256 -eq "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") {
        throw "Refusing to write DASHBOARD_ADMIN_CREDENTIALS with empty password hash. Re-run and type a non-empty password."
    }

    $dashboardCredentialsEntry = "$($dashboardAdminUsername.Trim()):sha256:$dashboardPasswordSha256"
    $dashboardAdminPassword = ""
    $dashboardAdminPasswordConfirm = ""

    $existingCredentials = ""
    if (Test-Path $targetEnvFile) {
        $existingCredentialsMatch = Select-String -Path $targetEnvFile -Pattern '^DASHBOARD_ADMIN_CREDENTIALS=(.*)$' -AllMatches
        if ($existingCredentialsMatch -and $existingCredentialsMatch.Matches.Count -gt 0) {
            $existingCredentials = $existingCredentialsMatch.Matches[0].Groups[1].Value.Trim().Trim('"')
        }
    }

    $newCredentials = if ([string]::IsNullOrWhiteSpace($existingCredentials)) {
        $dashboardCredentialsEntry
    } else {
        $entries = $existingCredentials -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }
        $filteredEntries = @()
        foreach ($entry in $entries) {
            if (-not $entry.StartsWith("$($dashboardAdminUsername.Trim()):")) {
                $filteredEntries += $entry
            }
        }
        ($filteredEntries + $dashboardCredentialsEntry) -join ';'
    }

    Set-Or-AppendEnvVar -FilePath $targetEnvFile -Key "DASHBOARD_ADMIN_USER_ID" -Value $UserId
    Set-Or-AppendEnvVar -FilePath $targetEnvFile -Key "DASHBOARD_ADMIN_PRIVATE_KEY_HEX" -Value $privateKeyHex
    Set-Or-AppendEnvVar -FilePath $targetEnvFile -Key "DASHBOARD_ADMIN_CREDENTIALS" -Value $newCredentials
    Set-Or-AppendEnvVar -FilePath $targetEnvFile -Key "DASHBOARD_SESSION_ENCRYPTION_KEY_B64" -Value $dashboardSessionEncryptionKeyB64
    Set-Or-AppendEnvVar -FilePath $targetEnvFile -Key "DASHBOARD_SESSION_SIGNING_KEY_B64" -Value $dashboardSessionSigningKeyB64

    Write-Host "Updated dashboard admin variables in: $targetEnvFile"
    Write-Host "Stored/updated DASHBOARD_ADMIN_CREDENTIALS entry for username: $($dashboardAdminUsername.Trim())"
    Write-Host "Generated new DASHBOARD_SESSION_ENCRYPTION_KEY_B64 and DASHBOARD_SESSION_SIGNING_KEY_B64 values."
}

if ($ApplyToDb) {
    Write-Host "Applying SQL to docker compose postgres service..."

    Push-Location $repoRoot
    try {
        $sql | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U $DbUser -d $DbName
        Write-Host "Admin user inserted/updated successfully."

        if (-not $KeepSqlFile) {
            if (Test-Path $outFile) {
                Remove-Item -Path $outFile -Force
                Write-Host "Deleted generated SQL file: $outFile"
            }
        } else {
            Write-Host "Keeping generated SQL file (KeepSqlFile enabled): $outFile"
        }
    }
    finally {
        Pop-Location
    }
} else {
    Write-Host "SQL not applied (dry-run). Use -ApplyToDb to execute directly."
    Write-Host "Generated SQL file kept at: $outFile"
}
