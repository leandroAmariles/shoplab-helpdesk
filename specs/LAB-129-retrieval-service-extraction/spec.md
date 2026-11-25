# Feature Specification: LAB-129 Extraer retrieval-service del monolito

**Feature Branch**: `feature/LAB-129-retrieval-service-extraction`

**Created**: 2026-11-25

**Status**: Draft

**Input**: User description: "LAB-129 (F5-22) Como desarrollador, quiero la base de conocimiento en su propio servicio, para que el trabajo de F3 (búsqueda híbrida, reranking, contextual retrieval) evolucione y se despliegue sin tocar el chat. Criterios de aceptación: repo `shoplab-retrieval-service` creado desde la plantilla de LAB-103, con el paquete `retrieval` y `db/migration/retrieval`; base de datos propia con los datos del esquema `retrieval` migrados; publica `POST/GET /api/documents` y `GET /api/knowledge/search` con su OpenAPI en `shoplab-contracts` y los stubs de Spring Cloud Contract que usa chat; llama a `ingest` por HTTP y calcula embeddings a través de LiteLLM; el cerrojo de indexado en memoria pasa a uno en base de datos (ADR-0017); el monolito llama a `retrieval-service` por HTTP con el cliente generado en lugar de `KnowledgeSearchApi`; el gateway mueve `/api/documents/**` y `/api/knowledge/**` y el módulo `retrieval` se retira del monolito; desplegado en dev por el ApplicationSet de LAB-104; las métricas de LAB-28 dan los mismos resultados que antes. Correcciones previas: plantilla de LAB-103 y `shoplab-ci`, `smoke` de `shoplab-infra` y los PostgreSQL del compose local. Depende de LAB-103, LAB-104, LAB-106, LAB-107 y LAB-110. Va antes de LAB-59."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - La plataforma permite crear y desplegar un servicio nuevo (Priority: P1)

Como desarrollador, quiero que la plantilla de servicio, los flujos compartidos, la comprobación de infraestructura y el entorno local funcionen, para crear `retrieval-service` sin arreglar la plataforma a mitad de la extracción.

**Why this priority**: Son correcciones detectadas en LAB-107 que bloquean todo lo demás: sin ellas el repo nuevo no construye su imagen, no despliega, rechaza las peticiones o no puede fusionar cambios de infraestructura. La historia exige corregirlas antes de crear el repo.

**Independent Test**: Generar `shoplab-retrieval-service` desde la plantilla y, antes de mover código, sin editar nada más que el nombre: pasa su CI, publica una release, su imagen versionada se firma, se verifica y queda lista para desplegar. En paralelo, un PR que toque `k8s/` obtiene `ci-gate` en verde y el compose local arranca en una máquina sin `.env`.

**Acceptance Scenarios**:

1. **Given** que no existe un repositorio plantilla, **When** se publica `shoplab-service-template`, **Then** un servicio nuevo se puede crear desde él y la guía de LAB-103 apunta a un repositorio que existe.
2. **Given** un servicio recién creado desde la plantilla, **When** se construye su imagen, **Then** la construcción funciona porque el repositorio incluye todo lo que su receta de imagen copia.
3. **Given** un servicio recién creado, **When** se publica una release `vX.Y.Z`, **Then** existe la imagen `:vX.Y.Z` que exige su flujo de despliegue y su firma se verifica sin fallar por diferencias de mayúsculas.
4. **Given** un servicio recién creado, **When** recibe una petición sin token mientras no existe el login (LAB-61), **Then** la atiende en lugar de rechazarla.
5. **Given** un servicio recién creado, **When** corre su CI, **Then** usa Spring Boot 4.x, descarga `shoplab-contracts` de GitHub Packages con credenciales y el análisis estático corre con una versión vigente de la acción.
6. **Given** un PR que cambia `k8s/` en `shoplab-infra`, **When** corre su CI, **Then** `ci-gate` termina en verde y deja fusionar: con el `smoke` pasando dentro de su límite o, si no se pudo solucionar, sin ese paso.
7. **Given** una máquina sin `.env` que fije la imagen de PostgreSQL, **When** se levanta el compose de `shoplab-helpdesk`, **Then** todos sus PostgreSQL arrancan, y los volúmenes existentes de PostgreSQL 17 conservan sus datos.

---

### User Story 2 - La base de conocimiento vive en su propio servicio con sus datos (Priority: P1)

Como desarrollador, quiero `retrieval-service` en su repositorio, con su base de datos y la misma API que hoy ofrece el monolito, para evolucionar la recuperación sin tocar el chat.

**Why this priority**: Es el núcleo de la historia. Con el servicio funcionando en paralelo al monolito ya se puede comparar su comportamiento sin cambiar nada para los usuarios.

**Independent Test**: Levantar el servicio con una copia migrada de los datos, junto al monolito intacto. Ejecutar contra ambos la subida, el listado y la búsqueda, y la evaluación de recuperación; las respuestas y las métricas coinciden.

**Acceptance Scenarios**:

1. **Given** el repositorio `shoplab-retrieval-service` creado desde la plantilla, **When** se construye y se prueba, **Then** contiene el código y las migraciones de `retrieval` y no depende de código del monolito.
2. **Given** los documentos, fragmentos, vectores y estados del esquema `retrieval`, **When** se migran a la base de datos propia, **Then** los recuentos y el contenido coinciden y no se recalcula ningún embedding.
3. **Given** el servicio en marcha, **When** se sube un documento, **Then** lo trocea `ingest` por HTTP, los embeddings se calculan a través del gateway de modelos y el documento pasa a indexado, como hoy.
4. **Given** una misma petición a `POST /api/documents`, `GET /api/documents` o `GET /api/knowledge/search`, **When** la atiende el servicio, **Then** el estado, el cuerpo y los códigos de error son los mismos que en el monolito.
5. **Given** una release del servicio, **When** termina su pipeline, **Then** su OpenAPI está en `shoplab-contracts`, el servicio la verifica como productor y quedan publicados los stubs que usa el chat.

---

### User Story 3 - El gateway entrega documentos y búsqueda al servicio en dev (Priority: P1)

Como usuario que administra documentos o evalúa la recuperación, quiero que todo siga igual cuando las rutas pasan al servicio nuevo, para no notar la extracción.

**Why this priority**: Es el momento en que la extracción entra en uso. Depende de US2 y es reversible solo con configuración.

**Independent Test**: En dev, con el servicio desplegado y los datos migrados, cambiar el destino de las dos rutas en el gateway; recorrer la pantalla de documentos y ejecutar la evaluación de recuperación; revertir y comprobar que el monolito vuelve a atenderlas (solo antes de retirar el módulo).

**Acceptance Scenarios**:

1. **Given** el alta de `retrieval-service` en `shoplab-infra`, **When** el ApplicationSet de dev lo recoge, **Then** queda sincronizado y sano, con su base de datos, sus secretos y el acceso a `ingest` y al gateway de modelos.
2. **Given** los datos migrados y el servicio sano, **When** se cambia en el gateway el destino de `/api/documents` y `/api/knowledge`, **Then** esas peticiones las atiende el servicio y el resto sigue en el monolito, sin publicar una versión nueva del gateway ni del frontend.
3. **Given** las rutas movidas, **When** se usa la pantalla de documentos, **Then** se listan los mismos documentos y se pueden subir nuevos.
4. **Given** las rutas movidas, **When** se ejecuta la evaluación de recuperación (LAB-28) con el mismo dataset, **Then** hit rate@k, MRR y recall@k son idénticos a la línea base tomada antes de extraer.
5. **Given** un problema tras mover las rutas y el módulo aún sin retirar, **When** se revierte la configuración, **Then** el monolito vuelve a atenderlas.

---

### User Story 4 - El chat usa el servicio por red y el módulo desaparece del monolito (Priority: P1)

Como cliente en el chat, quiero respuestas con el mismo contexto y las mismas citas aunque la búsqueda ya esté en otro servicio. Como desarrollador, quiero que el monolito no conserve código ni datos de `retrieval`.

**Why this priority**: Sin esto hay dos copias de la base de conocimiento que se separan con la primera subida, y el chat respondería con datos viejos.

**Independent Test**: Con el monolito apuntando al servicio, hacer preguntas conocidas y comparar fragmentos y citas con los de antes; subir un documento nuevo y preguntar por él; parar el servicio y comprobar el error del chat. Verificar que el monolito compila y prueba sin el módulo.

**Acceptance Scenarios**:

1. **Given** el chat en el monolito, **When** necesita contexto, **Then** lo pide a `retrieval-service` por HTTP con el cliente generado desde el contrato publicado, y `KnowledgeSearchApi` deja de existir.
2. **Given** una pregunta con respuesta en la base de conocimiento, **When** se hace en el chat, **Then** se aplican el mismo umbral y presupuesto de contexto y se muestran las mismas citas que antes.
3. **Given** un documento subido después de mover las rutas, **When** se pregunta por su contenido, **Then** el chat lo usa.
4. **Given** el servicio caído o que no responde a tiempo, **When** se hace una pregunta que necesita la base de conocimiento, **Then** el chat devuelve el mismo error tipado de recuperación que hoy (`SLHD_CHAT_00004`) y las cortesías (LAB-114) siguen respondiendo.
5. **Given** las pruebas de contrato del chat, **When** corren, **Then** usan los stubs publicados por `retrieval-service`, no los del monolito.
6. **Given** el módulo retirado, **When** se construye y prueba el monolito, **Then** no queda código, migración, ruta ni configuración de `retrieval` en él.

---

### User Story 5 - El indexado es seguro con más de una instancia (Priority: P2)

Como operador, quiero que dos instancias del servicio (o un despliegue en curso) no indexen a la vez, para no duplicar trabajo ni gasto de modelo.

**Why this priority**: En dev hay una réplica y la comprobación optimista ya protege los datos; el solapamiento real aparece en las actualizaciones y al escalar.

**Independent Test**: Levantar dos instancias contra la misma base de datos con documentos pendientes y comprobar que cada documento se indexa una sola vez; matar la instancia que tiene el turno y comprobar que otra puede continuar.

**Acceptance Scenarios**:

1. **Given** dos instancias sobre la misma base de datos, **When** ambas intentan indexar a la vez, **Then** cada documento pendiente se procesa una sola vez.
2. **Given** una instancia con el turno de indexado, **When** muere antes de terminar, **Then** el turno se libera sin intervención manual y los documentos siguen disponibles para indexar.
3. **Given** un indexado en curso, **When** llega una búsqueda o una subida, **Then** no queda bloqueada por el cerrojo.

---

### Edge Cases

- **Subida durante la migración de datos**: no puede perderse; las subidas se suspenden (o se avisa de no hacerlas) desde la copia hasta que las rutas apuntan al servicio.
- **Migración incompleta o repetida**: si los recuentos o el contenido no coinciden, no se mueven las rutas; repetir la migración no duplica datos.
- **Reversión después de subir documentos al servicio**: esos documentos no vuelven solos al monolito; el procedimiento indica cómo recargarlos.
- **Pregunta larga o con caracteres especiales**: la consulta más larga que el chat puede componer hoy (mensaje máximo más preguntas previas) y los textos con `{ }`, `&`, `+`, `#`, `%` o acentos llegan a la búsqueda sin alterarse ni provocar error por tamaño.
- **Respuesta vacía de la búsqueda**: el chat se comporta como hoy cuando no hay contexto; no se trata como fallo.
- **Índice reconstruido al migrar**: la búsqueda vectorial es aproximada; una diferencia de orden frente a la línea base se investiga y se explica antes de dar por buena la extracción, no se acepta sin más.
- **`ingest` o el gateway de modelos caídos o sin presupuesto**: los mismos errores tipados y estados de documento que hoy; el servicio arranca igual.
- **Archivo del tamaño máximo**: se acepta a través del gateway y del servicio; por encima, el mismo error tipado.
- **Base de datos del servicio sin la extensión vectorial o inaccesible**: el servicio no se declara listo e indica la causa.
- **Reinicio con documentos pendientes**: se retoman al arrancar, como hoy.

## Requirements *(mandatory)*

### Functional Requirements

**Correcciones previas (antes de crear el repositorio)**

- **FR-001**: Debe existir el repositorio plantilla `shoplab-service-template`, y un servicio creado desde él MUST construir su imagen (incluye `mvnw` y `.mvn`), usar Spring Boot 4.x y publicar la imagen `:vX.Y.Z` que su flujo de despliegue exige.
- **FR-002**: La plantilla MUST NOT exigir JWT mientras no exista el login (LAB-61).
- **FR-003**: `java-service.yml` de `shoplab-ci` MUST verificar la firma de la imagen sin fallar por mayúsculas del propietario, entregar credenciales a Maven para descargar `shoplab-contracts` y usar una versión vigente de la acción de Qodana.
- **FR-004**: El `smoke` de `shoplab-infra` MUST dejar de bloquear los cambios: se intenta que vuelva a pasar sin agotar los límites de `terraform apply` o `destroy` y, si no se puede solucionar, se elimina de la CI, conservando su ejecución en local. Esto cambia el criterio de la historia de Jira por decisión del mantenedor (2026-11-25).
- **FR-005**: El compose de `shoplab-helpdesk` MUST arrancar sin que `.env` fije imágenes de PostgreSQL (`litellm-postgres`; revisar `langfuse-postgres`), conservando los volúmenes de PostgreSQL 17 existentes.

**Repositorio y servicio**

- **FR-006**: El servicio MUST vivir en `shoplab-retrieval-service`, creado desde la plantilla, con el paquete `retrieval` y `db/migration/retrieval`, sin dependencias de código del monolito ni de otros servicios.
- **FR-007**: El servicio MUST usar el pipeline reutilizable y el versionado automático del resto de repositorios, con las mismas puertas de calidad (cobertura ≥ 80 %, pruebas de arquitectura, análisis de imagen).
- **FR-008**: `POST /api/documents`, `GET /api/documents` y `GET /api/knowledge/search` MUST conservar parámetros, cuerpos, estados, límites y códigos de error `SLHD_RETR_*`. Las respuestas MUST NOT envolverse en el formato genérico de la plantilla.
- **FR-009**: El servicio MUST llamar a `ingest` por HTTP como hoy y calcular los embeddings a través del gateway de modelos, con su propia credencial.
- **FR-010**: El servicio MUST conservar el indexado tras la subida, el barrido de pendientes y el reindexado por configuración (ADR-0017).

**Datos**

- **FR-011**: El servicio MUST tener su propia base de datos y credencial, y ser el único que la lee o escribe.
- **FR-012**: Los datos del esquema `retrieval` MUST migrarse sin pérdida (documentos, fragmentos, vectores, modelo, metadatos y estados), sin recalcular embeddings, con una verificación automática de recuentos y contenido y un procedimiento repetible.
- **FR-013**: Los datos antiguos MUST conservarse hasta verificar la extracción en dev; su borrado es un paso explícito y documentado.

**Indexado**

- **FR-014**: El cerrojo en memoria MUST sustituirse por uno en base de datos que valga para todas las instancias, se libere si la instancia muere y no bloquee búsquedas ni subidas.

**Contratos y chat**

- **FR-015**: El OpenAPI de `retrieval` en `shoplab-contracts` MUST ser la fuente de las tres operaciones; el servicio MUST verificarlo como productor y publicar los stubs bajo sus propias coordenadas.
- **FR-016**: El monolito MUST consultar la búsqueda por HTTP con el cliente generado desde ese contrato, en lugar de `KnowledgeSearchApi`, con la dirección del servicio configurable por entorno.
- **FR-017**: La llamada del chat MUST tener un tiempo máximo de espera, propagar el identificador de correlación y la traza, y traducir cualquier fallo al error de recuperación actual. Umbral, selección y citas siguen siendo responsabilidad del chat (ADR-0018).

**Enrutamiento, despliegue y retirada**

- **FR-018**: El gateway MUST mover `/api/documents/**` y `/api/knowledge/**` al servicio solo con configuración, en dev y en local.
- **FR-019**: El servicio MUST desplegarse en dev con el ApplicationSet de LAB-104. La configuración MUST incluir base de datos, secretos, `ingest`, gateway de modelos y los límites de subida.
- **FR-020**: El entorno local (LAB-110) MUST incluir el servicio y su base de datos con un solo `docker compose up`, además de `clone-all` y la opción de construcción local.
- **FR-021**: El módulo `retrieval` MUST retirarse del monolito junto con sus migraciones, rutas, configuración, contratos y documentación generada, después de verificar el servicio en dev.

**Observabilidad, verificación y documentación**

- **FR-022**: El servicio MUST publicar salud, métricas, registros y trazas; las métricas de recuperación actuales conservan su nombre y se siguen viendo en la observabilidad existente.
- **FR-023**: Se MUST tomar la línea base de LAB-28 antes de migrar y repetir la medición después, sobre el mismo corpus, dataset y modelo, y guardar la comparación.
- **FR-024**: Se MUST registrar un ADR (base de datos propia, cerrojo, corte y reversión) y actualizar el mapa de servicios, la documentación de módulos, contratos, base de conocimiento y gateway.

**Fuera de alcance**

- **FR-025**: Esta historia MUST NOT incluir: ingesta asíncrona ni eventos Kafka (LAB-47), búsqueda híbrida, reranking o contextual retrieval (F3), el servidor MCP del mapa de servicios, autenticación (LAB-61, LAB-60), ni la extracción de `chat` (LAB-59) o `eval` (LAB-130).

### Key Entities

- **Documento**: archivo fuente del corpus; identidad, nombre, metadatos, estado de indexado (pendiente, indexado, error) y causa del error.
- **Fragmento**: porción de un documento con su texto, sección, posición, metadatos, vector y modelo que lo generó. Pertenece a un documento.
- **Turno de indexado**: derecho exclusivo, compartido entre instancias, a calcular y guardar vectores; se libera al terminar o si su dueño muere.
- **Contrato de recuperación**: descripción publicada de las tres operaciones, de la que salen el cliente del chat y los stubs.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Las métricas de recuperación después de extraer son idénticas a las de antes (cuatro decimales, resumen y detalle por pregunta).
- **SC-002**: El 100 % de los documentos y fragmentos migrados coinciden con el origen y se calculan 0 embeddings para migrarlos.
- **SC-003**: Los recorridos de chat con citas, documentos y evaluación que funcionaban antes funcionan igual, con 0 cambios en el código del frontend y de la herramienta de evaluación.
- **SC-004**: Un cambio en la búsqueda se publica y queda activo en dev sin ninguna release del monolito.
- **SC-005**: La búsqueda del chat por red añade menos de 100 ms en el percentil 95 en el entorno local.
- **SC-006**: Con dos instancias y documentos pendientes, 0 documentos se indexan dos veces.
- **SC-007**: Las rutas se pueden devolver al monolito, antes de retirar el módulo, en menos de 10 minutos y con un solo cambio de configuración.
- **SC-008**: Un servicio creado desde la plantilla llega a una imagen publicada y verificada sin editar la plataforma a mano.

## Assumptions

- **Base de datos propia** significa una base de datos separada con credencial propia, que puede estar en el mismo servidor PostgreSQL de dev y local. Un servidor aparte no es necesario hoy.
- **Corte con ventana**: dev es el único ambiente; se acepta suspender las subidas durante la migración. No hay doble escritura ni sincronización continua.
- **Orden de entrega**: correcciones previas → servicio en paralelo → migración → rutas del gateway y chat por HTTP en la misma ventana → retirada del módulo. Mover las rutas sin mover el chat dejaría al chat leyendo datos viejos.
- **Códigos de error**: se mantienen los `SLHD_RETR_*` actuales aunque ADR-0031 prevea códigos propios por servicio; cambiarlos rompería a los consumidores.
- **Una réplica en dev**: el cerrojo se prueba con dos instancias en pruebas, no se escala dev.
- **Sin autenticación**, como hoy (ADR-0024).

## Notes for Planning

Hallazgos del repositorio que el plan debe resolver; no cambian el alcance.

- `shoplab-contracts` genera para Java solo los modelos (`generateApis=false`): no existe todavía el «cliente generado» que pide FR-016. Existe un cliente escrito a mano (`HttpKnowledgeSearchClient`, LAB-106), sin tiempo máximo de espera.
- ADR-0017 anticipa `SELECT ... FOR UPDATE SKIP LOCKED`, pero también que los vectores se calculan sin retener una conexión de base de datos. El diseño del cerrojo debe cumplir ambas cosas o registrar el cambio en el ADR nuevo.
- La plantilla solo está en `specs/LAB-103-reusable-workflows-service-template/shoplab-service-template/`.
- `shoplab-infra` ya tiene `apps/retrieval-service` en `main`, con la imagen `v1.0.0` y solo variables de base de datos: faltan `ingest`, gateway de modelos y límites, y hay que alinear la versión.

## Dependencies

- **LAB-103**: plantilla y flujos reutilizables (con las correcciones de US1).
- **LAB-104**: ApplicationSet y `apps/<servicio>` en `shoplab-infra`.
- **LAB-106 / LAB-108**: contratos y paquetes versionados.
- **LAB-107**: gateway que mueve las rutas.
- **LAB-110**: entorno local multi-repo.
- **LAB-28**: evaluación de recuperación usada como línea base.
- **Posteriores**: LAB-59 (chat-service) va después de esta historia.
