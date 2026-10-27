# Implementation Plan: Carga del dataset Olist en esquemas por contexto

**Branch**: `feature/LAB-17-olist-dataset-by-context` | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-17-olist-dataset-by-context/spec.md`

## Summary

Crear las tablas de los esquemas `orders` y `catalog` como migraciones Flyway del módulo dueño de cada uno
(`V2__…` junto al `V1__baseline.sql` de LAB-105) y un cargador Python en `data/olist/` que descarga el dataset público
de Olist y lo reparte entre ambos esquemas, sin claves foráneas entre ellos (el ítem de pedido guarda `product_id` y
`seller_id` como ids).

Enfoque técnico (detalle en [research.md](research.md)):

- **Estructura = backend, datos = cargador.** Las tablas las crea el backend al arrancar (un Flyway por módulo, ya
  existente); el cargador nunca ejecuta DDL: verifica que las tablas existen y falla con un mensaje accionable si no.
- **Una transacción por esquema**: `TRUNCATE` de las tablas del esquema + `COPY` de los CSV + confirmación. Una
  interrupción deja el esquema como estaba (sin datos parciales) y volver a ejecutar da el mismo estado (FR-008).
- **Descarga con caché**: archivo ZIP en `data/raw/` (ya ignorado por git); se reutiliza si existe. Credenciales de
  Kaggle por entorno; opción `--source` para usar un ZIP o carpeta ya descargados (sin red).
- **Stock sintético determinista** derivado de un hash del `product_id` (~10 % en 0) y **precio de referencia** =
  mediana de los precios de los ítems del producto, calculados por el cargador.
- **Pruebas**: pytest con Testcontainers aplicando los `V*.sql` reales a una Postgres desechable y CSV de ejemplo
  sintéticos; en el backend, ampliar las pruebas de esquemas existentes (S1–S5 de LAB-105) y comprobar las tablas.

## Technical Context

**Language/Version**: Python 3.12 (cargador, `data/olist/`); Java 21 / Spring Boot 4.1.2 solo para las migraciones SQL
(sin clases Java nuevas).

**Primary Dependencies**: cargador: `psycopg[binary]` 3.x (COPY nativo), `httpx` (descarga), `typer` (CLI);
desarrollo: `pytest`, `testcontainers[postgres]`, `ruff`. Gestor `uv`, igual que la CI existente. Backend: sin
dependencias nuevas (Flyway y el migrador por módulo vienen de LAB-105).

**Storage**: PostgreSQL 16 + pgvector (ADR-0002), esquemas `orders` y `catalog`; archivos del dataset en
`data/raw/` (fuera de git).

**Testing**: pytest + Testcontainers (Postgres real con el DDL de producción); JUnit/Testcontainers de LAB-105
(`ModuleSchemasIntegrationTests`, `SchemaIsolationTests`) ampliados. Cobertura JaCoCo >= 80 % no se ve afectada (no hay
código Java nuevo).

**Target Platform**: desarrollo local (Windows/Linux) con la Postgres de `infra/docker-compose.yml`.

**Project Type**: herramienta de línea de comandos (cargador de datos) + migraciones SQL del monolito modular.

**Performance Goals**: carga completa (~100 mil pedidos, ~112 mil ítems, ~400 mil filas en total) en menos de
10 minutos (SC-001); con `COPY` se espera menos de 1 minuto sin contar la descarga.

**Constraints**: memoria acotada (lectura por flujo, sin cargar los CSV enteros salvo el de ítems para la mediana de
precios); credenciales solo por entorno; el dataset no se versiona; sin FKs entre esquemas; las migraciones solo
tocan su propio esquema y pasan el lint S1–S3.

**Infrastructure**: sin cambios. La carga es solo local (FR-016); `shoplab-infra` no se toca. El cargador usa las
mismas variables `POSTGRES_*` que `infra/docker-compose.yml`.

**Scale/Scope**: 8 tablas en `orders`/`catalog` (cliente, pedido, ítem, pago, reseña; producto, categoría, vendedor),
2 migraciones SQL, 1 paquete Python pequeño (~6 módulos), ~25 pruebas.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación | Estado |
|---|---|---|
| I. Monolito modular primero | Cada módulo es dueño de su esquema y de sus migraciones; sin FKs ni joins entre esquemas; el cargador es una herramienta externa de siembra, no un módulo | Pasa |
| II. Hexagonal y dominio puro | No se añade código Java ni se toca `domain`; las tablas son infraestructura de cada módulo | Pasa |
| III. Identificadores en inglés, errores tipados | Tablas y columnas en inglés; el cargador usa excepciones propias con mensaje accionable. No hay errores HTTP nuevos | Pasa |
| IV. Test-first, cobertura >= 80 % | Pruebas del cargador contra Postgres real y pruebas negativas (FK cruzada, esquema sin migrar); el backend no pierde cobertura | Pasa |
| V. RAG con evaluación | No aplica (F0, sin cambios de recuperación) | N/A |
| VI. Observabilidad y datos seguros | Credenciales de Kaggle y de BD por entorno; dataset fuera de git; solo datos públicos anonimizados; resumen de carga sin datos personales en los logs | Pasa |
| VII. Simplicidad y ADR | Sin decisión que requiera ADR nuevo (aplica el ADR-0015). El cargador escribe en dos esquemas por ser siembra de desarrollo; se documenta en `docs/` y en research D-1 | Pasa |

Sin violaciones; **Complexity Tracking** no aplica.

**Re-evaluación post-diseño**: sin cambios; el diseño de Fase 1 no añade piezas fuera de lo anterior.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-17-olist-dataset-by-context/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   ├── loader-cli.md
│   └── schema-ddl.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
backend/src/main/resources/db/migration/
├── orders/
│   ├── V1__baseline.sql                 # existente (LAB-105)
│   └── V2__olist_orders_tables.sql      # NUEVO: customers, orders, order_items, order_payments, order_reviews
└── catalog/
    ├── V1__baseline.sql                 # existente (LAB-105)
    └── V2__olist_catalog_tables.sql     # NUEVO: product_categories, products, sellers

backend/src/test/java/com/shoplab/helpdesk/schema/
├── ModuleSchemasIntegrationTests.java   # AMPLIADA: tablas esperadas en orders y catalog
└── SchemaIsolationTests.java            # AMPLIADA: ítem de pedido sin FK hacia catalog

data/
├── kb/                                  # existente
├── raw/                                 # ignorado por git: ZIP y CSV descargados
└── olist/                               # NUEVO: cargador
    ├── pyproject.toml
    ├── uv.lock
    ├── README.md
    ├── src/olist_loader/
    │   ├── cli.py                       # comando `load`
    │   ├── download.py                  # descarga con caché / --source
    │   ├── preflight.py                 # esquemas y tablas presentes
    │   ├── transform.py                 # stock sintético, precio de referencia, limpieza
    │   ├── load.py                      # transacción por esquema, TRUNCATE + COPY
    │   └── report.py                    # resumen y referencias huérfanas
    └── tests/
        ├── conftest.py                  # Postgres desechable + aplica V*.sql reales
        ├── fixtures/                    # CSV de ejemplo sintéticos (mismas cabeceras que Olist)
        └── test_*.py

docs/olist-dataset.md                    # NUEVO: cómo cargar, reparto de tablas, stock sintético
.github/workflows/ci.yml                 # AJUSTE: añadir `data/olist` al job python (filtro y matriz)
README.md                                # AJUSTE: sección breve de datos de ejemplo
```

**Structure Decision**: el cargador va en `data/olist/` (la constitución sitúa ahí los cargadores) con proyecto Python
propio; no se usa `ingest/` porque añadirle un `pyproject.toml` activaría en su `Dockerfile` la rama real
(`uv sync --frozen` y `uvicorn app.main:app`, módulo aún inexistente) y rompería la imagen de relevo. Las migraciones
van en la carpeta de cada módulo, como exige el ADR-0015.

## Complexity Tracking

No aplica: sin violaciones de la constitución.
