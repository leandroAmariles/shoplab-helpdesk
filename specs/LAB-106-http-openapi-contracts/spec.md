# Feature Specification: Contratos HTTP con OpenAPI y pruebas de contrato

**Feature Branch**: `feature/LAB-106-http-openapi-contracts`

**Created**: 2026-11-24

**Status**: Draft

**Input**: User description: "LAB-106 (F5-14) Contratos HTTP con OpenAPI y pruebas de contrato. Como desarrollador, quiero que las APIs HTTP tengan contrato, para que un cambio en un servicio no rompa al frontend ni a otro servicio sin que el CI lo detecte. Criterios de aceptación: Cada API HTTP publicada (chat, retrieval, eval; catalog, orders y tickets al nacer) tiene su especificación OpenAPI en shoplab-contracts/openapi/; chat publica GET /api/assistant/settings (hoy AssistantSettingsApi en proceso) para que eval la lea por HTTP; El cliente de Angular se genera desde OpenAPI y se publica como paquete npm; el frontend no escribe a mano los tipos del backend; oasdiff en el CI de shoplab-contracts: el PR falla ante un cambio incompatible que no suba la versión mayor; Pruebas de contrato dirigidas por el consumidor (Spring Cloud Contract) para las llamadas de chat a retrieval y de eval a chat: el productor publica sus stubs en GitHub Packages y el repo consumidor los usa en sus tests; Documentado en docs/fases/ cuándo una API cambia de versión y cómo se retira la anterior. Depende de: LAB-108. Va antes de LAB-129 (retrieval-service), LAB-59 (chat-service) y LAB-130 (eval-service). Se adelantó de F5 al bloque de extracción temprana (ADR-0029/ADR-0031, LAB-128); ya no depende de LAB-50 porque OpenAPI no necesita los esquemas Avro."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Especificaciones OpenAPI en `shoplab-contracts/openapi/` para todas las APIs HTTP publicadas (Priority: P1)

Como desarrollador del ecosistema ShopLab Helpdesk, quiero que todas las APIs HTTP publicadas actualmente (`chat`, `retrieval`, `eval`) tengan su especificación OpenAPI 3.1 formal y autoritativa dentro de `shoplab-contracts/openapi/`, y que quede establecida la misma regla obligatoria para `catalog`, `orders` y `tickets` al nacer, para disponer de una única fuente de verdad contractual antes de extraer los módulos a servicios independientes (`LAB-129`, `LAB-59`, `LAB-130`).

**Alcance de contratos publicados**:
- **`chat`**:
  - `POST /api/chat`: flujo conversacional con streaming `text/event-stream` (SSE), cuerpo `ChatRequest`, cabeceras opcionales de override de prompts y esquema de eventos SSE emitidos.
  - `PUT /api/chat/feedback/{turnId}`: registro o actualización de valoración sobre un turno.
  - `GET /api/chat/feedback`: consulta paginada/filtrada de valoraciones (`rating`, `promoted`, `limit`).
  - `POST /api/chat/feedback/{turnId}/promote`: promoción de un turno con valoración negativa al dataset golden.
  - `GET /api/assistant/settings`: exposición HTTP de la configuración activa del asistente (US2).
- **`retrieval`**:
  - `POST /api/documents`: carga `multipart/form-data` de documentos a la base de conocimiento (`201 Created` cuando es nuevo, `200 OK` cuando reemplaza).
  - `GET /api/documents`: listado de documentos indexados y su estado.
  - `GET /api/knowledge/search`: búsqueda semántica de fragmentos (`query`, `limit`).
- **`eval`**:
  - `POST /api/eval/runs`: registro de una corrida de evaluación desde CI (con cabecera de token, `201 Created`).
  - `GET /api/eval/runs/{runId}`: consulta de corrida almacenada para verificación de ingesta (con cabecera de token).
  - `GET /api/eval/settings`: lectura de la configuración activa del asistente a través del módulo `eval` (con cabecera de token).
  - `GET /api/eval/history/runs`: listado histórico para `admin-eval` (`kind`, `origin`, `limit`).
  - `GET /api/eval/history/runs/{runId}`: detalle completo de una corrida con casos y enlace de traza.
  - `GET /api/eval/history/compare`: comparación de métricas entre corrida base y objetivo (`base`, `target`, `metric`, `drop`).
- **Esquema estándar de error**:
  - Todas las respuestas 4xx y 5xx en los contratos reflejan fielmente el cuerpo real devuelto por `GlobalExceptionHandler` (`ErrorResponse`: `code`, `message` y `detail` opcional).
- **Servicios futuros (`catalog`, `orders`, `tickets`)**:
  - Actualmente no tienen endpoints HTTP implementados y nacerán directamente como servicios independientes en F3/F4 (ADR-0031 / LAB-128). Antes de exponer su primer endpoint HTTP, cada servicio MUST publicar su especificación bajo `shoplab-contracts/openapi/<servicio>/v1/`.

**Why this priority**: Sin las especificaciones OpenAPI completas y fieles a las rutas y cargas reales en `shoplab-contracts`, no es posible generar el cliente Angular (US3), ejecutar `oasdiff` sobre las APIs reales (US4) ni extraer `retrieval-service`, `chat-service` o `eval-service` con contratos verificables.

**Independent Test**: Inspeccionar `shoplab-contracts/openapi/`, validar sintácticamente los archivos OpenAPI de `chat`, `retrieval` y `eval`, verificar que cada endpoint HTTP existente en `backend/` y cada parámetro/código de estado coinciden exactamente con su especificación, y comprobar que el build de validación/generación de `shoplab-contracts` procesa todas las especificaciones sin errores.

**Acceptance Scenarios**:

1. **Given** el repositorio `shoplab-contracts`, **When** se inspecciona `openapi/`, **Then** existen las especificaciones OpenAPI 3.1 independientes para `chat`, `retrieval` y `eval` con rutas, parámetros, cabeceras, cuerpos y códigos de respuesta completos (incluyendo respuestas de error `ErrorResponse`).
2. **Given** cualquier controlador REST activo en los módulos `chat`, `retrieval` o `eval` del backend (`ChatController`, `FeedbackController`, `DocumentController`, `KnowledgeSearchController`, `EvalRunController`, `EvalSettingsController`, `EvalHistoryController` y el nuevo endpoint de settings del asistente), **When** se compara su firma HTTP contra la especificación OpenAPI correspondiente, **Then** existe correspondencia exacta de ruta, método HTTP, parámetros obligatorios/opcionales, tipos de datos y códigos de estado.
3. **Given** la regla de nacimiento para los módulos sin implementación HTTP actual (`catalog`, `orders`, `tickets`), **When** se documentan las convenciones de `shoplab-contracts/openapi/`, **Then** queda explícito el directorio y convención (`openapi/<servicio>/v1/<servicio>-api.yaml`) que debe crearse antes de publicar endpoints en F3/F4.
4. **Given** una respuesta de error 4xx o 5xx de cualquier endpoint publicado, **When** se consulta su definición en OpenAPI, **Then** referencia el esquema estándar de error alineado con `ErrorResponse` (`code`, `message` y `detail` opcional cuando aplique).

---

### User Story 2 - Publicación de `GET /api/assistant/settings` en `chat` para consumo HTTP desde `eval` (Priority: P1)

Como desarrollador encargado de preparar la extracción de `chat` y `eval`, quiero que el módulo `chat` exponga públicamente el endpoint HTTP `GET /api/assistant/settings` con la misma información que hoy ofrece `AssistantSettingsApi` en proceso, para que `eval` pueda leer la configuración activa del asistente por red mediante contrato HTTP cuando ambos módulos vivan en servicios separados (`LAB-59` y `LAB-130`).

**Contexto funcional**:
Actualmente `AssistantSettingsApi.current()` entrega de forma síncrona en memoria `topK`, `similarityThreshold`, `maxContextCharacters` y la lista `prompts` (`name`, `version`, `source` con valores `LANGFUSE` o `DEFAULT`, donde `version` es `null` cuando se usa el texto por defecto empaquetado). Al publicarse como `GET /api/assistant/settings`, el endpoint mantiene el carácter de solo lectura sobre el estado en memoria (sin llamadas bloqueantes ni consultas a base de datos o red durante la petición) y pasa a formar parte del contrato OpenAPI de `chat`. Durante la transición previa a `LAB-130`, la interfaz en proceso `AssistantSettingsApi` puede coexistir o adaptarse mediante un adaptador HTTP sin romper el funcionamiento actual de `eval`.

**Why this priority**: Es un criterio explícito de LAB-106 y habilita directamente la prueba de contrato consumidor-productor `eval → chat` (US5) y la extracción posterior de `eval-service` (LAB-130).

**Independent Test**: Realizar una petición HTTP `GET /api/assistant/settings` contra el módulo `chat` (en test de controlador WebFlux / integración) y verificar que responde `200 OK` con JSON válido conforme al contrato OpenAPI de `chat`, tanto cuando los prompts provienen de Langfuse con versión numérica como cuando usan la versión `DEFAULT` con `version: null`.

**Acceptance Scenarios**:

1. **Given** el módulo `chat` con configuración de recuperación activa (`topK`, `similarityThreshold`, `maxContextCharacters`) y prompts servidos desde Langfuse con versión numerada, **When** un consumidor envía `GET /api/assistant/settings`, **Then** el backend responde `200 OK` con los valores vigentes en el formato definido en el contrato OpenAPI de `chat`.
2. **Given** un prompt servido desde la definición por defecto empaquetada en el backend (`source = "DEFAULT"`), **When** se consulta `GET /api/assistant/settings`, **Then** la respuesta incluye la entrada del prompt con `"source": "DEFAULT"` y `"version": null` sin fallos de serialización ni omisiones indebidas.
3. **Given** una invocación concurrente a `GET /api/assistant/settings`, **When** se atiende la petición, **Then** responde de forma no bloqueante leyendo el estado en memoria sin ejecutar I/O adicional hacia PostgreSQL ni Langfuse.

---

### User Story 3 - Cliente Angular generado desde OpenAPI, publicado como paquete npm y consumido por `frontend/` (Priority: P1)

Como desarrollador frontend, quiero que el paquete npm `@leandroamariles/shoplab-contracts` generado desde OpenAPI en `shoplab-contracts` incluya todos los modelos TypeScript y clientes Angular de las APIs publicadas (`chat`, `retrieval`, `eval`), y que `frontend/` consuma ese paquete versionado eliminando todas las interfaces y tipos de DTO del backend escritos a mano, para que cualquier desajuste entre la API del backend y la interfaz web sea detectado en tiempo de compilación en lugar de fallar en ejecución.

**Alcance en `frontend/`**:
- Configuración de dependencia fijada a `@leandroamariles/shoplab-contracts` en `frontend/package.json` (con soporte de registro npm de GitHub Packages y actualización vía Dependabot según `LAB-108`).
- Sustitución de los tipos escritos a mano del backend en:
  - `frontend/src/app/features/chat/` (`chat-feedback.client.ts`, `chat-stream.client.ts` — incluyendo el cuerpo de petición y los payloads de eventos SSE)
  - `frontend/src/app/features/documents/` (`documents-api.client.ts`)
  - `frontend/src/app/features/eval/` (`eval-api.client.ts`, `feedback-api.client.ts`)
  - `frontend/src/app/core/api/` (`api-error.ts` alineado con `ErrorResponse` del contrato)
- El transporte SSE de `POST /api/chat` basado en lectura incremental de stream (`fetch` / `ReadableStream`) puede conservar su lector incremental para no perder entrega token a token en el navegador, pero todos los tipos de petición, eventos SSE, cabeceras y errores que procesa deben importarse exclusivamente del paquete `@leandroamariles/shoplab-contracts`.

**Why this priority**: Elimina la duplicación manual de contratos entre Java y TypeScript/Angular, cumpliendo uno de los criterios centrales de LAB-106 y evitando roturas silenciosas en la SPA.

**Independent Test**: Generar y empaquetar `@leandroamariles/shoplab-contracts` en `shoplab-contracts/generators/npm`, instalarlo en `frontend/`, comprobar que no quedan interfaces/DTOs manuales de petición/respuesta/error del backend en `frontend/src/app/` y ejecutar con éxito `npm run build`, `npm run lint` y `npm test` en `frontend/`.

**Acceptance Scenarios**:

1. **Given** las especificaciones OpenAPI de `chat`, `retrieval`, `eval` y `common` en `shoplab-contracts/openapi/`, **When** se ejecuta la generación del paquete npm `@leandroamariles/shoplab-contracts`, **Then** el paquete publicado en GitHub Packages exporta los modelos TypeScript y servicios/clientes Angular tipados para todas las operaciones HTTP de dichas APIs.
2. **Given** el código fuente de `frontend/src/app/`, **When** se revisan los clientes HTTP (`chat-feedback.client.ts`, `chat-stream.client.ts`, `documents-api.client.ts`, `eval-api.client.ts`, `feedback-api.client.ts`, `api-error.ts`), **Then** todos los DTOs de petición, respuesta, eventos SSE y error se importan desde `@leandroamariles/shoplab-contracts` sin definiciones duplicadas escritas a mano.
3. **Given** el consumo de `@leandroamariles/shoplab-contracts` en `frontend/`, **When** se ejecutan la compilación de producción, el linter y las pruebas unitarias de Angular, **Then** las tres verificaciones pasan sin errores de tipos ni regresiones funcionales.
4. **Given** un cambio hipotético de contrato incompatible en un campo usado por `frontend/` (p. ej. renombrar o eliminar un campo en la respuesta generada), **When** se actualiza la versión del paquete npm y se compila `frontend/`, **Then** el compilador de TypeScript falla de inmediato señalando el sitio afectado.

---

### User Story 4 - Gate de compatibilidad OpenAPI con `oasdiff` en el CI de `shoplab-contracts` (Priority: P1)

Como mantenedor de contratos, quiero que el workflow de CI de `shoplab-contracts` ejecute `oasdiff` sobre todas las especificaciones bajo `openapi/` en cada pull request hacia `main`, bloqueando la fusión ante cualquier cambio incompatible que no declare explícitamente un incremento de versión mayor, para impedir rupturas inadvertidas hacia `frontend/` o hacia otros microservicios consumidores.

**Why this priority**: Sin la compuerta automática en CI sobre los contratos reales de `chat`, `retrieval` y `eval`, la publicación de versiones perdería la garantía de compatibilidad semántica exigida por LAB-106.

**Independent Test**: Simular o abrir en `shoplab-contracts` dos cambios sobre una especificación de servicio (`chat`, `retrieval` o `eval`): (a) añadir un campo opcional nuevo y verificar que el gate `oasdiff` pasa; (b) eliminar un campo requerido o cambiar un tipo sin `!:`/`BREAKING CHANGE:` y verificar que el job de CI falla indicando la ruptura; luego marcar el cambio como mayor y verificar que el gate lo permite.

**Acceptance Scenarios**:

1. **Given** un PR en `shoplab-contracts` que introduce un cambio retrocompatible en `openapi/<servicio>/` (p. ej. nuevo endpoint o campo opcional en una respuesta), **When** se ejecuta el workflow de CI, **Then** `oasdiff breaking` contra `origin/main` no detecta rupturas y el check termina en verde.
2. **Given** un PR en `shoplab-contracts` que introduce un cambio incompatible en `openapi/<servicio>/` (p. ej. eliminar un endpoint o campo, cambiar el tipo de una propiedad o volver obligatorio un parámetro antes opcional) sin indicar incremento mayor (`!:` o `BREAKING CHANGE:`), **When** se ejecuta el workflow de CI, **Then** el job falla con código distinto de cero, bloquea el merge y lista exactamente las incompatibilidades detectadas.
3. **Given** un PR con un cambio incompatible debidamente marcado como breaking change (`!:` en el título/commit o `BREAKING CHANGE:` en la descripción), **When** se ejecuta el workflow de CI, **Then** el gate valida que la ruptura fue declarada conscientemente y permite el flujo de `release-please` hacia una nueva versión mayor.
4. **Given** un archivo OpenAPI nuevo o modificado con errores de sintaxis o referencias `$ref` rotas, **When** corre el workflow de CI en `shoplab-contracts`, **Then** la validación falla explícitamente sin silenciar el error.

---

### User Story 5 - Pruebas de contrato dirigidas por el consumidor (Spring Cloud Contract) para `chat → retrieval` y `eval → chat` con stubs en GitHub Packages (Priority: P1)

Como desarrollador backend, quiero contar con pruebas de contrato dirigidas por el consumidor usando Spring Cloud Contract para las dos integraciones HTTP entre servicios (`chat → retrieval` y `eval → chat`), donde cada productor verifique sus endpoints reales contra los contratos, publique sus stubs en GitHub Packages y cada consumidor ejecute sus tests contra esos stubs descargados, para garantizar que productor y consumidor permanecen sincronizados antes y después de extraer los servicios del monolito.

**Integraciones cubiertas**:
1. **Consumidor `chat` → Productor `retrieval`**:
   - Operación: `GET /api/knowledge/search` (`query`, `limit`).
   - Escenarios contractuales mínimos:
     - Respuesta `200 OK` con `model` y lista ordenada de fragmentos recuperados (`results`).
     - Respuesta `200 OK` con lista vacía (`results: []`) cuando no hay coincidencias.
     - Respuesta `400 Bad Request` con `ErrorResponse` tipado cuando `query` está ausente o en blanco.
2. **Consumidor `eval` → Productor `chat`**:
   - Operación: `GET /api/assistant/settings`.
   - Escenarios contractuales mínimos:
     - Respuesta `200 OK` con configuración de recuperación (`topK`, `similarityThreshold`, `maxContextCharacters`) y prompts servidos mezclando origen `LANGFUSE` (con versión entera) y `DEFAULT` (con `version: null`).

**Separación para extracción temprana**:
Los contratos, la verificación del productor y los artefactos `stubs` publicados en GitHub Packages deben quedar claramente aislados por productor (`retrieval` y `chat`), de modo que durante el monolito actual ya publiquen sus stubs verificados y, al ejecutar `LAB-129` (`retrieval-service`), `LAB-59` (`chat-service`) y `LAB-130` (`eval-service`), cada repositorio heredero mantenga exactamente las mismas coordenadas de stubs y contratos sin reescribir las pruebas de sus consumidores.

**Why this priority**: OpenAPI valida la estructura estática, mientras que Spring Cloud Contract verifica el comportamiento real en tiempo de ejecución del productor (cabeceras, códigos HTTP, serialización real de nulos/errores) y provee stubs confiables para probar al consumidor de forma aislada.

**Independent Test**: Ejecutar la suite de verificación de contratos del productor en Maven (generación automática de pruebas desde los contratos DSL, ejecución contra los controladores WebFlux reales de `retrieval` y `chat`, empaquetado de los JARs de stubs y publicación en repositorio Maven/GitHub Packages) y ejecutar la suite de tests del consumidor (`chat` contra stub de `retrieval`, `eval` contra stub de `chat`) usando Stub Runner sin levantar el productor real.

**Acceptance Scenarios**:

1. **Given** los contratos Spring Cloud Contract definidos para `retrieval` (`GET /api/knowledge/search`) y `chat` (`GET /api/assistant/settings`), **When** se ejecuta el build Maven del productor, **Then** el plugin de Spring Cloud Contract genera y ejecuta las pruebas de verificación contra los endpoints HTTP reales del backend y todas pasan en verde.
2. **Given** que la verificación del productor en Maven pasa sin errores, **When** se ejecuta el empaquetado/publicación en CI (en merge/release o flujo de publicación configurado), **Then** los artefactos `*-stubs.jar` diferenciados por productor (`retrieval` y `chat`) quedan publicados y versionados en GitHub Packages.
3. **Given** un test del consumidor `chat` que consulta `GET /api/knowledge/search` mediante cliente HTTP, **When** se ejecuta usando Spring Cloud Contract Stub Runner cargando el stub publicado de `retrieval`, **Then** el consumidor recibe las respuestas contractuales esperadas (resultados, vacío y error 400) y valida su deserialización y manejo de errores sin iniciar el productor real.
4. **Given** un test del consumidor `eval` que consulta `GET /api/assistant/settings` mediante cliente HTTP, **When** se ejecuta usando Spring Cloud Contract Stub Runner cargando el stub publicado de `chat`, **Then** el consumidor deserializa correctamente tanto prompts con versión numérica como prompts `DEFAULT` con `version: null` sin iniciar el productor real.
5. **Given** una modificación incompatible en el controlador del productor (p. ej. renombrar un campo devuelto por `GET /api/assistant/settings` o cambiar el código de estado ante `query` vacía), **When** se ejecuta la verificación de contratos del productor, **Then** el build del productor falla inmediatamente antes de poder publicar un stub roto.

---

### User Story 6 - Documentación en `docs/fases/` sobre versionado de APIs y retiro de versiones anteriores (Priority: P2)

Como miembro del equipo de ShopLab Helpdesk, quiero contar con una guía práctica en `docs/fases/` que explique con precisión cuándo una API HTTP debe incrementar su versión (`patch`, `minor`, `major`) y el procedimiento paso a paso para deprecar y retirar una versión anterior sin interrumpir a los consumidores, para que el equipo aplique una política homogénea en toda la evolución de los servicios.

**Contenido obligatorio de la guía**:
- Criterios objetivos para determinar qué cambios disparan `patch` (correcciones documentales o de ejemplos sin alterar contrato), `minor` (cambios aditivos compatibles verificados por `oasdiff`) y `major` (cambios incompatibles declarados).
- Estrategia de convivencia de versiones durante la transición (expand-and-contract, versionado de rutas/paquetes de contrato).
- Procedimiento completo de retiro (sunset) de una versión anterior: marcado `deprecated: true` en OpenAPI, señalización en respuestas HTTP cuando aplique (p. ej. cabeceras `Deprecation` / `Sunset`), ventana de migración de consumidores vía PRs de Dependabot, verificación por métricas/logs de tráfico cero sobre la versión obsoleta y eliminación definitiva en la siguiente versión mayor.
- Enlaces cruzados consistentes con `docs/contratos.md` (LAB-108) y `docs/mapa-servicios.md` (LAB-128).

**Why this priority**: Completa la gobernanza de contratos garantizando que todos los desarrolladores sepan operar el ciclo de vida completo de una API más allá de su publicación inicial.

**Independent Test**: Leer el documento generado en `docs/fases/` y verificar que cubre criterios de cambio de versión (`patch`/`minor`/`major`), convivencia, pasos de deprecación y retiro de versión anterior, y que todos los enlaces relativos a otros documentos del repositorio existen y resuelven correctamente.

**Acceptance Scenarios**:

1. **Given** la carpeta `docs/fases/`, **When** se revisa su contenido tras completar LAB-106, **Then** existe un documento en español que define explícitamente cuándo una API HTTP cambia de versión (`patch`, `minor`, `major`) en coherencia con `oasdiff` y `release-please`.
2. **Given** la necesidad de retirar un endpoint o versión `v1` de una API HTTP, **When** un desarrollador consulta la guía en `docs/fases/`, **Then** encuentra un procedimiento secuencial claro que abarca marcado de deprecación en OpenAPI, notificación/migración de consumidores, verificación de ausencia de tráfico y eliminación segura en una versión mayor.
3. **Given** la documentación existente en `docs/contratos.md`, **When** se navega entre ambos documentos, **Then** no existen contradicciones sobre el flujo de `shoplab-contracts` ni enlaces rotos.

---

### Edge Cases

- **Modelado de streaming SSE (`POST /api/chat`) en OpenAPI y Angular**: `POST /api/chat` devuelve `text/event-stream` (`Flux<ServerSentEvent<Object>>`) con eventos heterogéneos (tokens, metadatos/citas, error, fin de stream). El contrato OpenAPI debe documentar el request body, las cabeceras opcionales y los esquemas de payload de los eventos SSE de forma que `openapi-generator` (`typescript-angular`) genere sin errores todos los modelos TypeScript consumidos por `chat-stream.client.ts`, evitando tipos escritos a mano aunque el transporte utilice lector incremental de stream en lugar de `HttpClient` estándar.
- **Carga `multipart/form-data` y doble código 2xx en `POST /api/documents`**: El endpoint de subida de documentos acepta un archivo multipart (`file`), responde `201 Created` cuando el documento es nuevo, `200 OK` cuando reemplaza uno existente y `413 Payload Too Large` (o `400 Bad Request`) con `ErrorResponse` en caso de exceso de tamaño o formato inválido. El contrato OpenAPI debe modelar explícitamente el cuerpo multipart y ambos códigos 2xx.
- **Alineación del esquema `ErrorResponse` entre `common-api.yaml` y el backend real**: `GlobalExceptionHandler` emite JSON con `{ code, message, detail? }` (`ErrorResponse`), mientras que la semilla inicial de `common-api.yaml` creada en LAB-108 definía `{ data, errors: [ErrorDto] }`. Los contratos OpenAPI de `chat`, `retrieval` y `eval` (y el esquema común compartido que referencien) deben alinearse con el formato real del backend para que el cliente generado en `frontend/` (`api-error.ts`) y los tests de Spring Cloud Contract validen respuestas reales sin fallos de deserialización.
- **Valores nulos en `GET /api/assistant/settings`**: Cuando un prompt se sirve desde los textos empaquetados por defecto (`source: "DEFAULT"`), `version` es `null`. Tanto el esquema OpenAPI como el contrato Spring Cloud Contract deben declarar explícitamente la nulabilidad de `version` y probar ese caso para evitar `NullPointerException` o errores de validación de esquema en productor y consumidor.
- **Validación sintáctica estricta en `oasdiff` para especificaciones nuevas**: Cuando se añade un archivo YAML/JSON nuevo en `openapi/` que aún no existe en `origin/main`, el script `check-openapi-compatibility.sh` no debe silenciar errores de sintaxis (`|| true`); cualquier especificación inválida debe hacer fallar la CI.
- **Transición de coordenadas de stubs entre monolito y microservicios (`LAB-129`, `LAB-59`, `LAB-130`)**: Mientras `retrieval` y `chat` coexisten en `backend/`, cada productor debe publicar sus propios stubs con identificador/clasificador bien diferenciado (p. ej. artefacto o `classifier` dedicado por productor) de modo que, al extraerse a `shoplab-retrieval-service` y `shoplab-chat-service`, las pruebas de contrato consumidor (`chat → retrieval` y `eval → chat`) sigan resolviendo los stubs sin conflictos.
- **Autenticación y modo offline/local con GitHub Packages**: Tanto Maven (`settings.xml`) como npm (`.npmrc`) requieren token con permiso `read:packages` para descargar paquetes de GitHub Packages en CI y local. Debe documentarse y soportarse el flujo de verificación local previo a publicar un release (instalación local en `~/.m2` y enlace/empaquetado local de npm) respetando las restricciones de compilación offline cuando aplique.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio `shoplab-contracts` MUST contener especificaciones OpenAPI 3.1 válidas bajo `openapi/` para todas las APIs HTTP actualmente publicadas del backend: `chat`, `retrieval` y `eval`.
- **FR-002**: La especificación OpenAPI de `chat` MUST cubrir `POST /api/chat` (incluyendo `ChatRequest`, cabeceras opcionales de override de prompts, `text/event-stream` y esquemas de eventos SSE), `PUT /api/chat/feedback/{turnId}`, `GET /api/chat/feedback`, `POST /api/chat/feedback/{turnId}/promote` y `GET /api/assistant/settings`.
- **FR-003**: La especificación OpenAPI de `retrieval` MUST cubrir `POST /api/documents` (`multipart/form-data`, respuestas `201` y `200`), `GET /api/documents` y `GET /api/knowledge/search` (parámetros `query` y `limit`).
- **FR-004**: La especificación OpenAPI de `eval` MUST cubrir `POST /api/eval/runs`, `GET /api/eval/runs/{runId}`, `GET /api/eval/settings` (incluyendo la cabecera de token requerida por estos tres endpoints), `GET /api/eval/history/runs`, `GET /api/eval/history/runs/{runId}` y `GET /api/eval/history/compare`.
- **FR-005**: Todas las especificaciones OpenAPI de servicio MUST referenciar un esquema estándar de error consistente con la respuesta real de `GlobalExceptionHandler` (`code`, `message`, `detail` opcional) para respuestas 4xx y 5xx.
- **FR-006**: La documentación y estructura de `shoplab-contracts/openapi/` MUST establecer que los servicios futuros `catalog`, `orders` y `tickets` (sin endpoints hoy; nacen en F3/F4 según ADR-0031) registrarán su especificación OpenAPI en `openapi/<servicio>/v1/` al nacer antes de exponer endpoints HTTP.
- **FR-007**: El módulo `chat` del backend MUST publicar el endpoint `GET /api/assistant/settings` de forma reactiva y no bloqueante, exponiendo los mismos datos de configuración de recuperación y prompts servidos que `AssistantSettingsApi`, manteniendo compatibilidad con el uso actual de `eval` hasta su extracción en `LAB-130`.
- **FR-008**: El generador npm de `shoplab-contracts` (`generators/npm`) MUST generar modelos TypeScript y clientes Angular tipados a partir de todas las especificaciones OpenAPI publicadas (`chat`, `retrieval`, `eval`, además de `common`) y publicarlos en el paquete `@leandroamariles/shoplab-contracts` en GitHub Packages.
- **FR-009**: El módulo `frontend/` MUST instalar y consumir la versión fijada de `@leandroamariles/shoplab-contracts` en `package.json` (con configuración de registro GitHub Packages y cobertura por Dependabot) y eliminar todas las interfaces y tipos de DTO del backend escritos a mano en `frontend/src/app/` (incluidos los modelos de petición, respuesta, eventos SSE y error HTTP).
- **FR-010**: La generación de código en `shoplab-contracts` (Java y npm) MUST permanecer libre de lógica de negocio, exponiendo únicamente contratos, modelos y clientes HTTP generados (principio de pureza de contratos de `LAB-108` y `ADR-0010`).
- **FR-011**: El workflow de CI de `shoplab-contracts` MUST ejecutar `oasdiff` sobre todas las especificaciones bajo `openapi/` en cada PR contra `main`, fallando automáticamente si detecta cualquier cambio incompatible sin incremento de versión mayor declarado (`!:` o `BREAKING CHANGE:`), así como si una especificación nueva o modificada presenta errores de sintaxis OpenAPI.
- **FR-012**: El backend MUST definir contratos dirigidos por el consumidor mediante Spring Cloud Contract para la llamada `chat → retrieval` (`GET /api/knowledge/search`, cubriendo resultados, lista vacía y error `400` por consulta inválida) y para la llamada `eval → chat` (`GET /api/assistant/settings`, cubriendo prompts con versión `LANGFUSE` y `DEFAULT` con `version: null`).
- **FR-013**: El build y CI del lado productor MUST generar y ejecutar automáticamente los tests de verificación de Spring Cloud Contract contra los controladores WebFlux reales de `retrieval` y `chat`, y empaquetar/publicar los artefactos `stubs` diferenciados por productor en GitHub Packages.
- **FR-014**: Los módulos consumidores (`chat` respecto a `retrieval` y `eval` respecto a `chat`) MUST incluir tests de contrato de consumidor usando Spring Cloud Contract Stub Runner que descarguen y ejecuten los stubs publicados del productor sin levantar la instancia real del productor.
- **FR-015**: La organización de los contratos Spring Cloud Contract y coordenadas de publicación de stubs MUST quedar desacoplada por módulo productor (`retrieval` y `chat`) para permitir su migración directa en `LAB-129`, `LAB-59` y `LAB-130` sin romper a los consumidores.
- **FR-016**: El repositorio MUST incorporar documentación en `docs/fases/` que especifique cuándo cambia de versión una API HTTP (`patch`, `minor`, `major`), cómo coexisten versiones y el procedimiento completo para deprecar y retirar una versión anterior, enlazada con `docs/contratos.md`.
- **FR-017**: Toda la implementación en Java MUST respetar los principios de arquitectura hexagonal, programación reactiva sin `.block()`, identificadores en inglés y cobertura JaCoCo ≥ 80 % establecidos en la Constitución del proyecto y `.ai/rules/development-guidelines.md`.
- **FR-018**: Al implementar o modificar endpoints HTTP en el backend (como `GET /api/assistant/settings`), se MUST generar o actualizar `specs/LAB-106-http-openapi-contracts/requests.http` cubriendo los escenarios del contrato.

### Key Entities

- **Especificación OpenAPI de Servicio**: Documento OpenAPI 3.1 alojado en `shoplab-contracts/openapi/<servicio>/v1/<servicio>-api.yaml` que define rutas, parámetros, cabeceras, payloads, eventos SSE y respuestas de error de una API HTTP.
- **Configuración Activa del Asistente (`AssistantSettings`)**: Instantánea de solo lectura de la configuración de recuperación (`topK`, `similarityThreshold`, `maxContextCharacters`) y de los prompts servidos (`name`, `version` nullable, `source`: `LANGFUSE` | `DEFAULT`), expuesta en `GET /api/assistant/settings`.
- **Paquete Cliente Angular (`@leandroamariles/shoplab-contracts`)**: Paquete npm generado automáticamente desde OpenAPI y publicado en GitHub Packages con los modelos TypeScript y clientes Angular HTTP consumidos por `frontend/`.
- **Contrato y Stub Spring Cloud Contract**: Definición DSL (YAML/Groovy) de una interacción productor-consumidor que genera tanto el test de verificación del productor como el `*-stubs.jar` publicado en GitHub Packages para pruebas de consumidor con Stub Runner.
- **Puerta de Compatibilidad (`oasdiff` CI Gate)**: Verificación automatizada en `shoplab-contracts` que compara cada contrato OpenAPI contra `origin/main` para impedir rupturas no declaradas como versión mayor.
- **Guía de Versionado y Retiro (`docs/fases/`)**: Documento normativo del proyecto que establece reglas de evolución semántica de APIs HTTP y el protocolo operativo de deprecación y retiro (`sunset`) de versiones anteriores.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los endpoints HTTP actualmente publicados en `chat`, `retrieval` y `eval` (incluyendo `GET /api/assistant/settings`) cuentan con especificación OpenAPI 3.1 válida en `shoplab-contracts/openapi/`, y la convención de alta al nacer para `catalog`, `orders` y `tickets` está documentada.
- **SC-002**: `GET /api/assistant/settings` responde `200 OK` en formato JSON conforme a su contrato OpenAPI y coincide al 100 % con los valores entregados por `AssistantSettingsApi.current()`.
- **SC-003**: Cero interfaces o tipos DTO del backend escritos a mano permanecen en `frontend/src/app/`; el 100 % de los clientes HTTP y parser SSE de `frontend/` consumen tipos generados desde `@leandroamariles/shoplab-contracts`, con `npm run build`, `npm run lint` y `npm test` en verde.
- **SC-004**: El check `oasdiff` en el CI de `shoplab-contracts` rechaza el 100 % de los PRs de prueba con cambios incompatibles sin declaración de versión mayor y aprueba el 100 % de los cambios aditivos o marcados explícitamente como breaking change mayor.
- **SC-005**: Las dos suites de pruebas de contrato Spring Cloud Contract (`chat → retrieval` y `eval → chat`) pasan al 100 % tanto en la verificación automática del productor como en los tests de consumidor con Stub Runner cargando los stubs publicados en GitHub Packages, sin arrancar el servicio productor.
- **SC-006**: La cobertura de pruebas backend en los módulos modificados se mantiene ≥ 80 % (JaCoCo) y cero violaciones de Checkstyle/PMD tras incorporar `GET /api/assistant/settings` y las pruebas de contrato.
- **SC-007**: Existe en `docs/fases/` el documento de versionado y retiro de APIs HTTP, con criterios completos para `patch`/`minor`/`major`, pasos de deprecación/retiro y enlaces válidos hacia `docs/contratos.md`.

---

## Assumptions

- **Dependencia `LAB-108` completada**: El repositorio `shoplab-contracts`, sus registros Maven y npm en GitHub Packages, la configuración base de `release-please`, `dependabot.yml` y el workflow base de `oasdiff` ya existen y están operativos.
- **Orden respecto a la extracción temprana (`ADR-0031` / `LAB-128`)**: `LAB-106` se ejecuta antes de `LAB-129` (`retrieval-service`), `LAB-59` (`chat-service`) y `LAB-130` (`eval-service`), por lo que la publicación inicial de contratos HTTP, `GET /api/assistant/settings` y las suites Spring Cloud Contract se implementan sobre los módulos del backend actual, preparadas para trasladarse limpiamente con cada servicio al extraerse.
- **Estado de `catalog`, `orders` y `tickets`**: Estos tres módulos no tienen endpoints HTTP implementados en el monolito y nacerán directamente como servicios independientes en F3/F4; en `LAB-106` no se inventan endpoints ficticios para ellos, sino que se formaliza su incorporación obligatoria a `shoplab-contracts/openapi/` al momento de nacer.
- **Alcance exclusivo sobre contratos HTTP**: Los esquemas Avro y AsyncAPI de mensajería asíncrona quedan fuera del alcance de `LAB-106` (pertenecen a `LAB-50`), conforme a lo indicado en la historia de Jira.
- **Coordinación multi-repositorio**: La implementación de `LAB-106` requerirá cambios coordinados en `shoplab-contracts` (especificaciones OpenAPI, generadores Java/npm, `oasdiff` CI) y en `shoplab-helpdesk` (endpoint `GET /api/assistant/settings`, Spring Cloud Contract, consumo del paquete npm en `frontend/`, documentación en `docs/fases/`).
