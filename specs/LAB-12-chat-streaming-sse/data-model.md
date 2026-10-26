# Data Model: Respuesta del chat en streaming (SSE)

**Feature**: LAB-12 | **Date**: 2026-10-26

La historia no persiste datos (FR-018): no hay tablas, migraciones ni cambios en PostgreSQL. El modelo son los
objetos en memoria que atraviesan la petición. Viven en `chat/domain/model` (sin lógica; excluidos de cobertura).

## StreamChatCommand (entrada del caso de uso)

| Campo | Tipo | Reglas |
|-------|------|--------|
| `message` | `String` | Obligatorio; no vacío ni solo espacios; longitud ≤ `shoplab.chat.max-message-length` (4000) |
| `requestId` | `String` | Obligatorio; identificador de correlación (de `X-Request-Id` o generado) |

- Lo construye el controlador desde `ChatRequest` (DTO) mediante un mapper explícito.
- La validación del mensaje (ausente/vacío/longitud) ocurre **una sola vez, en el caso de uso**, para que ningún
  adaptador de entrada pueda saltársela; el borde REST no la duplica.

## ChatEvent (salida del caso de uso)

Tipo sellado con tres variantes; el controlador las traduce a eventos SSE.

| Variante | Campos | Significado |
|----------|--------|-------------|
| `TokenEvent` | `content: String` | Un fragmento de texto generado, en orden |
| `DoneEvent` | `fragments: int` | La respuesta terminó correctamente |
| `ErrorEvent` | `code: String`, `message: String` | La respuesta falló tras iniciarse el flujo |

### Transiciones de un flujo

```text
        ┌─ error previo al primer evento ──▶ excepción tipada ──▶ HTTP 4xx/503 (sin flujo)
inicio ─┤
        └─ primer fragmento ──▶ TokenEvent* ──┬─▶ DoneEvent ──▶ cierre
                                              ├─▶ ErrorEvent ──▶ cierre
                                              └─▶ cancelación del cliente ──▶ (sin evento) cierre
```

- Un flujo emite **cero o más** `TokenEvent` y **exactamente uno** de `DoneEvent`/`ErrorEvent`, salvo cancelación.
- Una respuesta vacía del modelo produce solo `DoneEvent` con `fragments = 0`.

## ChatOutcome (para métricas)

Enumeración `COMPLETED`, `ERROR`, `CANCELLED` en `model/enums`; su valor externo en minúsculas es la etiqueta
`outcome` de `shoplab.chat.response`.

## Códigos de error (`ResponseCodeEnum`, módulo `shared`)

Formato `<SERVICE>_XXXX_NNNNN` con prefijo de servicio `SLHD`.

| Código | HTTP | Cuándo |
|--------|------|--------|
| `SLHD_CHAT_00001` | 400 | Mensaje ausente, vacío, solo espacios o demasiado largo |
| `SLHD_CHAT_00002` | 503 | Modelo no disponible antes de emitir el primer evento |
| `SLHD_CHAT_00003` | — (evento `error`) | El modelo falló después de iniciado el flujo |
| `SLHD_GENE_00001` | 500 | Error inesperado no clasificado |
| `SLHD_GENE_00002` | 400 | Cuerpo de la petición ilegible (JSON mal formado) |

## Configuración (`ChatProperties`, paquete `config`)

| Propiedad | Por defecto | Uso |
|-----------|-------------|-----|
| `shoplab.chat.max-message-length` | `4000` | Límite del mensaje (FR-007) |
| `shoplab.chat.system-prompt` | texto mínimo de mesa de ayuda | Prompt de sistema fijo del adaptador |
| `spring.ai.ollama.base-url` | `http://localhost:11434` | Dirección de Ollama |
| `spring.ai.ollama.chat.options.model` | `${OLLAMA_CHAT_MODEL:llama3.2:3b}` | Modelo de chat |
