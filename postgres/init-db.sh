#!/bin/bash
set -e

echo "=== Running database initialization script ==="

# Check if database already exists
if psql -U "$POSTGRES_USER" -lqt | cut -d \| -f 1 | grep -qw mainDatabase; then
    echo "Database 'mainDatabase' already exists, skipping creation."
else
    echo "Creating database 'mainDatabase'..."
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "postgres" <<-EOSQL
        CREATE DATABASE "mainDatabase";
EOSQL
    echo "Database 'mainDatabase' created successfully."
fi

echo "=== Database initialization complete ==="
