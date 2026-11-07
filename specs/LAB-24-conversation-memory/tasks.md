---

description: "Task list for LAB-24 - Memoria de la conversación"
---

# Tasks: Memoria de la conversación

**Input**: Design documents from `/specs/LAB-24-conversation-memory/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`chat-request.md`), quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas antes o junto con la implementación. En cada historia
las pruebas van primero y deben fallar antes de implementar. Backend: Ollama simulado con WireMock (`OllamaStubs`),
PostgreSQL real (`@PostgresTest`) y `DeterministicEmbeddingPort`. Frontend: Vitest (`ng test`). Ninguna prueba automática
necesita Ollama real.

**Organization**: Tareas agrupadas por historia de usuario. US1 (seguimiento comprendido, backend) y US2 (persistencia,
aislamiento y errores) son P1 y forman el MVP del backend; US3 (límites) y US4 (frontend) son P2; US5 (BlockHound y
observabilidad) es P3. El criterio «BlockHound en verde» se vigila en toda la suite desde la fase 2, no solo en US5.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US5)
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Main**: `backend/src/main/java/com/shoplab/helpdesk/` (en las tareas, `main/`)
- **Tests**: `backend/src/test/java/com/shoplab/helpdesk/` (en las tareas, `test/`)
- **Resources**: `backend/src/main/resources/` (en las tareas, `res/`)
- **Frontend**: `frontend/src/app/` (en las tareas, `fe/`); las pruebas `*.spec.ts` van junto al archivo
- Java: líneas ≤ 121 caracteres, llaves en todos los bloques, identificadores en inglés, constantes en `@UtilityClass` de
  `domain/model/constants`, sin `block()` en el event loop, JDBC solo en `infrastructure` y siempre con `BlockingCalls`,
  Spring AI solo en `infrastructure`, `application` sin Spring AI ni JDBC, `chat` no toca tablas de otros módulos
- Frontend: texto de interfaz en español; nada nuevo usa `[innerHTML]`
- Sin texto de mensajes, respuestas ni fragmentos en logs ni métricas, y sin `conversationId` como etiqueta de métrica
  (backend), ni en `console` (frontend)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base verde y decisión de diseño confirmada antes de tocar nada

- [X] T001 Ejecutar la línea base del backend: `cd backend && ./mvnw test -Dskip.srcclr=true --no-transfer-progress` debe
  pasar en la rama; anotar fallos previos si los hay
- [X] T002 Ejecutar la línea base del frontend: `cd frontend && npm test -- --watch=false` y `npm run lint` deben pasar;
  anotar fallos previos si los hay
- [X] T003 Decisión de [research.md](research.md) R1 confirmada por el responsable (2026-11-07): puerto y adaptador JDBC
  propios en lugar de `JdbcChatMemoryRepository` de Spring AI. Anotada en research.md R1, plan.md y spec.md
  (Assumptions)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: identificador, errores, configuración, tabla, ventana de historial, consulta de seguimiento y adaptador JDBC
que necesitan todas las historias

**CRITICAL**: ninguna historia puede empezar hasta terminar esta fase

- [X] T004 [P] Test de `ConversationId` en `test/chat/domain/model/ConversationIdTests.java`: `optional(null)` → vacío; un
  UUID canónico de 36 caracteres es válido y se normaliza a minúsculas (`3F1B6C52-…` y `3f1b6c52-…` son el mismo
  identificador); cadena vacía, solo espacios, 35 o 37 caracteres, sin guiones, con llaves, con prefijo `urn:uuid:` y texto
  que no es UUID lanzan `INVALID_CONVERSATION_ID`; el mensaje de la excepción no contiene el valor recibido
- [X] T005 Tipos de dominio y errores: `main/chat/domain/model/ConversationId.java` (`optional(String)` devuelve
  `Optional<ConversationId>`; `value()` es el UUID canónico en minúsculas), `main/chat/domain/model/ConversationTurn.java`
  (`question` y `answer`, ambos no vacíos), `main/chat/domain/model/enums/MemoryOperation.java` (`READ`, `WRITE`,
  `tagValue()` → `read` / `write`), `main/chat/domain/exception/ConversationMemoryException.java` (mismo estilo que
  `KnowledgeRetrievalException`, mapea a `CHAT_MEMORY_UNAVAILABLE`) y en `main/shared/exception/ResponseCodeEnum.java`:
  `INVALID_CONVERSATION_ID("SLHD_CHAT_00005", HttpStatus.BAD_REQUEST, "The conversation id must be a UUID")` y
  `CHAT_MEMORY_UNAVAILABLE("SLHD_CHAT_00006", HttpStatus.SERVICE_UNAVAILABLE, "The conversation memory is not available")`
- [X] T006 [P] Test de `MemoryProperties` en `test/chat/config/MemoryPropertiesTests.java`: valores por defecto
  (`max-turns` = 5, `max-history-characters` = 4000, `retrieval-turns` = 1); `max-turns` fuera de 0–50,
  `max-history-characters` < 200 y `retrieval-turns` fuera de 0–50 impiden arrancar con un mensaje que nombra la
  clave; `max-turns = 0` con `retrieval-turns` por defecto arranca (la consulta efectiva usa `min(retrieval-turns,
  max-turns)`); enlace desde `application.yml` con las variables `SHOPLAB_CHAT_MEMORY_MAX_TURNS`,
  `SHOPLAB_CHAT_MEMORY_MAX_HISTORY_CHARACTERS` y `SHOPLAB_CHAT_MEMORY_RETRIEVAL_TURNS`
- [X] T007 Implementar `main/chat/config/MemoryProperties.java` (`@ConfigurationProperties("shoplab.chat.memory")`, mismo
  estilo que `RagProperties`: record con valores por defecto y validación en el constructor compacto), registrarlo en
  `main/chat/config/ChatConfig.java` (`@EnableConfigurationProperties`) y añadir las tres claves con sus variables de
  entorno a `res/application.yml` bajo `shoplab.chat.memory`
- [X] T008 [P] Test del esquema: crear `test/schema/ChatSchemaIntegrationTests.java` con el estilo de
  `RetrievalSchemaIntegrationTests` (`@SpringBootTest` sin servidor + `@PostgresTest`) para comprobar que
  `chat.conversation_turn` existe con las columnas y tipos de [data-model.md](data-model.md), que `question` y `answer`
  rechazan la cadena vacía (`CHECK (length(question) > 0)`, `CHECK (length(answer) > 0)`), que `conversation_id`,
  `question` y `answer` rechazan `NULL`, y que existe el índice `conversation_turn_conversation_idx` sobre
  `(conversation_id, id DESC)`; `SchemaIsolationTests` sigue en verde
- [X] T009 Crear `res/db/migration/chat/V2__conversation_turns.sql`: tabla `conversation_turn` (`id bigint GENERATED ALWAYS
  AS IDENTITY PRIMARY KEY`, `conversation_id uuid NOT NULL`, `question text NOT NULL CHECK (length(question) > 0)`,
  `answer text NOT NULL CHECK (length(answer) > 0)`, `created_at timestamptz NOT NULL DEFAULT now()`) e índice
  `conversation_turn_conversation_idx ON conversation_turn (conversation_id, id DESC)`; comentario de cabecera como en
  `retrieval/V4` (qué guarda, que solo `chat` la lee y que contiene texto libre del cliente); sin referencias a otros esquemas
- [X] T010 [P] Test de `HistoryWindow` en `test/chat/application/usecase/HistoryWindowTests.java`: toma los `maxTurns` más
  recientes en orden cronológico; descarta el turno **más antiguo entero** mientras la suma de caracteres de pregunta +
  respuesta supere el presupuesto; si ni el último turno cabe devuelve vacío; nunca recorta un texto por la mitad; quita
  de las respuestas las marcas `[1]`, `[1][2]`, `[1, 2]`, `[1-3]` y los espacios que dejan, pero no `[opcional]` ni `[2024]`
  en la pregunta; no toca las preguntas; `maxTurns = 0` devuelve vacío; lista vacía devuelve vacío; `trimmed` es `true` si se descartó algún turno por cantidad
  (se le pasan `maxTurns + 1`) o por caracteres, y `false` en otro caso
- [X] T011 Implementar `main/chat/application/usecase/HistoryWindow.java` (lógica pura, sin Spring AI ni E/S, clase
  `@Component` sin estado como `ContextSelector`; devuelve `HistorySelection(turns, trimmed)`, registro en
  `main/chat/domain/model/HistorySelection.java`; expresión de marcas `\[\d+(?:\s*[,-]\s*\d+)*\]`)
- [X] T012 [P] Test de `FollowUpQuery` en `test/chat/application/usecase/FollowUpQueryTests.java` (firma
  `compose(message, history, retrievalTurns, maxCharacters)`): sin historial devuelve
  el mensaje tal cual; con `retrievalTurns = 0` también; con 1 antepone la pregunta del último turno separada por salto
  de línea y el mensaje al final; con 2 antepone las dos últimas en orden cronológico; el resultado nunca supera `maxCharacters`
  y, si lo supera, descarta primero las preguntas más antiguas pero conserva siempre el mensaje actual entero (aunque él
  solo supere el tope); no usa las respuestas del historial
- [X] T013 Implementar `main/chat/application/usecase/FollowUpQuery.java` (pura, `@Component`) y la constante
  `FOLLOW_UP_QUERY_MAX_CHARACTERS = 2000` en `main/chat/domain/model/constants/ChatConstants.java`
- [X] T014 [P] Test del adaptador JDBC en `test/chat/infrastructure/out/persistence/JdbcConversationMemoryAdapterTests.java`
  (`@PostgresTest`): `saveTurn` y `recent` devuelven los turnos en orden cronológico; `recent(id, n)` devuelve como máximo
  los `n` más recientes; dos identificadores no se mezclan; `recent` de un identificador sin turnos devuelve lista vacía;
  texto con comillas, saltos de línea, `%` y Unicode se guarda y se lee idéntico; un fallo de la base de datos (pool
  cerrado o tabla ausente) se traduce a `ConversationMemoryException` sin el texto del turno en el mensaje; las
  llamadas no bloquean el event loop (se suscribe desde un hilo `parallel` con BlockHound activo)
- [X] T015 Puerto y adaptador: `main/chat/application/port/out/ConversationMemoryPort.java`
  (`Mono<List<ConversationTurn>> recent(ConversationId id, int limit)`, `Mono<Void> saveTurn(ConversationId id,
  ConversationTurn turn)`; cualquier fallo es `ConversationMemoryException`) y
  `main/chat/infrastructure/out/persistence/JdbcConversationMemoryAdapter.java` (`@Component`,
  `@DependsOn("moduleSchemaMigrator")`, `JdbcTemplate`, `BlockingCalls.call/run`, consultas de
  [data-model.md](data-model.md) con la tabla cualificada `chat.conversation_turn`, `DataAccessException` →
  `ConversationMemoryException`; sin registrar texto)

**Checkpoint**: la memoria se puede guardar y leer, existen la ventana, la consulta y la configuración; las historias pueden empezar

---

## Phase 3: User Story 1 - Preguntas de seguimiento que se entienden (Priority: P1) 🎯 MVP

**Goal**: con un `conversationId`, la respuesta tiene en cuenta los turnos anteriores y la búsqueda de contexto recupera
los documentos del tema.

**Independent Test**: [quickstart.md](quickstart.md) §2 — «¿cuánto tarda el envío estándar?» y «¿y si es internacional?» con
el mismo identificador: la segunda respuesta trata envíos internacionales y sus `sources` son del documento de envíos.

### Tests for User Story 1

- [X] T016 [P] [US1] Ampliar `test/chat/application/usecase/StreamChatUseCaseTests.java` (mocks de los puertos, `StepVerifier`):
  con `conversationId` y dos turnos guardados, la búsqueda de contexto recibe `FollowUpQuery.compose(...)` y el modelo
  recibe el historial recortado y el mensaje actual **sin** componer; el turno se guarda con la pregunta original y la
  respuesta completa (tokens concatenados) **antes** de emitir `done` (verificar el orden con un guardado retardado); la
  respuesta fija de «sin información» también se guarda; una respuesta de texto vacío no se guarda; la primera pregunta
  de un identificador nuevo no lleva historial y se comporta como antes (mismos eventos y mismas llamadas que sin id);
  los `[n]` de la respuesta guardada se conservan tal cual
- [X] T017 [P] [US1] Ampliar `test/chat/infrastructure/out/ollama/OllamaChatAdapterTests.java`: el `Prompt` enviado al
  modelo tiene el orden `SystemMessage`, pares `UserMessage`/`AssistantMessage` del historial (más antiguo primero) y un
  `UserMessage` final con la pregunta y el contexto; el historial nunca va en el mensaje del sistema; sin historial el
  `Prompt` es idéntico al de LAB-23; las herramientas (`ToolCallbackComposer`) siguen aplicándose
- [X] T018 [P] [US1] Crear `test/chat/ChatMemoryIntegrationTests.java` (`@PostgresTest`, mismo andamiaje que
  `ChatCitationsIntegrationTests`: WireMock para Ollama, `DeterministicEmbeddingPort`, documentos de envíos y de
  devoluciones indexados): escenario del seguimiento — tras «¿cuánto tarda el envío estándar?» la segunda petición con el
  mismo `conversationId` («¿y si es internacional?») envía a Ollama el historial (la pregunta y la respuesta previas
  como mensajes `user`/`assistant`, sin marcas `[n]` en la respuesta) y su evento `sources` corresponde al documento de
  envíos; el cuerpo enviado a Ollama no contiene en el mensaje del sistema nada del historial

### Implementation for User Story 1

- [X] T019 [US1] Pasar el historial por el puerto del modelo: `main/chat/application/port/out/ChatModelPort.java`
  (`streamAnswer(String message, List<ConversationTurn> history, List<ContextFragment> context)`) y
  `main/chat/infrastructure/out/ollama/OllamaChatAdapter.java` (mensajes del historial entre el `SystemMessage` y el último
  `UserMessage`). Ajustar los llamantes y dobles de prueba existentes (`ChatModelPort` en `test/`)
- [X] T020 [US1] Contrato de entrada: `main/chat/domain/model/StreamChatCommand.java` (+ `String conversationId`, opcional),
  `main/chat/infrastructure/in/rest/dto/ChatRequest.java` (+ `conversationId`) y
  `main/chat/infrastructure/in/rest/ChatController.java` (copia el campo al comando; sin lógica). Ajustar
  `test/chat/ChatStreamContractTests.java` y las pruebas de controlador para el nuevo constructor sin cambiar sus
  expectativas
- [X] T021 [US1] Frase de historial en el prompt de sistema por defecto: `main/chat/domain/model/constants/ChatConstants.java`
  (`DEFAULT_SYSTEM_PROMPT` añade: los mensajes anteriores son la conversación; los datos solo salen del contexto del
  último mensaje; no se citan números de mensajes anteriores) y ampliar `test/chat/config/ChatPropertiesTests.java`
  (el valor por defecto contiene la frase; un `SHOPLAB_CHAT_SYSTEM_PROMPT` propio la sustituye por completo)
- [X] T022 [US1] Implementar el flujo en `main/chat/application/usecase/StreamChatUseCase.java`: inyectar
  `ConversationMemoryPort`, `HistoryWindow`, `FollowUpQuery` y `MemoryProperties`; sin `conversationId` o con
  `max-turns = 0` el flujo es el de LAB-23; con memoria: leer `recent(id, max-turns + 1)`, seleccionar con `HistoryWindow` (que
  devuelve también `trimmed`), buscar contexto con
  `FollowUpQuery.compose(message, history, min(retrieval-turns, max-turns), FOLLOW_UP_QUERY_MAX_CHARACTERS)`, llamar al modelo con el historial y el
  mensaje original, acumular los tokens y, cuando el flujo del modelo completa y el texto no está en blanco, ejecutar
  `saveTurn` y solo entonces emitir `done` (`concatWith(Mono.defer(...))`); nada se guarda si el flujo falla o se cancela

**Checkpoint**: US1 funciona con `curl` (quickstart §2); el seguimiento se entiende y las pruebas de LAB-12/22/23 siguen en verde

---

## Phase 4: User Story 2 - La conversación se conserva y se mantiene aislada (Priority: P1)

**Goal**: el historial se identifica con un UUID, sobrevive al reinicio, no se mezcla entre conversaciones y los errores
siguen el contrato del chat.

**Independent Test**: [quickstart.md](quickstart.md) §3 y §4 — otro identificador no entiende el seguimiento; un
identificador inválido da 400 `SLHD_CHAT_00005`; tras reiniciar el backend el seguimiento sigue entendiéndose.

### Tests for User Story 2

- [X] T023 [P] [US2] Ampliar `test/chat/application/usecase/StreamChatUseCaseTests.java`: `conversationId` inválido (`""`,
  `"  "`, `"no-es-un-uuid"`) lanza `INVALID_CONVERSATION_ID` antes de llamar a la memoria, a la búsqueda de contexto y al
  modelo; un fallo de `recent` hace fallar el flujo con `CHAT_MEMORY_UNAVAILABLE` **antes** del primer evento y sin llamar
  al modelo ni responder «como sin historial»; un fallo de `saveTurn` emite un último evento `error`
  (`SLHD_CHAT_00006`) y **no** `done`; un fallo del modelo antes o a mitad, y la cancelación del cliente, no llaman a
  `saveTurn`; dos peticiones con identificadores distintos usan solo su propio historial (el doble de la memoria recibe
  el identificador correcto)
- [X] T024 [P] [US2] Ampliar `test/chat/ChatStreamContractTests.java`: identificador inválido → HTTP 400 con
  `SLHD_CHAT_00005` y el cuerpo de error estándar sin el valor recibido; memoria no disponible al leer → HTTP 503 con
  `SLHD_CHAT_00006`; sin `conversationId` el flujo y los eventos son byte a byte los de LAB-23; el campo `conversationId`
  es opcional y `null` equivale a ausente; el orden de los eventos con memoria es `sources`?, `token`\*, `done`
- [X] T025 [P] [US2] Ampliar `test/chat/ChatMemoryIntegrationTests.java`: aislamiento (dos identificadores con temas
  distintos: cada seguimiento recibe solo su historial en el cuerpo enviado a Ollama); persistencia (tras `saveTurn` desde
  una instancia nueva del adaptador sobre la misma base de datos, un contexto de aplicación nuevo lee el historial);
  respuesta fallida a mitad de flujo (WireMock con fallo) y petición cancelada no dejan filas en `chat.conversation_turn`;
  dos peticiones simultáneas de la misma conversación dejan dos turnos enteros (ninguno partido ni duplicado) y un
  tercer mensaje ve ambos; memoria desactivada con `max-turns = 0` no lee ni escribe aunque llegue el identificador
- [X] T026 [P] [US2] Ampliar `test/shared/exception/ResponseCodeEnumTests.java` y
  `test/shared/infrastructure/in/rest/handler/GlobalExceptionHandlerTests.java`: `SLHD_CHAT_00005` (400) y
  `SLHD_CHAT_00006` (503) existen, son únicos y se traducen al cuerpo de error estándar sin detalles internos

### Implementation for User Story 2

- [X] T027 [US2] Validación y errores en `main/chat/application/usecase/StreamChatUseCase.java`: validar `conversationId`
  con `ConversationId.optional(...)` dentro de `validate(command)` (antes de cualquier E/S); traducir
  `ConversationMemoryException` de lectura en el error del flujo (HTTP 503) y de escritura en el evento `error` con
  `CHAT_MEMORY_UNAVAILABLE` en lugar de `done`; registrar con `requestId`, operación y código, sin texto ni
  identificador; `GlobalExceptionHandler` no necesita cambios (error tipado `HelpdeskException`)

**Checkpoint**: US1 y US2 completas; el backend entrega la memoria con aislamiento y los errores del contrato

---

## Phase 5: User Story 3 - Historial acotado y respuesta sin degradarse (Priority: P2)

**Goal**: las conversaciones largas siguen respondiendo; el modelo solo recibe lo más reciente dentro de los límites.

**Independent Test**: una conversación de más turnos que `max-turns` responde, el primer token sigue dentro de 5 s y el
modelo ya no recibe el turno más antiguo; los turnos antiguos siguen en la tabla.

### Tests for User Story 3

- [X] T028 [P] [US3] Ampliar `test/chat/application/usecase/StreamChatUseCaseTests.java`: con más turnos que `max-turns` el
  modelo recibe solo los `max-turns` más recientes; con turnos que superan `max-history-characters` se descartan los más
  antiguos enteros y nunca el mensaje actual ni los fragmentos del contexto ya seleccionados; la lectura pide
  `max-turns + 1` turnos para saber si hubo recorte; `retrieval-turns = 0` busca solo con el mensaje actual
- [X] T029 [P] [US3] Ampliar `test/chat/ChatMemoryIntegrationTests.java`: conversación de `max-turns + 2` turnos — el
  cuerpo enviado a Ollama contiene exactamente los `max-turns` últimos y ninguno anterior, y la tabla conserva todos; un
  turno muy largo que no cabe en `max-history-characters` deja el historial vacío y la respuesta se genera igualmente;
  cambiar `shoplab.chat.memory.max-turns` por propiedad de entorno se aplica al crear el contexto

### Implementation for User Story 3

- [X] T030 [US3] En `main/chat/application/usecase/StreamChatUseCase.java` escribir un único registro INFO por petición
  con memoria: `requestId`, `turns`, `characters`, `trimmed` (solo recuentos, nunca texto); los límites ya los aplica
  `HistoryWindow` desde T022

**Checkpoint**: el historial está acotado y verificado de extremo a extremo

---

## Phase 6: User Story 4 - El chat envía el identificador y permite empezar de nuevo (Priority: P2)

**Goal**: la pantalla mantiene un identificador por conversación, lo envía en cada mensaje y ofrece «Nueva conversación».

**Independent Test**: [quickstart.md](quickstart.md) §5 — pregunta + seguimiento se entiende; «Nueva conversación» y el
mismo seguimiento ya no se entiende; recargar crea una conversación nueva.

### Tests for User Story 4

- [X] T031 [P] [US4] Ampliar `fe/features/chat/chat-stream.client.spec.ts`: el cuerpo del `POST /api/chat` lleva
  `conversationId` además de `message`; sin identificador (llamada antigua) el cuerpo no lo incluye
- [X] T032 [P] [US4] Ampliar `fe/features/chat/chat-store.spec.ts`: el almacén crea un UUID (`crypto.randomUUID`, simulado
  en la prueba) al construirse y lo envía en cada mensaje de la misma conversación; `newConversation()` cancela el flujo en
  curso, vacía los mensajes, genera un UUID distinto y los eventos tardíos del flujo cancelado no se añaden a la
  conversación nueva; con la conversación vacía `newConversation()` no cambia el identificador; los errores
  `SLHD_CHAT_00005` y `SLHD_CHAT_00006` se muestran con el tratamiento de error existente (`chat-error.ts`); un
  evento `error` `SLHD_CHAT_00006` recibido **después** de todos los tokens conserva el texto ya recibido y marca el
  mensaje como fallido (como el error a mitad de flujo de LAB-12); dos instancias del almacén (recarga de la página)
  generan identificadores distintos
- [X] T033 [P] [US4] Ampliar `fe/features/chat/chat.spec.ts`: existe el botón «Nueva conversación» con nombre accesible;
  está deshabilitado con la conversación vacía; al pulsarlo (clic y teclado Enter/Espacio) invoca `newConversation()` y la
  vista queda vacía y con el foco en el campo de mensaje; no pierde el texto que el cliente esté escribiendo

### Implementation for User Story 4

- [X] T034 [US4] `fe/features/chat/chat-stream.client.ts`: aceptar `conversationId` y enviarlo en el cuerpo
- [X] T035 [US4] `fe/features/chat/chat-store.ts`: UUID por conversación (`crypto.randomUUID()`), envío con cada mensaje y
  `newConversation()` (cancelar el flujo en curso con el mismo mecanismo que ya usa el almacén, vaciar mensajes, nuevo UUID)
- [X] T036 [US4] `fe/features/chat/chat.ts`, `chat.html` y `chat.scss`: botón «Nueva conversación» en el encabezado de la
  pantalla, con `aria-label`, deshabilitado si no hay mensajes, operable con teclado y legible en móvil; texto en español

**Checkpoint**: la pantalla usa la memoria de extremo a extremo

---

## Phase 7: User Story 5 - Memoria que no bloquea ni filtra datos (Priority: P3)

**Goal**: ninguna llamada de la memoria bloquea el event loop; métricas y registros sin texto ni identificadores de cliente.

**Independent Test**: la suite completa pasa con BlockHound y [quickstart.md](quickstart.md) §6 muestra las métricas sin
etiquetas con identificadores.

### Tests for User Story 5

- [X] T037 [P] [US5] Crear `test/blockhound/ChatMemoryBlockHoundTests.java` (`@PostgresTest`): una petición completa por
  `WebTestClient` con memoria (lectura y escritura) no produce ninguna detección de BlockHound; la lectura y la escritura
  suscritas desde un hilo del event loop pasan porque el adaptador usa `BlockingCalls`; la prueba negativa existente de
  LAB-111 sigue demostrando que la detección está viva
- [X] T038 [P] [US5] Ejecutar `test/architecture/ReactiveArchitectureTests.java` y `ModuleArchitectureTests.java` y
  confirmar que `JdbcConversationMemoryAdapter` queda cubierto por las reglas por capa de
  `ReactiveArchitectureRules` (`Schedulers` solo en infraestructura, JDBC detrás de un puerto); las reglas no listan
  paquetes concretos, así que no se espera cambio. Si una regla no lo cubre, ampliarla y añadir el ejemplo negativo
  en `test/architecture/violations/`
- [X] T039 [P] [US5] Ampliar `test/chat/infrastructure/out/metrics/MicrometerChatMetricsTests.java`:
  `shoplab.chat.history.messages` (resumen) registra los mensajes entregados al modelo, `shoplab.chat.history.trimmed`
  (contador) cuenta las peticiones con recorte y `shoplab.chat.memory.failures` cuenta fallos con la etiqueta
  `operation` = `read` | `write`; ninguna de las tres lleva otra etiqueta
- [X] T040 [P] [US5] Ampliar `test/chat/ChatMemoryIntegrationTests.java` y `StreamChatUseCaseTests.java`: tras varias
  conversaciones, `/actuator/prometheus` no contiene el `conversationId` ni texto de mensajes; la captura de logs de la
  petición (appender de prueba) no contiene el texto de la pregunta, de la respuesta ni el identificador; un fallo de la
  memoria queda registrado con `requestId` y operación

### Implementation for User Story 5

- [X] T041 [US5] Métricas: `main/chat/application/port/out/ChatMetricsPort.java` (`recordHistory(int messages, boolean
  trimmed)`, `recordMemoryFailure(MemoryOperation operation)`) y
  `main/chat/infrastructure/out/metrics/MicrometerChatMetrics.java` (`shoplab.chat.history.messages`,
  `shoplab.chat.history.trimmed`, `shoplab.chat.memory.failures{operation}`); constantes de nombre en
  `main/chat/domain/model/constants/ChatConstants.java`
- [X] T042 [US5] Registrar las métricas desde `main/chat/application/usecase/StreamChatUseCase.java` (historial entregado y
  recorte en cada petición con memoria; fallo de lectura o escritura) sin añadir texto ni identificador a los registros

**Checkpoint**: las cinco historias completas

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: decisión documentada, documentación, verificación completa

- [X] T043 [P] Crear `docs/adr/0020-memoria-de-conversacion-en-tabla-propia.md` con la plantilla `docs/adr/0000-plantilla.md`
  (tabla `chat.conversation_turn` con una fila por turno, puerto propio frente a `JdbcChatMemoryRepository`, guardado antes
  de `done`, consulta de búsqueda compuesta frente a reformulación con el modelo, identificador generado por el cliente
  como capacidad secreta; ver research R1–R4, R7) y añadir la fila al índice en `docs/adr/README.md`
- [X] T044 [P] Actualizar `docs/chat-streaming.md` (campo `conversationId`, orden `done` tras guardar, errores
  `SLHD_CHAT_00005`/`00006`, métricas, variables de entorno de `shoplab.chat.memory`, y sustituir el aviso de que el chat
  no tiene memoria en «Alcance actual») y `docs/modulos.md` (tabla `chat.conversation_turn`, texto libre del cliente, sin
  retención todavía) y el apartado del chat de `README.md` (la conversación del servidor se identifica con un UUID; la
  pantalla visible sigue sin persistirse)
- [X] T045 Generar `specs/LAB-24-conversation-memory/requests.http` (regla `http test requests`): secuencia pregunta →
  seguimiento con el mismo `conversationId` (variable declarada al inicio), el mismo seguimiento con otro identificador
  (no se entiende), identificador inválido (400 `SLHD_CHAT_00005`), sin identificador (comportamiento anterior),
  identificador en mayúsculas equivalente al de minúsculas, y una conversación más larga que `max-turns` si es alcanzable;
  `baseUrl` = `http://localhost:8080`; listar en un comentario superior lo no alcanzable por HTTP («Not covered here»:
  memoria caída, límites de caracteres, métricas, «Nueva conversación»)
- [X] T046 Backend completo: `cd backend && ./mvnw verify -Dskip.srcclr=true --no-transfer-progress` (Checkstyle, PMD,
  Modulith/ArchUnit/BlockHound, JaCoCo ≥ 80 %); corregir lo que falle sin tocar contratos de LAB-12, LAB-22 ni LAB-23
- [X] T047 Frontend completo: `cd frontend && npm run lint && npm test -- --watch=false && npm run build` sin errores ni
  avisos nuevos
- [X] T048 (PARCIAL: hecho lo comprobable por API, ver validation.md; falta la revisión manual de la pantalla con teclado, lector de pantalla y móvil) Verificación manual con Ollama real según [quickstart.md](quickstart.md) §2–§6: 10 pares «pregunta +
  seguimiento» (SC-001 ≥ 8 de 10; SC-002 ≥ 80 % recuperan el documento del tema), reinicio del backend (SC-004), primer
  token con el historial al máximo (SC-005 < 5 s), revisión de la pantalla con teclado y móvil, y registros sin texto
  (SC-008); anotar resultados y capturas en `specs/LAB-24-conversation-memory/validation.md`
- [X] T049 Listar las propiedades nuevas con la skill `new-properties` (`shoplab.chat.memory.*` con sus variables de
  entorno) para pegarlas en el yml del entorno, y comprobar si `infra/` o `../shoplab-infra` necesitan algún valor
  (probablemente ninguno: todas tienen valor por defecto); si hace falta, se hace dentro de la historia

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T003 (decisión R1) ya está resuelta.
- **Foundational (Phase 2)**: depende de Setup; **bloquea** todas las historias.
- **US1 (Phase 3)**: depende de T005, T007, T009, T011, T013 y T015. Backend puro.
- **US2 (Phase 4)**: depende de US1 (mismo caso de uso y mismo andamiaje de pruebas).
- **US3 (Phase 5)**: depende de US1 (y de T011 para la ventana).
- **US4 (Phase 6)**: independiente del backend para sus pruebas (el cliente se simula); para probar el flujo real necesita US1.
- **US5 (Phase 7)**: depende de US1 y US2 (necesita lectura y escritura reales); T041 antes de T042.
- **Polish (Phase 8)**: depende de las historias entregadas; T048 necesita US1–US4.

### Orden dentro de cada historia

Pruebas (que fallan) → tipos/dominio → puertos → casos de uso → adaptadores/UI. Los archivos compartidos
(`StreamChatUseCase.java`, `StreamChatUseCaseTests.java`, `ChatMemoryIntegrationTests.java`, `ChatConstants.java`) se tocan
en orden de tarea: T016 → T022 → T023 → T027 → T028 → T030 → T040 → T042.

### Oportunidades de paralelismo

- Foundational: T004, T006, T008, T010, T012 y T014 en paralelo (archivos distintos); T005 tras T004, T007 tras T006,
  T009 tras T008, T011 tras T010, T013 tras T012, T015 tras T014 y T005.
- US1: T016, T017 y T018 en paralelo; T019, T020 y T021 en paralelo antes de T022.
- US2: T023–T026 en paralelo.
- US4 (frontend) puede avanzar en paralelo con US2, US3 y US5 (backend) por personas distintas tras la fase 2.
- Polish: T043 y T044 en paralelo.

---

## Implementation Strategy

### MVP (US1 + US2)

1. Setup y Foundational.
2. US1: `curl` con el mismo `conversationId` entiende «¿y si es internacional?».
3. US2: aislamiento, persistencia tras reinicio y errores del contrato. **Parar y validar** con el quickstart §2, §3 y §4;
   es lo que pide el criterio de la historia («`ChatMemory` en JDBC por `conversationId`», «una pregunta de seguimiento
   se entiende»).

### Entrega incremental

4. US3: límites de historial y presupuesto de contexto.
5. US4: la pantalla envía el identificador y ofrece «Nueva conversación».
6. US5: BlockHound en verde con la memoria real, métricas y registros sin texto.
7. Polish: ADR-0020, documentación, `requests.http`, verificación completa y validación manual.

## Notes

- Cada tarea se marca `[X]` al terminar y se hace un commit atómico por tarea o grupo lógico (Conventional Commits).
- Las pruebas de cada historia deben fallar antes de implementar.
- No tocar los contratos de LAB-12, LAB-22 ni LAB-23: el campo nuevo es opcional y los eventos no cambian.
- Decisión R1 confirmada: puerto y adaptador JDBC propios (no `JdbcChatMemoryRepository`).
- Desvíos respecto a lo escrito arriba, sin cambiar el resultado:
  - La lógica de memoria del caso de uso (leer, consulta de búsqueda, guardar, métricas y registro) vive en el componente
    `ConversationHistory` (`main/chat/application/usecase/`), para no superar los 10 parámetros del constructor de
    `StreamChatUseCase`; T022, T027, T030 y T042 se implementaron ahí y en el caso de uso.
  - Las pruebas nuevas del caso de uso (T016, T023, T028, T040) están en `StreamChatUseCaseMemoryTests.java`, no en
    `StreamChatUseCaseTests.java` (que solo se adaptó a la nueva firma).
  - T018, T025, T029 y T040 están en `ChatMemoryIntegrationTests`; los casos de memoria caída (T023/T024) en
    `ChatMemoryDownIntegrationTests` y el caso `max-turns = 0` en `ChatMemoryDisabledIntegrationTests`.
  - T037: BlockHound ya vigila toda la suite; la lectura y la escritura se prueban desde un hilo no bloqueante en
    `JdbcConversationMemoryAdapterTests` y de extremo a extremo en `ChatMemoryIntegrationTests`, sin un archivo aparte.
  - T038: `ReactiveArchitectureTests` y `ModuleArchitectureTests` pasan sin cambios (las reglas son por capa).
  - T049: no hay propiedades que añadir en `infra/` ni en `../shoplab-infra`: ninguna pinta las variables del chat y
    todas las nuevas tienen valor por defecto.
  - El frontend añade `conversation-id.ts` (UUID v4 con respaldo si `crypto.randomUUID` no existe, p. ej. HTTP sin TLS).
