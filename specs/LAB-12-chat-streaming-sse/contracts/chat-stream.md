# Contrato del flujo de eventos: `POST /api/chat`

**Feature**: LAB-12 | Petición, cabeceras y errores HTTP: [openapi.yaml](openapi.yaml)

## Petición

```http
POST /api/chat
Content-Type: application/json
Accept: text/event-stream

{ "message": "¿Dónde está mi pedido?" }
```

## Respuesta 200

`Content-Type: text/event-stream;charset=UTF-8`, con `Cache-Control: no-cache` y `X-Accel-Buffering: no`.
Cada evento sigue el formato SSE estándar: líneas `event:` y `data:` y una línea en blanco. `data` es siempre
una sola línea de JSON (los saltos de línea del texto van escapados como `\n` dentro del JSON).

### Evento `token` — un fragmento de la respuesta

```text
event: token
data: {"content":"Hola"}

```

- Cero o más por respuesta, en el orden en que los genera el modelo.
- Concatenar los `content` en orden da la respuesta completa. Un fragmento puede estar vacío o ser solo espacios.

### Evento `done` — respuesta completa

```text
event: done
data: {"fragments":42}

```

- Es el último evento de una respuesta correcta; el servidor cierra la conexión justo después.
- `fragments` es el número de eventos `token` enviados (0 si el modelo no generó texto).

### Evento `error` — fallo después de iniciado el flujo

```text
event: error
data: {"code":"SLHD_CHAT_00003","message":"The chat model failed while generating the answer"}

```

- Es el último evento; el servidor cierra la conexión justo después. No va precedido de `done`.
- `code` pertenece a `ResponseCodeEnum`; `message` es una descripción para depurar, no para mostrar tal cual.

## Garantías

| Garantía | Detalle |
|----------|---------|
| Terminación | Todo flujo `200` acaba con exactamente un `done` o un `error`, salvo que el cliente cierre antes |
| Orden | Los `token` llegan en orden de generación; nunca se mezclan con los de otra petición |
| Codificación | UTF-8; un carácter multibyte nunca se parte entre dos eventos |
| Errores previos | Si el fallo ocurre antes del primer evento, no hay flujo: respuesta HTTP JSON (400/503/500) |
| Cancelación | Cerrar la conexión cancela la generación en el modelo; no se envía ningún evento más |
| Sin estado | Cada petición es independiente; no hay historial ni identificador de conversación |

## Consumo desde un cliente

- `curl -N` (sin buffer) para pruebas desde la terminal: ver [quickstart.md](../quickstart.md).
- Navegador: `EventSource` solo admite GET, por lo que el frontend debe usar `fetch` con el cuerpo `POST` y leer el
  `ReadableStream` (o una librería SSE que admita POST). Fuera del alcance de esta historia.
