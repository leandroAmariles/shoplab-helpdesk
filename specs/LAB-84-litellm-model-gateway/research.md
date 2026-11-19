# Research: Gateway de modelos con LiteLLM

Fase 0 del plan. Cada decisión indica qué se comprobó (**Verificado**) y qué queda para un spike (**A confirmar**). Los
spikes están en R-12 y se ejecutan antes de las tareas de implementación.

## R-01 Cliente del backend: starter OpenAI de Spring AI

- **Decision**: sustituir `spring-ai-starter-model-ollama` por `spring-ai-starter-model-openai` y apuntar
  `spring.ai.openai.base-url` al gateway (incluyendo `/v1`) con la clave virtual del backend como `api-key`.
- **Rationale** (Verificado en `spring-ai-openai-2.0.2-sources.jar`): el módulo usa el SDK `openai-java` 4.80.0; el
  streaming usa `OpenAIClientAsync.createStreaming` convertido a `Flux` (no bloquea un hilo por stream, igual que el
  adaptador actual); `stream_options.include_usage` se envía a `true` por defecto; `OpenAiChatOptions` admite
  `customHeaders`, `user`, `extraBody` y `timeout`; `OpenAiEmbeddingOptions` admite `user`, `dimensions` y `extraBody`.
  `ChatModel.stream` y `EmbeddingModel.embed` mantienen la misma interfaz, así que `ChatModelPort` y `EmbeddingPort` no
  cambian de contrato.
- **Alternatives considered**:
  - Seguir con el starter de Ollama apuntando a la ruta compatible con Ollama del gateway: LiteLLM no aplica fallback,
    presupuesto ni coste en esa ruta de paso, y la historia pide API compatible con OpenAI.
  - Cliente HTTP propio (`WebClient`) contra `/v1/chat/completions`: reimplementa SSE, herramientas y uso de tokens que
    Spring AI ya resuelve.
  - Spring AI `ChatClient`: ya descartado en el adaptador actual (re-publica en `boundedElastic`, ADR-0011).

## R-02 Nombres lógicos de modelo

- **Decision**: el gateway expone `chat` y `embeddings` (más `chat-cloud` opcional, R-08). El backend solo conoce estos
  nombres (`spring.ai.openai.chat.options.model=chat`, `spring.ai.openai.embedding.options.model=embeddings`). Los modelos
  reales y sus parámetros viven en `infra/litellm/config.yaml`.
- **Rationale**: cambiar de modelo = editar un archivo del gateway y recargar (FR-006/007). El nombre guardado con cada
  vector (`EmbeddingPort.modelName()`) hoy es el real (`bge-m3`); se mantiene leyendo `shoplab.retrieval.embedding.model-name`
  (propiedad nueva con el mismo defecto) para que la reindexación por cambio de modelo (ADR-0017) siga funcionando.
- **Alternatives considered**: pedir el nombre real desde el backend (rompe FR-006); un alias por entorno (complica sin
  aportar).

## R-03 Fallback

- **Decision**: grupo `chat` con dos despliegues (principal y respaldo, ambos `ollama_chat/…`), `num_retries` bajo,
  `request_timeout` y `stream_timeout` acotados, `fallbacks: [{chat: [chat-fallback]}]`, y
  `enable_mid_stream_fallback_continuation: false` explícito. `embeddings` sin fallback.
- **Rationale** (Verificado en la documentación de fiabilidad y ajustes de LiteLLM): el fallback entre grupos y los
  reintentos son configurables; la continuación de un stream roto a mitad de respuesta es una opción separada, desactivada
  por defecto (`enable_mid_stream_fallback_continuation`), y la dejamos desactivada para cumplir el caso límite de la spec
  (un fallo a mitad de stream se trata como hoy, sin reiniciar con otro modelo). El fallo antes del primer fragmento sí
  dispara el fallback.
- **A confirmar (S-3)**: con Ollama, «modelo no disponible» se produce de formas distintas (modelo no descargado → 404,
  contenedor parado → conexión rechazada, modelo cargándose → tiempo agotado); el procedimiento de demostración debe
  provocar al menos una de ellas de forma reproducible y las tres deben acabar en el respaldo.
- **Demostración (FR-017)**: parar el modelo principal sin tocar código — p. ej. `LITELLM_CHAT_PRIMARY_MODEL` apuntando a un
  modelo no descargado, o un *alias* de pruebas `chat-primary-down` — documentado en `docs/gateway-de-modelos.md`; el
  procedimiento exacto se fija en S-3.
- **Alternatives considered**: fallback en el backend (duplica lógica y deja de ser «sin tocar código»); un único modelo con
  reintentos (no demuestra fallback entre modelos).

## R-04 Claves virtuales y presupuestos

- **Decision**: LiteLLM solo guarda claves en su base de datos (no hay sección declarativa de claves). Un script
  idempotente `bootstrap_keys.py` lee `infra/litellm/keys.yaml` (alias `<consumidor>-<entorno>`, presupuesto
  `max_budget`, `budget_duration`, lista de `models` permitidos y el valor de la clave desde una variable de entorno) y
  hace *upsert* mediante la API de administración con la clave maestra: crea si no existe, actualiza si cambió.
- **Rationale**: reproducible tras `destroy`/`apply` (FR-018, FR-023), sin alta manual, y los valores secretos entran por
  entorno/secreto, no por el archivo. Se ejecuta como servicio de un solo disparo en compose (`depends_on` del gateway sano)
  y como Job de Argo CD (hook `PostSync`) en el clúster.
- **Rationale de claves**: una clave por consumidor y entorno (`backend-local`, `ingest-local`, `backend-dev`, `ingest-dev`)
  porque cada entorno tiene su propio gateway y su propia base; el «entorno» es el alias y el presupuesto que carga cada
  instancia (no hay claves compartidas entre entornos).
- **A confirmar (S-5)**: que `/key/generate` acepte un valor de clave predefinido (`key`) y `key_alias` con el patrón
  `^[a-z0-9]+(-[a-z0-9]+)*$` (el documento lo exige si el patrón está activo); que `/key/update` sea idempotente; la unidad
  de `max_budget` (USD) y que `budget_duration` (`1d`) reinicie solo.
- **Alternatives considered**: crear claves a mano o por la interfaz (no reproducible); `litellm --config` con
  `general_settings.virtual_keys` (no existe como sección declarativa); un Helm chart (el proyecto usa Kustomize).

## R-05 Precios, incluso de modelos locales

- **Decision**: `model_info.input_cost_per_token` y `output_cost_per_token` en cada modelo de `config.yaml`, con precios de
  referencia para los locales (por defecto equivalentes a un modelo cloud comparable de tamaño parecido, editables por
  variables de compose). Un modelo sin precio queda sin coste y se registra como «sin precio» (el *callback* distingue
  `response_cost == null` de `0`).
- **Rationale**: cumple «precios configurados para medir el coste aunque el modelo sea local» (FR-022) y permite ejercitar
  el presupuesto en local. El presupuesto se mide con esos precios.
- **Relación con LAB-64**: el backend conserva su propia tabla (`shoplab.chat.tracing.pricing`, Ollama = 0). Las trazas
  seguirán diciendo coste 0 mientras el gateway cuenta coste de referencia. Se documenta; unificar sería leer el coste del
  gateway en la traza (cabecera/`usage`), mejora posible fuera de alcance (Complexity Tracking de ADR-0023).

## R-06 Línea de log por petición con coste y conversación

- **Decision**: *callback* propio `shoplab_callbacks.py` (clase `CustomLogger`) registrado en `litellm_settings.callbacks`;
  en `async_log_success_event` / `async_log_failure_event` escribe **una línea JSON** a stdout con el esquema de
  [contracts/log-schema.md](contracts/log-schema.md): consumidor (alias de la clave), conversación (`user`), id de petición
  del chat (cabecera `x-shoplab-request-id`), modelo pedido, modelo servido, fallback, tokens (null si desconocido), coste
  (null si sin precio), latencia, resultado. Nunca lee `messages` ni `response`.
- **Rationale**: el log de acceso por defecto no trae tokens ni coste; `turn_off_message_logging: true` evita que el
  contenido llegue a otros *callbacks*; filtrar por conversación con `jq` basta para FR-027 (sin base de datos extra, sin
  Langfuse). `json_logs: true` y `request_correlation_in_logs: true` aportan formato y correlación al resto de líneas.
- **A confirmar (S-4)**: nombres exactos en `standard_logging_payload` (`response_cost`, `prompt_tokens`,
  `completion_tokens`, `end_user`, `model_group`, `model`, `api_base`, `metadata.headers`) y cómo se obtiene el «modelo
  servido» y el número de intentos de fallback en la versión fijada.
- **Alternatives considered**: tabla `LiteLLM_SpendLogs` (guarda solo si `store_prompts_in_spend_logs`; se mantiene apagado) y
  consultarla por SQL: viable pero exige acceso a la base; callback `generic_api`/OTel hacia Langfuse: ya hay trazas de
  LAB-64 y el criterio pide logs del gateway; se deja como mejora.
- **Embeddings**: llevan `user` = `purpose:document-index` o `purpose:search-query`, de modo que no se atribuyen a una
  conversación inexistente (spec US5, escenario 4).

## R-07 Correlación desde el backend

- **Decision**: por petición se envían `user` = id de la conversación (UUID, ya validado por `INVALID_CONVERSATION_ID`) y la
  cabecera `x-shoplab-request-id` = `X-Request-Id` del chat (el mismo de los logs y de las trazas de LAB-64). Para
  embeddings, `user` = `purpose:<EmbeddingPurpose>` y el mismo id de petición cuando existe.
- **Rationale**: `user` es un campo estándar de OpenAI que LiteLLM trata como usuario final; la cabecera es el medio
  neutro para el id de petición. Spring AI los admite por petición (`OpenAiChatOptions.user`, `customHeaders`). Llegan al
  adaptador por el contexto de Reactor, igual que `TurnTrace` (`TracingConstants.TURN_TRACE_KEY`), sin cambiar la firma de
  `ChatModelPort`.
- **Privacidad**: id de conversación = UUID aleatorio, no contenido ni persona; se documenta en el ADR.
- **Cambio de puerto necesario**: `EmbeddingPort.embed(texts)` → `embed(texts, purpose)` (2 llamadores: `DocumentEmbedder`,
  `SearchChunksUseCase`).

## R-08 Proveedor cloud opcional

- **Decision**: un despliegue `chat-cloud` en `config.yaml` con `api_key: os.environ/CLOUD_PROVIDER_API_KEY`. El arranque del
  gateway usa un `config.yaml` **generado** (envsubst/plantilla) donde la sección del proveedor cloud solo se incluye si
  `CLOUD_PROVIDER_API_KEY` no está vacío; sin clave el nombre `chat-cloud` no existe (400 «modelo desconocido», sin salir a
  Internet). Nunca entra en `fallbacks` ni en el nombre `chat` salvo configuración explícita (FR-012).
- **A confirmar (S-1)**: si LiteLLM omite por sí mismo un despliegue cuya clave de entorno está vacía o falla al arrancar;
  si falla, la plantilla es el mecanismo.
- **Pruebas**: servidor simulado compatible (WireMock en el backend; un servidor Python mínimo en las pruebas del script),
  nunca una cuenta real (FR-037, SC-007).

## R-09 Errores del gateway y su traducción

- **Decision**: `GatewayErrorTranslator` en el adaptador mapea las excepciones del SDK (`com.openai.errors.*`):

  | Respuesta del gateway | Causa | Excepción del backend | Código |
  |---|---|---|---|
  | 400 con `type: budget_exceeded` | presupuesto de la clave agotado | `ChatBudgetExceededException` / `EmbeddingBudgetExceeded…` | `CHAT_BUDGET_EXCEEDED` / `EMBEDDING_BUDGET_EXCEEDED` (429) |
  | 401 | clave inexistente/revocada/ausente | `ChatModelException` (registra la causa) | `CHAT_MODEL_UNAVAILABLE` (503) |
  | 400/404 «model not found» | nombre lógico sin configurar | `ChatModelException` | `CHAT_MODEL_UNAVAILABLE` (503) |
  | 5xx, conexión rechazada, tiempo agotado (fallback agotado) | modelo no disponible | `ChatModelException` | `CHAT_MODEL_UNAVAILABLE` (503) |

- **Rationale**: el usuario nunca ve detalles internos (FR-004); el presupuesto es distinguible; una clave inválida es un
  problema de operación (aviso en log), no del usuario. El caso de uso ya diferencia error antes/después del primer
  fragmento y no cambia.
- **A confirmar (S-6)**: el cuerpo exacto del error de presupuesto (`type`, `code`, estado HTTP) en la versión fijada; la
  traducción se ajusta a lo observado y las pruebas usan el cuerpo real capturado.

## R-10 Despliegue en compose

- **Decision**: en `core` (sin `profiles:`), tres servicios nuevos con las convenciones del archivo:
  - `litellm-postgres` (`postgres:17.7`, volumen `litellm_postgres_data`, sin puerto publicado, salud con `pg_isready`);
  - `litellm` (imagen fija, `config.yaml` y `shoplab_callbacks.py` de solo lectura, `/health/liveliness` como salud,
    puerto `${LITELLM_PORT:-4000}`, `depends_on` de `litellm-postgres` y `ollama` en `service_healthy`);
  - `litellm-bootstrap` (misma imagen, `restart: "no"`, ejecuta `bootstrap_keys.py`, `depends_on: litellm: service_healthy`).
  `ollama` deja de publicar su puerto por defecto y pasa a una red `models` que comparte solo con `litellm` (así `ingest`
  no lo alcanza por la red de compose). `infra/docker-compose.ollama-host.yml` (opcional) lo vuelve a publicar: lo usa el
  clúster de DEV, que llega al Ollama del host por `172.17.0.1:11434`, y la medición de SC-004. El backend, que corre en
  el host, solo conoce `http://localhost:4000/v1`.
- **Rationale**: FR-002/029/031. El orden `healthy` evita el problema de Langfuse (R-10 de LAB-64) de arrancar antes que los
  almacenes.
- **Riesgo**: `docker compose up --wait` espera servicios sin comprobación de salud; `litellm-bootstrap` termina y devuelve 0
  — se comprueba que `--wait` lo tolera (S-1).
- **Alternativas**: perfil propio `gateway` (descartado: el chat no funciona sin él; ver spec, Assumptions); sidecar de
  `bootstrap` dentro del contenedor del gateway (mezcla ciclo de vida del gateway con el de las claves).

## R-11 Despliegue en el clúster (DEV)

- **Decision**: `k8s/base/litellm/` con `Deployment` (1 réplica, `readinessProbe` en `/health/readiness`), `Service`
  `litellm:4000`, `ConfigMap` (`config.yaml`, `shoplab_callbacks.py`, `keys.yaml`, `bootstrap_keys.py` generados con
  `configMapGenerator` desde los mismos archivos que usa compose, para que haya una sola fuente), `StatefulSet`
  `litellm-postgres` con PVC `local-path`, `Job` de claves como hook `PostSync` de Argo CD, y `ExternalSecret`
  `litellm-credentials` (JSON en `/shoplab/<ambiente>/litellm`: clave maestra, claves virtuales de backend e ingest, clave
  del proveedor cloud opcional, contraseña de Postgres). `ollama` en DEV sigue siendo el del Docker del host
  (`ollama-host.yaml`); el Service `litellm` es quien lo consume. Como en DEV Ollama es externo al clúster, una `NetworkPolicy` de entrada
  sobre un pod `ollama` no lo protege: se añade una de **salida** en `backend` e `ingest` hacia el `Service` `ollama` y
  `172.17.0.1:11434`, y entrada restringida a `litellm` (y a `litellm-postgres`).
- **Rationale**: mismo patrón que `k8s/base/langfuse/` (LAB-64) y `postgres/` (LAB-22); un solo origen de la configuración
  entre compose y clúster evita deriva.
- **Backend de DEV**: `SPRING_AI_OPENAI_BASE_URL=http://litellm:4000/v1`; `SPRING_AI_OPENAI_API_KEY` desde un `ExternalSecret`
  (clave `backend-dev`); `SPRING_AI_OLLAMA_BASE_URL` se elimina del `ConfigMap`.
- **A confirmar (S-7)**: memoria real de LiteLLM + su Postgres en el nodo junto a Langfuse; si no cabe, patrón de
  `EndpointSlice` a un Postgres del host.

## R-12 Spikes previos a las tareas

| Id | Qué se comprueba | Resultado esperado | Si falla |
|---|---|---|---|
| S-1 | Tag exacto de la imagen de LiteLLM y su registro; arranque con la clave maestra local; cuánto pesa; `--wait` con un servicio de un disparo; despliegue cloud omitido sin clave | Una imagen con versión fija arranca en < 60 s con < 1 GiB; plantilla de proveedor cloud opcional decidida | Usar imagen `-database` o ajustar la clave maestra; plantilla obligatoria |
| S-2 | Forma real de la respuesta SSE de `chat` vía LiteLLM → Ollama (chunk final con `usage`, campo `model`, llamadas a herramientas) | Reproducible en `GatewayStubs` | Ajustar `usageOf` del adaptador |
| S-3 | Fallback antes del primer fragmento con las tres formas de «modelo caído»; ausencia de continuación a mitad de stream | Respuesta completa con el respaldo; log con `fallback=true` | Reintentos/timeouts; revisar `fallbacks` por grupo |
| S-4 | `num_ctx` y `keep_alive` llegan a Ollama; campos del `standard_logging_payload`; modelo servido y número de intentos; el callback se invoca también en rechazos previos a la llamada (presupuesto, 401, modelo desconocido) | Prompt de 7 000 tokens no se trunca; callback con los campos del esquema | Parámetros en `litellm_params`/`extra_body` |
| S-5 | `/key/generate` con clave predefinida, alias, presupuesto y reinicio; `/key/update` idempotente; significado de `models: []` | `bootstrap_keys.py` converge en dos ejecuciones | Borrar y recrear la clave por alias |
| S-6 | Cuerpo/estado del error de presupuesto agotado, 401 y modelo inexistente | Tabla de R-09 confirmada | Ajustar `GatewayErrorTranslator` |
| S-7 | Recursos de LiteLLM y su Postgres en el nodo de DEV; el CNI aplica `NetworkPolicy` de entrada y de salida | `requests` mínimos caben con Langfuse | Postgres en el host |

## R-13 Cambios de pruebas y arquitectura

- **Decision**: `OllamaStubs` → `GatewayStubs` (mismos nombres de métodos públicos para minimizar el cambio en ~20 clases de
  prueba; por dentro sirve SSE OpenAI-compatible y `/v1/embeddings`). `OllamaChatAdapterTests` y
  `OllamaChatAdapterTraceTests` se renombran y se adaptan; `IngestHasNoDatabaseCredentialsTests` y los demás que citan
  Ollama solo en texto no cambian. `ModuleArchitectureRules` añade `com.openai..` a los paquetes prohibidos en dominio y
  aplicación.
- **Rationale**: Constitución IV (pruebas deterministas, sin modelo real); mantener la nomenclatura evita una reescritura
  grande de pruebas.
- **Notebooks** (`notebooks/`, LAB-26) usan Ollama directamente: son experimentos y «nunca los importa código de
  producción» (constitución); quedan fuera de alcance y se anota en la guía.

## R-14 `ingest`

- **Decision**: `ingest` no llama hoy a ningún modelo, así que no se le añade cliente ni variables. En esta historia solo
  recibe (a) su clave virtual `ingest-<entorno>` con presupuesto, creada por el gateway, y (b) la regla de red: en compose
  `ollama` no publica puerto para la aplicación y en el clúster una `NetworkPolicy` solo deja entrar al pod de `litellm`.
  Una prueba (pytest) de `ingest` comprueba que no importa clientes de modelos ni contiene la dirección de Ollama; cuando
  exista la primera llamada a un modelo, usará la dirección del gateway y esa clave.
- **Rationale**: cubre el criterio («solo hablan con él») sin construir código que nadie ejecuta (Constitución VII).
- **Alternativa descartada**: publicar `LITELLM_BASE_URL`/`LITELLM_API_KEY` en `ingest` sin usarlas: variables muertas que
  inducen a error y secretos montados sin necesidad.

## Fuentes consultadas

- Código fuente de `spring-ai-openai` 2.0.2 (Maven Central, `-sources.jar`).
- Documentación de LiteLLM proxy (fiabilidad y fallbacks, claves virtuales, ajustes de configuración, registro) y
  releases de GitHub (v1.109.x en la fecha de esta especificación).
- Código de LAB-64 (`OllamaChatAdapter`, `TurnTrace`, ADR-0022), `infra/docker-compose.yml`, `shoplab-infra/k8s/**`.

## Resultados de los spikes (verificado contra LiteLLM v1.109.1 con un Ollama simulado)

Imagen fijada: `ghcr.io/berriai/litellm:v1.109.1` (existe en ghcr.io; la línea v1.109.x es la estable más reciente). Arranca en
unos 10 s con Postgres 17.7, `python` 3.13 y `pyyaml` disponibles. Se verificó con un servidor que imita las rutas de Ollama
(`/api/tags`, `/api/chat`, `/api/embed`); **no** con modelos reales (no se descargaron), así que `num_ctx`/`keep_alive`,
la memoria real y el comportamiento de un modelo cargándose quedan por comprobar con Ollama de verdad (quickstart §1-4).

| Spike | Resultado |
|---|---|
| S-1 | Imagen, arranque y clave maestra `sk-shoplab-master-local-only` aceptada. `litellm-bootstrap` como servicio de un solo disparo y el bloque cloud condicional (la plantilla lo quita sin clave) funcionan; `up --wait` con ese servicio queda por comprobar con el compose real. |
| S-2 | El chunk de streaming trae `model` = **nombre del grupo** que respondió (`chat` o `chat-fallback`), no el modelo real; el último chunk trae `usage` con `cost`. `ServedModel` usa ese nombre y `shoplab.chat.gateway.primary-model` vale `chat`. |
| S-3 | Con el principal caído (404 de Ollama) la petición cae a `chat-fallback` antes del primer fragmento; el log lleva `fallback:true` y `served_model_id: chat-fallback`. Tras `cooldown_time` vuelve al principal. **La clave virtual debe poder llamar a `chat-fallback`** o LiteLLM omite el fallback («caller is not authorized»). |
| S-4 | `standard_logging_object` trae lo que lee el callback (alias de la clave, `end_user`, `model_group`, `model_id`, coste, tokens, `completionStartTime`) y la cabecera `x-shoplab-request-id` está en `litellm_params.metadata.headers`. Los **eventos de fallo del router se disparan en cada intento**, también en los que un fallback rescata, y reutilizan `litellm_call_id`: los fallos se registran desde `async_post_call_failure_hook` (una vez por petición). Los rechazos de clave, modelo y presupuesto llegan a ese hook. |
| S-5 | `/key/info?key=` (404/400 si no existe), `/key/generate` con clave predefinida y `/key/update` son idempotentes: `created` → `unchanged` → `updated` al cambiar presupuesto o modelos. `models: []` significa «todos»: `ingest` recibe el nombre `shoplab-no-model-access`. |
| S-6 | Presupuesto agotado: **HTTP 422**, `type: budget_exceeded`. Clave inexistente: 401 `token_not_found_in_db`. Modelo que la clave no puede llamar o que no existe: 403 `key_model_access_denied`. Principal y respaldo caídos: error 5xx/404 del router. |
| Embeddings | Ollama rechaza el parámetro `user` (`UnsupportedParamsError`): se activa `litellm_settings.drop_params: true`; el callback sigue viendo el `user` y registra `operation: search-query`. |
| Privacidad | El log completo del gateway no contiene el texto de las preguntas, ni valores de claves, ni cabeceras de autorización (SC-009). |
| S-7 | Pendiente: requiere el clúster (memoria real junto a Langfuse y aplicación de `NetworkPolicy`). |
