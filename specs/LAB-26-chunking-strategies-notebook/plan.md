# Implementation Plan: Notebook comparativo de estrategias de chunking

**Branch**: `feature/LAB-26-chunking-strategies-notebook` | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-26-chunking-strategies-notebook/spec.md`

## Summary

Un proyecto Python nuevo bajo `notebooks/` con dos piezas: un **paquete pequeño y probado** (`chunking_lab`) que contiene
toda la lógica (tres estrategias de troceado, cliente de embeddings, carga y validación de las 20 preguntas, recuperación
por coseno y métricas) y un **notebook delgado** (`chunking-strategies.ipynb`) que lo orquesta, lo explica y guarda sus
resultados.

- **Tamaño fijo**: ventanas de caracteres con solape sobre el texto completo, sin respetar estructura.
- **Por encabezados**: llama a `parse_markdown` y `split_sections` de `ingest/` sin modificarlos, así que es exactamente el
  troceado de producción (ADR-0016). Una prueba lo compara con `ingest_document` sobre todo el corpus.
- **Semántica**: frases/elementos → embeddings con `bge-m3` (Ollama, ADR-0003/0017) → corte donde la distancia entre
  unidades consecutivas supera un percentil, con tope de tamaño común y fusión de fragmentos muy cortos.

Las tres estrategias se evalúan con las **20 preguntas** (`notebooks/data/questions.toml`, 4 por tema): se recuperan los
`k = 4` chunks más cercanos por coseno y se registra la posición del primero que sea del documento esperado **y** contenga
el pasaje de referencia. El notebook muestra la tabla de 20 filas, hit rate@k y MRR por estrategia, casos que difieren,
una conclusión y los límites. Los resultados se guardan en el notebook (con salidas) y en `results/chunking-results.json`.

Decisiones y alternativas en [research.md](research.md); formatos en [contracts/](contracts/); tipos en
[data-model.md](data-model.md); validación en [quickstart.md](quickstart.md).

## Technical Context

**Language/Version**: Python 3.12 o superior (como `ingest/`, `data/olist` y `data/kb-tools`), gestionado con `uv`

**Primary Dependencies**: `httpx` (cliente de Ollama), `numpy` (coseno), `pandas` y `matplotlib` (tablas y gráficos del
notebook), `markdown-it-py` (lo necesita el parser de `ingest` que se reutiliza), `ipykernel`. Desarrollo: `pytest`,
`pytest-cov`, `ruff`, `nbformat`, `nbconvert`/`nbclient` (ejecutar y validar el notebook), `pdfplumber` (solo para importar
`ingest.app.service` en la prueba de equivalencia). Ninguna dependencia nueva en `ingest/` ni en `backend/`

**Storage**: archivos. Entrada: `data/kb/*.md` y `notebooks/data/questions.toml`. Salida: el `.ipynb` con salidas y
`notebooks/results/chunking-results.json`. Caché local opcional de embeddings en `notebooks/.cache/` (ignorada por git)

**Testing**: `pytest` con embeddings simulados (vectores deterministas) y `httpx.MockTransport` para Ollama; la prueba de
la validación de preguntas y la de equivalencia con `ingest` usan el corpus real. **Ninguna prueba necesita Ollama**; solo
ejecutar el notebook lo necesita

**Target Platform**: máquina del desarrollador (Windows, macOS, Linux) con Ollama en marcha
(`docker compose -f infra/docker-compose.yml up`); CI en Ubuntu solo ejecuta lint y pruebas

**Project Type**: proyecto de experimentos (paquete de biblioteca + notebook), sin servicio ni contrato HTTP

**Performance Goals**: ejecución completa del notebook, con la caché vacía y Ollama ya levantado, < 15 minutos en una
máquina sin GPU (SC-003); unos 650 textos a embeber en total (medido sobre el corpus: 34 629 caracteres; ≈ 400 unidades
semánticas, ≈ 40 chunks de tamaño fijo, 138 por encabezados y un centenar o menos de semánticos, más 20 preguntas); con la
caché llena, segundos

**Constraints**: mismo corpus, tamaño máximo, solape (donde aplica), modelo, `k` y preguntas para las tres estrategias;
estrategias fija y por encabezados deterministas; el código de producción no importa nada de `notebooks/`; sin datos
personales, secretos ni claves; líneas ≤ 121 en el paquete; identificadores en inglés, texto del notebook en español

**Scale/Scope**: 24 documentos Markdown (cinco temas), 3 estrategias, 20 preguntas, `k = 4`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | No aplica: no toca módulos del backend ni sus esquemas. |
| II. Hexagonal | No aplica a un proyecto de experimentos; se conserva la idea de fondo: la lógica vive en el paquete (con el cliente de Ollama detrás de un `Protocol` `Embedder` para simularlo en pruebas) y el notebook solo orquesta. |
| III. Inglés y errores tipados | Cumple: identificadores en inglés; errores tipados propios (`PreflightError`, `InvalidQuestionsError`, `EmbeddingError`) con mensajes accionables en español para quien ejecuta el notebook. No hay respuestas HTTP. |
| IV. Pruebas con cobertura | Cumple: pruebas unitarias del paquete, validación de las preguntas contra el corpus real, equivalencia con `ingest` y comprobación estructural del notebook; `--cov-fail-under=80` como en `ingest/`. Las pruebas son deterministas y no llaman a ningún modelo. |
| V. RAG con evaluación | Cumple sin desviación: es un experimento que **mide**, no cambia chunking, embeddings ni recuperación de producción (FR-022). No sustituye el dataset golden ni la evaluación automática de F2. Su conclusión puede motivar una historia posterior que sí pasará por esa evaluación. |
| VI. Observabilidad y datos seguros | Cumple: corpus sintético, sin datos reales ni secretos; el modelo corre en local (Ollama); el notebook no envía texto a servicios externos. La caché de embeddings contiene texto del corpus sintético, vive fuera de git. |
| VII. Simplicidad y ADR | Cumple: sin ADR nuevo, porque no hay decisión arquitectónica ni de estrategia de recuperación de producción (ADR-0016 sigue vigente). Se evita un servicio, una base de datos y una tabla de resultados; el paquete se limita a lo que el notebook usa. |

**Gate**: PASA, sin desviaciones.

**Re-evaluación tras el diseño**: PASA. El diseño añade un componente Python aislado, un cambio de CI (un componente más
en la matriz de Python) y documentación; no modifica `ingest/`, `backend/`, `frontend/` ni `infra/`.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-26-chunking-strategies-notebook/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── questions-format.md      # formato de data/questions.toml y reglas de validación
│   └── results-format.md        # formato de results/chunking-results.json
├── checklists/requirements.md
└── tasks.md                     # lo genera /speckit-tasks
```

### Source Code (repository root)

```text
notebooks/
├── README.md                              # requisitos, ejecución desde cero, cómo leer la tabla (FR-019)
├── pyproject.toml                         # proyecto uv "chunking-lab"; ruff y pytest como en ingest/
├── uv.lock                                # `uv sync --frozen` en CI
├── chunking-strategies.ipynb              # el notebook, versionado con sus salidas
├── data/
│   └── questions.toml                     # las 20 preguntas (4 por tema), datos versionados (FR-009)
├── results/
│   └── chunking-results.json              # métricas y filas de la última ejecución
├── src/chunking_lab/
│   ├── __init__.py
│   ├── config.py                          # ExperimentConfig: todos los parámetros en un sitio (FR-016)
│   ├── errors.py                          # PreflightError, InvalidQuestionsError, EmbeddingError
│   ├── corpus.py                          # carga data/kb/*.md (sin INDEX/REVIEW) → Document
│   ├── embeddings.py                      # Embedder (Protocol), OllamaEmbedder, caché en disco, preflight
│   ├── strategies/
│   │   ├── __init__.py                    # Chunk y registro de las tres estrategias
│   │   ├── fixed.py                       # ventanas de caracteres con solape
│   │   ├── headings.py                    # adaptador de ingest (parse_markdown + split_sections)
│   │   └── semantic.py                    # unidades → distancias → cortes → tope y fusión
│   ├── questions.py                       # carga TOML y validación contra el corpus (FR-010)
│   ├── retrieval.py                       # índice por estrategia y top-k por coseno con desempate
│   ├── metrics.py                         # acierto de un chunk, posición, hit rate@k, MRR
│   └── report.py                          # tablas pandas, gráficos, lectura/escritura de results.json
└── tests/
    ├── conftest.py                        # añade ingest/ al final de sys.path; fixtures y embedder falso
    ├── test_config.py
    ├── test_corpus.py
    ├── test_fixed.py
    ├── test_headings.py                   # idéntico a ingest_document en los 24 documentos
    ├── test_semantic.py
    ├── test_embeddings.py                 # MockTransport: lotes, caché, preflight y mensajes
    ├── test_questions.py                  # el questions.toml real valida contra data/kb
    ├── test_retrieval_metrics.py          # coseno, desempate, posición, hit rate, MRR
    ├── test_report.py
    └── test_notebook.py                   # estructura del .ipynb y coherencia con results.json

.github/workflows/ci.yml                    # + filtro `notebooks` y componente en la matriz de Python
.gitignore                                  # + notebooks/.cache/
README.md                                   # fila de `notebooks/` y frase de CI
```

**Structure Decision**: un único proyecto Python nuevo en `notebooks/` (carpeta que la constitución reserva para
experimentos), con la lógica en un paquete probable y el notebook como capa de narración. No se toca `ingest/`,
`backend/`, `frontend/` ni `infra/`. El troceado por encabezados se importa de `ingest/` en lugar de copiarse (R2).

## Complexity Tracking

| Elemento | Por qué es necesario | Alternativa más simple descartada porque |
|---|---|---|
| Paquete `chunking_lab` con pruebas **y** notebook | Un notebook con la lógica dentro no se puede probar ni revisar en un diff; el paquete deja la lógica en `.py` y el notebook cuenta la historia | Todo en celdas: sin pruebas, sin lint, diffs ilegibles y errores silenciosos en las métricas |
| Importar `parse_markdown` y `split_sections` de `ingest/` con `sys.path` | FR-004 exige reutilizar el troceado de producción; así el resultado no diverge si `ingest` cambia | Copiar el código (se desincroniza); llamar al servicio HTTP (exige otro servicio levantado y un puerto); empaquetar `ingest` (`package = false`, nombre `app` genérico) |
| Caché de embeddings en disco | La estrategia semántica embebe unas 400 unidades; repetir el notebook sin caché cuesta minutos cada vez | Sin caché: más simple, pero hace penosa la iteración y la comparación de dos ejecuciones |
| Componente nuevo en la matriz de Python de CI | Las pruebas del paquete deben correr en cada PR que toque el notebook, `ingest/app/` o el corpus | No probarlas en CI: la validación de las preguntas contra `data/kb` dejaría de protegerse |
