# Feature Specification: Respuesta del chat en streaming (SSE)

**Feature Branch**: `feature/LAB-12-chat-streaming-sse`

**Created**: 2026-10-26

**Status**: Draft

**Input**: User description: "LAB-12 (F0-04) Respuesta del chat en streaming (SSE). Como cliente, quiero enviar un mensaje y ver la respuesta escribiéndose en tiempo real. Criterios: `POST /api/chat` devuelve SSE token a token desde Ollama; primer token en menos de 2 s en local."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Recibir la respuesta token a token (Priority: P1)

Como cliente, quiero enviar un mensaje al chat y recibir la respuesta del modelo a medida que se genera, para ver
cómo se escribe en tiempo real en lugar de esperar a que termine completa.

**Why this priority**: Es el objetivo de la historia y el cierre de la fase F0 («chat con streaming de punta a
punta»): sin este flujo no hay chat. Todo lo demás (historial, RAG, citas) se apoya en él.

**Independent Test**: Con el entorno local levantado y el modelo cargado, enviar un mensaje a `POST /api/chat` y
comprobar que la respuesta llega como un flujo de eventos con varios fragmentos de texto antes de que termine la
generación, y que el flujo se cierra de forma explícita al acabar.

**Acceptance Scenarios**:

1. **Given** el backend y el modelo local disponibles, **When** el cliente envía un mensaje válido a `POST /api/chat`,
   **Then** la respuesta es un flujo de eventos del servidor (`text/event-stream`) y cada evento de contenido lleva
   el siguiente fragmento de texto generado por el modelo.
2. **Given** una respuesta larga, **When** el modelo la va generando, **Then** los fragmentos llegan al cliente
   mientras se generan, no todos juntos al final.
3. **Given** que el modelo terminó de generar, **When** se emite el último fragmento, **Then** el servidor envía un
   evento de finalización y cierra el flujo, de modo que el cliente sabe sin ambigüedad que la respuesta está
   completa.
4. **Given** los fragmentos recibidos en orden, **When** el cliente los concatena, **Then** obtiene exactamente el
   texto completo de la respuesta, sin pérdidas, duplicados ni reordenamientos.
5. **Given** un mensaje con tildes, eñes, emojis u otros caracteres no ASCII, **When** se recibe la respuesta,
   **Then** los caracteres llegan íntegros, sin cortar un carácter entre dos eventos.

---

### User Story 2 - Primer token rápido (Priority: P1)

Como cliente, quiero que el primer fragmento de la respuesta aparezca en menos de 2 segundos, para sentir que el
chat responde de inmediato aunque la respuesta completa tarde más.

**Why this priority**: Es un criterio de aceptación explícito de la historia y es la razón de ser del streaming:
la latencia percibida depende del primer token, no de la respuesta completa.

**Independent Test**: Con el modelo ya cargado en memoria, enviar un mensaje corto y medir el tiempo entre el envío
de la petición y la llegada del primer evento de contenido; repetir varias veces y comprobar que cumple el límite.

**Acceptance Scenarios**:

1. **Given** el entorno local con el modelo ya cargado, **When** el cliente envía un mensaje, **Then** el primer
   evento de contenido llega en menos de 2 segundos.
2. **Given** el backend recibe la petición, **When** empieza a atenderla, **Then** abre el flujo y empieza a enviar
   sin esperar a que el modelo termine ni acumular la respuesta en memoria.
3. **Given** que el backend está en ejecución, **When** se consulta el punto de métricas, **Then** hay una medida
   del tiempo hasta el primer token y de la duración total de cada respuesta, para poder verificar este criterio
   sin cronometrar a mano.

---

### User Story 3 - Errores claros durante y antes del streaming (Priority: P2)

Como cliente, quiero recibir un aviso claro cuando algo falla (mensaje inválido, modelo no disponible o error a mitad
de la respuesta), para saber qué ocurrió y no quedarme esperando un flujo que nunca termina.

**Why this priority**: Sin manejo de errores, un fallo de Ollama deja al cliente colgado. No bloquea el camino
feliz, pero es necesario para que el chat sea utilizable y depurable.

**Independent Test**: Enviar un mensaje vacío, detener el modelo antes de enviar y provocar un corte a mitad de una
respuesta, y comprobar en cada caso que el cliente recibe un error identificable y que el flujo se cierra.

**Acceptance Scenarios**:

1. **Given** un mensaje vacío o formado solo por espacios, **When** se envía a `POST /api/chat`, **Then** el servidor
   responde con un error de validación (HTTP 400) con código de error estándar del proyecto, sin abrir flujo ni
   llamar al modelo.
2. **Given** un mensaje que supera la longitud máxima permitida, **When** se envía, **Then** el servidor responde
   con un error de validación (HTTP 400) y no llama al modelo.
3. **Given** que el modelo local no está disponible antes de empezar a responder, **When** se envía un mensaje,
   **Then** el servidor responde con un error de servicio no disponible (HTTP 503) con código de error estándar, y
   no con un flujo vacío ni un error genérico 500.
4. **Given** que el flujo ya empezó y el modelo falla a mitad de la respuesta, **When** ocurre el fallo, **Then** el
   servidor envía un evento de error con código y descripción, y cierra el flujo, sin dejar la conexión abierta.
5. **Given** cualquier error, **When** se registra en logs, **Then** el log lleva el identificador de correlación de
   la petición y no incluye el contenido completo del mensaje del cliente.

---

### User Story 4 - Cancelar liberando recursos (Priority: P3)

Como cliente, quiero poder cerrar la conexión (por ejemplo, al cerrar la pestaña o detener la respuesta) y que el
servidor deje de generar, para no desperdiciar capacidad del modelo local, que es limitada.

**Why this priority**: En una máquina local el modelo es el recurso escaso; seguir generando para nadie degrada las
siguientes peticiones. Es una mejora de robustez, no de funcionalidad básica.

**Independent Test**: Iniciar una respuesta larga, cerrar la conexión del cliente a mitad y comprobar que el
servidor cancela la generación y que una petición posterior se atiende con normalidad.

**Acceptance Scenarios**:

1. **Given** una respuesta en curso, **When** el cliente cierra la conexión, **Then** el servidor cancela la
   generación en el modelo y libera la petición.
2. **Given** una cancelación reciente, **When** llega una petición nueva, **Then** se atiende con normalidad y el
   tiempo hasta el primer token sigue dentro del límite.
3. **Given** una cancelación, **When** se revisan las métricas y los logs, **Then** la petición queda registrada como
   cancelada y no como error.

---

### Edge Cases

- **Mensaje vacío, solo espacios o ausente** (cuerpo sin el campo del mensaje, cuerpo vacío o JSON mal formado): el
  servidor responde 400 sin tocar el modelo.
- **Mensaje muy largo**: por encima del límite se rechaza con 400; justo en el límite se acepta (caso límite
  explícito en las pruebas).
- **Modelo aún no descargado o frío**: el primer token puede superar 2 s la primera vez (carga del modelo en
  memoria); el criterio de latencia aplica con el modelo ya cargado. Si el modelo no existe, se trata como modelo no
  disponible (503).
- **Ollama caído o inalcanzable**: 503 si se detecta antes de emitir el primer evento; evento de error si se detecta
  después.
- **Corte de red del cliente**: el servidor detecta el cierre y cancela la generación (User Story 4).
- **Peticiones concurrentes**: varias peticiones a la vez no mezclan fragmentos entre sí; cada flujo contiene solo su
  respuesta. Si el modelo local se satura, las peticiones esperan o fallan con error claro, nunca se cruzan.
- **Respuesta vacía del modelo**: se emite el evento de finalización sin eventos de contenido; no es un error.
- **Proxy o balanceador con buffering**: el flujo debe poder atravesar el ingreso del clúster sin quedar retenido
  hasta el final (cabeceras que desactivan el buffering).
- **Idioma**: el mensaje puede venir en español o inglés; el chat no restringe el idioma.
- **Cliente que no soporta SSE**: fuera de alcance; se documenta cómo consumir el flujo.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El backend MUST exponer `POST /api/chat`, que recibe un mensaje de texto del cliente en el cuerpo de
  la petición (JSON) y responde con un flujo de eventos del servidor (`Content-Type: text/event-stream`).
- **FR-002**: El flujo MUST emitir un evento de contenido por cada fragmento de texto que genera el modelo (Ollama),
  en el mismo orden en que se generan y sin esperar a la respuesta completa.
- **FR-003**: El flujo MUST terminar siempre con un evento de finalización explícito (o de error, ver FR-008) seguido
  del cierre de la conexión; el cliente nunca debe quedar esperando un flujo sin fin.
- **FR-004**: Los eventos MUST tener un formato estable y documentado: tipo de evento (contenido, finalización,
  error) y carga útil serializada, de modo que el frontend (LAB-13 en adelante) pueda consumirlos sin
  interpretaciones.
- **FR-005**: El primer evento de contenido MUST llegar en menos de 2 segundos desde que se envía la petición, en el
  entorno local con el modelo ya cargado y un mensaje corto.
- **FR-006**: El backend MUST propagar los fragmentos al cliente según se reciben del modelo, sin acumular la
  respuesta completa en memoria ni retenerla en buffers que retrasen el envío.
- **FR-007**: El backend MUST validar el mensaje antes de llamar al modelo: rechazar con HTTP 400 y código de error
  estándar los mensajes ausentes, vacíos, solo con espacios o que superen la longitud máxima (4000 caracteres).
- **FR-008**: Si el modelo falla después de iniciado el flujo, el backend MUST enviar un evento de error con código y
  descripción estándar y cerrar el flujo; si falla antes de enviar el primer evento, MUST responder con un error
  HTTP (503 para modelo no disponible).
- **FR-009**: Los errores MUST lanzarse como excepciones tipadas con códigos en `ResponseCodeEnum` y traducirse a
  HTTP solo en el manejador global; los adaptadores y casos de uso MUST NOT construir respuestas de error HTTP. Los
  errores durante el flujo (ya iniciado) se traducen a un evento de error por el mismo mecanismo central.
- **FR-010**: Cuando el cliente cierra la conexión, el backend MUST cancelar la generación en el modelo y liberar los
  recursos de la petición.
- **FR-011**: El acceso al modelo MUST estar detrás de un puerto de la capa de aplicación, de modo que el proveedor
  (Ollama) se pueda sustituir sin tocar el dominio ni el controlador; el controlador no contiene lógica y solo
  construye el comando y llama al puerto de entrada.
- **FR-012**: El modelo de chat MUST ser configurable por variable de entorno (`OLLAMA_CHAT_MODEL`, por defecto
  `llama3.2:3b`) y la dirección de Ollama por configuración; sin valores fijos en el código.
- **FR-013**: El backend MUST publicar métricas del chat: tiempo hasta el primer token, duración total de la
  respuesta y número de respuestas por resultado (completada, error, cancelada), sin etiquetas de cardinalidad
  ilimitada (nunca el contenido del mensaje).
- **FR-014**: Cada petición MUST llevar un identificador de correlación en los logs, y los logs MUST NOT incluir el
  contenido completo del mensaje ni de la respuesta (datos del cliente).
- **FR-015**: Las respuestas MUST ser independientes entre peticiones concurrentes: ningún fragmento de una petición
  puede aparecer en el flujo de otra.
- **FR-016**: Los fragmentos MUST transmitirse en UTF-8 sin cortar caracteres multibyte entre eventos.
- **FR-017**: El endpoint MUST estar documentado (contrato del flujo de eventos y de los errores) y el
  procedimiento para probarlo en local (por ejemplo con `curl -N`) MUST figurar en la documentación del backend.
- **FR-018**: Quedan fuera de alcance: persistencia de conversaciones e historial multi-turno, recuperación de
  conocimiento (RAG) y citas, autenticación y límites de uso por usuario, y la interfaz Angular del chat. Cada
  petición es independiente y sin memoria.

### Key Entities *(include if feature involves data)*

- **Mensaje de chat (petición)**: el texto que escribe el cliente; único dato de entrada, con longitud máxima y sin
  estado entre peticiones.
- **Fragmento de respuesta (evento de contenido)**: porción de texto generada por el modelo, entregada en orden;
  la concatenación de todos los fragmentos forma la respuesta.
- **Evento de finalización**: marca explícita de que la respuesta terminó correctamente.
- **Evento de error**: marca explícita de que la respuesta falló a mitad de camino, con código y descripción
  estándar del proyecto.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El cliente ve aparecer el primer fragmento de la respuesta en menos de 2 segundos en el 95 % de las
  peticiones en local con el modelo ya cargado.
- **SC-002**: Para una respuesta larga, el cliente recibe al menos 10 fragmentos separados en el tiempo antes de que
  termine la generación (es decir, la respuesta no llega de golpe).
- **SC-003**: El 100 % de las respuestas termina con un evento explícito de finalización o de error; 0 flujos
  quedan abiertos sin cierre tras un fallo del modelo.
- **SC-004**: El texto reconstruido a partir de los fragmentos coincide al 100 % con la respuesta completa del
  modelo, incluidos caracteres no ASCII.
- **SC-005**: Tras cerrar la conexión del cliente, la generación se cancela en menos de 2 segundos y la siguiente
  petición cumple SC-001.
- **SC-006**: Los mensajes inválidos (vacíos, demasiado largos, mal formados) se rechazan en el 100 % de los casos
  con un error HTTP 400 sin consumir tiempo del modelo.
- **SC-007**: Con 5 peticiones concurrentes, el 100 % de los flujos contiene únicamente su propia respuesta.
- **SC-008**: Un desarrollador nuevo prueba el chat en streaming desde la terminal siguiendo solo la documentación,
  en menos de 5 minutos con el entorno local ya levantado.

## Assumptions

- La historia pertenece a la fase F0 (Fundaciones) y a la épica LAB-1; se apoya en el backend modular (LAB-11), el
  entorno local con Ollama en compose (LAB-10) y las métricas base (LAB-102), todos ya fusionados.
- «Primer token en menos de 2 s» se mide en local con el modelo ya cargado en memoria (calentamiento previo) y con
  el modelo por defecto `llama3.2:3b`; la primera petición tras arrancar Ollama (carga en frío) queda fuera del
  criterio, como indica la historia al decir «en local».
- Una sola petición es independiente: no hay conversación, historial ni prompt de sistema complejo en esta historia
  (llegan con las historias de RAG y de conversaciones); se puede usar un prompt de sistema mínimo y fijo.
- El límite de longitud del mensaje es de 4000 caracteres, valor por defecto razonable para un mensaje de
  mesa de ayuda; es configurable.
- No hay autenticación en esta fase (Keycloak llega después); el endpoint queda abierto en el entorno local y
  protegerlo será parte de la historia de seguridad correspondiente.
- El cliente consumidor inicial es `curl`/herramientas de prueba y, después, el frontend Angular (historia
  posterior); por eso el formato de eventos se documenta como contrato.
- La integración con el modelo usa Spring AI 2.0 sobre Ollama (ADR-0003 y ADR-0004) detrás de un puerto; la
  elección concreta se detalla en `/speckit-plan`.
- Cobertura mínima del 80 % en el módulo modificado; en CI las llamadas al modelo se simulan o se graban (principio
  IV de la constitución), y solo la verificación local usa Ollama real.
- Sin datos reales de clientes: las pruebas usan mensajes de ejemplo.
