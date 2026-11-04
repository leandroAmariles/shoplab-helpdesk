# ADR-0019: Citas a la fuente en el chat

- Estado: aceptado
- Fecha: 2026-11-03

## Contexto

LAB-22 hizo que el bot respondiera con los documentos de la tienda, pero el cliente no puede comprobar de dónde sale cada
dato, y la constitución (principio V) exige que las respuestas ancladas en la base de conocimiento citen sus fragmentos.
LAB-23 añade citas `[n]` en el texto, la lista de fuentes y la apertura del fragmento. Hay cuatro decisiones con
alternativas reales:

- **Cómo llegan las fuentes al cliente.** El cliente necesita el título, la sección y el texto de cada fragmento antes de
  la primera cita y sin una segunda llamada.
- **Quién valida las citas.** El modelo es pequeño (`llama3.2:3b`) y puede citar mal.
- **Cómo se pintan dentro del Markdown saneado** (ADR-0014), que hoy no admite botones.
- **Cómo se abre el fragmento** de forma accesible.

## Decisión

- **Evento SSE nuevo `sources`, aditivo.** Antes del primer `token` de una respuesta con contexto, el servidor envía
  `{"sources":[{"number","title","section","text"}]}` con los fragmentos entregados al modelo, numerados desde 1 en el orden
  en que se entregan. `token`, `done`, `error` y los códigos de error no cambian. Se envía **justo antes del primer token y no
  al recuperar el contexto**: así un modelo que falla antes de responder sigue acabando en un error HTTP (LAB-12), no en una
  respuesta que ya empezó. Sin contexto, o con una respuesta vacía, no hay evento.
- **El título sale de `retrieval`.** `KnowledgeChunk` (la API publicada) gana `title`, que es la columna
  `retrieval.document.title` ya existente; sin migración. Si llegara vacío, `chat` lo deriva del nombre del archivo.
- **El prompt pide citar.** Cada fragmento va con la cabecera `[n] Título › sección`; el prompt de sistema por defecto gana
  la regla de terminar cada afirmación con `[n]` y la plantilla de contexto una línea equivalente. Siguen siendo
  configuración (`shoplab.chat.system-prompt`, `shoplab.chat.rag.context-template`).
- **Validación en dos sitios con responsabilidades distintas.** El **cliente** decide qué se presenta: una marca `[n]` es una
  cita solo si `n` es una de las fuentes recibidas de esa respuesta; si no, queda como texto. El **servidor** solo mide:
  `CitationTracker` cuenta, sin retener tokens ni alterar el flujo, si la respuesta completada con contexto cita bien
  (`valid`), cita fuentes inexistentes (`invalid`) o no cita (`none`) en `shoplab_chat_citations_total{outcome}`. La gramática
  (`[1]`, `[1][2]`, `[1, 2]`, `[1-3]`) es la misma en ambos lados y se prueba con los mismos vectores.
- **Las citas son botones dentro del Markdown saneado.** Una extensión de `marked` emite
  `<button type="button" class="citation" data-source="N">` solo para números con fuente, y nunca dentro de enlaces ni de
  código. `MarkdownRenderer` amplía su lista blanca con `button` y deja pasar solo los botones que él mismo creó (cada
  render lleva un secreto que el modelo no conoce); cualquier `<button>` escrito por el modelo se convierte en texto.
- **El fragmento se abre en un `MatDialog`** (ADR-0013), con el texto saneado por el mismo renderizador y sin citas.

## Consecuencias

- El contrato de `POST /api/chat` crece con un evento; un cliente que no lo conozca lo ignora y ve las marcas `[n]` como texto.
- Quien configure su propio prompt de sistema debe incluir la regla de citas; si no, el contador `none` crecerá.
- El tamaño del evento está acotado por el presupuesto de contexto (`max-context-characters`, 8000 por defecto).
- Las fuentes viajan con la respuesta y no se guardan: si se recarga la página se pierden con la conversación. Cuando
  exista historial (historia posterior) habrá que decidir si se persisten.
- Las citas no demuestran que la afirmación sea fiel al fragmento; solo que remite a uno entregado. La fidelidad se mide con
  la evaluación de F2 (Constitución V); `shoplab_chat_citations_total` es la señal previa.
- Con modelos que citan mal, la lista cae a «Fuentes consultadas» (sin atribuir afirmaciones concretas) en lugar de mostrar
  citas inventadas.
