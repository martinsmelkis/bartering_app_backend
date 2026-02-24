# Complete Deployment Guide - Docker-based Setup on AlmaLinux VPS

This guide shows how to deploy your Barter app using Docker Compose (Backend + PostgreSQL + Ollama) with Flutter web frontend on AlmaLinux 10.

**Time Estimate:** 30-40 minutes (even faster with Docker!)  
**Difficulty:** ⭐☆☆☆☆ (Even Easier!)

---

## Why Docker Makes It Easier

✅ **One command deployment** - `docker-compose up -d`  
✅ **Consistent environment** - Same setup on dev and production  
✅ **Easy rollback** - Just switch image versions  
✅ **Isolated services** - Backend, DB, Ollama don't conflict  
✅ **Automatic restarts** - Docker handles service failures  
✅ **No manual dependency installation** - Java, PostgreSQL, etc. in containers  

---

## Architecture Overview

```
yourdomain.com              → Flutter web (Nginx serves static files)
yourdomain.com/api/v1/*     → Docker: Kotlin backend (port 8081)
yourdomain.com/chat         → Docker: WebSocket endpoints
Docker Network:
  - barter_app_backend      → Kotlin/Ktor (port 8081)
  - postgresql_server       → PostgreSQL database
  - ollama                  → Ollama AI service
```

---

## Prerequisites

- ✅ VPS running AlmaLinux 10
- ✅ Domain name purchased
- ✅ Root or sudo access
- ✅ Your existing `docker-compose.yml` file
- ✅ Flutter app ready to build

---

## Part 1: DNS Configuration (5 minutes)

Same as before - point your domain to the VPS:

### Step 1: Point Domain to VPS

1. Log into your domain registrar (Namecheap, GoDaddy, etc.)
2. Go to DNS settings
3. Add an **A record**:

```
Type:  A
Host:  @
Value: YOUR_VPS_IP_ADDRESS
TTL:   3600
```

**Wait 5-30 minutes for DNS propagation**

---

## Part 2: VPS Initial Setup (10 minutes)

### Step 2: Connect to VPS

```bash
ssh root@YOUR_VPS_IP
```

### Step 3: Update System

```bash
sudo dnf update -y
sudo dnf install -y epel-release
```

### Step 4: Install Docker and Docker Compose

```bash
# Install Docker
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

# Start and enable Docker
sudo systemctl start docker
sudo systemctl enable docker

# Verify Docker is running
sudo docker --version
sudo docker compose version

# Optional: Allow your user to run Docker without sudo
sudo usermod -aG docker $USER
# Log out and back in for this to take effect
```

### Step 5: Install Nginx (for serving Flutter web)

```bash
# Install Nginx
sudo dnf install -y nginx

# Install Certbot for SSL
sudo dnf install -y certbot python3-certbot-nginx
```

### Step 6: Configure Firewall

```bash

sudo yum install firewalld -y
sudo systemctl enable --now firewalld.service

# Start firewall
sudo systemctl start firewalld

docker network ls
sudo firewall-cmd --permanent --zone=trusted --add-interface=br-0882f8054788
# Allow postgres locally
sudo firewall-cmd --zone=trusted --add-port=5432/tcp --permanent

sudo firewall-cmd --zone=public --add-port=8081/tcp --permanent
sudo firewall-cmd --reload

# Allow HTTP, HTTPS
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=https
sudo firewall-cmd --reload
```

### Step 7: Create Directories

```bash
# Create app directory
mkdir /opt/barterappbackend
mkdir /opt/barterappbackend/deploy
sudo mkdir -p /var/www/yourdomain.com

# Set permissions
sudo chown -R $USER:$USER /opt/barter-app
sudo chown -R nginx:nginx /var/www/yourdomain.com
```

---

## Part 3: Prepare Docker Setup (5 minutes)

### Step 8: Transfer Docker Compose Configuration

From your dev machine:

```bash
# Transfer docker-compose.yml
scp docker-compose.yml root@YOUR_VPS_IP:/opt/barterappbackend/deploy/

# Transfer any environment files
scp .env.production root@YOUR_VPS_IP:/opt/barterappbackend/deploy/.env

# Transfer application configs
scp resources/application.prod.conf root@YOUR_VPS_IP:/opt/barterappbackend/deploy/resources/
```

### Step 9: Update docker-compose.yml for Production

On VPS, edit `/opt/barterappbackend/deploy/docker-compose.yml`:

```yaml
services:
  postgres:
    build:
      context: ./postgres
    container_name: postgresql_server
    network_mode: host
    restart: always
    environment:
      POSTGRES_USER: ${POSTGRES_USER:-postgres}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-Test1234}
      POSTGRES_DB: ${POSTGRES_DB:-mainDatabase}
      OLLAMA_URL: ${OLLAMA_HOST:-http://localhost:11434}
    dns:
      - 8.8.8.8
      - 8.8.4.4
    healthcheck:
      test: [ "CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-postgres} -d ${POSTGRES_DB:-mainDatabase}" ]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    volumes:
      - postgres_data:/var/lib/postgresql

  ollama:
    image: docker.io/ollama/ollama:latest
    container_name: ollama_server
    network_mode: host
    pull_policy: always
    tty: true
    restart: always
    environment:
      - OLLAMA_KEEP_ALIVE=24h
      - OLLAMA_HOST=${OLLAMA_HOST:-http://localhost:11434}
      - OLLAMA_EMBED_MODEL=${OLLAMA_EMBED_MODEL:-mxbai-embed-large}
    dns:
      - 8.8.8.8
      - 8.8.4.4
    volumes:
      - ollama_data:/root/.ollama
    entrypoint: /bin/sh
    command: -c "ollama serve & sleep 5 && ollama pull ${OLLAMA_EMBED_MODEL:-mxbai-embed-large} && wait"

  barter_app_server:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: barter_app_server
    network_mode: host
    dns:
      - 8.8.8.8
      - 8.8.4.4
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      OLLAMA_HOST: ${OLLAMA_HOST:-http://localhost:11434}
      OLLAMA_EMBED_MODEL: ${OLLAMA_EMBED_MODEL:-mxbai-embed-large}
      POSTGRES_USER: ${POSTGRES_USER:-postgres}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-Test1234}
      POSTGRES_DB: ${POSTGRES_DB:-mainDatabase}
      ENVIRONMENT: ${ENVIRONMENT:-production}
      IMAGE_UPLOAD_DIR: ${IMAGE_UPLOAD_DIR:-/uploads/images}
      IMAGE_BASE_URL: ${IMAGE_BASE_URL:-/api/v1/images}
      IMAGE_STORAGE_TYPE: local
      LOG_LEVEL: ${LOG_LEVEL:-ERROR}
      # Firebase Push Notifications
      FIREBASE_CREDENTIALS_PATH: /app
      FIREBASE_CREDENTIALS_FILE: barter-app-backend-dev-firebase-adminsdk-fbsvc-393197c88a.json
      MAILJET_API_KEY: ${MAILJET_API_KEY:-}
      MAILJET_API_SECRET: ${MAILJET_API_SECRET:-}
      PUSH_PROVIDER: firebase
    volumes:
      - ./uploads:/uploads
    restart: always

volumes:
  postgres_data:
    driver: local
  ollama_data:
    driver: local
```

### Step 10: Create .env File for Production

On VPS, create `/opt/barterappbackend/deploy/.env`:

```bash
# Database
DB_NAME=mainDatabase
DB_USER=barter_user
DB_PASSWORD=your_secure_password_here_change_this

# Application
APP_ENV=production
DOMAIN=yourdomain.com

# Image Storage
IMAGE_STORAGE_TYPE=local
UPLOAD_DIR=/app/uploads

# Email (if using)
# EMAIL_PROVIDER=sendgrid
# SENDGRID_API_KEY=your_key_here

# Push Notifications (if using)
# PUSH_PROVIDER=firebase
```

**Important:** Change `DB_PASSWORD` to a secure password!

---

## Part 4: Build and Deploy Docker Containers (10 minutes)

### Step 11: Transfer Application Code (Option A: Direct Transfer)

```bash
# From your dev machine
cd /path/to/barter_app_backend

# Build the JAR locally first (faster than building on VPS)
./gradlew clean build -x test

# Transfer everything
rsync -avz --exclude 'build' --exclude '.git' \
  ./ root@YOUR_VPS_IP:/opt/barterappbackend/deploy/

# Or using scp
tar -czf barter-app.tar.gz --exclude='build' --exclude='.git' .
scp barter-app.tar.gz root@YOUR_VPS_IP:/tmp/
ssh root@YOUR_VPS_IP "cd /opt/barter-app && tar -xzf /tmp/barter-app.tar.gz"
```

### Step 11: Transfer Application Code (Option B: Git)

```bash
# On VPS
cd /opt/barter-app

# Install git
sudo dnf install -y git

# Clone your repository
git clone https://github.com/yourusername/barter_app_backend.git .

# Checkout production branch
git checkout main  # or production
```

### Step 12: Build and Start Docker Containers

```bash
# On VPS
cd /opt/barter-app

# Build the Docker images
sudo docker compose build

# Start all services
sudo docker compose up -d

# Check if containers are running
sudo docker compose ps

# View logs
sudo docker compose logs -f

# Wait for services to be healthy
sudo docker compose ps
# Should show all services as "Up" and "healthy"
```

### Step 13: Pull Ollama Model (if needed)

```bash
# Download the model you're using (e.g., llama2)
sudo docker compose exec ollama ollama pull llama2

# Or whatever model you're using
sudo docker compose exec ollama ollama pull mistral
```

### Step 14: Verify Services

```bash
# Test database connection
sudo docker compose exec postgresql_server psql -U barter_user -d mainDatabase -c "SELECT 1;"

# Test backend
curl http://localhost:8081/public-api/v1/healthCheck

# Test Ollama
curl http://localhost:11434/api/version

# View logs
sudo docker compose logs barter_app_server
```

---

## Part 5: Deploy Flutter Web (5 minutes)

### Step 15: Build and Deploy Flutter

```bash
# On your dev machine
cd /path/to/barter_app_flutter

# Update API endpoint for production
# Edit your config file to point to: https://yourdomain.com/api/v1

# Build Flutter web
flutter clean
flutter build web --release

# Deploy to VPS
scp -r build/web/* root@YOUR_VPS_IP:/var/www/yourdomain.com/

# Set permissions
ssh root@YOUR_VPS_IP "sudo chown -R nginx:nginx /var/www/yourdomain.com"
```

---

## Part 6: Configure Nginx (5 minutes)

### Step 16: Create Nginx Configuration

```bash
# On VPS
sudo nano /etc/nginx/conf.d/barter-app.conf
```

Paste this configuration:

```nginx
# HTTP - Redirect to HTTPS
server {
    listen 80;
    listen [::]:80;
    server_name yourdomain.com www.yourdomain.com;
    
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }
    
    location / {
        return 301 https://$server_name$request_uri;
    }
}

# HTTPS - Main configuration
server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name yourdomain.com www.yourdomain.com;

    # SSL certificates
    ssl_certificate /etc/letsencrypt/live/yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/yourdomain.com/privkey.pem;
    
    # SSL configuration
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;

    # Security headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Strict-Transport-Security "max-age=31536000" always;

    # Flutter web app
    root /var/www/yourdomain.com;
    index index.html;

    # Gzip compression
    gzip on;
    gzip_vary on;
    gzip_comp_level 6;
    gzip_types text/plain text/css text/javascript application/json application/javascript;

    # Serve Flutter app
    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "public, max-age=31536000, immutable";
        
        location = /index.html {
            add_header Cache-Control "no-cache, no-store, must-revalidate";
        }
    }

    # Proxy to Docker backend
    location /api/ {
        proxy_pass http://127.0.0.1:8081/api/;
        proxy_http_version 1.1;
        
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # Timeouts
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    # WebSocket support
    location /chat {
        proxy_pass http://127.0.0.1:8081/chat;
        proxy_http_version 1.1;
        
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        
        # WebSocket timeouts
        proxy_connect_timeout 7d;
        proxy_send_timeout 7d;
        proxy_read_timeout 7d;
    }

    # Public API
    location /public-api/ {
        proxy_pass http://127.0.0.1:8081/public-api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # File uploads
    location /api/v1/postings {
        proxy_pass http://127.0.0.1:8081/api/v1/postings;
        client_max_body_size 20M;
        
        proxy_connect_timeout 300s;
        proxy_send_timeout 300s;
        proxy_read_timeout 300s;
    }

    # Serve uploaded images
    location /uploads/ {
        alias /opt/barterappbackend/deploy/uploads/;
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
```

### Step 17: Test and Start Nginx

```bash
# Test configuration
sudo nginx -t

# Start Nginx
sudo systemctl start nginx
sudo systemctl enable nginx
```

---

## Part 7: Setup SSL Certificate (5 minutes)

### Step 18: Get SSL Certificate

```bash
# Stop Nginx temporarily
sudo systemctl stop nginx

# Get certificate
sudo certbot certonly --standalone \
  -d barters.lv \
  -d www.barters.lv \
  --non-interactive \
  --agree-tos \
  -m your-email@example.com

# Start Nginx
sudo systemctl start nginx
```

---

## Part 8: Create Deployment Scripts

### Step 19: Create Update Script

Create `/opt/barterappbackend/deploy/update.sh`:

```bash
#!/bin/bash
# update.sh - Update Docker containers

set -e

echo "🔄 Updating Barter App..."

cd /opt/barter-app

# Pull latest code (if using git)
# git pull origin main

# Rebuild and restart containers
echo "🔨 Rebuilding containers..."
sudo docker compose build --no-cache

echo "🔄 Restarting services..."
sudo docker compose down
sudo docker compose up -d

# Wait for health checks
echo "⏳ Waiting for services to be healthy..."
sleep 10

# Check status
sudo docker compose ps

echo "✅ Update complete!"
echo "📊 View logs: sudo docker compose logs -f"
```

Make it executable:
```bash
chmod +x /opt/barterappbackend/deploy/update.sh
```

### Step 20: Create Backup Script

Create `/opt/barterappbackend/deploy/backup.sh`:

```bash
#!/bin/bash
# backup.sh - Backup database and uploads

BACKUP_DIR="/backup/barter"
DATE=$(date +%Y%m%d_%H%M%S)

mkdir -p "$BACKUP_DIR"

echo "📦 Backing up database..."
sudo docker compose exec -T postgresql_server pg_dump \
  -U barter_user mainDatabase > "$BACKUP_DIR/db-$DATE.sql"

echo "📦 Backing up uploads..."
tar -czf "$BACKUP_DIR/uploads-$DATE.tar.gz" /opt/barterappbackend/deploy/uploads/

echo "📦 Backing up configs..."
tar -czf "$BACKUP_DIR/configs-$DATE.tar.gz" \
  /opt/barterappbackend/deploy/docker-compose.yml \
  /opt/barterappbackend/deploy/.env \
  /etc/nginx/conf.d/barter-app.conf

# Keep only last 7 days
find "$BACKUP_DIR" -mtime +7 -delete

echo "✅ Backup complete: $DATE"
```

Make executable:
```bash
chmod +x /opt/barterappbackend/deploy/backup.sh
```

Schedule daily backups:
```bash
sudo crontab -e
# Add:
0 2 * * * /opt/barterappbackend/deploy/backup.sh
```

---

## Part 9: Docker Management Commands

### Useful Docker Commands

```bash
# View all containers
sudo docker compose ps

# View logs
sudo docker compose logs -f
sudo docker compose logs barter_app_server -f  # Specific service

# Restart all services
sudo docker compose restart

# Restart specific service
sudo docker compose restart barter_app_server

# Stop all services
sudo docker compose down

# Stop and remove volumes (careful!)
sudo docker compose down -v

# Start services
sudo docker compose up -d

# Rebuild after code changes
sudo docker compose build
sudo docker compose up -d

# Access container shell
sudo docker compose exec barter_app_server bash
sudo docker compose exec postgresql_server bash

# View resource usage
sudo docker stats

# Remove old images (cleanup)
sudo docker system prune -a
```

### Database Operations

```bash
# Access PostgreSQL
sudo docker compose exec postgresql_server psql -U barter_user -d mainDatabase

# Run migrations manually
sudo docker compose exec barter_app_server ./gradlew flywayMigrate

# Backup database
sudo docker compose exec postgresql_server pg_dump -U barter_user mainDatabase > backup.sql

# Restore database
cat backup.sql | sudo docker compose exec -T postgresql_server psql -U barter_user -d mainDatabase
```

---

## Part 10: Monitoring and Maintenance

### View Logs

```bash
# All services
sudo docker compose logs -f

# Just backend
sudo docker compose logs -f barter_app_server

# Just database
sudo docker compose logs -f postgresql_server

# Last 100 lines
sudo docker compose logs --tail=100 barter_app_server
```

### Monitor Resources

```bash
# Real-time stats
sudo docker stats

# Check container health
sudo docker compose ps

# Inspect container
sudo docker inspect barter_app_server
```

### Health Checks

```bash
# Check backend health
curl http://localhost:8081/public-api/v1/healthCheck

# Check from internet
curl https://yourdomain.com/public-api/v1/healthCheck

# Check Ollama
sudo docker compose exec ollama ollama list
```

---

## Part 11: Automated Deployment Script

### Complete Deploy Script

Create `deploy-docker.sh` on your dev machine:

```bash
#!/bin/bash
# deploy-docker.sh - Complete deployment to VPS

set -e

# Configuration
DOMAIN="yourdomain.com"
VPS_USER="root"
VPS_IP="YOUR_VPS_IP"
FLUTTER_PATH="./barter_app_flutter"
BACKEND_PATH="./barter_app_backend"

echo "🚀 Deploying to $DOMAIN..."

# Build Flutter
echo "📱 Building Flutter web..."
cd "$FLUTTER_PATH"
flutter clean
flutter build web --release
cd ..

# Deploy Flutter
echo "📤 Deploying Flutter..."
scp -r "$FLUTTER_PATH/build/web/"* "$VPS_USER@$VPS_IP:/var/www/$DOMAIN/"

# Build and deploy backend with Docker
echo "☕ Deploying Kotlin backend..."
cd "$BACKEND_PATH"

# Build locally and transfer JAR (faster)
./gradlew clean build -x test

# Create tar of necessary files
tar -czf deploy.tar.gz \
  --exclude='.git' \
  --exclude='build/tmp' \
  --exclude='build/kotlin' \
  src/ resources/ build/libs/ \
  Dockerfile docker-compose.yml build.gradle settings.gradle

# Transfer and deploy
scp deploy.tar.gz "$VPS_USER@$VPS_IP:/tmp/"

ssh "$VPS_USER@$VPS_IP" << 'ENDSSH'
cd /opt/barter-app
tar -xzf /tmp/deploy.tar.gz
rm /tmp/deploy.tar.gz

# Rebuild and restart
docker compose build --no-cache barter_app_server
docker compose up -d

# Wait for health check
sleep 10
docker compose ps

echo "✅ Deployment complete!"
ENDSSH

echo "🌐 Visit: https://$DOMAIN"
```

Usage:
```bash
chmod +x deploy-docker.sh
./deploy-docker.sh
```

---

## Troubleshooting

### Container won't start

```bash
# Check logs
sudo docker compose logs barter_app_server

# Check if ports are available
sudo ss -tulpn | grep 8081

# Check container status
sudo docker compose ps

# Try rebuilding
sudo docker compose build --no-cache
sudo docker compose up -d
```

### Database connection issues

```bash
# Check if PostgreSQL is healthy
sudo docker compose ps

# Check connection from backend
sudo docker compose exec barter_app_server ping postgresql_server

# Check database logs
sudo docker compose logs postgresql_server

# Access database directly
sudo docker compose exec postgresql_server psql -U barter_user -d mainDatabase
```

### Out of disk space

```bash
# Check disk usage
df -h

# Check Docker disk usage
sudo docker system df

# Clean up
sudo docker system prune -a
sudo docker volume prune

# Remove old logs
sudo journalctl --vacuum-time=7d
```

### Memory issues

```bash
# Check memory usage
free -h
sudo docker stats

# Limit container memory in docker-compose.yml:
services:
  barter_app_server:
    deploy:
      resources:
        limits:
          memory: 1G
```

### SELinux blocking Docker

```bash
# If you get permission errors
sudo setenforce 0  # Temporary

# Permanent fix - allow Docker
sudo setsebool -P container_manage_cgroup true
```

---

## Performance Optimization

### Docker Optimizations

1. **Use multi-stage builds** (already in your Dockerfile)
2. **Limit container resources**:

```yaml
services:
  barter_app_server:
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
        reservations:
          memory: 512M
```

3. **Enable log rotation**:

```yaml
services:
  barter_app_server:
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
```

### Database Optimizations

Add to `docker-compose.yml`:

```yaml
postgresql_server:
  command: >
    postgres
    -c shared_buffers=256MB
    -c effective_cache_size=1GB
    -c maintenance_work_mem=64MB
    -c checkpoint_completion_target=0.9
```

---

## Advantages of Docker Setup

### Compared to traditional deployment:

✅ **Easier setup** - One command vs. many manual steps  
✅ **Consistent** - Same environment everywhere  
✅ **Isolated** - Services don't interfere with each other  
✅ **Easy updates** - Just rebuild and restart  
✅ **Easy rollback** - Keep old images  
✅ **Portable** - Move to different VPS easily  
✅ **Scalable** - Easy to add more containers  

### Docker Compose Benefits:

✅ **Single config file** - Everything in `docker-compose.yml`  
✅ **Automatic networking** - Containers can talk to each other  
✅ **Volume management** - Persistent data  
✅ **Health checks** - Automatic restart on failure  
✅ **Dependencies** - Start services in right order  

---

## Quick Reference

### Daily Commands

```bash
# View logs
sudo docker compose logs -f

# Restart backend
sudo docker compose restart barter_app_server

# Update all services
cd /opt/barter-app && ./update.sh

# Backup
/opt/barterappbackend/deploy/backup.sh

# Monitor resources
sudo docker stats

# Health check
curl https://yourdomain.com/api/v1/health
```

### File Locations

- Docker compose: `/opt/barterappbackend/deploy/docker-compose.yml`
- Environment: `/opt/barterappbackend/deploy/.env`
- Database data: `/opt/barterappbackend/deploy/postgres-data/`
- Uploads: `/opt/barterappbackend/deploy/uploads/`
- Ollama data: `/opt/barterappbackend/deploy/ollama-data/`
- Flutter web: `/var/www/yourdomain.com/`
- Nginx config: `/etc/nginx/conf.d/barter-app.conf`

---

## Summary

With Docker, your deployment is:

✅ **Simpler** - No manual Java/PostgreSQL installation  
✅ **Faster** - One command to start everything  
✅ **More reliable** - Automatic restarts and health checks  
✅ **Easier to maintain** - Update with `docker compose build`  
✅ **Better isolated** - Services in separate containers  

**Time saved:** ~20 minutes compared to traditional deployment!

**Your stack is now:**
- 🐳 Docker Compose managing all services
- 🌐 Nginx serving Flutter web + proxying to Docker
- 🔒 HTTPS with auto-renewal
- 💾 PostgreSQL in container with persistent volume
- 🤖 Ollama in container with GPU support
- ☕ Kotlin backend in container

**Next steps:**
1. Set up automated backups
2. Configure monitoring (Prometheus/Grafana)
3. Set up CI/CD with GitHub Actions
4. Add staging environment

---

**Happy deploying!** 🐳🚀
