# Implementation Plan: Dataset golden de preguntas y respuestas

**Branch**: `feature/LAB-27-golden-dataset` | **Date**: 2026-11-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-27-golden-dataset/spec.md`

## Summary

Un proyecto Python nuevo en `eval/` (carpeta que la constitución reserva para el dataset golden y las métricas) con tres
piezas: los **datos** (`eval/golden.jsonl`, 90 casos), un **paquete pequeño y probado** (`shoplab_eval`) que los lee y
los valida, y una **guía de autoría y revisión** (`eval/README.md`, `eval/REVIEW.md`). Las historias siguientes de F2
(LAB-28, LAB-29, LAB-30) añaden sus módulos al mismo paquete y reutilizan el lector.

- **Datos**: un objeto JSON por línea con `id`, `question`, `reference_answer`, `expected_documents` y `category`. Un caso
  es «sin respuesta» cuando `expected_documents` está vacío (no se guarda un campo aparte que pueda contradecirlo).
- **Composición objetivo** (90 casos): 70 con respuesta (14 por tema, de ellos al menos 8 que combinan dos documentos) y
  20 sin respuesta (12 del tema con el dato no publicado y 8 ajenas a la tienda), es decir, el 22 %.
- **Validación**: una función única que aplica todas las reglas de la especificación y devuelve **todos** los fallos con
  el `id` del caso (patrón de `InvalidQuestionsError` de LAB-26); se ejecuta como prueba de `pytest` contra el corpus real
  y desde un comando `validate` que imprime el resumen por categoría y por tipo.
- **CI**: el componente `eval` ya existe en la matriz de Python de `ci.yml`; solo falta que un cambio en `data/kb/**`
  también lo dispare, para detectar documentos que cambian o desaparecen debajo de los casos (spec, edge «Cambio del
  corpus»).

Decisiones y alternativas en [research.md](research.md); tipos en [data-model.md](data-model.md); formato del archivo,
reglas de validación y API de lectura en [contracts/golden-format.md](contracts/golden-format.md); verificación en
[quickstart.md](quickstart.md).

## Technical Context

**Language/Version**: Python 3.12 o superior (como `ingest/`, `notebooks/` y `data/*`), gestionado con `uv`

**Primary Dependencies**: solo biblioteca estándar para leer y validar el JSONL (`json`, `re`, `unicodedata`,
`dataclasses`, `pathlib`) y `pyyaml` para leer el `topic` del front matter del corpus (la misma dependencia que ya usa
`data/kb-tools`). Desarrollo: `pytest`, `pytest-cov`, `ruff`. Ninguna dependencia nueva en `ingest/`, `backend/` ni
`notebooks/`

**Storage**: archivos. Datos: `eval/golden.jsonl` (UTF-8, sin BOM). Entrada de la validación: `data/kb/*.md`. Sin base de
datos ni servicios

**Testing**: `pytest` con conjuntos mínimos generados en `tmp_path` (un corpus de juguete y archivos con un error cada
uno) y una prueba contra el **dataset y el corpus reales**; cobertura `--cov-fail-under=80` como en `ingest/`. Ninguna
prueba usa red, base de datos ni modelo de lenguaje (FR-016)

**Target Platform**: máquina del desarrollador (Windows, macOS, Linux) y CI en Ubuntu; no necesita Docker ni Ollama

**Project Type**: proyecto de datos + biblioteca (paquete instalable con diseño `src/`), sin servicio ni contrato HTTP

**Performance Goals**: validar los 90 casos contra los 24 documentos en menos de 2 segundos; la ampliación manual del
conjunto (SC-007) en menos de 10 minutos con la guía

**Constraints**: determinista (mismo archivo → mismo resultado); informar todos los fallos juntos; sin datos personales
reales ni secretos; el código de producción (`backend/`, `ingest/`) no importa nada de `eval/`; líneas ≤ 121;
identificadores en inglés, datos y documentación en español; los umbrales de la spec viven en una sola estructura
(`GoldenRules`) para no repartirlos por el código

**Scale/Scope**: 90 casos (entre 80 y 100), 24 documentos en cinco temas, 6 categorías (cinco temas + `off_topic`)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | No aplica: no toca módulos del backend ni sus esquemas. |
| II. Hexagonal | No aplica a un conjunto de datos con su lector; se conserva la idea de fondo: el lector y la validación no conocen quién los usa (LAB-28/29/30) y el corpus entra por una ruta, no por una dependencia. |
| III. Inglés y errores tipados | Cumple: identificadores y claves del JSON en inglés; errores tipados propios (`InvalidGoldenError` que agrega los fallos con código y `id`); los textos de los casos y los mensajes para quien edita el archivo, en español. No hay respuestas HTTP. |
| IV. Pruebas con cobertura | Cumple: pruebas unitarias de cada regla, una prueba del dataset real contra el corpus real y una de determinismo; `--cov-fail-under=80`; sin llamadas a modelos. |
| V. RAG con evaluación | Cumple: esta historia **crea** el dataset que la constitución exige («golden dataset in `eval/`»). No cambia prompt, chunking, embeddings ni recuperación, así que no necesita resultado de evaluación; el cálculo y la puerta en los PR llegan en LAB-28 a LAB-30. |
| VI. Observabilidad y datos seguros | Cumple: corpus sintético, preguntas sin datos reales; una regla automática rechaza correos que no sean `@shoplab.example` y secuencias largas de dígitos. No hay secretos ni servicios externos. |
| VII. Simplicidad y ADR | Cumple: sin ADR nuevo, porque la ubicación (`eval/golden.jsonl`) y el formato (JSON Lines) vienen impuestos por el criterio de aceptación y la constitución, y no hay decisión arquitectónica. Se evita la base de datos (la persistencia de ejecuciones es LAB-31), una librería de esquemas y un lector para cada consumidor futuro. |

**Gate**: PASA, sin desviaciones.

**Re-evaluación tras el diseño**: PASA. El diseño añade un proyecto Python aislado, una línea en el filtro de CI y
documentación; no modifica `ingest/`, `backend/`, `frontend/`, `infra/` ni `data/kb/`.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-27-golden-dataset/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── golden-format.md        # formato de golden.jsonl, reglas de validación, comando y API de lectura
├── checklists/requirements.md
└── tasks.md                    # lo genera /speckit-tasks
```

### Source Code (repository root)

```text
eval/
├── README.md                              # formato, cómo añadir/cambiar un caso, qué hacer si cambia el corpus (FR-017)
├── REVIEW.md                              # revisión manual registrada: muestra de 15 casos y búsqueda de los sin respuesta
├── pyproject.toml                         # proyecto uv "shoplab-eval"; ruff y pytest como en ingest/ y notebooks/
├── uv.lock                                # `uv sync --frozen` en CI
├── golden.jsonl                           # el dataset: 90 casos, uno por línea
├── src/shoplab_eval/
│   ├── __init__.py                        # exporta load_golden, validate_golden, GoldenCase, InvalidGoldenError
│   ├── __main__.py                        # `python -m shoplab_eval validate`: imprime el resumen, código de salida 0/1
│   ├── constants.py                       # rutas por defecto, categorías, GoldenRules (los umbrales de la spec)
│   ├── errors.py                          # InvalidGoldenError (agrega Issue con código, id de caso y mensaje)
│   ├── corpus.py                          # ids y tema de cada documento de data/kb (sin INDEX/REVIEW)
│   ├── golden.py                          # GoldenCase y load_golden (lectura JSONL tolerante a líneas vacías)
│   ├── validation.py                      # reglas de caso, de conjunto y de corpus → lista de Issue
│   └── summary.py                         # resumen por categoría y tipo, texto para el comando
└── tests/
    ├── conftest.py                        # corpus de juguete y escritor de archivos JSONL en tmp_path
    ├── test_golden_load.py                # lectura: UTF-8, líneas vacías, JSON inválido con número de línea
    ├── test_validation_case.py            # estructura, categoría, ids, respuesta sin cifras, PII
    ├── test_validation_set.py             # conteos, reparto por categoría y tipo, dos documentos, duplicados, cobertura
    ├── test_corpus.py                     # lectura del corpus real y de un documento sin front matter
    ├── test_summary_cli.py                # resumen y códigos de salida del comando
    └── test_real_dataset.py               # golden.jsonl real contra data/kb real: debe pasar; y determinismo (FR-016)

.github/workflows/ci.yml                    # + 'data/kb/**' en el filtro `eval`
.gitignore                                  # sin cambios (los .coverage y __pycache__ ya están cubiertos)
README.md                                   # sin cambios (la fila de `eval/` ya existe); se enlaza eval/README.md si hace falta
```

**Structure Decision**: un único proyecto Python en `eval/`, con la lógica en un paquete con diseño `src/` y los datos
al lado, en `eval/golden.jsonl` como pide la historia. Se prefiere esto a un script suelto para que LAB-28 a LAB-30
importen el lector (`from shoplab_eval import load_golden`) en lugar de volver a leer el archivo cada uno a su manera, y
a ponerlo en `notebooks/`, que la constitución reserva a experimentos que el código de producción nunca importa. La
herramienta de validación no se copia de `data/kb-tools` (que valida el corpus, no el dataset) pero sigue su misma
convención de proyecto (`pyproject.toml`, `uv.lock`, `ruff` a 121, `pytest`).

## Complexity Tracking

| Elemento | Por qué es necesario | Alternativa más simple descartada porque |
|---|---|---|
| Paquete `shoplab_eval` con lector y validador, en vez de solo el archivo | FR-014 exige una comprobación automática, y LAB-28/29/30 necesitan leer el mismo archivo con la misma interpretación (qué es «sin respuesta», qué es un id válido) | Solo el `.jsonl` y un script de una sola vez: sin pruebas, y cada historia siguiente reimplementaría la lectura con riesgo de divergir |
| Lectura del front matter del corpus con `pyyaml` | La regla «la categoría coincide con el tema de algún documento esperado» necesita el `topic` real de cada documento; deducirlo del prefijo del nombre duplicaría una convención que `kb-tools` ya valida | Deducir el tema del nombre (`returns-01-...`): frágil si cambia la convención de nombres |
| `eval/REVIEW.md` además del README | SC-005 pide una revisión manual **registrada** y la spec la hace una condición de aceptación; el corpus ya sigue el mismo patrón con `data/kb/REVIEW.md` | Dejarlo en la descripción del PR: no queda versionado junto a los datos |
| `data/kb/**` en el filtro `eval` de CI | Sin esto, borrar o renombrar un documento esperado no dispararía la validación en el PR que lo hace | No tocar el filtro: el dataset se pudriría en silencio hasta el siguiente cambio en `eval/` |
