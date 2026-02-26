# Local Federation Testing Guide - Windows Version

This guide explains how to test the federated servers feature locally using two server instances.

---

## Overview

Federation allows multiple barter servers to communicate and share data. This setup runs two independent server instances on your local machine for testing.

| Server | URL                   | Database Port | Purpose |
|--------|-----------------------|---------------|---------|
| Server A | http://localhost:8081 | 5432          | Primary test server |
| Server B | http://localhost:8083 | 5434          | Secondary test server |

> **Important:** Servers communicate via Docker internal URLs (`barter-a`, `barter-b`), not `localhost`.

## Prerequisites

1. Docker and Docker Compose installed
2. This project built (`docker build .` should succeed)
3. Generate the secret for federation init token:
openssl rand -hex 32
4. FEDERATION_INIT_TOKEN=a3f9b2c8d1e5f7a0b4c6d9e8f2a1b5c7d3e8f9a0b1c2d4e5f6a7b8c9d0e1f2 (env var)
5. ADMIN_IP_ALLOWLIST=127.0.0.1,::1,192.168.1.0/24,10.0.0.0/8 (env var)

---

## Quick Start

### 1. Setup the Federation Test Environment

```powershell
# Stop any existing containers first
docker-compose down
docker-compose -f docker-compose.federation.yml down

Optional:
docker network prune -f
docker volume rm barter_app_backend_postgres_data
mark network as external: true in federated docker compose

# Adjust application.conf ports before each start

# 1. Adjust Dockerfile EXPOSE port to 8083
# 2. Adjust application.conf postgres to postgres2
# 3. Adjust Application port to 8083

# Start the federation setup
docker-compose -f docker-compose.federation.yml up --build

# 1. Adjust Dockerfile EXPOSE port to 8081
# 2. Adjust application.conf postgres2 to postgres
# 3. Adjust Application port to 8081

docker-compose -f docker-compose.yml up --build

```

### 2. Verify Both Servers Are Running

```powershell
# Check Server A
curl.exe http://localhost:8081/public-api/v1/healthCheck

# Check Server B  
curl.exe http://localhost:8083/public-api/v1/healthCheck
```

Both should return a health status response.

### 3. Federation Own Server Identity Setup

---

**Authentication:**.

Each server needs its own identity before it can federate. The `serverUrl` must use Docker internal 
hostnames so servers can reach each other.

Since this is a bootstrap endpoint (the RSA keypair doesn't exist yet),
we cannot use RSA signature verification. Instead:

1. Client sends header `X-Timestamp: current_unix_millis`
2. Client computes `X-Init-Token = lowercase_hex( HMAC-SHA256(FEDERATION_INIT_TOKEN, X-Timestamp) )`

The raw secret is never transmitted — only its keyed digest is sent.

**PowerShell example:**
```powershell
# Configuration

**Unix Example:**

# Compute HMAC-SHA256 using openssl
TOKEN=$(echo -n "$TIMESTAMP" | openssl dgst -sha256 -hmac "$FEDERATION_INIT_TOKEN" | awk '{print $NF}')

```

### Initialize Server A (Port 8081)

```powershell

$FEDERATION_INIT_TOKEN = "a3f9b2c8d1e5f7a0b4c6d9e8f2a1b5c7d3e8f9a0b1c2d4e5f6a7b8c9d0e1f2"
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

# Compute HMAC-SHA256
$keyBytes = [System.Text.Encoding]::UTF8.GetBytes($FEDERATION_INIT_TOKEN)
$dataBytes = [System.Text.Encoding]::UTF8.GetBytes($timestamp.ToString())
$hmac = New-Object System.Security.Cryptography.HMACSHA256(, $keyBytes)
$hashBytes = $hmac.ComputeHash($dataBytes)
$token = -join ($hashBytes | ForEach-Object { "{0:x2}" -f $_ })

curl.exe -X POST http://localhost:8081/api/v1/federation/admin/initialize `
  -H "Content-Type: application/json" `
  -H "X-Timestamp: $timestamp" `
  -H "X-Init-Token: $token" `
  -d '{\"serverUrl\": \"http://localhost:8081\", \"serverName\": \"Barter Server Alpha\", \"adminContact\": \"admin-a@localhost\", \"description\": \"Primary test server\", \"locationHint\": \"Local-Test-A\"}'
```

### Initialize Server B (Port 8083)

```powershell

$FEDERATION_INIT_TOKEN = "8403d9570aa42d4234d578fc5c1b00bfe3dfb99e1764974a7d9d84c771d94072"
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

# Compute HMAC-SHA256
$keyBytes = [System.Text.Encoding]::UTF8.GetBytes($FEDERATION_INIT_TOKEN)
$dataBytes = [System.Text.Encoding]::UTF8.GetBytes($timestamp.ToString())
$hmac = New-Object System.Security.Cryptography.HMACSHA256(, $keyBytes)
$hashBytes = $hmac.ComputeHash($dataBytes)
$token = -join ($hashBytes | ForEach-Object { "{0:x2}" -f $_ })

curl.exe -X POST http://localhost:8083/api/v1/federation/admin/initialize `
  -H "Content-Type: application/json" `
  -H "X-Timestamp: $timestamp" `
  -H "X-Init-Token: $token" `
  -d '{\"serverUrl\": \"http://localhost:8083\", \"serverName\": \"Barter Server Beta\", \"adminContact\": \"admin-b@localhost\", \"description\": \"Secondary test server\", \"locationHint\": \"Local-Test-B\"}'
```

Both should return `success: true` with a generated `serverId`. **Save these IDs** - you'll need them later.

## 4. Federation Handshake Test

You'll need admin users on **both** servers for the handshake process.
Federation requires **both servers** to handshake with each other. This is not automatic!

## Bidirectional Handshake

## To get the server ids:

docker exec -it postgresql_server psql -U postgres -d mainDatabase

SELECT server_id FROM local_server_identity;

SELECT server_id, server_name, server_url, trust_level, is_active, federation_agreement_hash
FROM federated_servers;

Exit with \q

### Server A Initiates Handshake to Server B

```powershell
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

curl.exe -X POST http://localhost:8083/api/v1/federation/admin/handshake `
  -H "Content-Type: application/json" `
  -H "X-Timestamp: $timestamp" `
  -d '{\"targetServerUrl\": \"http://barter_app_server:8081\", \"proposedScopes\": {\"users\": true, \"postings\": true, \"chat\": true, \"geolocation\": true, \"attributes\": true}}'
```

**Expected response:** `accepted: true` with Server B's details.

### Server B Initiates Handshake Back to Server A

> **Critical:** Use the Docker internal URL `http://barter-a:8081`

```powershell
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

curl.exe -X POST http://localhost:8081/api/v1/federation/admin/handshake `
  -H "Content-Type: application/json" `
  -H "X-Timestamp: $timestamp" `
  -d '{\"targetServerUrl\": \"http://barter_app_server2:8083\", \"proposedScopes\": {\"users\": true, \"postings\": true, \"chat\": true, \"geolocation\": true, \"attributes\": true}}'
```

### 5: Verify Trust Status

Check both servers to see the pending trust relationships:

```powershell
# Check Server A's federated servers
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
curl.exe http://localhost:8081/api/v1/federation/admin/servers `
  -H "X-Federation-Shared-Secret: a3f9b2c8d1e5f7a0b4c6d9e8f2a333c7d3e8f9a0b1c2d4e5f6a7b8c9d0e1f2" `
  -H "X-Timestamp: $timestamp"

# Check Server B's federated servers
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
curl.exe http://localhost:8083/api/v1/federation/admin/servers `
  -H "X-Federation-Shared-Secret: a3f9b2c8d1e5f7a0b4c6d9e8f2a333c7d3e8f9a0b1c2d4e5f6a7b8c9d0e1f2" `
  -H "X-Timestamp: $timestamp"
```

Both should show `trustLevel: PENDING`.

### 6: Upgrade Trust Level to FULL

## Upgrading Trust Level

After both handshakes complete, manually upgrade trust from `PENDING` to `FULL` on both servers.

### On Server A - Trust Server B

Use script in scripts\test_local_federation_servers_win to get curl request for Trust update.
Get private key from local_server_identity Table

```powershell

.\sign-request.ps1 -serverId "2b28f997-2b8d-423d-b61f-e681de047418" -targetServerId "2b28f997-2b8d-423d-b61f-e681de047418" -privateKeyFile "server-a-private.pem"

curl.exe -X POST http://localhost:8083/api/v1/federation/admin/servers/2b28f997-2b8d-423d-b61f-e681de047418/trust -H "Content-Type: application/json" -H "X-Server-Id: 2b28f997-2b8d-423d-b61f-e681de047418" -H "X-Timestamp: 1772030260038" -H "X-Signature: $signature" -d '{\"trustLevel\":\"FULL\"}'                                                                                                                                                                                                                                                      
{
  "success" : true,
  "serverId" : "2b28f997-2b8d-423d-b61f-e681de047418",
  "trustLevel" : "FULL",
  "message" : "Trust level updated successfully"
}
**On Server B - Trust Server A:**
```

### On Server B - Trust Server A:

```powershell

curl.exe -X POST "http://localhost:8081/api/v1/federation/admin/servers/90644f4a-cce2-49e9-b65d-7ec52ea0fefa/trust" `
  -H "Content-Type: application/json" `
  -H "X-Server-Id: $serverId" `
  -H "X-Timestamp: $timestamp" `
  -H "X-Signature: test-signature" `
  -d '{\"trustLevel\": \"FULL\"}'
```

---

## Testing Cross-Server Queries

Once federation is established, you can query data from one server via the other.

### Query Users from Server B via Server A
```powershell
# IMPORTANT: Change barter-b:8081 to localhost:8083!
# The signature is bound to exact coordinates, radius, and timestamp

$timestamp = "<TIMESTAMP>"
$signature = "<URL-ENCODED_SIGNATURE"

# Query Server B's users via Server A's signed request
curl.exe "http://localhost:8083/federation/v1/users/nearby?serverId=<OWN_SERVER_ID>&lat=56.95&lon=24.10&radius=50000&timestamp=$timestamp&signature=Ieon6...f7A%3D%3D"
```

### Query Postings

```powershell
# Generate signed URL
curl.exe "http://localhost:8081/api/v1/federation/admin/proxy/postings/search?targetServerId=34f24f14-4d7b-456e-bb59-3aec28002322&q=test&limit=20"

# Execute query with proper URL-encoding
$timestamp = "<TIMESTAMP>"
$signature = "<URL-ENCODED_SIGNATURE>"

curl.exe "http://localhost:8083/federation/v1/postings/search?serverId=2b28f997-2b8d-423d-b61f-e681de047418&q=test&limit=20&timestamp=$timestamp&signature=$signature"
```

## Example:

```
PS C:\Users\User\barter_app_backend> curl.exe "http://localhost:8081/api/v1/federation/admin/proxy/users/nearby?targetServerId=84d24ffb-de34-4cf9-84c2-2a59d0f01332&lat=48.85&lon=2.29&radius=50000"
{
    "success" : true,
    "targetUrl" : "http://barter_app_server2:8083/federation/v1/users/nearby?serverId=b70e1db9-0d3f-4a99-8bf9-0d40b4553ac3&lat=48.85&lon=2.29&radius=50000.0&timestamp=1771951924847&signature=eFp3H6JzbKBnb79kwJ9NtHLJSmIKxU/zgoJzDLZewokS0sbd9zuM2y4gXU08yyLN8Q1BhfUC/Ehg8GjWP5C0KctZxQ+9apVNybWooykLGzlCRXzbLHpd5lCOfOdXJyzX5zJ21XyMxK+RZN3CdvCWUKmL4PmtDVmEQv5+d61OBH3MDFz46MG8Zfxp0ij37GX2uEzOL3RvnixJXqNigt0MZBYtPrfWOmywfo/mCbVlCgXiyfKzJTBQgyWrcRkgK7uvuFMPnrtP8vG7s9nkMv+OR7aa1+JFQjGNZUxQMiCs+hnMM98fWxU556TPa5Wx3Kj+yuVTx7mNNneFBA8ofpl1QA==",
    "signatureData" : "b70e1db9-0d3f-4a99-8bf9-0d40b4553ac3|48.85|2.29|50000.0|1771951924847",
    "signature" : "eFp3H6JzbKBnb79kwJ9NtHLJSmIKxU/zgoJzDLZewokS0sbd9zuM2y4gXU08yyLN8Q1BhfUC/Ehg8GjWP5C0KctZxQ+9apVNybWooykLGzlCRXzbLHpd5lCOfOdXJyzX5zJ21XyMxK+RZN3CdvCWUKmL4PmtDVmEQv5+d61OBH3MDFz46MG8Zfxp0ij37GX2uEzOL3RvnixJXqNigt0MZBYtPrfWOmywfo/mCbVlCgXiyfKzJTBQgyWrcRkgK7uvuFMPnrtP8vG7s9nkMv+OR7aa1+JFQjGNZUxQMiCs+hnMM98fWxU556TPa5Wx3Kj+yuVTx7mNNneFBA8ofpl1QA==",
    "localServerId" : "b70e1db9-0d3f-4a99-8bf9-0d40b4553ac3",
    "targetServerId" : "84d24ffb-de34-4cf9-84c2-2a59d0f01332"
}
PS C:\Users\User\barter_app_backend> $timestamp = "1771951924847"                                                                                                                                                                                                                                        
PS C:\Users\User\barter_app_backend> curl.exe "http://localhost:8083/federation/v1/users/nearby?serverId=b70e1db9-0d3f-4a99-8bf9-0d40b4553ac3&lat=48.85&lon=2.29&radius=50000&signature=eFp3H6JzbKBnb79kwJ9NtHLJSmIKxU%2FzgoJzDLZewokS0sbd9zuM2y4gXU08yyLN8Q1BhfUC%2FEhg8GjWP5C0KctZxQ%2B9apVNybWooykLGzlCRXzbLHpd5lCOfOdXJyzX5zJ21XyMxK%2BRZN3CdvCWUKmL4PmtDVmEQv5%2Bd61OBH3MDFz46MG8Zfxp0ij37GX2uEzOL3RvnixJXqNigt0MZBYtPrfWOmywfo%2FmCbVlCgXiyfKzJTBQgyWrcRkgK7uvuFMPnrtP8vG7s9nkMv%2BOR7aa1%2BJFQjGNZUxQMiCs%2BhnMM98fWxU556TPa5Wx3Kj%2ByuVTx7mNNneFBA8ofpl1QA%3D%3D&timestamp=$timestamp"                               
{
    "success" : true,
    "data" : {
    "users" : [ ],
    "count" : 0
},
    "timestamp" : 1771952003707
}
PS C:\Users\User\barter_app_backend> curl.exe "http://localhost:8083/federation/v1/users/nearby?serverId=b70e1db9-0d3f-4a99-8bf9-0d40b4553ac3&lat=48.85&lon=2.29&radius=50000&signature=eFp3H6JzbKBnb79kwJ9NtHLJSmIKxU%2FzgoJzDLZewokS0sbd9zuM2y4gXU08yyLN8Q1BhfUC%2FEhg8GjWP5C0KctZxQ%2B9apVNybWooykLGzlCRXzbLHpd5lCOfOdXJyzX5zJ21XyMxK%2BRZN3CdvCWUKmL4PmtDVmEQv5%2Bd61OBH3MDFz46MG8Zfxp0ij37GX2uEzOL3RvnixJXqNigt0MZBYtPrfWOmywfo%2FmCbVlCgXiyfKzJTBQgyWrcRkgK7uvuFMPnrtP8vG7s9nkMv%2BOR7aa1%2BJFQjGNZUxQMiCs%2BhnMM98fWxU556TPa5Wx3Kj%2ByuVTx7mNNneFBA8ofpl1QA%3D%3D&timestamp=$timestamp"                               
{
    "success" : true,
    "data" : {
    "users" : [ {
        "userId" : "4ce01c32-da6f-4927-a4d6-17bbfc0b9f64",
        "name" : "User_1",
        "location" : {
            "lat" : 48.85613168160397,
            "lon" : 2.3373413085937504
        },
        "attributes" : [ "day_trading", "handmade_items", "handyman_services", "moving_help", "permaculture", "storytelling" ],
        "lastOnline" : "2026-02-24T16:55:22.494Z"
    } ],
    "count" : 1
    },
    "timestamp" : 1771952131971
}
```
---

## Critical Rules for Signatures

Signatures are cryptographically bound to **exact parameter values**:

| Parameter | Must Match |
|-----------|------------|
| `serverId` | The requesting server's ID |
| `lat` | Exact value used during signing |
| `lon` | Exact value used during signing |
| `radius` | Exact value used during signing |
| `timestamp` | Exact millisecond value |
| `q` (for postings) | Exact search term |
| `limit` (for postings) | Exact limit value |

**If you change any value, the signature becomes invalid!**

### URL-Encoding Special Characters

Base64 signatures contain `+`, `/`, and `=` which must be URL-encoded:

| Character | URL-Encoded |
|-----------|-------------|
| `+` | `%2B` |
| `/` | `%2F` |
| `=` | `%3D` |

In PowerShell:
```powershell
$encodedSignature = [System.Web.HttpUtility]::UrlEncode($rawSignature)
```

---

## URL Patterns Reference

### Admin Endpoints (Management)

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/federation/admin/initialize` | POST | Initialize server identity |
| `/api/v1/federation/admin/handshake` | POST | Initiate handshake with another server |
| `/api/v1/federation/admin/servers` | GET | List federated servers |
| `/api/v1/federation/admin/servers/{id}/trust` | POST | Update trust level |

### Server-to-Server Endpoints (Federation)

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/federation/v1/handshake` | POST | Receive handshake requests |
| `/federation/v1/users/nearby` | GET | Search users by location |
| `/federation/v1/postings/search` | GET | Search postings |

> **Note:** Server-to-server endpoints do NOT include `/api/v1/` in the path!

---

## Troubleshooting

### Issue: "Invalid signature"

**Causes:**
1. Timestamp expired (older than 5 minutes)
2. Wrong coordinates in query vs. signature generation
3. Missing or incorrect `timestamp` parameter
4. Signature not URL-encoded
5. Wrong `serverId` (should be requester's ID, not target's)

**Fix:** Regenerate the signed URL with exact parameters you intend to query.

### Issue: "Connection refused" during handshake

**Cause:** Using `localhost` URLs instead of Docker internal hostnames.

**Fix:** Use `http://barter-b:8081` (Docker internal), not `http://localhost:8083`.

### Issue: "Duplicate key" errors

**Cause:** Trying to run both docker-compose files simultaneously.

**Fix:** Only run `docker-compose.federation.yml` - it has both servers on the same network.

### Issue: "404 Not Found" on federation endpoints

**Cause:** Mixing up URL patterns.

**Examples of WRONG URLs:**
- `http://localhost:8081/api/v1/federation/users/nearby` ❌
- `http://localhost:8083/api/v1/federation/v1/...` ❌

**Correct URLs:**
- Admin proxy: `http://localhost:8081/api/v1/federation/admin/proxy/...`
- Server-to-server: `http://localhost:8083/federation/v1/...` (no `/api/v1/`)

---

## Cleanup

To stop the federation test environment:

```powershell
docker-compose -f docker-compose.federation.yml down
```

To also remove data volumes (start fresh):

```powershell
docker-compose -f docker-compose.federation.yml down -v
```

---

## Production Deployment (VPS)

For production with two VPS instances:

1. **Use HTTPS**: `https://barter1.yourdomain.com`
2. **Public URLs**: Server URLs must be publicly accessible
3. **No Docker hostnames**: Use actual domain names, not `barter-a` or `barter-b`
4. **Database**: Keep PostgreSQL internal (port 5432 not exposed)
5. **SSL Certificates**: Use Let's Encrypt or similar
6. **Trust levels**: Same manual upgrade process

See `DOCS/DEPLOYMENT_GUIDE_VPS_DOCKER.md` for full VPS setup instructions.
