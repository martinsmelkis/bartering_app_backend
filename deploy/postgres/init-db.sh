#!/bin/bash
set -e

echo "====================================================================="
echo "Database Verification Script"
echo "====================================================================="

# Extract just the database name from POSTGRES_DB (in case it's a JDBC URL)
DB_NAME=$(echo "$POSTGRES_DB" | sed 's#^jdbc:postgresql://[^/]*/##' | sed 's#?.*##')

echo "Target database: ${DB_NAME}"
echo ""

# The database should already be created by 10_postgis.sh from template_postgis
# This script verifies it's ready

echo "Step 1: Checking if database exists..."
if psql -U "$POSTGRES_USER" -lqt | cut -d \| -f 1 | grep -qw "$DB_NAME"; then
    echo "✓ Database '${DB_NAME}' exists"
else
    echo "✗ ERROR: Database '${DB_NAME}' was not created!"
    echo ""
    echo "Available databases:"
    psql -U "$POSTGRES_USER" -l
    exit 1
fi

echo "Step 2: Verifying PostGIS installation..."
if psql -U "$POSTGRES_USER" -d "$DB_NAME" -c "SELECT PostGIS_Version();" >/dev/null 2>&1; then
    POSTGIS_VERSION=$(psql -U "$POSTGRES_USER" -d "$DB_NAME" -t -c "SELECT PostGIS_Version();" | head -1 | xargs)
    echo "✓ PostGIS is installed: ${POSTGIS_VERSION}"
else
    echo "✗ ERROR: PostGIS not found in '${DB_NAME}'"
    exit 1
fi

echo "Step 3: Checking database connection..."
if psql -U "$POSTGRES_USER" -d "$DB_NAME" -c "SELECT 1;" >/dev/null 2>&1; then
    echo "✓ Database connection successful"
else
    echo "✗ ERROR: Cannot connect to database '${DB_NAME}'"
    exit 1
fi

echo ""
echo "====================================================================="
echo "✓ Database '${DB_NAME}' is ready for use!"
echo "====================================================================="
