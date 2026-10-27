# Quickstart: validar la carga de Olist

Guía para comprobar de punta a punta que la historia funciona. Detalle de tablas en [data-model.md](data-model.md); CLI
en [contracts/loader-cli.md](contracts/loader-cli.md); reglas del DDL en [contracts/schema-ddl.md](contracts/schema-ddl.md).

## Prerrequisitos

- Docker, JDK 21, `uv`, y la rama de LAB-105 incorporada (esquemas y migrador por módulo).
- Cuenta de Kaggle con `KAGGLE_USERNAME` y `KAGGLE_KEY` en el entorno (solo para la primera descarga), o un ZIP del
  dataset ya descargado para usar con `--source`.

## 1. Levantar la infraestructura local y crear las tablas

```bash
docker compose -f infra/docker-compose.yml up -d --wait
cd backend && mvn spring-boot:run        # el arranque aplica las migraciones de cada módulo
```

Resultado esperado: el log muestra `Schema 'orders' ready` y `Schema 'catalog' ready`, y las tablas de
[data-model.md](data-model.md) existen (vacías).

## 2. Cargar el dataset

```bash
cd data/olist
uv sync
uv run olist-loader load            # o: uv run olist-loader load --source <ruta-al-zip>
```

Resultado esperado: salida con el resumen por esquema y tabla, 0 referencias huérfanas, código de salida 0 y un tiempo
total inferior a 10 minutos (SC-001).

## 3. Escenarios de validación

| # | Escenario | Cómo se comprueba | Resultado esperado |
|---|---|---|---|
| V1 | Conteos (historia 1, SC-002) | `select count(*)` de cada tabla frente al resumen del cargador | Coinciden; filas descartadas listadas con motivo |
| V2 | Pedido completo solo en `orders` (SC-005) | Consultar un `order_id` uniendo `customers`, `order_items`, `order_payments`, `order_reviews` | Devuelve cliente, ítems, pagos y reseña sin tocar `catalog` |
| V3 | Producto completo solo en `catalog` (SC-005) | Consultar un `product_id` con su categoría | Categoría, precio de referencia y stock presentes |
| V4 | Sin FKs entre esquemas (SC-003) | Consulta S5 de [schema-ddl.md](contracts/schema-ddl.md) | 0 filas |
| V5 | Ítem sin producto en `catalog` (historia 2.3) | Pruebas del cargador con CSV de ejemplo | Se carga y se cuenta como huérfano |
| V6 | Idempotencia (SC-004) | Ejecutar `load` dos veces | Mismos conteos, 0 duplicados |
| V7 | Atomicidad (SC-004) | Interrumpir la carga a mitad y repetirla | Estado final completo; sin datos parciales |
| V8 | Base sin migrar (FR-010) | Ejecutar `load` contra una base sin tablas | Código de salida 4 con el mensaje de arrancar el backend |
| V9 | Stock determinista (FR-015) | Comparar `stock` de dos cargas | Idéntico por producto; hay productos con 0 |
| V10 | Reutiliza descarga (FR-009) | Segunda ejecución sin red | No descarga; usa `data/raw/` |

## 4. Pruebas automáticas

```bash
cd data/olist && uv run ruff check . && uv run pytest        # cargador, con Postgres desechable
cd backend && mvn test -Dtest='ModuleSchemas*,SchemaIsolation*' -Dskip.srcclr=true --no-transfer-progress
```

## 5. Verificaciones pendientes de la primera ejecución real

Datos que el plan da por supuestos y deben contrastarse con la descarga real:

- Los nombres de columna y los conteos de la [tabla de origen](research.md) (incluida la repetición de `review_id`).
- El endpoint de descarga de Kaggle y su autenticación (research D-3); si no sirve, cambiar `download.py` a
  `kagglehub` sin tocar el resto.
- Que 0 ítems queden huérfanos respecto de `products` y `sellers` con el dataset completo.
