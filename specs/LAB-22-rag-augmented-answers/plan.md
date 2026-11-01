# Implementation Plan: Respuestas aumentadas con los documentos (RAG)

**Branch**: `feature/LAB-22-rag-augmented-answers` | **Date**: 2026-11-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-22-rag-augmented-answers/spec.md`

## Summary

Antes de generar cada respuesta, `chat` recupera de `retrieval` los fragmentos más cercanos a la pregunta, los filtra
(umbral, duplicados, presupuesto de caracteres) y los entrega al modelo como contexto, con un prompt de sistema que le
obliga a responder solo con ese contexto y a reconocer cuando no lo tiene. `retrieval` publica la búsqueda en su `api`
(`KnowledgeSearchApi`); `chat` la consume tras un puerto de salida nuevo (`KnowledgeContextPort`). El aumento del prompt
usa `ContextualQueryAugmenter` de `spring-ai-rag` dentro de `OllamaChatAdapter`, que sigue transmitiendo con
`ChatModel.stream` (no `ChatClient` + advisor: ADR-0011 prohíbe el hilo por flujo y la búsqueda es reactiva). Top-k,
umbral, presupuesto, prompt y plantillas son configuración. Un fallo de la recuperación es un error tipado nuevo
(`SLHD_CHAT_00004`, 503). Sin cambios en el contrato SSE de `POST /api/chat`, en el esquema ni en `ingest`.

Decisiones y alternativas en [research.md](research.md).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1.2 (WebFlux), Spring AI 2.0.2 (`spring-ai-starter-model-ollama` existente) y
**una dependencia nueva**, `spring-ai-rag` (gestionada por el BOM de Spring AI 2.0.2; no está en el repositorio Maven
local, se descarga con red), Spring Modulith 2.1.2, Micrometer

**Storage**: N/A para `chat` (no persiste nada). `retrieval` solo se lee, sin migraciones nuevas (PostgreSQL 16 + pgvector,
esquema `retrieval`)

**Testing**: JUnit 5 + Mockito + Reactor `StepVerifier` (casos de uso y adaptadores), WireMock (Ollama), Testcontainers
`@PostgresTest` + `DeterministicEmbeddingPort` (flujo de extremo a extremo), ArchUnit / Spring Modulith / BlockHound
existentes

**Target Platform**: contenedores Linux; local con `docker compose` (Ollama incluido); dev por GitOps (`shoplab-infra`)

**Project Type**: web-service (módulos `chat` y `retrieval` del monolito modular)

**Performance Goals**: primer token < 5 s con el modelo cargado, incluida la recuperación (SC-004); recuperación < 1 s

**Constraints**: ninguna llamada bloqueante en el event loop (la búsqueda de LAB-21 ya es reactiva); `chat` solo ve
`retrieval.api` (D5); `application` sin Spring AI; líneas ≤ 121 caracteres; sin texto de preguntas ni fragmentos en logs ni
métricas; contrato SSE sin cambios

**Scale/Scope**: ~25 documentos / unos cientos de chunks (LAB-18); top-k 4 por defecto, máx. 20; contexto ≤ 8 000
caracteres

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | Cumple: `chat` accede a `retrieval` solo por su `api` publicada; ninguna tabla compartida; la costura sirve para extraer en F5. |
| II. Hexagonal | Cumple: puerto de salida `KnowledgeContextPort` y `ChatModelPort` ampliado; Spring AI (`ContextualQueryAugmenter`, `ChatModel`) solo en `infrastructure`; selección del contexto en lógica pura de `application`. |
| III. Inglés y errores tipados | Cumple: código `SLHD_CHAT_00004` en `ResponseCodeEnum`, excepción de dominio `KnowledgeRetrievalException`, mapeo solo en `GlobalExceptionHandler`/caso de uso existente. |
| IV. Pruebas con cobertura | Cumple: pruebas con la implementación, integración con pgvector real y Ollama simulado, ≥ 80 %. |
| V. RAG con evaluación | **Desviación acotada**: las citas son obligatorias para respuestas ancladas en la base y llegan en la historia siguiente de F1; esta historia se despliega solo en `dev` y no es publicable sin ellas (declarado en el spec). La evaluación automática empieza en F2; hasta entonces SC-001/SC-002 son manuales. Se conservan los metadatos de origen para las citas. |
| VI. Observabilidad y datos seguros | Cumple: temporizador y resumen de fragmentos de la recuperación, logs con `requestId` y recuentos, nunca texto. |
| VII. Simplicidad y ADR | Cumple con **ADR-0018** (recuperación en el caso de uso + `ContextualQueryAugmenter`, desviación del advisor literal). Sin reescritura de consulta, memoria ni modo de mensaje fijo (YAGNI). |

**Gate**: PASA con una desviación declarada (citas, principio V), ya prevista por el calendario de F1 y justificada en
Complexity Tracking.

**Re-evaluación tras el diseño**: PASA. El diseño añade un puerto, un selector puro, una interfaz pública de `retrieval` y una
dependencia; no toca esquema ni infraestructura ni el contrato HTTP.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-22-rag-augmented-answers/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── rag-answers.md      # api de retrieval, puertos de chat, configuración, error, métricas, forma del prompt
├── checklists/requirements.md
└── tasks.md                # lo genera /speckit-tasks
```

### Source Code (repository root)

```text
backend/src/main/java/com/shoplab/helpdesk/
├── retrieval/
│   ├── api/
│   │   ├── KnowledgeSearchApi.java                      # nuevo: búsqueda publicada
│   │   └── KnowledgeChunk.java                          # nuevo: registro público
│   └── infrastructure/in/api/
│       └── KnowledgeSearchApiAdapter.java               # nuevo: delega en SearchChunksServicePort
├── chat/
│   ├── application/
│   │   ├── port/out/KnowledgeContextPort.java           # nuevo
│   │   ├── port/out/ChatModelPort.java                  # cambia: streamAnswer(message, context)
│   │   ├── port/out/ChatMetricsPort.java                # + recordContext(...)
│   │   └── usecase/{StreamChatUseCase, ContextSelector}.java   # el segundo es nuevo
│   ├── config/{ChatProperties, RagProperties, ChatConfig}.java # RagProperties nuevo; prompt fundamentado
│   ├── domain/
│   │   ├── exception/KnowledgeRetrievalException.java   # nuevo
│   │   └── model/{ContextFragment, ContextOutcome}.java # nuevos (ContextOutcome en enums/)
│   └── infrastructure/out/
│       ├── knowledge/KnowledgeSearchAdapter.java        # nuevo: usa retrieval.api
│       ├── ollama/OllamaChatAdapter.java                # cambia: ContextualQueryAugmenter
│       └── metrics/MicrometerChatMetrics.java           # + métricas de contexto
├── shared/exception/ResponseCodeEnum.java               # + CHAT_CONTEXT_UNAVAILABLE
backend/src/main/resources/application.yml               # + shoplab.chat.rag.*, system-prompt, num-ctx
backend/pom.xml                                          # + spring-ai-rag

backend/src/test/java/com/shoplab/helpdesk/
├── chat/application/usecase/{StreamChatUseCaseTests, ContextSelectorTests}.java
├── chat/config/RagPropertiesTests.java
├── chat/infrastructure/out/{knowledge, ollama, metrics}/…Tests.java
├── chat/RagAnsweringIntegrationTests.java               # nuevo: documento → chat → prompt a Ollama
├── retrieval/infrastructure/in/api/KnowledgeSearchApiAdapterTests.java
└── (ajustes) chat/*IntegrationTests, shared/exception/ResponseCodeEnumTests

docs/adr/0018-recuperacion-de-contexto-en-chat.md        # nuevo
docs/{chat-streaming.md, modulos.md, base-conocimiento.md}   # actualizar
```

**Structure Decision**: cambios solo en `backend/` (módulos `chat` y `retrieval`) y `docs/`. No hay cambios en `frontend/`,
`ingest/`, `infra/` ni en migraciones. Cada módulo mantiene sus capas `api / domain / application / infrastructure /
config`.

## Complexity Tracking

| Violación | Por qué es necesaria | Alternativa más simple descartada porque |
|---|---|---|
| Principio V: respuestas fundamentadas sin citas | Las citas son la historia siguiente de F1; el criterio de LAB-22 no las incluye | Añadir citas aquí mezcla dos historias y retrasa el valor principal; se mitiga con despliegue solo en `dev` y metadatos de origen conservados |
| No usar el `RetrievalAugmentationAdvisor` literal | Exige `ChatClient` (hilo por flujo, ADR-0011) y una búsqueda bloqueante | Usar el advisor obligaría a `block()` en el event loop o a perder la búsqueda por modelo de LAB-21 |
| Dependencia nueva `spring-ai-rag` | Aporta el aumentador de contexto probado del advisor | Concatenar el contexto a mano pierde la plantilla y el tratamiento de contexto vacío |
