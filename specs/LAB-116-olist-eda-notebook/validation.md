# Validación: análisis exploratorio de Olist (LAB-116)

Registro de lo medido al implementar, siguiendo [quickstart.md](quickstart.md). Fecha: 2026-11-20.

## Entorno y una salvedad

- Python 3.12.13, `uv` 0.11.7, pandas 3.1.1, NumPy 2.5.5, psycopg 3.3.9, matplotlib 3.11.2, Docker 29.6.1, Windows 11.
- **Las ejecuciones se hicieron contra una copia exacta de la base local**, no contra la propia. El volumen de Postgres de
  `docker compose` se inicializó con una contraseña distinta de la que declara `infra/docker-compose.yml`, y el preflight del
  notebook lo detectó (rechazo de autenticación). En lugar de cambiar las credenciales de la base local, se creó un contenedor
  desechable (`pgvector/pgvector:0.8.7-pg16`, mismo que el compose) y se le copiaron los esquemas `orders` y `catalog` con
  `pg_dump` desde el contenedor local, solo lectura. Los recuentos de las ocho tablas coinciden con los de la base local. El
  contenedor y sus bases de prueba son efímeros y se eliminan al terminar.

## §1 Pruebas automáticas

| Medida | Resultado |
|---|---|
| `uv run ruff check .` | sin errores |
| `uv run pytest` | **184 pruebas**, todas en verde, ~4 s |
| Cobertura del paquete `olist_eda` | **99,18 %** (umbral 80 %) |
| Sin base de datos ni red | sí: DataFrames en memoria, conector falso y corpus real |

Comprobación de que las pruebas del notebook pueden fallar (mutación): al alterar a mano `delivery.analyzed_orders`, el número de
filas de `orders.orders` y una acción del JSON, fallaron `test_the_conclusions_quote_the_numbers_of_the_summary`,
`test_the_sizes_shown_in_the_notebook_match_the_summary` y `test_the_discrepancy_table_matches_the_summary…`. La primera versión
de la prueba de tamaños **no** detectaba el cambio (la cifra `5` aparecía como subcadena en otro sitio) y se endureció para exigir la
fila exacta *tabla + filas*.

## §2 Ejecución completa (dataset completo)

Filas leídas, idénticas a las de la base local (T001):

| Tabla | Filas |
|---|---|
| `orders.customers` | 99.441 |
| `orders.orders` | 99.441 |
| `orders.order_items` | 112.650 |
| `orders.order_payments` | 103.886 |
| `orders.order_reviews` | 99.224 |
| `catalog.product_categories` | 73 |
| `catalog.products` | 32.951 |
| `catalog.sellers` | 3.095 |

- Duración de `nbconvert --execute` con el dataset completo: **~6 s** (objetivo < 5 min).
- Tamaño del notebook guardado: **373 KB** (límite ~2 MB); resumen JSON: 13 KB; cuatro figuras PNG a 100 dpi.
- `data_scale` del resumen: `full`.

### Hallazgos reales (16)

| Área | Tipo | Sujeto | Cantidad | Destino |
|---|---|---|---|---|
| calidad | `unexpected_nulls` | `products.category_name` (y `name_length`, `description_length`, `photos_count`: los mismos 610 productos) | 610 | `new_task` / `analysis_note` |
| calidad | `unexpected_nulls` | `orders.approved_at` (pedidos `delivered`) | 14 | `LAB-41` |
| calidad | `unexpected_nulls` | `orders.delivered_to_carrier_at` (pedidos `delivered`) | 2 | `LAB-41` |
| calidad | `unexpected_nulls` | `products.weight_g` (y `length_cm`, `height_cm`, `width_cm`: los mismos 2 productos) | 2 | `new_task` / `analysis_note` |
| calidad | `implausible_range` | `products.weight_g` = 0 | 4 | `new_task` |
| calidad | `implausible_range` | `order_items.shipping_limit_at` en 2020 | 4 | `loader` |
| calidad | `category_without_translation` | `product_categories.name_en` | 2 | `loader` |
| valores | `value_not_in_policies` | `payment_type:not_defined` (3 pagos de importe cero en pedidos `canceled`) | 3 | `corpus` |
| entregas | `delivered_without_date` | `orders.delivered_to_customer_at` | 8 | `LAB-41` |
| entregas | `date_with_non_delivered_status` | `orders.delivered_to_customer_at` (pedidos `canceled`) | 6 | `LAB-41` |

Sin discrepancias (y el notebook lo dice): integridad entre esquemas (0 huérfanos), filas duplicadas (0), estados de pedido
frente al corpus (8 de 8 coinciden), fechas de entrega o estimada anteriores a la compra (0).

Cifras de entregas y reseñas: 96.476 de 99.441 pedidos analizados; el 91,89 % llega antes de lo estimado y el 8,11 % tarde; la
puntuación media baja de 4,29 a 1,73 según el retraso.

## §4 Preflight (FR-018)

Cuatro situaciones provocadas; en todas `nbconvert` termina con error en la **segunda celda de código** y no escribe ningún
notebook parcial:

| Situación | Mensaje |
|---|---|
| Servidor sin escuchar | «No se pudo conectar a 127.0.0.1:55999 (connection timeout expired). Arranca la base local: docker compose -f infra/docker-compose.yml up -d --wait.» |
| Contraseña incorrecta | «La base en 127.0.0.1:55432 está arriba pero rechazó el usuario o la contraseña (…). Revisa POSTGRES_USER y POSTGRES_PASSWORD…» |
| Base sin esquemas | «Faltan tablas en catalog: …; Faltan tablas en orders: …. Arranca el backend para aplicar las migraciones.» |
| Tabla vacía | «La tabla orders.order_reviews no tiene filas. Carga Olist: cd data/olist && uv run olist-loader load.» |

El caso de contraseña incorrecta se descubrió de forma real al preparar el entorno: el primer mensaje decía «Arranca la base
local», que despistaba. Se corrigió el preflight (con prueba) para distinguir un rechazo de credenciales de un servidor caído.

## §5 Solo lectura (FR-025)

Con la conexión del paquete, `INSERT`, `UPDATE`, `DELETE`, `CREATE TABLE` y `TRUNCATE` fueron rechazados por Postgres con
`cannot execute … in a read-only transaction`. Los recuentos de `orders.orders` (99.441), `catalog.sellers` (3.095) y
`orders.order_reviews` (99.224) no cambiaron.

## §6 Reproducibilidad (SC-008)

Dos ejecuciones consecutivas con los mismos datos: `olist-eda-summary.json` idéntico salvo `generated_at`, y las salidas de
texto del notebook idénticas.

## §7 CI con volumen pequeño (SC-004)

Reproducido en local con una base nueva:

1. `ml/ci/prepare-database.sh` (ejecutado tal cual, con `psql` de la imagen de Postgres) aplicó `V1` y `V2` de `orders` y de
   `catalog` en orden de versión y dejó las 8 tablas; el cargador de LAB-17 cargó las fixtures (3 pedidos).
2. `nbconvert --execute --output-dir … ` con `EDA_MIN_GROUP_ORDERS=1` terminó **sin errores**; mostró el banner «volumen
   reducido», 1 de 3 pedidos analizados, 23 hallazgos (algunos sin acción específica: resolvieron la acción por tipo), sin
   gráficos vacíos ni divisiones por cero.
3. El notebook y el resumen versionados **no cambiaron** (comparación byte a byte): el resumen no se escribe con datos de muestra
   y la copia ejecutada va a un directorio aparte.

**Pendiente de verificar en GitHub Actions** (no se puede hacer en local): que el job `ml-notebook` pase de verdad en un runner
y la comprobación negativa del quickstart §7 (un commit desechable que provoque un error en una celda debe hacer fallar
`ml-notebook` y `ci-gate`). El YAML se validó sintácticamente y sus necesidades y filtros se comprobaron por código.

## §8 Alcance

- `git diff --stat origin/main -- backend ingest data/olist data/kb frontend infra eval notebooks`: vacío.
- `grep` de `olist_eda`/`ml` en `backend` e `ingest`: sin resultados.
- CSV en el repositorio: solo las fixtures preexistentes de `data/olist/tests/fixtures`.
- Ninguna ruta local, secreto ni identificador de Olist en el notebook, su resumen, el README ni el código.

## Seguridad de dependencias (T054)

- `trivy fs ml` con el comando documentado en el README (`--scanners vuln,secret,misconfig --severity HIGH,CRITICAL
  --ignore-unfixed`): 0 vulnerabilidades en `uv.lock`, 0 secretos. `trivy fs .github/workflows/ci.yml`: sin hallazgos.
- `pip-audit` sobre las dependencias bloqueadas (`uv export`): sin vulnerabilidades conocidas.
- CI ya escanea todo el repo con Trivy, así que cubre `ml/uv.lock` sin cambios. Dependabot solo vigila `ingest` y `eval` en Python
  (tampoco `notebooks` ni `data/*`); `ml` queda igual que ellos y añadirlo sería un cambio aparte.

## Desvíos respecto al diseño

- La columna derivada `coherent` del data-model se sustituyó por `exclusion_reason` (un único motivo) y `analyzed`.
- Con pandas 3 las fechas leídas son `datetime64[us]`, no `[ns]`; las pruebas comprueban el tipo de forma genérica.
- Se añadió `AnalysisSettings.describe()`: la salida guardada filtraba una ruta local (`C:/Users/...`) al imprimir los
  parámetros, lo que FR-024 prohíbe; ahora imprime una vista sin host, contraseña ni rutas absolutas.
- Se añadió `%matplotlib inline` a la celda de parámetros: sin él las figuras creadas con la API orientada a objetos se
  guardaban como texto (`<Figure size …>`) y no como imagen.
- El resumen JSON no incluye `impact` por hallazgo (el contrato no lo pedía); sí está en la tabla de la sección 12.
