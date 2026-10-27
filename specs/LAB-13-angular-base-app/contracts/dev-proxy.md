# Contrato: proxy de desarrollo `/api`

Aplica solo a `ng serve` (no al contenedor, donde el enrutado lo resuelve la infraestructura).

| Aspecto | Contrato |
|---------|----------|
| Prefijo | Toda petición relativa que empiece por `/api` se reenvía al backend |
| Destino por defecto | `http://localhost:8080` (puerto del backend en `application.yml`) |
| Punto único de cambio | `frontend/proxy.conf.json` |
| Métodos y cabeceras | Se reenvían sin cambios (incluidos `Accept: text/event-stream` y `Content-Type`) |
| Streaming | Las respuestas `text/event-stream` llegan al navegador evento a evento, sin acumularse |
| Backend caído | El proxy responde un error de conexión (5xx del proxy); la app lo trata como `ApiError.network` |
| CORS | No se configura nada en el backend: el navegador solo habla con el origen del servidor de desarrollo |

**Verificación**: ver [quickstart.md](../quickstart.md), escenarios 3 y 4.
