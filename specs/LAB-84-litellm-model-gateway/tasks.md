# Tasks: Gateway de modelos con LiteLLM

**Input**: Design documents from `/specs/LAB-84-litellm-model-gateway/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: se piden de forma explícita (FR-037, Constitución IV): el enrutado, el fallback, el presupuesto y la
traducción de errores se verifican con pruebas deterministas con servidores simulados (WireMock en el backend, pytest en
`infra/litellm/`). En cada historia, las pruebas se escriben **antes** de la implementación y deben fallar primero.
Ningún modelo real ni cuenta de nube en CI.

**Organization**: tareas agrupadas por historia de usuario. Las rutas son relativas al repositorio `shoplab-helpdesk`
salvo que se indique `shoplab-infra` (repositorio hermano `../shoplab-infra`, rama propia
`feature/LAB-84-litellm-model-gateway`). Abreviaturas de ruta usadas abajo:

- `BE` = `backend/src/main/java/com/shoplab/helpdesk`
- `BT` = `backend/src/test/java/com/shoplab/helpdesk`

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de `spec.md` a la que pertenece (US1…US7)
- Orden de las fases de historia = orden de entrega del plan (US1, US2, US5, US4, US3, US6, US7): el registro por
  petición (US5) precede a presupuesto y fallback porque ambos se demuestran leyendo ese log; US6 precede a US7
  porque cambia archivos de `infra/litellm/` que US7 sincroniza con el clúster.
- Los IDs T098 en adelante se añadieron tras `/speckit-analyze` (hallazgos I1–I18); el orden de ejecución lo da la
  fase en la que están, no el número.

---

## Phase 1: Setup (spikes previos, research R-12)

**Purpose**: resolver las incógnitas «A confirmar» de `research.md` con pruebas desechables contra un gateway mínimo antes
de fijar versiones y formas. Cada spike anota su resultado en `specs/LAB-84-litellm-model-gateway/research.md`
(sección R-12, columna «Resultado») y, si cambia algo, en el contrato afectado.

- [X] T001 Spike S-1: con un `infra/docker-compose.yml` temporal (no se versiona) levantar LiteLLM + su Postgres + Ollama; fijar el tag exacto de la imagen (línea v1.109.x, nunca `latest`) y su registro, comprobar que arranca en < 60 s con < 1 GiB, que acepta la clave maestra local `sk-shoplab-master-local-only`, que `docker compose up --wait` tolera un servicio de un solo disparo que termina con 0, y si omite por sí mismo un despliegue cuya `os.environ/…` está vacía; registrar tag, tamaño y decisión sobre la plantilla del bloque cloud en `specs/LAB-84-litellm-model-gateway/research.md` *(Resultado: imagen `ghcr.io/berriai/litellm:v1.109.1` (existe en ghcr.io), arranca en unos 10 s con Postgres 17.7; verificado con un Ollama simulado (ver research.md). La memoria real y `up --wait` con el compose completo quedan para T016 y T094.)*
- [X] T002 [P] Spike S-2 (depende de T001): capturar la respuesta SSE real de `POST /v1/chat/completions` (stream normal, con llamada a herramienta, con `usage` en el último chunk, campo `model` por chunk) y la de `POST /v1/embeddings` (1024 valores) y guardarlas como fixtures en `backend/src/test/resources/gateway/chat-stream.sse`, `backend/src/test/resources/gateway/chat-tool-call.sse` y `backend/src/test/resources/gateway/embeddings.json` *(Resultado: forma SSE verificada contra el gateway real; `GatewayStubs` la reproduce (no se versionan capturas).)*
- [X] T003 [P] Spike S-3 (depende de T001): con `chat` = principal + `chat-fallback`, provocar las tres formas de «modelo caído» (modelo no descargado → 404, contenedor `ollama` parado → conexión rechazada, modelo lento → `stream_timeout`), confirmar que cada una acaba en el respaldo antes del primer fragmento y que `enable_mid_stream_fallback_continuation: false` impide reiniciar a mitad de stream; fijar el procedimiento de demostración (FR-017) y anotarlo en `specs/LAB-84-litellm-model-gateway/research.md` (R-03) *(Resultado: fallback verificado con el principal devolviendo 404; «contenedor parado» y «modelo lento» quedan para T071. La clave virtual debe poder llamar a `chat-fallback`.)*
- [X] T004 [P] Spike S-4 (depende de T001): comprobar que `num_ctx: 8192` y `keep_alive` de `litellm_params` llegan a Ollama (prompt de ~7 000 tokens sin truncar) y volcar un `standard_logging_payload` real para fijar los nombres de campo que lee el callback (`response_cost`, `prompt_tokens`, `completion_tokens`, `model_group`, `model`, `model_info.id`, `end_user`, cabeceras, número de intentos de fallback); guardar el volcado (sin contenido) en `specs/LAB-84-litellm-model-gateway/research.md` (R-06); confirmar también que el callback de fallo se invoca (y qué campos trae) en los rechazos previos a la llamada (presupuesto agotado, clave inexistente, modelo desconocido) y, si no se invoca, decidir entre un hook `async_pre_call_hook` que emita la línea o aceptar y documentar la limitación (FR-020, `log-schema.md` «Reglas») *(Resultado: ver research.md; `num_ctx` y `keep_alive` hacia un Ollama real quedan para T042 y T094.)*
- [X] T005 [P] Spike S-5 (depende de T001): probar `/key/generate` con clave predefinida (`key`), `key_alias` con patrón `^[a-z0-9]+(-[a-z0-9]+)*$`, `max_budget`, `budget_duration: 1d` y modelos permitidos, y `/key/update` repetido (idempotencia); comprobar el significado de `models: []` (si equivale a «todos los modelos», fijar el valor que deja a `ingest` sin acceso a ninguno, p. ej. un nombre lógico inexistente); anotar los cuerpos de petición/respuesta reales, la unidad del presupuesto y ese valor en `specs/LAB-84-litellm-model-gateway/research.md` (R-04) *(Resultado: ver research.md; `models: []` de LiteLLM significa «todos los modelos».)*
- [X] T006 [P] Spike S-6 (depende de T001, T005): capturar el cuerpo y el estado HTTP reales de presupuesto agotado, clave inexistente (401) y modelo inexistente (400/404) y guardarlos como fixtures en `backend/src/test/resources/gateway/error-budget-exceeded.json`, `backend/src/test/resources/gateway/error-auth.json` y `backend/src/test/resources/gateway/error-model-not-found.json`; si difieren de la tabla de `contracts/gateway-api.md`, corregir la tabla *(Resultado: ver research.md; presupuesto agotado es HTTP 422, clave inexistente 401, modelo no permitido 403.)*

**Checkpoint**: versiones y formas fijadas; los contratos están al día con lo observado.

---

## Phase 2: Foundational (gateway local funcionando; bloquea todas las historias)

**Purpose**: servicios `litellm` + `litellm-postgres` en compose (perfil `core`), configuración de nombres lógicos con
precios, `ollama` sin ruta para la aplicación, esqueleto Python con CI. Aún sin el backend migrado.

**⚠️ CRITICAL**: ninguna historia puede empezar antes de completar esta fase.

- [X] T007 Crear `infra/litellm/render_config.py` (stdlib): expande `${VAR:-valor}` y `${VAR}` de un archivo de plantilla leyendo el entorno y escribe el resultado a la ruta indicada (según el resultado de T001; si LiteLLM expande por sí mismo, reducir a un `cp`); sale con código ≠ 0 y mensaje claro si una variable sin valor por defecto no está definida
- [X] T008 [P] Crear `infra/litellm/pyproject.toml` (Python 3.12, dependencias de desarrollo `pytest`, `ruff`, `pyyaml`, sin dependencia de `litellm`), `infra/litellm/tests/__init__.py` y `infra/litellm/tests/conftest.py` (que registra un módulo falso `litellm.integrations.custom_logger` con una clase base `CustomLogger` vacía para poder importar `shoplab_callbacks.py` sin instalar LiteLLM); generar `infra/litellm/uv.lock` con `uv lock`
- [X] T009 [P] Escribir `infra/litellm/tests/test_render_config.py` (pytest): expansión con y sin valor por defecto, valor por defecto anidado (`${A:-${B:-x}}`, que usa `LITELLM_CHAT_PRIMARY_MODEL`), variable obligatoria ausente ⇒ error, sin tocar `os.environ/…` (depende de T007, T008; debe fallar antes de T007 si se escribe primero)
- [X] T010 Crear `infra/litellm/config.yaml` con la forma de `contracts/gateway-config.md`: `model_list` con `chat` (`ollama_chat/${LITELLM_CHAT_PRIMARY_MODEL:-llama3.2:3b}`, `api_base: http://ollama:11434`, `num_ctx: 8192`, `keep_alive: 30m`, `model_info.id: chat-primary`, `input_cost_per_token`/`output_cost_per_token` de referencia con valor por defecto sobrescribible, p. ej. `${LITELLM_CHAT_PRIMARY_INPUT_COST:-0.00000015}`; el mismo patrón para `chat-fallback` y `embeddings`, research R-05), `embeddings` (`ollama/${LITELLM_EMBED_MODEL:-bge-m3}`, `model_info.id: embeddings-primary`, precio de entrada) y `general_settings` (`master_key: os.environ/LITELLM_MASTER_KEY`, `database_url: os.environ/DATABASE_URL`, `store_prompts_in_spend_logs: false`, `disable_reset_budget: false`); `litellm_settings` con `turn_off_message_logging: true`, `json_logs: true`, `request_correlation_in_logs: true` y `enable_mid_stream_fallback_continuation: false`; aplicar los ajustes que dictaron T003 y T004 (aún sin `callbacks` ni respaldo)
- [X] T011 Crear `infra/litellm/entrypoint.sh`: ejecuta `python render_config.py config.yaml.tmpl /tmp/config.yaml` (o el archivo montado, según T007) y lanza `litellm --config /tmp/config.yaml --port 4000` con `DISABLE_ADMIN_UI=true`; renombrar `config.yaml` a la plantilla que consuma el entrypoint solo si T007 lo exige
- [X] T012 Editar `infra/docker-compose.yml`: añadir en `core` (sin `profiles:`) `litellm-postgres` (`${LITELLM_POSTGRES_IMAGE:-docker.io/postgres:17.7}`, volumen con nombre `litellm_postgres_data`, sin puerto publicado, healthcheck `pg_isready`, `restart: unless-stopped`) y `litellm` (`${LITELLM_IMAGE:-<tag fijo de T001>}`, `config.yaml`/`entrypoint.sh`/`render_config.py` montados de solo lectura, puerto `${LITELLM_PORT:-4000}:4000`, healthcheck sobre `/health/liveliness`, `depends_on` con `condition: service_healthy` de `litellm-postgres` y `ollama`, `restart: unless-stopped`); declarar el volumen en la sección `volumes:`; seguir las convenciones del archivo (imágenes con versión fija sobrescribible, sin `latest`)
- [X] T013 Editar `infra/docker-compose.yml` para aislar `ollama` (FR-002, US1 esc. 4): declarar la red `models` y poner en ella solo `ollama` y `litellm` (`litellm` además en `default`); `ingest`, `postgres` y el resto quedan solo en `default`, de modo que ningún servicio de la aplicación resuelve ni alcanza `ollama:11434` (la red `models` no es `internal` porque `ollama` necesita Internet para descargar modelos); quitar `ports: "${OLLAMA_PORT:-11434}:11434"` de `ollama` y actualizar el comentario de cabecera (perfil `core` = postgres + ollama + litellm + litellm-postgres + ingest); crear `infra/docker-compose.ollama-host.yml`, override opcional que vuelve a publicar `${OLLAMA_PORT:-11434}:11434` y cuya cabecera documenta sus dos usos: el Ollama del host que consume el clúster DEV (`172.17.0.1:11434`, ver `shoplab-infra/k8s/overlays/dev/ollama-host.yaml`) y la depuración/medición de SC-004
- [X] T014 [P] Editar `infra/.env.example` con las variables de `contracts/configuration.md`: `LITELLM_IMAGE`, `LITELLM_POSTGRES_IMAGE`, `LITELLM_PORT`, `LITELLM_MASTER_KEY` (marcador `sk-shoplab-master-local-only`), `LITELLM_KEY_BACKEND`, `LITELLM_KEY_INGEST`, `LITELLM_POSTGRES_PASSWORD` (marcador `change-me-local-only`), `LITELLM_CHAT_PRIMARY_MODEL`, `LITELLM_CHAT_FALLBACK_MODEL`, `LITELLM_EMBED_MODEL`, `OLLAMA_FALLBACK_MODEL`, `CLOUD_PROVIDER_API_KEY` (vacío), `GATEWAY_ENVIRONMENT=local` y los precios opcionales `LITELLM_<MODELO>_INPUT_COST`/`LITELLM_<MODELO>_OUTPUT_COST`; todos con marcadores obviamente locales
- [X] T015 Editar `.github/workflows/ci.yml`: (a) en el job `changes` añadir el filtro y el output `litellm` (`'infra/litellm/**'`, `'infra/docker-compose.yml'`, `'infra/docker-compose.*.yml'`, `'infra/.env.example'`); (b) en el paso «Select Python components» añadir `"infra/litellm"` a la matriz cuando `litellm` o `ci` sean `true` (exporta el output al `env` del paso); (c) en el job `observability` (el que ya valida los perfiles de compose) ampliar su `if:` para que corra también cuando cambie `litellm` y añadir los pasos `docker compose -f infra/docker-compose.yml config -q` (perfil `core`) y `docker compose -f infra/docker-compose.yml -f infra/docker-compose.ollama-host.yml config -q`
- [X] T100 [P] Crear `infra/litellm/tests/test_config_invariants.py` (pytest, lee `infra/litellm/config.yaml`, `keys.yaml`, `docker-compose.yml` y `.env.example` con `pyyaml`; se amplía cuando T068 y T086 añaden `chat-fallback` y `chat-cloud`): todo modelo de `model_list` lleva `model_info.input_cost_per_token` (y `output_cost_per_token` salvo embeddings) (FR-022); `embeddings` y `chat-cloud` no aparecen en `router_settings.fallbacks` ni como destino (FR-009, FR-012); `enable_mid_stream_fallback_continuation` es `false`; `turn_off_message_logging` es `true` y `store_prompts_in_spend_logs` es `false` (FR-026); ni `docker-compose.yml` ni `.env.example` contienen imágenes `:latest` ni valores con forma de clave real `sk-[A-Za-z0-9]{20,}` (SC-007)
- [ ] T016 Verificar la fase: `docker compose -f infra/docker-compose.yml up -d --wait`, `curl -s http://localhost:4000/health/liveliness`, `curl -s http://localhost:4000/v1/chat/completions` con la clave maestra y `model: chat` y `model: embeddings` contra Ollama, `docker compose -f infra/docker-compose.yml port ollama 11434` sin puerto publicado, `docker compose -f infra/docker-compose.yml exec ingest python -c "import socket; socket.create_connection(('ollama', 11434), 2)"` falla (FR-002), `litellm` sí alcanza a `ollama`, y `docker compose … --profile observability --profile llmops config -q` sin conflictos de puertos, nombres ni volúmenes (FR-031); `cd infra/litellm && uv run pytest && uv run ruff check .` en verde (incluye T100) *(Parcial: `compose config -q` de `core`, `observability`, `llmops` y el override, `pytest` y `ruff` en verde, y red `models` probada. Falta `up -d --wait` con Ollama real.)*

**Checkpoint**: el gateway enruta `chat` y `embeddings` a Ollama con precios; el backend aún usa Ollama directamente (compilará pero no llegará a Ollama porque ya no publica puerto: no ejecutar el backend hasta completar US1).

---

## Phase 3: User Story 1 - Todas las llamadas a modelos pasan por el gateway (Priority: P1) 🎯 MVP

**Goal**: el chat y los embeddings del backend salen solo hacia el gateway (API compatible con OpenAI); el backend no
conoce ningún servidor de modelos; `ingest` queda sin ruta a Ollama; el comportamiento observable del chat no cambia.

**Independent Test**: con solo Ollama + gateway, preguntar al chat sobre un documento indexado y subir un documento nuevo;
ambas operaciones aparecen en el gateway y Ollama solo recibe peticiones del gateway (quickstart §1-2); `mvn test` verde.

### Tests for User Story 1 (escribir primero, deben fallar)

- [X] T017 [P] [US1] Crear `BT/chat/GatewayStubs.java` (WireMock) a partir de `BT/chat/OllamaStubs.java`, **conservando los nombres de los métodos públicos** para minimizar el cambio en las ~18 clases de prueba, pero sirviendo SSE de `/v1/chat/completions` y JSON de `/v1/embeddings` con las formas capturadas en T002 (stream normal con y sin `usage`, llamada a herramienta, corte a mitad, conexión cerrada, respuesta lenta) y los errores de T006 (400 `budget_exceeded`, 401, 404 modelo, 503); copiar los fixtures de `BT/../resources/ollama/chat-stream.ndjson` a `backend/src/test/resources/gateway/`
- [X] T018 [P] [US1] Crear `BT/chat/RawGatewayServer.java` a partir de `BT/chat/RawOllamaServer.java` (servidor crudo para cortes de conexión y respuestas parciales) hablando SSE OpenAI-compatible
- [X] T019 [P] [US1] Crear `BT/chat/infrastructure/out/gateway/GatewayChatAdapterTests.java` (migra `OllamaChatAdapterTests`): streaming con los mismos eventos, uso de tokens informado, ambos en cero ⇒ `TokenUsage.unknown()`, modelo `chat` pedido (nunca un nombre real), cancelación, llamada a herramientas
- [X] T020 [P] [US1] Crear `BT/chat/infrastructure/out/gateway/GatewayErrorTranslatorTests.java`: 5xx, conexión rechazada, tiempo agotado, 401 y 404/400 «modelo desconocido» ⇒ `ChatModelException` con `CHAT_MODEL_UNAVAILABLE` sin detalles internos en el mensaje (la rama de presupuesto se añade en US4) *(Cubierto en `GatewayChatAdapterTests` y `GatewayErrorsIntegrationTests`.)*
- [X] T021 [P] [US1] Actualizar `BT/retrieval/infrastructure/out/embedding/SpringAiEmbeddingAdapterTests.java`: la petición va a `/v1/embeddings` con `model: embeddings`, devuelve vectores de 1024 dimensiones, otra dimensión ⇒ `EMBEDDING_DIMENSION_MISMATCH` (US2, escenario 3), fallo del gateway ⇒ `EMBEDDING_SERVICE_UNAVAILABLE`/`EMBEDDING_SERVICE_FAILED`, y que el cuerpo **no** lleva `dimensions`
- [X] T022 [P] [US1] Crear `BT/chat/NoModelServerAddressTests.java`: ninguna `backend/src/main/resources/application*.yml` ni código de `backend/src/main` contiene `ollama` como dirección, propiedad `spring.ai.ollama.*` o puerto `11434` (FR-001, US1 escenario 3); crear `ingest/tests/test_no_model_clients.py` (pytest): `ingest/app/**` no importa clientes de modelos (`openai`, `ollama`, `litellm`, `anthropic`) ni contiene `11434` (research R-14)
- [X] T023 [P] [US1] Actualizar `BT/architecture/ModuleArchitectureRules.java`: añadir `com.openai..` a los paquetes prohibidos en dominio y aplicación (junto a `org.springframework.ai..` y `io.opentelemetry..`) y comprobar en `ModuleArchitectureTests` que `retrieval` no importa nada de `chat` (FR-005, Constitución I)
- [X] T024 [P] [US1] Añadir a `BT/chat/ChatStreamContractTests.java` (o crear `BT/chat/ChatGatewayDownIntegrationTests.java`) el caso de gateway detenido: el usuario recibe el mismo error tipado `SLHD_CHAT_00002` (503) que con el servidor de modelos caído, sin detalles internos (US1 escenario 6; el existente `ChatModelDownIntegrationTests` puede cubrirlo tras migrar el stub)
- [X] T099 [P] [US1] Crear `infra/litellm/tests/test_compose_isolation.py` (pytest; se omite si no hay `docker compose`): con `docker compose -f ../docker-compose.yml config --format json` comprueba que `ollama` no tiene `ports`, que la red `models` solo contiene `ollama` y `litellm`, que `ingest` y `postgres` no están en `models` y que `litellm` está en `default` y `models`; con `-f ../docker-compose.ollama-host.yml` `ollama` sí publica `${OLLAMA_PORT:-11434}` (FR-002, US1 esc. 4)

### Implementation for User Story 1

- [X] T025 [US1] Editar `backend/pom.xml`: sustituir `spring-ai-starter-model-ollama` por `spring-ai-starter-model-openai` (versión por el BOM de Spring AI 2.0.2 ya presente) y comprobar con `mvn dependency:tree` que no queda ninguna dependencia de Ollama
- [X] T026 [US1] Editar `backend/src/main/resources/application.yml`: eliminar el bloque `spring.ai.ollama.*` (líneas ~18-27) y conservar `shoplab.chat.tracing.pricing.ollama` (línea ~66, tabla de precios de LAB-64) y añadir `spring.ai.openai.base-url: ${SPRING_AI_OPENAI_BASE_URL:http://localhost:4000/v1}`, `spring.ai.openai.api-key: ${SPRING_AI_OPENAI_API_KEY:sk-shoplab-backend-local}`, `spring.ai.openai.chat.options.model: chat`, `spring.ai.openai.embedding.options.model: embeddings`, `shoplab.retrieval.embedding.model-name: ${SHOPLAB_RETRIEVAL_EMBEDDING_MODEL_NAME:bge-m3}`, `shoplab.chat.gateway.primary-model: ${SHOPLAB_CHAT_GATEWAY_PRIMARY_MODEL:llama3.2:3b}` y `shoplab.chat.gateway.request-timeout: ${SHOPLAB_CHAT_GATEWAY_REQUEST_TIMEOUT:60s}`; sin `temperature` ni demás opciones del modelo (viven en el gateway)
- [X] T027 [P] [US1] Crear `BE/chat/config/GatewayProperties.java` (`@ConfigurationProperties("shoplab.chat.gateway")`: `primaryModel` String vacío permitido, `requestTimeout` Duration por defecto 60 s) y registrarlo donde se registran `TracingProperties`/`EmbeddingProperties`; actualizar `BE/retrieval/config/EmbeddingProperties.java` para que `model-name` sea la propiedad `shoplab.retrieval.embedding.model-name` (valor por defecto `bge-m3`, ya no `spring.ai.ollama.embedding.options.model`) y corregir su Javadoc
- [X] T028 [P] [US1] Crear `BE/chat/domain/model/constants/GatewayConstants.java` (`@UtilityClass`) solo con lo propio del chat (nombre lógico `chat`); **no** poner aquí nada que use `retrieval`, porque un import de `retrieval` hacia `chat` rompe Modulith (Constitución I)
- [X] T098 [P] [US1] Crear `BE/shared/constants/GatewayHeaders.java` (`@UtilityClass`; `shared` es módulo `OPEN`) con `REQUEST_ID_HEADER = "x-shoplab-request-id"` y la clave del contexto de Reactor `REQUEST_ID_CONTEXT_KEY`; es el único punto compartido entre `chat` y `retrieval` para correlacionar con el gateway (FR-025)
- [X] T029 [US1] Mover `BE/chat/infrastructure/out/ollama/` a `BE/chat/infrastructure/out/gateway/` con `git mv` (`ToolCallbackComposer`, `TracedToolCallback`, `OllamaChatAdapter` → `GatewayChatAdapter`) y sus pruebas a `BT/chat/infrastructure/out/gateway/` (`ToolCallbackComposerTests`, `TracedToolCallbackTests`, `OllamaChatAdapterTraceTests` → `GatewayChatAdapterTraceTests`); `OllamaChatAdapterTests` se sustituye por el nuevo `GatewayChatAdapterTests` de T019 y se borra con `git rm`; corregir paquetes e imports; sin cambios de lógica en este paso
- [X] T030 [US1] Reescribir `BE/chat/infrastructure/out/gateway/GatewayChatAdapter.java` sobre `OpenAiChatModel`/`OpenAiChatOptions` de Spring AI (`model: chat`, `ChatModel.stream` → `Flux`, mismo mecanismo de herramientas y de `TurnTrace` que el adaptador actual, `stream_options.include_usage` por defecto, `timeout` de `GatewayProperties`); el puerto `BE/chat/application/port/out/ChatModelPort.java` no cambia; sin `boundedElastic` (ADR-0011) ni llamadas bloqueantes (BlockHound)
- [X] T031 [US1] Crear `BE/chat/infrastructure/out/gateway/GatewayErrorTranslator.java`: traduce las excepciones de `com.openai.errors.*` y de conexión/tiempo agotado a `ChatModelException` (`CHAT_MODEL_UNAVAILABLE`), registra solo `type`/`code` del error (nunca el texto) y se usa desde `GatewayChatAdapter`; deja un punto de extensión para la rama de presupuesto de US4 (depende de T030)
- [X] T032 [US1] Editar `BE/retrieval/infrastructure/out/embedding/SpringAiEmbeddingAdapter.java` para usar `OpenAiEmbeddingModel` con `model: embeddings`, `modelName()` desde `EmbeddingProperties.modelName`, sin enviar `dimensions`, y traducir los errores del gateway a `EMBEDDING_SERVICE_UNAVAILABLE`/`EMBEDDING_SERVICE_FAILED` conservando `EMBEDDING_DIMENSION_MISMATCH` (depende de T027)
- [X] T033 [US1] Migrar mecánicamente a `GatewayStubs`/`RawGatewayServer` las pruebas que citan Ollama: `BT/chat/ChatCitationsIntegrationTests.java`, `ChatMemoryDisabledIntegrationTests.java`, `ChatMemoryDownIntegrationTests.java`, `ChatMemoryIntegrationTests.java`, `ChatMidStreamFailureIntegrationTests.java`, `ChatModelDownIntegrationTests.java`, `ChatStreamContractTests.java`, `ChatStreamingIntegrationTests.java`, `RagAnsweringIntegrationTests.java`, `RagConfigurationIntegrationTests.java`, `ToolCompositionIntegrationTests.java`, `TraceMidStreamFailureIntegrationTests.java`, `TraceResilienceIntegrationTests.java`, `TraceSpecialTurnsIntegrationTests.java`, `TurnTraceIntegrationTests.java`, `TurnTraceToolCallIntegrationTests.java`, `BT/blockhound/TracingBlockHoundTests.java`, `BT/chat/support/EmptyKnowledgeContextConfig.java`; ajustar propiedades de prueba (`spring.ai.openai.base-url` al puerto de WireMock); borrar `BT/chat/OllamaStubs.java`, `BT/chat/RawOllamaServer.java` y `backend/src/test/resources/ollama/` (depende de T017, T018, T030, T032)
- [X] T034 [US1] Ejecutar `cd backend && ./mvnw -B -ntp clean verify` y corregir hasta verde: contrato de `POST /api/chat` y de la ingesta sin cambios (eventos SSE, citas, historial, herramientas, tokens), cobertura JaCoCo ≥ 80 %, ArchUnit/Modulith y BlockHound en verde (SC-001 de la parte automática)
- [ ] T035 [US1] Verificar en local (quickstart §1-2, SC-001): arrancar el backend con `mvn spring-boot:run`, hacer una pregunta con documento indexado y subir un documento nuevo; comprobar con `docker compose -f infra/docker-compose.yml logs litellm` que ambas operaciones pasaron por el gateway con `docker compose … logs ollama` que solo recibe peticiones de `litellm` y con `docker compose … exec ingest python -c "import socket; socket.create_connection(('ollama', 11434), 2)"` que `ingest` no alcanza a Ollama; ejecutar las preguntas de referencia del quickstart (y el dataset de `eval/` si ya existe por LAB-30) contra la rama anterior y esta, y registrar en `specs/LAB-84-litellm-model-gateway/validation.md` que citas, herramientas y uso del contexto (`num_ctx` 8192) no empeoran (Constitución V) *(Pendiente: requiere Ollama real con los modelos descargados.)*

**Checkpoint**: US1 completa y demostrable. MVP: todo el tráfico a modelos pasa por el gateway.

---

## Phase 4: User Story 2 - Cambiar de modelo sin tocar el código (Priority: P1)

**Goal**: cambiar el modelo real de un nombre lógico editando solo la configuración del gateway; errores de configuración
visibles al arrancar; documentar que cambiar el modelo de embeddings exige reindexar.

**Independent Test**: cambiar `LITELLM_CHAT_PRIMARY_MODEL`, recargar solo el gateway y preguntar: la respuesta y el log
muestran el modelo nuevo sin reiniciar el backend (quickstart §3, SC-002).

### Tests for User Story 2

- [X] T036 [P] [US2] Crear `infra/litellm/tests/test_check_models.py` (pytest) para `infra/litellm/check_models.py`: con un servidor falso de `/api/tags` que lista solo `llama3.2:3b`, un modelo configurado y no descargado produce una línea `WARN model_missing model=<…> name=<…>` y código de salida 0 (no impide arrancar); con Ollama inalcanzable reintenta hasta `CHECK_MODELS_TIMEOUT` y termina sin romper (US2 escenario 2, FR-008, caso «arranca antes que Ollama»)
- [X] T037 [P] [US2] Crear `BT/chat/GatewayErrorsIntegrationTests.java`: petición con nombre de modelo que el gateway no tiene (stub 404/400 de T006) ⇒ el usuario del chat recibe `SLHD_CHAT_00002` (503), no un fallo genérico; 401 ⇒ `SLHD_CHAT_00002` y aviso en el log del backend sin la clave (US2 escenario 4, FR-004)

### Implementation for User Story 2

- [X] T038 [US2] Crear `infra/litellm/check_models.py` (stdlib): lee `model_list` del `config.yaml` renderizado, consulta `${OLLAMA_API_BASE}/api/tags` y emite `WARN model_missing …` por cada modelo local configurado y no descargado; reintenta mientras Ollama no responde; siempre termina con 0 (depende de T036)
- [X] T039 [US2] Editar `infra/litellm/entrypoint.sh` para ejecutar `check_models.py` tras renderizar la configuración y antes de lanzar `litellm`, de modo que el aviso quede en los registros del gateway en cada arranque o recarga (FR-008); montar `check_models.py` en `infra/docker-compose.yml`
- [X] T040 [US2] Completar `BE/chat/infrastructure/out/gateway/GatewayErrorTranslator.java` y `SpringAiEmbeddingAdapter` para que 400/404 «modelo no existe» y 401 queden cubiertos por T037 (si T031 ya los trató, solo ajustar hasta que T037 pase)
- [X] T041 [US2] Crear `docs/gateway-de-modelos.md` con la guía inicial: levantar el gateway, nombres lógicos (`chat`, `embeddings`, `chat-cloud` opcional), cómo cambiar el modelo de `chat` (variable + `docker compose … up -d litellm`, que recrea el contenedor; si solo se edita el `infra/litellm/config.yaml` montado, usar `docker compose … restart litellm`) y **advertencia de que cambiar el modelo de embeddings con otra dimensión exige reindexar** (ADR-0003, ADR-0017; el backend no mezcla vectores: `EMBEDDING_DIMENSION_MISMATCH`); nota de que `notebooks/` sigue usando Ollama directamente (research R-13)
- [ ] T042 [US2] Verificar en local (quickstart §3, SC-002): cambiar `LITELLM_CHAT_PRIMARY_MODEL` a otro modelo descargado, `docker compose -f infra/docker-compose.yml up -d litellm` sin tocar el backend y comprobar el modelo nuevo en el log del gateway en < 3 minutos; poner un modelo inexistente y ver la línea `model_missing` al arrancar; `cd infra/litellm && uv run pytest && uv run ruff check .` en verde *(Pendiente: requiere Ollama real con los modelos descargados.)*

**Checkpoint**: US1 y US2 funcionan; ambas P1 completas.

---

## Phase 5: User Story 5 - Coste y tokens por conversación en los logs del gateway (Priority: P2)

**Goal**: una línea JSON por petición con consumidor, conversación, modelos, tokens, coste, latencia y resultado, sin
contenido; el backend envía la conversación y el id de petición.

**Independent Test**: conversación de tres turnos y, solo con los logs del gateway, obtener tokens y coste totales que
coinciden con la suma de los turnos (quickstart §6, SC-006) sin texto de preguntas ni respuestas (SC-009).

### Tests for User Story 5

- [X] T043 [P] [US5] Crear `infra/litellm/tests/test_shoplab_callbacks.py` (pytest) con `standard_logging_payload` simulados según T004: éxito de chat (una sola línea `event: llm_request` con todos los campos de `contracts/log-schema.md`), `conversation_id` desde `user` UUID y `null` en embeddings, `operation` = `chat`/`document-index`/`search-query`, `request_id` desde `x-shoplab-request-id`, tokens `null` cuando faltan (nunca 0), `cost_status` = `priced`/`unpriced`/`unknown_usage`, fallo ⇒ `result` ≠ `success` con tokens/coste `null`, petición con fallback ⇒ **una** línea con el modelo realmente servido y `fallback: true`, error interno del callback ⇒ línea `callback_error` sin datos y sin lanzar; y un caso que verifica que **ningún** texto de `messages`/`response`/cabecera `Authorization` aparece en la salida (FR-026)
- [X] T044 [P] [US5] Añadir a `BT/chat/infrastructure/out/gateway/GatewayChatAdapterTests.java` y a `BT/retrieval/infrastructure/out/embedding/SpringAiEmbeddingAdapterTests.java` la comprobación sobre WireMock de que el chat envía `user` = UUID de la conversación (omitido si no hay `conversationId`) y la cabecera `x-shoplab-request-id` = `X-Request-Id` del turno, y que los embeddings envían `user` = `purpose:document-index` o `purpose:search-query` y la misma cabecera cuando el contexto trae el id de petición (FR-025)
- [X] T045 [P] [US5] Actualizar `BT/retrieval/application/usecase/SearchChunksUseCaseTests.java`, la prueba de `DocumentEmbedder` (localizarla con `grep -rl DocumentEmbedder backend/src/test`) y `BT/retrieval/support/DeterministicEmbeddingPort.java`/`DeterministicEmbeddingPortTests.java` para la nueva firma `embed(texts, purpose)` con `EmbeddingPurpose` (escribir primero; no compilarán hasta T049)

### Implementation for User Story 5

- [X] T046 [US5] Crear `infra/litellm/shoplab_callbacks.py` con la clase `ShoplabLogger(CustomLogger)` y la instancia `shoplab_logger`: `async_log_success_event`/`async_log_failure_event` escriben exactamente una línea JSON a stdout con el esquema de `contracts/log-schema.md`, sin leer nunca `messages`, `input`, `response` ni cabeceras de autorización, campos ausentes ⇒ `null`, errores propios capturados (depende de T043)
- [X] T047 [US5] Editar `infra/litellm/config.yaml`: `litellm_settings.callbacks: shoplab_callbacks.shoplab_logger`; editar `infra/docker-compose.yml` para montar `shoplab_callbacks.py` de solo lectura en el directorio del que LiteLLM carga el módulo (según T004)
- [X] T048 [P] [US5] Crear `BE/retrieval/domain/model/enums/EmbeddingPurpose.java` (`DOCUMENT_INDEX` → `purpose:document-index`, `SEARCH_QUERY` → `purpose:search-query`) con el valor de `user` como campo del propio enum y `userValue()` (patrón `ClientProfile` de la guía §2.2); no depende de constantes del módulo `chat` (Constitución I)
- [X] T049 [US5] Editar `BE/retrieval/application/port/out/EmbeddingPort.java` a `embed(List<String> texts, EmbeddingPurpose purpose)` y actualizar `BE/retrieval/application/usecase/DocumentEmbedder.java` (`DOCUMENT_INDEX`) y `BE/retrieval/application/usecase/SearchChunksUseCase.java` (`SEARCH_QUERY`) y el adaptador `SpringAiEmbeddingAdapter` (`OpenAiEmbeddingOptions.user` y, si hay id de petición en el contexto de Reactor, la cabecera `GatewayHeaders.REQUEST_ID_HEADER`)
- [X] T050 [US5] Propagar el id de petición y el de conversación por el contexto de Reactor, igual que `TracingConstants.TURN_TRACE_KEY` (`StreamChatCommand` y `StreamChatUseCase` ya llevan `requestId`; localizar dónde se escribe el contexto con `grep -rn TURN_TRACE_KEY backend/src/main`): `StreamChatUseCase` escribe `GatewayHeaders.REQUEST_ID_CONTEXT_KEY` y la conversación; `GatewayChatAdapter` los añade a `OpenAiChatOptions.user` (conversación) y `customHeaders` (id de petición) solo si existen; el adaptador de embeddings lee la misma clave del contexto para que las búsquedas de un turno crucen con su log; sin cambiar la firma de `ChatModelPort` (depende de T030, T098)
- [X] T051 [US5] Documentar en `docs/gateway-de-modelos.md` el procedimiento de coste por conversación (FR-027, US5 escenario 6): el `jq` de `contracts/log-schema.md#coste-y-tokens-de-una-conversación-fr-027-sc-006` para compose y para el clúster, `unpriced_or_unknown` y la explicación de que el coste del gateway (precios de referencia) y el de las trazas de LAB-64 (Ollama = 0) son dos mediciones distintas (ADR-0022)
- [ ] T052 [US5] Verificar en local (quickstart §6, SC-006, SC-009): conversación de 3 turnos con el mismo `conversationId`, ejecutar la consulta y comprobar que suma los turnos; una subida de documento aparece con `operation: document-index`; `docker compose … logs litellm | grep -c "<texto de la pregunta>"` devuelve 0 y no hay claves ni cabeceras de autorización; `uv run pytest` y `./mvnw -B -ntp clean verify` en verde *(Parcial: línea de log, coste y privacidad (SC-009) verificados contra el gateway real; falta la conversación de 3 turnos con el backend.)*

**Checkpoint**: coste y tokens por conversación visibles solo con los logs del gateway.

---

## Phase 6: User Story 4 - Presupuesto por clave virtual y entorno (Priority: P2)

**Goal**: una clave virtual por consumidor (backend, ingest) y entorno con presupuesto y reinicio de periodo, creadas
desde configuración versionada sin alta manual; presupuesto agotado ⇒ error tipado distinto del de modelo no disponible.

**Independent Test**: con un presupuesto mínimo en la clave del backend, hacer preguntas hasta agotarlo: el chat devuelve
429 `SLHD_CHAT_00007` y `ingest-local` sigue aceptando peticiones (quickstart §5, SC-005).

### Tests for User Story 4

- [X] T053 [P] [US4] Crear `infra/litellm/tests/test_bootstrap_keys.py` (pytest, servidor HTTP falso de `/health/readiness`, `/key/generate`, `/key/update`, `/key/info` con los cuerpos de T005): crea las claves que faltan (`created alias=…`), segunda ejecución ⇒ `unchanged`, cambio de `max_budget`/`budget_duration`/`models` ⇒ `updated`, nunca borra claves desconocidas, no imprime valores de clave (solo alias), reintenta hasta `BOOTSTRAP_TIMEOUT`, sale ≠ 0 si no converge; validaciones de `data-model.md`: alias único y con patrón, `max_budget > 0`, `budget_duration` reconocida (`1h`, `1d`, `7d`, `30d`), `key_env` definida y no vacía, y rechazo de valores `change-me`/`local` cuando `GATEWAY_ENVIRONMENT=dev`; la clave de `ingest` se crea con el valor que fijó S-5 para «ningún modelo» (nunca una lista vacía que dé acceso a todos)
- [X] T054 [P] [US4] Crear `BT/chat/GatewayBudgetIntegrationTests.java`: stub 400 `budget_exceeded` (T006) antes del primer fragmento ⇒ HTTP 429 con `SLHD_CHAT_00007`, mensaje fijo sin detalles y **distinto** de `SLHD_CHAT_00002` (US4 escenario 3, FR-004); el mismo error en embeddings ⇒ `SLHD_RETR_00010`; un 401 sigue siendo `SLHD_CHAT_00002`
- [X] T055 [P] [US4] Actualizar `BT/shared/exception/ResponseCodeEnumTests.java` con `CHAT_BUDGET_EXCEEDED` = `SLHD_CHAT_00007` (429) y `EMBEDDING_BUDGET_EXCEEDED` = `SLHD_RETR_00010` (429)

### Implementation for User Story 4

- [X] T056 [US4] Crear `infra/litellm/keys.yaml` según `contracts/gateway-config.md` (`environment: ${GATEWAY_ENVIRONMENT}`; claves `backend-<env>` con `models: [chat, embeddings]`, `max_budget: 5.00`, `budget_duration: 1d`, `key_env: LITELLM_KEY_BACKEND`, e `ingest-<env>` sin acceso a ningún modelo (representación fijada en T005), `max_budget: 1.00`, `key_env: LITELLM_KEY_INGEST`)
- [X] T057 [US4] Crear `infra/litellm/bootstrap_keys.py` (stdlib + `pyyaml` de la imagen) según el contrato: valida, espera `/health/readiness`, hace *upsert* idempotente con la clave maestra usando los cuerpos de T005 y expande `${GATEWAY_ENVIRONMENT}` con `render_config.py` (depende de T053)
- [X] T058 [US4] Editar `infra/docker-compose.yml`: servicio `litellm-bootstrap` (misma imagen, `restart: "no"`, `entrypoint` que ejecuta `bootstrap_keys.py`, monta `keys.yaml` y el script, variables `LITELLM_BASE_URL=http://litellm:4000`, `LITELLM_MASTER_KEY`, `LITELLM_KEY_BACKEND`, `LITELLM_KEY_INGEST`, `GATEWAY_ENVIRONMENT`, `depends_on: litellm: condition: service_healthy`); comprobar que `up -d --wait` termina con 0 (resultado de T001)
- [X] T059 [P] [US4] Editar `BE/shared/exception/ResponseCodeEnum.java`: añadir `CHAT_BUDGET_EXCEEDED("SLHD_CHAT_00007", HttpStatus.TOO_MANY_REQUESTS, …)` y `EMBEDDING_BUDGET_EXCEEDED("SLHD_RETR_00010", HttpStatus.TOO_MANY_REQUESTS, …)` con mensaje fijo sin detalles; crear `BE/chat/domain/exception/ChatBudgetExceededException.java` (`TechnicalException`) y la excepción técnica equivalente de `retrieval` junto a las de embeddings existentes (`grep -rn EMBEDDING_SERVICE_UNAVAILABLE backend/src/main`); verificar en `GlobalExceptionHandler` que se mapean sin código nuevo
- [X] T060 [US4] Completar `GatewayErrorTranslator` (chat) y el equivalente en `SpringAiEmbeddingAdapter`: 400 con `type: budget_exceeded` ⇒ la excepción de presupuesto; registrar solo `type`/`code`; sin tocar el caso de uso (ya distingue antes/después del primer fragmento) (depende de T054, T059)
- [X] T061 [US4] Documentar en `docs/gateway-de-modelos.md` claves y presupuestos: `keys.yaml`, cómo reejecutar `docker compose -f infra/docker-compose.yml run --rm litellm-bootstrap`, consultar el gasto, reinicio por periodo, que perder la base del gateway recrea claves y presupuestos pero puede perder el gasto acumulado (FR-023) y los precios de referencia de los modelos locales
- [ ] T062 [US4] Verificar en local (quickstart §5, SC-005): primera subida crea `backend-local` e `ingest-local` sin alta manual (`created`), segunda `unchanged`; bajar `max_budget` a `0.0001`, `run --rm litellm-bootstrap`, agotarlo con preguntas ⇒ 429 `SLHD_CHAT_00007` y `result: budget_exceeded` en el log mientras `ingest-local` sigue aceptando; confirmar que el gasto sube con el modelo local (precio de referencia, US4 escenario 5); clave inexistente ⇒ 401 y ningún modelo atendido (US4 escenario 6); comprobar que el rechazo por presupuesto, el 401 y el modelo desconocido dejan su línea de log con el `result` correspondiente (FR-020); persistencia (FR-023): `docker compose … restart litellm` conserva claves y gasto, y `docker compose … down -v` seguido de `up` hace que `litellm-bootstrap` recree `backend-local` e `ingest-local` con el gasto a cero; restaurar `max_budget`; `uv run pytest` y `./mvnw -B -ntp clean verify` en verde *(Parcial: created/unchanged/updated, presupuesto agotado (422) y aislamiento de claves verificados contra el gateway real; falta la persistencia tras reiniciar y la prueba con el backend.)*

**Checkpoint**: presupuestos por clave y entorno funcionando y auditables con el log de US5.

---

## Phase 7: User Story 3 - Fallback demostrable entre dos modelos locales (Priority: P2)

**Goal**: si el modelo principal falla o supera el tiempo antes del primer fragmento, el gateway responde con el de
respaldo; los registros y la traza identifican el modelo servido y el fallback; procedimiento documentado reproducible en < 5 min.

**Independent Test**: dejar inaccesible el principal siguiendo el procedimiento, preguntar y comprobar respuesta completa,
`fallback: true` en el log y vuelta al principal al restaurarlo (quickstart §4, SC-003).

### Tests for User Story 3

- [X] T063 [P] [US3] Crear `BT/chat/GatewayFallbackIntegrationTests.java`: el stub responde con `model` = respaldo (`llama3.2:1b`) ⇒ el usuario recibe la respuesta completa sin error y la traza de LAB-64 muestra el modelo servido y `fallback = true`; con `model` = principal ⇒ `fallback = false`; con `shoplab.chat.gateway.primary-model` vacío nunca se marca fallback; modelo servido ausente ⇒ `unknown`
- [X] T064 [P] [US3] Ampliar `BT/chat/ChatMidStreamFailureIntegrationTests.java` y crear en `BT/chat/ChatModelDownIntegrationTests.java` los casos: corte tras el primer fragmento ⇒ error `CHAT_STREAM_FAILED` conservando el texto parcial y **sin** reiniciar con otro modelo (edge case de la spec); principal y respaldo caídos (stub 503) o respuesta más lenta que `request-timeout` ⇒ `SLHD_CHAT_00002` en un tiempo acotado, nunca una espera indefinida (US3 escenario 5, FR-014) *(Cubierto por `GatewayErrorsIntegrationTests` y los tests de fallo a mitad de stream existentes.)*
- [X] T065 [P] [US3] Crear `BT/chat/domain/model/ServedModelTests.java` (o junto al paquete de `ServedModel`): `fallback` solo si `name` ≠ `primary-model`; sin dependencias de Spring AI ni de OpenAI en el dominio

### Implementation for User Story 3

- [X] T066 [P] [US3] Crear `BE/chat/domain/model/ServedModel.java` (record `name`, `fallback`; `name = "unknown"` si la respuesta no lo trae) y editar `BE/chat/application/port/out/GenerationScope.java` para que `complete(TokenUsage usage, String answer, ServedModel served)` (actualizar `GenerationScope.NONE` y todas las implementaciones y llamadas: `grep -rn "GenerationScope\|\.complete(" backend/src`)
- [X] T067 [US3] Editar `GatewayChatAdapter` para extraer el modelo servido de los metadatos de la respuesta (campo `model` del chunk) y construir `ServedModel` con `GatewayProperties.primaryModel`; editar `BE/chat/infrastructure/out/tracing/LangfuseChatTrace.java` para registrar el modelo servido y la marca `fallback` en la generación en lugar del alias lógico `chat` (FR-015); mantener `TurnTrace` sin dependencias de Spring AI (depende de T063, T066)
- [X] T068 [US3] Editar `infra/litellm/config.yaml`: añadir el despliegue `chat-fallback` (`ollama_chat/${LITELLM_CHAT_FALLBACK_MODEL:-llama3.2:1b}`, `num_ctx: 8192`, `model_info.id: chat-fallback`, precios de referencia menores) y `router_settings` (`fallbacks: [{ chat: [chat-fallback] }]`, `num_retries: 1`, `timeout: 60`, `stream_timeout: 30`, `allowed_fails: 1`, `cooldown_time: 30`) con los ajustes que dictó T003; sin fallback para `embeddings` (FR-009) ni para `chat-cloud` (FR-012)
- [X] T069 [US3] Editar `infra/ollama/entrypoint.sh` y la sección `ollama` de `infra/docker-compose.yml`: si `OLLAMA_FALLBACK_MODEL` no está vacío, descargarlo también y exigirlo en el healthcheck; si está vacío, no se descarga nada (modelo de respaldo solo si se activa); pasar `LITELLM_CHAT_FALLBACK_MODEL`/`OLLAMA_FALLBACK_MODEL` al servicio `litellm`
- [X] T070 [US3] Documentar en `docs/gateway-de-modelos.md` la demostración del fallback (FR-017) con el procedimiento fijado en T003: activar `OLLAMA_FALLBACK_MODEL`, hacer inalcanzable el principal (`LITELLM_CHAT_PRIMARY_MODEL=modelo-que-no-existe` y el caso «contenedor parado»), leer `fallback: true` y `served_model_id: chat-fallback` en el log, restaurar y comprobar la vuelta al principal sin reiniciar nada; reproducible en < 5 min sin claves de nube y sin editar código
- [ ] T071 [US3] Verificar en local (quickstart §4, SC-003): 20 preguntas con el principal inaccesible ⇒ todas completas con el respaldo y `fallback: true`; el modelo servido correcto en el log del gateway y en la traza de Langfuse (perfil `llmops`); restaurar ⇒ `fallback: false`; principal lento (más que `stream_timeout`) ⇒ respuesta completa con el respaldo (US3 esc. 3); ambos caídos ⇒ `SLHD_CHAT_00002` tras el tiempo acotado; `./mvnw -B -ntp clean verify` en verde *(Pendiente: requiere Ollama real con el modelo de respaldo; el fallo con el principal en 404 ya se verificó.)*

**Checkpoint**: fallback demostrable en local y visible en log y traza.

---

## Phase 8: User Story 6 - Proveedor cloud opcional, desactivado sin clave (Priority: P3)

**Goal**: sin `CLOUD_PROVIDER_API_KEY` no existe `chat-cloud` ni hay salidas a Internet; con ella pasa a estar
disponible, con presupuesto y coste, y nunca se usa si no se configura explícitamente como principal o respaldo.

**Independent Test**: arrancar sin clave y comprobar que `chat-cloud` no figura en `/v1/models` y pedirlo da error claro;
con un servidor simulado compatible y una clave de prueba, aparece y se puede pedir (quickstart §7).

### Tests for User Story 6

- [X] T085 [P] [US6] Ampliar `infra/litellm/tests/test_render_config.py` y crear `infra/litellm/tests/fake_openai_server.py` (servidor HTTP mínimo compatible con OpenAI para las pruebas): sin `CLOUD_PROVIDER_API_KEY` el bloque `chat-cloud` no aparece en el `config.yaml` renderizado; con ella aparece con `api_key: os.environ/CLOUD_PROVIDER_API_KEY`; `chat-cloud` nunca figura en `fallbacks` ni como destino del nombre `chat`; y que `bootstrap_keys.py` solo incluye `chat-cloud` en los modelos permitidos de la clave del backend cuando hay clave (US6 escenarios 1-4) *(La rama con clave se cubre renderizando la plantilla y con `bootstrap_keys`; sin `fake_openai_server.py`.)*

### Implementation for User Story 6

- [X] T086 [US6] Editar `infra/litellm/render_config.py` para soportar un bloque opcional delimitado por marcadores (`#@cloud-begin` / `#@cloud-end`) que solo se conserva si `CLOUD_PROVIDER_API_KEY` no está vacío (o usar el comportamiento nativo de LiteLLM si T001 confirmó que omite el despliegue); añadir a `infra/litellm/config.yaml` el bloque `chat-cloud` (`model: ${CLOUD_PROVIDER_MODEL}` + `api_key: os.environ/CLOUD_PROVIDER_API_KEY`, `api_base` opcional `${CLOUD_PROVIDER_API_BASE}` para el servidor simulado, precio de referencia) sin referencias en `router_settings.fallbacks` (depende de T085)
- [X] T087 [US6] Editar `infra/litellm/bootstrap_keys.py` y `infra/litellm/keys.yaml` para añadir `chat-cloud` a los modelos permitidos del backend solo si `CLOUD_PROVIDER_API_KEY` está definido; editar `infra/docker-compose.yml` y `infra/.env.example` para pasar `CLOUD_PROVIDER_API_KEY`, `CLOUD_PROVIDER_MODEL` y `CLOUD_PROVIDER_API_BASE` (vacíos por defecto) a `litellm` y `litellm-bootstrap`
- [X] T088 [US6] Documentar en `docs/gateway-de-modelos.md` cómo activar un proveedor cloud (variables, reinicio del gateway, que sigue sujeto a presupuesto y a coste) y que ninguna petición del chat lo usa salvo configuración explícita (FR-012); anotar que el `ExternalSecret` de T074 (US7, posterior) debe declarar `CLOUD_PROVIDER_API_KEY` como clave opcional
- [ ] T089 [US6] Verificar (quickstart §7, SC-007): sin clave, `curl http://localhost:4000/v1/models` no lista `chat-cloud`, pedirlo devuelve un error claro y el gateway no intenta salir a Internet; con `fake_openai_server.py` y una clave de prueba, reiniciar el gateway y comprobar que `chat-cloud` aparece, responde y deja su línea de log con coste; `git grep -nE 'sk-[A-Za-z0-9]{20,}'` sin claves nuevas *(Parcial: sin clave, `chat-cloud` no aparece en `/v1/models` del gateway real; falta el caso con clave y servidor simulado.)*

**Checkpoint**: las siete historias funcionan de forma independiente.

---

## Phase 9: User Story 7 - Gateway funcionando en el ambiente DEV del clúster (Priority: P2)

**Goal**: el gateway (y su almacén) corre en `dev` con Argo CD, el backend de DEV lo usa con su clave virtual desde
`ExternalSecret`, sin pasos manuales tras `destroy`/`apply` y sin credenciales versionadas.

**Independent Test**: desplegar con Argo CD, preguntar al chat de DEV y ver en el log del gateway del clúster la petición
con `consumer: backend-dev`, modelo, tokens y coste (quickstart §8, SC-008).

**Repositorio**: `shoplab-infra`. Crear antes la rama
`feature/LAB-84-litellm-model-gateway` en `shoplab-infra`. Seguir los patrones de `k8s/base/langfuse/` y `k8s/base/postgres/`.

- [ ] T072 [US7] Spike S-7 en `shoplab-infra`: medir con `kubectl top` la memoria real de LiteLLM + su Postgres junto a Langfuse en el nodo único de DEV y fijar `requests`/`limits` mínimos; si no caben, aplicar el patrón `EndpointSlice` a un Postgres del host (como `k8s/overlays/dev/postgres-host.yaml`); comprobar además si el CNI del k3s de Floci aplica `NetworkPolicy` de entrada y de salida (política de denegación de prueba y una conexión desde un pod); anotar el resultado en `specs/LAB-84-litellm-model-gateway/research.md` (R-11) *(Pendiente: requiere el clúster.)*
- [X] T073 [P] [US7] Crear en `shoplab-infra` `scripts/sync-litellm-files.sh`: copia `config.yaml`, `shoplab_callbacks.py`, `keys.yaml`, `bootstrap_keys.py`, `render_config.py`, `check_models.py` y `entrypoint.sh` desde `../shoplab-helpdesk/infra/litellm/` a `k8s/base/litellm/files/` y escribe `k8s/base/litellm/files/SOURCE` con el commit de origen, de modo que compose y clúster compartan una sola fuente (research R-11); documentarlo en el encabezado del script y ejecutarlo de nuevo tras cualquier cambio posterior en `infra/litellm/` (el PR de infraestructura debe llevar en `SOURCE` el último commit de origen)
- [X] T074 [US7] Crear `k8s/base/litellm/postgres.yaml` (`StatefulSet` `litellm-postgres` con imagen `postgres:17.7` fija, PVC `local-path`, `Service`, probes, `requests`/`limits` de T072) y `k8s/base/litellm/externalsecret.yaml` (`ExternalSecret litellm-credentials` desde `/shoplab/<ambiente>/litellm` con `LITELLM_MASTER_KEY`, `LITELLM_KEY_BACKEND`, `LITELLM_KEY_INGEST`, `POSTGRES_PASSWORD` y `CLOUD_PROVIDER_API_KEY` opcional) siguiendo `k8s/base/langfuse/externalsecret.yaml`
- [X] T075 [US7] Crear `k8s/base/litellm/deployment.yaml` (1 réplica, imagen de versión fija igual a la de compose, `readinessProbe` en `/health/readiness`, `livenessProbe` en `/health/liveliness`, `DISABLE_ADMIN_UI=true`, `GATEWAY_ENVIRONMENT=dev`, `DATABASE_URL` desde el secreto, `OLLAMA_API_BASE` hacia el `Service` `ollama`, `requests`/`limits` de T072, `securityContext` y demás exigencias de las políticas de `policies/`), `k8s/base/litellm/service.yaml` (`litellm:4000`, solo `ClusterIP`; sin Ingress, FR-035) y `k8s/base/litellm/kustomization.yaml` con `configMapGenerator` desde `files/` (depende de T073)
- [X] T076 [US7] Crear `k8s/base/litellm/job-bootstrap-keys.yaml`: `Job` `litellm-bootstrap-keys` con hook de Argo CD `PostSync` (`argocd.argoproj.io/hook: PostSync`, `hook-delete-policy: BeforeHookCreation`), misma imagen, ejecuta `bootstrap_keys.py` con `LITELLM_BASE_URL=http://litellm:4000` y las variables del `ExternalSecret`; rechaza valores `change-me`/`local` en `dev`
- [X] T077 [US7] Crear `k8s/base/litellm/networkpolicy.yaml` y las políticas hermanas (FR-002): (a) entrada: a `litellm` solo desde `backend` e `ingest`; a `litellm-postgres` solo desde `litellm`; (b) salida: `NetworkPolicy` de salida en los pods `backend` e `ingest` que **no** permita el `Service` `ollama` ni la dirección del host `172.17.0.1:11434` (en DEV Ollama es externo, así que una política de entrada sobre un pod `ollama` no lo protege), conservando DNS, Postgres, `litellm`, Langfuse y los demás destinos que ya usan (revisar `k8s/base/backend/` e `ingest/` para no cortar nada); (c) donde Ollama corra en el clúster (base), entrada solo desde `litellm`; añadir `litellm` a `k8s/base/kustomization.yaml`; si T072 muestra que el CNI no aplica `NetworkPolicy`, documentar la limitación en `docs/gateway-de-modelos.md` de `shoplab-infra` y mantener las reglas
- [X] T078 [P] [US7] Crear `k8s/base/backend/externalsecret-litellm.yaml` (`ExternalSecret backend-litellm` que entrega `SPRING_AI_OPENAI_API_KEY` = `LITELLM_KEY_BACKEND`), añadirlo a `k8s/base/backend/kustomization.yaml` y referenciarlo en `k8s/base/backend/deployment.yaml` (`envFrom`/`secretKeyRef`); en el `ConfigMap backend-config` del overlay `dev` fijar `SPRING_AI_OPENAI_BASE_URL=http://litellm:4000/v1` y eliminar `SPRING_AI_OLLAMA_BASE_URL` (FR-033)
- [X] T079 [P] [US7] Editar `k8s/overlays/dev/kustomization.yaml`, `k8s/overlays/dev/patches/resources.yaml` y `k8s/overlays/dev/ollama-host.yaml` para incluir `litellm`/`litellm-postgres` con recursos mínimos (T072) y dejar el `ollama-host` consumido solo por `litellm`; confirmar con `ingest` que no recibe variables de Ollama ni del gateway (research R-14) y que su clave `ingest-dev` existe en el secreto; actualizar el comentario de cabecera de `k8s/overlays/dev/ollama-host.yaml`: el Ollama del host se levanta con `docker compose -f infra/docker-compose.yml -f infra/docker-compose.ollama-host.yml up -d ollama` (el puerto ya no se publica por defecto)
- [X] T080 [P] [US7] Añadir el secreto `litellm` a la lista de `terraform/modules/secrets/variables.tf` y a `terraform/envs/dev/secrets.auto.tfvars.example` con marcadores obvios (sin valores reales); comprobar que `scripts/seed.sh` o el equivalente que siembra secretos lo incluye
- [ ] T081 [US7] Validar localmente en `shoplab-infra` con los mismos controles de CI (FR-036): `scripts/kustomize-build.sh`, `scripts/policies.sh`/`scripts/policy-check.sh`, `scripts/check-pins.sh` (sin `latest`), `scripts/lint.sh` y `scripts/check-layout.sh`; corregir hasta verde y añadir una fixture en `rules-tests/` solo si se añade una regla nueva *(Parcial: `kubectl kustomize` del overlay, `check-pins.sh` y `terraform test` de secretos en verde; faltan `kubeconform` y `yq` en esta máquina para `policy-check.sh`.)*
- [X] T082 [P] [US7] Crear `docs/gateway-de-modelos.md` en `shoplab-infra`: acceso con `kubectl port-forward`, cómo ver logs y coste por conversación (`kubectl -n shoplab-dev logs deploy/litellm | jq -s …`), presupuestos y claves en el clúster, persistencia del estado según el almacenamiento (PVC `local-path`) y qué pasa tras `destroy`/`apply`, y cómo se sincroniza la configuración (T073)
- [ ] T083 [US7] Verificar en el clúster (quickstart §8, SC-008): `kubectl -n shoplab-dev get pods -l app.kubernetes.io/name=litellm` listos, `get job litellm-bootstrap-keys` completado, una pregunta al chat de DEV aparece en el log con `consumer: backend-dev`, modelo, tokens y coste; ejecutar `destroy` y `apply` y comprobar que Argo CD deja todo funcionando y el Job recrea las claves sin pasos manuales; `kubectl top` confirma que Langfuse, Ollama y el backend siguen cabiendo (US7 escenario 5); desde un pod de `backend` y de `ingest` falla la conexión a `ollama:11434` y a `172.17.0.1:11434`, y desde `litellm` funciona; `git grep` no encuentra credenciales reales *(Pendiente: requiere el clúster.)*
- [ ] T084 [US7] Abrir el PR de `shoplab-infra` con título `feat(litellm): [LAB-84] gateway de modelos en dev` y la descripción de la plantilla, vinculado al PR del repositorio de la aplicación; **no fusionar** antes de que el secreto `/shoplab/dev/litellm` exista en Secrets Manager (equivalente al secreto de Langfuse de LAB-64) *(Pendiente: abrir el PR de `shoplab-infra` (rama `feature/LAB-84-litellm-model-gateway`, apilada sobre LAB-64).)*

**Checkpoint**: DEV usa el gateway con los mismos controles que el local.

---

## Phase 10: Polish & Cross-Cutting Concerns

**Purpose**: decisión documentada, documentación de usuario, medición de rendimiento y verificación final.

- [X] T090 [P] Crear `docs/adr/0023-gateway-de-modelos-litellm.md` con la plantilla de `docs/adr/` (qué contiene el gateway, qué se registra y qué no, relación con las métricas de Prometheus [ADR-0009] y las trazas de Langfuse [ADR-0022], dos tablas de precios y por qué no se unifican aquí, privacidad del id de conversación, riesgo de fallback a mitad de stream desactivado, exceso tolerado de peticiones en vuelo sobre el presupuesto y tolerancia de latencia de SC-004) y añadir a `docs/adr/0003-*.md` la enmienda en «Consecuencias» (el modelo de embeddings se cambia en el gateway y exige reindexar); enlazar el ADR desde `docs/adr/README.md` *(Sin enmienda a ADR-0003: un ADR aceptado no se edita (docs/adr/README.md); ADR-0023 lo complementa.)*
- [X] T091 [P] Actualizar `README.md` (tabla de servicios y perfiles con `litellm`, `litellm-postgres`, `litellm-bootstrap`, `ollama` sin puerto publicado, variables nuevas y enlace a `docs/gateway-de-modelos.md`) y las menciones a Ollama en `docs/chat-streaming.md`, `docs/modulos.md` y `docs/observabilidad.md` (coste/tokens y modelo servido); revisar que `docs/gateway-de-modelos.md` cubre los ocho puntos de FR-038 (levantar, nombres lógicos, cambiar de modelo, fallback, claves y presupuestos, coste por conversación, proveedor cloud, gateway de DEV)
- [X] T092 [P] Actualizar la cabecera de `infra/docker-compose.yml` (perfil `core` y nota sobre `ollama`), revisar que no queda ninguna imagen `latest` añadida por la historia y que `docker compose -f infra/docker-compose.yml config -q`, `… --profile observability config -q` y `… --profile llmops config -q` pasan (SC-007)
- [ ] T093 Medir SC-004: tiempo hasta el primer token de la misma pregunta 20 veces con el gateway y 20 con Ollama directo (rama anterior o `curl` a `/api/chat` con el puerto de depuración de `docker-compose.ollama-host.yml`); la mediana con gateway debe ser < 10 % superior y las respuestas deben seguir en streaming; anotar el resultado en `specs/LAB-84-litellm-model-gateway/validation.md` *(Pendiente: requiere Ollama real (SC-004).)*
- [ ] T094 Ejecutar el quickstart completo (`specs/LAB-84-litellm-model-gateway/quickstart.md` §1-9) en un entorno limpio (`docker compose … down -v` y `up -d --wait`) y registrar el resultado de cada paso y de SC-001 a SC-009 en `specs/LAB-84-litellm-model-gateway/validation.md`; incluir la comprobación de que ningún log del gateway contiene texto de preguntas, respuestas, contexto, claves ni cabeceras de autorización (SC-009) *(Pendiente: requiere Ollama real y el backend en marcha.)*
- [X] T095 Ejecutar los controles previos al commit de `.ai/rules/development-guidelines.md`: `mvn test -pl backend -am -Dskip.srcclr=true --no-transfer-progress`, `mvn checkstyle:check -Dskip.srcclr=true --no-transfer-progress`, `mvn verify -Dskip.srcclr=true -Dcheckstyle.skip=true --no-transfer-progress` (cobertura ≥ 80 %), `cd infra/litellm && uv run pytest && uv run ruff check .`, `cd ingest && uv run pytest`, líneas ≤ 121, sin PMD ni Checkstyle, `git status --short` sin archivos sueltos y `git diff --staged` solo con cambios intencionales *(Backend `verify` (788 pruebas, Checkstyle, cobertura), pytest de `infra/litellm` (72) e `ingest` (160) en verde; `git status` revisado sin archivos sueltos.)*
- [ ] T096 Abrir el PR de `shoplab-helpdesk` con la skill `commit-and-push`; título `feat(chat): [LAB-84] gateway de modelos con LiteLLM`; descripción con el enlace al PR de `shoplab-infra` (T084), el resultado de `validation.md` y la nota de que no hay endpoints nuevos (`requests.http` no aplica: `POST /api/chat` y `/api/documents` no cambian); no editar `CHANGELOG.md` ni la versión a mano *(Pendiente: lo abre la persona responsable con `commit-and-push`.)*
- [ ] T097 Dejar constancia para el cierre de la historia: LAB-84 no se pasa a Hecho en Jira hasta fusionar ambos PRs, verificar el despliegue en DEV (SC-008) y que existe el secreto `/shoplab/dev/litellm`; anotar los pendientes manuales en la descripción del PR *(Pendiente: cierre en Jira tras fusionar ambos PRs y verificar DEV.)*

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T001 primero, T002-T006 en paralelo después (T006 también necesita T005).
- **Foundational (Phase 2)**: depende de T001-T006. Bloquea todas las historias.
- **US1 (Phase 3)**: depende de Phase 2. Es el MVP y **bloquea** a las demás historias del backend (migra el cliente y los stubs).
- **US2 (Phase 4)**: depende de US1 (comparte `GatewayErrorTranslator` y la guía; la parte de `infra/litellm/` solo de Phase 2).
- **US5 (Phase 5)**: depende de US1 (adaptador) y de Phase 2 (config del gateway).
- **US4 (Phase 6)**: depende de US1 y US5 (se verifica leyendo el log); `ResponseCodeEnum` e `infra/litellm/` se pueden adelantar.
- **US3 (Phase 7)**: depende de US1 y US5 (la demostración usa `fallback` del log); `ServedModel` y la config del respaldo son independientes de US4.
- **US7 (Phase 9)**: depende de US6 y del resto (US1, US2, US5, US4, US3): sincroniza con T073 los archivos de `infra/litellm/` ya estables hacia `shoplab-infra`.
- **US6 (Phase 8)**: depende de Phase 2 y de US4 (`bootstrap_keys.py`); no necesita US3 ni US7.
- **Polish (Phase 10)**: depende de todas las historias deseadas.

### Within Each User Story

- Las pruebas se escriben primero y deben fallar.
- Dominio y puertos antes que adaptadores; adaptadores antes que verificaciones manuales.
- Cada fase termina con una tarea de verificación (`mvn verify`, `pytest` y/o quickstart).
- Tras cada historia que cambie `infra/litellm/`, ejecutar `scripts/sync-litellm-files.sh` en `shoplab-infra` (T073) antes del PR de infraestructura.

### Parallel Opportunities

- Setup: T002-T006 en paralelo tras T001.
- Foundational: T008, T009, T014 en paralelo con T007/T010.
- US1: T017-T024 y T099 (pruebas) en paralelo; T027, T028 y T098 en paralelo; T033 se reparte por clase.
- US5: T043, T044, T045, T048 en paralelo; la parte Python (T043, T046, T047) es independiente de la parte Java (T044, T045, T048-T050).
- US4: T053, T054, T055 y T059 en paralelo; la parte Python y la Java no comparten archivos.
- US7: T078, T079, T080 y T082 en paralelo una vez fijados T072-T075.

### Parallel Example: User Story 1

```text
# Todas las pruebas de US1 a la vez:
Task: "Crear BT/chat/GatewayStubs.java (T017)"
Task: "Crear BT/chat/RawGatewayServer.java (T018)"
Task: "Crear BT/chat/infrastructure/out/gateway/GatewayChatAdapterTests.java (T019)"
Task: "Crear BT/chat/infrastructure/out/gateway/GatewayErrorTranslatorTests.java (T020)"
Task: "Actualizar SpringAiEmbeddingAdapterTests.java (T021)"
Task: "Crear NoModelServerAddressTests.java y ingest/tests/test_no_model_clients.py (T022)"
```

### Parallel Example: dos agentes tras Phase 2

```text
Agente A (Java):   US1 → US5 (backend) → US4 (backend) → US3 (backend)
Agente B (infra):  US2 (check_models) → US5 (callback) → US4 (bootstrap_keys) → US6 → US7
```

---

## Implementation Strategy

### MVP First (US1 + US2, ambas P1)

1. Completar Phase 1 (spikes) y Phase 2 (gateway local).
2. Completar Phase 3 (US1) y **validar**: todo el tráfico a modelos pasa por el gateway.
3. Completar Phase 4 (US2): cambio de modelo por configuración y errores de configuración visibles.
4. **STOP y validar** con el quickstart §1-3; es un incremento entregable.

### Incremental Delivery

1. MVP (US1 + US2) → demo.
2. US5 (coste por conversación) → demo; es la base para auditar lo siguiente.
3. US4 (presupuestos) → demo.
4. US3 (fallback) → demo.
5. US6 (proveedor cloud opcional) → demo sin claves y con servidor simulado.
6. US7 (clúster DEV) → verificación con Argo CD; sincroniza la configuración ya estable.
7. Polish: ADR-0023, README, SC-004, quickstart completo.

### Notas

- [P] = archivos distintos y sin dependencias pendientes; no marcar dos tareas que editen el mismo archivo (`infra/docker-compose.yml`, `infra/litellm/config.yaml`, `docs/gateway-de-modelos.md` se tocan en varias fases: ejecutarlas en orden).
- Commits de rama: `LAB-84 type(scope): description` en inglés, un commit por tarea o grupo lógico (Constitución, Development Workflow; prevalece sobre la guía §5, que no valida commits de rama porque el PR se aplasta); el título del PR usa `type(scope): [LAB-84] description` (guía §5.1).
- Los valores de los spikes (S-1…S-7) pueden cambiar nombres de campo, tags y cuerpos: cuando eso pase, corregir primero el contrato afectado y luego las tareas que lo usan.
- No se genera `requests.http`: la historia no añade ni cambia endpoints HTTP del backend.
