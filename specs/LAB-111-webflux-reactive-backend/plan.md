# Implementation Plan: Backend reactivo con Spring WebFlux

**Branch**: `refactor/LAB-111-webflux-reactive-backend` | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-111-webflux-reactive-backend/spec.md`

## Summary

Migrar la capa web del backend de Spring MVC (servlet) a Spring WebFlux (Netty) **sin cambiar el contrato de
`POST /api/chat`**. El camino del chat ya es reactivo por dentro (`Flux<ChatEvent>`, `ChatClient.stream()`), así que el
cambio se concentra en el borde: el starter y el servidor, el controlador, el filtro de correlación (ahora `WebFilter`
con contexto de Reactor), el manejador global de errores (variante reactiva, `ServerWebInputException`, sin 406 para
clientes SSE) y todos los tests (`WebTestClient` + `StepVerifier`). Se añaden tres garantías automáticas: BlockHound
en la suite (con prueba negativa), reglas ArchUnit (nada de `Schedulers`/JDBC/clientes HTTP bloqueantes fuera de
`infrastructure`) y un *golden file* del flujo SSE para demostrar que el contrato no cambió. Se crea un ayudante mínimo
`BlockingCalls` para aislar JDBC en `Schedulers.boundedElastic()` y se redacta el ADR-0011. No se migra ningún acceso a
datos: el backend aún no tiene JDBC. Decisiones y riesgos por verificar en [research.md](research.md).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1.2 con `spring-boot-starter-webflux` (Netty) en lugar de
`spring-boot-starter-webmvc`; Spring AI 2.0.2 (starter de Ollama; `ChatClient.stream()` usa `WebClient`); Spring
Modulith 2.1.2; Micrometer/Prometheus (LAB-102); Reactor; `io.micrometer:context-propagation` (MDC)

**Storage**: N/A hoy (sin JDBC en el backend). Futuro: PostgreSQL + pgvector por JDBC, aislado en
`Schedulers.boundedElastic()` (ADR-0011)

**Testing**: JUnit 5 + AssertJ; `WebTestClient` y `@WebFluxTest` (`spring-boot-starter-webflux-test`);
`StepVerifier`/`TestPublisher` (`reactor-test`); BlockHound 1.0.11.RELEASE (test); ArchUnit (transitivo vía Modulith);
WireMock simulando Ollama; JaCoCo ≥ 80 %, Checkstyle, PMD

**Target Platform**: servidor JVM en local y, después, contenedor en el clúster (sin cambios de imagen ni puerto)

**Project Type**: web-service (backend en `backend/`, monolito modular)

**Performance Goals**: primer `token` < 2 s en el 95 % de las peticiones con el modelo cargado (SC-002); cancelación
efectiva < 2 s (SC-003); hilos de atención acotados con 50 streams simultáneos (SC-005)

**Constraints**: contrato de `/api/chat` byte a byte igual; cero operaciones bloqueantes en hilos del bucle de
eventos; ningún `.block()` en `src/main`; correlación `requestId` en logs entre saltos de hilo; sin contenido de
mensajes en logs ni etiquetas

**Scale/Scope**: laboratorio local; 1 endpoint de negocio; 4 clases de borde reescritas (controlador, filtro, manejador,
configuración) + 1 ayudante nuevo; ~9 clases de test migradas

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación | Estado |
|-----------|------------|--------|
| I. Monolito modular | Sin módulos nuevos; el ayudante vive en el módulo abierto `shared`. `ModularityTests` sigue esperando los mismos cinco módulos | Pasa |
| II. Hexagonal y pureza del dominio | Puertos y caso de uso intactos (ya reactivos); `Schedulers`/JDBC solo en `infrastructure`; regla ArchUnit lo verifica; controlador sin lógica | Pasa |
| III. Identificadores en inglés, errores tipados | Mismos códigos en `ResponseCodeEnum` y un único `GlobalExceptionHandler`, ahora reactivo; texto de usuario en español permitido | Pasa |
| IV. Test-first y cobertura ≥ 80 % | Golden file y BlockHound se escriben antes de migrar; mismos escenarios de LAB-12; LLM simulado | Pasa |
| V. RAG evaluado | No aplica: sin recuperación ni citas | N/A |
| VI. Observabilidad y manejo seguro | Métricas HTTP y del chat intactas (línea base + comparación); `requestId` propagado por contexto; sin datos del cliente en logs | Pasa |
| VII. Simplicidad y ADR | Cambio de plataforma justificado en ADR-0011. `BlockingCalls` sin consumidor real hoy: ver Complexity Tracking | Pasa, con nota |

**Re-evaluación tras el diseño (Phase 1)**: sin cambios. No se añade persistencia, mensajería ni servicios. La única
adición con riesgo de sobreingeniería (`BlockingCalls`) está justificada abajo.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-111-webflux-reactive-backend/
├── plan.md              # Este archivo
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── compatibility.md # Qué debe quedar idéntico (chat, errores, métricas) y diferencias aceptadas
│   └── blocking-isolation.md # Contrato de BlockingCalls y reglas de arquitectura
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks — no lo crea este comando)
```

### Source Code (repository root)

```text
backend/
├── pom.xml                                   # webmvc → webflux; + blockhound, context-propagation; surefire argLine
├── src/main/
│   ├── resources/application.yml             # + spring.reactor.context-propagation: auto
│   └── java/com/shoplab/helpdesk/
│       ├── chat/                             # SIN cambios: domain, application, ports, use case, adapters, metrics
│       │   └── infrastructure/in/rest/
│       │       └── ChatController.java       # lee requestId del ServerWebExchange; resto igual
│       └── shared/
│           └── infrastructure/
│               ├── in/rest/
│               │   ├── filter/RequestIdFilter.java        # OncePerRequestFilter → WebFilter + contextWrite
│               │   └── handler/GlobalExceptionHandler.java # variante reactiva; ServerWebInputException; sin 406
│               └── out/blocking/BlockingCalls.java        # NUEVO: aísla llamadas bloqueantes en boundedElastic
├── src/test/
│   ├── java/com/shoplab/helpdesk/
│   │   ├── architecture/ReactiveArchitectureTests.java    # NUEVO: reglas ArchUnit (FR-005, FR-007)
│   │   ├── blockhound/HelpdeskBlockHoundIntegration.java  # NUEVO: allowances mínimos y justificados
│   │   ├── blockhound/BlockHoundSessionListener.java      # NUEVO: instala BlockHound una vez por sesión
│   │   ├── blockhound/BlockHoundDetectionTests.java       # NUEVO: prueba negativa y positiva (FR-009)
│   │   ├── chat/                                          # migrados a WebTestClient/StepVerifier
│   │   │   ├── ChatStreamingIntegrationTests.java
│   │   │   ├── ChatMidStreamFailureIntegrationTests.java
│   │   │   ├── ChatModelDownIntegrationTests.java
│   │   │   ├── ChatStreamContractTests.java               # NUEVO: golden file byte a byte
│   │   │   └── infrastructure/in/rest/ChatControllerTests.java
│   │   └── shared/infrastructure/
│   │       ├── in/rest/filter/RequestIdFilterTests.java
│   │       ├── in/rest/handler/GlobalExceptionHandlerTests.java
│   │       └── out/blocking/BlockingCallsTests.java       # NUEVO
│   └── resources/
│       ├── META-INF/services/org.junit.platform.launcher.LauncherSessionListener
│       └── chat/golden-stream.txt                         # capturado en MVC antes de migrar
└── (SseTestClient.java se elimina cuando nada lo use)

docs/adr/
├── 0011-web-reactiva-persistencia-jdbc.md    # NUEVO
└── README.md                                 # + fila en el índice
```

**Structure Decision**: cambio acotado a `backend/` (y un ADR en `docs/`). Se respeta la estructura hexagonal por módulo
de LAB-11/LAB-12: ningún fichero de `chat/domain`, `chat/application` ni los adaptadores de salida se toca. El único
código nuevo de producción es `BlockingCalls`, en el módulo abierto `shared`; el resto es reescritura de pegamento web y
migración de pruebas.

## Fases de implementación (orden recomendado)

1. **Spike y red de seguridad (en MVC, antes de migrar)**: capturar el *golden file* del flujo SSE, la línea base de
   nombres/etiquetas de `/actuator/prometheus` y el contrato de errores; añadir BlockHound + listener y ejecutar la
   suite actual para ver los falsos positivos. Resuelve los 6 riesgos de la sección final de research.md.
2. **Cambio de plataforma**: `pom.xml`, `RequestIdFilter`, `GlobalExceptionHandler`, `ChatController`,
   `application.yml`. La suite de contrato (golden + errores) debe volver a verde.
3. **Aislamiento y reglas**: `BlockingCalls` + pruebas; reglas ArchUnit; BlockHound con prueba negativa.
4. **Migración de pruebas** restante a `WebTestClient`/`StepVerifier`, eliminación de `SseTestClient` y de lo MVC.
5. **ADR-0011 y documentación**: ADR, índice, notas de arquitectura del README/`docs` que mencionen servlet/MVC.
6. **Verificación**: `mvn verify` (cobertura, Checkstyle, PMD), `quickstart.md` en local con Ollama real, comparación
   de métricas con la línea base.

## Complexity Tracking

> Solo se rellena por desviaciones o adiciones que la constitución pide justificar.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `BlockingCalls` en `shared` sin consumidor real hoy (VII: YAGNI) | El criterio 5 y la US3.3 piden dejar establecido y probado el patrón de aislamiento antes de que lleguen pgvector y ChatMemory; sin él cada módulo improvisaría su `subscribeOn` y la regla ArchUnit no tendría a qué apuntar | Esperar a la primera historia con JDBC: esa historia tendría que decidir y probar el patrón a la vez que su lógica, y BlockHound no tendría un ejemplo positivo |
| Pruebas ArchUnit propias sobre `Schedulers`/JDBC/clientes HTTP | Hacen ejecutables los criterios 4 y 5; BlockHound solo detecta bloqueos que ocurren en ejecución, ArchUnit detecta el diseño | Revisión manual en PR: no escala ni falla en CI |

## Cambio respecto al plan (2026-10-26)

El adaptador `OllamaChatAdapter` transmite desde `ChatModel.stream()` y no desde `ChatClient.stream()`, porque el asesor
terminal del cliente fija un hilo `boundedElastic` por stream (research R12). Es una desviación del criterio 2 de la
historia aprobada por el responsable; la petición a Ollama y el contrato no cambian.
