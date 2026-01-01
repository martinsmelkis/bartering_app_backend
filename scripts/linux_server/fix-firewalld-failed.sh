#!/bin/bash
# Fix firewalld FAILED state on AlmaLinux 10

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Fixing Firewalld FAILED State${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Step 1: Stop firewalld
echo -e "${YELLOW}[1] Stopping firewalld...${NC}"
sudo systemctl stop firewalld 2>/dev/null || true
sleep 2
echo -e "${GREEN}✓ Firewalld stopped${NC}"
echo ""

# Step 2: Check offline config for errors
echo -e "${YELLOW}[2] Checking firewalld configuration...${NC}"
if sudo firewall-offline-cmd --check-config 2>&1 | tee /tmp/firewall-check.log | grep -i error; then
    echo -e "${RED}✗ Configuration errors found${NC}"
    echo ""
    echo -e "${YELLOW}Backing up and resetting firewalld configuration...${NC}"
    
    # Backup existing config
    sudo cp -r /etc/firewalld /etc/firewalld.backup.$(date +%Y%m%d_%H%M%S) 2>/dev/null || true
    
    # Remove problematic configurations
    sudo rm -rf /etc/firewalld/zones/*.xml.old 2>/dev/null || true
    sudo rm -rf /etc/firewalld/zones/*~ 2>/dev/null || true
    sudo rm -rf /etc/firewalld/direct.xml 2>/dev/null || true
    sudo rm -rf /etc/firewalld/direct.xml.old 2>/dev/null || true
    
    echo -e "${GREEN}✓ Cleaned up configuration files${NC}"
else
    echo -e "${GREEN}✓ Configuration check passed${NC}"
fi
echo ""

# Step 3: Reset to default zones if needed
echo -e "${YELLOW}[3] Resetting firewalld zones to defaults...${NC}"

# Create a clean docker zone configuration
sudo tee /etc/firewalld/zones/docker.xml > /dev/null <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<zone target="ACCEPT">
  <short>docker</short>
  <description>Docker zone for container networking</description>
  <masquerade/>
</zone>
EOF

# Ensure public zone has masquerading
sudo firewall-offline-cmd --zone=public --add-masquerade 2>/dev/null || true

echo -e "${GREEN}✓ Zones configured${NC}"
echo ""

# Step 4: Remove any problematic direct rules
echo -e "${YELLOW}[4] Cleaning direct rules...${NC}"
sudo rm -f /etc/firewalld/direct.xml 2>/dev/null || true
echo -e "${GREEN}✓ Direct rules cleaned${NC}"
echo ""

# Step 5: Verify configuration again
echo -e "${YELLOW}[5] Verifying configuration...${NC}"
if sudo firewall-offline-cmd --check-config; then
    echo -e "${GREEN}✓ Configuration is valid${NC}"
else
    echo -e "${RED}✗ Configuration still has errors${NC}"
    echo -e "${YELLOW}Performing full reset...${NC}"
    
    # Full reset - restore default firewalld config
    sudo rm -rf /etc/firewalld/zones/*
    sudo rm -rf /etc/firewalld/services/*
    sudo rm -rf /etc/firewalld/icmptypes/*
    sudo rm -rf /etc/firewalld/direct.xml
    
    # Reinstall firewalld to get clean config
    sudo dnf reinstall -y firewalld 2>/dev/null || true
    
    echo -e "${GREEN}✓ Firewalld reset to defaults${NC}"
fi
echo ""

# Step 6: Start firewalld
echo -e "${YELLOW}[6] Starting firewalld...${NC}"
sudo systemctl start firewalld
sleep 3

if sudo systemctl is-active --quiet firewalld; then
    echo -e "${GREEN}✓ Firewalld started successfully${NC}"
else
    echo -e "${RED}✗ Firewalld failed to start${NC}"
    sudo journalctl -xeu firewalld.service --no-pager | tail -30
    exit 1
fi
echo ""

# Step 7: Configure firewalld for Docker
echo -e "${YELLOW}[7] Configuring firewalld for Docker...${NC}"

# Ensure docker zone exists
if ! sudo firewall-cmd --get-zones | grep -q docker; then
    sudo firewall-cmd --permanent --new-zone=docker
fi

# Configure docker zone
sudo firewall-cmd --permanent --zone=docker --set-target=ACCEPT
sudo firewall-cmd --permanent --zone=docker --add-masquerade

# Configure public zone
sudo firewall-cmd --permanent --zone=public --add-masquerade

# Allow necessary ports
sudo firewall-cmd --permanent --zone=public --add-port=22/tcp     # SSH
sudo firewall-cmd --permanent --zone=public --add-port=80/tcp     # HTTP
sudo firewall-cmd --permanent --zone=public --add-port=443/tcp    # HTTPS
sudo firewall-cmd --permanent --zone=public --add-port=8081/tcp   # Application

# Reload firewall
sudo firewall-cmd --reload

echo -e "${GREEN}✓ Firewalld configured for Docker${NC}"
echo ""

# Step 8: Display current configuration
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Current Firewall Configuration${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

echo -e "${YELLOW}Firewalld status:${NC}"
sudo systemctl status firewalld --no-pager | head -10
echo ""

echo -e "${YELLOW}Active zones:${NC}"
sudo firewall-cmd --get-active-zones
echo ""

echo -e "${YELLOW}Docker zone:${NC}"
sudo firewall-cmd --zone=docker --list-all
echo ""

echo -e "${YELLOW}Public zone:${NC}"
sudo firewall-cmd --zone=public --list-all
echo ""

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  SUCCESS!${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "${GREEN}Firewalld is now running properly${NC}"
echo ""
echo -e "${YELLOW}Next step: Fix Docker with the corrected firewall${NC}"
echo "Run: sudo bash fix-docker-iptables.sh"
echo ""
