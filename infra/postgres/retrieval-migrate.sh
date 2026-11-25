#!/usr/bin/env bash
# Copia el esquema `retrieval` de la base del monolito a la base `retrieval` (LAB-129). Corre DENTRO del contenedor de
# PostgreSQL: úsalo con scripts/migrate-retrieval.sh o .ps1. El origen solo se lee; los vectores se copian tal cual.
# Si el destino ya tiene datos no hace nada salvo con --replace, que lo borra y vuelve a copiar. Todo en una transacción.
set -euo pipefail

SOURCE_DB="${SOURCE_DB:-${POSTGRES_DB:-shoplab}}"
TARGET_DB="${TARGET_DB:-retrieval}"
DB_USER="${POSTGRES_USER:-shoplab}"
replace="${1:-}"

query() { psql -v ON_ERROR_STOP=1 -qtA -U "$DB_USER" -d "$1" -c "$2"; }

if [ "$(query "$TARGET_DB" "SELECT to_regclass('retrieval.document') IS NOT NULL")" = t ]; then
  rows="$(query "$TARGET_DB" 'SELECT (SELECT count(*) FROM retrieval.document) + (SELECT count(*) FROM retrieval.chunk)')"
  if [ "$rows" != 0 ] && [ "$replace" != --replace ]; then
    echo "el destino ya tiene $rows filas: usa --replace solo antes del corte" >&2
    exit 3
  fi
fi

dump="$(mktemp)"
trap 'rm -f "$dump"' EXIT
pg_dump -U "$DB_USER" -d "$SOURCE_DB" --schema=retrieval --format=custom --file="$dump"

{
  echo 'DROP SCHEMA IF EXISTS retrieval CASCADE;'
  echo 'SET ROLE retrieval;'
  pg_restore --no-owner --no-privileges --file=- "$dump"
} | psql -v ON_ERROR_STOP=1 -q -U "$DB_USER" -d "$TARGET_DB" --single-transaction >/dev/null

echo "esquema retrieval copiado de '$SOURCE_DB' a '$TARGET_DB'"
