# Feature Specification: Pantalla de evolución de métricas (admin-eval)

**Feature Branch**: `feature/LAB-32-admin-eval-screen`

**Created**: 2026-11-21

**Status**: Draft

**Input**: User description: "LAB-32 (F2-06) Pantalla de evolución de métricas (admin-eval). Como administrador, quiero ver la
evolución de las métricas. Criterios: pantalla `admin-eval` con gráfica por ejecución; detalle de los casos que fallan."
Depende de LAB-13 (aplicación Angular base) y LAB-31 (persistencia de las ejecuciones de evaluación); ambas están fusionadas
en `main`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver la evolución de las métricas ejecución a ejecución (Priority: P1)

Como administrador, quiero abrir una pantalla y ver, en una gráfica, cómo han cambiado las métricas del asistente de una
ejecución de evaluación a la siguiente, para saber de un vistazo si el asistente mejora, empeora o sigue igual sin abrir CSV ni
escribir consultas.

Hoy el historial existe (LAB-31) pero solo se lee con consultas SQL documentadas en `eval/queries/`. Eso sirve a quien mide, no
a quien administra el asistente y quiere responder «¿la última versión del prompt mejoró la fidelidad?». La pantalla
`admin-eval` convierte ese historial en una gráfica: una **posición por ejecución**, ordenadas por fecha, con el valor del
conjunto completo de la métrica elegida.

Las dos mediciones tienen métricas distintas (calidad de respuesta: fidelidad, relevancia de la respuesta y precisión del
contexto; recuperación: hit rate y recall por cada k, y MRR), así que la pantalla deja elegir **el tipo de medición** y, dentro
de él, **qué métricas** ver. Por defecto muestra la medición de calidad con la fidelidad, que es la métrica que más importa
para un asistente que no debe inventar.

**Why this priority**: Es el primer criterio de aceptación de Jira («gráfica por ejecución») y la razón de ser de la historia.
Sin ella el historial de LAB-31 no llega a nadie que no sepa SQL.

**Independent Test**: Con al menos tres ejecuciones guardadas de un mismo tipo, abrir la pantalla y comprobar que la gráfica
tiene un punto por ejecución en orden cronológico, que el valor de cada punto coincide con la media del conjunto completo de esa
ejecución (consulta `03-run-summary.sql` o el CSV) y que cambiar de métrica o de tipo cambia la gráfica.

**Acceptance Scenarios**:

1. **Given** varias ejecuciones de calidad guardadas, **When** el administrador abre `admin-eval`, **Then** ve una gráfica con
   la fidelidad de cada ejecución en orden cronológico, la más reciente a la derecha, y el resumen de cuántas ejecuciones se
   muestran.
2. **Given** la gráfica, **When** el administrador señala un punto (con ratón, teclado o toque), **Then** ve el identificador de
   la ejecución, su fecha, el valor exacto de la métrica y con cuántos casos se calculó esa media.
3. **Given** la gráfica de calidad, **When** el administrador añade otra métrica (relevancia o precisión del contexto),
   **Then** las métricas se pueden comparar en la misma gráfica sin que una oculte a la otra.
4. **Given** que el administrador cambia al tipo recuperación, **When** se carga, **Then** la gráfica muestra las métricas de
   recuperación (hit rate, recall por k y MRR) de las ejecuciones de ese tipo, y nunca mezcla en una misma serie ejecuciones de
   tipos distintos.
5. **Given** una ejecución en la que una métrica **no se pudo calcular por definición** (la media no tiene ningún caso), **When**
   se dibuja, **Then** esa ejecución queda como un hueco visible en la serie y no como un cero.
6. **Given** ejecuciones de orígenes distintos (`local`, `dev`, `ci`), **When** el administrador filtra por origen, **Then** la
   gráfica y el resto de la pantalla solo muestran las ejecuciones de ese origen, para no confundir una prueba local con una
   ejecución de referencia.
7. **Given** una serie con pocas ejecuciones o con una sola, **When** se dibuja, **Then** la gráfica sigue siendo legible (un
   punto aislado se ve como punto) y no inventa una tendencia.

---

### User Story 2 - Ver el detalle de una ejecución y de sus casos que fallan (Priority: P1)

Como administrador, quiero elegir una ejecución y ver cuáles de sus casos fallan, con qué valor y por qué, para pasar de «la
fidelidad bajó a 0,71» a «estas preguntas son las que fallan» y poder actuar sobre ellas.

Al elegir una ejecución (desde un punto de la gráfica o desde la lista de ejecuciones) la pantalla muestra:

- **Con qué se midió**: la versión de cada prompt, el modelo del asistente, el de embeddings, el juez y la configuración de
  recuperación, el origen, el commit y cuántos casos se midieron, se excluyeron o quedaron sin puntuar. Es lo que permite
  explicar un cambio por lo que cambió y no por adivinación (LAB-31).
- **El resumen**: el valor de cada métrica del conjunto completo y por categoría de caso, con el número de casos de cada media.
- **Los casos que fallan**: una fila por caso con su identificador (`G044`), su categoría, su pregunta, el valor de cada
  métrica, los documentos que se esperaban frente a los que se recuperaron y, cuando existe, la forma de llegar a su traza en
  Langfuse, que es donde viven el prompt, los fragmentos y la respuesta.

Un caso **falla** en calidad cuando alguna de sus métricas **con valor** queda por debajo de un **umbral que elige quien mira**
en la pantalla (valor inicial 0,5; el criterio es informativo y no bloquea nada, los umbrales de bloqueo son de LAB-30). En
recuperación, un caso falla cuando no se encontró ninguno de los documentos esperados dentro de los primeros k recuperados, con
el k que se elige en la pantalla (por defecto, el mayor de los k pedidos en esa ejecución).

**Why this priority**: Es el segundo criterio de aceptación de Jira («detalle de los casos que fallan»). La gráfica dice que algo
cambió; el detalle dice qué, y es lo que lleva a editar un prompt, corregir un documento o revisar un caso del dataset.

**Independent Test**: Elegir una ejecución con casos de varias categorías, localizar tres casos que fallan en la pantalla y
comprobar contra el CSV de esa ejecución (o la consulta del historial) que sus valores, sus documentos esperados y los
recuperados coinciden, incluido un caso con una métrica vacía por definición y un caso sin respuesta (solo fidelidad).

**Acceptance Scenarios**:

1. **Given** una ejecución guardada, **When** el administrador la elige, **Then** ve los parámetros con que se midió (prompts con
   su versión numérica, modelos, juez, recuperación, origen, commit y dataset) sin leer código ni CSV.
2. **Given** la misma ejecución, **When** se muestra el resumen, **Then** las medias del conjunto completo y por categoría
   coinciden con las que se obtienen de sus resultados ignorando los vacíos, y cada media indica con cuántos casos se calculó.
3. **Given** una ejecución con casos por debajo del umbral, **When** el administrador abre el detalle, **Then** ve la lista de
   esos casos ordenada de peor a mejor, y puede reordenarla y filtrarla por categoría y por métrica.
4. **Given** un caso de la lista, **When** el administrador lo abre, **Then** ve la pregunta, la respuesta de referencia, los
   documentos esperados y los recuperados en el orden en que se entregaron, y ve cuáles aciertan y cuáles no.
5. **Given** un caso con una métrica vacía (no aplica o no se pudo calcular por definición), **When** se muestra, **Then** la
   métrica aparece como «sin valor» claramente distinta de un cero real, y un caso así no cuenta como fallo por esa métrica.
6. **Given** un caso con identificador de traza, **When** el administrador sigue el enlace, **Then** llega a la traza de ese
   turno en Langfuse; si el enlace no se puede construir, ve el identificador para copiarlo.
7. **Given** una ejecución sin ningún caso que falle, **When** se abre el detalle, **Then** la pantalla lo dice explícitamente
   en lugar de mostrar una lista vacía sin explicación.
8. **Given** que el administrador quiere saber con qué prompt se midió, **When** mira una ejecución, **Then** **no** ve el texto
   de ninguna respuesta del asistente ni de ningún fragmento recuperado, porque no se guardan (LAB-31, FR-009); para eso sigue
   el enlace a la traza.

---

### User Story 3 - Entender qué cambió entre dos ejecuciones (Priority: P2)

Como administrador, quiero comparar una ejecución con otra, para ver qué parámetros difieren y qué casos empeoraron o
mejoraron, y así atribuir un cambio de métricas a su causa real.

Una gráfica que baja sin explicación genera más preguntas que respuestas. Desde la gráfica o la lista, el administrador elige una
ejecución de **referencia** y otra **a comparar** (por defecto, la anterior del mismo tipo). La pantalla muestra:

- Los **parámetros que difieren** (versión de un prompt, modelo del asistente, juez, embeddings, recuperación, dataset), con el
  valor de cada lado.
- La **diferencia de cada métrica** del conjunto completo.
- Los **casos que empeoraron y los que mejoraron**, con el valor de cada ejecución y su diferencia.

Un caso cuyo contenido cambió entre las dos ejecuciones (una pregunta reescrita, una respuesta de referencia corregida) se marca
como **no comparable** en lugar de presentar su diferencia como un cambio de calidad, y la comparación advierte cuando difieren
el juez, el modelo del asistente, el de embeddings, la recuperación o el dataset (el mismo criterio que LAB-31, FR-017).

**Why this priority**: Es P2 porque las historias 1 y 2 cumplen los criterios de aceptación de Jira; la comparación es lo que
vuelve accionable la gráfica y está respaldada por el mismo historial (revisión de caso y parámetros guardados), pero no es lo
mínimo para entregar valor.

**Independent Test**: Con dos ejecuciones guardadas, una de ellas con un cambio deliberado de prompt, comparar ambas y comprobar
que la pantalla señala el prompt que cambió, que la lista de casos que empeoraron coincide con la consulta
`07-regressions.sql` y que un caso editado entre las dos aparece como no comparable.

**Acceptance Scenarios**:

1. **Given** dos ejecuciones del mismo tipo que difieren en la versión de un prompt, **When** el administrador las compara,
   **Then** la pantalla nombra el prompt y las dos versiones, y muestra la diferencia de cada métrica del conjunto completo.
2. **Given** la misma comparación, **When** se listan los casos, **Then** aparecen los que empeoraron, con el valor de cada
   ejecución y su diferencia, y los que mejoraron; la lista de los que empeoraron coincide con la de las consultas del historial.
3. **Given** un caso cuyo contenido cambió entre las dos ejecuciones, **When** se comparan, **Then** el caso se marca como no
   comparable y no figura entre los que empeoraron ni entre los que mejoraron.
4. **Given** dos ejecuciones cuyo juez, modelo del asistente, embeddings, recuperación o dataset difieren, **When** se comparan,
   **Then** la pantalla advierte que la comparación puede no reflejar un cambio de calidad y nombra qué difiere.
5. **Given** dos ejecuciones de tipos distintos, **When** el administrador intenta compararlas, **Then** la pantalla no ofrece
   la comparación y explica por qué (sus métricas no son las mismas).
6. **Given** un caso que existe en una ejecución y no en la otra (retirado o añadido al dataset), **When** se comparan,
   **Then** se muestra como presente solo en una, sin diferencia calculada.

---

### User Story 4 - Llegar a la pantalla y usarla en cualquier dispositivo y estado (Priority: P2)

Como administrador, quiero llegar a `admin-eval` desde la navegación de la aplicación y que me diga con claridad qué pasa cuando
no hay datos, cuando carga o cuando algo falla, para no confundir un historial vacío con una pantalla rota.

La pantalla se integra en la navegación existente de la aplicación (la de LAB-13, junto a «Chat» y «Documentos») y sigue las
convenciones de las pantallas anteriores: se adapta a pantallas estrechas sin desplazamiento horizontal de la página, se maneja
con teclado y comunica sus estados también con texto, no solo con color.

**Why this priority**: Es P2 porque las historias 1 y 2 ya dan el valor, pero una pantalla inalcanzable, inaccesible o muda ante
un historial vacío o un error no se puede dar por terminada.

**Independent Test**: Abrir la aplicación en un teléfono y en un escritorio, llegar a la pantalla desde la navegación sin
escribir la dirección, y comprobar los cuatro estados (cargando, sin ejecuciones, con ejecuciones, con error) y el uso solo con
teclado.

**Acceptance Scenarios**:

1. **Given** la aplicación, **When** el administrador usa la navegación, **Then** encuentra el acceso a la evaluación en el mismo
   lugar y con el mismo estilo que «Chat» y «Documentos», en escritorio y en teléfono.
2. **Given** que todavía no hay ninguna ejecución guardada (o la persistencia nunca se activó), **When** se abre la pantalla,
   **Then** explica que aún no hay historial y cómo se genera uno (la medición con guardado activado), sin errores ni gráfica
   vacía sin explicación.
3. **Given** que el servicio no responde, **When** se abre la pantalla, **Then** muestra un mensaje de error comprensible, sin
   detalles internos, con la posibilidad de reintentar, y no deja la pantalla cargando indefinidamente.
4. **Given** que los datos tardan en llegar, **When** se espera, **Then** la pantalla indica que está cargando y mantiene lo ya
   mostrado mientras actualiza.
5. **Given** un administrador que usa solo teclado o un lector de pantalla, **When** recorre la pantalla, **Then** puede
   elegir tipo, métricas, origen, ejecución y caso, y la gráfica ofrece una alternativa textual equivalente (una tabla con los
   mismos valores).
6. **Given** una pantalla de teléfono, **When** se muestra, **Then** la gráfica y las tablas se adaptan sin scroll horizontal de
   la página y el detalle de los casos sigue siendo legible.

---

### Edge Cases

- **Muchas ejecuciones**: con cientos de ejecuciones guardadas, la gráfica sigue siendo legible: muestra por defecto las más
  recientes y permite ampliar el rango; nunca se truncan en silencio (la pantalla dice cuántas muestra y cuántas hay).
- **Dos ejecuciones con la misma fecha**: se ordenan de forma estable (por fecha de inicio y, en empate, por identificador) y
  cada una es un punto distinto.
- **Ejecuciones de recuperación con k distintos**: solo se dibujan como serie las métricas que existen en las ejecuciones
  mostradas; la métrica que una ejecución no tiene queda como hueco, no como cero (LAB-31: añadir una métrica no reescribe lo
  anterior).
- **Caso editado o retirado del dataset**: lo medido en una ejecución sigue mostrando el contenido que el caso tenía entonces; la
  pantalla no lo reescribe con la versión actual (LAB-31, FR-004).
- **Caso excluido o sin respuesta**: los casos de conversación (`small_talk`, `injection`) no están en el historial y no figuran
  en el detalle; el número de excluidos se muestra como dato de la ejecución. Un caso sin respuesta solo tiene fidelidad y no
  cuenta como fallo en las demás métricas.
- **Datos no disponibles de una ejecución**: el commit o el modelo del asistente pueden ser desconocidos; se muestran como
  «desconocido», nunca como un valor inventado ni se omite la fila.
- **Ejecuciones de pruebas o de máquinas locales**: se distinguen por su origen y se pueden filtrar; el filtro elegido no hace
  desaparecer en silencio las ejecuciones: la pantalla dice cuántas hay ocultas.
- **Dataset en borrador**: el dataset golden sigue siendo un borrador hasta su revisión humana (`eval/REVIEW.md`); los valores de
  la pantalla heredan esa reserva y la pantalla no los presenta como una certificación de calidad.
- **Datos sensibles**: la pantalla muestra puntuaciones, identificadores de documentos del corpus sintético y el contenido del
  dataset golden (sintético), nunca texto de respuestas ni de fragmentos, claves, tokens ni direcciones internas.
- **Historial que cambia mientras se mira**: si se guarda una ejecución nueva con la pantalla abierta, la pantalla no cambia por
  sorpresa; la nueva aparece al actualizar (acción explícita) y nunca deja una ejecución a medias (una ejecución visible está
  entera, LAB-31).
- **Traza no alcanzable**: si Langfuse está apagado o su dirección no se conoce, el identificador de traza se muestra para
  copiarlo y la pantalla no falla.
- **Acciones de escritura**: la pantalla es de **solo lectura**; no lanza mediciones, no edita casos ni borra ejecuciones.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: La aplicación MUST ofrecer una pantalla `admin-eval` accesible desde la navegación existente, con el mismo patrón
  de acceso que «Chat» y «Documentos», en escritorio y en teléfono.
- **FR-002**: La pantalla MUST mostrar una gráfica con **un punto por ejecución guardada**, en orden cronológico, con el valor
  del conjunto completo de la métrica elegida (criterio de aceptación de Jira).
- **FR-003**: La pantalla MUST permitir elegir el **tipo de medición** (calidad de respuesta o recuperación) y las **métricas**
  que se dibujan, y MUST NOT mezclar en una misma serie ejecuciones de tipos distintos. Por defecto: calidad, fidelidad.
- **FR-004**: Cada punto MUST poder inspeccionarse con ratón, teclado y toque, y MUST mostrar el identificador de la ejecución,
  su fecha, el valor exacto y con cuántos casos se calculó; la gráfica MUST ofrecer una alternativa textual con los mismos
  valores.
- **FR-005**: Un valor que no se pudo calcular por definición (ningún caso aplicable) MUST dibujarse como un **hueco** y mostrarse
  como «sin valor», distinto de un cero; MUST NOT conectarse con una línea como si valiera cero.
- **FR-006**: La pantalla MUST permitir filtrar por **origen** de la ejecución e informar de cuántas ejecuciones quedan
  ocultas por el filtro.
- **FR-007**: Los valores mostrados (puntos de la gráfica, resumen, valores por caso) MUST ser **idénticos** a los guardados por
  LAB-31: la media de cada métrica recalculada desde los resultados de la ejecución, ignorando los vacíos, y el número de casos
  de cada media, MUST coincidir con el resumen de su CSV hasta el cuarto decimal.
- **FR-008**: Al elegir una ejecución, la pantalla MUST mostrar con qué se midió: versión numérica de cada prompt (y si se fijó
  por petición), modelo del asistente y de embeddings, juez y biblioteca de evaluación (en calidad), configuración de
  recuperación, origen, commit, huella del dataset y número de casos medidos, excluidos y sin puntuar. Un dato desconocido MUST
  mostrarse como desconocido, no omitirse ni inventarse.
- **FR-009**: La pantalla MUST mostrar el resumen de la ejecución por métrica, del conjunto completo y por categoría de caso,
  con el número de casos de cada media.
- **FR-010**: La pantalla MUST mostrar el **detalle de los casos que fallan** de una ejecución (criterio de aceptación de Jira):
  identificador, categoría, pregunta, valor de cada métrica, documentos esperados frente a los recuperados y acceso a la traza.
  En calidad, un caso MUST contar como fallo cuando alguna de sus métricas con valor queda por debajo del **umbral que elige
  quien mira** en la pantalla (valor inicial 0,5, entre 0 y 1); cambiarlo MUST actualizar la lista sin recargar la ejecución.
- **FR-011**: En recuperación, un caso MUST contar como fallo cuando ninguno de sus documentos esperados aparece entre los
  primeros k recuperados, con el k elegido en la pantalla (por defecto, el mayor de los k pedidos en esa ejecución); un caso
  sin documentos esperados no cuenta como fallo.
- **FR-012**: La lista de casos que fallan MUST poder ordenarse (peor primero por defecto) y filtrarse por categoría y por
  métrica, y MUST informar de cuántos casos fallan sobre cuántos se midieron.
- **FR-013**: Un valor vacío de un caso (no aplica o no se pudo calcular por definición) MUST mostrarse como «sin valor», distinto
  de un cero, y MUST NOT contar como fallo por esa métrica.
- **FR-014**: Al abrir un caso, la pantalla MUST mostrar su pregunta, su respuesta de referencia, los documentos esperados y los
  recuperados en el orden en que se entregaron, marcando cuáles acertaron. El contenido del caso MUST ser el que tenía cuando se
  midió esa ejecución (LAB-31, FR-004), no el actual del dataset.
- **FR-015**: Cuando una ejecución tiene el identificador de traza de un caso, la pantalla MUST ofrecer la forma de llegar a esa
  traza en Langfuse o, si no se puede construir el enlace, mostrar el identificador para copiarlo.
- **FR-016**: La pantalla MUST NOT mostrar ni almacenar el texto de las respuestas del asistente ni el de los fragmentos
  recuperados, ni claves, tokens o direcciones internas (LAB-31, FR-009).
- **FR-017**: La pantalla MUST permitir **comparar dos ejecuciones del mismo tipo**: parámetros que difieren, diferencia de cada
  métrica del conjunto completo y casos que empeoraron y que mejoraron, con el valor de cada ejecución. MUST NOT ofrecer
  comparar ejecuciones de tipos distintos.
- **FR-018**: La comparación MUST marcar como **no comparable** el caso cuyo contenido cambió entre las dos ejecuciones, y MUST
  advertir, nombrando qué difiere, cuando difieren el juez, el modelo del asistente, el de embeddings, la configuración de
  recuperación o el dataset (LAB-31, FR-017).
- **FR-019**: La lista de casos que empeoraron entre dos ejecuciones MUST coincidir con la que dan las consultas documentadas del
  historial (`07-regressions.sql`) para los mismos parámetros.
- **FR-020**: El sistema MUST ofrecer a la pantalla una **lectura del historial** de solo lectura (listado de ejecuciones con sus
  medias, detalle de una ejecución con sus parámetros, resumen y resultados), que LAB-31 dejó fuera de alcance y esta historia
  define; MUST NOT exponer la escritura de ejecuciones ni exigir a la pantalla el token de guardado.
- **FR-021**: La pantalla MUST mostrar estados explícitos para cargando, sin ejecuciones (con la explicación de cómo se genera un
  historial), con datos y con error (mensaje comprensible sin detalles internos y reintento), sin quedar cargando
  indefinidamente.
- **FR-022**: La pantalla MUST ser accesible (teclado, etiquetas, estado comunicado también con texto, contraste suficiente) y
  adaptarse a pantallas estrechas sin desplazamiento horizontal de la página.
- **FR-023**: Cuando hay más ejecuciones de las que caben con claridad, la pantalla MUST mostrar por defecto las más recientes,
  permitir ampliar el rango e indicar cuántas muestra y cuántas hay; MUST NOT truncar en silencio.
- **FR-024**: La pantalla MUST ser de **solo lectura**: no lanza mediciones, no edita casos del dataset ni borra ejecuciones, y
  MUST NOT cambiar el asistente, el dataset ni la forma de calcular las métricas (LAB-28, LAB-29, LAB-31).
- **FR-025**: La lógica de lectura y de presentación (qué cuenta como fallo, orden, huecos, comparabilidad, redondeo) MUST poder
  probarse de forma aislada, sin red, sin modelo de lenguaje y sin base de datos real, y la lectura del historial MUST verificarse
  contra una base real en las pruebas de integración del proyecto.
- **FR-026**: La documentación MUST explicar para qué sirve la pantalla, cómo leer la gráfica y el detalle, qué significa un
  hueco o «sin valor», qué es «no comparable» y qué hacer cuando no hay historial, y MUST actualizar la documentación de `eval/`
  para señalar la pantalla como la forma de lectura del historial junto con las consultas SQL.
- **FR-027**: La pantalla MUST funcionar contra el entorno de desarrollo local (`make up`), que es el entorno del equipo, y no
  solo contra servicios aislados de prueba.

### Key Entities *(include if feature involves data)*

- **Ejecución (de evaluación)**: una pasada de la medición de recuperación o de calidad, tal como la guardó LAB-31: identificador,
  tipo, fechas, origen, commit, parámetros con que se midió y número de casos. La pantalla las lista y las ordena en el tiempo.
- **Serie de una métrica**: la secuencia de valores de una métrica del conjunto completo, uno por ejecución del mismo tipo, con el
  número de casos de cada media y huecos donde no hay valor.
- **Resumen de ejecución**: el valor de cada métrica del conjunto completo y por categoría, con su número de casos.
- **Caso que falla**: un caso de una ejecución con alguna métrica por debajo del criterio de fallo, con su contenido tal como se
  midió, sus valores y sus documentos esperados y recuperados.
- **Comparación**: la diferencia entre dos ejecuciones del mismo tipo: parámetros que difieren, diferencia de métricas y
  casos que empeoraron, mejoraron o no son comparables.
- **Traza**: la referencia (identificador y, si se puede, enlace) a la traza del turno en Langfuse, donde viven el prompt, los
  fragmentos y la respuesta.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un administrador que no conoce SQL responde, en menos de 1 minuto desde que abre la pantalla, si la fidelidad de la
  última ejecución mejoró o empeoró respecto de la anterior.
- **SC-002**: Un administrador localiza, en menos de 3 minutos desde que abre la pantalla, los tres casos que peor puntuaron de
  una ejecución dada y con qué versión de prompt y qué modelo se midió.
- **SC-003**: Para el 100 % de las ejecuciones mostradas, los valores de la gráfica y del resumen coinciden con los de su CSV y
  con los de las consultas del historial hasta el cuarto decimal, y el número de casos de cada media coincide.
- **SC-004**: En una verificación manual de cinco casos tomados al azar entre los que fallan, el 100 % coincide con la fila
  equivalente del CSV de detalle (valores, documentos esperados y recuperados), incluidos los valores vacíos.
- **SC-005**: Con dos ejecuciones guardadas (una con un cambio deliberado de prompt), la pantalla señala el prompt que cambió y la
  lista de casos que empeoraron coincide con la de las consultas documentadas en el 100 % de los casos.
- **SC-006**: Con 100 ejecuciones guardadas, la gráfica y el resumen aparecen en menos de 3 segundos en el entorno de desarrollo
  local y el detalle de una ejecución en menos de 2 segundos.
- **SC-007**: En el 100 % de las comprobaciones, ningún valor vacío se muestra ni se dibuja como cero, y ningún dato desconocido
  se muestra con un valor inventado.
- **SC-008**: Ninguna pantalla ni respuesta de lectura contiene el texto de una respuesta del asistente ni el de un fragmento
  recuperado, verificado por una prueba sobre una ejecución real.
- **SC-009**: Todas las funciones de la pantalla (elegir tipo, métricas, origen, ejecución, caso y comparación) se pueden usar solo
  con teclado y no hay scroll horizontal de la página en un ancho de teléfono.
- **SC-010**: Una persona que no conoce el repositorio llega a la pantalla desde la navegación y encuentra su ejecución recién
  guardada siguiendo solo la documentación, en menos de 10 minutos.

## Assumptions

- LAB-13 (aplicación Angular base y navegación) y LAB-31 (persistencia de las ejecuciones de evaluación) están fusionadas en
  `main` y se usan tal cual. Esta historia **solo lee** lo que LAB-31 guarda; no cambia cómo se calculan las métricas, cómo se
  guardan las ejecuciones ni el dataset (FR-024).
- **El historial no tiene todavía una lectura para el frontend**: LAB-31 dejó expresamente fuera de alcance «exponer una API de
  lectura para otros módulos o para el frontend» y delegó su definición en esta historia. Hoy el módulo `eval` solo guarda,
  comprueba la existencia de una ejecución y publica la configuración del asistente, y todo ello protegido por el token de
  escritura. Qué se publica, con qué forma y cómo se paginan las ejecuciones es **decisión de planificación** y se registrará
  en un ADR; esta especificación fija qué se ve y las garantías (FR-020).
- **No hay autenticación ni roles en el proyecto** (igual que en LAB-25, ADR-0008): «administrador» describe al actor, pero la
  pantalla queda abierta en el entorno de desarrollo único, sin simular control de acceso. Es aceptable porque la lectura solo
  expone puntuaciones, identificadores de documentos y contenido sintético del dataset golden (nunca texto de respuestas, claves
  ni tokens). El token de guardado **no** viaja al navegador. El control de acceso se aborda en la fase de seguridad.
- El nombre de la pantalla en Jira es `admin-eval`; su dirección y su etiqueta en la navegación («Evaluación») son decisiones de
  diseño que siguen el patrón de las pantallas existentes.
- Las métricas se muestran **tal como las nombra LAB-31**: `faithfulness`, `answer_relevancy` y `context_precision` en
  calidad; `hit_at_<k>`, `recall_at_<k>` y `reciprocal_rank` en recuperación. Una métrica nueva en el futuro aparece sin
  reescribir las ejecuciones anteriores, que quedan con hueco en esa serie.
- **Valor del conjunto completo** significa la media de la métrica sobre los casos con valor de esa ejecución, ignorando los
  vacíos, exactamente como el resumen del CSV (la fila `all`); no se redefine aquí.
- **Qué es un «caso que falla»** se resolvió con la opción recomendada de la aclaración: umbral elegido por quien mira (valor
  inicial 0,5) en calidad, y ningún documento esperado dentro de los k en recuperación. Es **informativo** de la pantalla: no
  bloquea nada. Los umbrales de bloqueo de una medición son de LAB-30 (evaluación en CI), que no está implementada y queda
  fuera de alcance. Si se prefiere otro criterio, se cambia aquí y en FR-010.
- La **traza en Langfuse** se enlaza si la dirección de Langfuse es conocida por la configuración de la aplicación; si no, se
  muestra el identificador para copiarlo. Esta historia no requiere que Langfuse esté encendido.
- Una ejecución es visible solo cuando está entera (LAB-31, FR-012), así que la pantalla nunca muestra una ejecución parcial.
- **Fuera de alcance**: lanzar mediciones desde la pantalla; editar casos del dataset o prompts; borrar o archivar ejecuciones;
  el feedback 👍/👎 de usuarios (LAB-33); la evaluación en CI, la línea base y los umbrales de bloqueo (LAB-30); la
  autenticación y los roles; exportar la gráfica o los datos; alertas por correo o notificaciones; y cualquier cambio del asistente,
  la búsqueda o las métricas.
