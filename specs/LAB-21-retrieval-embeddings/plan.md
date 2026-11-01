# Implementation Plan: Embeddings calculados solo en retrieval

**Branch**: `feature/LAB-21-retrieval-embeddings` | **Date**: 2026-10-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-21-retrieval-embeddings/spec.md`

## Summary

El módulo `retrieval` pasa a ser el único que calcula embeddings. Se añaden tres casos de uso con sus puertos: **indexar**
(completar con vector los chunks pendientes y marcar el documento como indexado o en error), **reindexar** (recalcular con
el modelo configurado los chunks de otro modelo, o todos si se fuerza) y **buscar** (embedding de la consulta y
vecinos más cercanos solo entre chunks con vector del mismo modelo). El `EmbeddingModel` de Spring AI (Ollama, `bge-m3`)
queda detrás de un puerto de salida. Una migración `V4` añade el estado del documento (`PENDING`, `INDEXED`, `ERROR`) y la
causa del último error. El cálculo se hace fuera de la transacción y se guarda por documento de forma atómica con una
comprobación optimista (`updated_at`) contra reingestas concurrentes. Sin cambios en `ingest`, su contrato ni sus
dependencias.

Decisiones y alternativas en [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 y SQL PostgreSQL 16 (`retrieval`); `ingest` sin cambios

**Primary Dependencies**: Spring Boot 4.1 (WebFlux, JDBC), Spring AI 2.0.2 (`spring-ai-starter-model-ollama`, ya en el
`pom.xml`: aporta `EmbeddingModel`), Micrometer. Sin dependencias nuevas.

**Storage**: PostgreSQL 16 + pgvector, esquema `retrieval` (ADR-0002, ADR-0015); migración `V4` aditiva

**Testing**: JUnit 5 + Mockito (casos de uso y adaptador del `EmbeddingModel`), Testcontainers (`@PostgresTest`) para
persistencia, búsqueda y concurrencia, WireMock para `ingest` en la prueba de flujo, doble determinista del puerto de
embeddings, ArchUnit/Modulith y BlockHound existentes

**Target Platform**: contenedores Linux; local con `docker compose` (Ollama ya incluido); dev por GitOps (`shoplab-infra`)

**Project Type**: web-service (módulo `retrieval` del monolito modular)

**Performance Goals**: indexar una política típica (≤ 20 páginas) < 30 s (SC-006); búsqueda sobre el corpus < 2 s
(SC-007)

**Constraints**: vector fijo de 1 024 dimensiones; JDBC solo vía `BlockingCalls`; llamadas al modelo sin bloquear el
event loop (el `EmbeddingModel` es bloqueante, también va por `BlockingCalls`); líneas ≤ 121 caracteres; sin texto de
chunks ni consultas en logs; sin REST ni pantalla (LAB-25)

**Scale/Scope**: corpus de ~25 documentos (LAB-18), unos cientos de chunks; diseño válido hasta miles por documento con
lotes configurables

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | Cumple: todo ocurre dentro de `retrieval`, único dueño del esquema; ningún otro módulo toca sus tablas. `ingest` no cambia ni conoce embeddings. |
| II. Hexagonal | Cumple: casos de uso en `application` con puertos (`EmbeddingPort`, `EmbeddingStorePort`, `EmbeddingMetricsPort`); `EmbeddingModel` de Spring AI y JDBC solo en `infrastructure`; acceso a LLM/embeddings detrás de puerto como exige la constitución. |
| III. Inglés y errores tipados | Cumple: identificadores en inglés; cuatro códigos nuevos `SLHD_RETR_*` en `ResponseCodeEnum`, creados con `ExceptionUtil`; sin `ResponseEntity` en adaptadores. |
| IV. Pruebas con cobertura | Cumple: pruebas con la implementación, ≥ 80 % (JaCoCo), integración contra PostgreSQL real; el modelo se sustituye por un doble determinista en CI. |
| V. RAG con evaluación | No aplica todavía (desde F2). Modelo y tamaño de lote configurables y `embedding_model` por chunk permiten comparar modelos en la evaluación. |
| VI. Observabilidad y datos seguros | Cumple: `Timer` por lote y contadores de documentos por resultado, logs con identificadores y recuentos, nunca texto. Trazas distribuidas siguen fuera (desviación ya registrada en LAB-20). |
| VII. Simplicidad y ADR | Cumple con ADR-0017 (embeddings solo en `retrieval`, estado del documento y búsqueda por modelo). Sin colas ni planificador: disparo por evento en proceso y barrido al arrancar. |

**Gate**: PASA, sin desviaciones nuevas.

**Re-evaluación tras el diseño**: PASA. El diseño añade puertos y casos de uso al módulo, una migración aditiva y ninguna
dependencia; la concurrencia se resuelve con una comprobación optimista en SQL, sin bloqueos largos ni infraestructura.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-21-retrieval-embeddings/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── embedding-indexing.md      # puertos, errores, configuración y SQL de búsqueda
├── checklists/requirements.md
└── tasks.md                       # lo genera /speckit-tasks
```

(Sin `requests.http`: la historia no añade ni cambia endpoints HTTP.)

### Source Code (repository root)

```text
backend/
├── src/main/java/com/shoplab/helpdesk/retrieval/
│   ├── domain/model/
│   │   ├── PendingDocument, ChunkToEmbed, ChunkEmbedding     # records (documento y chunks a calcular)
│   │   ├── SearchResult, IndexingSummary, IndexingFailure     # records
│   │   ├── constants/RetrievalConstants                       # + EMBEDDING_DIMENSIONS, métricas
│   │   └── enums/ (IndexStatus, IndexScope, IndexOutcome)
│   ├── application/
│   │   ├── port/in/ (IndexDocumentsServicePort, ReindexDocumentsServicePort, SearchChunksServicePort)
│   │   ├── port/out/ (EmbeddingPort, EmbeddingStorePort, EmbeddingMetricsPort)
│   │   └── usecase/ (IndexDocumentsUseCase, ReindexDocumentsUseCase, SearchChunksUseCase, EmbeddingBatches)
│   ├── infrastructure/
│   │   ├── in/event/ (IndexAfterIngestListener, IndexOnStartupRunner)
│   │   └── out/
│   │       ├── embedding/ (SpringAiEmbeddingAdapter)
│   │       ├── persistence/ (JdbcEmbeddingStoreAdapter, VectorLiteral)
│   │       └── metrics/MicrometerEmbeddingMetrics
│   └── config/ (EmbeddingProperties, RetrievalEmbeddingConfig)
├── src/main/java/com/shoplab/helpdesk/retrieval/application/usecase/IngestDocumentUseCase   # publica DocumentIngestedEvent
├── src/main/java/com/shoplab/helpdesk/shared/exception/ResponseCodeEnum.java   # + 4 códigos SLHD_RETR_*
├── src/main/resources/db/migration/retrieval/V4__document_index_status.sql     # NUEVO
├── src/main/resources/application.yml          # + spring.ai.ollama.embedding.options.model y shoplab.retrieval.embedding.*
└── src/test/java/com/shoplab/helpdesk/
    ├── retrieval/application/usecase/ (IndexDocumentsUseCaseTests, ReindexDocumentsUseCaseTests, SearchChunksUseCaseTests)
    ├── retrieval/infrastructure/out/embedding/SpringAiEmbeddingAdapterTests
    ├── retrieval/infrastructure/out/persistence/JdbcEmbeddingStoreAdapterTests   # búsqueda, filtro por modelo, concurrencia
    ├── retrieval/EmbeddingFlowIntegrationTests   # documento → ingest (WireMock) → chunks → embeddings → búsqueda
    ├── retrieval/support/DeterministicEmbeddingPort   # doble de CI, vectores de 1 024 valores desde el texto
    └── schema/ (RetrievalSchemaIntegrationTests actualizada a V4; JdbcKnowledgeBaseAdapterTests y flujo de LAB-20: documento PENDING)

docs/adr/0017-embeddings-solo-en-retrieval.md   # NUEVO; + docs/modulos regenerado
```

**Structure Decision**: se reutilizan las capas `domain`, `application`, `infrastructure` y `config` de `retrieval`
(ADR-0015). El estado del documento se guarda en `retrieval.document` (columna nueva) en vez de una tabla aparte: es un
atributo del documento y evita un join. `IngestDocumentUseCase` solo cambia para publicar un evento en proceso; no conoce
embeddings.

## Orden de implementación recomendado

1. `V4` (estado del documento) y ajuste de `JdbcKnowledgeBaseAdapter` (reingesta deja el documento `PENDING`), con las
   pruebas de esquema y de LAB-20 actualizadas.
2. Dominio, puertos y errores (`ResponseCodeEnum`), con el doble determinista de `EmbeddingPort`.
3. `IndexDocumentsUseCase` + `JdbcEmbeddingStoreAdapter` (lectura de pendientes, guardado atómico con comprobación
   optimista, `markFailed`).
4. `SpringAiEmbeddingAdapter` (lotes, validación de dimensión, traducción de errores).
5. `SearchChunksUseCase` y consulta SQL por modelo y `embedding IS NOT NULL`.
6. `ReindexDocumentsUseCase` (ámbitos `PENDING`, `OTHER_MODEL`, `ALL`) y resumen.
7. Disparo: evento tras la ingesta y barrido/reindexado al arrancar; métricas.
8. `EmbeddingFlowIntegrationTests`, ADR-0017, documentación y `docs/modulos` regenerado.
9. Comprobar en `shoplab-infra` que el backend recibe el modelo de embeddings (ver research D-9).

## Complexity Tracking

Sin desviaciones de la constitución que justificar.
