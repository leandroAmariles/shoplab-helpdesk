---

description: "Task list for LAB-28 - Métricas de retrieval (hit rate, MRR, recall)"
---

# Tasks: Métricas de retrieval (hit rate, MRR, recall)

**Input**: Design documents from `/specs/LAB-28-retrieval-metrics/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`search-endpoint.md`, `retrieval-csv.md`),
quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas y cobertura ≥ 80 %; `eval/` ya tiene
`--cov-fail-under=80`. En cada historia las pruebas van primero y deben fallar antes de implementar. Ninguna prueba usa
Ollama, base de datos ni red externa: las de Python usan funciones puras, un buscador falso inyectado y un `http.server`
local en un hilo; las de Java usan `@WebFluxTest` con el puerto mockeado.

**Organization**: Tareas agrupadas por historia de usuario: US1 (calcular las métricas, P1), US2 (CSV, P1) y US3 (fallar con
claridad, P2). Antes de las historias hay una fase Foundational con el endpoint del backend, que es lo único que bloquea la
medición contra el stack real; el código de `eval/` se puede desarrollar en paralelo contra el servidor falso.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US3)
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **`eval/`**: paquete `eval/src/shoplab_eval/`, pruebas en `eval/tests/` (sin `tests/__init__.py`), comandos desde `eval/`
  con `uv run …`. Al ejecutar solo algunos archivos de prueba durante el TDD, añadir `--no-cov`
  (`uv run pytest --no-cov tests/<archivo>`) para que falle por la razón esperada y no por la cobertura
- **`backend/`**: `backend/src/main/java/com/shoplab/helpdesk/retrieval/…` y `backend/src/test/java/…`; Maven se ejecuta
  desde `backend/` (no hay `pom.xml` en la raíz) con `-Dskip.srcclr=true --no-transfer-progress`
- Python: líneas ≤ 121, `ruff` con `select = ["E", "F", "I", "B", "UP"]`, identificadores en inglés; mensajes para quien
  ejecuta y documentación en español. Java: sin violaciones de PMD/Checkstyle, líneas ≤ 121, llaves en todos los bloques,
  identificadores en inglés
- Sin dependencias nuevas en `eval/` ni en `backend/`; `data/kb/` y `eval/golden.jsonl` no se modifican
- Nada de `eval/` se importa desde `backend/` ni `ingest/`; el controlador no tiene lógica ni construye respuestas de error
  (guía de desarrollo, secciones 6 y 7)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base verde, constantes, error tipado y carpeta de salida

- [X] T001 Línea base en la rama: `cd eval && uv sync --frozen && uv run ruff check . && uv run pytest` en verde, y
  `uv run python -m shoplab_eval validate` informa 90 casos (70 con respuesta, 20 sin respuesta). En `backend/`, `mvn test
  -Dtest=SearchChunksUseCaseTests,DocumentControllerTests,KnowledgeSearchApiAdapterTests -Dskip.srcclr=true
  --no-transfer-progress` en verde. Anotar fallos previos si los hay; no se corrigen aquí
- [X] T002 [P] Añadir a `eval/src/shoplab_eval/constants.py` las constantes de la medición, con comentario del origen en la
  spec o el plan: `DEFAULT_KS = (1, 3, 5)`; `DEFAULT_DEPTH = 50` y `MAX_DEPTH = 50` (máximo del backend,
  `RetrievalConstants.MAX_SEARCH_LIMIT`); `DEFAULT_BASE_URL = "http://localhost:8080"` y `BASE_URL_ENV =
  "SHOPLAB_EVAL_BASE_URL"`; `DEFAULT_TIMEOUT_SECONDS = 10`; `DEFAULT_RESULTS_DIR = REPO_ROOT / "eval" / "results"`;
  `SUMMARY_FILE = "retrieval-summary.csv"`, `DETAIL_FILE = "retrieval-detail.csv"`; `ALL_SCOPE = "all"`;
  `LIST_SEPARATOR = "|"`; `DECIMALS = 4`; `INDEXED_STATUS = "INDEXED"`
- [X] T003 [P] Añadir `MeasurementError(Exception)` a `eval/src/shoplab_eval/errors.py`: error tipado de una medición que no
  es válida; el mensaje (en español) nombra la causa y, según el caso, el id del caso, los documentos o la URL afectados
- [X] T004 [P] Crear `eval/results/.gitkeep` y añadir a `.gitignore` las líneas `eval/results/*` y `!eval/results/.gitkeep`
  (los CSV de una ejecución local no se versionan; la línea base versionada es de LAB-30, research R10)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: el endpoint `GET /api/knowledge/search` en `retrieval` (research R1), la puerta de entrada que falta para ejercer
la búsqueda real (FR-002) sin tocar el esquema (ADR-0015). Contrato en
[contracts/search-endpoint.md](contracts/search-endpoint.md).

**⚠️ CRITICAL**: la medición contra el stack real (quickstart §3 en adelante) depende de esta fase. Las historias US1–US3 se
pueden desarrollar y probar sin ella porque usan un servidor falso.

### Tests (primero; deben fallar)

- [X] T005 [P] Ampliar `backend/src/test/java/com/shoplab/helpdesk/retrieval/application/usecase/SearchChunksUseCaseTests.java`
  con una prueba de `embeddingModel()`: devuelve el valor de `EmbeddingPort.modelName()` sin llamar a la búsqueda ni al
  almacén. Leer primero cómo construye el caso de uso para reutilizar sus dobles
- [X] T006 [P] Crear `backend/src/test/java/com/shoplab/helpdesk/retrieval/infrastructure/in/rest/KnowledgeSearchControllerTests.java`
  con `@WebFluxTest` y `@MockitoBean SearchChunksServicePort`, importando `GlobalExceptionHandler` como
  `DocumentControllerTests`. Casos: (a) 200 con `model` y `results` en el **mismo orden** que devuelve el puerto, con los
  campos `source`, `title`, `section`, `position`, `score`, `text` y **sin** `chunkId` ni `documentId`; (b) `limit` se pasa al
  puerto tal cual y, si falta, se pasa `null`; (c) `query` en blanco → 422 con `SLHD_RETR_00009` (el puerto lanza
  `ExceptionUtil.business(ResponseCodeEnum.SEARCH_QUERY_INVALID)`); (d) `query` ausente → el mismo 422 (el parámetro no es
  obligatorio para Spring, la decisión es del caso de uso); (e) puerto con `EMBEDDING_SERVICE_UNAVAILABLE` → 503
  `SLHD_RETR_00006`; (f) lista vacía → 200 con `results: []`; (g) tildes, `¿` y `?` en `query` llegan intactos al puerto

### Implementation

- [X] T007 Añadir a `backend/src/main/java/com/shoplab/helpdesk/retrieval/application/port/in/SearchChunksServicePort.java`
  el método `String embeddingModel();` con javadoc («modelo de embeddings con el que se busca; solo se comparan vectores de
  ese modelo, ADR-0017») e implementarlo en `.../application/usecase/SearchChunksUseCase.java` como
  `return embedding.modelName();`. Comprobar con `grep -rn "SearchChunksServicePort" backend/src` que ningún otro tipo lo
  implementa a mano (hoy solo el caso de uso; los demás lo mockean)
- [X] T008 [P] Crear los DTO `backend/src/main/java/com/shoplab/helpdesk/retrieval/infrastructure/in/rest/dto/KnowledgeSearchHitResponse.java`
  (`record`: `String source, String title, String section, int position, double score, String text`) y
  `.../dto/KnowledgeSearchResponse.java` (`record`: `String model, List<KnowledgeSearchHitResponse> results`), sin lógica
- [X] T009 Crear `backend/src/main/java/com/shoplab/helpdesk/retrieval/infrastructure/in/rest/util/mapper/KnowledgeSearchResponseMapper.java`
  con el estilo de `DocumentResponseMapper` (clase final, constructor privado, métodos estáticos): `SearchResult` →
  `KnowledgeSearchHitResponse` copiando solo los seis campos públicos (nunca `chunkId` ni `documentId`) y
  `(model, List<SearchResult>)` → `KnowledgeSearchResponse` conservando el orden
- [X] T010 Crear `backend/src/main/java/com/shoplab/helpdesk/retrieval/infrastructure/in/rest/KnowledgeSearchController.java`:
  `@RestController` en `@RequestMapping("/api/knowledge/search")` con un `@GetMapping` que recibe `@RequestParam(required =
  false) String query` y `@RequestParam(required = false) Integer limit`, llama a `SearchChunksServicePort.search(query,
  limit)` y devuelve `Mono<KnowledgeSearchResponse>` usando `embeddingModel()` y el mapper. Sin lógica, sin `ResponseEntity`
  de error, sin registrar la consulta ni el texto de los fragmentos (contrato, «Reglas»). Javadoc de clase que cite
  `specs/LAB-28-retrieval-metrics` como los demás controladores
- [X] T011 [P] Crear `docs/adr/0024-metricas-de-recuperacion-por-http.md` desde `docs/adr/0000-plantilla.md` (borrar el
  comentario de la plantilla; estado `aceptado`, fecha 2026-11-17): **Contexto** (la búsqueda es una API interna sin HTTP, el
  dataset lista documentos, ADR-0015 impide leer el esquema ajeno); **Decisión** (endpoint de solo lectura
  `GET /api/knowledge/search`, medición a nivel de documento con profundidad de 50 fragmentos, orden de la búsqueda antes de
  la selección del chat, CSV determinista); **Consecuencias** (endpoint sin autenticación como el resto de `/api/**`, riesgo
  aceptado hasta que exista seguridad; hit@k por documento es más indulgente que los 4 fragmentos del chat; HNSW aproximado,
  research R12). Alternativas descartadas de research R1. Añadir su fila al índice en `docs/adr/README.md`
  (`| 0024 | [Métricas de recuperación por HTTP](0024-metricas-de-recuperacion-por-http.md) | aceptado |`)
- [X] T012 Pasar las pruebas del backend y los controles de arquitectura: en `backend/`, `mvn test
  -Dtest=KnowledgeSearchControllerTests,SearchChunksUseCaseTests -Dskip.srcclr=true --no-transfer-progress` en verde y
  después `mvn test -Dtest=ModularityTests -Dskip.srcclr=true --no-transfer-progress` (verifica ArchUnit y Modulith y
  regenera `docs/modulos/`). Si regenera archivos, revisar el diff y conservarlo; si no cambian, no tocar nada. Ejecutar
  `mvn checkstyle:check -Dskip.srcclr=true --no-transfer-progress` sin violaciones

**Checkpoint**: el endpoint responde y está probado; `eval/` ya puede apuntarle.

---

## Phase 3: User Story 1 - Saber si la búsqueda encuentra los documentos correctos (Priority: P1) 🎯 MVP

**Goal**: ejecutar la búsqueda real sobre cada pregunta con respuesta y obtener hit rate@k, recall@k y MRR contra los
documentos esperados, por categoría y en conjunto, mostrados en pantalla.

**Independent Test**: con el stack indexado, `uv run python -m shoplab_eval retrieval` imprime la tabla con la fila `all` y
una por tema (70 casos medidos, 20 excluidos); tres casos comprobados a mano (acierto en 1.ª posición, acierto posterior,
fallo) coinciden con las definiciones (quickstart §3 y §4). Sin stack: las pruebas de abajo lo comprueban con un buscador
falso.

### Tests for User Story 1 (primero; deben fallar)

- [X] T013 [P] [US1] Crear `eval/tests/test_retrieval_metrics.py` con casos fijados a mano (FR-017, sin red ni backend):
  (a) acierto en 1.ª posición → `hit` 1 para todo k, `recall` 1, `reciprocal_rank` 1, `first_hit_rank` 1; (b) acierto en 3.ª
  posición con k = 1, 3, 5 → hit 0, 1, 1 y `reciprocal_rank` ⅓ (`0.3333` al formatear); (c) dos documentos esperados con un
  solo acierto entre los k primeros → `recall` 0,5 y `hit` 1; con k = 1 y dos esperados el recall máximo es 0,5; (d) ningún
  documento esperado en la lista → todo 0 y `first_hit_rank` `None`; (e) fragmentos repetidos del mismo documento
  (`a.md`, `a.md`, `b.md`) → lista `("a", "b")` con `b` en la posición 2; (f) `payments-01.md` y `payments-01.pdf` cuentan
  como el mismo documento `payments-01` en la posición del primero; (g) menos documentos distintos que k → se calcula con los
  que hay, sin error; (h) MRR sobre la lista completa, sin truncar a k (acierto en la posición 7 con k máximo 5 →
  `reciprocal_rank` 1/7 y hit 0); (i) agregación: media por ámbito, fila `all` más una por categoría con casos medibles en el
  orden de `CATEGORIES`, `off_topic` sin fila, `excluded` por categoría y total
- [X] T014 [P] [US1] Crear `eval/tests/test_search_client.py` (parte de éxito) con un `http.server` local en un hilo (fixture
  en `eval/tests/conftest.py` o helper en `eval/tests/helpers.py`, que registra las peticiones recibidas): `search()` envía
  `GET /api/knowledge/search?query=…&limit=50` con la consulta codificada en UTF-8 (tildes, `¿`, `?`, coma) y devuelve
  `SearchResponse(model, hits)` con los `hits` **en el orden recibido**; `list_documents()` lee `GET /api/documents` y devuelve
  `IndexedDocument(name, status)`; la URL base con barra final funciona igual
- [X] T015 [P] [US1] Crear `eval/tests/test_retrieval_run.py` (parte de éxito) con un buscador falso inyectado (objeto con
  `search(query, limit)` y `list_documents()` que cumple el `Protocol` de T018): los casos sin documentos esperados no se
  buscan y se cuentan como excluidos; cada caso medible se busca una vez con `limit` igual a la profundidad; el informe tiene
  una `CaseResult` por caso medible en el orden del dataset y el `model` de la respuesta; las métricas del informe coinciden
  con las de T013 para un dataset de juguete (reutilizar `eval/tests/helpers.py`)
- [X] T016 [P] [US1] Crear `eval/tests/test_retrieval_cli.py` (parte de éxito): con el servidor de T014 sirviendo respuestas
  fijas y el corpus y dataset de juguete, `main(["retrieval", "--base-url", url, "--golden", g, "--kb", kb])` devuelve `0` e
  imprime una fila `all` y una por categoría con casos, el modelo y el número de casos medidos y excluidos

### Implementation for User Story 1

- [X] T017 [P] [US1] Crear `eval/src/shoplab_eval/retrieval_metrics.py` (funciones puras, sin red): `distinct_documents(sources)`
  → ids con `Path(source).stem`, conservando la primera aparición de cada uno; dataclasses `CaseMetrics`, `CaseResult` y
  `ScopeSummary` con los campos de [data-model.md](data-model.md); `compute_case_metrics(expected, retrieved, ks)` con
  `hit@k = 1 si E ∩ D[:k] ≠ ∅, si no 0`, `recall@k = |E ∩ D[:k]| / |E|`, `reciprocal_rank = 1 / r` sobre la lista **completa**
  (0 si no aparece) y `first_hit_rank`; `summarize(results, excluded_by_category, ks)` con la media aritmética por ámbito
  (`all` y cada categoría con al menos un caso medible, en el orden de `CATEGORIES`)
- [X] T018 [P] [US1] Crear `eval/src/shoplab_eval/search_client.py`: dataclasses `SearchHit(source, score)`,
  `SearchResponse(model, hits)` e `IndexedDocument(name, status)`; `Protocol` `Searcher` con `search(query, limit)` y
  `list_documents()`; `KnowledgeSearchClient(base_url, timeout)` con `urllib.request` (sin dependencias nuevas) que construye
  la URL con `urllib.parse.urlencode` (UTF-8) contra `/api/knowledge/search` y `/api/documents` y lee el JSON de la respuesta
  conservando el orden de `results`. Los errores de red y de estado se traducen en US3 (T031)
- [X] T019 [US1] Crear `eval/src/shoplab_eval/retrieval_run.py`: `RetrievalReport` (`model`, `depth`, `ks`, `results`,
  `summaries`, `excluded`, según [data-model.md](data-model.md)) y `measure_retrieval(cases, searcher, ks, depth)`: separa los
  casos con `expected_documents` no vacío de los demás, busca cada medible en el orden del dataset con `limit=depth`, reduce
  los fragmentos a documentos distintos (T017), calcula las métricas y resume. Sin escribir archivos. Depende de T017 y T018
- [X] T020 [US1] Crear `eval/src/shoplab_eval/retrieval_report.py` con `format_report(report) -> str`: tabla de texto legible
  en español con una fila por ámbito (`scope`, casos, hit rate@k, recall@k y MRR con cuatro decimales), el modelo de
  embeddings, la profundidad y «N casos medidos, M excluidos». Depende de T019
- [X] T021 [US1] Añadir el subcomando `retrieval` a `eval/src/shoplab_eval/__main__.py` con las opciones del contrato
  (`--k` por defecto `1,3,5`, `--depth`, `--base-url` con `$SHOPLAB_EVAL_BASE_URL` o `DEFAULT_BASE_URL`, `--out`, `--golden`,
  `--kb`, `--timeout`): carga el dataset con la lectura y validación de LAB-27, crea el `KnowledgeSearchClient`, ejecuta
  `measure_retrieval`, imprime `format_report` y devuelve `0`. Mantener `validate` y `_use_utf8_output` intactos. La escritura
  de CSV llega en US2 y el manejo de errores en US3. Depende de T019 y T020
- [X] T022 [US1] Ejecutar `uv run pytest --no-cov tests/test_retrieval_metrics.py tests/test_search_client.py
  tests/test_retrieval_run.py tests/test_retrieval_cli.py` en `eval/`: todo en verde, y `uv run ruff check .` sin avisos

**Checkpoint**: la medición calcula y muestra las tres métricas; US1 se puede demostrar sola.

---

## Phase 4: User Story 2 - Un resultado en CSV para comparar entre ejecuciones (Priority: P1)

**Goal**: escribir el resumen y el detalle en CSV, deterministas y recalculables, con los parámetros que hacen comparables dos
ejecuciones. Formato en [contracts/retrieval-csv.md](contracts/retrieval-csv.md).

**Independent Test**: ejecutar `retrieval`, abrir `eval/results/retrieval-summary.csv` y `retrieval-detail.csv` en una hoja de
cálculo, recalcular la media de una columna del detalle y ver que coincide con el resumen; dos ejecuciones dan archivos
idénticos (quickstart §3 a §5).

### Tests for User Story 2 (primero; deben fallar)

- [X] T023 [P] [US2] Crear `eval/tests/test_retrieval_report.py` con un `RetrievalReport` fijado a mano: (a) cabeceras
  exactas del resumen (`scope,cases,excluded_cases,embedding_model,search_depth,hit_rate@1,…,recall@1,…,mrr`) y del detalle
  (`id,category,question,expected_documents,retrieved_documents,first_hit_rank,hit@1,…,recall@1,…,reciprocal_rank`) con un
  grupo por cada k pedido y en el orden dado (probar también `--k 2,4`); (b) números con cuatro decimales fijos y punto
  decimal (`0.8571`, `1.0000`, `0.3333`), `hit@k` como `1`/`0`; (c) listas de documentos unidas con `|` y `first_hit_rank`
  vacío cuando no hay acierto; (d) una pregunta con coma, comillas, tildes, eñes y `¿?` se lee con `csv.reader` sin columnas
  desplazadas ni caracteres alterados; (e) **invariante**: cada valor del resumen es la media de la columna correspondiente del
  detalle filtrando por `scope` (SC-003), `cases` de `all` es el número de filas del detalle y `cases + excluded_cases` de
  `all` es el tamaño del dataset; (f) un caso con dos esperados y un acierto tiene `recall@k = 0.5000` y `hit@k = 1`;
  (g) **determinismo**: dos escrituras del mismo informe producen archivos idénticos byte a byte, sin BOM, con `\n` y sin
  marcas de tiempo ni rutas; (h) ningún archivo contiene texto de fragmentos ni respuestas de referencia; (i) `off_topic` no
  genera fila en el resumen; (j) la carpeta de salida se crea si falta
- [X] T024 [US2] Ampliar `eval/tests/test_retrieval_cli.py` (parte de CSV): con `--out <tmp>`, `retrieval` escribe
  `retrieval-summary.csv` y `retrieval-detail.csv`, el resumen tiene la fila `all` y una por categoría con casos, el detalle
  tiene una fila por caso medible, e imprime las rutas; dos ejecuciones con `--out` distintos producen carpetas idénticas
  (`filecmp`/bytes)

### Implementation for User Story 2

- [X] T025 [US2] Ampliar `eval/src/shoplab_eval/retrieval_report.py` con `summary_csv(report) -> str`, `detail_csv(report) ->
  str` y `write_report(report, out_dir) -> tuple[Path, Path]`: módulo `csv` estándar con `lineterminator="\n"`, UTF-8 **sin
  BOM**, cabecera siempre, números con `f"{value:.{DECIMALS}f}"`, listas unidas con `LIST_SEPARATOR`, filas en el orden del
  dataset y de `CATEGORIES` (nunca de un `dict` o `set` sin orden). `write_report` construye **ambos** textos antes de escribir
  y crea la carpeta si falta. No se escribe ningún texto de fragmentos (`text` no se guarda en `SearchHit`). Depende de T020
- [X] T026 [US2] En `eval/src/shoplab_eval/__main__.py`, tras `measure_retrieval`, llamar a `write_report(report, args.out)`
  con `--out` (por defecto `DEFAULT_RESULTS_DIR`) e imprimir las rutas escritas después de la tabla. Depende de T025
- [X] T027 [US2] Ejecutar `uv run pytest --no-cov tests/test_retrieval_report.py tests/test_retrieval_cli.py` y
  `uv run ruff check .` en `eval/`: en verde

**Checkpoint**: US1 + US2 entregan el criterio de aceptación completo de Jira (métricas y CSV).

---

## Phase 5: User Story 3 - Fallar con claridad si la medición no es válida (Priority: P2)

**Goal**: que un entorno roto (búsqueda caída, índice vacío o incompleto, dataset inválido, k inválido) termine con error
visible, código distinto de cero y **sin CSV**, y nunca como ceros en las métricas (research R8).

**Independent Test**: parar el backend, dejar un documento sin indexar, pasar un dataset con una pregunta duplicada y pedir
`--k 0`; cada uno termina con el código y el mensaje del contrato y sin archivos nuevos (quickstart §6).

### Tests for User Story 3 (primero; deben fallar)

- [X] T028 [P] [US3] Crear `eval/tests/test_search_client_errors.py` (los errores del cliente, aparte de la parte de éxito): conexión rechazada → `MeasurementError`
  que incluye la URL base; respuesta 503 y 422 → error que incluye el estado HTTP y el `code` del cuerpo si existe; cuerpo no
  JSON o sin `results` → error de respuesta ilegible; búsqueda que excede `--timeout` → error de tiempo agotado. Ninguno
  reintenta (research R9)
- [X] T029 [P] [US3] Ampliar `eval/tests/test_retrieval_run.py` (parte de errores) con el buscador falso: (a) **comprobación
  previa**: si falta un documento esperado en `list_documents()`, o está en `PENDING`/`ERROR`, se lanza `MeasurementError` que
  lista los ids y su estado **sin haber llamado a `search`** (contador de llamadas en cero); (b) un documento indexado como
  `.md` y `.pdf` cuenta una sola vez, y basta con que alguno de los dos esté `INDEXED`; (c) una búsqueda que falla en un caso
  lanza un error que nombra el id del caso (`G0xx`); (d) si `model` cambia entre dos respuestas, error que nombra los dos
  modelos; (e) una búsqueda correcta con lista vacía o sin el documento esperado **no** es error: el caso aporta 0 (FR-015);
  (f) menos documentos que k no es error
- [X] T030 [P] [US3] Ampliar `eval/tests/test_retrieval_cli.py` (parte de errores): (a) dataset inválido (pregunta duplicada)
  → código `1`, la salida contiene `G-DUPLICATE` y **no hay CSV** en `--out`; (b) backend inalcanzable → `1`, mensaje con la
  URL base, sin CSV; (c) índice incompleto → `1`, lista los documentos, sin CSV y con **cero** peticiones a `/api/knowledge/search`
  registradas por el servidor; (d) fallo en el caso 40 de 70 → `1`, sin CSV aunque ya se hubieran procesado 39 casos y aunque
  la carpeta de salida ya existiera con archivos de una ejecución anterior (esos archivos no se tocan); (e) `--k 0`,
  `--k 3,x`, `--k 3,3` y `--k 5 --depth 3` → código `2` antes de cualquier petición (contador en cero), con el valor
  rechazado y el rango aceptado en el mensaje; (f) `--depth 0` y `--depth 51` → `2`

### Implementation for User Story 3

- [X] T031 [US3] En `eval/src/shoplab_eval/search_client.py`, traducir a `MeasurementError` (mensajes en español, nombrando la
  URL base y la causa, con el estado HTTP y el `code` del cuerpo cuando existan): `URLError`/`ConnectionRefusedError`,
  `TimeoutError`, `HTTPError` y `json.JSONDecodeError`/estructura inesperada. Sin reintentos. Depende de T018
- [X] T032 [US3] En `eval/src/shoplab_eval/retrieval_run.py` añadir `check_index(searcher, expected_documents)` (se ejecuta
  **antes** del bucle de búsquedas): consulta `list_documents()`, agrupa por `Path(name).stem` y exige al menos un archivo
  `INDEXED` por cada id esperado por el dataset; si no, `MeasurementError` con los ids faltantes y su estado. En el bucle,
  envolver cada búsqueda para que un fallo nombre el id del caso, y verificar que `model` no cambia entre respuestas. Depende
  de T019 y T031
- [X] T033 [US3] En `eval/src/shoplab_eval/__main__.py` (depende de T026 y T032): validar `--k` (enteros positivos, sin
  repetir, cada uno ≤ `--depth`) y `--depth` (1–`MAX_DEPTH`) **antes de consultar nada**, terminando con código `2` y un
  mensaje que da el valor rechazado y el rango aceptado; capturar `MeasurementError` e `InvalidGoldenError` (mismo formato que
  `validate`: imprime el mensaje y devuelve `1`) y garantizar que `write_report` solo se llama con la medición completa. Un
  fallo no deja ningún archivo nuevo
- [X] T034 [US3] Ejecutar `uv run pytest` completo en `eval/` (con cobertura) y `uv run ruff check .`: todo en verde con
  cobertura ≥ 80 % (`--cov-fail-under=80` ya configurado); revisar en el informe que `retrieval_*.py` y `search_client.py` están
  cubiertos, incluidas las ramas de error

**Checkpoint**: las tres historias funcionan y están probadas de forma independiente.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: documentación (FR-019), pruebas HTTP del endpoint, verificación contra el stack real y puertas de calidad

- [X] T035 [P] Ampliar `eval/README.md` con la sección «Métricas de recuperación» (FR-019): qué mide cada métrica con un
  **ejemplo numérico** (un caso con el documento en 3.ª posición y otro con dos esperados y un acierto), cómo ejecutar el
  comando y sus opciones, cómo leer los dos CSV (columnas de [contracts/retrieval-csv.md](contracts/retrieval-csv.md)) y cómo
  abrirlos en una hoja de cálculo (importar como UTF-8), tabla de errores y qué hacer en cada uno, y la nota de que la medición
  es **a nivel de documento** y antes de la selección del chat (más indulgente que los 4 fragmentos del chat). Añadir la fila
  de los CSV de `results/` a la tabla de archivos y enlazar `specs/LAB-28-retrieval-metrics/`. Sin copiar el contrato entero
- [X] T036 [P] Generar `specs/LAB-28-retrieval-metrics/requests.http` (regla «HTTP test requests»; el endpoint nuevo lo hace
  obligatorio) con `@baseUrl = http://localhost:8080` y un caso por requisito y escenario alcanzable por HTTP, cada uno con su
  origen y el resultado esperado: orden de resultados y campos del contrato (`FR-002`/`FR-003`, escenario de
  `search-endpoint.md`), `limit` en el límite inferior (`1`) y superior (`50`), `limit=51` y `limit=0` (acotados por el caso de
  uso a 50 y a 1), `limit` ausente (5 por defecto), consulta con tildes y `¿?`, consulta en blanco y consulta ausente → 422
  `SLHD_RETR_00009`, y `GET /api/documents` que sirve a la comprobación previa del índice (`FR-014`). Los requisitos que solo
  existen en la línea de comandos (CSV, determinismo, exclusión de casos sin respuesta) van en el bloque de comentarios
  «Not covered here» al inicio. Sin secretos ni datos reales
- [X] T037 Puertas de calidad del backend (guía de desarrollo §8): en `backend/`, `mvn checkstyle:check -Dskip.srcclr=true
  --no-transfer-progress` y `mvn verify -Dskip.srcclr=true -Dcheckstyle.skip=true --no-transfer-progress` en verde, con
  cobertura de JaCoCo ≥ 80 % en los módulos tocados y `docs/modulos/` coherente con el código
- [X] T038 **Medición real** (necesita Docker y Ollama): con `docker compose -f infra/docker-compose.yml up`, los 24 documentos
  de `data/kb` indexados y `bge-m3` disponible, ejecutar `cd eval && uv run python -m shoplab_eval retrieval` y recorrer
  [quickstart.md](quickstart.md) §3 a §7: resultado en menos de 1 minuto, 70 casos medidos y 20 excluidos, determinismo (§5),
  errores visibles (§6: backend caído, índice incompleto, dataset inválido, `--k 0`, Ollama caído) y cambio visible de una
  métrica al reindexar (§7). Registrar el resultado (comandos, métricas obtenidas, fecha y modelo) en
  `specs/LAB-28-retrieval-metrics/validation.md`, con lo que no se pudo ejecutar y por qué. Si el stack no está disponible,
  dejar la tarea abierta y decirlo
- [X] T039 **Revisión manual de tres casos** (SC-002, la hace una persona): en `eval/results/retrieval-detail.csv` elegir un
  caso con `first_hit_rank = 1`, uno con `first_hit_rank > 1` y uno con la celda vacía; calcular a mano `hit@k`, `recall@k` y
  `reciprocal_rank` y confirmar que coinciden, y recalcular en una hoja de cálculo la media de una columna por tema contra el
  resumen (SC-003). Anotar el resultado en `specs/LAB-28-retrieval-metrics/validation.md`
- [X] T040 Revisión previa al commit (guía de desarrollo §8.4–8.6): `git status --short` sin archivos sueltos (`*.orig`,
  `*.log`, `eval/results/*.csv`, `__pycache__`, `.coverage`), `git diff` revisado para que solo haya cambios de esta historia
  (ningún cambio en `data/kb/`, `eval/golden.jsonl` ni `chat/`), commits atómicos con formato `type(scope): descripción`
  (p. ej. `feat(retrieval): [LAB-28] endpoint de búsqueda de solo lectura`, `feat(eval): [LAB-28] métricas de recuperación`,
  `docs(adr): [LAB-28] ADR-0024`). No hacer commit ni push sin que se pida
- [ ] T041 Preparar el PR (solo cuando se pida abrirlo): título `feat(eval): [LAB-28] métricas de recuperación (hit rate,
  MRR, recall)` (release-please lo lee: `feat` sube la versión menor); la descripción cita ADR-0024 y deja claro que el
  endpoint es el único cambio en `backend/`. Tras fusionar, validar el primer tag `vX.Y.Z` **antes** de cerrar LAB-28 en Jira

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: sin dependencias. T002, T003 y T004 son paralelas entre sí; T001 va primero (línea base)
- **Phase 2 (Foundational, backend)**: no depende de Phase 1 para el código Java, pero bloquea solo la verificación real
  (T038). Dentro de la fase: T005 y T006 (pruebas) primero → T007 → T008 → T009 → T010 → T012; T011 (ADR) es paralela a todo
- **Phase 3 (US1)**: necesita T002 y T003. **No depende** de la Phase 2: usa un servidor y un buscador falsos
- **Phase 4 (US2)**: depende de US1 (necesita `RetrievalReport` y `format_report`)
- **Phase 5 (US3)**: depende de US1 (T031 amplía T018, T032 amplía T019) y de US2 (T033 amplía T026)
- **Phase 6 (Polish)**: T035 y T036 después de US3 (y T036 también de T010); T037 después de la Phase 2; T038 y T039 necesitan
  todo lo anterior y el stack local

### User Story Dependencies

- **US1 (P1)**: independiente tras Setup. Es el MVP (métricas por pantalla)
- **US2 (P1)**: construye sobre el informe de US1; entrega el criterio «resultado en CSV»
- **US3 (P2)**: endurece US1 y US2; cada fallo es independiente de los demás

### Within Each User Story

- Las pruebas se escriben primero y deben fallar; después, tipos y funciones puras → cliente → orquestación → informe → CLI
- `retrieval_report.py` y `__main__.py` se modifican en tres historias: esas tareas son **secuenciales** (no `[P]`)

### Parallel Opportunities

- Phase 1: T002, T003, T004
- Phase 2: T005 ∥ T006; T008 ∥ T011
- US1: T013, T014, T015, T016 (archivos de prueba distintos); T017 ∥ T018
- US3: T028, T029, T030 (tres archivos de prueba)
- Polish: T035 ∥ T036
- Con dos personas: una hace la Phase 2 (Java) mientras la otra hace US1 en `eval/`

### Parallel Example: User Story 1

```text
Task: "Escribir eval/tests/test_retrieval_metrics.py (T013)"
Task: "Escribir eval/tests/test_search_client.py, parte de éxito (T014)"
Task: "Escribir eval/tests/test_retrieval_run.py, parte de éxito (T015)"
Task: "Escribir eval/tests/test_retrieval_cli.py, parte de éxito (T016)"
# después, en paralelo:
Task: "Implementar eval/src/shoplab_eval/retrieval_metrics.py (T017)"
Task: "Implementar eval/src/shoplab_eval/search_client.py (T018)"
```

---

## Implementation Strategy

### MVP First (US1 + US2)

1. Phase 1: constantes, error tipado y carpeta de salida.
2. Phase 3 (US1): métricas puras, cliente, orquestación y subcomando; pruebas con servidor falso.
3. Phase 4 (US2): CSV determinista.
4. **STOP y VALIDAR**: con el endpoint (Phase 2) y el stack, ejecutar quickstart §3 a §5. Eso cubre los dos criterios de
   aceptación de Jira (métricas y CSV).

### Orden recomendado

1. Phase 2 en paralelo con Phase 3, o antes si trabaja una sola persona (el endpoint es corto y desbloquea la verificación).
2. US1 → US2 → US3 → Polish. US3 puede ir antes de US2 si se prefiere tener los fallos cubiertos pronto; las dos amplían los
   mismos archivos, así que no se hacen a la vez.

### Incremental Delivery

1. Setup + Foundational → endpoint probado.
2. US1 → las métricas existen y se ven. US2 → resultado comparable en CSV. US3 → no se confunde un entorno roto con ceros.
3. Polish → README, `requests.http`, verificación real y PR.

---

## Notes

- [P] = archivos distintos, sin dependencias pendientes
- Un cero por búsqueda correcta que no encuentra el documento esperado **no** es un error (FR-015); un entorno que no puede
  buscar o no tiene indexado el corpus **sí** lo es (FR-014). No relajar esa frontera para que una prueba pase
- No aplicar el umbral de similitud ni la selección del chat (research R5), no reimplementar la búsqueda ni leer tablas de
  `retrieval` (ADR-0015), no guardar texto de fragmentos en los CSV, no añadir dependencias nuevas
- El endpoint no tiene autenticación, como el resto de `/api/**`; si en la implementación aparece una capa de seguridad,
  revisar T010 y ADR-0024 antes de continuar
- No se añaden propiedades de configuración nuevas (no aplica el paso de `new-properties`)
- La verificación real (T038) y la revisión manual (T039) no son delegables en un modelo ni en las pruebas automáticas; la
  implementación puede dejar el borrador del resultado, pero la confirmación la firma la persona
- Commit tras cada tarea o grupo lógico; el título del PR (T041) es el que lee release-please

---

## Estado al implementar (2026-11-18)

**Hecho**: todo salvo el PR (T041). Resultados en [validation.md](validation.md):

- `mvn verify` completo en verde con Docker (797 pruebas, 0 errores, umbrales de cobertura cumplidos) y 180 pruebas de `eval/`
  con cobertura 98,8 %.
- Medición real contra el stack local: 70 casos medidos y 20 excluidos, hit@1/3/5 = 0,43 / 0,89 / 0,93, MRR 0,66, en 5,4 s.
  Determinismo, errores visibles (backend caído, índice incompleto, dataset inválido, k inválido, Ollama parado) y cambio de
  métricas al reindexar comprobados.
- **T039**: se cerró con un recálculo independiente por script de todas las filas de los CSV reales (0 discrepancias), no con
  una lectura humana; una persona puede revisar un caso a ojo si quiere dejarlo firmado.

**Abierto**:

- **T041**: PR; no se abre hasta que lo pidas. Tras fusionar, validar el primer tag `vX.Y.Z` antes de cerrar LAB-28 en Jira.
- Las peticiones de `requests.http` no se ejecutaron en un cliente HTTP (ver `validation.md`).
