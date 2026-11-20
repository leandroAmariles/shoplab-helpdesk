# Contrato: API de prompts de Langfuse que usa el backend

Lo que `LangfusePromptClient` consume y publica. Autenticación en todas: `Authorization: Basic base64(publicKey:secretKey)`
(las mismas claves del proyecto que el envío de trazas). La URL base es `shoplab.chat.prompts.base-url`
(local: `http://localhost:3001`; DEV: `http://langfuse-web:3000`).

> **Estado de verificación**: verificado el 2026-11-19 contra la imagen local `langfuse:4.77.0` (T001, resultados en
> [validation.md](../validation.md)). Lo que difería de lo esperado está marcado «(verificado)».

## Leer un prompt

```http
GET {base}/api/public/v2/prompts/{name}?label=production
GET {base}/api/public/v2/prompts/{name}?version=3
```

Respuesta `200`:

```json
{ "name": "chat-system", "version": 3, "type": "text", "prompt": "Eres el asistente…", "labels": ["production"], "config": {} }
```

| Caso | Qué hace el backend |
|---|---|
| `200` con `type = "text"` | valida (data-model: reglas 2 a 5) y, si es válido y distinto, reemplaza |
| `200` con `type = "chat"` | rechaza con motivo `not-text` |
| `404` `LangfuseNotFoundError` (prompt, etiqueta o versión inexistente; **verificado**: mismo código y mismo error para los tres, solo cambia el mensaje) | motivo `missing`; conserva lo cargado; si `publish-missing`, se comprueba con `?label=latest` si el prompt existe (ver «Publicar») |
| `401` / `403` | motivo `failed`; conserva lo cargado; aviso con el código HTTP, sin la cabecera |
| `5xx`, tiempo agotado, conexión rechazada | motivo `failed`; conserva lo cargado |

El tiempo máximo de cada petición es `request-timeout` (2 s por omisión). Los redireccionamientos no se siguen.

## Publicar un prompt que no existe

```http
POST {base}/api/public/v2/prompts
Content-Type: application/json

{ "name": "chat-system", "type": "text", "prompt": "<valor por defecto>", "labels": ["production"],
  "commitMessage": "Publicación inicial desde el repositorio (LAB-85)" }
```

- Solo se envía si el prompt **no existe** (no si solo falta la etiqueta): un prompt que existe sin la etiqueta `production`
  no se toca (el desarrollador la retiró a propósito o aún no la puso).
- Respuesta `201` con la versión creada (**verificado**: devuelve `version`, `labels` y añade solo la etiqueta automática
  `latest`). Cualquier otra respuesta se registra como `failed` y se reintenta en el siguiente arranque, no en cada intervalo.
- **Distinguir «el prompt no existe» de «la etiqueta no existe» (verificado)**: la API responde `404` en ambos casos, y un
  `GET` sin `label` ni `version` equivale a `label=production` (da `404` si falta la etiqueta). La solución es consultar
  `GET .../{name}?label=latest`: Langfuse pone `latest` automáticamente en la versión más reciente de todo prompt, así que
  `404` con `label=latest` significa «el prompt no existe» y `200` significa «existe».
- `POST` **no es idempotente (verificado)**: repetirlo con el mismo nombre crea la versión 2. Por eso el publicador siempre
  comprueba antes con `label=latest`.
- Un prompt `type: chat` se acepta y se lee con `type = "chat"` (el cliente lo rechaza con `not-text`).

## Lo que el backend nunca hace

- No mueve ni quita etiquetas, ni crea versiones de un prompt que ya existe.
- No envía a Langfuse texto de conversaciones por esta API (solo los textos de los prompts por defecto).
- No escribe las claves en el registro, en las métricas ni en los mensajes de error.
