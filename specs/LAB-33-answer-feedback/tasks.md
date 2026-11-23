# Tasks: Feedback 👍/👎 en cada respuesta

**Input**: documentos de diseño en `specs/LAB-33-answer-feedback/`
**Prerequisitos**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/feedback-api.md](contracts/feedback-api.md), [quickstart.md](quickstart.md)

**Pruebas**: SÍ. La Constitución (IV) exige Test-First con cobertura de líneas ≥ 80 % en el backend, pruebas deterministas sin red ni modelo real y pruebas en frontend (Vitest) y en `eval/` (`pytest`). Cada pieza con lógica lleva su tarea de prueba **antes**. **JaCoCo excluye** `dto`, `config`, `model`, `util/mapper` y `exception`: la lógica va en `application/usecase` o `infrastructure`, nunca en esos paquetes.

**Organización**: Las tareas están agrupadas por historia de usuario (US1, US2, US3, US4) tras la fase fundacional para permitir validar cada incremento de forma independiente.

## Formato: `[ID] [P?] [Historia] Descripción con ruta`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[US#]**: historia de `spec.md` a la que pertenece
- Rutas relativas a la raíz del repositorio:
  - `BASE` = `backend/src/main/java/com/shoplab/helpdesk`
  - `TEST` = `backend/src/test/java/com/shoplab/helpdesk`
  - `FE_CHAT` = `frontend/src/app/features/chat`
  - `FE_EVAL` = `frontend/src/app/features/eval`
  - `SPEC` = `specs/LAB-33-answer-feedback`
- Identificadores en inglés; documentación y textos visibles al usuario en español; líneas ≤ 121 caracteres; llaves en todos los bloques; Checkstyle, PMD, ESLint, Prettier y `ruff` limpios.

---

## Phase 1: Setup (verificación previa y registro de validación)

**Propósito**: confirmar el número de ADR libre tras la fusión de LAB-32 en `main` y dejar preparada la plantilla de validación.

- [x] T001 Comprobar el número libre del ADR en `docs/adr/` (tras `0029-lectura-del-historial-de-evaluacion.md` de LAB-32, el siguiente es **0030**) y crear `SPEC/validation.md` con una sección por escenario de `quickstart.md` (V1–V11) y la línea «ADR: 0030»
- [x] T002 Comprobar la línea base de pruebas en la rama `feature/LAB-33-answer-feedback` actualizada sobre `main`: ejecutar `cd backend && ./mvnw -q test -Dtest='Chat*Tests,ModuleSchemasIntegrationTests'` y `cd frontend && npm test -- --watch=false`

---

## Phase 2: Foundational (migración V3, códigos de error, modelos y puertos base)

**Propósito**: crear la migración `V3__answer_feedback.sql` del esquema `chat`, los cinco códigos de error en `ResponseCodeEnum`, las excepciones tipadas, constantes, modelos de dominio y puertos que comparten las historias US1–US4.

**⚠️ CRÍTICO**: terminar esta fase completa antes de iniciar las historias de usuario.

- [x] T003 [P] Actualizar `TEST/schema/ModuleSchemasIntegrationTests.java` para exigir que el esquema `chat` tenga las versiones aplicadas `"1"`, `"2"`, `"3"` y que `foreignKeysOf("chat")` contenga únicamente `"feedback->conversation_turn"` (clave foránea interna dentro del esquema `chat`), y ampliar `TEST/schema/ChatSchemaIntegrationTests.java` (o crearla si no existe) para verificar las columnas y restricciones `CHECK` y `UNIQUE` de `chat.conversation_turn` (`turn_id`, `delivered_documents`, `trace_id`) y `chat.feedback` (`turn_ref`, `rating`, `comment`, `promoted_case_id`, `promoted_case_jsonl`, `promoted_at`)
- [x] T004 Crear `backend/src/main/resources/db/migration/chat/V3__answer_feedback.sql`: añadir a `conversation_turn` las columnas `turn_id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE`, `delivered_documents text[] NOT NULL DEFAULT '{}'` y `trace_id text CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$')`; crear la tabla `feedback` con todas las columnas, restricciones `CHECK`/`UNIQUE` e índice `feedback_rating_created_idx` de `data-model.md`. Hace pasar T003
- [x] T005 [P] Añadir en `BASE/shared/exception/ResponseCodeEnum.java` los cinco códigos del contrato (`INVALID_FEEDBACK` `SLHD_CHAT_00011` 400, `FEEDBACK_TURN_NOT_FOUND` `SLHD_CHAT_00012` 404, `FEEDBACK_ALREADY_PROMOTED` `SLHD_CHAT_00013` 409, `INVALID_GOLDEN_CASE` `SLHD_CHAT_00014` 422, `FEEDBACK_NOT_NEGATIVE` `SLHD_CHAT_00015` 422) y crear en `BASE/chat/domain/exception/` las excepciones tipadas `FeedbackTurnNotFoundException`, `FeedbackAlreadyPromotedException`, `FeedbackNotNegativeException` e `InvalidGoldenCaseException` (esta última implementando `PublicDetail`)
- [x] T006 [P] Crear `BASE/chat/domain/model/enums/FeedbackRating.java` (`UP`, `DOWN`, con `tagValue()` en minúsculas `"up"`/`"down"` y `fromValue(String)`) y `BASE/chat/domain/model/constants/FeedbackConstants.java` (`@UtilityClass`: `MAX_COMMENT_LENGTH = 500`, límites de listado `DEFAULT_LIST_LIMIT = 100`, `MAX_LIST_LIMIT = 500`, `DEFAULT_BASE_GOLDEN_CASE_NUMBER = 120`, patrones `CASE_ID`, `DOCUMENT_ID`, `EMAIL`, `LONG_DIGITS` y listas de categorías de LAB-27)
- [x] T007 [P] Crear en `BASE/chat/domain/model/` los records de dominio: `SubmitFeedbackCommand`, `FeedbackRecord`, `FeedbackItem`, `FeedbackListing`, `GoldenCaseDraft` y `PromoteFeedbackCommand`, y ampliar `ChatEvent.DoneEvent` con `String turnId` (manteniendo constructor `DoneEvent(int fragments)` que delega con `null` para compatibilidad con tests existentes) y `ConversationTurn` con `List<String> deliveredDocuments` y `String traceId` (manteniendo constructor `ConversationTurn(String question, String answer)` que delega con `List.of()` y `null`)
- [x] T008 [P] Crear `BASE/chat/config/FeedbackProperties.java` (`@ConfigurationProperties("shoplab.chat.feedback")` con `int baseGoldenCaseNumber`, por defecto `120`), registrarlo en `BASE/chat/config/ChatConfig.java` y añadir `shoplab.chat.feedback.base-golden-case-number: ${SHOPLAB_CHAT_FEEDBACK_BASE_GOLDEN_CASE_NUMBER:120}` en `backend/src/main/resources/application.yml`
- [x] T009 [P] Crear en `BASE/chat/application/port/`: puertos de entrada `in/SubmitFeedbackServicePort.java`, `in/ListFeedbackQuery.java`, `in/ListFeedbackServicePort.java`, `in/PromoteFeedbackServicePort.java`, y puerto de salida `out/FeedbackStorePort.java` (`upsert`, `list`, `promote`), además de actualizar `out/ConversationMemoryPort.java` para que `saveTurn` devuelva `Mono<String>` (el `turnId` generado)

**Checkpoint**: `./mvnw -B -ntp test -Dtest='ModuleSchemasIntegrationTests,ChatSchemaIntegrationTests'` en verde.

---

## Phase 3: User Story 2 — Conservar el feedback en `chat.feedback` con el contexto del turno (Priority: P1) 🎯 MVP Backend

**Goal**: que cada turno completado con `conversationId` guarde sus documentos entregados y `traceId` en `chat.conversation_turn`, devuelva `turnId` en el evento SSE `done` (sin alterar el stream cuando no hay `conversationId`), y permita guardar y actualizar la valoración en `chat.feedback` a través de `PUT /api/chat/feedback/{turnId}`.

**Independent Test**: enviar un turno de chat con `conversationId`, tomar el `turnId` del evento `done`, enviar `PUT /api/chat/feedback/{turnId}` con `UP` y luego con `DOWN` y comentario, y verificar en PostgreSQL que existe una única fila en `chat.feedback` vinculada al turno con su pregunta, respuesta y documentos entregados.

### Pruebas primero (US2)

- [x] T010 [P] [US2] Ampliar `TEST/chat/infrastructure/out/persistence/JdbcConversationMemoryAdapterTests.java` y `TEST/chat/application/usecase/StreamChatUseCaseTests.java`: verificar que `saveTurn` guarda `delivered_documents` (ids de documento sin `.md`/`.pdf`, sin duplicados y en orden) y `trace_id`, devuelve el `turnId` (`UUID`), y que `StreamChatUseCase` emite `DoneEvent(fragments, turnId)` cuando hay `conversationId` y `DoneEvent(fragments, null)` (serializado como `{"fragments":N}` sin clave `turnId`) cuando no hay `conversationId`
- [x] T011 [P] [US2] Crear `TEST/chat/application/usecase/SubmitFeedbackUseCaseTests.java` (Mockito + `StepVerifier`): valida `turnId` (UUID válido, si no `SLHD_CHAT_00011`), `rating` (`UP`/`DOWN`), normaliza `comment` (`strip()`, vacío → `null`, > 500 caracteres → `SLHD_CHAT_00011`), delega en `FeedbackStorePort.upsert`, propaga `FeedbackTurnNotFoundException` (`SLHD_CHAT_00012`) y `FeedbackAlreadyPromotedException` (`SLHD_CHAT_00013`)
- [x] T012 [P] [US2] Crear `TEST/chat/infrastructure/out/persistence/JdbcFeedbackAdapterTests.java` (`@SpringBootTest`, `@PostgresTest`): insertar valoración `UP` sin comentario sobre un turno existente; actualizar el mismo `turnId` a `DOWN` con comentario y comprobar que sigue habiendo 1 sola fila en `chat.feedback` (`SC-003`); turno inexistente → `FeedbackTurnNotFoundException`; turno ya promovido → `FeedbackAlreadyPromotedException` al intentar cambiar su voto o comentario; fallo de conexión → `ConversationMemoryException` (`SLHD_CHAT_00006`)

### Implementación (US2)

- [x] T013 [US2] Actualizar `BASE/chat/infrastructure/out/persistence/JdbcConversationMemoryAdapter.java`, `BASE/chat/application/usecase/ConversationHistory.java`, `BASE/chat/application/usecase/StreamChatUseCase.java`, `BASE/chat/infrastructure/in/rest/dto/ChatEventPayload.java` (`Done(int fragments, @JsonInclude(NON_NULL) String turnId)`) y `BASE/chat/infrastructure/in/rest/util/mapper/ChatEventMapper.java` para guardar `delivered_documents` y `trace_id` y emitir `turnId` en `done`. Hace pasar T010 sin romper `ChatStreamContractTests`
- [x] T014 [US2] Implementar `BASE/chat/application/usecase/SubmitFeedbackUseCase.java` (`@Service`, implementa `SubmitFeedbackServicePort`). Hace pasar T011
- [x] T015 [US2] Implementar ` upsert` en `BASE/chat/infrastructure/out/persistence/JdbcFeedbackAdapter.java` (`@Component`, `@DependsOn("moduleSchemaMigrator")`, usa `BlockingCalls` y `TransactionTemplate`). Hace pasar T012
- [x] T016 [P] [US2] Crear `TEST/chat/infrastructure/in/rest/FeedbackControllerTests.java` (`WebTestClient`, parte `PUT /api/chat/feedback/{turnId}`): 200 al crear/actualizar, 400 `SLHD_CHAT_00011` con UUID inválido, voto inválido o comentario > 500 caracteres, 404 `SLHD_CHAT_00012` si el turno no existe, 409 `SLHD_CHAT_00013` si ya fue promovido, 503 `SLHD_CHAT_00006` si falla el almacén
- [x] T017 [US2] Crear los DTOs `SubmitFeedbackRequest.java` y `FeedbackResponse.java` en `BASE/chat/infrastructure/in/rest/dto/`, el mapeador `BASE/chat/infrastructure/in/rest/util/mapper/FeedbackRestMapper.java` y el endpoint `PUT /api/chat/feedback/{turnId}` en `BASE/chat/infrastructure/in/rest/FeedbackController.java`. Hace pasar T016

**Checkpoint**: `./mvnw -B -ntp test -Dtest='JdbcConversationMemoryAdapterTests,StreamChatUseCaseTests,SubmitFeedbackUseCaseTests,JdbcFeedbackAdapterTests,FeedbackControllerTests,ChatStreamContractTests'` en verde.

---

## Phase 4: User Story 1 — Valorar una respuesta con 👍 o 👎 y comentario opcional en la pantalla de chat (Priority: P1) 🎯 MVP Frontend

**Goal**: mostrar los botones 👍 y 👎 y el comentario opcional en cada burbuja completada del asistente en `/chat`, enviando la valoración en un clic y permitiendo añadir o actualizar el comentario sobre el mismo turno.

**Independent Test**: en `/chat`, completar una respuesta, pulsar 👍 (queda marcado como útil), cambiar a 👎, abrir el comentario, escribir un motivo y guardarlo; verificar que las burbujas en `streaming`, `stopped` o `failed` no muestran controles de valoración.

### Pruebas primero (US1)

- [x] T018 [P] [US1] Ampliar `FE_CHAT/chat-stream.client.spec.ts` para verificar que el evento SSE `done` extrae `turnId` cuando viene en el JSON y sigue funcionando cuando `turnId` no viene, y crear `FE_CHAT/chat-feedback.client.spec.ts` (`HttpTestingController`) para `PUT /api/chat/feedback/{turnId}`
- [x] T019 [P] [US1] Ampliar `FE_CHAT/chat-store.spec.ts`: al recibir `done` con `turnId`, el mensaje del asistente queda en `status: 'complete'` con ese `turnId`; `rateMessage(messageId, 'UP' | 'DOWN')` envía la valoración y actualiza el estado (`saving`, `saved`, `rating`); `toggleFeedbackComment(messageId)` abre/cierra el cuadro de comentario; `saveFeedbackComment(messageId, comment)` envía el voto actual con el comentario; un fallo HTTP expone un mensaje en español en `feedback.error` sin borrar el comentario y permite reintentar
- [x] T020 [P] [US1] Ampliar `FE_CHAT/message-bubble/message-bubble.spec.ts`: los botones «Respuesta útil» (👍) y «Respuesta no útil» (👎) solo se renderizan cuando `author === 'assistant' && status === 'complete' && turnId`; tienen `aria-pressed`; al pulsar 👍 o 👎 emiten el voto; permiten abrir el campo de comentario opcional (máximo 500 caracteres), enviarlo y ver confirmación o aviso de error con `role="status"` / `role="alert"`

### Implementación (US1)

- [x] T021 [US1] Actualizar `FE_CHAT/chat-message.ts` (`turnId?: string`, `MessageFeedbackState`, `FeedbackRating`, `MAX_FEEDBACK_COMMENT_LENGTH = 500`), `FE_CHAT/chat-stream.client.ts` (leer `turnId` opcional en `done`) y crear `FE_CHAT/chat-feedback.client.ts` (`submitFeedback(turnId, rating, comment?)`). Hace pasar T018
- [x] T022 [US1] Actualizar `FE_CHAT/chat-store.ts` para almacenar `turnId` al recibir `done` y añadir `rateMessage`, `toggleFeedbackComment`, `updateFeedbackDraft` y `saveFeedbackComment`. Hace pasar T019
- [x] T023 [US1] Actualizar `FE_CHAT/message-bubble/message-bubble.ts`, `.html` y `.scss`, y conectar los eventos en `FE_CHAT/chat.ts` y `FE_CHAT/chat.html`: barra de feedback accesible por teclado, `aria-pressed`, botón de comentario opcional, campo de texto con límite de 500 caracteres, estado guardado y error en español, adaptable a 360 px sin desborde horizontal. Hace pasar T020

**Checkpoint**: `cd frontend && npm test -- --watch=false` en verde; US1 y US2 completas de punta a punta en `/chat`.

---

## Phase 5: User Story 3 — Promover respuestas con 👎 a casos del dataset golden desde `admin-eval` (Priority: P1)

**Goal**: listar las valoraciones con 👎 en `admin-eval`, validar y promover una valoración negativa a un caso golden `G<NNN>` cumpliendo las reglas de LAB-27, guardar el vínculo y la línea JSONL en `chat.feedback`, mostrar la línea JSONL para copiar o descargar y ampliar el tope `GoldenRules.max_cases` en `eval/` para admitir los casos promovidos.

**Independent Test**: abrir `/admin-eval`, cargar las respuestas con 👎, promover una completando categoría, documentos esperados y respuesta de referencia válida; comprobar que queda marcada como promovida a `G121`, que un caso inválido se rechaza con 422 y su código `G-...`, y que la línea JSONL generada pasa `uv run python -m shoplab_eval validate`.

### Pruebas primero (US3 — Backend y `eval/`)

- [x] T024 [P] [US3] Crear `TEST/chat/application/usecase/GoldenCaseValidatorTests.java` (JUnit puro): valida un caso con respuesta, uno sin respuesta del tema, uno `off_topic`, uno `small_talk` y uno `injection`, y verifica que serializa la línea JSONL exacta con las 5 claves en orden (`id`, `question`, `reference_answer`, `expected_documents`, `category`); verifica el rechazo con `InvalidGoldenCaseException` y el código en `publicDetail()` para cada violación: `G-ID` (formato distinto de `^G[0-9]{3}$`), `G-CATEGORY` (categoría desconocida), `G-QUESTION-LENGTH` (< 10 o > 300 en conocimiento; < 2 o > 300 en conversación), `G-ANSWER-LENGTH` (< 20 o > 800), `G-DOC-COUNT` (> 2 documentos, id inválido o repetidos), `G-CATEGORY-TOPIC` (documentos en `off_topic`/`small_talk`/`injection`, o documento cuyo prefijo no coincide con el tema), `G-NOANSWER-DIGITS` (cifras en `referenceAnswer` cuando `expectedDocuments` es vacía) y `G-PII` (secuencia de ≥ 8 dígitos o correo distinto de `@shoplab.example`)
- [x] T025 [P] [US3] Crear `TEST/chat/application/usecase/ListFeedbackUseCaseTests.java` y `TEST/chat/application/usecase/PromoteFeedbackUseCaseTests.java` (Mockito + `StepVerifier`): listado con filtro `rating` (por defecto `DOWN`), `promoted` (`true`/`false`/`null`) y `limit` (`1..500`, fuera de rango → `SLHD_CHAT_00011`); promoción valida el caso con `GoldenCaseValidator` antes de tocar el puerto, rechaza si el turno no existe (`SLHD_CHAT_00012`), si el voto es `UP` (`SLHD_CHAT_00015`) o si ya estaba promovido o el `caseId` ya existe (`SLHD_CHAT_00013`)
- [x] T026 [P] [US3] Ampliar `TEST/chat/infrastructure/out/persistence/JdbcFeedbackAdapterTests.java` y `TEST/chat/infrastructure/in/rest/FeedbackControllerTests.java` para `list` (`GET /api/chat/feedback`) y `promote` (`POST /api/chat/feedback/{turnId}/promote`): orden por fecha descendente, conteos `total`/`pendingCount`/`promotedCount`, cálculo de `suggestedNextCaseId` (`G121` al inicio, `G122` tras promover `G121`), rechazo de voto `UP` (422 `SLHD_CHAT_00015`), de doble promoción o `caseId` repetido (409 `SLHD_CHAT_00013`) y de regla golden inválida (422 `SLHD_CHAT_00014` con `detail` `G-...`)

### Implementación (US3 — Backend y `eval/`)

- [x] T027 [US3] Implementar `BASE/chat/application/usecase/GoldenCaseValidator.java` (`@Component`), `BASE/chat/application/usecase/ListFeedbackUseCase.java` y `BASE/chat/application/usecase/PromoteFeedbackUseCase.java`. Hace pasar T024 y T025
- [x] T028 [US3] Implementar `list` y `promote` en `BASE/chat/infrastructure/out/persistence/JdbcFeedbackAdapter.java`, añadir los DTOs `FeedbackItemResponse.java`, `FeedbackListResponse.java` y `PromoteFeedbackRequest.java` en `BASE/chat/infrastructure/in/rest/dto/`, ampliar `FeedbackRestMapper.java` y exponer `GET /api/chat/feedback` y `POST /api/chat/feedback/{turnId}/promote` en `BASE/chat/infrastructure/in/rest/FeedbackController.java`. Hace pasar T026
- [x] T029 [P] [US3] En `eval/src/shoplab_eval/constants.py`, ampliar `GoldenRules.max_cases` de `100` a `150` para admitir en `eval/golden.jsonl` los casos promovidos desde `chat.feedback`, actualizar la prueba del tope superior en `eval/tests/test_validation.py` y verificar `cd eval && uv run pytest && uv run ruff check .`

### Pruebas e implementación (US3 — Frontend `admin-eval`)

- [x] T030 [P] [US3] Crear `FE_EVAL/feedback-promotion/feedback-api.client.spec.ts` y `FE_EVAL/feedback-promotion/feedback-api.client.ts`: `listFeedback(promoted?, limit?)` (`GET /api/chat/feedback?rating=DOWN...`) y `promoteFeedback(turnId, draft)` (`POST /api/chat/feedback/{turnId}/promote`) con `HANDLES_OWN_ERRORS`
- [x] T031 [P] [US3] Crear `FE_EVAL/feedback-promotion/feedback-promotion.spec.ts`: sección «Feedback 👎 de clientes» con carga bajo demanda («Cargar respuestas con 👎» / «Actualizar feedback»), filtro «Pendientes de promover / Todas», estado vacío y de error con reintento; al pulsar «Promover a caso golden» sobre un 👎 pendiente, abre el formulario precargado con `suggestedNextCaseId` (`G121`), la `question` del cliente y los `deliveredDocuments` (hasta 2) con `referenceAnswer` vacía; al enviar, llama a `POST /api/chat/feedback/{turnId}/promote`, muestra la línea `promotedCaseJsonl` con botones «Copiar JSONL» y «Descargar .jsonl», marca la fila como «Promovido a G121» e impide promoverla de nuevo; si el backend devuelve 422 `SLHD_CHAT_00014` o 409 `SLHD_CHAT_00013`, muestra el detalle de validación sin cerrar el formulario
- [x] T032 [US3] Implementar `FE_EVAL/feedback-promotion/feedback-promotion.ts`, `.html` y `.scss`, e integrarlo (`<app-feedback-promotion />`) en `FE_EVAL/admin-eval.ts` y `FE_EVAL/admin-eval.html`, añadiendo una comprobación en `FE_EVAL/admin-eval.spec.ts` de que la sección de feedback está presente sin romper ninguna de las pruebas de LAB-32. Hace pasar T030 y T031

**Checkpoint**: `./mvnw -B -ntp test -Dtest='GoldenCaseValidatorTests,ListFeedbackUseCaseTests,PromoteFeedbackUseCaseTests,JdbcFeedbackAdapterTests,FeedbackControllerTests'`, `cd frontend && npm test -- --watch=false` y `cd eval && uv run pytest` en verde.

---

## Phase 6: User Story 4 — Observabilidad y privacidad en logs y métricas del feedback (Priority: P2)

**Goal**: registrar métricas en Prometheus (`shoplab.chat.feedback` con `rating=up|down` y `shoplab.chat.feedback.promoted`), logs estructurados sin texto libre (Constitución VI) y prueba de integración de punta a punta con BlockHound activo.

**Independent Test**: ejecutar el flujo completo en una prueba de integración (`POST /api/chat` con `conversationId` → `PUT /api/chat/feedback/{turnId}` con `UP` y `DOWN` → `GET /api/chat/feedback` → `POST /api/chat/feedback/{turnId}/promote`) y comprobar en `/actuator/prometheus` que los contadores suben y no contienen `turnId`, `conversationId`, preguntas, respuestas ni comentarios.

- [x] T033 [P] [US4] Ampliar `TEST/chat/infrastructure/out/metrics/MicrometerChatMetricsTests.java` (o crear la prueba unitaria) y `TEST/chat/application/usecase/SubmitFeedbackUseCaseTests.java` / `PromoteFeedbackUseCaseTests.java` para verificar que `recordFeedback(rating)` incrementa `shoplab.chat.feedback` (`rating=up|down`) y `recordFeedbackPromoted()` incrementa `shoplab.chat.feedback.promoted`, sin etiquetas de alta cardinalidad
- [x] T034 [US4] Añadir `FEEDBACK_METRIC = "shoplab.chat.feedback"`, `FEEDBACK_PROMOTED_METRIC = "shoplab.chat.feedback.promoted"` y `RATING_TAG = "rating"` en `BASE/chat/domain/model/constants/ChatConstants.java`, los métodos `recordFeedback(FeedbackRating)` y `recordFeedbackPromoted()` en `BASE/chat/application/port/out/ChatMetricsPort.java` y su implementación en `BASE/chat/infrastructure/out/metrics/MicrometerChatMetrics.java`, e invocarlos desde `SubmitFeedbackUseCase` y `PromoteFeedbackUseCase`. Hace pasar T033
- [x] T035 [US4] Crear `TEST/chat/ChatFeedbackIntegrationTests.java` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@PostgresTest`): prueba de extremo a extremo que (1) envía una pregunta con `conversationId` a `POST /api/chat`, (2) extrae `turnId` del evento SSE `done`, (3) registra feedback `UP` y luego lo actualiza a `DOWN` con comentario vía `PUT /api/chat/feedback/{turnId}`, (4) consulta `GET /api/chat/feedback?rating=DOWN&promoted=false`, (5) promueve el feedback a `G121` con `POST /api/chat/feedback/{turnId}/promote` y verifica la línea JSONL devuelta, (6) comprueba que re-promover o cambiar el voto devuelve 409 `SLHD_CHAT_00013`, y (7) verifica en `/actuator/prometheus` que aparecen `shoplab_chat_feedback_total{...,rating="up"}`, `shoplab_chat_feedback_total{...,rating="down"}` y `shoplab_chat_feedback_promoted_total` sin contener `turnId`, `conversationId` ni textos de la conversación

**Checkpoint**: `./mvnw -B -ntp test -Dtest='ChatFeedbackIntegrationTests,MicrometerChatMetricsTests'` en verde con BlockHound activo.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Propósito**: ADR-0030, documentación actualizada, archivo `requests.http` (regla `http-test-requests.md`) y verificación completa de puertas de calidad.

- [x] T036 [P] Crear `docs/adr/0030-feedback-de-respuestas-y-promocion-a-golden.md` siguiendo `docs/adr/0000-plantilla.md` (contexto de LAB-33, decisión de identificar el turno con `turn_id` UUID en `chat.conversation_turn` y evento `done` aditivo, tabla `chat.feedback` en el esquema `chat` con relación 1:1, validación con reglas de LAB-27 y promoción desde `admin-eval` generando la línea JSONL) y añadir su fila a `docs/adr/README.md`
- [x] T037 [P] Actualizar `docs/admin-eval.md`, `docs/chat-streaming.md`, `docs/modulos.md` y `eval/README.md` (FR-016): documentar el campo `turnId` del evento `done`, los endpoints `/api/chat/feedback`, la tabla `chat.feedback` en el esquema `chat`, cómo revisar y promover respuestas con 👎 desde `/admin-eval` hacia `eval/golden.jsonl` y el registro de revisión humana en `eval/REVIEW.md`
- [x] T038 [P] Crear `SPEC/requests.http` (regla `http-test-requests.md`, sintaxis IntelliJ HTTP Client / VS Code REST Client, `{{baseUrl}}` = `http://localhost:8080`) cubriendo todos los escenarios alcanzables por HTTP: turno de chat con `conversationId` que devuelve `turnId` en `done`, turno sin `conversationId` sin `turnId`, `PUT /api/chat/feedback/{turnId}` con `UP`, actualización a `DOWN` con comentario, comentario en el límite (500) y fuera del límite (501 → 400 `SLHD_CHAT_00011`), `turnId` inexistente (404 `SLHD_CHAT_00012`), `GET /api/chat/feedback?rating=DOWN&promoted=false`, promoción inválida por `G-NOANSWER-DIGITS` y por `G-PII` (422 `SLHD_CHAT_00014`), intento de promover un `UP` (422 `SLHD_CHAT_00015`), promoción válida a `G121` (200) e intento de re-promover o cambiar voto de un feedback ya promovido (409 `SLHD_CHAT_00013`)
- [x] T039 Ejecutar todas las puertas de calidad y actualizar `SPEC/validation.md`:
  - `cd backend && ./mvnw clean verify -Dskip.srcclr=true --no-transfer-progress` (tests, BlockHound, Modulith, ArchUnit, Checkstyle, PMD, JaCoCo ≥ 80 % y `docs/modulos/` sin deriva)
  - `cd frontend && npm run lint && npm test -- --watch=false && npm run build`
  - `cd eval && uv run pytest && uv run ruff check .`

---

## Dependencies & Execution Order

### Phase Dependencies

1. **Setup (Phase 1)**: inmediato.
2. **Foundational (Phase 2)**: depende de Phase 1; bloquea todas las historias (crea la migración `V3`, códigos de error, modelos y puertos).
3. **User Story 2 (Phase 3 — Backend de feedback y `turnId` en `done`)**: depende de Phase 2.
4. **User Story 1 (Phase 4 — UI de feedback 👍/👎 en `/chat`)**: puede avanzar en paralelo con Phase 3 (sus tests usan `HttpTestingController`) y se integra con Phase 3.
5. **User Story 3 (Phase 5 — Promoción de 👎 a casos golden en backend, `eval/` y `/admin-eval`)**: depende de Phase 3 (`JdbcFeedbackAdapter` y `FeedbackController`).
6. **User Story 4 (Phase 6 — Métricas, logs sin PII y test de integración E2E)**: depende de Phases 3 y 5.
7. **Polish (Phase 7 — ADR-0030, documentación, `requests.http` y puertas de calidad)**: depende de Phases 3–6.
