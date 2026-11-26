#!/usr/bin/env bash
# Base de datos propia de chat-service en el mismo servidor PostgreSQL (LAB-59): rol y base dedicada sin pgvector.
# Idempotente. No va en init/: esos scripts solo corren sobre un volumen vacío y el nuestro ya existe.
# Entorno: CHAT_DB_PASSWORD (obligatoria); PGHOST, PGUSER (superusuario) y PGPASSWORD para llegar al servidor;
# APP_DB (base del monolito).
set -euo pipefail

: "${CHAT_DB_PASSWORD:?falta CHAT_DB_PASSWORD}"
PGUSER="${PGUSER:-shoplab}"
APP_DB="${APP_DB:-shoplab}"
export PGUSER

psql -v ON_ERROR_STOP=1 -q -d "$APP_DB" -v password="$CHAT_DB_PASSWORD" -v app_db="$APP_DB" <<'SQL'
SELECT 'CREATE ROLE chat LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'chat')
\gexec
ALTER ROLE chat LOGIN PASSWORD :'password';
SELECT 'CREATE DATABASE chat OWNER chat' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'chat')
\gexec
REVOKE CONNECT ON DATABASE chat FROM PUBLIC;
GRANT CONNECT ON DATABASE chat TO chat;
-- Solo entran en la base del monolito su dueño y los superusuarios.
REVOKE CONNECT ON DATABASE :"app_db" FROM PUBLIC;
SQL

echo "base 'chat' lista"
