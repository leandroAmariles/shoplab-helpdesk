# Feature Specification: Métricas de calidad de respuesta (fidelidad, relevancia y precisión del contexto)

**Feature Branch**: `feature/LAB-29-answer-quality-metrics`

**Created**: 2026-11-18

**Status**: Draft

**Input**: User description: "LAB-29 (F2-03) Métricas de calidad de respuesta con RAGAS o DeepEval. Como desarrollador,
quiero medir la calidad de las respuestas. Criterios: RAGAS o DeepEval con faithfulness, answer relevancy y context
precision; LLM-as-judge configurable; las puntuaciones se adjuntan a las trazas de Langfuse." Depende de LAB-27 (dataset
golden) y LAB-64 (trazas de Langfuse), ya fusionadas en `main`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Saber si las respuestas del asistente son fieles, pertinentes y están bien fundamentadas (Priority: P1)

Como desarrollador, quiero ejecutar el asistente sobre cada pregunta del dataset golden y obtener tres puntuaciones por
respuesta (fidelidad, relevancia de la respuesta y precisión del contexto), para saber **qué tan buenas son las
respuestas** y no solo si la búsqueda encontró el documento correcto (LAB-28).

Las tres puntuaciones, todas entre 0 y 1, responden a tres preguntas distintas:

- **Fidelidad (faithfulness)**: ¿cada afirmación de la respuesta se sostiene en el contexto que el asistente recibió? Un 1
  significa que no inventó nada; un valor bajo significa que dijo cosas que ningún fragmento respalda.
- **Relevancia de la respuesta (answer relevancy)**: ¿la respuesta contesta lo que el cliente preguntó? Penaliza las
  respuestas que divagan, contestan otra cosa o se quedan a medias.
- **Precisión del contexto (context precision)**: ¿los fragmentos que llegaron al modelo son los que hacían falta, y los
  útiles van primero? Penaliza el ruido que el asistente tuvo que atravesar.

Para cada caso se consulta al asistente real con la pregunta del caso, se toman la respuesta que dio y el contexto que
recibió, y un modelo juez puntúa cada métrica usando la respuesta de referencia del dataset como verdad de apoyo. El
resultado agrega las puntuaciones por categoría y en conjunto, igual que LAB-28.

Qué métrica se calcula depende del tipo de caso:

| Tipo de caso del dataset | Fidelidad | Relevancia | Precisión del contexto |
|---|---|---|---|
| Con respuesta (76 hoy) | sí | sí | sí |
| Sin respuesta del tema o ajeno a la tienda (20 hoy) | sí | no aplica | no aplica |
| Conversación (`small_talk`, `injection`; 24 hoy) | excluido | excluido | excluido |

Los casos sin respuesta se puntúan solo en fidelidad: es la forma de comprobar que, cuando la tienda no publica el dato, el
asistente **no se lo inventa** (lo que LAB-28 dejó explícitamente para esta historia). Los casos de conversación no pasan
por la recuperación del contexto y no tienen contexto que juzgar; se excluyen y se informa cuántos son.

**Why this priority**: Es el criterio de aceptación principal. La Constitución V exige que desde F2 los cambios de prompt,
chunking, embeddings o recuperación lleven un resultado de evaluación con fidelidad, relevancia y precisión del contexto;
LAB-28 cubre solo la recuperación. Sin estas puntuaciones no hay forma de saber si un cambio empeora lo que el cliente
realmente lee, y LAB-30 (evaluación en CI) las necesita como entrada.

**Independent Test**: Con el stack local levantado, la base de conocimiento indexada y un modelo juez disponible, ejecutar
la medición sobre el dataset golden y comprobar que informa las tres métricas por categoría y en conjunto; después
comprobar a mano tres casos (una respuesta fiel y pertinente, una respuesta con un dato inventado y una respuesta que
contesta otra cosa) y verificar que las puntuaciones los distinguen en el sentido esperado.

**Acceptance Scenarios**:

1. **Given** la base de conocimiento indexada, el dataset golden vigente y un juez disponible, **When** se ejecuta la
   medición, **Then** se informa, para el conjunto completo y para cada categoría, la media de fidelidad, de relevancia y de
   precisión del contexto, todas entre 0 y 1, junto con el número de casos que entró en cada media.
2. **Given** un caso con respuesta cuya respuesta del asistente solo afirma lo que dicen los fragmentos recibidos, **When**
   se puntúa, **Then** su fidelidad es alta (cercana a 1).
3. **Given** un caso con respuesta en el que el asistente añade un dato que ningún fragmento recibido respalda (un plazo o
   un importe inventado), **When** se puntúa, **Then** su fidelidad baja de forma proporcional a lo afirmado sin respaldo.
4. **Given** un caso en el que el asistente contesta algo distinto de lo preguntado, **When** se puntúa, **Then** su
   relevancia es baja aunque la respuesta sea fiel al contexto.
5. **Given** un caso en el que los fragmentos útiles llegaron al modelo después de varios fragmentos irrelevantes, **When**
   se puntúa, **Then** su precisión del contexto es menor que la de un caso idéntico con los fragmentos útiles primero.
6. **Given** un caso sin respuesta (el dato no está publicado) en el que el asistente responde que no tiene información,
   **When** se puntúa, **Then** no se inventa nada: su fidelidad no penaliza la negativa y no se calculan relevancia ni
   precisión del contexto.
7. **Given** un caso sin respuesta en el que el asistente inventa un dato, **When** se puntúa, **Then** su fidelidad es baja
   y el caso aparece como fallo en el detalle.
8. **Given** que el dataset incluye casos de conversación, **When** se calculan las métricas, **Then** esos casos no entran
   en ninguna media y el resultado informa cuántos se excluyeron.

---

### User Story 2 - Un juez configurable y registrado (Priority: P1)

Como desarrollador, quiero poder elegir qué modelo hace de juez (y con qué ajustes) sin cambiar código, y que el resultado
diga con qué juez se calculó, para comparar ejecuciones entre cosas comparables y para poder cambiar de juez cuando el
actual sea demasiado caro, lento o poco fiable.

Las puntuaciones de un modelo juez dependen del modelo que juzga: un juez más débil puntúa distinto de uno más fuerte. Por
eso el juez se configura por quien ejecuta (proveedor y modelo, temperatura, tiempo máximo) a través de los mismos
mecanismos de configuración del resto del proyecto, y por defecto usa el modelo local del gateway, de modo que medir no
exige una cuenta ni una clave externa. El resultado registra el juez y sus ajustes, la versión de la biblioteca de
evaluación y las instrucciones de juicio usadas, y no guarda ningún secreto.

**Why this priority**: Es el segundo criterio de aceptación explícito. Sin juez configurable la medición quedaría atada a
un único modelo, y sin registrarlo dos resultados no serían comparables sin que nadie lo notara.

**Independent Test**: Ejecutar la medición con el juez por defecto y otra vez con otro modelo configurado solo por
configuración; comprobar que ambas terminan, que cada resultado indica su juez y que el cambio no requirió editar código.

**Acceptance Scenarios**:

1. **Given** que no se configura nada, **When** se ejecuta la medición, **Then** usa el juez por defecto y el resultado lo
   nombra.
2. **Given** otro modelo juez indicado por configuración, **When** se ejecuta la medición, **Then** las puntuaciones se
   calculan con ese modelo y el resultado nombra ese modelo y sus ajustes.
3. **Given** un juez que necesita una clave de acceso, **When** se ejecuta la medición, **Then** la clave se toma del
   entorno y no aparece en el resultado, en la pantalla ni en los registros.
4. **Given** un modelo juez inexistente o inalcanzable, **When** se ejecuta la medición, **Then** termina con error que
   nombra el juez, **antes** de consultar al asistente y sin escribir resultados.
5. **Given** dos resultados calculados con jueces distintos, **When** se comparan, **Then** el propio archivo indica la
   diferencia de juez, para que no se confunda una diferencia de juez con una diferencia de calidad.

---

### User Story 3 - Las puntuaciones quedan en la traza de cada respuesta (Priority: P1)

Como desarrollador, quiero que las puntuaciones de cada respuesta se adjunten a su traza en Langfuse, para abrir una
respuesta mala y ver, en el mismo sitio, el prompt que recibió el modelo, los fragmentos recuperados, la respuesta y las
puntuaciones que sacó.

Hoy cada turno del chat deja una traza (LAB-64) con la pregunta, el prompt, los fragmentos con su puntuación, la respuesta,
los tokens y el coste. Esta historia añade a esa misma traza las puntuaciones de fidelidad, relevancia y precisión, con un
nombre estable por métrica y un valor entre 0 y 1, de modo que en Langfuse se pueda filtrar por «fidelidad baja» y abrir las
trazas afectadas. Cada ejecución se distingue de las anteriores para que las puntuaciones de una no machaquen las de otra.

**Why this priority**: Es el tercer criterio de aceptación explícito. El CSV dice **cuántas** respuestas fallan; la traza
dice **por qué**. Sin la traza, depurar una fidelidad baja obliga a reconstruir a mano lo que recibió el modelo.

**Independent Test**: Con las trazas de Langfuse activadas, ejecutar la medición, abrir en Langfuse la traza de un caso
cuya fidelidad salió baja y comprobar que muestra su prompt, sus fragmentos y las tres puntuaciones (o las que aplican), con
los mismos valores que el CSV.

**Acceptance Scenarios**:

1. **Given** las trazas activadas y la medición terminada, **When** se abre la traza de un caso con respuesta, **Then**
   tiene tres puntuaciones (fidelidad, relevancia y precisión del contexto) con los mismos valores que el detalle del CSV.
2. **Given** un caso sin respuesta, **When** se abre su traza, **Then** tiene solo la puntuación de fidelidad.
3. **Given** dos ejecuciones sucesivas sobre el mismo dataset, **When** se abre la traza de un mismo caso en cada una,
   **Then** cada ejecución tiene su traza y sus puntuaciones propias, y las de la primera siguen intactas.
4. **Given** una traza de una ejecución de medición, **When** se busca en Langfuse, **Then** se reconoce que procede de la
   evaluación (no de un cliente real) y de qué ejecución, de modo que no se mezcla con el tráfico del chat.
5. **Given** que el envío a Langfuse no está activado ni configurado, **When** se ejecuta la medición, **Then** se calculan
   las puntuaciones y se escribe el CSV igualmente, y el resultado dice que no se adjuntó nada.
6. **Given** que se pidió adjuntar a Langfuse y Langfuse o las trazas del chat no están disponibles, **When** se ejecuta la
   medición, **Then** termina con error sin consultar al juez y sin recorrer el resto de los casos, para no gastar una
   ejecución cuyas puntuaciones no se podrían adjuntar. Si Langfuse no responde, el error llega antes de cualquier consulta;
   si son las trazas del chat las que están apagadas, solo se habrá hecho la consulta de prueba de un único caso.
7. **Given** que Langfuse deja de responder a mitad de la medición, **When** falla el adjuntar de algún caso, **Then** el
   CSV se escribe completo con las puntuaciones ya calculadas, el resultado indica qué casos no se adjuntaron y la
   ejecución termina con código distinto de cero.

---

### User Story 4 - Distinguir una respuesta mala de una medición inválida (Priority: P2)

Como desarrollador, quiero que la medición falle de forma visible cuando no puede dar un resultado fiable (asistente caído,
juez que no responde, salida del juez ilegible), para no confundir un entorno roto con un asistente que responde mal.

Un asistente que no responde o un juez que devuelve basura darían ceros, idénticos a los de un asistente pésimo. La medición
distingue los dos: una respuesta mala puntúa bajo y la medición sigue; una medición que no puede juzgar se detiene y dice
por qué, sin escribir un resultado que parezca válido.

**Why this priority**: Es P2 porque las puntuaciones ya son útiles con un entorno sano, pero un cero silencioso por un
entorno roto llevaría a decisiones equivocadas sobre cambios de prompt o de recuperación, que es lo que esta historia quiere
evitar. Es el mismo principio que LAB-28.

**Independent Test**: Ejecutar la medición con el backend apagado, con el juez apagado y con un dataset inválido, y
comprobar que en cada caso termina con error que explica la causa, con código distinto de cero y sin CSV.

**Acceptance Scenarios**:

1. **Given** que el backend del chat no está disponible, **When** se ejecuta la medición, **Then** termina con error que
   nombra la causa y no escribe ningún CSV.
2. **Given** que el asistente falla en una pregunta (turno cancelado, error del modelo, flujo cortado), **When** se ejecuta
   la medición, **Then** termina con error que indica el caso afectado y no lo cuenta como una respuesta de baja calidad.
3. **Given** que el juez no responde o su salida no se puede interpretar para un caso, **When** se ejecuta la medición,
   **Then** termina con error que indica el caso y la métrica afectados y no lo cuenta como un cero.
4. **Given** un dataset golden inválido según la validación de LAB-27, **When** se ejecuta la medición, **Then** termina con
   error que reproduce los fallos del dataset en lugar de medir sobre datos rotos.
5. **Given** una respuesta del asistente sin ninguna afirmación que verificar (por ejemplo, un «no tengo información» sobre
   una pregunta que sí tenía respuesta), **When** se puntúa, **Then** la métrica que no se puede calcular por definición
   queda **vacía** en el detalle (no en cero), no entra en la media y el resultado informa cuántos casos quedaron sin
   puntuar en esa métrica.
6. **Given** cualquier error, **When** termina la ejecución, **Then** el código de salida es distinto de cero, para que una
   automatización (LAB-30) lo detecte.
7. **Given** un valor de configuración no válido (un modelo juez vacío, un tiempo máximo no positivo), **When** se ejecuta
   la medición, **Then** termina con error que explica el valor aceptado, antes de consultar nada.

---

### Edge Cases

- **Respuesta fiel pero inútil**: una respuesta que solo repite una frase del contexto sin contestar la pregunta tiene
  fidelidad alta y relevancia baja; es el motivo de medir las dos por separado.
- **Respuesta correcta que no está en el contexto**: si el asistente acierta pero con un dato que ningún fragmento recibido
  respalda, la fidelidad es baja aunque el dato sea cierto; la fidelidad mide si la respuesta está **fundamentada**, no si
  es verdad.
- **Contexto vacío en un caso con respuesta**: si la recuperación no entregó ningún fragmento (y el asistente contestó con
  su mensaje fijo de «no encontré información»), la precisión del contexto es 0 porque no hubo nada útil que entregar y la
  relevancia es baja; no es un error de la medición.
- **Negativa correcta ante un caso sin respuesta**: «no tengo información sobre eso» es la respuesta buena; no debe
  puntuarse como una respuesta pobre. Por eso en estos casos solo se mide la fidelidad.
- **Documento esperado distinto del recuperado pero con el mismo dato** (una política y su FAQ): la precisión del contexto
  se juzga contra lo que el fragmento aporta a la respuesta de referencia, no contra el nombre del documento; el documento
  esperado del dataset no es la vara de esta métrica.
- **Variabilidad del juez**: un modelo juez no da exactamente el mismo número en dos ejecuciones aunque nada cambie.
  Las diferencias pequeñas no son un cambio de calidad; el resultado registra el juez para poder acotarlo y la
  documentación indica cómo medir el ruido (repetir la ejecución).
- **Respuesta muy larga o con tablas**: un tope razonable de tamaño de lo que se envía al juez evita que una respuesta
  desmedida agote su ventana; si lo supera, es un error de la medición que nombra el caso, no un cero.
- **Datos personales**: el CSV contiene preguntas e identificadores del dataset, puntuaciones y los identificadores de los
  documentos del corpus sintético. No escribe el texto de la respuesta ni el de los fragmentos; ese contenido vive solo en
  las trazas de Langfuse, que ya están pensadas para llevarlo (ADR-0022). Solo datos públicos o de prueba.
- **Traza no localizable**: si la traza de un caso no aparece en Langfuse tras un plazo razonable (por ejemplo, porque su
  exportación se descartó por cola llena), no se puede adjuntar; es el caso del escenario 7 de la historia 3 y se informa
  como tal.
- **Tráfico del chat mezclado**: las trazas de la medición se reconocen como de evaluación y se separan de las de clientes
  reales (historia 3, escenario 4). Las métricas agregadas del backend sí cuentan estos turnos como cualquier otro: distinguirlos
  exigiría cambiar el asistente, que esta historia no hace; la documentación lo advierte para que no se lea como uso real.
- **Cambio de k o de profundidad de recuperación entre ejecuciones**: esta historia no los cambia. Mide lo que el chat entrega
  hoy, no una versión idealizada.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: La medición MUST leer el dataset golden de `eval/golden.jsonl` a través de la lectura y validación de LAB-27,
  y MUST NOT medir sobre un dataset inválido.
- **FR-002**: Para cada caso medible, la medición MUST consultar al **asistente real** (el mismo flujo de chat que usa un
  cliente) con la pregunta del caso, y MUST NOT simular su respuesta ni reconstruir su contexto por otra vía.
- **FR-003**: De cada turno la medición MUST obtener la respuesta completa del asistente y el contexto (los fragmentos, en el
  orden en que se entregaron) que recibió el modelo, y MUST tratar un turno incompleto, cancelado o fallido como error de la
  medición, no como una respuesta de baja calidad.
- **FR-004**: La medición MUST calcular, por caso y con un modelo juez, la **fidelidad** (proporción de las afirmaciones de la
  respuesta respaldadas por el contexto recibido), la **relevancia de la respuesta** (cuánto contesta la respuesta lo
  preguntado) y la **precisión del contexto** (cuántos de los fragmentos entregados son útiles, con más peso cuanto antes
  aparecen), todas entre 0 y 1.
- **FR-005**: La medición MUST usar la respuesta de referencia del dataset como verdad de apoyo cuando una métrica la
  necesite, y MUST NOT modificar el dataset.
- **FR-006**: Qué métricas se calculan MUST depender del tipo de caso: los casos con respuesta reciben las tres; los casos
  sin respuesta (del tema o ajenos a la tienda) reciben solo fidelidad; los casos de conversación (`small_talk`,
  `injection`) se excluyen y su número se informa.
- **FR-007**: El modelo juez MUST ser configurable por quien ejecuta (modelo y proveedor, temperatura, tiempo máximo y
  tamaño máximo de entrada) sin cambiar código, con un juez local por defecto que no requiere cuenta ni clave externa.
- **FR-008**: Las claves de acceso del juez MUST venir del entorno y MUST NOT aparecer en el resultado, en pantalla, en los
  registros ni en las trazas.
- **FR-009**: El juez MUST ejercerse a través del mismo gateway de modelos del resto del proyecto, de modo que cambiar de
  juez no implique un cliente nuevo ni credenciales fuera del mecanismo existente.
- **FR-010**: La medición MUST comprobar antes de empezar —antes de consultar al asistente o al juez en cantidad— que el
  backend, el juez y, si se pidió adjuntar, Langfuse están disponibles, y MUST terminar con error que nombra el componente
  caído si no lo están.
- **FR-011**: Una puntuación que no se puede calcular **por definición** (por ejemplo, fidelidad de una respuesta sin
  afirmaciones) MUST quedar vacía, no en cero; MUST excluirse de las medias y MUST contarse y informarse. Un fallo del juez
  (sin respuesta, salida ilegible, entrada demasiado grande) MUST ser un error de la medición, nunca una puntuación.
- **FR-012**: Las puntuaciones MUST adjuntarse a la traza de Langfuse de cada turno medido, con un nombre estable por métrica
  (fidelidad, relevancia, precisión del contexto) y valores entre 0 y 1, sin duplicarse al repetir la ejecución.
- **FR-013**: Cada ejecución MUST poder distinguirse de las demás (identificador de ejecución), y las trazas de la medición
  MUST poder reconocerse como de evaluación y filtrarse por ejecución, de modo que las puntuaciones de una ejecución no
  sobrescriban las de otra ni se mezclen con las trazas de clientes.
- **FR-014**: Adjuntar a Langfuse MUST ser opcional por configuración. Sin él, la medición MUST calcular y escribir el CSV
  igualmente e informar que no se adjuntó nada. Si se pidió y un adjuntar falla a mitad de ejecución, el CSV MUST escribirse
  completo, el resultado MUST indicar qué casos no se adjuntaron y el código de salida MUST ser distinto de cero.
- **FR-015**: El resultado MUST escribirse en CSV (UTF-8, con cabecera, separador y comillas estándar): un **resumen** con una
  fila para el conjunto completo y una por categoría con casos puntuados, y un **detalle** con una fila por caso medido.
- **FR-016**: Cada fila del resumen MUST incluir, por métrica, la media y el número de casos que entró en ella, además de los
  casos excluidos y los casos sin puntuar en esa métrica.
- **FR-017**: Cada fila del detalle MUST incluir el identificador del caso, su categoría, el valor de cada métrica (vacío
  si no aplica o no se pudo calcular), los identificadores de los documentos de los fragmentos entregados en orden y el
  identificador de la traza, de modo que el resumen pueda recalcularse a partir del detalle y cada fila lleve a su traza.
- **FR-018**: El resultado MUST registrar los parámetros que lo hacen comparable: el juez y sus ajustes, la biblioteca de
  evaluación y su versión, la versión de las instrucciones de juicio, el modelo del asistente y el de embeddings con que se
  midió, el identificador de la ejecución y el número de casos medidos, excluidos y sin puntuar.
- **FR-019**: El CSV MUST NOT contener el texto de las respuestas ni el de los fragmentos; ese contenido MUST quedar solo en
  las trazas de Langfuse.
- **FR-020**: La medición MUST terminar con error, con código de salida distinto de cero y sin escribir CSV, cuando el
  backend del chat o el juez no están disponibles al empezar, cuando el asistente falla en un caso, cuando el juez falla en
  un caso o cuando el dataset es inválido; el mensaje MUST indicar la causa y, según el caso, el caso o la métrica afectados.
  La única excepción es la del FR-014 (fallo de adjuntar a Langfuse), que conserva el CSV.
- **FR-021**: Las puntuaciones bajas MUST contarse como resultados (valor bajo), nunca como errores de la medición.
- **FR-022**: La medición MUST poder ejecutarse con un único comando desde `eval/`, sin pasos manuales intermedios más allá
  de tener el stack levantado y la base de conocimiento indexada, y MUST informar en pantalla un resumen legible al terminar,
  incluido el avance mientras corre.
- **FR-023**: El cálculo de las medias, las exclusiones y el reparto de métricas por tipo de caso MUST poder probarse de forma
  aislada, sin red, sin base de datos y sin modelo de lenguaje, con valores fijados a mano que cubran un caso con respuesta,
  un caso sin respuesta, un caso de conversación, una puntuación vacía por definición y una categoría sin casos puntuados.
- **FR-024**: El resumen y el detalle MUST poder leerse por las historias siguientes de F2 (comparación con línea base y
  umbrales en LAB-30, persistencia de ejecuciones en LAB-31) sin transformar su estructura, por lo que sus columnas y su
  significado MUST documentarse, siguiendo la convención de los CSV de LAB-28.
- **FR-025**: La documentación de `eval/` MUST explicar qué mide cada métrica con un ejemplo, cómo ejecutar la medición,
  cómo elegir y cambiar el juez, cómo ver las puntuaciones en Langfuse, cómo leer los dos CSV, cómo estimar el ruido del juez
  y qué hacer ante cada error, de modo que otra persona pueda usarla sin leer el código.
- **FR-026**: La medición MUST NOT cambiar el comportamiento del asistente ni el dataset; mide, no corrige. Una traza de
  evaluación MUST NOT alterar el contenido de lo que el cliente recibe.

### Key Entities *(include if feature involves data)*

- **Caso medible**: un caso del dataset golden de tipo con respuesta o sin respuesta. Es la unidad sobre la que se calculan
  las puntuaciones; los casos de conversación no lo son.
- **Turno evaluado**: la respuesta completa del asistente a la pregunta de un caso, junto con el contexto (los fragmentos, en
  orden) que recibió el modelo y el identificador de su traza. Es lo único que se juzga.
- **Puntuación**: valor entre 0 y 1 de una métrica sobre un turno evaluado, o vacío si la métrica no aplica o no se puede
  calcular por definición. Una puntuación nunca es un cero que oculte un fallo.
- **Métrica de calidad de respuesta**: fidelidad, relevancia de la respuesta o precisión del contexto, calculada por caso y
  promediada después por categoría y en conjunto.
- **Juez**: el modelo de lenguaje que puntúa, con sus ajustes y las instrucciones de juicio. Forma parte de la identidad del
  resultado.
- **Ejecución de evaluación**: una pasada de la medición completa, con identificador propio, que agrupa sus trazas y sus
  puntuaciones y distingue unas de otras.
- **Resumen de la medición**: tabla con una fila por ámbito (conjunto completo y cada categoría), con la media de cada
  métrica, los casos que entraron en ella y los parámetros que la hacen comparable.
- **Detalle de la medición**: tabla con una fila por caso medido: sus puntuaciones, el contexto entregado (por documento) y
  su traza. Es de donde se explica cualquier número del resumen.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con el stack levantado, la base indexada y un juez disponible, una única ejecución produce el resumen y el
  detalle en CSV para los 96 casos de conocimiento del dataset actual (76 con respuesta y 20 sin respuesta), sin pasos
  manuales, y en menos de 30 minutos con el juez local por defecto.
- **SC-002**: En una verificación manual de cinco pares de respuestas (una fiel y otra con un dato inventado sobre la misma
  pregunta), la fidelidad de la respuesta con el dato inventado es menor que la de la fiel en el 100 % de los pares.
- **SC-003**: En una verificación manual de cinco casos (una respuesta pertinente y una que contesta otra cosa sobre la misma
  pregunta, y tres con el contexto desordenado frente a ordenado), la relevancia y la precisión del contexto se mueven en el
  sentido esperado en el 100 % de ellos.
- **SC-004**: Recalcular con una hoja de cálculo la media de una columna del detalle, ignorando las celdas vacías, reproduce
  el valor del resumen correspondiente en el 100 % de los ámbitos.
- **SC-005**: Para cada caso de las trazas de una ejecución con Langfuse activado, las puntuaciones de la traza coinciden con
  las del detalle del CSV en el 100 % de los casos.
- **SC-006**: Cambiar el juez solo por configuración produce un resultado cuyo archivo nombra el nuevo juez, sin editar
  código, en el 100 % de las pruebas.
- **SC-007**: Con el backend apagado, con el juez apagado y con un dataset inválido, la medición termina con error y sin CSV
  en el 100 % de los intentos, y el mensaje permite identificar la causa sin leer el código.
- **SC-008**: Ante un cambio deliberado que empeore la respuesta (p. ej. un prompt que permita inventar), la fidelidad baja
  de forma visible en el resumen y el detalle señala los casos que empeoraron.
- **SC-009**: Dos ejecuciones consecutivas sin cambios producen medias por métrica que difieren como máximo en 0,05; el
  ruido observado queda documentado.
- **SC-010**: Una persona que no conoce el repositorio ejecuta la medición, elige otro juez y encuentra en Langfuse la traza
  de un caso fallado siguiendo solo la documentación, en menos de 15 minutos.

## Assumptions

- El dataset golden de LAB-27 (120 casos: 96 de conocimiento —76 con respuesta y 20 sin respuesta— y 24 de conversación) y
  las trazas de LAB-64 están fusionados en `main` y se usan tal cual. Esta historia no modifica ni el dataset ni el corpus,
  ni el chat más allá de lo que haga falta para poder identificar y correlacionar sus turnos de evaluación.
- La respuesta de referencia del dataset está **en borrador hasta que una persona la revise** (`eval/REVIEW.md`). Las
  puntuaciones que la usan heredan esa reserva; esta historia ni la resuelve ni la registra en el resultado: la documentación
  de `eval/` la advierte, para que no se confíe de más.
- «RAGAS o DeepEval» se decide en la planificación (ADR): esta especificación fija qué se mide, no con qué biblioteca. La
  elegida debe soportar un juez configurable y sus puntuaciones deben equivaler a las tres definiciones de la historia 1.
- La **recuperación del contexto** (el *context recall* de la Constitución V) la cubre LAB-28 por documento; esta historia no
  la repite. La precisión del contexto aquí es la de los fragmentos que **recibió el modelo**, tras el umbral y el
  presupuesto del chat (ADR-0018), que es lo que el cliente efectivamente se lleva.
- El juez por defecto es el modelo local servido por el gateway (LAB-84), porque el corpus es sintético y público y medir no
  debe exigir una cuenta de pago. Un juez local más débil que un modelo grande de pago produce puntuaciones menos finas; por
  eso se registra y es intercambiable. Usar un juez externo con datos reales no está permitido por la Constitución VI y no
  forma parte de esta historia.
- El ambiente de desarrollo (`shoplab-infra`, `make up`) es el **entorno local del equipo**: la medición debe funcionar contra
  él con `make up` y la base de conocimiento cargada, y no solo contra `docker compose`. Lo ya implementado (backend, frontend,
  ingest, gateway de modelos, trazas y observabilidad) debe funcionar en ese clúster, con Ollama como único componente fuera de
  él (en el Docker del host, como ya hace el overlay de dev). La infraestructura que eso exija se hace en esta historia.
- La medición se ejecuta contra el stack local o de desarrollo ya levantado, con la base indexada y, para adjuntar
  puntuaciones, con las trazas del chat y Langfuse activados (están apagados por defecto, ADR-0022). Levantarlos es un
  requisito previo, no parte de esta historia.
- La medición consume los resultados del chat real (nada simulado), por lo que **no es determinista**: el asistente y el juez
  son modelos de lenguaje. A diferencia de LAB-28, dos ejecuciones no dan CSV idénticos; esta historia exige en cambio que el
  resultado se pueda comparar (FR-018) y que el ruido esté acotado y documentado (SC-009).
- El objetivo de 30 minutos (SC-001) y la tolerancia de 0,05 (SC-009) son puntos de partida razonables para el juez local;
  se confirman o se ajustan en la planificación con una medición real.
- Los CSV son resultados de una ejecución local y no se versionan, igual que los de LAB-28; dónde se guardan por defecto
  (`eval/results/`) es una decisión de planificación. Esta especificación solo fija su contenido.
- Los casos de conversación se excluyen porque no pasan por la recuperación y su calidad no se expresa con estas tres
  métricas. Medir que una inyección no revela el prompt es una prueba del backend (LAB-114), no una métrica de esta historia.
- **Fuera de alcance**: ejecutar la evaluación automáticamente en cada PR, compararla con una línea base o fijar umbrales de
  bloqueo (LAB-30); persistir ejecuciones en base de datos (LAB-31); la pantalla `admin-eval` (LAB-32); el feedback de los
  usuarios (LAB-33); métricas de recuperación (LAB-28); evaluar la calidad de las citas de las fuentes; medir latencia o
  coste del asistente; y cambiar el asistente, el dataset o la búsqueda. Esta historia produce y documenta solo las
  métricas de calidad de respuesta, su resultado en CSV y su adjuntado a las trazas.
- La Constitución V exige un resultado de evaluación en cada cambio de prompt, chunking, embeddings o recuperación; esta
  historia aporta la mitad de calidad de respuesta de ese resultado (la de recuperación es LAB-28). La puerta que lo exige en
  los PR llega con LAB-30.
