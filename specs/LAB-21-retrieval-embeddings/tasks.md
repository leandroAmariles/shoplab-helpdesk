---

description: "Task list for LAB-21 - Embeddings calculados solo en retrieval"
---

# Tasks: Embeddings calculados solo en retrieval

**Input**: Design documents from `/specs/LAB-21-retrieval-embeddings/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`embedding-indexing.md`), quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas antes o junto con la implementación. En cada historia
las pruebas van primero y deben fallar antes de implementar. Las pruebas de base de datos usan PostgreSQL con pgvector
real (Testcontainers, `@PostgresTest`), sin mocks de base de datos; el modelo de embeddings se sustituye por el doble
determinista `DeterministicEmbeddingPort` (sin servidor de modelos en CI) y `ingest` por WireMock.

**Organization**: Tareas agrupadas por historia de usuario. Todo el trabajo vive en el módulo `retrieval` del backend;
`ingest` no se toca. US1 (indexar) es el MVP: US2 (búsqueda) y US3 (reindexado) dependen de que existan vectores, pero se
prueban de forma independiente insertándolos con la base de datos o con el doble.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US4)
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Main**: `backend/src/main/java/com/shoplab/helpdesk/retrieval/` (en las tareas, `retrieval/`)
- **Tests**: `backend/src/test/java/com/shoplab/helpdesk/` (en las tareas, `test/`)
- Java: líneas ≤ 121 caracteres, llaves en todos los bloques, identificadores en inglés, constantes en
  `@UtilityClass`/`final class` de `model/constants`, JDBC y llamadas bloqueantes solo vía `BlockingCalls`, sin
  `RestTemplate`/`RestClient`, errores con `ExceptionUtil` y `ResponseCodeEnum`
- No hay endpoint HTTP nuevo: no se genera `requests.http`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base verde y configuración del modelo de embeddings

- [X] T001 Comprobar la línea base: `./mvnw -B -ntp test -Dtest='ModuleSchemasIntegrationTests,RetrievalSchemaIntegrationTests,IngestDocumentFlowIntegrationTests,ModuleArchitectureTests'` en `backend/` pasa, y `git diff main -- ingest` está vacío
- [X] T002 Añadir a `backend/src/main/resources/application.yml` `spring.ai.ollama.embedding.options.model: ${OLLAMA_EMBED_MODEL:bge-m3}` y `shoplab.retrieval.embedding.{batch-size: ${SHOPLAB_RETRIEVAL_EMBEDDING_BATCH_SIZE:16}, search-limit: ${SHOPLAB_RETRIEVAL_EMBEDDING_SEARCH_LIMIT:5}, reindex-on-startup: ${SHOPLAB_RETRIEVAL_EMBEDDING_REINDEX_ON_STARTUP:false}}`; crear `retrieval/config/EmbeddingProperties.java` (`@ConfigurationProperties("shoplab.retrieval.embedding")`, record con `batchSize`, `searchLimit`, `reindexOnStartup`, más el modelo leído de `spring.ai.ollama.embedding.options.model`; `batchSize` ≥ 1 y `searchLimit` en [1, 50] o el arranque falla con mensaje claro) y `retrieval/config/RetrievalEmbeddingConfig.java` (`@EnableConfigurationProperties`); prueba `test/retrieval/config/EmbeddingPropertiesTests.java` (valores por defecto y rechazo de `batch-size` < 1)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: estado del documento, dominio, puertos y errores que necesitan todas las historias

**⚠️ CRITICAL**: ninguna historia empieza hasta terminar esta fase

- [X] T003 [P] Actualizar `test/schema/RetrievalSchemaIntegrationTests.java` y `test/schema/ModuleSchemasIntegrationTests.java` para `V4` (deben fallar antes de T005): `document.index_status` por defecto `PENDING`; valor fuera de (`PENDING`, `INDEXED`, `ERROR`) rechazado; `ERROR` exige `index_error` no nulo y `INDEXED` exige `indexed_at` no nulo, y viceversa; el relleno de documentos existentes deja `INDEXED` solo si todos sus chunks tienen vector y `PENDING` si no (incluido el documento sin chunks)
- [X] T004 [P] Actualizar `test/retrieval/infrastructure/out/persistence/JdbcKnowledgeBaseAdapterTests.java` y `test/retrieval/IngestDocumentFlowIntegrationTests.java`: tras `replaceDocument` el documento queda `PENDING` con `index_error` e `indexed_at` nulos; reingerir un documento `INDEXED` o `ERROR` lo devuelve a `PENDING` y cambia `updated_at`
- [X] T005 Crear `backend/src/main/resources/db/migration/retrieval/V4__document_index_status.sql` según `data-model.md` §1: columnas `index_status text NOT NULL DEFAULT 'PENDING' CHECK (index_status IN ('PENDING', 'INDEXED', 'ERROR'))`, `index_error text`, `indexed_at timestamptz`, restricción `document_index_state_chk` (`(index_status = 'ERROR') = (index_error IS NOT NULL) AND (index_status = 'INDEXED') = (indexed_at IS NOT NULL)`) y `UPDATE` de relleno; comentario de cabecera como V1–V3; sin tocar `chunk_embedding_hnsw_idx` ni otras migraciones
- [X] T006 Modificar `retrieval/infrastructure/out/persistence/JdbcKnowledgeBaseAdapter.java`: `UPSERT_DOCUMENT` fija `index_status = 'PENDING'`, `index_error = NULL`, `indexed_at = NULL` y `updated_at = now()` en el `ON CONFLICT`; actualizar el Javadoc de la clase (los chunks se completan en LAB-21)
- [X] T007 [P] Crear en `retrieval/domain/model/` los registros `PendingDocument` (`documentId: UUID`, `source`, `version: Instant`, `chunks: List<ChunkToEmbed>`), `ChunkToEmbed` (`chunkId: UUID`, `position`, `text`), `ChunkEmbedding` (`chunkId: UUID`, `vector: float[]`, `vector.length == 1024`), `SearchResult` (`chunkId`, `documentId`, `source`, `section`, `position`, `text`, `score: double`), `IndexingSummary` (`documents`, `chunks`, `failed: List<IndexingFailure>`, `skipped: boolean`), `IndexingFailure` (`source`, `code`), `DocumentIngestedEvent` (`documentId`, `source`); y los enums `enums/IndexStatus` (`PENDING`, `INDEXED`, `ERROR`, con `externalValue` como `DocumentFormat`), `enums/IndexScope` (`PENDING`, `OTHER_MODEL`, `ALL`), `enums/IndexOutcome` (`INDEXED`, `FAILED`, `SKIPPED`, con `tagValue()` como `IngestOutcome`); sin dependencias de Spring, JDBC ni Reactor
- [X] T008 [P] Ampliar `retrieval/domain/model/constants/RetrievalConstants.java` con `EMBEDDING_DIMENSIONS = 1024`, `EMBEDDING_BATCH_METRIC = "retrieval.embedding.batch"`, `EMBEDDING_DOCUMENTS_METRIC = "retrieval.embedding.documents"`, `SEARCH_METRIC = "retrieval.search"` y `MAX_SEARCH_LIMIT = 50`
- [X] T009 [P] Añadir a `backend/src/main/java/com/shoplab/helpdesk/shared/exception/ResponseCodeEnum.java` `EMBEDDING_SERVICE_UNAVAILABLE("SLHD_RETR_00006", 503)`, `EMBEDDING_SERVICE_FAILED("SLHD_RETR_00007", 502)`, `EMBEDDING_DIMENSION_MISMATCH("SLHD_RETR_00008", 502)` y `SEARCH_QUERY_INVALID("SLHD_RETR_00009", 422)` con mensajes en inglés como los de `SLHD_RETR_00001`–`00005`; verificar con la prueba existente de `ResponseCodeEnum` (o crearla en `test/shared/`) que los códigos no se repiten
- [X] T010 [P] Crear los puertos en `retrieval/application/port/in/` (`IndexDocumentsServicePort.indexPending(boolean retryFailed)`, `ReindexDocumentsServicePort.reindex(boolean force)`, `SearchChunksServicePort.search(String query, Integer limit)`) y `retrieval/application/port/out/` (`EmbeddingPort.embed(List<String>)` + `modelName()`, `EmbeddingStorePort` con `findDocuments(IndexScope, String model)`, `saveEmbeddings(PendingDocument, List<ChunkEmbedding>, String model)` → `Mono<Boolean>`, `markFailed(PendingDocument, String reason)`, `search(float[], String model, int limit)`, `EmbeddingMetricsPort`) con las firmas y el Javadoc de `contracts/embedding-indexing.md`
- [X] T011 [P] Crear el doble `test/retrieval/support/DeterministicEmbeddingPort.java` (implementa `EmbeddingPort`; vector de 1 024 valores derivado del texto de forma determinista y normalizado, mismo texto ⇒ mismo vector, textos distintos ⇒ vectores distintos; nombre de modelo configurable; contador de llamadas y modo de fallo inyectable para las pruebas) y su prueba `DeterministicEmbeddingPortTests`
- [X] T012 [P] Crear `retrieval/infrastructure/out/persistence/VectorLiteral.java` (formatea `float[]` como `[v1,v2,...]` con `Locale.ROOT`) y `test/retrieval/infrastructure/out/persistence/VectorLiteralTests.java` (1 024 valores, decimales con punto, vector vacío rechazado)
- [X] T013 [P] Crear `retrieval/infrastructure/out/metrics/MicrometerEmbeddingMetrics.java` (implementa `EmbeddingMetricsPort`: `Timer` `retrieval.embedding.batch` y `retrieval.search` con etiqueta `outcome` de valores fijos, contador `retrieval.embedding.documents` por `IndexOutcome`) y su prueba `MicrometerEmbeddingMetricsTests` con `SimpleMeterRegistry`

**Checkpoint**: `V4` aplicada, la reingesta deja el documento `PENDING` y los puertos compilan; las historias pueden empezar

---

## Phase 3: User Story 1 - Completar los chunks pendientes con su vector (Priority: P1) 🎯 MVP

**Goal**: los chunks guardados sin vector reciben su embedding en `retrieval`, con su `embedding_model`, y el documento pasa
de pendiente a indexado o a error (FR-001–FR-008, FR-015–FR-017, FR-019).

**Independent Test**: con un documento ingerido (chunks sin vector) y el doble determinista, `indexPending(false)` deja todos
sus chunks con vector de 1 024 valores y `embedding_model`, y el documento `INDEXED`; con el doble en modo fallo queda
`ERROR` con causa y conserva sus chunks.

### Tests for User Story 1 (escribirlas primero y comprobar que fallan)

- [X] T014 [P] [US1] `test/retrieval/application/usecase/IndexDocumentsUseCaseTests.java` (Mockito sobre los puertos, sin Spring): documento pendiente ⇒ lotes de `batch-size` al modelo, `saveEmbeddings` con el modelo configurado e `INDEXED`; el último lote parcial funciona; fallo del modelo en un lote ⇒ `markFailed` con el mensaje del código (sin texto del documento) y no se guarda ningún vector de ese documento; un documento en error no impide indexar los demás; `retryFailed = false` no recorre los `ERROR` y `true` sí; segunda ejecución sin pendientes ⇒ cero llamadas al modelo (idempotente); `saveEmbeddings` devuelve `false` (reingesta concurrente) ⇒ resultado descartado y documento sin marcar `ERROR`; ejecución simultánea ⇒ la segunda devuelve `skipped = true`; métricas registradas
- [X] T015 [P] [US1] `test/retrieval/infrastructure/out/persistence/JdbcEmbeddingStoreAdapterTests.java` (`@PostgresTest`, parte de indexado): `findDocuments(PENDING, model)` devuelve solo documentos `PENDING` con sus chunks ordenados por `chunk_index` y su `version`; `saveEmbeddings` escribe `embedding` y `embedding_model` de todos los chunks y deja el documento `INDEXED` con `indexed_at` en una sola transacción; si falla a mitad (vector de dimensión incorrecta) no cambia nada; con `updated_at` cambiado por una reingesta devuelve `false` y no escribe; `markFailed` fija `ERROR` e `index_error` sin tocar vectores; documentos y chunks ajenos no se modifican; el texto, la posición y los metadatos de los chunks quedan intactos
- [X] T016 [P] [US1] `test/retrieval/infrastructure/out/embedding/SpringAiEmbeddingAdapterTests.java` (Mockito sobre `EmbeddingModel`): devuelve un vector por texto en el mismo orden; `modelName()` es el configurado; vector de dimensión ≠ 1 024 ⇒ `EMBEDDING_DIMENSION_MISMATCH`; excepción de conexión o tiempo de espera ⇒ `EMBEDDING_SERVICE_UNAVAILABLE`; otra excepción del modelo (p. ej. texto demasiado largo) ⇒ `EMBEDDING_SERVICE_FAILED`; número de vectores distinto del de textos ⇒ `EMBEDDING_SERVICE_FAILED`; la llamada bloqueante va por `BlockingCalls` (BlockHound activo)
- [X] T017 [P] [US1] `test/retrieval/infrastructure/in/event/IndexAfterIngestListenerTests.java` y `IndexOnStartupRunnerTests.java`: el evento tras la ingesta lanza `indexPending(false)` sin esperar su resultado y un error se registra sin propagarse; al arrancar se indexan los `PENDING` y nunca los `ERROR`; `IngestDocumentUseCaseTests` (existente) comprueba que se publica `DocumentIngestedEvent` solo tras guardar y no si falla el parseo o el guardado

### Implementation for User Story 1

- [X] T018 [US1] Crear `retrieval/application/usecase/EmbeddingBatches.java` (parte una lista en lotes de tamaño `batchSize` conservando el orden) y `retrieval/application/usecase/IndexingGuard.java` (cerrojo no bloqueante en memoria con `AtomicBoolean`: `tryAcquire()`/`release()`), compartido por indexar y reindexar (research D-5); pruebas unitarias de ambos
- [X] T019 [US1] Crear `retrieval/application/usecase/IndexDocumentsUseCase.java` (`@Service`, implementa `IndexDocumentsServicePort`): adquiere el cerrojo (si no, `IndexingSummary` con `skipped = true`), recorre `EmbeddingStorePort.findDocuments(IndexScope.PENDING, model)`, calcula los vectores por lotes con `EmbeddingPort`, guarda con `saveEmbeddings` y, ante `HelpdeskException` del modelo, `markFailed(document, <mensaje del código>)` y sigue con el siguiente; `retryFailed = true` incluye los `ERROR`; registra métricas y logs con identificadores y recuentos, nunca texto; devuelve el `IndexingSummary`; libera el cerrojo siempre
- [X] T020 [US1] Crear `retrieval/infrastructure/out/persistence/JdbcEmbeddingStoreAdapter.java` (`@Component`, `@DependsOn("moduleSchemaMigrator")`, implementa `EmbeddingStorePort`): `findDocuments` (consulta corta de documentos y chunks por ámbito), `saveEmbeddings` (transacción: `SELECT updated_at ... FOR UPDATE`, comparación con `version`, `batchUpdate` de chunks con `?::public.vector` y `VectorLiteral`, `UPDATE document` a `INDEXED`; devuelve `false` si la versión cambió) y `markFailed` (`ERROR` + `index_error`); todo por `BlockingCalls`; `DataAccessException`/`TransactionException` ⇒ `DATABASE_UNAVAILABLE`, como `JdbcKnowledgeBaseAdapter`
- [X] T021 [US1] Crear `retrieval/infrastructure/out/embedding/SpringAiEmbeddingAdapter.java` (`@Component`, implementa `EmbeddingPort`): `EmbeddingModel.embed(List<String>)` por `BlockingCalls`, validación de 1 024 dimensiones y de cantidad, traducción de errores según T016 con `ExceptionUtil.technical(...)`; `modelName()` devuelve el modelo de `EmbeddingProperties`
- [X] T022 [US1] Modificar `retrieval/application/usecase/IngestDocumentUseCase.java` para publicar `DocumentIngestedEvent` (puerto de salida o `ApplicationEventPublisher` en el borde, sin que el caso de uso conozca embeddings) tras `replaceDocument`; crear `retrieval/infrastructure/in/event/IndexAfterIngestListener.java` (`@EventListener`; lanza `indexPending(false)` con `subscribe` y registra el error) y `retrieval/infrastructure/in/event/IndexOnStartupRunner.java` (`ApplicationRunner`; indexa los `PENDING` al arrancar sin bloquear el arranque ni fallar si el servidor de modelos no está)
- [X] T023 [US1] Ejecutar `./mvnw -B -ntp test -Dtest='IndexDocumentsUseCaseTests,JdbcEmbeddingStoreAdapterTests,SpringAiEmbeddingAdapterTests,IndexAfterIngestListenerTests,IndexOnStartupRunnerTests,IngestDocumentUseCaseTests'` en `backend/`: todas pasan, y `ModuleArchitectureTests`/`ReactiveArchitectureTests` siguen en verde (Spring AI e JDBC solo en `infrastructure`, dominio sin Spring)

**Checkpoint**: US1 funciona por sí sola: ingesta → chunks pendientes → vectores → documento indexado o en error

---

## Phase 4: User Story 2 - Embedding de la consulta y búsqueda solo entre vectores del mismo modelo (Priority: P1)

**Goal**: buscar calcula el embedding de la consulta y devuelve los chunks más cercanos solo entre los que tienen vector del
modelo configurado (FR-009–FR-011, FR-015).

**Independent Test**: con chunks de dos modelos y chunks sin vector, `search` devuelve solo chunks del modelo configurado con
vector, ordenados por similitud; una consulta vacía se rechaza sin llamar al modelo.

### Tests for User Story 2

- [X] T024 [P] [US2] `test/retrieval/application/usecase/SearchChunksUseCaseTests.java` (Mockito): consulta vacía, nula o solo espacios ⇒ `SEARCH_QUERY_INVALID` sin llamar a `EmbeddingPort`; embebe la consulta, pide `search(vector, modelo configurado, límite)` y devuelve el resultado; `limit` nulo usa `searchLimit`, un valor fuera de [1, 50] se acota; sin resultados ⇒ lista vacía; fallo del modelo ⇒ el error tipado se propaga y no hay resultados parciales; métrica de búsqueda registrada
- [X] T025 [P] [US2] Ampliar `test/retrieval/infrastructure/out/persistence/JdbcEmbeddingStoreAdapterTests.java` (parte de búsqueda): con chunks del modelo A, del modelo B y sin vector, `search(.., A, n)` devuelve solo los del A con vector (SC-002); el orden es por similitud de coseno descendente y `score = 1 - distancia`; el resultado trae documento, sección, posición y texto; sin chunks elegibles ⇒ lista vacía; respeta el límite

### Implementation for User Story 2

- [X] T026 [US2] Añadir `search` a `retrieval/infrastructure/out/persistence/JdbcEmbeddingStoreAdapter.java` con la consulta de `contracts/embedding-indexing.md` (`WHERE c.embedding IS NOT NULL AND c.embedding_model = ?`, `ORDER BY c.embedding <=> ?::public.vector`, `LIMIT ?`), parametrizada y por `BlockingCalls`
- [X] T027 [US2] Crear `retrieval/application/usecase/SearchChunksUseCase.java` (`@Service`, implementa `SearchChunksServicePort`) con la validación, el acotado del límite, el embedding de la consulta y la métrica `retrieval.search`; el texto de la consulta nunca se registra en logs
- [X] T028 [US2] Ejecutar `./mvnw -B -ntp test -Dtest='SearchChunksUseCaseTests,JdbcEmbeddingStoreAdapterTests'` en `backend/`: pasan; comprobar con `EXPLAIN` en la prueba (o anotar en la tarea) que la consulta usa el índice HNSW cuando hay datos suficientes y que no hay `ORDER BY` sin límite (nota: el `EXPLAIN` no se comprobó; la consulta es la del contrato)

**Checkpoint**: US1 + US2 funcionan: se indexa y se busca solo entre vectores del modelo vigente

---

## Phase 5: User Story 3 - Reindexar para cambiar de modelo sin tocar ingest (Priority: P1)

**Goal**: recalcular los embeddings con el modelo configurado sin volver a parsear ni tocar `ingest`, sin que un documento
mezcle modelos (FR-012–FR-014, FR-017).

**Independent Test**: con documentos indexados con el modelo A, configurar el B y reindexar: todos los chunks pasan al B,
`embedding_model` se actualiza, `ingest` no recibe llamadas y texto, posición y metadatos quedan iguales.

### Tests for User Story 3

- [X] T029 [P] [US3] `test/retrieval/application/usecase/ReindexDocumentsUseCaseTests.java` (Mockito): `reindex(false)` usa `IndexScope.OTHER_MODEL` y `reindex(true)` usa `ALL`; documentos ya con el modelo configurado no se recalculan sin `force`; un fallo a mitad de un documento ⇒ `markFailed`, vectores anteriores intactos y el siguiente documento se procesa; el resumen cuenta documentos y chunks reindexados, fallidos con su código y `skipped` si hay otra ejecución (comparte `IndexingGuard`); no usa `DocumentParserPort`
- [X] T030 [P] [US3] Ampliar `test/retrieval/infrastructure/out/persistence/JdbcEmbeddingStoreAdapterTests.java` (parte de reindexado): `findDocuments(OTHER_MODEL, B)` devuelve los documentos con algún chunk de modelo ≠ B o sin vector y no los que ya están en B; `ALL` devuelve todos; tras `saveEmbeddings` con B ningún documento mezcla modelos; si el guardado falla el documento conserva sus vectores del A completos; durante el reindexado `search(.., B, n)` solo devuelve documentos ya pasados a B y no falla
- [X] T031 [P] [US3] Ampliar `test/retrieval/infrastructure/in/event/IndexOnStartupRunnerTests.java`: `reindex-on-startup=false` solo indexa pendientes; `true` reindexa los de otro modelo; `force` reindexa todos; cualquier valor distinto se rechaza al arrancar con un mensaje claro

### Implementation for User Story 3

- [X] T032 [US3] Ampliar `findDocuments` de `retrieval/infrastructure/out/persistence/JdbcEmbeddingStoreAdapter.java` con los ámbitos `OTHER_MODEL` (documentos con algún chunk de `embedding_model` distinto del modelo o `embedding IS NULL`) y `ALL`
- [X] T033 [US3] Extraer la rutina «calcular y guardar un documento» de `IndexDocumentsUseCase` a un componente de aplicación `DocumentEmbedder` (sin Spring salvo `@Component`), usado por indexar y reindexar; crear `retrieval/application/usecase/ReindexDocumentsUseCase.java` (`@Service`, implementa `ReindexDocumentsServicePort`) que recorre `OTHER_MODEL` o `ALL`, registra métricas y devuelve el `IndexingSummary`; repetir las pruebas de T014 para confirmar que indexar no cambió
- [X] T034 [US3] Ampliar `retrieval/infrastructure/in/event/IndexOnStartupRunner.java` y `retrieval/config/EmbeddingProperties.java` para `reindex-on-startup` (`false` | `true` | `force`, como enum `ReindexMode` en `domain/model/enums/`) y registrar un aviso cuando existan chunks de un modelo distinto del configurado y no se reindexe (caso límite «configuración cambiada sin reindexar»)
- [X] T035 [US3] Ejecutar `./mvnw -B -ntp test -Dtest='ReindexDocumentsUseCaseTests,IndexDocumentsUseCaseTests,JdbcEmbeddingStoreAdapterTests,IndexOnStartupRunnerTests'` en `backend/`: todas pasan

**Checkpoint**: cambiar de modelo = cambiar una variable y reiniciar con `reindex-on-startup=true`

---

## Phase 6: User Story 4 - Prueba de integración del flujo completo (Priority: P2)

**Goal**: una prueba determinista recorre documento → `ingest` → chunks → embeddings guardados en el esquema `retrieval`
(FR-018, SC-001, SC-009).

**Independent Test**: ejecutar `EmbeddingFlowIntegrationTests`: pasa en CI sin servidor de modelos.

- [X] T036 [US4] Crear `test/retrieval/EmbeddingFlowIntegrationTests.java` (`@PostgresTest`, contexto completo con `ingest` simulado por WireMock como en `IngestDocumentFlowIntegrationTests` y `DeterministicEmbeddingPort` sustituyendo al puerto): (1) ingerir un Markdown de `data/kb` ⇒ documento `PENDING` ⇒ tras indexar queda `INDEXED` y cada fila de `retrieval.chunk` tiene vector de 1 024 valores y `embedding_model`; (2) buscar un texto contenido en el documento devuelve su chunk entre los primeros; (3) cambiar el modelo del doble y reindexar ⇒ un solo `embedding_model` en la tabla y `ingest` sin llamadas nuevas (WireMock `verify`); (4) apagar el doble (modo fallo) y reingerir ⇒ documento `ERROR` con `index_error` y chunks intactos, y al restablecerlo `indexPending(true)` lo deja `INDEXED`; (5) reingerir un documento `INDEXED` ⇒ vuelve a `PENDING` sin vectores de la versión anterior
- [X] T037 [US4] Añadir a esa prueba el caso de concurrencia: una reingesta entre la lectura y el guardado de vectores descarta el resultado (el documento queda `PENDING`, ningún chunk nuevo recibe un vector de contenido ajeno), y dos ejecuciones simultáneas no procesan dos veces el mismo documento (contador de llamadas del doble)
- [X] T038 [US4] Ejecutar `./mvnw -B -ntp test -Dtest=EmbeddingFlowIntegrationTests` en `backend/` varias veces seguidas (sin flakiness) y comprobar en la salida que no se usa ningún servidor de modelos

**Checkpoint**: las cuatro historias funcionan y el flujo completo está cubierto

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: ADR, documentación, verificación de límites y checklist previo al commit

- [X] T039 [P] Crear `docs/adr/0017-embeddings-solo-en-retrieval.md` con la plantilla de `docs/adr/0000-plantilla.md`: embeddings solo en `retrieval` detrás de un puerto, nombre del modelo desde una sola propiedad, estado del documento (`V4`), cálculo fuera de la transacción con comprobación optimista, cerrojo en memoria hasta F5 (después, `FOR UPDATE SKIP LOCKED`), búsqueda por modelo y el riesgo del filtro tras el recorrido HNSW con sus salidas (escaneo iterativo o índice parcial); añadir la fila en `docs/adr/README.md`
- [X] T040 [P] Actualizar `docs/modulos.md` (sección «Datos de `retrieval`»: estado del documento y `V4`, vectores completados por `retrieval`, reindexado por configuración y reinicio, búsqueda mínima por modelo) y añadir una nota en `specs/LAB-20-document-parsing-chunking/spec.md` o `data-model.md` que enlaza a LAB-21 como cierre del «trabajo derivado» sobre chunks sin vector
- [X] T041 Ejecutar `./mvnw test -Dtest=ModularityTests` en `backend/` y commitear el resultado regenerado de `docs/modulos/` (la CI falla si difiere); comprobar `ModuleArchitectureTests`, `ReactiveArchitectureTests` y `IngestHasNoDatabaseCredentialsTests`
- [X] T042 Verificar la configuración de despliegue: en `infra/docker-compose.yml` Ollama ya descarga `OLLAMA_EMBED_MODEL` (sin cambios); en el repositorio hermano `../shoplab-infra` comprobar que el backend recibe `OLLAMA_EMBED_MODEL` (si solo la recibe Ollama, añadirla al `ConfigMap` del backend en una rama y PR propios de ese repositorio; el valor por defecto `bge-m3` coincide, así que no bloquea) y anotar el resultado en el PR
- [ ] T043 [P] Prueba manual de `quickstart.md` §2 con Ollama real (opcional): ingesta ⇒ `PENDING` ⇒ `INDEXED`; cambio de modelo con `reindex-on-startup=true`; Ollama apagado ⇒ `ERROR` y reintento; comprobar que `/actuator/prometheus` expone `retrieval_embedding_batch_seconds` y `retrieval_embedding_documents_total` y que ningún log contiene texto de chunks ni de consultas
- [X] T044 Checklist previo al commit (`.ai/rules/development-guidelines.md` §8): `cd backend && ./mvnw -B -ntp clean verify` (Checkstyle, PMD, Modulith, JaCoCo ≥ 80 %); `git status --short` sin basura (`target/`, `*.log`); `git diff main -- ingest` vacío; `git diff` solo con cambios intencionales; commits atómicos con el formato `type(scope): description`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias
- **Foundational (Phase 2)**: depende de Setup; **bloquea** todas las historias. Orden interno: T003/T004 (pruebas) → T005/T006 (migración y adaptador); T007–T013 son independientes entre sí tras T002
- **US1 (Phase 3)**: depende de la fase 2. Es el MVP
- **US2 (Phase 4)**: depende de la fase 2 y del adaptador JDBC de US1 (T020); se prueba insertando vectores directamente
- **US3 (Phase 5)**: depende de US1 (reutiliza `IndexDocumentsUseCase`, `IndexingGuard` y el adaptador)
- **US4 (Phase 6)**: depende de US1, US2 y US3
- **Polish (Phase 7)**: depende de las historias deseadas; T041 y T044 al final

### User Story Dependencies

- **US1 (P1)**: independiente tras la fase 2
- **US2 (P1)**: comparte `JdbcEmbeddingStoreAdapter` con US1; sin dependencia funcional (otra consulta)
- **US3 (P1)**: extiende el adaptador y el caso de uso de US1
- **US4 (P2)**: valida el conjunto

### Within Each User Story

- Las pruebas se escriben primero y deben fallar antes de implementar
- Dominio y puertos → casos de uso → adaptadores → disparo (eventos) → prueba de la historia
- Las tareas que tocan el mismo archivo (`JdbcEmbeddingStoreAdapter`, `IndexOnStartupRunner`, `IndexDocumentsUseCase`) van en orden, nunca en paralelo

### Parallel Opportunities

- Fase 2: T003 ∥ T004; T007 ∥ T008 ∥ T009 ∥ T010 ∥ T011 ∥ T012 ∥ T013
- US1: T014 ∥ T015 ∥ T016 ∥ T017 (archivos de prueba distintos)
- US2: T024 ∥ T025; US3: T029 ∥ T030 ∥ T031
- Con dos personas, tras la fase 2: una en US1 y otra en US2 (excepto T026, que espera a T020)
- Polish: T039 ∥ T040 ∥ T043

---

## Parallel Example: Phase 2

```text
Tarea: "T007 Registros y enums de dominio en retrieval/domain/model/"
Tarea: "T009 Códigos SLHD_RETR_00006–00009 en ResponseCodeEnum"
Tarea: "T011 DeterministicEmbeddingPort en test/retrieval/support/"
Tarea: "T012 VectorLiteral en retrieval/infrastructure/out/persistence/"
```

## Implementation Strategy

### MVP First (User Story 1)

1. Fase 1 (Setup) y fase 2 (Foundational)
2. Fase 3 (US1): indexar los pendientes
3. **Parar y validar**: ingerir un documento y comprobar que queda `INDEXED` con sus vectores

### Incremental Delivery

1. Setup + Foundational ⇒ `V4` y puertos
2. US1 ⇒ el contenido queda vectorizado (MVP)
3. US2 ⇒ búsqueda por modelo (desbloquea LAB-22)
4. US3 ⇒ cambio de modelo sin tocar `ingest`
5. US4 ⇒ prueba de extremo a extremo
6. Polish ⇒ ADR-0017, documentación, `docs/modulos`, verificación de infra y `verify`

---

## Notes

- [P] = archivos distintos y sin dependencias pendientes
- La etiqueta [Story] vincula cada tarea con su historia para la trazabilidad
- Las restricciones de `data-model.md` van citadas en T003 y T005 (`index_status`, `index_error`, `indexed_at`)
- No se añaden dependencias Maven: `spring-ai-starter-model-ollama` ya está en el `pom.xml`
- Commitear tras cada tarea o grupo lógico; detenerse en cada checkpoint para validar la historia
