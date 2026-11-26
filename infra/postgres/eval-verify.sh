#!/usr/bin/env bash
# Compara el esquema `eval` de la base del monolito con el de la base `eval` (LAB-130), incluidas las secuencias
# GENERATED ALWAYS AS IDENTITY. Corre DENTRO del contenedor de PostgreSQL: úsalo con scripts/verify-eval-migration.sh
# o .ps1. Solo lee. Sale con 0 únicamente si todo coincide.
set -euo pipefail

SOURCE_DB="${SOURCE_DB:-${POSTGRES_DB:-shoplab}}"
TARGET_DB="${TARGET_DB:-eval}"
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

check_sequence() { # tabla
  local table="$1"
  local ok
  ok="$(query "$TARGET_DB" "
    WITH seq AS (
      SELECT last_value FROM pg_sequences
      WHERE schemaname = 'eval'
        AND sequencename = split_part(pg_get_serial_sequence('eval.$table', 'id'), '.', 2)
    ),
    mx AS (SELECT coalesce(max(id), 0) AS max_id FROM eval.$table)
    SELECT CASE
      WHEN mx.max_id = 0 THEN 'ok (tabla vacia)'
      WHEN seq.last_value >= mx.max_id THEN 'ok (last_value=' || seq.last_value || ', max_id=' || mx.max_id || ')'
      ELSE 'DESALINEADA (last_value=' || coalesce(seq.last_value::text, 'null') || ', max_id=' || mx.max_id || ')'
    END
    FROM seq, mx;
  ")"
  if [[ "$ok" == ok* ]]; then
    printf 'IGUAL     %-28s %s\n' "secuencia $table" "$ok"
  else
    printf 'DISTINTO  %-28s %s\n' "secuencia $table" "$ok"
    status=1
  fi
}

rows() { echo "SELECT count(*) || ' filas, ' || coalesce(md5(string_agg(md5(t::text), '' ORDER BY t.$2)), '-') FROM $1 t"; }

compare "eval_run" "$(rows eval.eval_run id)"
compare "eval_case" "$(rows eval.eval_case id)"
compare "eval_result" "$(rows eval.eval_result id)"

last="$(query "$SOURCE_DB" 'SELECT max(installed_rank) FROM eval.flyway_schema_history')"
compare "flyway_schema_history" \
  "$(rows "(SELECT * FROM eval.flyway_schema_history WHERE installed_rank <= $last)" installed_rank)"

check_sequence "eval_run"
check_sequence "eval_case"
check_sequence "eval_result"

[ "$status" -eq 0 ] && echo "las dos bases coinciden" || echo "HAY DIFERENCIAS" >&2
exit "$status"
