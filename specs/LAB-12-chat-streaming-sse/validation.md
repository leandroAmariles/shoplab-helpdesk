# Validación: Respuesta del chat en streaming (SSE)

**Feature**: LAB-12 | Registro de los resultados de pruebas y de la validación en vivo.

## Línea base (T001)

- Fecha: 2026-10-26, rama `feature/LAB-12-chat-streaming-sse`, sin cambios de código.
- `./mvnw -B -ntp clean verify` en `backend/`: **BUILD SUCCESS** (15 s). Checkstyle 0 violaciones, PMD sin fallos.
- JaCoCo: «Analyzed bundle with 0 classes» (todo el código actual está en paquetes excluidos); el umbral del 80 % se
  cumple vacuamente. La cobertura real empieza a medirse con la lógica de `chat` y `shared`.

## Fase 2 y US1 (T006–T027)

- T006/T022: antes de implementar, las pruebas nuevas no compilaban (faltaban `shared`, `StreamChatUseCase`,
  `ChatController`, `OllamaChatAdapter`): estado rojo confirmado.
- Tras implementar: `./mvnw -B -ntp test` → **34 pruebas, 0 fallos** (`ModularityTests` con 5 módulos,
  `GlobalExceptionHandlerTests`, `RequestIdFilterTests`, `StreamChatUseCaseTests`, `ChatControllerTests`,
  `OllamaChatAdapterTests`, `ChatStreamingIntegrationTests`, `MicrometerChatMetricsTests`).
- El streaming real de Spring AI 2.0.2 contra WireMock (NDJSON) entrega los fragmentos en orden, con `ñ` y emoji
  intactos, y 5 peticiones concurrentes no mezclan fragmentos (SC-004, SC-007).
- T029/T033 (adaptador y prueba de métricas, de US2) se adelantaron: el contexto de Spring necesita un bean de
  `ChatMetricsPort` para arrancar.

## US2, US3, US4 y cierre (T028–T053, T055)

- **US2**: el primer evento llega mientras el modelo simulado sigue generando (primer `token` < 1 s, `done` > 2,5 s con
  10 fragmentos repartidos en 3 s) y `/actuator/prometheus` expone `shoplab_chat_first_token_seconds` y
  `shoplab_chat_response_seconds{outcome=...}` con `application="helpdesk-backend"`.
  - Hallazgo: WireMock comprime en gzip cuando el cliente de Spring AI envía `Accept-Encoding`, lo que acumulaba la
    respuesta entera; no ocurre con Ollama real. Las pruebas usan `gzipDisabled(true)`. Con gzip desactivado el
    adaptador entrega un fragmento cada ~300 ms, sin acumular.
- **US3**: 400 `SLHD_CHAT_00001` (vacío, solo espacios, `null`, ausente, 4001 caracteres; 4000 se acepta) sin llamar al
  modelo (WireMock `verify(0)`); 400 `SLHD_GENE_00002` con JSON ilegible; 503 `SLHD_CHAT_00002` con Ollama caído
  (puerto cerrado), con modelo inexistente (404) y con conexión reiniciada, también con `Accept: text/event-stream`;
  fallo a mitad de la respuesta (servidor TCP que corta la conexión) → los `token` ya enviados y un último
  `event:error` con `SLHD_CHAT_00003`, sin `done`. Un cliente que solo acepta `application/json` recibe 406.
  - Riesgo R4 de `research.md`: **no hizo falta la contingencia**. Un `Flux` que falla antes del primer elemento
    llega al `GlobalExceptionHandler` con la respuesta sin confirmar (503 JSON), probado a nivel de `@WebMvcTest` y
    de servidor real.
- **US4**: cerrar la conexión cancela la suscripción al modelo y registra `outcome="cancelled"` en < 3 s (Awaitility);
  la petición siguiente cumple el límite del primer evento. Un flujo ya completado no se registra como cancelado.
- **Cierre (T053)**: `./mvnw -B -ntp clean verify` → **72 pruebas, 0 fallos**; Checkstyle 0 violaciones; PMD sin
  fallos; JaCoCo «All coverage checks have been met» con **100 % de líneas** en las 6 clases con lógica
  (`StreamChatUseCase`, `OllamaChatAdapter`, `MicrometerChatMetrics`, `ChatController`, `GlobalExceptionHandler`,
  `RequestIdFilter`), por encima de la línea base (0 clases medidas) y del umbral del 80 %.
- Ruido esperado en los logs de las pruebas de fallo: Spring AI registra `Aggregation Error` a nivel ERROR cuando el
  flujo del modelo falla; es de la librería, no de este código.

## Validación en vivo con Ollama real (T035, T049, T054)

- **Entorno** (2026-10-26): `docker compose -f infra/docker-compose.yml -f infra/docker-compose.gpu.yml up -d` con
  `infra/.env` copiado de `.env.example`; `llama3.2:3b` y `bge-m3` ya estaban en el volumen. Ollama 100 % en GPU
  (RTX 5070, `ollama ps`), contexto 4096. Backend con `./mvnw spring-boot:run`.
- **Carga en frío**: la primera petición tardó **36,6 s** hasta el primer token (carga del modelo), como asumía la spec
  (el criterio de 2 s es con el modelo cargado).
- **T035 / SC-001** (modelo caliente, mensaje corto, tiempo hasta la primera línea `event:token`): 20 peticiones →
  19 por debajo de 2 s, mediana 33 ms; otras 40 → 40 de 40 por debajo de 2 s (máx. 50 ms). Total **59 de 60 (98,3 %)**,
  cumple el 95 %. Hubo **un valor atípico de 3,6 s** en la segunda petición de la primera tanda; Ollama no recargó el
  modelo (un solo `loaded runners` en sus logs) y no se determinó la causa. El mismo tiempo medido con el comando de la
  guía (`curl -w %{time_starttransfer}`) da ~15 ms, porque las cabeceras salen con el primer evento (no se envían antes).
- **Mantenimiento del modelo en memoria**: `ollama ps` mostraba `UNTIL 4 minutes from now`: con el `keep_alive` por
  defecto de Ollama (5 min) una pausa mayor descarga el modelo y la siguiente petición vuelve a costar decenas de
  segundos. **Aplicado después**: `spring.ai.ollama.chat.options.keep-alive: ${OLLAMA_KEEP_ALIVE:30m}`. Con el backend
  reiniciado y una petición, `ollama ps` pasó a `UNTIL 29 minutes from now`; la prueba
  `OllamaChatAdapterTests.asksOllamaToKeepTheModelLoadedForTheConfiguredTime` fija que `keep_alive` viaja a Ollama.
- **US1 / FR-016**: la respuesta llega como `text/event-stream`, 74-91 fragmentos por respuesta, `done` con el número
  exacto de fragmentos y caracteres españoles íntegros (sin U+FFFD; `envío`, `Cañete`). El cuerpo termina (EOF) justo
  después de `done`.
- **T049 / US4 / SC-005**: respuesta larga completa → 1466 fragmentos y **8,18 s** en Ollama; la misma petición cerrada
  por el cliente tras 3 fragmentos → **157 ms** en Ollama (dejó de generar). `outcome="cancelled"` apareció **0,07 s**
  después de cerrar y la petición siguiente tardó 32 ms hasta el primer token.
- **US3 en vivo**: mensaje en blanco → `400 SLHD_CHAT_00001` con `X-Request-Id`; JSON ilegible → `400 SLHD_GENE_00002`;
  4001 caracteres → `400 SLHD_CHAT_00001`; 4000 → `200`. Con Ollama parado → `503 SLHD_CHAT_00002` en JSON. Parando
  Ollama a mitad de una respuesta (tras 5 fragmentos) → los fragmentos recibidos, un `event:error` con
  `SLHD_CHAT_00003` a los 1,11 s y el cuerpo termina a los 1,12 s (sin `done`). Tras reiniciar Ollama el chat se
  recupera sin reiniciar el backend. Métricas finales: `completed` 71, `error` 2, `cancelled` 1.
- **T054 / SC-008 (hallazgo)**: al seguir el `quickstart.md` literalmente en Windows, el primer `curl` del paso 2 con
  «Explícame» falló con `400 SLHD_GENE_00002`: `curl.exe` envía los argumentos en cp1252, no en UTF-8, y el servidor lo
  rechaza correctamente (con el JSON en un archivo UTF-8 da `200`; con bytes cp1252 da `400`). No es un fallo del
  servidor, pero la guía no lo avisaba: se añadió la nota en `quickstart.md` y en `docs/chat-streaming.md`. El resto de
  pasos (2, 3, 4, 5 y 6) se ejecutaron como están escritos y dieron lo esperado.
