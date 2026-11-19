# Investigación: Trazas de LLM con Langfuse autoalojado (LAB-64)

**Fecha**: 2026-11-10 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

Cada decisión indica qué se verificó (fuente) y qué queda como verificación en la primera tarea (spike). No quedan
marcadores `NEEDS CLARIFICATION`.

## R-1. Cómo llegan las trazas a Langfuse

**Decisión**: OTLP sobre HTTP (protobuf) a `{langfuse}/api/public/otel/v1/traces`, con autenticación Basic
(`public_key:secret_key`) y la cabecera `x-langfuse-ingestion-version: 4`.

**Razón**: es la vía que Langfuse documenta como principal (Langfuse ≥ 3.22; la instancia objetivo es la 4.x). Los
atributos `langfuse.*` y `gen_ai.*` se mapean a trazas, sesiones, generaciones, uso, coste y nivel. Solo admite HTTP
(`gRPC` no está soportado), lo que simplifica el cliente. Sin la cabecera de versión los datos pueden tardar hasta 10
minutos en aparecer.

**Alternativas descartadas**:
- *API de ingesta REST propia* (`/api/public/ingestion`): obliga a reimplementar cola acotada, reintentos y vaciado al
  apagar, que el SDK de OTel ya trae; Langfuse la presenta como vía heredada.
- *SDK de Langfuse para Java*: no hay SDK oficial confirmado; el propio proyecto recomienda OTel para Java.
- *LiteLLM como pasarela con callback a Langfuse*: es LAB-84, otra historia.

**Fuentes**: langfuse.com/docs/opentelemetry/get-started; langfuse.com/integrations/frameworks/spring-ai.

## R-2. Pipeline de OTel dedicado, no el tracing global de Spring Boot

**Decisión**: un `SdkTracerProvider` propio (BatchSpanProcessor + exportador OTLP HTTP) creado solo cuando
`shoplab.chat.tracing.enabled=true`, usado únicamente por el adaptador de trazas del chat. No se añade
`spring-boot-starter-opentelemetry` ni `micrometer-tracing`.

**Razón**:
- La guía oficial de Langfuse + Spring AI activa el tracing global de Boot. Eso crea un span por cada petición HTTP,
  y su receta para evitarlo (`management.observations.enable."http.server.requests": false`) **apagaría también las
  métricas HTTP de LAB-102** (Prometheus/Grafana). Un pipeline dedicado no toca ninguna de las dos cosas.
- El apagado por defecto es exacto: sin la propiedad no existe ningún exportador ni conexión (FR-008). Con el starter
  de Boot habría que desactivar el exportador por propiedades cuyos nombres exactos la documentación no recoge.
- Las trazas distribuidas entre servicios son LAB-63. Este pipeline no las condiciona: LAB-63 podrá sustituirlo o
  reutilizar el exportador.

**Alternativas descartadas**: tracing global de Boot con `SpanExportingPredicate` para filtrar (dejaría el span del
turno huérfano de su padre HTTP y mezcla dos responsabilidades); `micrometer-tracing` con un `OtelTracer` manual
(clases internas del puente).

**Coste asumido**: ADR-0009 dejó fuera OpenTelemetry de la observabilidad base. Esta historia lo incorpora solo como
**formato de transporte hacia Langfuse**; queda registrado en un ADR nuevo (FR-032, `0022`) y en *Complexity Tracking*.

## R-3. Modelo explícito (`ChatTracePort`) y no observaciones de Spring AI

**Decisión**: el caso de uso `StreamChatUseCase` abre y cierra el turno a través de un puerto de salida
`ChatTracePort`; el adaptador del modelo (`OllamaChatAdapter`) informa de la generación y de las herramientas al
`TurnTrace` que recibe por el contexto de Reactor.

**Razón**:
- Las puntuaciones de similitud de los fragmentos entregados solo existen en el caso de uso (`ContextFragment.score`);
  el evento `sources` que sale al cliente no las lleva (FR-011).
- La documentación de Spring AI 2.0.2 **no dice** cuándo termina la observación de una llamada en streaming ni si captura
  el uso en el último fragmento. Con el modelo explícito el adaptador lee el uso del último `ChatResponse` y se prueba con
  WireMock (los stubs de Ollama ya existen).
- Mantiene la regla de la constitución: el acceso al LLM va detrás de puertos; el dominio no importa Spring AI ni OTel
  (reglas D1/D2 de ArchUnit).
- Cuando no hay trazas, el puerto lo implementa un `NoOpChatTrace`: el caso de uso no ramifica.

**Cómo llega el `TurnTrace` al adaptador**: `ChatTracePort.traceTurn` escribe el `TurnTrace` en el contexto de Reactor
(`contextWrite`) bajo una clave conocida; `OllamaChatAdapter` lo lee con `Flux.deferContextual`. `ChatModelPort` no
cambia de firma, de modo que sus pruebas y las de composición de herramientas siguen valiendo; sin contexto, el
adaptador usa `TurnTrace.NONE`.

**Herramientas**: Spring AI ejecuta las herramientas dentro de `ChatModel.stream`. `ToolCallbackComposer.compose()` ya
se llama por petición; en esa llamada cada `ToolCallback` se envuelve en un `TracedToolCallback` que informa nombre,
argumentos, resultado, duración y error al `TurnTrace` de la petición. No hay herramientas reales hoy
(supuesto de la spec); se verifica con la herramienta de prueba de `ToolCompositionIntegrationTests`.

**Alternativas descartadas**: `ObservationHandler` sobre las observaciones `gen_ai.*` de Spring AI (ver arriba; además
da una generación por vuelta del bucle de herramientas, útil pero no necesaria ahora); un `StreamChatServicePort`
decorador (no ve las puntuaciones).

## R-4. Identificadores de traza estables

**Decisión**: `traceId` = primeros 32 caracteres hexadecimales de `SHA-256(requestId)`; `sessionId` = el identificador de
la conversación cuando existe. El `requestId` también va como metadato de la traza.

**Razón**: FR-009/FR-020 piden que el id sea derivable del turno para que LAB-29 adjunte puntuaciones. El SDK de OTel no
deja fijar el `traceId` al crear un span raíz, pero sí aceptar un `IdGenerator`: se usa uno propio que devuelve el id
preparado en un `ThreadLocal` justo antes de `startSpan()` (síncrono, en el mismo hilo) y cae en el generador por defecto
si no hay ninguno. Los spans hijos heredan el `traceId` y no llaman al generador. SHA-256 no bloquea (la regla
`noSecureRandomIds` de ArchUnit prohíbe `SecureRandom`, no `MessageDigest`).

**Verificación pendiente (spike S-2)**: confirmar en un Langfuse real que un `traceId` derivado se acepta y que
`GET /api/public/traces/{traceId}` lo devuelve.

**Alternativa descartada**: padre remoto sintético con el `traceId` deseado; deja el span raíz con un padre inexistente.

## R-5. Contenido de la traza y mapeo a Langfuse

**Decisión**: árbol `chat-turn` → `retrieval` → `generation` → `tool:<nombre>`, con los atributos de
[contracts/trace-schema.md](contracts/trace-schema.md). Tipos Langfuse: `span` (turno), `retriever`, `generation`, `tool`.

**Reglas**:
- Contenido (pregunta, prompt, respuesta, fragmentos, argumentos y resultados de herramientas) con tope configurable por
  atributo (por defecto 20 000 caracteres); al superarlo se trunca y se añade `truncated=true` en los metadatos (FR-018).
- Nunca se envían claves, cabeceras de autorización ni configuración del cliente HTTP (FR-019). El adaptador solo
  serializa datos del dominio que construye él mismo.
- Los logs del backend siguen sin contenido; el único aviso nuevo es «no se pudo enviar la traza» sin payload (FR-022).
- `level=ERROR` y `status_message` para turnos fallidos; `CANCELLED` se marca `WARNING` con el texto parcial como salida.

## R-6. Uso de tokens y coste

**Decisión**: tokens de entrada/salida del último `ChatResponse` con metadatos de uso (Ollama los devuelve en el
fragmento final con `done: true`: `prompt_eval_count` y `eval_count`); si el modelo hace varias vueltas por herramientas,
se suman los de cada vuelta. Sin dato → atributo ausente y metadato `usage_status=unknown` (nunca 0).
Coste: `tokens × precio` con una tabla `shoplab.chat.tracing.pricing` (precio por millón de tokens de entrada y de salida)
buscada por nombre de modelo y, si no existe, por proveedor (`ollama` = 0). Sin entrada → no se envía `cost_details` y se
marca `cost_status=unpriced`; con uso desconocido → `cost_status=unknown_usage`.

**Razón**: el coste lo calcula el backend para no depender del catálogo de modelos de Langfuse, que no conoce
`llama3.2:3b` ni los futuros modelos de LAB-84 y mostraría coste vacío en vez de «0» o «sin precio». El valor se envía en
`langfuse.observation.cost_details`.

**Verificación pendiente (spike S-1)**: el fragmento final del stub de Ollama (`OllamaStubs.body`) hoy no trae
`prompt_eval_count`/`eval_count`; la primera tarea amplía el stub y comprueba que Spring AI 2.0.2 los expone como `Usage`
en streaming. Si no lo hiciera, el adaptador leería los metadatos nativos de Ollama.

## R-7. Resiliencia del envío (FR-021 a FR-023)

**Decisión**: `BatchSpanProcessor` del SDK con cola acotada (2048 spans), lotes de 256, retardo de 2 s, tiempo de
exportación de 5 s y vaciado al apagar con tope de 5 s. Todo configurable. La cola llena descarta spans (el SDK lo
registra); el hilo de exportación es del SDK, nunca el del chat.

**Razón**: cubre «asíncrono», «búfer acotado», «descartar antes que bloquear» y «vaciar al apagar» sin código propio.
BlockHound no se ve afectado: la creación de spans es CPU pura en el hilo del evento y la red ocurre en el hilo del SDK.
`DroppedSpansReporter` lee la métrica `processedSpans{dropped=true}` del propio `BatchSpanProcessor`
(`setMeterProvider` existe en el SDK 1.62.0) y la vuelca al contador de Micrometer `shoplab.chat.trace.dropped`
(sin etiquetas; se lee cada 5 s) para que los descartes no sean silenciosos.

**Fallo de Langfuse**: `ExportFailureReporter` envuelve al exportador, cuenta el lote fallido en
`shoplab.chat.trace.export.failures` y registra un aviso de **texto fijo** (a lo sumo uno por minuto); el exportador OTLP
solo informa éxito o fallo, no el código HTTP, así que el aviso no lo incluye. El chat no se entera.
Al volver Langfuse, los lotes nuevos salen sin reiniciar (el exportador no mantiene estado de conexión).

## R-8. Langfuse en docker compose (perfil `llmops`)

**Decisión**: servicios `langfuse-web`, `langfuse-worker`, `langfuse-postgres`, `langfuse-clickhouse`, `langfuse-redis`,
`langfuse-minio` (prefijo para no chocar con el `postgres` del perfil `core`). Se omite `langfuse-ai-gateway` (beta, sin uso
aquí). Basado en el `docker-compose.yml` oficial de Langfuse v4.

**Imágenes fijadas** (todas verificadas con `docker manifest inspect`):

| Servicio | Imagen |
|---|---|
| web | `docker.langfuse.com/langfuse/langfuse:4.77.0` |
| worker | `docker.langfuse.com/langfuse/langfuse-worker:4.77.0` |
| clickhouse | `docker.io/clickhouse/clickhouse-server:26.3.62.3` |
| redis | `docker.io/redis:7.4.6` |
| postgres | `docker.io/postgres:17.7` |
| minio | `cgr.dev/chainguard/minio@sha256:fb893e36fc369d41e92f678b17ec27378fa5bf0576aeef7ac15460c13629ab5a` (índice multi-arquitectura; el mismo en compose y en el clúster) |

MinIO ya no publica imágenes en Docker Hub/Quay y Chainguard solo ofrece `latest`: se fija por **digest** del índice
multi-arquitectura (`sha256:fb893e36…ab5a`), que cumple «nunca `latest`». La versión 4.77.0 es la penúltima publicada;
4.78.0 salió el mismo día de esta investigación.

**Puertos**: la interfaz sale por `${LANGFUSE_PORT:-3001}:3000` (**3001**, no 3000: Grafana del perfil `observability` ya
usa 3000). El resto no se publica salvo MinIO en `127.0.0.1:${LANGFUSE_MINIO_PORT:-9092}` (carga de medios desde el
navegador). `NEXTAUTH_URL=http://localhost:${LANGFUSE_PORT:-3001}`.

**Arranque sin pasos manuales (FR-004)**: variables `LANGFUSE_INIT_*` en `langfuse-web` crean organización, proyecto, usuario
y claves (`pk-lf-shoplab-local` / `sk-lf-shoplab-local`, marcadores locales). `LANGFUSE_INIT_USER_EMAIL=admin@shoplab.local`.

**Salud (FR-003)**: la compose oficial no define healthchecks en web ni worker; se añaden (`/api/public/health` en 3000;
`/api/health` en 3030 — spike S-3 para confirmarlo). `depends_on` con `condition: service_healthy`.

**Memoria**: la guía oficial recomienda 4 núcleos y 16 GiB para producción; en local ClickHouse es el mayor consumidor. Se
documenta el rango real medido en la verificación y que el perfil es opcional.

**Persistencia (FR-006)**: volúmenes con nombre `langfuse_postgres_data`, `langfuse_clickhouse_data`,
`langfuse_clickhouse_logs`, `langfuse_minio_data`, `langfuse_redis_data`.

**Secretos locales**: contraseñas, `SALT`, `ENCRYPTION_KEY` (64 hex), `NEXTAUTH_SECRET` con marcadores obvios y
sobrescribibles por `infra/.env`.

## R-9. Langfuse en el clúster DEV

**Decisión**: manifiestos propios en `shoplab-infra/k8s/base/langfuse/` (`kustomize`, mismo estilo que `postgres` y
`ollama`): un `Deployment` para web y otro para worker, y un `StatefulSet` por almacén (postgres, clickhouse, redis,
minio) con `volumeClaimTemplates` en `local-path`. El overlay `dev` fija recursos. `Application shoplab-dev` ya sincroniza
`k8s/overlays/dev`, así que no hay `Application` nueva.

**Razón**: la spec (FR-024) pide Langfuse desplegado por Argo CD, y la plataforma ya instala `local-path-provisioner`
(docs/gitops.md), por lo que los PVC se enlazan. Precedente de DEV: Postgres y Ollama corren en el Docker del host por peso;
Langfuse se despliega en el clúster porque así lo exige la historia, con requests mínimos (ver plan). **Riesgo R1**: el
nodo de Floci es de un solo nodo y comparte recursos; si la verificación del despliegue muestra que no cabe junto al resto,
la salida es el patrón `EndpointSlice` de `postgres-host.yaml` (Langfuse en el Docker del host) y una enmienda a la
spec; se decide en la tarea de verificación, no antes.

**Secretos**: un único secreto JSON `/shoplab/ENVIRONMENT/langfuse` en Secrets Manager (diez claves: contraseñas de los cuatro
almacenes, `SALT`, `ENCRYPTION_KEY`, `NEXTAUTH_SECRET`, claves y contraseña de inicialización). Un `ExternalSecret`
(`dataFrom.extract`) lo expone a los pods de Langfuse y otro, con `property`, entrega solo las dos claves al backend.
Se añade `langfuse` a la lista de `terraform/modules/secrets/variables.tf` y a `secrets.auto.tfvars.example`.

**Acceso**: sin ingress; `kubectl -n shoplab-dev port-forward svc/langfuse-web 3001:3000`, igual que Grafana
(docs/observability.md). Los almacenes son `ClusterIP` sin publicar (FR-028).

**Backend en DEV**: `backend-config` añade `SHOPLAB_CHAT_TRACING_ENABLED=true` y
`SHOPLAB_CHAT_TRACING_ENDPOINT=http://langfuse-web:3000/api/public/otel`; las claves vienen del `ExternalSecret`.

**CI de `shoplab-infra`**: ya valida `kustomize build`, `kubeconform` y las políticas (FR-029); se añaden fixtures si una
política nueva lo necesita (p. ej. recursos obligatorios, `securityContext`).

## R-10. Versión de Langfuse: v4

**Decisión**: línea 4.x (fijada a 4.77.0), no la 3.x aún mantenida (3.227.1). Es la que documenta la guía actual de
autoalojamiento y exige la cabecera `x-langfuse-ingestion-version: 4`. Las dos usan los mismos almacenes.

## R-11. Verificaciones (spikes) que abren la implementación

| Id | Qué se comprueba | Si falla |
|---|---|---|
| S-1 | Spring AI 2.0.2 expone el uso de tokens en streaming con Ollama (stub ampliado) | Leer los metadatos nativos de Ollama |
| S-2 | Langfuse acepta un `traceId` derivado y lo devuelve por API | Guardar `requestId` como metadato y buscar por él |
| S-3 | Rutas de salud de web y worker; `cost_details` y `completion_start_time` se aceptan por OTLP | Ajustar healthcheck o atributos |
| S-4 | El perfil `llmops` levanta en una máquina de 16 GB junto a `core` y `observability`; el stack cabe en el nodo de Floci | Ver riesgo R1 de R-9 |
| S-5 | `opentelemetry-sdk` y `opentelemetry-exporter-otlp` los gestiona el BOM de Spring Boot 4.1.2 (el repositorio local ya trae `opentelemetry-bom`) | Fijar versión en `pom.xml` |

Resolver S-1 a S-3 antes de escribir el adaptador completo; S-4 y S-5 al integrar.

## R-12. Resultados de las verificaciones (2026-11-10)

- **S-1 (uso en streaming)**: Spring AI 2.0.2 con Ollama expone el uso **solo en el último fragmento** (`done: true`):
  `promptTokens=11`, `completionTokens=7`. Los demás fragmentos y las respuestas en que Ollama no informa conteos traen
  `DefaultUsage` con **ceros** (no `EmptyUsage`). Regla adoptada: se suma el uso de todos los fragmentos y un total
  de cero tokens de entrada **y** salida se trata como **desconocido** (`usage_status=unknown`).
- **S-5 (BOM)**: el BOM de Spring Boot 4.1.2 gestiona OpenTelemetry **1.62.0** (`opentelemetry-sdk`,
  `opentelemetry-exporter-otlp`, `opentelemetry-sdk-testing`); no hace falta fijar versión. El exportador trae
  `opentelemetry-exporter-sender-okhttp` en `runtime`. `BatchSpanProcessor.setMeterProvider` existe en esa versión.
- **Cambio de diseño**: no se crea `GenerationTrace`; el puerto `GenerationScope` recibe los datos directamente
  (`complete(TokenUsage, String)`) y no hace falta un registro intermedio.
- **Puerto de MinIO (T017)**: el defecto `9090` choca con el de Prometheus (`PROMETHEUS_PORT`, perfil
  `observability`) y estaba ocupado en la máquina de desarrollo; el defecto pasa a **9092** (`LANGFUSE_MINIO_PORT`).
- **S-2 / S-3 (Langfuse 4.77.0 real, 2026-11-10)**: enviando OTLP/JSON a `POST /api/public/otel/v1/traces` con la
  cabecera `x-langfuse-ingestion-version: 4`: (a) el `traceId` derivado se conserva; (b) `usage_details`, `cost_details`
  (incluido el coste `0`), `model.name`, `completion_start_time` (→ `timeToFirstToken`), `session.id` y
  `trace.metadata.*` se respetan; (c) **`langfuse.trace.input/output` NO se mapean** a la observación raíz: hay que
  poner `langfuse.observation.input/output` también en la raíz; (d) la lectura es `GET /api/public/v2/observations
  ?traceId=…&fields=…`: `GET /api/public/traces[/{id}]` y `/observations` devuelven un error en modo `events_only`.
  La ingesta aparece en pocos segundos.
- **Healthchecks**: ver la sección de hallazgos de `validation.md` (escuchan en la dirección del contenedor).
