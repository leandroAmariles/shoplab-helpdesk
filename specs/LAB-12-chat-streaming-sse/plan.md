# Implementation Plan: Respuesta del chat en streaming (SSE)

**Branch**: `feature/LAB-12-chat-streaming-sse` | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-12-chat-streaming-sse/spec.md`

## Summary

`POST /api/chat` recibe un mensaje y devuelve la respuesta del modelo local (Ollama) como flujo SSE, fragmento a
fragmento, con un evento final `done` o `error`. El módulo `chat` se organiza en hexagonal: un controlador sin lógica
llama a un puerto de entrada; el caso de uso compone un `Flux<ChatEvent>` sobre dos puertos de salida (`ChatModelPort`
hacia Ollama vía Spring AI, y `ChatMetricsPort` hacia Micrometer). Los errores son excepciones tipadas traducidas en un
único `GlobalExceptionHandler` (antes del primer evento → HTTP 400/503; después → evento `error`). Como el backend aún
no tiene infraestructura de errores, la historia crea un módulo Modulith abierto `shared` para ella. Decisiones y
riesgos por verificar en [research.md](research.md).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1.2 (pila servlet, `spring-boot-starter-webmvc`), Spring AI 2.0.2 (starter de
Ollama, a confirmar en T001), Spring Modulith 2.1.2, Micrometer (ya presente por LAB-102), Reactor (`Flux`)

**Storage**: N/A (sin persistencia; cada petición es independiente)

**Testing**: JUnit 5 + AssertJ, `StepVerifier` (`reactor-test`, nueva, test), `@WebMvcTest`, `@SpringBootTest` con
WireMock (`wiremock-standalone`, test) simulando Ollama; JaCoCo ≥ 80 %, Checkstyle, PMD

**Target Platform**: Servidor JVM en local (host) y, más adelante, contenedor en el clúster; Ollama en `localhost:11434`

**Project Type**: web-service (módulo `chat` del monolito modular en `backend/`)

**Performance Goals**: primer evento `token` < 2 s en el 95 % de las peticiones con el modelo ya cargado (SC-001);
cancelación efectiva < 2 s (SC-005)

**Constraints**: sin `.block()` ni acumulación de la respuesta completa en memoria; mensaje ≤ 4000 caracteres; sin
contenido de mensajes en logs ni en etiquetas de métricas; flujo sin buffering en intermediarios

**Scale/Scope**: laboratorio local; ≥ 5 peticiones concurrentes sin mezclar flujos (SC-007); 1 endpoint, 3 tipos de
evento

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación | Estado |
|-----------|------------|--------|
| I. Monolito modular | La funcionalidad vive en `chat`; sin acceso a internals de otros módulos. Se añade el módulo `shared` (abierto) para errores comunes; verificado por `ModularityTests` actualizado | Pasa, con nota (ver Complexity Tracking) |
| II. Hexagonal y pureza del dominio | Controlador sin lógica → `StreamChatServicePort`; caso de uso sin infraestructura; LLM tras `ChatModelPort`; métricas tras `ChatMetricsPort`; constantes en `model/constants`, enums en `model/enums` | Pasa |
| III. Identificadores en inglés, errores tipados | Excepciones tipadas, códigos en `ResponseCodeEnum`, un solo `GlobalExceptionHandler`; texto de usuario en español permitido | Pasa |
| IV. Test-first y cobertura ≥ 80 % | Pruebas por capa antes/junto a la implementación; LLM simulado con WireMock; `dto`, `config`, `model`, `exception` sin lógica | Pasa |
| V. RAG evaluado | No aplica: sin recuperación ni citas en F0; la evaluación automática llega en F2 | N/A |
| VI. Observabilidad y manejo seguro | Métricas de primer token y duración por resultado; logs con `requestId` y sin contenido del cliente; sin secretos | Pasa |
| VII. Simplicidad y ADR | Sin historial, RAG ni auth (YAGNI). El módulo `shared` requiere ADR (tarea) | Pasa, con ADR pendiente |

**Re-evaluación tras el diseño (Phase 1)**: sin cambios. El diseño no introduce persistencia, mensajería ni nuevos
servicios. Las dos desviaciones leves están justificadas abajo.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-12-chat-streaming-sse/
├── plan.md              # Este archivo
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── openapi.yaml     # Petición, cabeceras y errores HTTP
│   └── chat-stream.md   # Contrato de los eventos SSE
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks; no lo crea /speckit-plan)
```

### Source Code (repository root)

```text
backend/
├── pom.xml                                   # + starter Ollama; test: reactor-test, webmvc-test, wiremock
├── src/main/resources/application.yml        # + spring.ai.ollama.*, shoplab.chat.*
├── src/main/java/com/shoplab/helpdesk/
│   ├── shared/                               # módulo abierto: errores comunes
│   │   ├── package-info.java                 # @ApplicationModule(type = OPEN)
│   │   ├── exception/                        # ResponseCodeEnum, BusinessException, TechnicalException, ExceptionUtil
│   │   └── infrastructure/in/rest/
│   │       ├── handler/GlobalExceptionHandler.java
│   │       ├── dto/ErrorResponse.java
│   │       └── filter/RequestIdFilter.java   # X-Request-Id -> MDC y cabecera de respuesta
│   └── chat/
│       ├── domain/model/
│       │   ├── ChatEvent.java                # sealed: TokenEvent | DoneEvent | ErrorEvent
│       │   ├── StreamChatCommand.java
│       │   ├── constants/ChatConstants.java  # @UtilityClass (nombres de evento, prompt por defecto)
│       │   └── enums/ChatOutcome.java
│       ├── application/
│       │   ├── port/in/StreamChatServicePort.java
│       │   ├── port/out/ChatModelPort.java
│       │   ├── port/out/ChatMetricsPort.java
│       │   └── usecase/StreamChatUseCase.java
│       ├── infrastructure/
│       │   ├── in/rest/ChatController.java
│       │   ├── in/rest/dto/ChatRequest.java
│       │   ├── in/rest/util/mapper/ChatEventMapper.java   # ChatEvent -> ServerSentEvent
│       │   ├── out/ollama/OllamaChatAdapter.java
│       │   └── out/metrics/MicrometerChatMetrics.java
│       └── config/ChatProperties.java        # @ConfigurationProperties("shoplab.chat")
└── src/test/java/com/shoplab/helpdesk/
    ├── ModularityTests.java                  # esperado: chat, retrieval, tools, tickets, shared
    ├── shared/…                              # manejador, filtro, ExceptionUtil
    └── chat/
        ├── application/usecase/StreamChatUseCaseTests.java    # StepVerifier
        ├── infrastructure/in/rest/ChatControllerTests.java    # @WebMvcTest
        ├── infrastructure/out/ollama/OllamaChatAdapterTests.java   # WireMock
        └── ChatStreamingIntegrationTests.java    # @SpringBootTest + WireMock + cliente HTTP real
docs/adr/0010-modulo-shared-errores.md            # nuevo ADR (módulo shared)
README.md / docs/                                 # cómo probar el chat (FR-017)
```

**Structure Decision**: se amplía el proyecto Maven único `backend/` (ADR-0004) dentro del módulo `chat`, con la
disposición hexagonal de la guía de desarrollo. No se crean servicios ni proyectos nuevos. `shared` es el único
paquete de primer nivel nuevo.

## Complexity Tracking

| Desviación | Por qué es necesaria | Alternativa más simple descartada porque |
|------------|----------------------|------------------------------------------|
| Nuevo módulo `shared` (5.º módulo; cambia `ModularityTests` y exige ADR-0010) | La guía exige un único `GlobalExceptionHandler` y `ResponseCodeEnum`, y `retrieval`/`tools`/`tickets` los necesitarán | Dejarlos en `chat` obliga a los demás módulos a depender de `chat` para lanzar errores; en el paquete raíz no hay frontera verificable |
| `ChatMetricsPort` aparte (un puerto más) | El caso de uso no puede depender de Micrometer (constitución II), y las métricas son requisito (FR-013) | Registrar métricas directamente en el caso de uso acopla la aplicación a infraestructura |
