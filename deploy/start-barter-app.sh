#!/bin/bash
# Startup script for Barter App with Ollama
# Place this in ~/start-barter-app.sh and run: chmod +x ~/start-barter-app.sh

set -e  # Exit on error

echo "=== Barter App Startup Script ==="
echo "Date: $(date)"

# Configuration
COMPOSE_FILE="/opt/barterappbackend/deploy/docker-compose.yml"
OLLAMA_HOST="${OLLAMA_HOST:-http://localhost:11434}"
OLLAMA_MODEL="${OLLAMA_EMBED_MODEL:-mxbai-embed-large}"

# Check if running as root (needed for nginx)
if [ "$EUID" -ne 0 ]; then
    echo "⚠️  Warning: This script should be run as root for nginx control"
fi

sudo docker compose down

echo "📍 Using compose file: $COMPOSE_FILE"
echo "🤖 Ollama host: $OLLAMA_HOST"
echo "📦 Model: $OLLAMA_MODEL"

# Step 1: Stop nginx temporarily
echo ""
echo "🔴 Stopping nginx..."
sudo systemctl stop nginx || echo "Nginx was not running"

# Step 2: Start/Restart Ollama service
echo ""
echo "🤖 Starting Ollama container..."
docker compose -f "$COMPOSE_FILE" up -d ollama

# Step 3: Wait for Ollama to be healthy
echo ""
echo "⏳ Waiting for Ollama to be ready..."
MAX_RETRIES=30
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    if curl -s "$OLLAMA_HOST/api/tags" > /dev/null 2>&1; then
        echo "✅ Ollama is up and responding!"
        break
    fi
    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "  Attempt $RETRY_COUNT/$MAX_RETRIES - Ollama not ready yet, waiting 2s..."
    sleep 2
done

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    echo "❌ ERROR: Ollama failed to start after $MAX_RETRIES attempts"
    echo "Checking Ollama logs:"
    docker logs ollama_server --tail 50
    exit 1
fi

# Step 4: Ensure the model is pulled
echo ""
echo "📥 Checking if model $OLLAMA_MODEL is available..."
if ! curl -s "$OLLAMA_HOST/api/tags" | grep -q "$OLLAMA_MODEL"; then
    echo "📥 Pulling model $OLLAMA_MODEL..."
    docker exec ollama_server ollama pull "$OLLAMA_MODEL"
    echo "✅ Model pulled successfully"
else
    echo "✅ Model $OLLAMA_MODEL already available"
fi

# Step 5: Start remaining services
echo ""
echo "🚀 Starting all services..."
docker compose -f "$COMPOSE_FILE" up -d

# Step 6: Wait for postgres to be healthy
echo ""
echo "⏳ Waiting for PostgreSQL to be healthy..."
MAX_RETRIES=30
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    if docker compose -f "$COMPOSE_FILE" ps postgres | grep -q "healthy"; then
        echo "✅ PostgreSQL is healthy!"
        break
    fi
    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "  Attempt $RETRY_COUNT/$MAX_RETRIES - PostgreSQL not healthy yet, waiting 2s..."
    sleep 2
done

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    echo "❌ ERROR: PostgreSQL failed to become healthy"
    docker logs postgresql_server --tail 50
    exit 1
fi

# Step 7: Wait for app server to be ready
echo ""
echo "⏳ Waiting for Barter App server to be ready..."
MAX_RETRIES=30
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    if curl -s http://localhost:8081/health > /dev/null 2>&1 || \
       curl -s http://localhost:8081/api/health > /dev/null 2>&1 || \
       docker compose -f "$COMPOSE_FILE" ps barter_app_server | grep -q "healthy\|Up"; then
        echo "✅ Barter App server is up!"
        break
    fi
    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "  Attempt $RETRY_COUNT/$MAX_RETRIES - App server not ready yet, waiting 2s..."
    sleep 2
done

# Step 8: Start nginx
echo ""
echo "🟢 Starting nginx..."
sudo systemctl start nginx

# Step 9: Verify nginx
echo ""
echo "🔍 Verifying nginx is running..."
if systemctl is-active --quiet nginx; then
    echo "✅ Nginx is running!"
else
    echo "❌ ERROR: Nginx failed to start"
    sudo systemctl status nginx
    exit 1
fi

sudo docker compose up --build

# Final status
echo ""
echo "=========================================="
echo "✅ All services started successfully!"
echo "=========================================="
echo ""
docker compose -f "$COMPOSE_FILE" ps

echo ""
echo "Useful commands:"
echo "  View logs:          docker compose -f $COMPOSE_FILE logs -f"
echo "  View Ollama logs:   docker logs -f ollama_server"
echo "  View app logs:      docker logs -f barter_app_server"
echo "  Restart:            ~/start-barter-app.sh"
echo "  Stop:               docker compose -f $COMPOSE_FILE down"
