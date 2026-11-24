# Tasks: Contratos HTTP con OpenAPI y pruebas de contrato

**Input**: Design documents from `/specs/LAB-106-http-openapi-contracts/`  
**Prerequisites**: [plan.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/plan.md), [spec.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/spec.md), [research.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/research.md), [data-model.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/data-model.md), [contracts/](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/contracts/), [quickstart.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/quickstart.md)

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Ejecutable en paralelo (archivos independientes, sin dependencias mutuas)
- **[Story]**: Historia de usuario correspondiente (`US1`..`US6`)
- Rutas explícitas para cada repositorio (`shoplab-contracts/` o `shoplab-helpdesk/`)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Preparación de directorios de contratos, configuración de registros GitHub Packages y validación de dependencias automáticas.

- [x] T001 Verificar rama de trabajo en `../shoplab-contracts` y crear la estructura `shoplab-contracts/openapi/chat/v1/`, `shoplab-contracts/openapi/retrieval/v1/` y `shoplab-contracts/openapi/eval/v1/`
- [x] T002 [P] Actualizar `frontend/.npmrc` en `shoplab-helpdesk` manteniendo `engine-strict=true` y configurando `@leandroamariles:registry=https://npm.pkg.github.com` con autenticación por variable de entorno (sin secretos embebidos)
- [x] T003 [P] Verificar en `.github/dependabot.yml` de `shoplab-helpdesk` la cobertura de `github-npm` para `/frontend` y `github-maven` para `/backend`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Esquema común de error alineado con el backend real, infraestructura Maven de Spring Cloud Contract (`WEBTESTCLIENT`) y permisos de paquetes en CI.

**⚠️ CRITICAL**: Ninguna historia de usuario puede completarse antes de cerrar esta fase.

- [x] T004 Añadir de forma aditiva el esquema `ApiErrorResponse` (`code`, `message`, `detail` nullable) en `shoplab-contracts/openapi/common/v1/common-api.yaml` sin modificar `HealthStatus`, `PingResponse`, `ErrorDto` ni el `ErrorResponse` semilla
- [x] T005 Actualizar `backend/pom.xml` en `shoplab-helpdesk`: importar BOM `org.springframework.cloud:spring-cloud-contract-dependencies:5.0.3` y `io.rest-assured:rest-assured-bom:6.3.0` (compatible Spring Framework 7), añadir dependencias de test (`spring-cloud-starter-contract-verifier`, `spring-cloud-starter-contract-stub-runner`, `io.rest-assured:spring-web-test-client`), configurar `spring-cloud-contract-maven-plugin` (`testMode=WEBTESTCLIENT`, `contractsDirectory`, `baseClassMappings` para `retrieval` y `chat`), añadir `<distributionManagement>` hacia GitHub Packages (`https://maven.pkg.github.com/leandroAmariles/shoplab-helpdesk`) y excluir `target/generated-test-sources/contracts` en Checkstyle y PMD
- [x] T006 [P] Actualizar `.github/workflows/ci.yml` en `shoplab-helpdesk` para añadir `permissions: packages: read` y credenciales de GitHub Packages en los jobs `backend` (Maven server auth) y `frontend` (`setup-node` con `registry-url: 'https://npm.pkg.github.com'`, `scope: '@leandroamariles'`, `NODE_AUTH_TOKEN: ${{ secrets.GITHUB_TOKEN }}`)

**Checkpoint**: Base lista — contratos comunes, toolchain de verificación Spring Cloud Contract y permisos de CI operativos.

---

## Phase 3: User Story 1 - Especificaciones OpenAPI en `shoplab-contracts/openapi/` para todas las APIs HTTP publicadas (Priority: P1) 🎯 MVP

**Goal**: Publicar las especificaciones OpenAPI 3.1.0 autoritativas de `chat`, `retrieval` y `eval`, formalizar la convención al nacer para `catalog`, `orders` y `tickets`, y generar los modelos Java correspondientes sin lógica de negocio.

**Independent Test**: Validar sintaxis OpenAPI y compilar `shoplab-contracts/generators/java` verificando correspondencia exacta con todos los controladores activos del backend y pasando `verify-contracts-purity.sh`.

### Implementation for User Story 1

- [x] T007 [P] [US1] Crear `shoplab-contracts/openapi/chat/v1/chat-api.yaml` a partir de [chat-api.yaml](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/contracts/chat-api.yaml) (`POST /api/chat` con `text/event-stream`, payloads SSE y cabeceras `X-Prompt-*`, `PUT/GET /api/chat/feedback*`, `POST /api/chat/feedback/{turnId}/promote`, `GET /api/assistant/settings` y respuestas `ApiErrorResponse`)
- [x] T008 [P] [US1] Crear `shoplab-contracts/openapi/retrieval/v1/retrieval-api.yaml` a partir de [retrieval-api.yaml](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/contracts/retrieval-api.yaml) (`POST /api/documents` multipart `201`/`200`/`413`/`415`/`422`, `GET /api/documents`, `GET /api/knowledge/search` con `200` y `422 SLHD_RETR_00009`)
- [x] T009 [P] [US1] Crear `shoplab-contracts/openapi/eval/v1/eval-api.yaml` a partir de [eval-api.yaml](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/contracts/eval-api.yaml) (`POST/GET /api/eval/runs*`, `GET /api/eval/settings`, `GET /api/eval/history/*` con `X-Eval-Token`, `additionalProperties: false` y nomenclatura `snake_case` exacta)
- [x] T010 [P] [US1] Crear `shoplab-contracts/openapi/README.md` y enlazarlo desde `shoplab-contracts/README.md` documentando la estructura por servicio y la regla obligatoria de alta de `openapi/<servicio>/v1/<servicio>-api.yaml` al nacer para `catalog`, `orders` y `tickets` en F3/F4
- [x] T011 [US1] Actualizar `shoplab-contracts/generators/java/pom.xml` añadiendo ejecuciones de `openapi-generator-maven-plugin` para `chat`, `retrieval` y `eval` (`com.shoplab.contracts.{chat,retrieval,eval}.model`) con `generateApis=false` y cero lógica de negocio
- [x] T012 [US1] Ejecutar `mvn clean compile -B` en `shoplab-contracts/generators/java` y `bash scripts/verify-contracts-purity.sh` en `shoplab-contracts` para validar todas las especificaciones OpenAPI y la pureza del paquete

**Checkpoint**: US1 completada — todas las APIs publicadas tienen contrato OpenAPI 3.1 validado y modelos Java compilando en limpio.

---

## Phase 4: User Story 2 - Publicación de `GET /api/assistant/settings` en `chat` para consumo HTTP desde `eval` (Priority: P1)

**Goal**: Exponer por HTTP en `chat` la configuración activa del asistente (`topK`, `similarityThreshold`, `maxContextCharacters` y `prompts` con `version` nullable para `DEFAULT`) manteniendo arquitectura hexagonal y sin bloqueo reactivo.

**Independent Test**: Ejecutar las pruebas unitarias y `@WebFluxTest` contra `AssistantSettingsController` comprobando `200 OK`, formato camelCase acorde a `chat-api.yaml`, `version: null` en `DEFAULT` y ejecución limpia con BlockHound.

### Tests for User Story 2 (Test-First) ⚠️

- [x] T013 [P] [US2] Crear test unitario `backend/src/test/java/com/shoplab/helpdesk/chat/application/usecase/GetAssistantSettingsUseCaseTest.java` validando con `StepVerifier` la lectura no bloqueante para prompts `LANGFUSE` versionados y `DEFAULT` (`version == null`)
- [x] T014 [P] [US2] Crear test de controlador `@WebFluxTest` `backend/src/test/java/com/shoplab/helpdesk/chat/infrastructure/in/rest/AssistantSettingsControllerTest.java` (con `@Import(GlobalExceptionHandler.class)`) verificando `200 OK`, esquema camelCase, serialización de `version: null` y mapeo de errores tipados

### Implementation for User Story 2

- [x] T015 [P] [US2] Crear puerto de entrada `backend/src/main/java/com/shoplab/helpdesk/chat/application/port/in/GetAssistantSettingsServicePort.java` y caso de uso `backend/src/main/java/com/shoplab/helpdesk/chat/application/usecase/GetAssistantSettingsUseCase.java` delegando de forma reactiva (`Mono.fromSupplier`) sin I/O ni `.block()`
- [x] T016 [P] [US2] Crear DTO de respuesta `backend/src/main/java/com/shoplab/helpdesk/chat/infrastructure/in/rest/dto/AssistantSettingsResponse.java` y mapper explícito `backend/src/main/java/com/shoplab/helpdesk/chat/infrastructure/in/rest/util/mapper/AssistantSettingsRestMapper.java`
- [x] T017 [US2] Crear controlador `backend/src/main/java/com/shoplab/helpdesk/chat/infrastructure/in/rest/AssistantSettingsController.java` (`@RestController`, `@RequestMapping("/api/assistant/settings")`, `@GetMapping`) delegando exclusivamente en `GetAssistantSettingsServicePort`
- [x] T018 [US2] Registrar manualmente el bean de `GetAssistantSettingsServicePort` en `backend/src/main/java/com/shoplab/helpdesk/chat/config/ChatConfig.java` (`@ConditionalOnBean(AssistantSettingsApi.class)`) preservando intacto `AssistantSettingsApi` en proceso
- [x] T019 [US2] Ejecutar `./mvnw -B -ntp test -Dtest=GetAssistantSettingsUseCaseTest,AssistantSettingsControllerTest` en `backend/` y verificar ejecución en verde con BlockHound activo

**Checkpoint**: US2 completada — `GET /api/assistant/settings` operativo, no bloqueante y validado contra su contrato.

---

## Phase 5: User Story 4 - Gate de compatibilidad OpenAPI con `oasdiff` en el CI de `shoplab-contracts` (Priority: P1)

**Goal**: Asegurar que el CI de `shoplab-contracts` detecta y bloquea cualquier cambio incompatible no declarado como versión mayor y rechaza contratos nuevos con sintaxis OpenAPI inválida.

**Independent Test**: Ejecutar el script y los tests de compatibilidad en `shoplab-contracts` verificando los 4 casos: cambio compatible (pasa), ruptura sin `!:`/`BREAKING CHANGE:` (falla), ruptura marcada con `!:` (pasa) y YAML nuevo inválido (falla).

### Implementation for User Story 4

- [x] T020 [US4] Endurecer `shoplab-contracts/scripts/check-openapi-compatibility.sh`: normalizar `BASE_REF` hacia `origin/<rama>` cuando reciba nombre de rama corto (`main` → `origin/main`), eliminar `|| true` en la validación de especificaciones nuevas para fallar ante sintaxis inválida y mantener el bloqueo ante `oasdiff breaking` sin `!:` ni `BREAKING CHANGE:`
- [x] T021 [US4] Actualizar `shoplab-contracts/.github/workflows/ci.yml` para pasar `BASE_REF` normalizado y `PR_TITLE`, y ejecutar dry-run de generación y compilación multi-spec para Java y npm
- [x] T022 [P] [US4] Añadir o ampliar los tests y fixtures en `shoplab-contracts/tests/compatibility/` cubriendo cambio compatible aditivo, breaking change sin marca mayor (fallo), breaking change con `!:` / `BREAKING CHANGE:` (aprobado) y especificación nueva sintácticamente inválida (fallo)
- [x] T023 [US4] Ejecutar la suite unitaria de compatibilidad (`python -m unittest discover -s tests/compatibility -v`) en `shoplab-contracts` confirmando todos los escenarios (el binario `oasdiff` corre en el job Linux de CI al no estar instalado localmente en Windows)

**Checkpoint**: US4 completada — compuerta `oasdiff` estricta activa para todas las especificaciones de `openapi/`.

---

## Phase 6: User Story 3 - Cliente Angular generado desde OpenAPI, publicado como paquete npm y consumido por `frontend/` (Priority: P1)

**Goal**: Generar en `@leandroamariles/shoplab-contracts` todos los modelos TypeScript y clientes Angular de `common`, `chat`, `retrieval` y `eval`, publicarlo en GitHub Packages y migrar `frontend/src/app/` eliminando todos los DTOs del backend escritos a mano.

**Independent Test**: Construir el paquete npm en `shoplab-contracts`, consumirlo en `frontend/` comprobando cero interfaces wire del backend escritas a mano y ejecutar con éxito `npm run lint`, `npm test -- --watch=false` y `npm run build`.

### Implementation for User Story 3

- [x] T024 [US3] Crear `shoplab-contracts/generators/npm/scripts/generate-all.mjs` y actualizar `shoplab-contracts/generators/npm/package.json` y `tsconfig.json` para ejecutar `openapi-generator-cli` (`typescript-angular`) sobre `common`, `chat`, `retrieval` y `eval` en `src/{common,chat,retrieval,eval}`, generar barrel `src/index.ts` y ejecutar `npm run generate` automáticamente dentro de `npm run build`
- [x] T025 [US3] Verificar `shoplab-contracts/.github/workflows/publish.yml` asegurando que `publish-npm` ejecuta `npm install && npm run build && npm publish` regenerando los clientes desde `openapi/` en cada release
- [x] T026 [US3] Publicar `@leandroamariles/shoplab-contracts@1.2.0` en GitHub Packages, fijar la versión exacta en `frontend/package.json`, actualizar `frontend/package-lock.json` vía `npm install` y validar `npm ci && npm run lint && npm test -- --watch=false && npm run build`
- [x] T027 [P] [US3] Refactorizar `frontend/src/app/core/api/api-error.interceptor.ts` y `frontend/src/app/core/api/api-error.interceptor.spec.ts` para tipar el cuerpo de error HTTP con `ApiErrorResponse` importado de `@leandroamariles/shoplab-contracts`
- [x] T028 [P] [US3] Refactorizar `frontend/src/app/features/chat/chat-feedback.client.ts` y `frontend/src/app/features/chat/chat-feedback.client.spec.ts` eliminando `FeedbackSubmitResponse` manual y usando `FeedbackResponse` / `SubmitFeedbackRequest` generados
- [x] T029 [P] [US3] Refactorizar `frontend/src/app/features/chat/chat-stream.client.ts`, `frontend/src/app/features/chat/chat-message.ts` y `frontend/src/app/features/chat/chat-stream.client.spec.ts` importando `ChatRequest`, `ChatSource` y los payloads SSE (`ChatSseSourcesPayload`, `ChatSseTokenPayload`, `ChatSseDonePayload`, `ChatSseErrorPayload`) desde `@leandroamariles/shoplab-contracts`, manteniendo el lector incremental `fetch`/`ReadableStream`
- [x] T030 [P] [US3] Refactorizar `frontend/src/app/features/documents/document.ts`, `frontend/src/app/features/documents/documents-api.client.ts` y `frontend/src/app/features/documents/documents-api.client.spec.ts` usando `DocumentSummaryResponse`, `DocumentUploadResponse` y `DocumentStatus` generados (conservando solo `UploadItem` y `UploadState` de estado local UI)
- [x] T031 [P] [US3] Refactorizar `frontend/src/app/features/eval/eval-api.client.ts`, `frontend/src/app/features/eval/eval-history.ts` y sus specs (`eval-api.client*.spec.ts`) eliminando las 12 interfaces `Wire*` escritas a mano y mapeando desde `RunListResponse`, `RunDetailResponse` y `RunComparisonResponse` generados
- [x] T032 [P] [US3] Refactorizar `frontend/src/app/features/eval/feedback-promotion/feedback-api.client.ts` y `feedback-api.client.spec.ts` sustituyendo `FeedbackListItem`, `FeedbackListResult` y `PromoteFeedbackDraft` por `FeedbackItemResponse`, `FeedbackListResponse` y `PromoteFeedbackRequest` generados
- [x] T033 [US3] Auditar `frontend/src/app/` comprobando que no permanecen interfaces o tipos DTO de petición, respuesta, eventos SSE ni error del backend escritos a mano
- [x] T034 [US3] Ejecutar `npm run lint`, `npm test -- --watch=false` y `npm run build` en `frontend/` verificando que todas las comprobaciones pasan en verde (48 archivos, 627 tests)

**Checkpoint**: US3 completada — paquete `@leandroamariles/shoplab-contracts@1.2.0` publicado y consumido en `frontend/`.

---

## Phase 7: User Story 5 - Pruebas de contrato dirigidas por el consumidor (Spring Cloud Contract) para `chat → retrieval` y `eval → chat` (Priority: P1)

**Goal**: Implementar contratos Spring Cloud Contract separados por productor (`retrieval` y `chat`), verificarlos con `WEBTESTCLIENT`, publicar los stubs en GitHub Packages y validar los clientes HTTP consumidores mediante `StubRunner`.

**Independent Test**: Ejecutar `./mvnw -B -ntp clean install` (verificación de productor + generación de `helpdesk-backend-stubs.jar`) y `./mvnw -B -ntp test -Dtest="*ContractConsumerTest"` validando tanto modo `LOCAL` como resolución `REMOTE` desde GitHub Packages sin levantar el productor real.

### Contratos DSL y Clases Base del Productor

- [x] T035 [P] [US5] Crear contrato `backend/src/test/resources/contracts/retrieval/shouldReturnKnowledgeSearchResults.yml` según [spring-cloud-contracts.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/contracts/spring-cloud-contracts.md)
- [x] T036 [P] [US5] Crear contrato `backend/src/test/resources/contracts/retrieval/shouldReturnEmptyKnowledgeSearchResults.yml` según [spring-cloud-contracts.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/contracts/spring-cloud-contracts.md)
- [x] T037 [P] [US5] Crear contrato `backend/src/test/resources/contracts/retrieval/shouldRejectBlankQueryWith422.yml` validando respuesta `422` con código `SLHD_RETR_00009`
- [x] T038 [P] [US5] Crear contrato `backend/src/test/resources/contracts/chat/shouldReturnAssistantSettings.yml` validando configuración de recuperación y prompts `LANGFUSE` + `DEFAULT` (`version: null`)
- [x] T039 [P] [US5] Crear clase base `backend/src/test/java/com/shoplab/helpdesk/retrieval/infrastructure/in/rest/RetrievalContractBase.java` configurando `RestAssuredWebTestClient` con `KnowledgeSearchController` y `GlobalExceptionHandler`
- [x] T040 [P] [US5] Crear clase base `backend/src/test/java/com/shoplab/helpdesk/chat/infrastructure/in/rest/ChatContractBase.java` configurando `RestAssuredWebTestClient` con `AssistantSettingsController` y `GlobalExceptionHandler`

### Adaptadores Consumidores HTTP y Tests con Stub Runner

- [x] T041 [P] [US5] Implementar adaptador reactivo `backend/src/main/java/com/shoplab/helpdesk/chat/infrastructure/out/knowledge/HttpKnowledgeSearchClient.java` (usando `WebClient`, mapeando `200 OK` → `List<ContextFragment>` y errores `422`/`5xx` → `KnowledgeRetrievalException`) y registrarlo como bean opt-in (`shoplab.retrieval.client.mode=http`)
- [x] T042 [P] [US5] Implementar adaptador reactivo `backend/src/main/java/com/shoplab/helpdesk/eval/infrastructure/out/chat/HttpAssistantSettingsClient.java` (usando `WebClient`, mapeando `200 OK` → `AssistantSettingsSnapshot` con `version` nullable) y registrarlo como bean opt-in (`shoplab.chat.settings.client.mode=http`)
- [x] T043 [P] [US5] Crear test de consumidor `backend/src/test/java/com/shoplab/helpdesk/chat/infrastructure/out/knowledge/ChatRetrievalContractConsumerTest.java` usando Spring Cloud Contract Stub Runner (`AUTO`/`LOCAL`/`REMOTE`) cubriendo hits, lista vacía y `422 SLHD_RETR_00009`
- [x] T044 [P] [US5] Crear test de consumidor `backend/src/test/java/com/shoplab/helpdesk/eval/infrastructure/out/chat/EvalChatContractConsumerTest.java` usando Spring Cloud Contract Stub Runner validando `LANGFUSE` y `DEFAULT` (`version: null`)
- [x] T045 [US5] Crear workflow `.github/workflows/publish-stubs.yml` en `shoplab-helpdesk` para verificar contratos del productor y publicar `com.shoplab:helpdesk-backend:<version>:stubs` en GitHub Packages en `main`/release, y validar en local `install` de stubs + `test -Dtest="*ContractConsumerTest" -Dshoplab.contracts.stubs-mode=LOCAL`

**Checkpoint**: US5 completada — contratos productor/consumidor verificados en `WEBTESTCLIENT` y `StubRunner`, con publicación de stubs lista para extracción temprana.

---

## Phase 8: User Story 6 - Documentación en `docs/fases/` sobre versionado y retiro de APIs (Priority: P2)

**Goal**: Documentar cuándo cambia de versión una API HTTP (`patch`, `minor`, `major`), cómo coexisten versiones y cómo se retira una versión anterior de forma segura.

**Independent Test**: Revisar el documento en `docs/fases/` verificando cobertura completa de criterios SemVer, convivencia y pasos de retiro (*sunset*), más validez de enlaces relativos con `docs/contratos.md`.

### Implementation for User Story 6

- [x] T046 [US6] Redactar `docs/fases/F5-contratos-http-versionado-y-retiro.md` cubriendo: matriz `patch`/`minor`/`major` alineada con `oasdiff`, convivencia (expand-and-contract y versionado por ruta/paquete), y procedimiento de retiro en 6 pasos (`deprecated: true` en OpenAPI, cabeceras `Deprecation`/`Sunset`, migración de consumidores vía Dependabot, verificación de tráfico cero en Prometheus/logs y eliminación en versión mayor)
- [x] T047 [P] [US6] Añadir referencia cruzada bidireccional en `docs/contratos.md` hacia `docs/fases/F5-contratos-http-versionado-y-retiro.md` y verificar que todos los enlaces Markdown resuelven correctamente

**Checkpoint**: US6 completada — política operativa de evolución y retiro de APIs documentada en `docs/fases/`.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Pruebas HTTP manuales/reproducibles, verificación Modulith, compuerta completa de calidad y trazabilidad final de LAB-106.

- [x] T048 Generar `specs/LAB-106-http-openapi-contracts/requests.http` cubriendo `GET /api/assistant/settings` y los escenarios contractuales de `GET /api/knowledge/search` (éxito, lista vacía y `422 SLHD_RETR_00009`)
- [x] T049 Ejecutar `./mvnw -B -ntp test -Dtest=ModularityTests` en `backend/` y verificar que `docs/modulos/` permanece sincronizado sin cambios pendientes
- [x] T050 Ejecutar Checkstyle, PMD, tests de arquitectura, unitarios, `ChatTest`, `RetrievalTest` y `*ContractConsumerTest` en `backend/` (nota: los tests de integración con Testcontainers/PostgreSQL en `clean verify` requieren Docker activo en el runner CI)
- [x] T051 [P] Ejecutar la validación local de [quickstart.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/quickstart.md) en `shoplab-contracts`, `frontend/` y `backend/`, y revisar `git status --short` en ambos repositorios para asegurar ausencia de archivos temporales o basura
- [x] T052 Verificar trazabilidad 100 % de los criterios de éxito `SC-001`–`SC-007` de [spec.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/spec.md)
