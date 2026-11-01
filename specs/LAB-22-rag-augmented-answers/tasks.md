---

description: "Task list for LAB-22 - Respuestas aumentadas con los documentos (RAG)"
---

# Tasks: Respuestas aumentadas con los documentos (RAG)

**Input**: Design documents from `/specs/LAB-22-rag-augmented-answers/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`rag-answers.md`), quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas antes o junto con la implementación. En cada historia
las pruebas van primero y deben fallar antes de implementar. Las pruebas de integración usan PostgreSQL con pgvector real
(Testcontainers, `@PostgresTest`) y Ollama simulado con WireMock (`OllamaStubs`); el modelo de embeddings se sustituye por
`DeterministicEmbeddingPort`. Ninguna prueba automática necesita Ollama real.

**Organization**: Tareas agrupadas por historia de usuario. El trabajo vive en los módulos `chat` (principal) y `retrieval`
(solo su `api`) del backend. `ingest`, `frontend`, `infra` y las migraciones no se tocan. US1 (contexto en la respuesta)
es el MVP; US2 completa la regla «solo con el contexto»; US3 y US4 son P2.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US4)
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Main**: `backend/src/main/java/com/shoplab/helpdesk/` (en las tareas, `main/`)
- **Tests**: `backend/src/test/java/com/shoplab/helpdesk/` (en las tareas, `test/`)
- Java: líneas ≤ 121 caracteres, llaves en todos los bloques, identificadores en inglés, constantes en `@UtilityClass`
  de `domain/model/constants`, sin `block()` ni llamadas bloqueantes en el event loop, errores con `ExceptionUtil` y
  `ResponseCodeEnum`, Spring AI solo en `infrastructure`, `chat` solo importa `retrieval.api`
- Sin texto de preguntas ni de fragmentos en logs, métricas ni mensajes de error

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base verde y dependencia nueva comprobada

- [X] T001 Ejecutar la línea base antes de tocar nada: `mvn test -pl backend -Dskip.srcclr=true --no-transfer-progress`
  debe pasar en la rama; anotar fallos previos si los hay
- [X] T002 Añadir la dependencia `org.springframework.ai:spring-ai-rag` (versión gestionada por el BOM 2.0.2, sin
  `<version>`) en `backend/pom.xml` junto a `spring-ai-starter-model-ollama` y resolverla con red
- [X] T003 Spike (research D-1): con la dependencia resuelta, comprobar en un test desechable que
  `ContextualQueryAugmenter.builder().allowEmptyContext(false).promptTemplate(...).emptyContextPromptTemplate(...)` y
  `Query`/`Document` existen en 2.0.2 con la API de 1.1.x y que el aumentador funciona sin `ChatClient`; anotar el
  resultado (y si el advisor de 2.0.2 ofreciera un camino no bloqueante) al final de `research.md`. Borrar el test
  desechable. Si la API difiere, ajustar D-1 y el contrato antes de seguir

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: costuras entre módulos, tipos y errores que necesitan todas las historias

**CRITICAL**: ninguna historia puede empezar hasta terminar esta fase

- [X] T004 [P] Test de `KnowledgeSearchApiAdapter` en
  `test/retrieval/infrastructure/in/api/KnowledgeSearchApiAdapterTests.java`: mapea `SearchResult` a `KnowledgeChunk`
  (`source`, `section`, `position`, `text`, `score`; sin `chunkId` ni `documentId`), devuelve lista vacía si la búsqueda
  la devuelve, pasa `query` y `limit` tal cual y propaga el error de la búsqueda sin envolverlo
- [X] T005 [P] Crear `main/retrieval/api/KnowledgeChunk.java` (registro público: `String source`, `String section`,
  `int position`, `String text`, `double score`) y `main/retrieval/api/KnowledgeSearchApi.java`
  (`Mono<List<KnowledgeChunk>> search(String query, int limit)`) con Javadoc del contrato de
  `contracts/rag-answers.md` §1; el paquete `api` ya está anotado `@NamedInterface("api")`
- [X] T006 Implementar `main/retrieval/infrastructure/in/api/KnowledgeSearchApiAdapter.java` (`@Component`, implementa
  `KnowledgeSearchApi`, delega en `SearchChunksServicePort.search(query, limit)` y mapea con un mapper explícito
  `main/retrieval/infrastructure/in/api/KnowledgeChunkMapper.java`); hace pasar T004
- [X] T007 [P] Crear en `main/chat/domain/model/ContextFragment.java` el registro `ContextFragment(String source,
  String section, int position, String text, double score)` con validación en el constructor compacto: `source` y
  `section` no nulos (vacío permitido), `position` ≥ 0, `text` no vacío; y en
  `main/chat/domain/model/enums/ContextOutcome.java` el enum `WITH_CONTEXT`, `NO_CONTEXT`, `FAILED` con `tagValue()` en
  minúsculas (patrón de `ChatOutcome`)
- [X] T008 [P] Crear `main/chat/domain/exception/KnowledgeRetrievalException.java` (extiende `RuntimeException` como
  `ChatModelException`; guarda la causa) y añadir `CHAT_CONTEXT_UNAVAILABLE("SLHD_CHAT_00004",
  HttpStatus.SERVICE_UNAVAILABLE, "The knowledge base is not available")` en `main/shared/exception/ResponseCodeEnum.java`;
  actualizar `test/shared/exception/ResponseCodeEnumTests.java` (código único, formato `SLHD_CHAT_NNNNN`, estado 503)
- [X] T009 [P] Crear `main/chat/application/port/out/KnowledgeContextPort.java` con
  `Mono<List<ContextFragment>> retrieve(String question, int limit)` y Javadoc de `contracts/rag-answers.md` §2
- [X] T010 [P] Test de `KnowledgeSearchAdapter` en
  `test/chat/infrastructure/out/knowledge/KnowledgeSearchAdapterTests.java` (Mockito sobre `KnowledgeSearchApi`): mapea
  `KnowledgeChunk` a `ContextFragment` conservando el orden; convierte cualquier error de la API (un
  `HelpdeskException` como `EMBEDDING_SERVICE_UNAVAILABLE`, un `RuntimeException`) en `KnowledgeRetrievalException` con
  la causa original; descarta chunks de texto vacío con un `WARN` sin el texto; vacío → lista vacía
- [X] T011 Implementar `main/chat/infrastructure/out/knowledge/KnowledgeSearchAdapter.java` (`@Component`, implementa
  `KnowledgeContextPort`, usa solo `retrieval.api.KnowledgeSearchApi` y un mapper explícito
  `main/chat/infrastructure/out/knowledge/ContextFragmentMapper.java`); hace pasar T010
- [X] T012 [P] Test de `RagProperties` en `test/chat/config/RagPropertiesTests.java`: defaults (`topK` 4,
  `similarityThreshold` 0.0, `maxContextCharacters` 8000, plantillas por defecto); rechazan con
  `IllegalArgumentException` que nombra la propiedad y el rango: `topK` fuera de `1 ≤ valor ≤ 20` (0, -1, 21),
  `similarityThreshold` fuera de `0.0 ≤ valor ≤ 1.0`, `maxContextCharacters` < 500, `contextTemplate` sin `{query}` o sin
  `{context}`, `emptyContextTemplate` vacía
- [X] T013 Crear `main/chat/config/RagProperties.java` (`@ConfigurationProperties("shoplab.chat.rag")`, record con
  `Integer topK`, `Double similarityThreshold`, `Integer maxContextCharacters`, `String contextTemplate`,
  `String emptyContextTemplate`; validación en el constructor compacto, patrón de `EmbeddingProperties`; plantillas por
  defecto en español en `main/chat/domain/model/constants/ChatConstants.java`) y registrarlo en
  `main/chat/config/ChatConfig.java` (`@EnableConfigurationProperties`); hace pasar T012
- [X] T014 [P] Añadir a `ChatMetricsPort` `void recordContext(ContextOutcome outcome, int fragments, Duration elapsed)`;
  test en `test/chat/infrastructure/out/metrics/MicrometerChatMetricsTests.java` (Timer
  `shoplab.chat.context.retrieval` con etiqueta `outcome` = `with_context` | `no_context` | `failed`, y
  DistributionSummary `shoplab.chat.context.fragments` que registra los fragmentos entregados) y su implementación en
  `main/chat/infrastructure/out/metrics/MicrometerChatMetrics.java` con nombres en `ChatConstants`
- [X] T015 Cambiar `ChatModelPort` a `Flux<String> streamAnswer(String message, List<ContextFragment> context)` en
  `main/chat/application/port/out/ChatModelPort.java` y adaptar los llamantes mínimos para que el proyecto compile:
  `StreamChatUseCase` (pasa `List.of()` por ahora), `OllamaChatAdapter` (ignora el contexto por ahora) y los tests que
  mockean o llaman al puerto (`StreamChatUseCaseTests`, `OllamaChatAdapterTests`, `ChatControllerTests`)
- [X] T016 Dar a las pruebas de integración de LAB-12 un `KnowledgeContextPort` que devuelva lista vacía, sin cambiar sus
  aserciones: crear `test/chat/support/EmptyKnowledgeContextConfig.java` (`@TestConfiguration` con el bean
  `@Primary`) e importarlo en `ChatStreamingIntegrationTests`, `ChatStreamContractTests`,
  `ChatMidStreamFailureIntegrationTests`, `ChatModelDownIntegrationTests` y `ToolCompositionIntegrationTests` si no
  arrancan ya PostgreSQL; ejecutar `mvn test -pl backend -Dtest='Chat*Tests,ToolComposition*'` y confirmar que pasan sin
  tocar su contrato (SC-005)

**Checkpoint**: el proyecto compila, `chat` ve la búsqueda solo por `retrieval.api` y las pruebas de LAB-12 siguen verdes

---

## Phase 3: User Story 1 - Respuestas basadas en los documentos de la tienda (Priority: P1) MVP

**Goal**: cada respuesta se genera con los fragmentos más cercanos a la pregunta como contexto, sin cambiar el contrato SSE.

**Independent Test**: con un documento indexado, `POST /api/chat` con una pregunta sobre su contenido hace que la petición
que llega al modelo contenga el fragmento correcto y la respuesta siga llegando token a token con los mismos eventos.

### Tests for User Story 1 (escribir primero; deben fallar)

- [X] T017 [P] [US1] `test/chat/application/usecase/ContextSelectorTests.java`: devuelve los fragmentos en el orden
  recibido; elimina textos repetidos (tras normalizar espacios y mayúsculas) conservando el de mayor puntuación; añade
  fragmentos completos mientras quepan en `maxContextCharacters` y el primero que no cabe corta la lista (ninguno se
  trunca); si el primero ya excede el presupuesto se entrega solo ese; lista vacía → vacía
- [X] T018 [P] [US1] Ampliar `test/chat/application/usecase/StreamChatUseCaseTests.java` (StepVerifier, puertos
  simulados): con fragmentos recuperados, `streamAnswer` recibe exactamente la lista seleccionada y se piden `topK`
  candidatos; la salida mantiene `TokenEvent`s y `DoneEvent` con el recuento de fragmentos de texto; la recuperación
  ocurre una vez por petición y con el texto de la pregunta actual (sin arrastrar la anterior); el mensaje inválido
  falla antes de llamar a `KnowledgeContextPort`
- [X] T019 [P] [US1] Ampliar `test/chat/infrastructure/out/ollama/OllamaChatAdapterTests.java`: con contexto, el `Prompt`
  enviado al `ChatModel` lleva un mensaje de sistema con el `systemPrompt` y un mensaje de usuario que contiene la pregunta
  y el texto de cada fragmento, cada uno precedido de `[source › section]` (o solo `[source]` si la sección está vacía);
  las herramientas siguen añadiéndose a las opciones (`ToolCallbackComposer`)
- [X] T020 [US1] `test/chat/RagAnsweringIntegrationTests.java` (`@PostgresTest`, WireMock de Ollama con `OllamaStubs`,
  `DeterministicEmbeddingPort`, cliente `ChatTestClient`): ingerir/insertar y dejar indexado un documento del corpus
  (patrón de `EmbeddingFlowIntegrationTests`), enviar `POST /api/chat` con una pregunta sobre su contenido y comprobar con
  `WireMock.verify` que el cuerpo recibido por `/api/chat` de Ollama contiene el texto del fragmento correcto y que la
  respuesta SSE tiene los eventos `token`… y `done` del contrato de LAB-12

### Implementation for User Story 1

- [X] T021 [US1] Crear `main/chat/application/usecase/ContextSelector.java` (clase pura, sin Spring AI ni Reactor;
  método `select(List<ContextFragment> candidates, double threshold, int maxCharacters)`): primero umbral (solo si
  `threshold > 0`, conserva `score >= threshold`), luego duplicados, luego presupuesto (research D-6); `WARN` sin texto si
  el primero excede el presupuesto; hace pasar T017
- [X] T022 [US1] Modificar `main/chat/application/usecase/StreamChatUseCase.java`: tras `validate`, llamar a
  `knowledgeContext.retrieve(message, rag.topK())`, pasar el resultado por `ContextSelector` y encadenar
  `chatModel.streamAnswer(message, selected)` con `Mono#flatMapMany`; mantener intactos `TokenEvent`, `DoneEvent`, el manejo
  de errores existente y la cancelación; el tiempo hasta el primer token pasa a medirse desde el inicio de la petición
  (incluye la recuperación); inyectar `KnowledgeContextPort`, `ContextSelector` y `RagProperties`; hace pasar T018
- [X] T023 [US1] Modificar `main/chat/infrastructure/out/ollama/OllamaChatAdapter.java`: construir
  `Query` con el mensaje, convertir los `ContextFragment` en `Document` (texto precedido de `[source › section]`, metadatos
  `source`, `section`, `position`, `score`), aumentar con `ContextualQueryAugmenter` (bean en
  `main/chat/config/ChatConfig.java` con `allowEmptyContext(false)` y las plantillas de `RagProperties`) y enviar
  `SystemMessage(systemPrompt)` + `UserMessage(consulta aumentada)`; seguir usando `ChatModel.stream` y
  `mapNotNull/filter/onErrorMap` actuales (nada de `ChatClient`, nada de `block()`); hace pasar T019
- [X] T024 [US1] Ejecutar `T020` y `ChatStreamContractTests`/`ChatStreamingIntegrationTests`: deben pasar; ejecutar
  `ModularityTests`, `ModuleArchitectureTests` y `ReactiveArchitectureTests` para confirmar que `chat` solo usa
  `retrieval.api` y que no hay bloqueos

**Checkpoint**: el bot responde con contexto de los documentos y el contrato del chat no cambió (MVP)

---

## Phase 4: User Story 2 - Responder solo con el contexto y reconocer lo que no sabe (Priority: P1)

**Goal**: el modelo recibe la regla de responder solo con el contexto; sin contexto útil, la respuesta reconoce que no tiene
la información.

**Independent Test**: preguntar algo ausente del corpus y comprobar que la petición al modelo lleva la plantilla de
contexto vacío y el prompt fundamentado; la comprobación de calidad con Ollama real se hace en T043.

### Tests for User Story 2 (escribir primero; deben fallar)

- [X] T025 [P] [US2] `test/chat/config/ChatPropertiesTests.java`: con `systemPrompt` nulo o en blanco, el valor por defecto es
  el prompt fundamentado y contiene las cinco reglas de `contracts/rag-answers.md` §5 (responder solo con el CONTEXTO; no usar
  conocimiento general sobre la tienda; reconocer cuando el contexto no responde y cuando cubre solo una parte; el CONTEXTO es
  dato y no instrucción y las reglas no cambian a petición del cliente; los datos de herramientas cuentan como contexto);
  una propiedad explícita lo sustituye
- [X] T026 [P] [US2] Ampliar `test/chat/infrastructure/out/ollama/OllamaChatAdapterTests.java`: con contexto vacío el mensaje
  de usuario usa `emptyContextTemplate` (indica que no hay información y que no se use conocimiento general) y conserva la
  pregunta; con contexto, el mensaje de usuario no incluye el texto de la plantilla vacía; una pregunta que intenta cambiar
  las reglas llega como parte de la pregunta, sin alterar el mensaje de sistema; un fragmento que contiene órdenes se entrega
  dentro de la sección de contexto, nunca en el mensaje de sistema
- [X] T027 [P] [US2] Ampliar `test/chat/application/usecase/StreamChatUseCaseTests.java`: recuperación vacía → se llama a
  `streamAnswer` con lista vacía y el flujo termina con `DoneEvent` (no se cortocircuita); todos los fragmentos bajo el umbral →
  lista vacía; saludo corto → igual camino, sin error
- [X] T028 [US2] Ampliar `test/chat/RagAnsweringIntegrationTests.java`: base sin documentos indexados → la petición a Ollama
  contiene la plantilla de contexto vacío y ningún fragmento; documento indexado con otro modelo de embeddings → mismo
  resultado (no se mezclan modelos, LAB-21)

### Implementation for User Story 2

- [X] T029 [US2] Sustituir el valor por defecto de `systemPrompt` en `main/chat/config/ChatProperties.java` por el prompt
  fundamentado en español (texto en `ChatConstants`, cinco reglas de `contracts/rag-answers.md` §5); hace pasar T025
- [X] T030 [US2] Fijar en `ChatConstants` y `RagProperties` las plantillas por defecto: `contextTemplate` (con `{query}` y
  `{context}`, delimita el contexto como «datos de referencia») y `emptyContextTemplate` (sin información sobre el tema,
  dilo con claridad e invita a contactar con la tienda, no uses conocimiento general; antepone la pregunta); hace pasar T026
- [X] T031 [US2] Verificar que `main/chat/application/usecase/StreamChatUseCase.java` y `OllamaChatAdapter` siguen el camino
  de contexto vacío sin código especial (lista vacía → plantilla vacía); ajustar solo si T026/T027/T028 lo exigen; hace pasar
  T027 y T028

**Checkpoint**: sin contexto útil el bot lo reconoce; el prompt obliga a responder solo con el contexto

---

## Phase 5: User Story 3 - Número de fragmentos (top-k) configurable (Priority: P2)

**Goal**: top-k, umbral y presupuesto se ajustan por configuración, con valores por defecto documentados y arranque que falla
ante valores inválidos.

**Independent Test**: cambiar `shoplab.chat.rag.top-k` y comprobar cuántos fragmentos recibe el modelo; un valor inválido
impide arrancar y nombra la propiedad.

### Tests for User Story 3 (escribir primero; deben fallar)

- [X] T032 [P] [US3] Ampliar `test/chat/application/usecase/ContextSelectorTests.java` y `StreamChatUseCaseTests.java`: con
  `topK` N se piden N candidatos a `KnowledgeContextPort`; con menos candidatos que N se entregan los que haya sin error; con
  umbral 0.6 solo pasan los fragmentos con `score >= 0.6`; si ninguno lo alcanza, lista vacía (camino «sin información»);
  umbral 0.0 no descarta nada, ni siquiera puntuaciones negativas
- [X] T033 [US3] `test/chat/RagConfigurationIntegrationTests.java` (contexto Spring con `ApplicationContextRunner` o
  `@SpringBootTest` y propiedades): `top-k=2` con 5 candidatos → el cuerpo enviado al stub de Ollama contiene exactamente 2
  fragmentos, los más cercanos; sin configurar → 4; `top-k=0`, `21` o `similarity-threshold=1.5` hacen fallar el arranque con
  un mensaje que nombra la propiedad y el rango (`1 ≤ valor ≤ 20`, `0.0 ≤ valor ≤ 1.0`)

### Implementation for User Story 3

- [X] T034 [US3] Añadir a `backend/src/main/resources/application.yml` las propiedades de `contracts/rag-answers.md` §4:
  `shoplab.chat.system-prompt: ${SHOPLAB_CHAT_SYSTEM_PROMPT:}`, `shoplab.chat.rag.top-k`, `similarity-threshold`,
  `max-context-characters` (variables `SHOPLAB_CHAT_RAG_*`) y `spring.ai.ollama.chat.options.num-ctx: ${OLLAMA_NUM_CTX:8192}`;
  hace pasar T033
- [X] T035 [P] [US3] Documentar las variables nuevas con sus rangos y defectos en `README.md` (tabla de configuración, si
  existe) y `docs/chat-streaming.md`; si `infra/` o `../shoplab-infra` declaran variables del backend (ConfigMap/env), añadir
  `OLLAMA_NUM_CTX` solo si el entorno `dev` la necesita (decisión en el PR; los defectos ya funcionan)

**Checkpoint**: el ajuste de calidad no requiere código

---

## Phase 6: User Story 4 - Resiliencia y observabilidad del paso de recuperación (Priority: P2)

**Goal**: un fallo de la recuperación da un error tipado y nunca una respuesta sin contexto fingida; hay métricas y logs sin
datos sensibles.

**Independent Test**: simular la búsqueda caída y comprobar `503` `SLHD_CHAT_00004`, ninguna llamada a Ollama y el log
registrado; con búsqueda normal, comprobar las métricas.

### Tests for User Story 4 (escribir primero; deben fallar)

- [X] T036 [P] [US4] Ampliar `test/chat/application/usecase/StreamChatUseCaseTests.java`: `KnowledgeRetrievalException` antes
  del primer token → el flujo termina con error `HelpdeskException` con `ResponseCodeEnum.CHAT_CONTEXT_UNAVAILABLE` (no con
  `CHAT_MODEL_UNAVAILABLE`) y `streamAnswer` nunca se invoca; se registra `recordContext(FAILED, 0, …)`; con éxito se registra
  `recordContext(WITH_CONTEXT, n, …)` o `NO_CONTEXT` con 0; la cancelación durante la recuperación no deja métricas de error
- [X] T037 [P] [US4] Ampliar `test/shared/infrastructure/in/rest/handler/GlobalExceptionHandlerTests.java`: el error con
  `CHAT_CONTEXT_UNAVAILABLE` se mapea a 503 con el cuerpo de error estándar y sin la causa interna
- [X] T038 [US4] Ampliar `test/chat/RagAnsweringIntegrationTests.java`: con la búsqueda simulada caída (bean de
  `KnowledgeSearchApi` que falla con `EMBEDDING_SERVICE_UNAVAILABLE`) `POST /api/chat` responde `503` `SLHD_CHAT_00004` y
  WireMock confirma que Ollama no recibió petición; con la base vacía `GET /actuator/prometheus` expone
  `shoplab_chat_context_retrieval_seconds_count{outcome="no_context"}`; capturar el log (ListAppender) y comprobar que no
  contiene el texto de la pregunta ni del fragmento

### Implementation for User Story 4

- [X] T039 [US4] En `main/chat/application/usecase/StreamChatUseCase.java`: medir la recuperación + selección, llamar a
  `metrics.recordContext(outcome, fragments, elapsed)` una sola vez por petición (completa, vacía o fallida), traducir
  `KnowledgeRetrievalException` a `ResponseCodeEnum.CHAT_CONTEXT_UNAVAILABLE` en `codeOf`, registrar `INFO` con `requestId`,
  recuperados, tras umbral, entregados y mejor puntuación, y `WARN` en `no_context` y `failed`, sin texto; hace pasar T036
  y T038 (los tests de T037 y la métrica de T014 ya están cubiertos por las tareas anteriores)
- [X] T040 [US4] Confirmar en `main/shared/infrastructure/in/rest/handler/GlobalExceptionHandler.java` que un
  `HelpdeskException` con el código nuevo se mapea sin cambios de código; si no, añadir solo el mapeo necesario (nunca
  construir `ResponseEntity` fuera del manejador)

**Checkpoint**: operable y observable; el cliente nunca recibe una respuesta sin contexto presentada como fundamentada

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: decisiones documentadas, validación manual y puertas de calidad

- [X] T041 [P] Redactar `docs/adr/0018-recuperacion-de-contexto-en-chat.md` con la plantilla `0000-plantilla.md`: decisión de
  recuperar en el caso de uso y aumentar con `ContextualQueryAugmenter` (no `ChatClient` + `RetrievalAugmentationAdvisor`,
  ADR-0011), `retrieval.api` como costura, contexto vacío sin cortocircuito, valores por defecto y consecuencias
  (citas pendientes, evaluación en F2); añadirlo al índice de `docs/adr/README.md`
- [X] T042 [P] Actualizar `docs/chat-streaming.md`, `docs/modulos.md` (sección `chat`/`retrieval`: nueva dependencia
  `chat → retrieval.api`) y `docs/base-conocimiento.md` (cómo llega el contexto al chat); regenerar los diagramas de módulos
  si el proyecto lo hace en el build (`docs/modulos/*`)
- [X] T043 Validación manual con Ollama real según `quickstart.md` §2: SC-001 (10 preguntas, objetivo ≥ 8 correctas), SC-002
  (5 preguntas sin respuesta en el corpus, objetivo 5/5) y SC-004 (primer token < 5 s con el modelo cargado); registrar
  preguntas, respuestas y veredicto en `specs/LAB-22-rag-augmented-answers/validation.md`. Si SC-002 falla, anotarlo como
  riesgo de D-4 en el PR y en `research.md` sin cambiar el alcance
- [X] T044 Generar `specs/LAB-22-rag-augmented-answers/requests.http` (IntelliJ HTTP Client) con un caso por escenario de
  aceptación alcanzable por HTTP: pregunta con respuesta en el corpus (US1), pregunta sin respuesta (US2.1), pregunta
  mixta (US2.3), intento de saltarse las reglas (US2.5), mensaje en blanco `400` `SLHD_CHAT_00001`, mensaje de 4001
  caracteres; listar en un bloque «Not covered here» los escenarios que no se alcanzan por HTTP (top-k, umbral, fallo de
  búsqueda, métricas); `baseUrl` = `http://localhost:8080`, sin secretos
- [X] T045 Puertas de calidad (regla 8 de las guías): `mvn test -pl backend -Dskip.srcclr=true --no-transfer-progress`,
  `mvn checkstyle:check -Dskip.srcclr=true --no-transfer-progress`,
  `mvn verify -Dskip.srcclr=true -Dcheckstyle.skip=true --no-transfer-progress` (cobertura ≥ 80 % en `chat` y `retrieval`) y
  la compilación local `mvn install --no-transfer-progress -o -Dskip.srcclr=true` si el repositorio local ya tiene
  `spring-ai-rag`; corregir PMD/Checkstyle y líneas > 121 caracteres
- [ ] T046 Revisión final: `git status --short` sin archivos sueltos, `git diff --staged` solo con cambios intencionales, sin
  texto de preguntas ni fragmentos en logs, `CHANGELOG.md` y versión intactos (release-please); título del PR
  `feat(chat): [LAB-22] respuestas aumentadas con los documentos (RAG)`; el PR declara las desviaciones (advisor literal y
  citas pendientes, principio V) y que se despliega solo en `dev` hasta que lleguen las citas

---

## Dependencies & Execution Order

- **Phase 1** (T001–T003) → **Phase 2** (T004–T016) → historias. T003 (spike) puede cambiar D-1: hacerlo antes de T023.
- Phase 2: T004→T006; T005 antes de T006 y T011; T007 antes de T009, T010, T014; T008 antes de T010; T010→T011; T012→T013;
  T015 después de T007 y T009 (necesita `ContextFragment`); T016 al final de la fase.
- **US1** (T017–T024) no depende de otras historias; **US2** (T025–T031) usa los caminos de US1 (T022, T023) y los
  confirma; **US3** (T032–T035) usa `RagProperties` (T013) y el caso de uso de US1; **US4** (T036–T040) modifica
  `StreamChatUseCase` después de US1 (T022). US3 y US4 pueden hacerse en cualquier orden tras US1.
- Dentro de cada historia: pruebas primero (deben fallar), luego implementación.
- **Polish** (T041–T046) al final; T041 y T042 pueden empezar en cuanto D-1 esté confirmado (T003).

## Parallel Opportunities

- Phase 2: T004, T007, T008, T009, T012, T014 en paralelo; T005 y T010 en paralelo tras T007/T008.
- US1: T017, T018, T019 en paralelo (archivos distintos); US2: T025, T026, T027 en paralelo; US4: T036, T037 en paralelo.
- Polish: T041, T042 en paralelo con las historias P2.

## Implementation Strategy

1. **MVP = Phase 1 + 2 + US1**: el bot ya usa los documentos y el contrato no cambia. Validar T020 y detenerse a revisar.
2. **US2** completa la garantía de «solo con el contexto» (el valor para el cliente depende de ella: no desplegar US1 sin US2).
3. **US3 y US4** son P2: ajuste por configuración y operabilidad; entregar antes de fusionar.
4. T043 (validación manual con Ollama) es la puerta de calidad de F1 hasta que exista evaluación automática (F2).
5. No fusionar a producción sin las citas de la historia siguiente (Constitución V).

## Notas de implementación (2026-11-01)

- **T016**: `ToolCompositionIntegrationTests` no necesitó `EmptyKnowledgeContextConfig` (no llama al endpoint). Las pruebas de
  LAB-12 conservan el contrato SSE; `OllamaStubs.chatFor` pasó de comparar el mensaje de usuario por igualdad a buscar la
  línea «Pregunta del cliente: …» dentro de la plantilla, y `OllamaChatAdapterTests` comprueba `contains` en lugar de `==`.
- **T023**: el `ContextualQueryAugmenter` se construye en el constructor de `OllamaChatAdapter` (no como bean de
  `ChatConfig`) y el prefijo `[source › section]` se aplica con `documentFormatter`. Sin contexto no se usa el aumentador:
  su camino de contexto vacío sustituye la pregunta por un texto fijo (ver ADR-0018).
- **T037**: no se añadió un test al manejador (es genérico por código); lo cubren `ResponseCodeEnumTests` y el 503
  `SLHD_CHAT_00004` de `RagAnsweringIntegrationTests`.
- **T035**: variables documentadas en `docs/chat-streaming.md`. `OLLAMA_NUM_CTX` en `infra/` y `shoplab-infra` queda por
  decidir en el PR: el valor por defecto de la aplicación ya funciona.
- **T043 hecha** (ver `validation.md`): SC-001 9/10 y SC-004 1–2 s se cumplen; SC-002 solo con umbral > 0 (3/5 con el
  valor por defecto 0, 5/5 con 0,6). El pod de Ollama de `shoplab-dev` muere por memoria (4Gi) con los dos modelos.
- **T046 pendiente**: revisión de `git diff --staged` y título del PR al hacer commit/PR. `docs/modulos/*` se regeneró con
  el build (Spring Modulith) y refleja la nueva dependencia `chat → retrieval.api`.
- **Verificación**: `mvnw verify` (440 tests, JaCoCo), `checkstyle:check` (0 violaciones) y `pmd:check` pasan.
