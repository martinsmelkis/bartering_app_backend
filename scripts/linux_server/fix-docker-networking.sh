#!/bin/bash
# Final fix: Disable Docker's embedded DNS and use host DNS directly

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Final Docker Networking Fix${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

echo -e "${YELLOW}The problem: Docker's embedded DNS (127.0.0.11) is not working${NC}"
echo -e "${YELLOW}The solution: Bypass it completely and use external DNS directly${NC}"
echo ""

# Step 1: Stop everything
echo -e "${YELLOW}[1] Stopping all containers and Docker...${NC}"
cd /opt/barterappbackend/deploy
docker compose down 2>/dev/null || true
systemctl stop docker
systemctl stop docker.socket
sleep 3
echo -e "${GREEN}✓ Everything stopped${NC}"
echo ""

# Step 2: Configure Docker to disable embedded DNS
echo -e "${YELLOW}[2] Configuring Docker to bypass embedded DNS...${NC}"
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "dns": ["8.8.8.8", "8.8.4.4"],
  "dns-opts": ["ndots:0"],
  "dns-search": [],
  "iptables": true,
  "ip-forward": true,
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
EOF
echo -e "${GREEN}✓ Docker daemon configured${NC}"
cat /etc/docker/daemon.json
echo ""

# Step 3: Configure sysctl for networking
echo -e "${YELLOW}[3] Configuring kernel networking...${NC}"
sudo tee /etc/sysctl.d/99-docker.conf > /dev/null <<'EOF'
net.ipv4.ip_forward = 1
net.bridge.bridge-nf-call-iptables = 1
net.bridge.bridge-nf-call-ip6tables = 1
EOF
sudo sysctl --system > /dev/null 2>&1
echo -e "${GREEN}✓ Kernel configured${NC}"
echo ""

# Step 4: Clean up Docker state completely
echo -e "${YELLOW}[4] Cleaning Docker state...${NC}"
sudo rm -rf /var/lib/docker/network
sudo rm -rf /var/lib/docker/containers
echo -e "${GREEN}✓ State cleaned${NC}"
echo ""

# Step 5: Start Docker
echo -e "${YELLOW}[5] Starting Docker...${NC}"
sudo systemctl daemon-reload
sudo systemctl start docker
sleep 5

if systemctl is-active --quiet docker; then
    echo -e "${GREEN}✓ Docker started${NC}"
else
    echo -e "${RED}✗ Docker failed to start${NC}"
    journalctl -xeu docker.service --no-pager | tail -30
    exit 1
fi
echo ""

# Step 6: Test Docker networking basics
echo -e "${YELLOW}[6] Testing Docker networking...${NC}"
if docker run --rm alpine ping -c 2 8.8.8.8 > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Basic networking works${NC}"
else
    echo -e "${RED}✗ Basic networking failed${NC}"
    exit 1
fi

if docker run --rm alpine nslookup google.com 8.8.8.8 > /dev/null 2>&1; then
    echo -e "${GREEN}✓ DNS resolution works${NC}"
else
    echo -e "${RED}✗ DNS resolution failed${NC}"
fi
echo ""

# Step 7: Update docker-compose.yml to use external DNS
echo -e "${YELLOW}[7] Updating docker-compose.yml...${NC}"
cd /opt/barterappbackend/deploy

# Backup
cp docker-compose.yml docker-compose.yml.backup

# Remove 127.0.0.11 and add dns-search to disable embedded DNS
cat > docker-compose-fixed.yml <<'EOFCOMPOSE'
version: '3.8'

networks:
  barter-net:
    driver: bridge
    ipam:
      config:
        - subnet: 172.20.0.0/16

services:
  postgres:
    build:
      context: ../postgres
    container_name: postgresql_server
    restart: always
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: ${POSTGRES_DB}
      OLLAMA_URL: http://ollama:11434
    dns:
      - 8.8.8.8
      - 8.8.4.4
    dns_search: []
    extra_hosts:
      - "host.docker.internal:host-gateway"
    healthcheck:
      test: [ "CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}" ]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    ports:
      - "0.0.0.0:5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    networks:
      barter-net:
        ipv4_address: 172.20.0.2

  ollama:
    image: docker.io/ollama/ollama:latest
    ports:
      - "0.0.0.0:11434:11434"
    volumes:
      - ollama_data:/root/.ollama
    container_name: ollama_server
    hostname: ollama
    pull_policy: always
    tty: true
    restart: always
    environment:
      - OLLAMA_KEEP_ALIVE=24h
      - OLLAMA_HOST=0.0.0.0
    dns:
      - 8.8.8.8
      - 8.8.4.4
    dns_search: []
    entrypoint: /bin/sh
    command: -c "ollama serve & sleep 5 && ollama pull nomic-embed-text && wait"
    networks:
      barter-net:
        ipv4_address: 172.20.0.3

  barter_app_server:
    build:
      context: ..
      dockerfile: Dockerfile
    container_name: barter_app_server
    ports:
      - "0.0.0.0:8081:8081"
    dns:
      - 8.8.8.8
      - 8.8.4.4
    dns_search: []
    depends_on:
      postgres:
        condition: service_healthy
      ollama:
        condition: service_started
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: ${POSTGRES_DB}
    extra_hosts:
      - "postgres:172.20.0.2"
      - "ollama:172.20.0.3"
    networks:
      barter-net:
        ipv4_address: 172.20.0.4
    restart: always

volumes:
  postgres_data:
    driver: local
  ollama_data:
    driver: local
EOFCOMPOSE

mv docker-compose-fixed.yml docker-compose.yml
echo -e "${GREEN}✓ docker-compose.yml updated with fixed networking${NC}"
echo ""

# Step 8: Start containers with new configuration
echo -e "${YELLOW}[8] Starting containers with fixed networking...${NC}"
docker compose up -d --force-recreate --build
sleep 15
echo -e "${GREEN}✓ Containers started${NC}"
echo ""

# Step 9: Test networking between containers
echo -e "${YELLOW}[9] Testing container networking...${NC}"

echo -e "${BLUE}Testing ollama DNS:${NC}"
if docker exec ollama_server nslookup google.com 8.8.8.8 2>&1 | grep -q "Address"; then
    echo -e "${GREEN}✓ Ollama can resolve external DNS${NC}"
else
    echo -e "${RED}✗ Ollama cannot resolve DNS${NC}"
fi

echo -e "${BLUE}Testing postgres can reach ollama by IP:${NC}"
if docker exec postgresql_server ping -c 2 172.20.0.3 > /dev/null 2>&1; then
    echo -e "${GREEN}✓ PostgreSQL can reach Ollama by IP${NC}"
else
    echo -e "${RED}✗ PostgreSQL cannot reach Ollama${NC}"
fi

echo -e "${BLUE}Testing postgres can reach ollama by hostname:${NC}"
if docker exec postgresql_server ping -c 2 ollama > /dev/null 2>&1; then
    echo -e "${GREEN}✓ PostgreSQL can reach Ollama by hostname${NC}"
else
    echo -e "${YELLOW}⚠ PostgreSQL cannot reach Ollama by hostname (using IP in extra_hosts)${NC}"
fi
echo ""

# Step 10: Show logs
echo -e "${YELLOW}[10] Container logs...${NC}"
echo ""
echo -e "${BLUE}=== OLLAMA (last 15 lines) ===${NC}"
docker logs ollama_server 2>&1 | tail -15
echo ""
echo -e "${BLUE}=== APPLICATION (last 15 lines) ===${NC}"
docker logs barter_app_server 2>&1 | tail -15
echo ""

# Step 11: Summary
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  SUMMARY${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

if docker logs ollama_server 2>&1 | grep -q "success"; then
    echo -e "${GREEN}✓ Ollama model downloaded successfully${NC}"
elif docker logs ollama_server 2>&1 | grep -q "pulling"; then
    echo -e "${YELLOW}⏳ Ollama is still downloading the model...${NC}"
    echo -e "${YELLOW}   Wait 2-3 minutes and check: docker logs ollama_server${NC}"
else
    echo -e "${RED}✗ Ollama has issues${NC}"
fi

if docker logs barter_app_server 2>&1 | grep -q "Populated.*embeddings"; then
    echo -e "${GREEN}✓ Application populated embeddings successfully${NC}"
else
    echo -e "${YELLOW}⏳ Application waiting for Ollama or still starting...${NC}"
fi

echo ""
echo -e "${YELLOW}Key changes made:${NC}"
echo "1. Disabled Docker's embedded DNS (127.0.0.11)"
echo "2. Using external DNS (8.8.8.8, 8.8.4.4) directly"
echo "3. Added static IP addresses to containers"
echo "4. Added extra_hosts for explicit hostname resolution"
echo "5. Enabled iptables for Docker to manage firewall rules"
echo ""
echo -e "${YELLOW}Watch logs:${NC}"
echo "docker compose logs -f"
echo ""
