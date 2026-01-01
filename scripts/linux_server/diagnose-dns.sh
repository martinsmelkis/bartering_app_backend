#!/bin/bash
# DNS Diagnostic Script for AlmaLinux 10
# This script helps diagnose DNS resolution issues in Docker containers

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  DNS Diagnostic Script${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# 1. Check if docker-compose files have DNS configuration
echo -e "${YELLOW}[1] Checking docker-compose.yml for DNS configuration...${NC}"
if grep -q "dns:" docker-compose.yml; then
    echo -e "${GREEN}✓ DNS configuration found in docker-compose.yml${NC}"
    echo "DNS servers configured:"
    grep -A 3 "dns:" docker-compose.yml | head -20
else
    echo -e "${RED}✗ DNS configuration NOT found in docker-compose.yml${NC}"
    echo -e "${RED}  You need to add DNS configuration to all services${NC}"
fi
echo ""

# 2. Check Docker daemon DNS configuration
echo -e "${YELLOW}[2] Checking Docker daemon DNS configuration...${NC}"
if [ -f /etc/docker/daemon.json ]; then
    echo -e "${GREEN}✓ /etc/docker/daemon.json exists${NC}"
    cat /etc/docker/daemon.json
    if grep -q "dns" /etc/docker/daemon.json; then
        echo -e "${GREEN}✓ DNS configuration found${NC}"
    else
        echo -e "${RED}✗ DNS configuration NOT found${NC}"
    fi
else
    echo -e "${RED}✗ /etc/docker/daemon.json does NOT exist${NC}"
    echo -e "${YELLOW}  Run: sudo ./deploy/fix-docker-dns.sh${NC}"
fi
echo ""

# 3. Check if containers are running
echo -e "${YELLOW}[3] Checking container status...${NC}"
if docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "postgresql_server|ollama_server|barter_app"; then
    echo -e "${GREEN}✓ Containers are running${NC}"
else
    echo -e "${RED}✗ Containers are NOT running${NC}"
    echo -e "${YELLOW}  Start them with: docker compose up -d${NC}"
    exit 1
fi
echo ""

# 4. Check container DNS configuration
echo -e "${YELLOW}[4] Checking PostgreSQL container DNS configuration...${NC}"
if docker inspect postgresql_server 2>/dev/null | grep -A 10 '"Dns":' | head -15; then
    if docker inspect postgresql_server | grep -q '"8.8.8.8"'; then
        echo -e "${GREEN}✓ PostgreSQL container has DNS servers configured${NC}"
    else
        echo -e "${RED}✗ PostgreSQL container does NOT have DNS servers configured${NC}"
        echo -e "${YELLOW}  The container needs to be recreated with DNS config${NC}"
        echo -e "${YELLOW}  Run: docker compose down && docker compose up -d${NC}"
    fi
else
    echo -e "${RED}✗ Could not inspect PostgreSQL container${NC}"
fi
echo ""

echo -e "${YELLOW}[5] Checking Ollama container DNS configuration...${NC}"
if docker inspect ollama_server 2>/dev/null | grep -A 10 '"Dns":' | head -15; then
    if docker inspect ollama_server | grep -q '"8.8.8.8"'; then
        echo -e "${GREEN}✓ Ollama container has DNS servers configured${NC}"
    else
        echo -e "${RED}✗ Ollama container does NOT have DNS servers configured${NC}"
        echo -e "${YELLOW}  The container needs to be recreated with DNS config${NC}"
        echo -e "${YELLOW}  Run: docker compose down && docker compose up -d${NC}"
    fi
else
    echo -e "${RED}✗ Could not inspect Ollama container${NC}"
fi
echo ""

# 5. Test DNS resolution from PostgreSQL container
echo -e "${YELLOW}[6] Testing DNS resolution from PostgreSQL container...${NC}"

echo -e "Testing external DNS (8.8.8.8)..."
if docker exec postgresql_server ping -c 2 8.8.8.8 2>/dev/null; then
    echo -e "${GREEN}✓ Can reach external DNS server${NC}"
else
    echo -e "${RED}✗ Cannot reach external DNS server${NC}"
fi

echo -e "\nTesting DNS resolution (google.com)..."
if docker exec postgresql_server ping -c 2 google.com 2>/dev/null; then
    echo -e "${GREEN}✓ Can resolve external hostnames${NC}"
else
    echo -e "${RED}✗ Cannot resolve external hostnames${NC}"
fi

echo -e "\nTesting ollama hostname resolution..."
if docker exec postgresql_server getent hosts ollama 2>/dev/null; then
    echo -e "${GREEN}✓ Can resolve 'ollama' hostname${NC}"
else
    echo -e "${RED}✗ Cannot resolve 'ollama' hostname${NC}"
    echo -e "${YELLOW}  This is the main problem - PostgreSQL can't find Ollama${NC}"
fi

echo -e "\nTesting connection to Ollama..."
if docker exec postgresql_server curl -s --connect-timeout 5 http://ollama:11434/ 2>/dev/null | grep -q "Ollama"; then
    echo -e "${GREEN}✓ Can connect to Ollama service${NC}"
else
    echo -e "${RED}✗ Cannot connect to Ollama service${NC}"
fi
echo ""

# 6. Test DNS resolution from Ollama container
echo -e "${YELLOW}[7] Testing DNS resolution from Ollama container...${NC}"

echo -e "Testing DNS resolution (registry.ollama.ai)..."
if docker exec ollama_server ping -c 2 registry.ollama.ai 2>/dev/null; then
    echo -e "${GREEN}✓ Can resolve registry.ollama.ai${NC}"
else
    echo -e "${RED}✗ Cannot resolve registry.ollama.ai${NC}"
fi
echo ""

# 7. Check if Ollama has the model
echo -e "${YELLOW}[8] Checking if Ollama has the nomic-embed-text model...${NC}"
if docker exec ollama_server ollama list 2>/dev/null | grep -q "nomic-embed-text"; then
    echo -e "${GREEN}✓ nomic-embed-text model is installed${NC}"
    docker exec ollama_server ollama list | grep nomic-embed-text
else
    echo -e "${RED}✗ nomic-embed-text model is NOT installed${NC}"
    echo -e "${YELLOW}  Check Ollama logs: docker logs ollama_server${NC}"
fi
echo ""

# 8. Check firewall
echo -e "${YELLOW}[9] Checking firewall configuration...${NC}"
if systemctl is-active --quiet firewalld; then
    echo -e "${GREEN}✓ firewalld is active${NC}"
    
    echo -e "\nChecking if docker0 is in trusted zone..."
    if sudo firewall-cmd --zone=trusted --list-interfaces 2>/dev/null | grep -q docker0; then
        echo -e "${GREEN}✓ docker0 interface is in trusted zone${NC}"
    else
        echo -e "${RED}✗ docker0 interface is NOT in trusted zone${NC}"
        echo -e "${YELLOW}  Run: sudo firewall-cmd --permanent --zone=trusted --add-interface=docker0${NC}"
        echo -e "${YELLOW}  Then: sudo firewall-cmd --reload${NC}"
    fi
    
    echo -e "\nChecking masquerading..."
    if sudo firewall-cmd --zone=public --query-masquerade 2>/dev/null; then
        echo -e "${GREEN}✓ Masquerading is enabled${NC}"
    else
        echo -e "${RED}✗ Masquerading is NOT enabled${NC}"
        echo -e "${YELLOW}  Run: sudo firewall-cmd --permanent --zone=public --add-masquerade${NC}"
        echo -e "${YELLOW}  Then: sudo firewall-cmd --reload${NC}"
    fi
else
    echo -e "${YELLOW}⚠ firewalld is not active${NC}"
fi
echo ""

# 9. Check network configuration
echo -e "${YELLOW}[10] Checking Docker network...${NC}"
docker network inspect barter-net 2>/dev/null | grep -A 5 "Containers" || echo -e "${RED}✗ Network 'barter-net' not found${NC}"
echo ""

# 10. Summary and recommendations
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  SUMMARY & RECOMMENDATIONS${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check if main issue is found
if docker inspect postgresql_server 2>/dev/null | grep -q '"8.8.8.8"'; then
    if docker exec postgresql_server getent hosts ollama 2>/dev/null > /dev/null; then
        echo -e "${GREEN}✓✓✓ DNS is configured correctly!${NC}"
        echo -e "${GREEN}All tests passed. Your containers should work.${NC}"
    else
        echo -e "${YELLOW}⚠ DNS is configured but hostname resolution still fails${NC}"
        echo -e "${YELLOW}This might be a network connectivity issue.${NC}"
        echo -e "\n${BLUE}Try these steps:${NC}"
        echo "1. Check if containers are on the same network:"
        echo "   docker network inspect barter-net"
        echo "2. Restart Docker daemon:"
        echo "   sudo systemctl restart docker"
        echo "3. Recreate containers:"
        echo "   docker compose down && docker compose up -d"
    fi
else
    echo -e "${RED}✗✗✗ DNS is NOT configured in running containers${NC}"
    echo -e "\n${BLUE}Follow these steps to fix:${NC}"
    echo ""
    echo "1. Ensure docker-compose.yml has DNS configuration (already checked above)"
    echo "2. Pull latest changes from git:"
    echo -e "   ${GREEN}git pull${NC}"
    echo "3. Stop and remove all containers:"
    echo -e "   ${GREEN}docker compose down${NC}"
    echo "4. Recreate containers with new configuration:"
    echo -e "   ${GREEN}docker compose up -d --build${NC}"
    echo "5. Watch the logs:"
    echo -e "   ${GREEN}docker compose logs -f${NC}"
    echo ""
    echo "The key is that containers must be RECREATED, not just restarted."
    echo "The 'docker compose down' command removes containers completely,"
    echo "and 'docker compose up' creates new ones with the DNS config."
fi

echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Diagnostic complete${NC}"
echo -e "${BLUE}========================================${NC}"
