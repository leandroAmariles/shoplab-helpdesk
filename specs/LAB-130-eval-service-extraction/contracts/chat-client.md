# Contrato eval → chat

`eval-service` consume una sola operación de `chat-service`: `GET /api/assistant/settings` (FR-013…FR-016).

## Cliente

- Sale del OpenAPI de `chat-service` publicado en `shoplab-contracts` (interfaz reactiva generada). `eval-service` no
  depende de código de `chat` ni conserva el modo `in-process`.
- Detrás de `AssistantSettingsPort` (domain); el adaptador está en `infrastructure/out/chat`.

## Comportamiento

| Aspecto | Regla |
|---|---|
| Tiempo máximo | `SHOPLAB_CHAT_SETTINGS_CLIENT_TIMEOUT`, por defecto `5s` |
| Trazabilidad | Propaga `X-Request-Id` y `traceparent` recibidos |
| Fallo de red, timeout o 5xx | `EvalStoreUnavailableException` → `503 SLHD_EVAL_00005` |
| Respuesta inesperada | Misma traducción; nunca devuelve un cuerpo parcial |
| Aislamiento | Solo afecta a `GET /api/eval/settings`; guardar y leer ejecuciones no lo llaman (FR-016) |

## Prueba de contrato

`EvalChatContractConsumerTest` (existente) se mueve al repo nuevo y usa los stubs
`com.shoplab:shoplab-chat-service:<versión>:stubs`. Se fija la versión de los stubs en el `pom.xml`; subirla es un
cambio explícito. Un cambio incompatible en `chat-service` hace fallar esta prueba, no producción (SC-009).

## Por verificar en la fase A

- Que `chat-service` publique stubs resolubles con las credenciales de GitHub Packages del repo nuevo.
- Que el cliente generado compile junto a los modelos de `eval-service` (Jackson 2 vs 3 en Boot 4), como se detectó en LAB-129.
