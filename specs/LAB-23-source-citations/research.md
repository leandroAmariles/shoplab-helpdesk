# Research: Citas a la fuente en cada respuesta

Cada decisión responde a una incógnita del plan. No quedan `NEEDS CLARIFICATION`.

## D-1 — Cómo llegan las fuentes al cliente

**Decisión**: un evento SSE nuevo, `sources`, emitido una sola vez **antes del primer `token`** de una respuesta con contexto,
con `{"sources":[{"number","title","section","text"}]}`. No se emite si no hay contexto (mensaje fijo o
`no-context-mode=model`). `token`, `done` y `error` no cambian; `done.fragments` sigue contando solo tokens.

**Razón**: la recuperación termina antes de que el modelo empiece, así que las fuentes se conocen desde el principio y el
cliente puede abrir `[1]` mientras la respuesta aún se genera (FR-004). Un cliente antiguo ignora los eventos que no
conoce (el cliente actual ya descarta los desconocidos con un aviso), así que el cambio es aditivo (FR-005).

**Alternativas**:
- *Enviarlas en `done`*: llegan al final; las citas del streaming no se pueden abrir y `done` cambiaría de forma.
- *Segunda llamada (`GET /api/chat/{id}/sources`)*: exige guardar el contexto por petición en el servidor (estado, caducidad,
  limpieza) y contradice FR-003.
- *Cabecera HTTP*: límite de tamaño y no apta para texto de fragmentos.
- *Insertar el bloque dentro del texto de los tokens*: mezcla datos con la respuesta y obliga al cliente a separarlos.

## D-2 — De dónde sale el título legible

**Decisión**: `retrieval.document.title` ya existe (V2, lo rellena la ingesta desde el front matter `title:` del corpus, p. ej.
«Política de devoluciones»). Se añade `title` a `SearchResult` (consulta `SEARCH`: `d.title`) y a `KnowledgeChunk` en
`retrieval.api`. `chat` lo copia a `ContextFragment.title`; si llegara vacío, `ContextFragmentMapper` deriva uno del nombre
del archivo (sin extensión, guiones por espacios, primera letra en mayúscula).

**Razón**: cero migraciones, cero lectura de `data/kb/INDEX.md` en tiempo de ejecución y los límites entre módulos se
mantienen (ADR-0015, ADR-0017). Añadir un componente a un `record` público es compatible a nivel de fuente para quien lo
crea con `new` solo si se actualizan los constructores: los usos están en `retrieval` y en pruebas de `chat` y se ajustan
en la misma historia.

**Alternativas**: leer `INDEX.md` desde `chat` (acopla a un archivo del corpus y salta el límite de módulo); mostrar el nombre
del archivo (incumple FR-008 / escenario 2.6).

## D-3 — Numeración y forma del prompt

**Decisión**: el número es la posición (desde 1) en la lista **ya seleccionada** por `ContextSelector` (umbral, duplicados,
presupuesto), que es la que se entrega al modelo; así los números son contiguos y las fuentes del evento coinciden con el
contexto. `OllamaChatAdapter` cambia la cabecera de cada fragmento de `[origen › sección]` a `[n] Título › sección` y el
texto del fragmento debajo. El prompt de sistema gana una regla (la 5) y la plantilla de contexto una línea:

- Regla 5: «Cada afirmación basada en el CONTEXTO termina con el número del fragmento entre corchetes, p. ej. [1]. Usa solo
  los números que aparecen en el CONTEXTO y no inventes citas.»
- Plantilla: «Cita los fragmentos usados con su número, p. ej. [1].»

Siguen siendo texto de configuración con valor por defecto en `ChatConstants` (FR-002, FR-019). Las plantillas no admiten
llaves `{...}` distintas de los marcadores (restricción de LAB-22): los ejemplos usan corchetes.

**Razón**: la numeración antes del modelo es la única que puede validarse contra lo realmente entregado (FR-006); el título
en la cabecera ayuda al modelo a distinguir fragmentos y no cuesta tokens relevantes.

**Alternativas**: numerar por documento (varias secciones del mismo documento compartirían número y no se podría abrir el
fragmento exacto); dejar que el modelo elija los números (no verificable).

**Consecuencia**: quien configure su propio `SHOPLAB_CHAT_SYSTEM_PROMPT` debe incluir la regla de citas; se documenta en
`docs/chat-streaming.md`.

## D-4 — Dónde se validan las citas

**Decisión**: dos lugares con responsabilidades distintas.
- **Cliente (qué se muestra)**: una marca `[n]` es una cita válida si `n` está en las fuentes recibidas de **esa** respuesta;
  si no, queda como texto. Es lo que el cliente ve y lo que FR-006/007 exige.
- **Servidor (qué se mide)**: `CitationTracker` (puro, en `application`) acumula el texto de los tokens, extrae las marcas
  con la misma gramática y, al completarse la respuesta con contexto, clasifica: `VALID` (≥ 1 válida y ninguna inválida),
  `INVALID` (alguna fuera de rango) o `NONE` (ninguna marca). Registra la métrica y un `WARN` con `requestId`, número de
  citas válidas e inválidas, nunca texto. No altera el flujo.

**Razón**: la gramática vive en un único contrato (tabla de vectores de prueba compartida, ver contracts) y se prueba con los
mismos casos en ambos lados. El servidor no retiene tokens, así que el primer token no se retrasa (SC-005) y `token` conserva
su fidelidad.

**Alternativas**: reescribir el texto en el servidor (retiene tokens o requiere búfer de ventana, y cambia el significado del
contrato); validar solo en el cliente (sin métrica, FR-015 incumplido); validar solo en el servidor (el cliente no sabría
qué marcas pintar mientras llega el texto).

## D-5 — Cómo se pintan las citas dentro del Markdown saneado

**Decisión**: extensión de `marked` (tokenizador *inline*) que reconoce la gramática de citas y emite
`<button type="button" class="citation" data-source="N" aria-label="Fuente N: Título">[N]</button>` solo para números
presentes en las fuentes de la respuesta. Cambios en `MarkdownRenderer`:
- `render(markdown, options?)` con `options.sources` (número → título) y `options.streaming`.
- Lista blanca de DOMPurify ampliada con la etiqueta `button` y los atributos `type`, `data-source`, `aria-label`; el hook
  `afterSanitizeAttributes` conserva `class="citation"` solo en un `button` y no pone `target`/`rel` en él.
- El *renderer* de enlaces desactiva las citas en su texto (evita botones dentro de `<a>`, escenario 4.4); `marked` no
  tokeniza dentro de código en línea ni bloques de código.
- Con `streaming`, un final incompleto (`[`, `[1`, `[1,`) se oculta hasta que se cierre o llegue otro carácter que lo
  descarte, para que no parpadee una cita a medias (edge case «cita partida»).
- El clic se recoge por delegación en el contenedor `.markdown` del mensaje (`closest('button[data-source]')`), porque el
  HTML entra por `[innerHTML]` y no admite *bindings* de Angular.

**Razón**: mantiene el saneado en un único sitio (la regla de ADR-0014: lo que sale de `MarkdownRenderer` ha pasado por la
lista blanca) y funciona con el re-renderizado completo que ya hace el streaming. `<button>` da Enter/Espacio y foco sin
código extra.

**Alternativas**: reemplazar `[n]` por regex sobre el HTML ya saneado (rompe dentro de código y de atributos, y obliga a
confiar en un segundo paso); componente Angular dinámico por cita (muchas instancias que se destruyen en cada frame del
streaming); `<a href="#">` (Espacio no lo activa y se confunde con enlaces externos).

## D-6 — Cómo se abre el fragmento

**Decisión**: `MatDialog` de Angular Material (ADR-0013) con un componente `SourcePreview` que recibe la fuente. Antes de
abrir se llama a `closeAll()` para no acumular paneles (escenario 3.4). Cierra con botón «Cerrar», Escape o clic fuera;
`MatDialog` ya atrapa el foco, lo devuelve al elemento que abrió y anuncia el rol de diálogo. Título, sección y texto se
muestran con `MarkdownRenderer` **sin** citas (FR-011); en móvil el diálogo usa casi todo el ancho y la altura se desplaza.

**Razón**: cumple FR-010 y FR-012 con un componente ya aprobado en el proyecto, sin código propio de foco ni de capas. El
diálogo vive fuera del `ChatComponent` (overlay), por lo que cerrar no afecta al borrador del cliente (FR-010).

**Alternativas**: panel lateral propio (hay que implementar foco, Escape, capas y responsive); `<details>` en línea (no
«abre el fragmento» en un lugar aparte y empuja el contenido de la conversación).

## D-7 — Qué fuentes se listan

**Decisión**: función pura `citedNumbers(content, sources)` en `citations.ts` (misma gramática): devuelve los números válidos,
únicos y en orden de primera aparición. `SourceList` muestra «Fuentes» con esas entradas; si la respuesta terminó
(`complete` o `stopped`), tiene fuentes y no hay ninguna cita válida, muestra todas bajo «Fuentes consultadas». Durante el
streaming solo se muestran las ya citadas. Todo es derivado: no se guarda la lista en el almacén.

**Razón**: FR-008/FR-009 sin estado extra; coherente con que el texto es la única fuente de verdad de qué se citó.

**Alternativas**: listar siempre todas las entregadas (sugiere atribución que el modelo no hizo); no listar nada si no hay citas
(el cliente pierde la verificación).

## D-8 — Gramática de citas

**Decisión**: una marca es `[` seguida de uno o más números enteros positivos de 1 a 2 dígitos separados por `,` (con espacios
opcionales) o un rango `a-b` (a ≤ b, a lo sumo 20 elementos), y `]`. Se expande a las citas individuales: `[1, 2]` y `[1-2]`
son las citas 1 y 2; `[1][2]` son dos marcas. No son citas: `[]`, `[0]`, `[abc]`, `[1a]`, `(1)`, `【1】`, `[2024]` si 2024 no es una
fuente (3+ dígitos nunca coincide). Un número fuera de rango dentro de una lista (`[1, 9]` con 4 fuentes) se trata marca a
marca: 1 válida, 9 inválida.

**Razón**: cubre los formatos habituales de modelos pequeños sin ambigüedad y se puede probar con una tabla (contrato).

**Alternativas**: solo `[n]` literal (descarta citas legítimas `[1, 2]` que `llama3.2:3b` produce a menudo); reconocer más
formatos (`(1)`, `^1`) y generar falsos positivos con paréntesis y años.

## D-9 — Métrica y registro

**Decisión**: contador `shoplab.chat.citations` con la etiqueta `outcome` ∈ `valid|invalid|none` (una muestra por respuesta
completada con contexto; las canceladas, fallidas y sin contexto no cuentan, escenario 5.3). Prometheus lo expone como
`shoplab_chat_citations_total`. Un `INVALID` escribe `WARN` con `requestId`, `valid` e `invalid` (recuentos).

**Razón**: señal mínima para calibrar el prompt/modelo en F2 sin guardar texto (Constitución VI).

**Alternativas**: histograma de citas por respuesta (más cardinalidad sin una decisión que lo pida hoy); registrar el texto
citado (viola SC-008).
