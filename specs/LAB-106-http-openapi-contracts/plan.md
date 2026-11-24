# Implementation Plan: Contratos HTTP con OpenAPI y pruebas de contrato

**Branch**: `feature/LAB-106-http-openapi-contracts` | **Date**: 2026-11-24 | **Spec**: [specs/LAB-106-http-openapi-contracts/spec.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/spec.md)

**Input**: Feature specification from `/specs/LAB-106-http-openapi-contracts/spec.md`

## Summary

Dotar a todas las APIs HTTP publicadas de **ShopLab Helpdesk** (`chat`, `retrieval`, `eval`; y convención de nacimiento para `catalog`, `orders`, `tickets`) de contratos formales verificados automáticamente en CI antes de ejecutar la extracción temprana de servicios (`LAB-129`, `LAB-59`, `LAB-130`). El plan coordina cambios en `shoplab-contracts` (creado en `LAB-108`) y `shoplab-helpdesk`:

1. **Especificaciones OpenAPI 3.1 en `shoplab-contracts/openapi/`**: `chat-api.yaml`, `retrieval-api.yaml` y `eval-api.yaml`, más el esquema aditivo `ApiErrorResponse` en `common-api.yaml` alineado con `GlobalExceptionHandler`, y regla obligatoria al nacer para `catalog`, `orders` y `tickets`.
2. **Publicación de `GET /api/assistant/settings` en `chat`**: nuevo controlador WebFlux hexagonal de solo lectura que expone por HTTP la configuración que hoy entrega `AssistantSettingsApi` en proceso.
3. **Cliente Angular generado y consumo en `frontend/`**: generación multi-spec en `@leandroamariles/shoplab-contracts` (GitHub Packages) y migración completa de `frontend/src/app/` para eliminar todos los DTOs del backend escritos a mano (incluidos los payloads de eventos SSE).
4. **Endurecimiento del gate `oasdiff` en `shoplab-contracts`**: corrección de `BASE_REF` en PRs y fallo estricto ante sintaxis inválida en contratos nuevos.
5. **Pruebas de contrato Spring Cloud Contract (`chat → retrieval` y `eval → chat`)**: contratos YAML DSL separados por productor, verificación reactiva en `WEBTESTCLIENT`, publicación de stubs en GitHub Packages y tests de consumidor con `StubRunner` usando adaptadores HTTP `WebClient` listos para la extracción.
6. **Guía de versionado y retiro en `docs/fases/`**: `F5-contratos-http-versionado-y-retiro.md` con matriz SemVer, convivencia y protocolo de *sunset*.

## Technical Context

**Language/Version**: Java 21, TypeScript `~6.0.2` (Angular `22.2.x`), OpenAPI `3.1.0`, YAML DSL (Spring Cloud Contract)

**Primary Dependencies**:
- Backend: Spring Boot `4.1.2` (WebFlux), Spring Modulith `2.1.2`, Spring Cloud `2025.1.3` (*Oakwood* — Spring Cloud Contract `5.0.x`: `spring-cloud-starter-contract-verifier`, `spring-cloud-starter-contract-stub-runner`, `spring-cloud-contract-maven-plugin` en modo `WEBTESTCLIENT`), `com.shoplab:shoplab-contracts`
- Frontend: Angular `22.2.6`, RxJS `~7.8.0`, Vitest `^5.1.0`, `@leandroamariles/shoplab-contracts` (GitHub Packages npm)
- Contratos CI (`shoplab-contracts`): `oasdiff` `1.11.7`, `@openapitools/openapi-generator-cli` (`typescript-angular`), `openapi-generator-maven-plugin` `7.10.0`, `release-please`

**Storage**: N/A (los contratos de interfaz y `GET /api/assistant/settings` no añaden tablas ni migraciones Flyway)

**Testing**:
- Backend: JUnit 5, Mockito, `StepVerifier`, `WebTestClient` (`RestAssuredWebTestClient`), Spring Cloud Contract Verifier + Stub Runner (`LOCAL` y `REMOTE`), BlockHound, `ModularityTests`, Checkstyle, PMD, JaCoCo ≥ 80 %
- Frontend: ESLint (`npm run lint`), Vitest (`npm test -- --watch=false`), Angular CLI (`npm run build`)
- Contratos: `scripts/check-openapi-compatibility.sh` (`oasdiff`), `scripts/verify-contracts-purity.sh`, build dry-run Java & npm

**Target Platform**: Linux CI (GitHub Actions) y desarrollo local Windows/Linux; artefactos publicados en GitHub Packages (Maven y npm)

**Project Type**: Aplicación web multi-módulo (`backend/` Spring Modulith + `frontend/` Angular) coordinada con el repositorio de contratos (`shoplab-contracts/`)

**Performance Goals**:
- `GET /api/assistant/settings` responde en < 10 ms leyendo estado en memoria sin I/O bloqueante ni llamadas a PostgreSQL/Langfuse
- Suite de contratos y Stub Runner < 30 s dentro de `./mvnw verify`

**Constraints**:
- Cero `.block()` en producción y tests reactivos (BlockHound activo)
- Cero lógica de dominio en `shoplab-contracts` (ADR-0010, ADR-0015)
- Versiones exactas fijadas para dependencias de contratos en `pom.xml` y `frontend/package.json`
- Código HTTP reales preservados (p. ej. `422 SLHD_RETR_00009` en `GET /api/knowledge/search`)
- Generación de `requests.http` en `specs/LAB-106-http-openapi-contracts/` al implementar `GET /api/assistant/settings`

**Scale/Scope**: 3 APIs HTTP publicadas (`chat`: 5 endpoints, `retrieval`: 3 endpoints, `eval`: 6 endpoints), 2 relaciones de contrato consumidor-productor (`chat → retrieval`, `eval → chat`) y 5 clientes Angular migrados.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio Constitucional | Pre-Design | Post-Design | Justificación y Cumplimiento |
|---|---|---|---|
| **I. Modular Monolith First, Extract Later** | PASS | PASS | Se mantiene el monolito modular en `backend/` y la comunicación en proceso por defecto (`AssistantSettingsApi`, `KnowledgeSearchApi`). Los clientes HTTP `WebClient` quedan desacoplados detrás de propiedad opt-in hasta `LAB-129`/`LAB-130`, verificados por `ModularityTests`. |
| **II. Hexagonal Architecture and Domain Purity** | PASS | PASS | `AssistantSettingsController` solo mapea y delega al puerto de entrada. `HttpKnowledgeSearchClient` y `HttpAssistantSettingsClient` viven en `infrastructure/out` implementando puertos existentes (`KnowledgeContextPort`, `AssistantSettingsPort`). `shoplab-contracts` no contiene lógica de dominio. |
| **III. English Identifiers, Typed Errors** | PASS | PASS | Todos los identificadores Java/TS/OpenAPI están en inglés; documentación en español. Todos los errores pasan por `HelpdeskException` + `ResponseCodeEnum` + `GlobalExceptionHandler` (`ApiErrorResponse`), sin `ResponseEntity` de error en controladores ni adaptadores. |
| **IV. Test-First with Coverage Gate** | PASS | PASS | Incluye tests unitarios/WebFlux para `GET /api/assistant/settings`, tests generados de Spring Cloud Contract (`WEBTESTCLIENT`), tests de consumidor `StubRunner`, actualización de specs Vitest en `frontend/` y validación JaCoCo ≥ 80 % + Checkstyle + PMD. |
| **V. Evaluation-Driven RAG** | N/A | N/A | No modifica prompts, chunking, embeddings ni lógica de recuperación; solo formaliza contratos y expone metadatos de configuración en lectura. |
| **VI. Observability and Safe AI Handling** | PASS | PASS | Tokens de GitHub Packages (`GITHUB_TOKEN`) gestionados vía secretos de CI y `~/.m2`/`~/.npmrc` locales, nunca en el repositorio. Sin datos reales de clientes en ejemplos OpenAPI ni en contratos YAML. |
| **VII. Simplicity and Documented Decisions** | PASS | PASS | Usa `ApiErrorResponse` aditivo para evitar breaking changes prematuros, `WEBTESTCLIENT` in-memory para WebFlux, y documenta versionado/retiro en `docs/fases/F5-contratos-http-versionado-y-retiro.md`. |

## Project Structure

### Documentation (this feature)

```text
specs/LAB-106-http-openapi-contracts/
├── spec.md              # Especificación funcional y escenarios
├── plan.md              # Este documento (salida de /speckit-plan)
├── research.md          # Decisiones técnicas de Fase 0
├── data-model.md        # Esquemas contractuales y metadatos de stubs
├── quickstart.md        # Comandos reproducibles de validación
├── contracts/
│   ├── chat-api.yaml                 # Blueprint OpenAPI 3.1 de chat
│   ├── retrieval-api.yaml            # Blueprint OpenAPI 3.1 de retrieval
│   ├── eval-api.yaml                 # Blueprint OpenAPI 3.1 de eval
│   └── spring-cloud-contracts.md     # Contratos YAML DSL, bases y StubRunner
├── checklists/
│   └── requirements.md               # Checklist de calidad de especificación
└── requests.http        # Casos HTTP (generado durante /speckit-implement)
```

### Source Code (across coordinated repositories)

#### 1. Repositorio `shoplab-contracts` (`../shoplab-contracts`)
```text
shoplab-contracts/
├── .github/workflows/
│   ├── ci.yml                               # Endurecimiento base_ref y dry-run multi-spec
│   └── publish.yml                          # Ejecuta npm run generate antes de build/publish
├── openapi/
│   ├── common/v1/common-api.yaml            # Añade esquema aditivo ApiErrorResponse
│   ├── chat/v1/chat-api.yaml                # Contrato OpenAPI 3.1 de chat + SSE + assistant/settings
│   ├── retrieval/v1/retrieval-api.yaml      # Contrato OpenAPI 3.1 de documents + knowledge/search
│   ├── eval/v1/eval-api.yaml                # Contrato OpenAPI 3.1 de runs + settings + history
│   └── README.md                            # Regla de nacimiento para catalog, orders y tickets
├── generators/
│   ├── java/pom.xml                         # Generación DTOs Java para common, chat, retrieval, eval
│   └── npm/
│       ├── package.json                     # Generación multi-spec + barrel src/index.ts
│       ├── tsconfig.json
│       └── scripts/generate-all.mjs         # Script de generación limpia y barrel exports
└── scripts/
    └── check-openapi-compatibility.sh       # Falla ante sintaxis inválida en specs nuevas
```

#### 2. Repositorio `shoplab-helpdesk` (`backend/`, `frontend/`, `docs/`, `.github/`)
```text
backend/
├── pom.xml                                  # Spring Cloud BOM 2025.1.3, Contract Verifier/StubRunner, plugin, distributionManagement
├── src/main/java/com/shoplab/helpdesk/
│   ├── chat/
│   │   ├── application/port/in/GetAssistantSettingsServicePort.java
│   │   ├── application/usecase/GetAssistantSettingsUseCase.java
│   │   ├── config/ChatConfiguration.java    # Cableado manual del nuevo caso de uso y cliente HTTP opt-in
│   │   ├── infrastructure/in/rest/
│   │   │   ├── AssistantSettingsController.java
│   │   │   ├── dto/AssistantSettingsResponse.java
│   │   │   └── util/mapper/AssistantSettingsRestMapper.java
│   │   └── infrastructure/out/knowledge/
│   │       └── HttpKnowledgeSearchClient.java        # Adaptador WebClient opt-in (shoplab.retrieval.client.mode=http)
│   └── eval/
│       ├── config/EvalConfiguration.java             # Cableado opt-in de HttpAssistantSettingsClient
│       └── infrastructure/out/chat/
│           └── HttpAssistantSettingsClient.java      # Adaptador WebClient opt-in (shoplab.chat.settings.client.mode=http)
└── src/test/
    ├── java/com/shoplab/helpdesk/
    │   ├── chat/infrastructure/in/rest/
    │   │   ├── AssistantSettingsControllerTest.java
    │   │   └── ChatContractBase.java
    │   ├── chat/infrastructure/out/knowledge/
    │   │   └── ChatRetrievalContractConsumerTest.java
    │   ├── retrieval/infrastructure/in/rest/
    │   │   └── RetrievalContractBase.java
    │   └── eval/infrastructure/out/chat/
    │       └── EvalChatContractConsumerTest.java
    └── resources/contracts/
        ├── retrieval/
        │   ├── shouldReturnKnowledgeSearchResults.yml
        │   ├── shouldReturnEmptyKnowledgeSearchResults.yml
        │   └── shouldRejectBlankQueryWith422.yml
        └── chat/
            └── shouldReturnAssistantSettings.yml

frontend/
├── .npmrc                                   # Registro @leandroamariles -> https://npm.pkg.github.com
├── package.json                             # Dependencia exacta @leandroamariles/shoplab-contracts
└── src/app/
    ├── core/api/
    │   ├── api-error.ts
    │   └── api-error.interceptor.ts         # Tipado de error con ApiErrorResponse del paquete
    └── features/
        ├── chat/
        │   ├── chat-feedback.client.ts      # Usa DTOs generados
        │   ├── chat-stream.client.ts        # Usa ChatRequest y payloads SSE generados
        │   └── chat-message.ts
        ├── documents/
        │   ├── document.ts
        │   └── documents-api.client.ts      # Usa DocumentSummaryResponse / DocumentUploadResponse
        └── eval/
            ├── eval-api.client.ts           # Reemplaza las 12 interfaces Wire* por tipos generados
            └── feedback-promotion/
                └── feedback-api.client.ts   # Usa FeedbackItemResponse / FeedbackListResponse / PromoteFeedbackRequest

docs/
├── fases/
│   └── F5-contratos-http-versionado-y-retiro.md   # Reglas SemVer, convivencia y sunset
└── modulos/                                       # Regenerado por ModularityTests si cambian relaciones

.github/workflows/
├── ci.yml                                   # Auth read:packages en backend & frontend
└── publish-stubs.yml                        # Publicación de helpdesk-backend-stubs.jar en GitHub Packages
```

**Structure Decision**: Se adopta la arquitectura multi-repositorio definida en ADR-0015 / LAB-108: las especificaciones OpenAPI, su validación `oasdiff` y la generación de artefactos versionados residen en `shoplab-contracts`, mientras `shoplab-helpdesk` implementa los endpoints productores, los contratos y stubs Spring Cloud Contract separados por productor, el consumo del paquete npm en `frontend/` y la guía normativa en `docs/fases/`.

## Implementation Phases Overview

1. **Fase A — Contratos OpenAPI, `oasdiff` y generadores (`shoplab-contracts`)**:
   - Incorporar `ApiErrorResponse` en `common-api.yaml` y publicar `chat-api.yaml`, `retrieval-api.yaml` y `eval-api.yaml` (más README con convención para `catalog`/`orders`/`tickets`).
   - Endurecer `scripts/check-openapi-compatibility.sh` y `.github/workflows/ci.yml` (`origin/${base_ref}` y fallo en sintaxis inválida).
   - Actualizar `generators/npm` y `generators/java` para generar y compilar los 4 módulos y preparar la release `minor` en GitHub Packages.
2. **Fase B — Endpoint `GET /api/assistant/settings` y Spring Cloud Contract (`backend/`)**:
   - Exponer `GET /api/assistant/settings` siguiendo arquitectura hexagonal sin bloqueo y añadir tests WebFlux + `requests.http`.
   - Configurar Spring Cloud `2025.1.3` (`WEBTESTCLIENT`), contratos YAML para `retrieval` y `chat`, clases `*ContractBase`, exclusiones Checkstyle/PMD de fuentes generadas y publicación de stubs en GitHub Packages.
   - Implementar `HttpKnowledgeSearchClient` y `HttpAssistantSettingsClient` (opt-in por configuración) junto con `ChatRetrievalContractConsumerTest` y `EvalChatContractConsumerTest` usando Stub Runner.
3. **Fase C — Migración del Frontend Angular e integración CI (`frontend/`, `.github/workflows/`)**:
   - Instalar `@leandroamariles/shoplab-contracts` fijado y reemplazar todos los tipos backend escritos a mano en `chat`, `documents`, `eval` y `core/api`, validando `lint`, `test` y `build`.
   - Configurar credenciales `packages: read` en `ci.yml` y añadir `publish-stubs.yml`.
4. **Fase D — Documentación y cierre de compuertas (`docs/fases/`, `backend/`)**:
   - Redactar `docs/fases/F5-contratos-http-versionado-y-retiro.md` enlazado con `docs/contratos.md`.
   - Ejecutar `ModularityTests` (verificando `docs/modulos/`) y `mvnw clean verify` completo.

## Complexity Tracking

> No hay violaciones constitucionales. Se documentan únicamente dos decisiones de transición controlada hacia la extracción de microservicios:

| Decisión de Transición | Por qué es necesaria | Alternativa más simple descartada |
|---|---|---|
| Adaptadores HTTP `HttpKnowledgeSearchClient` y `HttpAssistantSettingsClient` con propiedad opt-in (`in-process` por defecto) | Permite probar hoy con Stub Runner la integración por red (`chat → retrieval`, `eval → chat`) antes de extraer los servicios en `LAB-129`/`LAB-130`, sin introducir llamadas HTTP en el monolito en producción. | Esperar a `LAB-129`/`LAB-130` para escribir los clientes HTTP dejaría sin pruebas reales de consumidor en `LAB-106`. |
| Un único JAR `helpdesk-backend:stubs` con subcarpetas `contracts/retrieval/` y `contracts/chat/` | Mientras ambos módulos viven en el mismo `pom.xml`, Maven genera un artefacto stubs por proyecto; separar carpetas y clases `*ContractBase` permite extraer cada productor a su propio repo sin reescribir contratos. | Dividir `backend/` en sub-módulos Maven ahora añadiría complejidad prematura antes de `LAB-129` y `LAB-59`. |
