# Implementation Plan: Citas a la fuente en cada respuesta

**Branch**: `feature/LAB-23-source-citations` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-23-source-citations/spec.md`

## Summary

`chat` numera los fragmentos que entrega al modelo (`[1]…[k]`, tras la selección de LAB-22), le pide en el prompt que cite
`[n]` y emite, **antes del primer token**, un evento SSE nuevo `sources` con `{number, title, section, text}` de cada
fragmento. Los eventos `token`, `done` y `error` no cambian: el texto de la respuesta sigue siendo el que genera el
modelo, con las marcas `[n]` dentro. `retrieval` solo añade `title` a su `KnowledgeChunk` (la columna `document.title` ya
existe; sin migración). En Angular, el almacén guarda las fuentes en el mensaje del asistente; `MarkdownRenderer` convierte
las marcas válidas en botones accesibles (las inválidas quedan como texto); una lista de fuentes bajo la respuesta y un
diálogo de Angular Material abren el fragmento. Un `CitationTracker` puro en el caso de uso cuenta las citas válidas,
inválidas o ausentes para una métrica, sin tocar el flujo.

Decisiones y alternativas en [research.md](research.md); contrato en [contracts/sources-event.md](contracts/sources-event.md).

## Technical Context

**Language/Version**: Java 21 (backend); TypeScript con Angular 22 (frontend)

**Primary Dependencies**: Spring Boot 4.1.2 (WebFlux), Spring AI 2.0.2, Spring Modulith 2.1.2, Micrometer (sin
dependencias nuevas); Angular 22, Angular Material/CDK 22 (`MatDialog`), `marked` 18, `dompurify` 3 (sin dependencias
nuevas)

**Storage**: N/A. `retrieval.document.title` ya existe (V2); no hay migraciones. Las fuentes viajan con la respuesta y no
se persisten

**Testing**: backend: JUnit 5 + Mockito + `StepVerifier`, WireMock (Ollama), Testcontainers `@PostgresTest`, ArchUnit /
Modulith / BlockHound existentes; frontend: Vitest (`ng test`) con pruebas de componentes, del almacén, del cliente SSE y
del renderizador

**Target Platform**: contenedores Linux; local con `docker compose`; `dev` por GitOps

**Project Type**: web-service + SPA (módulos `chat` y `retrieval`, `frontend/`)

**Performance Goals**: primer token < 5 s (SC-005, sin cambio respecto a LAB-22); abrir un fragmento < 1 s sin llamadas
(SC-004); el evento `sources` pesa como máximo el presupuesto de contexto (8 000 caracteres) más cabeceras

**Constraints**: `chat` solo ve `retrieval.api`; `application` sin Spring AI; contrato SSE solo aditivo; sin texto de
preguntas, respuestas ni fragmentos en logs ni métricas; HTML siempre saneado (ADR-0014); líneas ≤ 121 caracteres

**Scale/Scope**: top-k ≤ 20 (por defecto 4) → hasta 20 fuentes por respuesta; ~25 documentos en `data/kb`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | Cumple: `chat` sigue accediendo a `retrieval` solo por `retrieval.api`; el cambio en la API es un campo nuevo (`title`) en un registro público; sin tablas compartidas. |
| II. Hexagonal | Cumple: numeración y `CitationTracker` en `application` (lógica pura, sin Spring AI); el formato del prompt (`[n] título › sección`) en `infrastructure/out/ollama`; el evento SSE se mapea en `infrastructure/in/rest`. |
| III. Inglés y errores tipados | Cumple: identificadores en inglés, texto de interfaz en español. No se añade ningún error nuevo: no hay fallo nuevo que traducir (una cita inválida no es un error, es una métrica). |
| IV. Pruebas con cobertura | Cumple: pruebas con la implementación en backend y frontend; integración con pgvector real y Ollama simulado; ≥ 80 % en módulos tocados. |
| V. RAG con evaluación | **Cumple la parte de citas** que LAB-22 dejó como desviación (las respuestas ancladas llevan citas a sus fragmentos). La evaluación automática (fidelidad, precisión de contexto) sigue siendo de F2; la métrica de calidad de citas (FR-015) deja la señal preparada. |
| VI. Observabilidad y datos seguros | Cumple: contador `shoplab.chat.citations` por resultado y log con `requestId` y recuentos, nunca texto; el texto del fragmento solo viaja al cliente. |
| VII. Simplicidad y ADR | Cumple con **ADR-0019** (evento `sources` aditivo; validación de citas en el cliente, medida en el servidor). Sin reescritura del flujo, sin persistencia, sin endpoint nuevo. |

**Gate**: PASA sin desviaciones.

**Re-evaluación tras el diseño**: PASA. El diseño añade un evento al contrato, un campo a la API de `retrieval`, una clase
pura y tres piezas de interfaz (lista, diálogo, extensión del renderizador); no toca esquema, infraestructura ni
dependencias.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-23-source-citations/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── sources-event.md    # evento SSE `sources`, gramática de citas, métrica, forma del prompt, configuración
├── checklists/requirements.md
└── tasks.md                # lo genera /speckit-tasks
```

### Source Code (repository root)

```text
backend/src/main/java/com/shoplab/helpdesk/
├── retrieval/
│   ├── api/KnowledgeChunk.java                          # + title (campo aditivo)
│   ├── domain/model/SearchResult.java                   # + title
│   ├── infrastructure/in/api/KnowledgeChunkMapper.java  # mapea title
│   └── infrastructure/out/persistence/JdbcEmbeddingStoreAdapter.java   # SEARCH: + d.title
├── chat/
│   ├── application/
│   │   ├── port/out/ChatMetricsPort.java                # + recordCitations(CitationOutcome)
│   │   └── usecase/
│   │       ├── StreamChatUseCase.java                   # emite SourcesEvent antes del primer token; usa CitationTracker
│   │       └── CitationTracker.java                     # nuevo: puro, acumula texto y clasifica las citas
│   ├── domain/model/
│   │   ├── ChatEvent.java                               # + SourcesEvent(List<AnswerSource>)
│   │   ├── AnswerSource.java                            # nuevo: number, title, section, text
│   │   ├── ContextFragment.java                         # + title
│   │   ├── enums/CitationOutcome.java                   # nuevo: VALID, INVALID, NONE
│   │   └── constants/ChatConstants.java                 # + SOURCES_EVENT, métrica, prompt con regla de citas
│   ├── infrastructure/
│   │   ├── in/rest/dto/ChatEventPayload.java            # + Sources, Source
│   │   ├── in/rest/util/mapper/ChatEventMapper.java     # + SourcesEvent
│   │   ├── out/knowledge/ContextFragmentMapper.java     # título con respaldo derivado del nombre del archivo
│   │   ├── out/ollama/OllamaChatAdapter.java            # cabecera de fragmento `[n] título › sección`
│   │   └── out/metrics/MicrometerChatMetrics.java       # + shoplab.chat.citations
backend/src/main/resources/application.yml               # sin claves nuevas (el prompt y las plantillas siguen en ChatConstants)

backend/src/test/java/com/shoplab/helpdesk/
├── chat/application/usecase/{CitationTrackerTests, StreamChatUseCaseTests}.java
├── chat/infrastructure/in/rest/{ChatControllerTests}.java + mapper
├── chat/infrastructure/out/{knowledge, ollama, metrics}/…Tests.java
├── chat/{ChatStreamContractTests, RagAnsweringIntegrationTests, ChatCitationsIntegrationTests}.java
└── retrieval/EmbeddingFlowIntegrationTests.java         # título en los resultados

frontend/src/app/
├── core/markdown/markdown-renderer.ts                   # extensión de citas; render(markdown, options)
├── features/chat/
│   ├── chat-message.ts                                  # + ChatSource, evento `sources`, ChatMessage.sources
│   ├── chat-stream.client.ts                            # parsea `sources`
│   ├── chat-store.ts                                    # guarda las fuentes; abre/cierra el fragmento
│   ├── citations.ts                                     # nuevo: gramática de citas y citedNumbers() (puro)
│   ├── message-bubble/message-bubble.{ts,html,scss}     # delega el clic de las citas; muestra la lista
│   ├── source-list/source-list.{ts,html,scss}           # nuevo: «Fuentes» / «Fuentes consultadas»
│   └── source-preview/source-preview.{ts,html,scss}     # nuevo: contenido del MatDialog con el fragmento
└── (pruebas *.spec.ts junto a cada archivo)

docs/adr/0019-citas-a-la-fuente-en-el-chat.md            # nuevo (+ fila en docs/adr/README.md)
docs/{chat-streaming.md, modulos.md}                     # actualizar (evento `sources`, API de retrieval)
```

**Structure Decision**: cambios en `backend/` (módulos `chat` y `retrieval`), `frontend/` (pantalla de chat y renderizador
Markdown) y `docs/`. Sin cambios en `ingest/`, `infra/` ni migraciones. Backend con sus capas `api / domain / application /
infrastructure / config`; frontend con la estructura por funcionalidad (`features/chat`) y utilidades en `core/`.

## Complexity Tracking

| Elemento | Por qué es necesario | Alternativa más simple descartada porque |
|---|---|---|
| Evento SSE nuevo `sources` | El cliente necesita número, título, sección y texto antes de la primera cita y sin segunda llamada (FR-003, FR-004) | Meterlo en `done` llega tarde (las citas del streaming no se podrían abrir); un endpoint aparte exige guardar el contexto por petición (estado en servidor) |
| Validar citas en el cliente y medirlas en el servidor | El cliente decide qué se muestra (FR-006/007) y el servidor mide la calidad (FR-015) sin reescribir el texto | Reescribir el flujo en el servidor obliga a retener tokens (retrasa el primer token) y rompe la fidelidad del contrato `token` |
| Extensión de `marked` + lista blanca ampliada (`button`, `data-source`, `aria-label`) | Las citas deben ser botones accesibles dentro del Markdown saneado | Post-procesar el DOM tras `innerHTML` rompe en cada re-render del streaming y desacopla el saneado |
