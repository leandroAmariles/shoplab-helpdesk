#!/usr/bin/env bash
# Copia los datos de eval a su base propia, contra el PostgreSQL del compose (LAB-130). Uso:
#   bash scripts/migrate-eval.sh [--replace]
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "${BASH_SOURCE[0]}")/.."
container="${PG_CONTAINER:-$(docker compose -f infra/docker-compose.yml ps -q postgres)}"
[ -n "$container" ] || { echo "el servicio postgres del compose no está arriba" >&2; exit 1; }
tr -d '\r' <infra/postgres/eval-migrate.sh | docker exec -i "$container" bash -s -- "$@"
