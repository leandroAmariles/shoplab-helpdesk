# Data Model: Citas a la fuente en cada respuesta

No hay tablas ni migraciones nuevas. Todo vive en memoria durante una respuesta y en el estado de la pantalla.

## Backend

### `retrieval` (API publicada)

| Tipo | Cambio | Reglas |
|---|---|---|
| `KnowledgeChunk(source, title, section, position, text, score)` | + `title` (texto del documento, `document.title`) | `title` nunca es `null`; puede llegar vacío solo si la fila lo está |
| `SearchResult` (dominio interno) | + `title` | La consulta `SEARCH` añade `d.title` |

### `chat` (dominio)

| Tipo | Campos | Reglas |
|---|---|---|
| `ContextFragment` | `source`, **`title`**, `section`, `position`, `text`, `score` | `title` no vacío (el mapeador deriva uno del nombre del archivo si falta); resto como LAB-22 |
| `AnswerSource` | `number` (int ≥ 1), `title`, `section` (puede ser vacío), `text` | Se crea al entregar el contexto; `number` = posición + 1 en la lista seleccionada; inmutable |
| `ChatEvent.SourcesEvent` | `List<AnswerSource>` | Solo existe con contexto no vacío; como mucho uno por respuesta y siempre antes del primer `TokenEvent` |
| `CitationOutcome` (enum) | `VALID`, `INVALID`, `NONE` | `tagValue()` en minúsculas para la etiqueta de la métrica |

Secuencia de eventos de una respuesta:

```text
con contexto : sources → token* → done | error
sin contexto : token* → done          (mensaje fijo)    /    token* → done | error   (no-context-mode=model)
fallo previo : error HTTP, sin flujo  (validación, modelo caído, recuperación fallida)
```

### `CitationTracker` (aplicación, puro)

Estado: texto acumulado de la respuesta (solo para extraer marcas; no se registra) y número de fuentes `k`.

- `append(String token)`: añade texto. Una marca partida entre tokens se completa al llegar el resto.
- `outcome()`: extrae las marcas con la gramática de [contracts/sources-event.md](contracts/sources-event.md) §3 y devuelve
  `NONE` (0 marcas), `INVALID` (alguna con número > `k`) o `VALID`.
- Se descarta al terminar la respuesta; no se guarda ni se loguea el texto.

## Frontend

| Tipo | Campos | Reglas |
|---|---|---|
| `ChatSource` | `number`, `title`, `section`, `text` | Validado al parsear el evento: `number` entero ≥ 1, resto cadenas; una fuente mal formada descarta todo el evento (se ignora con aviso sin contenido) |
| `ChatStreamEvent` | + `{ type: 'sources'; sources: ChatSource[] }` | Un evento `sources` por respuesta; si llega otro, el último gana |
| `ChatMessage` | + `sources: ChatSource[]` (por defecto `[]`) | Solo se rellena en mensajes del asistente; no se persiste |
| Derivados (no se guardan) | `citedNumbers(content, sources)` → `number[]`; `consulted` = todas las fuentes si el mensaje terminó, hay fuentes y `citedNumbers` está vacío | Orden de primera aparición, sin repetidos (Historia 4) |

Estado de una respuesta en pantalla respecto a las fuentes:

```text
streaming  : sources recibidas → la lista muestra las citadas hasta ahora → clic abre el fragmento
complete   : lista «Fuentes» (citadas) | «Fuentes consultadas» (si no hay citas) | nada (sin fuentes)
stopped    : igual que complete con lo recibido hasta el corte
failed     : si hubo `sources`, se conservan las ya citadas; si no, nada
```

El fragmento abierto es el `ChatSource` del mensaje; cierra al pulsar Cerrar, Escape o fuera, y se cierra también al abrir
otro.
