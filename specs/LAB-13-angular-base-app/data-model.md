# Data Model: App Angular base

La app no persiste datos. Estas son las estructuras de configuración que usa el layout y el cliente HTTP.

## AppRoute (configuración de ruta)

| Campo | Tipo | Reglas |
|-------|------|--------|
| `path` | texto | Único; `''` es la raíz; `'**'` captura lo no encontrado y va siempre **última** |
| `title` | texto en español | Obligatorio; se muestra en cabecera y título de pestaña |
| `component` | carga diferida (`loadComponent`) | Cada pantalla es standalone |
| `nav` | `NavItem` opcional | Si existe, la ruta aparece en la navegación |

## NavItem (entrada de navegación)

| Campo | Tipo | Reglas |
|-------|------|--------|
| `label` | texto en español | Obligatorio |
| `icon` | nombre de icono Material | Obligatorio |
| `link` | ruta absoluta | Debe corresponder a un `AppRoute.path` |

Las entradas se derivan de la configuración de rutas (una sola fuente de verdad), de modo que añadir una pantalla
exige tocar solo el componente y `app.routes.ts` (SC-004).

## ApiError (error normalizado)

| Campo | Tipo | Reglas |
|-------|------|--------|
| `kind` | `network` \| `http` | `network` cuando no hay respuesta (backend caído); `http` para 4xx/5xx |
| `status` | número opcional | Presente solo si `kind = http` |
| `message` | texto en español | Mensaje apto para mostrar al usuario |

## Estado de la interfaz (signals)

- `isHandset`: derivado del ancho de pantalla (`BreakpointObserver`).
- `sidenavOpen`: abierto/cerrado de la navegación; en pantallas anchas permanece abierto, en estrechas se cierra al
  navegar.
