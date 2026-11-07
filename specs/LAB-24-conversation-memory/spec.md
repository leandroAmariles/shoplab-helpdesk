# Feature Specification: Memoria de la conversación

**Feature Branch**: `feature/LAB-24-conversation-memory`

**Created**: 2026-11-07

**Status**: Draft

**Input**: User description: "LAB-24 (F1-07) Memoria de la conversación. Como cliente, quiero que el bot recuerde lo que
dije antes en la conversación. Criterios: `ChatMemory` en JDBC por `conversationId`, con las llamadas aisladas en
`Schedulers.boundedElastic()` (LAB-111); una pregunta de seguimiento («¿y si es internacional?») se entiende; BlockHound
en verde." Depende de LAB-111 (backend reactivo).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Preguntas de seguimiento que se entienden (Priority: P1)

Como cliente, quiero hacer una pregunta de seguimiento sin repetir el contexto («¿y si es internacional?» después de
preguntar por el costo de envío) y que el bot la entienda a partir de lo que ya hablamos.

Hoy cada mensaje se responde por separado (LAB-12, LAB-22): el bot no sabe a qué se refiere «es internacional». Esta
historia hace que cada respuesta tenga en cuenta los mensajes anteriores de la misma conversación, tanto para redactar la
respuesta como para buscar los documentos que la apoyan.

**Why this priority**: Es el criterio de aceptación principal y lo que convierte el chat en una conversación en lugar de
una serie de preguntas aisladas.

**Independent Test**: Con la base indexada, preguntar «¿cuánto tarda el envío estándar?» y a continuación «¿y si es
internacional?» dentro de la misma conversación; la segunda respuesta habla de envíos internacionales (no pide aclarar de
qué se habla) y cita los documentos de envíos.

**Acceptance Scenarios**:

1. **Given** una conversación con una pregunta y su respuesta sobre un tema, **When** el cliente envía una pregunta de
   seguimiento que solo se entiende con ese tema, **Then** la respuesta trata el mismo tema aplicando la variación pedida.
2. **Given** una pregunta de seguimiento sin palabras clave propias («¿y si es internacional?»), **When** se busca
   contexto en la base de conocimiento, **Then** los documentos recuperados corresponden al tema de la conversación, no
   solo a las palabras del último mensaje.
3. **Given** una conversación con varios turnos, **When** el cliente se refiere a algo dicho antes («lo que me dijiste
   sobre devoluciones»), **Then** la respuesta puede apoyarse en ese intercambio previo.
4. **Given** una pregunta que ya es autosuficiente tras varios turnos sobre otro tema, **When** se responde, **Then** la
   respuesta trata la pregunta nueva sin arrastrar el tema anterior.
5. **Given** las citas y fuentes de LAB-23, **When** la respuesta es un seguimiento, **Then** sus citas `[n]` se numeran
   desde 1 y remiten solo a los fragmentos entregados en esa respuesta, nunca a los de turnos anteriores.

---

### User Story 2 - La conversación se conserva y se mantiene aislada (Priority: P1)

Como cliente, quiero que mi conversación se guarde en el servidor y se identifique con un identificador, para que el bot
recuerde lo dicho entre un mensaje y el siguiente sin mezclarlo con la conversación de otro cliente.

**Why this priority**: Sin conservar el historial por conversación no hay memoria; sin aislamiento, el bot podría revelar
a un cliente lo dicho por otro.

**Independent Test**: Mantener dos conversaciones con identificadores distintos, cada una sobre un tema diferente, y
comprobar que la respuesta de seguimiento de cada una trata su propio tema y nada del otro.

**Acceptance Scenarios**:

1. **Given** dos conversaciones con identificadores distintos, **When** cada cliente envía un seguimiento, **Then** cada
   respuesta usa únicamente el historial de su conversación.
2. **Given** un mensaje con un identificador de conversación nuevo, **When** se responde, **Then** se inicia una
   conversación con ese identificador y la respuesta se comporta como hoy (sin historial previo).
3. **Given** una conversación existente, **When** el servicio se reinicia, **Then** el siguiente mensaje con el mismo
   identificador sigue disponiendo del historial.
4. **Given** un mensaje sin identificador de conversación, **When** se responde, **Then** se atiende como una pregunta
   independiente, igual que antes de esta historia, sin guardar nada.
5. **Given** un identificador de conversación con un formato no válido, **When** se envía, **Then** se rechaza con el
   mismo contrato de errores del chat (petición inválida), sin llamar al modelo.
6. **Given** una respuesta que termina bien, **When** concluye, **Then** el mensaje del cliente y la respuesta completa
   quedan guardados en ese orden; **Given** una respuesta que falla o se cancela a mitad, **Then** no se guarda una
   respuesta parcial y el historial sigue siendo coherente (sin mensajes del cliente sin respuesta que confundan al
   siguiente turno).
7. **Given** una respuesta completa cuyo turno no se puede guardar, **When** termina, **Then** el cliente recibe el error
   del chat (`SLHD_CHAT_00006`) en lugar de la señal de fin; la pantalla conserva el texto ya recibido marcado como
   fallido, y el bot no recordará ese turno en los mensajes siguientes.

---

### User Story 3 - Historial acotado y respuesta sin degradarse (Priority: P2)

Como cliente, quiero que las conversaciones largas sigan respondiendo con rapidez y sin errores, aunque el bot solo tenga
en cuenta lo más reciente.

**Why this priority**: Un historial sin límite agota el espacio que admite el modelo y retrasa el primer token (SC-004
de LAB-22). No bloquea el valor principal, pero lo protege.

**Independent Test**: Mantener una conversación de más turnos que el límite configurado y comprobar que la respuesta
llega, que el primer token sigue dentro del objetivo y que el bot ya no recuerda el turno más antiguo.

**Acceptance Scenarios**:

1. **Given** una conversación más larga que el límite de historial configurado, **When** se responde, **Then** solo se
   entregan al modelo los turnos más recientes dentro del límite; los más antiguos no se descartan del
   almacenamiento por ese motivo.
2. **Given** un historial reciente que, junto al contexto de la base de conocimiento, no cabe en el presupuesto de
   contexto, **When** se arma la petición, **Then** se recorta primero el historial más antiguo y nunca el mensaje actual
   ni los fragmentos de LAB-22 ya seleccionados.
3. **Given** el límite de historial configurable, **When** se cambia el valor por variable de entorno, **Then** se aplica
   al reiniciar, sin recompilar.

---

### User Story 4 - El chat envía el identificador y permite empezar de nuevo (Priority: P2)

Como cliente, quiero que la pantalla de chat mantenga mi conversación mientras la tengo abierta y poder empezar una
conversación nueva cuando quiera, para que el bot no mezcle temas distintos.

**Why this priority**: Sin que el frontend envíe el identificador, la memoria del servidor no se usa; sin «nueva
conversación», el cliente no puede cortar el hilo.

**Independent Test**: En la pantalla de chat, hacer una pregunta y un seguimiento y ver que se entiende; pulsar «Nueva
conversación» y repetir el seguimiento sin contexto, y ver que ya no se entiende por tema anterior.

**Acceptance Scenarios**:

1. **Given** la pantalla de chat recién abierta, **When** el cliente envía su primer mensaje, **Then** la pantalla
   identifica esa conversación y envía el mismo identificador en los mensajes siguientes.
2. **Given** una conversación en pantalla, **When** el cliente pulsa «Nueva conversación», **Then** se vacía la
   conversación visible, se usa un identificador nuevo y el bot no recuerda lo anterior.
3. **Given** una respuesta en curso, **When** el cliente intenta empezar una conversación nueva, **Then** la respuesta en
   curso se cancela o la acción espera a su fin, sin mezclar su resultado en la conversación nueva.
4. **Given** la recarga de la página, **When** se vuelve a abrir el chat, **Then** se muestra una conversación vacía con un
   identificador nuevo (la conversación visible sigue sin persistirse en el navegador).
5. **Given** el control «Nueva conversación», **When** se usa con teclado o lector de pantalla, **Then** es operable y
   tiene un nombre accesible; su etiqueta está en español.

---

### User Story 5 - Memoria que no bloquea ni filtra datos (Priority: P3)

Como desarrollador, quiero que el acceso al historial no bloquee el servidor reactivo y que no deje texto de clientes en
registros ni métricas, para sostener las garantías de LAB-111 y de la Constitución VI.

**Why this priority**: Son garantías de operación; el cliente obtiene valor sin ellas, pero rompen la plataforma si fallan.

**Independent Test**: Ejecutar el conjunto de pruebas con BlockHound activo y las llamadas a la memoria en juego, y
revisar registros y métricas tras varias conversaciones.

**Acceptance Scenarios**:

1. **Given** el acceso al historial (lectura y escritura), **When** se ejecuta bajo detección de bloqueos, **Then** no se
   detecta ninguna llamada bloqueante en los hilos reactivos.
2. **Given** cualquier petición con memoria, **When** termina, **Then** se registran métricas de uso (conversaciones con
   historial, mensajes entregados al modelo, recortes por límite) sin texto de mensajes ni identificadores de cliente
   como etiqueta.
3. **Given** un fallo al leer o guardar el historial, **When** ocurre, **Then** el error se trata con el contrato de
   errores del chat y queda registrado con el identificador de la petición, sin el texto de los mensajes.

---

### Edge Cases

- **Primer mensaje de una conversación**: no hay historial; la respuesta es igual a la de hoy.
- **Memoria que no se puede leer**: la petición falla con el error del chat (`SLHD_CHAT_00006`) en lugar de responder
  como si no hubiera contexto previo, para no dar respuestas engañosas. Una conversación sin turnos guardados no es un
  fallo: es una conversación nueva.
- **Dos mensajes simultáneos de la misma conversación**: ambos se atienden; el orden del historial queda determinado y
  coherente (sin pérdida ni duplicados de mensajes).
- **Mensajes muy largos en el historial**: el recorte por presupuesto de contexto los trata como cualquier otro mensaje.
- **Instrucciones dentro de mensajes previos** («olvida las reglas anteriores»): el historial se entrega como mensajes del
  cliente y del asistente, nunca como instrucciones del sistema; no puede sustituir el prompt de sistema.
- **Respuesta sin contexto de conocimiento** (mensaje fijo de «no hay información» de LAB-22): también forma parte del
  historial, de modo que un seguimiento posterior se entienda.
- **Conversación en la que cambia el tema**: la búsqueda de contexto prioriza el mensaje actual; el historial solo
  completa lo que el mensaje no dice por sí mismo.
- **Identificadores de conversación adivinables**: un cliente podría enviar un identificador ajeno; los identificadores
  MUST ser imposibles de adivinar en la práctica (aleatorios y largos).
- **Cliente antiguo que no envía identificador**: sigue funcionando sin memoria (ver US2-4).
- **Datos personales dentro del historial**: el texto se guarda solo en el almacén de conversaciones; no aparece en
  registros ni métricas (Constitución VI).
- **Idioma**: las etiquetas nuevas de la interfaz («Nueva conversación») están en español.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El sistema MUST guardar, por identificador de conversación, los mensajes del cliente y las respuestas
  completas del asistente, en orden, en el almacén de datos del módulo `chat`.
- **FR-002**: El sistema MUST entregar al modelo, junto con el mensaje actual, los turnos más recientes de esa
  conversación, como mensajes de cliente y de asistente, nunca como instrucciones del sistema.
- **FR-003**: El historial de una conversación MUST NOT ser visible para otra conversación; leer y escribir se hace
  siempre acotado por identificador.
- **FR-004**: La petición de chat MUST aceptar un identificador de conversación opcional; sin él, la petición se atiende
  sin memoria como antes. El cambio del contrato MUST ser aditivo (los eventos `token`, `done`, `error`, `sources` y los
  códigos de error vigentes conservan su significado; LAB-12, LAB-22 y LAB-23 siguen pasando).
- **FR-005**: Un identificador de conversación con formato no válido MUST rechazarse con el contrato de errores vigente
  antes de llamar al modelo ni a la base de conocimiento.
- **FR-006**: La búsqueda de contexto en la base de conocimiento MUST tener en cuenta el tema de la conversación cuando el
  mensaje actual es un seguimiento que no se entiende por sí solo, de modo que «¿y si es internacional?» recupere los
  documentos del tema previo.
- **FR-007**: Solo se MUST guardar el turno cuando la respuesta termina correctamente; una respuesta fallida o cancelada
  MUST NOT dejar una respuesta parcial ni un mensaje del cliente huérfano que altere los turnos siguientes.
- **FR-008**: El historial entregado al modelo MUST estar acotado por un límite configurable (número de turnos) y por el
  presupuesto de contexto de LAB-22; al recortar, se descarta primero lo más antiguo y nunca el mensaje actual ni los
  fragmentos seleccionados. El límite MUST ser ajustable por variable de entorno sin recompilar.
- **FR-009**: Todas las llamadas a la memoria en JDBC MUST aislarse del hilo reactivo en `Schedulers.boundedElastic()`
  usando el ayudante de LAB-111 (ADR-0011), y BlockHound MUST permanecer en verde en toda la batería de pruebas.
- **FR-010**: Un fallo al leer o guardar el historial MUST tratarse con una excepción tipada y mapearse en el
  `GlobalExceptionHandler` al contrato de errores del chat; MUST NOT responder en silencio como si no hubiera historial
  cuando la lectura falla.
- **FR-011**: El esquema de la memoria MUST pertenecer al módulo `chat` (migración propia en su esquema); ningún otro
  módulo lee ni escribe esas tablas (ADR-0015, Constitución I).
- **FR-012**: Los identificadores de conversación MUST generarse de forma aleatoria e impredecible en el cliente, y el
  servidor MUST aceptar solo el formato esperado.
- **FR-013**: Las citas y fuentes de LAB-23 MUST seguir siendo por respuesta: los fragmentos de turnos anteriores no
  se re-numeran ni se reutilizan como fuentes del turno actual, y el historial guardado no incluye bloques de contexto
  ya entregados.
- **FR-014**: La pantalla de chat MUST enviar el identificador de la conversación con cada mensaje y MUST ofrecer una
  acción «Nueva conversación» que vacíe la conversación en pantalla y use un identificador nuevo, operable con teclado y
  con nombre accesible.
- **FR-015**: Los registros y métricas MUST NOT contener el texto de mensajes, respuestas ni fragmentos, ni el
  identificador de conversación como etiqueta de métrica (Constitución VI); se mide al menos: mensajes de historial
  entregados, recortes por límite y fallos de memoria.
- **FR-016**: La funcionalidad MUST verificarse con pruebas automáticas deterministas (modelo simulado) que cubran:
  seguimiento comprendido, aislamiento entre conversaciones, persistencia tras reinicio del contexto, ausencia de
  identificador, identificador inválido, respuesta fallida/cancelada, límite de historial, historial como mensajes y no
  como sistema, y contrato aditivo; pruebas del frontend sobre el envío del identificador y «Nueva conversación»; y una
  verificación manual documentada con Ollama real (Constitución IV).
- **FR-017**: Esta historia MUST NOT incluir: listado, recuperación o borrado de conversaciones por el cliente, caducidad
  automática del historial, resumen del historial con el modelo, restauración de la conversación al recargar la página,
  autenticación o propiedad de conversaciones por usuario, ni memoria a largo plazo entre conversaciones.
- **FR-018**: El comportamiento MUST ser el mismo en el entorno local y en `dev`; el límite de historial y los demás
  ajustes MUST ser configurables por variables de entorno.

### Key Entities *(include if feature involves data)*

- **Conversación**: hilo de mensajes entre un cliente y el bot, identificado por un identificador aleatorio generado en
  el cliente. Existe en el servidor desde su primer turno guardado.
- **Mensaje de la conversación**: texto de un turno con su rol (cliente o asistente) y su posición en el orden; solo el
  texto, sin bloques de contexto ni fuentes.
- **Historial reciente**: subconjunto de mensajes más recientes de una conversación, acotado por límite y presupuesto de
  contexto, que se entrega al modelo en cada turno.
- **Consulta de contexto**: texto con el que se busca en la base de conocimiento; en un seguimiento, combina el mensaje
  actual con el tema de la conversación.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: En al menos 8 de 10 pares «pregunta + seguimiento» representativos (envíos → «¿y si es internacional?»,
  devoluciones → «¿y si ya lo usé?», pagos, garantía), la respuesta al seguimiento trata el tema correcto sin pedir
  aclaración, revisado manualmente con el modelo local real.
- **SC-002**: En esos mismos pares, al menos el 80 % de los seguimientos recupera al menos un fragmento del documento del
  tema de la conversación, revisado manualmente.
- **SC-003**: En el 100 % de las pruebas de aislamiento, ninguna respuesta usa mensajes de una conversación distinta.
- **SC-004**: Tras reiniciar el servicio, el 100 % de las conversaciones guardadas siguen disponibles para el siguiente
  mensaje.
- **SC-005**: El primer token de una respuesta en una conversación con historial al límite configurado sigue llegando en
  menos de 5 segundos (SC-004 de LAB-22) con el modelo ya cargado.
- **SC-006**: La suite completa, incluidas las pruebas de memoria, pasa con BlockHound activo y sin detecciones de
  bloqueo.
- **SC-007**: Los escenarios de contrato de LAB-12, LAB-22 y LAB-23 pasan sin modificar sus expectativas.
- **SC-008**: En el 100 % de las peticiones, registros y métricas no contienen texto de mensajes ni identificadores de
  conversación como etiqueta.
- **SC-009**: Las pruebas automáticas (backend y frontend) pasan en CI sin servidor de modelos real, y los módulos
  modificados mantienen una cobertura de líneas de al menos el 80 %.

## Assumptions

- **Dependencias**: LAB-111 (backend reactivo, `BlockingCalls`, BlockHound, ADR-0011), LAB-22 (contexto de la base de
  conocimiento y presupuesto de contexto) y LAB-23 (citas y fuentes por respuesta, ya fusionada en `main`). Esta rama
  parte de `main`.
- **Identificador generado por el cliente**: la pantalla genera un identificador aleatorio (UUID) por conversación; no hay
  sesión ni usuario autenticado en F1. El identificador actúa como capacidad secreta: quien lo conoce accede al historial.
  La propiedad por usuario llega con la autenticación de una fase posterior (fuera de alcance, FR-017).
- **Almacén de la memoria (decidido)**: tabla propia del esquema `chat` detrás de un puerto de aplicación, no el
  repositorio JDBC de Spring AI, para respetar la propiedad del esquema por módulo (ADR-0015) y guardar cada turno
  en una sola sentencia. El «`ChatMemory` en JDBC» del criterio se cumple en su contrato (historial por
  `conversationId` en JDBC) y se registra en el ADR-0020.
- **Identificador opcional**: sin él, el chat funciona como hasta ahora; así los clientes antiguos y las pruebas de
  contrato previas no cambian.
- **Qué se guarda**: texto de cliente y texto final del asistente por turno. Las fuentes y los bloques de contexto no se
  guardan; cada turno recupera su propio contexto.
- **Límite de historial por defecto**: 5 turnos recientes (10 mensajes), ajustable. Es un punto de partida que la
  evaluación de F2 calibrará; el plan fija el valor definitivo y la política de recorte frente al presupuesto de contexto.
- **Comprensión del seguimiento**: el plan decide la técnica (p. ej. reformular la pregunta con el historial antes de
  buscar, o combinar el último mensaje con el anterior) y su coste en latencia; esta spec fija el resultado observable
  (FR-006, SC-001, SC-002).
- **Sin caducidad del historial**: las conversaciones se conservan hasta que una historia posterior defina retención y
  borrado; el volumen en F1 es de desarrollo y datos sintéticos.
- **Sin persistencia en el navegador**: recargar la página inicia una conversación nueva en pantalla (el historial del
  servidor queda sin uso hasta su retención futura).
- **Infraestructura**: no cambia (Ollama, PostgreSQL); se añade solo una migración al esquema `chat`.
- **Idioma**: documentos, prompt, etiquetas de la interfaz y respuestas en español; identificadores de código en inglés
  (Constitución III).
