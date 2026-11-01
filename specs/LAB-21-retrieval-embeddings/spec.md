# Feature Specification: Embeddings calculados solo en retrieval

**Feature Branch**: `feature/LAB-21-retrieval-embeddings`

**Created**: 2026-10-30

**Status**: Draft

**Input**: User description: "LAB-21 (F1-04) Embeddings calculados solo en retrieval. Como desarrollador, quiero calcular
los embeddings en un solo lugar, para cambiar de modelo sin tocar dos servicios a la vez. Criterios: `retrieval` calcula
los embeddings con el `EmbeddingModel` de Spring AI (modelo multilingüe en Ollama) al guardar chunks y al buscar; cada
chunk guarda `embedding_model` y la búsqueda solo compara vectores del mismo modelo; los chunks que LAB-20 guardó sin
vector (`embedding` y `embedding_model` nulos, migración V3) se completan, y el documento pasa de pendiente a indexado, o
a error si falla; la búsqueda solo considera chunks con `embedding IS NOT NULL`; comando de reindexado para cambiar de
modelo sin tocar `ingest`; test de integración documento → `ingest` → chunks → embeddings guardados en el esquema
`retrieval`." Depende de LAB-19 (esquema `retrieval`) y LAB-20 (parseo y chunking), ya fusionadas en `main`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Completar los chunks pendientes con su vector (Priority: P1)

Como desarrollador, quiero que los chunks que `ingest` devolvió y `retrieval` guardó sin vector reciban su embedding en
`retrieval`, para que el contenido ingerido quede listo para buscarse sin que `ingest` conozca ningún modelo de embeddings.

Hoy (LAB-20) la ingesta guarda el documento y sus chunks con `embedding` y `embedding_model` nulos. Esta historia añade el
paso que calcula el vector de cada chunk pendiente, lo guarda junto con el nombre del modelo que lo generó y marca el
documento como **indexado**. Si el cálculo falla, el documento queda en **error** con la causa, sin perder los chunks.

**Why this priority**: Es el núcleo de la historia y lo que desbloquea la búsqueda (LAB-22) y las respuestas con citas. Sin
vectores, la base de conocimiento no sirve para recuperar.

**Independent Test**: Con un documento ya ingerido (chunks sin vector), ejecutar el cálculo de embeddings y comprobar que
todos sus chunks tienen vector y `embedding_model`, y que el documento figura como indexado.

**Acceptance Scenarios**:

1. **Given** un documento con chunks sin vector, **When** se completan sus embeddings, **Then** cada chunk guarda su
   vector y el nombre del modelo que lo generó, y el documento pasa de pendiente a indexado.
2. **Given** un documento recién ingerido, **When** termina de guardarse, **Then** queda pendiente hasta que sus vectores
   se completan; no figura como indexado con chunks sin vector.
3. **Given** que el modelo de embeddings no responde o devuelve un error para algún chunk, **When** se completa el
   documento, **Then** el documento queda en error con una causa legible y ningún chunk queda con vector a medias
   (todo o nada por documento).
4. **Given** un documento en error, **When** se vuelve a completar, **Then** los chunks sin vector se calculan de nuevo y,
   si todo sale bien, el documento pasa a indexado.
5. **Given** un documento ya indexado, **When** se vuelve a completar, **Then** no se recalculan sus vectores ni cambia
   su estado.
6. **Given** chunks pendientes de varios documentos, **When** se completan, **Then** el fallo de un documento no impide
   indexar los demás.
7. **Given** una reingesta de un documento indexado (LAB-20), **When** sus chunks se reemplazan, **Then** el documento
   vuelve a pendiente y sus chunks nuevos se completan; no quedan vectores de la versión anterior.

---

### User Story 2 - Embedding de la consulta y búsqueda solo entre vectores del mismo modelo (Priority: P1)

Como desarrollador, quiero que `retrieval` calcule también el embedding de la consulta y busque solo entre chunks
generados por ese mismo modelo, para que nunca se comparen vectores de espacios distintos, aunque convivan chunks de dos
modelos durante un cambio.

La búsqueda calcula el vector de la consulta con el modelo configurado y devuelve los chunks más cercanos por similitud,
considerando únicamente los chunks con vector (`embedding IS NOT NULL`) cuyo `embedding_model` coincide con el modelo
configurado.

**Why this priority**: Es un criterio de aceptación explícito y la razón por la que se guarda `embedding_model`: sin este
filtro, un cambio de modelo produciría resultados sin sentido en silencio.

**Independent Test**: Con chunks de dos modelos distintos y chunks sin vector en la base de datos, buscar con el modelo
configurado y comprobar que solo se devuelven chunks de ese modelo con vector.

**Acceptance Scenarios**:

1. **Given** chunks indexados con el modelo configurado, **When** se busca un texto, **Then** se devuelven los chunks más
   cercanos ordenados por similitud, con su texto, documento, sección y posición.
2. **Given** chunks pendientes (sin vector), **When** se busca, **Then** no aparecen en los resultados ni provocan error.
3. **Given** chunks indexados con un modelo distinto al configurado, **When** se busca, **Then** no aparecen en los
   resultados.
4. **Given** que no hay ningún chunk indexado con el modelo configurado, **When** se busca, **Then** el resultado es una
   lista vacía, no un error.
5. **Given** que el modelo de embeddings no responde al calcular la consulta, **When** se busca, **Then** se produce un
   error tipado que indica que el servicio de embeddings falló; no se devuelven resultados parciales.
6. **Given** una consulta vacía o solo espacios, **When** se busca, **Then** se rechaza como consulta no válida sin llamar
   al modelo.

---

### User Story 3 - Reindexar para cambiar de modelo sin tocar `ingest` (Priority: P1)

Como desarrollador, quiero un comando de reindexado que recalcule los embeddings de los chunks existentes con el modelo
configurado, para cambiar de modelo modificando solo la configuración de `retrieval`, sin volver a parsear ni a trocear
los documentos y sin tocar `ingest`.

**Why this priority**: Es un criterio de aceptación explícito y el objetivo declarado de la historia («cambiar de modelo
sin tocar dos servicios a la vez»). ADR-0003 ya establece que cambiar el modelo obliga a reindexar.

**Independent Test**: Con documentos indexados con el modelo A, configurar el modelo B, ejecutar el reindexado y comprobar
que todos los chunks pasan a tener vectores del modelo B, con su `embedding_model` actualizado, sin invocar `ingest`.

**Acceptance Scenarios**:

1. **Given** chunks indexados con un modelo distinto al configurado, **When** se ejecuta el reindexado, **Then** sus
   vectores se recalculan con el modelo configurado, `embedding_model` se actualiza y los documentos terminan indexados.
2. **Given** el reindexado, **When** se ejecuta, **Then** no se llama a `ingest` ni se modifica el texto, la posición ni
   los metadatos de los chunks.
3. **Given** chunks que ya tienen el modelo configurado, **When** se ejecuta el reindexado sin forzarlo, **Then** no se
   recalculan.
4. **Given** el reindexado de un documento que falla a mitad, **When** termina, **Then** el documento conserva sus
   vectores anteriores completos y consistentes (nunca mezcla dos modelos en un mismo documento) y queda marcado como
   error con la causa.
5. **Given** el reindexado en curso, **When** se hace una búsqueda, **Then** la búsqueda sigue funcionando y solo
   considera chunks del modelo configurado que ya tengan vector; los documentos aún sin reindexar dejan temporalmente de
   aparecer, sin error.
6. **Given** el reindexado terminado, **When** se resume, **Then** informa cuántos documentos y chunks se reindexaron,
   cuántos fallaron y con qué causa.

---

### User Story 4 - Prueba de integración del flujo completo (Priority: P2)

Como desarrollador, quiero una prueba de integración que recorra documento → `ingest` → chunks → embeddings guardados en
el esquema `retrieval`, para detectar de forma automática cualquier ruptura del flujo de extremo a extremo.

**Why this priority**: Es un criterio de aceptación explícito, pero valida lo que las historias 1 a 3 entregan; las
historias tienen valor funcional sin ella, aunque no deben fusionarse sin ella.

**Independent Test**: Ejecutar la prueba: ingerir un documento del corpus con `ingest` y un modelo de embeddings
determinista, y comprobar que `retrieval.chunk` contiene los chunks con vector y `embedding_model`, y que el documento
figura como indexado.

**Acceptance Scenarios**:

1. **Given** un documento Markdown del corpus, **When** se ingiere y se completan sus embeddings, **Then**
   `retrieval.document` tiene una fila indexada y `retrieval.chunk` tiene una fila por chunk con vector de 1 024 valores
   y `embedding_model`.
2. **Given** el mismo flujo, **When** se busca un texto contenido en el documento, **Then** el chunk correspondiente
   aparece entre los primeros resultados.
3. **Given** la prueba en CI, **When** se ejecuta, **Then** es determinista y no depende de un servidor de modelos real.

---

### Edge Cases

- **Dimensión distinta de 1 024**: la columna de vectores tiene 1 024 dimensiones (LAB-19). Si el modelo configurado
  devuelve otra dimensión, el cálculo se rechaza con un error claro que nombra la dimensión esperada y la recibida; no se
  guarda nada. Cambiar a un modelo de otra dimensión requiere una migración propia y queda fuera de esta historia.
- **Modelo configurado sin nombre o no disponible**: si el modelo no está disponible en el servidor de modelos, el cálculo
  falla con un error tipado y los documentos afectados quedan en error; el servicio de `retrieval` sigue arrancando.
- **Chunk de texto muy largo para el modelo**: un chunk que supera el límite del modelo se trata como fallo del documento
  con causa legible; no se trunca en silencio.
- **Documento sin chunks pendientes**: completar sus embeddings no hace nada y no cambia su estado.
- **Ejecuciones simultáneas**: dos ejecuciones concurrentes (p. ej. una ingesta y un reindexado) no procesan dos veces el
  mismo documento ni dejan vectores mezclados; la segunda espera o se omite.
- **Reingesta durante un cálculo en curso**: si los chunks de un documento se reemplazan mientras se calculan sus vectores,
  los vectores de los chunks antiguos se descartan; nunca se asigna un vector a un chunk de otro contenido.
- **Reanudación tras caída**: documentos pendientes o en error tras reiniciar el servicio pueden completarse en una
  ejecución posterior, sin intervención sobre los datos.
- **Metadatos y contenido**: el cálculo de embeddings nunca modifica el texto, la posición ni los metadatos de un chunk.
- **Configuración del modelo cambiada sin reindexar**: la búsqueda no devuelve chunks del modelo anterior; el efecto es
  una lista vacía hasta que se reindexe, y se registra un aviso que lo explica.
- **Rendimiento**: los chunks se envían al modelo por lotes de tamaño configurable para no saturar al servidor de modelos.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El módulo `retrieval` MUST ser el único componente que calcula embeddings; `ingest` MUST NOT conocer, llamar
  ni configurar ningún modelo de embeddings.
- **FR-002**: El acceso al modelo de embeddings MUST estar detrás de un puerto de salida de `retrieval`, de modo que el
  proveedor pueda cambiarse o simularse sin tocar el dominio ni los casos de uso (Constitución II).
- **FR-003**: El modelo de embeddings MUST ser configurable (nombre del modelo y lote), con `bge-m3` (multilingüe, 1 024
  dimensiones, servido por Ollama) como valor por defecto, y cambiarlo MUST requerir solo configuración de `retrieval`
  (ADR-0003).
- **FR-004**: El sistema MUST completar los chunks pendientes (`embedding` y `embedding_model` nulos): calcular su vector,
  guardarlo junto con el nombre del modelo y mantener el invariante de V3 (ambas columnas presentes o ambas ausentes).
- **FR-005**: Cada documento MUST tener un estado explícito entre **pendiente**, **indexado** y **error**. Un documento
  recién ingerido o reingerido está pendiente; pasa a indexado cuando todos sus chunks tienen vector del modelo
  configurado; pasa a error, con una causa legible, si el cálculo falla.
- **FR-006**: Completar los vectores de un documento MUST ser atómico: o todos sus chunks reciben vector y el documento
  pasa a indexado, o no cambia ninguno y el documento pasa a error.
- **FR-007**: Un fallo en un documento MUST NOT impedir indexar los demás ni perder sus chunks; un documento en error MUST
  poder reintentarse sin reingerirlo.
- **FR-008**: Completar un documento ya indexado con el modelo configurado MUST NOT recalcular sus vectores (idempotente).
- **FR-009**: La búsqueda MUST calcular el embedding de la consulta con el modelo configurado y devolver los chunks más
  cercanos por similitud, con texto, documento, sección, posición y puntuación, con un número máximo de resultados
  configurable.
- **FR-010**: La búsqueda MUST considerar solo chunks con `embedding IS NOT NULL` y cuyo `embedding_model` sea igual al
  modelo configurado; nunca compara vectores de modelos distintos.
- **FR-011**: La búsqueda MUST rechazar una consulta vacía o solo con espacios como no válida sin llamar al modelo, y
  devolver una lista vacía (no un error) cuando no hay chunks elegibles.
- **FR-012**: El sistema MUST ofrecer un comando de reindexado que recalcule los embeddings de los chunks cuyo
  `embedding_model` difiere del configurado (o de todos, si se fuerza) sin llamar a `ingest` ni modificar texto, posición
  ni metadatos.
- **FR-013**: El reindexado MUST ser atómico por documento: un documento nunca mezcla vectores de dos modelos; si el
  reindexado de un documento falla, conserva sus vectores anteriores y queda marcado como error.
- **FR-014**: El reindexado MUST informar un resumen: documentos y chunks reindexados, fallidos y la causa de cada fallo.
- **FR-015**: Un fallo del modelo de embeddings (no disponible, error, tiempo de espera, dimensión distinta de la columna,
  texto demasiado largo) MUST traducirse a un error tipado propio, distinguible de un documento inválido, con código en
  `ResponseCodeEnum` y manejado solo en `GlobalExceptionHandler` si llega a HTTP (Constitución III).
- **FR-016**: La reingesta de un documento (LAB-20) MUST dejarlo pendiente y descartar los vectores de sus chunks
  anteriores.
- **FR-017**: El cálculo de embeddings MUST NOT modificar el texto, la posición ni los metadatos de los chunks, ni el
  contrato de `POST /ingest`.
- **FR-018**: Debe existir una prueba de integración determinista (sin servidor de modelos real) que recorra documento →
  `ingest` → chunks → embeddings guardados en el esquema `retrieval`, y pruebas que cubran el filtro por modelo y por
  `embedding IS NOT NULL`.
- **FR-019**: El sistema MUST emitir métricas y registros estructurados del cálculo de embeddings (documentos y chunks
  procesados, fallos, latencia por lote y modelo), sin incluir el texto de los chunks ni de las consultas en los registros
  (Constitución VI).
- **FR-020**: Esta historia MUST NOT incluir búsqueda híbrida (texto completo), reordenado, generación de respuestas con
  citas, la pantalla de administración ni un endpoint REST de subida; solo embeddings, el estado del documento, la
  búsqueda vectorial mínima y el reindexado.

### Key Entities *(include if feature involves data)*

- **Documento (`retrieval.document`)**: registro del archivo ingerido. Gana un **estado** (pendiente, indexado, error) y la
  causa del último error, que lo hacen observable por la pantalla de administración (LAB-25).
- **Chunk (`retrieval.chunk`)**: fragmento de un documento con su texto, posición y metadatos; su **vector** y el
  **modelo** que lo generó son opcionales hasta que se completan.
- **Modelo de embeddings**: modelo configurado que produce los vectores (nombre, dimensión de 1 024). Identifica en cada
  chunk con qué espacio vectorial se calculó.
- **Resultado de búsqueda**: chunk recuperado con su puntuación de similitud y sus metadatos de origen (documento,
  sección, posición).
- **Resumen de reindexado**: resultado de una ejecución: documentos y chunks reindexados, fallidos y causas.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los documentos de `data/kb` ingeridos queda indexado, con el 100 % de sus chunks con vector de
  1 024 valores y su `embedding_model`.
- **SC-002**: El 100 % de los resultados de una búsqueda procede de chunks con vector y con el modelo configurado; en una
  base de datos con chunks de dos modelos y chunks pendientes, ningún resultado procede de otro modelo ni de un chunk sin
  vector.
- **SC-003**: Cambiar de modelo requiere modificar únicamente la configuración de `retrieval` y ejecutar el reindexado: cero
  cambios en `ingest` y cero cambios de código.
- **SC-004**: Tras un reindexado, el 100 % de los chunks tiene el `embedding_model` configurado y ningún documento mezcla
  modelos, incluso cuando un documento falla.
- **SC-005**: Ante un fallo del modelo de embeddings, el 100 % de los documentos afectados queda en error con una causa
  legible y conserva sus chunks; los demás documentos se indexan normalmente.
- **SC-006**: Completar los embeddings de una política típica del corpus (hasta 20 páginas) con el modelo por defecto tarda
  menos de 30 segundos en un equipo de desarrollo estándar.
- **SC-007**: Una búsqueda sobre el corpus completo responde en menos de 2 segundos, sin contar el arranque en frío del
  modelo.
- **SC-008**: Ejecutar dos veces el cálculo de embeddings sobre documentos ya indexados no realiza ninguna llamada al
  modelo en la segunda ejecución.
- **SC-009**: La prueba de integración documento → `ingest` → embeddings pasa en CI sin acceso a un servidor de modelos
  real, y el módulo mantiene una cobertura de líneas de al menos el 80 %.

## Assumptions

- **Estado del documento**: el esquema actual (V2/V3) no tiene columna de estado; los estados «pendiente, indexado y error»
  de la historia se materializan con una migración `V4` del módulo `retrieval` (estado y causa del último error en
  `retrieval.document`), sin tocar las migraciones de otros módulos ni el índice HNSW. Los documentos existentes se
  consideran pendientes o indexados según si todos sus chunks tienen vector.
- **Dimensión**: se mantiene `vector(1024)` (LAB-19); `bge-m3` produce 1 024 dimensiones. Un modelo de otra dimensión
  exigiría migración y ADR, y queda fuera.
- **Disparo del cálculo**: la ingesta de LAB-20 guarda chunks pendientes y esta historia los completa en un paso posterior
  invocable (caso de uso de `retrieval`), no dentro de la transacción de la ingesta, para que una caída del servidor de
  modelos no impida guardar el documento. Si el paso se encadena automáticamente tras una ingesta o corre en segundo plano
  se decide en el plan; en cualquier caso el estado pendiente/error es la fuente de verdad y todo es reintentable.
- **Entrada del reindexado y la búsqueda**: igual que la ingesta de LAB-20, se entregan como casos de uso de `retrieval`
  (puertos de entrada) verificados con pruebas automáticas, sin endpoint REST ni pantalla. El «comando de reindexado» es
  un caso de uso invocable (y, si el plan lo estima oportuno, expuesto como tarea administrativa interna); el endpoint
  REST y la pantalla de administración son LAB-25.
- **Búsqueda mínima**: este alcance incluye únicamente la búsqueda vectorial por similitud de coseno que exigen los
  criterios («al buscar», «solo compara vectores del mismo modelo», «`embedding IS NOT NULL`»). La recuperación completa
  (filtros por metadatos, híbrida, reordenado, citas) pertenece a historias posteriores de F1/F2.
- **Spring AI**: se usa el `EmbeddingModel` de Spring AI 2.0 con el proveedor Ollama ya presente en el backend (ADR-0004,
  ADR-0003); el acceso queda detrás de un puerto propio.
- **Tests deterministas**: en CI el modelo de embeddings se sustituye por un doble determinista que produce vectores de 1
  024 valores a partir del texto (Constitución IV); la verificación con Ollama real es manual o de un perfil opcional.
- **Servidor de modelos**: Ollama ya forma parte del perfil `core` de `infra/docker-compose.yml` y descarga `bge-m3` en el
  primer arranque (ADR-0003); esta historia no cambia la infraestructura.
- **Lotes**: los chunks se envían al modelo por lotes con tamaño por defecto de 16, configurable; el valor se afinará con
  medición.
- **Dependencias**: LAB-19 (esquema `retrieval`) y LAB-20 (ingesta y chunks sin vector, migración V3), ambas fusionadas en
  `main`; ADR-0003 (modelos locales), ADR-0015 (propiedad de los datos) y ADR-0016 (ingesta síncrona).
