# Feature Specification: Pantalla de chat con streaming y Markdown

**Feature Branch**: `feature/LAB-14-chat-streaming-markdown-ui`

**Created**: 2026-10-26

**Status**: Draft

**Input**: User description: "LAB-14 (F0-06) Pantalla de chat con streaming y Markdown. Como cliente, quiero una pantalla de chat que muestre el streaming y renderice Markdown. Criterios: burbujas usuario/bot e indicador de escritura; botón detener; Markdown renderizado de forma segura."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Conversar viendo la respuesta escribirse (Priority: P1)

Como cliente, quiero escribir un mensaje en una pantalla de chat y ver mi mensaje y la respuesta del asistente como
burbujas distintas, con la respuesta apareciendo a medida que se genera y un indicador de escritura mientras llega,
para sentir una conversación fluida en lugar de esperar a que la respuesta termine completa.

**Why this priority**: Es el objetivo de la historia y cierra la fase F0 («chat con streaming de punta a punta»): une
la base Angular (LAB-13) con el backend de streaming (LAB-12). Sin este flujo el cliente no tiene chat.

**Independent Test**: Con el backend y el modelo local levantados y la app abierta en `/chat`, escribir un mensaje y
enviarlo; comprobar que aparece la burbuja del usuario, que la burbuja del asistente crece fragmento a fragmento con
el indicador de escritura visible, y que al terminar el indicador desaparece.

**Acceptance Scenarios**:

1. **Given** la app abierta en la ruta de chat, **When** se carga la pantalla, **Then** se ve el historial de la
   conversación (vacío al inicio, con una invitación a escribir), un campo de texto y un control para enviar.
2. **Given** un mensaje escrito, **When** el cliente lo envía (botón o tecla Enter), **Then** el mensaje aparece de
   inmediato como burbuja del usuario, el campo se vacía y se crea la burbuja del asistente.
3. **Given** que el backend empieza a responder, **When** llegan fragmentos de texto, **Then** la burbuja del
   asistente los muestra en orden a medida que llegan, no todos juntos al final.
4. **Given** una respuesta en curso, **When** aún no ha terminado, **Then** se muestra un indicador de escritura
   asociado a la burbuja del asistente (también antes de que llegue el primer fragmento).
5. **Given** que el backend señala el final de la respuesta, **When** se recibe, **Then** el indicador de escritura
   desaparece, la burbuja queda completa y el cliente puede enviar un nuevo mensaje.
6. **Given** una conversación con varios mensajes, **When** llegan nuevos fragmentos, **Then** la vista se mantiene
   al final para seguir la respuesta, salvo que el cliente haya subido a leer mensajes anteriores.
7. **Given** un mensaje con tildes, eñes o emojis, **When** se envía y se recibe la respuesta, **Then** los
   caracteres se muestran íntegros.

---

### User Story 2 - Detener la respuesta (Priority: P1)

Como cliente, quiero un botón para detener la respuesta mientras se está generando, para cortar una respuesta que no
me interesa o que va por mal camino sin tener que esperar a que termine.

**Why this priority**: Es un criterio de aceptación explícito. Además libera el modelo local, que es el recurso
escaso (LAB-12 cancela la generación al cerrarse la conexión).

**Independent Test**: Enviar un mensaje que provoque una respuesta larga, pulsar «Detener» a mitad y comprobar que
los fragmentos dejan de llegar, el texto ya recibido se conserva y el chat vuelve a aceptar mensajes.

**Acceptance Scenarios**:

1. **Given** una respuesta en curso, **When** se mira el área de entrada, **Then** el control de enviar se sustituye
   (o acompaña) por un botón «Detener» visible y accesible.
2. **Given** una respuesta en curso, **When** el cliente pulsa «Detener», **Then** deja de mostrarse texto nuevo, el
   indicador de escritura desaparece y la conexión con el backend se cierra.
3. **Given** una respuesta detenida, **When** se revisa la burbuja, **Then** conserva el texto recibido hasta ese
   momento y queda marcada como interrumpida, sin tratarse como un error.
4. **Given** una respuesta detenida, **When** el cliente escribe otro mensaje, **Then** se envía con normalidad y el
   fragmento de la respuesta anterior no se mezcla con la nueva.
5. **Given** que no hay respuesta en curso, **When** se mira el área de entrada, **Then** el botón «Detener» no se
   muestra.

---

### User Story 3 - Respuestas con Markdown renderizado de forma segura (Priority: P1)

Como cliente, quiero que las respuestas con formato Markdown (listas, negritas, código, enlaces, tablas) se vean con
su formato, y que ningún contenido de la respuesta pueda ejecutar código en mi navegador, para leer respuestas claras
sin riesgo.

**Why this priority**: Es un criterio de aceptación explícito. Los modelos responden en Markdown de forma habitual
y mostrarlo como texto plano degrada la lectura; renderizarlo sin sanear abriría la puerta a inyección de HTML/script
(XSS), por lo que el renderizado seguro es parte del criterio, no un extra.

**Independent Test**: Enviar mensajes que hagan responder con listas, bloques de código, tablas y enlaces, y
comprobar que se ven formateados; después forzar una respuesta que contenga HTML/script malicioso y comprobar que no
se ejecuta ni altera la página.

**Acceptance Scenarios**:

1. **Given** una respuesta con Markdown (encabezados, negritas, cursivas, listas, citas, tablas), **When** se muestra
   en la burbuja del asistente, **Then** se renderiza con el formato correspondiente.
2. **Given** una respuesta con un bloque de código, **When** se muestra, **Then** aparece en formato monoespaciado,
   con scroll horizontal si es ancho, sin romper el ancho de la burbuja.
3. **Given** una respuesta con un enlace, **When** se muestra, **Then** el enlace es visible y al pulsarlo se abre en
   una pestaña nueva sin dar acceso a la página de origen; los enlaces con esquemas peligrosos (por ejemplo
   `javascript:`) no son navegables.
4. **Given** una respuesta que contiene HTML o script (por ejemplo una etiqueta de script, un atributo de evento o un
   marco incrustado), **When** se muestra, **Then** no se ejecuta ni se inserta como HTML activo: se elimina o se
   muestra como texto.
5. **Given** una respuesta que llega a medias (Markdown incompleto, como un bloque de código sin cerrar), **When**
   se renderiza durante el streaming, **Then** la burbuja sigue siendo legible, no se rompe la página y el resultado
   final es correcto al completarse.
6. **Given** un mensaje escrito por el cliente, **When** se muestra en su burbuja, **Then** se muestra como texto
   literal, sin interpretar Markdown ni HTML.

---

### User Story 4 - Errores claros en el chat (Priority: P2)

Como cliente, quiero que el chat me avise con claridad cuando algo falla (backend caído, modelo no disponible, fallo a
mitad de respuesta, mensaje inválido), para saber qué ocurrió y poder reintentar sin quedarme esperando.

**Why this priority**: Sin manejo de errores un fallo deja la interfaz colgada con el indicador de escritura
encendido. No bloquea el camino feliz, pero es necesario para que la pantalla sea utilizable.

**Independent Test**: Con el backend detenido enviar un mensaje; con el modelo detenido enviar otro; provocar un corte
a mitad de una respuesta; en cada caso comprobar que aparece un aviso entendible y que el chat vuelve a quedar
utilizable.

**Acceptance Scenarios**:

1. **Given** que el backend no está disponible o el modelo no está disponible (503), **When** el cliente envía un
   mensaje, **Then** ve un aviso en español que lo explica, el indicador de escritura desaparece y puede reintentar.
2. **Given** un error de validación del backend (mensaje vacío o demasiado largo), **When** ocurre, **Then** el aviso
   indica el motivo en términos comprensibles.
3. **Given** que el flujo ya empezó y el backend envía un evento de error o se corta la conexión, **When** ocurre,
   **Then** la burbuja conserva el texto recibido, se marca como fallida, se muestra el aviso y el indicador de
   escritura desaparece.
4. **Given** una respuesta fallida, **When** el cliente quiere reintentar, **Then** dispone de una acción para
   reenviar el último mensaje o puede escribir uno nuevo.
5. **Given** cualquier error, **When** se muestra al cliente, **Then** no se exponen detalles técnicos internos (trazas,
   códigos crudos) más allá de un identificador de referencia opcional.

---

### User Story 5 - Entrada de mensajes cómoda y controlada (Priority: P2)

Como cliente, quiero que el campo de mensaje sea cómodo (Enter envía, Mayús+Enter salta de línea), rechace envíos
vacíos o excesivos y no me deje enviar mientras otra respuesta está en curso, para evitar errores antes de llegar al
backend.

**Why this priority**: Reduce errores evitables y refleja las reglas del backend (LAB-12), pero el chat funciona sin
ello; es una mejora de usabilidad.

**Independent Test**: Intentar enviar un mensaje vacío, uno solo con espacios, uno que supere el límite y uno mientras
hay una respuesta en curso; comprobar que no se envían y que se explica el motivo.

**Acceptance Scenarios**:

1. **Given** el campo vacío o solo con espacios, **When** el cliente intenta enviar, **Then** el envío está
   deshabilitado y no se hace ninguna petición.
2. **Given** un mensaje de más de 4000 caracteres (el máximo del backend), **When** el cliente escribe o intenta
   enviarlo, **Then** ve el contador de caracteres, el envío queda bloqueado y se indica el límite.
3. **Given** un mensaje de exactamente 4000 caracteres, **When** se envía, **Then** se acepta.
4. **Given** una respuesta en curso, **When** el cliente intenta enviar otro mensaje, **Then** el envío está
   bloqueado hasta que la respuesta termine o se detenga.
5. **Given** un campo de varias líneas, **When** el cliente pulsa Enter, **Then** se envía; con Mayús+Enter se
   inserta un salto de línea.
6. **Given** un envío exitoso, **When** la pantalla vuelve a estar lista, **Then** el foco queda en el campo de texto.

---

### User Story 6 - Pantalla accesible y adaptable (Priority: P3)

Como cliente, quiero usar el chat desde una pantalla ancha o desde el móvil y con teclado o lector de pantalla, para
poder consultar desde cualquier dispositivo.

**Why this priority**: La accesibilidad y la adaptabilidad son requisitos de calidad de la base (LAB-13) y deben
mantenerse en la pantalla nueva, pero no condicionan el flujo principal.

**Independent Test**: Abrir el chat a 360 px y a 1440 px de ancho y recorrer todo el flujo solo con teclado;
comprobar que no hay desbordes y que las respuestas nuevas se anuncian a un lector de pantalla.

**Acceptance Scenarios**:

1. **Given** la pantalla de chat, **When** se abre a 360 px y a 1440 px de ancho, **Then** no hay desbordes
   horizontales ni controles inaccesibles; las burbujas se adaptan al ancho.
2. **Given** el chat, **When** se usa solo con teclado, **Then** se puede escribir, enviar y detener sin ratón, con
   el foco visible.
3. **Given** un lector de pantalla, **When** llega una respuesta, **Then** el historial se expone como región viva y
   cada burbuja identifica su autor (cliente o asistente) y el estado de escritura.

---

### Edge Cases

- **Respuesta vacía del modelo**: llega solo el evento de finalización; la burbuja del asistente queda vacía con una
  indicación («sin respuesta») y el chat sigue utilizable; no es un error.
- **Fragmentos que parten un carácter multibyte o una línea de evento entre lecturas**: el texto final no pierde ni
  duplica caracteres (la concatenación en orden es exacta).
- **Respuesta muy larga**: la vista no se degrada visiblemente; el renderizado incremental no bloquea la escritura ni
  el botón «Detener».
- **Pulsar «Detener» justo cuando la respuesta termina**: el resultado es coherente (una sola burbuja, sin estado
  duplicado, sin error).
- **Envío doble rápido** (Enter repetido): solo se envía un mensaje.
- **Navegar a otra ruta o cerrar la pestaña con una respuesta en curso**: la conexión se cierra para que el backend
  cancele la generación, sin dejar procesos abiertos.
- **Recargar la página**: la conversación se pierde (sin persistencia, fuera de alcance) y el chat arranca vacío.
- **Primer token lento (modelo frío)**: el indicador de escritura se mantiene visible hasta que llegue algo; no se
  considera error mientras la conexión siga abierta.
- **Markdown con HTML mezclado, imágenes remotas o enlaces con esquemas inusuales**: el HTML activo se descarta; las
  imágenes remotas no se cargan automáticamente (evita rastreo y fugas) y se muestra su texto alternativo o enlace.
- **Mensajes del cliente con caracteres especiales** (`<`, `&`, backticks): se muestran literales.
- **Backend con SSE retenido por un proxy**: si no llegan eventos, el cliente no los inventa; el indicador sigue
  visible hasta que llegue el primero o se detenga.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: La app MUST ofrecer una pantalla de chat en la ruta `/chat`, dentro del layout base, con una entrada
  en la navegación, un historial de mensajes y un área de entrada (campo de texto y control de envío).
- **FR-002**: Cada mensaje MUST mostrarse en una burbuja diferenciada visualmente según su autor (cliente o
  asistente), con alineación y estilo distintos y el autor identificable también para lectores de pantalla.
- **FR-003**: Al enviar, el mensaje del cliente MUST aparecer de inmediato en el historial y MUST iniciarse una
  petición `POST /api/chat` con el cuerpo `{ "message": "..." }` y aceptando `text/event-stream` (contrato de LAB-12).
- **FR-004**: La app MUST consumir el flujo de eventos del backend (`token`, `done`, `error`) y acumular los
  fragmentos de `token` en orden en la burbuja del asistente, mostrándolos a medida que llegan.
- **FR-005**: La app MUST mostrar un indicador de escritura en la burbuja del asistente desde el envío hasta que
  llegue `done`, `error`, se detenga la respuesta o falle la conexión, incluido el intervalo antes del primer
  fragmento.
- **FR-006**: Mientras haya una respuesta en curso, la app MUST mostrar un botón «Detener» que cierre la conexión con
  el backend (cancelando la generación, según LAB-12), conserve el texto ya recibido, marque la burbuja como
  interrumpida y devuelva el chat a un estado utilizable. Sin respuesta en curso el botón no se muestra.
- **FR-007**: La app MUST renderizar el contenido de las burbujas del asistente como Markdown (como mínimo:
  párrafos, encabezados, énfasis, listas, citas, código en línea y en bloque, enlaces y tablas), y el contenido del
  cliente como texto literal.
- **FR-008**: El renderizado de Markdown MUST ser seguro: todo HTML generado MUST sanearse con una lista de
  elementos y atributos permitidos antes de insertarse en la página; scripts, manejadores de eventos, marcos
  incrustados y URL con esquemas peligrosos (`javascript:`, `data:` ejecutable) MUST NOT llegar al DOM activo.
- **FR-009**: Los enlaces de las respuestas MUST abrirse en una pestaña nueva sin dar acceso a la página de origen,
  y las imágenes remotas MUST NOT cargarse automáticamente.
- **FR-010**: El renderizado MUST tolerar Markdown incompleto durante el streaming (por ejemplo un bloque de código
  sin cerrar) sin romper el diseño ni lanzar errores, y el resultado final MUST coincidir con el de renderizar la
  respuesta completa de una vez.
- **FR-011**: Los errores MUST mostrarse al cliente en español, sin exponer trazas ni detalles internos, cubriendo
  como mínimo: backend inalcanzable, modelo no disponible (503), mensaje inválido (400), evento `error` a mitad de
  respuesta y corte de conexión. Tras un error el indicador de escritura MUST desaparecer y el chat MUST quedar
  utilizable.
- **FR-012**: Ante una respuesta fallida, la app MUST conservar el texto parcial recibido, marcar la burbuja como
  fallida y ofrecer reintentar el último mensaje.
- **FR-013**: La app MUST impedir el envío de mensajes vacíos o de solo espacios, de más de 4000 caracteres (con
  contador visible al acercarse al límite) y mientras haya una respuesta en curso; en esos casos no se hace petición
  alguna.
- **FR-014**: Enter MUST enviar el mensaje y Mayús+Enter MUST insertar un salto de línea; tras enviar, el foco MUST
  volver al campo de texto.
- **FR-015**: El historial MUST desplazarse automáticamente al final con cada fragmento nuevo, salvo cuando el
  cliente haya subido a leer mensajes anteriores; en ese caso se ofrece volver al final.
- **FR-016**: Al abandonar la pantalla (cambio de ruta o cierre) con una respuesta en curso, la app MUST cerrar la
  conexión para que el backend cancele la generación.
- **FR-017**: La pantalla MUST ser utilizable de 360 px a 1440 px de ancho sin desbordes, operable solo con teclado
  con foco visible, y exponer el historial como región viva para lectores de pantalla.
- **FR-018**: El estado de la conversación (mensajes, respuesta en curso, error) MUST modelarse con signals y los
  componentes MUST ser standalone, coherentes con la base de LAB-13; la lógica de consumo del flujo MUST estar
  separada de los componentes de presentación para poder probarse de forma aislada.
- **FR-019**: La lógica de fragmentación del flujo (ensamblado de eventos a partir de bloques de bytes, decodificación
  UTF-8 sin cortar caracteres, orden) MUST estar cubierta por pruebas unitarias, incluidos los casos de error,
  cancelación y Markdown malicioso.
- **FR-020**: Los mensajes de la interfaz MUST estar en español; los identificadores de código, en inglés.
- **FR-021**: Quedan fuera de alcance: persistencia del historial y conversaciones múltiples, memoria entre turnos
  (cada petición es independiente según LAB-12), RAG y citas, autenticación, adjuntos, voz, internacionalización,
  modo oscuro, copiar/regenerar respuesta, y consolas de agentes o administración.

### Key Entities *(include if feature involves data)*

- **Mensaje del chat**: elemento del historial; tiene un autor (cliente o asistente), un contenido de texto y un
  estado (completo, en escritura, interrumpido, fallido). El contenido del asistente se interpreta como Markdown; el
  del cliente es texto literal.
- **Conversación**: lista ordenada de mensajes de la sesión actual, solo en memoria de la pestaña; se pierde al
  recargar.
- **Evento del flujo**: unidad que llega del backend: fragmento de texto (`token`), finalización (`done`) o error
  (`error` con código y descripción), según el contrato de LAB-12.
- **Error de chat**: fallo mostrado al cliente, con un mensaje en español derivado del tipo de fallo (red, modelo
  no disponible, validación, fallo en el flujo) y la posibilidad de reintentar.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El cliente ve su mensaje en el historial en menos de 100 ms desde que lo envía y el primer fragmento de
  la respuesta aparece en pantalla menos de 300 ms después de que el backend lo emita.
- **SC-002**: Para una respuesta larga, el texto crece de forma continua en pantalla (al menos 10 actualizaciones
  visibles antes de terminar) y la interfaz no se bloquea: «Detener» responde en menos de 500 ms durante el streaming.
- **SC-003**: Tras pulsar «Detener», no aparece texto nuevo en la burbuja y el chat acepta un nuevo mensaje en menos
  de 1 segundo; el backend registra la petición como cancelada, no como error.
- **SC-004**: El 100 % de las respuestas con Markdown de la batería de pruebas (encabezados, listas, tablas, código,
  enlaces) se renderiza con su formato; el 0 % de las cargas maliciosas de la batería (script, eventos en atributos,
  marcos, `javascript:`) se ejecuta o llega al DOM activo.
- **SC-005**: El 100 % de los fallos simulados (backend caído, 503, 400, error en mitad del flujo, corte de red) deja
  el chat utilizable con un aviso comprensible y sin indicador de escritura colgado.
- **SC-006**: La pantalla se muestra sin desbordes ni controles inaccesibles a 360 px y a 1440 px, y todo el flujo
  (escribir, enviar, detener, reintentar) se completa solo con teclado.
- **SC-007**: Un cliente nuevo completa su primera conversación (abrir el chat, escribir, leer la respuesta) en menos
  de 30 segundos sin instrucciones.
- **SC-008**: Las pruebas unitarias del frontend y el pipeline de CI (lint, pruebas y build) pasan en verde con la
  pantalla de chat incluida.

## Assumptions

- Depende de LAB-12 (`POST /api/chat`, flujo SSE con eventos `token`, `done` y `error`, límite de 4000 caracteres)
  y de LAB-13 (app Angular base con layout, enrutado, proxy de desarrollo que transmite SSE sin acumular, y la
  librería de componentes ya elegida); se reutilizan sin cambiarlos. La ruta `/chat` fue reservada por LAB-13.
- El navegador no puede usar `EventSource` con `POST`; el consumo del flujo se hace leyendo la respuesta como flujo
  de bytes (decisión técnica que se registra en la fase de plan).
- El Markdown se renderiza en el cliente y se sanea antes de insertarse; la elección de las librerías de
  renderizado y saneamiento, y su justificación, se registran en la fase de plan.
- La conversación vive solo en memoria de la pestaña; recargar la pantalla la reinicia. Cada petición al backend
  contiene únicamente el último mensaje (el backend no tiene memoria, LAB-12).
- Las imágenes remotas en las respuestas no se cargan automáticamente por privacidad; el resto del formato
  Markdown estándar se soporta.
- Resaltado de sintaxis del código, botón de copiar y regeneración de respuestas son mejoras posteriores.
- No se requiere autenticación en esta historia; el chat es de acceso abierto en el entorno local de F0.
- Los mensajes de error visibles al cliente se derivan del tipo de fallo (red, 400, 503, evento `error`); los códigos
  de `ResponseCodeEnum` del backend no se muestran tal cual.
- El gate de cobertura numérico de la constitución aplica al backend; para el frontend las pruebas unitarias deben
  existir y pasar en CI, con cobertura efectiva de la lógica de flujo y saneamiento (FR-019).
