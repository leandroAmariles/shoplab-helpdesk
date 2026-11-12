# Contrato: esquema de la traza hacia Langfuse

**Spec**: [../spec.md](../spec.md) | **Datos**: [../data-model.md](../data-model.md)

Interfaz entre el backend y Langfuse: OTLP/HTTP protobuf a `POST {endpoint}/v1/traces`, con las cabeceras
`Authorization: Basic base64(publicKey:secretKey)` y `x-langfuse-ingestion-version: 4`. Los nombres de atributo son los
que Langfuse documenta en *OpenTelemetry → Attribute Mapping*; los de `langfuse.*` tienen prioridad sobre `gen_ai.*`.

## Recurso

| Atributo | Valor |
|---|---|
| `service.name` | `helpdesk-backend` (`spring.application.name`) |
| `deployment.environment.name` | `shoplab.chat.tracing.environment` (`local`, `dev`) |

## Árbol de spans (uno por turno)

```text
chat-turn                     type=span        ← raíz; traceId = SHA-256(requestId)[0..32]
├── retrieval                 type=retriever
└── generation                type=generation  ← ausente en NO_CONTEXT
    └── tool:<nombre>         type=tool        ← 0..n, solo si el modelo llama herramientas
```

Cada span lleva marca de inicio y de fin reales (Langfuse copia una a la otra si falta una).

## `chat-turn` (raíz)

| Atributo | Contenido | FR |
|---|---|---|
| `langfuse.trace.name` | `chat-turn` | FR-009 |
| `langfuse.observation.type` | `span` | |
| `langfuse.session.id` | identificador de la conversación; ausente sin memoria | FR-009 |
| `langfuse.observation.input` | pregunta del usuario (truncada). **Es el que Langfuse v4 muestra**: `langfuse.trace.input` está obsoleto y no se mapea (verificado, S-3) | FR-010 |
| `langfuse.observation.output` | respuesta final, o texto parcial si se canceló (ídem) | FR-010, FR-016 |
| `langfuse.trace.input` / `langfuse.trace.output` | los mismos valores, por compatibilidad | FR-010 |
| `langfuse.trace.tags` | `["chat"]` | |
| `langfuse.environment` | igual que el recurso | |
| `langfuse.trace.metadata.request_id` | `requestId` del chat | FR-009, FR-020 |
| `langfuse.trace.metadata.result` | `completed` \| `no_context` \| `cancelled` \| `error` | FR-016 |
| `langfuse.trace.metadata.error_code` | código de `ResponseCodeEnum` (solo en `error`) | FR-016 |
| `langfuse.trace.metadata.failed_stage` | `retrieval` \| `generation` \| `tool` (solo en `error`) | FR-016 |
| `langfuse.trace.metadata.history_turns` | turnos de memoria incluidos en el prompt | FR-010 |
| `langfuse.trace.metadata.context_fragments` | fragmentos entregados | FR-011 |
| `langfuse.trace.metadata.time_to_first_token_ms` | ms hasta el primer token (ausente sin modelo) | FR-015 |
| `langfuse.trace.metadata.truncated` | `true` si algún contenido se recortó | FR-018 |
| `langfuse.observation.level` | `ERROR` si `error`; `WARNING` si `cancelled`; si no, `DEFAULT` | FR-016 |
| `langfuse.observation.status_message` | descripción corta del error, sin datos de la conversación | FR-016 |

La latencia total es la duración del span raíz (FR-015).

## `retrieval`

| Atributo | Contenido | FR |
|---|---|---|
| `langfuse.observation.type` | `retriever` | |
| `langfuse.observation.input` | texto realmente buscado | FR-011 |
| `langfuse.observation.output` | JSON de los fragmentos **entregados** (abajo); `[]` si ninguno | FR-011 |
| `langfuse.observation.metadata.candidates` | encontrados antes de seleccionar | FR-011 |
| `langfuse.observation.metadata.delivered` | entregados | FR-011 |
| `langfuse.observation.metadata.best_score` | mejor puntuación, ausente si `[]` | FR-011 |
| `langfuse.observation.level` | `ERROR` si la búsqueda falló | FR-016 |

Fragmento (`output`): `{"number":1,"source":"…","title":"…","section":"…","position":3,"score":0.83,"text":"…"}`;
`number` es la posición de la cita `[n]`.

## `generation`

| Atributo | Contenido | FR |
|---|---|---|
| `langfuse.observation.type` | `generation` | |
| `langfuse.observation.model.name` | modelo usado | FR-017 |
| `langfuse.observation.model.parameters` | JSON (temperatura, contexto máximo…) | FR-017 |
| `langfuse.observation.input` | JSON `[{"role":"system","content":"…"},…]`: sistema, historial, pregunta con contexto | FR-010 |
| `langfuse.observation.output` | respuesta (o texto parcial) | FR-010 |
| `langfuse.observation.usage_details` | `{"input":n,"output":m,"total":n+m}`; **ausente** si el uso es desconocido | FR-013 |
| `langfuse.observation.cost_details` | `{"input":x,"output":y,"total":z}` en USD; **ausente** si no hay precio o uso | FR-014 |
| `langfuse.observation.completion_start_time` | ISO 8601 del primer token | FR-015 |
| `langfuse.observation.metadata.usage_status` | `known` \| `unknown` | FR-013 |
| `langfuse.observation.metadata.cost_status` | `priced` \| `unpriced` \| `unknown_usage` | FR-014 |
| `langfuse.observation.level` | `ERROR` si falló; `WARNING` si se canceló | FR-016 |

Con precio cero (modelos locales) `cost_details` es `{"input":0,"output":0,"total":0}` y `cost_status=priced`.

## `tool:<nombre>`

| Atributo | Contenido | FR |
|---|---|---|
| `langfuse.observation.type` | `tool` | |
| `langfuse.observation.input` | argumentos tal como los recibió la herramienta | FR-012 |
| `langfuse.observation.output` | resultado (ausente si falló) | FR-012 |
| `langfuse.observation.level` / `status_message` | `ERROR` y mensaje si falló | FR-012 |

## Turnos especiales (US5)

| Caso | `chat-turn` | Hijos |
|---|---|---|
| Sin contexto + mensaje fijo | `result=no_context`, salida = mensaje fijo | `retrieval` con `[]`; **sin** `generation` |
| Cancelado a mitad | `result=cancelled`, `level=WARNING`, salida = texto parcial | `generation` cerrada con el parcial |
| Fallo de recuperación | `result=error`, `failed_stage=retrieval` | `retrieval` con `ERROR`; sin `generation` |
| Fallo del modelo | `result=error`, `failed_stage=generation` | `generation` con `ERROR` |
| Fallo de una herramienta | `result=error`, `failed_stage=tool` (el adaptador propaga el error de la herramienta) | `tool:<nombre>` con `ERROR` |

## Reglas transversales

1. **Tope de contenido**: todo atributo de texto (`input`, `output`, fragmentos, argumentos) se recorta a
   `max-content-characters`; al recortar se añade `…[truncated]` y `metadata.truncated=true` (FR-018).
2. **Nunca** se serializan cabeceras, claves, el cliente HTTP ni propiedades de configuración (FR-019); solo los objetos del
   dominio de la tabla anterior.
3. **Sin cardinalidad no acotada en métricas**: los atributos de contenido solo viajan en spans; la única métrica nueva
   (`shoplab.chat.trace.dropped` y `shoplab.chat.trace.export.failures`, contadores sin etiquetas) no los usa.
4. **Idempotencia**: reenviar un mismo `traceId` no duplica la traza en Langfuse; el cierre del turno ocurre una vez.
5. **Ausente ≠ cero**: tokens y coste desconocidos se omiten, no se escriben como `0`.
