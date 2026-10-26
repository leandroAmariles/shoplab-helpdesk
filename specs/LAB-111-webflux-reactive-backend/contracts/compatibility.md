# Contrato de compatibilidad: lo que NO puede cambiar con la migración

**Feature**: LAB-111 | Referencia: [LAB-12 chat-stream.md](../../LAB-12-chat-streaming-sse/contracts/chat-stream.md) y
[openapi.yaml](../../LAB-12-chat-streaming-sse/contracts/openapi.yaml)

Esta historia no define una API nueva. Fija qué debe permanecer idéntico y cómo se demuestra. Cualquier diferencia no
listada en «Diferencias aceptadas» es una regresión.

## 1. Chat: `POST /api/chat`

| Aspecto | Debe ser idéntico a LAB-12 | Cómo se demuestra |
|---------|----------------------------|-------------------|
| Ruta, método, cuerpo `{"message": "..."}` | Sí | `ChatStreamingIntegrationTests` |
| `Content-Type` de éxito | `text/event-stream` con `charset=UTF-8` | Cabeceras en el test de contrato |
| `Cache-Control: no-cache`, `X-Accel-Buffering: no` | Sí | `ChatControllerTests` |
| Formato de cada evento | `event:token`/`data:{"content":"..."}`, `event:done`/`data:{"fragments":N}`, `event:error`/`data:{"code":..,"message":..}` | **Golden file** `chat/golden-stream.txt` comparado byte a byte (`ChatStreamContractTests`) |
| Orden y terminación | Un `done` o un `error` final; luego cierra | Mismos escenarios que LAB-12 |
| UTF-8 sin cortar caracteres | Sí | Fixture con tildes, eñes y emoji |
| Primer token | < 2 s con el modelo cargado | `theFirstTokenArrivesWhileTheModelIsStillGenerating` + quickstart |
| Cancelación | Cierre del cliente cancela al modelo; `outcome="cancelled"` | `TestPublisher.assertCancelled()` + integración con `thenCancel()` |
| Cabecera `X-Request-Id` | Eco del valor válido; UUID nuevo si falta o es inseguro (`^[A-Za-z0-9._-]{1,64}$`) | `RequestIdFilterTests` + integración |

## 2. Errores HTTP (siempre JSON, también con `Accept: text/event-stream`)

| Situación | Estado | `code` |
|-----------|--------|--------|
| Mensaje ausente, vacío, solo espacios o > 4000 caracteres | 400 | `SLHD_CHAT_00001` |
| JSON ilegible / cuerpo ausente | 400 | `SLHD_GENE_00002` |
| Modelo no disponible antes del primer evento | 503 | `SLHD_CHAT_00002` |
| Fallo del modelo tras iniciar el flujo | 200 + evento `error` | `SLHD_CHAT_00003` |
| Error inesperado | 500 | `SLHD_GENE_00001` (sin filtrar el mensaje interno) |

Forma del cuerpo: `{"code": "...", "message": "..."}` (`ErrorResponse`). Los errores previos al primer evento los
traduce **solo** el `GlobalExceptionHandler`; el caso de uso y los adaptadores siguen sin construir respuestas HTTP.

## 3. Métricas (LAB-102 y LAB-12)

| Serie | Debe conservar |
|-------|----------------|
| `http_server_requests_seconds_{count,sum,bucket}` | nombre, histograma activado, etiqueta común `application`, agrupación `uri` por plantilla (`/probe/{id}`) |
| `jvm_*`, `process_*`, `system_*` | nombre y etiqueta `application` |
| `shoplab_chat_first_token_seconds_*` | nombre y etiqueta `application` |
| `shoplab_chat_response_seconds_*` | nombre; etiqueta `outcome` con valores `completed`, `error`, `cancelled` |
| `/actuator/prometheus` y `/actuator/health` | 200 en el mismo puerto; `/actuator/env` sigue sin exponerse (404) |

**Procedimiento**: en la rama base (MVC) guardar la lista ordenada de `nombre{claves-de-etiqueta}` de
`/actuator/prometheus` en `specs/LAB-111-webflux-reactive-backend/metrics-baseline.txt`; tras migrar, repetir y
comparar. Las series nuevas o ausentes se listan en la sección siguiente o se corrigen.

## 4. Diferencias aceptadas (a confirmar en el spike T001)

- Métricas JVM de hilos: desaparecen/aparecen series propias de Tomcat (`tomcat_*`) y de Netty (`reactor_netty_*`).
  Los dashboards de LAB-102 no las usan (verificar en `infra/`).
- `http_server_requests` de una petición cancelada puede registrar `outcome="UNKNOWN"`/`status="200"` en lugar de lo
  que registraba Tomcat; la cancelación sigue visible en `shoplab_chat_response_seconds_count{outcome="cancelled"}`.
- Etiqueta `error` de `http_server_requests_seconds_*` (presente en la línea base de MVC): puede no existir en
  WebFlux. Si falta, se acepta salvo que un panel la use (T004: ninguno).
- El nombre de los hilos en los logs cambia (`reactor-http-nio-N` en lugar de `http-nio-8080-exec-N`).
- Cualquier otra diferencia debe documentarse aquí con su justificación antes de aceptarse.

## 5. Configuración

Sin cambios de nombres ni valores por defecto: `OLLAMA_BASE_URL`, `OLLAMA_CHAT_MODEL`, `OLLAMA_KEEP_ALIVE`,
`shoplab.chat.max-message-length`, `server.port`. Nueva propiedad interna: `spring.reactor.context-propagation=auto`
(no es de despliegue).

## 6. Paneles que consumen métricas (T004)

`infra/observability/dashboards/http.json` usa `http_server_requests_*` y `jvm.json` usa `jvm_threads_live_threads` y
`jvm_threads_daemon_threads`. Ningún panel ni regla usa series `tomcat_*`, así que su desaparición no afecta a LAB-102.
Esas series y la etiqueta `uri`/`method`/`status`/`outcome` deben conservarse (verificado en T038–T039).
