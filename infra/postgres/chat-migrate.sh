#!/usr/bin/env bash
# Copia el esquema `chat` de la base del monolito a la base `chat` (LAB-59). Corre DENTRO del contenedor de
# PostgreSQL: úsalo con scripts/migrate-chat.sh o .ps1. El origen solo se lee.
# Si el destino ya tiene datos no hace nada salvo con --replace, que lo borra y vuelve a copiar. Todo en una transacción.
set -euo pipefail

SOURCE_DB="${SOURCE_DB:-${POSTGRES_DB:-shoplab}}"
TARGET_DB="${TARGET_DB:-chat}"
DB_USER="${POSTGRES_USER:-shoplab}"
replace="${1:-}"

query() { psql -v ON_ERROR_STOP=1 -qtA -U "$DB_USER" -d "$1" -c "$2"; }

if [ "$(query "$TARGET_DB" "SELECT to_regclass('chat.conversation_turn') IS NOT NULL")" = t ]; then
  rows="$(query "$TARGET_DB" 'SELECT (SELECT count(*) FROM chat.conversation_turn) + (SELECT count(*) FROM chat.feedback)')"
  if [ "$rows" != 0 ] && [ "$replace" != --replace ]; then
    echo "el destino ya tiene $rows filas: usa --replace solo antes del corte" >&2
    exit 3
  fi
fi

dump="$(mktemp)"
trap 'rm -f "$dump"' EXIT
pg_dump -U "$DB_USER" -d "$SOURCE_DB" --schema=chat --format=custom --file="$dump"

{
  echo 'DROP SCHEMA IF EXISTS chat CASCADE;'
  echo 'SET ROLE chat;'
  pg_restore --no-owner --no-privileges --file=- "$dump"
} | psql -v ON_ERROR_STOP=1 -q -U "$DB_USER" -d "$TARGET_DB" --single-transaction >/dev/null

echo "esquema chat copiado de '$SOURCE_DB' a '$TARGET_DB'"
