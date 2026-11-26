#!/usr/bin/env bash
# Compara los datos de chat en las dos bases, contra el PostgreSQL del compose (LAB-59). Sale con 0 si coinciden.
#   bash scripts/verify-chat-migration.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "${BASH_SOURCE[0]}")/.."
container="${PG_CONTAINER:-$(docker compose -f infra/docker-compose.yml ps -q postgres)}"
[ -n "$container" ] || { echo "el servicio postgres del compose no está arriba" >&2; exit 1; }
tr -d '\r' <infra/postgres/chat-verify.sh | docker exec -i "$container" bash -s
