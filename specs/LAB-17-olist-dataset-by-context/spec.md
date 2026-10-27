# Feature Specification: Carga del dataset Olist en esquemas por contexto

**Feature Branch**: `feature/LAB-17-olist-dataset-by-context`

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "LAB-17 (F0-09) Carga del dataset Olist en esquemas por contexto. Como desarrollador, quiero
cargar el dataset de Olist separado por contexto de negocio, para que `orders` y `catalog` puedan extraerse como
servicios sin partir tablas. Criterios: script Python que descarga y carga el dataset; esquema `orders` (clientes,
pedidos, ítems, pagos y reseñas) y esquema `catalog` (productos, categorías, vendedores, precio y stock); sin claves
foráneas entre esquemas: un ítem de pedido referencia el producto solo por id; migraciones Flyway en el módulo dueño de
cada esquema (`orders`, `catalog`). Depende de LAB-105."

## Clarifications

### Session 2026-10-27

- Q: ¿De dónde sale el stock de `catalog`, si el dataset de Olist no lo incluye? → A: Cantidad sintética determinista
  por producto, con algunos productos en 0 (opción A).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Datos de pedidos y de catálogo cargados en su propio esquema (Priority: P1)

Como desarrollador, quiero ejecutar un único comando que descargue el dataset público de Olist y lo cargue repartido
entre el esquema `orders` (clientes, pedidos, ítems, pagos y reseñas) y el esquema `catalog` (productos, categorías,
vendedores, precio y stock), para disponer de datos realistas en local sobre los que construir las herramientas del
agente (consulta de pedidos, búsqueda de productos) sin preparar datos a mano.

**Why this priority**: Sin datos cargados no hay nada que consultar: las historias de F1 en adelante (herramientas de
pedidos y catálogo, evaluación, demos) dependen de este contenido. Es el MVP de la historia.

**Independent Test**: Partir de una base de datos con los esquemas creados y vacíos, ejecutar la carga y comprobar que
cada tabla de `orders` y de `catalog` contiene filas, que los conteos coinciden con los del dataset de origen y que un
pedido concreto puede reconstruirse completo (cliente, ítems, pagos, reseña) sin consultar el esquema `catalog`.

**Acceptance Scenarios**:

1. **Given** una base de datos con los esquemas `orders` y `catalog` vacíos y acceso al dataset, **When** el
   desarrollador ejecuta la carga, **Then** el dataset se descarga, `orders` queda poblado con clientes, pedidos, ítems,
   pagos y reseñas, y `catalog` queda poblado con productos, categorías, vendedores, precio y stock.
2. **Given** la carga terminada, **When** se comparan los conteos de cada tabla con los del dataset de origen, **Then**
   coinciden para todas las tablas (salvo las filas descartadas y documentadas por datos inválidos).
3. **Given** la carga terminada, **When** se consulta un pedido por su identificador, **Then** se obtienen su cliente,
   sus ítems, sus pagos y su reseña usando solo el esquema `orders`.
4. **Given** la carga terminada, **When** se consulta un producto por su identificador, **Then** se obtienen su
   categoría, su vendedor, su precio y su stock usando solo el esquema `catalog`.

---

### User Story 2 - Aislamiento entre esquemas: referencias solo por id (Priority: P1)

Como desarrollador, quiero que ningún dato de `orders` tenga una clave foránea hacia `catalog` (ni al revés) y que un
ítem de pedido guarde el producto y el vendedor únicamente por su identificador, para poder extraer `orders` y `catalog`
como servicios independientes sin partir tablas ni deshacer acoplamientos de base de datos.

**Why this priority**: Es el objetivo de negocio de la historia. Una vez cargados los datos, un acoplamiento entre
esquemas es caro de deshacer; la regla debe cumplirse desde la primera carga (continuación de LAB-105).

**Independent Test**: Tras la carga, inspeccionar las restricciones de la base de datos y verificar que ninguna cruza
de esquema; cargar un ítem de pedido cuyo producto no existe en `catalog` y comprobar que la carga lo acepta sin error
de integridad.

**Acceptance Scenarios**:

1. **Given** los esquemas cargados, **When** se listan las restricciones de integridad, **Then** no existe ninguna clave
   foránea entre `orders` y `catalog`; las relaciones internas de cada esquema (pedido→ítems, pedido→pagos,
   pedido→reseña, producto→categoría) sí están garantizadas dentro de su propio esquema.
2. **Given** un ítem de pedido, **When** se revisa su definición, **Then** referencia al producto y al vendedor solo
   mediante sus identificadores, sin dependencia de existencia en `catalog`.
3. **Given** un ítem de pedido cuyo producto no está en `catalog`, **When** se carga, **Then** se acepta y se reporta
   como referencia huérfana informativa, sin abortar la carga.
4. **Given** una verificación automática de aislamiento, **When** se introduce de forma deliberada una clave foránea
   entre esquemas, **Then** la verificación falla y señala la restricción.

---

### User Story 3 - Estructura versionada y propiedad en el módulo dueño (Priority: P1)

Como desarrollador, quiero que la estructura de tablas de `orders` y de `catalog` esté versionada como migraciones en
el módulo que posee cada esquema, y que se aplique automáticamente al arrancar, para que cualquier entorno nuevo
obtenga las mismas tablas y que extraer un módulo sea mover su código junto con sus migraciones.

**Why this priority**: Garantiza reproducibilidad y la propiedad de datos de LAB-105; sin ella la carga dependería de
tablas creadas a mano y no sería repetible.

**Independent Test**: Arrancar el backend contra una base de datos vacía y comprobar que ambos esquemas tienen todas
sus tablas, y que las migraciones de cada esquema viven solo en la carpeta de su módulo.

**Acceptance Scenarios**:

1. **Given** una base de datos vacía, **When** arranca el backend, **Then** se aplican las migraciones de `orders` y de
   `catalog` desde la carpeta de su módulo dueño y quedan creadas todas sus tablas.
2. **Given** las migraciones de un módulo, **When** se revisa su contenido, **Then** solo crean o modifican objetos de
   su propio esquema.
3. **Given** una base de datos ya migrada, **When** el backend arranca de nuevo, **Then** no se reaplica ni se rompe
   nada (idempotente).
4. **Given** los módulos `tickets`, `chat` y `retrieval`, **When** se aplica esta historia, **Then** sus esquemas y
   migraciones no cambian.

---

### User Story 4 - Carga repetible y segura de re-ejecutar (Priority: P2)

Como desarrollador, quiero poder volver a ejecutar la carga —tras un fallo a medias, un cambio de datos o para
reiniciar el entorno— y obtener siempre el mismo estado final sin duplicados ni datos a medio cargar, para confiar en
que el entorno local es reproducible.

**Why this priority**: Aporta comodidad y robustez, pero la historia entrega valor sin ella (P1 ya carga los datos una
vez); es la capa que evita estados sucios en uso diario.

**Independent Test**: Ejecutar la carga dos veces seguidas y comprobar que los conteos no cambian; interrumpirla a la
mitad, volver a ejecutarla y comprobar que el resultado final es el completo y correcto.

**Acceptance Scenarios**:

1. **Given** una base de datos ya cargada, **When** se vuelve a ejecutar la carga, **Then** el estado final es idéntico
   (mismos conteos, sin filas duplicadas).
2. **Given** una carga interrumpida a mitad, **When** se vuelve a ejecutar, **Then** termina con el conjunto completo y
   sin datos parciales de la ejecución anterior.
3. **Given** el dataset ya descargado, **When** se vuelve a ejecutar la carga, **Then** reutiliza los archivos locales y
   no vuelve a descargarlos.
4. **Given** la carga terminada, **When** el desarrollador revisa su salida, **Then** ve un resumen por esquema y tabla
   (filas leídas, cargadas y descartadas) y el tiempo total.

---

### Edge Cases

- **Dataset no disponible o credenciales ausentes**: si la descarga falla (sin red, sin acceso, archivo corrupto), la
  carga termina con un error claro que indica qué falta y no deja datos a medias.
- **Filas con datos inválidos o incompletos** (fechas vacías en pedidos no entregados, productos sin categoría ni
  medidas, reseñas sin comentario): se cargan tal cual con valores nulos cuando el dato es opcional; solo se descartan
  las filas que violan una regla de integridad dentro de su esquema, y se contabilizan en el resumen.
- **Productos con categoría sin traducción**: se conservan con la categoría original; no se descartan.
- **Referencia entre esquemas huérfana** (ítem cuyo producto o vendedor no existe en `catalog`): se acepta y se reporta
  (ver historia 2); nunca aborta la carga.
- **Base de datos sin los esquemas creados o con migraciones pendientes**: la carga se niega a ejecutarse y explica que
  primero deben aplicarse las migraciones.
- **Base de datos con datos previos**: la carga no mezcla datos antiguos con nuevos; deja el estado final del dataset
  (ver historia 4).
- **Volumen**: el dataset completo (~100 mil pedidos y ~112 mil ítems) debe cargarse sin agotar memoria en un equipo de
  desarrollo estándar.
- **Datos personales**: el dataset está anonimizado; no se añaden ni se infieren datos reales de clientes.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: MUST existir un script ejecutable por un desarrollador con un único comando que descargue el dataset de
  Olist y lo cargue en la base de datos, sin pasos manuales intermedios (aparte de aportar credenciales o acceso si el
  origen los exige).
- **FR-002**: La carga MUST poblar el esquema `orders` con clientes, pedidos, ítems de pedido, pagos y reseñas.
- **FR-003**: La carga MUST poblar el esquema `catalog` con productos, categorías, vendedores, precio y stock.
- **FR-004**: Ninguna estructura de `orders` o `catalog` MUST tener claves foráneas hacia el otro esquema; un ítem de
  pedido MUST referenciar el producto y el vendedor solo por identificador.
- **FR-005**: Las relaciones internas de cada esquema (pedido→ítems, pedido→pagos, pedido→reseña, cliente→pedido en
  `orders`; producto→categoría en `catalog`) MUST garantizarse dentro de su propio esquema.
- **FR-006**: La estructura de tablas de `orders` y de `catalog` MUST definirse como migraciones versionadas ubicadas en
  la carpeta de migraciones del módulo dueño de cada esquema, y MUST aplicarse automáticamente al arrancar el backend.
- **FR-007**: Las migraciones de un módulo MUST crear o modificar solo objetos de su propio esquema, y MUST NOT alterar
  los esquemas de los demás módulos.
- **FR-008**: La carga MUST ser repetible: ejecutarla varias veces o tras una interrupción MUST dejar el mismo estado
  final, sin duplicados ni datos parciales.
- **FR-009**: La carga MUST reutilizar los archivos ya descargados en lugar de descargarlos de nuevo.
- **FR-010**: La carga MUST negarse a ejecutarse, con un mensaje accionable, si los esquemas destino no existen o sus
  migraciones no están aplicadas.
- **FR-011**: Al terminar, la carga MUST mostrar por esquema y tabla las filas leídas, cargadas y descartadas, las
  referencias huérfanas entre esquemas y el tiempo total.
- **FR-012**: Una verificación automática MUST fallar si aparece una clave foránea entre `orders` y `catalog`.
- **FR-013**: Las credenciales y el acceso al origen del dataset MUST provenir del entorno o de configuración local;
  MUST NOT versionarse en el repositorio. Los archivos del dataset descargado MUST NOT versionarse.
- **FR-014**: La carga MUST NOT introducir datos reales de clientes; solo el dataset público anonimizado de Olist.
- **FR-015**: El stock de cada producto MUST generarse como una cantidad sintética determinista (el dataset de Olist no
  incluye stock): la misma entrada produce siempre la misma cantidad por producto, con valores variados que incluyan
  productos agotados (stock 0). Esa cantidad MUST quedar documentada como dato sintético y no proviene del dataset.
- **FR-016**: Quedan fuera de alcance: los esquemas `tickets`, `chat` y `retrieval`; las herramientas del agente y los
  endpoints que consulten estos datos; la tabla de geolocalización del dataset; la actualización incremental del
  dataset; y la carga en entornos distintos del local de desarrollo.

### Key Entities *(include if feature involves data)*

Esquema `orders` (dueño: módulo `orders`):

- **Cliente**: persona anonimizada que realiza pedidos; identificador, identificador único del cliente y ubicación
  aproximada (ciudad, estado, código postal parcial).
- **Pedido**: compra realizada por un cliente; estado y marcas de tiempo del ciclo de vida (compra, aprobación, entrega
  al transportista, entrega al cliente, fecha estimada).
- **Ítem de pedido**: línea de un pedido; posición, precio, flete, **identificador de producto** e **identificador de
  vendedor** (solo ids, sin relación hacia `catalog`) y fecha límite de envío.
- **Pago**: pago asociado a un pedido; secuencia, método, cuotas y valor.
- **Reseña**: valoración de un pedido; puntuación, título y comentario opcionales, fechas de creación y respuesta.

Esquema `catalog` (dueño: módulo `catalog`):

- **Producto**: artículo vendible; identificador, categoría, medidas y peso, número de fotos, precio de referencia y
  stock sintético (no proviene del dataset).
- **Categoría**: clasificación de productos con su nombre original y su traducción al inglés.
- **Vendedor**: comercio que ofrece productos; identificador y ubicación aproximada.

Relaciones: dentro de `orders`, Cliente 1–N Pedido, Pedido 1–N Ítem/Pago, Pedido 1–N Reseña; dentro de `catalog`,
Categoría 1–N Producto. Entre esquemas: el Ítem de pedido apunta a Producto y Vendedor solo por identificador, nunca por
relación de base de datos.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un desarrollador con el entorno local levantado obtiene `orders` y `catalog` poblados ejecutando un solo
  comando, sin pasos manuales aparte de aportar el acceso al dataset, en menos de 10 minutos con el dataset completo.
- **SC-002**: El 100 % de las tablas de `orders` y `catalog` tiene filas, y los conteos coinciden con los del dataset de
  origen salvo las filas descartadas, que quedan listadas con su motivo en el resumen.
- **SC-003**: 0 claves foráneas entre `orders` y `catalog`; la verificación automática detecta el 100 % de las
  violaciones introducidas de forma deliberada.
- **SC-004**: Ejecutar la carga dos veces seguidas produce exactamente los mismos conteos (0 filas duplicadas), y tras
  una interrupción la siguiente ejecución completa el 100 % del conjunto.
- **SC-005**: Un pedido cualquiera puede reconstruirse completo (cliente, ítems, pagos, reseña) consultando solo
  `orders`, y un producto cualquiera consultando solo `catalog`, en el 100 % de los casos probados.
- **SC-006**: Una base de datos vacía queda con ambos esquemas completos tras un solo arranque del backend, sin
  intervención manual, y los arranques posteriores no modifican nada.
- **SC-007**: Las historias posteriores que consulten pedidos o productos (herramientas del agente en F1+) pueden
  empezar sin crear tablas ni mover estructura entre módulos.

## Assumptions

- **Dependencia de LAB-105**: ya existen los módulos `orders` y `catalog`, sus esquemas y sus carpetas de migraciones
  con la migración de base (`V1__baseline.sql`); esta historia añade las migraciones de tablas a continuación. La rama
  parte de `refactor/LAB-105-business-modules-data-ownership` porque LAB-105 aún no está fusionado a `main`.
- **Origen del dataset**: el dataset público «Brazilian E-Commerce Public Dataset by Olist». Su descarga puede exigir
  una cuenta o credenciales en el sitio que lo aloja; se aportan por entorno o configuración local (constitución,
  principio VI).
- **Ubicación del script**: el cargador vive en el área de datos del repositorio (`data/`), donde la constitución sitúa
  los cargadores, y los archivos descargados quedan en `data/raw/`, fuera del control de versiones. No se usa `ingest/`
  porque es el servicio de ingesta de RAG y su imagen se activa al añadirle un proyecto Python.
- **Reparto de tablas del origen**: clientes, pedidos, ítems, pagos y reseñas → `orders`; productos, traducción de
  categorías y vendedores → `catalog`. El **precio** es el de cada ítem de pedido (hecho histórico, se queda en
  `orders`); `catalog` guarda además un precio de referencia por producto derivado de los precios observados del propio
  dataset.
- **Stock sintético** (clarificación 2026-10-27, opción A): el stock se genera de forma determinista a partir del
  identificador del producto, de modo que dos cargas dan el mismo resultado (refuerza FR-008 y SC-004); una fracción
  de los productos queda en 0 para poder probar escenarios de agotado en las herramientas del agente.
- **Reseñas**: pertenecen al contexto de pedidos porque se emiten sobre un pedido, no sobre un producto.
- **Geolocalización**: la tabla de geolocalización del dataset queda fuera; los códigos postales parciales de clientes
  y vendedores se conservan como atributo.
- **Entorno**: base de datos PostgreSQL local de la infraestructura de LAB-10; la carga se ejecuta solo en desarrollo.
- **Idioma**: nombres de tablas y columnas en inglés (constitución, principio III); documentación y mensajes de
  usuario pueden estar en español.
- **Datos personales**: el dataset ya viene anonimizado; no se requieren controles adicionales de privacidad.
