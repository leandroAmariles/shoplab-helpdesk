---

description: "Task list for LAB-20 - Parseo y chunking de documentos"
---

# Tasks: Parseo y chunking de documentos

**Input**: Design documents from `/specs/LAB-20-document-parsing-chunking/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`ingest.openapi.yaml`,
`retrieval-ingestion.md`), quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas antes o junto con la implementación. En cada historia
las pruebas van primero y deben fallar antes de implementar. Las pruebas de base de datos usan PostgreSQL con pgvector
real (Testcontainers, `@PostgresTest`), sin mocks de base de datos; las del adaptador `WebClient` usan WireMock.

**Organization**: Tareas agrupadas por historia de usuario. US1 y US2 viven en el servicio `ingest` (Python); US3 en el
módulo `retrieval` (Java); US4 formaliza el contrato. US3 no necesita `ingest` real: se prueba contra WireMock con la
respuesta de ejemplo de `contracts/ingest.openapi.yaml`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US4)
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Servicio**: `ingest/app/` (código) e `ingest/tests/` (pruebas); ruff con línea ≤ 121
- **Backend**: `backend/src/main/java/com/shoplab/helpdesk/retrieval/` y `backend/src/test/java/com/shoplab/helpdesk/`
- Java: líneas ≤ 121 caracteres, llaves en todos los bloques, identificadores en inglés, sin `RestTemplate`/`RestClient`
- Hay un endpoint HTTP (`POST /ingest`): al final se genera `requests.http` en esta carpeta (T043)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base verde y esqueleto del proyecto Python

- [X] T001 Comprobar la línea base: `./mvnw -B -ntp test -Dtest='ModuleSchemasIntegrationTests,RetrievalSchemaIntegrationTests,IngestHasNoDatabaseCredentialsTests'` en `backend/` pasa, y `ingest/` solo contiene `Dockerfile`
- [X] T002 Crear `ingest/pyproject.toml` (nombre `ingest`, `requires-python >= 3.12`; dependencias `fastapi`, `uvicorn`, `python-multipart`, `markdown-it-py`, `pdfplumber`, `pydantic-settings`, `prometheus-client`; grupo `dev`: `pytest`, `pytest-cov`, `httpx`, `ruff`, `fpdf2`, `pypdf`; `[tool.uv] package = false`; `[tool.ruff] line-length = 121`, `target-version = "py312"`, `select = ["E","F","I","B","UP"]`; `[tool.pytest.ini_options] testpaths = ["tests"]`, `pythonpath = ["."]`, `addopts = "--cov=app --cov-fail-under=80"`; la CI usa `uv run pytest` tal cual y exige el 80 %; en el ciclo TDD local se ejecutan pruebas sueltas con `uv run pytest --no-cov tests/<archivo>`) y generar `ingest/uv.lock` con `uv lock`
- [X] T003 [P] Crear `ingest/.dockerignore` (`.venv`, `__pycache__`, `.pytest_cache`, `.ruff_cache`, `tests`, `.coverage`) y los paquetes vacíos `ingest/app/{api,core,domain,parsing,chunking}/__init__.py` y `ingest/tests/__init__.py`
- [X] T004 Comprobar que `docker build ingest` sigue produciendo una imagen (el `Dockerfile` ya usa `uv sync --frozen --no-dev` cuando existe `pyproject.toml`; no hace falta tocarlo salvo que falle) y que el `CMD` arranca `app.main:app`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: migración `V3`, códigos de error y núcleo de troceado de `ingest`, que necesitan todas las historias

**⚠️ CRITICAL**: ninguna historia puede empezar hasta completar esta fase

- [X] T005 Actualizar `backend/src/test/java/com/shoplab/helpdesk/schema/RetrievalSchemaIntegrationTests.java` al contrato `V3` (relaja el FR-003 de LAB-19): sustituir las pruebas «chunk sin `embedding_model` se rechaza» por: se **acepta** un chunk con `embedding` y `embedding_model` ambos nulos; se **rechaza** `embedding` sin `embedding_model` y `embedding_model` sin `embedding` (`CHECK ((embedding IS NULL) = (embedding_model IS NULL))`, constraint `chunk_embedding_pair_chk`); se conservan `vector(1024)`, `btrim(embedding_model) <> ''` y el índice HNSW; un chunk sin vector no aparece en `ORDER BY embedding <=> ? LIMIT 5`. Debe fallar antes de T007
- [X] T006 Actualizar `backend/src/test/java/com/shoplab/helpdesk/schema/ModuleSchemasIntegrationTests.java`: el historial de `retrieval.flyway_schema_history` incluye `V1`, `V2` y `V3`, y un segundo arranque no aplica nada
- [X] T007 Crear `backend/src/main/resources/db/migration/retrieval/V3__chunk_embedding_optional.sql`: `ALTER TABLE chunk ALTER COLUMN embedding DROP NOT NULL`, `ALTER TABLE chunk ALTER COLUMN embedding_model DROP NOT NULL` y `ALTER TABLE chunk ADD CONSTRAINT chunk_embedding_pair_chk CHECK ((embedding IS NULL) = (embedding_model IS NULL))`; cabecera de comentario como `V2` (explica que relaja el FR-003 de LAB-19 hasta LAB-21); sin tocar `V1`/`V2`, sin `SET search_path` ni referencias a otros esquemas (lint S1–S3). Hacer pasar T005–T006 y `SchemaIsolationTests`
- [X] T008 [P] Añadir en `backend/src/main/java/com/shoplab/helpdesk/shared/exception/ResponseCodeEnum.java` los códigos `DOCUMENT_UNSUPPORTED_FORMAT` (`SLHD_RETR_00001`, 415), `DOCUMENT_TOO_LARGE` (`SLHD_RETR_00002`, 413), `DOCUMENT_INVALID` (`SLHD_RETR_00003`, 422), `INGEST_SERVICE_UNAVAILABLE` (`SLHD_RETR_00004`, 503) e `INGEST_SERVICE_FAILED` (`SLHD_RETR_00005`, 502) con mensajes por defecto en inglés; ampliar `backend/src/test/java/com/shoplab/helpdesk/shared/infrastructure/in/rest/handler/GlobalExceptionHandlerTests.java` para comprobar el estado HTTP y el código de cada uno
- [X] T009 [P] Crear los modelos y errores de `ingest`: `ingest/app/domain/models.py` (`ParsedSection(path: list[str], blocks: list[str])`, `Chunk(text, document, section, position)`, `IngestResult(name, title, format, chunks)`, `ChunkingConfig(chunk_size, chunk_overlap)` con `chunk_size ≥ 100` y `0 ≤ chunk_overlap < chunk_size`) y `ingest/app/domain/errors.py` (`IngestError` con `code` y `message`; subclases para `UNSUPPORTED_FORMAT`, `DOCUMENT_TOO_LARGE`, `EMPTY_DOCUMENT`, `NO_EXTRACTABLE_TEXT`, `INVALID_DOCUMENT`, `INVALID_CHUNKING_CONFIG`). Pruebas primero en `ingest/tests/test_models.py` (configuración inválida lanza `INVALID_CHUNKING_CONFIG`)
- [X] T010 [P] Crear `ingest/app/core/config.py` (`pydantic-settings`, prefijo `INGEST_`: `chunk_size=1000`, `chunk_overlap=100`, `max_document_bytes=10485760`; rechaza arrancar con solape ≥ tamaño) con `ingest/tests/test_config.py` (valores por defecto, sobreescritura por variable de entorno, combinación inválida)
- [X] T011 Pruebas primero de `ingest/tests/test_splitter.py` para `ingest/app/chunking/splitter.py`: sección que cabe → un chunk; sección larga → varios chunks, ninguno supera `chunk_size` (el máximo **incluye** el solape); corte preferente en párrafo, luego frase (`. `, `? `, `! `, salto de línea), luego palabra, por último carácter; con solape N cada chunk salvo el primero de la sección empieza con los **últimos N caracteres exactos** del anterior; solape 0 no repite; bloque (tabla, lista corta, bloque de código) que cabe no se parte; sección sin texto propio no genera chunk; determinista; el texto de dos secciones nunca se mezcla; acentos y «ñ» intactos
- [X] T012 Implementar `ingest/app/chunking/splitter.py` (`split_sections(sections, config, document) -> list[Chunk]`: el encabezado de la sección es la primera línea del primer chunk; `section` = `path` unida por ` > `; `position` desde 0, consecutiva, sin repeticiones; `text` no vacío y de longitud ≤ `chunk_size`) hasta que pasen las pruebas de T011

**Checkpoint**: `V3` aplicada, errores definidos y troceado probado - las historias pueden empezar

---

## Phase 3: User Story 1 - Trocear un documento Markdown con metadatos (Priority: P1) 🎯 MVP

**Goal**: `POST /ingest` devuelve los chunks de un Markdown, con documento, sección y posición, sin guardar nada.

**Independent Test**: enviar una política Markdown de `data/kb` a `POST /ingest` y comprobar lista ordenada de chunks con
texto, documento, sección y posición; dos envíos del mismo documento dan respuestas idénticas.

### Tests for User Story 1 (escribir primero, deben fallar)

- [X] T013 [P] [US1] `ingest/tests/test_markdown_parser.py`: ruta de encabezados anidados (`A > B`); encabezados saltados (`#` a `###`) sin inventar niveles; Markdown sin encabezados → una sección de ruta vacía; encabezado sin texto debajo no genera sección; tabla, lista y bloque de código se conservan enteros como un bloque; título = primer `#` o, si no hay, nombre del archivo sin extensión; acentos y «ñ» intactos
- [X] T014 [P] [US1] `ingest/tests/test_detect.py`: formato por extensión (`.md` → `markdown`, `.pdf` → `pdf`, otras → `UNSUPPORTED_FORMAT`); un `.pdf` cuyo contenido no empieza por `%PDF-` → `INVALID_DOCUMENT`; documento mayor que `max_document_bytes` → `DOCUMENT_TOO_LARGE`; vacío o solo espacios → `EMPTY_DOCUMENT`; Markdown no UTF-8 → `INVALID_DOCUMENT`; un nombre con ruta (`a/b.md`, `..\x.md`) se normaliza a su nombre base (`b.md`, `x.md`) en `document.name`
- [X] T015 [P] [US1] `ingest/tests/test_api.py` con `TestClient`: `200` con `document` y `chunks` (cada chunk con `text` y `metadata.document/section/position`); posiciones consecutivas desde 0; respuesta idéntica en dos envíos; `chunk_size`/`chunk_overlap` por petición sustituyen los valores por defecto; `415`, `413`, `422` con `code` y `message` según `research.md` D-3; `INVALID_CHUNKING_CONFIG` si `chunk_overlap >= chunk_size`; `GET /health` → `200 {"status":"ok"}`; `GET /metrics` en formato Prometheus con el contador `ingest_requests_total{outcome}` (`ok`, `rejected`, `failed`) y el histograma `ingest_duration_seconds`, sin datos del documento en las etiquetas; 20 peticiones simultáneas (hilos) con documentos distintos devuelven cada una su resultado sin interferencias; ningún archivo ni conexión queda abierto tras responder (sin estado)
- [X] T016 [P] [US1] `ingest/tests/test_corpus.py` (parte Markdown): todos los `data/kb/*.md` (menos `INDEX.md` y `REVIEW.md`) se ingieren sin error, producen ≥ 1 chunk, ninguno supera `chunk_size`, las posiciones son consecutivas desde 0 y ningún chunk mezcla secciones (SC-001–SC-003)

### Implementation for User Story 1

- [X] T017 [US1] Implementar `ingest/app/parsing/detect.py` (formato por extensión y firma, normalización del nombre a nombre base, comprobación de tamaño —antes de leer el cuerpo cuando llega `Content-Length`—, vacío y UTF-8) hasta pasar T014
- [X] T018 [US1] Implementar `ingest/app/parsing/markdown.py` con `markdown-it-py` (`parse_markdown(text) -> (title, list[ParsedSection])`) hasta pasar T013
- [X] T019 [US1] Implementar `ingest/app/service.py` (`ingest_document(name, content, config) -> IngestResult`: detecta formato, parsea, trocea con `splitter`, falla con `EMPTY_DOCUMENT` si no hay chunks; sin estado ni E/S)
- [X] T020 [US1] Implementar la API: `ingest/app/api/schemas.py` (modelos Pydantic `IngestResponse`, `DocumentInfo`, `Chunk`, `ChunkMetadata`, `ErrorResponse` con los nombres y restricciones de `contracts/ingest.openapi.yaml`), `ingest/app/api/errors.py` (manejadores: `IngestError` → estado y `{code, message}`; excepción inesperada → `500 INTERNAL_ERROR` sin filtrar detalles), `ingest/app/api/routes.py` (`POST /ingest` como función síncrona con `UploadFile` y campos de formulario opcionales `chunk_size`, `chunk_overlap`; `GET /health`) e `ingest/app/main.py` (crea la app, registra rutas y manejadores, middleware que lee `X-Request-Id` o genera uno y lo escribe en los logs JSON del servicio) e `ingest/app/core/metrics.py` (`prometheus-client`: contador `ingest_requests_total{outcome}` e histograma `ingest_duration_seconds` según lo probado en T015, y la ruta `GET /metrics`). Pasar T015 y T016

**Checkpoint**: US1 completa - `POST /ingest` trocea Markdown de punta a punta (MVP del servicio)

---

## Phase 4: User Story 2 - Parsear y trocear un PDF (Priority: P1)

**Goal**: el mismo endpoint acepta PDF con texto y devuelve los mismos metadatos, reconociendo los títulos por tamaño de letra.

**Independent Test**: enviar un PDF de `data/kb` a `POST /ingest` y comprobar chunks con texto extraído, sección detectada y posición; un PDF escaneado o corrupto se rechaza.

### Tests for User Story 2 (escribir primero, deben fallar)

- [X] T021 [P] [US2] `ingest/tests/test_pdf_parser.py` con PDF generados con `fpdf2` (y `pypdf` para cifrar): títulos de tamaños distintos → rutas de sección por nivel (el tamaño mayor es nivel 1; título = línea ≥ 1 pt mayor que el cuerpo y ≤ 120 caracteres); texto de un solo tamaño → una sección de ruta vacía; líneas que son solo un número de página se descartan; PDF solo con imágenes o páginas en blanco → `NO_EXTRACTABLE_TEXT`; bytes corruptos con firma `%PDF-` → `INVALID_DOCUMENT`; PDF con contraseña → `INVALID_DOCUMENT`; orden de lectura entre páginas conservado
- [X] T022 [P] [US2] Ampliar `ingest/tests/test_api.py` con `POST /ingest` de un PDF (`200`, mismos campos, `document.format == "pdf"`) y los rechazos de PDF escaneado y corrupto (`422`)
- [X] T023 [P] [US2] Ampliar `ingest/tests/test_corpus.py` con todos los `data/kb/*.pdf`: ingeridos sin error, ≥ 1 chunk, tamaño máximo, posiciones consecutivas y secciones sin mezcla (SC-001–SC-003); y una prueba de tiempo (SC-004): un PDF de 20 páginas generado con `fpdf2` se ingiere en menos de 5 s

### Implementation for User Story 2

- [X] T024 [US2] Implementar `ingest/app/parsing/pdf.py` con `pdfplumber` (`parse_pdf(content: bytes) -> (title, list[ParsedSection])`: agrupa caracteres en líneas, calcula el tamaño de cuerpo como el más frecuente por número de caracteres, detecta títulos y niveles, descarta números de página; traduce `PDFPasswordIncorrect`, errores de `pdfminer` y de lectura a `INVALID_DOCUMENT` y la ausencia de texto a `NO_EXTRACTABLE_TEXT`) hasta pasar T021
- [X] T025 [US2] Conectar `pdf.py` en `ingest/app/service.py` según el formato detectado por `detect.py` y pasar T022–T023

**Checkpoint**: US1 y US2 - `ingest` troceando Markdown y PDF

---

## Phase 5: User Story 3 - `retrieval` entrega el documento a `ingest` y guarda el resultado (Priority: P1)

**Goal**: el módulo `retrieval` llama a `ingest` y guarda documento y chunks de forma atómica, sin vector, reemplazando al reingerir.

**Independent Test**: con `ingest` simulado (WireMock) y PostgreSQL real, ingerir un documento deja una fila en `retrieval.document` y una por chunk en `retrieval.chunk` (con `embedding` y `embedding_model` nulos); reingerirlo no duplica; un fallo de `ingest` no deja nada.

### Tests for User Story 3 (escribir primero, deben fallar)

- [X] T026 [P] [US3] `backend/src/test/java/com/shoplab/helpdesk/retrieval/application/usecase/IngestDocumentServiceTests.java` (Mockito + `StepVerifier`): éxito parsea y luego guarda y devuelve `IngestedDocument`; si `parse` falla (negocio o técnico) no se invoca `replaceDocument` y el error se propaga tal cual; nombre vacío, con separadores de ruta (`/`, `\`) o contenido vacío rechazados antes de llamar a `parse`
- [X] T027 [P] [US3] `backend/src/test/java/com/shoplab/helpdesk/retrieval/infrastructure/out/ingest/IngestWebClientAdapterTests.java` (WireMock): la petición es `multipart/form-data` con la parte `file` y su nombre de archivo, y reenvía `X-Request-Id`; respuesta `200` del ejemplo de `contracts/ingest.openapi.yaml` → `ParsedDocument`; `415` → `DOCUMENT_UNSUPPORTED_FORMAT`; `413` → `DOCUMENT_TOO_LARGE`; `422` → `DOCUMENT_INVALID`; `500`, cuerpo ilegible o sin chunks → `INGEST_SERVICE_FAILED`; conexión rechazada y tiempo de espera → `INGEST_SERVICE_UNAVAILABLE`; respuesta mayor que 256 KiB se lee bien (`max-response-bytes`); el `Timer` `retrieval.ingest.parse` registra `ok`, `rejected` y `failed`
- [X] T028 [P] [US3] `backend/src/test/java/com/shoplab/helpdesk/retrieval/infrastructure/out/persistence/JdbcKnowledgeBaseAdapterTests.java` (`@PostgresTest`, prefijo `test/` y limpieza como en `RetrievalSchemaIntegrationTests`): inserta documento y chunks con `chunk_index`, `content`, `metadata` (`document`, `section`, `position`) y `embedding`/`embedding_model` nulos; reingesta del mismo `source` conserva `document.id`, actualiza `title`, `metadata` y `updated_at` y reemplaza los chunks (menos chunks nuevos → sobrantes eliminados); un fallo a mitad de lote (dos chunks con la misma posición) revierte todo y deja el estado previo; nada fuera de `retrieval.*`
- [X] T029 [P] [US3] `backend/src/test/java/com/shoplab/helpdesk/retrieval/IngestDocumentFlowIntegrationTests.java` (`@SpringBootTest` + `@PostgresTest` + WireMock como `ingest`): flujo completo con el ejemplo del contrato → filas esperadas; `ingest` caído → error `INGEST_SERVICE_UNAVAILABLE` y cero filas nuevas; `ingest` responde `422` → `DOCUMENT_INVALID`; segunda ingesta idéntica → mismo número de documentos y chunks (SC-005, SC-007); BlockHound activo no detecta bloqueos

### Implementation for User Story 3

- [X] T030 [P] [US3] Crear los registros inmutables en `backend/src/main/java/com/shoplab/helpdesk/retrieval/domain/model/`: `ParsedDocument(name, title, format, chunks)`, `ParsedChunk(position, text, section)`, `IngestedDocument(documentId, source, title, chunkCount)`; sin importar Spring, JDBC ni Reactor
- [X] T031 [P] [US3] Crear los puertos en `backend/src/main/java/com/shoplab/helpdesk/retrieval/application/port/`: `in/IngestDocumentUseCase` (+ `IngestDocumentCommand(name, content)` con `content` no vacío y `name` no vacío y sin `/` ni `\`), `out/DocumentParserPort` (`Mono<ParsedDocument> parse(String name, byte[] content)`) y `out/KnowledgeBasePort` (`Mono<IngestedDocument> replaceDocument(ParsedDocument parsed)`)
- [X] T032 [US3] Implementar `backend/src/main/java/com/shoplab/helpdesk/retrieval/application/usecase/IngestDocumentService.java` (parse y luego replace; sin lógica de red ni SQL) hasta pasar T026
- [X] T033 [US3] Configuración: `backend/src/main/java/com/shoplab/helpdesk/retrieval/config/IngestClientProperties.java` (`@ConfigurationProperties("shoplab.retrieval.ingest")`: `baseUrl`, `timeout`, `maxResponseBytes`) y `RetrievalIngestConfig.java` (bean `WebClient` con `baseUrl`, límite de búfer y filtro que copia `requestId` del contexto de Reactor a `X-Request-Id`); añadir a `backend/src/main/resources/application.yml` `shoplab.retrieval.ingest.base-url: ${SHOPLAB_RETRIEVAL_INGEST_BASE_URL:http://localhost:8000}`, `timeout: ${SHOPLAB_RETRIEVAL_INGEST_TIMEOUT:30s}` y `max-response-bytes: ${SHOPLAB_RETRIEVAL_INGEST_MAX_RESPONSE_BYTES:33554432}`
- [X] T034 [US3] Implementar `backend/src/main/java/com/shoplab/helpdesk/retrieval/infrastructure/out/ingest/IngestWebClientAdapter.java` (`MultipartBodyBuilder`, `Timer` Micrometer `retrieval.ingest.parse` etiquetado `outcome`), el DTO `IngestResponse` y `IngestErrorMapper` (códigos de `ingest` → `ResponseCodeEnum` según `data-model.md` §4, construidos con `ExceptionUtil`; sin construir `ResponseEntity`) hasta pasar T027
- [X] T035 [US3] Implementar `backend/src/main/java/com/shoplab/helpdesk/retrieval/infrastructure/out/persistence/JdbcKnowledgeBaseAdapter.java`: una transacción (`TransactionTemplate`) dentro de `BlockingCalls.call`; `INSERT INTO document ... ON CONFLICT (source) DO UPDATE SET title, metadata, updated_at = now() RETURNING id`; `DELETE FROM chunk WHERE document_id = ?`; inserción por lotes con `metadata` como `jsonb`; `document.metadata = {"format": <format>}`; SQL literal solo sobre `retrieval.*` (lo vigila el lint de SQL); fallos JDBC → `DATABASE_UNAVAILABLE`. Pasar T028–T029
- [X] T036 [US3] Ejecutar `./mvnw test -Dtest=ModularityTests` en `backend/` y commitear el resultado regenerado de `docs/modulos/` (la CI falla si difiere); comprobar `ModuleArchitectureTests` y `ReactiveArchitectureTests` (sin `RestTemplate`, JDBC solo vía `BlockingCalls`, dominio sin Spring)

**Checkpoint**: US3 completa - `retrieval` ingiere y guarda; el esquema sigue con un único dueño

---

## Phase 6: User Story 4 - Contrato `POST /ingest` publicado en OpenAPI (Priority: P2)

**Goal**: el contrato de `POST /ingest` queda versionado y la CI falla si el servicio o el cliente lo contradicen.

**Independent Test**: `ingest/openapi.json` describe petición, respuesta y errores; modificar el servicio sin regenerarlo hace fallar `pytest`; modificar un campo del DTO de `retrieval` hace fallar la prueba de contrato del backend.

### Tests for User Story 4 (escribir primero, deben fallar)

- [X] T037 [P] [US4] `ingest/tests/test_openapi_contract.py`: `app.openapi()` es igual a `ingest/openapi.json`; el esquema contiene `POST /ingest` con `multipart/form-data` (`file` obligatorio, `chunk_size`, `chunk_overlap`), respuestas `200/413/415/422/500` y `GET /health`; los `code` de `ErrorResponse` coinciden con los de `ingest/app/domain/errors.py`
- [X] T038 [P] [US4] `backend/src/test/java/com/shoplab/helpdesk/retrieval/IngestContractTests.java`: lee `../ingest/openapi.json` con Jackson; los componentes del DTO `IngestResponse` (y de `DocumentInfo`, `Chunk`, `ChunkMetadata`) coinciden con las propiedades y `required` del esquema; el ejemplo del contrato se deserializa; la petición del adaptador usa los nombres de parte del esquema (`file`)

### Implementation for User Story 4

- [X] T039 [US4] Crear `ingest/app/openapi_export.py` (escribe `app.openapi()` en `ingest/openapi.json` con claves ordenadas e indentación fija) y declarar en las rutas los `responses` de error y el `example` de `IngestResponse` para que el esquema generado coincida con `specs/LAB-20-document-parsing-chunking/contracts/ingest.openapi.yaml`; ejecutar `uv run python -m app.openapi_export` y commitear `ingest/openapi.json`. Pasar T037–T038
- [X] T040 [US4] Ampliar el filtro `backend` de `.github/workflows/ci.yml` con `'ingest/openapi.json'` para que un cambio del contrato dispare las pruebas del backend y añadir `'data/kb/**'` al filtro `ingest` para que un cambio del corpus ejecute las pruebas de corpus de `ingest` (el job `python` ya se dispara con `ingest/**`)

**Checkpoint**: las cuatro historias funcionan y el contrato se verifica en ambos lados

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: despliegue, documentación y verificación final

- [X] T041 [P] Añadir el servicio `ingest` a `infra/docker-compose.yml` (`build: ../ingest`, puerto `${INGEST_PORT:-8000}:8000`, `INGEST_CHUNK_SIZE`/`INGEST_CHUNK_OVERLAP`/`INGEST_MAX_DOCUMENT_BYTES` con sus valores por defecto, healthcheck contra `/health`, `restart: unless-stopped`); **sin** variables, secretos, cadenas de conexión ni `depends_on` de base de datos (`IngestHasNoDatabaseCredentialsTests` rechaza esos textos y la palabra «postgres» dentro del bloque); no escribir en el bloque ni en `ingest/` textos que esa prueba prohíbe (`POSTGRES_`, `DATABASE_URL`, `jdbc:`, «postgres»; redactar como «sin credenciales de base de datos»); añadir el job `ingest` (`ingest:8000`, ruta `/metrics`) a `infra/observability/prometheus/prometheus.yml`; ejecutar `docker compose -f infra/docker-compose.yml --profile observability config -q` y esa prueba
- [X] T042 [P] Crear `ingest/README.md` (qué hace, variables `INGEST_*`, cómo arrancar y probar, cómo regenerar `openapi.json`, `/metrics` y `/health`; límites: sin OCR, solo PDF con texto de una columna y Markdown UTF-8; sin los textos prohibidos por `IngestHasNoDatabaseCredentialsTests`, ver T041)
- [X] T043 Generar `specs/LAB-20-document-parsing-chunking/requests.http` según la regla `http-test-requests`: un caso por escenario de aceptación de US1 y US2 (sección anidada, tamaño máximo y solape, sin encabezados, mismo documento dos veces, PDF con títulos, PDF sin texto, PDF corrupto), los límites del spec (`chunk_overlap` justo por debajo y justo igual a `chunk_size`, documento justo en y por encima de `INGEST_MAX_DOCUMENT_BYTES`) y los errores `415/413/422`; variables `{{baseUrl}}` = `http://localhost:8000`; archivos del corpus referenciados con rutas relativas a `data/kb`; cada petición con su requisito/escenario y su resultado esperado; listar en «Not covered here» lo que no es alcanzable por HTTP (guardado en `retrieval`, FR-011 a FR-015, FR-017)
- [X] T044 [P] Redactar `docs/adr/0016-troceado-por-encabezados-e-ingesta-sincrona.md` con la plantilla `docs/adr/0000-plantilla.md` (D-1, D-2, D-5, D-11 de `research.md`; alternativas descartadas) y añadirlo al índice de `docs/adr/README.md`
- [X] T045 [P] Actualizar `docs/modulos.md` (sección «Datos de `retrieval`»: chunks guardados sin vector hasta LAB-21, `V3`, caso de uso de ingesta y dónde se configura `ingest`) y añadir una nota en `specs/LAB-19-retrieval-schema-ownership/data-model.md` que enlaza a `V3` como relajación del FR-003
- [X] T046 Cambios en el repo hermano `../shoplab-infra` (rama nueva desde `main`, PR propia; **no tocar** los cambios locales sin commitear del usuario en `Makefile`, `docker-compose.yml` ni `scripts/*`): en `k8s/base/ingest/deployment.yaml` sustituir las sondas `tcpSocket` por `httpGet` a `/health` en el puerto `http` (y quitar el comentario obsoleto); en `k8s/base/backend/kustomization.yaml` añadir `SHOPLAB_RETRIEVAL_INGEST_BASE_URL=http://ingest:8000` a `backend-config`; validar con `kubectl kustomize k8s/overlays/dev`; crear `k8s/base/ingest/servicemonitor.yaml` (copia del de `backend`, puerto `http`, ruta `/metrics`) y listarlo en `k8s/base/ingest/kustomization.yaml`; sin credenciales de base de datos en `ingest` **Hecho en la rama `feature/LAB-20-ingest-health-and-metrics` de `shoplab-infra` (sin commit ni PR): debe fusionarse junto con la release que incluya `ingest`, porque la sonda `/health` falla con la imagen marcador anterior.**
- [X] T047 Verificar la imagen: `docker build -t ingest:test ingest`, arrancarla y comprobar `GET /health` y `POST /ingest` con un documento de `data/kb`; revisar que el tamaño de la imagen y las dependencias no introducen hallazgos HIGH/CRITICAL corregibles (el job Trivy de `images.yml` es el control definitivo)
- [X] T048 Checklist previo al commit (`.ai/rules/development-guidelines.md` §8): `cd ingest && uv run ruff check . && uv run pytest`; `cd backend && ./mvnw -B -ntp clean verify` (Checkstyle, PMD, Modulith, JaCoCo ≥ 80 %); `git status --short` sin basura (`.venv`, `.coverage`, `target/`); `git diff` solo con cambios intencionados; medir SC-004 de extremo a extremo con la política PDF más larga de `data/kb` (< 5 s)
- [X] T049 Validar `specs/LAB-20-document-parsing-chunking/quickstart.md` de principio a fin y ejecutar la skill `new-properties` para listar las propiedades de configuración nuevas (`shoplab.retrieval.ingest.*`, `INGEST_*`)
- [X] T050 Marcar las tareas completadas en este archivo y, al fusionar la PR, avisar de que LAB-21 (completar chunks con `embedding IS NULL`) y LAB-25 (endpoint de subida) ya tienen sus criterios en Jira

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias
- **Foundational (Phase 2)**: depende de Setup; **bloquea** todas las historias. T005–T007 (`V3`) bloquean US3; T008 bloquea US3; T009–T012 bloquean US1 y US2
- **US1 (Phase 3)**: depende de T009–T012; es el MVP del servicio
- **US2 (Phase 4)**: depende de US1 (reutiliza `detect`, `service` y API)
- **US3 (Phase 5)**: depende de T005–T008 y del ejemplo de `contracts/`; **no** depende de US1/US2 (usa WireMock)
- **US4 (Phase 6)**: depende de US1 (la API existe) y de US3 (DTOs del backend) para T038
- **Polish (Phase 7)**: depende de las historias deseadas; T043 necesita US1 y US2; T046 y T047 necesitan US1

### User Story Dependencies

- **US1 (P1)**: tras Foundational
- **US2 (P1)**: tras US1
- **US3 (P1)**: tras Foundational; paralelizable con US1/US2 por quien trabaje en el backend
- **US4 (P2)**: tras US1 y US3

### Within Each User Story

- Las pruebas se escriben y fallan antes de implementar
- Modelos y puertos → servicios/casos de uso → adaptadores → integración
- Cada historia se cierra con su punto de control antes de pasar a la siguiente

### Parallel Opportunities

- Fase 2: T008, T009 y T010 son independientes; T005–T007 (SQL) en paralelo con T009–T012 (Python)
- US1: T013–T016 en paralelo (archivos de prueba distintos)
- US2: T021–T023 en paralelo
- US3: T026–T029 en paralelo; T030 y T031 en paralelo
- Polish: T041, T042, T044 y T045 en paralelo

---

## Parallel Example: User Story 3

```text
# Pruebas (archivos distintos):
Task: "T026 IngestDocumentServiceTests"
Task: "T027 IngestWebClientAdapterTests"
Task: "T028 JdbcKnowledgeBaseAdapterTests"
Task: "T029 IngestDocumentFlowIntegrationTests"

# Tipos base (archivos distintos):
Task: "T030 Registros de dominio"
Task: "T031 Puertos de entrada y salida"
```

---

## Implementation Strategy

### MVP First (US1)

1. Setup y Foundational (T001–T012)
2. US1 (T013–T020): `POST /ingest` troceando Markdown
3. **Parar y validar** con `requests.http` parcial y `data/kb/*.md`

### Incremental Delivery

1. + US2 → PDF
2. + US3 → `retrieval` guarda (esquema `V3` ya lista)
3. + US4 → contrato verificado en CI
4. Polish → compose, `shoplab-infra`, ADR, `requests.http`

### Notas

- Un commit = un cambio lógico (`type(scope): description`); la PR lleva `feat(ingest): [LAB-20] ...` como título.
- Si `V3` o el contrato cambian durante la implementación, usar `/loop-spec` para reconciliar spec, plan y tareas.
- Trazas distribuidas: no existe aún pila de trazado en el proyecto; se añaden a backend e `ingest` en una historia de observabilidad (ver Complexity Tracking del plan). Crear esa historia en Jira al fusionar.
