---

description: "Task list for LAB-17 - Carga del dataset Olist en esquemas por contexto"
---

# Tasks: Carga del dataset Olist en esquemas por contexto

**Input**: Design documents from `/specs/LAB-17-olist-dataset-by-context/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`loader-cli.md`, `schema-ddl.md`), quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas antes o junto con la implementación y las pruebas
negativas de las reglas ejecutables; en cada historia las pruebas van primero y deben fallar antes de implementar.

**Organization**: Tareas agrupadas por historia de usuario. Las tres historias P1 comparten prioridad, pero **US1
depende de US3** (la carga necesita las tablas): por eso la fase de US3 va primero.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US4)
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Migraciones**: `backend/src/main/resources/db/migration/<modulo>/`
- **Pruebas del backend**: `backend/src/test/java/com/shoplab/helpdesk/schema/`
- **Cargador**: `data/olist/` (código en `src/olist_loader/`, pruebas en `tests/`)
- No hay endpoints HTTP en esta historia: no se genera `requests.http`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: proyecto Python del cargador y CI

- [X] T001 Crear el proyecto `data/olist/pyproject.toml` (Python 3.12; dependencias `psycopg[binary]`, `httpx`, `typer`; grupo dev `pytest`, `testcontainers[postgres]`, `ruff` con `line-length = 121`; script `olist-loader = "olist_loader.cli:app"`) y los paquetes vacíos `data/olist/src/olist_loader/__init__.py` y `data/olist/tests/__init__.py`
- [X] T002 Generar `data/olist/uv.lock` con `uv lock` y comprobar que `uv sync --frozen && uv run ruff check .` pasa en `data/olist/` (la CI usa `uv sync --frozen`)
- [X] T003 [P] Añadir `data/olist` al job `python` de `.github/workflows/ci.yml`: nueva salida `olist` del filtro de rutas (`'data/olist/**'`) y alta de `"data/olist"` en la matriz `python_matrix`, igual que `ingest` y `eval`; no tocar `images.yml`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: piezas comunes del cargador que usan todas las historias

**⚠️ CRITICAL**: ninguna historia puede empezar hasta completar esta fase

- [X] T004 [P] Implementar `data/olist/src/olist_loader/config.py`: `Settings` leída del entorno (`POSTGRES_URL` por defecto `postgresql://localhost:5432/shoplab`, `POSTGRES_USER` por defecto `shoplab`, `POSTGRES_PASSWORD` por defecto `change-me-local-only`, `KAGGLE_USERNAME`, `KAGGLE_KEY`, carpeta de datos por defecto `data/raw`); la contraseña y la clave de Kaggle no aparecen en `repr` ni en logs
- [X] T005 [P] Implementar `data/olist/src/olist_loader/errors.py`: `DownloadError` (código de salida 3), `DatabaseNotReadyError` (4), `LoadError` (5), todas con mensaje accionable en español
- [X] T006 [P] Crear los CSV de ejemplo sintéticos en `data/olist/tests/fixtures/` con las **mismas cabeceras que Olist** (nombres de la columna «Origen» de `data-model.md`, incluidos `product_name_lenght` y `product_description_lenght`) y valores obviamente de prueba; deben cubrir: reseña con `review_id` repetido en pedidos distintos, pedido sin entregar (fechas vacías), producto sin categoría, categoría sin traducción, ítem cuyo producto no existe en productos, ítem cuyo `order_id` no existe en pedidos, pago con `order_id` inexistente
- [X] T007 Implementar `data/olist/src/olist_loader/cli.py` (typer): comando `load` con `--source`, `--data-dir`, `--dry-run`, `--skip-download` según `contracts/loader-cli.md`, y traducción de `DownloadError`/`DatabaseNotReadyError`/`LoadError` a los códigos de salida 3/4/5 (uso incorrecto = 2); el cuerpo del pipeline se conecta en T024

**Checkpoint**: Foundation ready - las historias pueden empezar

---

## Phase 3: User Story 3 - Estructura versionada y propiedad en el módulo dueño (Priority: P1)

**Goal**: las tablas de `orders` y `catalog` existen tras arrancar el backend, definidas por migraciones del módulo dueño.

**Independent Test**: arrancar contra una base vacía y comprobar que ambos esquemas tienen todas sus tablas y que `tickets`, `chat` y `retrieval` siguen solo con `V1` (quickstart §1; contrato D1–D7).

### Tests for User Story 3 ⚠️

> **NOTE: escribir primero, comprobar que FALLAN antes de implementar**

- [X] T008 [US3] Ampliar `backend/src/test/java/com/shoplab/helpdesk/schema/ModuleSchemasIntegrationTests.java`: tras migrar una base vacía existen `orders.customers`, `orders.orders`, `orders.order_items`, `orders.order_payments`, `orders.order_reviews`, `catalog.product_categories`, `catalog.products` y `catalog.sellers`; un segundo arranque no cambia `flyway_schema_history` (D7); los historiales de `tickets`, `chat` y `retrieval` siguen solo con `V1`
- [X] T009 [US3] En el mismo `ModuleSchemasIntegrationTests.java`, comprobar las FKs internas (D5) consultando `pg_constraint`: `orders.customer_id → customers`; `order_items`, `order_payments` y `order_reviews` → `orders`; `products.category_name → product_categories`; y las restricciones `CHECK` de `data-model.md` (`score` entre 1 y 5; `price`, `freight_value`, `payment_value`, `installments`, `weight_g`, `length_cm`, `height_cm`, `width_cm`, `reference_price` y `stock` ≥ 0)

### Implementation for User Story 3

- [X] T010 [P] [US3] Crear `backend/src/main/resources/db/migration/orders/V2__olist_orders_tables.sql` con las tablas de `data-model.md`, sin calificar nombres con ningún esquema (D1) y sin tocar `V1__baseline.sql`: `customers` (`customer_id` text PK; `customer_unique_id` text NOT NULL con índice; `zip_code_prefix`, `city`, `state` text), `orders` (`order_id` text PK; `customer_id` text NOT NULL FK → `customers`; `status` text NOT NULL con índice; `purchased_at` timestamp; `approved_at`, `delivered_to_carrier_at`, `delivered_to_customer_at` timestamp NULL permitido; `estimated_delivery_at` timestamp), `order_items` (PK `(order_id, item_number)`; `order_id` FK → `orders`; `product_id` text NOT NULL con índice y **sin FK**; `seller_id` text NOT NULL con índice y **sin FK**; `shipping_limit_at` timestamp; `price` numeric(10,2) NOT NULL CHECK ≥ 0; `freight_value` numeric(10,2) NOT NULL CHECK ≥ 0), `order_payments` (PK `(order_id, payment_sequence)`; `order_id` FK → `orders`; `payment_type` text NOT NULL; `installments` integer NOT NULL CHECK ≥ 0; `payment_value` numeric(10,2) NOT NULL CHECK ≥ 0), `order_reviews` (`id` bigint PK `generated always as identity`; `review_id` text NOT NULL con índice; `order_id` text NOT NULL con índice y FK → `orders`; `score` smallint NOT NULL CHECK entre 1 y 5; `comment_title`, `comment_message` text NULL permitido; `created_at` timestamp; `answered_at` timestamp NULL permitido)
- [X] T011 [P] [US3] Crear `backend/src/main/resources/db/migration/catalog/V2__olist_catalog_tables.sql` con las tablas de `data-model.md`, sin calificar nombres con ningún esquema (D1): `product_categories` (`name` text PK; `name_en` text NULL permitido), `products` (`product_id` text PK; `category_name` text NULL permitido FK → `product_categories`; `name_length`, `description_length`, `photos_count` integer NULL permitido; `weight_g`, `length_cm`, `height_cm`, `width_cm` integer NULL permitido CHECK ≥ 0; `reference_price` numeric(10,2) NULL permitido CHECK ≥ 0; `stock` integer NOT NULL CHECK ≥ 0), `sellers` (`seller_id` text PK; `zip_code_prefix`, `city`, `state` text)
- [X] T012 [US3] Crear `data/olist/tests/conftest.py`: una Postgres desechable por sesión (`pgvector/pgvector:0.8.7-pg16`, la misma imagen que `infra/docker-compose.yml`) y una fixture que crea los esquemas `orders` y `catalog` y aplica en orden los `V*.sql` **reales** de `backend/src/main/resources/db/migration/<modulo>/` con `search_path` fijado al esquema (emula el `defaultSchema` de Flyway); fixture auxiliar para dejar las tablas vacías entre pruebas
- [X] T013 [US3] Ejecutar `mvn test -Dtest='ModuleSchemas*,SchemaIsolation*' -Dskip.srcclr=true --no-transfer-progress` en `backend/` y confirmar que T008 y T009 pasan y que el lint S1–S3 no reporta nada sobre los scripts nuevos

**Checkpoint**: las tablas existen y están verificadas; el cargador ya puede escribir en ellas

---

## Phase 4: User Story 1 - Datos de pedidos y de catálogo cargados en su propio esquema (Priority: P1) 🎯 MVP

**Goal**: un único comando descarga el dataset y llena `orders` y `catalog`.

**Independent Test**: con las tablas vacías, ejecutar la carga y comprobar que cada tabla tiene filas, que los conteos coinciden con el origen y que un pedido y un producto se reconstruyen consultando solo su esquema (quickstart V1–V3).

### Tests for User Story 1 ⚠️

- [X] T014 [P] [US1] `data/olist/tests/test_download.py`: reutiliza el ZIP/los CSV existentes sin llamar a la red (FR-009); `--source` acepta ZIP y carpeta; sin `KAGGLE_USERNAME`/`KAGGLE_KEY` lanza `DownloadError`; ZIP corrupto o sin alguno de los 8 CSV esperados lanza `DownloadError` con el nombre del archivo que falta (usar `httpx.MockTransport`, sin red real)
- [X] T015 [P] [US1] `data/olist/tests/test_transform.py`: stock determinista (`h` = 4 primeros bytes de `sha256("olist-stock-v1:" + product_id)`; `h % 10 == 0` → 0, si no `1 + (h // 10) % 200`) con vectores calculados a mano, igual en dos llamadas, rango 0–200 y entre el 5 % y el 15 % en 0 sobre 10 000 ids; precio de referencia = mediana de `price` por producto a 2 decimales y `None` sin ventas; cadena vacía → `None` en columnas opcionales y descarte (con contador por motivo) en `NOT NULL`; renombre `product_name_lenght` → `name_length` y `product_description_lenght` → `description_length`; categoría sin traducción → fila de `product_categories` con `name_en` nulo; ítems, pagos y reseñas con `order_id` inexistente se descartan
- [X] T016 [P] [US1] `data/olist/tests/test_preflight.py`: base sin esquemas, con esquema pero sin tablas, o con una tabla faltante → `DatabaseNotReadyError` con el mensaje «arranca el backend para aplicar las migraciones» (FR-010); base completa → pasa; tras el fallo no hay ninguna tabla creada
- [X] T017 [P] [US1] `data/olist/tests/test_report.py`: el resumen lista por esquema y tabla leídas/cargadas/descartadas (con motivo), el recuento de referencias huérfanas y el tiempo total, y no contiene valores de fila
- [X] T018 [P] [US1] `data/olist/tests/test_load.py` (integración con la Postgres de `conftest.py`): cargar los CSV de ejemplo; los conteos de cada tabla coinciden con lo esperado de las fixtures; un pedido se reconstruye completo (cliente, ítems, pagos, reseña) con consultas solo sobre `orders`; un producto se obtiene con categoría, `reference_price` y `stock` solo desde `catalog`; la reseña con `review_id` repetido se conserva en ambos pedidos

### Implementation for User Story 1

- [X] T019 [P] [US1] Implementar `data/olist/src/olist_loader/download.py`: descarga de `olistbr/brazilian-ecommerce` con `httpx` y autenticación básica a `data/raw/olist.zip`, extracción a `data/raw/olist/`, reutilización si ya existe, `--source` (ZIP o carpeta) y `--skip-download`; verificar el endpoint y la autenticación vigentes de Kaggle antes de fijarlos (research D-3) y, si no sirven, usar `kagglehub` manteniendo la misma interfaz; nunca registrar credenciales
- [X] T020 [P] [US1] Implementar `data/olist/src/olist_loader/transform.py`: lectura de los CSV por flujo, mapa de columnas de `data-model.md`, vacío → `None`, descartes con contador por motivo, stock sintético, mediana de precios de referencia, categorías sin traducción (todo según T015)
- [X] T021 [P] [US1] Implementar `data/olist/src/olist_loader/preflight.py`: comprueba conexión, que existen los esquemas `orders` y `catalog` y todas las tablas esperadas con su clave primaria, y que el historial de Flyway de cada esquema no tiene migraciones fallidas; nunca ejecuta DDL (D-1, D-8)
- [X] T022 [P] [US1] Implementar `data/olist/src/olist_loader/report.py`: resumen por esquema y tabla, referencias huérfanas y tiempo total, en el formato de `contracts/loader-cli.md`
- [X] T023 [US1] Implementar `data/olist/src/olist_loader/load.py`: por esquema, una transacción con un único `TRUNCATE` de todas sus tablas y `COPY` de las filas transformadas (orden: categorías antes que productos; clientes, pedidos y luego ítems, pagos y reseñas); un error revierte el esquema y lanza `LoadError`; `catalog` y `orders` se cargan en transacciones separadas, sin consultas entre esquemas (depende de T020)
- [X] T024 [US1] Conectar el pipeline en `cli.py` (descarga → preflight → transformación → carga → resumen) y respetar `--dry-run` (reporta sin escribir); comprobar que T014–T018 pasan (depende de T019–T023)

**Checkpoint**: US1 completa; la carga funciona de punta a punta sobre los datos de ejemplo

---

## Phase 5: User Story 2 - Aislamiento entre esquemas: referencias solo por id (Priority: P1)

**Goal**: ninguna FK cruza entre `orders` y `catalog`; un ítem de pedido guarda producto y vendedor solo por id.

**Independent Test**: tras la carga, no hay restricciones que crucen de esquema y un ítem con un producto inexistente en `catalog` se acepta y se reporta como huérfano (quickstart V4, V5).

### Tests for User Story 2 ⚠️

- [X] T025 [P] [US2] Ampliar `backend/src/test/java/com/shoplab/helpdesk/schema/ModuleSchemasIntegrationTests.java` (donde viven las comprobaciones S4/S5 contra la base real; `SchemaIsolationTests` solo contiene el lint estático) con las tablas nuevas: la consulta S5 devuelve 0 filas tras migrar; la consulta de `contracts/schema-ddl.md` sobre `orders.order_items` lista **solo** la FK hacia `orders` (D3); prueba negativa: una FK temporal de `orders.order_items.product_id` hacia una tabla de `catalog` hace que S5 devuelva filas (si LAB-105 ya cubre la negativa genérica, reutilizarla y añadir solo lo específico de estas tablas)
- [X] T026 [P] [US2] `data/olist/tests/test_orphans.py`: un ítem cuyo `product_id` o `seller_id` no existe en las fixtures de `catalog` se carga sin error, se cuenta como huérfano en el resumen (con desglose `order_items.product_id` / `order_items.seller_id`) y la carga termina con código 0; tras cargar, la consulta S5 contra la base devuelve 0 filas

### Implementation for User Story 2

- [X] T027 [US2] Implementar la detección de referencias huérfanas en `data/olist/src/olist_loader/transform.py` y `report.py` comparando los conjuntos de ids ya leídos en memoria (sin ninguna consulta entre esquemas) y mostrarla en el resumen; confirmar que T025 y T026 pasan (depende de T020, T022)

**Checkpoint**: US1 y US2 funcionan; el aislamiento está verificado en base de datos y en el cargador

---

## Phase 6: User Story 4 - Carga repetible y segura de re-ejecutar (Priority: P2)

**Goal**: ejecutar la carga varias veces o tras una interrupción da siempre el mismo estado final.

**Independent Test**: ejecutar dos veces y comparar conteos; interrumpir a la mitad y repetir (quickstart V6, V7, V10).

### Tests for User Story 4 ⚠️

- [X] T028 [P] [US4] `data/olist/tests/test_idempotence.py`: dos cargas seguidas dejan los mismos conteos y 0 filas duplicadas; una base con filas previas (incluidas filas ajenas al dataset) termina solo con el contenido del dataset
- [X] T029 [P] [US4] `data/olist/tests/test_atomicity.py`: provocar un fallo a mitad de la carga de un esquema (por ejemplo una fila que viola un `CHECK`) deja ese esquema exactamente como estaba antes (sin datos parciales) y sale con código 5; reejecutar con datos corregidos completa el 100 % del conjunto
- [X] T030 [P] [US4] `data/olist/tests/test_offline_rerun.py`: con el dataset ya en `data/raw/`, una segunda ejecución con un transporte HTTP que falla ante cualquier petición termina en 0 y no descarga nada
- [X] T031 [P] [US4] `data/olist/tests/test_cli.py`: códigos de salida 0 (carga correcta y `--dry-run`), 2 (opción inválida), 3 (sin credenciales ni `--source`), 4 (base sin migrar) y 5 (fallo de carga); `--dry-run` no modifica la base de datos; `--skip-download` falla con código 3 si no hay CSV

### Implementation for User Story 4

- [X] T032 [US4] Cerrar las brechas que revelen T028–T031 en `data/olist/src/olist_loader/load.py`, `download.py` y `cli.py` (rollback por esquema, mensajes y códigos de salida, resumen con el tiempo total) sin añadir comportamiento fuera de la spec

**Checkpoint**: todas las historias funcionan de forma independiente

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: documentación, verificación con datos reales y comprobaciones previas al commit

- [X] T033 [P] Crear `docs/olist-dataset.md`: reparto de tablas entre esquemas, regla del stock sintético y del precio de referencia, aviso de licencia CC BY-NC-SA 4.0 (el dataset no se versiona), cómo cargar y cómo funcionan las referencias por id
- [X] T034 [P] Crear `data/olist/README.md` (instalación con `uv`, variables de entorno, ejemplos de `olist-loader load`) y añadir a `README.md` una sección breve «Datos de ejemplo (Olist)» con el comando y el enlace a `docs/olist-dataset.md`
- [ ] T035 Ejecutar la carga con el dataset **real** y contrastar lo que el plan dio por supuesto (quickstart §5): nombres de columna y conteos de la tabla de origen, reseñas con `review_id` repetido (confirmar que la PK sustituta es suficiente), endpoint y autenticación de Kaggle, y 0 ítems huérfanos; corregir `research.md`, `data-model.md` y las migraciones si algo difiere
- [X] T036 Ejecutar los escenarios V1–V10 de `quickstart.md` contra la Postgres local y anotar el resultado en `specs/LAB-17-olist-dataset-by-context/validation.md`
- [X] T037 Comprobaciones previas al commit: `uv run ruff check . && uv run pytest` en `data/olist/`; `mvn test -Dtest='ModuleSchemas*,SchemaIsolation*'` y `mvn checkstyle:check -Dskip.srcclr=true --no-transfer-progress` en `backend/`; `git status --short` sin archivos sueltos (en particular, nada de `data/raw/` ni credenciales) y `git diff --staged` solo con cambios de la historia

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias.
- **Foundational (Phase 2)**: depende de Setup; bloquea las historias.
- **US3 (Phase 3)**: depende de Foundational; **bloquea US1, US2 y US4** porque crea las tablas y el `conftest.py`.
- **US1 (Phase 4)**: depende de US3. Es el MVP.
- **US2 (Phase 5)**: depende de US3 (tablas) y reutiliza `transform.py` y `report.py` de US1 (T020, T022).
- **US4 (Phase 6)**: depende de US1 (necesita el cargador funcionando).
- **Polish (Phase 7)**: depende de todas las historias; T035 requiere acceso real a Kaggle.

### Within Each User Story

- Las pruebas primero y fallando; después las migraciones/modelos; después la lógica; al final la integración.
- `load.py` (T023) depende de `transform.py` (T020); `cli.py` (T024) depende de T019–T023.

### Parallel Opportunities

- Foundational: T004, T005 y T006 en paralelo (T007 después de T005).
- US3: T010 y T011 en paralelo (T008 y T009 comparten archivo, van seguidas).
- US1: T014–T018 (pruebas) en paralelo; T019–T022 en paralelo.
- US2: T025 y T026 en paralelo.
- US4: T028–T031 en paralelo.
- Polish: T033 y T034 en paralelo.

---

## Parallel Example: User Story 1

```bash
# Pruebas de US1 a la vez:
Task: "test_download.py en data/olist/tests/"
Task: "test_transform.py en data/olist/tests/"
Task: "test_preflight.py en data/olist/tests/"
Task: "test_report.py en data/olist/tests/"
Task: "test_load.py en data/olist/tests/"

# Módulos independientes de US1 a la vez:
Task: "download.py en data/olist/src/olist_loader/"
Task: "transform.py en data/olist/src/olist_loader/"
Task: "preflight.py en data/olist/src/olist_loader/"
Task: "report.py en data/olist/src/olist_loader/"
```

---

## Implementation Strategy

### MVP First (US3 + US1)

1. Setup y Foundational.
2. US3: migraciones y su verificación (las tablas son imprescindibles).
3. US1: cargador de punta a punta.
4. **STOP y VALIDAR**: carga con los CSV de ejemplo y comprobar quickstart V1–V3.

### Incremental Delivery

1. Setup + Foundational + US3 → tablas verificadas.
2. + US1 → datos cargados (MVP).
3. + US2 → aislamiento verificado en BD y en el cargador.
4. + US4 → repetible y atómica.
5. Polish → documentación, datos reales y comprobaciones.

### Notes

- Las migraciones solo añaden `V2__…`; nunca se edita `V1__baseline.sql` (Flyway rechaza cambios por checksum).
- Sin cambios en `infra/` ni en `shoplab-infra`: la carga es solo local (research D-12).
- Commits atómicos con el formato `type(scope): description`; el título de la PR lleva `[LAB-17]`.
