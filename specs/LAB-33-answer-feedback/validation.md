# Registro de validación — LAB-33 Feedback 👍/👎 en cada respuesta

ADR: 0030

**Cómo leer este archivo.** «Resultado» es el de la **validación manual** de `quickstart.md` en un navegador con el stack local arriba. En «Evidencia» se anota la verificación automática ejecutada en esta rama.

## Escenarios de `quickstart.md`

### V1 — Aparición de los botones 👍 y 👎 al completarse la respuesta (`done`)

- **Resultado**: pendiente (requiere navegador con `/chat`)
- **Evidencia**: `message-bubble.spec.ts` («renders 👍 and 👎 buttons only for completed assistant messages that have a turnId»), `chat-stream.client.spec.ts` y `chat-store.spec.ts` (verdes).

### V2 — Valoración 👍 inicial en un clic (`UP` sin comentario)

- **Resultado**: pendiente (navegador)
- **Evidencia**: `chat-store.spec.ts`, `chat-feedback.client.spec.ts`, `SubmitFeedbackUseCaseTests`, `JdbcFeedbackAdapterTests` y `ChatFeedbackIntegrationTests` (verdes).

### V3 — Cambio de voto a 👎 y comentario opcional sobre la misma fila (`SC-003`)

- **Resultado**: pendiente (navegador)
- **Evidencia**: `JdbcFeedbackAdapterTests.upsertsFeedbackOnExistingTurnKeepingASingleRowWhenUpdated` y `ChatFeedbackIntegrationTests` comprueban contra PostgreSQL real que tras cambiar de `UP` a `DOWN` con comentario sigue existiendo una única fila en `chat.feedback` para ese `turn_id` (verdes).

### V4 — Valoración sobre respuestas de cortesía o de seguimiento con documentos entregados (`SC-002`)

- **Resultado**: pendiente (navegador)
- **Evidencia**: `StreamChatUseCaseTests.emitsThePersistedTurnIdInDoneAndStoresDeliveredDocumentIdsAndTraceIdWhenAConversationIsPresent`, `StreamChatUseCaseSmallTalkTests` y `JdbcConversationMemoryAdapterTests` (verdes).

### V5 — Listado de valoraciones con 👎 en `/admin-eval` (`FR-009`)

- **Resultado**: pendiente (navegador)
- **Evidencia**: `ListFeedbackUseCaseTests`, `JdbcFeedbackAdapterTests.listsNegativeFeedbackNewestFirstAndComputesCountsAndSuggestedNextCaseId`, `FeedbackControllerTests` y `feedback-promotion.spec.ts` (verdes).

### V6 — Apertura del formulario de promoción precargado con `G121` (`FR-010`, `FR-011`)

- **Resultado**: pendiente (navegador)
- **Evidencia**: `feedback-promotion.spec.ts` («preloads the promotion form with suggestedNextCaseId, question and deliveredDocuments and promotes to G121») (verde).

### V7 — Rechazo 422 `SLHD_CHAT_00014` ante reglas de LAB-27 incumplidas (`G-NOANSWER-DIGITS`, `G-PII`) (`SC-005`)

- **Resultado**: pendiente (navegador)
- **Evidencia**: `GoldenCaseValidatorTests` (verifica `G-ID`, `G-CATEGORY`, `G-QUESTION-LENGTH`, `G-ANSWER-LENGTH`, `G-DOC-COUNT`, `G-CATEGORY-TOPIC`, `G-NOANSWER-DIGITS` y `G-PII`), `PromoteFeedbackUseCaseTests`, `FeedbackControllerTests` y `feedback-promotion.spec.ts` (verdes).

### V8 — Promoción válida a `G121` con línea JSONL lista para copiar o descargar (`SC-004`)

- **Resultado**: pendiente (navegador)
- **Evidencia**: `PromoteFeedbackUseCaseTests`, `JdbcFeedbackAdapterTests`, `ChatFeedbackIntegrationTests` y `feedback-promotion.spec.ts` (verdes).

### V9 — Validación de la línea JSONL generada con `shoplab_eval validate` (`SC-004`)

- **Resultado**: pendiente (ejecución manual tras pegar en `eval/golden.jsonl`)
- **Evidencia**: `GoldenCaseValidatorTests` verifica el formato exacto de la línea JSONL con las 5 claves en orden y `eval/tests/test_validation_set.py` verifica el nuevo tope `GoldenRules.max_cases = 150` con `pytest` y `ruff` en verde.

### V10 — Rechazo 409 `SLHD_CHAT_00013` al intentar re-promover o alterar un feedback ya promovido (`FR-011`)

- **Resultado**: pendiente (navegador)
- **Evidencia**: `JdbcFeedbackAdapterTests.enforcesPromotionInvariantsForNotFoundPositiveAlreadyPromotedAndDuplicateCaseId` y `ChatFeedbackIntegrationTests` (verdes).

### V11 — Métricas en `/actuator/prometheus` y logs sin texto libre ni PII (`SC-007`)

- **Resultado**: pendiente (navegador / stack local)
- **Evidencia**: `MicrometerChatMetricsTests`, `SubmitFeedbackUseCaseTests`, `PromoteFeedbackUseCaseTests` y `ChatFeedbackIntegrationTests` verifican `shoplab_chat_feedback_total{rating="up|down"}` y `shoplab_chat_feedback_promoted_total` en `/actuator/prometheus` y `CapturedOutput` sin `turnId`, `conversationId` ni textos de la conversación (verdes).

## Puertas de calidad (T039)

- **Backend (`./mvnw test jacoco:report checkstyle:check pmd:check`)**: Verde (317 pruebas pasadas, 0 fallos, Checkstyle 0 violaciones, PMD 0 violaciones, Modulith y ArchUnit en verde, BlockHound activo).
- **Frontend (`npm run lint && npm test -- --watch=false && npm run build`)**: Verde (ESLint limpio, 48 archivos / 627 pruebas en verde, bundle generado sin errores).
- **Eval (`uv run pytest && uv run ruff check .`)**: Verde (639 pruebas pasadas, cobertura 97.64 %, `ruff check` sin hallazgos).
