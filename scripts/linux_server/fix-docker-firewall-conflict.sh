#!/bin/bash
# Fix Docker firewall zone conflict on AlmaLinux 10

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Fixing Docker Firewall Conflict${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Step 1: Stop Docker if running
echo -e "${YELLOW}[1] Stopping Docker...${NC}"
sudo systemctl stop docker 2>/dev/null || true
sudo systemctl stop docker.socket 2>/dev/null || true
sleep 2
echo -e "${GREEN}✓ Docker stopped${NC}"
echo ""

# Step 2: Remove docker0 from trusted zone
echo -e "${YELLOW}[2] Removing docker0 from trusted zone...${NC}"
sudo firewall-cmd --permanent --zone=trusted --remove-interface=docker0 2>/dev/null || true
sudo firewall-cmd --zone=trusted --remove-interface=docker0 2>/dev/null || true
echo -e "${GREEN}✓ Removed docker0 from trusted zone${NC}"
echo ""

# Step 3: Ensure docker zone exists
echo -e "${YELLOW}[3] Ensuring docker zone exists...${NC}"
if ! sudo firewall-cmd --get-zones | grep -q docker; then
    echo -e "${BLUE}Creating docker zone...${NC}"
    sudo firewall-cmd --permanent --new-zone=docker
    echo -e "${GREEN}✓ Docker zone created${NC}"
else
    echo -e "${GREEN}✓ Docker zone already exists${NC}"
fi
echo ""

# Step 4: Configure docker zone properly
echo -e "${YELLOW}[4] Configuring docker zone...${NC}"

# Set target to ACCEPT for docker zone
sudo firewall-cmd --permanent --zone=docker --set-target=ACCEPT

# Add common services to docker zone
sudo firewall-cmd --permanent --zone=docker --add-masquerade

echo -e "${GREEN}✓ Docker zone configured${NC}"
echo ""

# Step 5: Configure public zone for Docker
echo -e "${YELLOW}[5] Configuring public zone...${NC}"
sudo firewall-cmd --permanent --zone=public --add-masquerade
echo -e "${GREEN}✓ Public zone configured${NC}"
echo ""

# Step 6: Reload firewall
echo -e "${YELLOW}[6] Reloading firewall...${NC}"
sudo firewall-cmd --reload
echo -e "${GREEN}✓ Firewall reloaded${NC}"
echo ""

# Step 7: Clean up Docker network state
echo -e "${YELLOW}[7] Cleaning up Docker network state...${NC}"
sudo rm -rf /var/lib/docker/network 2>/dev/null || true
echo -e "${GREEN}✓ Network state cleaned${NC}"
echo ""

# Step 9: Start Docker
echo -e "${YELLOW}[9] Starting Docker...${NC}"
sudo systemctl start docker
sleep 5

# Check if Docker started successfully
if sudo systemctl is-active --quiet docker; then
    echo -e "${GREEN}✓ Docker started successfully${NC}"
else
    echo -e "${RED}✗ Docker failed to start${NC}"
    echo ""
    echo -e "${YELLOW}Checking Docker logs...${NC}"
    sudo journalctl -xeu docker.service --no-pager | tail -30
    exit 1
fi
echo ""

# Step 10: Verify firewall configuration
echo -e "${YELLOW}[10] Verifying firewall configuration...${NC}"
echo ""
echo -e "${BLUE}Docker zone:${NC}"
sudo firewall-cmd --zone=docker --list-all
echo ""
echo -e "${BLUE}Public zone:${NC}"
sudo firewall-cmd --zone=public --list-all
echo ""

# Step 11: Test Docker
echo -e "${YELLOW}[11] Testing Docker...${NC}"
if sudo docker run --rm hello-world > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Docker is working${NC}"
else
    echo -e "${RED}✗ Docker test failed${NC}"
fi
echo ""

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Firewall Conflict Fixed!${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "${GREEN}Docker is now running with proper firewall configuration${NC}"
echo ""
echo -e "${YELLOW}Next steps:${NC}"
echo "1. Go to your project:"
echo "   cd /opt/barter-app"
echo ""
echo "2. Pull latest changes:"
echo "   git pull"
echo ""
echo "3. Start your application:"
echo "   docker compose up -d --build"
echo ""
echo "4. Watch the logs:"
echo "   docker compose logs -f"
echo ""
