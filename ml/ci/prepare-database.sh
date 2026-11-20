#!/usr/bin/env bash
# Prepares an EMPTY Postgres so the notebook can run in CI (LAB-116):
#   1. creates the orders and catalog schemas and applies the real Flyway scripts of the backend, in version order
#      (the same logic as data/olist/tests/conftest.py);
#   2. loads the small fixtures of the Olist loader (LAB-17) with the loader itself.
# The full Olist dataset is CC BY-NC-SA and is never downloaded in CI: the notebook only has to run with few rows.
#
# Reads the standard libpq variables: PGHOST, PGPORT, PGUSER, PGPASSWORD and PGDATABASE.
set -euo pipefail

for variable in PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE; do
  if [ -z "${!variable:-}" ]; then
    echo "Falta la variable $variable (la base de datos vacía en la que preparar los esquemas)." >&2
    exit 1
  fi
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
migrations="$repo_root/backend/src/main/resources/db/migration"

for schema in orders catalog; do
  psql -v ON_ERROR_STOP=1 -q -c "CREATE SCHEMA IF NOT EXISTS \"$schema\""
  # Flyway applies V<n>__*.sql in numeric order; `sort -V` gives V2 before V10.
  while IFS= read -r script; do
    echo "Aplicando ${script#"$repo_root"/}"
    PGOPTIONS="-c search_path=$schema" psql -v ON_ERROR_STOP=1 -q -f "$script"
  done < <(find "$migrations/$schema" -maxdepth 1 -name 'V*__*.sql' | sort -V)
done

echo "Cargando las fixtures de LAB-17 con olist-loader"
cd "$repo_root/data/olist"
POSTGRES_URL="postgresql://$PGHOST:$PGPORT/$PGDATABASE" \
POSTGRES_USER="$PGUSER" \
POSTGRES_PASSWORD="$PGPASSWORD" \
  uv run olist-loader load --source "$repo_root/data/olist/tests/fixtures"
