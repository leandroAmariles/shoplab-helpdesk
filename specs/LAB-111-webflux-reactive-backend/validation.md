# Validación: Backend reactivo con Spring WebFlux

**Feature**: LAB-111 | Registro de resultados de verificación (se completa durante la implementación).

## Línea base (T001) — rama base, MVC, 2026-10-26

- `./mvnw -B -ntp clean verify -o` en `backend/`: **BUILD SUCCESS** (≈ 26 s).
- Pruebas: 73 ejecutadas, 0 fallos, 0 errores, 0 omitidas.
- Cobertura JaCoCo de líneas: 114 cubiertas, 0 perdidas (100 %) sobre las clases no excluidas.
- Checkstyle: 0 violaciones. PMD: sin violaciones.

## Resultados de la implementación (2026-10-26)

### Suite automatizada (T044)

- `./mvnw -B -ntp clean verify` en `backend/`: **BUILD SUCCESS**.
- Pruebas: **101** ejecutadas, 0 fallos, 0 errores, 0 omitidas (línea base MVC: 73).
- Cobertura JaCoCo de líneas: 119 cubiertas, 0 perdidas (**100 %**, umbral 80 %; línea base 100 %).
- Checkstyle: 0 violaciones. PMD: sin violaciones.
- Servidor en las pruebas: `Netty started on port ...`; el árbol de dependencias no contiene Tomcat, `spring-webmvc` ni
  servlet.

### Contrato idéntico (US1)

- `ChatStreamContractTests` compara los bytes del flujo SSE con `chat/golden-stream.txt`, capturado en MVC antes de
  migrar: **idéntico**.
- Los 10 escenarios de `ChatStreamingIntegrationTests`, los de `ChatModelDown*`/`ChatMidStream*` y `ChatControllerTests`
  pasan reescritos con `WebTestClient`/`StepVerifier`. `SseTestClient` eliminado.
- Manual con Ollama real (backend migrado, `llama3.2:3b` caliente): primer byte del cuerpo en **64–99 ms** en 10
  mediciones consecutivas (SC-002: ≥ 9 de 10 < 2 s). La primera petición en frío tardó ~7 s (carga del modelo; fuera del
  criterio, como en LAB-12). `time_starttransfer` de curl no sirve para esto en Netty: mide las cabeceras, no el primer
  token.
- Errores con curl: `{"message":"   "}` → 400 `SLHD_CHAT_00001`; JSON ilegible → 400 `SLHD_GENE_00002` con
  `X-Request-Id` devuelto; `Accept: application/json` → 406. El 503 con Ollama parado está cubierto por
  `ChatModelDownIntegrationTests` (no se paró el contenedor de Ollama del entorno).
- UTF-8: la respuesta a un mensaje con tildes y emoji llega íntegra (11 eventos `token`, 0 errores en el log).

### Cancelación (US2)

- Pruebas: `StreamChatUseCaseTests` (cancelación, `TestPublisher.assertCancelled()` + sin suscriptores),
  `OllamaChatAdapterTests` (cancelar vuelve en < 3 s sin esperar al modelo) y la de integración con `thenCancel()` y
  contador `outcome="cancelled"` (+1, sin `error`).
- Manual con Ollama real: `timeout 2 curl -N` sobre una respuesta larga → `shoplab_chat_response_seconds_count
  {outcome="cancelled"}` pasa de 11 a 12 y la petición siguiente responde en ~110 ms. No se midió la carga de CPU/GPU de
  Ollama; la cancelación del upstream se comprueba por las pruebas de unidad y de integración.

### Nada bloquea el bucle de eventos (US3)

- BlockHound se instala una vez por sesión de JUnit (`BlockHoundSessionListener`). Pruebas: `BlockHoundDetectionTests`
  (bloqueo en `parallel` → `BlockingOperationError`; en `boundedElastic` pasa) y `EventLoopGuardTests` (un handler que
  bloquea el hilo de Netty falla con `Blocking call! java.lang.Thread.sleep0`; aislado, responde 200).
- Ningún allowance (`HelpdeskBlockHoundIntegration` queda vacía). Ver el fallo de CI en Linux más abajo.
- Prueba manual de la guardia (T035, no commiteada): un `Thread.sleep(50)` metido en el `doOnNext` de
  `StreamChatUseCase` hace fallar 6 de 13 pruebas de `ChatStreamingIntegrationTests` con `BlockingOperationError`
  (el error aparece en `http_server_requests` como `exception="BlockingOperationError"`). Revertido.
- `ReactiveArchitectureTests`: las 5 reglas pasan sobre producción y cada una falla sobre su fixture infractora.
- `BlockingCallsTests`: 9 pruebas (hilo `boundedElastic-`, perezoso, error, `null` → vacío, `run`, `stream`, sin falso
  positivo, contexto de Reactor visible, y la contraprueba en `parallel`).

### Hallazgo: `ChatClient.stream()` fija un hilo por stream (decisión del responsable)

- Con `ChatClient.stream()` y 50 streams simultáneos: `jvm_threads_live_threads` **40 → 86** (50 hilos
  `boundedElastic-N`). Causa: `ChatModelStreamAdvisor` hace `publishOn(Schedulers.boundedElastic())`.
- Con `ChatModel.stream()`: **40 → 40**, los 50 streams terminan con `done` (50/50) y el primer byte tras la carga es
  ~100 ms. Se adopta `ChatModel.stream()` (research R12, ADR-0011). Test guarda: `OllamaChatAdapterTests#emitsOnTheHttp
  ClientThread...`.

### Métricas y paneles (US4)

- Comparación de series (`name{label,keys}`) contra la línea base de MVC (78 series): sin series con otra forma salvo la
  **desaparición de `tomcat_sessions_*`** (6 series, sin uso en ningún panel). Aparecen 3 series
  `jvm_gc_concurrent_phase_time_seconds_*` que dependen de que haya ocurrido una fase de GC, no de la migración.
  `http_server_requests_seconds_*` conserva nombre y etiquetas, incluida `error`.
- Con el stack de observabilidad levantado y Prometheus scrapeando el backend (`up`): de las 19 expresiones de los
  dashboards, **12 devuelven datos** (todas las de `http.json` y `jvm.json` salvo GC). Sin datos: 2 de pausas de GC (aún
  no hubo ninguna en una JVM recién arrancada) y 5 de `pods.json` (métricas de Kubernetes, no aplican en local). No se
  abrió la UI de Grafana; se evaluaron las expresiones de los paneles contra la API de Prometheus.
- `MetricsEndpointTests` (6 pruebas) y la aserción añadida de `http_server_requests{uri="/api/chat"}` pasan.

### Escalabilidad (SC-005)

- 50 streams simultáneos contra Ollama real: hilos 40 → 40 con el adaptador definitivo; 50/50 streams completos.

### Documentación y verificación manual

- T042: no hay menciones vigentes al modelo servlet fuera de specs históricas de otras historias ni de las skills de
  migración genéricas; nada que actualizar.
- `requests.http` generado: 15 peticiones (14 automáticas y 1 manual).
- Los contenedores de observabilidad levantados para la comprobación se han vuelto a parar.

### Ajuste tras revisión (2026-10-26)

- Comentario en `OllamaChatAdapter`: usar excepciones propias y sacar del adaptador la lógica de elegir el código. El
  adaptador ahora solo lanza `ChatModelException` (`chat/domain/exception`); `StreamChatUseCase` decide según el estado
  del stream: antes del primer fragmento el error sigue hasta el `GlobalExceptionHandler` (503 `SLHD_CHAT_00002`), y
  después se emite el evento `error` con `SLHD_CHAT_00003`. Contrato HTTP sin cambios (golden y pruebas de integración
  en verde). `mvn clean verify`: 101 pruebas, cobertura y Checkstyle/PMD en verde.

### Fallo de CI en el PR #66 y corrección (2026-10-26)

- **`backend`**: 9 tests fallaban en Linux con `BlockingOperationError: java.io.FileInputStream#readBytes`. Causa:
  `UUID.randomUUID()` en `RequestIdFilter` (y en el fallback de `ChatController`) lee `/dev/urandom` en el hilo del
  bucle de eventos. En Windows no ocurre, por eso pasaba en local. Corregido en el código (`newRequestId()` con
  `ThreadLocalRandom`) y protegido con la regla ArchUnit A6 (+ fixture) y una prueba del formato UUID v4. Reproducido y
  verificado en Linux/JDK 21 con Docker: 103 tests en verde.
- **`ChatStreamContractTests` en Windows**: `core.autocrlf=true` reescribía el golden con CRLF tras el rebase;
  `.gitattributes` fija `eol=lf` para `golden-stream.txt`.
- **Qodana**: 2 problemas de nulabilidad (overrides de métodos `@NullMarked` sin anotar en `RequestIdFilter.filter` y
  `GlobalExceptionHandler.handleServerWebInputException`). Anotados con `@NonNull` (incluido el retorno).
