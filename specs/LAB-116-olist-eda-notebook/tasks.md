---

description: "Task list for LAB-116 - Análisis exploratorio de Olist con Pandas y NumPy"
---

# Tasks: Análisis exploratorio de Olist con Pandas y NumPy

**Input**: Design documents from `/specs/LAB-116-olist-eda-notebook/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`notebook-contract.md`,
`summary-format.md`, `data-files-format.md`), quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas antes o junto con la implementación y `ml/` usará
`--cov-fail-under=80` como `ingest/` y `notebooks/`. En cada historia las pruebas van primero y deben fallar antes de
implementar. Ninguna prueba unitaria necesita base de datos ni red: usan DataFrames en memoria y un conector falso. Solo
ejecutar el notebook (T012, T019, T026, T032, T039, T042, T047, T049, T055) necesita la base local con Olist completo, y el job de CI
`ml-notebook` lo ejecuta contra Postgres con las fixtures de LAB-17.

**Organization**: Tareas agrupadas por historia de usuario. US1 (calidad) y US2 (valores) son P1 y forman el MVP; US3
(entregas y reseñas), US4 (discrepancias y conclusiones) y US5 (ejecución, lectura y CI) son P2. US1, US2 y US3 son
independientes entre sí a nivel de código (cada una añade módulos propios), pero **comparten el archivo del notebook**, así
que sus tareas sobre el `.ipynb` se hacen en orden. US4 consume los resultados de US1 a US3; el job de CI de US5 puede
construirse en paralelo desde que existe el andamiaje del notebook (T013).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US5)
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Proyecto**: `ml/` (proyecto `uv` `shoplab-ml`). **Paquete**: `ml/src/olist_eda/`. **Pruebas**: `ml/tests/` (sin
  `tests/__init__.py`, para no chocar con el `tests/` de `ingest/`). **Notebook**: `ml/notebooks/olist-eda.ipynb`.
  **Datos propios**: `ml/notebooks/data/`. **Resultados**: `ml/notebooks/results/`.
- Python: líneas ≤ 121 caracteres, identificadores en inglés, `ruff` con `select = ["E","F","I","B","UP"]` como
  `data/olist`; el texto del notebook y los mensajes de error para quien lo ejecuta, en español.
- Nada de `ml/` se importa desde `backend/` ni `ingest/`; `backend/`, `ingest/`, `data/olist`, `data/kb`, `frontend/` e
  `infra/` **no se modifican** (FR-023, FR-025). Las únicas ediciones fuera de `ml/` y `specs/` son `.github/workflows/ci.yml`,
  `.specify/memory/constitution.md` y `README.md`.
- Sin datos personales, identificadores de Olist, rutas locales (`C:\Users\…`), secretos ni claves en el notebook, sus salidas
  ni los datos (FR-024).
- El notebook se escribe con `nbformat` desde un script desechable **fuera del repositorio** (o en Jupyter) y se ejecuta con
  `uv run jupyter nbconvert --to notebook --execute --inplace notebooks/olist-eda.ipynb` desde `ml/`. La lógica va en el
  paquete; el notebook solo orquesta y explica.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base verde, base local con Olist completo y proyecto `uv` creado

- [X] T001 Verificar la línea base: rama `feature/LAB-116-olist-eda-notebook` con árbol limpio, `uv --version`, base local
  levantada (`docker compose -f infra/docker-compose.yml up -d --wait`) con las migraciones de `orders` y `catalog`
  aplicadas, y Olist **completo** cargado (`cd data/olist && uv run olist-loader load`, o `--source` con el ZIP si no hay
  cuenta de Kaggle). Anotar el número de filas de las ocho tablas (lo usan T019 y T055)
- [X] T002 Crear `ml/pyproject.toml` (proyecto `shoplab-ml`, Python `>=3.12`, `hatchling`, `packages = ["src/olist_eda"]`;
  dependencias `pandas>=2.2`, `numpy>=2.0`, `psycopg[binary]>=3.2`, `matplotlib>=3.9`, `ipykernel>=6.29`; grupo `dev` con
  `pytest>=8.0`, `pytest-cov>=5.0`, `ruff>=0.6`, `nbformat>=5.10`, `nbconvert>=7.16`, `nbclient>=0.10`; `pytest`:
  `testpaths = ["tests"]`, `pythonpath = ["src"]`, `addopts = "--cov=olist_eda --cov-fail-under=80"`; `ruff`:
  `line-length = 121`, `target-version = "py312"`, `src = ["src", "tests"]`, `extend-exclude = ["*.ipynb"]`) y la
  estructura vacía `ml/src/olist_eda/__init__.py`, `ml/tests/`, `ml/notebooks/data/`, `ml/notebooks/results/`
- [X] T003 Generar `ml/uv.lock` con `cd ml && uv lock && uv sync` y comprobar `uv run ruff check .` en verde (aún sin
  pruebas: el umbral de cobertura entra en vigor cuando existan)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: configuración, errores, acceso de solo lectura a la base, utilidades de presentación y andamiaje del notebook
que usan todas las historias

**⚠️ CRITICAL**: ninguna historia puede empezar hasta completar esta fase

- [X] T004 [P] Escribir `ml/tests/test_config.py`: valores por defecto (`postgresql://localhost:5432/shoplab`, `shoplab`,
  `change-me-local-only`, `min_group_orders` = 30, `top_n` = 10); lectura de `POSTGRES_URL`, `POSTGRES_USER`,
  `POSTGRES_PASSWORD`, `EDA_MIN_GROUP_ORDERS` y `KB_DIR`; la forma `jdbc:postgresql://…` se normaliza quitando `jdbc:`; la
  contraseña no aparece en `repr`; `EDA_MIN_GROUP_ORDERS` no entera o `< 1` lanza `ValueError` que nombra la variable;
  `kb_dir` por defecto resuelve a `data/kb` desde la raíz del repositorio
- [X] T005 [P] Crear `ml/src/olist_eda/errors.py` con `PreflightError`, `PolicyParseError` y `MissingActionError`
  (data-model «Reglas de validación y errores»)
- [X] T006 Implementar `ml/src/olist_eda/config.py`: dataclass inmutable `AnalysisSettings` (`postgres_url`, `postgres_user`,
  `postgres_password` con `repr=False`, `min_group_orders` ≥ 1, `kb_dir`, `top_n`) y `from_env()`; hace verdes T004
- [X] T007 [P] Crear `ml/tests/conftest.py` con una fixture `tables` que devuelve un `dict[str, DataFrame]` con las ocho
  tablas **en memoria** y anomalías deliberadas y documentadas en su docstring, para que las pruebas las citen: un pedido
  `delivered` sin fecha de entrega; un `canceled` sin entrega (nulo esperado); una entrega anterior a la compra; una fecha
  estimada anterior a la compra; un ítem con `product_id` y otro con `seller_id` inexistentes en `catalog`; dos reseñas con el
  mismo `review_id`; dos reseñas idénticas salvo `id`; un `payment_type` fuera de las políticas; un estado de pedido que la
  consulta de pedidos no cubre; un producto sin categoría; un pedido con productos de dos categorías de precios distintos y otro
  con empate de precio. Tipos ya convertidos como los dejaría `db.load_tables` (`float64`, `datetime64[ns]`, `Int64`)
- [X] T008 [P] Escribir `ml/tests/test_db.py` con un conector falso (`FakeConnection`/`FakeCursor` con `description` y
  `fetchall`; sin Postgres): `connect` pasa `options=-c default_transaction_read_only=on` y `connect_timeout=5`;
  `preflight` lanza `PreflightError` con el mensaje de conexión (incluye `docker compose -f infra/docker-compose.yml up -d
  --wait`) si la conexión falla, con el de tablas ausentes (incluye «Arranca el backend para aplicar las migraciones») si
  falta un esquema o una tabla, y con el de tabla vacía (incluye `olist-loader load`) si alguna de las ocho tiene 0 filas;
  `load_tables` devuelve exactamente las 8 claves cualificadas (`orders.orders`, …) y convierte `numeric` → `float64`,
  `timestamp` → `datetime64[ns]` y `smallint`/`integer` → `Int64` admitiendo nulos
- [X] T009 Implementar `ml/src/olist_eda/db.py`: `connect(settings)` de solo lectura (D-3), `preflight(settings)` en el orden
  del contrato (inalcanzable → falta esquema/tabla → tabla vacía; se detiene en la primera falla) y
  `load_tables(conn)` con `SELECT *` por tabla y `pd.DataFrame(cursor.fetchall(), columns=…)` (D-2); sin SQLAlchemy ni
  `read_sql`; hace verdes T008
- [X] T010 [P] Escribir `ml/tests/test_report.py` (parte 1, presentación): `show_table(df, max_rows=5)` nunca muestra más de 5
  filas y **elimina las columnas `*_id`** (FR-024); un DataFrame vacío no falla
- [X] T011 Implementar `ml/src/olist_eda/report.py` (parte 1): `show_table` y el formato común de tablas (porcentajes con 2
  decimales, enteros con separador de miles); hace verdes T010
- [X] T012 Crear el andamiaje del notebook `ml/notebooks/olist-eda.ipynb`, con un script desechable fuera del repo: los 15
  encabezados de nivel 2 de la sección «Estructura» de [contracts/notebook-contract.md](contracts/notebook-contract.md), en
  ese orden (0 Parámetros … 14 Límites) y la **celda de parámetros** (la primera celda de código; **una sola celda**, cada
  parámetro con su explicación breve; construye `AnalysisSettings`, FR-019), la **celda de preflight** (la **segunda** celda
  de código, FR-018) y la de carga con `db.load_tables` y el banner «volumen reducido: muestra de pruebas» si hay menos de
  1 000 pedidos. Ejecutarlo contra la base local con `nbconvert` y comprobar que corre de arriba abajo

**Checkpoint**: configuración, acceso de solo lectura, preflight y notebook de andamiaje listos; las historias pueden empezar

---

## Phase 3: User Story 1 - Conocer la calidad de cada tabla de Olist (Priority: P1) 🎯 MVP

**Goal**: perfil de calidad de las ocho tablas (nulos esperados e inesperados, duplicados, tipos, rangos) e integridad entre
esquemas, con operaciones vectorizadas y sin bucles por fila.

**Independent Test**: con la base local cargada por LAB-17, ejecutar solo las secciones 0 a 4 del notebook y comprobar que
hay un perfil por cada una de las ocho tablas con nulos, duplicados, tipos y rangos, el recuento de huérfanos entre
esquemas y ninguna celda con bucle por fila.

### Tests for User Story 1 ⚠️

> **NOTE: escribir estas pruebas primero y comprobar que fallan antes de implementar**

- [X] T013 [P] [US1] Escribir `ml/tests/test_profiling.py` sobre la fixture `tables`: filas por tabla; nulos por columna
  (cantidad y porcentaje de las filas); nulos **inesperados** según la regla de cada columna (el `delivered` sin fecha de
  entrega cuenta, el `canceled` sin entrega no; `order_reviews.comment_title`, `comment_message` y `answered_at` siempre
  esperados; `products.category_name` nulo inesperado; una columna **sin regla** trata todo nulo como inesperado);
  duplicados por **clave natural** y por **fila completa excluyendo la columna sustituta** `id` (dos reseñas idénticas salvo
  `id` cuentan como fila repetida; `review_id` repetido cuenta en la clave pero no necesariamente en la fila completa);
  `customer_unique_id` y `order_reviews.order_id` repetidos se informan como **dato informativo**, no como defecto; tipo de
  cada columna; mínimo, máximo y percentiles `p01`, `p25`, `p50`, `p75`, `p99` solo en columnas numéricas y de fecha;
  conteo de valores **fuera de lo plausible** (`price` ≤ 0, `freight_value` < 0, `weight_g` ≤ 0, dimensiones ≤ 0, `score`
  fuera de 1–5, fechas fuera de `2016-01-01`–`2018-12-31`); tabla vacía y columna toda nula no provocan división por cero;
  comprobación estática: el texto de `profiling.py` e `integrity.py` no contiene `iterrows`, `itertuples`, `apply(` con
  `axis=1` ni un `for` sobre filas de un DataFrame (FR-005, SC-007)
- [X] T014 [P] [US1] Escribir `ml/tests/test_integrity.py`: ítems cuyo `product_id` no está en `catalog.products` y cuyo
  `seller_id` no está en `catalog.sellers`, con cantidad y porcentaje (FR-008); `products.category_name` ausente de
  `product_categories` da 0; sin ítems no hay división por cero

### Implementation for User Story 1

- [X] T015 [US1] Crear `ml/src/olist_eda/schema.py`: `TableSpec` (esquema, nombre, `natural_key`, `surrogate_columns`,
  `null_rules`) y `TABLES` con las ocho tablas y las claves de [data-model.md](data-model.md) («Entrada: tablas leídas»);
  reglas de nulos inesperados de research D-8: `delivered_to_customer_at` nulo y `status == 'delivered'`;
  `delivered_to_carrier_at` nulo y `status ∈ {shipped, delivered}`; `approved_at` nulo y `status ∉ {created, canceled}`;
  reseñas opcionales siempre esperadas; producto sin categoría o sin medidas inesperado; columna sin regla ⇒ todo nulo
  inesperado
- [X] T016 [US1] Implementar `ml/src/olist_eda/profiling.py`: `TableProfile` y `ColumnProfile` del data-model; todo
  vectorizado (`isna().sum()`, `duplicated`, `quantile`, máscaras booleanas, `np.select`); hace verdes T013
- [X] T017 [P] [US1] Implementar `ml/src/olist_eda/integrity.py` con `isin` sobre columnas completas; hace verdes T014
- [X] T018 [US1] Rellenar en `ml/notebooks/olist-eda.ipynb` las secciones **3 Calidad por tabla** (ocho subsecciones, una
  por tabla: filas, nulos con la división esperado/inesperado y el criterio declarado, duplicados con la explicación de
  `review_id`, tipos, rangos y percentiles, valores implausibles) y **4 Integridad entre esquemas** (huérfanos, y la nota de
  que LAB-17 quitó las claves foráneas entre esquemas a propósito)
- [X] T019 [US1] Ejecutar las secciones 0 a 4 contra la base local completa: las ocho tablas aparecen (SC-001) con filas
  iguales a las anotadas en T001, ninguna celda recorre filas y el tiempo es razonable; anotar las anomalías reales para
  T041 y T055

**Checkpoint**: US1 funciona y se puede validar sola (MVP parcial)

---

## Phase 4: User Story 2 - Validar los valores reales de pago y estado contra las políticas (Priority: P1) 🎯 MVP

**Goal**: frecuencias de `payment_type` y `order_status` contrastadas, en ambos sentidos, con las políticas del corpus y con
los estados de la consulta de pedidos de LAB-41.

**Independent Test**: ejecutar solo las secciones 5 a 7 y comprobar que hay una tabla de frecuencias por columna, cada valor
marcado como mencionado o no por las políticas y por la consulta de pedidos, y los valores declarados que no existen en los
datos listados aparte.

### Tests for User Story 2 ⚠️

- [X] T020 [P] [US2] Escribir `ml/tests/test_policies.py`: con el **corpus real** (`data/kb`) el parser devuelve 4 tipos de
  pago declarados (`credit_card`, `debit_card`, `boleto`, `voucher`, de la tabla «Métodos de pago» de
  `payments-01-payment-methods`) y 8 estados declarados (de la tabla «Estados del pedido» de `shipping-03-order-tracking`);
  `INDEX.md` y `REVIEW.md` se ignoran; `mentions` lista los documentos que citan un valor observado entre comillas
  invertidas (p. ej. `canceled` aparece en más de un documento); con un corpus temporal sin la tabla canónica lanza
  `PolicyParseError` que nombra el documento y la sección
- [X] T021 [P] [US2] Escribir `ml/tests/test_categorical.py` sobre la fixture `tables`: la tabla de frecuencias incluye el
  **100 %** de los valores distintos, ordenada por frecuencia, con `count` y `share` que suman 1; marca `declared_by_policy`,
  `mentioned_in` y (solo `order_status`) `covered_by_order_tool`; `AbsentValues` lista los declarados que no existen en los
  datos; el estado que la consulta no cubre y el valor fuera de las políticas de la fixture aparecen como discrepancia;
  `load_order_tool_states` valida el TOML de [contracts/data-files-format.md](contracts/data-files-format.md) (`source`
  obligatorio, `states` no vacía, sin duplicados, forma `[a-z_]+`; errores `ValueError` con el campo afectado)

### Implementation for User Story 2

- [X] T022 [US2] Implementar `ml/src/olist_eda/policies.py`: `PolicyReference` y `load_policy_reference(kb_dir,
  observed_values)` según research D-6 (declarados desde las dos tablas canónicas; mencionados por tokens `[a-z_]+` entre
  comillas invertidas restringidos a los valores observados); hace verdes T020
- [X] T023 [P] [US2] Crear `ml/notebooks/data/order-tool-states.toml` con `source` y los 8 estados (`created`, `approved`,
  `invoiced`, `processing`, `shipped`, `delivered`, `unavailable`, `canceled`) y el comentario de que LAB-41 es lo único que
  debe actualizarlo (research D-7)
- [X] T024 [US2] Implementar `ml/src/olist_eda/categorical.py`: `ValueFrequency`, `AbsentValues`, la tabla de frecuencias
  con cobertura y `load_order_tool_states`; hace verdes T021
- [X] T025 [US2] Rellenar en `ml/notebooks/olist-eda.ipynb` las secciones **5 Tipos de pago**, **6 Estados del pedido** y
  **7 Contraste con políticas y con la consulta de pedidos**, incluida la advertencia explícita de que, mientras
  `order-tool-states.toml` coincida con la tabla del corpus, el contraste con LAB-41 no aporta información nueva (research
  D-7) y la aclaración de que `payment_type` se cuenta por pago, no por pedido
- [X] T026 [US2] Ejecutar las secciones 5 a 7 contra la base local completa; revisar a mano los valores raros o ausentes de
  las políticas y anotarlos para T041 y T055

**Checkpoint**: US1 y US2 funcionan; el MVP cubre los dos criterios P1 de la historia

---

## Phase 5: User Story 3 - Entender los tiempos de entrega, los retrasos y las reseñas (Priority: P2)

**Goal**: distribución de tiempos real y estimado, retrasos por estado geográfico y por categoría, y puntuación de reseñas
frente al retraso, dejando visible todo lo excluido.

**Independent Test**: ejecutar solo las secciones 8 a 11 y comprobar la distribución de tiempos, las dos tablas de retrasos
con su umbral, el histograma de puntuaciones y su relación con el retraso.

### Tests for User Story 3 ⚠️

- [X] T027 [P] [US3] Escribir `ml/tests/test_delivery.py` sobre la fixture `tables`: `actual_days`, `estimated_days` y
  `delay_days` (positivo = tarde) calculados en días con `/ np.timedelta64(1, "D")`; `is_delayed = delay_days > 0`;
  `delay_bucket`: «a tiempo o antes» (≤ 0), «1 a 7 días tarde» (0 < d ≤ 7), «más de 7 días tarde» (> 7), con casos en los
  límites 0 y 7; población analizada = entrega no nula **y** `actual_days ≥ 0` y `estimated_days ≥ 0`; las exclusiones se
  clasifican y cuentan por `sin_fecha_de_entrega` (con su estado), `entrega_anterior_a_compra`,
  `estimada_anterior_a_compra` y `entregado_sin_fecha`; fracción «antes», «el mismo día» y «tarde» suma 100 ± 0,01;
  `primary_category` = categoría del ítem de mayor precio con empate por menor `item_number`, `name_en` o nombre original, y
  «sin categoría»; el número de pedidos multicategoría se informa; `DelaySummary` por estado del cliente y por categoría con
  `below_threshold` cuando hay menos de `min_group_orders` pedidos (los grupos pequeños salen aparte y no entran al
  ranking); un pedido con varias reseñas usa la **media** de sus puntuaciones; población vacía y ningún grupo sobre el umbral
  devuelven resultados vacíos sin error ni división por cero
- [X] T028 [P] [US3] Ampliar `ml/tests/test_report.py` (parte 2, figuras): las funciones de figura devuelven una figura con
  el backend `Agg` o `None` con un aviso cuando no hay datos (nunca un gráfico vacío), y los PNG embebidos usan resolución
  moderada (`dpi=100`)

### Implementation for User Story 3

- [X] T029 [US3] Implementar `ml/src/olist_eda/delivery.py` según research D-11 a D-14 y data-model («Columnas derivadas de
  entrega», `DelaySummary`), todo vectorizado (`np.select`, `sort_values` + `drop_duplicates(subset="order_id")`,
  `groupby`); hace verdes T027
- [X] T030 [US3] Ampliar `ml/src/olist_eda/report.py` con las figuras de la parte 2 (histograma de tiempos real y estimado,
  distribución de la diferencia, barras de retraso por estado y por categoría, histograma de puntuaciones y puntuación media
  por tramo de retraso); hace verdes T028
- [X] T031 [US3] Rellenar en `ml/notebooks/olist-eda.ipynb` las secciones **8 Tiempos de entrega**, **9 Retrasos por estado
  del cliente**, **10 Retrasos por categoría** y **11 Reseñas**: distribución, exclusiones con su motivo, fracciones antes/a
  tiempo/tarde, criterio de categoría y umbral declarados, y avisos en lugar de gráficos vacíos
- [X] T032 [US3] Ejecutar las secciones 8 a 11 contra la base local completa; comprobar que las exclusiones suman el total de
  pedidos y que el `.ipynb` se mantiene por debajo de ~2 MB (research D-16); anotar los hallazgos para T041 y T055

**Checkpoint**: US1, US2 y US3 funcionan de forma independiente

---

## Phase 6: User Story 4 - Obtener conclusiones y discrepancias convertidas en acciones (Priority: P2)

**Goal**: una tabla de discrepancias generada por código, donde cada hallazgo resuelve una acción, y conclusiones y límites
escritos sobre la ejecución completa guardada.

**Independent Test**: leer las secciones 12 a 14 del notebook ya ejecutado: resumen de las tres áreas, cada discrepancia con
origen, impacto y acción, ninguna sin acción, y límites declarados.

### Tests for User Story 4 ⚠️

- [X] T033 [P] [US4] Escribir `ml/tests/test_findings.py`: un caso por cada uno de los 12 `kind` del catálogo de
  [data-model.md](data-model.md) (`value_not_in_policies`, `policy_value_not_in_data`, `status_not_in_order_tool`,
  `tool_state_not_in_data`, `unexpected_nulls`, `duplicate_rows`, `orphan_reference`, `incoherent_dates`,
  `delivered_without_date`, `date_with_non_delivered_status`, `implausible_range`, `category_without_translation`) con la
  fixture `tables`; los hallazgos informativos (`customer_unique_id` repetido) **no** entran; `resolve_action` busca primero
  `kind:subject`, luego `kind`, y lanza `MissingActionError` (tipo y sujeto) si no hay ninguna; el
  `discrepancy-actions.toml` real tiene una entrada por **cada** `kind` del catálogo; una clave específica con un `kind`
  inexistente es un error; `target` ∈ `corpus`, `LAB-41`, `loader`, `new_task`, `analysis_note`; `action` no vacía
- [X] T034 [P] [US4] Ampliar `ml/tests/test_report.py` (parte 3, resumen): `write_summary`/`read_summary` respetan
  [contracts/summary-format.md](contracts/summary-format.md): `schema_version` entero, `data_scale` = `full` si
  `orders.orders` tiene ≥ 1 000 filas y `sample` si no, exactamente 8 entradas en `tables`, el 100 % de los valores en
  `values`, todo `finding` con `action` no vacía y ordenado por `area` y luego `count` descendente, ninguna cadena de 32
  caracteres hexadecimales, decimales con 4 como máximo

### Implementation for User Story 4

- [X] T035 [US4] Implementar `ml/src/olist_eda/findings.py`: `Finding` (`kind`, `subject`, `area`, `count`, `share`,
  `impact`, `action`, `target`), un detector por tipo que reutiliza `profiling`, `integrity`, `categorical` y `delivery`, y
  `resolve_action` (D-15); hace verdes T033 salvo la entrada por tipo del TOML
- [X] T036 [US4] Crear `ml/notebooks/data/discrepancy-actions.toml` con una entrada **por tipo** (`target` + `action` en
  español, imperativo, una o dos frases) para los 12 `kind`; hace verde la comprobación de cobertura de T033
- [X] T037 [US4] Ampliar `ml/src/olist_eda/report.py` (parte 3) con `write_summary`/`read_summary`; hace verdes T034
- [X] T038 [US4] Rellenar en `ml/notebooks/olist-eda.ipynb` la sección **12 Discrepancias y acciones**: tabla generada por
  código con origen, impacto, destino y acción de cada hallazgo, y escritura de
  `ml/notebooks/results/olist-eda-summary.json`
- [X] T039 [US4] Ejecutar el notebook completo contra la base local y revisar la lista **real** de hallazgos de las tres
  áreas (contrastarla con las anotaciones de T019, T026 y T032)
- [X] T040 [US4] Curar en `ml/notebooks/data/discrepancy-actions.toml` las **acciones específicas** (`kind:subject`) de los
  hallazgos reales de T039 (valores de pago o estado fuera de las políticas, huérfanos, `delivered` sin fecha, etc.), cada
  una con su `target`; propone ajustes del corpus, tareas o notas para LAB-41, pero no los aplica (spec, Assumptions)
- [X] T041 [US4] Escribir a mano en el notebook las secciones **13 Conclusiones** (hallazgos de calidad, valores y entregas
  con las cifras que los respaldan; las áreas sin discrepancias lo dicen explícitamente; cada discrepancia que afecta a una
  historia pendiente como LAB-41 la nombra) y **14 Límites** (marketplace brasileño de 2016–2018, stock sintético, hallazgos
  descriptivos y no causales, esta historia no modifica el corpus ni los datos, y el aviso de volver a leer las conclusiones
  si cambian los datos), siempre **después** de T040
- [X] T042 [US4] Ejecutar de nuevo el notebook completo con `nbconvert --inplace` para guardar salidas y regenerar
  `olist-eda-summary.json` (`data_scale` = `full`); comprobar que la tabla de la sección 12 no tiene ninguna fila sin acción
  (SC-003)

**Checkpoint**: las historias 1 a 4 están completas y el notebook guardado cuenta la historia entera

---

## Phase 7: User Story 5 - Ejecutarlo en local, leerlo sin ejecutarlo y validarlo en CI (Priority: P2)

**Goal**: ejecución reproducible en local, lectura sin ejecutar y ejecución real contra Postgres en integración continua.

**Independent Test**: desde un clon limpio con la base local cargada, seguir el README y ejecutar el notebook sin editar
código; en CI, el job `ml-notebook` ejecuta el notebook sobre las fixtures y falla si una celda da error.

### Tests for User Story 5 ⚠️

- [X] T043 [P] [US5] Escribir `ml/tests/test_notebook.py` sobre el `.ipynb` guardado y `olist-eda-summary.json`: los 15
  encabezados de nivel 2 existen y en orden ([contracts/notebook-contract.md](contracts/notebook-contract.md)); los
  parámetros están en **una sola celda** de código y el preflight es la **segunda** celda de código; ningún código del
  notebook contiene `iterrows`, `itertuples`, `apply(` con `axis=1` ni `for` sobre filas; ninguna salida contiene una
  cadena hexadecimal de 32 caracteres (FR-024) ni más de 5 filas de una tabla; ninguna ruta local ni credencial; el tamaño
  del `.ipynb` es ≤ ~2 MB; `data_scale` = `full`; las filas por tabla del notebook coinciden con `tables` del JSON y las
  filas de la sección 12 con `findings`, y ninguna tiene `action` vacía; `ml/` no contiene ningún `.csv` (FR-002, SC-009)

### Implementation for User Story 5

- [X] T044 [P] [US5] Crear `ml/ci/prepare-database.sh` (`set -euo pipefail`, ejecutable con `chmod +x` y registrado con
  `git update-index --chmod=+x`): para `orders` y luego `catalog`, crea el esquema y aplica con `psql` los
  `backend/src/main/resources/db/migration/<esquema>/V*__*.sql` en **orden de versión** (`sort -V`) con
  `PGOPTIONS="-c search_path=<esquema>"`, la misma lógica que `data/olist/tests/conftest.py`; después ejecuta
  `uv run --project data/olist olist-loader load --source data/olist/tests/fixtures`; lee `PGHOST`, `PGPORT`, `PGUSER`,
  `PGPASSWORD` y `PGDATABASE` y falla con un mensaje claro si falta alguna
- [X] T045 [US5] Editar `.github/workflows/ci.yml`: filtro nuevo `ml` (`ml/**`, `data/kb/**`, `data/olist/**`,
  `backend/src/main/resources/db/migration/orders/**`, `backend/src/main/resources/db/migration/catalog/**`) con su salida
  en `changes`, y `ml` añadido a la matriz de Python (`ruff` + `pytest`, sin base de datos) con el mismo patrón que
  `notebooks`
- [X] T046 [US5] En `.github/workflows/ci.yml`, añadir el job `ml-notebook` (`needs: changes`, `if` del filtro `ml` o de
  `ci`, `timeout-minutes: 15`) con servicio `pgvector/pgvector:0.8.7-pg16`, `pg_isready` como health check, `astral-sh/setup-uv`,
  `uv sync --frozen` en `ml` y en `data/olist`, `ml/ci/prepare-database.sh`, y
  `uv run jupyter nbconvert --to notebook --execute --output-dir "$RUNNER_TEMP" notebooks/olist-eda.ipynb` con
  `EDA_MIN_GROUP_ORDERS=1` y las variables `POSTGRES_*` y `PG*` del servicio (valores locales públicos, sin secretos); añadir
  `ml-notebook` a `needs` de `ci-gate`
- [X] T047 [US5] Reproducir CI en local (quickstart §7): base `shoplab_ci` nueva, `ml/ci/prepare-database.sh`, ejecutar el
  notebook con `EDA_MIN_GROUP_ORDERS=1` y `--output-dir`; corregir en el paquete o en el notebook cualquier división por
  cero, gráfico vacío o sección que falle con 3 pedidos, y comprobar que el banner «volumen reducido» aparece
- [X] T048 [US5] Escribir `ml/README.md` (FR-021): qué es `ml/` y qué contiene hoy, requisitos, cómo preparar la base
  (compose, backend, cargador), cómo ejecutar desde un clon limpio, cómo leer los resultados y qué significan las secciones,
  variables de entorno, cómo se valida en CI (`ml-notebook` sobre las fixtures y por qué sus cifras no se parecen a las de
  la ejecución completa), el aviso de volver a leer las conclusiones si cambian los datos, y la estructura del directorio
- [X] T049 [US5] Verificar a mano el quickstart §4 (base apagada y base sin esquemas: la segunda celda de código se detiene
  con el mensaje y no hay resultados parciales), §5 (una escritura desde la conexión del paquete falla por ser de solo
  lectura) y §6 (dos ejecuciones consecutivas dan el mismo `olist-eda-summary.json` salvo `generated_at`, SC-008); anotar los
  resultados para T055. Con esto, T043 pasa

**Checkpoint**: todas las historias son funcionales; el notebook se ejecuta en local y en CI

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: documentación del repositorio, enmienda de la constitución, verificación completa y entrega

- [X] T050 [P] Añadir a `README.md` la fila de `ml/` (análisis exploratorio y ML clásico de F8, con enlace a `ml/README.md`)
  y la mención de `ml/` en la frase de CI (filtro `ml` y job `ml-notebook`)
- [X] T051 [P] Enmendar `.specify/memory/constitution.md` a **1.0.2** (PATCH, LAB-116): en «Technology & Architecture
  Constraints» añadir `ml/` («análisis exploratorio y ML clásico de F8; nunca importado por código de producción») y aclarar
  que `notebooks/` conserva los experimentos de RAG; actualizar el comentario «Sync Impact Report» y la línea
  `**Version**`/`Last Amended` con la fecha de hoy; no cambia ningún principio
- [X] T052 Comprobación previa a cada commit (`CLAUDE.md` §8): `cd ml && uv run ruff check .`, `uv run pytest` con
  cobertura ≥ 80 % del paquete, `git status --short` sin archivos sueltos (`*.orig`, `.cache`, salidas temporales, `ml/.venv`,
  `.ipynb_checkpoints`), `git diff --staged` solo con cambios intencionales. Los comandos Maven (`checkstyle`, `verify`) no
  aplican: no hay cambios en `backend/`
- [X] T053 Comprobar el alcance (quickstart §8): `git diff --stat origin/main -- backend ingest data/olist data/kb frontend
  infra` vacío; `grep -rn "olist_eda" backend ingest` sin resultados; `git ls-files | grep -i '\.csv$'` solo con las
  fixtures de `data/olist/tests/fixtures`; ninguna ruta local, secreto ni identificador de Olist en el notebook ni en el JSON
- [X] T054 Revisar la seguridad de dependencias: confirmar si el escaneo Trivy de CI (LAB-76) cubre `ml/uv.lock`; si no,
  ejecutar `pip-audit` sobre las dependencias bloqueadas y dejar el resultado en `validation.md`; corregir con la versión
  mínima que resuelva cualquier hallazgo
- [X] T055 Ejecutar la guía completa [quickstart.md](quickstart.md) §1 a §9 y rellenar `specs/LAB-116-olist-eda-notebook/
  validation.md` con lo medido: filas por tabla, duración de la ejecución completa y en CI, número de pruebas y cobertura,
  tamaño del notebook, valores y hallazgos reales y qué acción propone cada uno, y los resultados de §4 a §6
- [ ] T056 Abrir el pull request con la skill `commit-and-push`: título `feat(ml): [LAB-116] análisis exploratorio de Olist
  con Pandas y NumPy` (`CLAUDE.md` §5.1; release-please lo trata como `feat`, minor), commits atómicos Conventional Commits en
  la rama, plantilla de PR rellenada. Cuando CI esté en verde, hacer la **comprobación negativa** del quickstart §7 con un
  commit desechable que provoque un error en una celda (`ml-notebook` y `ci-gate` deben fallar) y revertirlo

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; empieza de inmediato.
- **Foundational (Phase 2)**: depende de Setup; **bloquea** todas las historias.
- **User Stories (Phase 3 a 7)**: dependen de Foundational.
  - US1, US2 y US3 se pueden implementar en paralelo en el código (módulos propios), pero **no** sus tareas sobre el
    `.ipynb` (T018, T025, T031): se hacen en orden de sección.
  - US4 depende de US1, US2 y US3: sus detectores (T035) usan `profiling`, `integrity`, `categorical` y `delivery`, y las
    conclusiones (T041) necesitan la ejecución completa.
  - US5: T044 a T046 (script y CI) pueden empezar en cuanto exista el andamiaje del notebook (T012), en paralelo con US1 a
    US4; T047 y T049 necesitan el notebook completo; T043 necesita el `.ipynb` y el JSON finales de T042.
- **Polish (Phase 8)**: depende de todas las historias deseadas; T056 es lo último.

### User Story Dependencies

- **US1 (P1)**: tras Foundational; sin dependencias de otras historias.
- **US2 (P1)**: tras Foundational; sin dependencias de US1 salvo el orden de secciones del notebook.
- **US3 (P2)**: tras Foundational; usa las mismas tablas que US1 pero no su código.
- **US4 (P2)**: tras US1, US2 y US3.
- **US5 (P2)**: la parte de CI tras T012; la verificación final tras US4.

### Within Each User Story

- Las pruebas se escriben primero y **deben fallar** antes de implementar.
- Esquema y tipos antes que funciones; módulos del paquete antes que las celdas del notebook; ejecución real de la sección al
  final de cada historia.
- Las tareas sobre el `.ipynb` y las anotaciones para las conclusiones (T019, T026, T032) alimentan T039 a T041.

### Parallel Opportunities

- Foundational: T004, T005, T007, T008 y T010 (archivos distintos) a la vez; luego T006, T009 y T011.
- US1: T013 y T014 a la vez; T017 en paralelo con T016.
- US2: T020, T021 y T023 a la vez.
- US3: T027 y T028 a la vez.
- US4: T033 y T034 a la vez.
- US5: T043 y T044 a la vez; T045 y T046 son secuenciales (mismo archivo).
- Polish: T050 y T051 a la vez.
- Con más de una persona: A hace US1, B hace US2, C hace US3 y D el CI de US5, y se reúnen en US4.

---

## Parallel Example: Foundational y User Story 1

```bash
# Foundational: pruebas y módulos de archivos distintos
Task: "Escribir ml/tests/test_config.py"                              # T004
Task: "Crear ml/src/olist_eda/errors.py"                              # T005
Task: "Crear ml/tests/conftest.py con la fixture tables"              # T007
Task: "Escribir ml/tests/test_db.py con el conector falso"            # T008
Task: "Escribir ml/tests/test_report.py parte 1"                      # T010

# User Story 1: pruebas a la vez
Task: "Escribir ml/tests/test_profiling.py"                           # T013
Task: "Escribir ml/tests/test_integrity.py"                           # T014
```

---

## Implementation Strategy

### MVP (US1 + US2)

1. Setup y Foundational.
2. US1: perfil de calidad de las ocho tablas e integridad entre esquemas. **Parar y validar** con el quickstart §1 y §2
   (secciones 0 a 4).
3. US2: valores de pago y estado contrastados con políticas y LAB-41. **Parar y validar** con el quickstart §1 y §2
   (secciones 5 a 7); ya cumple los criterios P1 de la historia (lectura sin CSV, calidad con operaciones vectorizadas y
   valores reales frente a las políticas).

### Entrega incremental

4. US3: entregas, retrasos y reseñas.
5. US4: tabla de discrepancias, acciones y conclusiones sobre la ejecución completa.
6. US5: README, reproducibilidad, preflight, solo lectura y CI.
7. Polish: README raíz, constitución 1.0.2, verificación completa, `validation.md` y pull request.

## Notes

- Cada tarea se marca `[X]` al terminar y se hace un commit atómico por tarea o grupo lógico (Conventional Commits, p. ej.
  `feat(ml): add table profiling`). El título del PR lleva `[LAB-116]` (`CLAUDE.md` §5.1).
- Las pruebas de cada historia deben fallar antes de implementar.
- Esta historia no añade ningún endpoint HTTP, por lo que **no genera `requests.http`**; tampoco añade propiedades de
  configuración a ningún `application*.yml` (las variables `POSTGRES_*` ya existen) ni cambios en `infra/`.
- Las acciones específicas (T040) y las conclusiones (T041) se redactan a mano **después** de la primera ejecución completa
  real; el titular numérico de cada sección lo genera código para no contradecir las tablas (research D-18).
- Las salidas guardadas en el repositorio proceden **siempre** de la ejecución local con el dataset completo
  (`data_scale` = `full`); la ejecución de CI usa las fixtures, escribe en un directorio temporal y nunca las sustituye.
- No modificar `backend/`, `ingest/`, `data/olist`, `data/kb`, `frontend/` ni `infra/`: si el análisis revelara un cambio
  necesario allí, se anota como acción propuesta (T040), no se aplica (FR-025).
- Si T001 muestra que Olist no está cargado y no hay acceso a Kaggle ni a un ZIP, parar y consultarlo: sin el dataset
  completo no se pueden guardar las salidas ni escribir las conclusiones (T039 a T042).
