# Barter App Docker Deployment Guide

## Auto-Start Setup with Systemd

### Option 1: Systemd Service (Recommended)

This approach gives you full control and ensures proper startup order with health checks.

#### Step 1: Update Paths

Edit the following files and replace `/path/to/barter_app_backend` with your actual path:

- `deploy/start-barter-app.sh` (line 8: `COMPOSE_FILE`)
- `deploy/barter-app.service` (lines 11 and 18)

#### Step 2: Install the Service

```bash
# Copy service file to systemd
sudo cp deploy/barter-app.service /etc/systemd/system/

# Make startup script executable
chmod +x /path/to/barter_app_backend/deploy/start-barter-app.sh

# Reload systemd
sudo systemctl daemon-reload

# Enable service to start on boot
sudo systemctl enable barter-app.service

# Start the service now
sudo systemctl start barter-app.service

# Check status
sudo systemctl status barter-app.service
```

#### Step 3: View Logs

```bash
# Service logs
sudo journalctl -u barter-app.service -f

# Container logs
docker compose -f /path/to/barter_app_backend/deploy/docker-compose.yml logs -f
```

---

### Option 2: Docker Restart Policy Only (Simpler)

If you prefer a simpler setup without systemd, Docker's `restart: always` will handle container crashes, but you need to ensure Docker starts on boot:

```bash
# Enable Docker to start on boot (usually enabled by default)
sudo systemctl enable docker

# Then just start containers normally - they'll auto-restart after reboot
cd /path/to/barter_app_backend/deploy
sudo systemctl stop nginx
docker compose up -d ollama
sleep 10
docker exec ollama_server ollama pull mxbai-embed-large
sudo systemctl start nginx
docker compose up -d
```

**Note:** With this approach, you'll need to manually pull the model after the first boot or system restart, since the model download doesn't persist across restarts unless you script it.

---

### Option 3: Crontab @reboot (Quick & Dirty)

Add to crontab:

```bash
sudo crontab -e
```

Add this line:
```
@reboot /path/to/barter_app_backend/deploy/start-barter-app.sh >> /var/log/barter-app-startup.log 2>&1
```

---

## Manual Commands Reference

### Start Everything
```bash
sudo ~/start-barter-app.sh
```

### Stop Everything
```bash
cd /path/to/barter_app_backend/deploy
docker compose down
sudo systemctl stop nginx
```

### Restart Just Ollama
```bash
docker restart ollama_server
sleep 5
docker exec ollama_server ollama pull mxbai-embed-large
```

### Check Status
```bash
docker ps
sudo systemctl status nginx
sudo systemctl status barter-app.service
```

---

## Troubleshooting

### Ollama Connection Refused

If you see errors like:
```
ERROR: httpx.ConnectError: [Errno 111] Connection refused
```

1. Check Ollama container:
   ```bash
   docker logs ollama_server
   curl http://localhost:11434/api/tags
   ```

2. Restart Ollama:
   ```bash
   docker restart ollama_server
   sleep 5
   docker exec ollama_server ollama pull mxbai-embed-large
   ```

### PostgreSQL Not Ready

The startup script waits for PostgreSQL healthcheck, but if needed:
```bash
docker logs postgresql_server
```

### Nginx Conflicts

If nginx fails to start, check for port conflicts:
```bash
sudo netstat -tlnp | grep :80
sudo netstat -tlnp | grep :443
```

---

## Environment Variables

Create a `.env` file in `/path/to/barter_app_backend/deploy/`:

```env
OLLAMA_HOST=http://localhost:11434
OLLAMA_EMBED_MODEL=mxbai-embed-large
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your_secure_password
POSTGRES_DB=mainDatabase
ENVIRONMENT=production
```

The systemd service and startup script will use these values.
