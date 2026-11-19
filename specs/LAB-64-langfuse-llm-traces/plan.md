# Implementation Plan: Trazas de LLM con Langfuse autoalojado

**Branch**: `feature/LAB-64-langfuse-llm-traces` | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-64-langfuse-llm-traces/spec.md`

## Summary

Cada turno del chat deja en Langfuse una traza con la pregunta, el prompt enviado al modelo, los fragmentos de contexto
(con fuente y puntuación), las herramientas llamadas, los tokens y el coste. Langfuse corre autoalojado en el perfil
`llmops` de docker compose y en el ambiente DEV del clúster.

Enfoque técnico (detalle y alternativas en [research.md](research.md)):

- **Puerto de salida** `ChatTracePort` en el módulo `chat`: el caso de uso abre y cierra el turno y registra la recuperación;
  el adaptador del modelo registra la generación y las herramientas. Sin trazas, un `NoOpChatTrace` (el caso de uso no
  ramifica).
- **Adaptador Langfuse** (`infrastructure/out/tracing`): un pipeline de OpenTelemetry **dedicado** (SDK + exportador OTLP
  HTTP, cola acotada) que solo existe con `shoplab.chat.tracing.enabled=true`. No toca el tracing ni las métricas HTTP de
  LAB-102 y no anticipa LAB-63.
- **Identidad estable**: `traceId` derivado de `SHA-256(requestId)`, `sessionId` = conversación; así LAB-29 adjunta
  puntuaciones sin buscar.
- **Coste** calculado en el backend con una tabla de precios por modelo (local = 0); uso desconocido ≠ 0.
- **Compose**: seis servicios Langfuse v4 con versiones fijas en el perfil `llmops`, inicializados por variables, con
  salud, volúmenes con nombre y puerto 3001.
- **Clúster**: manifiestos kustomize en `shoplab-infra/k8s/base/langfuse/`, PVC `local-path`, secreto JSON en Secrets
  Manager, acceso por `port-forward`; el backend de DEV activa el envío.

## Technical Context

**Language/Version**: Java 21 (backend); YAML (Compose, Kustomize); HCL mínimo (lista de secretos de Terraform)

**Primary Dependencies**: Spring Boot 4.1.2, Spring AI 2.0.2, Spring Modulith, Micrometer (ya presentes). Nuevas:
`io.opentelemetry:opentelemetry-sdk` y `opentelemetry-exporter-otlp` (versión del BOM de Spring Boot, a confirmar en S-5);
de prueba, `opentelemetry-sdk-testing`. Langfuse 4.77.0 (web y worker), ClickHouse 26.3.62.3, Redis 7.4.6, PostgreSQL 17.7,
MinIO fijado por digest.

**Storage**: la base de datos de la aplicación no cambia. Langfuse usa sus propios almacenes (PostgreSQL, ClickHouse,
Redis, MinIO) en volúmenes con nombre (compose) o PVC `local-path` (clúster).

**Testing**: JUnit 5 + AssertJ; `InMemorySpanExporter` para verificar los spans sin red; WireMock para Ollama (stubs ya
existentes, ampliados con conteos de tokens) y para un Langfuse simulado (caído/lento/401); `reactor-test`; BlockHound;
ArchUnit y Modulith (`ModularityTests`); JaCoCo ≥ 80 %. CI: `docker compose --profile llmops config -q`; en
`shoplab-infra`, `kustomize build`, `kubeconform` y sus políticas.

**Target Platform**: contenedores Linux en el Docker del desarrollador; k3s de un solo nodo de Floci para DEV.

**Project Type**: monolito modular web reactivo (`backend/`) + infraestructura en dos repositorios
(`shoplab-helpdesk/infra`, `shoplab-infra`).

**Performance Goals**: sobrecoste del tiempo hasta el primer token < 5 % con el envío activo (SC-004); envío siempre
fuera del hilo del chat.

**Constraints**: ninguna llamada bloqueante en el hilo del evento (BlockHound); cola de spans acotada (2048); contenido por
atributo ≤ 20 000 caracteres; líneas ≤ 121; sin `latest`; sin secretos en git; el chat nunca falla por las trazas.

**Scale/Scope**: uso de desarrollo (decenas de turnos al día); un único ambiente (`dev`).

## Constitution Check

*GATE: debe pasar antes de la investigación (Phase 0). Se vuelve a evaluar tras el diseño (Phase 1).*

| Principio | Evaluación | Resultado |
|---|---|---|
| I. Monolito modular primero | El cambio vive en el módulo `chat` y en `shared`/infra; no toca tablas de otros módulos ni añade servicios de aplicación. | ✅ |
| II. Hexagonal y dominio puro | `ChatTracePort` en `application/port/out`; el SDK de OTel solo aparece en `infrastructure/out/tracing`; el dominio no importa OTel ni Spring AI (se añade `io.opentelemetry..` a las reglas D1). El acceso al LLM sigue detrás de `ChatModelPort`. | ✅ |
| III. Identificadores en inglés, errores tipados | Identificadores en inglés. Un fallo de trazas nunca es un error de la aplicación: no hay nuevo `ResponseCodeEnum` ni `ResponseEntity`. | ✅ |
| IV. Test-first, cobertura ≥ 80 % | Pruebas deterministas con `InMemorySpanExporter`, WireMock y stubs; sin Langfuse ni modelo reales en CI (FR-030). | ✅ |
| V. RAG guiado por evaluación | No cambia prompt, troceado, embeddings ni recuperación; no degrada métricas. Habilita LAB-29. La evaluación en PR aún no existe (LAB-30). | ✅ N/A |
| VI. Observabilidad y manejo seguro de IA | Complementa las métricas (LAB-102) con trazas por turno. Claves por entorno, nada de datos reales de clientes, logs sin contenido. Las trazas sí llevan la conversación: por eso el envío es opt-in y solo con datos públicos. | ✅ con vigilancia |
| VII. Simplicidad y decisiones documentadas | Dos puntos justificados en *Complexity Tracking*; ADR-0022. | ✅ justificado |

**Restricciones tecnológicas**: `docker compose -f infra/docker-compose.yml up` sigue arrancando solo `core` (el perfil es
opcional); sin violaciones de estilo previstas.

**Resultado de la puerta**: aprobada. **Tras el diseño de Phase 1**: sin cambios; el modelo de datos y los contratos
mantienen el dominio libre de OTel y el pipeline aislado.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-64-langfuse-llm-traces/
├── plan.md              # Este archivo
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── trace-schema.md      # Árbol de spans y mapeo de atributos a Langfuse
│   └── configuration.md     # Propiedades, variables de compose y secretos
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks; no lo crea este comando)
```

`requests.http` no se genera: la historia no añade ni cambia ningún endpoint HTTP (`POST /api/chat` no cambia).

### Source Code (repository root)

```text
shoplab-helpdesk/
├── infra/
│   ├── docker-compose.yml           # + servicios langfuse-* (perfil llmops), volúmenes; cabecera de perfiles
│   └── .env.example                 # + variables LANGFUSE_* (imágenes, puerto, claves locales)
├── backend/
│   ├── pom.xml                      # + opentelemetry-sdk, opentelemetry-exporter-otlp, opentelemetry-sdk-testing (test)
│   └── src/
│       ├── main/
│       │   ├── resources/application.yml                # + shoplab.chat.tracing.*
│       │   └── java/com/shoplab/helpdesk/chat/
│       │       ├── domain/model/
│       │       │   ├── TokenUsage.java, RetrievalTrace.java,
│       │       │   │   PromptMessage.java, ToolCallTrace.java, TurnFailure.java        # nuevos
│       │       │   ├── constants/TracingConstants.java                                 # nuevo
│       │       │   └── enums/TurnResult.java, FailureStage.java                        # nuevos
│       │       ├── application/
│       │       │   ├── port/out/ChatTracePort.java, TurnTrace.java, GenerationScope.java   # nuevos
│       │       │   └── usecase/StreamChatUseCase.java                                  # modificado
│       │       ├── config/TracingProperties.java, TracingConfig.java                   # nuevos
│       │       └── infrastructure/out/
│       │           ├── tracing/LangfuseChatTrace.java, NoOpChatTrace.java,
│       │           │           LangfuseAttributes.java, DerivedTraceIdGenerator.java,
│       │           │           ContentLimiter.java, CostCalculator.java,
│       │           │           DroppedSpansCounter.java                                  # nuevos
│       │           └── ollama/OllamaChatAdapter.java, ToolCallbackComposer.java,
│       │                       TracedToolCallback.java                                   # modificados / nuevo
│       └── test/java/com/shoplab/helpdesk/chat/
│           ├── OllamaStubs.java                                            # modificado: conteos de tokens
│           ├── TurnTraceIntegrationTests.java, TraceResilienceIntegrationTests.java,
│           │   TraceSpecialTurnsIntegrationTests.java                      # nuevos
│           ├── application/usecase/StreamChatUseCaseTests.java (+ memoria) # modificados: nuevo colaborador
│           └── infrastructure/out/tracing/*Tests.java                      # nuevos
├── docs/adr/0022-trazas-de-llm-con-langfuse-por-otlp.md                    # nuevo
└── README.md                                                               # perfil llmops, cómo leer una traza

shoplab-infra/
├── k8s/base/langfuse/                       # nuevo: web, worker, 4 almacenes, ExternalSecret, kustomization
├── k8s/base/kustomization.yaml              # + langfuse
├── k8s/base/backend/{externalsecret,kustomization}.yaml   # + claves de Langfuse, tracing activado por overlay
├── k8s/overlays/dev/{kustomization.yaml,patches/resources.yaml}   # recursos, SHOPLAB_CHAT_TRACING_*
├── terraform/modules/secrets/variables.tf, terraform/envs/dev/secrets.auto.tfvars.example   # + secreto langfuse
└── docs/observability.md                    # acceso a Langfuse del clúster
```

**Structure Decision**: se mantiene la arquitectura por módulo ya existente (`domain`, `application`, `infrastructure`,
`config`). Todo el código de aplicación queda en `chat`; el adaptador de trazas es un adaptador de salida más, al lado de
`metrics/` y `ollama/`. Los manifiestos del clúster siguen la convención de `shoplab-infra` (base + overlay `dev`). Cada
repositorio lleva su propia rama y PR (`feature/LAB-64-langfuse-llm-traces` en ambos).

## Orden de entrega (para `/speckit-tasks`)

1. **Spikes S-1 a S-3** (research R-11) con pruebas desechables o el compose ya levantado.
2. **US1** — perfil `llmops`: servicios, salud, inicialización, `.env.example`, CI `config -q`, README.
3. **US2 (esqueleto)** — dominio, `ChatTracePort`/`TurnTrace`, `NoOpChatTrace`, cambio de `StreamChatUseCase` y de sus pruebas
   (sin cambio de comportamiento observable).
4. **US2 (adaptador)** — `LangfuseChatTrace` con el SDK, ids derivados, límites de contenido, coste; instrumentación de
   `OllamaChatAdapter` y `ToolCallbackComposer`; pruebas con `InMemorySpanExporter`.
5. **US4** — resiliencia: Langfuse caído/lento/401, interruptor apagado, cola llena, vaciado al apagar, BlockHound.
6. **US5** — turnos sin contexto, cancelados y fallidos.
7. **US3** — `shoplab-infra`: manifiestos, secretos, recursos, `port-forward`, validación de CI; verificación en el clúster (S-4).
8. **Cierre** — ADR-0022, README, quickstart ejecutado, informe de verificación.

## Riesgos

| Riesgo | Mitigación |
|---|---|
| Uso de tokens ausente en streaming (S-1) | Leer metadatos nativos de Ollama; el estado `unknown` ya está en el diseño |
| `traceId` derivado rechazado u ordenado raro en Langfuse (S-2) | `requestId` como metadato + búsqueda por API |
| El stack de Langfuse no cabe en el nodo de Floci (S-4) | Patrón `EndpointSlice` hacia el Docker del host (como `postgres-host.yaml`) y enmienda de la spec |
| Langfuse publica versiones muy seguido (4.77 → 4.78 en un día) | Versión fijada; actualizar es un cambio explícito; sin `latest` |
| Las trazas llevan conversaciones | Envío apagado por defecto; solo datos públicos; truncado; sin credenciales; documentado en el ADR |

## Complexity Tracking

| Desviación | Por qué es necesaria | Alternativa más simple descartada porque |
|---|---|---|
| Dependencias nuevas de OpenTelemetry (SDK + exportador OTLP) tras haberlas dejado fuera en ADR-0009 | Es el protocolo de ingesta que Langfuse recomienda y da gratis cola acotada, lotes, tiempo de espera y vaciado al apagar | La API REST de ingesta obligaría a reimplementar todo eso; el tracing global de Boot apagaría o contaminaría las métricas HTTP de LAB-102 |
| Una pila de seis servicios (Langfuse v4) en compose y en el clúster | La historia pide Langfuse autoalojado en ambos; v4 necesita PostgreSQL, ClickHouse, Redis y almacenamiento S3 | No existe una variante de Langfuse con menos almacenes; se aísla en un perfil opcional para no cargar la máquina |
