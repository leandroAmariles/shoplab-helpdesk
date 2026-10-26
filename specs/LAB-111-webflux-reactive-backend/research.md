# Research: Backend reactivo con Spring WebFlux

**Feature**: LAB-111 | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)

No quedan marcadores `NEEDS CLARIFICATION` en la spec. Esta investigación fija las decisiones técnicas que la spec
delega al plan y deja explícito lo que **no se pudo verificar sin ejecutar**; eso se convierte en la primera tarea
(spike T001), como en LAB-12.

## Estado de partida (verificado en el código)

- El camino del chat **ya es reactivo por dentro**: `ChatModelPort` devuelve `Flux<String>`, `StreamChatServicePort`
  devuelve `Flux<ChatEvent>` y `OllamaChatAdapter` usa `ChatClient.stream().content()`. El cambio real está en el
  borde: el contenedor (Tomcat/servlet → Netty), el controlador, el filtro de correlación, el manejador global de
  errores y los tests.
- Clases con API servlet/MVC que hay que reescribir: `RequestIdFilter` (`OncePerRequestFilter`, `MDC`),
  `GlobalExceptionHandler` (`ResponseEntityExceptionHandler` de MVC, `HttpMessageNotReadableException`), y
  `ChatController` (`@RequestAttribute` sobre atributos de petición servlet).
- Tests con API MVC: `ChatControllerTests` y `GlobalExceptionHandlerTests` (`@WebMvcTest`/`MockMvc`),
  `RequestIdFilterTests` (`MockHttpServletRequest`). `ChatStreamingIntegrationTests` y `MetricsEndpointTests` hablan
  HTTP real con `java.net.http.HttpClient` y siguen valiendo, pero la spec pide pasarlos a `WebTestClient`.
- **No hay JDBC hoy**: el `pom.xml` solo trae `spring-modulith-starter-core` (sin starter JDBC, sin Flyway, sin
  `spring-ai-starter-vector-store-pgvector`, sin `DataSource`). La spec decía que Modulith y Flyway ya eran JDBC en
  el camino del chat; es falso por ahora (se corrige en la spec). Consecuencia: esta historia **no migra ningún
  acceso a datos**, solo deja establecido y probado el patrón de aislamiento que usarán LAB-14/LAB-105 y las
  historias de RAG.
- Ya existen `reactor-test` y WireMock en dependencias de test. `ArchUnit` llega transitivamente con
  `spring-modulith-core`.
- El repositorio local de Maven tiene `spring-boot-starter-webflux` y `spring-boot-starter-webflux-test` 4.0.5;
  para 4.1.2 se resuelven en línea. BlockHound: última versión publicada `1.0.11.RELEASE` (Maven Central).

## R1 — Pila web: `spring-boot-starter-webflux` (Netty) sustituye a `webmvc`

- **Decision**: reemplazar `spring-boot-starter-webmvc` por `spring-boot-starter-webflux` y
  `spring-boot-starter-webmvc-test` por `spring-boot-starter-webflux-test`. No convivir con ambos (con los dos en el
  classpath Boot elige servlet y ignora Netty).
- **Rationale**: es el criterio 1 de la historia. El actuator y Micrometer se autoconfiguran también sobre WebFlux
  (`MetricsWebFilter`), en el mismo puerto 8080.
- **Alternatives**: mantener MVC devolviendo `Flux` (lo que hace LAB-12 y funciona) — se descarta por la historia y se
  argumenta en el ADR (R9); MVC + hilos virtuales — alternativa descartada documentada en el ADR.
- **Por verificar (T001)**: nombres exactos de artefactos y paquetes de Boot 4.1.2 para `@WebFluxTest` y
  `WebTestClient` (en Boot 4 los slices viven en módulos propios, p. ej. `org.springframework.boot.webflux.test.
  autoconfigure`); que Spring AI 2.0.2 arranque sin servlet.

## R2 — Controlador y contrato SSE idéntico

- **Decision**: el controlador conserva `POST /api/chat`, `produces = text/event-stream`, y devuelve
  `ResponseEntity<Flux<ServerSentEvent<Object>>>` con `Cache-Control: no-cache` y `X-Accel-Buffering: no`. Lo único
  que cambia es de dónde lee el `requestId` (atributo del `ServerWebExchange`) y que el mapper sigue siendo el mismo
  (`ChatEventMapper`, `ChatEventPayload`).
- **Rationale**: cero cambios en puertos, caso de uso, mapper, DTOs y métricas → el contrato lo protege el propio
  diseño; solo se reemplaza el pegamento web.
- **Riesgo 1 — bytes del SSE**: el escritor SSE de WebFlux y el de MVC deberían producir `event:token\ndata:{...}\n\n`
  idénticos, pero no se asume. **Mitigación**: antes de migrar (en la rama base) capturar el cuerpo SSE crudo de
  `ChatStreamingIntegrationTests` con la fixture de Ollama y guardarlo como *golden file*
  (`src/test/resources/chat/golden-stream.txt`); tras migrar, una prueba compara byte a byte. Cubre SC-001.
- **Riesgo 2 — errores antes del primer evento**: hoy un `Flux.error` previo al primer elemento acaba como JSON 400/503
  gracias a `GlobalExceptionHandler`. En WebFlux el error ocurre al escribir la respuesta; si la respuesta no está
  confirmada aún, el `@ExceptionHandler` lo traduce, pero la negociación de contenido puede dar **406** porque el
  handler mapping fija `PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE = text/event-stream` y el cliente pide `text/event-stream`.
  **Mitigación**: en el manejador, eliminar ese atributo del `ServerWebExchange` antes de devolver el
  `ResponseEntity` con `Content-Type: application/json` (equivalente al truco de LAB-12 en MVC). Verificado por las
  pruebas de 400/503 con `Accept: text/event-stream`.
- **Riesgo 3 — entradas de usuario**: `{"message":` mal formado llega como `ServerWebInputException`
  (causa `DecodingException`), no como `HttpMessageNotReadableException`; `{}` y `null` llegan al caso de uso con
  `message == null` (se valida allí, igual que hoy). Se mapea `ServerWebInputException` →
  `INVALID_REQUEST_BODY` (`SLHD_GENE_00002`, 400).
- **Alternatives**: `Flux<ChatEventPayload>` + `@ResponseBody` con `produces` (el cliente perdería el nombre de
  evento: descartado); `ServerResponse` funcional con router (reescribe más sin beneficio).

## R3 — Cancelación de extremo a extremo

- **Decision**: no añadir código de cancelación; confiar en que Netty propaga el cierre del cliente como `cancel()` al
  `Flux` del controlador → caso de uso (`doOnCancel` ya registra `ChatOutcome.CANCELLED`) → `ChatClient.stream()` →
  `WebClient` de Spring AI, que cierra la conexión con Ollama.
- **Rationale**: es exactamente lo que ofrece la cadena reactiva; cualquier operador intermedio que "trague" la
  cancelación sería el bug. Por eso se protege con dos pruebas: (a) unitaria con `TestPublisher`
  (`assertCancelled()`) sobre caso de uso y adaptador; (b) integración con WireMock `withChunkedDribbleDelay` y
  `StepVerifier.thenCancel()` sobre `WebTestClient`, comprobando la métrica `outcome="cancelled"` y que Ollama
  deja de recibir demanda.
- **Por verificar (T001)**: que la cancelación llega a Ollama en menos de 2 s con Netty (SC-003) y que no se
  registra como error.

## R4 — Llamadas HTTP salientes con `WebClient`

- **Decision**: el único cliente HTTP saliente hoy es el de Spring AI hacia Ollama, que para `stream()` ya usa
  `WebClient`. Se configura exponiendo el `WebClient.Builder` de Boot (sin código nuevo) y se **prohíbe por regla
  ArchUnit** `RestTemplate`, `RestClient`, `HttpURLConnection` y `java.net.http.HttpClient` en `src/main`.
- **Rationale**: el criterio 4 es una invariante de arquitectura más que una migración; lo barato y robusto es
  una prueba que falle si alguien la rompe, en lugar de una convención.
- **Nota**: `ChatClient.call()` (no streaming) usaría `RestClient` bloqueante; esta historia no lo usa y la regla
  ArchUnit se limita a código propio, no a Spring AI.
- **Alternatives**: wrapper propio sobre `WebClient` (YAGNI hasta que haya un segundo cliente HTTP).

## R5 — Persistencia JDBC aislada en `Schedulers.boundedElastic()`

- **Decision**: añadir en `shared/infrastructure/out/blocking/` un pequeño ayudante `BlockingCalls`:
  `Mono<T> call(Callable<T>)`, `Mono<Void> run(Runnable)` y `Flux<T> stream(Callable<? extends Iterable<T>>)`, cada uno
  `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. Es lo **único** que menciona `Schedulers`.
  Los adaptadores JDBC futuros (`..infrastructure.out.jdbc..`) lo usan; dominio, aplicación y controlador no lo ven.
- **Rationale**: el criterio 5 pide el patrón y la spec (US3.3) pide dejarlo preparado y probado; sin consumidor real
  hoy, el ayudante mínimo + prueba con BlockHound es el menor artefacto que lo demuestra (constitución VII,
  justificado en Complexity Tracking). Evita que cada módulo reinvente el `subscribeOn`.
- **Regla ArchUnit (FR-007)**: ninguna clase en `..domain..`, `..application..` o `..in.rest..` depende de
  `reactor.core.scheduler.Schedulers`, `javax.sql`, `java.sql` ni `org.springframework.jdbc`; `Schedulers` solo
  puede usarse desde `..infrastructure..` o `shared..blocking`.
- **Cancelación y `boundedElastic`**: cancelar una suscripción interrumpe el hilo en `subscribeOn`; JDBC puede
  ignorar la interrupción. Documentado como límite aceptado (Edge Case de la spec): el resultado se descarta.
- **Alternatives**: `@Async`/`TaskExecutor` (fuera del modelo Reactor, pierde cancelación y contexto);
  `Schedulers.fromExecutor` con hilos virtuales (decisión de ADR: se descarta de momento, ver R9).

## R6 — BlockHound en los tests

- **Decision**: `io.projectreactor.tools:blockhound:1.0.11.RELEASE` (scope test). Instalación **una vez por ejecución**
  con un `LauncherSessionListener` de JUnit Platform registrado en
  `META-INF/services/org.junit.platform.launcher.LauncherSessionListener` que llama
  `BlockHound.install(new HelpdeskBlockHoundIntegration())`. Surefire recibe
  `-XX:+AllowRedefinitionToAddDeleteMethods` (necesario en JDK 13+) **sin romper el `argLine` de JaCoCo**
  (`@{argLine} -XX:+AllowRedefinitionToAddDeleteMethods`).
- **Rationale**: instalar vía listener evita repetir `@BeforeAll` en cada clase y garantiza que la detección esté
  activa también en los `@SpringBootTest`. `boundedElastic` está permitido por defecto; el bucle de Netty y
  `parallel`/`single` no.
- **Prueba negativa (FR-009)**: un test que hace `Mono.fromCallable(() -> { Thread.sleep(10); return 1; })
  .subscribeOn(Schedulers.parallel())` y espera `BlockingOperationError`; y su contraparte en `boundedElastic` que
  pasa. Demuestra que la detección está activa en esta JVM de pruebas.
- **Riesgos conocidos (T001)**:
  1. *Logging por consola en el bucle de eventos* (`FileOutputStream.writeBytes`) — falso positivo habitual con
     Logback; se permite solo en `ch.qos.logback.core.OutputStreamAppender` (`writeBytes`/`encode`/`append`).
  2. *`UUID.randomUUID()` / `SecureRandom`* en `RequestIdFilter` puede leer `/dev/urandom` la primera vez; se
     prefiere calentar el generador al arrancar el filtro o, si BlockHound lo marca, permitirlo con comentario.
  3. *WireMock/Jetty y clientes de prueba* corren en hilos que no son del bucle de eventos: no se esperan falsos
     positivos; cualquier otro se permite caso a caso, por clase y método concretos, nunca por paquete.
  4. *Arranque de Spring* en el hilo `main`: no es hilo no bloqueante, no hay allowance.
- **Regla**: cada allowance vive en `HelpdeskBlockHoundIntegration`, con comentario del porqué. Sin comodines.
- **Alternatives**: `reactor-tools` `ReactorDebugAgent` (otra cosa: depuración); detectar bloqueos solo por revisión
  de código (no automatizable, descartado por el criterio 6).

## R7 — Filtro de correlación y propagación entre hilos

- **Decision**: reemplazar `RequestIdFilter` por un `WebFilter` (`Ordered.HIGHEST_PRECEDENCE`) que resuelve el
  identificador con las mismas reglas (cabecera `X-Request-Id` válida o UUID nuevo), lo guarda como atributo del
  `ServerWebExchange`, lo escribe en la respuesta y llama `chain.filter(exchange)` con
  `.contextWrite(ctx -> ctx.put("requestId", id))`. El controlador lo lee del atributo (`@RequestAttribute` funciona en
  WebFlux) y el caso de uso sigue pasándolo explícito a los logs (ya lo hace).
- **MDC**: se activa `spring.reactor.context-propagation: auto` y se registra el acceso al MDC (`requestId`) mediante
  `io.micrometer:context-propagation` para que **cualquier** línea de log dentro de la cadena (Spring AI incluido)
  lleve el identificador aunque cambie de hilo (FR-013). Si el accesor de MDC no estuviera disponible en la versión
  que trae Boot 4.1.2, se mantiene la práctica explícita de LAB-12 (`requestId` como argumento del log) y se
  documenta.
- **Rationale**: en reactivo el `ThreadLocal` del MDC no sobrevive a los saltos de hilo; el contexto de Reactor sí.
- **Alternatives**: `Hooks.enableAutomaticContextPropagation()` a mano (es lo que hace la propiedad); un
  `ThreadLocalAccessor` propio (más código sin necesidad).
- **Por verificar (T001)**: test que emite en `boundedElastic` y comprueba `MDC.get("requestId")` dentro de un
  `doOnNext`.

## R8 — Métricas HTTP intactas

- **Decision**: ningún cambio de código de métricas. Se conserva la configuración de `application.yml`
  (`management.metrics.distribution.percentiles-histogram.http.server.requests`, etiqueta común `application`). Antes
  de tocar el `pom.xml` se captura una **línea base** de nombres y etiquetas de `/actuator/prometheus` con la build
  de MVC, y tras migrar se compara.
- **Rationale**: Boot registra `http.server.requests` en ambos modelos con las etiquetas `method`, `uri`, `status`,
  `outcome`, `exception`; lo que puede cambiar es el valor de `uri`/`outcome` en peticiones canceladas o sin ruta, que
  pueden romper paneles. No se asume: se mide.
- **Cobertura**: `MetricsEndpointTests` queda como prueba de regresión (la ruta de sondeo `/probe/{id}` sigue siendo
  válida en WebFlux), más una comprobación de las métricas del chat ya existentes en `ChatStreamingIntegrationTests`.
- **Por verificar (T001)**: etiqueta `uri` de `/api/chat` agrupada, y comportamiento de `outcome`/`status` en
  cancelación (puede aparecer `UNKNOWN`/`CLIENT_ERROR`); se documenta como diferencia aceptada si los paneles de
  LAB-102 no la usan.

## R9 — Contenido del ADR «Web reactiva con persistencia JDBC» (ADR-0011)

- **Decision**: crear `docs/adr/0011-web-reactiva-persistencia-jdbc.md` con la plantilla y la fila del índice. Estado
  *aceptado*, fecha de la implementación.
- **Contexto**: el chat es un flujo largo de eventos; la persistencia prevista (pgvector, ChatMemory, Modulith, Flyway)
  es JDBC (ADR-0002, ADR-0004).
- **Decisión**: WebFlux + Netty en la capa web y cliente del modelo; JDBC se mantiene y se aísla en
  `Schedulers.boundedElastic()` dentro de `infrastructure`; BlockHound garantiza la regla en CI.
- **Alternativas descartadas** (cada una con su razón):
  - *R2DBC*: pgvector en Spring AI, Spring Modulith (registro de publicación de eventos) y Flyway dependen de JDBC;
    migrarlos supone duplicar adaptadores y perder soporte.
  - *MVC con hilos virtuales*: más sencillo y válido, pero un hilo (virtual) por stream, cancelación vía
    interrupción en lugar de señal reactiva, `synchronized`/`ThreadLocal` y *pinning* con drivers, y la cadena de
    Spring AI ya es `Flux`; se decide por reactivo de extremo a extremo y se revisa si BlockHound o el aislamiento
    resultan una carga excesiva.
  - *MVC devolviendo `Flux` (estado LAB-12)*: funciona, pero reserva un hilo servlet por stream abierto y deja la
    detección de bloqueos sin guardia.
- **Consecuencias**: coste del contexto reactivo (MDC, transacciones `@Transactional` no cruzan `boundedElastic`),
  pruebas más verbosas, y regla explícita «todo acceso bloqueante pasa por `BlockingCalls`».
- **Nota**: el ADR debe ser honesto sobre que LAB-12 ya demostró que MVC soporta `Flux`; el motivo para WebFlux es el
  modelo de extremo a extremo y la guardia automática, no una limitación de MVC.

## R10 — Estrategia de migración de pruebas

- **Decision**: convertir cada prueba en su equivalente reactivo y borrar las clases MVC al terminar:

| Hoy | Después |
|-----|---------|
| `ChatControllerTests` (`@WebMvcTest`, `MockMvc`, `asyncDispatch`) | `@WebFluxTest(ChatController.class)` + `WebTestClient` + `StepVerifier` sobre `returnResult(...).getResponseBody()` |
| `GlobalExceptionHandlerTests` (`MockMvc`) | `@WebFluxTest` con `ProbeController` + `WebTestClient` |
| `RequestIdFilterTests` (`MockHttpServletRequest`) | `MockServerWebExchange` + `StepVerifier` sobre `filter.filter(exchange, chain)` |
| `ChatStreamingIntegrationTests` + `SseTestClient` (`java.net.http`) | `@SpringBootTest(RANDOM_PORT)` + `WebTestClient` (con `responseTimeout` ajustado); `SseTestClient` se elimina |
| `MetricsEndpointTests` | sin cambios funcionales; se ejecuta bajo BlockHound |
| `StreamChatUseCaseTests`, `OllamaChatAdapterTests` | ya usan `StepVerifier`; se añaden casos de cancelación con `TestPublisher` |

- **Rationale**: mismos escenarios que LAB-12 (FR-010), menos código de cliente a mano. Los tiempos de primer token
  se miden con `Flux.timestamp()`/`elapsed()` en el cliente de pruebas.
- **Cobertura**: `config`, `dto`, `model`, `exception` siguen excluidos de JaCoCo; el nuevo `BlockingCalls` y el
  `WebFilter` cuentan y se cubren con pruebas dedicadas.

## R11 — Operación y despliegue

- **Decision**: sin cambios en `Dockerfile`, puerto 8080, sondas de salud ni variables de entorno. Se documenta que
  el servidor ya no es Tomcat (sin propiedades `server.tomcat.*`; no hay ninguna en `application.yml`).
- **Rationale**: Netty usa memoria directa; con los límites de memoria del contenedor actuales no se espera
  problema, pero se anota para vigilar en el clúster (LAB-102).

## Resumen de riesgos que cubre el spike T001

1. Nombres de artefactos/paquetes de Boot 4.1.2 para WebFlux y su slice de pruebas.
2. Bytes del SSE idénticos (golden file) y cabeceras (`Content-Type`, `Cache-Control`, `X-Accel-Buffering`).
3. Errores previos al primer evento como JSON 400/503 con `Accept: text/event-stream` (sin 406).
4. Falsos positivos de BlockHound (logging, `SecureRandom`) y su lista mínima de allowances.
5. Propagación de MDC entre hilos.
6. Etiquetas de `http.server.requests` y `outcome` en cancelación.

## Hallazgos de la implementación (2026-10-26)

- **R1 (artefactos)**: Boot 4.1.2 resuelve `spring-boot-starter-webflux` (Netty 4.2, `reactor-netty-http` 1.3.8) y
  `spring-boot-starter-webflux-test` (trae `spring-boot-webflux-test` y `spring-boot-webtestclient`). Paquetes reales:
  `org.springframework.boot.webflux.test.autoconfigure.WebFluxTest` y
  `org.springframework.test.web.reactive.server.WebTestClient`. El árbol no contiene Tomcat, `spring-webmvc` ni
  servlet. `context-propagation` 1.2.1 llega transitivo; `junit-platform-launcher` hubo que añadirlo (test).
- **R2 (bytes del SSE)**: el escritor SSE de WebFlux produjo exactamente los mismos bytes que MVC (golden file
  `chat/golden-stream.txt`, capturado antes de migrar). El controlador no necesitó cambios: `@RequestAttribute`,
  `ResponseEntity<Flux<ServerSentEvent<Object>>>` y `produces` funcionan igual.
- **R2 (errores previos al primer evento)**: con `PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE` eliminado del exchange en el
  manejador, 400/503 salen como JSON también con `Accept: text/event-stream`; `ServerWebInputException` cubre el JSON
  ilegible. Un cliente que solo acepta `application/json` sigue recibiendo 406 (comportamiento de LAB-12).
- **R3 (cancelación)**: no hizo falta código; cerrar la conexión cancela la cadena y el contador `outcome="cancelled"`
  sube en 1 (sin `error`). Con Ollama real: +1 y la siguiente petición responde en ~110 ms.
- **R6 (BlockHound)**: la suite completa no necesitó ningún allowance; ni el logging por consola ni `UUID.randomUUID()`
  fueron marcados. La integración `HelpdeskBlockHoundIntegration` queda vacía a propósito.
- **R7 (MDC)**: `Slf4jThreadLocalAccessor("requestId")` registrado en `ReactorContextConfig` +
  `spring.reactor.context-propagation=auto` deja `requestId` en el MDC de un hilo `boundedElastic` (prueba
  `RequestIdPropagationTests`). El filtro escribe el mapa del MDC en el contexto de Reactor bajo la clave
  `Slf4jThreadLocalAccessor.KEY`.
- **R8 (métricas)**: la comparación contra la línea base solo muestra la desaparición de `tomcat_sessions_*`; las
  series `http_server_requests_*` conservan nombre y etiquetas (incluida `error`). Ver `validation.md`.
- **R12 (nuevo) — `ChatClient.stream()` fija un hilo por stream**: el asesor terminal `ChatModelStreamAdvisor` de
  Spring AI 2.0.2 hace `chatModel.stream(prompt).publishOn(Schedulers.boundedElastic())`. Con 50 streams simultáneos
  los hilos pasaron de 40 a 86 (50 hilos `boundedElastic-N`), contra 40 → 40 con `ChatModel.stream()`. El criterio 2 de
  la historia decía «a partir de `ChatClient.stream()`»; **decisión del responsable (2026-10-26)**: usar
  `ChatModel.stream()` en `OllamaChatAdapter` porque cumple la intención (sin un hilo por petición) con la misma
  petición a Ollama y el mismo contrato (22 pruebas de contrato y golden idénticos). Protegido por
  `OllamaChatAdapterTests#emitsOnTheHttpClientThread...`. Consecuencia: el caso de uso corre en el hilo de Netty, así
  que BlockHound lo vigila de verdad (comprobado metiendo un `Thread.sleep`).
