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
3. Admin user created on both servers (with ID matching `ADMIN_USER_IDS` env var)

---

## Quick Start

### 1. Start the Federation Test Environment

```powershell
# Stop any existing containers first
docker-compose down
docker-compose -f docker-compose.federation.yml down

Optional:
docker network prune -f
docker volume rm barter_app_backend_postgres_dat
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
curl.exe http://localhost:8082/public-api/v1/healthCheck
```

Both should return a health status response.

### 3. Create Admin Users (if not exists)

You'll need admin users on **both** servers for the handshake process. The `ADMIN_USER_IDS` environment variable is set to `MainAdmin` by default.

---

## Federation Handshake Test

Each server needs its own identity before it can federate. The `serverUrl` must use Docker internal hostnames so servers can reach each other.

### Initialize Server A (Port 8081)

```powershell
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

curl.exe -X POST http://localhost:8081/api/v1/federation/admin/initialize `
  -H "Content-Type: application/json" `
  -H "X-User-ID: MainAdmin" `
  -H "X-Timestamp: $timestamp" `
  -H "X-Signature: test-signature" `
  -d '{\"serverUrl\": \"http://localhost:8081\", \"serverName\": \"Barter Server Alpha\", \"adminContact\": \"admin-a@localhost\", \"description\": \"Primary test server\", \"locationHint\": \"Local-Test-A\"}'
```

### Initialize Server B (Port 8082)

```powershell
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

curl.exe -X POST http://localhost:8083/api/v1/federation/admin/initialize `
  -H "Content-Type: application/json" `
  -H "X-User-ID: MainAdmin" `
  -H "X-Timestamp: $timestamp" `
  -H "X-Signature: test-signature" `
  -d '{\"serverUrl\": \"http://localhost:8083\", \"serverName\": \"Barter Server Beta\", \"adminContact\": \"admin-b@localhost\", \"description\": \"Secondary test server\", \"locationHint\": \"Local-Test-B\"}'
```

Both should return `success: true` with a generated `serverId`. **Save these IDs** - you'll need them later.

### Step 2: Server A Initiates Handshake to Server B

## Bidirectional Handshake

Federation requires **both servers** to handshake with each other. This is not automatic!

### Step 1: Server A Initiates Handshake to Server B

> **Critical:** Use the Docker internal URL `http://barter-b:8081`

```powershell
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

curl.exe -X POST http://localhost:8083/api/v1/federation/admin/handshake `
  -H "Content-Type: application/json" `
  -H "X-User-ID: MainAdmin" `
  -H "X-Timestamp: $timestamp" `
  -H "X-Signature: test-signature" `
  -d '{\"targetServerUrl\": \"http://barter_app_server:8081\", \"proposedScopes\": {\"users\": true, \"postings\": true, \"chat\": false, \"geolocation\": false, \"attributes\": true}}'
```

**Expected response:** `accepted: true` with Server B's details.

### Step 2: Server B Initiates Handshake Back to Server A

> **Critical:** Use the Docker internal URL `http://barter-a:8081`

```powershell
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

curl.exe -X POST http://localhost:8081/api/v1/federation/admin/handshake `
  -H "Content-Type: application/json" `
  -H "X-User-ID: MainAdmin" `
  -H "X-Timestamp: $timestamp" `
  -H "X-Signature: test-signature" `
  -d '{\"targetServerUrl\": \"http://barter_app_server2:8083\", \"proposedScopes\": {\"users\": true, \"postings\": true, \"chat\": false, \"geolocation\": false, \"attributes\": true}}'
```

### Step 3: Verify Trust Status

Check both servers to see the pending trust relationships:

```powershell
# Check Server A's federated servers
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
curl.exe http://localhost:8081/api/v1/federation/admin/servers `
  -H "X-User-ID: MainAdmin" `
  -H "X-Timestamp: $timestamp" `
  -H "X-Signature: test-signature"

# Check Server B's federated servers
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
curl.exe http://localhost:8083/api/v1/federation/admin/servers `
  -H "X-User-ID: MainAdmin" `
  -H "X-Timestamp: $timestamp" `
  -H "X-Signature: test-signature"
```

Both should show `trustLevel: PENDING`.

### Step 5: Upgrade Trust Level to FULL

## Upgrading Trust Level

After both handshakes complete, manually upgrade trust from `PENDING` to `FULL` on both servers.

### On Server A - Trust Server B

```powershell
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$serverBId = "<SERVER_B_ID_FROM_PREVIOUS_RESPONSE>"

curl.exe -X POST "http://localhost:8081/api/v1/federation/admin/servers/$serverBId/trust" `
  -H "Content-Type: application/json" `
  -H "X-User-ID: MainAdmin" `
  -H "X-Timestamp: $timestamp" `
  -H "X-Signature: test-signature" `
  -d '{\"trustLevel\": \"FULL\"}'
```

**On Server B - Trust Server A:**
```powershell
$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$serverAId = "<SERVER_A_ID_FROM_PREVIOUS_RESPONSE>"

curl.exe -X POST "http://localhost:8083/api/v1/federation/admin/servers/$serverAId/trust" `
  -H "Content-Type: application/json" `
  -H "X-User-ID: MainAdmin" `
  -H "X-Timestamp: $timestamp" `
  -H "X-Signature: test-signature" `
  -d '{\"trustLevel\": \"FULL\"}'
```

---

## Testing Cross-Server Queries

Once federation is established, you can query data from one server via the other.

### Important Concepts

| Concept | Description |
|---------|-------------|
| **Admin Proxy** | `http://localhost:8081/api/v1/federation/admin/proxy/*` - Generates signed URLs for testing |
| **Direct Query** | `http://localhost:8082/federation/v1/*` - Server-to-server endpoints (no `/api/v1/`) |
| **Signatures** | Required for all server-to-server queries, bound to exact parameters |

### Generate Signed URL (Admin Proxy)

Use the admin proxy endpoint to generate a properly signed URL:

```powershell
# From Server A, get signed URL to query Server B's users
curl.exe "http://localhost:8081/api/v1/federation/admin/proxy/users/nearby?targetServerId=<SERVER_B_ID>&lat=56.95&lon=24.10&radius=50000"
```

**Response:**
```json
{
  "success": true,
  "targetUrl": "http://barter-b:8081/federation/v1/users/nearby?serverId=...&signature=...",
  "signatureData": "<serverId>|<lat>|<lon>|<radius>|<timestamp>",
  "signature": "<base64-signature>",
  "localServerId": "...",
  "targetServerId": "..."
}
```

### Execute the Query

Use the generated signature and timestamp, but **change the hostname** to `localhost:8082`:

### Query Users from Server B via Server A
```powershell
# IMPORTANT: Change barter-b:8081 to localhost:8082!
# The signature is bound to exact coordinates, radius, and timestamp

$timestamp = "<TIMESTAMP_FROM_RESPONSE>"
$signature = "<URL-ENCODED_SIGNATURE_FROM_RESPONSE>"

# Query Server B's users via Server A's signed request
curl.exe "http://localhost:8082/federation/v1/users/nearby?serverId=<SERVER_A_ID>&lat=56.95&lon=24.10&radius=50000&timestamp=$timestamp&signature=$signature"
```

### Query Postings

```powershell
# Generate signed URL
curl.exe "http://localhost:8081/api/v1/federation/admin/proxy/postings/search?targetServerId=<SERVER_B_ID>&q=test&limit=20"

# Execute query with proper URL-encoding
$timestamp = "<TIMESTAMP>"
$signature = "<URL-ENCODED_SIGNATURE>"

curl.exe "http://localhost:8082/federation/v1/postings/search?serverId=<SERVER_A_ID>&q=test&limit=20&timestamp=$timestamp&signature=$signature"
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
| `/api/v1/federation/admin/proxy/users/nearby` | GET | Generate signed URL for user search |
| `/api/v1/federation/admin/proxy/postings/search` | GET | Generate signed URL for posting search |

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

**Fix:** Use `http://barter-b:8081` (Docker internal), not `http://localhost:8082`.

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
- Server-to-server: `http://localhost:8082/federation/v1/...` (no `/api/v1/`)

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
