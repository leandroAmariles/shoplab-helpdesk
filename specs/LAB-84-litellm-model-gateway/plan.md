# Implementation Plan: Gateway de modelos con LiteLLM

**Branch**: `feature/LAB-84-litellm-model-gateway` | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-84-litellm-model-gateway/spec.md`

## Summary

Todas las llamadas a modelos del backend (chat y embeddings) pasan por un gateway LiteLLM que expone nombres lógicos
(`chat`, `embeddings`), enruta por defecto a Ollama, hace fallback entre dos modelos locales, aplica presupuesto por clave
virtual y registra tokens y coste por conversación en sus logs. Corre en el perfil `core` de docker compose y en el
ambiente DEV del clúster.

Enfoque técnico (detalle y alternativas en [research.md](research.md)):

- **Cliente del backend**: se sustituye `spring-ai-starter-model-ollama` por `spring-ai-starter-model-openai` (Spring AI
  2.0.2, cliente OpenAI asíncrono y streaming con `include_usage` por defecto). Los puertos `ChatModelPort` y
  `EmbeddingPort` no cambian de contrato; cambian los adaptadores (`OllamaChatAdapter` → `GatewayChatAdapter`,
  `SpringAiEmbeddingAdapter` sigue igual salvo la fuente del nombre del modelo).
- **Nombres lógicos**: el backend pide solo `chat` y `embeddings`; el gateway los asocia a modelos reales en
  `infra/litellm/config.yaml` (versionado). Las opciones propias de Ollama (`num_ctx`, `keep_alive`) pasan a
  `litellm_params`.
- **Fallback**: `chat` = principal + respaldo local, reintentos y tiempos acotados; antes del primer fragmento (el
  fallback a mitad de stream queda desactivado). `embeddings` sin fallback (FR-009).
- **Claves y presupuestos**: un script idempotente (`bootstrap-keys`) crea/actualiza las claves virtuales desde
  `infra/litellm/keys.yaml` (consumidor × entorno, presupuesto y periodo) tras arrancar el gateway; en compose es un
  servicio de un solo disparo y en el clúster un Job de Argo CD.
- **Precios**: `model_info.input_cost_per_token` / `output_cost_per_token` por modelo, también los locales (precios de
  referencia). Las trazas de LAB-64 conservan su tabla (ADR-0022).
- **Registro por conversación**: el backend envía `user` = id de conversación y la cabecera `x-shoplab-request-id`; un
  *callback* propio del gateway escribe una línea JSON por petición (consumidor, conversación, modelos, tokens, coste,
  latencia, fallback, resultado) sin contenido (`turn_off_message_logging`).
- **Errores**: nuevos códigos tipados para presupuesto agotado (distinto de modelo no disponible) y modelo/clave inválidos
  traducidos en el adaptador; sin detalles internos al usuario.
- **Trazas (LAB-64)**: el adaptador informa del modelo servido (metadatos de la respuesta) y de si hubo fallback; la traza
  deja de mostrar el alias lógico como si fuera el modelo.
- **Compose**: `litellm` + `litellm-postgres` (estado propio) + `litellm-bootstrap` en `core`; `ollama` deja de publicar
  una ruta a la aplicación (solo el gateway la usa). **Clúster**: `k8s/base/litellm/` en `shoplab-infra`, secretos por
  `ExternalSecret`, sin Ingress.

## Technical Context

**Language/Version**: Java 21 (backend); Python 3.12 stdlib (script de claves y *callback* de logs, dentro de la imagen del
gateway); YAML (Compose, Kustomize, configuración de LiteLLM); HCL mínimo (lista de secretos de Terraform).

**Primary Dependencies**: Spring Boot 4.1.2, Spring AI 2.0.2 (BOM ya presente). Nuevas: `spring-ai-starter-model-openai`
(trae `openai-java` 4.80.0 + okhttp 4.12.0; sustituye al starter de Ollama). Infraestructura: LiteLLM proxy (línea
v1.109.x, tag exacto fijado en el spike S-1), PostgreSQL 17.7 para el estado del gateway (misma imagen que
`langfuse-postgres`).

**Storage**: la base de datos de la aplicación no cambia. El gateway usa su propio PostgreSQL (`litellm-postgres`) en
volumen con nombre (compose) o PVC `local-path` (clúster) para claves virtuales y gasto.

**Testing**: JUnit 5 + AssertJ; WireMock sustituye a los stubs de Ollama con un servidor **OpenAI-compatible** (SSE de
`/v1/chat/completions` y `/v1/embeddings`, errores del gateway: 400 `budget_exceeded`, 401, 404 modelo, 503); `reactor-test`;
BlockHound; ArchUnit y Modulith; JaCoCo ≥ 80 %. Pruebas de Python (pytest, ya en `ingest`) para el script de claves y el
*callback* de logs contra un servidor falso. CI: `docker compose --profile … config -q` ampliado a `core`; en
`shoplab-infra`, `kustomize build`, `kubeconform` y sus políticas.

**Target Platform**: contenedores Linux en el Docker del desarrollador; k3s de un solo nodo (Floci) para DEV, con Ollama
y Postgres de la aplicación en el Docker del host (EndpointSlice) como hoy.

**Project Type**: monolito modular web reactivo (`backend/`) + infraestructura en dos repositorios
(`shoplab-helpdesk/infra`, `shoplab-infra`).

**Performance Goals**: sobrecoste del tiempo hasta el primer token < 10 % con el gateway (SC-004); streaming sin búfer
completo; latencia del arranque del gateway < 60 s.

**Constraints**: ninguna llamada bloqueante en el hilo del evento (BlockHound); líneas ≤ 121; sin `latest`; sin secretos
en git; el gateway no registra contenido; ninguna ruta de la aplicación hacia Ollama que no sea el gateway.

**Scale/Scope**: uso de desarrollo (decenas de turnos al día); un solo gateway sin réplicas (FR-040); dos entornos
(local, `dev`) y dos consumidores (`backend`, `ingest`).

## Constitution Check

*GATE: debe pasar antes de la investigación (Phase 0). Se vuelve a evaluar tras el diseño (Phase 1).*

| Principio | Evaluación | Resultado |
|---|---|---|
| I. Monolito modular primero | Cambios en `chat` y `retrieval` solo en la capa `infrastructure` y `config`; el gateway es infraestructura, no un servicio de aplicación ni un módulo nuevo; no hay tablas compartidas (su Postgres es propio). | ✅ |
| II. Hexagonal y dominio puro | El acceso a LLM y embeddings sigue detrás de `ChatModelPort` / `EmbeddingPort`; `org.springframework.ai..`, `com.openai..` y `io.opentelemetry..` no entran en dominio ni aplicación (se añade `com.openai..` a las reglas D1). El adaptador traduce los errores del gateway a excepciones tipadas. | ✅ |
| III. Identificadores en inglés, errores tipados | Códigos nuevos en `ResponseCodeEnum` (`CHAT_BUDGET_EXCEEDED`, `EMBEDDING_BUDGET_EXCEEDED`); sin `ResponseEntity` fuera de `GlobalExceptionHandler`. | ✅ |
| IV. Test-first, cobertura ≥ 80 % | WireMock OpenAI-compatible sustituye al de Ollama; ningún modelo ni gateway real en CI (FR-037). El gateway real se verifica solo en el quickstart y en el clúster. | ✅ |
| V. RAG guiado por evaluación | No cambia prompt, troceado, embeddings ni recuperación: mismo modelo, mismo `num_ctx`, misma dimensión (1024). Riesgo: el cambio de cliente puede alterar ligeramente el prompt enviado; se compara con la suite de chat existente. La evaluación en PR aún no existe (LAB-30). | ✅ con vigilancia |
| VI. Observabilidad y manejo seguro de IA | Mejora la visibilidad de coste/tokens; logs del gateway sin contenido; claves por entorno desde secretos; claves locales marcadores. | ✅ |
| VII. Simplicidad y decisiones documentadas | Tres puntos justificados en *Complexity Tracking*; ADR-0023. | ✅ justificado |

**Restricciones tecnológicas**: `docker compose -f infra/docker-compose.yml up` sigue siendo un único comando; arranca algo
más (gateway + su Postgres) pero ya no hay servicios opcionales que activar. PostgreSQL de la aplicación sigue siendo
único (ADR-0002); el del gateway es de infraestructura y no almacena datos del dominio.

**Resultado de la puerta**: aprobada. **Tras el diseño de Phase 1**: sin cambios; los contratos mantienen los puertos
intactos y el gateway fuera del dominio.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-84-litellm-model-gateway/
├── plan.md              # Este archivo
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── gateway-api.md       # Backend ↔ gateway: modelos, cabeceras, errores y su traducción
│   ├── gateway-config.md    # config.yaml, keys.yaml, precios, fallback y callback de logs
│   ├── log-schema.md        # Línea JSON por petición y consultas de coste por conversación
│   └── configuration.md     # Propiedades del backend, variables de compose y secretos del clúster
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks; no lo crea este comando)
```

`requests.http` no se genera: la historia no añade ni cambia ningún endpoint HTTP del backend (`POST /api/chat` y
`/api/documents` no cambian); solo cambia hacia dónde llama el backend.

### Source Code (repository root)

```text
shoplab-helpdesk/
├── infra/
│   ├── docker-compose.yml           # + litellm, litellm-postgres, litellm-bootstrap (core); red `models` solo con ollama y litellm; ollama sin puerto publicado
│   ├── docker-compose.ollama-host.yml  # override opcional: publica el puerto de Ollama (host de DEV y medición)  (nuevo)
│   ├── .env.example                 # + LITELLM_* (imágenes, puerto, clave maestra, claves virtuales, precios opcionales)
│   └── litellm/
│       ├── config.yaml              # nombres lógicos, modelos, fallback, precios, ajustes de logs     (nuevo)
│       ├── keys.yaml                # consumidor × entorno: alias, presupuesto, periodo, modelos        (nuevo)
│       ├── bootstrap_keys.py        # upsert idempotente de claves vía API del gateway                 (nuevo)
│       ├── shoplab_callbacks.py     # callback: una línea JSON por petición, sin contenido             (nuevo)
│       └── tests/                   # pytest del script y del callback                                 (nuevo)
├── backend/
│   ├── pom.xml                      # starter-model-ollama → starter-model-openai
│   └── src/
│       ├── main/
│       │   ├── resources/application.yml                # spring.ai.openai.*, shoplab.chat.gateway.*
│       │   └── java/com/shoplab/helpdesk/
│       │       ├── shared/exception/ResponseCodeEnum.java            # + 2 códigos de presupuesto
│       │       ├── chat/
│       │       │   ├── domain/exception/ChatBudgetExceededException.java                    # nuevo
│       │       │   ├── domain/model/{ServedModel.java}                                      # nuevo
│       │       │   ├── domain/model/constants/GatewayConstants.java                         # nuevo (nombre lógico `chat`)
│       │       ├── shared/constants/GatewayHeaders.java    # nuevo: cabecera x-shoplab-request-id y clave de contexto (comparten chat y retrieval)
│       │       │   ├── application/port/out/GenerationScope.java      # complete(...) recibe el modelo servido
│       │       │   ├── config/GatewayProperties.java                                       # nuevo
│       │       │   └── infrastructure/out/gateway/                     # antes out/ollama
│       │       │       GatewayChatAdapter.java, GatewayErrorTranslator.java,
│       │       │       ToolCallbackComposer.java, TracedToolCallback.java
│       │       └── retrieval/
│       │           ├── application/port/out/EmbeddingPort.java       # + EmbeddingPurpose
│       │           ├── domain/model/enums/EmbeddingPurpose.java                             # nuevo
│       │           └── infrastructure/out/embedding/SpringAiEmbeddingAdapter.java           # errores del gateway
│       └── test/java/com/shoplab/helpdesk/
│           ├── chat/GatewayStubs.java                    # antes OllamaStubs; OpenAI-compatible
│           ├── chat/*IntegrationTests.java               # renombrado mecánico del stub
│           ├── chat/GatewayFallbackIntegrationTests.java, GatewayBudgetIntegrationTests.java,
│           │   GatewayErrorsIntegrationTests.java        # nuevos
│           ├── architecture/ModuleArchitectureRules.java # + com.openai..
│           └── chat/infrastructure/out/gateway/*Tests.java
├── ingest/                           # sin cambios de código: solo su clave virtual y la regla de red (FR-001/002)
├── docs/adr/0023-gateway-de-modelos-litellm.md          # nuevo; enmienda a ADR-0003 (consecuencias)
├── docs/gateway-de-modelos.md                           # guía: cambiar de modelo, demostrar fallback, coste por conversación
└── README.md                                            # servicios nuevos, tabla de perfiles, variables

shoplab-infra/
├── k8s/base/litellm/                 # nuevo: deployment, service, configmap (config/keys/scripts), postgres, job de claves,
│                                     #        ExternalSecret, kustomization
├── k8s/base/kustomization.yaml       # + litellm
├── k8s/base/backend/{externalsecret-litellm,kustomization}.yaml   # + clave virtual del backend; URL del gateway
├── k8s/base/ingest/…                 # + clave virtual del consumidor ingest y NetworkPolicy
├── k8s/overlays/dev/{kustomization.yaml,patches/resources.yaml}   # recursos, ENDPOINT del gateway, ollama-host solo para litellm
├── terraform/modules/secrets/variables.tf, terraform/envs/dev/secrets.auto.tfvars.example   # + secreto litellm
└── docs/gateway-de-modelos.md        # acceso (port-forward), logs, presupuestos en el clúster
```

**Structure Decision**: se mantiene la arquitectura por módulo. El cambio de proveedor es un cambio de adaptador de
salida (`infrastructure/out/ollama` → `infrastructure/out/gateway`), sin tocar dominio ni casos de uso salvo dos puntos
pequeños: el puerto de embeddings recibe el propósito (para atribuir el coste) y `GenerationScope` recibe el modelo
servido. Los scripts del gateway viven en `infra/litellm/` (no en `backend/`), con sus propias pruebas. Cada repositorio
lleva su rama y PR (`feature/LAB-84-litellm-model-gateway` en ambos).

## Orden de entrega (para `/speckit-tasks`)

1. **Spikes S-1 a S-5** (research R-12) con el compose levantado y pruebas desechables.
2. **US1/US2 (infra local)** — `infra/litellm/` + servicios de compose + `.env.example` + CI `config -q` sobre `core`;
   gateway enrutando `chat`/`embeddings` a Ollama con precios.
3. **US1 (backend)** — starter OpenAI, `GatewayChatAdapter`, `GatewayStubs` y migración mecánica de las pruebas,
   `application.yml`; verificar que el contrato de `POST /api/chat` y la ingesta no cambian.
4. **US5** — `shoplab_callbacks.py` y cabeceras/`user` desde el backend (`EmbeddingPurpose`); pruebas de log sin contenido.
5. **US4** — `keys.yaml` + `bootstrap_keys.py`, errores de presupuesto/clave y su traducción tipada.
6. **US3** — fallback: configuración, modo de demostración documentado, modelo servido en la traza de LAB-64.
7. **US6** — proveedor cloud opcional (desactivado sin clave; verificado con servidor simulado).
8. **US7** — `shoplab-infra`: manifiestos, secretos, Job de claves, NetworkPolicy, recursos; verificación en el clúster.
9. **Cierre** — ADR-0023, README/guía, quickstart ejecutado, informe de verificación, medición de SC-004.

## Riesgos

| Riesgo | Mitigación |
|---|---|
| El cambio de cliente (Ollama → OpenAI) altera el prompt, las herramientas o el uso de tokens en streaming (S-2, S-3) | `GatewayStubs` reproduce el formato real capturado en S-2; suite de chat existente como red de seguridad; comparación manual con el gateway real |
| `num_ctx` (8192) no llega a Ollama desde el gateway y el RAG se trunca en silencio | Parámetro en `litellm_params` y comprobación en S-4 con un prompt largo; el quickstart lo verifica |
| LiteLLM rechaza la clave maestra de marcador ("valor inseguro conocido") (S-1) | Clave local con formato válido (`sk-…`) y obviamente local; documentado |
| Las claves virtuales no son declarativas en LiteLLM (S-5) | Script idempotente desde `keys.yaml`; si el gateway pierde su estado, vuelve a crearlas (FR-023) |
| Fallback a mitad de stream activado por defecto en alguna versión | `enable_mid_stream_fallback_continuation: false` explícito y prueba de integración |
| Memoria: segundo modelo local + gateway en máquinas pequeñas | Respaldo pequeño, descargado solo con `OLLAMA_FALLBACK_MODEL` definido; el gateway ocupa ~0,5 GiB (medir en S-1) |
| El nodo de DEV no cabe con Langfuse + gateway + su Postgres | Recursos mínimos; medición con `kubectl top`; patrón `EndpointSlice` al Docker del host como último recurso |
| Dos tablas de precios (gateway vs trazas) dan costes distintos | Documentado en ADR-0023 y en la guía; unificar queda como mejora explícita, no se hace aquí |
| Un cliente salta el gateway hacia `ollama` | Compose: red `models` solo con `ollama` y `litellm`. Clúster: en DEV Ollama es externo (EndpointSlice), así que `NetworkPolicy` de **salida** en `backend` e `ingest` hacia el `Service` `ollama` y `172.17.0.1:11434`, más entrada restringida a `litellm`; se comprueba si el CNI las aplica (S-7) |
| Quitar el puerto de `ollama` deja sin Ollama al clúster de DEV (`172.17.0.1:11434`) | Override `docker-compose.ollama-host.yml` documentado y comprobado en T083 |

## Complexity Tracking

| Desviación | Por qué es necesaria | Alternativa más simple descartada porque |
|---|---|---|
| Un PostgreSQL propio para el gateway (`litellm-postgres`) además del de la aplicación | Claves virtuales, presupuestos y gasto exigen persistencia; mezclarlos con la base de la aplicación rompe el principio de no compartir tablas y los scripts de inicialización solo corren en volúmenes nuevos | Reutilizar la base `shoplab` o crear una base `litellm` en el mismo servidor: obligaría a migrar volúmenes existentes y acopla el ciclo de vida del gateway al de la aplicación |
| Un script de arranque de claves (`bootstrap_keys.py`) y un *callback* propio en Python | LiteLLM no declara claves en su configuración ni emite por defecto una línea con consumidor + conversación + coste sin contenido | Crear claves a mano o por la interfaz: no reproducible tras `destroy`/`apply`; usar el log de acceso por defecto: no trae coste ni conversación |
| `ollama` deja de ser accesible para la aplicación y el cliente de Spring AI cambia de starter | FR-001/002: un único punto de salida | Dejar los dos starters y elegir por perfil: reintroduce el camino directo que la historia quiere eliminar |
