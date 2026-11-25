# Contrato de enrutamiento

Interfaz del gateway hacia sus clientes (web, herramienta de evaluación) y hacia los destinos.

## Coincidencia

| ID | Petición | Resultado |
|---|---|---|
| R1 | `<prefix>` exacto | al destino de la ruta |
| R2 | `<prefix>/...` | al destino de la ruta |
| R3 | `<prefix>algo` (`/api/chatbot`) | 404 `SLHD_GATE_00001` |
| R4 | prefijo sin ruta (`/api/catalog`) | 404 `SLHD_GATE_00001` |
| R5 | fuera de `/api` salvo `/actuator` propio | 404 `SLHD_GATE_00001` |
| R6 | segmentos `.` o `..`, literales o codificados | 404 `SLHD_GATE_00001`; nada llega al destino |

## Transparencia

- Sin cambios: método, ruta, consulta, cuerpo, `Host`, credenciales (`Authorization`, `X-Eval-Token`,
  `X-Prompt-Token`, `X-Prompt-Versions`), estado y cuerpo de la respuesta. Se descartan las cabeceras de salto
  (`Connection`, `Transfer-Encoding`, etc.), como en cualquier proxy.
- `Forwarded` y `X-Forwarded-*` de un remitente no confiable **se descartan** (no hay `trusted-proxies`). Ningún destino
  las lee hoy.
- `X-Request-Id`: se conserva si cumple `^[A-Za-z0-9._-]{1,64}$`; si no, se genera. Llega al destino y vuelve **una sola
  vez** al cliente.
- `traceparent`: se conserva; si falta, se genera.
- `text/event-stream`: cada fragmento se entrega al llegar; `X-Accel-Buffering` del destino pasa al cliente.
- Cancelar en el cliente cierra la conexión con el destino.

## Errores del gateway

Cuerpo `{"code": "...", "message": "...", "detail": null}`, sin nombres de host, direcciones ni trazas.

| Código | HTTP | Cuándo |
|---|---|---|
| `SLHD_GATE_00001` | 404 | sin ruta para la petición |
| `SLHD_GATE_00002` | 503 | destino inalcanzable (conexión rechazada, DNS, 2 s de conexión agotados) |
| `SLHD_GATE_00003` | 504 | el destino no respondió dentro del tiempo de la ruta |
| `SLHD_GATE_00004` | 500 | fallo inesperado del gateway (otro estado que fije el framework se conserva) |

Un fallo **después** de enviar las cabeceras (flujo cortado) no puede cambiar el estado: la conexión se interrumpe sin
cerrar el flujo como completo.

Los errores que devuelve el destino (400, 404, 409, 413, 429, 503…) pasan tal cual.

## Gestión propia

`/actuator/health` (con `liveness` y `readiness`), `/actuator/info`, `/actuator/prometheus`. El ingress solo publica
`/api` hacia el gateway, así que no salen del clúster. La disponibilidad de los destinos **no** forma parte de la salud
del gateway (SC-005).

## Registro de acceso

Una línea estructurada por petición: `requestId`, `routeId`, destino, método, estado, duración. No se registran la ruta
completa, la consulta (`/api/knowledge/search` lleva texto del usuario), cabeceras ni cuerpos.
