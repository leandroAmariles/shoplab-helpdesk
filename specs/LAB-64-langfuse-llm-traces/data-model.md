# Modelo de datos: Trazas de LLM con Langfuse (LAB-64)

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

La historia no añade tablas ni migraciones: la base de datos de la aplicación no cambia. Los datos nuevos son (a) objetos
de dominio en memoria que describen un turno y (b) las entidades que Langfuse guarda en sus propios almacenes. Aquí
se describen los primeros; el mapeo a (b) está en [contracts/trace-schema.md](contracts/trace-schema.md).

## Dominio del módulo `chat` (`domain/model`, Java puro)

Registros inmutables, sin dependencias de Spring AI, Reactor ni OpenTelemetry (reglas D1/D2 de ArchUnit).

### `TokenUsage`
Uso de tokens de una generación. Un valor `null` significa **desconocido**, nunca cero.

| Campo | Tipo | Reglas |
|---|---|---|
| `inputTokens` | `Integer` (anulable) | ≥ 0 si existe |
| `outputTokens` | `Integer` (anulable) | ≥ 0 si existe |

Operaciones: `total()` (vacío si falta alguno), `isKnown()`, `plus(TokenUsage)` (suma de las vueltas del bucle de
herramientas; desconocido + conocido = conocido solo si el otro lado informó).

### `PromptMessage`
Un mensaje del prompt enviado al modelo: `role` (`system`, `user`, `assistant`) y `content`. Orden = orden de envío.

### `RetrievalTrace`
| Campo | Tipo | Reglas |
|---|---|---|
| `searchText` | `String` | texto realmente buscado (pregunta + preguntas previas de la memoria) |
| `candidates` | `int` | encontrados antes de seleccionar, ≥ 0 |
| `delivered` | `List<ContextFragment>` | los entregados al modelo, en el orden de las citas; se reutiliza `ContextFragment` (fuente, título, sección, posición, texto, puntuación) |
| `failed` | `boolean` | la búsqueda falló |

La duración la mide el adaptador entre el inicio y el cierre del paso.

### Generación (sin registro propio)
No existe `GenerationTrace`: `TurnTrace.startGeneration(model, parameters, messages)` recibe directamente el modelo, los
parámetros relevantes (lista blanca, sin credenciales) y el prompt completo (`PromptMessage`: sistema, historial y
pregunta con contexto), y el `GenerationScope` devuelto cierra la generación con `complete(TokenUsage, respuesta)`
(`TokenUsage` puede ser desconocido), `fail(error)` o `cancel(parcial)`. `firstToken()` marca `completion_start_time`.

### `ToolCallTrace`
`name`, `arguments`, `result` (anulable si falló), `failed`, `errorMessage` (anulable), duración medida por el adaptador.

### `TurnFailure`
`code` (el código de error que recibió el usuario, de `ResponseCodeEnum`) y `stage` (`FailureStage`).

### Enumeraciones (`domain/model/enums`)
- **`TurnResult`**: `COMPLETED`, `NO_CONTEXT`, `CANCELLED`, `ERROR`. Es la «salida» del turno en la traza. No reutiliza
  `ChatOutcome`, que es el conjunto de etiquetas de la métrica `shoplab.chat.response` y no debe cambiar.
- **`FailureStage`**: `RETRIEVAL`, `GENERATION`, `TOOL`.

### Constantes (`domain/model/constants/TracingConstants`, `@UtilityClass`)
Nombres de contexto de Reactor (`TURN_TRACE_KEY`), valores de `usage_status` / `cost_status`, nombre de la métrica de
descartes (`shoplab.chat.trace.dropped`) y límites por defecto. El contador `shoplab.chat.trace.export.failures`
(lotes que Langfuse no recibió) vive en `ExportFailureReporter`.

## Puertos (`application/port/out`)

- **`ChatTracePort`**: `Flux<ChatEvent> traceTurn(StreamChatCommand, Function<TurnTrace, Flux<ChatEvent>>)`. Abre el turno,
  publica el `TurnTrace` en el contexto de Reactor, ejecuta el flujo y cierra el turno al completarse, fallar o cancelarse.
- **`TurnTrace`**: lo que el turno puede registrar.
  `retrieval(RetrievalTrace, Duration)`, `fixedMessageAnswer()`, `startGeneration(model, parameters, messages)` →
  `GenerationScope`, `toolCall(ToolCallTrace, Duration)`. `TurnTrace.NONE` ignora todo.
- **`GenerationScope`**: `firstToken()`, `complete(TokenUsage, String answer)`, `fail(Throwable)`, `cancel(String partial)`.

El registro de un paso nunca lanza excepciones hacia el chat: cualquier fallo interno del adaptador se captura, se avisa
sin contenido y se ignora (FR-021).

## Ciclo de vida de un turno

```text
iniciado ──► recuperación ──┬─► (sin contexto + mensaje fijo) ─► NO_CONTEXT
                            └─► generación ─► [herramientas]* ─┬─► COMPLETED
   cualquier paso que falle ───────────────────────────────────┼─► ERROR (código, etapa)
   suscripción cancelada ──────────────────────────────────────┴─► CANCELLED (texto parcial)
```

- Un turno produce **exactamente una** traza (FR-009); el cierre es idempotente (el primero entre completado, error y
  cancelación gana, igual que `record(...)` en el caso de uso).
- `NO_CONTEXT` no tiene paso de generación; `usage_status` y `cost_status` no aplican.
- Un turno con memoria fallida (`ConversationMemoryException`) sigue siendo `COMPLETED`: la degradación ya existe.

## Configuración como datos

`TracingProperties` (`record`, prefijo `shoplab.chat.tracing`) y la tabla de precios (`Price`: `inputPerMillion`,
`outputPerMillion`, ambos ≥ 0) se describen en [contracts/configuration.md](contracts/configuration.md).

## Entidades en Langfuse (no se modelan en el backend)

| Entidad de la spec | En Langfuse |
|---|---|
| Traza (turno) | *Trace* con `id` = `SHA-256(requestId)[0..32]` |
| Sesión | *Session* con `id` = identificador de la conversación |
| Paso | *Observation* (`span`, `retriever`, `generation`, `tool`) anidada |
| Uso de tokens / coste | `usage_details` / `cost_details` de la observación `generation` |
| Proyecto | *Project* creado por `LANGFUSE_INIT_*` |
