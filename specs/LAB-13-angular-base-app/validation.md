# Validación: App Angular base

## Línea base (T008, 2026-10-26)

Node 22.22.3, npm 10.9.8, Angular 22.2.x.

| Comando | Resultado |
|---|---|
| `npm run lint` | OK, "All files pass linting" |
| `npm test -- --watch=false` | OK, 1 archivo, 2 pruebas (proyecto recién generado) |
| `npm run build` | OK, bundle inicial 216.88 kB; salida en `dist/browser` |

## Proxy de desarrollo (T027, 2026-10-26)

Se probó con `ng serve` y un servidor SSE mínimo en el puerto 8080 que simula `POST /api/chat` de LAB-12 (un evento
`token` cada 500 ms y un `done` final). **No se probó contra el backend real**: eso queda para la pantalla de chat
(LAB-14).

| Caso | Resultado |
|---|---|
| `curl -N http://localhost:4200/api/chat` con `Accept: text/event-stream` | Los eventos llegan separados ~0,5 s entre sí; no hay buffering, no hace falta `proxy.conf.js` |
| Backend caído, `POST /api/chat` | HTTP 502 del proxy; `http://localhost:4200/` sigue respondiendo 200 |

## Imagen de contenedor (T030, 2026-10-26)

`docker build -t shoplab-frontend frontend` y `docker run -p 8081:8080`:

| Caso | Resultado |
|---|---|
| `GET /` | 200, título «ShopLab Helpdesk» (app real, no la página de relevo) |
| `GET /algo`, `GET /chat` (recarga de ruta interna) | 200 con la app; no hay 404 de nginx |
| `index.html` | `Cache-Control: no-cache` |
| Usuario del proceso | UID 101 (no root) |

## Verificación final (T031, 2026-10-26)

Desde `node_modules` borrado: `npm ci`, `npm run lint`, `npm test -- --watch=false`, `npm run build`.

| Comando | Resultado |
|---|---|
| `npm ci` / `npm audit` | OK, 0 vulnerabilidades (con el override de `vite`, ver research.md) |
| `npm run lint` | OK, 0 errores y 0 advertencias |
| `npm test -- --watch=false` | OK, 6 archivos, 15 pruebas |
| `npm run build` | OK, bundle inicial 475.65 kB, sin avisos de presupuesto |

**Pendiente (manual)**: revisar la app en el navegador a 360 px y a 1440 px (SC-006). El cambio de modo del menú
(lateral/superpuesto) está cubierto por pruebas unitarias, pero no se ha mirado visualmente.
