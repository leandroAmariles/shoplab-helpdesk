# Configuración de eval-service

Las propiedades de `eval` se conservan con los mismos nombres que en el monolito (menos ruido para la herramienta y los
manifiestos). Los valores secretos nunca van al repositorio (Constitución VI).

| Variable | Propiedad | Por defecto | Notas |
|---|---|---|---|
| `POSTGRES_URL` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `spring.datasource.*` | local: base `eval`, rol `eval` | En dev vienen del `ExternalSecret` `eval-service-db-secret` |
| `SHOPLAB_EVAL_PERSISTENCE_TOKEN` | `shoplab.eval.persistence.token` | vacío = guardado desactivado (403) | Secreto; el mismo valor que usa la herramienta |
| `SHOPLAB_EVAL_HISTORY_TRACE_URL_TEMPLATE` | `shoplab.eval.history.trace-url-template` | `http://localhost:3001/trace/{traceId}` | Enlace a Langfuse en `admin-eval` |
| `SHOPLAB_CHAT_SETTINGS_CLIENT_BASE_URL` | `shoplab.chat.settings.client.base-url` | local: `http://chat-service:8080`; dev: `http://chat-service:8080` | Antes `http://localhost:8083` en el monolito |
| `SHOPLAB_CHAT_SETTINGS_CLIENT_TIMEOUT` | `shoplab.chat.settings.client.timeout` | `5s` | |
| `SERVER_PORT` | `server.port` | `8080` | Compose publica `8084` solo para depurar |

Se eliminan: `shoplab.chat.settings.client.mode` (solo HTTP), todo `spring.ai.*`, OpenTelemetry/Langfuse y claves de LiteLLM.

## En otros repositorios

| Dónde | Cambio |
|---|---|
| `shoplab-infra/apps/eval-service` | Completar variables y `newTag` (esqueleto de LAB-104) |
| `shoplab-infra/apps/gateway/overlays/dev` | `SHOPLAB_GATEWAY_ROUTES_EVAL_URI=http://eval-service:8080` (el corte) |
| `shoplab-eval` | URL base de guardado apuntando al gateway (sin cambio de rutas); sin otro cambio |
| `infra/docker-compose.yml` | `eval-db-init`, `eval-service`; sin `backend` tras la fase D |
| `infra/.env.example` | `EVAL_DB_PASSWORD`, `SHOPLAB_EVAL_PERSISTENCE_TOKEN` |
