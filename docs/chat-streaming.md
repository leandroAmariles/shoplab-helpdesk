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

Contrato completo: [`contracts/chat-stream.md`](../specs/LAB-12-chat-streaming-sse/contracts/chat-stream.md) y
[`contracts/openapi.yaml`](../specs/LAB-12-chat-streaming-sse/contracts/openapi.yaml).

## Errores antes de empezar a responder

Si el fallo ocurre antes del primer evento no hay flujo: la respuesta es JSON `{"code","message"}` con estado HTTP.

| Estado | Código | Cuándo |
| --- | --- | --- |
| 400 | `SLHD_CHAT_00001` | Mensaje ausente, vacío, solo espacios o de más de 4000 caracteres |
| 400 | `SLHD_GENE_00002` | Cuerpo que no es JSON válido |
| 503 | `SLHD_CHAT_00002` | Ollama no responde o el modelo no existe |
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
| `shoplab.chat.system-prompt` | texto breve de mesa de ayuda | Prompt de sistema fijo |

## Observabilidad

- Cada petición lleva un `X-Request-Id` (el que envíe el cliente si es válido, o uno generado); se devuelve en la
  respuesta y aparece en los logs. Los logs **no** incluyen el texto del cliente.
- `/actuator/prometheus` publica `shoplab_chat_first_token_seconds` (tiempo hasta el primer token, con histograma) y
  `shoplab_chat_response_seconds` con la etiqueta `outcome` = `completed`, `error` o `cancelled`.
- Cerrar la conexión del cliente cancela la generación en el modelo.

## Detrás de un proxy o ingreso

La respuesta lleva `Cache-Control: no-cache` y `X-Accel-Buffering: no`. Si el proxy intermedio acumula la respuesta
(compresión o buffering), el streaming se pierde: desactiva ambos para la ruta `/api/chat`.

## Alcance actual

Cada petición es independiente: no hay historial ni identificador de conversación, ni recuperación de
conocimiento (RAG), ni autenticación. Llegan en historias posteriores.
