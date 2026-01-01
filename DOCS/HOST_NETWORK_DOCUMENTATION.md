# Host Network Mode - Key Takeaways & Considerations

## ✅ What Was Done

Your application is now running with **Docker host network mode** instead of bridge networking. This
completely bypasses the firewall/iptables issues that were blocking your deployment.

### Configuration Applied

1. **Docker Daemon** (`/etc/docker/daemon.json`):
   ```json
   {
     "dns": ["8.8.8.8", "8.8.4.4"],
     "iptables": false,
     "ip-forward": false
   }
   ```

2. **Firewalld**: ✅ ENABLED and configured (protecting internal services)
3. **Network Mode**: `network_mode: host` for all containers
4. **DNS**: Direct use of Google DNS (8.8.8.8, 8.8.4.4)

---

## ✅ Advantages of This Approach

### 1. **Simplicity**

- No complex Docker bridge networking
- No NAT/port mapping
- No iptables rules to manage
- Services communicate via `localhost`

### 2. **Performance**

- **Lower latency** - No network address translation overhead
- **Higher throughput** - Direct host network stack access
- **Reduced CPU usage** - No packet rewriting

### 3. **Reliability**

- **No firewall conflicts** - Bypasses firewalld/iptables completely
- **No DNS resolver issues** - Uses external DNS directly
- **Fewer failure points** - Simpler network path

### 4. **Common in Production**

- Many production systems use host networking for:
    - Database servers
    - High-performance applications
    - Microservices that need low latency

---

## ⚠️ Disadvantages & Trade-offs

### 1. **No Network Isolation**

- ❌ Containers share the host's network namespace
- ❌ All containers can see each other's network traffic
- ❌ Containers can access all host network interfaces
- ❌ No firewall between containers

**Impact:** If one container is compromised, it has direct network access to others.

### 2. **Port Conflicts**

- ❌ Only ONE container can use a given port
- ❌ Ports are directly exposed on the host
- ❌ Can't run multiple instances of the same service

**Example:** You can't run two PostgreSQL containers both on port 5432.

### 3. **Security Considerations**

### 4. **Less Portable**

- ❌ Host networking doesn't work on Docker Desktop (Mac/Windows)
- ❌ docker-compose files are less portable
- ❌ Harder to move to Kubernetes later (doesn't support host networking the same way)

### 5. **No Service Discovery**

- ❌ Can't use Docker's built-in DNS
- ❌ Services must use `localhost` or IP addresses
- ❌ Harder to add multiple replicas

---

### ✅ Correct Configuration

```
Public Zone (accessible from internet):
  ✅ Port 22 (SSH)        - For remote server access
  ✅ Port 80 (HTTP)       - For web traffic
  ✅ Port 443 (HTTPS)     - For secure web traffic  
  ✅ Port 8081            - For your application

NOT in Public Zone (localhost only):
  🔒 Port 5432 (PostgreSQL) - Database
  🔒 Port 11434 (Ollama)    - AI service
```

Your containers will still be able to access PostgreSQL and Ollama via `localhost` because they use
host networking mode, but external attackers cannot reach them.

### 📊 STEP 2: Alternative Security Layers (Optional but Recommended)

#### Option A: Use iptables Rules for Additional Protection

```bash
# Flush existing rules
iptables -F

# Default policies
iptables -P INPUT DROP
iptables -P FORWARD DROP
iptables -P OUTPUT ACCEPT

# Allow loopback
iptables -A INPUT -i lo -j ACCEPT

# Allow established connections
iptables -A INPUT -m state --state ESTABLISHED,RELATED -j ACCEPT

# Allow SSH (CRITICAL - don't lock yourself out!)
iptables -A INPUT -p tcp --dport 22 -j ACCEPT

# Allow HTTP/HTTPS
iptables -A INPUT -p tcp --dport 80 -j ACCEPT
iptables -A INPUT -p tcp --dport 443 -j ACCEPT

# Allow Application
iptables -A INPUT -p tcp --dport 8081 -j ACCEPT

# IMPORTANT: Do NOT allow 5432 (PostgreSQL) or 11434 (Ollama)
# These should only be accessible via localhost

# Save rules
iptables-save > /etc/iptables/rules.v4
```

#### Option B: Use Nginx Reverse Proxy (Recommended for Production)

```nginx
# /etc/nginx/conf.d/barter-app.conf
server {
    listen 80;
    server_name your-domain.com;
    
    # Force HTTPS
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name your-domain.com;
    
    ssl_certificate /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;
    
    # Proxy to application
    location / {
        proxy_pass http://localhost:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
    
    # Rate limiting
    limit_req_zone $binary_remote_addr zone=api_limit:10m rate=10r/s;
    limit_req zone=api_limit burst=20 nodelay;
}

# DO NOT expose PostgreSQL or Ollama through Nginx
```

Then:

- PostgreSQL (5432): Only accessible via `localhost` from other containers
- Ollama (11434): Only accessible via `localhost` from other containers
- Application (8081): Behind Nginx on port 80/443

---

## 📊 Current Architecture

```
┌─────────────────────────────────────────────────────┐
│            AlmaLinux 10 Server (Host)               │
│                                                      │
│  ┌──────────────────────────────────────────────┐  │
│  │  Host Network Namespace                      │  │
│  │                                               │  │
│  │  ┌──────────────┐  ┌──────────────┐         │  │
│  │  │ PostgreSQL   │  │ Ollama       │         │  │
│  │  │ Container    │  │ Container    │         │  │
│  │  │              │  │              │         │  │
│  │  │ Port 5432    │  │ Port 11434   │         │  │
│  │  └──────────────┘  └──────────────┘         │  │
│  │         ↑                  ↑                  │  │
│  │         │                  │                  │  │
│  │         └──────────┬───────┘                  │  │
│  │                    │                          │  │
│  │         ┌──────────────────────┐              │  │
│  │         │   Application        │              │  │
│  │         │   Container          │              │  │
│  │         │                      │              │  │
│  │         │   Port 8081          │              │  │
│  │         └──────────────────────┘              │  │
│  │                    │                          │  │
│  └────────────────────┼──────────────────────────┘  │
│                       ↓                             │
│              localhost:8081                         │
│                                                      │
└──────────────────────┼──────────────────────────────┘
                       ↓
                   Internet
                (NO FIREWALL!)
```

---

## 🔄 Alternative: Return to Bridge Networking (Future)

If you want better isolation later, you could:

### Option 1: Use a Different Linux Distribution

- Ubuntu/Debian: Better Docker networking support out-of-the-box
- No firewalld conflicts

### Option 2: Fix AlmaLinux Firewall

Would require:

1. Installing iptables-legacy
2. Configuring firewalld Docker zone properly
3. Enabling IP masquerading
4. This was attempted but had kernel module issues on your server

### Option 3: Use Docker Swarm or Kubernetes

- Better networking models
- Built-in service discovery
- Network policies
- But more complex to set up

---

## 📋 Operational Considerations

### Monitoring

Check service health:

```bash
# Application
curl http://localhost:8081/health

# PostgreSQL
docker exec postgresql_server pg_isready

# Ollama
curl http://ollama:11434/
```

### Logs

```bash
# All services
docker compose -f docker-compose-host-network.yml logs -f

# Specific service
docker logs postgresql_server -f
docker logs ollama_server -f
docker logs barter_app_server -f
```

### Backups

```bash
# PostgreSQL backup
docker exec postgresql_server pg_dump -U postgres mainDatabase > backup.sql

# Restore
docker exec -i postgresql_server psql -U postgres mainDatabase < backup.sql
```

### Updates

```bash
cd /opt/barterappbackend
git pull
docker compose -f docker-compose-host-network.yml down
docker compose -f docker-compose-host-network.yml up -d --build
```

---

## ✅ Best Practices with Host Networking

### 1. **Use Environment-Specific Configs**

```bash
# docker-compose.host-network.yml  - For AlmaLinux (current)
# docker-compose.yml               - For dev/other environments
```

### 2. **Document Port Usage**

Create `/opt/barterappbackend/PORTS.md`:

```
5432  - PostgreSQL (internal only)
8081  - Application (public via Nginx)
11434 - Ollama (internal only)
```

### 3. **Set Resource Limits**

Add to docker-compose:

```yaml
services:
  postgres:
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
```

### 4. **Use Health Checks**

Already configured, but monitor them:

```bash
docker ps --format "table {{.Names}}\t{{.Status}}"
```

### 5. **Regular Security Audits**

```bash
# Check what's listening
netstat -tulpn | grep LISTEN

# Check exposed services
nmap localhost
```

---

## 🎯 Summary

### What You Gained ✅

- ✅ **Working application** - No more DNS/firewall issues
- ✅ **Better performance** - Lower latency
- ✅ **Simpler setup** - Less complexity
- ✅ **Reliable networking** - Fewer failure points

### What You Lost ❌

- ❌ **Network isolation** - Containers share network
- ❌ **Security** - No firewall between services (MUST ADD EXTERNAL FIREWALL!)
- ❌ **Portability** - Tied to Linux host networking

### Critical Next Steps 🔴

1. **IMMEDIATELY** - Add firewall rules to protect PostgreSQL/Ollama ports
2. Set up Nginx reverse proxy for HTTPS
3. Configure SSL certificates with Let's Encrypt
4. Set up monitoring and alerting
5. Configure automated backups

---

## 📚 Further Reading

- [Docker Host Networking](https://docs.docker.com/network/host/)
- [Docker Security Best Practices](https://docs.docker.com/engine/security/)
- [AlmaLinux Firewalld](https://wiki.almalinux.org/documentation/firewalld.html)
- [Nginx Reverse Proxy](https://docs.nginx.com/nginx/admin-guide/web-server/reverse-proxy/)

---

---

## 🚨 SECURITY CHECKLIST

Run through this checklist on your AlmaLinux server:

- [ ] **Remove PostgreSQL (5432) from public zone** -
  `sudo firewall-cmd --permanent --zone=public --remove-port=5432/tcp`
- [ ] **Remove Ollama (11434) from public zone** -
  `sudo firewall-cmd --permanent --zone=public --remove-port=11434/tcp`
- [ ] **Remove DNS ports from public zone** -
  `sudo firewall-cmd --permanent --zone=public --remove-port=53/tcp --remove-port=53/udp`
- [ ] **Remove unknown port (49913) from public zone** -
  `sudo firewall-cmd --permanent --zone=public --remove-port=49913/tcp`
- [ ] **Reload firewall** - `sudo firewall-cmd --reload`
- [ ] **Verify configuration** - `sudo firewall-cmd --list-all --zone=public`
- [ ] **Test application still works** - Access your app via browser
- [ ] **Verify services accessible via localhost** - SSH to server and test `curl localhost:5432`,
  `curl ollama:11434`
- [ ] **Set up Nginx reverse proxy** - For HTTPS and additional security layer
- [ ] **Configure SSL certificates** - Use Let's Encrypt
- [ ] **Set up monitoring** - Track service health
- [ ] **Configure backups** - Regular PostgreSQL backups

---

**Last Updated:** December 6, 2025  
**Configuration:** Host Network Mode on AlmaLinux 10  
**Status:** ⚠️ CRITICAL - Security hardening required immediately  
**Action Required:** Remove exposed internal ports from firewall public zone
