#!/usr/bin/env bash
# Copia el catálogo de Olist de la base del monolito (esquema `catalog`) a la base de catalog-service (LAB-113).
# Corre dentro del contenedor de PostgreSQL. Las tablas se crean aquí solo si catalog-service aún no las creó: en ese
# caso hay que haber arrancado el servicio una vez, que es quien aplica Flyway.
# Uso: catalog-migrate.sh [--replace]    (sin --replace no toca una base que ya tiene productos)
set -euo pipefail

PGUSER="${PGUSER:-${POSTGRES_USER:-shoplab}}"
APP_DB="${APP_DB:-${POSTGRES_DB:-shoplab}}"
export PGUSER

source_sql() { psql -v ON_ERROR_STOP=1 -qtA -d "$APP_DB" -c "$1"; }
target_sql() { psql -v ON_ERROR_STOP=1 -qtA -d catalog -c "$1"; }

[ "$(target_sql "SELECT to_regclass('catalog.products') IS NOT NULL")" = "t" ] \
  || { echo "la base 'catalog' no tiene tablas: arranca catalog-service para que aplique Flyway" >&2; exit 1; }

if [ "$(target_sql 'SELECT count(*) FROM catalog.products')" != "0" ] && [ "${1:-}" != "--replace" ]; then
  echo "la base 'catalog' ya tiene productos: usa --replace para volver a copiarlos" >&2
  exit 1
fi

# Las columnas se nombran una a una: created_at y updated_at toman su valor por defecto.
declare -A columns=(
  [product_categories]="name, name_en"
  [products]="product_id, category_name, name_length, description_length, photos_count, weight_g, length_cm, height_cm, width_cm, reference_price, stock"
  [sellers]="seller_id, zip_code_prefix, city, state"
)
tables=(product_categories products sellers)

# Una sola transacción en el destino: o llega el catálogo entero o la base queda como estaba.
{
  echo "TRUNCATE catalog.products, catalog.product_categories, catalog.sellers;"
  for table in "${tables[@]}"; do
    echo "COPY catalog.$table (${columns[$table]}) FROM STDIN;"
    source_sql "COPY catalog.$table (${columns[$table]}) TO STDOUT"
    printf '%s\n' '\.'
  done
} | psql -v ON_ERROR_STOP=1 -q --single-transaction -d catalog

for table in "${tables[@]}"; do
  from="$(source_sql "SELECT count(*) FROM catalog.$table")"
  to="$(target_sql "SELECT count(*) FROM catalog.$table")"
  echo "$table: origen=$from destino=$to"
  [ "$from" = "$to" ] || { echo "las filas de $table no coinciden" >&2; exit 1; }
done
