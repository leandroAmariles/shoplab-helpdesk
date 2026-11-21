# Implementation Plan: Dataset etiquetado de intenciones del cliente

**Branch**: `feature/LAB-117-intent-labeled-dataset` | **Date**: 2026-11-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-117-intent-labeled-dataset/spec.md`

## Summary

Segundo contenido de `ml/` (F8): un **dataset de mensajes etiquetados por intención** (`ml/data/intents/`) y un **paquete
Python pequeño y probado** (`intent_dataset`) que lo construye, lo enmascara, lo parte y lo valida. El dataset son archivos
de datos versionados; el paquete existe para que sea reproducible, auditable y que **CI falle si alguien lo rompe**.

- **Construcción** (historia 1): cuatro orígenes (`golden`, `olist_review`, `manual_seed`, `paraphrase`) pasan por un flujo de
  *candidatos → revisión manual → dataset*. Las paráfrasis las genera el modelo local por el gateway de LAB-84; los
  candidatos se exportan a un CSV que revisa una persona (la decisión de cada fila es suya, nunca del modelo) y `build` solo
  admite lo aceptado y revisado. Los candidatos de Olist se enmascaran **al extraerse**, así que ningún texto sin enmascarar se
  escribe nunca en disco.
- **Etiquetas** (historia 2): guía de etiquetado con orden de prioridad entre clases y casos límite; muestra de 50 casos
  estratificada, congelada con la etiqueta del primer anotador; el segundo etiqueta a ciegas y se calcula el **kappa de Cohen**
  (fórmula propia, probada contra casos conocidos) antes de resolver los desacuerdos.
- **Partición** (historia 3): asignación **estratificada por clase e idioma y por grupos** (algoritmo propio, determinista,
  con semilla), con los textos idénticos normalizados unidos al mismo grupo. La partición **se guarda en cada ejemplo**: añadir
  ejemplos después no mueve los existentes.
- **Datos personales** (historia 4): `masking.py` sustituye por patrón correos, teléfonos, CPF/CNPJ, CEP, identificadores de
  32 hex y enlaces por marcas fijas (idempotente). Los nombres los cubre una revisión manual obligatoria de cada comentario de
  Olist (columna `pii_checked`). La misma función lo comprueba en el validador.
- **Ficha y validación** (historia 5): `DATASHEET.md` con un bloque de cifras **generado** por código (el validador falla si no
  coincide con el dataset) y `validate`, que reúne todas las reglas de FR-023 y corre en CI como una prueba de `pytest` sobre los
  archivos reales, sin red, base de datos ni modelo.

Decisiones y alternativas en [research.md](research.md); entidades y reglas en [data-model.md](data-model.md); formatos y
comandos en [contracts/](contracts/); validación en [quickstart.md](quickstart.md).

## Technical Context

**Language/Version**: Python 3.12 o superior, gestionado con `uv` (el proyecto `ml/` existente, `shoplab-ml`)

**Primary Dependencies**: ninguna nueva. Biblioteca estándar (`csv`, `json`, `random`, `re`, `unicodedata`, `urllib`) para
todo el paquete; `psycopg` (ya en `ml/`) solo para leer los comentarios de reseñas; reutiliza `olist_eda.config` y
`olist_eda.db.connect` para la conexión de solo lectura. Sin scikit-learn, httpx ni librerías de enmascarado: se evita
arrastrar dependencias que LAB-118 decidirá por su cuenta

**Storage**: archivos en `ml/data/intents/`: `intents.jsonl` (el dataset), `annotations/` (muestra doble-etiquetada y acuerdo),
`LABELING-GUIDE.md`, `DATASHEET.md` y `REVIEW.md`. Directorio de trabajo `ml/data/intents/work/` **ignorado por git** (CSV de
revisión, borradores de paráfrasis, hoja del segundo anotador). Entrada: `eval/golden.jsonl` (leído como archivo, no
importado) y `orders.order_reviews` (solo lectura)

**Testing**: `pytest` determinista sin red ni base de datos: unitarias por módulo, un cliente HTTP falso para las paráfrasis, un
conector falso para las reseñas y **una prueba sobre el `intents.jsonl` real** que ejecuta todas las reglas (FR-019, FR-023).
Cobertura del paquete ≥ 80 % como el resto de `ml/`. Las llamadas al modelo no ocurren en pruebas ni en CI

**Target Platform**: máquina del desarrollador con la pila de `docker compose` (Postgres con Olist cargado y gateway de modelos
para generar paráfrasis); CI en Ubuntu solo valida los archivos

**Project Type**: biblioteca + CLI de preparación de datos (`python -m intent_dataset`); sin servicio ni contrato HTTP

**Performance Goals**: `validate` y la partición sobre ~700 ejemplos en menos de 2 segundos; la generación de paráfrasis
(~250 padres × ~2 variantes) tarda lo que el modelo local, una sola vez y fuera de CI

**Constraints**: ningún texto sin enmascarar se escribe en disco; el dataset no importa ni es importado por producción
(`backend/`, `ingest/`); `eval/golden.jsonl` no se modifica; líneas ≤ 121; identificadores en inglés; textos de la guía y la
ficha en español; sin secretos (la clave del gateway se lee del entorno y no tiene valor por defecto)

**Scale/Scope**: objetivo ~700 ejemplos (mínimo exigido 600), 5 clases, 2 idiomas, 4 orígenes, 3 particiones, 50 casos de
acuerdo; ~12 módulos pequeños y 9 comandos de CLI

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | No aplica: no toca módulos del backend ni sus tablas. Solo **lee** `orders.order_reviews` en solo lectura, como LAB-116. |
| II. Hexagonal | No aplica a un proyecto de datos. Se conserva el criterio de fondo: lo externo (base de datos, gateway) queda tras dos módulos pequeños (`sources`/`paraphrase`) con dependencias inyectables, y el resto opera sobre objetos en memoria. |
| III. Inglés y errores tipados | Cumple: identificadores en inglés (las **etiquetas** `politica`, `producto`, `pedido`, `escalado`, `charla` son valores de datos fijados por la historia, no identificadores). Errores tipados propios (`InvalidDatasetError`, `ReviewError`, `ParaphraseError`) con mensajes accionables en español. |
| IV. Pruebas con cobertura | Cumple: pruebas antes o con el código, ≥ 80 %, deterministas, con el modelo y la base **falsos**; una prueba valida el dataset real en cada PR que toque `ml/**`. |
| V. RAG con evaluación | Cumple sin desviación: no cambia prompts, chunking ni recuperación. Una nota: los ejemplos derivados del golden **no** lo modifican, y su solape con el entrenamiento queda registrado para LAB-121 (R9). |
| VI. Observabilidad y datos seguros | Cumple y es el centro de la historia: enmascarado por patrón + revisión manual, validador que falla ante cualquier coincidencia, dataset público (Olist) con licencia declarada, sin secretos. Desviación aclarada: el criterio habla de «el mismo enmascarado que en las trazas» y **no existe** (LAB-64 lo dejó fuera); aquí se define el enmascarado mínimo (R3). |
| VII. Simplicidad y ADR | Cumple: sin dependencias, servicios ni esquemas nuevos; sin cambios en `infra/`. No hay decisión arquitectónica del producto, así que **no se escribe ADR**; las decisiones de datos quedan en `research.md` y en la ficha. |

**Gate**: PASA, sin violaciones y sin enmienda de la constitución (`ml/` ya figura en ella desde LAB-116).

**Re-evaluación tras el diseño**: PASA. El diseño añade un paquete, datos y documentos bajo `ml/` y `specs/`, más una línea en
`.gitignore`, una fila en el README de `ml/` y otra en el del repositorio. No toca `backend/`, `ingest/`, `frontend/`,
`infra/`, `eval/`, `data/` ni `.github/workflows/` (el job `python (ml)` ya ejecuta `ruff` y `pytest` en cada cambio de
`ml/**`).

## Project Structure

### Documentation (this feature)

```text
specs/LAB-117-intent-labeled-dataset/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── dataset-format.md        # intents.jsonl, annotations/, CSV de revisión, bloque de cifras de la ficha
│   └── cli-contract.md          # comandos de python -m intent_dataset, entradas, salidas y códigos de salida
├── checklists/requirements.md
└── tasks.md                     # lo genera /speckit-tasks
```

### Source Code (repository root)

```text
ml/
├── README.md                              # + sección «Dataset de intenciones» (cómo construir, revisar y validar)
├── pyproject.toml                         # + paquete src/intent_dataset y --cov=intent_dataset
├── data/
│   └── intents/
│       ├── intents.jsonl                  # el dataset: ≥ 600 ejemplos, uno por línea, con su partición
│       ├── LABELING-GUIDE.md              # definiciones, prioridad entre clases y casos límite (FR-010)
│       ├── DATASHEET.md                   # ficha del dataset; bloque de cifras generado (FR-021)
│       ├── REVIEW.md                      # registro de la revisión manual (quién, cuándo, qué), como eval/REVIEW.md
│       ├── annotations/
│       │   ├── agreement-sample.jsonl     # los 50 casos: etiqueta A, etiqueta B, final y motivo
│       │   └── agreement.json             # kappa, n, matriz de desacuerdos, tipo del anotador B
│       └── work/                          # IGNORADO por git: candidates.csv, paraphrase drafts, annotator-b.csv
├── src/intent_dataset/
│   ├── __init__.py
│   ├── __main__.py                        # CLI: candidates, paraphrase, build, split, sample, annotate, agreement, stats, validate
│   ├── constants.py                       # LABELS, LANGUAGES, SOURCES, SPLITS, DatasetRules (mínimos, proporciones, semilla)
│   ├── errors.py                          # InvalidDatasetError, ReviewError, ParaphraseError, Issue
│   ├── text.py                            # normalize_text (clave de duplicado) y utilidades de texto
│   ├── masking.py                         # mask_text (idempotente) y find_personal_data (lo usa el validador)
│   ├── dataset.py                         # Example, load/dump de JSONL con orden y formato estables
│   ├── sources.py                         # golden → candidatos (mapeo de categoría), reseñas de Olist (solo lectura, enmascaradas)
│   ├── paraphrase.py                      # cliente del gateway (urllib, inyectable), prompt, filtros de salida
│   ├── review.py                          # exportar candidatos a CSV e importar las decisiones (accept/edit/reject)
│   ├── splitting.py                       # grupos (unión por texto normalizado) y asignación estratificada estable
│   ├── agreement.py                       # muestra de 50, kappa de Cohen, matriz de desacuerdos, informe
│   ├── validation.py                      # todas las reglas de FR-023 → lista de Issue
│   └── datasheet.py                       # cifras por clase/idioma/partición/origen; render y comprobación del bloque
└── tests/intent_dataset/
    ├── conftest.py                        # ejemplos mínimos en memoria; cliente HTTP y conector falsos
    ├── test_masking.py                    # un caso por tipo de dato personal, idempotencia, texto intacto
    ├── test_text.py
    ├── test_dataset.py                    # JSONL: orden estable, claves desconocidas, ida y vuelta
    ├── test_sources.py                    # mapeo del golden real; reseñas con conector falso (enmascaradas al leer)
    ├── test_paraphrase.py                 # prompt, JSON roto, PII inventada → descartada, casi duplicados → descartados
    ├── test_review.py                     # decisiones, pii_checked obligatorio en Olist, ediciones
    ├── test_splitting.py                  # sin fuga de grupos, estratos, determinismo, estabilidad al añadir
    ├── test_agreement.py                  # kappa contra valores conocidos, muestra estratificada y reproducible
    ├── test_validation.py                 # un defecto provocado por regla (SC-009)
    ├── test_datasheet.py                  # el bloque coincide; uno alterado falla
    └── test_real_dataset.py               # ejecuta validate sobre intents.jsonl, la guía y la ficha reales

.gitignore                                 # + ml/data/intents/work/
README.md                                  # fila de `ml/`: añade el dataset de intenciones
```

**Structure Decision**: se extiende el proyecto uv `ml/` (no se crea otro) con un segundo paquete, `intent_dataset`, junto a
`olist_eda`. LAB-118 creará `ml/intent` (entrenamiento) y leerá `ml/data/intents/intents.jsonl`; el dataset no depende de ese
paquete y viceversa solo por el formato de archivo. La lógica vive en el paquete (probada); los **datos** son archivos
versionados, no código. El directorio `ml/data/` es nuevo y distinto de `data/` (corpus y cargador) y de `ml/notebooks/data/`
(TOML del notebook).

## Complexity Tracking

> Sin violaciones del Constitution Check que haya que justificar.

| Decisión a vigilar | Por qué | Alternativa más simple descartada |
|---|---|---|
| Algoritmo de partición propio (~60 líneas) | scikit-learn no está en `ml/` y `StratifiedGroupKFold` no da una partición 70/15/15 estable al añadir ejemplos (R6) | Dependencia de scikit-learn: la decide LAB-118; adelantarla acopla las dos historias |
| Reutilizar la clave `eval` del gateway para generar paráfrasis | Evita cambios en `infra/` y en el clúster (R5) | Crear la clave `ml` en `keys.yaml`: obliga a tocar `.env.example`, compose y el secreto del clúster para un paso que solo corre en local |
