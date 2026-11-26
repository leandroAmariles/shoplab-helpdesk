# Contract: HTTP API de `chat-service`

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md)

`chat-service` conserva exactamente la superficie HTTP, los formatos de respuesta (sin envoltorio genérico de la
plantilla), las cabeceras y los códigos de error `SLHD_CHAT_*` del módulo `chat` en el monolito.

---

## 1. Operaciones expuestas

| Método | Ruta | Content-Type / Respuesta | Consumidores |
|---|---|---|---|
| `POST` | `/api/chat` | `text/event-stream` (SSE: eventos `delta`, `citations`, `done`, `error`) | Frontend Angular, `shoplab-eval` (LAB-29) |
| `GET` | `/api/assistant/settings` | `application/json` (`AssistantSettingsResponse`) | `eval` (monolito → `eval-service`), `shoplab-eval` |
| `PUT` | `/api/chat/feedback/{turnId}` | `application/json` (`FeedbackResponse`) | Frontend Angular |
| `GET` | `/api/chat/feedback` | `application/json` (lista filtrable por `rating`, paginación/límite) | Frontend Angular (consola admin) |
| `POST` | `/api/chat/feedback/{turnId}/promote` | `application/json` (promoción a caso golden `G<NNN>`) | Frontend Angular (consola admin) |
| `GET` | `/actuator/health` | `application/json` (`liveness`, `readiness`) | Kubernetes probes, Docker Compose |
| `GET` | `/actuator/prometheus` | `text/plain` (métricas Micrometer) | Prometheus |

---

## 2. Cabeceras relevantes

- **`X-Request-Id`**: correlación obligatoria propagada en respuestas, logs y llamadas salientes a `retrieval-service`.
- **`traceparent`**: contexto W3C distribuido propagado reactivamente (`spring.reactor.context-propagation=auto`).
- **`X-Prompt-Versions` y `X-Prompt-Token`**: override controlado de versiones de prompts en `POST /api/chat` para
  experimentos y evaluación (LAB-85).

---

## 3. Catálogo de errores conservado (`SLHD_CHAT_*`)

| Código | HTTP | Caso |
|---|---|---|
| `SLHD_CHAT_00001` | 400 | Mensaje vacío o supera longitud máxima (`4000`) |
| `SLHD_CHAT_00002` | 503 | Modelo de chat / LiteLLM no disponible |
| `SLHD_CHAT_00003` | 500 | Fallo durante el streaming del modelo |
| `SLHD_CHAT_00004` | 503 | `retrieval-service` no disponible o timeout |
| `SLHD_CHAT_00005` | 400 | `conversationId` no es un UUID válido |
| `SLHD_CHAT_00006` | 503 | Memoria de conversación (PostgreSQL `chat`) no disponible |
| `SLHD_CHAT_00007` | 429 | Presupuesto de LiteLLM agotado |
| `SLHD_CHAT_00008` | 403 | Override de prompts no permitido / token inválido |
| `SLHD_CHAT_00009` | 400 | Formato inválido en `X-Prompt-Versions` |
| `SLHD_CHAT_00010` | 422 | Versión de prompt solicitada no disponible |
| `SLHD_CHAT_00011` | 400 | Petición de feedback inválida |
| `SLHD_CHAT_00012` | 404 | Turno o feedback no encontrado |
| `SLHD_CHAT_00013` | 409 | Feedback ya promocionado a golden |
| `SLHD_CHAT_00014` | 422 | Caso golden no cumple reglas del dataset |
| `SLHD_CHAT_00015` | 422 | Solo feedback `DOWN` puede promocionarse |

---

## 4. Contrato productor (Spring Cloud Contract + OpenAPI)

- **Fuente OpenAPI**: `shoplab-contracts` (`v1.4.0`) define los esquemas y operaciones de `chat`.
- **Verificación de productor**: `shoplab-chat-service` migra `src/test/resources/contracts/chat/shouldReturnAssistantSettings.yml`
  y `ChatContractBase` (`testMode=WEBTESTCLIENT`) y publica `com.shoplab:shoplab-chat-service:<version>:stubs` en GitHub Packages.
