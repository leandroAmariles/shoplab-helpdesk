# Data Model: Backend reactivo con Spring WebFlux

**Feature**: LAB-111 | **Date**: 2026-10-26

La historia no persiste datos ni cambia el dominio: **no hay tablas, migraciones ni nuevos objetos de dominio**. Los
modelos del chat (`StreamChatCommand`, `ChatEvent` y sus tres variantes, `ChatOutcome`) y sus DTO REST
(`ChatRequest`, `ChatEventPayload`) quedan exactamente como en LAB-12 (ver
[`specs/LAB-12-chat-streaming-sse/data-model.md`](../LAB-12-chat-streaming-sse/data-model.md)). Lo que sí aparece son
tres piezas de infraestructura sin estado de negocio.

## Objetos sin cambios (referencia)

| Objeto | Capa | Cambio |
|--------|------|--------|
| `StreamChatCommand(message, requestId)` | `chat/domain/model` | Ninguno |
| `ChatEvent` (`TokenEvent`, `DoneEvent`, `ErrorEvent`) | `chat/domain/model` | Ninguno |
| `ChatOutcome` (`COMPLETED`, `ERROR`, `CANCELLED`) | `chat/domain/model/enums` | Ninguno |
| `ChatRequest`, `ChatEventPayload`, `ErrorResponse` | DTO REST | Ninguno |
| `ChatProperties` | `chat/config` | Ninguno |

## Identificador de correlación (contexto de petición)

| Campo | Dónde vive | Reglas |
|-------|------------|--------|
| `requestId` | atributo del `ServerWebExchange`, clave `requestId` del contexto de Reactor y clave `requestId` del MDC | Cabecera `X-Request-Id` si cumple `^[A-Za-z0-9._-]{1,64}$`; si no, UUID nuevo. Se devuelve en la cabecera de respuesta |

- Antes (MVC): atributo de petición servlet + `MDC` de hilo, limpiado en `finally`.
- Ahora: el `WebFilter` lo escribe en el contexto de Reactor (`contextWrite`); la propagación automática lo copia al
  MDC en cada hilo que ejecuta un operador, de modo que no hay `finally` que limpiar a mano.
- Misma semántica de validación, mismos nombres: ver [contracts/compatibility.md](contracts/compatibility.md).

## Resultado de ejecución bloqueante aislada (`BlockingCalls`)

No es una entidad persistente: es el contrato del ayudante. Entrada: un `Callable<T>`/`Runnable`/`Callable<Iterable<T>>`
con código bloqueante. Salida: `Mono<T>`/`Mono<Void>`/`Flux<T>` que ejecuta ese código en `Schedulers.boundedElastic()`
al suscribirse y relanza sus excepciones como señal de error. Detalle en
[contracts/blocking-isolation.md](contracts/blocking-isolation.md).

## Transiciones de estado de una petición de chat

Sin cambios respecto a LAB-12; se repite aquí porque la cancelación es criterio de esta historia:

```text
recibida ──► validada ──► streaming ──► completada   (evento `done`; outcome=completed)
    │            │             ├──────► fallida      (evento `error`; outcome=error)
    │            │             └──────► cancelada    (cliente cierra; cancel al modelo; outcome=cancelled)
    │            └─ inválida ──────────► 400 (sin tocar el modelo)
    └─ modelo caído antes del 1.er evento ► 503
```
