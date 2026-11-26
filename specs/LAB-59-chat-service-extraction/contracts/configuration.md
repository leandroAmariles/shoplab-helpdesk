# Contract: Configuración, Secretos y Enrutamiento

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md)

---

## 1. Variables de entorno de `chat-service`

| Variable | Local (compose) | Dev (`shoplab-infra`) | Origen |
|---|---|---|---|
| `POSTGRES_URL` | `jdbc:postgresql://postgres:5432/chat` | `jdbc:postgresql://host.docker.internal:5432/chat` (o servicio equivalente) | Config / `ExternalSecret` |
| `POSTGRES_USER` | `chat` | `chat` | `chat-service-db-secret` |
| `POSTGRES_PASSWORD` | `${CHAT_DB_PASSWORD:-change-me-local-only}` | secreto en Secrets Manager | `chat-service-db-secret` |
| `SPRING_AI_OPENAI_BASE_URL` | `http://litellm:4000/v1` | `http://litellm:4000/v1` | ConfigMap |
| `SPRING_AI_OPENAI_API_KEY` | `${LITELLM_KEY_CHAT:-sk-shoplab-chat-local}` | secreto `LITELLM_KEY_CHAT` | `ExternalSecret` |
| `SHOPLAB_RETRIEVAL_CLIENT_BASE_URL` | `http://retrieval-service:8080` | `http://retrieval-service:8080` | ConfigMap |
| `SHOPLAB_RETRIEVAL_CLIENT_TIMEOUT` | `15s` | `15s` | ConfigMap |
| `SHOPLAB_CHAT_PROMPTS_ENABLED` | `${SHOPLAB_CHAT_PROMPTS_ENABLED:-false}` | según entorno `dev` | ConfigMap |
| `SHOPLAB_CHAT_PROMPTS_BASE_URL` | `http://langfuse-web:3000` (perfil `llmops`) | URL Langfuse de `dev` | ConfigMap |
| `SHOPLAB_CHAT_PROMPTS_PUBLIC_KEY` / `SECRET_KEY` | `.env` | `ExternalSecret` | Secreto |
| `SHOPLAB_CHAT_PROMPTS_OVERRIDE_TOKEN` | `.env` | `ExternalSecret` | Secreto |
| `SHOPLAB_CHAT_TRACING_ENABLED` | `${SHOPLAB_CHAT_TRACING_ENABLED:-false}` | según entorno `dev` | ConfigMap |
| `SHOPLAB_CHAT_TRACING_ENDPOINT` | `http://langfuse-web:3000/api/public/otel` | URL OTLP Langfuse `dev` | ConfigMap |
| `SHOPLAB_CHAT_TRACING_PUBLIC_KEY` / `SECRET_KEY` | `.env` | `ExternalSecret` | Secreto |
| `SHOPLAB_CHAT_TRACING_ENVIRONMENT` | `local` | `dev` | ConfigMap |

Puerto interno: `8080`. Puerto local de depuración en el host: `${CHAT_PORT:-8083}:8080` (`8080` gateway, `8081` backend,
`8082` retrieval-service, `8083` chat-service).

---

## 2. Clave virtual en LiteLLM (`infra/litellm/keys.yaml`)

```yaml
  - alias: chat-${GATEWAY_ENVIRONMENT}
    consumer: chat
    key_env: LITELLM_KEY_CHAT
    max_budget: 5.00
    budget_duration: 1d
    models: [chat, chat-fallback]
    models_when:
      - env: CLOUD_PROVIDER_API_KEY
        models: [chat-cloud]
```

---

## 3. Enrutamiento del gateway (`shoplab-gateway`)

### En `dev` (`shoplab-infra/apps/gateway/overlays/dev/kustomization.yaml`)

```yaml
      - SHOPLAB_GATEWAY_ROUTES_CHAT_URI=http://chat-service:8080
      - SHOPLAB_GATEWAY_ROUTES_ASSISTANT_URI=http://chat-service:8080
```

### En local (`infra/docker-compose.yml`)

`gateway` recibe por defecto `SHOPLAB_GATEWAY_ROUTES_CHAT_URI=http://chat-service:8080` y
`SHOPLAB_GATEWAY_ROUTES_ASSISTANT_URI=http://chat-service:8080` (sobrescribibles por `.env`).

---

## 4. Configuración de `backend` (`eval` → `chat-service`)

- **Release A (expansión)**: `SHOPLAB_CHAT_SETTINGS_CLIENT_MODE=in-process` (defecto); en el commit de corte se
  activa `SHOPLAB_CHAT_SETTINGS_CLIENT_MODE=http` y `SHOPLAB_CHAT_SETTINGS_CLIENT_BASE_URL=http://chat-service:8080`.
- **Release B (contracción)**: desaparece el switch `mode` y `backend` usa siempre `SHOPLAB_CHAT_SETTINGS_CLIENT_BASE_URL`
  (defecto `http://localhost:8083` en desarrollo, `http://chat-service:8080` en compose y `dev`) y
  `SHOPLAB_CHAT_SETTINGS_CLIENT_TIMEOUT` (`5s`). Se eliminan de `backend` las variables `SPRING_AI_OPENAI_*`,
  `SHOPLAB_RETRIEVAL_CLIENT_*` y `SHOPLAB_CHAT_*` (salvo `SHOPLAB_CHAT_SETTINGS_CLIENT_*`).
