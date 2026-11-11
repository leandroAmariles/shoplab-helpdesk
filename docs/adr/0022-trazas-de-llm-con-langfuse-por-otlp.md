# ADR-0022: Trazas de LLM con Langfuse autoalojado por OTLP

- Estado: aceptado
- Fecha: 2026-11-11
- Complementa a: ADR-0009 (observabilidad base); no lo sustituye

## Contexto

LAB-64 pide ver cada turno del chat con todo su detalle: la pregunta, el prompt que recibió el modelo, los fragmentos
recuperados con su puntuación, las herramientas llamadas, los tokens y el coste. Las métricas y los logs de ADR-0009 no
sirven para eso: la cardinalidad de un prompt no cabe en una etiqueta de Prometheus y un log con la conversación sería
una fuga de datos. LAB-29 adjuntará puntuaciones de evaluación a estas mismas trazas.

Condicionantes del estado actual:

- Las puntuaciones de similitud de los fragmentos entregados solo existen dentro de `StreamChatUseCase`
  (`ContextFragment.score`); el evento `sources` que ve el cliente no las lleva.
- Spring AI 2.0.2 no documenta cuándo termina la observación de una llamada en streaming ni si captura el uso en el
  último fragmento; la verificación S-1 mostró que Ollama informa el uso solo en el fragmento `done: true`.
- El dominio no puede depender de Spring AI, Reactor ni de una librería de trazas (reglas D1/D2 de ArchUnit).
- Langfuse v4 necesita cuatro almacenes (Postgres, ClickHouse, Redis y almacenamiento S3), por lo que no puede cargar la
  máquina de quien no lo usa.

## Decisión

1. **Transporte**: OTLP sobre HTTP hacia `/api/public/otel/v1/traces` de Langfuse, con autenticación Basic y la cabecera
   `x-langfuse-ingestion-version: 4`. OpenTelemetry es aquí solo el formato de transporte (ADR-0009 lo reserva para eso);
   no hay SDK propietario.
2. **Pipeline de OpenTelemetry dedicado**: `TracingConfig` crea su propio `SdkTracerProvider` con un `BatchSpanProcessor`.
   No se usa el tracing global de Spring Boot, de modo que ningún otro span (HTTP, JDBC) sale hacia Langfuse y apagar el
   interruptor no deja ningún hilo, exportador ni conexión.
3. **Modelo explícito, no observaciones de Spring AI**: el caso de uso abre y cierra el turno a través del puerto de salida
   `ChatTracePort`; el adaptador del modelo informa de la generación y de las herramientas al `TurnTrace` que recibe por el
   contexto de Reactor. `ChatModelPort` no cambia de firma. Sin trazas, el puerto lo implementa `NoOpChatTrace`.
4. **`traceId` derivado**: los primeros 32 caracteres hexadecimales de `SHA-256(X-Request-Id)`. Con un identificador de
   petición se encuentra la traza sin buscarla, y reenviar el mismo turno no la duplica.
5. **Contenido y salvaguardas** (las trazas llevan la conversación):
   - **apagado por defecto** (`shoplab.chat.tracing.enabled=false`): sin exportador, sin hilo y sin conexión;
   - tope de caracteres por atributo (`max-content-characters`, con marca `…[truncated]`);
   - nunca se serializan cabeceras, claves ni propiedades de configuración, y las claves no salen en ningún log;
   - solo datos públicos o de prueba: la base de conocimiento actual es pública, y quien active el envío con datos reales
     asume que Langfuse guarda la conversación;
   - un fallo de la traza nunca llega al chat: cada llamada a OpenTelemetry va protegida, la cola es acotada (se descarta
     antes que bloquear y se cuenta en `shoplab.chat.trace.dropped`) y los lotes fallidos solo dejan un aviso de texto fijo
     y el contador `shoplab.chat.trace.export.failures`.
6. **Despliegue**: perfil `llmops` de docker compose (opcional, con versiones fijas y sin tocar los demás perfiles) y, en
   el clúster DEV, seis cargas gestionadas por Argo CD en `shoplab-dev`, sin Ingress, con los secretos del mecanismo de
   `ExternalSecret` existente (`/shoplab/dev/langfuse`).

## Consecuencias

- Cada turno deja una traza `chat-turn` con `retrieval`, `generation` y un hijo `tool:*` por herramienta; los turnos sin
  contexto, cancelados o fallidos también la dejan, con su `result`, el código de error y la etapa.
- El modelo explícito obliga a mantener `OllamaChatAdapter` y `LangfuseChatTrace` al cambiar de modelo o de proveedor;
  a cambio, el uso de tokens y las puntuaciones son exactos y se prueban con WireMock sin depender del comportamiento
  interno de Spring AI.
- Un uso de tokens ausente se registra como **desconocido**, nunca como cero, y el coste solo se calcula si hay precio
  para el modelo en `shoplab.chat.tracing.pricing` (los modelos locales de Ollama valen cero).
- Langfuse local cuesta unos 2,8 GiB en reposo (ver `specs/LAB-64-langfuse-llm-traces/validation.md`); en DEV ocupa el
  nodo del clúster y su coste real se mide antes de dejarlo fijo.
- Las trazas de DEV no son accesibles por red: se leen con `kubectl port-forward`.
- LAB-29 podrá adjuntar puntuaciones a las trazas por su `traceId`; LAB-63 y las pasarelas de LLM (LAB-84) no dependen de
  esta decisión.

### Alternativas descartadas

- **API de ingesta REST propia** (`/api/public/ingestion`): obligaba a reimplementar cola acotada, reintentos y vaciado al
  apagar, que el SDK de OpenTelemetry ya trae; Langfuse la presenta como heredada.
- **SDK de Langfuse para Java**: no hay SDK oficial confirmado; el propio proyecto recomienda OpenTelemetry para Java.
- **Tracing global de Spring Boot con un filtro**: mezclaría los spans de HTTP y JDBC con los del chat y dejaría el
  exportador activo aunque no se use.
- **`ObservationHandler` sobre las observaciones `gen_ai.*` de Spring AI**: no ve las puntuaciones de los fragmentos y su
  comportamiento en streaming no está documentado.
- **LiteLLM como pasarela con callback a Langfuse**: es LAB-84, otra historia.
