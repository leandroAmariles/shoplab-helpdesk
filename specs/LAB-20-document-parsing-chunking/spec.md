# Feature Specification: Parseo y chunking de documentos

**Feature Branch**: `feature/LAB-20-document-parsing-chunking`

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "LAB-20 (F1-03) Parseo y chunking de documentos (FastAPI sin estado). Como administrador,
quiero que un documento subido se parsee y trocee. Criterios: servicio FastAPI `POST /ingest` que recibe un documento y
devuelve sus chunks con metadatos (documento, sección, posición); no guarda nada. Parseo de PDF y Markdown. Chunking por
encabezados con solape configurable. `retrieval` llama a `ingest` y guarda el resultado; el contrato de `POST /ingest`
queda en OpenAPI." Depende de LAB-19 (esquema `retrieval`), ya fusionada en `main`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Trocear un documento Markdown con metadatos (Priority: P1)

Como administrador, quiero que un documento Markdown se trocee por sus encabezados, para que cada fragmento conserve el
tema de la sección a la que pertenece y pueda citarse con precisión en las respuestas del asistente.

El servicio `ingest` recibe el documento, lo parsea y devuelve la lista ordenada de **chunks**. Cada chunk lleva su texto
y sus metadatos: el documento de origen, la sección (la ruta de encabezados que lo contiene, p. ej.
`Devoluciones > Plazos`) y su posición dentro del documento. El servicio no guarda nada: responde y olvida.

**Why this priority**: Es el núcleo de la historia y el formato de la mayor parte del corpus (`data/kb`). Con solo esto ya
se puede ingerir una política y obtener chunks citables.

**Independent Test**: Enviar a `POST /ingest` una política Markdown de `data/kb` y comprobar que la respuesta es una
lista de chunks ordenados, cada uno con texto, documento, sección y posición, y que no queda ningún estado en el servicio.

**Acceptance Scenarios**:

1. **Given** un Markdown con encabezados de varios niveles, **When** se envía a `POST /ingest`, **Then** se devuelve un
   chunk por sección (o varios, si la sección supera el tamaño máximo) y la sección de cada chunk es su ruta de
   encabezados.
2. **Given** la respuesta, **When** se recorren los chunks, **Then** sus posiciones son consecutivas desde 0, siguen el
   orden del documento y ninguna se repite.
3. **Given** una sección más larga que el tamaño máximo de chunk, **When** se trocea, **Then** se divide en varios chunks
   de la misma sección, cortando en límites de párrafo o frase cuando es posible, sin superar el tamaño máximo.
4. **Given** un solape configurado de N caracteres, **When** una sección se divide en varios chunks, **Then** cada chunk
   (salvo el primero de la sección) empieza repitiendo los últimos N caracteres del anterior.
5. **Given** un Markdown sin ningún encabezado, **When** se trocea, **Then** se devuelven chunks por tamaño, con la
   sección vacía, en lugar de fallar.
6. **Given** el mismo documento enviado dos veces, **When** se comparan las respuestas, **Then** son idénticas.
7. **Given** un encabezado sin texto debajo, **When** se trocea, **Then** no genera un chunk vacío.

---

### User Story 2 - Parsear y trocear un PDF (Priority: P1)

Como administrador, quiero subir también PDF, para ingerir las políticas que solo existen en ese formato o que se
publican como PDF, con los mismos metadatos y el mismo troceado que un Markdown.

**Why this priority**: Es un criterio de aceptación explícito y el corpus incluye PDF (varias políticas existen en `md` y
`pdf`). Es independiente del parseo de Markdown: solo comparten el troceado.

**Independent Test**: Enviar a `POST /ingest` un PDF de `data/kb` y comprobar que devuelve chunks con el texto extraído,
la sección detectada por sus títulos y la posición.

**Acceptance Scenarios**:

1. **Given** un PDF con texto, **When** se envía a `POST /ingest`, **Then** se extrae el texto en orden de lectura y se
   devuelve troceado con los mismos metadatos que un Markdown.
2. **Given** un PDF cuyos títulos se distinguen del cuerpo (por tamaño o formato), **When** se trocea, **Then** esos
   títulos se usan como encabezados de sección.
3. **Given** un PDF sin estructura de títulos reconocible, **When** se trocea, **Then** se devuelven chunks por tamaño
   con la sección vacía.
4. **Given** un PDF sin texto extraíble (p. ej. solo imágenes escaneadas), **When** se envía, **Then** se rechaza con un
   error que lo explica; no se devuelve una lista vacía como si hubiera ido bien.
5. **Given** un PDF corrupto o protegido con contraseña, **When** se envía, **Then** se rechaza con un error de
   documento no válido.

---

### User Story 3 - `retrieval` entrega el documento a `ingest` y guarda el resultado (Priority: P1)

Como administrador, quiero que el módulo `retrieval` envíe el documento a `ingest` y guarde los chunks devueltos, para que
el contenido quede en la base de conocimiento sin que `ingest` tenga acceso a la base de datos (ADR-0015).

`retrieval` registra el documento en `retrieval.document` y los chunks en `retrieval.chunk`. Los chunks se guardan sin
vector todavía: el embedding lo genera una historia posterior de F1 y completa esas filas.

**Why this priority**: Es un criterio de aceptación explícito y cierra el flujo: sin él, el troceado no llega a ningún
sitio. Mantiene a `retrieval` como único dueño del esquema.

**Independent Test**: Con `ingest` real o simulado, ingerir un documento desde `retrieval` y comprobar que
`retrieval.document` tiene una fila y `retrieval.chunk` tiene los chunks devueltos, con texto, posición y metadatos.

**Acceptance Scenarios**:

1. **Given** un documento válido, **When** `retrieval` lo ingiere, **Then** se crea una fila en `retrieval.document`
   (origen, título y metadatos) y una fila en `retrieval.chunk` por cada chunk devuelto, con su texto, su posición y sus
   metadatos (sección incluida).
2. **Given** un documento ya ingerido, **When** se vuelve a ingerir el mismo origen, **Then** no se duplica el documento:
   sus chunks anteriores se reemplazan por los nuevos y el resultado es el mismo que tras una primera ingesta.
3. **Given** que `ingest` no responde, tarda demasiado o devuelve un error, **When** `retrieval` intenta ingerir,
   **Then** no se guarda nada (ni documento ni chunks parciales) y el administrador recibe un error que indica que el
   servicio de parseo falló.
4. **Given** que `ingest` rechaza el documento por inválido, **When** `retrieval` lo ingiere, **Then** el error de
   `ingest` llega al administrador como error de documento no válido, no como fallo interno.
5. **Given** una ingesta que falla al guardar a medias, **When** termina, **Then** la base de datos queda como antes
   (todo o nada).
6. **Given** el código de `ingest`, **When** se revisa, **Then** no abre conexiones a la base de datos ni tiene
   credenciales de ella.

---

### User Story 4 - Contrato `POST /ingest` publicado en OpenAPI (Priority: P2)

Como desarrollador, quiero el contrato de `POST /ingest` descrito en OpenAPI, para que `retrieval` y cualquier cliente
futuro dependan de un contrato explícito y verificable y no del código de `ingest` (ADR-0015).

**Why this priority**: Es un criterio de aceptación explícito, pero el comportamiento funcional (historias 1 a 3) ya
tiene valor sin él; el contrato lo formaliza y evita que se rompa sin avisar.

**Independent Test**: Obtener la descripción OpenAPI del servicio y comprobar que `POST /ingest` describe la petición, la
respuesta con chunks y metadatos, y los errores; y que el cliente de `retrieval` y el servicio coinciden con ella.

**Acceptance Scenarios**:

1. **Given** el servicio `ingest`, **When** se consulta su descripción OpenAPI, **Then** incluye `POST /ingest` con el
   formato de la petición, la respuesta (chunks con documento, sección, posición y texto) y los códigos de error.
2. **Given** el contrato versionado en el repositorio, **When** cambia el servicio sin actualizarlo, **Then** una
   comprobación automática en CI falla.
3. **Given** el contrato, **When** `retrieval` se prueba contra él, **Then** su cliente produce peticiones y entiende
   respuestas conformes al contrato.

---

### Edge Cases

- **Tamaño del documento**: un documento por encima del límite configurado se rechaza con un error de «documento
  demasiado grande» antes de procesarlo.
- **Formato no soportado**: cualquier archivo cuya extensión no sea `.pdf` ni `.md` se rechaza con un error de formato no
  soportado, sin intentar adivinar el contenido. Un archivo con extensión válida pero contenido que no le corresponde
  (p. ej. un `.pdf` que no es un PDF) se rechaza como documento no válido.
- **Nombre del archivo con ruta** (`carpeta/politica.md`): se usa solo el nombre base como referencia del documento.
- **Documento vacío o solo espacios**: se rechaza como documento no válido; no se devuelve una lista vacía.
- **Codificación**: el Markdown se interpreta como UTF-8; una codificación inválida se rechaza como documento no válido.
  Los caracteres con tilde y la «ñ» del corpus en español se conservan intactos.
- **Encabezados saltados** (de `#` a `###` sin `##`): la ruta de sección usa los encabezados presentes, sin inventar
  niveles intermedios.
- **Bloques que no deben partirse**: tablas, listas cortas y bloques de código se mantienen enteros en un chunk si caben;
  si no caben, se dividen en su límite natural más cercano.
- **Solape mayor o igual que el tamaño máximo**: configuración inválida; el servicio rechaza arrancar con ella (o la
  petición, si el solape viene por petición) con un mensaje claro.
- **Carga concurrente**: varias peticiones a la vez no se afectan entre sí, porque el servicio no guarda estado.
- **Reingesta con menos chunks**: si la nueva versión del documento produce menos chunks, los sobrantes de la versión
  anterior desaparecen.
- **Documento ingerido sin vector aún**: sus chunks existen en `retrieval.chunk` pero no participan en la búsqueda hasta
  que se les genere el vector.
- **Fallo parcial de `ingest`** a mitad de respuesta o respuesta malformada: se trata como error de `ingest`; no se guarda
  nada.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El servicio `ingest` MUST exponer `POST /ingest`, que recibe un documento (PDF o Markdown) junto con su
  nombre y devuelve la lista ordenada de chunks.
- **FR-002**: Cada chunk MUST incluir: texto, documento de origen, sección (ruta de encabezados, vacía si no hay) y
  posición dentro del documento (entero desde 0, consecutivo, sin repeticiones).
- **FR-003**: `ingest` MUST ser sin estado: no persistir documentos ni chunks, no conservar nada entre peticiones y dar la
  misma respuesta para la misma entrada y configuración. Peticiones simultáneas no se afectan entre sí.
- **FR-004**: `ingest` MUST parsear Markdown y detectar sus encabezados como límites de sección.
- **FR-005**: `ingest` MUST parsear PDF extrayendo el texto en orden de lectura y reconociendo los títulos cuando el
  formato los distingue; sin títulos reconocibles, trocea por tamaño.
- **FR-006**: El chunking MUST ser por encabezados: un chunk nunca mezcla texto de dos secciones distintas.
- **FR-007**: Una sección que supera el tamaño máximo MUST dividirse en varios chunks sin superar ese tamaño, cortando
  preferentemente en límites de párrafo o frase.
- **FR-008**: El solape entre chunks consecutivos de una misma sección MUST ser configurable; con solape 0, no hay
  repetición.
- **FR-009**: El tamaño máximo de chunk, el solape y el tamaño máximo de documento MUST ser configurables, con valores por
  defecto de 1 000 caracteres, 100 caracteres y 10 MiB respectivamente; el tamaño máximo de chunk MUST ser al menos 100 y
  una combinación inválida (tamaño máximo < 100, solape negativo o solape ≥ tamaño máximo) MUST rechazarse.
- **FR-010**: `ingest` MUST rechazar con un error propio y distinguible: formato no soportado, documento vacío o sin
  texto extraíble, documento corrupto o protegido, y documento demasiado grande. Ninguno se responde como éxito con lista
  vacía.
- **FR-011**: El módulo `retrieval` MUST llamar a `ingest` y guardar el resultado en `retrieval.document` y
  `retrieval.chunk`; es el único que escribe en ese esquema (LAB-19).
- **FR-012**: `retrieval` MUST guardar un documento y sus chunks de forma atómica: o se guardan todos o ninguno.
- **FR-013**: Reingerir un origen ya existente MUST reemplazar sus chunks por los nuevos y no duplicar el documento
  (`source` es único).
- **FR-014**: Los chunks guardados MUST conservar texto, posición y metadatos (documento, sección); el vector y el modelo
  de embeddings quedan pendientes hasta la historia de embeddings (LAB-21). Para ello, una migración `V3` de `retrieval`
  MUST hacer opcionales `embedding` y `embedding_model` (ambas presentes o ambas ausentes), relajando el FR-003 de LAB-19,
  sin tocar el índice HNSW ni las migraciones de otros módulos.
- **FR-015**: Un fallo, tiempo de espera o error de `ingest` MUST traducirse en `retrieval` a un error tipado que no
  guarda nada; un rechazo de documento inválido MUST distinguirse de un fallo del servicio.
- **FR-016**: El contrato de `POST /ingest` MUST publicarse en OpenAPI (petición, respuesta y errores) y estar versionado
  en el repositorio; CI MUST fallar si el servicio y el contrato difieren.
- **FR-017**: `retrieval` MUST depender del contrato de `ingest` mediante un puerto propio (la llamada HTTP queda en
  infraestructura), de modo que el servicio pueda cambiarse o simularse sin tocar el dominio.
- **FR-018**: `ingest` MUST NOT recibir credenciales de base de datos en su código, imagen ni composición local.
- **FR-019**: Esta historia MUST NOT incluir generación de embeddings, búsqueda, OCR ni la interfaz de subida del
  administrador; solo parseo, troceado, su contrato y el guardado en `retrieval`.

### Key Entities *(include if feature involves data)*

- **Documento de entrada**: archivo PDF o Markdown con nombre, que el administrador sube. No se guarda en `ingest`.
- **Chunk (respuesta de `ingest`)**: fragmento de texto con documento de origen, sección (ruta de encabezados) y posición.
  Es un valor sin identidad; `ingest` no le asigna identificador persistente.
- **Documento (`retrieval.document`)**: registro del archivo ingerido: origen único (el nombre del documento), título y
  metadatos. Lo crea o actualiza `retrieval` (LAB-19).
- **Chunk (`retrieval.chunk`)**: fila con texto, posición y metadatos (sección incluida) que `retrieval` guarda a partir
  de la respuesta de `ingest`; el vector se completa más adelante.
- **Contrato OpenAPI de `ingest`**: descripción versionada de `POST /ingest` que comparten el servicio y el cliente de
  `retrieval`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los documentos de `data/kb` (Markdown y PDF) se ingiere sin error y produce al menos un chunk.
- **SC-002**: El 100 % de los chunks devueltos respeta el tamaño máximo configurado, y en el 100 % de las secciones que se
  dividen el solape coincide con el configurado.
- **SC-003**: Ningún chunk contiene texto de dos secciones distintas, y las posiciones son consecutivas desde 0 en el 100 %
  de los documentos del corpus.
- **SC-004**: Ingerir una política típica del corpus (hasta 20 páginas) tarda menos de 5 segundos de extremo a extremo.
- **SC-005**: Enviar dos veces el mismo documento produce respuestas idénticas, y reingerirlo deja en la base de datos el
  mismo número de documentos y chunks que tras la primera vez.
- **SC-006**: El 100 % de las entradas inválidas (formato no soportado, vacío, sin texto, corrupto, demasiado grande) se
  rechaza con un error distinguible; ninguna se responde como éxito con lista vacía.
- **SC-007**: Ante un fallo de `ingest`, el 100 % de los casos deja `retrieval.document` y `retrieval.chunk` sin cambios.
- **SC-008**: El contrato OpenAPI está versionado y CI falla en el 100 % de los cambios del servicio que lo contradicen.
- **SC-009**: Cero conexiones o credenciales de base de datos en `ingest`, comprobado de forma automática.

## Assumptions

- **Sin vector en esta historia (relaja el FR-003 de LAB-19)**: LAB-19 definió `retrieval.chunk.embedding` y
  `embedding_model` como obligatorios (FR-003). Esta historia **relaja ese requisito**: una migración `V3` de `retrieval`
  hace ambas columnas opcionales con una restricción «ambas presentes o ambas ausentes», y el índice HNSW se mantiene sin
  cambios porque ignora las filas sin vector. Es necesaria porque LAB-20 guarda chunks antes de que exista el embedding
  (LAB-21, que depende de LAB-20) y porque la pantalla de administración (LAB-25) muestra documentos en estado pendiente,
  indexado o error, lo que presupone chunks sin vector. Alternativa descartada: no guardar nada hasta tener el vector,
  que contradice el criterio «`retrieval` llama a `ingest` y guarda el resultado».
- **Trabajo derivado en LAB-21**: la historia de embeddings completa los chunks pendientes (los que tienen
  `embedding IS NULL`) y la búsqueda considera solo los chunks con `embedding IS NOT NULL`. Esta historia no lo implementa;
  se registra como criterio de LAB-21.
- **Entrada de la ingesta**: esta historia entrega el caso de uso de ingesta en `retrieval` (puerto de entrada), verificado
  con pruebas automáticas, sin endpoint REST ni pantalla. La pantalla de administración y su subida con progreso son
  LAB-25; el endpoint REST de subida del backend se propone como criterio de LAB-25 (ninguna historia lo nombra hoy). El
  «administrador» es el actor que dispara la ingesta.
- **Origen del documento**: el nombre base del archivo (p. ej. `returns-01-return-policy.md`; `ingest` descarta
  cualquier ruta y el caso de uso de `retrieval` rechaza nombres con separadores de ruta) es la referencia única de
  `retrieval.document.source` y el título es el primer encabezado de nivel 1 o, en su defecto, el nombre sin extensión.
- **Metadatos del chunk**: `document` (origen), `section` (ruta de encabezados separada por « > ») y `position`. Los
  metadatos del documento del corpus (tema, tipo) los aporta quien ingiere o el índice `data/kb/INDEX.md` en una historia
  posterior; aquí no se infieren.
- **Valores por defecto**: tamaño máximo de chunk de 1 000 caracteres, solape de 100 y documento máximo de 10 MB; todos
  configurables. Se miden en caracteres porque el tokenizador depende del modelo (ADR-0003) y aún no está fijado.
- **Formatos**: PDF con capa de texto y de una sola columna, y Markdown en UTF-8. Los PDF de varias columnas se leen en
  el orden en que el PDF los almacena; no se reordenan. OCR de PDF escaneados, DOCX, HTML y otros formatos quedan fuera.
- **Idioma**: el corpus está en español; el troceado no depende del idioma.
- **Servicio nuevo**: hoy `ingest` es un marcador de posición (`ingest/Dockerfile`, sin código). Esta historia crea el
  servicio FastAPI, con su imagen ya publicable por los flujos existentes; su despliegue en `shoplab-infra` se revisa en la
  historia que lo despliegue (LAB-19, FR-013).
- **Comunicación**: `retrieval` llama a `ingest` de forma síncrona por HTTP; la dirección de `ingest` es configuración del
  backend. No hay reintentos automáticos en esta historia: un fallo se informa al administrador, que puede reintentar.
- **Dependencia**: LAB-19 (esquema `retrieval`), ya fusionada en `main`; y ADR-0015 para la propiedad de los datos.
