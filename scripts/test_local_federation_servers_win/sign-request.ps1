param(
    [string]$serverId = "your-server-a-id",
    [string]$targetServerId = "your-server-b-id",
    [string]$action = "UPDATE_TRUST",
    [string]$privateKeyFile = "server-a-private.pem"
)

# Generate timestamp
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

# Create data to sign - EXACT format must match Kotlin
$dataToSign = "$serverId|$timestamp|$targetServerId|$action"
Write-Host "Data to sign: $dataToSign"
Write-Host "Byte length: $([System.Text.Encoding]::UTF8.GetBytes($dataToSign).Length)"

# Save data to temporary file - NO BOM, pure UTF-8
$tempDataFile = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tempDataFile, $dataToSign, [System.Text.Encoding]::UTF8)

# Sign with OpenSSL
$tempSigFile = [System.IO.Path]::GetTempFileName()
openssl dgst -sha256 -sign $privateKeyFile -out $tempSigFile $tempDataFile

if ($LASTEXITCODE -ne 0) {
    Write-Error "OpenSSL signing failed!"
    exit 1
}

# Convert binary signature to base64
$signatureBytes = [System.IO.File]::ReadAllBytes($tempSigFile)
$signatureB64 = [Convert]::ToBase64String($signatureBytes)

# Cleanup
Remove-Item $tempDataFile, $tempSigFile

# Output results
Write-Host ""
Write-Host "=== Use these values ==="
Write-Host "X-Server-Id: $serverId"
Write-Host "X-Timestamp: $timestamp"
Write-Host "X-Signature: $signatureB64"
Write-Host ""

# Build curl command
$curlCmd = 'curl.exe -X POST http://localhost:8081/api/v1/federation/admin/servers/' + $targetServerId + '/trust -H "Content-Type: application/json" -H "X-Server-Id: ' + $serverId + '" -H "X-Timestamp: ' + $timestamp + '" -H "X-Signature: ' + $signatureB64 + '" -d "{`"trustLevel`":`"FULL`"}"'

Write-Host "=== Example curl ==="
Write-Host $curlCmd

# Copy to clipboard
try {
    $curlCmd | Set-Clipboard
    Write-Host ""
    Write-Host "(Copied to clipboard!)"
} catch {}

# Also output for Server B (port 8083)
Write-Host ""
Write-Host "=== For Server B (port 8083) ==="
$curlCmdB = 'curl.exe -X POST http://localhost:8083/api/v1/federation/admin/servers/' + $targetServerId + '/trust -H "Content-Type: application/json" -H "X-Server-Id: ' + $serverId + '" -H "X-Timestamp: ' + $timestamp + '" -H "X-Signature: ' + $signatureB64 + '" -d "{`"trustLevel`":`"FULL`"}"'
Write-Host $curlCmdB

return @{
    ServerId = $serverId
    Timestamp = $timestamp
    Signature = $signatureB64
    DataSigned = $dataToSign
}
