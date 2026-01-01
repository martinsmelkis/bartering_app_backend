#!/bin/bash

# Force Clean Database Volume Script
# This script forcefully removes the PostgreSQL database volume

set -e

echo "=================================================="
echo "  Force Clean PostgreSQL Database Volume"
echo "=================================================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Change to the directory containing docker-compose.yml
cd "$(dirname "$0")"

echo -e "${RED}WARNING: This will DELETE ALL PostgreSQL data!${NC}"
echo ""
read -p "Are you absolutely sure? Type 'DELETE' to confirm: " -r
echo ""
if [[ ! $REPLY == "DELETE" ]]; then
    echo "Operation cancelled."
    exit 1
fi

echo -e "${YELLOW}Step 1: Stopping all containers...${NC}"
docker compose -f docker-compose.yml down
sleep 2
echo -e "${GREEN}✅ Containers stopped${NC}"
echo ""

echo -e "${YELLOW}Step 2: Finding and removing PostgreSQL volumes...${NC}"

# Find all postgres-related volumes
VOLUME_NAMES=$(docker volume ls --format "{{.Name}}" | grep -E "postgres" || true)

if [ -z "$VOLUME_NAMES" ]; then
    echo -e "${YELLOW}No PostgreSQL volumes found. Listing all volumes:${NC}"
    docker volume ls
    echo ""
else
    echo "Found PostgreSQL volumes:"
    echo "$VOLUME_NAMES"
    echo ""
    
    echo -e "${YELLOW}Step 3: Removing volumes...${NC}"
    for VOLUME_NAME in $VOLUME_NAMES; do
        echo "Removing volume: ${VOLUME_NAME}"
        docker volume rm "$VOLUME_NAME" 2>/dev/null || {
            echo -e "${RED}Failed to remove volume ${VOLUME_NAME}. Trying with force...${NC}"
            
            # Remove any containers using this volume
            CONTAINERS=$(docker ps -a --filter volume="$VOLUME_NAME" -q)
            if [ -n "$CONTAINERS" ]; then
                echo "Removing containers using this volume..."
                echo "$CONTAINERS" | xargs docker rm -f
                sleep 2
            fi
            
            # Try removing volume again
            docker volume rm "$VOLUME_NAME" 2>/dev/null || {
                echo -e "${RED}Still cannot remove ${VOLUME_NAME}${NC}"
            }
        }
        echo -e "${GREEN}✅ Volume removed: ${VOLUME_NAME}${NC}"
    done
fi
echo ""

echo -e "${YELLOW}Step 4: Cleaning up any orphaned volumes...${NC}"
docker volume prune -f
echo -e "${GREEN}✅ Orphaned volumes cleaned${NC}"
echo ""

echo -e "${YELLOW}Step 5: Removing PostgreSQL container completely...${NC}"
docker rm -f postgresql_server 2>/dev/null || true
echo -e "${GREEN}✅ PostgreSQL container removed${NC}"
echo ""

echo -e "${YELLOW}Step 6: Current volume list:${NC}"
docker volume ls
echo ""

echo "=================================================="
echo -e "${GREEN}✅ Database volume cleaned successfully!${NC}"
echo "=================================================="
echo ""
echo "Next steps:"
echo "  1. Run: docker compose -f docker-compose.yml up -d --build"
echo "  2. Watch logs: docker compose -f docker-compose.yml logs -f"
echo ""
