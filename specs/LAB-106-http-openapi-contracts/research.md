# Research & Technical Decisions: Contratos HTTP con OpenAPI y pruebas de contrato (LAB-106)

**Feature**: `feature/LAB-106-http-openapi-contracts`  
**Date**: 2026-11-24  
**Spec**: [spec.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/spec.md)

Este documento consolida la investigación técnica realizada sobre `shoplab-helpdesk` y `shoplab-contracts` para resolver todas las decisiones de diseño de la fase 0 sin ambigüedades pendientes.

---

## R1. Estructura OpenAPI 3.1 en `shoplab-contracts/openapi/` y alineación de `ErrorResponse`

- **Decisión**:
  - Publicar una especificación OpenAPI 3.1.0 independiente por cada API HTTP activa:
    - `openapi/chat/v1/chat-api.yaml`
    - `openapi/retrieval/v1/retrieval-api.yaml`
    - `openapi/eval/v1/eval-api.yaml`
  - Establecer la convención obligatoria `openapi/<servicio>/v1/<servicio>-api.yaml` para los servicios futuros `catalog`, `orders` y `tickets` (que hoy están vacíos y nacerán directamente como servicios en F3/F4 según ADR-0031 / LAB-128).
  - Añadir de forma aditiva en `openapi/common/v1/common-api.yaml` el esquema `ApiErrorResponse` (`code`, `message`, `detail` opcional nullable) y referenciarlo (o incluirlo alineado) en todas las respuestas 4xx/5xx de `chat`, `retrieval` y `eval`.
- **Justificación**:
  - En el backend real (`GlobalExceptionHandler.java` + `ErrorResponse.java`), todas las respuestas de error HTTP devuelven `{ "code": "SLHD_...", "message": "...", "detail": "..." }` (`detail` omitido cuando es `null` por `@JsonInclude(NON_NULL)`), y el interceptor Angular `apiErrorInterceptor` ya parsea `{ code, message }`.
  - Sin embargo, la semilla inicial `openapi/common/v1/common-api.yaml` (v1.0.0/1.1.0 de LAB-108) definió `ErrorResponse` con forma `{ data, errors: [ErrorDto] }`.
  - Añadir un nuevo esquema `ApiErrorResponse` (sin eliminar ni romper el `ErrorResponse` / `ErrorDto` semilla de `common-api.yaml`) evita una ruptura artificial de versión mayor en `common-api.yaml`, permite publicar el cambio como incremento `minor` en `shoplab-contracts` y garantiza que `chat`, `retrieval` y `eval` reflejen exactamente el JSON real de `GlobalExceptionHandler`.
- **Alternativas descartadas**:
  - Modificar `ErrorResponse` dentro de `common-api.yaml` en el lugar: generaría una ruptura `oasdiff` sobre `common-api.yaml` sin beneficio para los consumidores.
  - Cambiar `GlobalExceptionHandler` para envolver todos los errores en `{ data, errors }`: rompería retrocompatibilidad innecesariamente con clientes y pruebas existentes del backend y frontend.

---

## R2. Diseño de `GET /api/assistant/settings` en el módulo `chat`

- **Decisión**:
  - Crear `AssistantSettingsController` en `com.shoplab.helpdesk.chat.infrastructure.in.rest` mapeado a `@RequestMapping("/api/assistant/settings")` con método `@GetMapping`.
  - Respetar el Principio Constitucional II (Arquitectura Hexagonal): el controlador únicamente delega en un puerto de entrada (`GetAssistantSettingsServicePort`) implementado por un caso de uso ligero que lee de `AssistantSettingsApi` / `PromptCatalogPort` + `RagProperties`, y mapea al DTO `AssistantSettingsResponse` mediante `AssistantSettingsRestMapper`.
  - La operación retorna `Mono<AssistantSettingsResponse>` con `Mono.fromSupplier(...)` sin ejecutar I/O bloqueante ni consultar base de datos o Langfuse durante la petición.
  - Convención JSON **camelCase** coherente con todos los demás controladores del módulo `chat` (`ChatController`, `FeedbackController`) y los campos del record `AssistantSettings`:
    - `topK` (integer)
    - `similarityThreshold` (number/double)
    - `maxContextCharacters` (integer)
    - `prompts`: lista de `{ name: string, version: integer | null, source: "LANGFUSE" | "DEFAULT" }`
  - Mantener `AssistantSettingsApi` y `ChatAssistantSettingsAdapter` en proceso como implementación por defecto mientras `chat` y `eval` compartan monolito, habilitando el adaptador HTTP `HttpAssistantSettingsClient` para las pruebas de contrato y la futura extracción de `eval-service` (`LAB-130`).
- **Justificación**:
  - Mantiene coherencia de estilo JSON dentro de `chat` (camelCase), respeta la regla de no usar `.block()` (BlockHound-safe) y preserva la encapsulación de módulo requerida por `ModularityTests` (`chat.api` sigue siendo la API pública del módulo).
- **Alternativas descartadas**:
  - Exponer directamente el DTO `AssistantSettings` de `chat.api` en el controlador REST: mezclaría la frontera de API inter-módulo Java con la capa de transporte HTTP, violando la separación hexagonal.

---

## R3. Generación multi-spec en `shoplab-contracts` y migración de `frontend/` (Angular 22)

- **Decisión**:
  - Extender `shoplab-contracts/generators/npm/package.json` y `tsconfig.json` para generar módulos separados por especificación (`src/common`, `src/chat`, `src/retrieval`, `src/eval`) con `@openapitools/openapi-generator-cli` (`typescript-angular`, `ngVersion=22.0.0` o compatible Angular >= 18, `supportsES6=true`) y generar un punto de entrada `src/index.ts` que re-exporte por namespace (`common`, `chat`, `retrieval`, `evalApi`) además de los tipos y clientes sin colisiones.
  - Actualizar `generators/npm` `build` para ejecutar la generación antes de `tsc -p tsconfig.json` (y asegurar en `ci.yml` y `publish.yml` de `shoplab-contracts` que `npm run generate && npm run build` corre en cada build/publicación).
  - Extender `shoplab-contracts/generators/java/pom.xml` agregando ejecuciones de `openapi-generator-maven-plugin` (`library=native`, `generateApis=false`, `useJakartaEe=true`) para generar los modelos Java de `chat`, `retrieval` y `eval` en `com.shoplab.contracts.{chat,retrieval,eval}.model` con cero lógica de negocio.
  - En `frontend/`:
    - Añadir `frontend/.npmrc` configurando el registro de `@leandroamariles` hacia `https://npm.pkg.github.com`.
    - Fijar la dependencia exacta `@leandroamariles/shoplab-contracts` en `frontend/package.json` (sin rangos `^`/`~`).
    - Eliminar todas las interfaces DTO del backend escritas a mano en:
      - `frontend/src/app/features/chat/chat-feedback.client.ts` (reemplazar `FeedbackSubmitResponse` y cuerpo inline por `FeedbackResponse` y `SubmitFeedbackRequest`).
      - `frontend/src/app/features/chat/chat-stream.client.ts` y `chat-message.ts` (importar `ChatRequest`, `ChatSource`, `ChatSseSourcesPayload`, `ChatSseTokenPayload`, `ChatSseDonePayload`, `ChatSseErrorPayload` y `ApiErrorResponse` desde `@leandroamariles/shoplab-contracts`, conservando el lector `fetch`/`ReadableStream` para entrega incremental SSE).
      - `frontend/src/app/features/documents/document.ts` y `documents-api.client.ts` (importar `DocumentSummaryResponse`, `DocumentUploadResponse`, `DocumentStatus`; conservar únicamente `UploadItem` y `UploadState`, que son estado local de UI).
      - `frontend/src/app/features/eval/eval-api.client.ts` (eliminar las 12 interfaces `Wire*` escritas a mano y usar los tipos generados `RunListResponse`, `RunDetailResponse`, `RunComparisonResponse` y sus subtipos).
      - `frontend/src/app/features/eval/feedback-promotion/feedback-api.client.ts` (reemplazar `FeedbackListItem`, `FeedbackListResult` y `PromoteFeedbackDraft` por `FeedbackItemResponse`, `FeedbackListResponse` y `PromoteFeedbackRequest`).
      - `frontend/src/app/core/api/api-error.interceptor.ts` (usar `ApiErrorResponse` generado para tipar la lectura del cuerpo de error HTTP).
- **Justificación**:
  - `frontend/` utiliza Angular `22.2.6`, TypeScript `~6.0.2`, RxJS `~7.8.0` y Vitest `^5.1.0`. Generar modelos/servicios con `typescript-angular` en subcarpetas por dominio evita colisiones entre esquemas con nombres parecidos (p. ej. `PromptPart` o `ApiErrorResponse` compartidos) y mantiene `frontend/src/app/` 100 % libre de DTOs escritos a mano.
  - Mantener el lector `fetch` + `ReadableStream` en `ChatStreamClient` es necesario porque `HttpClient` estándar de Angular no expone chunks incrementales SSE de `POST` con cancelación inmediata de stream (`AbortController`), mientras que tipar los payloads SSE y `ChatRequest` desde OpenAPI cumple el criterio de que ningún tipo del backend se escriba a mano.

---

## R4. Endurecimiento de `oasdiff` en el CI de `shoplab-contracts`

- **Decisión**:
  - Corregir `shoplab-contracts/scripts/check-openapi-compatibility.sh` y `.github/workflows/ci.yml`:
    1. Eliminar `|| true` en la validación de especificaciones nuevas sin línea base (`oasdiff changelog "$spec" "$spec"` o validación explícita OpenAPI con salida != 0 si el archivo es inválido).
    2. Normalizar `BASE_REF` en PRs: cuando `github.base_ref` vale `main`, resolver a `origin/main` (anteponiendo `origin/` si no contiene `/`) para que `git cat-file -e "${BASE_REF}:${spec}"` siempre encuentre el contrato base del target branch en el runner de GitHub Actions.
    3. Validar explícitamente `breaking` con `oasdiff breaking "$BASE_FILE" "$spec" --fail-on ERR` (o comprobar código de salida y salida textual) y exigir `!:` en el título del PR o `BREAKING CHANGE:` en el commit/PR body antes de permitir una ruptura.
- **Justificación**:
  - En la revisión de `shoplab-contracts/scripts/check-openapi-compatibility.sh` (líneas 41–59) y `.github/workflows/ci.yml` (línea 41) se detectó que `github.base_ref` en PRs vale `main` (no `origin/main`) y que la rama `else` para contratos nuevos silenciaba errores con `|| true`. Corregir ambos puntos asegura que ningún YAML inválido ni ninguna ruptura no declarada pueda fusionarse.

---

## R5. Spring Cloud Contract 5.x con Spring Boot 4.1.2 y WebFlux (`WEBTESTCLIENT`)

- **Decisión**:
  - Incorporar el BOM `org.springframework.cloud:spring-cloud-dependencies` versión **`2025.1.3`** (release train *Oakwood*, compatible con Spring Boot `4.0.x`/`4.1.x`, que provee Spring Cloud Contract `5.0.x`).
  - Usar `spring-cloud-starter-contract-verifier`, `spring-cloud-starter-contract-stub-runner` (en `scope=test`) y `spring-cloud-contract-maven-plugin` con:
    - `<testMode>WEBTESTCLIENT</testMode>` (usa `RestAssuredWebTestClient` de forma no bloqueante, compatible con WebFlux y BlockHound).
    - Contratos en **YAML DSL** bajo carpetas separadas por productor:
      - `backend/src/test/resources/contracts/retrieval/`
      - `backend/src/test/resources/contracts/chat/`
    - `<baseClassMappings>` para vincular cada carpeta a su clase base dedicada:
      - `.*retrieval.*` -> `com.shoplab.helpdesk.retrieval.infrastructure.in.rest.RetrievalContractBase`
      - `.*chat.*` -> `com.shoplab.helpdesk.chat.infrastructure.in.rest.ChatContractBase`
    - Cada `*ContractBase` configura `RestAssuredWebTestClient.webTestClient(WebTestClient.bindToController(controller).controllerAdvice(new GlobalExceptionHandler()).build())` con puertos in-bound stubbeados mediante Mockito, probando serialización real, cabeceras, códigos HTTP y traducción real de excepciones en `GlobalExceptionHandler`.
  - **Corrección crítica de código HTTP en `retrieval` (`422` vs `400`)**:
    - En `ResponseCodeEnum.java` (línea 48), `SEARCH_QUERY_INVALID("SLHD_RETR_00009", HttpStatus.UNPROCESSABLE_CONTENT, "The search query must not be blank")` devuelve **HTTP `422`**, no `400`. Tanto `retrieval-api.yaml` como el contrato Spring Cloud Contract (`shouldRejectBlankQueryWith422.yml`) deben especificar estado `422` y código `SLHD_RETR_00009` para reflejar el comportamiento real del productor.
  - **Compatibilidad con Checkstyle y PMD**:
    - El `pom.xml` actual ejecuta Checkstyle (`includeTestSourceDirectory=true`) y PMD (`includeTests=true`). Para evitar fallos sobre las clases Java autogeneradas por `spring-cloud-contract-maven-plugin` en `target/generated-test-sources/contracts`, se configurarán exclusiones explícitas en ambos plugins (p. ej. `excludeRoots` / `excludes` para `generated-test-sources/contracts`) manteniendo el análisis estricto sobre todo el código fuente y de test escrito manualmente.
  - **Empaquetado y publicación de stubs en GitHub Packages**:
    - Configurar `<distributionManagement>` en `backend/pom.xml` hacia `https://maven.pkg.github.com/leandroAmariles/shoplab-helpdesk` y generar el artefacto `com.shoplab:helpdesk-backend:<version>:stubs` en fase `package`/`verify`.
    - Añadir workflow `.github/workflows/publish-stubs.yml` (disparado en `push` a `main` con cambios en `backend/**` o `release: published`) que ejecute la verificación de contratos y publique los stubs en GitHub Packages (`mvn deploy` / publicación específica del artefacto stubs y su POM).
- **Transición a extracción temprana (`LAB-129`, `LAB-59`, `LAB-130`)**:
  - Al mantener los contratos agrupados por carpeta de productor (`contracts/retrieval/` y `contracts/chat/`) y clases base independientes por módulo, cuando `LAB-129` cree `shoplab-retrieval-service` y `LAB-59` cree `shoplab-chat-service`, cada repo heredará directamente su carpeta de contratos, su clase `*ContractBase` y publicará `com.shoplab:shoplab-<servicio>-service:<version>:stubs`.

---

## R6. Clientes HTTP consumidores y pruebas con Spring Cloud Contract Stub Runner

- **Decisión**:
  - Implementar adaptadores de salida basados en `WebClient` reactivo (sin `.block()`):
    - `com.shoplab.helpdesk.chat.infrastructure.out.knowledge.HttpKnowledgeSearchClient` -> `GET /api/knowledge/search?query=...&limit=...`
    - `com.shoplab.helpdesk.eval.infrastructure.out.chat.HttpAssistantSettingsClient` -> `GET /api/assistant/settings`
  - Activarlos de forma condicional mediante propiedad (`@ConditionalOnProperty` p. ej. `shoplab.retrieval.client.mode=http` / `shoplab.chat.settings.client.mode=http`, con valor por defecto `in-process`) para no alterar el comportamiento actual del monolito ni los tests de `ModularityTests` mientras `chat`, `retrieval` y `eval` sigan en proceso, dejando listos los adaptadores de red para `LAB-129`/`LAB-59`/`LAB-130`.
  - Crear tests de consumidor con Spring Cloud Contract Stub Runner (`@ExtendWith` / `@AutoConfigureStubRunner` con puerto dinámico `ids = "com.shoplab:helpdesk-backend:+:stubs:0"`):
    - `ChatRetrievalContractConsumerTest` (verifica `HttpKnowledgeSearchClient` contra los stubs de `retrieval`: resultados ordenados, lista vacía y traducción de `422 SLHD_RETR_00009` a `KnowledgeRetrievalException`).
    - `EvalChatContractConsumerTest` (verifica `HttpAssistantSettingsClient` contra los stubs de `chat`: configuración de recuperación y prompts `LANGFUSE` + `DEFAULT` con `version: null`).
  - Configurar `stubsMode` parametrizable (`LOCAL` en desarrollo y primera pasada de Maven; `REMOTE` apuntando a `https://maven.pkg.github.com/leandroAmariles/shoplab-helpdesk` en CI/verificación remota) para validar el consumo real desde GitHub Packages sin depender de red externa en compilaciones offline.

---

## R7. Autenticación GitHub Packages en CI (`shoplab-helpdesk`) y `ModularityTests`

- **Decisión**:
  - Actualizar `.github/workflows/ci.yml`:
    - En el job `frontend`: añadir `permissions: packages: read`, configurar `actions/setup-node@v4` (o `v7`) con `registry-url: 'https://npm.pkg.github.com'` y `scope: '@leandroamariles'`, y exportar `NODE_AUTH_TOKEN: ${{ secrets.GITHUB_TOKEN }}` en `npm ci`.
    - En el job `backend`: añadir `permissions: packages: read` y configurar `actions/setup-java` con credenciales de servidor Maven para `github-shoplab-contracts` y `github` usando `GITHUB_TOKEN` (más `settings.xml` si es necesario para múltiples server-ids).
  - Ejecutar `./mvnw test -Dtest=ModularityTests` tras añadir los nuevos controladores/adaptadores en `chat` y `eval` para regenerar y commitear `docs/modulos/` si cambia el diagrama de dependencias de módulos.

---

## R8. Guía de versionado y retiro de APIs en `docs/fases/`

- **Decisión**:
  - Crear `docs/fases/F5-contratos-http-versionado-y-retiro.md` como documento oficial de fase F5, complementario a `docs/contratos.md` (LAB-108) y `docs/mapa-servicios.md` (LAB-128).
  - Incluir:
    1. Matriz de decisión SemVer (`patch` vs `minor` vs `major`) con ejemplos concretos de OpenAPI alineados con `oasdiff`.
    2. Reglas de convivencia de versiones (estrategia *expand-and-contract*, versionado por ruta `/v1` vs `/v2` cuando se requiere convivencia simultánea de contratos mayores).
    3. Procedimiento operativo de retiro (*sunset*) en 6 pasos: marcado `deprecated: true` en OpenAPI, cabeceras informativas `Deprecation` y `Sunset`, apertura de PRs de actualización en consumidores vía Dependabot, verificación de tráfico cero en Prometheus/logs (`http_server_requests_seconds_count`), eliminación en versión mayor (`feat!: ...`) y retiro de código/rutas en productor.
