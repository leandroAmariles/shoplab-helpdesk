# Feature Specification: LAB-59 Extraer chat-service del monolito

**Feature Branch**: `feature/LAB-59-chat-service-extraction`

**Created**: 2026-11-25

**Status**: Draft

**Input**: User description: "LAB-59 (F6-01) Como desarrollador, quiero separar `chat-service` y retirar el monolito, para que cada contexto con código viva en su propio servicio antes de F3. Criterios de aceptación: repo `shoplab-chat-service` creado desde la plantilla de LAB-103, con el paquete `chat` y su carpeta `db/migration/chat`; base de datos propia con los datos del esquema `chat` migrados; `chat` llama a `retrieval-service` (LAB-129) por HTTP con el contrato y los stubs de LAB-106; publica `POST /api/chat` y `GET /api/assistant/settings`; el gateway de LAB-107 mueve `/api/chat/**` al servicio; prompts versionados, trazas de Langfuse y el gateway de modelos siguen funcionando igual (mismas métricas de calidad de LAB-29); se eliminan del monolito los módulos vacíos `orders`, `catalog` y `tickets`; sus esquemas con los datos de Olist pasan a los servicios que nacen en F3/F4 (LAB-113, LAB-51, LAB-53); el monolito queda vacío y se retira; `shoplab-helpdesk` queda solo como repo paraguas (LAB-110). Depende de: LAB-103, LAB-104, LAB-106, LAB-107, LAB-110, LAB-129. Va antes de LAB-130 (eval-service). Se adelantó al bloque de extracción temprana (ADR-0029, LAB-128); retrieval-service pasa a LAB-129."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - El chat vive en su propio servicio con sus datos, prompts, trazas y modelos (Priority: P1)

Como desarrollador, quiero `chat-service` en su propio repositorio, con su base de datos y la misma API conversacional, de configuración y de feedback que hoy ofrece el monolito, para evolucionar y desplegar el asistente de forma independiente antes de F3.

**Why this priority**: Es el núcleo de la historia. Tener `chat-service` operando en paralelo al monolito permite validar streaming SSE, recuperación contra `retrieval-service`, prompts versionados, trazas de Langfuse, memoria de conversación y feedback sin afectar a los usuarios.

**Independent Test**: Levantar `shoplab-chat-service` con una copia migrada del esquema `chat`, apuntando a `retrieval-service`, LiteLLM y Langfuse, junto al monolito intacto. Ejecutar contra ambos un diálogo multi-turno (cortesía + pregunta con citas + repregunta), lectura de `GET /api/assistant/settings`, envío/listado/promoción de feedback y evaluación de calidad (LAB-29); las respuestas, trazas, citas y métricas coinciden.

**Acceptance Scenarios**:

1. **Given** el repositorio `shoplab-chat-service` creado desde `shoplab-service-template` (LAB-103), **When** se construye y se prueba en su CI, **Then** contiene el paquete `chat`, sus migraciones `db/migration/chat` (`V1`–`V3`) y sus prompts por defecto (`prompts/*.txt`), no depende de código del monolito ni de otros servicios, y pasa las puertas de calidad (cobertura ≥ 80 %, arquitectura, análisis estático e imagen).
2. **Given** las tablas `conversation_turn` y `feedback` y el historial de Flyway del esquema `chat` en la base del monolito, **When** se migran a la base de datos propia `chat`, **Then** los recuentos, el contenido (turnos, citas entregadas, `trace_id`, valoraciones y promociones a golden) y las secuencias de identidad coinciden sin pérdida y sin recalcular nada.
3. **Given** el servicio en marcha, **When** recibe una petición en `POST /api/chat` (SSE), `GET /api/assistant/settings`, `PUT /api/chat/feedback/{turnId}`, `GET /api/chat/feedback` o `POST /api/chat/feedback/{turnId}/promote`, **Then** responde con los mismos formatos, eventos SSE, cabeceras (`X-Request-Id`, `traceparent`, `X-Prompt-Versions`, `X-Prompt-Token`), límites y códigos de error `SLHD_CHAT_*` que el monolito.
4. **Given** una pregunta que requiere base de conocimiento, **When** `chat-service` la atiende, **Then** consulta `retrieval-service` (LAB-129) por HTTP usando el contrato y los stubs publicados en `shoplab-contracts` (LAB-106 / LAB-108), aplica el mismo umbral, presupuesto de contexto y citas (ADR-0018, ADR-0019), y consulta los modelos a través de LiteLLM con su propia clave virtual.
5. **Given** Langfuse habilitado en el entorno, **When** `chat-service` atiende un turno o resuelve prompts, **Then** obtiene y refresca los prompts versionados (LAB-85), admite el override por cabeceras para evaluación y envía la traza OTLP completa con coste y metadatos (LAB-64), manteniendo el comportamiento actual.
6. **Given** una release de `shoplab-chat-service`, **When** termina su pipeline, **Then** su OpenAPI (incluyendo `GET /api/assistant/settings` y las operaciones de chat) está publicada y verificada en `shoplab-contracts` como productor, junto con los stubs de Spring Cloud Contract que consumirá `eval`.

---

### User Story 2 - El gateway entrega el chat y la configuración del asistente al servicio en dev y local (Priority: P1)

Como usuario del chat, administrador que revisa feedback o desarrollador que ejecuta evaluaciones, quiero que todo funcione igual cuando las rutas `/api/chat/**` y `/api/assistant/**` pasen a `chat-service`, sin cambios en el frontend ni en la herramienta de evaluación.

**Why this priority**: Es el punto en el que `chat-service` entra en tráfico real. Depende de US1 y, antes de retirar el módulo del monolito, es reversible con un único cambio de configuración.

**Independent Test**: En `dev` (y en el compose local), con `chat-service` desplegado y datos migrados, cambiar en el gateway el destino de `chat` y `assistant` hacia `chat-service`; recorrer el chat con streaming y citas en el frontend, enviar y consultar feedback, y ejecutar la evaluación de calidad (LAB-29); revertir la configuración antes de retirar el módulo y verificar que el monolito vuelve a atender ambas rutas.

**Acceptance Scenarios**:

1. **Given** el alta de `chat-service` en `shoplab-infra`, **When** el ApplicationSet de `dev` (LAB-104) lo sincroniza, **Then** queda sano y listo con su base de datos propia, sus secretos (base de datos, clave virtual de LiteLLM, credenciales de Langfuse y token de override de prompts) y conectividad a `retrieval-service`, LiteLLM y Langfuse.
2. **Given** los datos del esquema `chat` migrados y `chat-service` sano, **When** se actualiza en el gateway el destino de `/api/chat/**` y `/api/assistant/**` hacia `chat-service`, **Then** el streaming SSE, el feedback y la configuración del asistente los atiende `chat-service` sin publicar una nueva versión del gateway ni del frontend.
3. **Given** las rutas movidas, **When** se ejecuta la evaluación de calidad de respuesta (LAB-29) sobre el mismo dataset golden y el mismo modelo que la línea base previa, **Then** las métricas de calidad (faithfulness, answer relevancy y demás métricas configuradas) son equivalentes a la línea base y no degradan.
4. **Given** el entorno local multi-repo de `shoplab-helpdesk` (LAB-110), **When** se levanta con `docker compose -f infra/docker-compose.yml up`, **Then** se inicializa la base `chat`, se registra la clave virtual de `chat` en LiteLLM, arranca `chat-service` y el gateway le envía `/api/chat/**` y `/api/assistant/**` por defecto.
5. **Given** un fallo tras mover las rutas mientras el módulo `chat` aún existe en el monolito, **When** se revierte la configuración del gateway, **Then** el monolito vuelve a atender `/api/chat/**` y `/api/assistant/**`.

---

### User Story 3 - `eval` consume `chat-service` por red y se retiran `chat` y los módulos vacíos del monolito (Priority: P1)

Como desarrollador, quiero que el módulo `eval` lea la configuración del asistente desde `chat-service` por HTTP y que se retiren del monolito el módulo `chat` y los módulos vacíos `orders`, `catalog` y `tickets` sin tocar los datos de Olist, dejando el monolito reducido al mínimo imprescindible hasta la extracción de `eval-service` en LAB-130.

**Why this priority**: Sin retirar `chat` del monolito y conmutar `eval` a HTTP quedarían dos copias del código de chat y una dependencia en proceso rota (`AssistantSettingsApi`). Además, limpiar los módulos vacíos evita mantener código muerto en `backend/` mientras se prepara el retiro definitivo del monolito en LAB-130.

**Independent Test**: Con `eval` apuntando a `chat-service` por HTTP, consultar `GET /api/eval/settings` con `chat-service` arriba (devuelve configuración y prompts servidos) y con `chat-service` caído (devuelve error controlado). Verificar que `backend/` compila y pasa todas sus pruebas sin `chat`, `orders`, `catalog` ni `tickets`, y que los esquemas Olist en PostgreSQL conservan intactas sus tablas y filas.

**Acceptance Scenarios**:

1. **Given** el módulo `eval` aún en el monolito antes de LAB-130, **When** necesita la configuración del asistente (`GET /api/eval/settings`), **Then** la obtiene de `chat-service` (`GET /api/assistant/settings`) por HTTP usando el contrato y los stubs publicados en `shoplab-contracts`, con tiempo máximo de espera y propagación de `X-Request-Id` y `traceparent`; `AssistantSettingsApi` y `ChatAssistantSettingsAdapter` dejan de existir.
2. **Given** `chat-service` verificado en `dev`, **When** se retira el módulo `chat` del monolito, **Then** no queda código, migraciones (`db/migration/chat`), prompts (`prompts/*.txt`), propiedades `shoplab.chat.*`, ni contratos/stubs de `chat` en `backend/`.
3. **Given** los módulos vacíos `orders`, `catalog` y `tickets` en el monolito, **When** se eliminan de `backend/`, **Then** desaparecen sus paquetes Java, configuraciones de esquema y registros Flyway del monolito, pero sus esquemas y datos de Olist en PostgreSQL permanecen intactos para los servicios de F3/F4 (LAB-113, LAB-51, LAB-53).
4. **Given** el monolito sin `chat`, `orders`, `catalog` ni `tickets`, **When** se construye, se prueba y se despliega en `dev`, **Then** solo conserva `eval` y `shared` (módulo técnico mínimo), pasa sus pruebas y cobertura ≥ 80 %, y sigue atendiendo `/api/eval/**` hasta que LAB-130 extraiga `eval-service` y retire definitivamente `backend/`.

---

### Edge Cases

- **Conversaciones y feedback durante la ventana de migración**: cualquier turno o feedback escrito en el monolito después del volcado y antes del cambio de rutas en el gateway se perdería; la migración se ejecuta en una ventana controlada en `dev` (sin tráfico de escritura o aceptando la ventana documentada).
- **Migración incompleta, repetida o con secuencias desalineadas**: si los recuentos o sumas de comprobación de `conversation_turn` o `feedback` no coinciden, no se mueven las rutas; repetir la migración es idempotente y las secuencias `GENERATED ALWAYS AS IDENTITY` de ambas tablas quedan sincronizadas tras restaurar para que el primer `INSERT` en `chat-service` no colisione.
- **Reversión tras recibir turnos o feedback en `chat-service`**: los turnos y feedbacks creados en el servicio no vuelven automáticamente al esquema antiguo del monolito; el procedimiento de reversión documenta este límite.
- **Streaming SSE a través del gateway y límites de tiempo**: el flujo `text/event-stream` de `POST /api/chat` llega incrementalmente al cliente sin buferizado intermedio; si `chat-service` tarda más del límite configurado o está caído, el gateway responde con `504`/`503` y el frontend no queda bloqueado.
- **`retrieval-service` caído o sin respuesta a tiempo**: `chat-service` traduce el fallo al error tipado actual (`SLHD_CHAT_00004`, 503) para preguntas que requieren base de conocimiento, mientras las cortesías acotadas (LAB-114) siguen respondiendo sin consultar `retrieval-service`.
- **LiteLLM caído, saturado o sin presupuesto**: `chat-service` conserva exactamente la traducción de errores `SLHD_CHAT_00002` (503), `SLHD_CHAT_00003` (500) y `SLHD_CHAT_00007` (429).
- **Langfuse caído, lento o deshabilitado**: el servicio arranca y atiende usando los prompts por defecto (`prompts/*.txt`) o la última caché válida sin bloquear el chat; el envío de trazas nunca rompe ni retrasa la respuesta al usuario; los errores de override de prompts conservan `SLHD_CHAT_00008`, `SLHD_CHAT_00009` y `SLHD_CHAT_00010`.
- **`chat-service` caído cuando `eval` consulta `GET /api/assistant/settings`**: el cliente HTTP en `eval` respeta su timeout y traduce el fallo a un error controlado sin bloquear hilos reactivos.
- **Eliminación de `orders`, `catalog` y `tickets` sin pérdida de Olist**: quitar esos módulos de `backend/` no ejecuta `DROP SCHEMA` ni elimina tablas/datos de Olist en PostgreSQL; esos esquemas quedan disponibles para `catalog-service`, `order-service` y `ticket-service`.
- **Base de datos `chat` inaccesible o esquema inconsistente al arrancar**: el servicio no se declara listo (`/actuator/health`) e informa la causa en los logs.

---

## Requirements *(mandatory)*

### Functional Requirements

**Repositorio y servicio**

- **FR-001**: El servicio MUST vivir en el repositorio `shoplab-chat-service`, creado desde `shoplab-service-template` (LAB-103), con el paquete `chat`, `db/migration/chat` y los prompts por defecto (`prompts/*.txt`), sin dependencias de código del monolito ni de otros servicios.
- **FR-002**: El servicio MUST usar el pipeline reutilizable (`shoplab-ci`) y el versionado automático (`release-please`) del resto de repositorios, con las mismas puertas de calidad (cobertura ≥ 80 %, pruebas de arquitectura, Qodana, Trivy, SBOM y firma de imagen).
- **FR-003**: `POST /api/chat` (SSE), `GET /api/assistant/settings`, `PUT /api/chat/feedback/{turnId}`, `GET /api/chat/feedback` y `POST /api/chat/feedback/{turnId}/promote` MUST conservar rutas, métodos, cabeceras, cuerpos, eventos SSE, límites y códigos de error `SLHD_CHAT_*`. Las respuestas MUST NOT envolverse en el formato genérico de la plantilla.
- **FR-004**: El servicio MUST conservar sin cambios funcionales la memoria de conversación (LAB-24), el flujo RAG con citas y umbral/presupuesto de contexto (LAB-22, LAB-23, ADR-0018, ADR-0019), la conversación de cortesía acotada (LAB-114) y el feedback con promoción a dataset golden (LAB-33).
- **FR-005**: El servicio MUST llamar al gateway de modelos LiteLLM (LAB-84) con su propia clave virtual (`chat-<entorno>`, modelos `chat`, `chat-fallback` y opcionalmente `chat-cloud` cuando exista proveedor cloud) y conservar el timeout y la detección de fallback actuales.
- **FR-006**: El servicio MUST conservar la gestión de prompts versionados en Langfuse (LAB-85, incluido caché, refresco, publicación opcional de faltantes y override por cabeceras `X-Prompt-Versions` / `X-Prompt-Token`) y las trazas OTLP de Langfuse por turno con cálculo de coste (LAB-64).

**Datos y migración**

- **FR-007**: El servicio MUST tener su propia base de datos (`chat`) y credencial dedicada, y ser el único servicio que la lee o escribe.
- **FR-008**: Los datos del esquema `chat` (`conversation_turn`, `feedback` e historial de Flyway) MUST migrarse sin pérdida desde la base del monolito a la base `chat`, con sincronización de secuencias de identidad y una verificación automática de recuentos y sumas de comprobación mediante un procedimiento repetible.
- **FR-009**: El esquema `chat` antiguo en la base `shoplab` MUST conservarse hasta verificar la extracción en `dev`; su borrado es un paso manual, explícito y documentado con copia de seguridad previa.

**Contratos y consumidores**

- **FR-010**: `chat-service` MUST llamar a `retrieval-service` (LAB-129) por HTTP usando el contrato publicado en `shoplab-contracts` (LAB-106 / LAB-108) y verificar su integración como consumidor contra los stubs publicados por `retrieval-service`, manteniendo el tiempo máximo de espera, la propagación de `X-Request-Id` y `traceparent`, y la traducción de fallos a `SLHD_CHAT_00004`.
- **FR-011**: El OpenAPI de `chat` en `shoplab-contracts` (incluyendo `POST /api/chat`, `GET /api/assistant/settings` y las operaciones de feedback) MUST ser verificado por `chat-service` como productor, publicando los stubs de Spring Cloud Contract bajo sus propias coordenadas para `GET /api/assistant/settings`.
- **FR-012**: El módulo `eval` (mientras permanezca en el monolito hasta LAB-130) MUST leer `GET /api/assistant/settings` de `chat-service` por HTTP usando el contrato y los stubs publicados, con dirección configurable por entorno, timeout explícito y propagación de correlación/traza, eliminando `AssistantSettingsApi` y la llamada en proceso.

**Enrutamiento, despliegue y entorno local**

- **FR-013**: El gateway (LAB-107) MUST mover `/api/chat/**` y `/api/assistant/**` a `chat-service` solo con configuración, en `dev` y en local, sin publicar una nueva versión del gateway ni del frontend.
- **FR-014**: `chat-service` MUST desplegarse en `dev` mediante el ApplicationSet de LAB-104 (`shoplab-infra`), con su base de datos, secretos (DB, LiteLLM, Langfuse, token de override), URL de `retrieval-service`, LiteLLM y Langfuse, y sin exigir JWT mientras no exista autenticación (LAB-61).
- **FR-015**: El entorno local de `shoplab-helpdesk` (LAB-110) MUST incluir la inicialización de la base `chat`, la clave virtual de `chat` en LiteLLM, `chat-service` arrancando con un solo `docker compose up`, su entrada en `clone-all` y la opción de construcción local.

**Retirada de módulos del monolito y estado transitorio**

- **FR-016**: Tras verificar `chat-service` en `dev`, el módulo `chat` MUST retirarse del monolito (`backend/`) junto con sus migraciones (`db/migration/chat`), prompts por defecto, propiedades, pruebas y contratos propios.
- **FR-017**: Los módulos vacíos `orders`, `catalog` y `tickets` MUST eliminarse del monolito (`backend/`), retirando sus paquetes Java, configuraciones de esquema y registros Flyway en `backend/`, sin eliminar ni alterar sus esquemas y datos de Olist en PostgreSQL (que pasan a los servicios de F3/F4: LAB-113, LAB-51, LAB-53).
- **FR-018**: Dado que LAB-59 precede a LAB-130 (`eval-service`), el monolito (`backend/`) MUST quedar reducido exclusivamente a `eval` y `shared` al cierre de LAB-59, dejando su retiro definitivo y la conversión final de `shoplab-helpdesk` en repo puramente paraguas para LAB-130 (ver *Assumptions*).

**Observabilidad, verificación y documentación**

- **FR-019**: `chat-service` MUST publicar salud (`/actuator/health`), métricas (`/actuator/prometheus`), logs estructurados con `X-Request-Id` y trazas; las métricas actuales de chat conservan su nombre y siguen visibles en los dashboards existentes de Grafana/Prometheus.
- **FR-020**: Se MUST tomar una línea base de la evaluación de calidad de respuesta (LAB-29) antes del corte y repetirla después sobre el mismo dataset golden, corpus y modelo, guardando la comparación para evidenciar que no hay degradación.
- **FR-021**: Se MUST registrar un ADR (decisión de base de datos propia de `chat`, corte en dos releases, transición de `eval` por HTTP y estado transitorio del monolito hasta LAB-130) y actualizar `docs/mapa-servicios.md`, la guía de rutas del gateway (`docs/fases/F5-mover-rutas-al-gateway.md`) y la documentación del entorno local.

**Fuera de alcance**

- **FR-022**: Esta historia MUST NOT incluir: extracción de `eval-service` ni el borrado definitivo de `backend/` mientras `eval` siga allí (LAB-130), autenticación JWT / login (LAB-60, LAB-61), clientes MCP hacia `catalog`/`orders`/`tickets`/`retrieval` (F3/F4), eventos Kafka de chat (`chat.turn.*`, F4/F5), ni cambios en lógica de RAG, prompts o modelo.

### Key Entities

- **Turno de conversación (`conversation_turn`)**: intercambio completo pregunta–respuesta de una conversación identificada por `conversation_id`, con identificador público `turn_id` (UUID), documentos entregados (`delivered_documents`), identificador opcional de traza Langfuse (`trace_id`) y marca temporal.
- **Feedback (`feedback`)**: valoración (`UP`/`DOWN`) asociada 1:1 a un turno completado, con comentario opcional y campos opcionales de promoción a caso golden (`promoted_case_id`, `promoted_case_jsonl`, `promoted_at`).
- **Configuración servida del asistente (`AssistantSettings`)**: instantánea de parámetros de recuperación (`topK`, `similarityThreshold`, `maxContextCharacters`) y versiones/fuentes de los prompts servidos, expuesta en `GET /api/assistant/settings` para `eval`.
- **Contrato de chat**: especificación OpenAPI en `shoplab-contracts` que rige las operaciones HTTP del servicio y genera los stubs verificados de productor/consumidor.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Las métricas de calidad de respuesta (LAB-29) tras el corte a `chat-service` no degradan frente a la línea base tomada antes de extraer sobre el mismo dataset golden, corpus y modelo.
- **SC-002**: El 100 % de las filas de `conversation_turn` y `feedback` migradas coinciden con el origen (recuentos y sumas de comprobación) y las nuevas escrituras funcionan sin errores de secuencia desde el primer turno.
- **SC-003**: Los recorridos de chat con streaming SSE, citas, memoria multi-turno, cortesías, feedback/promoción y `GET /api/eval/settings` funcionan de extremo a extremo con 0 cambios en el código del frontend y de la herramienta Python `shoplab-eval`.
- **SC-004**: Las trazas OTLP de Langfuse y los prompts versionados (incluido el refresco por etiqueta y el override de evaluación) siguen registrando el 100 % de los turnos evaluados con la misma estructura y cálculo de coste.
- **SC-005**: Un cambio en `chat-service` se construye, publica y despliega en `dev` sin ninguna release del monolito (`backend/`).
- **SC-006**: Las rutas `/api/chat/**` y `/api/assistant/**` pueden devolverse al monolito, antes de retirar el módulo `chat`, en menos de 10 minutos con un solo cambio de configuración en `shoplab-infra`.
- **SC-007**: El monolito (`backend/`) compila y pasa todas sus pruebas con cobertura ≥ 80 % sin `chat`, `orders`, `catalog` ni `tickets`, y el 100 % de las tablas y filas de Olist en los esquemas `catalog` y `orders` permanecen intactas.

---

## Assumptions

- **Retiro definitivo del monolito diferido a LAB-130**: El enunciado original de Jira para LAB-59 menciona *"El monolito queda vacío y se retira; `shoplab-helpdesk` queda solo como repo paraguas (LAB-110)"* y a la vez especifica *"Va antes de LAB-130 (eval-service)"*. En el estado actual del repositorio, el módulo `eval` (~1.400 líneas, `POST/GET /api/eval/runs`, `GET /api/eval/settings`) todavía vive en `backend/` y su extracción pertenece a LAB-130 (*"para retirar el monolito"*). Por tanto, esta especificación asume que LAB-59 extrae `chat-service`, conmuta `eval` para leer `GET /api/assistant/settings` por HTTP y elimina `chat`, `orders`, `catalog` y `tickets` de `backend/`, dejando `backend/` con solo `eval` + `shared` hasta que LAB-130 complete el retiro definitivo del monolito.
- **Cobertura de rutas en el gateway**: Aunque Jira menciona `/api/chat/**` y los endpoints `POST /api/chat` y `GET /api/assistant/settings`, el módulo `chat` también expone el feedback bajo `/api/chat/feedback/**` (ya cubierto por `/api/chat/**`) y la configuración del asistente bajo `/api/assistant/settings` (cubierto por la ruta `assistant` `/api/assistant/**` definida en `docs/fases/F5-mover-rutas-al-gateway.md`). Ambas rutas (`chat` y `assistant`) se mueven juntas en LAB-59.
- **Base de datos propia en el mismo servidor**: Al igual que en LAB-129 (ADR-0033), «base de datos propia» significa base de datos `chat` y rol `chat` dedicados dentro del mismo servidor PostgreSQL de `dev` y local, con `REVOKE CONNECT` a `PUBLIC`.
- **Corte en dos releases y ventana controlada**: En `dev` (único ambiente) se aplica el mismo patrón que LAB-129: primero `chat-service` en paralelo + migración + corte de rutas del gateway y modo `http` en `eval` en una misma ventana; después una segunda release de `backend/` que retira `chat`, `orders`, `catalog` y `tickets`. No hay doble escritura.
- **Códigos de error preservados**: Se conservan los códigos `SLHD_CHAT_*` actuales (igual que se conservaron `SLHD_RETR_*` en LAB-129) para no romper consumidores ni pruebas existentes.
- **Sin autenticación JWT todavía**: Igual que `retrieval-service` y el monolito actual, `chat-service` no exige JWT hasta LAB-61; los controles existentes por token (`X-Prompt-Token` para override de prompts) se mantienen sin cambios.
- **Preservación de datos Olist**: Las migraciones `V2__olist_catalog_tables.sql` y `V2__olist_orders_tables.sql` ya fueron aplicadas en el PostgreSQL compartido; quitar sus archivos de `backend/` no altera las tablas existentes, que serán migradas o adoptadas por los servicios de F3/F4.

---

## Notes for Planning

Hallazgos del repositorio que el plan técnico debe resolver sin alterar el alcance:

- **`HttpAssistantSettingsClient` ya existe en `eval`**: `backend/src/main/java/com/shoplab/helpdesk/eval/infrastructure/out/chat/HttpAssistantSettingsClient.java` ya está implementado detrás de `shoplab.chat.settings.client.mode=http` (LAB-106), pero carece de timeout explícito, traducción de errores tipada, propagación verificada de cabeceras y uso de modelos generados desde `shoplab-contracts`. El plan debe endurecerlo y alinearlo con el patrón usado en `HttpKnowledgeSearchClient` (LAB-129).
- **Clave virtual de LiteLLM**: `infra/litellm/keys.yaml` define actualmente `backend-<env>` (`LITELLM_KEY_BACKEND`) con `[chat, chat-fallback]` (y `chat-cloud`). Con `chat-service` extraído, conviene crear una clave dedicada `chat-<env>` (`LITELLM_KEY_CHAT`) y retirar o vaciar la de `backend` una vez que `chat` salga del monolito (`eval` en `backend/` no llama a LiteLLM).
- **Contratos y stubs en `shoplab-contracts`**: Verificar qué operaciones de `chat` están hoy en el OpenAPI de `shoplab-contracts` (especialmente `/api/chat/feedback/**` además de `POST /api/chat` y `GET /api/assistant/settings`) y publicar los stubs de productor desde `shoplab-chat-service` bajo sus nuevas coordenadas Maven.
- **Revisión de `shoplab-infra` (`apps/chat-service`)**: Comprobar si ya existe un esqueleto en `shoplab-infra` y completar la configuración de DB, `retrieval-service`, LiteLLM, Langfuse (trazas y prompts), límites y secretos en el overlay `dev`.
- **Migración de `chat` y secuencias**: `conversation_turn.id` y `feedback.id` son `bigint GENERATED ALWAYS AS IDENTITY`; el script de migración debe restaurar con `pg_dump`/`pg_restore` y validar que las secuencias quedan avanzadas por encima del `MAX(id)` y que `feedback.turn_ref` mantiene integridad referencial.
- **Streaming SSE en WebFlux y timeouts**: Verificar que la configuración de timeouts del gateway (120 s) y del ingress de `dev` (60 s sin datos) no interfieren con el streaming SSE desde `chat-service`.

---

## Dependencies

- **LAB-103**: plantilla `shoplab-service-template` y workflows reutilizables de `shoplab-ci`.
- **LAB-104**: ApplicationSet y manifiestos `apps/<servicio>` en `shoplab-infra`.
- **LAB-106 / LAB-108**: contratos OpenAPI y paquetes/stubs versionados en `shoplab-contracts`.
- **LAB-107**: gateway estrangulador (`shoplab-gateway`) que mueve `/api/chat/**` y `/api/assistant/**`.
- **LAB-110**: entorno local multi-repo en `shoplab-helpdesk`.
- **LAB-129**: `retrieval-service` extraído y en operación (consumido por `chat-service` vía HTTP).
- **LAB-29**: suite de evaluación de calidad de respuesta usada como línea base antes y después del corte.
- **Posteriores**: LAB-130 (`eval-service` y retiro definitivo de `backend/`), LAB-113 / LAB-51 / LAB-53 (servicios de F3/F4 que adoptan los datos de Olist).
