# Contrato: registro por petición del gateway

Una línea JSON por petición atendida o rechazada, escrita en stdout por `shoplab_callbacks.py`. Es la fuente del coste y los
tokens por conversación (FR-024, FR-027). **No contiene** texto de preguntas, respuestas, contexto, claves ni cabeceras de
autorización (FR-026).

## Campos

| Campo | Tipo | Descripción |
|---|---|---|
| `event` | texto | Siempre `llm_request` (permite filtrar entre otras líneas del gateway) |
| `ts` | texto ISO-8601 UTC | Fin de la petición |
| `consumer` | texto | Alias de la clave virtual (`backend-local`, `backend-dev`, `ingest-dev`…) |
| `conversation_id` | texto \| null | UUID de la conversación (campo `user` de chat); `null` en embeddings |
| `operation` | texto | `chat`, `document-index` o `search-query` (de `user` en embeddings) |
| `request_id` | texto \| null | Cabecera `x-shoplab-request-id` |
| `requested_model` | texto | Nombre lógico pedido (`chat`) |
| `served_model` | texto \| null | Modelo real que respondió (`llama3.2:3b`) |
| `served_model_id` | texto \| null | `model_info.id` del despliegue (`chat-primary` / `chat-fallback`) |
| `fallback` | booleano | `true` si respondió un despliegue distinto del principal |
| `prompt_tokens`, `completion_tokens`, `total_tokens` | entero \| null | `null` = desconocido (nunca 0 inventado) |
| `cost_usd` | decimal \| null | Coste con la tabla de precios del gateway |
| `cost_status` | texto | `priced` \| `unpriced` (modelo sin precio) \| `unknown_usage` (no hubo tokens) |
| `latency_ms` | entero | Duración total |
| `ttft_ms` | entero \| null | Tiempo hasta el primer fragmento (solo streaming) |
| `result` | texto | `success` \| `error` \| `budget_exceeded` \| `auth_failed` \| `model_not_found` |
| `error_type` | texto \| null | Tipo de error del gateway (sin mensaje) |

## Ejemplo

```json
{"event":"llm_request","ts":"2026-11-12T15:04:05.123Z","consumer":"backend-local","conversation_id":"3f1c7a52-5b8e-4d0e-9a47-2f6d1c0b8e11","operation":"chat","request_id":"0d3c…","requested_model":"chat","served_model":"llama3.2:1b","served_model_id":"chat-fallback","fallback":true,"prompt_tokens":812,"completion_tokens":143,"total_tokens":955,"cost_usd":0.0000694,"cost_status":"priced","latency_ms":5230,"ttft_ms":930,"result":"success","error_type":null}
```

## Coste y tokens de una conversación (FR-027, SC-006)

```bash
docker compose -f infra/docker-compose.yml logs --no-log-prefix litellm \
  | jq -s --arg c "<conversation-id>" '
      map(select(.event=="llm_request" and .conversation_id==$c))
      | { turns: length,
          prompt_tokens: (map(.prompt_tokens // 0) | add),
          completion_tokens: (map(.completion_tokens // 0) | add),
          cost_usd: (map(.cost_usd // 0) | add),
          unpriced_or_unknown: (map(select(.cost_status!="priced")) | length) }'
```

En el clúster: `kubectl -n shoplab-dev logs deploy/litellm | jq -s …` (misma consulta). `unpriced_or_unknown` > 0 avisa de
que la suma no es completa.

## Reglas

- Los rechazos (presupuesto, clave inválida, modelo desconocido) también generan una línea, con `result` ≠ `success` y
  tokens/coste `null`.
- Una petición con fallback genera **una** línea, con el modelo realmente servido (no una por intento).
- `jq` es una herramienta del desarrollador; el gateway no la necesita.
