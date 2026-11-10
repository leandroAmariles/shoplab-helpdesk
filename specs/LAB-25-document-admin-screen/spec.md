# Feature Specification: Pantalla de administración de documentos

**Feature Branch**: `feature/LAB-25-document-admin-screen`

**Created**: 2026-11-10

**Status**: Draft

**Input**: User description: "LAB-25 (F1-08) Pantalla de administración de documentos. Como administrador, quiero subir
documentos desde una pantalla. Criterios: endpoint REST de subida en el módulo `retrieval` (multipart) que recibe el
documento y dispara la ingesta (LAB-20) y los embeddings (LAB-21); upload con progreso; lista de documentos con estado
(pendiente, indexado, error) y número de chunks." Depende de LAB-13 (app Angular base) y LAB-21 (embeddings).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Subir un documento desde una pantalla (Priority: P1)

Como administrador, quiero elegir un archivo PDF o Markdown en una pantalla y subirlo, para que entre a la base de
conocimiento sin usar herramientas de línea de comandos.

Hoy un documento solo llega a la base de conocimiento mediante la ingesta (LAB-20) y los embeddings (LAB-21), que no
tienen una entrada de usuario. Esta historia añade esa entrada: al subir el archivo se trocea y se guarda, y a continuación
se calculan sus embeddings para que el chat (LAB-22) pueda usarlo.

**Why this priority**: Es el criterio de aceptación principal; sin subida no hay forma de alimentar la base de
conocimiento desde la aplicación.

**Independent Test**: Abrir la pantalla, subir un archivo Markdown válido y comprobar que aparece en la lista y, al cabo
de unos segundos, queda indexado y el chat puede citarlo.

**Acceptance Scenarios**:

1. **Given** la pantalla de administración, **When** el administrador elige un PDF o un Markdown válido y lo sube,
   **Then** el documento se ingiere, se guarda con sus chunks y se inicia el cálculo de embeddings sin que el
   administrador tenga que hacer nada más.
2. **Given** una subida terminada, **When** se muestra el resultado, **Then** el administrador ve el nombre del documento y
   el número de chunks generados.
3. **Given** un documento con el mismo nombre que uno ya guardado, **When** se sube, **Then** reemplaza al anterior (misma
   semántica que la ingesta de LAB-20) y la pantalla lo indica como actualización, no como duplicado.
4. **Given** un archivo de formato no admitido, vacío, sin texto extraíble o por encima del tamaño máximo, **When** se
   sube, **Then** se rechaza sin guardar nada y la pantalla muestra, en español, la causa concreta.
5. **Given** que el servicio de ingesta no está disponible, **When** se sube un documento, **Then** no se guarda nada, la
   pantalla indica que se puede reintentar y el administrador conserva el archivo elegido para hacerlo.
6. **Given** varios archivos elegidos a la vez, **When** se suben, **Then** cada uno se procesa y se informa por separado,
   y el fallo de uno no impide los demás.

---

### User Story 2 - Ver el progreso de la subida (Priority: P1)

Como administrador, quiero ver cuánto de mi archivo se ha enviado y en qué paso está, para saber que la subida avanza y no
repetirla por impaciencia.

**Why this priority**: Es criterio de aceptación explícito; los PDF grandes tardan y una pantalla sin señales parece
colgada.

**Independent Test**: Subir un archivo grande y comprobar que se ve el avance del envío y, una vez enviado, un estado de
«procesando» hasta que el servidor responde.

**Acceptance Scenarios**:

1. **Given** una subida en curso, **When** se envían los datos, **Then** la pantalla muestra el porcentaje enviado, que
   crece sin retroceder.
2. **Given** el archivo ya enviado y el servidor aún procesándolo (troceado), **When** no hay más bytes que enviar,
   **Then** la pantalla pasa de «enviando» a «procesando» en lugar de quedarse en 100 % sin explicación.
3. **Given** una subida en curso, **When** el administrador la cancela, **Then** el envío se interrumpe, la pantalla lo
   refleja y no queda un documento a medias en la lista.
4. **Given** un fallo de red durante el envío, **When** ocurre, **Then** la subida se marca como fallida con un mensaje en
   español y se puede reintentar.
5. **Given** el progreso, **When** se usa un lector de pantalla, **Then** el avance y el estado se anuncian de forma
   accesible (rol y valor de progreso, no solo color).

---

### User Story 3 - Lista de documentos con estado y chunks (Priority: P1)

Como administrador, quiero ver todos los documentos de la base de conocimiento con su estado (pendiente, indexado o error)
y su número de chunks, para saber qué puede usar el chat y qué hay que corregir.

**Why this priority**: Es criterio de aceptación explícito y la única forma de saber si un documento ya es útil: el
cálculo de embeddings ocurre después de la subida.

**Independent Test**: Con documentos en los tres estados, abrir la pantalla y comprobar que cada fila muestra su nombre,
estado y número de chunks, y que la causa aparece en los que están en error.

**Acceptance Scenarios**:

1. **Given** documentos ya ingeridos, **When** el administrador abre la pantalla, **Then** ve una fila por documento con
   nombre, estado (pendiente, indexado, error) y número de chunks.
2. **Given** un documento en error, **When** se muestra la lista, **Then** la fila incluye la causa del último fallo
   (la que registró LAB-21) en español y sin texto interno del sistema.
3. **Given** un documento recién subido en estado pendiente, **When** termina el cálculo de embeddings, **Then** la fila
   pasa a «indexado» sin recargar la página.
4. **Given** una base de conocimiento sin documentos, **When** se abre la pantalla, **Then** se muestra un estado vacío que
   invita a subir el primero.
5. **Given** muchos documentos, **When** se muestra la lista, **Then** está ordenada de forma estable (el más reciente
   primero) y la pantalla sigue siendo usable.
6. **Given** un documento en error, **When** el administrador sube de nuevo el mismo archivo, **Then** la fila vuelve a
   pendiente y se reevalúa su estado.
7. **Given** la lista, **When** se usa con teclado o lector de pantalla, **Then** el estado se comunica con texto además
   del color y la tabla tiene cabeceras y nombre accesibles.

---

### User Story 4 - Navegación y límites de la pantalla (Priority: P2)

Como administrador, quiero llegar a la pantalla desde la navegación de la aplicación y que se comporte bien en pantallas
pequeñas, para usarla sin buscar enlaces ocultos.

**Why this priority**: Hace descubrible la función, pero no bloquea el valor principal.

**Independent Test**: Desde la página de inicio, llegar a la pantalla por la navegación y abrirla en un ancho de móvil.

**Acceptance Scenarios**:

1. **Given** la aplicación, **When** el administrador usa la navegación, **Then** encuentra el acceso a «Documentos» en
   español y la ruta propia de la pantalla.
2. **Given** un ancho de pantalla reducido, **When** se abre la pantalla, **Then** la lista y el área de subida se
   adaptan sin scroll horizontal de la página.
3. **Given** una ruta inexistente, **When** se visita, **Then** se mantiene el comportamiento de «no encontrado» vigente.

---

### Edge Cases

- **Mismo documento subido dos veces seguidas**: la segunda subida reemplaza a la primera; si la indexación de la primera
  seguía en curso, el resultado final es coherente (el documento queda con los chunks de la última versión).
- **Subida mientras otro proceso de embeddings está en curso**: el documento queda pendiente y se indexa cuando es posible
  (reutiliza el reintento de LAB-21); la pantalla muestra «pendiente», no un error.
- **Nombre de archivo con ruta, caracteres especiales o muy largo**: el nombre se toma sin ruta, se normaliza como en
  LAB-20 y se rechaza con causa clara si no es válido; nunca escribe fuera de la base de conocimiento.
- **Archivo cuyo contenido no corresponde a su extensión**: se rechaza por la regla de LAB-20 (formato no admitido o
  documento inválido).
- **Archivo por encima del tamaño máximo**: se rechaza antes de enviarlo completo cuando la pantalla puede saberlo, y el
  servidor lo rechaza igualmente si llega.
- **Documento en error por causa transitoria** (modelo de embeddings caído): pasa a «indexado» al reindexarse, sin
  necesidad de subirlo otra vez.
- **Pérdida de conexión al consultar la lista**: se muestra el último estado conocido y un aviso con opción de
  reintentar, sin vaciar la lista.
- **Carga concurrente de varios administradores**: cada uno ve el estado real y las subidas no se pisan entre sí.
- **Texto de documentos en registros y métricas**: el contenido de los documentos no aparece en registros ni como
  etiqueta de métricas (Constitución VI); se registra solo el nombre, el resultado y el número de chunks.
- **Idioma**: todas las etiquetas y mensajes nuevos de la interfaz están en español.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema MUST ofrecer una operación de subida de documentos que reciba un archivo PDF o Markdown y lo
  procese con la ingesta existente (LAB-20), reemplazando el documento del mismo nombre.
- **FR-002**: Tras ingerir un documento, el sistema MUST iniciar el cálculo de sus embeddings (LAB-21) sin que la subida
  espere a que termine, y sin que un fallo de embeddings invalide la ingesta.
- **FR-003**: La respuesta de una subida correcta MUST indicar el nombre del documento, su estado y el número de chunks
  generados.
- **FR-004**: El sistema MUST rechazar sin guardar nada los archivos con formato no admitido, vacíos, sin texto
  extraíble, inválidos o por encima del tamaño máximo, con el contrato de errores vigente y un código distinguible por
  causa. Los errores técnicos (ingesta caída) MUST distinguirse de los de negocio (documento rechazado).
- **FR-005**: El sistema MUST ofrecer una operación que liste los documentos de la base de conocimiento con nombre,
  estado (pendiente, indexado, error), número de chunks, causa del último error cuando exista, y fecha de la última
  actualización.
- **FR-006**: El número de chunks de la lista MUST corresponder a los chunks realmente guardados del documento, y el estado
  MUST ser el que registra el módulo `retrieval` (LAB-21), sin recalcularse en la pantalla.
- **FR-007**: El módulo `retrieval` MUST seguir siendo el único que lee y escribe su esquema (ADR-0015); la pantalla y
  cualquier otro módulo acceden solo por esta operación.
- **FR-008**: La pantalla MUST permitir elegir uno o varios archivos (selector y arrastrar y soltar), validar
  formato y tamaño antes de enviar y mostrar la causa del rechazo en español.
- **FR-009**: La pantalla MUST mostrar el progreso del envío (porcentaje) y un estado de «procesando» entre el fin del
  envío y la respuesta, y permitir cancelar una subida en curso.
- **FR-010**: La pantalla MUST mostrar la lista de documentos con estado, chunks y causa de error, un estado vacío y un
  estado de error de carga con opción de reintentar.
- **FR-011**: La pantalla MUST actualizar el estado de los documentos pendientes sin recargar la página, hasta que dejen de
  estar pendientes, sin consultar de forma indefinida ni excesiva.
- **FR-012**: La subida MUST seguir el contrato de errores del backend (módulo compartido, ADR-0010): errores tipados con
  códigos `SLHD_<MÓDULO>_NNNNN` y mensajes en español, sin exponer trazas ni texto interno.
- **FR-013**: Las llamadas bloqueantes (ficheros, JDBC) MUST aislarse del hilo reactivo con el ayudante de LAB-111
  (ADR-0011) y BlockHound MUST permanecer en verde.
- **FR-014**: El sistema MUST registrar métricas de uso (subidas por resultado, duración, documentos por estado) sin
  nombres de cliente ni texto de documentos como etiquetas, e identificar cada petición para su seguimiento.
- **FR-015**: La pantalla MUST ser accesible (teclado, etiquetas, roles de progreso, estado comunicado también con texto) y
  adaptable a anchos reducidos, con una ruta propia y acceso desde la navegación.
- **FR-016**: Los criterios de aceptación de LAB-12, LAB-20, LAB-21, LAB-22, LAB-23 y LAB-24 MUST seguir cumpliéndose;
  ninguna operación existente cambia de contrato.

### Key Entities *(include if feature involves data)*

- **Documento (`retrieval.document`)**: registro del archivo ingerido, con nombre único, estado de indexación
  (pendiente, indexado, error), causa del último error y fecha de actualización (LAB-20, LAB-21). No se crean campos
  nuevos salvo que falte la fecha de actualización.
- **Chunk (`retrieval.chunk`)**: fragmento del documento; solo se necesita su recuento por documento.
- **Subida (en la pantalla)**: intento de enviar un archivo, con nombre, tamaño, porcentaje enviado y estado (enviando,
  procesando, completada, fallida, cancelada). Vive en el navegador; no se persiste.
- **Resumen de documento (respuesta de la lista)**: nombre, estado, número de chunks, causa del error y fecha; es una
  proyección de solo lectura de los datos anteriores.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un administrador sube un documento válido de hasta 5 MB y lo ve indexado en la lista en menos de 2 minutos
  desde que lo elige, sin ejecutar ningún comando.
- **SC-002**: El 100 % de los archivos rechazados (formato, vacío, sin texto, tamaño, inválido) muestran su causa concreta
  en español y ninguno deja datos parciales en la base de conocimiento.
- **SC-003**: El 95 % de las subidas de hasta 5 MB devuelven la respuesta de ingesta en menos de 10 segundos, sin contar el
  cálculo de embeddings.
- **SC-004**: La lista muestra el estado y el número de chunks correctos en el 100 % de los documentos de una base de
  100 documentos, y se carga en menos de 2 segundos.
- **SC-005**: Tras terminar la indexación, el estado visible cambia en menos de 10 segundos sin acción del administrador.
- **SC-006**: Toda la funcionalidad es operable solo con teclado y los estados y el progreso se anuncian a un lector de
  pantalla, sin depender del color.
- **SC-007**: Las pruebas existentes de chat, ingesta y embeddings siguen en verde y BlockHound no detecta bloqueos.

## Assumptions

- **Sin autenticación en F1**: el proyecto todavía no tiene usuarios ni roles. «Administrador» describe al actor, pero no
  se implementa control de acceso en esta historia; la pantalla y la operación quedan abiertas en el entorno de desarrollo
  único (ADR-0008). El control de acceso se aborda en la fase de seguridad y no se simula aquí.
- **Formatos y tamaño**: se admiten los formatos y el límite de tamaño que ya define la ingesta (LAB-20); esta historia no
  los amplía. La pantalla refleja esos valores sin duplicarlos de forma divergente.
- **Sin borrado ni edición**: eliminar documentos, descargarlos, previsualizar chunks o reindexar manualmente quedan fuera
  de alcance; solo se sube, reemplaza y consulta. El reindexado sigue siendo el de LAB-21.
- **Ingesta síncrona, embeddings asíncronos**: la subida responde cuando el documento está troceado y guardado
  (ADR-0016); los embeddings se calculan después (ADR-0017), de ahí el estado «pendiente».
- **Actualización del estado por consulta periódica** en la pantalla; no hay notificaciones en tiempo real en esta fase.
- **Subida de varios archivos**: se procesan de forma secuencial o con baja concurrencia para no saturar a `ingest`; la
  cifra exacta se decide en el plan.
- **Dependencias**: LAB-13 (aplicación Angular base y navegación), LAB-20 (ingesta), LAB-21 (embeddings y estado del
  documento) y LAB-111 (backend reactivo). El cuaderno de chunking del objetivo del sprint es una historia aparte.
- **Datos de prueba**: solo documentos del corpus del proyecto (LAB-18); nunca datos reales de clientes.
