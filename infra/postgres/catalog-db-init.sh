#!/usr/bin/env bash
# Base de datos propia de catalog-service en el mismo servidor PostgreSQL (LAB-113): rol y base dedicada sin pgvector.
# Idempotente.
# Entorno: CATALOG_DB_PASSWORD (obligatoria); PGHOST, PGUSER (superusuario) y PGPASSWORD para llegar al servidor;
# APP_DB (base del monolito).
set -euo pipefail

: "${CATALOG_DB_PASSWORD:?falta CATALOG_DB_PASSWORD}"
PGUSER="${PGUSER:-shoplab}"
APP_DB="${APP_DB:-shoplab}"
export PGUSER

psql -v ON_ERROR_STOP=1 -q -d "$APP_DB" -v password="$CATALOG_DB_PASSWORD" -v app_db="$APP_DB" <<'SQL'
SELECT 'CREATE ROLE catalog LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'catalog')
\gexec
ALTER ROLE catalog LOGIN PASSWORD :'password';
SELECT 'CREATE DATABASE catalog OWNER catalog' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'catalog')
\gexec
REVOKE CONNECT ON DATABASE catalog FROM PUBLIC;
GRANT CONNECT ON DATABASE catalog TO catalog;
SQL

echo "base 'catalog' lista"
