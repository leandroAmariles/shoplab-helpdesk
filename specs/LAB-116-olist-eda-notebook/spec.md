# Feature Specification: Análisis exploratorio de Olist con Pandas y NumPy

**Feature Branch**: `feature/LAB-116-olist-eda-notebook`

**Created**: 2026-11-20

**Status**: Draft

**Input**: User description: "LAB-116 (F8-01) Análisis exploratorio de Olist con Pandas y NumPy. Como estudiante, quiero un
análisis exploratorio de los datos de Olist, para conocer su calidad y validar los valores que usan el corpus y las
herramientas del chat. Criterios: notebook en `ml/notebooks/` que lee los esquemas `orders` y `catalog` con Pandas (sin
copiar CSV al repo); nulos, duplicados, tipos y rangos por tabla con operaciones vectorizadas de NumPy (sin bucles por
fila); valores reales y frecuencia de `payment_type` y `order_status`, contrastados con los de las políticas (LAB-18) y los
estados de la consulta de pedidos (LAB-41); distribución de tiempos de entrega (real frente a estimado), retrasos por
estado y categoría y puntuación de reseñas; conclusiones en Markdown con las discrepancias convertidas en tareas o ajustes
del corpus; el notebook se ejecuta de principio a fin en CI (`nbconvert --execute`) sin errores. Funciona en local contra el
Postgres de docker compose. Depende de LAB-17." Primera historia de la épica F8 · ML y MLOps (LAB-115).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Conocer la calidad de cada tabla de Olist (Priority: P1)

Como estudiante, quiero ver, tabla por tabla, cuántos nulos, duplicados y qué tipos y rangos tienen los datos cargados en
los esquemas `orders` y `catalog`, para saber en qué datos puedo confiar antes de construir nada encima (el chat, las
herramientas y, más adelante, los modelos de F8).

El notebook lee las ocho tablas ya cargadas en la base de datos local (cinco de `orders`: clientes, pedidos, ítems, pagos y
reseñas; tres de `catalog`: productos, categorías y vendedores) y muestra, para cada una, un perfil de calidad: número de
filas, nulos por columna (cantidad y porcentaje), duplicados, tipo de cada columna y rango (mínimo, máximo y percentiles) de
las columnas numéricas y de fecha. Los cálculos son vectorizados: ninguno recorre las filas una a una.

**Why this priority**: Es la base de todo el análisis y el primer criterio de aceptación: sin conocer la calidad de las
tablas, el resto de hallazgos (valores, entregas, reseñas) no se pueden interpretar. Por sí sola ya entrega valor: un
mapa de dónde hay huecos y anomalías.

**Independent Test**: Con la base local cargada por LAB-17, ejecutar solo la sección de perfilado y comprobar que muestra
un perfil por cada una de las ocho tablas con nulos, duplicados, tipos y rangos, y que ninguna celda recorre filas con un
bucle.

**Acceptance Scenarios**:

1. **Given** la base local con `orders` y `catalog` cargados, **When** se ejecuta el perfilado, **Then** se obtiene un perfil
   para cada una de las ocho tablas, con su número de filas.
2. **Given** una tabla con columnas que admiten nulos (p. ej. las fechas de aprobación y entrega de un pedido), **When** se
   muestra su perfil, **Then** cada columna indica cuántos nulos tiene y qué porcentaje de las filas representan, y distingue
   los nulos esperados por el negocio (un pedido no entregado no tiene fecha de entrega) de los que no lo son.
3. **Given** una tabla con clave natural (p. ej. `order_id` + `payment_sequence`, o `review_id`, que no es único en el
   origen), **When** se calculan los duplicados, **Then** se informa cuántas filas están repetidas según esa clave y cuántas
   según la fila completa, para no confundir un duplicado real con una clave que el origen no garantiza única.
4. **Given** las columnas numéricas y de fecha, **When** se muestra su rango, **Then** aparecen mínimo, máximo y percentiles
   clave, y se señalan valores fuera de lo plausible (p. ej. precio o peso en cero, fechas imposibles).
5. **Given** que `catalog` y `orders` no tienen claves foráneas entre sí (LAB-17), **When** se cruzan por identificador,
   **Then** el notebook informa cuántos ítems de pedido apuntan a un producto o vendedor que no existe en `catalog`.
6. **Given** el código del perfilado, **When** se revisa, **Then** ningún cálculo recorre filas de una en una; todos usan
   operaciones sobre columnas completas.

---

### User Story 2 - Validar los valores reales de pago y estado contra lo que dicen las políticas (Priority: P1)

Como estudiante, quiero ver qué valores existen realmente en `payment_type` y `order_status`, con qué frecuencia, y
contrastarlos con los que mencionan las políticas del corpus y con los estados que mostrará la consulta de pedidos, para
detectar valores que el corpus o las herramientas del chat no cubren (o que cubren pero no existen).

El notebook obtiene la lista de valores distintos de cada columna con su frecuencia absoluta y relativa, extrae los valores
que citan las políticas de `data/kb` (los tipos de pago y la tabla de estados del pedido) y los estados que usará la
consulta de pedidos de LAB-41, y presenta la comparación en ambos sentidos: valores de los datos que ninguna política ni la
herramienta mencionan, y valores mencionados que no aparecen en los datos.

**Why this priority**: Es el criterio que justifica el análisis («validar los valores que usan el corpus y las
herramientas del chat»): si una política habla de un tipo de pago que no existe, o los datos tienen un estado del que nadie
habla, el chat responderá mal. Es independiente del perfilado y entrega valor por sí sola.

**Independent Test**: Ejecutar solo la sección de valores y comprobar que hay una tabla de frecuencias para
`payment_type` y otra para `order_status`, y que cada valor aparece marcado como mencionado o no por las políticas y por la
consulta de pedidos, con los valores mencionados que no existen en los datos listados aparte.

**Acceptance Scenarios**:

1. **Given** la tabla de pagos, **When** se calculan los valores de `payment_type`, **Then** se listan todos los valores
   distintos con su número de pagos y su porcentaje, ordenados por frecuencia, incluidos los poco frecuentes o atípicos.
2. **Given** la tabla de pedidos, **When** se calculan los valores de `order_status`, **Then** se listan todos los estados
   distintos con su número de pedidos y su porcentaje, ordenados por frecuencia.
3. **Given** las políticas de pagos y de seguimiento del corpus (LAB-18), **When** se extraen los tipos de pago y los estados
   que mencionan, **Then** el notebook marca, para cada valor de los datos, si alguna política lo cita.
4. **Given** los estados que mostrará la consulta de pedidos (LAB-41), **When** se contrastan con `order_status`, **Then** se
   marca cada estado de los datos como cubierto o no cubierto por esa consulta y se listan los estados de la consulta que no
   existen en los datos.
5. **Given** un valor presente en los datos y ausente en las políticas (p. ej. un tipo de pago minoritario), **When** se
   revisa la comparación, **Then** aparece como discrepancia con su frecuencia, para valorar si merece documentarse.
6. **Given** un valor citado por una política que no existe en los datos, **When** se revisa la comparación, **Then**
   aparece como discrepancia en sentido contrario.

---

### User Story 3 - Entender los tiempos de entrega, los retrasos y las reseñas (Priority: P2)

Como estudiante, quiero ver cuánto tardan realmente las entregas frente a lo prometido, dónde se retrasan más y qué
puntuación dan los clientes, para conocer el comportamiento real del negocio que simulan los datos y tener una primera
intuición de qué factores se relacionan con el retraso (insumo para los modelos de F8).

El notebook calcula, para los pedidos entregados, el tiempo de entrega real (de la compra a la entrega al cliente) y el
estimado (de la compra a la fecha estimada), y muestra su distribución y la diferencia entre ambos. Resume los retrasos por
estado geográfico del cliente y por categoría de producto, y la distribución de la puntuación de las reseñas (1 a 5),
incluida su relación con el retraso.

**Why this priority**: Es el segundo gran bloque del análisis, pero depende de haber entendido la calidad de las fechas
(historia 1). Aporta conocimiento de negocio y preparación para el modelado, sin bloquear la validación de valores.

**Independent Test**: Ejecutar solo la sección de entregas y reseñas y comprobar que muestra la distribución de tiempos real
y estimado, una tabla de retrasos por estado y otra por categoría, y el histograma de puntuaciones con su relación con el
retraso.

**Acceptance Scenarios**:

1. **Given** los pedidos con fecha de entrega al cliente, **When** se calcula el tiempo de entrega real y el estimado,
   **Then** se muestra su distribución (media, mediana, percentiles y forma) y la de su diferencia, y se informa qué
   fracción llegó antes, a tiempo o después de lo estimado.
2. **Given** pedidos sin fecha de entrega (no entregados o cancelados), **When** se calculan los tiempos, **Then** quedan
   fuera del cálculo y el notebook indica cuántos son y por qué.
3. **Given** pedidos con fechas incoherentes (entrega anterior a la compra, por ejemplo), **When** se calculan los tiempos,
   **Then** se cuentan y se excluyen del análisis con su número a la vista, sin ocultarlos.
4. **Given** el retraso de cada pedido entregado, **When** se resume por estado geográfico del cliente, **Then** se ve por
   estado el número de pedidos, el retraso medio y el porcentaje con retraso, y se identifican los estados con peor
   comportamiento.
5. **Given** el retraso y la categoría de los productos del pedido, **When** se resume por categoría, **Then** se ve por
   categoría el número de pedidos, el retraso medio y el porcentaje con retraso, con un criterio declarado para los pedidos
   con productos de varias categorías y para las categorías con muy pocos pedidos.
6. **Given** las reseñas, **When** se calcula su puntuación, **Then** se muestra cuántas hay de cada puntuación (1 a 5) y
   cómo cambia la puntuación media entre pedidos con y sin retraso.

---

### User Story 4 - Obtener conclusiones y discrepancias convertidas en acciones (Priority: P2)

Como estudiante, quiero que el notebook cierre con conclusiones en prosa y con cada discrepancia encontrada convertida en
una tarea o un ajuste del corpus, para que el análisis no se quede en una observación sino que alimente el trabajo
siguiente.

Al final del notebook, una sección en Markdown resume los hallazgos principales de calidad, de valores y de entregas, y
tiene una tabla de discrepancias: qué se encontró, dónde (qué tabla, política o historia), qué impacto tiene en el chat y qué
acción se propone (un ajuste del corpus, una tarea o una nota para una historia concreta como LAB-41).

**Why this priority**: Es el criterio de cierre y el que convierte el análisis en trabajo accionable, pero necesita los
hallazgos de las historias 1 a 3 para existir.

**Independent Test**: Leer la sección final del notebook ya ejecutado y comprobar que resume los hallazgos de las tres
áreas, que cada discrepancia detectada en las secciones anteriores figura en la tabla con una acción propuesta, y que no hay
ninguna sin acción.

**Acceptance Scenarios**:

1. **Given** las secciones de calidad, valores y entregas, **When** se lee la conclusión, **Then** resume los hallazgos más
   importantes de cada una con las cifras que los respaldan.
2. **Given** cada discrepancia detectada (valores sin cobertura, nulos inesperados, anomalías de fechas, referencias
   huérfanas), **When** se revisa la tabla de discrepancias, **Then** figura con su origen, su impacto y una acción
   propuesta (ajuste del corpus, tarea nueva o nota para otra historia).
3. **Given** una discrepancia que afecta a una historia pendiente (p. ej. los estados de LAB-41), **When** se lee su fila,
   **Then** indica la historia afectada y qué debe tener en cuenta.
4. **Given** el análisis sin discrepancias en un área, **When** se lee la conclusión, **Then** lo dice de forma explícita en
   lugar de omitir el área.
5. **Given** la conclusión, **When** se lee, **Then** declara sus límites (el dataset es un marketplace brasileño de
   2016–2018, el stock es sintético, los hallazgos son descriptivos y no demuestran causas) y deja claro que esta historia no
   modifica el corpus ni los datos, solo propone los cambios.

---

### User Story 5 - Ejecutarlo en local, leerlo sin ejecutarlo y validarlo en CI (Priority: P2)

Como estudiante, quiero poder ejecutar el notebook en mi máquina contra la base de datos de docker compose, poder leer sus
resultados sin ejecutarlo y tener la garantía de que sigue ejecutándose sin errores en integración continua, para repetir el
análisis y estar seguro de que no se rompe cuando cambian los datos o el código.

**Why this priority**: La reproducibilidad es parte de los criterios de aceptación, pero el contenido (historias 1 a 4) ya
tiene valor aunque se ejecute a mano. Es necesaria para cerrar la historia.

**Independent Test**: Desde un clon limpio, con la base local cargada, seguir las instrucciones del README, ejecutar el
notebook de arriba abajo sin editar código, y comprobar que en CI el mismo notebook se ejecuta sin errores.

**Acceptance Scenarios**:

1. **Given** un clon limpio con la base local levantada y Olist cargado, **When** se siguen las instrucciones, **Then** el
   notebook se ejecuta de arriba abajo sin editar código, rutas ni credenciales.
2. **Given** el notebook guardado en el repositorio, **When** se abre sin ejecutarlo, **Then** ya muestra las tablas, los
   gráficos y las conclusiones de la última ejecución.
3. **Given** la base de datos inalcanzable, sin los esquemas o con tablas vacías, **When** se ejecuta el notebook, **Then**
   se detiene al inicio con un mensaje que dice qué falta y cómo resolverlo, sin mostrar resultados parciales.
4. **Given** un pull request que cambia el notebook o los datos de los que depende, **When** corre la integración continua,
   **Then** el notebook se ejecuta de principio a fin y el pull request falla si alguna celda da error.
5. **Given** el repositorio, **When** se revisa, **Then** no contiene ningún CSV de Olist ni copia de sus datos: el notebook
   los lee siempre de la base de datos.
6. **Given** dos ejecuciones sobre los mismos datos, **When** se comparan los resultados, **Then** coinciden.

---

### Edge Cases

- **Base de datos no disponible o sin datos**: el notebook lo detecta antes de calcular nada y se detiene con un mensaje
  accionable (levantar docker compose, arrancar el backend para aplicar migraciones, cargar Olist con el cargador de
  LAB-17); nunca muestra resultados vacíos como si fueran válidos.
- **Nulos esperados frente a inesperados**: un pedido cancelado no tiene fecha de entrega y un producto puede no tener
  categoría; el notebook no los presenta como fallos de calidad, los distingue.
- **Duplicados legítimos**: `review_id` no es único en el origen y un pedido puede tener varios pagos; se mide el duplicado
  respecto a la clave natural correcta de cada tabla y respecto a la fila completa, y se explica la diferencia.
- **Referencias huérfanas entre esquemas**: al no haber claves foráneas entre `orders` y `catalog`, un ítem puede apuntar a
  un producto inexistente; se cuenta y se informa, no se corrige.
- **Fechas incoherentes**: entregas anteriores a la compra, aprobaciones anteriores a la compra o fechas estimadas
  anteriores a la compra; se cuentan, se excluyen de las distribuciones y se listan en la tabla de discrepancias.
- **Pedidos de varias categorías o varios pagos**: para el retraso por categoría se declara cómo se asigna un pedido con
  productos de varias categorías; para los pagos, un pedido puede tener varios `payment_type` y se cuenta por pago, no por
  pedido, y así se indica.
- **Categorías con muy pocos pedidos**: se excluyen del ranking de retrasos o se agrupan bajo un umbral mínimo declarado,
  para que un solo pedido no parezca una tendencia.
- **Categorías sin traducción**: se muestran con su nombre original y se señala cuántas carecen de traducción.
- **Valores de pago o estado fuera de las políticas**: aparecen en la comparación con su frecuencia, aunque sean
  marginales; se decide caso por caso en las conclusiones si son ruido o requieren documentación.
- **Estados de LAB-41 aún sin implementar**: LAB-41 está pendiente; el contraste se hace con los estados definidos para esa
  consulta en la tabla de seguimiento del corpus y en su historia, y la discrepancia se anota como insumo para cuando se
  implemente.
- **Datos reales y licencia**: el dataset es público y anonimizado (CC BY-NC-SA 4.0, no se versiona); las salidas guardadas
  del notebook contienen agregados y muestras mínimas, sin volcar el dataset ni exponer identificadores de forma masiva.
- **Datos en CI**: la integración continua no puede depender de descargar el dataset completo; ejecuta el notebook sobre los
  datos de que dispone el pipeline y el notebook debe comportarse correctamente también con un volumen pequeño (sin
  divisiones por cero ni gráficos vacíos).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio MUST incluir un notebook de análisis exploratorio en `ml/notebooks/`, guardado con las salidas
  de su última ejecución.
- **FR-002**: El notebook MUST leer los datos directamente de los esquemas `orders` y `catalog` de la base de datos local, y
  MUST NOT leer, copiar ni incluir CSV del dataset en el repositorio.
- **FR-003**: El notebook MUST cubrir las ocho tablas de los dos esquemas: `customers`, `orders`, `order_items`,
  `order_payments` y `order_reviews` (`orders`); `product_categories`, `products` y `sellers` (`catalog`).
- **FR-004**: Para cada tabla, el notebook MUST mostrar el número de filas, los nulos por columna (cantidad y porcentaje), los
  duplicados, el tipo de cada columna y el rango (mínimo, máximo y percentiles) de las columnas numéricas y de fecha.
- **FR-005**: Los cálculos de perfilado MUST ser vectorizados con operaciones de NumPy y Pandas sobre columnas completas, y
  MUST NOT recorrer las filas una a una.
- **FR-006**: El notebook MUST calcular los duplicados respecto a la clave natural de cada tabla y respecto a la fila
  completa, e indicar la diferencia cuando la clave no sea única en el origen.
- **FR-007**: El notebook MUST distinguir los nulos esperados por el negocio de los inesperados, declarando el criterio.
- **FR-008**: El notebook MUST informar cuántos ítems de pedido referencian un producto o un vendedor inexistente en
  `catalog` (integridad entre esquemas sin clave foránea).
- **FR-009**: El notebook MUST listar todos los valores distintos de `payment_type` y de `order_status` con su frecuencia
  absoluta y relativa.
- **FR-010**: El notebook MUST contrastar esos valores con los tipos de pago y los estados de pedido que mencionan las
  políticas del corpus (`data/kb`) y con los estados que mostrará la consulta de pedidos (LAB-41), en ambos sentidos:
  valores de los datos no mencionados y valores mencionados que no existen en los datos.
- **FR-011**: El notebook MUST calcular el tiempo de entrega real y el estimado de los pedidos entregados, mostrar su
  distribución y la de su diferencia, e informar la fracción que llegó antes, a tiempo o después de lo estimado.
- **FR-012**: El notebook MUST excluir del cálculo de tiempos los pedidos sin fecha de entrega y los de fechas incoherentes,
  e informar cuántos son y por qué.
- **FR-013**: El notebook MUST resumir los retrasos por estado geográfico del cliente y por categoría de producto
  (pedidos, retraso medio y porcentaje con retraso), con un criterio declarado para pedidos con varias categorías y un umbral
  mínimo de pedidos para las categorías.
- **FR-014**: El notebook MUST mostrar la distribución de la puntuación de las reseñas (1 a 5) y su relación con el retraso
  de la entrega.
- **FR-015**: El notebook MUST terminar con una sección de conclusiones en Markdown que resuma los hallazgos de calidad, de
  valores y de entregas, con las cifras que los respaldan.
- **FR-016**: La sección de conclusiones MUST incluir una tabla de discrepancias en la que cada una indica su origen, su
  impacto en el chat y una acción propuesta (ajuste del corpus, tarea nueva o nota para otra historia); ninguna discrepancia
  detectada MUST quedar sin acción.
- **FR-017**: Las conclusiones MUST declarar los límites del análisis (descriptivo, no causal; dataset de 2016–2018; stock
  sintético) y MUST dejar claro que esta historia no modifica el corpus, los datos ni el código de producción.
- **FR-018**: El notebook MUST comprobar al inicio que la base de datos es alcanzable y que los esquemas contienen datos, y,
  si no, detenerse con un mensaje que indique qué falta y cómo resolverlo, antes de calcular nada.
- **FR-019**: El notebook MUST ejecutarse de arriba abajo en local contra la base de datos de docker compose, sin editar
  código, rutas ni credenciales; los parámetros de conexión se toman de la configuración local ya existente.
- **FR-020**: La integración continua MUST ejecutar el notebook de principio a fin (`nbconvert --execute`) en los pull
  requests que lo afecten, y MUST fallar si alguna celda produce un error.
- **FR-021**: Un README junto al notebook MUST explicar qué necesita, cómo ejecutarlo desde un clon limpio, cómo leer los
  resultados y cómo se valida en CI.
- **FR-022**: Las dependencias del análisis MUST declararse y poder instalarse con un solo comando, de forma aislada de
  `ingest/`, `eval/`, `notebooks/` y el backend.
- **FR-023**: El código de producción (`backend/`, `ingest/`) MUST NOT importar nada de `ml/`.
- **FR-024**: El notebook y sus salidas MUST NOT contener secretos, credenciales ni volcados masivos de datos: solo
  agregados y muestras mínimas.
- **FR-025**: Esta historia MUST NOT modificar los esquemas `orders` y `catalog`, el cargador de LAB-17, el corpus de
  `data/kb` ni las herramientas del chat; solo analiza y propone.

### Key Entities *(include if feature involves data)*

- **Tabla analizada**: una de las ocho tablas de `orders` y `catalog`. Se perfila con su número de filas, sus columnas y sus
  claves naturales.
- **Perfil de calidad**: resumen de una tabla con nulos por columna (cantidad y porcentaje), duplicados, tipo y rango de cada
  columna, y las anomalías detectadas.
- **Valor categórico observado**: un valor distinto de `payment_type` o `order_status` con su frecuencia absoluta y relativa
  y su cobertura (si lo mencionan las políticas y la consulta de pedidos).
- **Tiempo de entrega**: de un pedido entregado, el tiempo real (de la compra a la entrega al cliente), el estimado (de la
  compra a la fecha estimada) y su diferencia, que define si hubo retraso.
- **Resumen de retraso**: agregado de pedidos entregados por estado geográfico o por categoría, con número de pedidos,
  retraso medio y porcentaje con retraso.
- **Discrepancia**: hallazgo en que el dato real y lo que dice el corpus o la herramienta del chat no coinciden, o anomalía
  de calidad; lleva origen, impacto y acción propuesta.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El análisis perfila las 8 de 8 tablas de los dos esquemas, y cada perfil muestra nulos, duplicados, tipos y
  rangos.
- **SC-002**: Las tablas de frecuencias de `payment_type` y `order_status` incluyen el 100 % de los valores distintos
  presentes en los datos, cada uno marcado como mencionado o no por las políticas y por la consulta de pedidos, y los
  valores mencionados sin presencia en los datos aparecen aparte.
- **SC-003**: El 100 % de las discrepancias detectadas en el notebook figura en la tabla final con origen, impacto y acción
  propuesta; ninguna queda sin acción.
- **SC-004**: El notebook se ejecuta de principio a fin sin errores en integración continua en cada pull request que lo
  afecte, y un error provocado a propósito en una celda hace fallar el pull request.
- **SC-005**: Una persona que no ha escrito el notebook, siguiendo solo el README, lo ejecuta de arriba abajo en local sin
  editar código en menos de 10 minutos desde un clon limpio con la base local ya cargada.
- **SC-006**: Al abrir el notebook sin ejecutarlo se ven las tablas, los gráficos y las conclusiones de la última ejecución.
- **SC-007**: Ninguna operación de perfilado recorre filas una a una: una revisión del código del notebook no encuentra
  bucles por fila en los cálculos de calidad.
- **SC-008**: Dos ejecuciones consecutivas sobre los mismos datos producen las mismas cifras.
- **SC-009**: El repositorio no contiene ningún CSV de Olist ni copia de sus datos tras la historia.

## Assumptions

- **Destinatario**: el «estudiante» es quien explora este laboratorio; el notebook es material de aprendizaje y de apoyo a
  decisiones futuras, no una funcionalidad de la aplicación ni una herramienta para el usuario final del helpdesk.
- **Ubicación `ml/`**: la historia pide `ml/notebooks/`. La épica F8 (LAB-115) introduce `ml/` como directorio de primer
  nivel para el trabajo de ML (entrenamiento, MLflow, inferencia); este es su primer contenido. El notebook de LAB-26 sigue
  en `notebooks/`, con su propio proyecto y CI. Hay que revisar que la constitución (que cita `notebooks/` para
  experimentos) y el README del repositorio mencionen también `ml/`; eso se resuelve en la historia, no en este spec.
- **Datos de origen**: LAB-17 está fusionada: los esquemas `orders` y `catalog` existen y el cargador sabe poblarlos. Para
  ejecutar en local, la base de docker compose debe tener Olist cargado; el notebook no descarga el dataset ni ejecuta el
  cargador.
- **«Estado» en retrasos por estado**: se interpreta como estado geográfico del cliente (UF), que es la dimensión
  geográfica de Olist; el estado del pedido (`order_status`) se analiza en la historia 2, y solo los pedidos entregados
  tienen retraso medible.
- **Retraso**: un pedido está retrasado cuando la fecha real de entrega al cliente es posterior a la fecha estimada.
- **Categoría de un pedido**: un pedido con productos de varias categorías se asigna según un criterio declarado en el
  notebook (por ejemplo, la categoría del ítem de mayor valor, o contar el pedido en cada categoría presente); la elección
  concreta se fija en el plan.
- **Datos en CI**: el dataset completo es CC BY-NC-SA 4.0 y no se versiona ni se descarga en CI. En CI el notebook se ejecuta
  sobre los datos de que dispone el pipeline (por ejemplo, la muestra reducida de pruebas del cargador de LAB-17) y debe
  funcionar con volumen pequeño. Las salidas guardadas en el repositorio proceden de una ejecución local con el dataset
  completo. La estrategia exacta de CI (servicio de base de datos, carga de la muestra) se decide en el plan.
- **Contraste con LAB-41**: LAB-41 está pendiente. Los «estados que mostrará la consulta de pedidos» se toman de la tabla
  de estados del documento de seguimiento del corpus (`shipping-03-order-tracking`) y de lo que defina la historia LAB-41; si
  esa historia cambia el conjunto, el contraste se actualiza al reejecutar el notebook.
- **Acción sobre discrepancias**: el notebook propone la acción (ajuste del corpus, tarea nueva, nota para otra historia),
  pero crear las tareas en Jira o editar el corpus es trabajo posterior y queda fuera de esta historia.
- **Alcance de F8**: el modelado (scikit-learn, PyTorch), MLflow, el pipeline, el servicio de inferencia y la monitorización
  de drift son historias posteriores de la épica; aquí solo se explora y se describe.
- **Sin evaluación de RAG**: la evaluación del RAG pertenece a F2 y no se repite aquí; el análisis valida datos, no
  respuestas del chat.
- **Dependencias**: LAB-17 (carga de Olist, fusionada) y LAB-18 (corpus de políticas, fusionado); LAB-41 (consulta de
  pedidos) es una referencia de contraste, no un bloqueo.
