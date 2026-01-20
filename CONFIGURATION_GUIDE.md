# Configuration Guide

This guide explains the configurable options for the Barter App Backend.

## Environment Variables

### AI/Ollama Configuration

#### `OLLAMA_HOST`

The base URL for the Ollama service used for AI embeddings.

- **Default (Docker with bridge network)**: `http://ollama:11434`
- **For host networking (AlmaLinux/DNS issues)**: `http://localhost:11434`
- **Example**:
  ```bash
  export OLLAMA_HOST=http://localhost:11434
  ```

#### `OLLAMA_EMBED_MODEL`

The embedding model to use for generating semantic embeddings.

- **Default**: `mxbai-embed-large`
- **Other options**: `nomic-embed-text`, `all-minilm`, etc.
- **Example**:
  ```bash
  export OLLAMA_EMBED_MODEL=nomic-embed-text
  ```

### Database Configuration

#### `POSTGRES_USER`

PostgreSQL username.

- **Default**: `postgres`

#### `POSTGRES_PASSWORD`

PostgreSQL password.

- **Default**: `Test1234` (change in production!)

#### `POSTGRES_DB`

PostgreSQL database name.

- **Default**: `mainDatabase`

### LOGBACK ###

#### LOG_LEVEL

DEBUG, INFO, WARN, ERROR

### FCM ###

- FIREBASE_CREDENTIALS_PATH=/app
- FIREBASE_CREDENTIALS_FILE=firebase-credentials.json
- PUSH_PROVIDER=firebase

## Configuration Files

### Development

Configuration is loaded from `resources/application.conf` with defaults suitable for Docker
development.

### Production

Configuration is loaded from `resources/application.prod.conf` which uses environment variables:

```hocon
ai {
  ollama {
    host = ${?OLLAMA_HOST}
    host = "http://localhost:11434"  # Fallback default
    embedModel = ${?OLLAMA_EMBED_MODEL}
    embedModel = "mxbai-embed-large"  # Fallback default
  }
}
```

## Docker Compose Setup

### Development (docker-compose.yml)

```yaml
environment:
  - OLLAMA_HOST=http://ollama:11434
  - OLLAMA_EMBED_MODEL=mxbai-embed-large
```

### Production (docker-compose.prod.yml or deploy/docker-compose.yml)

For AlmaLinux servers with DNS blocking:

```yaml
environment:
  OLLAMA_HOST: http://localhost:11434
  OLLAMA_EMBED_MODEL: mxbai-embed-large
```

## Using .env File

Create a `.env` file in your project root (copy from `.env.example`):

```bash
# AI Configuration
OLLAMA_HOST=http://localhost:11434
OLLAMA_EMBED_MODEL=mxbai-embed-large

# Database
POSTGRES_USER=postgres
POSTGRES_PASSWORD=YourSecurePasswordHere
POSTGRES_DB=mainDatabase
```

Then run:

```bash
docker-compose --env-file .env up
```

## Network Modes

### Bridge Network (Default for Development)

Services communicate via service names:

- `OLLAMA_HOST=http://ollama:11434`

### Host Network (AlmaLinux/Production)

Services communicate via localhost:

- `OLLAMA_HOST=http://localhost:11434`
- Add `network_mode: host` to docker-compose.yml

## Testing Configuration

To verify your Ollama configuration is working:

```bash
# Check if Ollama is accessible
curl http://localhost:11434/api/tags

# Test embedding generation
curl http://localhost:11434/api/embeddings -d '{
  "model": "mxbai-embed-large",
  "prompt": "test"
}'
```

## Troubleshooting

### DNS Issues on AlmaLinux

If you see DNS resolution errors:

1. Use `network_mode: host` in docker-compose
2. Set `OLLAMA_HOST=http://localhost:11434`
3. Ensure all services use localhost instead of service names

### Embedding Model Not Found

If you see "model not found" errors:

1. Pull the model manually:
   ```bash
   docker exec -it ollama_server ollama pull mxbai-embed-large
   ```
2. Or change `OLLAMA_EMBED_MODEL` to an already downloaded model

### Connection Refused

If services can't connect to Ollama:

1. Verify Ollama is running: `docker ps | grep ollama`
2. Check logs: `docker logs ollama_server`
3. Verify the host is correct for your network mode

## Example .env

# Database Configuration
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your_secure_password_here
POSTGRES_DB=mainDatabase
POSTGRES_HOST=postgres
POSTGRES_PORT=5432

# Environment
ENVIRONMENT=development

# Inactive User Management
# Enable auto-deletion of users inactive for specified threshold
INACTIVE_USER_AUTO_DELETE=false

# Days of inactivity before auto-deletion (default: 180)
INACTIVE_USER_AUTO_DELETE_THRESHOLD=180

# Days before sending reactivation email (default: 60)
INACTIVE_USER_REACTIVATION_EMAIL_DAYS=60

# Days before sending deletion warning (default: 120)
INACTIVE_USER_WARNING_EMAIL_DAYS=120

# Firebase Configuration
GOOGLE_APPLICATION_CREDENTIALS=/path/to/firebase-credentials.json

# Email Provider Configuration
EMAIL_PROVIDER=aws_ses  # Options: sendgrid, aws_ses, smtp
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=your_aws_access_key
AWS_SECRET_ACCESS_KEY=your_aws_secret_key
AWS_SES_FROM_EMAIL=noreply@yourdomain.com

# Push Notification Provider
PUSH_PROVIDER=firebase  # Options: firebase, onesignal, aws_sns

# AI Configuration
OLLAMA_HOST=http://localhost:11434
OLLAMA_EMBED_MODEL=mxbai-embed-large

# Server Configuration
SERVER_PORT=8081
SERVER_HOST=0.0.0.0
