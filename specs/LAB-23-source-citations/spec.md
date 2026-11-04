# Feature Specification: Citas a la fuente en cada respuesta

**Feature Branch**: `feature/LAB-23-source-citations`

**Created**: 2026-11-03

**Status**: Draft

**Input**: User description: "LAB-23 (F1-06) Citas a la fuente en cada respuesta. Como cliente, quiero ver de qué documento
sale cada respuesta. Criterios: la respuesta lleva citas `[1]`; Angular muestra la fuente y abre el fragmento al hacer
clic." Depende de LAB-14 (interfaz de chat en Angular con streaming y Markdown) y LAB-22 (respuestas aumentadas con los
documentos).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Respuestas con citas numeradas (Priority: P1)

Como cliente, quiero que cada afirmación de la respuesta que viene de un documento lleve una cita numerada (`[1]`, `[2]`…),
para saber qué parte de la respuesta sale de qué documento.

Hoy (LAB-22) el bot responde con el contenido de los fragmentos recuperados, pero no dice de dónde sale cada dato. Esta
historia numera los fragmentos que se entregan al modelo como contexto y le exige citar, en el texto de la respuesta, el
número del fragmento en el que se apoya cada afirmación. Las citas llegan dentro del mismo flujo token a token.

**Why this priority**: Es el criterio de aceptación principal de la historia y el requisito de la Constitución V para
cualquier respuesta anclada en la base de conocimiento: sin citas, el cliente no puede comprobar lo que el bot afirma.

**Independent Test**: Con la base indexada, preguntar por un dato que solo aparece en un documento del corpus (p. ej. el
plazo de devolución) y comprobar que la respuesta contiene al menos una cita `[n]` y que el fragmento `n` es el que
contiene ese dato.

**Acceptance Scenarios**:

1. **Given** documentos indexados que contienen la respuesta, **When** el cliente pregunta por ese tema, **Then** la
   respuesta incluye al menos una cita `[n]` junto a la afirmación que se apoya en el fragmento `n`.
2. **Given** una respuesta apoyada en dos documentos distintos, **When** se genera, **Then** cada afirmación cita el
   fragmento correspondiente y ambos números aparecen en la respuesta.
3. **Given** una cita `[n]` en la respuesta, **When** se resuelve, **Then** `n` corresponde a uno de los fragmentos
   realmente entregados al modelo en esa respuesta; nunca a un fragmento ajeno o inexistente.
4. **Given** una respuesta que el modelo escribe con un número de cita fuera del rango de fragmentos entregados (p. ej.
   `[7]` con 4 fragmentos), **When** llega al cliente, **Then** esa marca no se presenta como una cita válida (no es
   clicable ni se asocia a ninguna fuente).
5. **Given** la misma pregunta hecha dos veces, **When** se responden, **Then** cada respuesta numera sus fuentes desde
   `[1]` de forma independiente; la numeración no se arrastra entre preguntas.
6. **Given** el contrato de eventos del chat vigente, **When** se añaden las citas, **Then** los eventos existentes
   (`token`, `done`, `error`) conservan su significado y formato; un cliente que ignore las citas sigue funcionando.

---

### User Story 2 - Ver la fuente de cada cita en la pantalla de chat (Priority: P1)

Como cliente, quiero ver, junto a la respuesta, la lista de documentos de los que sale, y que cada cita `[n]` del texto
esté relacionada con su fuente, para saber de qué documento y de qué sección viene cada dato.

**Why this priority**: Es el segundo criterio de aceptación («Angular muestra la fuente»): una cita numerada sin la
referencia al documento no informa al cliente.

**Independent Test**: Hacer una pregunta cubierta por el corpus en la pantalla de chat y comprobar que bajo la respuesta
aparece la lista de fuentes con el nombre legible del documento y su sección, y que el número de cada fuente coincide con
las citas del texto.

**Acceptance Scenarios**:

1. **Given** una respuesta con citas `[1]` y `[2]`, **When** termina de generarse, **Then** la pantalla muestra bajo ella
   una lista de fuentes numeradas con el título legible del documento y, si la hay, su sección.
2. **Given** una respuesta con citas, **When** se muestra el texto, **Then** cada marca `[n]` válida se presenta como un
   elemento identificable como cita (distinto del texto corriente), no como corchetes sueltos.
3. **Given** una respuesta en curso, **When** llegan los primeros tokens con una cita `[1]`, **Then** la cita ya puede
   relacionarse con su fuente sin esperar al final de la respuesta.
4. **Given** varias respuestas en la conversación en pantalla, **When** se muestran, **Then** cada respuesta tiene su
   propia lista de fuentes y `[1]` de una no se confunde con `[1]` de otra.
5. **Given** una respuesta sin citas (mensaje de «no hay información», saludo, error), **When** se muestra, **Then** no
   aparece lista de fuentes ni elementos vacíos.
6. **Given** el título de un documento y su sección, **When** se muestran, **Then** el cliente ve el título legible
   del documento (p. ej. «Política de devoluciones»), no el nombre del archivo ni una ruta interna.

---

### User Story 3 - Abrir el fragmento al hacer clic en una cita (Priority: P1)

Como cliente, quiero hacer clic en una cita o en una fuente y ver el fragmento exacto del documento en el que se apoya la
respuesta, para comprobar por mí mismo que el bot dice lo que dice el documento.

**Why this priority**: Es el tercer criterio de aceptación («abre el fragmento al hacer clic») y lo que convierte la cita
en verificable en lugar de decorativa.

**Independent Test**: Hacer clic en `[1]` dentro de la respuesta y en la fuente `1` de la lista y comprobar que, en ambos
casos, se abre el mismo fragmento con su texto, su documento y su sección; cerrarlo y volver a la conversación.

**Acceptance Scenarios**:

1. **Given** una respuesta con la cita `[1]`, **When** el cliente hace clic en ella, **Then** se abre el fragmento al que
   apunta, mostrando su texto completo, el título del documento y su sección.
2. **Given** la lista de fuentes bajo la respuesta, **When** el cliente hace clic en una fuente, **Then** se abre el mismo
   fragmento que con la cita del texto.
3. **Given** un fragmento abierto, **When** el cliente lo cierra (botón, tecla Escape o clic fuera), **Then** vuelve a la
   conversación en el mismo punto y sin perder la respuesta ni lo que estaba escribiendo.
4. **Given** un fragmento abierto, **When** el cliente hace clic en otra cita, **Then** se muestra el fragmento de esa
   cita sin acumular paneles abiertos.
5. **Given** un cliente que usa solo el teclado o un lector de pantalla, **When** recorre la respuesta, **Then** puede
   enfocar cada cita y fuente, activarlas con Enter o Espacio y recibe un nombre accesible («Fuente 1: Política de
   devoluciones»); el fragmento abierto recibe el foco y lo devuelve al cerrarse.
6. **Given** un fragmento cuyo texto contiene Markdown, HTML o instrucciones, **When** se muestra, **Then** se presenta
   como texto de referencia saneado (ADR-0014); nunca se ejecuta ni se interpreta como contenido activo.
7. **Given** un fragmento largo, **When** se abre, **Then** el cliente puede leerlo completo (con desplazamiento si hace
   falta) en pantallas de escritorio y de móvil.

---

### User Story 4 - Citas coherentes y sin falsas fuentes (Priority: P2)

Como cliente, quiero que las fuentes que veo sean solo documentos que el bot realmente consultó, y que el bot no invente
citas, para poder confiar en ellas.

**Why this priority**: Una cita falsa es peor que no citar: da apariencia de autoridad a un dato inventado. No bloquea el
valor principal (Historias 1 a 3), pero protege su fiabilidad.

**Independent Test**: Provocar una respuesta con una cita inexistente (modelo simulado que escribe `[9]`) y otra sin
citas pese a haber contexto, y comprobar el tratamiento descrito en los escenarios.

**Acceptance Scenarios**:

1. **Given** una respuesta que cita `[1]` y `[3]` de cuatro fragmentos entregados, **When** se muestra la lista de fuentes,
   **Then** aparecen las fuentes citadas (1 y 3); los fragmentos no citados no se presentan como fuentes de la respuesta.
2. **Given** una respuesta generada con contexto en la que el modelo no escribió ninguna cita, **When** termina, **Then**
   la pantalla muestra las fuentes consultadas bajo una etiqueta distinta («Fuentes consultadas») y no como citas de
   afirmaciones concretas, de modo que el cliente puede verificar el origen sin que se le sugiera una atribución que el
   bot no hizo.
3. **Given** una cita repetida (`[1]` varias veces en la respuesta), **When** se muestra, **Then** la fuente `1` aparece
   una sola vez en la lista y todas las marcas abren el mismo fragmento.
4. **Given** citas dentro de bloques de código o de enlaces del Markdown de la respuesta, **When** se renderiza, **Then**
   no rompen el Markdown ni crean elementos anidados incorrectos.
5. **Given** una respuesta con texto entre corchetes que no es una cita (p. ej. `[opcional]` o `[2024]` fuera del rango),
   **When** se muestra, **Then** se deja como texto normal.
6. **Given** que la respuesta se corta o se cancela a mitad, **When** se muestra, **Then** las citas ya emitidas siguen
   siendo válidas y la lista refleja solo las citadas hasta ese punto.

---

### User Story 5 - Observabilidad y límites de las citas (Priority: P3)

Como desarrollador, quiero saber con qué frecuencia las respuestas citan, citan mal o no citan, para detectar prompts o
modelos que no respetan la instrucción y calibrar la evaluación de F2.

**Why this priority**: Es una señal de operación para la evaluación posterior (Constitución V y VI); el cliente obtiene
valor sin ella.

**Independent Test**: Hacer varias preguntas con modelo simulado (con citas válidas, con citas inválidas, sin citas) y
comprobar que las métricas reflejan cada caso sin contener texto de preguntas, respuestas ni fragmentos.

**Acceptance Scenarios**:

1. **Given** cualquier respuesta con contexto, **When** termina, **Then** se registra una métrica que la clasifica como
   «con citas válidas», «con alguna cita inválida» o «sin citas».
2. **Given** una cita inválida detectada, **When** ocurre, **Then** queda en un registro estructurado con el identificador
   de la petición, sin el texto de la pregunta, de la respuesta ni de los fragmentos.
3. **Given** que el número de fragmentos entregados con contexto es cero, **When** se responde, **Then** no se evalúan
   citas ni se cuenta como «sin citas».

---

### Edge Cases

- **Respuesta sin contexto (mensaje fijo o `no-context-mode=model`)**: no hay fragmentos, por lo tanto no hay citas ni
  lista de fuentes; el mensaje de «no hay información» se muestra igual que hoy.
- **Un fragmento por documento vs. varios del mismo documento**: cada fragmento entregado recibe su propio número; si dos
  fragmentos son del mismo documento y distinta sección, son dos fuentes distintas (`[1]` y `[2]`) con el mismo título.
- **Fragmentos duplicados**: LAB-22 ya no entrega el mismo texto dos veces; no se numeran duplicados.
- **Fragmento recortado por el presupuesto de contexto**: LAB-22 descarta fragmentos completos; nunca se muestra un
  fragmento truncado a la mitad.
- **Documento reindexado o eliminado después de la respuesta**: el fragmento abierto es el que se entregó al modelo en esa
  respuesta (la copia viaja con ella), de modo que la cita sigue siendo verificable aunque el documento cambie después.
- **Modelo que escribe citas con otro formato** (`(1)`, `[1,2]`, `[1][2]`, `【1】`): `[1][2]` se trata como dos citas
  válidas; `[1,2]` y `[1-2]` se interpretan como las citas 1 y 2 si el formato es inequívoco; los demás formatos se dejan
  como texto normal y la métrica los cuenta como «sin citas» si no hay ninguna válida.
- **Cita partida entre tokens** (`[` en un token y `1]` en el siguiente): no se muestra una cita a medias ni parpadea; la
  marca se resuelve cuando está completa.
- **Respuesta muy larga con muchas citas**: la lista de fuentes se mantiene legible (una entrada por fuente, no por
  marca).
- **Pregunta de un cliente sobre datos personales en los documentos**: el texto del fragmento abierto es el del corpus; no
  se escribe en registros ni en métricas (Constitución VI).
- **Error o cancelación antes de recibir las fuentes**: no se muestran citas ni fuentes; el error se trata como en LAB-12.
- **Cliente antiguo o de terceros que solo lee `token`/`done`/`error`**: sigue recibiendo el texto, con las marcas `[n]`
  como texto plano.
- **Idioma**: las etiquetas de la interfaz («Fuentes», «Fuentes consultadas», «Cerrar») están en español.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Cada fragmento entregado al modelo como contexto MUST recibir un número de fuente (empezando en 1, en el
  orden en que se entrega), único dentro de esa respuesta.
- **FR-002**: El prompt de sistema y la plantilla de contexto MUST pedir al modelo que cite, tras cada afirmación apoyada
  en el contexto, el número del fragmento correspondiente con el formato `[n]`, y que no cite números que no existan; MUST
  seguir siendo configurables como texto de configuración con un valor por defecto en español (continuidad con
  LAB-22 FR-008).
- **FR-003**: Las respuestas con contexto MUST llegar al cliente con la información de las fuentes asociada a la respuesta:
  para cada número, el título legible del documento, la sección (si la hay) y el texto del fragmento entregado. El
  cliente MUST NOT necesitar una segunda llamada para abrir el fragmento.
- **FR-004**: La información de las fuentes MUST estar disponible antes de que el cliente muestre la primera cita, para
  que una cita pueda relacionarse con su fuente mientras la respuesta aún se genera.
- **FR-005**: El contrato de eventos de `POST /api/chat` MUST evolucionar solo de forma aditiva: los eventos `token`,
  `done` y `error` y los códigos de error vigentes conservan su significado y formato; un cliente que ignore las fuentes
  sigue funcionando (LAB-22 FR-010 se mantiene, ampliado con el nuevo dato).
- **FR-006**: El sistema MUST validar cada cita del texto contra los fragmentos realmente entregados en esa respuesta; una
  marca `[n]` con `n` fuera del rango NO MUST presentarse como cita válida ni asociarse a una fuente.
- **FR-007**: La pantalla de chat MUST presentar cada cita válida como un elemento identificable y operable distinto del
  texto corriente, y MUST dejar como texto normal cualquier marca entre corchetes que no sea una cita válida.
- **FR-008**: La pantalla de chat MUST mostrar, bajo cada respuesta con citas, la lista de fuentes citadas, una entrada por
  fuente, con su número, el título legible del documento y su sección. Los fragmentos entregados pero no citados MUST NOT
  presentarse como fuentes citadas.
- **FR-009**: Si una respuesta con contexto no contiene ninguna cita válida, la pantalla MUST mostrar las fuentes
  consultadas bajo una etiqueta distinta de «Fuentes», sin presentarlas como citas de afirmaciones concretas.
- **FR-010**: Hacer clic (o activar con teclado) en una cita del texto o en una entrada de la lista MUST abrir el
  fragmento correspondiente, mostrando su texto completo, el título del documento y su sección; MUST poder cerrarse sin
  perder la conversación ni el texto que el cliente esté escribiendo.
- **FR-011**: El texto del fragmento y los títulos MUST mostrarse como contenido no confiable y saneado, bajo las mismas
  reglas que el Markdown del chat (ADR-0014): sin HTML activo, sin enlaces peligrosos y sin imágenes remotas.
- **FR-012**: La interfaz de citas y fuentes MUST ser accesible: operable con teclado, con nombres accesibles que incluyan
  el número y el título, gestión del foco al abrir y cerrar el fragmento, contraste suficiente y uso correcto en pantallas
  de móvil y de escritorio.
- **FR-013**: Una respuesta sin contexto (mensaje de «sin información», saludo, error) MUST NOT mostrar citas, lista de
  fuentes ni elementos vacíos.
- **FR-014**: Cada respuesta MUST numerar y resolver sus fuentes de forma independiente; las citas de una respuesta MUST
  NOT referirse a fragmentos de otra.
- **FR-015**: El sistema MUST emitir métricas de la calidad de las citas por respuesta con contexto («con citas válidas»,
  «con alguna cita inválida», «sin citas») y registros estructurados de las citas inválidas con el identificador de la
  petición; MUST NOT incluir el texto de la pregunta, de la respuesta ni de los fragmentos (Constitución VI).
- **FR-016**: Las citas MUST poder verificarse con pruebas automáticas deterministas (modelos simulados) que cubran:
  numeración de fuentes, cita válida, cita fuera de rango, ausencia de citas, fuentes duplicadas, cita partida entre
  tokens, respuesta sin contexto y contrato de eventos aditivo; y pruebas del frontend sobre la presentación, el clic y
  la accesibilidad. MUST existir una verificación manual documentada con Ollama real (Constitución IV).
- **FR-017**: Los módulos MUST mantener sus límites: `chat` MUST seguir consumiendo `retrieval` solo por su API publicada
  entre módulos y MUST NOT acceder a sus tablas; los metadatos necesarios (título legible del documento, sección, texto)
  MUST provenir de lo que `retrieval` ya expone o de una ampliación aditiva de esa API (ADR-0015, ADR-0017, ADR-0018,
  Constitución I).
- **FR-018**: Esta historia MUST NOT incluir: memoria de conversación (historial entre mensajes), citas persistidas entre
  sesiones, enlace al documento original completo o descarga de archivos, resaltado dentro del documento fuente, búsqueda
  híbrida, reordenado, filtros por metadatos, ni evaluación automática (RAGAS / DeepEval) de la fidelidad de las citas.
- **FR-019**: El comportamiento MUST ser el mismo en el entorno local y en `dev`, y ajustable por variables de entorno
  sin recompilar en lo que sea configuración (texto del prompt y de la plantilla).

### Key Entities *(include if feature involves data)*

- **Fuente de una respuesta**: fragmento de la base de conocimiento entregado al modelo en una respuesta concreta, con su
  número (`1…k`), el título legible del documento, la sección y el texto. Existe solo en el contexto de esa respuesta; no
  se persiste.
- **Cita**: marca `[n]` en el texto de la respuesta que remite a la fuente número `n`. Es válida si `n` está entre las
  fuentes entregadas en esa respuesta.
- **Lista de fuentes**: conjunto de fuentes citadas (o, si no hay citas, consultadas) que la pantalla muestra bajo la
  respuesta.
- **Fragmento abierto**: vista del texto de una fuente con su documento y sección, abierta desde una cita o una entrada
  de la lista.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: En un conjunto de al menos 10 preguntas representativas cuya respuesta figura en `data/kb` (devoluciones,
  pagos, envíos, privacidad, garantía), al menos el 90 % de las respuestas con contexto contiene al menos una cita válida,
  revisado manualmente con el modelo local real.
- **SC-002**: En esas mismas preguntas, al menos el 80 % de las citas válidas remite a un fragmento que contiene el dato
  afirmado, revisado manualmente.
- **SC-003**: El 100 % de las citas que se presentan como válidas en pantalla remite a un fragmento realmente entregado al
  modelo en esa respuesta; ninguna cita fuera de rango se muestra como clicable.
- **SC-004**: Un cliente abre el fragmento de una fuente con un solo clic (o una sola activación de teclado) desde la cita
  o desde la lista, y el fragmento aparece en menos de 1 segundo, sin nuevas llamadas al servidor.
- **SC-005**: El primer token de la respuesta sigue llegando en menos de 5 segundos (SC-004 de LAB-22) y las citas no
  retrasan de forma perceptible el streaming.
- **SC-006**: Los eventos existentes de `POST /api/chat` no cambian: las pruebas de contrato de LAB-12 y LAB-22 pasan sin
  modificar sus expectativas en el 100 % de sus escenarios.
- **SC-007**: La interfaz de citas y fuentes pasa una revisión de accesibilidad (teclado, nombres accesibles, foco,
  contraste) sin incidencias críticas.
- **SC-008**: En el 100 % de las peticiones, los registros y métricas no contienen el texto de la pregunta, de la
  respuesta ni de los fragmentos.
- **SC-009**: Las pruebas automáticas (backend y frontend) pasan en CI sin servidor de modelos real, y los módulos
  modificados mantienen una cobertura de líneas de al menos el 80 %.

## Assumptions

- **Dependencias**: LAB-14 (pantalla de chat en Angular, streaming por `fetch`, Markdown saneado, ADR-0014) y LAB-22
  (recuperación de contexto con fragmentos que conservan documento, sección y posición, FR-016 de LAB-22). LAB-22 aún
  no está fusionada en `main` a fecha de esta especificación; esta rama parte de la de LAB-22 y se reubicará sobre
  `main` cuando LAB-22 se fusione.
- **Formato de cita `[n]`**: se fija por el criterio de la historia. La numeración es por respuesta, empieza en 1 y sigue
  el orden de entrega de los fragmentos al modelo (de mayor a menor relevancia).
- **Las fuentes viajan con la respuesta**: el cliente recibe en el mismo flujo la lista de fuentes (número, título, sección,
  texto) para no depender de una segunda llamada ni de que el documento siga indexado. Es una ampliación aditiva del
  contrato; el plan decide su forma exacta (p. ej. un evento nuevo antes del primer token) y la documenta en el contrato
  de la API.
- **Título legible**: el nombre del archivo (`returns-01-return-policy.md`) no es adecuado para el cliente. El título
  legible sale de los metadatos del corpus (`data/kb/INDEX.md`: «Política de devoluciones») o, si falta, se deriva del
  nombre del archivo. De dónde se toma y si `retrieval` debe exponerlo (ampliación aditiva de su API) lo decide el plan.
- **Qué se muestra como fuente**: por defecto, solo las fuentes citadas por el modelo. Si el modelo no cita ninguna
  teniendo contexto, se muestran las consultadas con una etiqueta distinta (FR-009). Es un punto de partida que la
  evaluación de F2 calibrará.
- **«Abrir el fragmento»**: significa mostrar el texto del fragmento recuperado con su documento y sección en una vista
  superpuesta o panel lateral; no incluye abrir el archivo original ni navegar al documento completo.
- **Sin persistencia**: las citas y fuentes viven mientras la respuesta está en pantalla (como el resto de la
  conversación, que aún no se persiste); recargar la página las pierde junto con la conversación.
- **Calidad del modelo local**: un modelo pequeño (`llama3.2:3b`) puede no respetar siempre el formato de cita. Por eso la
  validación de citas es del sistema y no del modelo (FR-006), y se mide su frecuencia (FR-015); la calidad se comprueba
  con los conjuntos de preguntas manuales de SC-001 y SC-002.
- **Sin memoria de conversación**: cada mensaje se responde por separado; el historial es una historia posterior (el
  objetivo del sprint incluye la memoria de la conversación, pero no es parte de LAB-23).
- **Infraestructura**: no cambia (Ollama, PostgreSQL con pgvector); no hay cambios en el esquema `retrieval`.
- **Idioma**: documentos, prompt por defecto, etiquetas de la interfaz y respuestas en español; los identificadores de
  código, en inglés (Constitución III).
