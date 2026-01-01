#!/bin/bash
# Ultimate fix: Run Docker without any iptables/firewall management - Fixed paths

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Docker Without Firewall (Fixed)${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Step 1: Stop everything
echo -e "${YELLOW}[1] Stopping all services...${NC}"
docker compose down 2>/dev/null || true
systemctl stop docker 2>/dev/null || true
systemctl stop docker.socket 2>/dev/null || true
sleep 3
echo -e "${GREEN}✓ Services stopped${NC}"
echo ""

# Step 2: Configure Docker - minimal config
echo -e "${YELLOW}[2] Configuring Docker (no iptables)...${NC}"
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "dns": ["8.8.8.8", "8.8.4.4"],
  "iptables": false,
  "ip-forward": false,
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
EOF
echo -e "${GREEN}✓ Docker configured${NC}"
echo ""

# Step 3: Start Docker
echo -e "${YELLOW}[3] Starting Docker...${NC}"
systemctl daemon-reload
systemctl start docker
sleep 5

if systemctl is-active --quiet docker; then
    echo -e "${GREEN}✓ Docker started${NC}"
else
    echo -e "${RED}✗ Docker failed${NC}"
    exit 1
fi
echo ""

# Step 5: Create docker-compose with host network
echo -e "${YELLOW}[5] Creating docker-compose with host networking...${NC}"

cat > docker-compose-host-network.yml <<'EOFCOMPOSE'
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
      OLLAMA_URL: http://ollama:11434
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
      - OLLAMA_HOST=ollama:11434
    dns:
      - 8.8.8.8
      - 8.8.4.4
    volumes:
      - ollama_data:/root/.ollama
    entrypoint: /bin/sh
    command: -c "ollama serve & sleep 5 && ollama pull nomic-embed-text && wait"

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
      POSTGRES_USER: ${POSTGRES_USER:-postgres}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-Test1234}
      POSTGRES_DB: ${POSTGRES_DB:-mainDatabase}
    restart: always

volumes:
  postgres_data:
    driver: local
  ollama_data:
    driver: local
EOFCOMPOSE

echo -e "${GREEN}✓ docker-compose created${NC}"
echo ""

# Step 6: Start containers
echo -e "${YELLOW}[6] Starting containers...${NC}"
docker compose -f docker-compose-host-network.yml up -d --force-recreate --build
sleep 30
echo -e "${GREEN}✓ Containers started${NC}"
echo ""

# Step 7: Check containers
echo -e "${YELLOW}[7] Checking containers...${NC}"
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
echo ""

# Step 8: Test connectivity
echo -e "${YELLOW}[8] Testing connectivity...${NC}"

echo -e "${BLUE}Testing ollama DNS:${NC}"
if docker exec ollama_server ping -c 2 8.8.8.8 > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Can reach DNS server${NC}"
else
    echo -e "${RED}✗ Cannot reach DNS${NC}"
fi

echo -e "${BLUE}Testing ollama external resolution:${NC}"
if docker exec ollama_server wget --spider --timeout=5 http://registry.ollama.ai > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Can reach registry.ollama.ai${NC}"
else
    echo -e "${YELLOW}⚠ Cannot reach registry (might be resolving)${NC}"
fi

echo -e "${BLUE}Testing postgres to ollama:${NC}"
if docker exec postgresql_server curl -s http://ollama:11434/ 2>&1 | grep -q "Ollama"; then
    echo -e "${GREEN}✓ Postgres can reach Ollama${NC}"
else
    echo -e "${YELLOW}⚠ Services might still be starting${NC}"
fi
echo ""

# Step 9: Show logs
echo -e "${YELLOW}[9] Recent logs...${NC}"
echo ""
echo -e "${BLUE}=== OLLAMA (last 20 lines) ===${NC}"
docker logs ollama_server 2>&1 | tail -20
echo ""
echo -e "${BLUE}=== APPLICATION (last 20 lines) ===${NC}"
docker logs barter_app_server 2>&1 | tail -20
echo ""

# Step 10: Summary
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  SUMMARY${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

if docker logs ollama_server 2>&1 | grep -q "success"; then
    echo -e "${GREEN}✓ Ollama model downloaded successfully${NC}"
elif docker logs ollama_server 2>&1 | grep -q "pulling"; then
    echo -e "${YELLOW}⏳ Ollama still downloading model...${NC}"
elif docker logs ollama_server 2>&1 | grep -i "error\|failed" | grep -v "experimental"; then
    echo -e "${RED}✗ Ollama has errors${NC}"
    docker logs ollama_server 2>&1 | grep -i "error\|failed" | tail -5
else
    echo -e "${YELLOW}⏳ Ollama status unclear, check logs${NC}"
fi

if docker logs barter_app_server 2>&1 | grep -q "Populated.*embeddings"; then
    echo -e "${GREEN}✓ Application is working${NC}"
elif docker logs barter_app_server 2>&1 | grep -q "responding"; then
    echo -e "${GREEN}✓ Application started${NC}"
else
    echo -e "${YELLOW}⏳ Application still starting or has issues${NC}"
fi

echo ""
echo -e "${BLUE}Configuration:${NC}"
echo "- Network mode: host"
echo "- Firewall: disabled"  
echo "- iptables: disabled"
echo "- DNS: 8.8.8.8, 8.8.4.4"
echo ""
echo -e "${BLUE}Services accessible at:${NC}"
echo "- PostgreSQL: localhost:5432"
echo "- Ollama: ollama:11434"
echo "- Application: localhost:8081"
echo ""
echo -e "${YELLOW}Watch logs:${NC}"
echo "docker compose -f docker-compose-host-network.yml logs -f"
echo ""
echo -e "${YELLOW}Check ollama model:${NC}"
echo "docker exec ollama_server ollama list"
echo ""
echo -e "${YELLOW}To make this permanent:${NC}"
echo "cd /opt/barterappbackend"
echo "cp docker-compose-host-network.yml deploy/docker-compose.yml"
echo ""
