# Production Deployment Guide - barters.lv (Docker + Nginx + SSL)

**Based on real deployment experience and debugging**

Time Estimate: 1-2 hours  
Difficulty: ⭐⭐⭐☆☆

---

## Architecture Overview

```
https://barters.lv/                     → Flutter web (static files)
https://barters.lv/api/v1/*            → Backend API (Docker :8081)
https://barters.lv/api/v1/images/*     → Image serving with ?size= support
wss://barters.lv/chat                  → WebSocket (Docker :8081)
https://barters.lv/uploads/*           → (REMOVED - use /api/v1/images)

Docker Stack:
  - barter_app_server  (Kotlin/Ktor on port 8081)
  - postgresql_server  (PostgreSQL database)
  - ollama_server      (AI embeddings on port 11434)
```

---

## Part 1: Prerequisites

### What You Need

- ✅ AlmaLinux/Rocky Linux VPS with root access
- ✅ Domain name (e.g., barters.lv)
- ✅ Docker and Docker Compose installed
- ✅ 2GB+ RAM (4GB recommended for Ollama)
- ✅ 20GB+ disk space

### DNS Setup

Point your domain to VPS:

```
Type:  A
Host:  @
Value: YOUR_VPS_IP
TTL:   3600

Type:  A
Host:  www
Value: YOUR_VPS_IP
TTL:   3600
```

Wait 5-30 minutes for DNS propagation.

---

## Part 2: Install Dependencies

### Install Docker

```bash
# Add Docker repository
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo

# Install Docker
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

# Start Docker
sudo systemctl start docker
sudo systemctl enable docker

# Verify
docker --version
docker compose version
```

### Install Nginx

```bash
sudo dnf install -y nginx

# Start nginx
sudo systemctl start nginx
sudo systemctl enable nginx
```

### Install Certbot (for SSL)

```bash
# Enable EPEL repository first
sudo dnf install -y epel-release
sudo dnf update -y

# Install certbot
sudo dnf install -y certbot

# Verify
certbot --version
```

---

## Part 3: Deploy Application

### Step 1: Upload Project Files

```bash
# Create project directory
sudo mkdir -p /opt/barterappbackend/deploy
cd /opt/barterappbackend

# Upload your project (from local machine)
scp -r deploy/ root@your-server:/opt/barterappbackend/
scp docker-compose.yml root@your-server:/opt/barterappbackend/deploy/
scp Dockerfile root@your-server:/opt/barterappbackend/deploy/
scp build/libs/BarterAppBackend-all.jar root@your-server:/opt/barterappbackend/deploy/
```

### Step 2: Create Environment Variables

**CRITICAL**: Use container paths, not host paths!

```bash
cd /opt/barterappbackend/deploy

cat > .env << 'EOF'
# ============================================================================
# ENVIRONMENT CONFIGURATION
# ============================================================================

ENVIRONMENT=production

# ============================================================================
# DATABASE
# ============================================================================
POSTGRES_USER=postgres
POSTGRES_PASSWORD=CHANGE_THIS_STRONG_PASSWORD
POSTGRES_DB=mainDatabase

# ============================================================================
# OLLAMA AI SERVICE
# ============================================================================
OLLAMA_HOST=http://localhost:11434
OLLAMA_EMBED_MODEL=mxbai-embed-large

# ============================================================================
# IMAGE STORAGE (CRITICAL - Use container paths!)
# ============================================================================
# Container path (not host path!)
IMAGE_UPLOAD_DIR=/uploads/images

# Use relative path for URL generation (browser adds domain)
IMAGE_BASE_URL=/api/v1/images

IMAGE_STORAGE_TYPE=local

# ============================================================================
# FIREBASE (Optional)
# ============================================================================
FIREBASE_CREDENTIALS_PATH=/app
FIREBASE_CREDENTIALS_FILE=firebase-credentials.json
PUSH_PROVIDER=firebase
EOF

chmod 600 .env
```

**⚠️ IMPORTANT**: 
- `IMAGE_UPLOAD_DIR` must be `/uploads/images` (container path)
- `IMAGE_BASE_URL` must be `/api/v1/images` (NOT full URL, NOT `/uploads`)
- Files are saved to container `/uploads/images` which maps to host `./uploads/images`

### Step 3: Create Directories

```bash
cd /opt/barterappbackend/deploy

# Create upload directories
mkdir -p uploads/images
chmod -R 755 uploads

# Create Flutter web directory
sudo mkdir -p /var/www/barters.lv
sudo chmod -R 755 /var/www/barters.lv
```

### Step 4: Start Docker Services

```bash
cd /opt/barterappbackend/deploy

# Build and start all services
docker-compose up -d --build

# Watch logs
docker-compose logs -f

# Check status
docker-compose ps
```

**Common Startup Issues**:

1. **Ollama can't download model** (TLS error with barters.lv certificate):
   ```bash
   # Stop nginx temporarily
   sudo systemctl stop nginx
   
   # Restart ollama to download model
   docker-compose restart ollama
   
   # Or download manually
   docker exec ollama_server ollama pull mxbai-embed-large
   
   # Start nginx again
   sudo systemctl start nginx
   ```

2. **Flyway migration checksum error** (modified migration files):
   ```bash
   # Reset database (DEVELOPMENT ONLY!)
   docker-compose down -v
   docker volume rm deploy_postgres_data
   docker-compose up -d
   ```

3. **Files not appearing in uploads**:
   - Check `.env` has **container paths** (`/uploads/images`)
   - Check docker-compose volume: `./uploads:/uploads`
   - Verify: `docker exec barter_app_server ls -la /uploads/images/`

---

## Part 4: Configure Nginx

### Step 1: Create Nginx Configuration

```bash
sudo nano /etc/nginx/conf.d/barters-lv.conf
```

Paste this configuration (based on `deploylive/nginx.conf`):

```nginx
upstream barter_backend {
    server 127.0.0.1:8081;
    keepalive 32;
}

# Rate limiting zones
limit_req_zone $binary_remote_addr zone=api_general:10m rate=100r/m;
limit_req_zone $binary_remote_addr zone=api_auth:10m rate=5r/m;
limit_req_zone $binary_remote_addr zone=api_upload:10m rate=2r/m;
limit_req_zone $binary_remote_addr zone=api_search:10m rate=10r/m;
limit_req_zone $binary_remote_addr zone=api_profile:10m rate=20r/m;
limit_req_zone $binary_remote_addr zone=api_websocket:10m rate=30r/m;
limit_conn_zone $binary_remote_addr zone=addr:10m;

# HTTP redirect to HTTPS
server {
    listen 80;
    listen [::]:80;
    server_name barters.lv www.barters.lv;

    # Allow certbot validation
    location ^~ /.well-known/acme-challenge/ {
        default_type "text/plain";
        root /var/www/certbot;
    }

    return 301 https://$server_name$request_uri;
}

# WWW redirect to non-www
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name www.barters.lv;

    ssl_certificate /etc/letsencrypt/live/barters.lv/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/barters.lv/privkey.pem;

    return 301 https://barters.lv$request_uri;
}

# Main HTTPS server
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name barters.lv;

    # SSL configuration
    ssl_certificate /etc/letsencrypt/live/barters.lv/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/barters.lv/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;

    # Security headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    # Logging
    access_log /var/log/nginx/barter-app-access.log;
    error_log /var/log/nginx/barter-app-error.log warn;

    client_max_body_size 5M;
    limit_req_status 429;
    limit_conn_status 429;

    # Health check (light rate limit)
    location /health {
        limit_req zone=api_general burst=50 nodelay;
        proxy_pass http://barter_backend;
        access_log off;
    }

    # Authentication endpoints (strictest limits)
    location ~ ^/api/v1/authentication/(login|register|verify) {
        limit_req zone=api_auth burst=3 nodelay;
        proxy_pass http://barter_backend;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 30s;
        proxy_send_timeout 30s;
        proxy_read_timeout 30s;
    }

    # WebSocket (CRITICAL: path is /chat not /ws!)
    location /chat {
        limit_req zone=api_websocket burst=10 nodelay;
        limit_conn addr 10;

        proxy_pass http://barter_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_connect_timeout 60s;
        proxy_send_timeout 86400s;
        proxy_read_timeout 86400s;

        proxy_buffering off;
        tcp_nodelay on;
    }

    # General API endpoints
    location /api/ {
        limit_req zone=api_general burst=20 nodelay;
        limit_conn addr 10;
        
        proxy_pass http://barter_backend;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    # Flutter web app (static files)
    location / {
        root /var/www/barters.lv;
        index index.html;
        try_files $uri $uri/ /index.html;
        
        # Cache static assets
        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
            root /var/www/barters.lv;
            expires 1y;
            add_header Cache-Control "public, immutable";
        }
        
        # Don't cache index.html
        location = /index.html {
            root /var/www/barters.lv;
            add_header Cache-Control "no-cache, no-store, must-revalidate";
            expires 0;
        }
    }
}
```

**Key Points**:
- ✅ WebSocket is at `/chat` (not `/ws`)
- ✅ Use `proxy_pass http://barter_backend;` (NO port!)
- ✅ Generous WebSocket limits (30/min, burst 10)
- ✅ Images served by backend at `/api/v1/images/*`
- ✅ NO `/uploads/` location block (backend handles images)

### Step 2: Test Nginx Configuration

```bash
sudo nginx -t
```

If errors, check:
- ❌ `unknown command "ollama" for "ollama"` - Wrong syntax, not nginx issue
- ❌ `upstream may not have port 8081` - Remove `:8081` from `proxy_pass`
- ❌ `listen ... http2` deprecated - Use `http2 on;` on separate line

---

## Part 5: Get SSL Certificates

### Step 1: Stop Nginx (Certbot needs port 80/443)

```bash
sudo systemctl stop nginx
```

### Step 2: Get Certificates

```bash
sudo mkdir -p /var/www/certbot

# Get certificates for both domains
sudo certbot certonly --standalone \
  -d barters.lv \
  -d www.barters.lv \
  --email your-email@example.com \
  --agree-tos \
  --non-interactive

# Certificates will be at:
# /etc/letsencrypt/live/barters.lv/fullchain.pem
# /etc/letsencrypt/live/barters.lv/privkey.pem
```

### Step 3: Set Permissions

```bash
sudo chmod 755 /etc/letsencrypt/live
sudo chmod 755 /etc/letsencrypt/archive
```

### Step 4: Test Auto-Renewal

```bash
sudo certbot renew --dry-run
```

### Step 5: Start Nginx

```bash
sudo nginx -t
sudo systemctl start nginx
sudo systemctl enable nginx
```

---

## Part 6: Deploy Flutter Web App

### On Your Development Machine

```bash
cd your-flutter-project

# Build for production
flutter build web --release

# Upload to server
rsync -avz --delete build/web/ root@your-server:/var/www/barters.lv/

# Or using scp
scp -r build/web/* root@your-server:/var/www/barters.lv/
```

### On Server

```bash
# Set permissions
sudo chown -R nginx:nginx /var/www/barters.lv
sudo chmod -R 755 /var/www/barters.lv

# SELinux context (AlmaLinux)
sudo chcon -R -t httpd_sys_content_t /var/www/barters.lv

# Verify files
ls -la /var/www/barters.lv/
# Should show: index.html, main.dart.js, flutter_service_worker.js, etc.
```

### Configure Flutter API URL

In your Flutter app, use **relative URLs**:

```dart
class ApiConfig {
  // ✅ CORRECT - Relative paths (no CORS issues!)
  static const String baseUrl = '/api/v1';
  
  // Auto-detect WebSocket protocol
  static String get wsUrl {
    final protocol = Uri.base.scheme == 'https' ? 'wss' : 'ws';
    return '$protocol://${Uri.base.host}/chat';
  }
}

// Usage
final response = await http.get(Uri.parse('/api/v1/postings/nearby'));
final ws = WebSocketChannel.connect(Uri.parse(ApiConfig.wsUrl));
```

**❌ DON'T USE**:
```dart
// Wrong - hard-coded domain
static const String baseUrl = 'https://barters.lv/api/v1';

// Wrong - includes port
static const String baseUrl = 'https://barters.lv:8081/api/v1';

// Wrong - HTTP on HTTPS site (mixed content error)
static const String baseUrl = 'http://barters.lv/api/v1';
```

---

## Part 7: Verify Deployment

### Check All Services

```bash
# Docker containers
docker-compose ps
# All should be "Up" or "Up (healthy)"

# Nginx
sudo systemctl status nginx

# Check logs
docker-compose logs barter_app_server | tail -50
sudo tail -f /var/log/nginx/barter-app-error.log
```

### Test Endpoints

```bash
# Flutter app
curl -I https://barters.lv/
# Should return 200 OK with HTML

# API health
curl https://barters.lv/health
# Should return {"status":"ok"}

# Image serving
curl -I "https://barters.lv/api/v1/images/userId/image.jpg?size=thumb"
# Should return 200 OK (after uploading an image)

# WebSocket upgrade
curl -I https://barters.lv/chat
# Should return 426 Upgrade Required or 400 Bad Request (normal for curl)
```

### Test from Browser

1. **Open**: https://barters.lv
2. **Check DevTools** (F12) → Network tab
3. **Verify**:
   - ✅ Static files load (200 OK)
   - ✅ API calls work (200 OK)
   - ✅ WebSocket connects (101 Switching Protocols)
   - ✅ Images load (200 OK)
   - ❌ No CORS errors
   - ❌ No mixed content errors

---

## Part 8: Application Configuration

### CORS Configuration

With same-domain setup (Flutter at `barters.lv`, API at `barters.lv/api`), CORS is **disabled in production**:

```kotlin
// src/app/bartering/Application.kt
val isDevelopment = System.getenv("ENVIRONMENT")?.lowercase() != "production"

if (isDevelopment) {
    install(CORS) {
        // Development only
        allowHost("localhost:8081")
        // ...
    }
} else {
    log.info("🔒 CORS disabled for production (same-domain nginx setup)")
}
```

### Image Serving

Images are served by the backend at `/api/v1/images/{userId}/{fileName}?size={thumb|full}`:

- **Upload**: Files saved as `{uuid}_full.jpg` and `{uuid}_thumb.jpg`
- **URL**: `/api/v1/images/{userId}/{uuid}.jpg` (no suffix!)
- **Query param**: `?size=thumb` or `?size=full`
- **Backend**: `ImageServeRoutes.kt` handles size parameter and serves correct file

**Environment Variables**:
```bash
IMAGE_UPLOAD_DIR=/uploads/images          # Container path
IMAGE_BASE_URL=/api/v1/images             # URL path (no domain!)
```

---

## Troubleshooting

### Issue: WebSocket 429 Too Many Requests

**Symptoms**: `WebSocketChannelException: Failed to connect`

**Cause**: Nginx rate limiting too strict (5/min default)

**Fix**: Update nginx config:
```nginx
limit_req_zone $binary_remote_addr zone=api_websocket:10m rate=30r/m;

location /chat {
    limit_req zone=api_websocket burst=10 nodelay;
    # ...
}
```

### Issue: Images Return 404

**Symptoms**: `HTTP request failed, statusCode: 404` for image URLs

**Causes & Fixes**:

1. **Wrong IMAGE_BASE_URL**:
   ```bash
   # ❌ Wrong
   IMAGE_BASE_URL=https://barters.lv/uploads
   IMAGE_BASE_URL=/uploads
   
   # ✅ Correct
   IMAGE_BASE_URL=/api/v1/images
   ```

2. **Wrong IMAGE_UPLOAD_DIR** (host path instead of container):
   ```bash
   # ❌ Wrong
   IMAGE_UPLOAD_DIR=/opt/barterappbackend/deploy/uploads/images
   
   # ✅ Correct
   IMAGE_UPLOAD_DIR=/uploads/images
   ```

3. **Files not being saved**:
   ```bash
   # Check inside container
   docker exec barter_app_server ls -la /uploads/images/
   
   # Check on host
   ls -la /opt/barterappbackend/deploy/uploads/images/
   
   # Should show same files (volume mount)
   ```

### Issue: Ollama TLS Certificate Error

**Symptoms**: `x509: certificate is valid for barters.lv, not registry.ollama.ai`

**Cause**: Nginx intercepting outbound HTTPS on port 443 (network_mode: host)

**Fix**:
```bash
# Option 1: Stop nginx temporarily, download model, restart
sudo systemctl stop nginx
docker exec ollama_server ollama pull mxbai-embed-large
sudo systemctl start nginx

# Option 2: Download on dev machine, transfer to server
# (see STOPWORDS_MULTILANGUAGE_GUIDE.md)
```

### Issue: Images Return 403 Forbidden

**Symptoms**: `HTTP request failed, statusCode: 403`

**Causes**:

1. **Wrong filename** (URL has no suffix, files have `_full.jpg`/`_thumb.jpg`):
   - This is normal! Backend route handles suffix mapping
   - Verify `IMAGE_BASE_URL=/api/v1/images` (not `/uploads`)

2. **SELinux blocking**:
   ```bash
   # Check SELinux
   getenforce
   
   # Set correct context
   sudo chcon -R -t httpd_sys_content_t /opt/barterappbackend/deploy/uploads
   
   # Or temporarily disable to test
   sudo setenforce 0
   # (Re-enable: sudo setenforce 1)
   ```

3. **Permission issues**:
   ```bash
   sudo chmod -R 755 /opt/barterappbackend/deploy/uploads
   sudo chown -R 1000:1000 /opt/barterappbackend/deploy/uploads
   ```

### Issue: Database Migration Checksum Mismatch

**Symptoms**: `FlywayValidateException: Migration checksum mismatch`

**Cause**: Modified migration file after it was applied

**Fix (DEVELOPMENT ONLY - DESTROYS DATA)**:
```bash
docker-compose down
docker volume rm deploy_postgres_data
docker-compose up -d
```

**Production Fix**: Never modify applied migrations! Create new migration instead.

### Issue: Container Keeps Restarting

**Symptoms**: `docker-compose ps` shows "Restarting"

**Debug**:
```bash
# Check logs
docker-compose logs barter_app_server

# Common causes:
# - Missing .env file
# - Wrong paths in .env
# - Database not ready
# - Port already in use
```

---

## Maintenance

### View Logs

```bash
# Docker logs
docker-compose logs -f barter_app_server
docker-compose logs -f postgresql_server

# Nginx logs
sudo tail -f /var/log/nginx/barter-app-access.log
sudo tail -f /var/log/nginx/barter-app-error.log
```

### Restart Services

```bash
# Restart all containers
docker-compose restart

# Restart specific service
docker-compose restart barter_app_server

# Reload nginx
sudo systemctl reload nginx
```

### Update Application

```bash
# Build new JAR
./gradlew shadowJar

# Upload to server
scp build/libs/BarterAppBackend-all.jar root@server:/opt/barterappbackend/deploy/

# Rebuild and restart
cd /opt/barterappbackend/deploy
docker-compose down
docker-compose up -d --build
```

### Backup Database

```bash
# Backup
docker exec postgresql_server pg_dump -U postgres mainDatabase > backup.sql

# Restore
cat backup.sql | docker exec -i postgresql_server psql -U postgres mainDatabase
```

---

## Security Checklist

- ✅ SSL certificates installed and auto-renewing
- ✅ Nginx rate limiting enabled
- ✅ Strong database password in `.env`
- ✅ `.env` file has 600 permissions
- ✅ No port 8081 exposed to internet (only nginx)
- ✅ SELinux enabled (AlmaLinux)
- ✅ CORS disabled in production
- ✅ Security headers configured in nginx
- ✅ Image serving restricted to valid extensions
- ✅ WebSocket connection limits (10 per IP)

---

## Performance Tips

- **Enable HTTP/2**: Already enabled with `http2 on;`
- **Gzip compression**: Add to nginx for text content
- **Image optimization**: Backend generates thumbnails automatically
- **Database indexing**: Check `user_postings` has indexes on frequently queried fields
- **Docker resource limits**: Add CPU/memory limits in docker-compose if needed

---

## Summary

**Key Learnings from Production Deployment**:

1. ✅ Use **container paths** in .env (`/uploads/images`), not host paths
2. ✅ Use **relative URLs** for IMAGE_BASE_URL (`/api/v1/images`)
3. ✅ WebSocket path is `/chat` (your ChatRoutes.kt uses this)
4. ✅ Images served by **backend** (`ImageServeRoutes.kt`), not nginx directly
5. ✅ Rate limits need to be **generous for WebSocket** (30/min, not 5/min)
6. ✅ CORS **disabled in production** (same-domain setup)
7. ✅ SSL certificates intercepting Ollama downloads (stop nginx temporarily)
8. ✅ Use `http2 on;` separately, not `listen 443 ssl http2;`
9. ✅ Never modify applied Flyway migrations

**Architecture Benefits**:
- 🔒 No CORS needed (same origin)
- 🚀 Backend handles image resizing/caching
- 📦 Docker makes deployment reproducible
- 🔄 Easy to rollback and update
- 🛡️ Nginx provides security layer

---

## Related Documentation

- [Environment Variables](.env.production)
- [Nginx Configuration](../deploylive/nginx.conf)
- [Docker Compose](../deploy/docker-compose.yml)

---

**Deployed successfully?** 🎉 Your app is now live at https://barters.lv!
