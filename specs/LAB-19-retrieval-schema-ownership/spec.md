# Feature Specification: Esquema retrieval propiedad del módulo retrieval

**Feature Branch**: `feature/LAB-19-retrieval-schema-ownership`

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "LAB-19 (F1-02) Esquema retrieval propiedad del módulo retrieval. Como desarrollador,
quiero el esquema `retrieval` con documentos y chunks vectorizados, con un único dueño. Criterios: tablas `document` y
`chunk` con `vector(1024)`, metadatos JSONB y columna `embedding_model`; índice HNSW; migración Flyway en el módulo
`retrieval` (`db/migration/retrieval`), el único que escribe y lee el esquema `retrieval`; `ingest` no tiene
credenciales de base de datos. Depende de LAB-105." Ajuste de LAB-105 (comentario del 2026-10-27): donde la historia
dice «esquema `kb`» se lee esquema `retrieval`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Almacenar documentos y chunks vectorizados (Priority: P1)

Como desarrollador, quiero que el esquema `retrieval` tenga las tablas `document` y `chunk`, para que las historias
siguientes de F1 (ingesta, recuperación y respuestas con citas) tengan dónde guardar el contenido de la base de
conocimiento troceado y vectorizado.

Un **documento** representa un archivo fuente del corpus (p. ej. una política de `data/kb`). Un **chunk** es un
fragmento de texto de un documento junto con su vector de 1024 dimensiones, el nombre del modelo de embeddings que lo
generó y metadatos libres. Los metadatos son semiestructurados (JSONB) para poder añadir atributos del corpus (tema,
tipo, idioma, alcance) sin nuevas migraciones.

**Why this priority**: Sin estas tablas no se puede guardar ni buscar nada. Es el MVP de la historia y desbloquea la
ingesta y la búsqueda.

**Independent Test**: Con una base de datos desechable, aplicar las migraciones, insertar un documento con dos chunks
(cada uno con un vector de 1024 valores y su `embedding_model`) y leerlos de vuelta con sus metadatos intactos.

**Acceptance Scenarios**:

1. **Given** una base de datos vacía, **When** arranca el backend y se aplican las migraciones, **Then** existen las
   tablas `retrieval.document` y `retrieval.chunk`.
2. **Given** las tablas creadas, **When** se guarda un documento y varios chunks que lo referencian, **Then** cada chunk
   conserva su texto, su orden dentro del documento, su vector, su `embedding_model` y sus metadatos JSON.
3. **Given** un chunk, **When** se intenta guardar con un vector que no tiene 1024 dimensiones, **Then** la base de
   datos lo rechaza.
4. **Given** un chunk, **When** se intenta guardar sin `embedding_model`, o sin documento, **Then** la base de datos lo
   rechaza.
5. **Given** un documento con chunks, **When** se borra el documento, **Then** sus chunks se borran con él (no quedan
   chunks huérfanos dentro del esquema).
6. **Given** un chunk con metadatos JSON anidados, **When** se consulta por un atributo de los metadatos (p. ej.
   `topic = 'returns'`), **Then** la consulta devuelve ese chunk.

---

### User Story 2 - Búsqueda por similitud con índice HNSW (Priority: P1)

Como desarrollador, quiero un índice HNSW sobre el vector de los chunks, para que la búsqueda de los chunks más
cercanos a una consulta sea rápida cuando el corpus crezca, en lugar de recorrer todos los chunks.

**Why this priority**: Es un criterio de aceptación explícito y la razón de ser del almacenamiento vectorial (ADR-0002).
Se verifica de forma independiente del resto de columnas.

**Independent Test**: Tras aplicar las migraciones, comprobar en el catálogo de la base de datos que existe un índice
HNSW sobre `retrieval.chunk` y que una consulta de los k vecinos más cercanos lo usa (con datos suficientes para que el
planificador lo elija).

**Acceptance Scenarios**:

1. **Given** las migraciones aplicadas, **When** se consulta el catálogo de índices de `retrieval.chunk`, **Then** hay un
   índice de tipo HNSW sobre la columna del vector.
2. **Given** varios chunks con vectores distintos, **When** se pide los k chunks más cercanos a un vector de consulta
   usando la distancia coseno, **Then** se devuelven ordenados de más a menos cercano.
3. **Given** que la columna del vector admite el índice, **When** se inserta o actualiza un chunk, **Then** el índice
   se mantiene sin pasos manuales posteriores.

---

### User Story 3 - Un único dueño del esquema y `ingest` sin credenciales (Priority: P1)

Como desarrollador, quiero que el módulo `retrieval` sea el único que escribe y lee el esquema `retrieval`, y que el
servicio `ingest` no tenga credenciales de base de datos, para que el acceso a los datos de la base de conocimiento
pase siempre por la API del módulo dueño y, cuando `retrieval` se extraiga como servicio en F5, solo haya que mover su
paquete y sus migraciones (ADR-0015).

**Why this priority**: Es un criterio de aceptación explícito y la decisión de arquitectura que justifica crear el
esquema ahora. Las tablas sin dueño único reintroducen el acoplamiento que LAB-105 quiso evitar.

**Independent Test**: Revisar las pruebas de arquitectura y de aislamiento de esquemas y la configuración desplegable:
ningún otro módulo toca `retrieval.*`, ninguna migración crea claves foráneas hacia otros esquemas y `ingest` no recibe
variables ni secretos de conexión a la base de datos.

**Acceptance Scenarios**:

1. **Given** el código del backend, **When** se ejecutan las pruebas de arquitectura, **Then** ningún módulo distinto de
   `retrieval` accede a las tablas del esquema `retrieval`.
2. **Given** la migración nueva, **When** pasa el lint de SQL y la consulta al catálogo, **Then** no existen claves
   foráneas desde o hacia otros esquemas, y la migración no modifica objetos de otros esquemas.
3. **Given** la configuración de `ingest` en este repositorio (imagen y composición local), **When** se revisa,
   **Then** no define ninguna variable, secreto ni cadena de conexión a la base de datos.
4. **Given** otro módulo que necesite datos de la base de conocimiento, **When** los obtiene, **Then** lo hace a través
   de la API de `retrieval` (que llega con la historia de recuperación) y guarda, como mucho, identificadores de
   documento o de chunk; hasta entonces, el acceso directo lo impiden el lint y Modulith.

---

### User Story 4 - Migración versionada y repetible (Priority: P2)

Como desarrollador, quiero que el esquema se cree con una migración Flyway del módulo `retrieval`, para que cualquier
entorno (local, pruebas, dev) llegue al mismo estado sin pasos manuales y el historial de cambios quede versionado.

**Why this priority**: Es la forma de entrega del esquema; las historias 1 a 3 dependen de ella, pero su comportamiento
(aplicar una vez, no repetir) lo da el migrador ya existente de LAB-105.

**Independent Test**: Arrancar el backend sobre una base de datos con solo la línea base `V1` y comprobar que aplica
`V2` una vez; arrancar de nuevo y comprobar que no cambia nada.

**Acceptance Scenarios**:

1. **Given** una base de datos con `retrieval` en su línea base `V1`, **When** arranca el backend, **Then** se aplica la
   migración `V2` en `db/migration/retrieval` y queda registrada en `retrieval.flyway_schema_history`.
2. **Given** una base de datos ya migrada, **When** el backend arranca de nuevo, **Then** no aplica nada y los datos
   existentes se conservan.
3. **Given** una base de datos sin la extensión de vectores disponible, **When** arranca el backend, **Then** el arranque
   aborta nombrando el módulo `retrieval` y la causa, en lugar de arrancar con el esquema incompleto.
4. **Given** las migraciones de los demás módulos, **When** se aplica `V2` de `retrieval`, **Then** ninguna de ellas
   cambia.

---

### Edge Cases

- **Cambio de modelo de embeddings**: el vector es de 1024 dimensiones por el modelo actual (ADR-0003). Cambiar a un
  modelo con otra dimensión exige una migración nueva y reindexar; `embedding_model` permite distinguir qué chunks
  se generaron con qué modelo mientras dure la transición. Mezclar modelos en una misma búsqueda queda fuera de este
  alcance.
- **Documento reingestado**: volver a ingerir el mismo archivo no debe duplicar el documento; el esquema debe permitir
  identificar el documento por su origen (referencia a la fuente) de forma única.
- **Documento sin chunks**: es válido (p. ej. un documento registrado antes de trocearse).
- **Metadatos vacíos o ausentes**: un documento o chunk sin metadatos se guarda con un objeto JSON vacío, no con nulo.
- **Texto de chunk vacío**: la base de datos lo rechaza; un chunk sin texto no tiene valor para la búsqueda.
- **Borrado de documento**: borra también sus chunks. Las referencias externas al documento (por identificador, desde
  otros módulos) quedan huérfanas y el módulo que referencia las trata como «no encontrado» (ADR-0015).
- **Base de datos sin la extensión de vectores**: el arranque falla de forma explícita (ver historia 4).
- **Rendimiento del índice con pocas filas**: con un corpus de ~25 documentos el planificador puede preferir un
  recorrido secuencial; el índice debe existir de todos modos y comprobarse por catálogo, no solo por plan de consulta.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El esquema `retrieval` MUST contener una tabla `document` que represente un archivo fuente del corpus, con
  identificador, referencia única a su origen, título, metadatos JSON y marcas de creación y actualización.
- **FR-002**: El esquema `retrieval` MUST contener una tabla `chunk` que represente un fragmento de un documento, con
  identificador, referencia obligatoria a su documento, posición dentro del documento, texto, vector de 1024
  dimensiones, `embedding_model` y metadatos JSON.
- **FR-003**: `chunk.embedding_model` MUST ser obligatoria, para saber con qué modelo se generó cada vector.
- **FR-004**: Los metadatos de `document` y `chunk` MUST almacenarse como JSONB, con un objeto vacío como valor por
  defecto y sin admitir nulos.
- **FR-005**: La tabla `chunk` MUST rechazar un vector que no tenga exactamente 1024 dimensiones.
- **FR-006**: Cada par (documento, posición) MUST ser único: un documento no puede tener dos chunks en la misma
  posición.
- **FR-007**: Borrar un documento MUST borrar sus chunks.
- **FR-008**: La tabla `chunk` MUST tener un índice HNSW sobre el vector, con distancia coseno, que la base de datos
  mantenga al insertar y actualizar.
- **FR-009**: Los metadatos de `chunk` MUST poder filtrarse por atributo (p. ej. `topic`) sin recorrer toda la tabla.
- **FR-010**: Las tablas y el índice MUST crearse en una única migración `V2__...sql` en
  `backend/src/main/resources/db/migration/retrieval/`, sin modificar `V1__baseline.sql` ni las migraciones de otros
  módulos.
- **FR-011**: La migración MUST NOT crear claves foráneas hacia o desde otros esquemas ni referenciar objetos de otros
  esquemas; las referencias a otros módulos se guardan como identificadores.
- **FR-012**: Solo el módulo `retrieval` MUST leer y escribir `retrieval.*`; el resto de módulos accede a esos datos por
  la API de `retrieval`, y las pruebas de arquitectura y de aislamiento de esquemas lo verifican.
- **FR-013**: El servicio `ingest` MUST NOT tener credenciales de base de datos (variables, secretos ni cadena de
  conexión) en su imagen ni en la composición local de este repositorio. La configuración de despliegue vive en
  `shoplab-infra` y se revisa en la historia que despliegue `ingest`.
- **FR-014**: La extensión de vectores MUST estar disponible en la base de datos antes de aplicar la migración; si no lo
  está, el arranque MUST abortar nombrando el módulo `retrieval`.
- **FR-015**: La migración MUST ser compatible con la tabla de historial propia del módulo
  (`retrieval.flyway_schema_history`) y con el migrador por módulo existente, sin nuevas piezas de arranque.
- **FR-016**: Esta historia MUST NOT incluir lógica de ingesta, troceado, generación de embeddings ni búsqueda; solo el
  esquema y las verificaciones de su propiedad.

### Key Entities *(include if feature involves data)*

- **Documento (`retrieval.document`)**: archivo fuente del corpus (p. ej. una política). Atributos: identificador,
  referencia única al origen, título, metadatos JSON (tema, tipo, idioma, alcance…), marcas de creación y
  actualización. Tiene cero o más chunks.
- **Chunk (`retrieval.chunk`)**: fragmento de texto de un documento. Atributos: identificador, documento al que
  pertenece, posición dentro del documento, texto, vector de 1024 dimensiones, `embedding_model`, metadatos JSON, marca
  de creación. Pertenece a exactamente un documento.
- **Referencia externa**: identificador de documento o de chunk guardado por otro módulo (p. ej. `chat` para citas). No
  es una clave foránea y puede quedar huérfana.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Sobre una base de datos desechable, el 100 % de las migraciones de los cinco módulos se aplica sin error en
  un único arranque, y un segundo arranque no aplica ninguna.
- **SC-002**: Un documento con 100 chunks vectorizados se guarda y se lee de vuelta sin pérdida de texto, vector,
  `embedding_model` ni metadatos.
- **SC-003**: El 100 % de los intentos de guardar un chunk inválido (dimensión distinta de 1024, sin modelo, sin
  documento, sin texto) es rechazado por la propia base de datos.
- **SC-004**: Con 10 000 chunks de prueba, obtener los 5 chunks más cercanos a un vector de consulta tarda menos de
  200 ms en una máquina de desarrollo (la prueba automática de CI usa un margen mayor) y el plan de consulta usa el
  índice HNSW.
- **SC-005**: Cero claves foráneas entre esquemas y cero accesos a `retrieval.*` desde otros módulos: las pruebas de
  arquitectura, de aislamiento y la consulta al catálogo pasan en CI.
- **SC-006**: Cero variables, secretos o cadenas de conexión a base de datos en la configuración de `ingest`,
  comprobado en CI o por una prueba automatizada.

## Assumptions

- **Esquema `retrieval`, no `kb`**: así lo fija el comentario de LAB-105 y el ADR-0015; módulo = esquema = carpeta de
  migraciones. El nombre de las tablas es `retrieval.document` y `retrieval.chunk`.
- **Dimensión 1024**: la fija el modelo de embeddings por defecto `bge-m3` (ADR-0003). `embedding_model` guarda su
  nombre (p. ej. `bge-m3`).
- **Distancia coseno** para el índice HNSW, la habitual para embeddings de texto; los parámetros del índice (`m`,
  `ef_construction`) toman los valores por defecto de la extensión y se ajustan en una historia posterior si la
  evaluación (F1) lo pide.
- **Extensión de vectores ya disponible** en la base de datos de dev y local (imagen de pgvector y script de
  inicialización existentes); la migración la declara de forma idempotente por si falta.
- **Módulo y esquema ya existen**: `retrieval`, su `ModuleSchema` y su `V1__baseline.sql` los creó LAB-105; esta historia
  solo añade `V2`.
- **Texto original conservado**: el chunk guarda su texto para poder citarlo en las respuestas (historias posteriores
  de F1), por lo que no se requiere un almacén de texto aparte.
- **Búsqueda de texto completo fuera de alcance**: ADR-0002 la prevé para la búsqueda híbrida, pero llega con la
  historia de recuperación; añadirla después es una migración aditiva.
- **Acceso desde `ingest`**: `ingest` es hoy un marcador de posición sin servicio. Cuando exista, enviará su salida a
  `retrieval` por una API que define una historia posterior; esta historia solo garantiza que no tenga credenciales de
  base de datos.
- **Sin roles de base de datos por módulo**: se difieren a F5 (ADR-0015); en esta historia la propiedad única la
  garantizan las pruebas de arquitectura y de aislamiento, no los permisos de la base de datos.
- **Dependencia**: LAB-105 (módulos por negocio con dueño de datos), ya fusionada en `main`.
