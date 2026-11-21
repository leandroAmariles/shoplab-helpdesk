# Feature Specification: Persistencia de las ejecuciones de evaluación

**Feature Branch**: `feature/LAB-31-eval-run-persistence`

**Created**: 2026-11-20

**Status**: Draft

**Input**: User description: "LAB-31 (F2-05) Persistencia de las ejecuciones de evaluación. Como desarrollador, quiero guardar
cada ejecución de evaluación. Criterios: esquema `eval` (`eval_run`, `eval_case`, `eval_result`); guarda versión de prompt,
modelo y configuración de retrieval." Depende de LAB-30 (evaluación en CI) y LAB-85 (prompts versionados). LAB-85 está
fusionada en `main`; LAB-30 aún no está implementada (ver *Assumptions*).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Guardar cada ejecución con el resultado de cada caso (Priority: P1)

Como desarrollador, quiero que cada ejecución de la evaluación (la medición de recuperación de LAB-28 y la de calidad de
respuesta de LAB-29) quede guardada en la base de datos con el resultado de cada caso del dataset golden, para poder consultar
después qué se midió, cuándo y qué salió, sin depender de los CSV sueltos de mi máquina.

Hoy una ejecución termina en dos archivos CSV locales (`eval/results/`, no versionados). Sirven para mirar una ejecución, pero
no para responder preguntas que importan: ¿cuándo empezó a bajar la fidelidad?, ¿esta pregunta ya fallaba hace tres
ejecuciones?, ¿qué ejecución es la mejor hasta ahora? Esos CSV se pierden al limpiar la carpeta, no se pueden cruzar entre sí y
cada persona tiene los suyos. Guardar la ejecución en un esquema propio (`eval`) convierte la evaluación en un historial.

Se guardan tres cosas, una por cada tabla pedida por el criterio de aceptación:

- **Ejecución** (`eval_run`): una pasada de la medición completa, con su identificador, cuándo empezó y terminó, de qué tipo
  fue (recuperación o calidad de respuesta) y con qué se midió (historia 2).
- **Caso** (`eval_case`): un caso del dataset golden, con su identificador estable (`G001`), su categoría, su pregunta, sus
  documentos esperados y su respuesta de referencia. Es la parte estable: muchas ejecuciones apuntan al mismo caso.
- **Resultado** (`eval_result`): lo que un caso dio en una ejecución. Un resultado pertenece a una ejecución y a un caso, y
  contiene el valor de cada métrica (vacío cuando no aplica o no se pudo calcular por definición, nunca cero), los documentos
  recuperados en orden, el tipo de caso, si el asistente se abstuvo y el identificador de la traza en Langfuse.

**Why this priority**: Es el criterio de aceptación principal (el esquema `eval` con sus tres tablas) y la razón de ser de la
historia. LAB-32 (pantalla `admin-eval`) y la comparación a lo largo del tiempo necesitan este historial como entrada.

**Independent Test**: Con la base de datos disponible y la persistencia activada, ejecutar la medición de calidad sobre el
dataset golden y comprobar que existe una ejecución con un resultado por cada caso medido; ejecutar también la medición de
recuperación y comprobar que queda como otra ejecución; después comprobar a mano tres casos (uno con las tres métricas, uno
sin respuesta con solo fidelidad y uno con una métrica vacía por definición) contra el CSV de esa misma ejecución.

**Acceptance Scenarios**:

1. **Given** el esquema `eval` creado y la persistencia activada, **When** termina una medición de calidad de respuesta,
   **Then** existe una ejecución guardada y un resultado por cada caso medido, con las mismas puntuaciones que el detalle del
   CSV de esa ejecución.
2. **Given** la misma situación con una medición de recuperación, **When** termina, **Then** queda guardada como otra
   ejecución, de tipo recuperación, con hit rate, recall y MRR por caso para cada k pedido.
3. **Given** un caso sin respuesta (el dato no está publicado), **When** se guarda su resultado, **Then** solo tiene
   fidelidad y las métricas que no aplican quedan **vacías**, no en cero.
4. **Given** una puntuación que no se pudo calcular por definición (una respuesta sin afirmaciones que verificar), **When** se
   guarda, **Then** queda vacía y distinguible de un cero real.
5. **Given** dos ejecuciones guardadas sobre el mismo dataset, **When** se consulta un mismo caso, **Then** hay un único caso y
   dos resultados, uno por ejecución, y los de la primera siguen intactos.
6. **Given** una ejecución guardada, **When** se recalcula la media de una métrica a partir de sus resultados ignorando los
   vacíos, **Then** coincide con la fila del conjunto completo del resumen de esa ejecución, y lo mismo por categoría.
7. **Given** los casos de conversación (`small_talk`, `injection`), **When** se guarda la ejecución, **Then** quedan excluidos
   igual que en los CSV y el número de excluidos queda registrado en la ejecución.

---

### User Story 2 - Saber con qué prompt, modelo y recuperación se midió (Priority: P1)

Como desarrollador, quiero que cada ejecución guarde la versión de cada prompt usado, el modelo del asistente y la
configuración de recuperación con que se midió, para poder explicar un cambio de métricas por lo que cambió y no por
adivinación.

Una ejecución guardada sin su contexto no dice nada: «fidelidad 0,71» no significa lo mismo con otro prompt, con otro modelo o
con otro umbral de similitud. Cada ejecución guarda:

- **Prompts**: el nombre y la versión concreta de cada prompt que usó el asistente (LAB-85: el de sistema, el de la charla
  de cortesía y las dos plantillas del mensaje del cliente, con y sin contexto), tal como los resolvió Langfuse, y si se
  fijaron por petición o se usó el de producción.
- **Modelos**: el modelo del asistente, el de embeddings y, en la medición de calidad, el juez con sus ajustes
  (temperatura, semilla, tamaño máximo de entrada), la biblioteca de evaluación y su versión, y la versión de las instrucciones
  de juicio.
- **Configuración de recuperación**: cuántos fragmentos entrega el chat al modelo, el umbral de similitud, el presupuesto de
  contexto, la profundidad pedida a la búsqueda y los valores de k de la medición. Hoy el CSV registra el modelo de embeddings
  pero no estos parámetros; esta historia los hace constar.
- **Dataset y código**: cuántos casos se midieron, excluyeron y quedaron sin puntuar, una huella del dataset golden y, si se
  conoce, el commit del código con que se midió.

**Why this priority**: Es el segundo criterio de aceptación explícito (versión de prompt, modelo y configuración de
retrieval). Sin esto el historial es una lista de números imposibles de interpretar, y LAB-85 ya hizo de la versión del
prompt algo que se puede variar a propósito.

**Independent Test**: Ejecutar la medición de calidad dos veces, la segunda fijando otra versión del prompt de abstención y otro
modelo del asistente, y comprobar que las dos ejecuciones guardadas dicen cuál prompt, cuál versión y cuál modelo usó cada
una, y que una consulta puede encontrar «todas las ejecuciones con el prompt `X` versión `n`».

**Acceptance Scenarios**:

1. **Given** una ejecución que usó el prompt de producción, **When** se guarda, **Then** registra el nombre y la **versión
   numérica concreta** que Langfuse resolvió, no solo la etiqueta `production`.
2. **Given** una ejecución con una versión de prompt fijada por petición, **When** se guarda, **Then** registra esa versión y
   que fue fijada, distinta de la de producción en ese momento.
3. **Given** una ejecución guardada, **When** se lee, **Then** muestra el modelo del asistente, el de embeddings, el juez y sus
   ajustes, la biblioteca y su versión, y los parámetros de recuperación con que se midió.
4. **Given** dos ejecuciones que difieren solo en el modelo del asistente, **When** se comparan sus ejecuciones, **Then** la
   diferencia de parámetros se ve sin leer los CSV ni el código.
5. **Given** una ejecución sobre un dataset al que después se le cambió un caso, **When** se consulta, **Then** la huella del
   dataset y el contenido guardado de cada caso permiten saber qué decía el caso cuando se midió (historia 3).
6. **Given** un parámetro que el job no pudo averiguar (por ejemplo, el commit fuera de un repositorio), **When** se guarda,
   **Then** queda vacío con claridad; nunca se inventa un valor ni se guarda como si lo fuera.

---

### User Story 3 - Comparar ejecuciones y ver qué casos cambiaron (Priority: P1)

Como desarrollador, quiero consultar el historial de ejecuciones y comparar dos de ellas caso a caso, para ver no solo que la
media cambió sino **qué preguntas** empeoraron o mejoraron.

El historial se puede consultar con las consultas documentadas en `eval/` sin escribir SQL desde cero: listar las ejecuciones
por fecha o por tipo, ver una ejecución con sus parámetros y su resumen, ver la evolución de una métrica a lo largo del
tiempo y comparar dos ejecuciones caso a caso. Esta historia **no** construye la pantalla `admin-eval` (LAB-32) ni la
comparación de prompts de LAB-85 por CSV; deja el historial en una forma que ambas puedan leer sin transformarlo.

Un caso del dataset puede editarse con el tiempo (una pregunta reescrita, una respuesta de referencia corregida) conservando su
identificador. Para que una comparación no atribuya a la calidad lo que fue un cambio del caso, lo guardado de un caso es el
contenido **que tenía cuando se midió**: si el contenido cambia, lo medido después apunta a una revisión nueva del caso, y la
comparación advierte los casos cuyas revisiones difieren.

**Why this priority**: Un historial que solo se puede abrir ejecución por ejecución no es mucho más que los CSV. La comparación
caso a caso es lo que lo vuelve útil, y es lo que LAB-30 (diferencia contra la línea base) y LAB-32 (pantalla de
evaluación) van a necesitar.

**Independent Test**: Con dos ejecuciones guardadas (una con un cambio deliberado de prompt), usar las consultas documentadas
para listar ambas, ver los parámetros de cada una, obtener la diferencia de cada métrica y listar los casos cuya fidelidad
bajó más de 0,2; verificar a mano dos de ellos contra los CSV.

**Acceptance Scenarios**:

1. **Given** varias ejecuciones guardadas, **When** se consulta el historial, **Then** se obtienen ordenadas por fecha, con su
   tipo, su identificador, sus parámetros principales (prompt y versión, modelo del asistente) y su media del conjunto completo.
2. **Given** dos ejecuciones del mismo tipo, **When** se comparan, **Then** se obtiene, por caso, el valor de cada ejecución y
   su diferencia, y se pueden filtrar los casos que empeoraron.
3. **Given** dos ejecuciones cuyos modelos, juez, embeddings o dataset difieren, **When** se comparan, **Then** la comparación
   lo advierte nombrando qué difiere (el mismo criterio que LAB-85, FR-022).
4. **Given** un caso cuyo contenido cambió entre las dos ejecuciones, **When** se comparan, **Then** el caso se marca como no
   comparable en lugar de presentar su diferencia como cambio de calidad.
5. **Given** una métrica, **When** se consulta su evolución, **Then** se obtiene el valor del conjunto completo de cada
   ejecución en orden cronológico.
6. **Given** un resultado guardado, **When** se sigue su identificador de traza, **Then** lleva a la traza de Langfuse del
   turno, que es donde viven el prompt, los fragmentos y la respuesta.

---

### User Story 4 - Una ejecución se guarda entera o no se guarda, y nunca por duplicado (Priority: P2)

Como desarrollador, quiero que el guardado sea atómico, opcional y explícito, para no encontrar en el historial ejecuciones a
medias, duplicadas o de mediciones que no fueron válidas, y para que medir en local o en CI sin base de datos siga funcionando
como hoy.

Guardar es **opcional por configuración**: sin base de datos configurada, la medición se comporta como hasta ahora (calcula,
escribe el CSV y dice que no guardó nada), de modo que el CI efímero y las pruebas locales sin servicios no se rompen. Si se
pidió guardar, el sistema se comporta con el mismo principio de LAB-28 y LAB-29: una respuesta mala puntúa bajo y la medición
sigue; una medición que no puede hacerse (o no puede guardarse) se detiene y lo dice, en lugar de dejar un historial que parezca
válido.

**Why this priority**: Es P2 porque con un entorno sano las historias 1 a 3 ya dan valor, pero un historial con ejecuciones
parciales o repetidas contamina toda comparación posterior y haría desconfiar de los números.

**Independent Test**: Ejecutar la medición con la base apagada y la persistencia pedida, con una medición que falla a mitad
(asistente caído) y repetir una ejecución con un identificador ya guardado; comprobar que en ninguno de los tres casos queda una
ejecución a medias o duplicada en la base y que el mensaje explica la causa.

**Acceptance Scenarios**:

1. **Given** que no hay base de datos configurada, **When** se ejecuta la medición, **Then** calcula y escribe los CSV como
   antes y el resultado dice que no se guardó nada en la base.
2. **Given** que se pidió guardar y la base no está disponible o el esquema `eval` no existe, **When** se ejecuta la medición,
   **Then** termina con error que nombra la causa **antes** de consultar al asistente o al juez, para no gastar una ejecución
   que no se podría guardar.
3. **Given** una medición que falla a mitad (asistente caído, juez que no responde, dataset inválido), **When** termina,
   **Then** no queda ninguna ejecución ni resultado parcial en la base, igual que no se escribe CSV.
4. **Given** que el guardado falla al final de una medición válida, **When** termina, **Then** los CSV se escriben completos,
   la base no guarda nada de esa ejecución y el código de salida es distinto de cero, con un mensaje que lo dice.
5. **Given** un identificador de ejecución ya guardado, **When** se pide guardar otra ejecución con ese identificador, **Then**
   termina con error antes de medir y no sobrescribe ni duplica la existente.
6. **Given** una ejecución terminada y guardada, **When** se consulta su estado, **Then** consta como completa; una ejecución
   solo es visible en el historial cuando está entera.
7. **Given** varias ejecuciones concurrentes con identificadores distintos, **When** guardan, **Then** ninguna interfiere con
   las demás.

---

### Edge Cases

- **Dataset editado entre ejecuciones**: un caso conserva su identificador pero cambia su pregunta o su respuesta de referencia.
  Lo medido apunta a la revisión que tenía el caso entonces; la comparación marca esos casos como no comparables (historia 3,
  escenario 4).
- **Caso retirado del dataset**: sus resultados históricos se conservan y siguen apuntando a la revisión con que se midieron; que
  el caso ya no esté en el dataset no borra su historia.
- **Respuesta del asistente muy larga o con datos personales**: la base **no** guarda el texto de las respuestas ni el de los
  fragmentos recuperados; el identificador de traza apunta a Langfuse, que ya está pensado para llevar ese contenido (ADR-0022),
  igual que FR-019 de LAB-29. Solo se guardan puntuaciones, identificadores de documentos del corpus sintético y el contenido
  del dataset golden, que es de prueba.
- **Versión de prompt no resoluble** (Langfuse caído, prompt inexistente): una ejecución que no sabe con qué prompt se midió no
  se guarda como si lo supiera; sigue la regla de LAB-85 (FR-023): falla con error claro en vez de medir y guardar con un valor
  por defecto.
- **Ejecuciones de tipos distintos con el mismo identificador**: la medición de recuperación y la de calidad son dos ejecuciones
  independientes; no comparten identificador implícitamente. Reutilizar uno ya guardado es el error del escenario 5 de la
  historia 4.
- **Datos de prueba mezclados con los reales**: una ejecución lanzada por una prueba automática o por un desarrollador contra su
  máquina no debe confundirse con una de referencia; la ejecución registra quién o qué la lanzó (entorno de origen) para poder
  filtrarla.
- **Crecimiento**: cada ejecución añade del orden de cien filas de resultado. Con una ejecución por PR y por cambio de prompt, el
  volumen es pequeño; no se necesita retención ni archivado, pero tampoco se promete borrado (fuera de alcance).
- **Migración de ejecuciones anteriores**: los CSV ya existentes (`eval/results/`) no se importan; el historial empieza con esta
  historia.
- **Cambio de las métricas en el futuro**: añadir una métrica nueva no debe obligar a reescribir las ejecuciones guardadas; las
  ejecuciones anteriores simplemente no la tienen (vacío), y lo guardado de una métrica lleva su nombre estable.
- **Varias bases**: la ejecución se guarda en la base del entorno en el que se lanzó; guardar en una base distinta a la del
  asistente medido no está soportado. El entorno de origen se registra para no confundir ejecuciones de entornos distintos.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: La base de datos MUST tener un esquema `eval` con las tablas `eval_run`, `eval_case` y `eval_result`, creado por
  migraciones versionadas y propias de ese esquema, que no toca esquemas ajenos ni establece claves foráneas hacia ellos.
- **FR-002**: Cada ejecución de la medición de recuperación (LAB-28) y de la medición de calidad de respuesta (LAB-29) MUST poder
  guardarse como una **ejecución** con su tipo, su identificador, el momento de inicio y de fin y el entorno de origen.
- **FR-003**: Cada **resultado** MUST pertenecer a una ejecución y a un caso, y MUST guardar el valor de cada métrica de su
  tipo de ejecución, con **vacío** (no cero) cuando la métrica no aplica o no se pudo calcular por definición, los
  identificadores de los documentos recuperados en el orden en que se entregaron, el tipo de caso, si el asistente se abstuvo y
  el identificador de la traza de Langfuse cuando existe.
- **FR-004**: Cada **caso** MUST guardar el contenido del dataset golden que se midió: identificador estable, categoría,
  pregunta, documentos esperados y respuesta de referencia. Si el contenido de un caso cambia, lo medido a partir de entonces
  MUST apuntar a una revisión nueva y las ejecuciones anteriores MUST seguir apuntando a la revisión con que se midieron.
- **FR-005**: Cada ejecución MUST guardar el **nombre y la versión numérica concreta** de cada prompt usado por el asistente
  (LAB-85), y si la versión se fijó por petición o se resolvió desde la etiqueta de producción.
- **FR-006**: Cada ejecución MUST guardar el **modelo del asistente** y el **modelo de embeddings** con que se midió y, en las
  de calidad, el juez con sus ajustes (modelo, temperatura, semilla, tamaño máximo de entrada), la biblioteca de evaluación y su
  versión, y la versión de las instrucciones de juicio.
- **FR-007**: Cada ejecución MUST guardar la **configuración de recuperación** con que se midió: fragmentos entregados al
  modelo, umbral de similitud, presupuesto de contexto, profundidad pedida a la búsqueda y valores de k, tal como los usó el
  asistente medido, no los valores por defecto de la herramienta.
- **FR-008**: Cada ejecución MUST guardar el número de casos medidos, excluidos y sin puntuar por métrica, una huella del
  dataset golden y, si se conoce, el commit del código con que se midió. Lo que no se pudo averiguar MUST quedar vacío y
  distinguible, nunca inventado.
- **FR-009**: La base MUST NOT guardar el texto de las respuestas del asistente ni el de los fragmentos recuperados, ni claves,
  tokens o direcciones internas; ese contenido vive solo en las trazas de Langfuse.
- **FR-010**: El guardado MUST ser **opcional por configuración**: sin base configurada, la medición MUST comportarse como hasta
  ahora (calcular, escribir los CSV y decir que no se guardó nada).
- **FR-011**: Si se pidió guardar, la medición MUST comprobar antes de consultar al asistente o al juez que la base está
  disponible, que el esquema `eval` existe y que el identificador de ejecución no está ya guardado, y MUST terminar con error
  que nombra la causa si alguna de las tres falla.
- **FR-012**: Una ejecución MUST guardarse **entera o no guardarse**: ni una medición que falla, ni un guardado que falla dejan
  una ejecución parcial visible en el historial. Una ejecución solo MUST ser visible cuando está completa.
- **FR-013**: Un identificador de ejecución ya guardado MUST NOT sobrescribirse ni duplicarse; es un error de uso que se detecta
  antes de medir.
- **FR-014**: Si el guardado falla después de una medición válida, los CSV MUST escribirse completos, la base MUST no quedar con
  datos de esa ejecución y el código de salida MUST ser distinto de cero, con un mensaje que lo indique.
- **FR-015**: Los valores guardados de una ejecución MUST ser **idénticos** a los de sus CSV (resumen y detalle): las medias por
  ámbito recalculadas desde los resultados MUST reproducir el resumen, y el número de casos que entró en cada media MUST
  coincidir.
- **FR-016**: Las consultas del historial MUST estar documentadas en `eval/` con ejemplos que cubran: listar ejecuciones,
  ver una ejecución con sus parámetros y su resumen, evolución de una métrica, comparar dos ejecuciones caso a caso y buscar
  ejecuciones por prompt, versión o modelo.
- **FR-017**: La comparación de dos ejecuciones MUST advertir cuando difieren el juez, el modelo del asistente, el modelo de
  embeddings, la configuración de recuperación o el contenido de un caso, para no atribuir a la calidad lo que cambió en la
  vara de medir.
- **FR-018**: Guardar MUST NOT cambiar el comportamiento del asistente, el dataset ni la forma de calcular las métricas (LAB-28 y
  LAB-29); registra lo que esas mediciones producen, tal cual.
- **FR-019**: La traducción de una ejecución a lo que se guarda (reparto de vacíos, revisión de caso, atomicidad, identificador
  repetido, parámetros ausentes) MUST poder probarse de forma aislada, sin red, sin modelo de lenguaje y sin base de datos real,
  y el esquema y su aislamiento MUST verificarse contra una base real en las pruebas de integración del proyecto.
- **FR-020**: La documentación de `eval/` MUST explicar qué se guarda y qué no, cómo activar el guardado, cómo se identifica una
  ejecución, cómo leer y comparar el historial, y qué hacer ante cada error, de modo que otra persona pueda usarlo sin leer el
  código.
- **FR-021**: El guardado MUST funcionar contra la base del entorno de desarrollo local (`make up`), que es el entorno del
  equipo, y no solo contra un `docker compose` aislado.

### Key Entities *(include if feature involves data)*

- **Ejecución (`eval_run`)**: una pasada de la medición de recuperación o de calidad con su identificador, tipo, momento,
  entorno de origen y todo lo que la hace comparable: prompts y versiones, modelos, juez, configuración de recuperación, huella
  del dataset, número de casos y commit. Es completa o no existe.
- **Caso (`eval_case`)**: un caso del dataset golden con su identificador estable y el contenido de cada revisión que se midió
  (categoría, pregunta, documentos esperados, respuesta de referencia). Muchos resultados apuntan al mismo caso.
- **Resultado (`eval_result`)**: lo que un caso dio en una ejecución: valor de cada métrica (o vacío), documentos recuperados en
  orden, tipo de caso, abstención e identificador de traza. Es la fila de la que se explica cualquier número del resumen.
- **Parámetro de comparabilidad**: cualquier dato de la ejecución cuya diferencia entre dos ejecuciones puede explicar un cambio
  de métricas sin que cambie la calidad del asistente (juez, modelo, embeddings, recuperación, dataset).
- **Historial de evaluación**: el conjunto ordenado de ejecuciones completas de un esquema `eval`, consultable por fecha, tipo,
  prompt, versión o modelo.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con la base disponible y la persistencia activada, una única ejecución de cada tipo deja guardada su ejecución y
  un resultado por cada caso medido (96 de conocimiento en el dataset actual), sin pasos manuales, y el guardado añade menos de
  un minuto al tiempo total de la medición.
- **SC-002**: Para el 100 % de las ejecuciones guardadas, las medias por ámbito recalculadas desde sus resultados, ignorando los
  vacíos, coinciden con las del resumen del CSV de esa ejecución hasta el cuarto decimal, y el número de casos de cada media
  coincide.
- **SC-003**: En una verificación manual de cinco resultados tomados al azar, el 100 % coincide con la fila equivalente del CSV de
  detalle, incluidos los valores vacíos.
- **SC-004**: Dada una ejecución guardada, una persona responde en menos de cinco minutos y sin leer el código a: con qué
  versión de qué prompt, con qué modelo y con qué configuración de recuperación se midió.
- **SC-005**: En el 100 % de las pruebas con la base apagada, el esquema ausente, un identificador repetido, una medición que
  falla a mitad y un guardado que falla al final, la base no contiene ninguna ejecución parcial ni duplicada.
- **SC-006**: Con la persistencia desactivada, las mediciones de LAB-28 y LAB-29 producen los mismos CSV y el mismo código de
  salida que antes de esta historia, en el 100 % de las pruebas existentes.
- **SC-007**: Con dos ejecuciones guardadas (una con un cambio deliberado de prompt), las consultas documentadas listan los casos
  que empeoraron y esa lista coincide con la que se obtiene comparando a mano los dos CSV en el 100 % de los casos.
- **SC-008**: Ninguna fila de la base contiene el texto de una respuesta ni el de un fragmento recuperado, verificado por una
  prueba sobre una ejecución real.
- **SC-009**: Una persona que no conoce el repositorio activa el guardado, ejecuta una medición y encuentra su ejecución en el
  historial siguiendo solo la documentación, en menos de 15 minutos.

## Assumptions

- LAB-27 (dataset golden), LAB-28 (métricas de recuperación), LAB-29 (métricas de calidad de respuesta), LAB-64 (trazas de
  Langfuse) y LAB-85 (prompts versionados) están fusionadas en `main` y se usan tal cual. Esta historia **solo guarda** lo que
  esas mediciones ya producen (FR-018); no cambia cómo se calculan.
- **LAB-30 (evaluación en CI) no está implementada**; Jira la lista como dependencia. Esta historia no la necesita para
  funcionar: guarda las ejecuciones de las mediciones que hoy se lanzan con un comando, y cuando LAB-30 exista podrá guardar las
  suyas con el mismo mecanismo. Lo que no se resuelve aquí es **dónde** guarda una ejecución de CI efímero: con base no
  configurada se comporta como hasta ahora (FR-010), y dónde se configura una base persistente alcanzable desde CI es una
  decisión de LAB-30. Si LAB-30 se especifica antes, esta suposición se reconcilia con ella.
- La **regla del proyecto** es un esquema de PostgreSQL por módulo, con migraciones propias, sin claves foráneas ni `JOIN`
  entre esquemas (ADR-0015). El esquema `eval` la sigue. Quién escribe en él —la herramienta Python de `eval/` con credenciales
  propias, o el backend mediante un módulo `eval` que recibe las ejecuciones por una interfaz, como `ingest` entrega su salida a
  `retrieval` sin credenciales de base de datos (ADR-0017)— es una **decisión de arquitectura** para la planificación y se
  registrará en un ADR; esta especificación fija qué se guarda y qué garantías da, no por dónde se escribe.
- La base de **desarrollo es el entorno local del equipo** (`make up`): el guardado debe funcionar contra ella y el esquema
  `eval` debe aparecer allí con las migraciones. Si eso exige infraestructura (credenciales, un secreto, acceso desde el
  cliente de evaluación), **se hace dentro de esta historia**, igual que en LAB-29.
- Se guarda el contenido del dataset golden (preguntas, respuestas de referencia, documentos esperados) porque es sintético y de
  prueba, no de clientes (Constitución VI); no se guarda el texto de lo que respondió el asistente ni el de lo que recuperó
  (FR-009), que sí puede reflejar datos más variados y vive en las trazas.
- La ejecución de recuperación y la de calidad son **ejecuciones separadas** porque hoy son dos comandos con salidas
  independientes. Cómo se agrupan para una vista conjunta (p. ej. las seis métricas de LAB-30 sobre el mismo commit) es una
  decisión de LAB-30 y LAB-32 y puede apoyarse en el commit y el entorno de origen que esta historia guarda.
- Los valores vacíos conservan el significado de LAB-29: vacío = no aplica o no se pudo calcular por definición; cero = el caso
  puntuó cero. Una puntuación que no se pudo calcular por un fallo del juez no es una puntuación: la ejecución entera falla
  (historia 4).
- La huella del dataset y la identificación de la revisión de un caso se calculan sobre el contenido que se midió; qué función se
  usa y cómo se representa es decisión de planificación.
- No hay retención, archivado ni borrado de ejecuciones en esta historia: se conservan indefinidamente. Si el volumen lo pide
  algún día, será otra historia.
- **Fuera de alcance**: la pantalla `admin-eval` (LAB-32); el feedback de usuarios (LAB-33); la evaluación en CI y la
  comparación con la línea base (LAB-30); importar los CSV anteriores; cambiar métricas, dataset, asistente o búsqueda;
  guardar el texto de respuestas o fragmentos; y exponer una API de lectura para otros módulos o para el frontend (si LAB-32
  la necesita, la define allí).
