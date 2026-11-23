# Research: Feedback 👍/👎 en cada respuesta — LAB-33

Las decisiones de diseño de esta historia se recogen aquí con las alternativas evaluadas y se registran como **ADR-0030** (el siguiente número libre en `docs/adr/` tras ADR-0029 de LAB-32).

**Alcance**:
- Valorar respuestas completadas del asistente con 👍 (`UP`) o 👎 (`DOWN`) y comentario opcional desde la pantalla de chat (`/chat`).
- Guardar y actualizar la valoración en `chat.feedback` dentro del esquema `chat`, con el contexto del turno (pregunta, respuesta, documentos entregados y `traceId`).
- Revisar las valoraciones con 👎 y promoverlas a casos golden validados desde la interfaz web `admin-eval` (aclaración resuelta con la **Opción B**), registrando el identificador `G<NNN>` y el caso JSONL en `chat.feedback` y ofreciendo la línea JSONL exacta para copiar o descargar e incorporar a `eval/golden.jsonl`.

---

## R1 · Cómo identifica el cliente el turno que valora

**Decisión**:
- Se amplía `chat.conversation_turn` (migración `V3__answer_feedback.sql`) con tres columnas:
  - `turn_id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE`: identificador público e impredecible del turno.
  - `delivered_documents text[] NOT NULL DEFAULT '{}'`: identificadores de los documentos (`source` sin extensión `.md`/`.pdf`, sin duplicados y en orden de entrega) de los fragmentos entregados al modelo en ese turno.
  - `trace_id text`: identificador de traza de Langfuse del turno (`DerivedTraceIdGenerator.traceIdOf(requestId)`, 32 hex).
- Cuando un turno con `conversationId` termina y se persiste en `chat.conversation_turn` antes de emitir `done` (ADR-0020), `saveTurn` devuelve el `turn_id` (`UUID`) generado, y el evento SSE `done` lo incluye como campo opcional:
  ```json
  event:done
  data:{"fragments":6,"turnId":"2b6f..."}
  ```
- Si la petición no llevaba `conversationId` (por ejemplo, llamadas directas de `ChatStreamContractTests`, LAB-12 y LAB-23), `turnId` es `null` y `@JsonInclude(JsonInclude.Include.NON_NULL)` en `ChatEventPayload.Done` lo omite, de modo que el stream sigue terminando exactamente con `data:{"fragments":6}` byte a byte.

**Por qué**:
- **No se confía en el cliente para reenviar la pregunta, la respuesta ni los documentos**: el navegador solo envía `turnId`, `rating` y `comment`; el servidor ya tiene el turno real en `chat.conversation_turn`.
- **La pantalla de chat siempre envía `conversationId`** (LAB-24, `ChatStore.conversationId = newConversationId()`), incluidas las respuestas con contexto, las sin contexto y las de cortesía (`small_talk`, ver R2), así que el 100 % de las respuestas completadas en la pantalla de chat tienen `turnId` y se pueden valorar.
- **No rompe `ChatStreamContractTests`**: la prueba de contrato byte a byte de LAB-111 llama a `POST /api/chat` sin `conversationId` y compara contra `src/test/resources/chat/golden-stream.txt` (`data:{"fragments":6}`). Con `@JsonInclude(NON_NULL)`, las peticiones sin `conversationId` no alteran un solo byte.

**Alternativas descartadas**:
- *Que el navegador envíe `{ question, answer, sources, rating, comment }` al valorar*: permitiría inyectar preguntas o respuestas falsas en `chat.feedback` y obligaría al frontend a reconstruir ids de documento (que `sources` en SSE deliberadamente no expone; `sources` solo envía `title`, `section`, `text`, LAB-23).
- *Devolver el `id` (`bigint`) de `chat.conversation_turn`*: es secuencial y adivinable (cualquiera podría votar los turnos `1`, `2`, `3` de otros clientes). Un UUID aleatorio actúa como capacidad secreta, igual que `conversationId` (ADR-0020).
- *Emitir siempre `turnId` aunque no haya `conversationId`*: rompería `ChatStreamContractTests` (`golden-stream.txt`) y obligaría a persistir en base de datos todos los turnos de pruebas unitarias/contrato que llamaban sin memoria.

---

## R2 · Persistir también los turnos de cortesía (`small_talk`) cuando hay `conversationId`

**Decisión**:
- En `StreamChatUseCase`, cuando `conversation != null` y `answerText` no está en blanco, `finish(...)` guarda el turno en `chat.conversation_turn` **también para los turnos de cortesía (`small_talk`)**, con `delivered_documents = '{}'`.
- `ConversationHistory.searchText` ya filtra los turnos cuya pregunta es cortesía (`!smallTalk.isCourtesy(turn.question())`, LAB-114), así que guardar el turno de cortesía cuando hay `conversationId` no contamina la búsqueda de seguimientos posteriores y además hace que:
  1. Cualquier respuesta completada en la pantalla de chat (con contexto, sin contexto o de cortesía) reciba su `turnId` y pueda valorarse con 👍 o 👎 (FR-001, FR-002).
  2. La memoria de conversación sea uniforme para todos los turnos completados de una conversación.

**Por qué**:
- En LAB-114, `smallTalkTurn` salía antes de leer la memoria, pero `finish(...)` sí se ejecutaba al final del `Flux` (`turn(...).concatWith(Mono.defer(() -> finish(...)))`). Por tanto, los turnos de cortesía con `conversationId` **ya** pasaban por `finish(...)` (`ChatMemoryIntegrationTests.aCourtesyBetweenTheQuestionAndTheFollowUpDoesNotPolluteTheSearch` ya comprueba `storedTurns == 3`). Solo necesitamos pasar a `finish(...)` la lista de documentos entregados (`List<String>`) para que la guarde junto con la pregunta y la respuesta.

---

## R3 · Estructura de `chat.feedback` y relación con `chat.conversation_turn`

**Decisión**:
- La tabla `chat.feedback` vive en el esquema `chat` (`V3__answer_feedback.sql`) y tiene una relación `1:1` con `chat.conversation_turn` mediante `turn_ref bigint NOT NULL UNIQUE REFERENCES conversation_turn(id) ON DELETE CASCADE` (clave foránea **dentro del mismo esquema `chat`**, permitida y verificada por `ModuleSchemasIntegrationTests`, como `chunk -> document` en `retrieval` y `eval_result -> eval_run` en `eval`).
- Además de `turn_ref`, `chat.feedback` guarda:
  - `id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY`
  - `rating text NOT NULL CHECK (rating IN ('UP', 'DOWN'))`
  - `comment text CHECK (comment IS NULL OR (length(btrim(comment)) > 0 AND length(comment) <= 500))`
  - `created_at timestamptz NOT NULL DEFAULT now()`
  - `updated_at timestamptz NOT NULL DEFAULT now()`
  - `promoted_case_id text UNIQUE CHECK (promoted_case_id IS NULL OR promoted_case_id ~ '^G[0-9]{3}$')`
  - `promoted_case_jsonl text CHECK ((promoted_case_id IS NULL) = (promoted_case_jsonl IS NULL))`
  - `promoted_at timestamptz CHECK ((promoted_case_id IS NULL) = (promoted_at IS NULL))`
  - `CHECK (promoted_case_id IS NULL OR rating = 'DOWN')`
- Al consultar `chat.feedback` (para devolver la valoración o listar las respuestas con 👎), el adaptador JDBC del módulo `chat` hace `JOIN chat.conversation_turn t ON t.id = f.turn_ref` dentro de su propio esquema `chat`, obteniendo `turn_id`, `conversation_id`, `question`, `answer`, `delivered_documents` y `trace_id` sin duplicar las cadenas de texto en dos tablas.

**Por qué**:
- **Cero duplicación de textos dentro del esquema `chat`**: la pregunta, la respuesta, los documentos entregados y el `trace_id` ya están en `chat.conversation_turn`; `chat.feedback` referencia la fila de `chat.conversation_turn` con una FK interna del esquema `chat` y añade el voto, el comentario y los datos de promoción.
- **`UNIQUE (turn_ref)` garantiza una sola valoración por turno** (FR-006, SC-003): `INSERT INTO chat.feedback ... ON CONFLICT (turn_ref) DO UPDATE` actualiza `rating`, `comment` y `updated_at` de forma atómica cuando el turno aún no ha sido promovido.
- **Protección de promociones ya hechas**: si `promoted_case_id IS NOT NULL`, la fila ya dio origen a un caso golden `G<NNN>`, por lo que cambiar el voto a `UP` o volver a promoverla a otro caso se rechaza con `409 Conflict` (`SLHD_CHAT_00013`, `FEEDBACK_ALREADY_PROMOTED`).

**Alternativas descartadas**:
- *Copiar `question`, `answer`, `delivered_documents` y `trace_id` también en `chat.feedback`*: duplica datos dentro del mismo esquema `chat` sin necesidad, porque `conversation_turn` y `feedback` pertenecen al mismo módulo y esquema.
- *Permitir múltiples filas de feedback por turno*: un mismo cliente que corrige su clic de 👍 a 👎 o añade un comentario dejaría dos votos contradictorios para la misma respuesta.

---

## R4 · Dónde viven los endpoints de feedback y promoción

**Decisión**:
- Todos los endpoints de feedback viven en el módulo `chat` (`FeedbackController`, bajo `/api/chat/feedback`), porque `chat` es el único dueño del esquema `chat` (ADR-0015):
  1. `PUT /api/chat/feedback/{turnId}` → registra o actualiza la valoración (`rating`: `"UP"` | `"DOWN"`, `comment`: opcional, hasta 500 caracteres tras `strip()`) del turno con ese `turnId`.
  2. `GET /api/chat/feedback?rating=DOWN&promoted=false&limit=100` → lista las valoraciones (por defecto `rating=DOWN`, las más recientes primero), incluyendo el contenido del turno (`turnId`, `question`, `answer`, `deliveredDocuments`, `traceId`), el voto, el comentario, el estado de promoción (`promotedCaseId`, `promotedCaseJsonl`, `promotedAt`) y el siguiente identificador `G<NNN>` sugerido (`suggestedNextCaseId`).
  3. `POST /api/chat/feedback/{turnId}/promote` → valida el caso golden propuesto (`caseId`, `question`, `referenceAnswer`, `expectedDocuments`, `category`), construye su línea JSONL canónica, marca la valoración con 👎 como promovida a ese `G<NNN>` en `chat.feedback` y devuelve la valoración actualizada con `promotedCaseJsonl`.
- Ningún otro módulo accede a `chat.feedback`. En el frontend, la pantalla `/admin-eval` (o la ruta de evaluación) consume estos endpoints para mostrar la sección «Feedback 👎 y promoción a casos golden».

**Por qué**:
- **Respeta estrictamente ADR-0015 y `ModularityTests`**: `chat` es dueño de `chat.conversation_turn` y `chat.feedback`. Ni `eval` ni ningún otro módulo hacen `SELECT` o `UPDATE` sobre `chat.*`, y no hace falta acoplar `eval` con `chat` para esta operación: el frontend llama directamente a `/api/chat/feedback`.
- **`PUT /api/chat/feedback/{turnId}` es semánticamente idempotente**: tanto el primer clic en 👍/👎 como el cambio de voto o la adición del comentario reemplazan el estado de la valoración de ese `turnId`.

**Alternativas descartadas**:
- *Exponer la promoción desde el módulo `eval` leyendo `chat.feedback`*: violaría ADR-0015 (acceso entre esquemas) o exigiría puentear todo por `chat.api` sin aportar ninguna lógica propia de `eval_run`.

---

## R5 · Validación del caso golden al promover y generación de la línea JSONL

**Decisión**:
- `GoldenCaseValidator` (en `chat/application/usecase`, puro y sin dependencias de infraestructura) aplica las mismas reglas por caso de LAB-27 (`eval/src/shoplab_eval/validation.py` y `EvalRunValidator` de LAB-31):
  1. `caseId`: obligatorio, coincide con `^G[0-9]{3}$` (`G001`..`G999`) y no está ya usado por otro feedback promovido en `chat.feedback` (`UNIQUE (promoted_case_id)`).
  2. `category`: una de las 8 categorías del dataset (`payments`, `privacy`, `returns`, `shipping`, `warranty`, `off_topic`, `small_talk`, `injection`).
  3. `question` (tras `strip()`): entre 10 y 300 caracteres para casos de conocimiento (`payments`, `privacy`, `returns`, `shipping`, `warranty`, `off_topic`), o entre 2 y 300 caracteres para conversación (`small_talk`, `injection`).
  4. `referenceAnswer` (tras `strip()`): entre 20 y 800 caracteres, y **distinta** de copiar a ciegas una respuesta vacía; además, si `expectedDocuments` es vacía (caso sin respuesta), `referenceAnswer` **no puede contener cifras (`0-9`)** (regla `G-NOANSWER-DIGITS` de LAB-27).
  5. `expectedDocuments`: lista de 0 a 2 identificadores de documento válidos (`^[a-z0-9]+(?:-[a-z0-9]+)*$`), sin duplicados. Si `category` es `off_topic`, `small_talk` o `injection`, `expectedDocuments` debe estar vacía (`G-CATEGORY-TOPIC`). Si `expectedDocuments` no está vacía y `category` es uno de los 5 temas, cada documento debe empezar por el prefijo de ese tema (`<category>-...`, como todos los documentos de `data/kb`: `payments-01-...`, `returns-01-...`, etc.; regla `G-CATEGORY-TOPIC`).
  6. Ausencia de PII (`G-PII`): ni `question` ni `referenceAnswer` pueden contener una secuencia de 8 o más dígitos seguidos (`\d{8,}`) ni un correo electrónico cuyo dominio no sea `@shoplab.example`.
- Si alguna regla falla, lanza `InvalidGoldenCaseException` (implementa `PublicDetail`, código `SLHD_CHAT_00014`, HTTP 422) con el código de la regla (`G-ID`, `G-CATEGORY`, `G-QUESTION-LENGTH`, `G-ANSWER-LENGTH`, `G-DOC-COUNT`, `G-CATEGORY-TOPIC`, `G-NOANSWER-DIGITS`, `G-PII`) y la explicación, sin modificar `chat.feedback`.
- Si todo es válido, serializa la línea JSONL canónica con las 5 claves en el orden exacto de `eval/golden.jsonl`:
  ```json
  {"id": "G121", "question": "...", "reference_answer": "...", "expected_documents": ["..."], "category": "..."}
  ```
- **Sugerencia automática del siguiente `id` (`suggestedNextCaseId`)**: el dataset actual llega hasta `G120` (`BASE_LAST_GOLDEN_NUMBER = 120`, configurable con `shoplab.chat.feedback.base-golden-case-number`, por defecto `120`). El backend calcula el máximo entre ese número base y el mayor `G<NNN>` ya promovido en `chat.feedback`, y sugiere el siguiente (`G121`, `G122`, …).
- **Margen en `eval/` (`GoldenRules.max_cases`)**: en `eval/src/shoplab_eval/constants.py`, `GoldenRules.max_cases` está en `100` (con 96 casos de conocimiento actuales, lo que solo permitiría 4 promociones antes de fallar `G-COUNT`). Se mantiene la validación pero se amplía `max_cases` de `100` a `150` (actualizando la prueba unitaria de ese tope en `eval/tests/`) para que los casos promovidos desde `chat.feedback` puedan añadirse a `eval/golden.jsonl` sin romper `validate_golden` tras el cuarto caso.

**Por qué**:
- Garantiza que cualquier línea JSONL generada desde `admin-eval` cumpla por construcción todas las reglas individuales de `uv run python -m shoplab_eval validate`.
- Al guardar `promoted_case_jsonl` en `chat.feedback`, el administrador puede volver a copiar o descargar la línea JSONL en cualquier momento desde `admin-eval` (US3 escenario 5).

---

## R6 · Interfaz en la pantalla de chat (`/chat`) y en `admin-eval`

**Decisión**:
1. **En la pantalla de chat (`frontend/src/app/features/chat/`)**:
   - `ChatMessage` añade `turnId?: string` (rellenado al llegar el evento `done` con `turnId`) y `feedback?: MessageFeedbackState` (`rating?: 'UP' | 'DOWN'`, `comment: string`, `commentOpen: boolean`, `saving: boolean`, `saved: boolean`, `error?: string`).
   - En `message-bubble`: cuando `!isUser() && message().status === 'complete' && message().turnId`, se muestra la barra de valoración (`app-feedback-bar` o controles integrados en la burbuja):
     - Botón 👍 (`aria-label="Respuesta útil"`, `[attr.aria-pressed]="feedback?.rating === 'UP'"`).
     - Botón 👎 (`aria-label="Respuesta no útil"`, `[attr.aria-pressed]="feedback?.rating === 'DOWN'"`).
     - Botón «Añadir comentario» / «Editar comentario» (o apertura automática del campo de comentario al pulsar 👎, permitiendo también enviarlo de inmediato al pulsar 👍/👎 y añadir el comentario opcional después).
     - Al pulsar 👍 o 👎, se envía de inmediato `PUT /api/chat/feedback/{turnId}` con el voto elegido (y el comentario actual si ya lo había), cumpliendo SC-001 («valorar con un solo clic»). Además, el cliente puede abrir el campo de comentario opcional (hasta 500 caracteres), escribirlo y pulsar «Guardar comentario» para actualizar la fila con el comentario.
2. **En la pantalla de evaluación (`/admin-eval`)**:
   - La ruta `/admin-eval` y el componente `AdminEval` (`frontend/src/app/features/eval/admin-eval.{ts,html,scss}`) ya están en `main` (LAB-32).
   - Se añade un componente standalone dedicado (`frontend/src/app/features/eval/feedback-promotion/`) y se integra `<app-feedback-promotion />` al final de `AdminEval` (`admin-eval.html` y `admin-eval.ts`).
   - Para no disparar una petición HTTP extra no esperada en las pruebas existentes de `AdminEval` (`admin-eval.spec.ts`, `admin-eval.detail.spec.ts`, `admin-eval.compare.spec.ts` y `a11y.spec.ts` de LAB-32, que usan `http.verify()` en `afterEach`), la sección «Feedback 👎 de clientes» muestra inicialmente un encabezado y el botón **«Cargar respuestas con 👎»** (o carga perezosa bajo demanda al desplegar/activar la sección). Al pulsarlo, llama a `GET /api/chat/feedback?rating=DOWN` y muestra el listado, el filtro y el formulario de promoción.

**Por qué**:
- **Un solo clic para 👍/👎 y comentario opcional sin fricción**: si el cliente solo quiere pulsar 👍 o 👎, queda guardado al instante; si quiere explicar por qué (especialmente en 👎), abre o edita el comentario y lo guarda sobre la misma fila (`PUT`).
- **Cero regresiones en la batería de pruebas de LAB-32**: con carga bajo demanda al abrir la sección de feedback en `admin-eval`, el 100 % de los tests de `AdminEval` ya fusionados en `main` pasan sin tocar una sola expectativa de `HttpTestingController`.

---

## R7 · Observabilidad y privacidad

**Decisión**:
- Dos contadores en `MicrometerChatMetrics` (`ChatMetricsPort`):
  - `shoplab.chat.feedback` con etiqueta `rating` (`up` | `down`): se incrementa cada vez que se guarda una valoración en `chat.feedback`.
  - `shoplab.chat.feedback.promoted` (sin etiquetas): se incrementa cada vez que una valoración con 👎 se promueve a un caso golden.
- Logs estructurados en `SubmitFeedbackUseCase` y `PromoteFeedbackUseCase`:
  - `LOG.info("Chat feedback recorded: requestId={}, turnId={}, rating={}, hasComment={}", ...)`
  - `LOG.info("Chat feedback promoted: requestId={}, turnId={}, caseId={}", ...)`
  - **Nunca** se incluye `question`, `answer` ni `comment` en logs, excepciones ni métricas (Constitución VI).
- En `ChatStreamContractTests`, los contadores de feedback solo se registran cuando se invoca el caso de uso de feedback (o con `Counter.builder(...).register(registry)` bajo demanda, igual que `recordTurn`, `recordContext` y `recordCitations` en `MicrometerChatMetrics`), de modo que la exposición base de `/actuator/prometheus` durante `ChatStreamContractTests` tampoco se altera.
