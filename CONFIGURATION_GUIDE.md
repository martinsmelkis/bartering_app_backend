# Configuration Guide

This document lists runtime configuration for the current backend codebase.

## Scope

This guide covers:
- Main backend service (`barter_app_server`)
- Optional dashboard services (`barter_dashboard_admin_compliance`, `barter_dashboard_user_moderation`)
- Local Docker and production-style deployment overrides

## Configuration Sources and Precedence

Configuration is resolved in this order:
1. Environment variables (`System.getenv(...)`)
2. `resources/application.conf` (dev defaults)
3. `resources/application.prod.conf` (prod defaults/fallbacks where present)

## Main Backend Environment Variables

### Required in most real deployments

#### `MAILJET_API_KEY`
Mailjet public API key for email sending.

#### `MAILJET_API_SECRET`
Mailjet private API key for email sending.

> `EMAIL_PROVIDER` currently supports `mailjet` in runtime module wiring.

### Core runtime / behavior

#### `ENVIRONMENT`
- **Used for**: CORS behavior
- **Value**: `development` enables permissive CORS; other values keep CORS disabled (same-domain production assumption)

#### `LOG_LEVEL`
- Common values: `DEBUG`, `INFO`, `WARN`, `ERROR`

### Database

#### `POSTGRES_USER`
Database username.

#### `POSTGRES_PASSWORD`
Database password.

#### `POSTGRES_DB`
**Important:** in current backend code this is treated as JDBC URL (not plain DB name) when provided to the app process.

- Example valid value:
  - `jdbc:postgresql://postgres:5432/mainDatabase`

If you do not set `POSTGRES_DB` for the app process, backend falls back to `database.MainDatabaseUrl` in `application.conf` / `application.prod.conf`.

### AI / Ollama

#### `OLLAMA_HOST`
Base URL for Ollama service.
- Docker bridge example: `http://ollama:11434`
- Host-network example: `http://localhost:11434`

#### `OLLAMA_EMBED_MODEL`
Embedding model name.
- Default in config: `mxbai-embed-large`

#### `OLLAMA_TRANSLATION_MODEL`
Translation model name used by translation service.
- Recommended explicit value: `llama3.2:3b`

### Image storage

#### `IMAGE_STORAGE_TYPE`
- `local` (default)
- `firebase`

#### `IMAGE_UPLOAD_DIR`
Filesystem path for local storage.
- Default: `uploads/images`

#### `IMAGE_BASE_URL`
Base URL for serving local images.
- Default: `/api/v1/images`

If `IMAGE_STORAGE_TYPE=firebase`, also configure:

#### `FIREBASE_SERVICE_ACCOUNT_KEY`
Path to Firebase service account JSON used by image storage.

#### `FIREBASE_STORAGE_BUCKET`
Firebase storage bucket name.

### Push notifications (FCM)

#### `PUSH_PROVIDER`
- Current supported value: `firebase`

#### `FIREBASE_CREDENTIALS_PATH`
Directory where FCM credentials JSON is mounted.

#### `FIREBASE_CREDENTIALS_FILE`
FCM credentials filename.

### Email service (Mailjet)

#### `EMAIL_PROVIDER`
- Current supported value: `mailjet`
- Default in DI module: `mailjet`

#### `MAILJET_FROM_EMAIL`
Default sender address.
- Default: `info@bartering.app`

#### `MAILJET_SANDBOX`
- `true` / `false`
- Default: `false`

#### Optional Mailjet compatibility variables
- `MJ_APIKEY_PUBLIC` (fallback for API key)
- `MJ_APIKEY_PRIVATE` (fallback for API secret)

### RevenueCat purchases / premium sync

#### `REVENUECAT_API_BASE_URL`
RevenueCat API base URL.
- Default: `https://api.revenuecat.com/v2`
- Usually keep default unless RevenueCat changes API base.

#### `REVENUECAT_PROJECT_ID`
RevenueCat project identifier used in v2 API path.
- **Where to find**: RevenueCat dashboard project URL (`/projects/<project_id>/...`) or project settings metadata.
- Example: in `https://app.revenuecat.com/projects/projf1888888/api-keys`, the project ID is `projf1888888`.

#### `REVENUECAT_API_KEY`
RevenueCat **Secret API key** used by backend to call RevenueCat v2 API.
- **Where to find**: Project → **API keys** → **Secret API keys**.
- Use the key that starts with `sk_...`.
- Never expose this key to clients.

#### `REVENUECAT_PREMIUM_ENTITLEMENT_ID`
Entitlement identifier that backend treats as "premium".
- **Where to find**: RevenueCat → Entitlements.
- Use the entitlement **identifier** (often looks like `entl...`), not display name.
- Important: must exactly match `items[].entitlement_id` returned by RevenueCat API.

#### `REVENUECAT_WEBHOOK_AUTH_TOKEN`
Shared secret token for webhook Authorization header validation.
- **Where to set**: backend env and RevenueCat webhook auth header value.
- Backend expects: `Authorization: Bearer <token>`.
- Generate as a random secret (32+ bytes) and rotate if exposed.

### Inactive user cleanup

#### `INACTIVE_USER_AUTO_DELETE`
Enable/disable auto-delete for inactive users.
- Default: `false`

#### `INACTIVE_USER_AUTO_DELETE_THRESHOLD`
Days before inactive user auto-delete.
- Default: `180`

### Compliance / retention

#### `RETENTION_CHAT_MESSAGES_DAYS`
Default: `7`

#### `RETENTION_CHAT_ANALYTICS_DAYS`
Default: `90`

#### `RETENTION_CHAT_READ_RECEIPTS_DAYS`
Default: `30`

#### `RETENTION_RISK_DEVICE_TRACKING_DAYS`
Default: `90`

#### `RETENTION_RISK_IP_TRACKING_DAYS`
Default: `90`

#### `RETENTION_RISK_LOCATION_CHANGES_DAYS`
Default: `90`

#### `RETENTION_RISK_PATTERNS_DAYS`
Default: `180`

#### `RETENTION_POSTING_HARD_DELETE_GRACE_DAYS`
Default: `30`

#### `RETENTION_BACKUP_DAYS`
Default: `30`

#### `RETENTION_BACKUP_POLICY_ENFORCEMENT`
Default: `true`

#### `RETENTION_ORCHESTRATOR_INTERVAL_HOURS`
Default: `24`

#### `GDPR_DSAR_SLA_DAYS`
Default: `30`

### Federation admin bootstrap hardening

#### `FEDERATION_INIT_TOKEN`
Required for federation initialization endpoint auth.

#### `FEDERATION_ADMIN_IP_INIT_ALLOWLIST`
Comma-separated allowlist for federation admin/bootstrap access.

### Analytics

#### `ANALYTICS_HASH_SALT`
Salt used for analytics hashing.
- Set explicitly in production.

## Dashboard Service Configuration

Both dashboard services use the same env variable set:

### Required for usable login/session security

#### `DASHBOARD_ADMIN_CREDENTIALS`
Semicolon-separated list of credentials in format:
- `username:sha256:<hex_hash>`

#### `DASHBOARD_SESSION_ENCRYPTION_KEY_B64`
Base64 key for cookie encryption.
- Expected decoded size: 16 bytes

#### `DASHBOARD_SESSION_SIGNING_KEY_B64`
Base64 key for cookie signing.
- Expected decoded size: 32 bytes

### Required for backend API calls

#### `DASHBOARD_BACKEND_BASE_URL`
Backend base URL, e.g. `http://barter_app_server:8081`.

#### `DASHBOARD_ADMIN_USER_ID`
Admin user ID used when signing internal API requests.

#### `DASHBOARD_ADMIN_PRIVATE_KEY_HEX`
Admin private key (hex) used for signed internal API requests.

### Optional

#### `DASHBOARD_SECURE_COOKIES`
- `true` in HTTPS production
- `false` for local HTTP dev

#### `DASHBOARD_SESSION_TTL_SECONDS`
- Default: `3600`

## Docker Notes (Current Repository)

### Local dev compose
`docker-compose.yml` defines defaults for:
- Postgres container
- Ollama container/model pull
- Backend + dashboard services

### Federation/dev variant
`docker-compose.federation.yml` mirrors local setup for secondary/federation-oriented local runs.

### Security reminder
Do not commit secrets into compose files or repository env files.
Use local untracked `.env`/secret management instead.

## Example `.env` (safe template)

```bash
# Runtime mode
ENVIRONMENT=development
LOG_LEVEL=DEBUG

# Database (JDBC URL when passed as POSTGRES_DB)
POSTGRES_DB=jdbc:postgresql://postgres:5432/mainDatabase
POSTGRES_USER=postgres
POSTGRES_PASSWORD=change_me

# AI
OLLAMA_HOST=http://ollama:11434
OLLAMA_EMBED_MODEL=mxbai-embed-large
OLLAMA_TRANSLATION_MODEL=llama3.2:3b

# Notifications
EMAIL_PROVIDER=mailjet
MAILJET_API_KEY=your_mailjet_public_key
MAILJET_API_SECRET=your_mailjet_private_key
MAILJET_FROM_EMAIL=info@bartering.app
PUSH_PROVIDER=firebase
FIREBASE_CREDENTIALS_PATH=/app
FIREBASE_CREDENTIALS_FILE=firebase-credentials.json

# RevenueCat (premium sync)
REVENUECAT_API_BASE_URL=https://api.revenuecat.com/v2
REVENUECAT_PROJECT_ID=projxxxxxxxx
REVENUECAT_API_KEY=sk_xxxxxxxxxxxxxxxxxxxxxxxxx
REVENUECAT_PREMIUM_ENTITLEMENT_ID=entlxxxxxxxxxxxx
REVENUECAT_WEBHOOK_AUTH_TOKEN=replace_with_long_random_secret

# Image storage (local)
IMAGE_STORAGE_TYPE=local
IMAGE_UPLOAD_DIR=/uploads/images
IMAGE_BASE_URL=/api/v1/images

# Inactive user cleanup
INACTIVE_USER_AUTO_DELETE=false
INACTIVE_USER_AUTO_DELETE_THRESHOLD=180

# Compliance retention
RETENTION_ORCHESTRATOR_INTERVAL_HOURS=24
RETENTION_BACKUP_POLICY_ENFORCEMENT=true
GDPR_DSAR_SLA_DAYS=30

# Federation bootstrap
FEDERATION_INIT_TOKEN=replace_with_long_random_secret
FEDERATION_ADMIN_IP_INIT_ALLOWLIST=127.0.0.1,::1,192.168.1.0/24

# Optional dashboards
# DASHBOARD_BACKEND_BASE_URL=http://barter_app_server:8081
# DASHBOARD_ADMIN_CREDENTIALS=admin:sha256:<hash>
# DASHBOARD_ADMIN_USER_ID=<admin-user-id>
# DASHBOARD_ADMIN_PRIVATE_KEY_HEX=<private-key-hex>
# DASHBOARD_SESSION_ENCRYPTION_KEY_B64=<base64-16-byte-key>
# DASHBOARD_SESSION_SIGNING_KEY_B64=<base64-32-byte-key>
# DASHBOARD_SECURE_COOKIES=false
# DASHBOARD_SESSION_TTL_SECONDS=3600
```

## Quick Validation Checklist

- Backend starts and connects to DB (no Flyway/Hikari errors)
- Ollama `/api/tags` reachable from backend network context
- Mailjet key/secret present when email features are enabled
- Firebase credentials file exists when push is enabled
- If dashboards are enabled, session keys and hashed admin credentials are configured
- Federation init token and allowlist are set before exposing admin/bootstrap endpoints
