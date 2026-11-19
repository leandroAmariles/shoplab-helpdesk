# Contrato: configuración del gateway

Archivos versionados en `infra/litellm/` (compose los monta; el clúster los genera como `ConfigMap` desde los mismos
archivos). Los fragmentos son la **forma** esperada; los valores finales se fijan tras los spikes S-1 a S-5.

## `config.yaml`

```yaml
model_list:
  # Chat: principal y respaldo, ambos locales (FR-010, FR-013).
  - model_name: chat
    litellm_params:
      model: ollama_chat/${LITELLM_CHAT_PRIMARY_MODEL:-llama3.2:3b}
      api_base: http://ollama:11434
      num_ctx: 8192          # antes spring.ai.ollama.chat.options.num-ctx
      keep_alive: 30m        # antes spring.ai.ollama.chat.options.keep-alive
    model_info:
      id: chat-primary
      input_cost_per_token: 0.00000015     # precio de referencia (FR-022)
      output_cost_per_token: 0.0000006
  - model_name: chat-fallback
    litellm_params:
      model: ollama_chat/${LITELLM_CHAT_FALLBACK_MODEL:-llama3.2:1b}
      api_base: http://ollama:11434
      num_ctx: 8192
    model_info:
      id: chat-fallback
      input_cost_per_token: 0.00000005
      output_cost_per_token: 0.0000002
  # Embeddings: sin fallback (FR-009).
  - model_name: embeddings
    litellm_params:
      model: ollama/${LITELLM_EMBED_MODEL:-bge-m3}
      api_base: http://ollama:11434
    model_info:
      id: embeddings-primary
      input_cost_per_token: 0.0000001
  # Proveedor cloud: la plantilla solo incluye este bloque si CLOUD_PROVIDER_API_KEY no está vacío (FR-011).
  # - model_name: chat-cloud
  #   litellm_params: { model: <proveedor>/<modelo>, api_key: os.environ/CLOUD_PROVIDER_API_KEY }

router_settings:
  fallbacks: [{ chat: [chat-fallback] }]     # FR-013; ni embeddings ni chat-cloud
  num_retries: 1
  timeout: 60                                 # antes del primer fragmento
  stream_timeout: 30
  allowed_fails: 1
  cooldown_time: 30                           # el principal vuelve solo (FR-016)

litellm_settings:
  callbacks: shoplab_callbacks.shoplab_logger # una línea JSON por petición (log-schema.md)
  turn_off_message_logging: true              # sin contenido en ningún callback (FR-026)
  enable_mid_stream_fallback_continuation: false
  drop_params: true                           # Ollama rechaza `user` en embeddings; el callback lo sigue viendo
  json_logs: true
  request_correlation_in_logs: true

general_settings:
  master_key: os.environ/LITELLM_MASTER_KEY
  database_url: os.environ/DATABASE_URL
  store_prompts_in_spend_logs: false
  disable_reset_budget: false                 # el presupuesto se reinicia solo (FR-020)
```

Notas:

- `${VAR:-valor}` lo expande la plantilla de arranque (`envsubst`) o, si el spike S-1 confirma que LiteLLM lo hace, se usa
  `os.environ/…`; el contrato es el mismo: el modelo real se cambia por variable o editando este archivo, sin tocar el
  backend (FR-007).
- Los precios de ejemplo son de referencia, no de ningún proveedor concreto (spec, Assumptions).
- `chat-fallback` no es un nombre que el backend pida; existe solo como destino.
- Validación al arrancar (FR-008): el `entrypoint` ejecuta una comprobación que lista los modelos de Ollama
  (`/api/tags`) y registra una línea `WARN model_missing model=<…> name=<…>` por cada modelo configurado y no descargado,
  sin impedir el arranque (el respaldo puede no estar descargado en máquinas pequeñas).

## `keys.yaml`

```yaml
environment: ${GATEWAY_ENVIRONMENT}          # local | dev
keys:
  - alias: backend-${GATEWAY_ENVIRONMENT}
    consumer: backend
    key_env: LITELLM_KEY_BACKEND             # el valor viene del entorno/secreto, nunca de este archivo
    max_budget: 5.00                         # USD de referencia por periodo
    budget_duration: 1d
    models: [chat, chat-fallback, embeddings]   # chat-fallback: sin permiso LiteLLM omite el fallback
  - alias: ingest-${GATEWAY_ENVIRONMENT}
    consumer: ingest
    key_env: LITELLM_KEY_INGEST
    max_budget: 1.00
    budget_duration: 1d
    models: []                               # ingest no llama a modelos hoy; bootstrap_keys.py lo traduce a un nombre inexistente (`models: []` de LiteLLM = todos)
```

## `bootstrap_keys.py`

| Aspecto | Contrato |
|---|---|
| Entrada | `keys.yaml`, `LITELLM_BASE_URL`, `LITELLM_MASTER_KEY` y las variables que nombra `key_env` |
| Idempotencia | Ejecutado N veces deja el mismo estado: crea las claves que faltan y actualiza presupuesto, periodo y modelos si cambiaron |
| Esperas | Reintenta hasta `BOOTSTRAP_TIMEOUT` (defecto 120 s) a que el gateway responda `/health/readiness` |
| Salida | Una línea por clave: `created|updated|unchanged alias=<…>`; código 0 si todas convergieron, ≠ 0 si no |
| Secretos | No imprime valores de claves; solo alias |
| Validación | Reglas de [data-model.md](../data-model.md#clave-virtual-keysyaml) |

## `shoplab_callbacks.py`

Clase `CustomLogger` de LiteLLM instanciada como `shoplab_logger`. Contrato de comportamiento:

1. Un éxito (`async_log_success_event`) o el fallo final de la petición (`async_post_call_failure_hook`) ⇒ exactamente una
   línea JSON en stdout con el esquema de [log-schema.md](log-schema.md). Los intentos fallidos que un fallback rescata no
   se registran.
2. Nunca lee ni imprime `messages`, `input`, `response` ni cabeceras de autorización.
3. Un fallo del propio callback no rompe la petición (captura y una línea `callback_error` sin datos).
4. Campos ausentes ⇒ `null`; nunca `0` inventado (FR-028).
