# Tasks: Backend reactivo con Spring WebFlux

**Input**: Design documents from `/specs/LAB-111-webflux-reactive-backend/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: se piden (constitución, principio IV; FR-009 y FR-010). Cada prueba nueva se escribe antes del código que la
satisface y se comprueba que falla. Ninguna prueba usa un modelo real: Ollama se simula con WireMock.

**Organization**: tareas agrupadas por historia de usuario en orden de prioridad: **US1 (P1) → US2 (P1) → US3 (P1) →
US4 (P2) → US5 (P2)**. La red de seguridad (golden file y línea base de métricas, capturados **en MVC antes de
migrar**) va en la fase 1; el cambio de plataforma y el pegamento web, que bloquean a todas las historias, en la fase 2.

Abreviaturas de rutas (todas relativas a la raíz del repositorio):

- `MAIN` = `backend/src/main/java/com/shoplab/helpdesk`
- `TEST` = `backend/src/test/java/com/shoplab/helpdesk`
- `RES` = `backend/src/test/resources`
- `SPEC` = `specs/LAB-111-webflux-reactive-backend`

Decisiones y riesgos: [research.md](research.md). Qué no puede cambiar: [contracts/compatibility.md](contracts/compatibility.md).
Ayudante bloqueante y reglas ArchUnit: [contracts/blocking-isolation.md](contracts/blocking-isolation.md).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de `spec.md` a la que pertenece (US1…US5)
- Todo identificador de código en inglés, líneas ≤ 121 caracteres, llaves en todos los bloques (Checkstyle/PMD).
- Los comandos Maven se ejecutan en `backend/` con `./mvnw -B -ntp ...`.

## Phase 1: Setup — red de seguridad en MVC (antes de tocar el `pom.xml`)

**Purpose**: fijar, con la build actual, lo que la migración no puede cambiar. Estas tareas **no modifican código de
producción**.

- [X] T001 Ejecutar `./mvnw -B -ntp clean verify` sin cambios y anotar resultado y cobertura de líneas JaCoCo como «Línea base» en `SPEC/validation.md` (créalo)
- [X] T002 Crear `TEST/chat/ChatStreamContractTests.java` (`@SpringBootTest(RANDOM_PORT)` + WireMock con `OllamaStubs.fixtureBody()`, cliente `java.net.http.HttpClient`) que lee el cuerpo SSE **crudo** de `POST /api/chat` y lo compara byte a byte con `RES/chat/golden-stream.txt`; con `-Dgolden.record=true` en lugar de comparar escribe `RES/chat/golden-stream.txt` y, tras el flujo, escribe en `SPEC/metrics-baseline.txt` la lista ordenada y sin duplicados de `nombre{claves-de-etiqueta}` (sin valores) de `GET /actuator/prometheus`; la fixture debe incluir tildes, eñes y un emoji
- [X] T003 Ejecutar `./mvnw -B -ntp test -Dtest=ChatStreamContractTests -Dgolden.record=true` en la rama base (MVC), revisar a mano `RES/chat/golden-stream.txt` (formato `event:token`/`data:{"content":...}` y `event:done`) y `SPEC/metrics-baseline.txt`, volver a ejecutar sin `-Dgolden.record` y comprobar que pasa
- [X] T004 [P] Buscar en `infra/` (dashboards de Grafana, reglas de Prometheus) qué series usan los paneles de LAB-102 (`grep -rn "tomcat_\|http_server_requests\|jvm_threads" infra/`) y anotar en `SPEC/contracts/compatibility.md` §4 cuáles de las «Diferencias aceptadas» afectan a un panel o regla
- [ ] T005 Hacer commit de la red de seguridad (`test(chat): [LAB-111] record golden SSE stream and metrics baseline`) para que la comparación posterior sea contra una referencia versionada

**Checkpoint**: golden file y línea base de métricas versionados; la suite en MVC sigue verde.

---

## Phase 2: Foundational — cambio de plataforma (bloquea a todas las historias)

**Purpose**: sustituir Tomcat/MVC por Netty/WebFlux y reescribir las cuatro piezas de borde. Al terminar, la suite
entera vuelve a verde sobre WebFlux. Cierra los riesgos 1, 3 y 5 de [research.md](research.md).

### Tests ⚠️ escribir primero y comprobar que fallan

- [X] T006 Reescribir `TEST/shared/infrastructure/in/rest/filter/RequestIdFilterTests.java` para `WebFilter`: `MockServerWebExchange` + `StepVerifier` sobre `filter.filter(exchange, chain)`; mismos casos de LAB-12 (genera UUID si falta; conserva un valor que cumple `^[A-Za-z0-9._-]{1,64}$`; sustituye uno de 65 caracteres o con salto de línea; devuelve el valor en la cabecera `X-Request-Id`) más: el valor queda en el atributo `requestId` del exchange y en el contexto de Reactor (`Mono.deferContextual` dentro de la cadena)
- [X] T007 [P] Reescribir `TEST/shared/infrastructure/in/rest/handler/GlobalExceptionHandlerTests.java` con `@WebFluxTest` y el `ProbeController` anidado + `WebTestClient`: `BusinessException` → 400 `SLHD_CHAT_00001`; `TechnicalException(CHAT_MODEL_UNAVAILABLE)` → 503 `SLHD_CHAT_00002`; excepción inesperada → 500 `SLHD_GENE_00001` sin el texto `secret internal detail`; cuerpo JSON ilegible (`ServerWebInputException`) → 400 `SLHD_GENE_00002`; con `Accept: text/event-stream` el error sigue siendo `application/json` y **no** 406 (riesgo 3 de research.md)
- [X] T008 [P] Reescribir `TEST/chat/infrastructure/in/rest/ChatControllerTests.java` con `@WebFluxTest(ChatController.class)` + `@MockitoBean StreamChatServicePort` + `WebTestClient`, importando `RequestIdFilter` y `GlobalExceptionHandler`: eventos `token`/`done` en orden con `returnResult(String.class).getResponseBody()` y `StepVerifier`; cabeceras `Cache-Control: no-cache` y `X-Accel-Buffering: no`; el comando recibe `message` y `requestId` (de `X-Request-Id` o generado); `Flux.error(INVALID_CHAT_MESSAGE)` → 400 JSON; `Flux.error(CHAT_MODEL_UNAVAILABLE)` con `Accept: text/event-stream` → 503 JSON; cuerpo ilegible → 400 `SLHD_GENE_00002`

### Implementation

- [X] T009 En `backend/pom.xml` reemplazar `spring-boot-starter-webmvc` por `spring-boot-starter-webflux` y `spring-boot-starter-webmvc-test` por `spring-boot-starter-webflux-test`; ejecutar `./mvnw -B -ntp dependency:tree` y anotar en `SPEC/research.md` R1 los artefactos y paquetes reales de Boot 4.1.2 (`@WebFluxTest`, `WebTestClient`); comprobar que no queda `spring-boot-starter-tomcat`/`spring-webmvc` en el árbol (FR-001) y que Spring AI 2.0.2 arranca sin servlet
- [X] T010 En `backend/src/main/resources/application.yml` añadir `spring.reactor.context-propagation: auto`; en `backend/pom.xml` añadir `io.micrometer:context-propagation` si el árbol de T009 no lo trae; registrar el acceso al `MDC` para la clave `requestId` en una clase de configuración `MAIN/shared/config/ReactorContextConfig.java` (excluida de cobertura por estar en `config`) según R7 de research.md; si el accesor de MDC no está disponible en esta versión, omitir y anotar en R7 que se mantiene `requestId` explícito en los logs
- [X] T011 Reescribir `MAIN/shared/infrastructure/in/rest/filter/RequestIdFilter.java` como `WebFilter` con `@Order(Ordered.HIGHEST_PRECEDENCE)`: mismas reglas de validación (`^[A-Za-z0-9._-]{1,64}$`, UUID si falta o es inseguro), atributo `requestId` en el exchange, cabecera `X-Request-Id` en la respuesta, `chain.filter(exchange).contextWrite(ctx -> ctx.put(MDC_KEY, id))`; conservar las constantes públicas `HEADER`, `MDC_KEY`, `REQUEST_ATTRIBUTE`; hace pasar T006
- [X] T012 Reescribir `MAIN/shared/infrastructure/in/rest/handler/GlobalExceptionHandler.java` para WebFlux (`@RestControllerAdvice`, **único** traductor de excepciones a HTTP): manejar `HelpdeskException` (log `error` si 5xx, `warn` si no; sin contenido del cliente), `ServerWebInputException` → `INVALID_REQUEST_BODY` y `Exception` → `UNEXPECTED_ERROR`; devolver siempre `Content-Type: application/json` y **eliminar** `HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE` del `ServerWebExchange` antes de responder para evitar el 406 con clientes SSE; hace pasar T007
- [X] T013 Actualizar `MAIN/chat/infrastructure/in/rest/ChatController.java`: sustituir `@RequestAttribute` servlet por `@RequestAttribute` de WebFlux sobre el atributo `RequestIdFilter.REQUEST_ATTRIBUTE` (o `ServerWebExchange`), conservar `ResponseEntity<Flux<ServerSentEvent<Object>>>`, `produces = text/event-stream` y las cabeceras `Cache-Control: no-cache` y `X-Accel-Buffering: no`; **no** tocar `ChatEventMapper`, DTOs, puertos ni caso de uso; hace pasar T008
- [X] T014 Ejecutar `./mvnw -B -ntp test`: deben pasar `ChatStreamContractTests` (golden, **mismos bytes** que en MVC), `ModularityTests` (5 módulos), `MetricsEndpointTests`, `StreamChatUseCaseTests`, `OllamaChatAdapterTests` y las pruebas de T006–T008; si el golden difiere, corregir el controlador/codec (no el golden) y anotar la causa en `SPEC/research.md` R2 riesgo 1
- [X] T015 [P] Añadir `TEST/shared/infrastructure/in/rest/filter/RequestIdPropagationTests.java`: dentro de un `Flux` que salta a `Schedulers.boundedElastic()`, un `doOnNext` lee `MDC.get("requestId")` y es igual al valor escrito por el filtro (FR-013); si T010 decidió omitir el accesor de MDC, probar en su lugar que el valor está en el contexto de Reactor

**Checkpoint**: backend en Netty, contrato de bytes idéntico, errores previos al flujo en JSON, correlación propagada. Las historias pueden empezar.

---

## Phase 3: User Story 1 — El chat sigue funcionando igual tras la migración (Priority: P1) 🎯 MVP

**Goal**: los mismos escenarios de LAB-12 pasan contra el backend migrado, ya escritos con `WebTestClient` y
`StepVerifier`.

**Independent Test**: `./mvnw -B -ntp test -Dtest='Chat*Tests'` en verde, y los pasos 2 y 3 de [quickstart.md](quickstart.md).

### Tests

- [X] T016 [US1] Migrar `TEST/chat/ChatStreamingIntegrationTests.java` a `WebTestClient.bindToServer().baseUrl("http://localhost:" + port)` con `responseTimeout` de 30 s, conservando los 10 escenarios actuales: respuesta completa en orden terminando en `done`, 5 peticiones concurrentes sin mezclar fragmentos, primer token mientras el modelo sigue generando (`Flux.timestamp()`/`elapsed()` para medir: primer `token` < 1000 ms y `done` > 2500 ms con ≥ 10 `token`), métricas en Prometheus, 400 para `{"message":"   "}`/`""`/`null`/`{}` con `X-Request-Id` devuelto y **0** llamadas a Ollama, límite 4001 → 400 y 4000 → 200, JSON ilegible → 400 `SLHD_GENE_00002`, modelo ausente (404 de Ollama) → 503 `SLHD_CHAT_00002` en JSON, eco de `X-Request-Id` en éxito, y el caso de cancelación (queda para T022)
- [X] T017 [P] [US1] Migrar `TEST/chat/ChatModelDownIntegrationTests.java` y `TEST/chat/ChatMidStreamFailureIntegrationTests.java` a `WebTestClient`/`StepVerifier`: Ollama caído antes del primer evento → 503 JSON `SLHD_CHAT_00002`; corte a mitad (con `RawOllamaServer`) → eventos `token` seguidos de un único `error` con `SLHD_CHAT_00003` y cierre del flujo
- [X] T018 [P] [US1] Añadir a `TEST/chat/ChatStreamingIntegrationTests.java` una prueba UTF-8: con la fixture de T002 (tildes, eñes, emoji) la concatenación de los `content` de los `token` es exactamente el texto de la fixture, sin cortar ningún carácter entre eventos (FR-016 de LAB-12, SC-001)
- [X] T019 [US1] Eliminar `TEST/chat/SseTestClient.java` cuando ningún test lo use (`grep -rn SseTestClient backend/src/test`) y comprobar que `./mvnw -B -ntp test` sigue verde

### Implementation

- [X] T020 [US1] Si alguna prueba de T016–T018 falla por una diferencia real de comportamiento (no de test), corregir el pegamento web de la fase 2 sin tocar `chat/domain`, `chat/application` ni los adaptadores de salida; documentar cada corrección en `SPEC/research.md` bajo el riesgo correspondiente
- [X] T021 [US1] Ejecutar los pasos 2 y 3 de [quickstart.md](quickstart.md) con Ollama real (`curl -N`, 10 mediciones de `time_starttransfer`) y registrar resultados en `SPEC/validation.md` (SC-002: ≥ 9 de 10 por debajo de 2 s)

**Checkpoint**: US1 completa — el contrato de LAB-12 se demuestra intacto con tests reactivos y con Ollama real.

---

## Phase 4: User Story 2 — Cancelar al cliente cancela al modelo (Priority: P1)

**Goal**: el cierre del cliente cancela la suscripción al modelo sin tokens huérfanos.

**Independent Test**: `./mvnw -B -ntp test -Dtest='*Cancel*'` en verde y el paso 4 de [quickstart.md](quickstart.md).

### Tests ⚠️ escribir primero y comprobar que fallan o pasan por la razón correcta

- [X] T022 [US2] Añadir a `TEST/chat/ChatStreamingIntegrationTests.java` la prueba de cancelación: Ollama simulado con `withChunkedDribbleDelay(40, 8000)`, `WebTestClient` + `StepVerifier.create(flux).expectNextCount(1).thenCancel().verify()`; esperar con Awaitility (≤ 3 s) a que `shoplab_chat_response_seconds_count{outcome="cancelled"}` suba exactamente 1 y que **no** suba `outcome="error"`; después, una petición nueva responde 200 con `done` (US2.2, US2.3, SC-003)
- [X] T023 [P] [US2] Añadir a `TEST/chat/application/usecase/StreamChatUseCaseTests.java` una prueba con `TestPublisher<String>` como `ChatModelPort`: tras emitir un fragmento, `StepVerifier ... thenCancel()` deja `publisher.assertCancelled()` y `metrics.recordResponse(CANCELLED, ...)` se invoca una sola vez (sin `ERROR` ni `COMPLETED`)
- [X] T024 [P] [US2] Añadir a `TEST/chat/infrastructure/out/ollama/OllamaChatAdapterTests.java` una prueba de que cancelar el `Flux` de `streamAnswer` cancela la petición HTTP a Ollama (WireMock con respuesta lenta y comprobación de que la conexión se cierra; sin tokens emitidos tras la cancelación)

### Implementation

- [X] T025 [US2] Si T022–T024 revelan que algún operador absorbe la cancelación (p. ej. `onErrorResume`, `cache`, `publish`), corregirlo en `MAIN/chat/application/usecase/StreamChatUseCase.java` o `MAIN/chat/infrastructure/out/ollama/OllamaChatAdapter.java` manteniendo el contrato; si pasan sin cambios, anotarlo en `SPEC/research.md` R3
- [X] T026 [US2] Ejecutar el paso 4 de [quickstart.md](quickstart.md) con Ollama real (Ctrl+C a los 2 s) y registrar en `SPEC/validation.md` que la carga de Ollama baja en < 2 s y que `outcome="cancelled"` aumenta en 1 (SC-003)

**Checkpoint**: US2 completa — cancelación demostrada a nivel de unidad, integración y manualmente.

---

## Phase 5: User Story 3 — Nada bloquea el hilo de eventos (Priority: P1)

**Goal**: BlockHound activo en toda la suite, aislamiento de llamadas bloqueantes en `infrastructure` y reglas de
arquitectura que lo hacen cumplir.

**Independent Test**: `./mvnw -B -ntp test -Dtest='BlockHound*,BlockingCalls*,ReactiveArchitecture*'` en verde, y el paso 7 de [quickstart.md](quickstart.md).

### Tests ⚠️ escribir primero y comprobar que fallan

- [X] T027 [US3] Crear `TEST/blockhound/BlockHoundDetectionTests.java` (FR-009): (a) `Mono.fromCallable(() -> { Thread.sleep(10); return 1; }).subscribeOn(Schedulers.parallel())` falla con `BlockingOperationError` verificado con `StepVerifier.expectErrorMatches(e -> e.getCause() instanceof BlockingOperationError || e instanceof BlockingOperationError)`; (b) el mismo código dentro de `Schedulers.boundedElastic()` completa con `1`; debe **fallar** hasta que T029–T031 instalen BlockHound
- [X] T028 [P] [US3] Crear `TEST/shared/infrastructure/out/blocking/BlockingCallsTests.java` según [contracts/blocking-isolation.md](contracts/blocking-isolation.md): `call` ejecuta el `Callable` en un hilo cuyo nombre empieza por `boundedElastic-` (no en el del llamador), es perezoso (no ejecuta al construir), propaga la excepción como señal de error y mapea `null` a `Mono` vacío; `run` completa sin elemento; `stream` emite los elementos de la colección en orden; con BlockHound activo, un `Thread.sleep` dentro de `call` **no** falla; el contexto de Reactor (`requestId`) sigue visible aguas abajo
- [X] T029 [P] [US3] Crear `TEST/architecture/ReactiveArchitectureTests.java` con `ArchUnit` (`ClassFileImporter` sobre `com.shoplab.helpdesk`, solo clases de producción) para las reglas A1–A5 de [contracts/blocking-isolation.md](contracts/blocking-isolation.md): A1 `Schedulers` fuera de `..domain..`/`..application..`/`..in.rest..`; A2 sin `java.sql`/`javax.sql`/`org.springframework.jdbc` en `..domain..`/`..application..`; A3 `Schedulers` solo desde `..infrastructure..`; A4 sin `RestTemplate`, `RestClient`, `HttpURLConnection` ni `java.net.http.HttpClient`; A5 sin llamadas a `Mono.block*`, `Flux.blockFirst/blockLast/toIterable`; añadir una prueba que verifica que cada regla **sí** detecta una clase de ejemplo infractora (fixture en `TEST/architecture/violations/`, excluida del importado principal)

### Implementation

- [X] T030 [US3] En `backend/pom.xml` añadir `io.projectreactor.tools:blockhound:1.0.11.RELEASE` (scope `test`) y configurar `maven-surefire-plugin` con `<argLine>@{argLine} -XX:+AllowRedefinitionToAddDeleteMethods</argLine>` (conservar el `argLine` que inyecta JaCoCo `prepare-agent`); confirmar que la versión resuelve en Maven Central y que `./mvnw -B -ntp test` sigue arrancando con JaCoCo
- [X] T031 [US3] Crear `TEST/blockhound/HelpdeskBlockHoundIntegration.java` (`BlockHoundIntegration`; `applyTo(Builder)` con los allowances **mínimos**, uno por línea y con comentario del porqué) y `TEST/blockhound/BlockHoundSessionListener.java` (`LauncherSessionListener` que ejecuta `BlockHound.install(new HelpdeskBlockHoundIntegration())` una sola vez en `launcherSessionOpened`); registrar el listener en `RES/META-INF/services/org.junit.platform.launcher.LauncherSessionListener`; hace pasar T027
- [X] T032 [US3] Ejecutar `./mvnw -B -ntp test` con BlockHound activo y resolver los falsos positivos del riesgo 4 de research.md **uno por uno**: logging por consola de Logback (`ch.qos.logback.core.OutputStreamAppender`), `SecureRandom`/`UUID.randomUUID()` en el filtro (preferir calentar el generador en el arranque del filtro; si no, allowance por clase y método), y cualquier otro; prohibido permitir paquetes enteros ni `*`; registrar cada allowance y su motivo en `SPEC/research.md` R6
- [X] T033 [US3] Crear `MAIN/shared/infrastructure/out/blocking/BlockingCalls.java` (clase final sin estado, constructor privado): `call(Callable<T>)` → `Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic())`; `run(Runnable)` → `Mono.<Void>fromRunnable(runnable).subscribeOn(...)`; `stream(Callable<? extends Iterable<T>>)` → `call(...).flatMapMany(Flux::fromIterable)`; Javadoc que remita a `docs/adr/0011-web-reactiva-persistencia-jdbc.md`; hace pasar T028
- [X] T034 [US3] Hacer pasar T029: si alguna regla ArchUnit detecta un uso real en `src/main`, corregirlo (no relajar la regla); confirmar que `Schedulers` solo aparece en `BlockingCalls` y que no hay clientes HTTP bloqueantes propios (FR-005)
- [X] T035 [US3] Prueba manual de la guardia (no se commitea, paso 7 de [quickstart.md](quickstart.md)): añadir `Thread.sleep(50)` en un `doOnNext` de `StreamChatUseCase`, ejecutar `ChatStreamingIntegrationTests` y comprobar que falla con `BlockingOperationError`; revertir y anotar el resultado en `SPEC/validation.md`

**Checkpoint**: US3 completa — un bloqueo en el bucle de eventos o una violación de las reglas rompe la build.

---

## Phase 6: User Story 4 — Observabilidad intacta (Priority: P2)

**Goal**: `/actuator/prometheus` y las métricas de LAB-102 y LAB-12 se conservan.

**Independent Test**: `./mvnw -B -ntp test -Dtest='MetricsEndpointTests,ChatStreamingIntegrationTests'` y el paso 6 de [quickstart.md](quickstart.md).

### Tests

- [X] T036 [US4] Ejecutar `MetricsEndpointTests` sobre WebFlux (sus 6 pruebas: métricas JVM y HTTP, histograma, etiqueta `application`, health, `uri` por plantilla `/probe/{id}`, `/actuator/env` → 404) y corregir **la configuración**, no la prueba, si algo falla; la prueba usa `java.net.http.HttpClient` y puede migrarse a `WebTestClient` si se quiere uniformidad, sin cambiar sus aserciones
- [X] T037 [P] [US4] Añadir a `TEST/MetricsEndpointTests.java` o a `ChatStreamingIntegrationTests` una aserción de que tras una petición a `/api/chat` aparece `http_server_requests_seconds_count{...uri="/api/chat"...}` con `application="helpdesk-backend"` y que `shoplab_chat_first_token_seconds_bucket` y `shoplab_chat_response_seconds_count{outcome="completed"}` siguen presentes

### Implementation

- [X] T038 [US4] Con la app arrancada sobre WebFlux repetir la captura de T002 y comparar con `SPEC/metrics-baseline.txt` (`diff` de las listas ordenadas, paso 6 de [quickstart.md](quickstart.md)); cada serie distinta debe figurar en «Diferencias aceptadas» de `SPEC/contracts/compatibility.md` §4 (con la nota de T004 sobre paneles) o corregirse; guardar el resultado en `SPEC/validation.md`
- [X] T039 [US4] Abrir los dashboards de Grafana de LAB-102 contra el backend migrado (`docker compose -f infra/docker-compose.yml up -d`) y confirmar que los paneles muestran datos sin editar consultas; registrar capturas o la descripción en `SPEC/validation.md` (SC-007)

**Checkpoint**: US4 completa — sin regresión de métricas ni de paneles.

---

## Phase 7: User Story 5 — La decisión queda registrada (Priority: P2)

**Goal**: ADR-0011 con las tres justificaciones pedidas.

**Independent Test**: revisar el ADR y el índice (paso 8 de [quickstart.md](quickstart.md)).

- [X] T040 [US5] Crear `docs/adr/0011-web-reactiva-persistencia-jdbc.md` copiando `docs/adr/0000-plantilla.md` (título «ADR-0011: Web reactiva con persistencia JDBC», `Estado: aceptado`, fecha de hoy `AAAA-MM-DD`, borrar el comentario de instrucciones) en español, con el contenido de R9 de [research.md](research.md): **Contexto** (el chat es un flujo largo; pgvector, ChatMemory, Modulith y Flyway son JDBC; ADR-0002 y ADR-0004), **Decisión** (WebFlux/Netty en la capa web y el cliente del modelo; JDBC se queda y se aísla con `BlockingCalls` en `Schedulers.boundedElastic()` dentro de `infrastructure`; BlockHound y ArchUnit lo hacen cumplir), **Alternativas descartadas** (R2DBC; MVC con hilos virtuales; MVC devolviendo `Flux` como en LAB-12, reconociendo que funcionaba) y **Consecuencias** (contexto reactivo y MDC, `@Transactional` no cruza `boundedElastic`, pruebas más verbosas, regla «todo acceso bloqueante pasa por `BlockingCalls`», criterio para revisar con hilos virtuales)
- [X] T041 [US5] Añadir la fila `| 0011 | [Web reactiva con persistencia JDBC](0011-web-reactiva-persistencia-jdbc.md) | aceptado |` al índice de `docs/adr/README.md`, a continuación de la fila 0010

**Checkpoint**: US5 completa — decisión registrada y enlazada.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T042 [P] Buscar y actualizar menciones al modelo servlet en la documentación (`grep -rn -i "tomcat\|servlet\|webmvc\|MockMvc" README.md docs/ backend/ specs/*/quickstart.md --include=*.md`): solo notas de arquitectura vigentes (no specs históricas de otras historias); anotar el resultado en `SPEC/validation.md`
- [X] T043 [P] Generar `SPEC/requests.http` (IntelliJ HTTP Client) según la regla «HTTP test requests after implementation»: un caso por escenario HTTP de la spec con `{{baseUrl}}` = `http://localhost:8080` — éxito SSE (US1.1), mensaje vacío/ausente/4001 caracteres → 400 `SLHD_CHAT_00001` (US1.3, límite 4000 → 200), JSON ilegible → 400 `SLHD_GENE_00002`, `X-Request-Id` válido y no válido (eco/regeneración), `/actuator/prometheus` y `/actuator/health` (US4); comentario inicial «Not covered here» para cancelación, BlockHound, ArchUnit y ADR (no alcanzables por HTTP)
- [X] T044 Ejecutar la verificación completa de la guía de desarrollo en `backend/`: `./mvnw -B -ntp checkstyle:check`, `./mvnw -B -ntp verify` (JaCoCo ≥ 80 %, Checkstyle y PMD sin violaciones, BlockHound y ArchUnit en verde) y registrar la cobertura final frente a la línea base de T001 en `SPEC/validation.md` (SC-006)
- [X] T045 Recorrer [quickstart.md](quickstart.md) completo con Ollama real, incluido el paso 5 (50 streams simultáneos: `jvm_threads_live_threads` no crece de forma proporcional, SC-005), y marcar cada paso en `SPEC/validation.md`
- [X] T046 Revisar `git status --short` y `git diff` para descartar archivos sueltos (`*.orig`, `*.log`, capturas, `target/`), comprobar que `SseTestClient` ya no existe y que no hay cambios en `chat/domain`, `chat/application` ni `chat/infrastructure/out`; el título del PR será `refactor(backend): [LAB-111] backend reactivo con Spring WebFlux` (no genera versión nueva)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: sin dependencias; **debe terminar antes de tocar el `pom.xml`** (el golden file y la línea base solo se pueden capturar en MVC).
- **Phase 2 (Foundational)**: depende de la fase 1; **bloquea** las fases 3–7.
- **Phases 3–5 (US1, US2, US3, todas P1)**: dependen de la fase 2. US1 → US2 comparten `ChatStreamingIntegrationTests.java` (T016 antes de T022); US3 es independiente de ellas salvo por la suite verde.
- **Phases 6–7 (US4, US5, P2)**: dependen de la fase 2; US4 se apoya en que la suite de US1 esté verde para comparar métricas con tráfico real; US5 es independiente.
- **Phase 8 (Polish)**: depende de todas las historias que se vayan a entregar.

### Dependencies entre tareas

- T002 → T003 → T005 (golden y línea base antes del commit de referencia); T004 es independiente.
- T006/T007/T008 (tests) antes de T011/T012/T013 (código) respectivamente; T009 antes de todas (sin WebFlux no compilan los tests).
- T014 depende de T009–T013. T015 depende de T010–T011.
- T016 → T019 (no se borra `SseTestClient` hasta migrar todo) y T017/T018 → T019.
- T027–T029 (tests) antes de T030–T034; T030 → T031 → T032 (el listener necesita la dependencia y los allowances se descubren ejecutando la suite); T033 hace pasar T028; T034 hace pasar T029; T035 al final de la fase.
- T038 depende de T036 y de T002/T003 (línea base); T039 depende de T038.
- T040 → T041. T044 depende de T042 y T043 solo en que no deben dejar la build rota.

### Parallel Opportunities

- Fase 1: T004 en paralelo con T002–T003.
- Fase 2: T007 y T008 en paralelo con T006 (archivos distintos); T015 en paralelo con T012–T013.
- US1: T017 y T018 en paralelo tras T016.
- US2: T023 y T024 en paralelo entre sí (T022 comparte archivo con T016).
- US3: T028 y T029 en paralelo con T027.
- US4: T037 en paralelo con T036.
- Polish: T042 y T043 en paralelo.
- US3 y US5 pueden trabajarse por personas distintas una vez terminada la fase 2.

## Implementation Strategy

### MVP First (US1)

1. Fase 1 (red de seguridad en MVC) → 2. Fase 2 (plataforma) → 3. Fase 3 (US1): **parar y validar** que el contrato es
idéntico con el golden file y con Ollama real. Con eso la migración es utilizable, aunque aún sin la guardia de
BlockHound.

### Incremental Delivery

US1 (contrato) → US2 (cancelación) → US3 (guardia: BlockHound + ArchUnit + `BlockingCalls`) → US4 (métricas) → US5 (ADR).
Las tres historias P1 son el criterio de aceptación de la historia de Jira; US4 y US5 completan la definición de terminado.

### Riesgos que cubre cada fase (ver research.md)

| Riesgo | Fase / tarea |
|--------|--------------|
| 1. Bytes del SSE | T002–T003, T014 |
| 2. Nombres de artefactos de Boot 4.1.2 | T009 |
| 3. Errores previos al primer evento (406) | T007, T012 |
| 4. Falsos positivos de BlockHound | T031–T032 |
| 5. Propagación de MDC | T010, T015 |
| 6. Etiquetas de `http.server.requests` | T036–T039 |

## Notes

- La validación del mensaje sigue viviendo **en el caso de uso**, no en el borde REST (decisión de LAB-12); el
  `GlobalExceptionHandler` solo traduce.
- No se añade ningún acceso JDBC: el backend no lo tiene hoy. `BlockingCalls` queda probado pero sin consumidor
  (justificado en Complexity Tracking del plan).
- No tocar `CHANGELOG.md`, `.release-please-manifest.json` ni la versión del proyecto (release-please).
- Commits de rama: `type(scope): [LAB-111] descripción en inglés`; un commit por cambio lógico (p. ej. uno por fase).
