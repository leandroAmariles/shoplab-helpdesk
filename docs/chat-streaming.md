# Chat en streaming (`POST /api/chat`)

El backend responde al mensaje del cliente con la respuesta del modelo local **a medida que se genera** (la pide al gateway de modelos, LiteLLM, que la encamina a Ollama; `docs/gateway-de-modelos.md`),
como un flujo de eventos del servidor (SSE). Especificación y decisiones: `specs/LAB-12-chat-streaming-sse/`.

## Probarlo desde la terminal

Con el perfil `core` de compose levantado (PostgreSQL, que el backend necesita para arrancar, y Ollama con el modelo de chat descargado) y el backend en marcha
(`cd backend && ./mvnw spring-boot:run`):

```bash
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -d '{"message":"Explícame en tres frases cómo devolver un pedido"}'
```

`-N` desactiva el buffer de `curl`; sin él los eventos aparecen todos juntos al final. En Windows usa `curl.exe`.

> **Windows y caracteres no ASCII**: `curl.exe` envía los argumentos `-d` en cp1252, no en UTF-8, y el servidor
> responde `400 SLHD_GENE_00002` (cuerpo ilegible). Guarda el JSON en un archivo UTF-8 y usa
> `--data-binary @mensaje.json`, o limítate a ASCII en el mensaje.

## Formato de los eventos

Cada evento tiene nombre (`event:`) y una línea de datos JSON (`data:`):

| Evento | Datos | Significado |
| --- | --- | --- |
| `token` | `{"content":"Hola"}` | Un fragmento de la respuesta, en orden. Concatenar los `content` da el texto completo |
| `done` | `{"fragments":42}` o `{"fragments":42,"turnId":"2b6f1a04-..."}` | La respuesta terminó bien. Es el último evento; el servidor cierra la conexión. Cuando la petición lleva `conversationId` y la memoria está activa, incluye `turnId` (UUID del turno guardado en `chat.conversation_turn`) para poder valorarlo en `PUT /api/chat/feedback/{turnId}` (LAB-33) |
| `error` | `{"code":"SLHD_CHAT_00003","message":"..."}` | El modelo falló **después** de empezar. Es el último evento, sin `done` |
| `sources` | `{"sources":[{"number":1,"title":"Política de devoluciones","section":"Plazos","text":"..."}]}` | Los fragmentos de los que sale la respuesta, numerados desde 1. Solo si hay contexto; se envía **una vez, justo antes del primer `token`**. No cuenta en `done.fragments` |

Un cliente que no conozca `sources` lo ignora: recibe el texto con las marcas `[n]` como texto plano. Contrato de las
citas: [`contracts/sources-event.md`](../specs/LAB-23-source-citations/contracts/sources-event.md).

Contrato completo: [`contracts/chat-stream.md`](../specs/LAB-12-chat-streaming-sse/contracts/chat-stream.md) y
[`contracts/openapi.yaml`](../specs/LAB-12-chat-streaming-sse/contracts/openapi.yaml).

## Errores antes de empezar a responder

Si el fallo ocurre antes del primer evento no hay flujo: la respuesta es JSON `{"code","message"}` con estado HTTP.

| Estado | Código | Cuándo |
| --- | --- | --- |
| 400 | `SLHD_CHAT_00001` | Mensaje ausente, vacío, solo espacios o de más de 4000 caracteres |
| 400 | `SLHD_CHAT_00005` | `conversationId` presente pero que no es un UUID en texto canónico (también con la memoria desactivada) |
| 400 | `SLHD_GENE_00002` | Cuerpo que no es JSON válido |
| 403 | `SLHD_CHAT_00008` | La petición lleva `X-Prompt-Versions` sin que la capacidad esté activa o con un `X-Prompt-Token` ausente o incorrecto (solo la evaluación usa estas cabeceras) |
| 400 | `SLHD_CHAT_00009` | `X-Prompt-Versions` mal formada, con un prompt desconocido o repetido, o con una versión que no es un entero desde 1 |
| 422 | `SLHD_CHAT_00010` | Una versión de prompt fijada con `X-Prompt-Versions` no existe, no es válida o Langfuse no responde: no se responde con otro texto |
| 503 | `SLHD_CHAT_00002` | El gateway de modelos no responde, rechaza la clave, el modelo no existe o principal y respaldo han fallado |
| 429 | `SLHD_CHAT_00007` | La clave virtual del backend agotó su presupuesto en el gateway |
| 503 | `SLHD_CHAT_00004` | No se pudo recuperar el contexto de la base de conocimiento (servicio de embeddings o base de datos caídos). El bot no responde sin contexto |
| 503 | `SLHD_CHAT_00006` | No se pudo leer la memoria de la conversación (base de datos caída). El bot no responde como si no hubiera historial |
| 500 | `SLHD_GENE_00001` | Error inesperado |

Un cliente que solo acepte `application/json` recibe `406`: el endpoint produce `text/event-stream`. `curl` sin
`Accept` (equivale a `*/*`) funciona.

## Configuración

| Variable / propiedad | Por defecto | Uso |
| --- | --- | --- |
| `SPRING_AI_OPENAI_BASE_URL` | `http://localhost:4000/v1` | Dirección del gateway de modelos (con `/v1`); el modelo real lo decide el gateway (`LITELLM_CHAT_PRIMARY_MODEL`) |
| `SPRING_AI_OPENAI_API_KEY` | `sk-shoplab-backend-local` | Clave virtual del backend (marcador solo para uso local) |
| `SHOPLAB_CHAT_GATEWAY_PRIMARY_MODEL` | `chat` | Nombre con el que el gateway informa las respuestas de su modelo principal; otro nombre se marca como fallback en la traza |
| `SHOPLAB_CHAT_GATEWAY_REQUEST_TIMEOUT` | `60s` | Tiempo máximo que el backend espera al gateway |
| `shoplab.chat.max-message-length` | `4000` | Longitud máxima del mensaje, en caracteres |
| `SHOPLAB_CHAT_PROMPTS_ENABLED` / `shoplab.chat.prompts.enabled` | `false` | Carga los cuatro prompts del chat desde Langfuse (sección «Prompts versionados en Langfuse»). Apagado, el chat usa siempre el texto por defecto y no crea ningún cliente ni hilo. Es independiente del envío de trazas |
| `SHOPLAB_CHAT_PROMPTS_BASE_URL` | `http://localhost:3001` | Dirección de Langfuse (sin `/api/...`); en DEV, `http://langfuse-web:3000` |
| `SHOPLAB_CHAT_PROMPTS_PUBLIC_KEY` / `…_SECRET_KEY` | las de `SHOPLAB_CHAT_TRACING_*` | Claves del proyecto de Langfuse; la secreta nunca se imprime. Sin ellas, aviso y se comporta como apagado |
| `SHOPLAB_CHAT_PROMPTS_LABEL` | `production` | Etiqueta cuya versión se sirve |
| `SHOPLAB_CHAT_PROMPTS_REFRESH_INTERVAL` | `60s` | Cada cuánto se vuelven a pedir los prompts; es el máximo que tarda un cambio de etiqueta en llegar al chat (mínimo 5 s) |
| `SHOPLAB_CHAT_PROMPTS_REQUEST_TIMEOUT` | `2s` | Tiempo máximo de cada petición a Langfuse, incluida la carga de arranque (de 100 ms a 30 s) |
| `SHOPLAB_CHAT_PROMPTS_MAX_PROMPT_CHARACTERS` | `20000` | Tamaño máximo de un prompt; uno mayor se descarta como edición mala (mínimo 100) |
| `SHOPLAB_CHAT_PROMPTS_PUBLISH_MISSING` | `false` | Al arrancar crea en Langfuse, con su texto por defecto y la etiqueta, los prompts que **no existen**; nunca crea versiones de uno que existe ni mueve etiquetas |
| `SHOPLAB_CHAT_PROMPTS_OVERRIDE_TOKEN` | vacío | Token que permite a la evaluación fijar versiones de prompts en una petición. Vacío, o con `enabled=false`, la capacidad está apagada |
| `SHOPLAB_CHAT_RAG_TOP_K` / `shoplab.chat.rag.top-k` | `4` | Fragmentos que se piden a la búsqueda por pregunta. Rango 1–20; fuera de rango el arranque falla |
| `SHOPLAB_CHAT_RAG_SIMILARITY_THRESHOLD` / `shoplab.chat.rag.similarity-threshold` | `0.6` | Similitud mínima (0–1) de un fragmento; `0` no descarta nada. Con `0` la búsqueda entrega siempre `top-k` fragmentos aunque no se parezcan a la pregunta y el bot responde con contexto de otro tema |
| `SHOPLAB_CHAT_RAG_MAX_CONTEXT_CHARACTERS` / `shoplab.chat.rag.max-context-characters` | `8000` | Máximo de caracteres de contexto que recibe el modelo (mínimo 500); cuenta solo el texto de los fragmentos, no sus cabeceras `[origen › sección]` ni la plantilla (unos cientos de caracteres más); los fragmentos van completos o no van |
| `SHOPLAB_CHAT_RAG_NO_CONTEXT_MODE` / `shoplab.chat.rag.no-context-mode` | `fixed-message` | Qué hacer si no hay fragmentos útiles: `fixed-message` responde el texto de `shoplab.chat.rag.no-context-message` sin llamar al modelo (determinista, no inventa); `model` deja que el modelo conteste con la plantilla de contexto vacío (conserva saludos y cortesías, pero el modelo local puede usar conocimiento general) |
| `SHOPLAB_CHAT_MEMORY_MAX_TURNS` / `shoplab.chat.memory.max-turns` | `5` | Turnos recientes (pregunta + respuesta) que recibe el modelo. Rango 0–50; `0` apaga la memoria aunque llegue `conversationId` (el identificador se sigue validando) |
| `SHOPLAB_CHAT_MEMORY_MAX_HISTORY_CHARACTERS` / `shoplab.chat.memory.max-history-characters` | `4000` | Máximo de caracteres de historial (preguntas y respuestas), mínimo 200; se descarta el turno más antiguo entero, nunca se recorta un texto |
| `SHOPLAB_CHAT_MEMORY_RETRIEVAL_TURNS` / `shoplab.chat.memory.retrieval-turns` | `1` | Preguntas anteriores que se anteponen a la consulta de búsqueda para que un seguimiento encuentre su tema (0–50); `0` busca solo con el mensaje. Nunca más que `max-turns` |
| `SHOPLAB_CHAT_SMALL_TALK_ENABLED` / `shoplab.chat.small-talk.enabled` | `true` | Responde las cortesías (saludos, agradecimientos, despedidas, «¿qué puedes hacer?») sin recuperar contexto. `false` restablece exactamente el comportamiento anterior a LAB-114 |
| `SHOPLAB_CHAT_SMALL_TALK_TOPICS` / `shoplab.chat.small-talk.topics` | `pagos, privacidad, devoluciones, envíos y garantías` | Temas que el bot nombra al decir qué puede hacer (sustituyen a `{topics}` en el prompt `chat-small-talk`); no puede quedar en blanco |
| `SHOPLAB_CHAT_SMALL_TALK_GREETINGS` / `…THANKS` / `…FAREWELLS` / `…ACKNOWLEDGMENTS` / `…CAPABILITIES` (`shoplab.chat.small-talk.greetings`, `thanks`, `farewells`, `acknowledgments`, `capabilities`) | listas incorporadas | Léxico cerrado de cortesías, frases separadas por comas (mayúsculas, acentos y signos no importan). Vacío conserva la lista incorporada; un valor la **reemplaza**. El arranque falla si una frase queda vacía, lleva cifras o tiene más de 10 palabras |
| `LITELLM_NUM_CTX` / `LITELLM_KEEP_ALIVE` | `8192` / `30m` | Ventana de contexto y tiempo de carga del modelo que el **gateway** pide a Ollama (antes `OLLAMA_NUM_CTX` y `OLLAMA_KEEP_ALIVE` en el backend) |

## Observabilidad

- Cada petición lleva un `X-Request-Id` (el que envíe el cliente si es válido, o uno generado); se devuelve en la
  respuesta y aparece en los logs. Los logs **no** incluyen el texto del cliente.
- `/actuator/prometheus` publica `shoplab_chat_first_token_seconds` (tiempo hasta el primer token, con histograma, que
  incluye la recuperación del contexto) y `shoplab_chat_response_seconds` con la etiqueta `outcome` = `completed`, `error`
  o `cancelled`.
- `shoplab_chat_context_retrieval_seconds` (etiqueta `outcome` = `with_context`, `no_context` o `failed`) mide la
  recuperación y selección del contexto, y `shoplab_chat_context_fragments` resume los fragmentos entregados al modelo por
  pregunta. Un `no_context` frecuente indica una base sin indexar, un modelo de embeddings cambiado sin reindexar o un
  umbral demasiado alto.
- `shoplab_chat_turns_total` cuenta cada turno atendido con la etiqueta `kind` = `small_talk`, `with_context` o `no_context`
  (un turno cuya recuperación falla no tiene tipo: lo cuenta `shoplab_chat_context_retrieval_seconds{outcome="failed"}`).
  La proporción de charla es `sum(rate(shoplab_chat_turns_total{kind="small_talk"}[5m])) / sum(rate(shoplab_chat_turns_total[5m]))`;
  una proporción que se dispara puede indicar que el léxico de cortesías se queda corto o que algo repite saludos. Una charla
  no se cuenta en `shoplab_chat_first_token_seconds`, que mide respuestas con recuperación.
- `shoplab_chat_prompts_refresh_total` cuenta cada consulta a Langfuse por prompt (`prompt`) y resultado (`result` = `updated`, `unchanged`, `invalid`, `missing` o `failed`) y `shoplab_chat_prompts_version` es la versión cargada de cada prompt (0 mientras se usa el texto por defecto). Un `invalid` o un `failed` sostenido significa que el chat sigue con la última versión válida.
- Cerrar la conexión del cliente cancela la generación en el modelo.

## Prompts versionados en Langfuse (LAB-85)

Los cuatro textos que el chat envía al modelo se editan en **Langfuse** (Prompts), sin tocar código ni redesplegar:

| Prompt en Langfuse | Para qué | Marcadores obligatorios |
| --- | --- | --- |
| `chat-system` | Reglas de toda respuesta con conocimiento (se envía tal cual; sus llaves son literales) | ninguno |
| `chat-small-talk` | Instrucciones de la respuesta a una cortesía | `{topics}` |
| `chat-context` | Mensaje del cliente con los fragmentos recuperados | `{query}` y `{context}` (ninguna otra variable) |
| `chat-empty-context` | Mensaje del cliente sin fragmentos (solo con `no-context-mode=model`) | `{query}` |

**Cómo funciona.** El backend pide a Langfuse la versión con la etiqueta `production` de cada prompt y la guarda en memoria; el
turno **solo lee esa memoria**, así que un Langfuse lento o caído no retrasa ni rompe ninguna pregunta. Cada
`SHOPLAB_CHAT_PROMPTS_REFRESH_INTERVAL` (60 s por omisión) vuelve a preguntar: **ese es el máximo que tarda un cambio de etiqueta
en llegar al chat**, en todas las instancias y sin reiniciar. Cada turno toma la versión de cada prompt al empezar y la mantiene
hasta terminar, aunque la etiqueta se mueva mientras responde.

**Si Langfuse no ayuda** el chat usa, por este orden, la última versión válida cargada (aunque sea antigua) y el texto por defecto
de `backend/src/main/resources/prompts/*.txt`, que es la única copia del texto en el repositorio. Una versión nueva se **descarta**
(con un aviso que nombra el prompt, la versión y el motivo, nunca el texto) si no es de tipo texto, está vacía, supera
`SHOPLAB_CHAT_PROMPTS_MAX_PROMPT_CHARACTERS`, le falta un marcador obligatorio o `chat-context` usa otra variable.

**Primera vez.** Con `SHOPLAB_CHAT_PROMPTS_PUBLISH_MISSING=true` el backend crea, al arrancar, los prompts que no existen (versión 1,
etiqueta `production`, el texto por defecto). Repetirlo no crea versiones y nunca toca un prompt que ya existe.

### Editar, probar, comparar, promover y revertir

1. **Editar**: en Langfuse, abre el prompt y crea una versión nueva. Sin etiqueta no la usa nadie: el chat sigue con `production`.
2. **Probarla** sin tocar `production`: pon una etiqueta propia (por ejemplo `staging`) y mide con
   `uv run python -m shoplab_eval quality --prompt chat-system=staging --out eval/results/staging` (ver `eval/README.md`).
3. **Compararla**: mide también la versión actual y usa `compare` sobre los dos resúmenes. La comparación avisa si los dos
   resultados no son comparables (otro juez, otro modelo del asistente u otro número de casos).
4. **Promoverla**: mueve la etiqueta `production` a la versión nueva. En ≤ `REFRESH_INTERVAL` las preguntas nuevas la usan.
5. **Revertir**: mueve `production` de vuelta a la versión anterior; vuelve exactamente el texto anterior en el mismo tiempo.

Un prompt no debe contener claves, datos de clientes ni nada que no pueda verse en una traza: las trazas guardan el prompt
completo (con el tope y el saneado de LAB-64). Cambiar un prompt sin una medición de calidad antes y después se rechaza
(Constitución V).

### Qué dice cada traza

Con el envío de trazas activo, la generación de cada turno enlaza con la versión de su prompt principal (`chat-system` en un turno
de conocimiento, `chat-small-talk` en una cortesía) y puede saltarse a ella desde Langfuse. La traza guarda además, como
metadatos filtrables, la versión de **cada** prompt que el turno usó (`prompt_chat_system`, `prompt_chat_context`…). Con el texto
por defecto no hay enlace y el metadato dice `default`; una cortesía no registra los prompts de contexto.

### Fijar versiones solo para evaluar

Una petición puede fijar versiones con `X-Prompt-Versions: chat-system=3,chat-context=2` y `X-Prompt-Token`. Solo funciona si el
backend tiene `SHOPLAB_CHAT_PROMPTS_ENABLED=true` y un `SHOPLAB_CHAT_PROMPTS_OVERRIDE_TOKEN`, y la petición lo presenta; en cualquier
otro caso responde **403** (nunca ignora la cabecera: una evaluación mal configurada mediría con los prompts equivocados). Una
versión que no existe es un **422** y no se cae al texto de `production`. No mueve ninguna etiqueta ni cambia la respuesta de otras
peticiones. Decisiones: [ADR-0027](adr/0027-prompts-versionados-en-langfuse.md).

### Cambio de configuración incompatible

Se retiraron `shoplab.chat.system-prompt`, `shoplab.chat.small-talk.system-prompt`, `shoplab.chat.rag.context-template` y
`shoplab.chat.rag.empty-context-template`, y las variables `SHOPLAB_CHAT_SYSTEM_PROMPT` y `SHOPLAB_CHAT_SMALL_TALK_SYSTEM_PROMPT`: su
valor ya no se lee. Si el entorno aún define una variable, el backend arranca y deja un aviso que la nombra. Para conservar un
texto propio, créalo como versión en Langfuse y ponle la etiqueta `production`.

## Detrás de un proxy o ingreso

La respuesta lleva `Cache-Control: no-cache` y `X-Accel-Buffering: no`. Si el proxy intermedio acumula la respuesta
(compresión o buffering), el streaming se pierde: desactiva ambos para la ruta `/api/chat`.

## Respuestas con los documentos (RAG, LAB-22)

Antes de generar cada respuesta, el chat busca en la base de conocimiento los fragmentos más cercanos a la pregunta
(`retrieval`, por su API publicada), descarta los que no alcanzan la similitud mínima o repiten texto, se queda con los que
caben en el presupuesto de caracteres y se los entrega al modelo como contexto. El prompt de sistema le obliga a responder
**solo** con ese contexto y a reconocer cuando no contiene la respuesta; si no se encontró nada, el bot responde un
mensaje fijo (`no-context-message`) sin llamar al modelo; con `no-context-mode=model` el modelo recibe en cambio una
plantilla que le dice que no hay información (y contesta saludos y cortesías con normalidad). El contrato de eventos
SSE no cambia. Decisiones: [ADR-0018](adr/0018-recuperacion-de-contexto-en-chat.md); especificación en
`specs/LAB-22-rag-augmented-answers/`.

## Citas a la fuente (LAB-23)

El bot numera los fragmentos que recibe como contexto (`[1]`, `[2]`…) y se le pide terminar cada afirmación con el número
de su fragmento. El evento `sources` lleva, para cada número, el título legible del documento, su sección y el texto del
fragmento. En la pantalla de chat las marcas `[n]` que apuntan a una fuente recibida se pintan como botones; bajo la
respuesta aparece «Fuentes» con las citadas (o «Fuentes consultadas» si el modelo no citó ninguna), y activar una cita o una
fuente abre el fragmento. Una marca que no apunta a ninguna fuente se queda como texto. Decisiones:
[ADR-0019](adr/0019-citas-a-la-fuente-en-el-chat.md).

La regla de citas está en el prompt de sistema y en la plantilla de contexto por defecto: quien escriba una versión nueva de
`chat-system` o de `chat-context` en Langfuse debe conservarla. `/actuator/prometheus` publica `shoplab_chat_citations_total` con la etiqueta
`outcome` = `valid` (cita solo fuentes que tiene), `invalid` (alguna cita sin fuente) o `none` (no cita), una vez por respuesta
completada con contexto; sin texto de preguntas, respuestas ni fragmentos.

## Memoria de la conversación (LAB-24)

La petición acepta un `conversationId` opcional, un UUID en texto canónico que genera la pantalla de chat
(`crypto.randomUUID()`):

```json
{ "message": "¿y si es internacional?", "conversationId": "3f1b6c52-8d0e-4c1a-9a53-7b2a0c9d4e11" }
```

Sin `conversationId` el mensaje se responde por sí solo, como antes. Con él, el bot recuerda los turnos anteriores de esa
conversación: se los entrega al modelo como mensajes previos, y la búsqueda de contexto antepone la pregunta anterior para
que «¿y si es internacional?» encuentre el documento de envíos. Los turnos viven en la tabla `chat.conversation_turn`
(esquema del módulo `chat`), sobreviven al reinicio del servicio y no se mezclan entre conversaciones. Decisiones:
[ADR-0020](adr/0020-memoria-de-conversacion-en-tabla-propia.md).

- **`done` solo se emite después de guardar el turno**, así que el cliente puede enviar su siguiente mensaje al recibirlo.
  Una respuesta fallida o cancelada no guarda nada. Si el guardado falla, el último evento es `error` con
  `SLHD_CHAT_00006` en lugar de `done`.
- El historial se acota por turnos y por caracteres; las marcas `[n]` de las respuestas anteriores no se reenvían al
  modelo, y las citas de cada respuesta siguen numerándose desde 1.
- La pantalla ofrece «Nueva conversación»: cancela la respuesta en curso, vacía la pantalla y usa un identificador nuevo.
  Recargar la página también empieza una conversación nueva.
- `/actuator/prometheus` publica `shoplab_chat_history_messages` (mensajes de historial entregados al modelo),
  `shoplab_chat_history_trimmed_total` (respuestas en las que se recortó historial) y
  `shoplab_chat_memory_failures_total{operation="read|write"}`. Ni los registros ni las métricas llevan el texto de los
  mensajes ni el identificador de la conversación.

## Charla de cortesía y defensa frente a inyección (LAB-114)

Un mensaje que es **solo** una cortesía («hola», «¡Buenas tardes!», «gracias, hasta luego», «¿qué puedes hacer?», también en
inglés) se detecta antes de leer el historial y de buscar, y se responde con un prompt propio y mínimo: sin búsqueda, sin
historial, sin herramientas y sin evento `sources`. La secuencia es `token…` y `done`; el contrato SSE y los códigos de error
no cambian. Con la recuperación o la memoria caídas, un saludo se responde igual; el turno se guarda como cualquier otro, pero
no se usa para completar la búsqueda de la pregunta siguiente («hola» no contamina «¿y para tarjetas?»).

- **La charla es estrecha a propósito.** Cortesía más cualquier otra cosa («hola, ¿cuánto tarda un envío?», «hola, cuéntame
  un chiste») no es charla: sigue el flujo con recuperación y, sin contexto, recibe el mensaje fijo. Ante la duda
  («envíos», «ok pero cuánto», «👋», «grasias», cualquier cifra) el mensaje es una pregunta. El léxico es cerrado: las listas
  incorporadas están en `SmallTalkConstants` y se pueden reemplazar por configuración (ver la tabla). Quien las amplíe asume
  que todo lo que figure en ellas se salta la búsqueda: solo cortesías, nunca «sí», «no» ni nada con una pregunta, y conviene
  revisarlas con los casos `small_talk`, `injection` y mixtos del dataset golden.
- **Los datos van delimitados.** El mensaje del cliente y las preguntas del historial llegan al modelo entre
  `<mensaje_del_cliente>` y `</mensaje_del_cliente>`; en ellos, en las respuestas anteriores y en los fragmentos se vuelve
  inerte lo que imite un delimitador o un rol (`</contexto>`, `<|im_start|>`, `[INST]`, `<<SYS>>`, líneas de guiones). Lo hace
  el código, no las plantillas: editar los prompts de Langfuse no lo quita. El prompt de sistema por defecto
  prohíbe además revelar, resumir o traducir sus instrucciones y su configuración.
- **El delimitador no llega al cliente.** Algunos modelos pequeños devuelven su respuesta envuelta en
  `<mensaje_del_cliente>…</mensaje_del_cliente>`. `DelimiterEchoFilter` quita esas etiquetas del stream antes de que lleguen
  al cliente, a la traza y a las citas, aunque el modelo las emita partidas entre fragmentos; un `<` que no acaba en etiqueta
  se emite intacto.
- **Es una mitigación, no una garantía.** Los guardrails formales y la limitación de ritmo son de LAB-66. Decisión y
  alternativas: [ADR-0025](adr/0025-conversacion-de-cortesia-acotada.md); especificación en
  `specs/LAB-114-bounded-small-talk/`.
- Con `no-context-mode=model`, los saludos pasan primero por la vía de charla, así que ese modo ya no hace falta para que el
  bot salude; su comportamiento para el resto de preguntas sin contexto no cambia.

## Feedback 👍/👎 en cada respuesta y promoción a golden (LAB-33)

Cuando una respuesta termina con éxito (`done`) y tiene `turnId`, la burbuja del asistente en `/chat` muestra los botones **👍 Útil** (`UP`) y **👎 No útil** (`DOWN`) junto con la opción de añadir o editar un comentario en texto plano (máximo 500 caracteres).

- `PUT /api/chat/feedback/{turnId}` crea o actualiza la única fila asociada al turno en `chat.feedback` (`turn_ref UNIQUE`). Si el cliente cambia de 👍 a 👎 o añade un comentario después, se actualiza la misma fila.
- `GET /api/chat/feedback` y `POST /api/chat/feedback/{turnId}/promote` permiten revisar las respuestas con 👎 desde `/admin-eval` y promoverlas a casos golden `G<NNN>` validados con las reglas de LAB-27 ([ADR-0030](adr/0030-feedback-de-respuestas-y-promocion-a-golden.md)). Una valoración promovida queda bloqueada (`409 SLHD_CHAT_00013`) frente a cambios de voto o nuevas promociones.
- `/actuator/prometheus` publica `shoplab_chat_feedback_total{rating="up|down"}` y `shoplab_chat_feedback_promoted_total`, sin incluir nunca `turnId`, `conversationId` ni textos de la conversación o del comentario.

## Alcance actual

Hay memoria de la conversación en el servidor, pero no hay caducidad ni borrado del historial, ni listado o recuperación
de conversaciones, ni autenticación: quien conoce el `conversationId` es quien accede a esa conversación. La conversación
visible en la pantalla no se restaura al recargar. Las fuentes viajan con la respuesta y no se guardan. Llegan en
historias posteriores.
