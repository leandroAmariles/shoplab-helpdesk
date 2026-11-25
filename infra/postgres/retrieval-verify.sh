#!/usr/bin/env bash
# Compara el esquema `retrieval` de la base del monolito con el de la base `retrieval` (LAB-129). Corre DENTRO del
# contenedor de PostgreSQL: úsalo con scripts/verify-retrieval-migration.sh o .ps1. Solo lee.
# Sale con 0 únicamente si todo coincide.
set -euo pipefail

SOURCE_DB="${SOURCE_DB:-${POSTGRES_DB:-shoplab}}"
TARGET_DB="${TARGET_DB:-retrieval}"
DB_USER="${POSTGRES_USER:-shoplab}"
status=0

query() { psql -v ON_ERROR_STOP=1 -qtA -U "$DB_USER" -d "$1" -c "$2"; }

compare() { # etiqueta consulta
  local source target
  source="$(query "$SOURCE_DB" "$2")"
  target="$(query "$TARGET_DB" "$2")" || target="(no se pudo leer)"
  if [ "$source" = "$target" ]; then
    printf 'IGUAL     %-28s %s\n' "$1" "${source//$'\n'/; }"
  else
    printf 'DISTINTO  %-28s origen=%s destino=%s\n' "$1" "${source//$'\n'/; }" "${target//$'\n'/; }"
    status=1
  fi
}

# Filas y suma de comprobación de las filas completas, ordenadas por clave.
rows() { echo "SELECT count(*) || ' filas, ' || coalesce(md5(string_agg(md5(t::text), '' ORDER BY t.$2)), '-') FROM $1 t"; }

compare "document" "$(rows retrieval.document id)"
compare "chunk" "$(rows retrieval.chunk id)"
compare "vectores por modelo" \
  "SELECT embedding_model || ': ' || count(embedding) FROM retrieval.chunk WHERE embedding IS NOT NULL
   GROUP BY embedding_model ORDER BY embedding_model"

# El servicio añade sus propias migraciones después: se comparan las que existen en el origen.
last="$(query "$SOURCE_DB" 'SELECT max(installed_rank) FROM retrieval.flyway_schema_history')"
compare "flyway_schema_history" \
  "$(rows "(SELECT * FROM retrieval.flyway_schema_history WHERE installed_rank <= $last)" installed_rank)"

[ "$status" -eq 0 ] && echo "las dos bases coinciden" || echo "HAY DIFERENCIAS" >&2
exit "$status"
