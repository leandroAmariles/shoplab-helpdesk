# Implementation Plan: Métricas de retrieval (hit rate, MRR, recall)

**Branch**: `feature/LAB-28-retrieval-metrics` | **Date**: 2026-11-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-28-retrieval-metrics/spec.md`

## Summary

Un comando nuevo en el paquete `shoplab_eval` de `eval/` (`python -m shoplab_eval retrieval`) que ejecuta la búsqueda **real**
del backend sobre cada pregunta con respuesta del dataset golden, convierte los fragmentos devueltos en una lista ordenada de
documentos y calcula hit rate@k, recall@k y MRR, por categoría y en conjunto. Escribe dos CSV (resumen y detalle) y falla con
claridad cuando el entorno no permite una medición fiable.

La decisión central es **cómo llega `eval/` (Python) a la búsqueda del backend** (FR-002). Hoy esa búsqueda es una API
interna del módulo `retrieval` (`KnowledgeSearchApi`) sin endpoint HTTP, y ADR-0015 prohíbe tocar el esquema de otro módulo.
Se resuelve con un **endpoint de solo lectura en `retrieval`**, `GET /api/knowledge/search`, que expone el mismo caso de uso
que ya consume el chat. Es el único cambio en `backend/`: no toca el esquema, el chat ni la lógica de búsqueda.

- **Backend** (cambio pequeño): `KnowledgeSearchController` en `retrieval/infrastructure/in/rest`, dos DTO y un método
  `embeddingModel()` en `SearchChunksServicePort` para informar con qué modelo se buscó. Más ADR-0024 que registra la decisión.
- **Medición** (`eval/`, Python): cálculo puro de métricas, cliente HTTP con la biblioteca estándar, orquestación con
  comprobación previa del índice, escritura de CSV y subcomando `retrieval`.
- **Determinismo**: CSV sin marcas de tiempo, números con cuatro decimales fijos, orden estable y fin de línea `\n`.
- **CI**: sin cambios de flujo. Las pruebas de `eval` no necesitan backend (cliente probado contra un servidor HTTP de
  juguete); la medición contra un backend vivo se ejecuta a mano ahora y automática en LAB-30.

Decisiones y alternativas en [research.md](research.md); tipos en [data-model.md](data-model.md); endpoint y CSV en
[contracts/](contracts/); verificación en [quickstart.md](quickstart.md).

## Technical Context

**Language/Version**: Python 3.12 o superior en `eval/` (gestionado con `uv`, como LAB-27); Java 21 / Spring Boot 4.x en
`backend/` para el endpoint

**Primary Dependencies**: Python: solo biblioteca estándar para lo nuevo (`urllib.request`, `json`, `csv`, `argparse`,
`dataclasses`); ya existe `pyyaml` de LAB-27. Ninguna dependencia nueva en `eval/` ni en `backend/` (Spring WebFlux ya está)

**Storage**: ninguno. Entrada: `eval/golden.jsonl` y el backend por HTTP. Salida: dos CSV en `eval/results/` (no versionados
por defecto)

**Testing**: Python: `pytest` con cobertura ≥ 80 % (`--cov-fail-under=80` ya configurado); métricas con casos fijados a mano;
cliente y orquestación contra un `http.server` local en un hilo; CSV con comparación byte a byte entre dos ejecuciones. Java:
`@WebFluxTest` del controlador con el puerto mockeado (patrón de `DocumentControllerTests`) y prueba del método nuevo del caso de
uso. Ninguna prueba usa Ollama, base de datos ni red externa

**Target Platform**: máquina del desarrollador con el stack local levantado (`docker compose`: backend, PostgreSQL con pgvector,
Ollama con `bge-m3`) y la base de conocimiento indexada; no corre en CI hasta LAB-30

**Project Type**: herramienta de línea de comandos en un proyecto Python existente + un endpoint de lectura en el backend

**Performance Goals**: medir los 70 casos con k = 1, 3, 5 en menos de 1 minuto con el stack local (70 búsquedas, un embedding y
una consulta SQL cada una); las pruebas de `eval` siguen por debajo de los 5 segundos

**Constraints**: determinista (mismo índice, configuración y dataset → CSV idénticos); no escribe CSV si falla; no reimplementa
ni aproxima la búsqueda; no lee tablas de `retrieval` (ADR-0015); no guarda textos de fragmentos en los CSV; código de
producción sin importar nada de `eval/`; líneas ≤ 121; identificadores en inglés, documentación en español

**Scale/Scope**: 90 casos del dataset, 70 medibles; 24 documentos; k por defecto 1, 3 y 5; profundidad de búsqueda 50
fragmentos (el máximo del backend)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | Cumple: el endpoint vive en `retrieval` y usa su propio puerto de entrada; `eval/` accede por HTTP y no toca el esquema (ADR-0015). Ningún módulo nuevo ni dependencia entre módulos. |
| II. Hexagonal | Cumple: el controlador no tiene lógica, solo llama a `SearchChunksServicePort` y mapea con un mapper explícito; el caso de uso no cambia salvo un método de consulta del modelo. |
| III. Inglés y errores tipados | Cumple: identificadores en inglés; el endpoint reutiliza `SEARCH_QUERY_INVALID` y los errores tipados del embedding, mapeados solo por `GlobalExceptionHandler`; en Python, `MeasurementError` tipado con causa. |
| IV. Pruebas con cobertura | Cumple: pruebas unitarias de las métricas y de cada fallo, controlador probado en frío, cobertura ≥ 80 % en `eval/` y en los módulos tocados; sin modelos en las pruebas. |
| V. RAG con evaluación | Cumple y desarrolla: esta historia **es** la mitad de recuperación del resultado de evaluación que la constitución exige. No cambia prompt, chunking ni embeddings, así que no necesita resultado previo; la puerta en los PR llega en LAB-30. |
| VI. Observabilidad y datos seguros | Cumple: el endpoint pasa por el mismo caso de uso que ya emite la métrica `retrieval.search` y el id de petición; no se registran preguntas ni textos de fragmentos; el corpus es sintético. Riesgo aceptado: el endpoint no tiene autenticación, como el resto de la API hoy (ver research R2). |
| VII. Simplicidad y ADR | Cumple con ADR: exponer la búsqueda por HTTP es una decisión de arquitectura de `retrieval`, y se registra en **ADR-0024**. Se descartan la consulta directa a la base de datos, un ejecutor en Java y una dependencia HTTP nueva. |

**Gate**: PASA. Una desviación declarada y justificada: se toca `backend/` en una historia de evaluación (ver Complexity Tracking).

**Re-evaluación tras el diseño**: PASA. El cambio en el backend se reduce a un controlador, dos registros y un método de consulta;
no hay migraciones, configuración nueva ni cambios en `chat`. `eval/` gana módulos y un subcomando sin dependencias nuevas.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-28-retrieval-metrics/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── search-endpoint.md      # GET /api/knowledge/search: parámetros, respuesta y errores
│   └── retrieval-csv.md        # columnas y significado del resumen y del detalle, y el comando
├── checklists/requirements.md
└── tasks.md                    # lo genera /speckit-tasks
```

### Source Code (repository root)

```text
backend/src/main/java/com/shoplab/helpdesk/retrieval/
├── application/port/in/SearchChunksServicePort.java            # + String embeddingModel()
├── application/usecase/SearchChunksUseCase.java                # implementa embeddingModel() con embedding.modelName()
└── infrastructure/in/rest/
    ├── KnowledgeSearchController.java                          # GET /api/knowledge/search?query=&limit=
    ├── dto/KnowledgeSearchResponse.java                        # model + results
    ├── dto/KnowledgeSearchHitResponse.java                     # source, title, section, position, score, text
    └── util/mapper/KnowledgeSearchResponseMapper.java          # SearchResult -> DTO, sin lógica

backend/src/test/java/com/shoplab/helpdesk/retrieval/
├── infrastructure/in/rest/KnowledgeSearchControllerTests.java  # 200, consulta vacía 422, embedding caído 503, límite
└── application/usecase/SearchChunksUseCaseTests.java           # + embeddingModel()

docs/adr/0024-metricas-de-recuperacion-por-http.md              # decisión: exponer la búsqueda, medir a nivel de documento
docs/adr/README.md                                              # + fila del ADR-0024
docs/modulos/                                                   # regenerado si el Documenter detecta el cambio

eval/
├── README.md                                   # + sección «Métricas de recuperación» (FR-019)
├── src/shoplab_eval/
│   ├── constants.py                            # + DEFAULT_KS, DEFAULT_DEPTH, DEFAULT_BASE_URL, DEFAULT_RESULTS_DIR
│   ├── errors.py                               # + MeasurementError
│   ├── __main__.py                             # + subcomando `retrieval`
│   ├── retrieval_metrics.py                    # puro: documentos distintos, hit@k, recall@k, rr, agregación
│   ├── search_client.py                        # HTTP con urllib: search() y list_documents()
│   ├── retrieval_run.py                        # comprobación previa del índice, bucle de casos, informe
│   └── retrieval_report.py                     # CSV de resumen y detalle, y texto para la pantalla
├── results/.gitkeep                            # carpeta de salida por defecto; el contenido no se versiona
└── tests/
    ├── test_retrieval_metrics.py               # casos fijados a mano: 1.ª posición, posterior, fallo, parcial, repetidos, corto
    ├── test_search_client.py                   # contra un http.server local: éxito, 4xx/5xx, caída, JSON inválido
    ├── test_retrieval_run.py                   # índice incompleto, dataset inválido, búsqueda que falla, exclusión de sin respuesta
    ├── test_retrieval_report.py                # columnas, recálculo del resumen desde el detalle, determinismo, caracteres
    └── test_retrieval_cli.py                   # códigos de salida, k inválidos, no escribe CSV al fallar

.gitignore                                      # + eval/results/* excepto .gitkeep
```

**Structure Decision**: la medición vive en el paquete `shoplab_eval` que LAB-27 creó para que LAB-28 a LAB-30 lo ampliaran, y
reutiliza `load_golden` (FR-001). Las métricas son funciones puras separadas del acceso HTTP para poder probarlas sin red
(FR-017), y el CSV se escribe solo cuando la medición terminó, así un fallo nunca deja un resultado que parezca válido. El
backend solo gana la puerta de entrada que falta, con el mismo caso de uso que el chat, para que la medición ejerza la búsqueda
real y siga reflejando los cambios futuros de la búsqueda (híbrida, otro modelo) sin tocar `eval/`.

## Complexity Tracking

| Elemento | Por qué es necesario | Alternativa más simple descartada porque |
|---|---|---|
| Endpoint nuevo en el backend, en una historia de evaluación | FR-002 exige ejercer la búsqueda real y no hay otra vía que respete ADR-0015: la búsqueda necesita el modelo de embeddings (Ollama) y la consulta a pgvector que solo `retrieval` conoce | Consultar pgvector desde Python: reimplementa la búsqueda y rompe la propiedad del esquema; se desviaría en silencio cada vez que cambie |
| ADR-0024 | Exponer la búsqueda por HTTP y medir a nivel de documento son decisiones de `retrieval` que la constitución (VII) obliga a registrar | Documentarlo solo en el PR: se perdería, y LAB-30 y LAB-31 se apoyan en ellas |
| Método `embeddingModel()` en el puerto de entrada | FR-012 exige registrar el modelo con que está indexada la base, y el controlador no debe llamar a un puerto de salida | Leer el modelo de la configuración en el controlador: duplicaría la fuente de verdad que ya usa el caso de uso al buscar |
| Comprobación previa con `GET /api/documents` | FR-014: distinguir un índice incompleto de una búsqueda mala sin ejecutar 70 búsquedas inútiles | Detectar el problema por los ceros del resultado: es justo la confusión que la historia quiere evitar |
