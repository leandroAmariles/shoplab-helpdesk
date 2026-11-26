#!/usr/bin/env bash
# Copia el catálogo a la base de catalog-service, contra el PostgreSQL del compose (LAB-113). Uso:
#   bash scripts/migrate-catalog.sh [--replace]
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "${BASH_SOURCE[0]}")/.."
container="${PG_CONTAINER:-$(docker compose -f infra/docker-compose.yml ps -q postgres)}"
[ -n "$container" ] || { echo "el servicio postgres del compose no está arriba" >&2; exit 1; }
tr -d '\r' <infra/postgres/catalog-migrate.sh | docker exec -i "$container" bash -s -- "$@"
