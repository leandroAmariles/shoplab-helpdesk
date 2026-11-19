# Implementation Plan: Memoria de la conversación

**Branch**: `feature/LAB-24-conversation-memory` | **Date**: 2026-11-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-24-conversation-memory/spec.md`

## Summary

`POST /api/chat` acepta un `conversationId` opcional (UUID generado por la pantalla). Con él, `StreamChatUseCase`
(1) lee los últimos turnos de la conversación, (2) los recorta a un límite de turnos y de caracteres, (3) construye la
consulta de búsqueda como «pregunta anterior + pregunta actual» para que un seguimiento recupere los documentos del
tema, (4) entrega el historial al modelo como mensajes `user`/`assistant` entre el prompt de sistema y el mensaje con
contexto, y (5) **antes de emitir `done`** guarda el turno completo (pregunta + respuesta) en una tabla propia del
esquema `chat`. Una respuesta fallida o cancelada no guarda nada. La memoria es un puerto de salida
(`ConversationMemoryPort`) con un adaptador JDBC que corre en `Schedulers.boundedElastic()` mediante `BlockingCalls`
(ADR-0011). En Angular, el almacén genera el UUID, lo envía en cada mensaje y ofrece «Nueva conversación».

Decisiones y alternativas en [research.md](research.md); contrato en [contracts/chat-request.md](contracts/chat-request.md).

## Technical Context

**Language/Version**: Java 21 (backend); TypeScript con Angular 22 (frontend)

**Primary Dependencies**: Spring Boot 4.1.2 (WebFlux + `spring-boot-starter-jdbc`), Spring AI 2.0.2, Spring Modulith
2.1.2, Micrometer, Flyway (todo ya presente; **sin dependencias nuevas**); Angular 22, Angular Material 22

**Storage**: PostgreSQL 16, esquema `chat` (propio del módulo): tabla nueva `chat.conversation_turn` en
`db/migration/chat/V2__conversation_turns.sql`. Sin cambios en otros esquemas

**Testing**: backend: JUnit 5 + Mockito + `StepVerifier`, WireMock (Ollama), Testcontainers `@PostgresTest`, ArchUnit,
Modulith y BlockHound existentes; frontend: Vitest (`ng test`)

**Target Platform**: contenedores Linux; local con `docker compose`; `dev` por GitOps

**Project Type**: web-service + SPA (módulo `chat`, `frontend/`)

**Performance Goals**: primer token < 5 s con historial al máximo (SC-005); lectura del historial < 50 ms con el índice
`(conversation_id, id DESC)`; el guardado antes de `done` añade una escritura simple al final del flujo

**Constraints**: `application` sin Spring AI ni JDBC; JDBC solo en `infrastructure` vía `BlockingCalls`; contrato solo
aditivo; sin texto de mensajes ni `conversationId` en métricas ni logs de texto; líneas ≤ 121; `chat` no toca tablas de
otros módulos

**Scale/Scope**: desarrollo y datos sintéticos; ≤ 5 turnos y ≤ 4 000 caracteres de historial por petición (por defecto);
sin caducidad (fuera de alcance)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | Cumple: la tabla vive en el esquema `chat` y solo `chat` la lee y escribe (ADR-0015); no se añade ninguna dependencia entre módulos. |
| II. Hexagonal | Cumple: `ConversationMemoryPort` y `HistoryWindow`/`FollowUpQuery` (lógica pura) en `application`; el adaptador JDBC en `infrastructure/out/persistence`; el adaptador de Ollama recibe el historial como mensajes. El controlador solo copia el campo al comando. |
| III. Inglés y errores tipados | Cumple: dos códigos nuevos en `ResponseCodeEnum` (`INVALID_CONVERSATION_ID`, `CHAT_MEMORY_UNAVAILABLE`) lanzados como excepciones tipadas y mapeados solo en `GlobalExceptionHandler`. |
| IV. Pruebas con cobertura | Cumple: pruebas unitarias, de integración con PostgreSQL real y Ollama simulado, BlockHound y frontend; ≥ 80 % en módulos tocados. |
| V. RAG con evaluación | Cumple sin desviación nueva: no cambia el recuperador ni el prompt de citas; la consulta de búsqueda compuesta es un cambio de recuperación **sin evaluación automática** (F2 aún no existe), igual que la desviación vigente de LAB-22. Se deja una métrica de la longitud del historial y la configuración `retrieval-turns` para calibrarla en F2. |
| VI. Observabilidad y datos seguros | Cumple: métricas sin `conversationId` como etiqueta; logs con `requestId` y recuentos, nunca texto. El texto de los mensajes solo está en la tabla del módulo. |
| VII. Simplicidad y ADR | Cumple con **ADR-0020** (memoria de conversación en tabla propia del módulo, no `JdbcChatMemoryRepository` de Spring AI). Se evita el resumen con el modelo y la reformulación con una segunda llamada. |

**Gate**: PASA. Hay una desviación del texto del criterio de aceptación («`ChatMemory`»), justificada en
Complexity Tracking y en el ADR-0020; **confirmada por el responsable de la historia el 2026-11-07**.

**Re-evaluación tras el diseño**: PASA. El diseño añade una tabla, un puerto, dos clases puras, dos códigos de error y
un campo opcional al contrato; sin dependencias ni cambios de infraestructura.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-24-conversation-memory/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── chat-request.md      # campo `conversationId`, errores, orden de eventos y de guardado, configuración, métricas
├── checklists/requirements.md
└── tasks.md                 # lo genera /speckit-tasks
```

### Source Code (repository root)

```text
backend/src/main/java/com/shoplab/helpdesk/
├── shared/exception/ResponseCodeEnum.java                  # + INVALID_CONVERSATION_ID, CHAT_MEMORY_UNAVAILABLE
├── chat/
│   ├── application/
│   │   ├── port/out/
│   │   │   ├── ConversationMemoryPort.java                 # nuevo: recent(id, turns), saveTurn(id, question, answer)
│   │   │   ├── ChatModelPort.java                          # + history en streamAnswer
│   │   │   └── ChatMetricsPort.java                        # + recordHistory(...), recordMemoryFailure(...)
│   │   └── usecase/
│   │       ├── StreamChatUseCase.java                      # lee historial, consulta compuesta, guarda antes de `done`
│   │       ├── HistoryWindow.java                          # nuevo: puro; devuelve HistorySelection(turns, trimmed); quita [n]
│   │       └── FollowUpQuery.java                          # nuevo: puro; compone la consulta (tope 2000 caracteres)
│   ├── domain/
│   │   ├── exception/ConversationMemoryException.java      # nuevo (fallo de lectura/escritura del historial)
│   │   └── model/
│   │       ├── ConversationId.java                         # nuevo: UUID canónico; optional(String) lanza el error tipado
│   │       ├── HistorySelection.java                       # nuevo: turns + trimmed
│   │       ├── ConversationTurn.java                       # nuevo: question, answer
│   │       ├── StreamChatCommand.java                      # + conversationId (String, opcional)
│   │       ├── enums/MemoryOperation.java                  # nuevo: READ, WRITE
│   │       └── constants/ChatConstants.java                # + frase del prompt de sistema sobre el historial
│   ├── config/
│   │   ├── MemoryProperties.java                           # nuevo: shoplab.chat.memory.*
│   │   └── ChatConfig.java                                 # + MemoryProperties
│   └── infrastructure/
│       ├── in/rest/dto/ChatRequest.java                    # + conversationId
│       ├── in/rest/ChatController.java                     # copia conversationId al comando
│       ├── out/persistence/JdbcConversationMemoryAdapter.java   # nuevo: JDBC en BlockingCalls
│       ├── out/ollama/OllamaChatAdapter.java               # historial como mensajes user/assistant
│       └── out/metrics/MicrometerChatMetrics.java          # + shoplab.chat.history, shoplab.chat.memory.failures
backend/src/main/resources/
├── db/migration/chat/V2__conversation_turns.sql            # nuevo
└── application.yml                                         # + shoplab.chat.memory.*

backend/src/test/java/com/shoplab/helpdesk/
├── chat/application/usecase/{HistoryWindowTests, FollowUpQueryTests, StreamChatUseCaseTests}.java
├── chat/domain/model/ConversationIdTests.java
├── chat/infrastructure/out/{persistence/JdbcConversationMemoryAdapterTests, ollama, metrics}/…Tests.java
├── chat/config/MemoryPropertiesTests.java
├── chat/{ChatMemoryIntegrationTests, ChatStreamContractTests}.java   # seguimiento, aislamiento, reinicio, BlockHound
└── schema/…                                                # la tabla nueva en las pruebas de esquema existentes

frontend/src/app/features/chat/
├── chat-store.ts                                           # conversationId (UUID); newConversation()
├── chat-stream.client.ts                                   # envía conversationId en el cuerpo
├── chat.{ts,html,scss}                                     # botón «Nueva conversación»
└── (pruebas *.spec.ts junto a cada archivo)

docs/adr/0020-memoria-de-conversacion-en-tabla-propia.md    # nuevo (+ fila en docs/adr/README.md)
docs/{chat-streaming.md, modulos.md}                        # actualizar (conversationId, tabla del esquema chat)
```

**Structure Decision**: cambios en `backend/` (módulo `chat`), `frontend/` (pantalla de chat) y `docs/`. Sin cambios en
`retrieval`, `ingest/`, `infra/`. Backend con sus capas `domain / application / infrastructure / config`.

## Complexity Tracking

| Elemento | Por qué es necesario | Alternativa más simple descartada porque |
|---|---|---|
| Puerto + adaptador JDBC propios en lugar de `JdbcChatMemoryRepository` de Spring AI (**desvío del texto del criterio**) | El repositorio de Spring AI crea una tabla fuera del esquema del módulo (rompe ADR-0015), reescribe la conversación completa en cada guardado, no es transaccional por turno y obliga a que `application` conozca Spring AI | Usarlo tal cual: añadiría una dependencia, una tabla ajena al esquema `chat` y un `saveAll` que borra e inserta toda la conversación en cada turno |
| Una fila por turno (`question` + `answer`) | Guardar el turno completo en una sola inserción garantiza que nunca haya una pregunta sin respuesta ni turnos entrelazados con peticiones simultáneas | Una fila por mensaje con rol: exige dos inserciones y un orden compartido |
| Guardar **antes** de emitir `done` | El siguiente mensaje del cliente solo se envía tras `done`; así el seguimiento siempre ve el turno anterior sin esperas ni reintentos | Guardar tras `done` o en segundo plano: el seguimiento rápido podría leer el historial sin el último turno |
| Consulta de búsqueda = pregunta anterior + actual | Cubre FR-006 sin una segunda llamada al modelo (latencia, SC-005; determinismo en CI) | Reformular con el modelo: añade segundos al primer token y es no determinista |
