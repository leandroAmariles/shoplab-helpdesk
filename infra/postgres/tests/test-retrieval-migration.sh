#!/usr/bin/env bash
# Prueba de la provisión y la migración de la base de retrieval (LAB-129, T036) contra un PostgreSQL desechable.
# No toca el compose ni sus volúmenes. Uso: bash infra/postgres/tests/test-retrieval-migration.sh
set -euo pipefail

export MSYS_NO_PATHCONV=1 # Git Bash en Windows: no traducir las rutas de dentro del contenedor
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS="$HERE/.."
# Copia del esquema que dejaba el módulo retrieval del monolito, retirado en LAB-129.
MIGRATIONS="$HERE/monolith-schema"
NAME="lab129-migration-test-$$"
IMAGE="pgvector/pgvector:0.8.7-pg16"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

fail() { echo "FALLO: $*" >&2; exit 1; }
sql() { docker exec -i "$NAME" psql -v ON_ERROR_STOP=1 -qtA -U shoplab -d "$1"; }
# Los scripts se envían por la entrada estándar, como hacen scripts/migrate-retrieval.* contra el compose.
run() { local script="$1"; shift; tr -d '\r' <"$SCRIPTS/$script" | docker exec -i "$NAME" bash -s -- "$@"; }

docker run -d --name "$NAME" -e POSTGRES_USER=shoplab -e POSTGRES_PASSWORD=test-only -e POSTGRES_DB=shoplab \
  "$IMAGE" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$NAME" pg_isready -h 127.0.0.1 -U shoplab -q && break
  sleep 1
done

# --- origen: el esquema tal como lo deja el monolito -----------------------------------------------------------------
{
  echo 'CREATE SCHEMA retrieval; SET search_path = retrieval;'
  echo 'CREATE TABLE flyway_schema_history (installed_rank int PRIMARY KEY, version text, checksum int);'
  rank=0
  for file in "$MIGRATIONS"/V[1-4]__*.sql; do
    rank=$((rank + 1))
    tr -d '\r' <"$file"
    echo "INSERT INTO flyway_schema_history VALUES ($rank, '$rank', $rank);"
  done
  cat <<'SQL'
INSERT INTO document (id, source, title, index_status, indexed_at)
VALUES ('11111111-1111-1111-1111-111111111111', 'returns.md', 'Devoluciones', 'INDEXED', now());
INSERT INTO chunk (document_id, chunk_index, content, embedding, embedding_model)
SELECT '11111111-1111-1111-1111-111111111111', n, 'fragmento ' || n,
       ('[' || array_to_string(array_fill((n + 1) / 7.0, ARRAY[1024]), ',') || ']')::public.vector, 'bge-m3'
FROM generate_series(0, 2) AS n;
SQL
} | sql shoplab

snapshot() { sql "$1" <<'SQL'
SELECT md5(string_agg(md5(t::text), '' ORDER BY t.id)) FROM retrieval.chunk t;
SQL
}
before="$(snapshot shoplab)"

echo "== provisión, dos veces"
for _ in 1 2; do
  docker exec -i -e RETRIEVAL_DB_PASSWORD='p4ss-de-prueba' -e PGUSER=shoplab -e APP_DB=shoplab "$NAME" \
    bash -s < <(tr -d '\r' <"$SCRIPTS/retrieval-db-init.sh") >/dev/null
done
docker exec -e PGPASSWORD='p4ss-de-prueba' "$NAME" psql -h 127.0.0.1 -U retrieval -d retrieval -qtAc 'select 1' \
  | grep -qx 1 || fail "el rol retrieval no entra en su base"
if docker exec -e PGPASSWORD='p4ss-de-prueba' "$NAME" psql -h 127.0.0.1 -U retrieval -d shoplab -qtAc 'select 1' \
  >/dev/null 2>&1; then
  fail "el rol retrieval puede entrar en la base shoplab"
fi

echo "== verificación antes de migrar"
run retrieval-verify.sh >/dev/null 2>&1 && fail "la verificación pasa sin haber migrado"

echo "== migración"
run retrieval-migrate.sh >/dev/null
run retrieval-verify.sh >/dev/null || fail "la verificación falla tras migrar"
[ "$(snapshot retrieval)" = "$before" ] || fail "los fragmentos copiados no son idénticos"
owner="$(sql retrieval <<'SQL'
SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'retrieval';
SQL
)"
[ "$owner" = retrieval ] || fail "las tablas no pertenecen al rol retrieval: $owner"
# La migración V2 vuelve a pedir la extensión como rol sin privilegios: debe ser inocuo.
docker exec -e PGPASSWORD='p4ss-de-prueba' "$NAME" psql -h 127.0.0.1 -U retrieval -d retrieval -v ON_ERROR_STOP=1 -qc \
  'CREATE EXTENSION IF NOT EXISTS vector SCHEMA public' >/dev/null 2>&1 || fail "el rol no puede repetir V2"

echo "== segunda migración sin --replace"
run retrieval-migrate.sh >/dev/null 2>&1 && fail "sobrescribe sin --replace"

echo "== una fila alterada en el destino"
sql retrieval <<'SQL'
UPDATE retrieval.chunk SET content = 'otro texto' WHERE chunk_index = 1;
SQL
run retrieval-verify.sh >/dev/null 2>&1 && fail "la verificación no detecta una fila alterada"

echo "== repetir con --replace"
run retrieval-migrate.sh --replace >/dev/null
run retrieval-verify.sh >/dev/null || fail "la verificación falla tras --replace"
[ "$(sql retrieval <<<'SELECT count(*) FROM retrieval.chunk')" = 3 ] || fail "se duplicaron filas"
[ "$(snapshot shoplab)" = "$before" ] || fail "el origen cambió"

echo "== el servicio añade su propia migración: la verificación la ignora"
sql retrieval <<'SQL'
INSERT INTO retrieval.flyway_schema_history VALUES (5, '5', 5);
SQL
run retrieval-verify.sh >/dev/null || fail "una migración posterior del servicio hace fallar la verificación"

echo "TODO CORRECTO"
