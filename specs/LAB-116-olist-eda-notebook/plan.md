# Implementation Plan: Análisis exploratorio de Olist con Pandas y NumPy

**Branch**: `feature/LAB-116-olist-eda-notebook` | **Date**: 2026-11-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-116-olist-eda-notebook/spec.md`

## Summary

Primer contenido del directorio `ml/` de la épica F8: un **proyecto Python con uv** que reúne un **paquete pequeño y
probado** (`olist_eda`) con toda la lógica del análisis y un **notebook delgado** (`olist-eda.ipynb`) que lo orquesta, lo
explica y guarda sus salidas.

- **Lectura**: conexión **de solo lectura** a Postgres (mismas variables `POSTGRES_*` y valores locales que el cargador de
  LAB-17); las ocho tablas de `orders` y `catalog` se leen a DataFrames de Pandas. Nunca se leen ni copian CSV. El cruce
  entre esquemas (ítem → producto/vendedor/categoría) se hace en Pandas, no en SQL, como lo haría un servicio extraído.
- **Calidad** (historia 1): perfil por tabla con operaciones vectorizadas (nulos esperados frente a inesperados según
  reglas declaradas, duplicados por clave natural y por fila completa, tipos, rangos y percentiles) e integridad entre
  esquemas con `isin`.
- **Valores** (historia 2): frecuencias de `payment_type` y `order_status`, contrastadas con lo que dicen las políticas
  (`data/kb`, extraído por un parser de Markdown probado contra el corpus real) y con los estados de la consulta de pedidos
  de LAB-41 (declarados en un archivo de datos porque LAB-41 aún no existe).
- **Entregas y reseñas** (historia 3): tiempos real y estimado, retraso (`entrega real > estimada`), resumen por estado
  geográfico del cliente y por categoría principal del pedido, y puntuación de reseñas frente al retraso.
- **Discrepancias** (historia 4): detectores que generan hallazgos tipados; cada uno resuelve una **acción** desde un archivo
  de datos curado (regla específica → regla por tipo → error), de modo que ninguno queda sin acción por construcción.
- **Ejecución** (historia 5): preflight que se detiene antes de calcular; en CI, un job nuevo con Postgres de servicio aplica
  las migraciones reales de `orders` y `catalog`, carga las **fixtures de LAB-17** con el propio cargador y ejecuta el
  notebook con `nbconvert --execute`. Las salidas guardadas en el repo proceden de una ejecución local con el dataset
  completo.

Decisiones y alternativas en [research.md](research.md); tipos y reglas en [data-model.md](data-model.md); formatos en
[contracts/](contracts/); validación en [quickstart.md](quickstart.md).

## Technical Context

**Language/Version**: Python 3.12 o superior (como `ingest/`, `data/olist` y `notebooks/`), gestionado con `uv`

**Primary Dependencies**: `pandas>=2.2` y `numpy>=2.0` (análisis; exigidos por la historia), `psycopg[binary]>=3.2` (lectura
de Postgres, el mismo driver que el cargador), `matplotlib>=3.9` (gráficos), `ipykernel`. Desarrollo: `pytest`,
`pytest-cov`, `ruff`, `nbformat`, `nbconvert`, `nbclient`. Sin SQLAlchemy ni dependencias nuevas en `ingest/`, `eval/`,
`notebooks/`, `data/olist` ni `backend/`

**Storage**: PostgreSQL 16 (esquemas `orders` y `catalog`), solo lectura. Entrada adicional: `data/kb/*.md` (políticas) y dos
archivos TOML propios (`order-tool-states.toml`, `discrepancy-actions.toml`). Salida: el `.ipynb` con salidas y
`ml/notebooks/results/olist-eda-summary.json`

**Testing**: `pytest` sobre DataFrames construidos en memoria (sin base de datos ni red), un conector falso para
`db.py`, el corpus real para el parser de políticas y comprobaciones estructurales del `.ipynb` guardado. La cobertura del
paquete se exige al 80 % como en `ingest/` y `notebooks/`. La **ejecución real** del notebook contra Postgres la cubre el
job de CI `ml-notebook`

**Target Platform**: máquina del desarrollador (Windows, macOS, Linux) con la base de `docker compose` y Olist cargado; CI en
Ubuntu con un servicio Postgres

**Project Type**: proyecto de análisis (paquete de biblioteca + notebook), sin servicio ni contrato HTTP

**Performance Goals**: ejecución completa con el dataset completo en local < 5 minutos en una máquina modesta (SC-005 da
10 minutos de margen a una persona que sigue el README); en CI con las fixtures, < 2 minutos incluyendo la preparación de la
base de datos. Volumen real: ~99 000 pedidos, ~113 000 ítems, ~104 000 pagos, ~100 000 reseñas, ~99 000 clientes, ~33 000
productos y ~3 000 vendedores, cifras que caben holgadamente en memoria

**Constraints**: lectura de solo lectura (`default_transaction_read_only`); cálculos de perfilado vectorizados sin bucles por
fila; sin CSV ni volcados masivos en el repo ni en las salidas (solo agregados y muestras mínimas sin identificadores);
ningún código de producción importa `ml/`; líneas ≤ 121 en el paquete; identificadores en inglés y texto del notebook en
español

**Scale/Scope**: 8 tablas en 2 esquemas, 2 columnas categóricas a contrastar, 24 documentos de corpus, ~10 detectores de
discrepancias, un notebook de 14 secciones

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | **Cumple con una nota.** El principio rige los módulos del backend. Este proyecto es una herramienta de análisis externa que lee ambos esquemas en **solo lectura** y los cruza en memoria; no añade dependencias entre módulos ni toca sus tablas (FR-025). La conexión de solo lectura lo garantiza en tiempo de ejecución, no solo por convención. |
| II. Hexagonal | No aplica a un proyecto de análisis; se conserva la idea de fondo: la base de datos queda detrás de un único módulo (`db.py`) y todo lo demás opera sobre DataFrames, por lo que se prueba sin base de datos. |
| III. Inglés y errores tipados | Cumple: identificadores en inglés; errores tipados propios (`PreflightError`, `MissingActionError`, `PolicyParseError`) con mensajes accionables en español para quien ejecuta el notebook. Sin respuestas HTTP. |
| IV. Pruebas con cobertura | Cumple: pruebas unitarias deterministas del paquete (≥ 80 %), parser contra el corpus real, estructura del notebook guardado y ejecución real del notebook en CI. Sin llamadas a modelos. |
| V. RAG con evaluación | Cumple sin desviación: no cambia prompts, chunking, embeddings ni recuperación. Su salida son **propuestas** de ajuste del corpus, que pasarán por la evaluación de F2 cuando se implementen. |
| VI. Observabilidad y datos seguros | Cumple: dataset público y anonimizado, no versionado (CC BY-NC-SA); las salidas guardadas solo contienen agregados y muestras sin identificadores (lo verifica una prueba); sin secretos, las credenciales salen de variables de entorno con los valores locales ya públicos de `docker compose`. |
| VII. Simplicidad y ADR | Cumple con una acción: `ml/` como directorio de primer nivel **no está en la constitución** (cita `notebooks/` para experimentos). Se resuelve con una enmienda PATCH (1.0.1 → 1.0.2) en este mismo PR, sin ADR: no es una decisión arquitectónica del producto sino de organización del repositorio. Se evita un servicio, un esquema nuevo y un ORM. |

**Gate**: PASA, con una enmienda de la constitución como tarea.

**Re-evaluación tras el diseño**: PASA. El diseño añade un proyecto Python aislado, un job de CI y documentación; no modifica
`backend/`, `ingest/`, `frontend/`, `infra/`, `data/kb` ni el cargador de LAB-17. La enmienda de la constitución y la fila del
README son las únicas ediciones fuera de `ml/` y de `specs/`, junto con `.github/workflows/ci.yml`.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-116-olist-eda-notebook/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── notebook-contract.md     # secciones, parámetros, variables de entorno y preflight del notebook
│   ├── summary-format.md        # formato de results/olist-eda-summary.json
│   └── data-files-format.md     # formato de order-tool-states.toml y discrepancy-actions.toml
├── checklists/requirements.md
└── tasks.md                     # lo genera /speckit-tasks
```

### Source Code (repository root)

```text
ml/
├── README.md                                # requisitos, ejecución desde cero, cómo leer el análisis, CI (FR-021)
├── pyproject.toml                           # proyecto uv "shoplab-ml"; ruff y pytest como en notebooks/
├── uv.lock                                  # `uv sync --frozen` en CI
├── ci/
│   └── prepare-database.sh                  # solo CI: migraciones de orders/catalog + fixtures con el cargador
├── notebooks/
│   ├── olist-eda.ipynb                      # el notebook, versionado con sus salidas
│   ├── data/
│   │   ├── order-tool-states.toml           # estados de la consulta de pedidos (LAB-41), declarados
│   │   └── discrepancy-actions.toml         # acción por hallazgo (específica y por tipo)
│   └── results/
│       └── olist-eda-summary.json           # cifras clave y hallazgos de la última ejecución
├── src/olist_eda/
│   ├── __init__.py
│   ├── config.py                            # AnalysisSettings: conexión, umbrales y rutas (variables de entorno)
│   ├── errors.py                            # PreflightError, PolicyParseError, MissingActionError
│   ├── db.py                                # conexión de solo lectura, preflight y load_tables → dict[str, DataFrame]
│   ├── schema.py                            # TABLES: claves naturales, columnas sustitutas y reglas de nulos
│   ├── profiling.py                         # filas, nulos, duplicados, tipos y rangos (vectorizado)
│   ├── integrity.py                         # referencias huérfanas entre orders y catalog
│   ├── policies.py                          # parser de data/kb → PolicyReference (declarados y mencionados)
│   ├── categorical.py                       # frecuencias y cobertura de payment_type y order_status
│   ├── delivery.py                          # tiempos, coherencia de fechas, retraso, resúmenes y reseñas
│   ├── findings.py                          # detectores → Finding; resolución de acciones
│   └── report.py                            # figuras, tablas de presentación y lectura/escritura del resumen JSON
└── tests/
    ├── conftest.py                          # DataFrames mínimos en memoria y conector falso
    ├── test_config.py
    ├── test_db.py                           # conector falso: solo lectura, preflight y mensajes
    ├── test_profiling.py
    ├── test_integrity.py
    ├── test_policies.py                     # el corpus real expone 4 tipos de pago y 8 estados
    ├── test_categorical.py
    ├── test_delivery.py
    ├── test_findings.py                     # cada tipo de hallazgo resuelve acción; falta una → error
    ├── test_report.py
    └── test_notebook.py                     # estructura, sin bucles por fila, sin identificadores, coherencia

.github/workflows/ci.yml                      # + filtro `ml`, componente `ml` en la matriz y job `ml-notebook`
.specify/memory/constitution.md               # enmienda PATCH 1.0.2: `ml/` en Technology & Architecture Constraints
README.md                                     # fila de `ml/` y frase de CI
.gitignore                                    # sin cambios previstos (`.venv/` y `.ipynb_checkpoints/` ya están)
```

**Structure Decision**: un único proyecto uv en `ml/` (no uno por notebook) porque las historias siguientes de F8
(scikit-learn, PyTorch, MLflow, servicio de inferencia) compartirán dependencias y datos; hoy solo se declaran las que usa
este análisis, y las demás las añadirá cada historia con su propio grupo de dependencias. El paquete `olist_eda` contiene la
lógica del análisis; el notebook solo orquesta y explica, como en LAB-26 (`notebooks/`, que no cambia).

## Complexity Tracking

> Sin violaciones del Constitution Check que haya que justificar. La única acción es la enmienda PATCH de la constitución
> para reconocer `ml/`, listada en las tareas.
