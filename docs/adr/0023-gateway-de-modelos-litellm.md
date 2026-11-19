# ADR-0023: Gateway de modelos con LiteLLM

- Estado: aceptado
- Fecha: 2026-11-16
- Complementa a: ADR-0003 (modelos locales con Ollama) y ADR-0022 (trazas de LLM); no los sustituye

## Contexto

LAB-84 pide que toda llamada a un modelo pase por un único punto para poder cambiar de modelo, tener fallback y
controlar el coste sin tocar el código. Hasta ahora el backend hablaba directamente con Ollama (`spring-ai-starter-model-ollama`)
y conocía su dirección, su modelo y sus opciones (`num_ctx`, `keep_alive`).

Condicionantes:

- Tiene que funcionar al 100 % en local, sin claves de nube (ADR-0003); un proveedor cloud es opcional.
- El presupuesto y el coste se tienen que poder ejercitar con modelos locales, que no cuestan nada.
- Los datos que pasan son públicos (Olist) o de prueba, pero las preguntas y respuestas no deben quedar en logs
  (Constitución VI).
- ADR-0002: la aplicación no mezcla tablas; el estado del gateway no puede vivir en la base de la aplicación.

## Decisión

1. **LiteLLM proxy** como gateway, con API compatible con OpenAI, en el perfil `core` de compose (`litellm`, su Postgres
   propio `litellm-postgres` y el servicio de un solo disparo `litellm-bootstrap`) y en el clúster DEV (`shoplab-infra`).
2. **El backend solo conoce el gateway.** Cambia `spring-ai-starter-model-ollama` por `spring-ai-starter-model-openai`
   apuntando a `SPRING_AI_OPENAI_BASE_URL` con su clave virtual, y pide solo dos nombres lógicos: `chat` y `embeddings`.
   `ChatModelPort` y `EmbeddingPort` mantienen su contrato (solo `EmbeddingPort.embed` gana el propósito); cambian los
   adaptadores (`GatewayChatAdapter`, `SpringAiEmbeddingAdapter`). `com.openai..` queda prohibido en dominio y aplicación.
3. **Ollama solo es alcanzable por el gateway.** En compose vive en una red `models` que comparte únicamente con `litellm`
   y no publica puerto; en el clúster, políticas de red. El override `docker-compose.ollama-host.yml` lo vuelve a publicar
   para el Ollama del host de DEV y para mediciones.
4. **Configuración versionada** en `infra/litellm/`: `config.yaml` (nombres lógicos, modelos, precios de referencia,
   fallback), `keys.yaml` (una clave virtual por consumidor y entorno con presupuesto y periodo) y el script idempotente
   `bootstrap_keys.py`, porque LiteLLM no declara las claves en su configuración. El valor de cada clave llega por
   entorno o secreto, nunca por el archivo.
5. **Fallback** del nombre lógico `chat` a `chat-fallback` (otro modelo local, más pequeño) cuando el principal falla o
   tarda más que `stream_timeout` **antes del primer fragmento**. La continuación de un stream a mitad de respuesta queda
   desactivada: un fallo a mitad se trata como siempre (error final con el texto parcial). `embeddings` no tiene
   fallback: un respaldo con otra dimensión de vector corrompería el índice. La clave del backend debe poder llamar a
   `chat-fallback` o LiteLLM omite el fallback (verificado contra el gateway real).
6. **Registro por petición** (`shoplab_callbacks.py`): una línea JSON con consumidor, conversación, id de la petición,
   modelo pedido y servido, fallback, tokens, coste, latencia y resultado. Sin contenido de preguntas ni respuestas, sin
   claves ni cabeceras de autorización (`turn_off_message_logging`). Los éxitos salen del evento de éxito; los fallos, del
   hook de fallo final del proxy, porque los eventos de fallo del router se disparan en cada intento, incluidos los que
   un fallback rescata. El backend envía `user` (UUID de la conversación o `purpose:document-index|search-query`) y la
   cabecera `x-shoplab-request-id`.
7. **Precios de referencia** también para modelos locales (`model_info.input_cost_per_token`), solo para medir y presupuestar;
   no son dinero real. Un modelo sin precio se registra como `unpriced`, y un uso ausente, como `null` (nunca 0).
8. **Errores tipados.** Presupuesto agotado (`type: budget_exceeded`, HTTP 422 en la versión fijada) es
   `CHAT_BUDGET_EXCEEDED`/`EMBEDDING_BUDGET_EXCEEDED` (HTTP 429), distinto de «modelo no disponible». Clave inválida
   (401), modelo no permitido o inexistente (403), 5xx y conexión rechazada son «no disponible» (503). Del error del
   gateway solo se registran el estado y el `type`/`code`, nunca su texto, que puede citar el prompt.
9. **Proveedor cloud opcional**: el bloque `chat-cloud` de `config.yaml` solo se incluye si `CLOUD_PROVIDER_API_KEY` no
   está vacío; sin la clave no existe ni sale a Internet, y nunca es principal ni respaldo salvo configuración explícita.
10. **`drop_params: true`**: Ollama rechaza parámetros estándar de OpenAI como `user` en embeddings; el gateway los descarta
    y el callback los sigue viendo.

## Alternativas descartadas

- Seguir con el cliente de Ollama y la ruta de paso de LiteLLM: no aplica fallback, presupuesto ni coste en esa ruta.
- Fallback en el backend: duplica lógica y deja de ser «sin tocar el código».
- Reutilizar la base de la aplicación para el estado del gateway: rompe ADR-0002 y acopla ciclos de vida.
- Un perfil `gateway` aparte: el chat no funciona sin el gateway, así que pertenece a `core`.
- Fallback a mitad de stream: reiniciaría una respuesta ya emitida.

## Consecuencias

- Arranca algo más (gateway ~1 contenedor y su Postgres), pero ya no hay servicios opcionales que activar.
- **Dos tablas de precios.** El gateway usa precios de referencia (los modelos locales cuestan algo para presupuestar);
  las trazas de LAB-64 conservan la suya (ADR-0022, Ollama = 0). Los costes pueden diferir y la guía lo explica; unificarlas
  (leer el coste del gateway en la traza) queda como mejora fuera de alcance.
- **ADR-0003.** El modelo de embeddings se cambia ahora en el gateway (`LITELLM_EMBED_MODEL`) y, si cambia la dimensión del
  vector, exige reindexar (ADR-0017); el backend no mezcla vectores (`EMBEDDING_DIMENSION_MISMATCH`).
- **Traza.** El gateway informa, por chunk, el nombre del grupo que respondió (`chat` o `chat-fallback`), no el modelo real:
  la traza de LAB-64 muestra ese nombre y marca el fallback; el modelo real está en el log del gateway.
- Si el gateway pierde su base, las claves y los presupuestos se recrean desde `keys.yaml`; el gasto acumulado del periodo
  puede perderse. Se acepta un pequeño exceso de peticiones en vuelo sobre el presupuesto.
- Fuera de alcance: prompts versionados (LAB-85), puntuaciones de evaluación (LAB-29), paneles de coste (LAB-65), trazas
  distribuidas (LAB-63), caché de respuestas, límites por usuario final, guardarraíles y varias réplicas del gateway.
- `notebooks/` sigue usando Ollama directamente: son experimentos y nunca los importa código de producción.
