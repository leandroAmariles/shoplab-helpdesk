#!/usr/bin/env bash
# Base de datos propia de eval-service en el mismo servidor PostgreSQL (LAB-130): rol y base dedicada sin pgvector.
# Idempotente. No va en init/: esos scripts solo corren sobre un volumen vacío y el nuestro ya existe.
# Entorno: EVAL_DB_PASSWORD (obligatoria); PGHOST, PGUSER (superusuario) y PGPASSWORD para llegar al servidor;
# APP_DB (base del monolito).
set -euo pipefail

: "${EVAL_DB_PASSWORD:?falta EVAL_DB_PASSWORD}"
PGUSER="${PGUSER:-shoplab}"
APP_DB="${APP_DB:-shoplab}"
export PGUSER

psql -v ON_ERROR_STOP=1 -q -d "$APP_DB" -v password="$EVAL_DB_PASSWORD" -v app_db="$APP_DB" <<'SQL'
SELECT 'CREATE ROLE eval LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'eval')
\gexec
ALTER ROLE eval LOGIN PASSWORD :'password';
SELECT 'CREATE DATABASE eval OWNER eval' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'eval')
\gexec
REVOKE CONNECT ON DATABASE eval FROM PUBLIC;
GRANT CONNECT ON DATABASE eval TO eval;
-- Solo entran en la base del monolito su dueño y los superusuarios.
REVOKE CONNECT ON DATABASE :"app_db" FROM PUBLIC;
SQL

echo "base 'eval' lista"
