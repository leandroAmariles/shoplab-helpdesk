# Contrato: citas y evento `sources`

`POST /api/chat` conserva la petición, los eventos `token` / `done` / `error` y los códigos de error de LAB-12 y LAB-22. Este
contrato añade **un evento** y fija la gramática de citas, la métrica y la forma del prompt.

## 1. Evento `sources`

Se emite una vez, **inmediatamente antes del primer `token`**, solo cuando la respuesta se genera con contexto. No se
envía al recuperar el contexto: si el modelo falla antes de dar su primer token, la respuesta sigue siendo un error HTTP
(LAB-12) y no un flujo que ya empezó. Una respuesta del modelo sin ningún token tampoco lleva `sources`.

```text
event: sources
data: {"sources":[{"number":1,"title":"Política de devoluciones","section":"Plazos","text":"Tienes 30 días…"},
                  {"number":2,"title":"Reembolsos","section":"","text":"El reembolso se hace…"}]}
```

| Campo | Tipo | Reglas |
|---|---|---|
| `sources` | array (1…20) | Orden ascendente por `number`; nunca vacío (sin contexto no se envía el evento) |
| `number` | entero ≥ 1 | Contiguo desde 1; único en la respuesta |
| `title` | cadena no vacía | Título legible del documento (nunca la ruta ni el nombre del archivo, salvo respaldo derivado) |
| `section` | cadena | Ruta de encabezados; vacía si no hay |
| `text` | cadena no vacía | Texto del fragmento entregado al modelo, tal cual se entregó (Markdown del corpus) |

- Orden de eventos con contexto: `sources`, `token`\*, y uno final `done` o `error`.
- `done.fragments` sigue siendo el número de eventos `token`.
- Un cliente que no conozca `sources` lo ignora y recibe el texto con las marcas `[n]` como texto plano.
- El tamaño está acotado por `shoplab.chat.rag.max-context-characters` (8 000 por defecto) más los títulos y secciones.

Cambios en el [contrato de LAB-12](../../LAB-12-chat-streaming-sse/contracts/chat-stream.md): ninguno salvo la fila nueva en la
tabla de eventos (se actualiza `docs/chat-streaming.md`).

## 2. API publicada por `retrieval`

```java
public record KnowledgeChunk(String source, String title, String section, int position, String text, double score) { }
```

Solo cambia el registro (campo `title`); `KnowledgeSearchApi.search(query, limit)` no cambia de firma. Origen: columna
`retrieval.document.title`, ya existente.

## 3. Gramática de citas (compartida por servidor y cliente)

Expresión de referencia: `\[\s*(\d{1,2}(?:\s*-\s*\d{1,2})?(?:\s*,\s*\d{1,2}(?:\s*-\s*\d{1,2})?)*)\s*\]`

Cada elemento de la lista es un número `n` (1–99) o un rango `a-b` con `a ≤ b` y como máximo 20 números; se expande a citas
individuales. Un número es **válido** si `1 ≤ n ≤ k` (k = fuentes de esa respuesta), **inválido** si no.

| Entrada (k = 4) | Citas | Resultado en pantalla |
|---|---|---|
| `[1]` | 1 | botón `[1]` |
| `[1][2]` | 1, 2 | dos botones |
| `[1, 2]` · `[1,2]` | 1, 2 | dos botones `[1]` `[2]` |
| `[1-3]` | 1, 2, 3 | tres botones |
| `[3-1]` · `[1-30]` | — | texto (rango inválido) |
| `[1, 9]` | 1 válida, 9 inválida | botón `[1]` y texto `[9]` no clicable |
| `[7]` | 7 inválida | texto `[7]` |
| `[0]` · `[]` · `[abc]` · `[1a]` | — | texto |
| `[2024]` | — | texto (3+ dígitos) |
| `(1)` · `【1】` | — | texto |
| `` `[1]` `` y bloques de código | — | texto (no se tokeniza dentro de código) |
| `[ver [1]](https://…)` | — | el enlace conserva su texto; sin botón dentro |
| `[1` al final de un mensaje en streaming | — | se oculta hasta que cierre o se descarte |

Las pruebas del backend (`CitationTrackerTests`) y del frontend (`citations.spec.ts`, `markdown-renderer.spec.ts`) usan esta
tabla como vectores de prueba.

## 4. Forma del prompt

Cabecera de cada fragmento en el contexto: `[n] Título › sección` (sin `›` si no hay sección), el texto en la línea
siguiente, fragmentos separados por una línea en blanco. Texto por defecto de las reglas (en `ChatConstants`, sobrescribible
por configuración, sin llaves `{...}` distintas de `{query}` y `{context}`):

- Regla 5 del prompt de sistema: cada afirmación apoyada en el CONTEXTO termina con el número de su fragmento entre
  corchetes (`[1]`); solo se usan números presentes; no se inventan citas.
- Plantilla de contexto: añade «Cita los fragmentos usados con su número, p. ej. [1].».

Quien defina `SHOPLAB_CHAT_SYSTEM_PROMPT` propio debe incluir la regla de citas.

## 5. Métrica y registro

| Nombre (Micrometer) | Prometheus | Etiquetas | Cuándo |
|---|---|---|---|
| `shoplab.chat.citations` (contador) | `shoplab_chat_citations_total` | `outcome` = `valid` \| `invalid` \| `none` | Una vez por respuesta **completada** con contexto |

- `INVALID` registra `WARN`: `requestId`, `valid=<n>`, `invalid=<n>`. Nunca texto de preguntas, respuestas ni fragmentos.
- Respuestas sin contexto, canceladas o con error no cuentan.

## 6. Contrato de la interfaz

- Cada cita válida es un `<button type="button" class="citation" data-source="N">` con nombre accesible
  «Fuente N: Título». Enter y Espacio la activan.
- Bajo la respuesta, `SourceList`: región con el título «Fuentes» (o «Fuentes consultadas»), una entrada-botón por fuente
  «N. Título › sección».
- Activar una cita o una entrada abre un `MatDialog` (rol `dialog`, etiquetado con el título de la fuente) con título,
  sección y texto saneado; botón «Cerrar»; Escape y clic fuera cierran; el foco vuelve al elemento que lo abrió. Abrir otro
  cierra el anterior.
- Etiquetas en español: «Fuentes», «Fuentes consultadas», «Cerrar», «Fuente N: …».

## 7. Configuración

Sin propiedades nuevas. Siguen vigentes `shoplab.chat.system-prompt` y `shoplab.chat.rag.context-template` (sección 4) y las
de LAB-22.
