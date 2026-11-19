# Tasks: Trazas de LLM con Langfuse autoalojado

**Input**: Design documents from `/specs/LAB-64-langfuse-llm-traces/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: se piden (constitución, principio IV, test-first; FR-030): pruebas unitarias, de integración con WireMock y un
exportador en memoria, ArchUnit y BlockHound. Cada prueba se escribe antes que el código que la satisface y se comprueba que
**falla** (rojo) antes de implementar.

**Organization**: tareas agrupadas por historia de usuario. El orden de las fases no es el numérico de `spec.md`:
**US1 → US2 → US4 → US5 → US3**. US1 (compose) es independiente del código; US4 y US5 endurecen lo que construye US2; US3
(clúster) va al final porque necesita el backend ya publicado en una versión (ADR-0012: DEV despliega solo releases) y la
medición de recursos del nodo (riesgo R1 del plan).

Dos repositorios y abreviaturas de ruta (todas relativas a la raíz de cada repositorio):

- `HD` = `shoplab-helpdesk` (este repositorio). `CHAT` = `backend/src/main/java/com/shoplab/helpdesk/chat`;
  `CHAT_TEST` = `backend/src/test/java/com/shoplab/helpdesk/chat`.
- `INF` = `shoplab-infra` (clon hermano en `../shoplab-infra`).

Comandos de Maven desde `backend/` (`./mvnw -B -ntp -Dskip.srcclr=true …`). Nombres, atributos y valores exactos:
[contracts/](contracts/) y [data-model.md](data-model.md).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de `spec.md` (US1…US5)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base y cierre de las verificaciones S-1 y S-5 de [research.md](research.md) (R-11) antes de escribir código.

- [X] T001 En `HD`, en la rama `feature/LAB-64-langfuse-llm-traces` sin cambios de código, ejecutar `./mvnw -B -ntp -Dskip.srcclr=true clean verify` en `backend/` y anotar el resultado y el porcentaje de cobertura JaCoCo como línea base en `specs/LAB-64-langfuse-llm-traces/validation.md` (créalo con la sección «Línea base»)
- [X] T002 En `backend/pom.xml` añadir sin versión `io.opentelemetry:opentelemetry-sdk` y `io.opentelemetry:opentelemetry-exporter-otlp` (compile) y `io.opentelemetry:opentelemetry-sdk-testing` (test). Con red (`./mvnw -B -ntp dependency:tree -Dincludes=io.opentelemetry`) confirmar que el BOM de Spring Boot 4.1.2 fija su versión (S-5); si no la gestiona, añadir la propiedad `opentelemetry.version` con la última 1.x estable verificada en Maven Central. Comprobar que `./mvnw -B -ntp -Dskip.srcclr=true -DskipTests compile` y `-Dtest=ModuleArchitectureTests,ReactiveArchitectureTests test` siguen en verde (el cliente HTTP transitivo del exportador no debe romper `noBlockingHttpClients`). Anotar el resultado de S-5 en `research.md` R-11
- [X] T003 [P] Spike S-1: en `CHAT_TEST/OllamaStubs.java` añadir `bodyWithUsage(int promptTokens, int outputTokens, String... fragments)` (igual que `body(...)` pero con `"prompt_eval_count"` y `"eval_count"` en la línea final `done:true`). Crear el test desechable `CHAT_TEST/infrastructure/out/ollama/UsageSpikeTests.java` (`@SpringBootTest(webEnvironment = NONE)` + `@PostgresTest` + WireMock como `ToolCompositionIntegrationTests`) que inyecta el `ChatModel`, hace `stream(new Prompt("hola"))` contra ese stub y comprueba si algún `ChatResponse.getMetadata().getUsage()` trae 11 y 7. Anotar en `research.md` R-11/S-1 si Spring AI 2.0.2 expone el uso en streaming y si el uso ausente llega como `EmptyUsage` (ceros), y borrar el test desechable (el helper se queda)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: dominio, puertos, implementación nula y cambio del caso de uso, sin cambio de comportamiento observable. Bloquea US2, US4 y US5 (US1 no depende de esta fase).

**⚠️ CRITICAL**: ninguna historia de código puede empezar hasta terminar esta fase.

- [X] T004 [P] Pruebas (rojo) del dominio en `CHAT_TEST/domain/model/TracingDomainTests.java`: `TokenUsage` (`inputTokens`/`outputTokens` `Integer` anulables, «≥ 0 si existe»; `null` = desconocido y nunca 0; `total()` vacío si falta alguno; `isKnown()`; `plus(...)` suma vueltas); `PromptMessage` (`role` ∈ `system`/`user`/`assistant`, `content` no nulo); `RetrievalTrace` (`candidates` ≥ 0, `delivered` lista inmutable); `GenerationTrace` y `ToolCallTrace` (`name` no vacío); `TurnFailure` (`code` y `stage` obligatorios)
- [X] T005 [P] ArchUnit: en `backend/src/test/java/com/shoplab/helpdesk/architecture/violations/domain/` crear `OpenTelemetryInDomain.java` (una clase que importa `io.opentelemetry.api.trace.Span`) y en `ModuleArchitectureTests.java` un test `theDomainRuleRejectsOpenTelemetryAndNamesTheClass` análogo a `theDomainRuleRejectsSpringAiAndReactorAndNamesTheClass` (rojo); después añadir `"io.opentelemetry.."` a `FORBIDDEN_IN_DOMAIN` en `ModuleArchitectureRules.java` y comprobar verde con `-Dtest=ModuleArchitectureTests test`
- [X] T006 Crear el dominio de `data-model.md` (depende de T004; sin Spring AI, Reactor ni OTel): en `CHAT/domain/model/` los registros `TokenUsage`, `PromptMessage`, `RetrievalTrace`, `GenerationTrace`, `ToolCallTrace`, `TurnFailure`; en `CHAT/domain/model/enums/` `TurnResult` (`COMPLETED`, `NO_CONTEXT`, `CANCELLED`, `ERROR`) y `FailureStage` (`RETRIEVAL`, `GENERATION`, `TOOL`); en `CHAT/domain/model/constants/TracingConstants.java` (`@UtilityClass`) la clave de contexto `TURN_TRACE_KEY`, los valores `usage_status` (`known`, `unknown`) y `cost_status` (`priced`, `unpriced`, `unknown_usage`) y el sufijo de truncado `…[truncated]`. Hacer verde T004
- [X] T007 Crear en `CHAT/application/port/out/` los puertos de `data-model.md`: `ChatTracePort` (`Flux<ChatEvent> traceTurn(StreamChatCommand command, Function<TurnTrace, Flux<ChatEvent>> turn)`), `TurnTrace` (`historyTurns(int)`, `retrieval(RetrievalTrace, Duration)`, `fixedMessageAnswer()`, `startGeneration(String model, Map<String,Object> parameters, List<PromptMessage> messages)` → `GenerationScope`, `toolCall(ToolCallTrace, Duration)`, constante `NONE` que ignora todo y cuyo `startGeneration` devuelve `GenerationScope.NONE`) y `GenerationScope` (`firstToken()`, `complete(TokenUsage, String answer)`, `fail(Throwable)`, `cancel(String partial)`, constante `NONE`). Documentar en cada método que nunca lanza excepciones al chat. Añadir `historyTurns(int)` a la sección «Puertos» de `data-model.md` (hace falta para `metadata.history_turns`)
- [X] T008 [P] Pruebas (rojo) de la implementación nula en `CHAT_TEST/infrastructure/out/tracing/NoOpChatTraceTests.java`: `traceTurn` devuelve los mismos eventos y en el mismo orden, propaga el error de la fuente y propaga la cancelación; el `TurnTrace` que entrega es `TurnTrace.NONE` (depende de T007)
- [X] T009 [P] Pruebas (rojo) de la configuración en `CHAT_TEST/config/TracingPropertiesTests.java` y `CHAT_TEST/config/TracingConfigNoOpTests.java` (`ApplicationContextRunner`): valores por defecto de `contracts/configuration.md` (`enabled=false`, `endpoint=http://localhost:3001/api/public/otel`, `max-content-characters=20000`, `queue-size=2048`, `batch-size=256`, `schedule-delay=2s`, `export-timeout=5s`, `shutdown-timeout=5s`, `environment=local`); validación «`queue-size`, `batch-size` y `max-content-characters` > 0; `batch-size` ≤ `queue-size`» y «los precios son ≥ 0»; con `enabled` ausente o `false` el contexto contiene un `NoOpChatTrace` y **ningún** bean de OpenTelemetry. Seguir el estilo de `RagPropertiesTests` y `RagConfigurationBindingTests`
- [X] T010 Crear `CHAT/infrastructure/out/tracing/NoOpChatTrace.java` (implementa `ChatTracePort`, sin anotación de componente; ejecuta la función con `TurnTrace.NONE`). Hacer verde T008
- [X] T011 Crear `CHAT/config/TracingProperties.java` (`record` con `@ConfigurationProperties("shoplab.chat.tracing")`, los campos y validaciones de `contracts/configuration.md`, y `Map<String, Price> pricing` con `Price(inputPerMillion, outputPerMillion)` ≥ 0) y `CHAT/config/TracingConfig.java` con un único `@Bean ChatTracePort chatTracePort(TracingProperties)` que devuelve `NoOpChatTrace` (la rama de Langfuse se añade en T034). Registrar `TracingProperties` en `@EnableConfigurationProperties` de `CHAT/config/ChatConfig.java`. Hacer verde T009
- [X] T012 [P] Pruebas (rojo) en `CHAT_TEST/application/usecase/StreamChatUseCaseTraceTests.java` con un `ChatTracePort` falso que registra las llamadas: el caso de uso abre el turno con el `StreamChatCommand`; informa `historyTurns` con los turnos de memoria cargados; informa `retrieval` con `searchText` (el de `history.searchText(...)`), `candidates`, `delivered` (los `ContextFragment` con su `score`, en el orden de las citas) y `failed=false`; con contexto vacío y `fixed-message` llama a `fixedMessageAnswer()`; un mensaje inválido (`validate`) lanza antes de abrir el turno; los eventos emitidos son idénticos a los de hoy (depende de T007)
- [X] T013 Modificar `CHAT/application/usecase/StreamChatUseCase.java`: nuevo último parámetro de constructor `ChatTracePort chatTrace`; tras `validate(command)`, envolver el flujo actual en `chatTrace.traceTurn(command, trace -> …)` y llamar a `trace.historyTurns(...)`, `trace.retrieval(...)` (desde `recordContext`, con la duración ya medida) y `trace.fixedMessageAnswer()` donde corresponda, sin cambiar eventos, métricas ni logs. Actualizar los tres constructores existentes en `CHAT_TEST/application/usecase/StreamChatUseCaseTests.java` (líneas ~84 y ~721) y `StreamChatUseCaseMemoryTests.java` (~77) para pasar `new NoOpChatTrace()`. Hacer verde T012 y toda la suite `-Dtest='StreamChatUseCase*' test`

**Checkpoint**: el chat funciona exactamente igual que antes (suite completa en verde) y existe el punto de enganche para las trazas.

---

## Phase 3: User Story 1 - Levantar Langfuse en local con el perfil `llmops` (Priority: P1) 🎯 MVP

**Goal**: `--profile llmops` arranca Langfuse v4 con sus cuatro almacenes, ya inicializado (organización, proyecto, usuario, claves), con versiones fijas, salud, volúmenes con nombre y sin tocar el resto de perfiles.

**Independent Test**: levantar el perfil, abrir `http://localhost:3001` con las credenciales locales y ver el proyecto con sus claves, sin pasos manuales (quickstart §1).

- [X] T014 [P] [US1] Añadir al final de `infra/.env.example` el bloque `# Perfil llmops (LAB-64)` con las variables de `contracts/configuration.md` («Docker Compose»): imágenes (`LANGFUSE_WEB_IMAGE`, `LANGFUSE_WORKER_IMAGE`, `LANGFUSE_CLICKHOUSE_IMAGE`, `LANGFUSE_REDIS_IMAGE`, `LANGFUSE_POSTGRES_IMAGE`, `LANGFUSE_MINIO_IMAGE` con los valores de `research.md` R-8, MinIO por digest), puertos (`LANGFUSE_PORT=3001`, `LANGFUSE_MINIO_PORT=9092`), claves e inicialización (`LANGFUSE_INIT_*`) y secretos locales con marcadores obvios (`change-me-local-only`; `LANGFUSE_ENCRYPTION_KEY` de 64 caracteres hexadecimales evidentes, p. ej. `0123456789abcdef` repetido 4 veces), con el aviso «solo para uso local»
- [X] T015 [US1] En `infra/docker-compose.yml` añadir los cuatro almacenes con `profiles: [llmops]`, `restart: unless-stopped`, comprobación de salud y variables con defecto (nombres con prefijo `langfuse-` para no chocar con `postgres` de `core`): `langfuse-postgres` (`postgres:17.7`, volumen `langfuse_postgres_data`, `pg_isready`), `langfuse-clickhouse` (`user: "101:101"`, volúmenes `langfuse_clickhouse_data` y `langfuse_clickhouse_logs`, `wget -q --spider http://localhost:8123/ping`), `langfuse-redis` (comando `--requirepass ${LANGFUSE_REDIS_AUTH:-…} --maxmemory-policy noeviction`, volumen `langfuse_redis_data`, `redis-cli`) y `langfuse-minio` (entrypoint `sh` que crea el bucket `langfuse` y arranca `minio server /data --console-address ":9001"`, volumen `langfuse_minio_data`, `mc ready local`, puerto solo en `127.0.0.1:${LANGFUSE_MINIO_PORT:-9092}:9000`). Ningún puerto de los almacenes se publica salvo MinIO. Declarar los cinco volúmenes en la sección `volumes:` y actualizar el comentario de cabecera (`llmops  langfuse (LAB-64): …`)
- [X] T016 [US1] En `infra/docker-compose.yml` añadir `langfuse-worker` y `langfuse-web` (`profiles: [llmops]`, `restart: unless-stopped`, `depends_on` con `condition: service_healthy` de los cuatro almacenes) tomando como base el `docker-compose.yml` oficial de Langfuse v4 (`research.md` R-8): `DATABASE_URL`, `SALT`, `ENCRYPTION_KEY`, `NEXTAUTH_SECRET`, `NEXTAUTH_URL=http://localhost:${LANGFUSE_PORT:-3001}`, `CLICKHOUSE_*`, `REDIS_*`, bloques `LANGFUSE_S3_EVENT_UPLOAD_*` y `LANGFUSE_S3_MEDIA_UPLOAD_*` hacia `http://langfuse-minio:9000`, `TELEMETRY_ENABLED=false`, y en `langfuse-web` las variables `LANGFUSE_INIT_ORG_ID`, `LANGFUSE_INIT_ORG_NAME`, `LANGFUSE_INIT_PROJECT_ID`, `LANGFUSE_INIT_PROJECT_NAME` (`shoplab-helpdesk`), `LANGFUSE_INIT_PROJECT_PUBLIC_KEY`, `LANGFUSE_INIT_PROJECT_SECRET_KEY`, `LANGFUSE_INIT_USER_EMAIL`, `LANGFUSE_INIT_USER_NAME`, `LANGFUSE_INIT_USER_PASSWORD` con los defectos de `contracts/configuration.md`. Puerto `${LANGFUSE_PORT:-3001}:3000`. Healthchecks provisionales: web `wget -q --spider http://localhost:3000/api/public/health`, worker `wget -q --spider http://localhost:3030/api/health` (se confirman en T017). **No** incluir `langfuse-ai-gateway`
- [X] T017 [US1] Verificar el arranque: `docker compose -f infra/docker-compose.yml --profile llmops config -q` y luego `… --profile llmops up -d --wait`; corregir los healthchecks de web y worker si las rutas de T016 no responden (S-3: anotar las rutas reales en `research.md` R-8) y comprobar que los seis servicios quedan `healthy`. Medir `docker stats --no-stream` con el perfil en reposo y anotar la memoria total en `validation.md` («Recursos locales») para la cifra del README
- [X] T018 [US1] Verificar los escenarios de US1 y anotarlos en `validation.md`: (1) solo `core` → `docker compose … ps --services | grep -c langfuse` da 0 (SC-005, escenario 1); (2) iniciar sesión en `http://localhost:3001` con las credenciales locales y ver organización, proyecto y la clave pública `pk-lf-shoplab-local` (escenario 3); (3) `docker compose … --profile llmops restart` y comprobar que proyecto y datos persisten (escenario 4); (4) `--profile llmops --profile observability` a la vez sin conflicto de puertos ni nombres (escenario 5); (5) `grep -n ':latest' infra/docker-compose.yml` no encuentra imágenes nuevas
- [X] T019 [P] [US1] En `.github/workflows/ci.yml`, junto al paso «Validate the compose profile» (línea ~292), añadir el paso `Validate the llmops compose profile` con `docker compose -f infra/docker-compose.yml --profile llmops config -q`
- [X] T020 [P] [US1] En `README.md` actualizar la tabla de perfiles (fila `llmops`: servicios `langfuse-web`, `langfuse-worker`, `langfuse-postgres`, `langfuse-clickhouse`, `langfuse-redis`, `langfuse-minio`; «Opcional: `--profile llmops`»), quitar `llmops` de la frase «Activar un perfil reservado…», y añadir una subsección «Langfuse (perfil `llmops`)» con: comando de activación, URL `http://localhost:3001`, credenciales locales, que usa ~la memoria medida en T017 y que es opcional, y los puertos configurables

**Checkpoint**: US1 completa y demostrable sin código del backend.

---

## Phase 4: User Story 2 - Ver cada turno del chat como una traza completa (Priority: P1)

**Goal**: con `shoplab.chat.tracing.enabled=true`, cada turno completado deja en Langfuse una traza `chat-turn` con la pregunta, el prompt, los fragmentos con su puntuación, las herramientas, los tokens, el coste y las latencias.

**Independent Test**: con Langfuse en marcha y el envío activado, una pregunta sobre un documento indexado produce una traza con `retrieval` y `generation` completos (quickstart §2).

### Verificación previa (S-2, S-3)

- [X] T021 [US2] Con el perfil de T017 en marcha, enviar con `curl` (script desechable en el directorio temporal, no se versiona) un span OTLP/JSON a `http://localhost:3001/api/public/otel/v1/traces` con `Authorization: Basic base64(pk-lf-shoplab-local:sk-lf-shoplab-local)` y `x-langfuse-ingestion-version: 4`, con un `traceId` derivado (`printf 'spike-1' | sha256sum | cut -c1-32`) y los atributos de `contracts/trace-schema.md` (`langfuse.session.id`, `langfuse.trace.input/output`, `langfuse.observation.type=generation`, `usage_details`, `cost_details`, `completion_start_time`, `metadata.*`). Comprobar con `GET /api/public/traces/<traceId>` que el id se conserva y que usos, coste y tiempos aparecen (S-2, S-3). Anotar el resultado en `research.md` R-11 y, si algún atributo no se respeta, corregir `contracts/trace-schema.md` antes de seguir

### Tests for User Story 2 (escribir primero; deben fallar)

- [X] T022 [P] [US2] `CHAT_TEST/infrastructure/out/tracing/DerivedTraceIdGeneratorTests.java`: el id es `SHA-256(requestId)` en hexadecimal, primeros 32 caracteres, determinista; el generador devuelve el id preparado una sola vez para el hilo y luego cae en un id aleatorio válido (32 hex, no todo ceros); el `ThreadLocal` se limpia tras `startSpan`; dos hilos no se pisan
- [X] T023 [P] [US2] `CHAT_TEST/infrastructure/out/tracing/ContentLimiterTests.java`: texto ≤ tope se devuelve igual y `truncated=false`; texto > tope se recorta al tope y termina en `…[truncated]` con `truncated=true`; `null` → vacío; límite exacto; el contador de recortes del turno se acumula
- [X] T024 [P] [US2] `CHAT_TEST/infrastructure/out/tracing/CostCalculatorTests.java`: precio por nombre exacto de modelo; si no, por proveedor (`ollama` → 0/0); sin entrada → `unpriced` y sin importes; uso desconocido → `unknown_usage` y sin importes; aritmética (1 000 tokens de entrada a 3,0 USD por millón = 0,003); precio cero → `priced` con `{input:0, output:0, total:0}`
- [X] T025 [P] [US2] `CHAT_TEST/infrastructure/out/tracing/LangfuseChatTraceTests.java` con `SdkTracerProvider` + `InMemorySpanExporter`: un turno completado produce exactamente un árbol `chat-turn` → `retrieval` → `generation` con **todos** los atributos y nombres de `contracts/trace-schema.md` (raíz: `langfuse.trace.name`, `session.id` solo con conversación, `trace.input/output`, `metadata.request_id`, `result=completed`, `history_turns`, `context_fragments`, `time_to_first_token_ms`; `retrieval`: `type=retriever`, `output` JSON con `number/source/title/section/position/score/text`, `candidates`, `delivered`, `best_score`; `generation`: modelo, parámetros, `input` JSON de mensajes, `output`, `usage_details` y `cost_details` o su ausencia, `completion_start_time`, `usage_status`, `cost_status`); `traceId` = `SHA-256(requestId)[0..32]`; contenido truncado marca `metadata.truncated=true`; ningún atributo contiene la clave secreta ni cabeceras (FR-019); un fallo interno del adaptador (p. ej. un exportador que lanza) no sale al flujo de eventos
- [X] T026 [P] [US2] Ampliar `CHAT_TEST/infrastructure/out/ollama/OllamaChatAdapterTests.java` y crear `CHAT_TEST/infrastructure/out/ollama/TracedToolCallbackTests.java`: con un `TurnTrace` en el contexto de Reactor, el adaptador llama a `startGeneration` con el modelo, los parámetros permitidos y los mensajes (`system`, historial como `user`/`assistant`, pregunta con contexto) y cierra con `complete(TokenUsage, respuesta)` usando el uso del último fragmento con metadatos (stub `bodyWithUsage` de T003); `firstToken()` se llama una vez al primer fragmento; sin uso informado (cero/`EmptyUsage` según T003) el `TokenUsage` es desconocido; sin `TurnTrace` en el contexto el comportamiento es el de hoy; `TracedToolCallback` delega la llamada, informa nombre, argumentos, resultado, duración y error al `TurnTrace` y relanza el error original
- [X] T027 [P] [US2] `CHAT_TEST/TurnTraceIntegrationTests.java` (`@SpringBootTest(RANDOM_PORT)` + `@PostgresTest`, como `RagAnsweringIntegrationTests`; `shoplab.chat.tracing.enabled=true` con claves de prueba; un `@TestConfiguration` aporta un `InMemorySpanExporter` como `SpanExporter` y el test llama a `forceFlush()`): una pregunta con contexto produce una sola traza; los fragmentos entregados llevan fuente y puntuación; el prompt de `generation` contiene el contexto; tokens `11/7` y coste 0 con el stub `bodyWithUsage`; `traceId` derivado del `X-Request-Id`; con `conversationId` dos preguntas comparten `langfuse.session.id`; el log capturado (`OutputCaptureExtension`) **no** contiene la pregunta, la respuesta ni los fragmentos
- [X] T028 [P] [US2] `CHAT_TEST/TurnTraceToolCallIntegrationTests.java`: igual que `ToolCompositionIntegrationTests` (herramienta de prueba `lookupSomething` publicada por una `@TestConfiguration`) pero con WireMock en dos pasos (escenario: primera respuesta con `tool_calls`, segunda con el texto final); comprobar un span hijo `tool:lookupSomething` (`type=tool`) con argumentos, resultado y duración dentro del turno, y que los tokens de `generation` suman las dos vueltas

### Implementation for User Story 2

- [X] T029 [P] [US2] Crear `CHAT/infrastructure/out/tracing/DerivedTraceIdGenerator.java` (implementa `io.opentelemetry.sdk.trace.IdGenerator`; `static String traceIdOf(String requestId)`; método `prepare(String traceId)` con `ThreadLocal`; `generateSpanId` y el respaldo aleatorio vía `IdGenerator.random()`). Hacer verde T022
- [X] T030 [P] [US2] Crear `CHAT/infrastructure/out/tracing/ContentLimiter.java` (recorte con `TracingConstants` y contador de recortes por turno). Hacer verde T023
- [X] T031 [P] [US2] Crear `CHAT/infrastructure/out/tracing/CostCalculator.java` (usa `TracingProperties.pricing`; devuelve un resultado con importes opcionales y `cost_status`). Hacer verde T024
- [X] T032 [P] [US2] Crear `CHAT/infrastructure/out/tracing/LangfuseAttributes.java` (`@UtilityClass`): una constante por cada nombre de atributo de `contracts/trace-schema.md` y los nombres de span (`chat-turn`, `retrieval`, `generation`, `tool:`)
- [X] T033 [US2] Crear `CHAT/infrastructure/out/tracing/LangfuseChatTrace.java` (implementa `ChatTracePort` y `AutoCloseable`; expone `forceFlush()` para las pruebas): `traceTurn` prepara el `traceId` con `DerivedTraceIdGenerator` justo antes de `startSpan()` de la raíz; crea los hijos con el span raíz como padre **explícito** (el contexto de OTel no viaja entre hilos de Reactor); publica el `TurnTrace` con `contextWrite` bajo `TracingConstants.TURN_TRACE_KEY`; acumula la respuesta con los `TokenEvent`; implementa `retrieval`, `startGeneration` (`GenerationScope` con `firstToken/complete/fail/cancel`) y `toolCall` según `contracts/trace-schema.md`, con `ContentLimiter` y `CostCalculator`; el cierre del turno es idempotente y **siempre** termina los spans (completado, error o cancelación; en esta historia solo se detalla el resultado `completed`, el resto se enriquece en US5); cada llamada a OpenTelemetry va dentro de `try/catch` que avisa sin contenido y nunca propaga. Hacer verde T025
- [X] T034 [US2] En `CHAT/config/TracingConfig.java` añadir la rama de Langfuse: con `enabled=true` y claves no vacías crear el `Resource` (`service.name` = `spring.application.name`, `deployment.environment.name`), el `SpanExporter` (usar el bean `SpanExporter` si existe —pruebas—; si no, `OtlpHttpSpanExporter` hacia `endpoint` sin barra final + `/v1/traces`, cabeceras `Authorization: Basic …` y `x-langfuse-ingestion-version: 4`, `export-timeout`), un `BatchSpanProcessor` con `queue-size`, `batch-size`, `schedule-delay`, `export-timeout`, el `SdkTracerProvider` con el `DerivedTraceIdGenerator` y muestreo siempre activo, y el `LangfuseChatTrace`; el bean cierra con `shutdown-timeout` (`close()` hace `forceFlush` + `shutdown().join(timeout)`). Hacer verde T027 (parte de configuración) y mantener verde T009
- [X] T035 [US2] Modificar `CHAT/infrastructure/out/ollama/OllamaChatAdapter.java` sin cambiar la firma de `ChatModelPort`: en `streamAnswer` usar `Flux.deferContextual` para leer el `TurnTrace` (por defecto `TurnTrace.NONE`), construir los `PromptMessage` a partir de la lista de mensajes que ya arma, llamar a `startGeneration(modelo, parámetros, mensajes)` (modelo desde los metadatos de la respuesta o `chatModel.getOptions()`; parámetros con lista blanca `temperature`, `topP`, `maxTokens`, `numCtx`, nunca credenciales), `firstToken()` en el primer fragmento no vacío, acumular el uso de cada `ChatResponse` con metadatos (tratar uso con ambos conteos a cero o `EmptyUsage` como **desconocido**, según S-1), y `complete(...)` al terminar, `fail(error)` en `onError`, `cancel(parcial)` en `doOnCancel`. Hacer verde la parte de T026 del adaptador
- [X] T036 [US2] Crear `CHAT/infrastructure/out/ollama/TracedToolCallback.java` (decorador de `ToolCallback`: delega y mide) y modificar `ToolCallbackComposer.java` añadiendo `compose(TurnTrace)` que envuelve cada callback; `compose()` se conserva (equivale a `TurnTrace.NONE`) para no romper `ToolCallbackComposerTests`; el adaptador (T035) usa `compose(trace)`. Hacer verde `TracedToolCallbackTests` y T028
- [X] T037 [US2] En `backend/src/main/resources/application.yml` añadir bajo `shoplab.chat` el bloque `tracing:` con todas las propiedades y variables de `contracts/configuration.md` (`enabled: ${SHOPLAB_CHAT_TRACING_ENABLED:false}` …) y `pricing.ollama` con `input-per-million: 0` y `output-per-million: 0`, con un comentario breve del interruptor y de que las trazas llevan la conversación
- [X] T038 [US2] Ejecutar en `backend/` `./mvnw -B -ntp -Dskip.srcclr=true test` y `checkstyle:check`; corregir hasta tener la suite completa en verde y sin violaciones (líneas ≤ 121, llaves siempre, sin importaciones sin uso)
- [ ] T039 [US2] Verificación manual contra Langfuse real (quickstart §2): arrancar el backend con `SHOPLAB_CHAT_TRACING_ENABLED=true` y las claves locales, hacer 20 preguntas variadas (con y sin `conversationId`) y comprobar (SC-001) tiempo desde `up` hasta la primera traza < 5 min; (SC-002) 20 trazas, cada una con prompt, contexto, tokens y coste; (SC-003) localizar contexto y tokens de un turno cualquiera en < 1 min; y que `GET /api/public/v2/observations?traceId=<sha256(requestId)[0..32]>` devuelve sus observaciones (FR-020; `GET /api/public/traces` no está disponible en Langfuse v4, ver quickstart §2). Anotar capturas de datos (sin secretos) y resultados en `validation.md`

**Checkpoint**: US1 y US2 funcionan juntas: el MVP completo en local.

---

## Phase 5: User Story 4 - El chat no depende de las trazas (Priority: P2)

**Goal**: ninguna falla, lentitud o mala configuración de Langfuse retrasa, altera o rompe una respuesta; apagado por defecto sin conexiones; cola acotada; vaciado al apagar.

**Independent Test**: con Langfuse detenido y el envío activo, las respuestas llegan completas y a la misma velocidad (quickstart §3).

### Tests for User Story 4 (escribir primero; deben fallar)

- [X] T040 [P] [US4] `CHAT_TEST/TraceResilienceIntegrationTests.java` (como T027 pero con **WireMock como Langfuse** en lugar del exportador en memoria, `endpoint` apuntando a él): (a) Langfuse inalcanzable (puerto cerrado) → respuesta completa y sin error; (b) Langfuse con retardo fijo mayor que `export-timeout` → respuesta completa y sin retraso apreciable; (c) 401 → respuesta completa, un aviso en el log **sin** pregunta, respuesta ni clave secreta; (d) Langfuse falla las primeras peticiones y luego responde 200 → la traza de un turno posterior llega sin reiniciar; (e) cola llena (`queue-size` mínimo y Langfuse bloqueado) → el chat sigue respondiendo y se cuentan los descartes en `shoplab.chat.trace.dropped`; (f) al cerrar el contexto, los spans pendientes se envían dentro de `shutdown-timeout`; (g) la petición OTLP lleva `Authorization: Basic` y `x-langfuse-ingestion-version: 4`
- [X] T041 [P] [US4] Ampliar `CHAT_TEST/config/TracingConfigNoOpTests.java` y crear `CHAT_TEST/config/TracingConfigLangfuseTests.java` (`ApplicationContextRunner`): `enabled=false` → sin `SdkTracerProvider`, sin exportador y **ninguna** petición a un WireMock de control (SC-005, escenario 3); `enabled=true` con claves vacías → `NoOpChatTrace`, el contexto arranca y el aviso del log no contiene valores de claves (regla de `contracts/configuration.md`); `enabled=true` con claves → `LangfuseChatTrace` y cerrar el contexto no lanza
- [X] T042 [P] [US4] Ampliar `backend/src/test/java/com/shoplab/helpdesk/blockhound/` con `TracingBlockHoundTests.java` (seguir el patrón de las pruebas existentes de esa carpeta): un turno trazado completo bajo BlockHound no dispara llamadas bloqueantes en el hilo del evento (la red del exportador ocurre en el hilo del SDK)

### Implementation for User Story 4

- [X] T043 [US4] Crear `CHAT/infrastructure/out/tracing/DroppedSpansReporter.java`: contar los descartes por cola llena en el contador de Micrometer `shoplab.chat.trace.dropped` (sin etiquetas) y registrar un `WARN` sin contenido, como máximo uno por minuto. Usar `BatchSpanProcessorBuilder.setMeterProvider(...)` con un `SdkMeterProvider` mínimo que lea la métrica de spans procesados con `error.type=queue_full`; si la versión del SDK gestionada por Boot no ofrece esa API, envolver el `SpanExporter` para contar y avisar de los **fallos de exportación** (`shoplab.chat.trace.export.failures`) y corregir `research.md` R-7 y `contracts/configuration.md` con el nombre de métrica real
- [X] T044 [US4] Integrar en `CHAT/config/TracingConfig.java` el reporter de T043 y el aviso por lote fallido (nivel `WARN`, texto fijo con el código HTTP o el tipo de excepción, **nunca** URL con credenciales, cabeceras ni contenido); comprobar que `close()` respeta `shutdown-timeout`; el aviso de claves vacías de T041 vive aquí. Hacer verde T040, T041 y T042
- [X] T045 [US4] Ejecutar la suite completa y `checkstyle:check` en `backend/`; corregir hasta verde
- [ ] T046 [US4] Verificación manual (quickstart §3) y medición de SC-004 en `validation.md`: detener `langfuse-web` y `langfuse-worker`, hacer 20 preguntas y comprobar 20 respuestas completas sin error; volver a levantar y comprobar que llegan trazas nuevas sin reiniciar el backend; medir la media del tiempo hasta el primer token (`shoplab_chat_first_token_seconds` en `/actuator/prometheus`) con 20 preguntas con trazas apagadas y con trazas activas, y anotar que el aumento es < 5 %. Con la variable sin definir comprobar con `docker compose … logs`/`ss` que el backend no abre conexiones al puerto 3001

**Checkpoint**: US1, US2 y US4 completas; el envío puede dejarse activo sin riesgo.

---

## Phase 6: User Story 5 - Distinguir los turnos que no son una respuesta normal (Priority: P3)

**Goal**: los turnos sin contexto, cancelados o fallidos dejan una traza con su resultado, el código de error, la etapa y el texto parcial.

**Independent Test**: provocar los tres casos y ver cada traza con su `result` (quickstart §4).

### Tests for User Story 5 (escribir primero; deben fallar)

- [X] T047 [P] [US5] `CHAT_TEST/TraceSpecialTurnsIntegrationTests.java` (base de T027 y de `ChatMidStreamFailureIntegrationTests`/`ChatModelDownIntegrationTests`/`RawOllamaServer`): (1) sin contexto y `fixed-message` → `result=no_context`, `retrieval` con `[]`, **sin** span `generation`, salida = mensaje fijo; (2) suscripción cancelada a mitad → `result=cancelled`, `level=WARNING`, salida = texto parcial, `generation` cerrada; (3) Ollama caído → `result=error`, `error_code=CHAT_MODEL_UNAVAILABLE` (valor de `ResponseCodeEnum`), `failed_stage=generation`, `level=ERROR`; (4) fallo a mitad de la respuesta → `error_code=CHAT_STREAM_FAILED`; (5) fallo de la búsqueda (un `KnowledgeContextPort` de prueba que falla) → `failed_stage=retrieval`, `retrieval` con `ERROR`, sin `generation`; (6) herramienta que lanza error → `failed_stage=tool` y span `tool:` con `ERROR`; (7) el turno se cierra una sola vez (una única raíz por `requestId`)
- [X] T048 [P] [US5] Ampliar `CHAT_TEST/application/usecase/StreamChatUseCaseTraceTests.java`: cuando la búsqueda falla, el caso de uso informa `retrieval` con `failed=true` y `candidates=0` antes de propagar el error; cuando el contexto es vacío con `no-context-mode=model` **no** llama a `fixedMessageAnswer()`
- [X] T049 [P] [US5] Ampliar `CHAT_TEST/infrastructure/out/tracing/LangfuseChatTraceTests.java` con los cuatro `result`, los niveles (`ERROR`, `WARNING`, `DEFAULT`), `error_code`, `failed_stage`, el texto parcial como salida y la ausencia de `generation` en `no_context`, según la tabla «Turnos especiales» de `contracts/trace-schema.md`

### Implementation for User Story 5

- [X] T050 [US5] En `CHAT/application/usecase/StreamChatUseCase.java` (`retrieveContext`) informar `trace.retrieval(new RetrievalTrace(searchText, 0, List.of(), true), elapsed)` en el `doOnError` existente, sin alterar el error que se propaga ni las métricas. Hacer verde T048
- [X] T051 [US5] En `CHAT/infrastructure/out/tracing/LangfuseChatTrace.java` completar el cierre del turno: derivar `TurnResult` (`NO_CONTEXT` si se llamó a `fixedMessageAnswer()`; `COMPLETED` con `DoneEvent`; `ERROR` con `ErrorEvent` o señal de error, tomando el código de `ErrorEvent` o de `HelpdeskException`; `CANCELLED` por cancelación), `FailureStage` (el paso abierto que falló: recuperación fallida → `RETRIEVAL`, `GenerationScope.fail` → `GENERATION`, herramienta con error → `TOOL`), niveles y `status_message` sin datos de la conversación, y el texto parcial como salida. Hacer verde T047 y T049
- [ ] T052 [US5] Ejecutar la suite completa y `checkstyle:check`; hacer los tres casos del quickstart §4 contra Langfuse real y anotar en `validation.md` que cada traza muestra su resultado

**Checkpoint**: US1, US2, US4 y US5 completas: todo el comportamiento del backend y de compose está terminado.

---

## Phase 7: User Story 3 - Tener Langfuse funcionando en el ambiente DEV del clúster (Priority: P2)

**Goal**: Langfuse y sus cuatro almacenes desplegados por Argo CD en `shoplab-dev`, con versiones fijas, secretos del mecanismo existente y el backend de DEV enviando trazas.

**Independent Test**: tras sincronizar `dev`, una pregunta al chat de DEV aparece como traza en el Langfuse del clúster sin editar configuración a mano (quickstart §6).

**Nota de dependencia**: T064 y T065 requieren que los cambios de `HD` estén fusionados y publicados en una versión `vX.Y.Z` (release-please, ADR-0012: DEV despliega solo releases). Las tareas T053–T063 solo necesitan el repositorio `INF`.

- [ ] T053 [US3] En `INF` (clon en `../shoplab-infra`, hoy en `fix/LAB-22-argocd-endpointslice` con árbol limpio): `git fetch origin` y `git switch -c feature/LAB-64-langfuse-llm-traces origin/main`. Leer `scripts/policy-check.sh`, `scripts/kustomize-build.sh` y `scripts/trivy.sh` y listar en `validation.md` las reglas que afectarán a los workloads nuevos (`IMAGE_LATEST`, `IMAGE_NO_TAG`, `PLAIN_SECRET`, `kubeconform` con CRDs, hallazgos de Trivy sobre `securityContext`/recursos); ejecutar `make kustomize policies` como línea base
- [X] T054 [P] [US3] Secretos en Terraform (`INF`): añadir `"langfuse"` a la lista `default` de `terraform/modules/secrets/variables.tf`; en `terraform/envs/dev/secrets.auto.tfvars.example` añadir la entrada `langfuse` con un JSON de ejemplo de **diez claves con marcadores `CHANGE_ME`** (las de `contracts/configuration.md`, «Clúster DEV»); actualizar el JSON de ejemplo `TF_VAR_secrets` en `scripts/lib.sh` (línea ~23) y cualquier fixture de `terraform/envs/dev/tests/`; ejecutar `make fmt-check validate tf-test` y dejar verde. No tocar `secrets.auto.tfvars` (no versionado) salvo para la verificación local de T064
- [X] T055 [P] [US3] `INF` `k8s/base/langfuse/postgres.yaml`: `StatefulSet` `langfuse-postgres` (`docker.io/postgres:17.7`, 1 réplica, `volumeClaimTemplates` `data` en `local-path` de 2Gi, sondas con `pg_isready`, `securityContext` igual al de `k8s/base/postgres/statefulset.yaml`: `runAsNonRoot`, `readOnlyRootFilesystem` con `emptyDir` en `/tmp` y `/var/run/postgresql`, `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault`, `automountServiceAccountToken: false`) y `Service` `ClusterIP` `langfuse-postgres:5432`; contraseña desde el secreto `langfuse-credentials` (`POSTGRES_PASSWORD`)
- [X] T056 [P] [US3] `INF` `k8s/base/langfuse/clickhouse.yaml`: `StatefulSet` + `Service` `langfuse-clickhouse` (`docker.io/clickhouse/clickhouse-server:26.3.62.3`, `runAsUser: 101`, `data` en `local-path` de 5Gi, sonda HTTP `/ping` en 8123, puertos 8123 y 9000 solo `ClusterIP`; `CLICKHOUSE_USER=clickhouse` y `CLICKHOUSE_PASSWORD` desde `langfuse-credentials`)
- [X] T057 [P] [US3] `INF` `k8s/base/langfuse/redis.yaml`: `StatefulSet` + `Service` `langfuse-redis` (`docker.io/redis:7.4.6`, `--requirepass $(REDIS_AUTH)` desde `langfuse-credentials`, `--maxmemory-policy noeviction`, `data` en `local-path` de 1Gi, sonda `redis-cli ping` con autenticación)
- [X] T058 [P] [US3] `INF` `k8s/base/langfuse/minio.yaml`: `StatefulSet` + `Service` `langfuse-minio` (`cgr.dev/chainguard/minio@sha256:5cfb31567972802ece1a39bb076078fda00095c7d11e82be65e762c528994608`; creación del bucket `langfuse` al arrancar; `data` en `local-path` de 2Gi; `MINIO_ROOT_USER=minio` y `MINIO_ROOT_PASSWORD` desde `langfuse-credentials`; sonda `mc ready local`); comprobar que `scripts/policy-check.sh` acepta la imagen con digest sin tag
- [X] T059 [US3] `INF` `k8s/base/langfuse/`: `web.yaml` (`Deployment` + `Service` `langfuse-web` puerto 3000 `ClusterIP`, imagen `docker.langfuse.com/langfuse/langfuse:4.77.0`, `NEXTAUTH_URL=http://localhost:3001` —se accede por `port-forward`—, variables de conexión a los almacenes por nombre de servicio, bloques `LANGFUSE_S3_*` hacia `http://langfuse-minio:9000`, `TELEMETRY_ENABLED=false`, `envFrom` del secreto `langfuse-credentials` y de `LANGFUSE_INIT_*`; sondas de `readiness`/`liveness` con la ruta confirmada en T017), `worker.yaml` (`Deployment`, `langfuse-worker:4.77.0`, sonda con la ruta confirmada en T017), `externalsecret.yaml` (`langfuse-credentials`: `dataFrom.extract` de `/shoplab/ENVIRONMENT/langfuse`, `ClusterSecretStore` `aws-secrets-manager`, `refreshInterval: 1h`, igual que `k8s/base/postgres/externalsecret.yaml`) y `kustomization.yaml` que lista todos los recursos del directorio. Todos los contenedores con el mismo `securityContext` endurecido y sin `latest`
- [X] T060 [US3] `INF`: añadir `langfuse` a `resources:` de `k8s/base/kustomization.yaml`; en `k8s/base/backend/` crear el `ExternalSecret` `backend-langfuse` (solo `SHOPLAB_CHAT_TRACING_PUBLIC_KEY` y `SHOPLAB_CHAT_TRACING_SECRET_KEY`, con `remoteRef.key: /shoplab/ENVIRONMENT/langfuse` y `property: LANGFUSE_INIT_PROJECT_PUBLIC_KEY` / `LANGFUSE_INIT_PROJECT_SECRET_KEY`), listarlo en `k8s/base/backend/kustomization.yaml` y añadir su `secretRef` en `envFrom` de `k8s/base/backend/deployment.yaml`
- [X] T061 [US3] `INF` `k8s/overlays/dev/kustomization.yaml` y `patches/resources.yaml`: (1) **corregir** el patch final que reemplaza `/spec/data/0/remoteRef/key` para *todos* los `ExternalSecret` (fallaría en `langfuse-credentials`, que usa `dataFrom`): limitarlo con `target.name: backend-credentials`; (2) añadir los patches equivalentes que sustituyen `ENVIRONMENT` por `dev` en `langfuse-credentials` (`/spec/dataFrom/0/extract/key`) y en `backend-langfuse` (`/spec/data/0/remoteRef/key` y `/spec/data/1/remoteRef/key`); (3) añadir al `configMapGenerator` de `backend-config` `SHOPLAB_CHAT_TRACING_ENABLED=true`, `SHOPLAB_CHAT_TRACING_ENDPOINT=http://langfuse-web:3000/api/public/otel` y `SHOPLAB_CHAT_TRACING_ENVIRONMENT=dev`; (4) en `patches/resources.yaml` fijar `requests` y `limits` de CPU y memoria de los seis Deployments/StatefulSets de Langfuse con valores mínimos iniciales (la medición real de T064 los ajusta)
- [ ] T062 [US3] En `INF` ejecutar `make kustomize policies test-policies prom-rules pins trivy` y `make check` si las herramientas están instaladas (`make check-tools`); corregir todos los hallazgos (p. ej. recursos, `securityContext`, imágenes). Si una política nueva exige una prueba, añadir su fixture en `scripts/tests/fixtures/` y su caso en `scripts/tests/policy-check.test.sh`
- [X] T063 [P] [US3] En `INF` documentar en `docs/observability.md` (sección «Langfuse (LAB-64)»): qué se despliega, que no hay ingress, el acceso con `kubectl -n shoplab-dev port-forward svc/langfuse-web 3001:3000`, el formato JSON del secreto `/shoplab/dev/langfuse` y cómo cargarlo desde `secrets.auto.tfvars`, y que los datos viven en PVC `local-path` (sobreviven al reinicio de pods, no a `destroy`); enlazar desde `README.md` de `INF` si lista los documentos
- [ ] T064 [US3] Verificación en el clúster (S-4), **tras** publicar la release de `HD` con LAB-64 (ver nota de dependencia): cargar el secreto `langfuse` en `terraform/envs/dev/secrets.auto.tfvars` (JSON con diez claves reales locales), `make up`, esperar a que `shoplab-dev` esté `Synced` y `Healthy`, comprobar que los seis pods de Langfuse están `Running`/`Ready`, medir `kubectl top pods -n shoplab-dev` y ajustar `patches/resources.yaml` (T061) con los valores reales; comprobar que el backend y el resto de la pila siguen sanos en el nodo. **Si Langfuse no cabe junto al resto**, detenerse, documentar la medición en `validation.md` y volver a decidir con el responsable (alternativa del riesgo R1: patrón `EndpointSlice` hacia el Docker del host, que exigiría enmendar FR-024 de la spec)
- [ ] T065 [US3] Verificar los escenarios de US3 y SC-006/SC-007 en `validation.md`: una pregunta al chat de DEV (`http://shoplab.localtest.me`) aparece como traza con entorno `dev` en < 1 min tras abrir el `port-forward`; `make down && make up` y comprobar que Langfuse vuelve con proyecto y claves sin pasos manuales; `grep -rn 'latest' k8s/base/langfuse` vacío y ningún valor real de secreto en `git diff`; los almacenes no tienen `Ingress` ni `NodePort`

**Checkpoint**: las cinco historias completas.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: decisión documentada, documentación y puertas de calidad de la constitución.

- [X] T066 [P] Crear `docs/adr/0022-trazas-de-llm-con-langfuse-por-otlp.md` con la plantilla `docs/adr/0000-plantilla.md` (estado, fecha, contexto, decisión, consecuencias): OTLP hacia Langfuse con un pipeline de OTel **dedicado** y no el tracing global; puerto `ChatTracePort` y modelo explícito frente a observaciones de Spring AI; `traceId` derivado de `requestId`; qué contenido se envía y las salvaguardas (apagado por defecto, tope, sin credenciales, solo datos públicos); relación con ADR-0009 (OpenTelemetry solo como formato de transporte), LAB-63 y LAB-29; alternativas descartadas de `research.md`. Añadir la fila al índice de `docs/adr/README.md`
- [X] T067 [P] En `README.md` añadir «Trazas de LLM» (FR-031): cómo activar el envío (`SHOPLAB_CHAT_TRACING_*`), cómo leer una traza (qué muestra cada paso), cómo editar la tabla de precios (`shoplab.chat.tracing.pricing`), el aviso de que las trazas contienen la conversación y solo deben usarse con datos públicos o de prueba, cómo calcular el `traceId` de un `X-Request-Id`, y cómo ver las trazas de DEV (`port-forward`, enlace a `INF` `docs/observability.md`)
- [X] T068 Reconciliar los documentos de diseño con lo aprendido en las verificaciones (S-1 a S-5, T043): actualizar `research.md`, `data-model.md`, `contracts/trace-schema.md` y `contracts/configuration.md` donde la implementación difiera (p. ej. nombre de la métrica de descartes, rutas de salud, manejo de uso desconocido)
- [X] T069 Puertas de la constitución en `HD` (`backend/`): `./mvnw -B -ntp -Dskip.srcclr=true test`, `checkstyle:check`, `verify -Dcheckstyle.skip=true` con cobertura JaCoCo ≥ 80 % (comparar con la línea base de T001); `git status --short` y eliminar archivos sueltos (`*.orig`, `*.log`, el script desechable de T021, el test de T003); `git diff --staged` revisado
- [ ] T070 Ejecutar `quickstart.md` de principio a fin y completar la tabla de criterios de salida en `validation.md` con la evidencia de SC-001 a SC-008 (SC-006 y SC-007 vienen de T065)
- [X] T071 Ejecutar la skill `new-properties` y entregar al usuario el YAML de las propiedades nuevas (`shoplab.chat.tracing.*`) con valores para el yml del ambiente
- [ ] T072 Commits atómicos con el formato de la constitución en ambos repositorios y apertura de los PR con la skill `commit-and-push`: `HD` con título `feat(chat): [LAB-64] trazas de LLM con Langfuse autoalojado`; `INF` con el título que valide su `pr-title.yml`. Mencionar en cada PR el otro. No cerrar LAB-64 en Jira hasta validar T064–T065 sobre la release publicada

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias. T001 → T002; T003 es independiente de T002 (ambos tras T001).
- **Foundational (Phase 2)**: depende de T002 (T005) y de T003 solo para el helper de pruebas. **Bloquea US2, US4, US5.**
- **US1 (Phase 3)**: no depende de la fase 2 ni del código; puede hacerse en paralelo a Foundational.
- **US2 (Phase 4)**: depende de Foundational y de T017 (Langfuse en marcha, para T021 y T039).
- **US4 (Phase 5)** y **US5 (Phase 6)**: dependen de US2; entre sí tocan archivos distintos salvo `LangfuseChatTrace.java` y `TracingConfig.java` → hacer US4 antes que US5 (o secuencial por archivo).
- **US3 (Phase 7)**: T053–T063 solo dependen de la spec y de `contracts/`; T064–T065 dependen de que US1, US2, US4 y US5 estén en una release publicada.
- **Polish (Phase 8)**: depende de las historias que se entreguen; T066–T067 pueden escribirse en cuanto se conozca el diseño final.

### Within Each Story

Pruebas primero y en rojo → modelos/constantes → servicios → integración → verificación manual → anotar evidencia en `validation.md`.

### Parallel Opportunities

- Fase 1: T002 ‖ T003.
- Fase 2: T004 ‖ T005 ‖ T012 (tras T007); T008 ‖ T009.
- US1 (T014 ‖ T019 ‖ T020) y el trabajo de Foundational pueden avanzar a la vez por personas distintas.
- US2: pruebas T022–T028 todas en paralelo; implementaciones T029–T032 en paralelo.
- US4: T040 ‖ T041 ‖ T042. US5: T047 ‖ T048 ‖ T049.
- US3: T054–T058 en paralelo (archivos distintos).

## Parallel Example: User Story 2

```bash
# Pruebas en paralelo (archivos distintos, todas en rojo):
Task: "DerivedTraceIdGeneratorTests en CHAT_TEST/infrastructure/out/tracing/"      # T022
Task: "ContentLimiterTests en CHAT_TEST/infrastructure/out/tracing/"               # T023
Task: "CostCalculatorTests en CHAT_TEST/infrastructure/out/tracing/"               # T024
Task: "LangfuseChatTraceTests en CHAT_TEST/infrastructure/out/tracing/"            # T025

# Piezas puras en paralelo:
Task: "DerivedTraceIdGenerator"   # T029
Task: "ContentLimiter"            # T030
Task: "CostCalculator"            # T031
Task: "LangfuseAttributes"        # T032
```

## Implementation Strategy

### MVP First (US1 + US2)

1. Setup (T001–T003) y Foundational (T004–T013); en paralelo US1 (T014–T020).
2. US2 (T021–T039). **Parar y validar**: quickstart §1 y §2. Es el MVP: Langfuse local y una traza completa por turno.

### Incremental Delivery

1. + US4 (T040–T046): ya se puede dejar el envío activado sin riesgo.
2. + US5 (T047–T052): cubre los turnos que fallan o se cancelan.
3. + US3 (T053–T065): ambiente DEV, tras publicar la release.
4. Polish (T066–T072) y PR en los dos repositorios.

### Notes

- **[P]** = archivos distintos y sin dependencias pendientes.
- Verificar que cada prueba falla antes de implementar; un commit por tarea o grupo lógico (`type(scope): descripción en inglés`).
- Línea de ≤ 121 caracteres, llaves siempre, identificadores en inglés, sin `latest`, sin secretos en git.
- Si una verificación (S-1 a S-4) contradice el diseño, parar y reconciliar `research.md`/`contracts/` (T068) antes de seguir.
