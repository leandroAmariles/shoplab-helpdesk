# Implementation Plan: Feedback 👍/👎 en cada respuesta

**Branch**: `feature/LAB-33-answer-feedback` | **Date**: 2026-11-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-33-answer-feedback/spec.md`

## Summary

Permitir que el cliente valore cada respuesta completada del asistente en la pantalla de chat con 👍 (`UP`) o 👎 (`DOWN`) y un comentario opcional, guardar la valoración en `chat.feedback` dentro del esquema del módulo `chat` y permitir que el administrador revise las respuestas con 👎 en `admin-eval` y las promueva a casos golden validados (obteniendo la línea JSONL lista para incorporar a `eval/golden.jsonl`).

Enfoque técnico (detalle y alternativas en [research.md](research.md)):

- **Identificación segura del turno en el evento SSE `done` (`turnId`)**: `chat.conversation_turn` (migración `V3__answer_feedback.sql`) gana un identificador público `turn_id` (`uuid`, único), la lista de documentos entregados en ese turno (`delivered_documents text[]`) y el identificador de traza (`trace_id`). Cuando el turno se guarda antes de emitir `done` (ADR-0020), `done` incluye `"turnId": "..."` de forma aditiva. Si la petición no llevaba `conversationId`, `turnId` es `null` y `@JsonInclude(NON_NULL)` lo omite, manteniendo intacto byte a byte `ChatStreamContractTests` (`golden-stream.txt`).
- **Tabla `chat.feedback` en el esquema `chat` (migración `V3`)**: relación `1:1` (`turn_ref bigint UNIQUE REFERENCES conversation_turn(id)`) dentro del mismo esquema `chat` (ADR-0015), con `rating` (`UP` / `DOWN`), `comment` opcional (≤ 500 caracteres), marcas de tiempo y datos de promoción (`promoted_case_id` `^G[0-9]{3}$`, `promoted_case_jsonl` y `promoted_at`). Re-votar o añadir comentario actualiza la misma fila (`ON CONFLICT (turn_ref) DO UPDATE`), siempre que aún no haya sido promovida.
- **API de feedback y promoción en el módulo `chat` (`/api/chat/feedback`)**:
  - `PUT /api/chat/feedback/{turnId}`: registra o actualiza la valoración y el comentario del turno.
  - `GET /api/chat/feedback?rating=DOWN&promoted=...&limit=...`: lista las valoraciones con el contenido del turno y el siguiente id `G<NNN>` sugerido.
  - `POST /api/chat/feedback/{turnId}/promote`: valida el caso golden propuesto con `GoldenCaseValidator` (mismas reglas por caso de LAB-27: `G-ID`, `G-CATEGORY`, `G-QUESTION-LENGTH`, `G-ANSWER-LENGTH`, `G-DOC-COUNT`, `G-CATEGORY-TOPIC`, `G-NOANSWER-DIGITS`, `G-PII`), guarda el estado promovido y la línea JSONL en `chat.feedback` y la devuelve a `admin-eval` para copiarla o descargarla.
- **Frontend Angular**:
  - En `/chat` (`message-bubble`, `chat-store`, `chat-feedback.client`): botones 👍 / 👎 con `aria-pressed`, envío inmediato en un clic y campo desplegable para añadir o editar el comentario opcional.
  - En `/admin-eval` (`features/eval/feedback-promotion/`): listado de valoraciones con 👎 (filtro pendientes/todas), formulario de promoción precargado con pregunta, documentos entregados e id `G<NNN>` sugerido, validación con mensajes claros y copia/descarga de la línea JSONL para `eval/golden.jsonl`.
- **Margen de crecimiento en `eval/`**: se amplía `GoldenRules.max_cases` de `100` a `150` en `eval/src/shoplab_eval/constants.py` para que los casos promovidos desde `chat.feedback` puedan añadirse a `eval/golden.jsonl` (que hoy tiene 96 casos de conocimiento) sin que `validate_golden` falle al pasar de 100.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 4.1.2, Spring AI 2.0.2, Spring Modulith 2.1.2) en `backend/`; TypeScript 6 / Angular 22 con Angular Material 22 en `frontend/`; Python ≥ 3.12 en `eval/`.

**Primary Dependencies**: Spring WebFlux, `JdbcTemplate` aislado con `BlockingCalls` (`Schedulers.boundedElastic()`, ADR-0011), Flyway por módulo (`ModuleSchemaMigrator`), Micrometer, Jackson; Angular Material, `HttpClient` y Signals en frontend. **Sin dependencias nuevas**.

**Storage**: PostgreSQL 16, esquema `chat` (`db/migration/chat/V3__answer_feedback.sql`): ampliación de `chat.conversation_turn` y creación de `chat.feedback` con FK interna `turn_ref -> conversation_turn(id)`. Sin claves foráneas entre esquemas.

**Testing**: JUnit 5 + Mockito + `StepVerifier`, `WebTestClient`, Testcontainers PostgreSQL (`@PostgresTest`), ArchUnit, Modulith y BlockHound en `backend/`; Vitest + jsdom (`npm test -- --watch=false`) en `frontend/`; `pytest` + `ruff` en `eval/`.

**Target Platform**: contenedores Linux / entorno de desarrollo local (`docker compose` y clúster `dev`).

**Project Type**: monolito modular (backend) + SPA Angular (frontend) + validador Python (`eval/`).

**Performance Goals**: registrar/actualizar feedback en < 200 ms en local (confirmación en pantalla < 1 s, SC-001); listar hasta 100 feedbacks con 👎 en < 500 ms usando el índice `(rating, created_at DESC, id DESC)`.

**Constraints**: dominio puro en `chat/domain` (sin Spring AI, Reactor ni JDBC, verificado por `ModuleArchitectureTests`); JDBC solo en `infrastructure/out/persistence` vía `BlockingCalls`; contrato SSE solo aditivo y idéntico byte a byte cuando no hay `conversationId`; cero textos de preguntas, respuestas o comentarios en logs o etiquetas de métricas (Constitución VI); cobertura de líneas ≥ 80 % en backend; Checkstyle, PMD, ESLint, Prettier y `ruff` en verde; máximo 121 caracteres por línea.

**Scale/Scope**: una fila por turno valorado en `chat.feedback`; listado acotado por `limit` (por defecto 100, máximo 500).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | ✅ `chat.feedback` vive en el esquema `chat` y solo el módulo `chat` la lee y escribe. La única FK (`feedback.turn_ref -> conversation_turn.id`) es interna al esquema `chat`. `ModularityTests` y `ModuleSchemasIntegrationTests` siguen en verde. |
| II. Hexagonal y pureza de dominio | ✅ Modelos (`Feedback`, `FeedbackItem`, `GoldenCaseDraft`), constantes (`FeedbackConstants`), enums (`FeedbackRating`) y excepciones en `chat/domain`; puertos (`SubmitFeedbackServicePort`, `ListFeedbackServicePort`, `PromoteFeedbackServicePort`, `FeedbackStorePort`), validador puro (`GoldenCaseValidator`) y casos de uso en `chat/application`; controlador REST (`FeedbackController`) y adaptador JDBC (`JdbcFeedbackAdapter`) en `chat/infrastructure`. |
| III. Identificadores en inglés, errores tipados | ✅ Todo el código en inglés; textos de interfaz y documentación en español. Cinco códigos nuevos `SLHD_CHAT_00011`..`SLHD_CHAT_00015` en `ResponseCodeEnum`, lanzados como excepciones tipadas y traducidos únicamente en `GlobalExceptionHandler`. |
| IV. Test-First con cobertura ≥ 80 % | ✅ Pruebas unitarias de `GoldenCaseValidator` y casos de uso, pruebas de controlador con `WebTestClient`, pruebas de integración con PostgreSQL real (`@PostgresTest`) para `JdbcFeedbackAdapter` y migración `V3`, pruebas Vitest en frontend y `pytest` en `eval/`. |
| V. Evaluation-Driven RAG | ✅ No modifica prompts, chunking, embeddings ni recuperación; cierra el bucle entre las conversaciones reales y `eval/golden.jsonl` exigiendo validación de LAB-27 y respuesta de referencia redactada por una persona antes de promover. |
| VI. Observabilidad y manejo seguro | ✅ Métricas `shoplab.chat.feedback` (etiqueta fija `rating=up\|down`) y `shoplab.chat.feedback.promoted`; logs con `requestId`, `turnId`, `rating` y `caseId`, nunca texto de pregunta, respuesta ni comentario. |
| VII. Simplicidad y decisiones documentadas | ✅ Se reutiliza `chat.conversation_turn` en el mismo esquema `chat` en lugar de crear otro módulo o duplicar textos, y se documenta la decisión en **ADR-0030** (tras ADR-0029 de LAB-32, ya fusionado en `main`). |

**Gate**: PASA sin violaciones. **Re-evaluación tras el diseño (Fase 1)**: PASA.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-33-answer-feedback/
├── spec.md
├── plan.md                      # este archivo
├── research.md                  # Fase 0: decisiones R1-R7 y alternativas
├── data-model.md                # Fase 1: migración V3, chat.conversation_turn, chat.feedback y validaciones
├── quickstart.md                # Fase 1: escenarios de verificación V1-V11
├── contracts/
│   └── feedback-api.md          # Fase 1: evento done, PUT/GET/POST /api/chat/feedback, errores y métricas
├── checklists/
│   └── requirements.md
└── tasks.md                     # Fase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
backend/src/main/
├── java/com/shoplab/helpdesk/
│   ├── shared/exception/ResponseCodeEnum.java                  # + SLHD_CHAT_00011..00015
│   └── chat/
│       ├── domain/
│       │   ├── exception/
│       │   │   ├── FeedbackAlreadyPromotedException.java       # 409 SLHD_CHAT_00013
│       │   │   ├── FeedbackNotNegativeException.java           # 422 SLHD_CHAT_00015
│       │   │   ├── FeedbackTurnNotFoundException.java          # 404 SLHD_CHAT_00012
│       │   │   └── InvalidGoldenCaseException.java             # 422 SLHD_CHAT_00014 (implementa PublicDetail)
│       │   └── model/
│       │       ├── ChatEvent.java                              # DoneEvent(int fragments, String turnId)
│       │       ├── ConversationTurn.java                       # + constructores/factoría con deliveredDocuments y traceId
│       │       ├── FeedbackItem.java                           # turno + valoración + datos de promoción
│       │       ├── FeedbackListing.java                        # total, pendingCount, promotedCount, suggestedNextCaseId, items
│       │       ├── FeedbackRecord.java                         # turnId, rating, comment, createdAt, updatedAt, promotedCaseId, promotedAt
│       │       ├── GoldenCaseDraft.java                        # caseId, question, referenceAnswer, expectedDocuments, category
│       │       ├── PromoteFeedbackCommand.java                 # turnId, GoldenCaseDraft, requestId
│       │       ├── SubmitFeedbackCommand.java                  # turnId, rating, comment, requestId
│       │       ├── constants/
│       │       │   ├── ChatConstants.java                      # + FEEDBACK_METRIC, FEEDBACK_PROMOTED_METRIC, RATING_TAG
│       │       │   └── FeedbackConstants.java                  # @UtilityClass: MAX_COMMENT_LENGTH (500), patrones y reglas golden
│       │       └── enums/
│       │           └── FeedbackRating.java                     # UP, DOWN
│       ├── application/
│       │   ├── port/
│       │   │   ├── in/
│       │   │   │   ├── ListFeedbackQuery.java
│       │   │   │   ├── ListFeedbackServicePort.java
│       │   │   │   ├── PromoteFeedbackServicePort.java
│       │   │   │   └── SubmitFeedbackServicePort.java
│       │   │   └── out/
│       │   │       ├── ChatMetricsPort.java                    # + recordFeedback(FeedbackRating), recordFeedbackPromoted()
│       │   │       ├── ConversationMemoryPort.java             # saveTurn devuelve Mono<String> (turnId)
│       │   │       └── FeedbackStorePort.java                  # upsert, list, promote
│       │   └── usecase/
│       │       ├── ConversationHistory.java                    # save(...) devuelve Mono<String> (turnId) con documentos y traceId
│       │       ├── GoldenCaseValidator.java                    # valida reglas LAB-27 y serializa la línea JSONL canónica
│       │       ├── ListFeedbackUseCase.java
│       │       ├── PromoteFeedbackUseCase.java
│       │       ├── StreamChatUseCase.java                      # pasa documentos entregados y traceId a finish() -> DoneEvent(fragments, turnId)
│       │       └── SubmitFeedbackUseCase.java
│       ├── config/
│       │   ├── ChatConfig.java                                 # + FeedbackProperties
│       │   └── FeedbackProperties.java                         # shoplab.chat.feedback.base-golden-case-number (120)
│       └── infrastructure/
│           ├── in/rest/
│           │   ├── FeedbackController.java                     # PUT/GET/POST /api/chat/feedback
│           │   ├── dto/
│           │   │   ├── ChatEventPayload.java                   # Done(int fragments, @JsonInclude(NON_NULL) String turnId)
│           │   │   ├── FeedbackItemResponse.java
│           │   │   ├── FeedbackListResponse.java
│           │   │   ├── FeedbackResponse.java
│           │   │   ├── PromoteFeedbackRequest.java
│           │   │   └── SubmitFeedbackRequest.java
│           │   └── util/mapper/
│           │       ├── ChatEventMapper.java                    # DoneEvent -> ChatEventPayload.Done
│           │       └── FeedbackRestMapper.java
│           └── out/
│               ├── metrics/MicrometerChatMetrics.java          # + recordFeedback, recordFeedbackPromoted
│               └── persistence/
│                   ├── JdbcConversationMemoryAdapter.java      # INSERT ... RETURNING turn_id (con delivered_documents y trace_id)
│                   └── JdbcFeedbackAdapter.java                # BlockingCalls sobre chat.feedback + chat.conversation_turn
└── resources/
    ├── application.yml                                         # + shoplab.chat.feedback.base-golden-case-number
    └── db/migration/chat/
        └── V3__answer_feedback.sql                             # ALTER conversation_turn + CREATE TABLE feedback

backend/src/test/java/com/shoplab/helpdesk/
├── chat/
│   ├── application/usecase/
│   │   ├── GoldenCaseValidatorTests.java
│   │   ├── ListFeedbackUseCaseTests.java
│   │   ├── PromoteFeedbackUseCaseTests.java
│   │   └── SubmitFeedbackUseCaseTests.java
│   ├── infrastructure/
│   │   ├── in/rest/FeedbackControllerTests.java
│   │   └── out/persistence/JdbcFeedbackAdapterTests.java
│   └── ChatFeedbackIntegrationTests.java
└── schema/ModuleSchemasIntegrationTests.java                   # chat appliedVersions: "1", "2", "3"; foreignKeysOf("chat"): "feedback->conversation_turn"

frontend/src/app/features/
├── chat/
│   ├── chat-feedback.client.ts                                 # PUT /api/chat/feedback/{turnId}
│   ├── chat-feedback.client.spec.ts
│   ├── chat-message.ts                                         # + turnId?: string, feedback?: MessageFeedbackState
│   ├── chat-store.ts                                           # guarda turnId en 'done', rateMessage(), saveFeedbackComment()
│   ├── chat-store.spec.ts
│   ├── chat-stream.client.ts                                   # lee turnId opcional en el evento 'done'
│   ├── chat-stream.client.spec.ts
│   └── message-bubble/
│       ├── message-bubble.ts                                   # controles 👍/👎 + comentario opcional
│       ├── message-bubble.html
│       ├── message-bubble.scss
│       └── message-bubble.spec.ts
└── eval/
    ├── admin-eval.ts                                           # (existente de LAB-32) importa FeedbackPromotionView
    ├── admin-eval.html                                         # (existente de LAB-32) añade <app-feedback-promotion />
    ├── admin-eval.spec.ts                                      # verifica que la sección de feedback 👎 se renderiza en admin-eval
    └── feedback-promotion/
        ├── feedback-api.client.ts                              # GET /api/chat/feedback y POST /api/chat/feedback/{turnId}/promote
        ├── feedback-api.client.spec.ts
        ├── feedback-promotion.ts                               # listado de 👎, filtro, formulario de promoción y copia/descarga JSONL
        ├── feedback-promotion.html
        ├── feedback-promotion.scss
        └── feedback-promotion.spec.ts

eval/
├── src/shoplab_eval/constants.py                               # GoldenRules.max_cases = 150 (margen para casos promovidos)
├── tests/test_validation.py                                    # actualiza prueba del límite superior max_cases
└── README.md                                                   # + sección de promoción de feedback 👎 desde admin-eval

docs/
├── adr/0030-feedback-de-respuestas-y-promocion-a-golden.md     # NUEVO + fila en docs/adr/README.md
├── admin-eval.md                                               # + sección «Feedback 👎 y promoción a casos golden (LAB-33)»
├── chat-streaming.md                                           # + turnId en evento done y endpoints /api/chat/feedback
└── modulos.md                                                  # + tabla chat.feedback en sección del módulo chat
```

**Structure Decision**: Toda la persistencia y lógica de negocio del feedback reside en el módulo `chat`, dueño del esquema `chat` (ADR-0015). En el frontend, los controles de valoración viven en `features/chat/message-bubble` y la vista de revisión y promoción de respuestas con 👎 vive en `features/eval/feedback-promotion` dentro de la pantalla `admin-eval`.

## Complexity Tracking

> Sin violaciones a la Constitución. Todas las decisiones respetan los límites de módulos (ADR-0015), la separación hexagonal y el aislamiento JDBC con `BlockingCalls` (ADR-0011).
