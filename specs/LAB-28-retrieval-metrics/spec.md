# Feature Specification: Métricas de retrieval (hit rate, MRR, recall)

**Feature Branch**: `feature/LAB-28-retrieval-metrics`

**Created**: 2026-11-17

**Status**: Draft

**Input**: User description: "LAB-28 (F2-02) Métricas de retrieval (hit rate, MRR, recall). Como desarrollador, quiero
medir el retrieval por separado. Criterios: hit rate@k, MRR y recall@k sobre los documentos esperados; resultado en CSV."
Depende de LAB-21 (embeddings y búsqueda por similitud) y LAB-27 (dataset golden), ya fusionadas en `main`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Saber si la búsqueda encuentra los documentos correctos (Priority: P1)

Como desarrollador, quiero ejecutar la búsqueda de la base de conocimiento sobre cada pregunta del dataset golden y
obtener hit rate@k, MRR y recall@k contra los documentos esperados, para saber si el retrieval trae lo que el asistente
necesita **antes** de mirar qué responde el modelo.

Hoy un mal resultado del asistente puede deberse a que no recuperó el documento correcto o a que, recuperándolo,
respondió mal; sin separar las dos causas no se sabe qué arreglar. Esta historia mide solo la primera: para cada pregunta
con respuesta se consulta la misma búsqueda que usa el asistente, se miran los documentos de los fragmentos devueltos en
orden de cercanía y se comparan con los documentos esperados del caso. Las tres métricas se calculan sobre todos los casos
con respuesta (hoy 70 de los 90 del dataset):

- **Hit rate@k**: proporción de casos en los que al menos un documento esperado aparece entre los k primeros documentos.
- **Recall@k**: proporción media de los documentos esperados de un caso que aparecen entre los k primeros documentos
  (importa en los casos cuya respuesta exige combinar dos documentos).
- **MRR**: media del inverso de la posición en la que aparece por primera vez un documento esperado (1 si es el primero,
  ½ si es el segundo… y 0 si no aparece).

**Why this priority**: Es el criterio de aceptación principal y la razón de ser de la historia. Desde F2 la Constitución V
exige que los cambios de chunking, embeddings o retrieval lleven un resultado de evaluación; sin estas métricas no hay
cómo saber si un cambio mejora o empeora la búsqueda, y LAB-30 (evaluación en CI) las necesita como entrada.

**Independent Test**: Con la base de conocimiento indexada, ejecutar la medición sobre el dataset golden vigente y
comprobar que informa hit rate@k, recall@k y MRR para cada k configurado; después comprobar a mano tres casos (uno acertado
en primera posición, uno acertado en una posición posterior y uno fallado) y verificar que sus valores coinciden con lo que
indica la definición.

**Acceptance Scenarios**:

1. **Given** la base de conocimiento indexada y el dataset golden vigente, **When** se ejecuta la medición, **Then** se
   informan hit rate@k y recall@k para cada k pedido y el MRR, todos como valores entre 0 y 1 calculados solo sobre los
   casos con respuesta.
2. **Given** un caso cuyo documento esperado es el primero de los devueltos, **When** se calculan sus valores, **Then** su
   aporte es: acierto para todo k, recall completo para todo k y MRR de 1.
3. **Given** un caso cuyo documento esperado aparece en la tercera posición, **When** se calculan sus valores con k = 1, 3
   y 5, **Then** es fallo con k = 1, acierto con k = 3 y k = 5, y su aporte al MRR es ⅓.
4. **Given** un caso cuyos documentos esperados son dos y solo uno aparece entre los k primeros, **When** se calcula su
   recall@k, **Then** vale 0,5 y cuenta como acierto en hit rate@k.
5. **Given** un caso cuyo documento esperado no aparece en ninguna posición devuelta, **When** se calculan sus valores,
   **Then** su aporte es 0 en todas las métricas.
6. **Given** que la búsqueda devuelve varios fragmentos del mismo documento, **When** se asignan las posiciones, **Then**
   el documento ocupa la posición de su fragmento más cercano y los fragmentos repetidos no ocupan posiciones adicionales,
   de modo que k cuenta documentos distintos y no fragmentos.
7. **Given** que el dataset incluye casos sin respuesta, **When** se calculan las métricas, **Then** esos casos no entran
   en ninguna de las tres métricas y el resultado informa cuántos se excluyeron.

---

### User Story 2 - Un resultado en CSV para comparar entre ejecuciones (Priority: P1)

Como desarrollador, quiero que el resultado se escriba en archivos CSV con el resumen por categoría y el detalle por
caso, para abrirlo en una hoja de cálculo, compararlo con una ejecución anterior y ver qué preguntas fallan y por qué.

El resumen tiene una fila para el conjunto completo y una por categoría (pagos, privacidad, devoluciones, envíos,
garantías), con una columna por métrica y k. El detalle tiene una fila por caso con respuesta: su identificador, sus
documentos esperados, los documentos recuperados en orden, la posición del primer acierto y su valor en cada métrica. Así,
ante una caída del hit rate se localiza la pregunta exacta y lo que la búsqueda devolvió en su lugar.

**Why this priority**: Es el segundo criterio de aceptación explícito. Un número sin su desglose no dice qué corregir, y
LAB-30 y LAB-31 consumirán este mismo formato para comparar contra una línea base y para persistir ejecuciones.

**Independent Test**: Ejecutar la medición, abrir los CSV en una hoja de cálculo y comprobar que el resumen tiene la fila
global y una por categoría, que el detalle tiene una fila por caso con respuesta y que, tomando un caso fallado, se ve
qué documentos devolvió la búsqueda en su lugar.

**Acceptance Scenarios**:

1. **Given** una medición terminada, **When** se abre el resumen, **Then** hay una fila para el conjunto completo y una por
   cada categoría con casos con respuesta, con el número de casos medidos y el valor de cada métrica.
2. **Given** una medición terminada, **When** se abre el detalle, **Then** hay exactamente una fila por caso con respuesta,
   con identificador, documentos esperados, documentos recuperados en orden de cercanía, posición del primer acierto (vacía
   si no hay) y valores por métrica.
3. **Given** que el valor de una métrica en el resumen es la media de las filas del detalle, **When** se recalcula a mano
   con una hoja de cálculo, **Then** coincide con el resumen.
4. **Given** que se ejecuta dos veces sin cambios en la base de conocimiento, en la configuración de búsqueda ni en el
   dataset, **When** se comparan los CSV, **Then** son idénticos.
5. **Given** un valor de texto con comas, comillas, tildes o eñes (identificadores o listas de documentos), **When** se
   abre el CSV en una herramienta estándar, **Then** se lee sin columnas desplazadas ni caracteres alterados.
6. **Given** que el resumen se guarda junto a otro de una ejecución anterior, **When** se comparan, **Then** el propio
   archivo indica con qué k, con qué modelo de embeddings y sobre cuántos casos se calculó, para que la comparación sea
   entre cosas comparables.

---

### User Story 3 - Fallar con claridad si la medición no es válida (Priority: P2)

Como desarrollador, quiero que la medición falle de forma visible cuando no puede dar un resultado fiable (búsqueda caída,
índice vacío o incompleto, dataset inválido), para no confundir un entorno roto con una búsqueda que de verdad no encuentra
nada.

Una búsqueda que no responde o un índice sin los documentos del corpus devolverían ceros en todas las métricas, idénticos
a los de una búsqueda pésima. La medición distingue los dos casos: si no puede ejecutar la búsqueda o el índice no
contiene lo que el dataset espera, se detiene y dice por qué, sin escribir un resultado que parezca válido.

**Why this priority**: Es P2 porque las métricas ya son útiles con un entorno sano, pero un cero silencioso por un
entorno roto llevaría a decisiones equivocadas sobre cambios de retrieval, que es exactamente lo que esta historia quiere
evitar.

**Independent Test**: Ejecutar la medición con la búsqueda apagada, y otra vez con el índice vacío, y comprobar que en
ambos casos termina con error explicando la causa y sin escribir CSV; ejecutarla con un dataset inválido y comprobar que
falla indicando el motivo del dataset.

**Acceptance Scenarios**:

1. **Given** que la búsqueda no está disponible, **When** se ejecuta la medición, **Then** termina con error que nombra la
   causa y no escribe ningún CSV.
2. **Given** un índice sin los documentos del corpus, o con solo parte de ellos, **When** se ejecuta la medición, **Then**
   termina con error que dice que el índice no contiene los documentos que el dataset espera y cuáles faltan, sin
   producir métricas.
3. **Given** un dataset golden inválido según la validación de LAB-27, **When** se ejecuta la medición, **Then** termina
   con error que reproduce los fallos del dataset en lugar de medir sobre datos rotos.
4. **Given** que la búsqueda falla de forma puntual en una pregunta, **When** se ejecuta la medición, **Then** termina
   con error que indica la pregunta afectada y no la cuenta como un fallo de recuperación.
5. **Given** un valor de k no válido (cero, negativo o no numérico), **When** se ejecuta la medición, **Then** termina con
   error que explica cuál es el valor aceptado antes de consultar nada.
6. **Given** cualquier error, **When** termina la ejecución, **Then** el código de salida es distinto de cero, para que
   una automatización (LAB-30) lo detecte.

---

### Edge Cases

- **Menos documentos devueltos que k**: si la búsqueda devuelve menos de k documentos distintos (porque hay pocos
  fragmentos indexados), las métricas se calculan con los que haya; los que faltan cuentan como no encontrados, no como
  error.
- **Caso con dos documentos esperados cuando k = 1**: el recall máximo posible es 0,5; es correcto y no un defecto. Por
  eso se informan varios k y no uno solo.
- **Caso en el que el documento esperado es el segundo de dos que repiten el mismo dato** (una política y su FAQ): basta
  con que aparezca cualquiera de los esperados para el acierto; el recall exige los dos solo si ambos están listados.
- **Documento esperado en dos formatos** (Markdown y PDF del mismo documento): se identifica por su id, sin extensión; un
  fragmento del PDF y uno del Markdown del mismo documento cuentan como el mismo documento.
- **Documento recuperado que no pertenece al dataset ni al corpus** (p. ej. subido a mano desde la pantalla de
  administración): cuenta como un documento recuperado más, que no es un acierto; no rompe la medición.
- **Casos sin respuesta**: no tienen documentos esperados y no se pueden puntuar con estas métricas; se excluyen y se
  informa cuántos son. Medir que el asistente no inventa ante ellos es tarea de la calidad de respuesta (LAB-29).
- **Categoría sin casos con respuesta** (`off_topic`): no aparece en el resumen por categoría, porque no tiene nada que
  medir.
- **Cambio de k entre ejecuciones**: los valores para un mismo k siguen siendo comparables; el resumen indica los k usados.
- **Empate de cercanía entre fragmentos**: el orden lo decide la búsqueda; la medición usa el orden recibido sin
  reordenarlo, de modo que mide la búsqueda real y no una versión ideal.
- **Índice cambiado entre dos ejecuciones** (reindexado, otro modelo de embeddings, otro troceado): es precisamente lo que
  la medición debe revelar; el resumen registra el modelo de embeddings para que la diferencia no pase inadvertida.
- **Datos personales**: el CSV contiene solo preguntas e identificadores del dataset y nombres de documentos del corpus
  sintético; no se escriben textos de los fragmentos recuperados.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: La medición MUST leer el dataset golden de `eval/golden.jsonl` a través de la lectura y validación de
  LAB-27, y MUST NOT medir sobre un dataset inválido.
- **FR-002**: Para cada caso con documentos esperados, la medición MUST ejecutar la misma búsqueda que utiliza el asistente
  para recuperar contexto (la búsqueda por similitud sobre los fragmentos indexados), con la pregunta del caso como
  consulta, y MUST NOT reimplementar ni aproximar esa búsqueda por otra vía.
- **FR-003**: Los fragmentos devueltos MUST convertirse en una lista ordenada de documentos distintos, identificados por el
  id del documento (nombre del archivo sin extensión, igual que `expected_documents`); cada documento ocupa la posición de
  su fragmento más cercano.
- **FR-004**: La medición MUST calcular, para cada k pedido, el **hit rate@k** (proporción de casos con al menos un
  documento esperado entre los k primeros) y el **recall@k** (media de la proporción de documentos esperados presentes
  entre los k primeros).
- **FR-005**: La medición MUST calcular el **MRR** como la media del inverso de la posición del primer documento esperado
  en la lista recuperada completa (0 si ninguno aparece), sin truncarla a k.
- **FR-006**: Las métricas MUST calcularse solo sobre los casos con documentos esperados; los casos sin respuesta MUST
  excluirse y su número MUST informarse en el resultado.
- **FR-007**: Los valores de k MUST ser configurables por quien ejecuta, con un conjunto por defecto de 1, 3 y 5, y MUST
  rechazarse los valores que no sean enteros positivos antes de consultar la búsqueda.
- **FR-008**: La medición MUST pedir a la búsqueda al menos tantos documentos distintos como el mayor k, de forma que un
  k grande no quede artificialmente limitado por el límite por defecto de la búsqueda.
- **FR-009**: El resultado MUST escribirse en CSV (UTF-8, con cabecera, separador y comillas estándar): un **resumen**
  con una fila para el conjunto completo y una por categoría con casos con respuesta, y un **detalle** con una fila por
  caso con respuesta.
- **FR-010**: Cada fila del resumen MUST incluir el número de casos medidos y el valor de hit rate@k y recall@k para cada
  k, además del MRR.
- **FR-011**: Cada fila del detalle MUST incluir el identificador del caso, su categoría, sus documentos esperados, los
  documentos recuperados en orden, la posición del primer documento esperado (vacía si no aparece) y el valor del caso en
  cada métrica, de modo que el resumen pueda recalcularse a partir del detalle.
- **FR-012**: El resultado MUST registrar los parámetros que lo hacen comparable: los k usados, el modelo de embeddings con
  el que está indexada la base de conocimiento, el número de casos medidos y el número de casos excluidos.
- **FR-013**: La medición MUST ser determinista: con el mismo índice, la misma configuración de búsqueda y el mismo
  dataset, dos ejecuciones MUST producir CSV idénticos byte a byte, por lo que el resultado no incluye marcas de tiempo
  ni otros datos que varíen de una ejecución a otra.
- **FR-014**: La medición MUST terminar con error, con código de salida distinto de cero y sin escribir CSV, cuando la
  búsqueda no está disponible, cuando falla en alguna pregunta, o cuando el índice no contiene alguno de los documentos
  esperados por el dataset; el mensaje MUST indicar la causa y, según el caso, la pregunta o los documentos afectados.
- **FR-015**: Una búsqueda que funciona pero no devuelve el documento esperado MUST contarse como fallo de recuperación
  (valor 0), nunca como error de la medición.
- **FR-016**: La medición MUST poder ejecutarse con un único comando desde `eval/`, sin pasos manuales intermedios más
  allá de tener la base de conocimiento indexada, y MUST informar en pantalla un resumen legible de las métricas al
  terminar.
- **FR-017**: El cálculo de las métricas a partir de una lista de documentos recuperados MUST poder probarse de forma
  aislada, sin red, sin base de datos y sin modelo de lenguaje, con casos fijados a mano que cubran acierto en primera
  posición, acierto posterior, fallo, dos documentos esperados con acierto parcial, documentos repetidos y menos
  documentos que k.
- **FR-018**: El resumen y el detalle MUST poder leerse por las historias siguientes de F2 (comparación con línea base en
  LAB-30, persistencia de ejecuciones en LAB-31) sin transformar su estructura, por lo que sus columnas y su significado
  MUST documentarse.
- **FR-019**: La documentación de `eval/` MUST explicar qué mide cada métrica con un ejemplo numérico, cómo ejecutar la
  medición, cómo leer los dos CSV y qué hacer ante cada error, de forma que otra persona pueda usarla sin leer el código.

### Key Entities *(include if feature involves data)*

- **Caso medible**: un caso del dataset golden con al menos un documento esperado. Es la unidad sobre la que se calculan
  las métricas; los casos sin documentos esperados no lo son.
- **Lista recuperada**: los documentos distintos que la búsqueda devuelve para la pregunta de un caso, en orden de
  cercanía, cada uno en la posición de su fragmento más cercano. Es lo único que se compara con los documentos esperados.
- **Métrica de retrieval**: hit rate@k, recall@k o MRR; valor entre 0 y 1 calculado por caso y promediado después por
  categoría y en conjunto.
- **Resumen de la medición**: tabla con una fila por ámbito (conjunto completo y cada categoría) y sus métricas, con los
  parámetros que la hacen comparable (k, modelo de embeddings, casos medidos y excluidos).
- **Detalle de la medición**: tabla con una fila por caso medible: lo esperado, lo recuperado en orden y el valor del caso
  en cada métrica. Es de donde se explica cualquier número del resumen.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con la base de conocimiento indexada, una única ejecución produce el resumen y el detalle en CSV para los
  70 casos con respuesta del dataset actual, sin pasos manuales.
- **SC-002**: En una verificación manual de cinco casos (acierto en primera posición, acierto posterior, fallo, dos
  documentos esperados con acierto parcial y documentos repetidos), los valores del CSV coinciden con los calculados a mano
  en el 100 % de ellos.
- **SC-003**: Recalcular con una hoja de cálculo la media de cualquier columna del detalle reproduce el valor del resumen
  correspondiente en el 100 % de los ámbitos.
- **SC-004**: Dos ejecuciones consecutivas sin cambios producen archivos idénticos en el 100 % de las pruebas.
- **SC-005**: Con la búsqueda apagada, con el índice vacío y con un dataset inválido, la medición termina con error y sin
  CSV en el 100 % de los intentos, y el mensaje permite identificar la causa sin leer el código.
- **SC-006**: Ante un cambio deliberado que empeore la búsqueda (p. ej. indexar con un troceado peor), el hit rate@k o el
  MRR bajan de forma visible en el resumen, y el detalle señala los casos que empeoraron.
- **SC-007**: Una persona que no conoce el repositorio ejecuta la medición y explica qué significa cada columna del
  resumen siguiendo solo la documentación, en menos de 10 minutos.

## Assumptions

- La base de conocimiento de LAB-18 y el dataset golden de LAB-27 (90 casos, de ellos 70 con respuesta y 20 sin
  respuesta) están fusionados en `main` y se usan tal cual. Esta historia no modifica ni el corpus ni el dataset.
- La búsqueda por similitud sobre embeddings (LAB-21) ya está disponible y es la que usa el asistente para recuperar
  contexto (LAB-22). La medición debe ejercer esa búsqueda real: si más adelante cambia (búsqueda híbrida, otro modelo de
  embeddings, otro troceado), las métricas reflejan ese cambio sin tocar la medición.
- La medición se hace **a nivel de documento**, no de fragmento: el dataset golden lista documentos esperados, no
  fragmentos, y el troceado puede cambiar sin invalidar el dataset. Por eso varios fragmentos del mismo documento cuentan
  una sola vez.
- La medición se ejecuta sobre una base de conocimiento ya indexada en el entorno local o de desarrollo; levantarla e
  indexarla es un requisito previo, no parte de esta historia. Tampoco requiere un modelo de lenguaje de respuesta: usa
  solo la búsqueda.
- El k por defecto (1, 3 y 5) cubre el contexto que el asistente consume hoy (unos pocos fragmentos) y deja margen para ver
  si el documento correcto aparece algo más abajo. Quien ejecuta puede pedir otros.
- Se mide el **orden** que produce la búsqueda, es decir, antes de la selección que el chat aplica después (umbral de
  similitud, texto repetido y presupuesto de caracteres, ADR-0018). Esa selección no se reimplementa aquí: una métrica de
  recuperación que dependiera del umbral mezclaría dos decisiones distintas.
- Los CSV son resultados de una ejecución local: dónde se guardan por defecto y si se versionan es una decisión de
  planificación; esta especificación solo fija su contenido y su determinismo.
- **Fuera de alcance**: medir la calidad de las respuestas (fidelidad, relevancia, citas; LAB-29), cualquier métrica que
  requiera un modelo de lenguaje como juez, la ejecución automática en cada PR y la comparación con una línea base o
  umbrales de bloqueo (LAB-30), la persistencia de ejecuciones en base de datos (LAB-31), la pantalla `admin-eval`
  (LAB-32), medir el comportamiento ante preguntas sin respuesta, y cambiar el dataset golden o la búsqueda. Esta historia
  produce y documenta solo las métricas de recuperación y su resultado en CSV.
- La Constitución V pide que los cambios de retrieval lleven un resultado de evaluación; esta historia aporta la mitad de
  recuperación de ese resultado. La puerta que lo exige en los PR llega con LAB-30.
