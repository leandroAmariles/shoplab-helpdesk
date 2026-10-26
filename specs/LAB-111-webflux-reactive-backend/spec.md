# Feature Specification: Backend reactivo con Spring WebFlux

**Feature Branch**: `refactor/LAB-111-webflux-reactive-backend`

**Created**: 2026-10-26

**Status**: Draft

**Input**: User description: "LAB-111 (F0-23) Backend reactivo con Spring WebFlux. Como desarrollador, quiero el backend sobre
Spring WebFlux, para servir el streaming del chat y las llamadas al LLM sin bloquear un hilo por petición. Criterios:
starter web reemplazado por el reactivo (Netty); `POST /api/chat` de LAB-12 migrado a un flujo reactivo de eventos SSE
sin cambiar su contrato; cancelación del cliente propagada al LLM; llamadas salientes con `WebClient`; persistencia
JDBC aislada en `Schedulers.boundedElastic()` dentro de `infrastructure`; BlockHound en los tests; tests de LAB-12 con
`WebTestClient` y `StepVerifier`, cobertura >= 80 %; `/actuator/prometheus` y métricas HTTP de LAB-102 intactas; ADR
«Web reactiva con persistencia JDBC». Depende de LAB-12; va antes de LAB-14 y LAB-105."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - El chat sigue funcionando igual tras la migración (Priority: P1)

Como cliente del chat (hoy `curl`, mañana el frontend Angular), quiero que `POST /api/chat` se comporte exactamente
igual que en LAB-12 —mismo formato de eventos, mismos errores, mismo primer token en menos de 2 s—, para que la
migración del backend a un modelo reactivo no me obligue a cambiar nada.

**Why this priority**: Es el criterio central de la historia: «el frontend no nota la diferencia». Una migración que
rompe el contrato de LAB-12 bloquea LAB-14 (pantalla de chat) y no aporta valor.

**Independent Test**: Ejecutar contra el backend migrado el mismo conjunto de casos del contrato de LAB-12 (camino
feliz, mensaje vacío, mensaje demasiado largo, modelo no disponible, fallo a mitad de respuesta) y comprobar que
estado HTTP, tipos de evento, códigos de error y orden de los fragmentos son idénticos a los documentados.

**Acceptance Scenarios**:

1. **Given** el backend migrado y el modelo local disponible, **When** el cliente envía un mensaje válido a
   `POST /api/chat`, **Then** recibe un flujo `text/event-stream` con los mismos tipos de evento (contenido,
   finalización, error) y la misma forma de carga útil que definía LAB-12.
2. **Given** el modelo ya cargado en el entorno local, **When** el cliente envía un mensaje corto, **Then** el primer
   evento de contenido llega en menos de 2 segundos, igual que antes de la migración.
3. **Given** un mensaje vacío, solo espacios, ausente o demasiado largo, **When** se envía, **Then** el servidor
   responde 400 con el código de error estándar, sin abrir flujo ni llamar al modelo.
4. **Given** que el modelo no está disponible antes de emitir el primer evento, **When** se envía un mensaje,
   **Then** el servidor responde 503 con el código estándar; si falla a mitad de la respuesta, envía un evento de
   error y cierra el flujo.
5. **Given** los fragmentos recibidos en orden, **When** el cliente los concatena, **Then** obtiene el texto completo
   sin pérdidas, duplicados ni reordenamientos, incluidos caracteres no ASCII.

---

### User Story 2 - Cancelar al cliente cancela al modelo (Priority: P1)

Como desarrollador, quiero que, si el cliente cierra la conexión, el servidor cancele de inmediato la generación en el
modelo, para no desperdiciar capacidad del LLM local ni producir tokens huérfanos que nadie lee.

**Why this priority**: Es un criterio explícito y la razón práctica de usar un modelo reactivo de extremo a extremo:
la cancelación debe viajar desde el cliente hasta el proveedor del modelo sin pasos intermedios que la absorban.

**Independent Test**: Iniciar una respuesta larga, cerrar la conexión del cliente a mitad y comprobar (con un modelo
simulado que registre la cancelación de su suscripción) que la generación se detiene y que no se siguen produciendo
fragmentos; después, enviar una petición nueva y verificar que se atiende con normalidad.

**Acceptance Scenarios**:

1. **Given** una respuesta en curso, **When** el cliente cierra la conexión, **Then** la suscripción al modelo se
   cancela y no se generan más fragmentos para esa petición.
2. **Given** una cancelación reciente, **When** llega una petición nueva, **Then** se atiende con normalidad y el
   primer token sigue dentro del límite.
3. **Given** una cancelación, **When** se revisan las métricas y los logs, **Then** la petición queda registrada como
   cancelada y no como error (comportamiento de LAB-12 preservado).

---

### User Story 3 - Nada bloquea el hilo de eventos (Priority: P1)

Como desarrollador, quiero que ninguna operación bloqueante se ejecute sobre los hilos que atienden las peticiones, y
que las pruebas lo detecten automáticamente, para que el servidor aguante muchas conexiones de streaming simultáneas
sin agotar hilos y para que un bloqueo accidental futuro falle en CI y no en producción.

**Why this priority**: La migración solo entrega su beneficio si es reactiva de verdad. Un solo acceso JDBC o una
llamada bloqueante en el hilo de eventos degrada todas las conexiones a la vez; sin una detección automática, este
tipo de regresión pasa inadvertida.

**Independent Test**: Ejecutar la suite de pruebas con la detección de bloqueos activa: debe pasar con el código
migrado y fallar de forma comprobable cuando se introduce una llamada bloqueante deliberada en el camino reactivo
(prueba negativa).

**Acceptance Scenarios**:

1. **Given** la suite de pruebas con detección de bloqueos activa, **When** se ejecutan las pruebas del chat y de la
   persistencia, **Then** ninguna operación bloqueante se ejecuta en un hilo del bucle de eventos y la suite pasa.
2. **Given** una llamada bloqueante deliberada en el camino reactivo, **When** corre la suite, **Then** la detección
   la reporta y la prueba falla (se verifica que el mecanismo realmente detecta).
3. **Given** que la persistencia (almacén vectorial, memoria de conversación, registro de eventos de Modulith y
   migraciones) sigue siendo JDBC, **When** un caso de uso la invoca desde el flujo reactivo, **Then** la llamada
   bloqueante se ejecuta aislada en el planificador elástico dentro de la capa de infraestructura y nunca en el
   dominio, la aplicación ni el controlador.

---

### User Story 4 - Observabilidad intacta (Priority: P2)

Como operador, quiero que `/actuator/prometheus` y las métricas HTTP de LAB-102 sigan funcionando tras el cambio de
pila web, para no perder los paneles ni las alertas ya construidos.

**Why this priority**: El cambio de pila web afecta a quien emite las métricas HTTP; una regresión silenciosa dejaría
Grafana vacío. No bloquea el chat, pero rompería una historia ya entregada.

**Independent Test**: Levantar el backend migrado, hacer peticiones a `/api/chat` y consultar
`/actuator/prometheus`: deben aparecer las métricas HTTP de servidor con los mismos nombres y etiquetas que en LAB-102,
y las métricas del chat de LAB-12.

**Acceptance Scenarios**:

1. **Given** el backend migrado en ejecución, **When** se consulta `/actuator/prometheus`, **Then** responde 200 con
   las métricas base (JVM, HTTP del servidor) con los mismos nombres de LAB-102.
2. **Given** peticiones al chat, **When** se consultan las métricas, **Then** las métricas HTTP registran la petición
   con su estado y ruta, y las del chat (tiempo hasta el primer token, duración, resultado) siguen publicándose.
3. **Given** los paneles y dashboards de LAB-102, **When** se abren contra el backend migrado, **Then** muestran datos
   sin modificar consultas.

---

### User Story 5 - La decisión queda registrada (Priority: P2)

Como miembro del equipo, quiero un ADR «Web reactiva con persistencia JDBC» que explique por qué WebFlux en la capa
web, por qué no R2DBC y qué alternativa se descartó, para que la decisión no se reabra sin contexto y los próximos
módulos sigan el mismo criterio.

**Why this priority**: La constitución exige un ADR para decisiones arquitectónicas significativas. Es documentación,
no funcionalidad, pero es parte de la definición de terminado de la historia.

**Independent Test**: Revisar el ADR publicado en el registro de ADR: sigue la plantilla, está enlazado desde el
índice y cubre las tres decisiones pedidas.

**Acceptance Scenarios**:

1. **Given** el registro de ADR, **When** se abre el nuevo ADR, **Then** sigue la plantilla del proyecto y figura en
   el índice.
2. **Given** el ADR, **When** se lee, **Then** justifica WebFlux en la capa web, explica por qué no se usa R2DBC
   (el almacén vectorial, Modulith y las migraciones dependen de JDBC) y describe la alternativa descartada de MVC con
   hilos virtuales con sus razones.
3. **Given** el ADR, **When** se lee, **Then** indica la regla de convivencia resultante: web y proveedor del modelo
   reactivos, persistencia bloqueante aislada en infraestructura.

---

### Edge Cases

- **Bloqueo accidental futuro**: una dependencia nueva que bloquea el hilo de eventos debe hacer fallar las pruebas
  (User Story 3), no pasar desapercibida.
- **Bloqueos legítimos conocidos del arranque** (inicialización, migraciones de base de datos al arrancar): no deben
  provocar falsos positivos; si hay exclusiones, deben ser explícitas, mínimas y justificadas.
- **Cancelación durante una llamada JDBC en curso**: la llamada bloqueante ya iniciada no se interrumpe, pero su
  resultado se descarta y no se siguen encadenando operaciones; no queda ningún recurso retenido.
- **Saturación del planificador elástico**: si la persistencia es lenta, las peticiones esperan o fallan con error
  claro; nunca bloquean el hilo de eventos ni se cruzan respuestas entre peticiones.
- **Peticiones concurrentes**: varias a la vez no mezclan fragmentos (FR-015 de LAB-12 se mantiene).
- **Proxy con buffering**: el flujo debe seguir atravesando el ingreso del clúster sin quedar retenido (cabeceras de
  LAB-12 se conservan).
- **Contexto de correlación**: el identificador de correlación de los logs debe seguir presente aunque la ejecución
  salte entre hilos del bucle de eventos y del planificador elástico.
- **Otros endpoints existentes** (actuator, health, errores): conservan su comportamiento y formato de error.
- **Suite de pruebas de otros módulos**: los módulos que no son web siguen pasando sin cambios; las pruebas de
  arquitectura de Modulith siguen verdes.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El backend MUST ejecutar su capa web sobre el modelo reactivo no bloqueante (servidor Netty),
  sustituyendo al modelo de un hilo por petición; el starter web bloqueante MUST NOT permanecer como dependencia.
- **FR-002**: `POST /api/chat` MUST exponerse como un flujo reactivo de eventos SSE alimentado por el streaming del
  modelo, sin cambiar su contrato externo: ruta, método, cuerpo de petición, `Content-Type`, tipos de evento, forma de
  la carga útil, códigos de error y cabeceras anti-buffering de LAB-12.
- **FR-003**: El primer evento de contenido MUST seguir llegando en menos de 2 segundos en el entorno local con el
  modelo ya cargado y un mensaje corto (criterio de LAB-12 preservado).
- **FR-004**: Si el cliente cancela o cierra la conexión, el backend MUST cancelar la suscripción al modelo en el
  acto, sin generar tokens huérfanos, y registrar la petición como cancelada.
- **FR-005**: Las llamadas HTTP salientes del backend MUST realizarse con el cliente HTTP reactivo; MUST NOT quedar
  clientes HTTP bloqueantes en el camino de las peticiones.
- **FR-006**: La persistencia MUST seguir siendo JDBC: almacén vectorial, memoria de conversación, registro de
  eventos de Modulith y migraciones de base de datos mantienen su implementación y comportamiento.
- **FR-007**: Toda llamada bloqueante (JDBC u otra) MUST aislarse en el planificador elástico de Reactor y vivir
  exclusivamente en la capa `infrastructure`; el dominio, los casos de uso y el controlador MUST NOT contener
  llamadas bloqueantes ni conocer el planificador (principio II de la constitución).
- **FR-008**: Los puertos de la capa de aplicación MUST exponer tipos reactivos donde el flujo lo requiera, sin filtrar
  tipos de infraestructura (cliente del modelo, JDBC) al dominio.
- **FR-009**: La suite de pruebas MUST ejecutarse con detección de bloqueos activa y MUST fallar si una operación
  bloquea un hilo del bucle de eventos; una prueba negativa MUST demostrar que la detección funciona.
- **FR-010**: Las pruebas de LAB-12 MUST migrarse al cliente de pruebas reactivo y a la verificación de flujos
  reactivos, cubriendo los mismos escenarios (feliz, validación, modelo no disponible, fallo a mitad, cancelación,
  concurrencia, UTF-8), y la cobertura de líneas MUST mantenerse >= 80 %.
- **FR-011**: Los errores MUST seguir lanzándose como excepciones tipadas con códigos en `ResponseCodeEnum` y
  traducirse a HTTP solo en el manejador global, ahora en su variante reactiva; los errores durante el flujo ya
  iniciado se traducen a un evento de error por el mismo mecanismo central (principio III).
- **FR-012**: `/actuator/prometheus` MUST seguir respondiendo, y las métricas HTTP de servidor de LAB-102 y las
  métricas del chat de LAB-12 MUST conservar nombres, etiquetas y semántica; los dashboards existentes MUST seguir
  funcionando sin cambios.
- **FR-013**: El identificador de correlación MUST propagarse en los logs a través de los saltos entre hilos del flujo
  reactivo, y los logs MUST NOT incluir el contenido completo del mensaje ni de la respuesta (FR-014 de LAB-12).
- **FR-014**: MUST existir un ADR «Web reactiva con persistencia JDBC» en `docs/adr/`, con la plantilla del
  proyecto y entrada en el índice, que cubra: por qué WebFlux en la capa web; por qué no R2DBC (pgvector, Modulith y
  Flyway dependen de JDBC); y la alternativa descartada de MVC con hilos virtuales.
- **FR-015**: La configuración existente (modelo, dirección de Ollama, límite de longitud) MUST conservar sus
  nombres y valores por defecto; la migración no introduce valores fijos nuevos en el código.
- **FR-016**: La documentación del backend (cómo probar el chat en local con `curl -N`) MUST seguir siendo válida sin
  cambios para el usuario; solo se actualizan las notas de arquitectura que mencionen el modelo bloqueante.
- **FR-017**: Quedan fuera de alcance: cambios de contrato del chat, R2DBC o cualquier migración de la persistencia,
  hilos virtuales, nuevas funciones del chat (historial, RAG, citas), autenticación y la interfaz Angular
  (LAB-14); LAB-105 se apoya en esta historia pero no forma parte de ella.

### Key Entities *(include if feature involves data)*

- **Flujo de eventos del chat**: secuencia ordenada de eventos de contenido que termina siempre en un evento de
  finalización o de error; es el contrato público que la migración no puede alterar.
- **Suscripción al modelo**: vínculo vivo entre una petición y la generación en el modelo; existe mientras el cliente
  esté conectado y se cancela cuando este se va.
- **Operación bloqueante aislada**: acceso a persistencia JDBC u otro recurso bloqueante, encapsulado en
  infraestructura y ejecutado fuera del bucle de eventos.
- **Registro de decisión (ADR)**: documento que fija la regla de convivencia entre web reactiva y persistencia JDBC.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los escenarios del contrato de LAB-12 (feliz, validación, 503, fallo a mitad, UTF-8)
  produce exactamente el mismo estado, tipos de evento y códigos de error tras la migración; 0 cambios requeridos en
  el cliente.
- **SC-002**: El cliente ve el primer fragmento en menos de 2 segundos en el 95 % de las peticiones en local con el
  modelo ya cargado (igual que SC-001 de LAB-12).
- **SC-003**: Tras cerrar la conexión del cliente, la generación en el modelo se detiene en menos de 2 segundos y
  0 fragmentos adicionales se producen; la siguiente petición cumple SC-002.
- **SC-004**: 0 operaciones bloqueantes detectadas en el bucle de eventos a lo largo de toda la suite de pruebas, y la
  prueba negativa demuestra que una violación hace fallar la suite el 100 % de las veces.
- **SC-005**: Con 50 conexiones de streaming simultáneas en local, el número de hilos que atienden peticiones no
  crece con las conexiones (permanece acotado) y todos los flujos contienen únicamente su propia respuesta.
- **SC-006**: Cobertura de líneas del backend >= 80 % tras migrar las pruebas, sin escenarios de LAB-12 eliminados.
- **SC-007**: El 100 % de las métricas base de LAB-102 y las del chat de LAB-12 siguen presentes en
  `/actuator/prometheus`, y los dashboards existentes muestran datos sin edición.
- **SC-008**: Un desarrollador nuevo entiende, solo leyendo el ADR, por qué la web es reactiva y la persistencia es
  JDBC, y qué hacer al añadir un acceso a base de datos, en menos de 10 minutos.

## Assumptions

- La historia pertenece a la fase F0 (Fundaciones) y a la épica LAB-1; depende de LAB-12 (endpoint de chat), que ya
  está implementado en la rama base, y se apoya en LAB-102 (métricas base). Va antes de LAB-14 y LAB-105.
- Es un refactor de arquitectura: no cambia el comportamiento observable para el cliente; por eso el tipo de cambio es
  `refactor` y no genera versión nueva por sí mismo.
- «Primer token en menos de 2 s» se mide como en LAB-12: local, modelo ya cargado y mensaje corto.
- La pila reactiva concreta (WebFlux sobre Netty, `WebClient`, Reactor, BlockHound) viene impuesta por la propia
  historia y por ADR-0004 (Spring Boot 4 y Spring AI 2.0); el diseño detallado se resuelve en `/speckit-plan`.
- Hoy el backend **no tiene acceso JDBC** (sin `DataSource`, Flyway, pgvector ni registro de eventos de Modulith); la
  memoria de conversación, el almacén vectorial y el registro de eventos llegan en historias posteriores y heredarán la
  regla del ADR. Esta historia no migra ningún acceso a datos: deja establecido y probado el patrón de aislamiento.
- No se usa R2DBC ni hilos virtuales; ambas opciones se documentan como descartadas en el ADR.
- Sin autenticación en esta fase; los endpoints de actuator conservan su exposición actual.
- El numeral del ADR será el siguiente libre del registro (después de ADR-0010) y el texto del ADR se redacta en
  español como los demás.
- Cobertura mínima del 80 % en el módulo modificado; en CI las llamadas al modelo se simulan (principio IV), y solo la
  verificación local usa Ollama real.
