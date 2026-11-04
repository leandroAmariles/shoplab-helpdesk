# Chat en streaming (`POST /api/chat`)

El backend responde al mensaje del cliente con la respuesta del modelo local (Ollama) **a medida que se genera**,
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
| `done` | `{"fragments":42}` | La respuesta terminó bien. Es el último evento; el servidor cierra la conexión |
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
| 400 | `SLHD_GENE_00002` | Cuerpo que no es JSON válido |
| 503 | `SLHD_CHAT_00002` | Ollama no responde o el modelo no existe |
| 503 | `SLHD_CHAT_00004` | No se pudo recuperar el contexto de la base de conocimiento (servicio de embeddings o base de datos caídos). El bot no responde sin contexto |
| 500 | `SLHD_GENE_00001` | Error inesperado |

Un cliente que solo acepte `application/json` recibe `406`: el endpoint produce `text/event-stream`. `curl` sin
`Accept` (equivale a `*/*`) funciona.

## Configuración

| Variable / propiedad | Por defecto | Uso |
| --- | --- | --- |
| `OLLAMA_CHAT_MODEL` | `llama3.2:3b` | Modelo de chat |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Dirección de Ollama |
| `OLLAMA_KEEP_ALIVE` | `30m` | Tiempo que Ollama mantiene el modelo cargado tras la última petición. Con el valor por defecto de Ollama (5 min) una pausa larga descarga el modelo y la siguiente petición tarda decenas de segundos |
| `shoplab.chat.max-message-length` | `4000` | Longitud máxima del mensaje, en caracteres |
| `SHOPLAB_CHAT_SYSTEM_PROMPT` / `shoplab.chat.system-prompt` | prompt fundamentado en español | Prompt de sistema; vacío usa el de por defecto (responder solo con el contexto, reconocer lo que no sabe) |
| `SHOPLAB_CHAT_RAG_TOP_K` / `shoplab.chat.rag.top-k` | `4` | Fragmentos que se piden a la búsqueda por pregunta. Rango 1–20; fuera de rango el arranque falla |
| `SHOPLAB_CHAT_RAG_SIMILARITY_THRESHOLD` / `shoplab.chat.rag.similarity-threshold` | `0.6` | Similitud mínima (0–1) de un fragmento; `0` no descarta nada. Con `0` la búsqueda entrega siempre `top-k` fragmentos aunque no se parezcan a la pregunta y el bot responde con contexto de otro tema |
| `SHOPLAB_CHAT_RAG_MAX_CONTEXT_CHARACTERS` / `shoplab.chat.rag.max-context-characters` | `8000` | Máximo de caracteres de contexto que recibe el modelo (mínimo 500); cuenta solo el texto de los fragmentos, no sus cabeceras `[origen › sección]` ni la plantilla (unos cientos de caracteres más); los fragmentos van completos o no van |
| `SHOPLAB_CHAT_RAG_NO_CONTEXT_MODE` / `shoplab.chat.rag.no-context-mode` | `fixed-message` | Qué hacer si no hay fragmentos útiles: `fixed-message` responde el texto de `shoplab.chat.rag.no-context-message` sin llamar al modelo (determinista, no inventa); `model` deja que el modelo conteste con la plantilla de contexto vacío (conserva saludos y cortesías, pero el modelo local puede usar conocimiento general) |
| `shoplab.chat.rag.context-template` / `empty-context-template` | plantillas en español | Mensaje de usuario con y sin contexto; llevan los marcadores `{query}` y `{context}` (la segunda, solo `{query}`). La de contexto no puede llevar otras llaves `{...}` (Spring AI las lee como variables): el arranque falla si no se puede renderizar |
| `OLLAMA_NUM_CTX` | `8192` | Ventana de contexto que se pide a Ollama (`num-ctx`); con la de por defecto el servidor podría recortar el contexto |

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
- Cerrar la conexión del cliente cancela la generación en el modelo.

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

La regla de citas está en el prompt de sistema y en la plantilla de contexto por defecto: quien defina su propio
`SHOPLAB_CHAT_SYSTEM_PROMPT` debe incluirla. `/actuator/prometheus` publica `shoplab_chat_citations_total` con la etiqueta
`outcome` = `valid` (cita solo fuentes que tiene), `invalid` (alguna cita sin fuente) o `none` (no cita), una vez por respuesta
completada con contexto; sin texto de preguntas, respuestas ni fragmentos.

## Alcance actual

Cada petición es independiente: no hay historial ni identificador de conversación, ni autenticación. Las fuentes viajan con
la respuesta y no se guardan. Llegan en historias posteriores.
