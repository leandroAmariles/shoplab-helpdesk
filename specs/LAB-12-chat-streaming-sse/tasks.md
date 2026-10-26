# Tasks: Respuesta del chat en streaming (SSE)

**Input**: Design documents from `/specs/LAB-12-chat-streaming-sse/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: se piden (constitución, principio IV, test-first; cobertura ≥ 80 %). Cada prueba se escribe antes que el
código que la satisface y se comprueba que falla. Ninguna prueba usa un modelo real: Ollama se simula con WireMock.

**Organization**: tareas agrupadas por historia de usuario en orden de prioridad: **US1 (P1) → US2 (P1) → US3 (P2) →
US4 (P3)**. La infraestructura que bloquea a todas (módulo `shared`, modelo, puertos, filtro) va en la fase 2.

Abreviaturas de rutas (todas relativas a la raíz del repositorio):

- `MAIN` = `backend/src/main/java/com/shoplab/helpdesk`
- `TEST` = `backend/src/test/java/com/shoplab/helpdesk`
- `SPEC` = `specs/LAB-12-chat-streaming-sse`

Nombres, campos y códigos exactos: [data-model.md](data-model.md). Eventos y errores HTTP:
[contracts/chat-stream.md](contracts/chat-stream.md) y [contracts/openapi.yaml](contracts/openapi.yaml).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de `spec.md` a la que pertenece (US1…US4)
- Todo identificador de código en inglés, líneas ≤ 121 caracteres, llaves en todos los bloques (Checkstyle/PMD).

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base y cierre de los puntos «por verificar» de [research.md](research.md) antes de escribir código.

- [X] T001 En `backend/` ejecutar `./mvnw -B -ntp clean verify` sin cambios de código y anotar resultado y cobertura JaCoCo como «Línea base» en `SPEC/validation.md` (créalo)
- [X] T002 En `backend/pom.xml` añadir `org.springframework.ai:spring-ai-starter-model-ollama` (sin versión; la gestiona el BOM 2.0.2), ejecutar `./mvnw -B -ntp dependency:tree` y registrar en `SPEC/research.md` (R2 y R8) el artefacto real, si trae `spring-webflux` (si no, añadir `org.springframework.boot:spring-boot-starter-webflux` solo como cliente HTTP reactivo, sin cambiar la pila servlet) y si autoconfigura un modelo de embeddings que exija propiedades; si el nombre del starter no resuelve, buscar el correcto en Maven Central y corregirlo aquí
- [X] T003 En `backend/pom.xml` añadir dependencias de **test**: `io.projectreactor:reactor-test`, `org.springframework.boot:spring-boot-starter-webmvc-test` (aporta `MockMvc`/`@WebMvcTest`, que no están en el classpath actual; confirmar el nombre resolviendo el árbol) y `org.wiremock:wiremock-standalone:3.5.4` (versión ya en la caché local; no la gestiona Boot). No añadir `spring-boot-starter-validation`: la validación del mensaje vive en el caso de uso (decisión en Notes)
- [X] T004 [P] Confirmar en `spring-modulith-core` 2.1.2 el valor exacto de `ApplicationModule.Type.OPEN` (inspeccionar el jar en `~/.m2/repository/org/springframework/modulith/` con `javap`) y anotar en `SPEC/research.md` R5 la anotación definitiva para el módulo `shared`
- [X] T005 [P] Crear el fixture `backend/src/test/resources/ollama/chat-stream.ndjson` con una respuesta de streaming de `POST /api/chat` de Ollama (una línea JSON por fragmento: `{"model":"llama3.2:3b","message":{"role":"assistant","content":"Hola"},"done":false}` … y una línea final con `"done":true,"done_reason":"stop"`), incluyendo fragmentos con `ñ`, tildes y un emoji; contrastar la forma exacta con la documentación de Ollama y con el adaptador de Spring AI resuelto en T002

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: infraestructura común de errores, correlación, modelo y puertos. Bloquea a todas las historias.

### Tests ⚠️ escribir primero y comprobar que fallan

- [X] T006 Actualizar `TEST/ModularityTests.java`: `EXPECTED_MODULES` pasa a `Set.of("chat", "retrieval", "tools", "tickets", "shared")`; ejecutar `./mvnw -B -ntp test -Dtest=ModularityTests` y comprobar que **falla** (el módulo `shared` aún no existe)
- [X] T007 [P] Crear `TEST/shared/infrastructure/in/rest/handler/GlobalExceptionHandlerTests.java` (`@WebMvcTest` con un controlador de prueba anidado importado con `@Import`) con las pruebas: `BusinessException` → su `httpStatus` y cuerpo `{code,message}`; `TechnicalException` con `SLHD_CHAT_00002` → `503`; excepción no controlada → `500` con `SLHD_GENE_00001` sin filtrar el mensaje interno; cuerpo JSON ilegible (`HttpMessageNotReadableException`) → `400` con `SLHD_GENE_00002`; con cabecera `Accept: text/event-stream` el error se devuelve igualmente como `application/json` (no `406`)
- [X] T008 [P] Crear `TEST/shared/infrastructure/in/rest/filter/RequestIdFilterTests.java`: sin `X-Request-Id` genera un UUID, lo pone en el MDC durante la petición y lo devuelve en la cabecera de respuesta; con `X-Request-Id` válido (≤ 64 caracteres, sin saltos de línea) lo conserva; con un valor demasiado largo o con caracteres de control lo sustituye por un UUID nuevo; el MDC queda limpio al terminar

### Implementation

- [X] T009 Crear `MAIN/shared/package-info.java` con `@ApplicationModule(displayName = "Shared", type = ...OPEN)` (anotación según T004) y los paquetes vacíos necesarios; ejecutar `ModularityTests` y comprobar que T006 pasa
- [X] T010 [P] Crear en `MAIN/shared/exception/`: `ResponseCodeEnum` (campos `code`, `httpStatus`, `defaultMessage`; valores exactos de la tabla «Códigos de error» de [data-model.md](data-model.md): `INVALID_CHAT_MESSAGE` `SLHD_CHAT_00001` 400, `CHAT_MODEL_UNAVAILABLE` `SLHD_CHAT_00002` 503, `CHAT_STREAM_FAILED` `SLHD_CHAT_00003`, `UNEXPECTED_ERROR` `SLHD_GENE_00001` 500, `INVALID_REQUEST_BODY` `SLHD_GENE_00002` 400), `BusinessException` y `TechnicalException` (ambas con `ResponseCodeEnum` y causa opcional) y `ExceptionUtil` para construirlas con código y descripción estandarizados
- [X] T011 [P] Crear `MAIN/shared/infrastructure/in/rest/dto/ErrorResponse.java` (record `code`, `message`)
- [X] T012 Crear `MAIN/shared/infrastructure/in/rest/handler/GlobalExceptionHandler.java` (`@RestControllerAdvice`, **único** lugar que traduce excepciones a HTTP): maneja `BusinessException`, `TechnicalException`, `HttpMessageNotReadableException` y `Exception`; fija siempre `Content-Type: application/json` en la respuesta de error; registra con nivel adecuado sin volcar contenido del cliente; hace pasar T007
- [X] T013 Crear `MAIN/shared/infrastructure/in/rest/filter/RequestIdFilter.java` (`OncePerRequestFilter`, orden alto) según T008 y la decisión R6 de [research.md](research.md); hace pasar T008
- [X] T014 [P] Crear el modelo de `chat` en `MAIN/chat/domain/model/`: `StreamChatCommand` (record `message`, `requestId`), `ChatEvent` (interfaz sellada con records `TokenEvent(content)`, `DoneEvent(fragments)`, `ErrorEvent(code, message)`), `enums/ChatOutcome` (`COMPLETED`, `ERROR`, `CANCELLED` con valor externo en minúsculas) y `constants/ChatConstants` (`@UtilityClass`: nombres de evento `token`/`done`/`error`, nombres de métricas `shoplab.chat.first.token` y `shoplab.chat.response`, etiqueta `outcome`)
- [X] T015 [P] Crear los puertos en `MAIN/chat/application/port/`: `in/StreamChatServicePort` (`Flux<ChatEvent> stream(StreamChatCommand command)`), `out/ChatModelPort` (`Flux<String> streamAnswer(String message)`) y `out/ChatMetricsPort` (`recordFirstToken(Duration)` y `recordResponse(ChatOutcome, Duration)`)
- [X] T016 [P] Crear `MAIN/chat/config/ChatProperties.java` (`@ConfigurationProperties("shoplab.chat")`: `maxMessageLength` por defecto `4000`, `systemPrompt` con un texto mínimo de mesa de ayuda) y registrarla; en `backend/src/main/resources/application.yml` añadir `spring.ai.ollama.base-url: ${OLLAMA_BASE_URL:http://localhost:11434}`, `spring.ai.ollama.chat.options.model: ${OLLAMA_CHAT_MODEL:llama3.2:3b}`, `shoplab.chat.max-message-length: 4000` y las propiedades que T002 haya mostrado necesarias para no exigir embeddings
- [X] T017 Ejecutar `./mvnw -B -ntp test` en `backend/` y comprobar que el contexto de Spring arranca (`ShopLabHelpdeskApplicationTests`, `MetricsEndpointTests`, `ModularityTests`) sin necesitar Ollama en marcha

**Checkpoint**: infraestructura lista; las historias pueden empezar.

---

## Phase 3: User Story 1 - Recibir la respuesta token a token (Priority: P1) 🎯 MVP

**Goal**: `POST /api/chat` devuelve un flujo `text/event-stream` con un evento `token` por fragmento del modelo, en
orden, y un evento `done` final con el cierre de la conexión.

**Independent Test**: `ChatStreamingIntegrationTests` (happy path) en verde y, con Ollama real, el paso 2 de
[quickstart.md](quickstart.md) muestra eventos `token` progresivos y un `done`.

### Tests for User Story 1 ⚠️ escribir primero y comprobar que fallan

- [X] T018 [P] [US1] Crear `TEST/chat/application/usecase/StreamChatUseCaseTests.java` (`StepVerifier`, dobles de `ChatModelPort` y `ChatMetricsPort`): el modelo emite `["Ho","la"," ","mundo"]` → `TokenEvent` ×4 en el mismo orden y un `DoneEvent(fragments = 4)`; la concatenación de `content` reproduce el texto; el modelo no emite nada → solo `DoneEvent(0)`; fragmentos con `ñ` y emoji se entregan sin modificar
- [X] T019 [P] [US1] Crear `TEST/chat/infrastructure/out/ollama/OllamaChatAdapterTests.java` (WireMock sirviendo `chat-stream.ndjson` de T005 con `Transfer-Encoding: chunked` en `POST /api/chat`): `streamAnswer` emite los `content` en orden, incluidos los multibyte, y completa tras la línea `done:true`; el cuerpo enviado a Ollama contiene el modelo configurado, `stream:true`, el prompt de sistema y el mensaje del usuario
- [X] T020 [P] [US1] Crear `TEST/chat/infrastructure/in/rest/ChatControllerTests.java` (`@WebMvcTest` con `StreamChatServicePort` simulado): `POST /api/chat` con `{"message":"Hola"}` responde `200` y `Content-Type` `text/event-stream`; el cuerpo contiene `event:token` / `data:{"content":"..."}` por fragmento y termina con `event:done` / `data:{"fragments":N}`; la respuesta lleva `Cache-Control: no-cache` y `X-Accel-Buffering: no`; el comando recibido lleva el mensaje y el `requestId` de la cabecera
- [X] T021 [P] [US1] Crear `TEST/chat/ChatStreamingIntegrationTests.java` (`@SpringBootTest` con puerto aleatorio, WireMock como Ollama y `java.net.http.HttpClient`, como `MetricsEndpointTests`): el flujo llega completo y en orden, la concatenación coincide con el texto del fixture (SC-004, incluye `ñ` y emoji); 5 peticiones concurrentes con fixtures distintos reciben cada una solo su respuesta (SC-007, FR-015)
- [X] T022 [US1] Ejecutar `./mvnw -B -ntp test -Dtest='StreamChatUseCaseTests,OllamaChatAdapterTests,ChatControllerTests,ChatStreamingIntegrationTests'` y comprobar que **fallan**; anotar el resultado en `SPEC/validation.md`

### Implementation for User Story 1

- [X] T023 [US1] Crear `MAIN/chat/application/usecase/StreamChatUseCase.java` (`@Service`, implementa `StreamChatServicePort`; depende solo de `ChatModelPort`, `ChatMetricsPort` y `ChatProperties`): `Flux<ChatEvent>` que mapea cada fragmento a `TokenEvent`, cuenta fragmentos y añade `DoneEvent(count)` al completar; sin `.block()`; registra `requestId` y longitud del mensaje, nunca su contenido (la validación y los errores llegan en US3)
- [X] T024 [P] [US1] Crear `MAIN/chat/infrastructure/out/ollama/OllamaChatAdapter.java` (`@Component`, implementa `ChatModelPort`): usa `ChatClient` de Spring AI (`prompt().system(systemPrompt).user(message).stream().content()`); el `ChatClient` se construye con el `ChatClient.Builder` autoconfigurado
- [X] T025 [P] [US1] Crear `MAIN/chat/infrastructure/in/rest/dto/ChatRequest.java` (record `message`) y `MAIN/chat/infrastructure/in/rest/util/mapper/ChatEventMapper.java` (`ChatEvent` → `ServerSentEvent<...>` con nombre de evento y carga JSON según [contracts/chat-stream.md](contracts/chat-stream.md); `ErrorEvent` incluido aunque se ejercite en US3)
- [X] T026 [US1] Crear `MAIN/chat/infrastructure/in/rest/ChatController.java` (`@RestController`, `POST /api/chat`, `produces = text/event-stream`): sin lógica; construye `StreamChatCommand` con el `requestId` del MDC/cabecera, llama a `StreamChatServicePort` y devuelve `Flux<ServerSentEvent<...>>` mapeado; añade `Cache-Control: no-cache` y `X-Accel-Buffering: no`
- [X] T027 [US1] Ejecutar las pruebas de T022 y comprobar que pasan; ejecutar `./mvnw -B -ntp test` completo

**Checkpoint**: US1 funcional y demostrable (MVP): el chat responde en streaming.

---

## Phase 4: User Story 2 - Primer token rápido (Priority: P1)

**Goal**: el primer fragmento llega en menos de 2 s con el modelo cargado, la respuesta no se acumula en memoria y
hay métricas del tiempo hasta el primer token y de la duración por resultado.

**Independent Test**: `ChatStreamingIntegrationTests` demuestra que el primer evento llega antes del fin de la
generación simulada, `/actuator/prometheus` expone `shoplab_chat_first_token_seconds`, y el paso 3 de
[quickstart.md](quickstart.md) da `time_starttransfer` < 2 s con Ollama real.

### Tests for User Story 2 ⚠️ escribir primero y comprobar que fallan

- [X] T028 [P] [US2] Ampliar `StreamChatUseCaseTests`: ante el primer fragmento se llama una sola vez a `recordFirstToken` con una duración ≥ 0; al completar se llama a `recordResponse(COMPLETED, duración)`; con cero fragmentos no se llama a `recordFirstToken` pero sí a `recordResponse(COMPLETED, ...)`
- [X] T029 [P] [US2] Crear `TEST/chat/infrastructure/out/metrics/MicrometerChatMetricsTests.java` (`SimpleMeterRegistry`): `recordFirstToken` registra en el `Timer` `shoplab.chat.first.token`; `recordResponse` registra en `shoplab.chat.response` con etiqueta `outcome` en minúsculas (`completed`, `error`, `cancelled`); no existe ninguna etiqueta con contenido del mensaje
- [X] T030 [US2] Ampliar `ChatStreamingIntegrationTests`: con WireMock sirviendo cada fragmento con un retardo fijo (p. ej. 300 ms × 10), el cliente recibe el primer evento `token` antes de 1 s y el `done` solo después de ≥ 2,5 s (la respuesta no se acumula, FR-006/SC-002); tras la petición, `GET /actuator/prometheus` contiene `shoplab_chat_first_token_seconds_bucket` y `shoplab_chat_response_seconds_count{...outcome="completed"...}` con la etiqueta `application="helpdesk-backend"`
- [X] T031 [US2] Ejecutar las pruebas ampliadas y comprobar que **fallan**; anotar en `SPEC/validation.md`

### Implementation for User Story 2

- [X] T032 [US2] Ampliar `StreamChatUseCase`: tomar el instante de inicio al suscribirse, llamar a `ChatMetricsPort.recordFirstToken` en el primer fragmento (`doOnNext` con indicador atómico) y a `recordResponse(COMPLETED, ...)` al completar; efectos secundarios solo en operadores `doOn*`
- [X] T033 [P] [US2] Crear `MAIN/chat/infrastructure/out/metrics/MicrometerChatMetrics.java` (`@Component`, implementa `ChatMetricsPort` sobre `MeterRegistry`; `Timer` con histograma de percentiles para ambas métricas y etiqueta `outcome` de valores fijos); en `application.yml` añadir `management.metrics.distribution.percentiles-histogram.shoplab.chat: true` si el histograma no se activa por defecto
- [X] T034 [US2] Ejecutar las pruebas de T031 y comprobar que pasan
- [X] T035 [US2] Con `docker compose -f infra/docker-compose.yml up -d` y el backend en marcha, ejecutar el paso 3 de [quickstart.md](quickstart.md) diez veces con el modelo caliente y anotar los tiempos en `SPEC/validation.md`; si el 95 % no baja de 2 s, evaluar la opción `keep-alive` de Ollama (`spring.ai.ollama.chat.options.keep-alive`) y dejar constancia de la decisión en `SPEC/research.md` R8

**Checkpoint**: US1 + US2 cumplen el criterio de latencia y son observables.

---

## Phase 5: User Story 3 - Errores claros durante y antes del streaming (Priority: P2)

**Goal**: mensajes inválidos → 400, modelo caído antes de empezar → 503, fallo a mitad → evento `error`; el flujo
siempre se cierra y los logs llevan el `requestId` sin contenido del cliente.

**Independent Test**: los pasos del apartado 4 de [quickstart.md](quickstart.md) y las pruebas de esta fase en verde.

### Tests for User Story 3 ⚠️ escribir primero y comprobar que fallan

- [X] T036 [P] [US3] Ampliar `StreamChatUseCaseTests`: mensaje `null`, vacío, `"   "` o de 4001 caracteres → `BusinessException` con `SLHD_CHAT_00001` y **sin** invocar `ChatModelPort` (verificar con `verifyNoInteractions`); mensaje de exactamente 4000 caracteres se acepta (límite); el modelo falla antes del primer fragmento (`TechnicalException` `SLHD_CHAT_00002` desde el puerto) → el `Flux` termina en error con ese código; el modelo falla tras emitir 2 fragmentos → 2 `TokenEvent` y un `ErrorEvent("SLHD_CHAT_00003", ...)` y el flujo completa sin propagar la excepción; en ambos fallos se llama a `recordResponse(ERROR, ...)`; con `OutputCaptureExtension` comprobar que los logs contienen el `requestId` y no el texto del mensaje
- [X] T037 [P] [US3] Ampliar `OllamaChatAdapterTests`: WireMock rechazando la conexión o respondiendo `500` antes de cualquier línea → el `Flux` emite `TechnicalException` con `SLHD_CHAT_00002`; WireMock con `Fault.CONNECTION_RESET_BY_PEER` tras algunas líneas → error en el `Flux` después de los fragmentos ya emitidos
- [X] T038 [P] [US3] Ampliar `ChatControllerTests` y `ChatStreamingIntegrationTests`: `{"message":"   "}`, `{"message":null}`, `{}` y mensaje de 4001 caracteres → `400` JSON con `SLHD_CHAT_00001` y WireMock **sin** peticiones recibidas; `{"message":` (JSON ilegible) → `400` con `SLHD_GENE_00002`; Ollama detenido (WireMock sin stub o puerto cerrado) → `503` JSON con `SLHD_CHAT_00002` incluso con `Accept: text/event-stream`; Ollama cortando la conexión a mitad → el cliente recibe los `token` previos, un último `event:error` con `SLHD_CHAT_00003` y la conexión se cierra (sin `done`); cabecera `X-Request-Id` devuelta tanto en éxito como en error
- [X] T039 [US3] Ejecutar las pruebas ampliadas y comprobar que **fallan**; anotar en `SPEC/validation.md`

### Implementation for User Story 3

- [X] T040 [US3] Ampliar `StreamChatUseCase`: validar el comando al inicio (`message` no nulo ni en blanco y ≤ `ChatProperties.maxMessageLength`) y lanzar `ExceptionUtil`→`BusinessException(INVALID_CHAT_MESSAGE)` antes de tocar el puerto; con un indicador «ya emitió» (`AtomicBoolean` creado por suscripción con `Flux.defer`) convertir un error posterior al primer fragmento en `ErrorEvent(CHAT_STREAM_FAILED)` seguido de completar, y dejar pasar como error el anterior al primer fragmento; llamar a `recordResponse(ERROR, ...)` en ambos casos; no registrar el contenido del mensaje
- [X] T041 [P] [US3] Ampliar `OllamaChatAdapter`: `onErrorMap` de fallos de conexión/HTTP del cliente a `TechnicalException` con `CHAT_MODEL_UNAVAILABLE` (conservando la causa) mientras no haya emitido fragmentos, y a `CHAT_STREAM_FAILED` si ocurre después; sin `.block()`
- [X] T042 [US3] Completar `ChatEventMapper` y el controlador para que `ErrorEvent` se emita como `event:error` con `{code,message}` (la descripción sale de `ResponseCodeEnum.defaultMessage`, nunca del mensaje de la excepción de la causa); verificar que un error previo al primer elemento sube al `GlobalExceptionHandler` y responde `503` (riesgo R4 de [research.md](research.md)); si el `Flux` fallido no llega al manejador con la respuesta sin confirmar, aplicar la contingencia de R4 (obtener el primer elemento con `Flux.switchOnFirst`/`next()` antes de devolver el flujo) y anotarlo en `SPEC/research.md`
- [X] T043 [US3] Ejecutar las pruebas de T039 y comprobar que pasan; ejecutar `./mvnw -B -ntp test` completo

**Checkpoint**: US1–US3 completas; el chat falla de forma controlada y diagnosticable.

---

## Phase 6: User Story 4 - Cancelar liberando recursos (Priority: P3)

**Goal**: al cerrar el cliente, el servidor cancela la generación y registra la respuesta como `cancelled`, sin
afectar a las peticiones siguientes.

**Independent Test**: `ChatStreamingIntegrationTests` (cancelación) en verde y el paso 5 de
[quickstart.md](quickstart.md) con Ollama real.

### Tests for User Story 4 ⚠️ escribir primero y comprobar que fallan

- [X] T044 [P] [US4] Ampliar `StreamChatUseCaseTests`: al cancelar la suscripción (`StepVerifier.cancel()`) tras el primer fragmento se llama a `recordResponse(CANCELLED, ...)` una sola vez y no se llama a `COMPLETED` ni `ERROR`; la suscripción al puerto del modelo queda cancelada (publicador de prueba que registra la cancelación)
- [X] T045 [US4] Ampliar `ChatStreamingIntegrationTests`: con WireMock sirviendo una respuesta larga y lenta, el cliente cierra la conexión tras recibir el primer evento; en < 2 s `shoplab_chat_response_seconds_count{...outcome="cancelled"...}` aumenta en 1 (esperar con Awaitility) y una petición nueva inmediata recibe su primer evento dentro del límite (SC-005, escenario US4-2)
- [X] T046 [US4] Ejecutar las pruebas de T044–T045 y comprobar que **fallan**; anotar en `SPEC/validation.md`

### Implementation for User Story 4

- [X] T047 [US4] Ampliar `StreamChatUseCase` con `doOnCancel` que llama a `recordResponse(CANCELLED, ...)` (usando el indicador de cierre para que una respuesta no se registre dos veces, p. ej. cancelación justo tras completar) y comprobar que la cancelación se propaga al `ChatModelPort`; si la caída del cliente no llega como cancelación al `Flux` (el contenedor solo la detecta al escribir), documentarlo en `SPEC/research.md` R1 y aplicar la mitigación mínima (p. ej. capturar el error de escritura del cliente cerrado y tratarlo como cancelación)
- [X] T048 [US4] Ejecutar las pruebas de T046 y comprobar que pasan
- [X] T049 [US4] Con Ollama real, ejecutar el paso 5 de [quickstart.md](quickstart.md) (`Ctrl+C` a mitad de una respuesta larga): comprobar que la actividad de Ollama baja, que `outcome="cancelled"` sube y que una petición posterior cumple el paso 3; anotar el resultado en `SPEC/validation.md`

**Checkpoint**: las cuatro historias funcionan de forma independiente.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T050 [P] Crear `docs/adr/0010-modulo-shared-errores.md` (estado, contexto, decisión, alternativas descartadas y consecuencias, con la plantilla de `docs/adr/README.md`) y añadirlo al índice; actualizar `docs/adr/0004-spring-boot-4-spring-ai-2.md` solo si cita la lista de módulos como cerrada (la decisión de Spring AI/Ollama ya está en ADR-0003/0004)
- [X] T051 [P] Actualizar `README.md` (lista de módulos del backend: ahora cinco, con `shared` como módulo abierto, y enlace al ADR-0010) y crear `docs/chat-streaming.md` con cómo probar el endpoint desde la terminal (`curl -N`), el formato de eventos (enlazando [contracts/chat-stream.md](contracts/chat-stream.md)), las variables `OLLAMA_CHAT_MODEL`/`OLLAMA_BASE_URL`, el límite de 4000 caracteres y la nota sobre proxies sin buffering (FR-017, SC-008)
- [X] T052 Generar `SPEC/requests.http` según la regla «HTTP test requests after implementation» de `CLAUDE.md`: una petición por requisito y escenario de la spec (camino feliz, mensaje vacío, mensaje de 4000 y de 4001 caracteres, JSON ilegible, modelo caído), con `{{baseUrl}}` = `http://localhost:8080` y aserciones solo de estado; los requisitos no alcanzables por HTTP (cancelación, métricas internas) se listan en el bloque «Not covered here»
- [X] T053 Ejecutar `./mvnw -B -ntp clean verify` en `backend/` (pruebas, Checkstyle, PMD y JaCoCo ≥ 80 %); corregir las violaciones y comprobar que la cobertura no baja de la línea base de T001
- [X] T054 Recorrer [quickstart.md](quickstart.md) completo con Ollama real y registrar en `SPEC/validation.md` el resultado de cada paso frente al esperado, incluyendo SC-001 (95 % < 2 s), SC-005 y SC-008 (tiempo de un desarrollador nuevo siguiendo solo la documentación)
- [X] T055 Antes de commitear: `git status --short` y `git diff --staged`; confirmar que solo entran cambios intencionados (sin `.specify/feature.json`, `target/` ni archivos temporales) y que el título de la PR será `feat(chat): [LAB-12] respuesta del chat en streaming (SSE)`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias. T002 y T003 editan `pom.xml` y van en ese orden; T004 y T005 son paralelas.
- **Foundational (Phase 2)**: depende de T002–T003. Bloquea todas las historias. T006 → T009; T007 → T012; T008 → T013.
- **US1 (P1)** → depende de Phase 2. Es el MVP.
- **US2 (P1)** → depende de US1 (amplía el mismo caso de uso e integración).
- **US3 (P2)** → depende de US1; puede ir antes que US2 si hace falta, pero comparten `StreamChatUseCase` y las clases de prueba, por lo que se hacen en secuencia.
- **US4 (P3)** → depende de US2 (usa `recordResponse` y la métrica `outcome`).
- **Polish** → depende de las historias que se entreguen.

### Within Each User Story

- Las pruebas se escriben primero y se ve cómo fallan; luego el código.
- Dominio y puertos (Phase 2) → caso de uso → adaptadores → controlador.
- La historia se cierra con sus pruebas en verde y su paso de [quickstart.md](quickstart.md).

### Parallel Opportunities

- Setup: T004 ∥ T005 (tras T002–T003 si T005 necesita el adaptador resuelto).
- Foundational: T007 ∥ T008 (pruebas); T010 ∥ T011 ∥ T014 ∥ T015 ∥ T016.
- US1: pruebas T018 ∥ T019 ∥ T020 ∥ T021; implementación T024 ∥ T025.
- US2: T028 ∥ T029; US3: T036 ∥ T037 ∥ T038, y T041 ∥ T040.
- Polish: T050 ∥ T051.

### Parallel Example: User Story 1

```text
Task: "Crear StreamChatUseCaseTests.java"        (TEST/chat/application/usecase/)
Task: "Crear OllamaChatAdapterTests.java"        (TEST/chat/infrastructure/out/ollama/)
Task: "Crear ChatControllerTests.java"           (TEST/chat/infrastructure/in/rest/)
Task: "Crear ChatStreamingIntegrationTests.java" (TEST/chat/)
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 (T001–T005) y Phase 2 (T006–T017).
2. Phase 3: US1 (T018–T027).
3. **STOP y validar**: pruebas en verde y paso 2 de [quickstart.md](quickstart.md) con Ollama real.
4. Es demostrable: el chat responde en streaming. Aún sin métricas, errores finos ni cancelación explícita.

### Incremental Delivery

1. US1 → MVP del chat en streaming.
2. + US2 → criterio de latencia verificable y métricas.
3. + US3 → errores 400/503 y evento `error`.
4. + US4 → cancelación con recursos liberados.
5. Polish → ADR-0010, documentación, `requests.http`, `verify` completo y quickstart real.

Una única pull request (`feat(chat): [LAB-12] …`) al final; las fases son puntos de validación internos.

---

## Notes

- `[P]` = archivos distintos y sin dependencias pendientes; `[US#]` enlaza cada tarea con su historia del spec.
- **Ajustes respecto a `plan.md`/`research.md`** (se reflejan aquí; los documentos se actualizan en T050/T051 si procede):
  - No se añade `spring-boot-starter-validation`: el mensaje se valida una sola vez, en el caso de uso, con
    `SLHD_CHAT_00001`; el JSON ilegible lo traduce el manejador global a `SLHD_GENE_00002`.
  - En lugar de excepciones con nombre propio (`ChatModelUnavailableException`…), se usan `BusinessException` y
    `TechnicalException` con el `ResponseCodeEnum` correspondiente; el manejador resuelve el estado HTTP desde el
    código.
  - `MockMvc` no está en el classpath actual (ya lo señaló LAB-102); T003 añade el starter de pruebas de MVC.
- Todas las pruebas son deterministas y sin modelo real; solo T035, T049 y T054 usan Ollama y no corren en CI.
- Los paquetes `dto`, `config`, `model` y `exception` quedan sin lógica (excluidos de JaCoCo); la lógica vive en
  `application`, `infrastructure` y el manejador/filtro de `shared`.
- Fuera de alcance: historial, RAG, autenticación y frontend (FR-018).
