# Contract: Cliente HTTP `eval` → `chat-service`

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md)

Define la comunicación HTTP entre el módulo `eval` (en `backend/` hasta LAB-130) y `chat-service` para sustituir la
llamada en proceso `AssistantSettingsApi`.

---

## 1. Operación consumida

- **Endpoint**: `GET /api/assistant/settings`
- **Interfaz generada**: `com.shoplab.contracts.chat.client.AssistantSettingsApi` (generada en `shoplab-contracts` `1.4.0`,
  mismo patrón reactivo `@HttpExchange` que `KnowledgeSearchApi` en LAB-129).
- **Respuesta esperada (`200 OK`)**:

```json
{
  "topK": 4,
  "similarityThreshold": 0.6,
  "maxContextCharacters": 8000,
  "prompts": [
    { "name": "chat-system", "version": 3, "source": "LANGFUSE" },
    { "name": "chat-small-talk", "version": null, "source": "DEFAULT" }
  ]
}
```

---

## 2. Reglas del adaptador (`HttpAssistantSettingsClient`)

- **Implementación**: `com.shoplab.helpdesk.eval.infrastructure.out.chat.HttpAssistantSettingsClient` implementa
  `AssistantSettingsPort`.
- **Propiedades de configuración**:
  - `shoplab.chat.settings.client.mode`: `in-process` (defecto en Release A) | `http` (activado en Corte; único modo tras Release B).
  - `shoplab.chat.settings.client.base-url`: dirección base de `chat-service` (local: `http://chat-service:8080` o `http://localhost:8083`; dev: `http://chat-service:8080`).
  - `shoplab.chat.settings.client.timeout`: tiempo máximo de espera por llamada (defecto: `5s`).
- **Propagación de contexto**: el `WebClient` configurado propaga `X-Request-Id` y `traceparent` desde el contexto reactivo.
- **Traducción de errores**: cualquier fallo de red, timeout, respuesta vacía o 4xx/5xx se traduce mediante `onErrorMap`
  a una excepción tipada del módulo `eval` (`TechnicalException` / `EVAL_STORE_UNAVAILABLE` o equivalente manejado por
  `GlobalExceptionHandler`), nunca con `.block()` ni devolviendo `null`.

---

## 3. Verificación por contrato (consumidor)

- **Stubs**: `com.shoplab:shoplab-chat-service:<version>:stubs` desde GitHub Packages (`https://maven.pkg.github.com/leandroAmariles/shoplab-chat-service`).
- **Test**: `EvalChatContractConsumerTest` usa `StubRunnerExtension` contra `com.shoplab:shoplab-chat-service` (en lugar
  de `com.shoplab:helpdesk-backend`) y valida:
  - Deserialización de `topK`, `similarityThreshold`, `maxContextCharacters`.
  - Prompts con `source=LANGFUSE` (versión entera) y `source=DEFAULT` (`version=null`).
- **Prueba adicional de resiliencia (WireMock)**: timeout superado y HTTP 503 traducidos al error tipado esperado.
