#!/bin/bash
set -e

# This script replaces the default PostGIS initialization from the base image
# to fix database name issues

# Perform all actions as $POSTGRES_USER
export PGUSER="$POSTGRES_USER"

# Extract just the database name from POSTGRES_DB (in case it's a JDBC URL)
# This handles the case where POSTGRES_DB might be set to a JDBC URL
DB_NAME=$(echo "$POSTGRES_DB" | sed 's#^jdbc:postgresql://[^/]*/##' | sed 's#?.*##')

echo "====================================================================="
echo "PostGIS Initialization Script"
echo "Target database: ${DB_NAME}"
echo "====================================================================="

# Create the 'template_postgis' template database with PostGIS
echo "Step 1: Creating template_postgis database..."
psql --dbname="postgres" <<-'EOSQL'
    CREATE DATABASE template_postgis IS_TEMPLATE true;
EOSQL
echo "✓ template_postgis created"

# Load PostGIS extensions into template_postgis
echo "Step 2: Loading PostGIS extensions into template_postgis..."
psql --dbname="template_postgis" <<-'EOSQL'
    CREATE EXTENSION IF NOT EXISTS postgis;
    CREATE EXTENSION IF NOT EXISTS postgis_topology;
    CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;
    CREATE EXTENSION IF NOT EXISTS postgis_tiger_geocoder;
EOSQL
echo "✓ PostGIS extensions loaded into template_postgis"

# Now create the target database from the PostGIS template
echo "Step 3: Creating target database '${DB_NAME}' from template_postgis..."
psql --dbname="postgres" <<-EOSQL
    -- Drop if it exists (from base image's attempt)
    DROP DATABASE IF EXISTS "${DB_NAME}";
    -- Create from our PostGIS template
    CREATE DATABASE "${DB_NAME}" TEMPLATE template_postgis;
EOSQL
echo "✓ Database '${DB_NAME}' created with PostGIS extensions"

# Verify PostGIS is working
echo "Step 4: Verifying PostGIS installation..."
POSTGIS_VERSION=$(psql --dbname="${DB_NAME}" -t -c "SELECT PostGIS_Version();" | head -1 | xargs)
echo "✓ PostGIS version: ${POSTGIS_VERSION}"

echo "====================================================================="
echo "PostGIS initialization complete!"
echo "Database: ${DB_NAME}"
echo "====================================================================="
