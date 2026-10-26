# Research: Respuesta del chat en streaming (SSE)

**Feature**: LAB-12 | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)

No quedan marcadores `NEEDS CLARIFICATION` en la spec. Esta investigación fija las decisiones técnicas que la spec
delega al plan, y deja explícito lo que **no se pudo verificar sin ejecutar** (se convierte en la primera tarea).

## R1 — Pila servlet (MVC) y tipo de retorno del controlador

- **Decision**: mantener `spring-boot-starter-webmvc` (pila servlet) y devolver
  `Flux<ServerSentEvent<ChatEventPayload>>` desde el controlador, con `produces = text/event-stream`.
- **Rationale**: el backend ya es MVC (ADR-0004, LAB-11); Spring MVC soporta tipos reactivos como retorno y escribe
  cada elemento a medida que llega, que es justo el streaming pedido. Spring AI entrega el stream del modelo como
  `Flux`, así que no hay adaptación intermedia. La cancelación (cierre del cliente) se propaga como `cancel` al
  `Flux` y de ahí a la petición HTTP hacia Ollama.
- **Alternatives**: `SseEmitter` (hilo y callbacks manuales, más código y más fácil de fugar recursos); migrar a
  WebFlux (cambia la plataforma decidida en ADR-0004 sin necesidad: una sola ruta reactiva no lo justifica).
- **Nota**: la skill `reactive-programming` describe servicios 100 % WebFlux. Aquí solo se aplican sus reglas para la
  cadena de `Flux`: cero `.block()`, errores traducidos con `onErrorMap`/`onErrorResume`, efectos secundarios solo en
  `doOn*`.

## R2 — Acceso al modelo detrás de puerto

- **Decision**: puerto de salida `ChatModelPort` con `Flux<String> streamAnswer(String message)`; adaptador
  `OllamaChatAdapter` que usa el `ChatClient` de Spring AI (`prompt().user(..).stream().content()`).
- **Rationale**: constitución II y ADR-0003/0004 exigen LLM tras un puerto. `Flux<String>` (fragmentos de texto)
  basta: el dominio no necesita metadatos del proveedor. Un prompt de sistema mínimo y fijo vive en el adaptador
  (configuración), no en el caso de uso.
- **Alternatives**: exponer `ChatClient` al caso de uso (acopla aplicación a Spring AI); usar `Flow.Publisher` para
  no depender de Reactor en el puerto (descartado: Reactor ya está en el classpath y es la API de Spring AI).
- **Por verificar** (tarea inicial T001): el *starter* de Ollama de Spring AI **2.0.2**. Solo hay 1.1.8 en la caché
  local (`spring-ai-starter-model-ollama`), así que el nombre del artefacto y la API de `ChatClient` en 2.0 se
  confirman resolviendo el árbol de dependencias antes de escribir código. También se confirma si el starter trae
  `spring-webflux` (necesario para el streaming) o hay que añadirlo.

## R3 — Contrato de eventos

- **Decision**: tres tipos de evento SSE, con `event:` nombrado y `data:` JSON:
  - `token` → `{"content":"<fragmento>"}`
  - `done` → `{"fragments":<n>}`
  - `error` → `{"code":"SLHD_CHAT_0000N","message":"<descripción>"}`
- **Rationale**: nombres explícitos permiten al cliente (Angular `EventSource`/`fetch`) distinguir fin y error sin
  heurísticas (FR-003/FR-004). `fragments` permite comprobar SC-004 sin recontar. JSON en `data` evita problemas
  con saltos de línea dentro del texto (un fragmento con `\n` rompería un `data:` en texto plano).
- **Alternatives**: `data` en texto plano (ambigüedad con saltos de línea); señal de fin `[DONE]` estilo OpenAI
  (sin canal de error estructurado); NDJSON (no es SSE, no lo pide la historia).
- **Nota**: `EventSource` del navegador solo soporta GET; el frontend consumirá el `POST` con `fetch` +
  `ReadableStream` o una librería SSE. No afecta a este contrato, pero se documenta en `contracts/`.

## R4 — Errores antes y después del primer evento

- **Decision**: el caso de uso devuelve `Flux<ChatEvent>` y lleva un indicador «ya emitió». Un error **antes** del
  primer elemento se propaga como `TechnicalException` con `SLHD_CHAT_00002` y el `GlobalExceptionHandler` responde 503. Un error **después** se convierte en un evento `error` final y el flujo se completa.
- **Rationale**: tras enviar la cabecera 200 ya no se puede cambiar el estado HTTP; antes sí, y 503 es más útil que
  un flujo con un solo evento de error (FR-008). El código del evento sale de la misma excepción tipada y del mismo
  `ResponseCodeEnum` (FR-009).
- **Alternatives**: siempre 200 + evento `error` (el cliente tiene que mirar dentro del flujo para saber que el
  modelo está caído; peor para sondas y pruebas); precomprobar la salud de Ollama antes de cada petición (añade
  latencia al primer token y carrera entre la comprobación y la llamada).
- **Riesgo / por verificar**: que en Spring MVC un `Flux` que falla antes de emitir llegue al
  `@ExceptionHandler` con la respuesta aún sin confirmar. Se cubre con una prueba de integración (escenario US3-3);
  si no se comporta así, el plan de contingencia es obtener el primer elemento con `Flux.switchOnFirst`/`next()`
  en el controlador antes de devolver el flujo.
- **Respuestas de error con `Accept: text/event-stream`**: el manejador debe fijar explícitamente
  `Content-Type: application/json` en la respuesta de error; si no, la negociación de contenido responde 406 al
  cliente SSE. Se cubre con una prueba.

## R5 — Módulo de errores compartido

- **Decision**: crear un módulo Modulith abierto `shared` con `ResponseCodeEnum`, las excepciones tipadas base,
  `ExceptionUtil` y el `GlobalExceptionHandler`, y actualizar `ModularityTests` (5.º módulo esperado).
- **Rationale**: hoy no existe infraestructura de errores en el backend (solo hay `package-info`). La guía exige un
  único `GlobalExceptionHandler` y un único `ResponseCodeEnum`, y los módulos `retrieval`, `tools` y `tickets`
  los necesitarán. Cualquier paquete de primer nivel nuevo es un módulo para Modulith, por eso se declara
  explícitamente como abierto en vez de dejarlo implícito.
- **Alternatives**: dejarlo dentro de `chat` (otros módulos tendrían que depender de `chat` para lanzar errores,
  invirtiendo la dependencia); ponerlo en el paquete raíz (no es módulo, pero mezcla infraestructura con la clase
  `Application` y queda sin frontera verificable).
- **Consecuencia**: se registra en el ADR de seguimiento (constitución VII) y se corrige la lista de módulos en
  `README.md`/ADR-0004 si el texto la cita como cerrada.

## R6 — Correlación y logs

- **Decision**: filtro de servlet que toma `X-Request-Id` de la petición o genera un UUID, lo pone en el MDC y lo
  devuelve en la cabecera de respuesta. El `requestId` viaja también dentro del comando para registrar desde la
  cadena reactiva sin depender del MDC.
- **Rationale**: el MDC es por hilo y el `Flux` salta de hilo; llevar el id en el comando evita perderlo. No hay
  librería de trazas en el backend todavía (Micrometer Tracing llegaría con la observabilidad de fases posteriores).
- **Alternatives**: añadir Micrometer Tracing ahora (alcance mayor que la historia); hooks de propagación
  automática de contexto de Reactor (frágil y no necesario aquí).
- Los logs registran longitud del mensaje y resultado, **nunca** su contenido (FR-014).

## R7 — Métricas

- **Decision**: puerto `ChatMetricsPort` con adaptador Micrometer. Métricas sobre el registro Prometheus ya
  existente (LAB-102):
  - `shoplab.chat.first.token` (Timer, histograma): tiempo desde la recepción hasta el primer fragmento.
  - `shoplab.chat.response` (Timer, histograma) con etiqueta `outcome` = `completed` | `error` | `cancelled`.
- **Rationale**: FR-013 y la verificación objetiva de SC-001 sin cronometrar a mano. Etiquetas con valores fijos →
  cardinalidad acotada. Se reutiliza la etiqueta común `application` ya configurada.
- **Alternatives**: contador + `LongTaskTimer` (más series sin más valor); medir solo en el cliente (no verificable
  en CI ni en el clúster).

## R8 — Configuración

- **Decision**: propiedades de Spring AI para Ollama (`spring.ai.ollama.base-url` por defecto
  `http://localhost:11434`; `spring.ai.ollama.chat.options.model: ${OLLAMA_CHAT_MODEL:llama3.2:3b}`) y propiedades
  propias bajo `shoplab.chat` (`max-message-length: 4000`, `system-prompt`) en un `@ConfigurationProperties` del
  paquete `config`.
- **Rationale**: FR-012; coincide con `infra/.env.example`. El mantenimiento del modelo caliente se afina con la
  opción `keep-alive` de Ollama si la medición lo pide (SC-001); no se fija por adelantado.
- **Por verificar**: el starter de Ollama también autoconfigura embeddings; si exige un modelo de embeddings sin
  usarse, se desactiva con `spring.ai.model.embedding=none` (o el equivalente de 2.0) hasta la historia de RAG.

## R9 — Estrategia de pruebas (sin LLM real en CI)

- **Decision**:
  - Caso de uso: pruebas unitarias con `StepVerifier` (`io.projectreactor:reactor-test`, gestionado por Boot) y
    dobles de `ChatModelPort`/`ChatMetricsPort`.
  - Controlador y manejador: `@WebMvcTest` con un `ChatModelPort` falso (validación 400, forma de los eventos,
    errores).
  - Adaptador y de punta a punta: WireMock simulando `POST /api/chat` de Ollama con respuesta NDJSON troceada y
    con retardos (`wiremock-standalone`, ya usado en la guía del proyecto), más una prueba `@SpringBootTest` con
    cliente HTTP real que mide orden, completitud, UTF-8, concurrencia y cancelación.
  - Verificación real: guion de `quickstart.md` contra Ollama local; no corre en CI (constitución IV).
- **Rationale**: pruebas deterministas y sin modelo, y aun así con el cable real (HTTP + SSE) en el medio.
- **Alternatives**: Testcontainers con Ollama (descarga de modelo, lento, no determinista); grabar respuestas reales
  (útil más adelante para RAG/evaluación, innecesario aquí).

## R10 — Sin buffering en intermediarios

- **Decision**: la respuesta lleva `Cache-Control: no-cache` y `X-Accel-Buffering: no`; la compresión no se aplica
  a `text/event-stream`.
- **Rationale**: un proxy o ingreso de Kubernetes que acumule la respuesta anularía el streaming (caso límite de la
  spec). No hay ingreso del chat desplegado todavía; queda documentado para el despliegue.

## Resumen de pendientes de verificación

| Pendiente | Dónde se resuelve |
|-----------|-------------------|
| Artefacto/API del starter Ollama en Spring AI 2.0.2 y presencia de `spring-webflux` | Tarea inicial: resolver dependencias y compilar un adaptador mínimo |
| `Flux` que falla antes del primer elemento llega al `@ExceptionHandler` (503) | Prueba de integración US3-3 |
| Respuesta de error JSON con `Accept: text/event-stream` | Prueba del manejador |
| Cancelación al cerrar el cliente llega hasta la llamada a Ollama (SC-005) | Prueba con WireMock + verificación manual |
| Embeddings autoconfigurados sin uso | Arranque del contexto en `@SpringBootTest` |

## Verificaciones realizadas en la implementación (2026-10-26)

- **R2 / R8 (T002)**: `org.springframework.ai:spring-ai-starter-model-ollama:2.0.2` resuelve con el BOM y trae
  `spring-ai-ollama`, `spring-ai-client-chat` (`ChatClient`), `spring-webflux` 7.0.10 y `reactor-netty-http` 1.3.8:
  no hace falta añadir `spring-boot-starter-webflux`. La pila sigue siendo servlet (`spring-webmvc`).
- **R5 (T004)**: `org.springframework.modulith.ApplicationModule` expone `Type.OPEN` y `Type.CLOSED`; el módulo
  `shared` usa `@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)`.
- **R9 (T003)**: `spring-boot-starter-webmvc-test` 4.1.2, `reactor-test` 3.8.8 y `wiremock-standalone` 3.5.4
  resuelven; `awaitility` 4.3.0 ya llega con `spring-boot-starter-test`.
- **R8 (keep-alive)**: la medición en vivo mostró que Ollama descarga el modelo a los 5 minutos de inactividad. Se fija
  `spring.ai.ollama.chat.options.keep-alive: ${OLLAMA_KEEP_ALIVE:30m}`; `ollama ps` pasó de `UNTIL ~5 minutes` a
  `29 minutes from now`. Una prueba del adaptador comprueba que `keep_alive` viaja en la petición.
- Fixture de Ollama (T005): la última línea de la respuesta trae `content` vacío con `done:true`; el adaptador
  descarta los fragmentos vacíos para que `fragments` cuente solo texto real.

