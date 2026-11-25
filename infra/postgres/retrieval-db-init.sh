#!/usr/bin/env bash
# Base de datos propia de retrieval-service en el mismo servidor PostgreSQL (LAB-129): rol, base y extensión pgvector.
# Idempotente. No va en init/: esos scripts solo corren sobre un volumen vacío y el nuestro ya existe.
# Entorno: RETRIEVAL_DB_PASSWORD (obligatoria); PGHOST, PGUSER (superusuario) y PGPASSWORD para llegar al servidor;
# APP_DB (base del monolito).
set -euo pipefail

: "${RETRIEVAL_DB_PASSWORD:?falta RETRIEVAL_DB_PASSWORD}"
PGUSER="${PGUSER:-shoplab}"
APP_DB="${APP_DB:-shoplab}"
export PGUSER

psql -v ON_ERROR_STOP=1 -q -d "$APP_DB" -v password="$RETRIEVAL_DB_PASSWORD" -v app_db="$APP_DB" <<'SQL'
SELECT 'CREATE ROLE retrieval LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'retrieval')
\gexec
ALTER ROLE retrieval LOGIN PASSWORD :'password';
SELECT 'CREATE DATABASE retrieval OWNER retrieval' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'retrieval')
\gexec
REVOKE CONNECT ON DATABASE retrieval FROM PUBLIC;
GRANT CONNECT ON DATABASE retrieval TO retrieval;
-- Solo entran en la base del monolito su dueño y los superusuarios.
REVOKE CONNECT ON DATABASE :"app_db" FROM PUBLIC;
SQL

# pgvector necesita un superusuario para crearse; la migración V2 del servicio la encuentra ya creada.
psql -v ON_ERROR_STOP=1 -q -d retrieval -c 'CREATE EXTENSION IF NOT EXISTS vector SCHEMA public'

echo "base 'retrieval' lista"
