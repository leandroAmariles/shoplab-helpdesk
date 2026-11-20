# Contrato: fijar versiones de prompts en una petición de chat (evaluación)

Cambio en `POST /api/chat`. **Solo lo usa la evaluación.** Un cliente normal no envía estas cabeceras y el comportamiento es el
de siempre.

## Cabeceras

| Cabecera | Valor | Obligatoria |
|---|---|---|
| `X-Prompt-Versions` | lista separada por comas de `nombre=versión`, por ejemplo `chat-system=3,chat-context=2` | no |
| `X-Prompt-Token` | el valor de `shoplab.chat.prompts.override-token` | sí, si hay `X-Prompt-Versions` |

- Nombres válidos: `chat-system`, `chat-small-talk`, `chat-context`, `chat-empty-context`. Cada uno, una sola vez.
- Versión: entero ≥ 1. No se aceptan etiquetas (la evaluación las resuelve antes, para registrar la versión concreta).
- Un prompt que no aparece en la lista usa su versión `production` como siempre.
- Las cabeceras no se registran en los logs de acceso ni van a las trazas (solo sus versiones, como metadatos).

## Respuestas

| Situación | Respuesta |
|---|---|
| Sin `X-Prompt-Versions` | flujo normal; `X-Prompt-Token` se ignora |
| Con `X-Prompt-Versions` y `override-token` sin configurar (vacío) | `403`, código `SLHD_CHAT_00008`; no se abre el stream |
| Con `X-Prompt-Versions` y `prompts.enabled=false` (aunque haya token) | `403`, mismo código: la capacidad solo existe si la gestión de prompts está activa y hay token; no se abre el stream |
| Con `X-Prompt-Versions` y token ausente o distinto (comparación en tiempo constante) | `403`, mismo código; no se abre el stream |
| Cabecera mal formada, nombre desconocido, nombre repetido o versión no entera/< 1 | `400`, código `SLHD_CHAT_00009`; no se abre el stream |
| La versión pedida no existe en Langfuse, no es válida o Langfuse no responde | `422` con el código `SLHD_CHAT_00010` (se resuelve antes del primer fragmento, igual que cualquier fallo anterior a la respuesta); **no** se cae al valor por defecto ni a `production` y no se llama al modelo |
| Todo correcto | flujo normal del chat con esas versiones; la traza lleva los metadatos `prompt_<nombre>=<versión>` y `prompt_override=true` |

## Garantías

- Fijar versiones **no** mueve etiquetas ni cambia la respuesta de ninguna otra petición simultánea: la selección vive en el
  contexto de esa petición.
- La versión fijada se lee con `GET ...?version=N` y se guarda en una caché acotada; no pasa por el refrescador.
- Con `override-token` vacío (por omisión) la capacidad está apagada: no hay forma de usarla.
