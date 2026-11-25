# Data Model: LAB-107

El gateway no tiene base de datos. El único «modelo» es la configuración, leída al arrancar.

## Route

| Campo | Tipo | Obligatorio | Regla |
|---|---|---|---|
| `name` | clave del mapa | sí | minúsculas y dígitos (así se puede fijar por variable de entorno) |
| `prefix` | texto | sí | empieza por `/api/`; segmentos `[a-z0-9-]`; sin `/` final ni comodines |
| `uri` | URI | sí | `http` o `https`, con host; sin ruta, consulta ni credenciales |
| `responseTimeout` | duración | no | positiva; por defecto 120 s |

Reglas del conjunto (FR-010): al menos una ruta; sin prefijos repetidos; ningún prefijo contiene a otro por segmentos
completos (evita depender del orden). Cualquier incumplimiento detiene el arranque y nombra la ruta.

## Rutas iniciales

| `name` | `prefix` | `uri` por defecto |
|---|---|---|
| `chat` | `/api/chat` | monolito |
| `documents` | `/api/documents` | monolito |
| `knowledge` | `/api/knowledge` | monolito |
| `eval` | `/api/eval` | monolito |
| `assistant` | `/api/assistant` | monolito |

## ApiError

Cuerpo de los errores originados en el gateway, compatible con `ApiErrorResponse`: `code` (texto), `message` (texto en
inglés), `detail` (siempre nulo en el gateway).

## Ciclo de vida de una ruta

```text
monolito ──(cambiar uri)──▶ servicio nuevo ──(revertir)──▶ monolito
```

No hay estados intermedios: sin canario ni reparto de tráfico (FR-022).
