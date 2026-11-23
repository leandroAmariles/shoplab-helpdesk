# Feature Specification: Feedback 👍/👎 en cada respuesta

**Feature Branch**: `feature/LAB-33-answer-feedback`

**Created**: 2026-11-23

**Status**: Draft

**Input**: User description: "LAB-33 (F2-07) Feedback 👍/👎 en cada respuesta. Como cliente, quiero valorar cada respuesta con 👍 o 👎. Criterios: feedback con comentario opcional guardado en `chat.feedback`; las respuestas con 👎 se pueden promover a casos golden." Depende de LAB-14 (pantalla de chat con streaming y Markdown) y LAB-24 (memoria de la conversación), además de apoyarse en el dataset golden de LAB-27 y la pantalla de evaluación `admin-eval` de LAB-32; todas están disponibles.

## Clarifications

### Session 2026-11-23

- Q: ¿Por qué medio se realiza la promoción de un feedback 👎 a caso golden? → A: **Opción B**: desde la interfaz web (`admin-eval`), donde el administrador revisa las respuestas con 👎, completa o ajusta los campos del caso golden (`id` `G<NNN>`, `question`, `reference_answer`, `expected_documents`, `category`), valida las reglas del caso, exporta/descarga o copia la línea JSONL lista para incorporar a `eval/golden.jsonl` y deja marcado el feedback como promovido con ese `G<NNN>`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Valorar una respuesta con 👍 o 👎 y un comentario opcional en la pantalla de chat (Priority: P1)

Como cliente, quiero pulsar 👍 o 👎 debajo de una respuesta completada del asistente y, si quiero, dejar un comentario breve explicando por qué me sirvió o por qué no, para que el equipo sepa qué respuestas funcionan y cuáles fallan en conversaciones reales.

Hoy el asistente se mide con un conjunto sintético de preguntas (LAB-27 a LAB-32), pero no hay forma de saber qué opina quien conversa en la pantalla de chat. Esta historia añade a cada burbuja completada del asistente dos controles de valoración (👍 útil / 👎 no útil) y la posibilidad de acompañar la valoración con un comentario opcional. La valoración solo está disponible cuando la respuesta ha terminado de escribirse completa: ni durante el streaming, ni en respuestas interrumpidas por el cliente o fallidas.

Para que el servidor pueda asociar la valoración con la respuesta concreta sin confiar en que el navegador reenvíe el texto por su cuenta, cada respuesta completada queda identificada de forma única por el servidor al terminar el turno. Si el cliente cambia de opinión dentro de la misma conversación en pantalla (por ejemplo, pulsó 👍 por error y cambia a 👎 o añade un comentario), queda guardada su última valoración para esa respuesta, sin duplicar filas.

**Why this priority**: Es la mitad visible para el cliente del primer criterio de aceptación de Jira («Como cliente, quiero valorar cada respuesta con 👍 o 👎. Feedback con comentario opcional guardado en `chat.feedback`»). Sin la acción en la pantalla de chat no se recoge feedback.

**Independent Test**: Abrir la pantalla de chat, hacer dos preguntas y, cuando terminen de responderse, valorar la primera con 👍 sin comentario y la segunda con 👎 y el comentario «No indica el plazo en días hábiles»; comprobar que la pantalla confirma cada valoración, refleja la opción elegida y que ambas quedan registradas en `chat.feedback`.

**Acceptance Scenarios**:

1. **Given** una respuesta del asistente que ha terminado de generarse (`done`), **When** se muestra en el chat, **Then** aparecen bajo la burbuja los controles para valorarla con 👍 o 👎, accesibles por ratón, teclado y lector de pantalla.
2. **Given** una respuesta aún en escritura (`streaming`), detenida por el cliente (`stopped`) o fallida (`failed`), **When** se muestra en el chat, **Then** no ofrece los controles de valoración.
3. **Given** una respuesta completada, **When** el cliente pulsa 👍 (o 👎) y envía la valoración sin comentario, **Then** el sistema guarda la valoración en `chat.feedback` y la burbuja muestra qué opción quedó elegida sin perder las citas ni el resto de la conversación.
4. **Given** una respuesta completada, **When** el cliente elige 👎 (o 👍), escribe un comentario opcional dentro del límite permitido y lo envía, **Then** el sistema guarda la valoración junto con el comentario y confirma que quedó registrada.
5. **Given** una respuesta ya valorada en la conversación en pantalla, **When** el cliente cambia su valoración (de 👍 a 👎 o al revés, o añade/actualiza el comentario), **Then** la valoración de esa respuesta se actualiza en `chat.feedback` en lugar de crear una segunda valoración para el mismo turno.
6. **Given** que el guardado del feedback falla (por ejemplo, servicio no disponible o comentario demasiado largo), **When** el cliente intenta enviarlo, **Then** la pantalla muestra un aviso comprensible en español sin borrar la respuesta ni el comentario escrito y permite reintentar.

---

### User Story 2 - Conservar el feedback en `chat.feedback` con el contexto necesario del turno (Priority: P1)

Como desarrollador, quiero que cada valoración quede guardada en la tabla `chat.feedback` del módulo `chat`, vinculada al turno respondido (la pregunta del cliente, la respuesta del asistente y los documentos de los fragmentos que se entregaron en ese turno, si los hubo), para que una valoración negativa tenga todo lo necesario cuando alguien la revise para convertirla en un caso del dataset golden.

En LAB-24 los turnos solo se guardaban en `chat.conversation_turn` cuando el cliente enviaba `conversationId`, y no guardaban qué documentos se entregaron como contexto. Para que cualquier respuesta completada se pueda valorar —con o sin `conversationId`— y para que un 👎 sirva después para redactar un caso golden sin adivinar qué preguntó el cliente ni qué documentos vio el asistente, `chat.feedback` conserva (o referencia dentro del propio esquema `chat`) la pregunta, la respuesta, los identificadores de los documentos entregados en ese turno, la valoración (`UP` / `DOWN`), el comentario opcional, la fecha y, si existe, la referencia a la traza del turno. Todo vive dentro del esquema `chat`, dueño único de los datos de conversación (ADR-0015), con llamadas JDBC aisladas fuera del bucle reactivo (ADR-0011).

**Why this priority**: Es la mitad de persistencia del primer criterio de aceptación («guardado en `chat.feedback`») y la base de datos de la que depende la promoción a casos golden.

**Independent Test**: Enviar una petición de chat con `conversationId` y contexto, registrar feedback (primero con 👍 y luego actualizarlo a 👎 con comentario) y comprobar en `chat.feedback` que la fila contiene la valoración, el comentario, la pregunta, la respuesta y los identificadores de los documentos entregados, y que rechaza valoraciones para turnos inexistentes o datos inválidos con errores tipados.

**Acceptance Scenarios**:

1. **Given** un turno completado del chat con `conversationId`, **When** termina (`done`), **Then** el cliente recibe un identificador de turno (`turnId`) con el que puede enviar su valoración, sin romper a los clientes que no envían `conversationId` o que solo leen `fragments`.
2. **Given** un identificador de turno completado válido, **When** se envía una valoración 👍 o 👎 con comentario opcional, **Then** se guarda en `chat.feedback` con la pregunta del turno, la respuesta entregada, los identificadores de los documentos de las fuentes entregadas (lista vacía si fue sin contexto o cortesía), el sentido del voto, el comentario (si lo hay) y el instante de registro.
3. **Given** un identificador de turno que ya tiene una fila en `chat.feedback`, **When** se envía una nueva valoración para ese mismo turno, **Then** se actualiza la fila existente y sigue habiendo a lo sumo una valoración por turno en `chat.feedback`.
4. **Given** un identificador de turno que no existe (o corresponde a un turno que nunca terminó), un voto inválido o un comentario que supera la longitud máxima, **When** se envía la valoración, **Then** se rechaza con el contrato de errores tipados del backend (`ResponseCodeEnum` y `GlobalExceptionHandler`, 404 o 400 según corresponda).
5. **Given** todas las lecturas y escrituras de `chat.feedback`, **When** se ejecutan con BlockHound activo, **Then** se aíslan en `Schedulers.boundedElastic()` y no se detecta ningún bloqueo en hilos reactivos, y los logs y métricas registran solo conteos y sentidos de voto (`UP`/`DOWN`), nunca el texto de la pregunta, de la respuesta ni del comentario (Constitución VI).

---

### User Story 3 - Promover respuestas con 👎 a casos del dataset golden desde `admin-eval` (Priority: P1)

Como administrador o curador del dataset, quiero ver en la pantalla `admin-eval` las respuestas valoradas con 👎 y promover una de ellas a un caso golden completando o revisando su categoría, documentos esperados y respuesta de referencia, obteniendo la línea JSONL validada lista para incorporar a `eval/golden.jsonl`, para que los fallos reales que detectan los clientes se conviertan en casos permanentes de la evaluación automática sin salir de la aplicación.

Una respuesta con 👎 es justamente una respuesta que el cliente consideró mala: **nunca** puede copiarse su respuesta del asistente como `reference_answer` del dataset golden, porque contaminaría todas las métricas con una respuesta errónea (LAB-27). Además, la pregunta original del cliente puede ser un seguimiento («¿y si es internacional?») o llevar datos que hay que limpiar para cumplir las reglas de `eval/golden.jsonl` (pregunta autocontenida en español, sin PII, categoría de las ocho permitidas, 0 a 2 documentos existentes en `data/kb` y respuesta de referencia cierta según esos documentos).

Por eso la promoción desde `admin-eval` convierte un feedback 👎 en un **caso golden validado**:
1. En `admin-eval`, el administrador abre la sección de feedback con 👎 (viendo la pregunta del turno, la respuesta que dio el asistente, los documentos que se habían recuperado, el comentario del cliente, la fecha y si ya fue promovido).
2. Elige una valoración con 👎 pendiente y abre el formulario de promoción precargado con la pregunta del cliente y los documentos entregados en aquel turno como sugerencia: puede conservar o reescribir la pregunta para que sea autocontenida y sin datos personales, indica el identificador `G<NNN>` (con sugerencia automática del siguiente disponible), la `category`, los `expected_documents` (0 a 2 documentos válidos del corpus) y redacta la `reference_answer` correcta.
3. El sistema valida que el caso cumpla las reglas individuales de LAB-27 (formato de `id` `G<NNN>`, longitudes de pregunta y respuesta, categoría válida, 0 a 2 documentos sin duplicados coherentes con el tema, ausencia de cifras en casos sin respuesta y ausencia de PII), marca ese feedback 👎 como promovido en `chat.feedback` junto con el identificador `G<NNN>` y entrega en pantalla la línea JSONL exacta (con acción para copiarla o descargarla) lista para pegar en `eval/golden.jsonl`.

**Why this priority**: Es el segundo criterio de aceptación explícito de Jira («Las respuestas con 👎 se pueden promover a casos golden») y cierra el ciclo de mejora continua de F2 que LAB-27 dejó preparado.

**Independent Test**: Registrar una valoración 👎 sobre una pregunta de devoluciones en el chat, abrir `admin-eval`, localizar esa valoración en la lista de 👎, promoverla indicando la categoría `returns`, el documento esperado y una respuesta de referencia válida; comprobar que el feedback queda marcado como promovido con su `G<NNN>`, y que al pegar la línea JSONL generada al final de `eval/golden.jsonl`, `uv run python -m shoplab_eval validate` pasa en verde.

**Acceptance Scenarios**:

1. **Given** varias valoraciones guardadas en `chat.feedback` (algunas con 👍 y otras con 👎), **When** el administrador consulta la sección de feedback en `admin-eval`, **Then** ve las valoraciones con 👎 (con opción de filtrar las pendientes de promover o todas), mostrando su fecha, pregunta, respuesta que dio el asistente, documentos entregados en el turno, comentario del cliente, enlace o identificador de traza (si existe) y estado de promoción (con el id `G<NNN>` si ya fue promovida).
2. **Given** una valoración con 👎 no promovida en `admin-eval`, **When** el administrador la promueve completando un `id` `G<NNN>`, una pregunta autocontenida, una respuesta de referencia válida, una categoría válida y los documentos esperados (0 a 2), **Then** el backend valida el caso, registra en `chat.feedback` que esa valoración fue promovida al caso `G<NNN>` (junto con el caso JSONL generado) y la pantalla muestra la línea JSONL exacta con acciones para copiarla al portapapeles o descargarla para `eval/golden.jsonl`.
3. **Given** un intento de promover una valoración con 👍, **When** se envía al backend, **Then** se rechaza con un error de negocio indicando que solo las respuestas con 👎 se promueven a casos golden.
4. **Given** un intento de promover una valoración con 👎 cuyos datos violan las reglas de un caso golden (por ejemplo, `id` que no sigue `G<NNN>`, pregunta o respuesta fuera de longitud, categoría incompatible con el tema de los documentos, más de 2 documentos o repetidos, cifras en una respuesta de caso sin respuesta, o presencia de PII), **When** se intenta promover, **Then** se rechaza informando la regla incumplida y la valoración sigue como no promovida.
5. **Given** una valoración con 👎 que ya fue promovida a un caso golden, **When** se consulta en `admin-eval`, **Then** aparece marcada como promovida con su `G<NNN>`, permite volver a copiar o descargar su línea JSONL e impide promoverla por segunda vez a otro caso distinto.

---

### User Story 4 - Observabilidad y privacidad en logs y métricas del feedback (Priority: P2)

Como desarrollador, quiero que el sistema emita métricas de las valoraciones recibidas (`UP` y `DOWN`) y de las promociones a casos golden sin exponer preguntas, respuestas ni comentarios en logs ni en etiquetas de métricas, para cumplir la Constitución VI.

`chat.feedback` guarda la pregunta escrita en el chat, la respuesta del asistente y el comentario libre del cliente. Por eso:
- El cliente del chat solo puede enviar una valoración si conoce el identificador impredecible del turno que acaba de recibir.
- Los logs y las métricas de Prometheus registran contadores por sentido del voto (`UP` / `DOWN`) y promociones realizadas, sin incluir jamás el texto de la pregunta, de la respuesta ni del comentario, ni identificadores de turno o conversación como etiqueta de métrica.

**Why this priority**: Es P2 porque las historias 1 a 3 entregan el flujo completo del criterio de aceptación, pero sin estas garantías se violaría la Constitución VI (privacidad en logs y métricas).

**Independent Test**: Enviar valoraciones 👍 y 👎 y promover un 👎 desde `admin-eval`, consultar `/actuator/prometheus` y los logs del backend para comprobar que el contador de feedback distingue `UP` y `DOWN` y registra la promoción sin textos libres ni etiquetas de alta cardinalidad.

**Acceptance Scenarios**:

1. **Given** valoraciones 👍 y 👎 enviadas desde el chat, **When** se consultan las métricas del backend, **Then** existe una métrica que cuenta las valoraciones registradas separadas por voto (`UP`, `DOWN`) sin etiquetas de alta cardinalidad (sin `conversationId`, `turnId` ni texto).
2. **Given** una valoración con comentario libre, **When** se revisan los logs del backend, **Then** el registro incluye el identificador de petición, el identificador del turno y el voto (`UP`/`DOWN`), pero nunca el texto del comentario, de la pregunta ni de la respuesta.
3. **Given** una promoción de feedback 👎 a caso golden `G<NNN>`, **When** se completa, **Then** el log y las métricas registran la promoción y el identificador `G<NNN>` sin incluir el texto de la pregunta, de la respuesta ni del comentario.

---

### Edge Cases

- **Cambio de voto tras haber sido promovido**: si un cliente aún tiene la pestaña abierta e intenta cambiar a 👍 una respuesta cuyo 👎 ya fue promovido a un caso golden, o si se consulta el historial, el vínculo con el caso golden creado no corrompe `eval/golden.jsonl` (el archivo ya tiene el caso; el estado promovido en `chat.feedback` se conserva o impide deshacer una promoción ya consumada).
- **Comentario vacío o solo con espacios**: un comentario que solo contiene espacios en blanco se normaliza a ausencia de comentario (`null`), en lugar de guardarse como cadena de espacios.
- **Comentario muy largo o con HTML/Markdown**: el comentario tiene un límite claro de caracteres (por ejemplo, 500 caracteres) validado tanto en la pantalla como en el backend; se guarda y se muestra siempre como texto plano literal, nunca interpretado como HTML activo.
- **Pregunta que era un seguimiento («¿y si es internacional?»)**: en `chat.feedback` se guarda la pregunta tal como se hizo en ese turno, y al promoverla a `eval/golden.jsonl` el curador puede reescribir la pregunta para que sea autocontenida (requisito FR-003 de LAB-27).
- **Turno sin `conversationId` o de conversación de cortesía (`small_talk`)**: la pantalla de chat envía siempre `conversationId` (LAB-24), por lo que toda respuesta completada en la interfaz —incluidas las de cortesía (`small_talk`) y las sin contexto (`delivered_documents` vacío)— recibe su `turnId` y se puede valorar con 👍 o 👎. Una petición directa a la API sin `conversationId` no persiste memoria (LAB-24) ni incluye `turnId` en `done`, preservando byte a byte el contrato de streaming sin memoria (`ChatStreamContractTests`).
- **Tope de casos en `eval/golden.jsonl`**: LAB-27 fijó `max_cases = 100` para los casos de conocimiento y hoy hay 96 (lo que deja margen inmediato para 4 casos promovidos; 24 son de conversación, para un total de 120). Si al promover casos reales se supera el tope, la regla de tamaño máximo del conjunto (`GoldenRules.max_cases`) debe admitir los casos promovidos o ampliarse de forma explícita y documentada sin debilitar las demás validaciones (`G-ID`, `G-DOC-MISSING`, `G-PII`, `G-DUPLICATE`, etc.).
- **Nueva conversación o recarga de página**: al pulsar «Nueva conversación» o recargar la página, las burbujas en pantalla desaparecen como ya ocurre hoy (LAB-24), pero las valoraciones ya enviadas permanecen guardadas en `chat.feedback`.
- **Envío repetido rápido (doble clic en 👍/👎)**: la pantalla evita peticiones simultáneas duplicadas y, en el servidor, la operación es idempotente por turno (una sola fila por turno en `chat.feedback`).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Cada turno del chat con conversación (`conversationId`) que termine correctamente (`done`) MUST identificarse con un identificador único e impredecible generado por el servidor y entregado al cliente en el evento `done` (de forma aditiva, conservando el campo `fragments` y el formato exacto de `done` cuando no hay `conversationId`, manteniendo intacta la compatibilidad con los clientes y pruebas de LAB-12, LAB-22, LAB-23, LAB-24 y LAB-111).
- **FR-002**: La pantalla de chat MUST mostrar controles de valoración 👍 y 👎 únicamente en las burbujas del asistente cuyo estado sea completado (`complete`); las burbujas del cliente y las respuestas en curso (`streaming`), detenidas (`stopped`) o fallidas (`failed`) MUST NOT mostrar controles de valoración.
- **FR-003**: La pantalla de chat MUST permitir al cliente enviar su valoración 👍 o 👎 con un solo clic (o activación por teclado) y MUST permitir añadir o editar un comentario opcional en texto plano antes o después de elegir el voto, mostrando el estado actual (enviando, guardado o error con opción de reintentar).
- **FR-004**: El módulo `chat` MUST persistir las valoraciones en una tabla `chat.feedback` dentro de su propio esquema `chat` (con migración Flyway en `db/migration/chat/`), sin claves foráneas ni consultas a esquemas de otros módulos (ADR-0015, Constitución I).
- **FR-005**: Cada registro de `chat.feedback` MUST conservar como mínimo: su identificador, el identificador único del turno valorado, el identificador de conversación (si el turno tuvo uno), la pregunta del cliente en ese turno, la respuesta completa del asistente, los identificadores de los documentos del corpus entregados como fuentes en ese turno (lista vacía si no hubo), el voto (`UP` o `DOWN`), el comentario opcional del cliente, el identificador de traza del turno (si aplica), las marcas de tiempo de creación/actualización y, cuando haya sido promovido, el identificador del caso golden (`G<NNN>`) y la fecha de promoción.
- **FR-006**: `chat.feedback` MUST garantizar a lo sumo una valoración por turno: si el cliente vuelve a valorar el mismo turno (cambia entre 👍 y 👎 o modifica el comentario) mientras el feedback no haya sido promovido, el sistema MUST actualizar el registro existente de ese turno en lugar de insertar un duplicado.
- **FR-007**: El comentario opcional MUST normalizarse recortando espacios extremos (si queda vacío se guarda como `null`) y MUST tener una longitud máxima acotada (por ejemplo, 500 caracteres); superar el límite, enviar un voto distinto de `UP`/`DOWN` o valorar un identificador de turno inexistente MUST rechazarse con excepciones tipadas y códigos en `ResponseCodeEnum` manejados por `GlobalExceptionHandler`.
- **FR-008**: Todas las operaciones JDBC de `chat.feedback` (guardar el turno valorable, registrar/actualizar feedback, listar feedbacks con 👎 y marcar como promovido) MUST aislarse en `Schedulers.boundedElastic()` mediante el mecanismo de LAB-111 (`BlockingCalls`), manteniendo BlockHound en verde.
- **FR-009**: El sistema MUST exponer a la pantalla `admin-eval` la consulta de las valoraciones con 👎 (con su pregunta, respuesta del asistente, documentos entregados, comentario, fecha, identificador de traza y estado de promoción) y la operación para promover una valoración con 👎 a un caso golden `G<NNN>`.
- **FR-010**: La pantalla `admin-eval` MUST ofrecer una sección de feedback con 👎 donde el administrador pueda revisar cada valoración negativa, abrir el formulario de promoción precargado con la pregunta y los documentos recuperados en aquel turno, definir o ajustar el identificador `G<NNN>` (con sugerencia automática), la pregunta autocontenida, la `category`, los `expected_documents` y la `reference_answer` correcta, validar las reglas del caso golden y, al confirmar, registrar el feedback como promovido en `chat.feedback` y mostrar la línea JSONL exacta con opciones para copiarla o descargarla para `eval/golden.jsonl`.
- **FR-011**: La promoción MUST NOT copiar automáticamente la respuesta del asistente valorada con 👎 como `reference_answer` definitiva sin que el administrador indique la respuesta de referencia correcta, MUST rechazar promover valoraciones con 👍 y MUST impedir promover por segunda vez un feedback ya promovido a otro caso distinto.
- **FR-012**: Si el caso propuesto al promover viola cualquiera de las reglas de validación de un caso golden (formato de `id` `G<NNN>`, longitudes de pregunta y respuesta, categoría válida, 0 a 2 documentos sin duplicados y coherentes con el tema, ausencia de cifras en respuestas de casos sin respuesta o presencia de PII), la promoción MUST rechazarse informando la regla incumplida y sin marcar el feedback como promovido.
- **FR-013**: Los controles de feedback en la pantalla de chat y la sección de promoción en `admin-eval` MUST ser accesibles: operables solo con teclado, con nombres accesibles en español («Respuesta útil», «Respuesta no útil», estado seleccionado `aria-pressed`, campo de comentario con etiqueta y anuncio de confirmación o error para lectores de pantalla) y sin desbordes en pantallas móviles (360 px) ni de escritorio.
- **FR-014**: El backend MUST emitir métricas de observabilidad sobre las valoraciones recibidas (conteo por sentido `UP` / `DOWN`) y logs estructurados con correlación (`requestId`), y MUST NOT incluir el texto de la pregunta, de la respuesta ni del comentario en logs ni en etiquetas de métricas (Constitución VI).
- **FR-015**: La funcionalidad MUST estar cubierta por pruebas automáticas deterministas en backend (dominio, caso de uso, controlador, adaptador JDBC con PostgreSQL real vía Testcontainers, reglas de arquitectura y Modulith, cobertura de líneas ≥ 80 %) y frontend (componentes y stores de `chat` y `admin-eval` en Vitest).
- **FR-016**: La documentación del proyecto (`docs/admin-eval.md`, `eval/README.md` y `docs/chat-streaming.md`) MUST actualizarse para explicar cómo funciona el feedback 👍/👎 en el chat y cómo se promueven las respuestas con 👎 desde `admin-eval` hacia `eval/golden.jsonl` manteniendo la revisión humana registrada en `eval/REVIEW.md`.

### Key Entities *(include if feature involves data)*

- **Turno valorable**: una respuesta completada del asistente en el chat, identificada por un identificador único de turno generado por el servidor, con la pregunta del cliente, la respuesta completa entregada, los identificadores de los documentos de las fuentes entregadas en ese turno y, si existe, el identificador de conversación y de traza.
- **Valoración (`chat.feedback`)**: el juicio del cliente sobre un turno valorable: voto positivo (`UP`, 👍) o negativo (`DOWN`, 👎), comentario opcional en texto plano, fecha de creación/actualización y estado de promoción (no promovido, o promovido al caso `G<NNN>` en una fecha dada). Existe a lo sumo una valoración por turno.
- **Candidato a caso golden**: una valoración con voto `DOWN` (👎) aún no promovida, que aporta la pregunta real del cliente, la respuesta fallida del asistente, los documentos recuperados en aquel turno y el comentario del cliente como insumo para redactar un nuevo caso golden.
- **Caso golden promovido**: la nueva entrada añadida a `eval/golden.jsonl` (`id`, `question`, `reference_answer`, `expected_documents`, `category`) que supera todas las reglas de validación de LAB-27 y queda vinculada en `chat.feedback` por su `id` (`G<NNN>`).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un cliente en la pantalla de chat puede valorar cualquier respuesta completada con 👍 o 👎 en un solo clic (o activación de teclado) y ver la confirmación en pantalla en menos de 1 segundo en el entorno local.
- **SC-002**: El 100 % de las valoraciones enviadas desde el chat (con o sin comentario, con contexto o en cortesía) se persiste en `chat.feedback` con la pregunta, la respuesta, los documentos entregados en el turno, el voto y el comentario, verificable tras reiniciar el backend.
- **SC-003**: Cambiar el voto o añadir un comentario a una respuesta ya valorada actualiza la misma fila en el 100 % de los casos: nunca existen dos filas en `chat.feedback` para el mismo turno.
- **SC-004**: Un administrador promueve una respuesta con 👎 desde `admin-eval` en menos de 2 minutos, obteniendo la línea JSONL lista para copiar o descargar; al añadir esa línea a `eval/golden.jsonl`, `uv run python -m shoplab_eval validate` pasa con código 0 y `chat.feedback` registra el identificador `G<NNN>` asignado.
- **SC-005**: El 100 % de los intentos de promover un caso inválido (identificador mal formado, longitudes fuera de rango, categoría incompatible con los documentos, cifras en respuesta sin respuesta, PII), un feedback con 👍 o un feedback ya promovido se rechaza con un mensaje explícito y no marca el feedback como promovido.
- **SC-006**: Los escenarios de contrato existentes del chat (LAB-12, LAB-22, LAB-23, LAB-24 y LAB-114) siguen pasando en el 100 % de los casos sin modificar sus expectativas previas, y la suite completa pasa con BlockHound activo sin bloqueos en hilos reactivos.
- **SC-007**: En el 100 % de las peticiones de feedback, ni los logs ni las métricas de Prometheus contienen el texto de la pregunta, de la respuesta ni del comentario del cliente.
- **SC-008**: Todos los controles de feedback en el chat se pueden operar solo con teclado y sin desborde horizontal en un ancho de 360 px.

## Assumptions

- **Dependencias**: LAB-14 (pantalla de chat con streaming y Markdown), LAB-23 (citas y fuentes por respuesta), LAB-24 (memoria de conversación en el esquema `chat`), LAB-27 (dataset golden y validador en `eval/`) y LAB-32 (pantalla `/admin-eval` en `frontend/src/app/features/eval/` y `docs/admin-eval.md`) están fusionadas en `main`.
- **Propiedad del esquema (`chat.feedback`)**: el criterio de aceptación nombra explícitamente `chat.feedback`. Conforme a ADR-0015 y la Constitución I, la tabla vive en el esquema `chat` y su único dueño es el módulo `chat` (migración `V3` en `backend/src/main/resources/db/migration/chat/`). Ningún otro módulo ni herramienta Python accede a `chat.feedback` por SQL directo; cualquier lectura o actualización desde fuera se hace a través de endpoints HTTP del módulo `chat`.
- **Identificación del turno a valorar**: la pantalla de chat siempre envía `conversationId` (LAB-24) y el backend guarda cada turno completado en `chat.conversation_turn` antes de emitir `done` (ADR-0020). Al añadir a `chat.conversation_turn` un identificador público `turn_id` (`uuid`, único) junto con los documentos entregados y el identificador de traza del turno, el evento `done` incluye `"turnId": "..."` cuando el turno se persiste (con `@JsonInclude(NON_NULL)` para que las llamadas sin `conversationId` mantengan intacto `data:{"fragments":N}`). Así el cliente nunca tiene que reenviar por su cuenta la pregunta, la respuesta ni los documentos al valorar.
- **Separación con `eval` (LAB-31)**: `eval.eval_case` guarda instantáneas inmutables de los casos medidos en ejecuciones pasadas (ADR-0028), mientras que la fuente única del dataset golden vivo sigue siendo el archivo `eval/golden.jsonl` (LAB-27). Por tanto, «promover a casos golden» significa añadir un caso válido a `eval/golden.jsonl` y dejar trazado en `chat.feedback` a qué identificador `G<NNN>` se promovió.
- **Revisión humana obligatoria al promover**: un 👎 solo indica que la respuesta del asistente fue insatisfactoria; la respuesta de referencia correcta (`reference_answer`) y los documentos esperados (`expected_documents`) los aporta y confirma una persona al promover el caso, igual que exige LAB-27 (`eval/REVIEW.md`).
- **Tope de casos del dataset golden**: `eval/golden.jsonl` tiene hoy 96 casos de conocimiento (límite actual `max_cases = 100` en `GoldenRules`) y 24 de conversación (`G001`–`G120`). En el plan se fijará el ajuste del tope superior de `GoldenRules.max_cases` si hace falta para que promover más de 4 feedbacks no bloquee la validación por exceso de casos.
- **Seguridad y acceso**: no hay autenticación ni roles en el proyecto (igual que en LAB-25 y LAB-32, ADR-0008). El envío de una valoración desde el chat está acotado a quien conoce el UUID del turno recién respondido, y la sección de feedback con 👎 y su promoción en `admin-eval` quedan abiertas en el entorno de desarrollo único, sin enviar tokens secretos al navegador. El control de acceso por roles se aborda en la fase de seguridad (F6).
- **Fuera de alcance**: autenticación de usuarios finales y asociación del feedback a una cuenta de cliente; edición automática de prompts o reentrenamiento automático a partir del feedback; eliminación o moderación masiva de comentarios; valoración de respuestas a mitad de streaming o de respuestas fallidas.
