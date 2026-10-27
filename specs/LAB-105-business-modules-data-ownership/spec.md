# Feature Specification: Módulos por negocio con dueño de datos y reglas verificadas

**Feature Branch**: `refactor/LAB-105-business-modules-data-ownership`

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "LAB-105 (F0-22) Módulos por negocio con dueño de datos y reglas verificadas. Como
desarrollador, quiero el monolito dividido por contextos de negocio y que cada módulo sea dueño de sus datos, para que
extraer un servicio en F5 a su propio repo sea mover un módulo y no desenredar tablas. Criterios: módulos `chat`,
`retrieval`, `orders`, `catalog` y `tickets`; el módulo técnico `tools` desaparece y cada módulo publica sus
herramientas `@Tool` en su API, que `chat` solo compone; un esquema de Postgres por módulo, con migraciones Flyway en
`db/migration/<modulo>`; sin claves foráneas ni joins entre esquemas (las referencias son por id); cada módulo separado
en `domain`, `application` e `infrastructure`; un test de ArchUnit impide que `domain` importe Spring AI, Kafka, JPA o
Reactor (los tipos `Mono` y `Flux` viven en `application` e `infrastructure`); `ApplicationModules.verify()` en verde y
documentación de módulos generada con el Documenter de Modulith en `docs/`; ADR «Límites de contexto y estrategia de
repositorios»: un dueño por dato; un repo por servicio desde F5 (el monolito vive en `shoplab-helpdesk` hasta F6-01);
`shoplab-contracts`, `shoplab-ci` y `shoplab-infra` como repos de soporte; qué se comparte (paquetes de contratos) y
qué no (librerías con dominio). Depende de LAB-11 y LAB-111; va antes de LAB-17 (carga de Olist) y LAB-19 (esquema
kb)."

## Clarifications

### Session 2026-10-27

- Q: ¿Debe el backend poder arrancar sin conexión a Postgres, o exigirla siempre? → A: La base de datos es
  obligatoria siempre: el arranque falla si no hay Postgres (dev ya dispone de Postgres, ver Assumptions).
- Q: ¿Cómo se llama el esquema del módulo `retrieval`? → A: `retrieval`, igual que el módulo; LAB-19 ajusta su texto de
  «esquema kb» a «esquema retrieval».

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Mapa de módulos por contexto de negocio (Priority: P1)

Como desarrollador, quiero que el backend esté dividido en los módulos `chat`, `retrieval`, `orders`, `catalog` y
`tickets` —sin el módulo técnico `tools`—, y que cada uno publique en su API las herramientas del agente que le
corresponden mientras `chat` solo las compone, para saber siempre a qué contexto pertenece una pieza de código y para
que ningún módulo dependa de un cajón de sastre técnico.

**Why this priority**: Es la base de la historia: los esquemas de datos, las reglas de capas y el ADR se apoyan en
este mapa. LAB-17 (pedidos y catálogo de Olist) y LAB-19 (base de conocimiento) necesitan saber en qué módulo aterrizan.

**Independent Test**: Listar los módulos detectados por Modulith y comprobar que son exactamente los esperados (más el
módulo compartido de errores); verificar que ninguna clase del módulo `chat` define herramientas de otro contexto y que
no existe ningún módulo `tools`.

**Acceptance Scenarios**:

1. **Given** el código del backend, **When** se detectan los módulos de la aplicación, **Then** existen `chat`,
   `retrieval`, `orders`, `catalog`, `tickets` y el módulo compartido de errores, y no existe `tools`.
2. **Given** un módulo de negocio que ofrece capacidades al agente, **When** se revisa su API pública, **Then** las
   herramientas del agente que le corresponden se publican allí y solo allí.
3. **Given** el módulo `chat`, **When** se revisa cómo obtiene las herramientas, **Then** las recibe de las APIs de los
   demás módulos y se limita a componerlas, sin definir lógica de herramientas de otro contexto.
4. **Given** el chat de LAB-12/LAB-111 en funcionamiento, **When** se reorganizan los módulos, **Then** `POST /api/chat`
   conserva su contrato y comportamiento sin cambios.

---

### User Story 2 - Cada módulo es dueño de sus datos (Priority: P1)

Como desarrollador, quiero un esquema de Postgres por módulo, con sus migraciones en una carpeta propia y sin claves
foráneas ni joins entre esquemas, para que extraer un módulo a su propio servicio en F5 sea mover código y su esquema, y
no desenredar tablas compartidas.

**Why this priority**: Es el objetivo de negocio de la historia («un dueño por dato»). Una vez que LAB-17 y LAB-19
creen tablas, deshacer un acoplamiento por base de datos es caro; fijar la regla antes lo evita.

**Independent Test**: Arrancar el backend contra una base de datos vacía, comprobar que existe un esquema por módulo
con datos y migraciones propias, e inspeccionar el catálogo de la base para verificar que ninguna tabla referencia a
otro esquema con una clave foránea.

**Acceptance Scenarios**:

1. **Given** una base de datos vacía, **When** arranca el backend, **Then** se aplican las migraciones de cada módulo
   desde su propia carpeta y queda un esquema por cada módulo que posee datos, nombrado con el del módulo.
2. **Given** los esquemas creados, **When** se inspeccionan las restricciones, **Then** no existe ninguna clave foránea
   que cruce de un esquema a otro.
3. **Given** el código y las migraciones, **When** se revisan consultas y definiciones, **Then** ningún módulo une
   (join) tablas de otro esquema; las referencias entre módulos se guardan como identificadores y se resuelven por la
   API del módulo dueño.
4. **Given** una migración nueva de un módulo, **When** se agrega, **Then** vive en la carpeta de su módulo y solo crea
   o modifica objetos de su propio esquema.
5. **Given** una regla de aislamiento incumplida de forma deliberada (una clave foránea entre esquemas), **When** corre
   la verificación, **Then** falla y señala la violación.

---

### User Story 3 - Capas verificadas automáticamente (Priority: P1)

Como desarrollador, quiero que cada módulo esté separado en `domain`, `application` e `infrastructure`, y que una
prueba automática impida que `domain` importe Spring AI, Kafka, JPA o Reactor, para que la pureza del dominio no
dependa de la disciplina de cada revisión.

**Why this priority**: Sin una regla ejecutable, la separación en capas se erosiona con el primer atajo. La
constitución exige dominio puro (principio II); aquí se vuelve verificable en CI.

**Independent Test**: Ejecutar la suite de arquitectura y comprobar que pasa con el código actual; introducir una
dependencia prohibida en una clase de `domain` (de prueba) y comprobar que la suite falla indicando la clase y la
dependencia.

**Acceptance Scenarios**:

1. **Given** cada módulo de negocio, **When** se revisa su estructura, **Then** su código está organizado en `domain`,
   `application` e `infrastructure`.
2. **Given** una clase en `domain`, **When** importa Spring AI, Kafka, JPA o Reactor, **Then** la prueba de arquitectura
   falla con un mensaje que identifica la clase y el paquete prohibido.
3. **Given** los tipos reactivos `Mono` y `Flux`, **When** se usan en `application` o `infrastructure`, **Then** la
   prueba no los reporta; solo se prohíben en `domain`.
4. **Given** las reglas de arquitectura ya existentes (LAB-111: sin JDBC en `application`, sin planificadores en
   `domain`), **When** corre la suite completa, **Then** siguen pasando junto a las nuevas.
5. **Given** un módulo vacío que aún no tiene lógica, **When** corre la prueba, **Then** no produce falsos fallos.

---

### User Story 4 - Límites de módulo verificados y documentados (Priority: P2)

Como desarrollador, quiero que la verificación de módulos de Modulith pase en verde y que su documentación se genere
automáticamente en `docs/`, para ver las dependencias reales entre contextos y detectar a tiempo un acoplamiento
indebido.

**Why this priority**: La verificación ya existe como mínimo desde LAB-11; aquí se extiende al nuevo mapa y se
publica el resultado. Aporta visibilidad, pero depende de que los módulos de las historias anteriores existan.

**Independent Test**: Ejecutar la verificación de módulos y la generación de documentación; abrir los artefactos
generados en `docs/` y comprobar que muestran los cinco módulos de negocio y sus dependencias permitidas.

**Acceptance Scenarios**:

1. **Given** el nuevo mapa de módulos, **When** corre la verificación de módulos, **Then** termina en verde, sin ciclos
   ni accesos a elementos internos de otro módulo.
2. **Given** la verificación, **When** corre la generación de documentación, **Then** quedan en `docs/` el diagrama
   general y la ficha de cada módulo, generados desde el código y no escritos a mano.
3. **Given** un acceso indebido a un elemento interno de otro módulo, **When** corre la verificación, **Then** falla.
4. **Given** el flujo de CI, **When** se ejecutan las pruebas, **Then** la verificación y la generación forman parte
   del mismo ciclo y un cambio de módulos sin documentación regenerada se detecta en revisión.

---

### User Story 5 - Decisión de límites y repositorios registrada (Priority: P2)

Como miembro del equipo, quiero un ADR «Límites de contexto y estrategia de repositorios» que fije un dueño por dato,
un repositorio por servicio desde F5 y qué se comparte entre servicios y qué no, para que la decisión no se reabra sin
contexto cuando llegue la extracción.

**Why this priority**: La constitución exige un ADR para decisiones de extracción de módulos (principio VII). Es
documentación, pero cierra la historia y orienta F5/F6.

**Independent Test**: Revisar el ADR publicado: sigue la plantilla, figura en el índice del registro y cubre cada punto
pedido.

**Acceptance Scenarios**:

1. **Given** el registro de ADR, **When** se abre el nuevo ADR, **Then** sigue la plantilla del proyecto y figura en el
   índice.
2. **Given** el ADR, **When** se lee, **Then** establece que cada dato tiene un único módulo dueño y que los demás lo
   consultan por su API o por eventos, nunca por tablas.
3. **Given** el ADR, **When** se lee, **Then** declara un repositorio por servicio desde F5, que el monolito vive en
   `shoplab-helpdesk` hasta F6-01 y que `shoplab-contracts`, `shoplab-ci` y `shoplab-infra` son repositorios de
   soporte.
4. **Given** el ADR, **When** se lee, **Then** distingue lo que se comparte (paquetes de contratos) de lo que no
   (librerías con dominio) y justifica la diferencia.

---

### Edge Cases

- **Módulos aún sin datos ni lógica** (`orders`, `catalog`): deben existir con su estructura y su esquema reservado, sin
  tablas inventadas; las historias posteriores (LAB-17, LAB-19) las llenan.
- **Módulo con esquema pero sin migraciones aplicables**: el arranque no falla; el esquema se crea de forma
  reproducible.
- **Referencias entre módulos**: un identificador guardado sin clave foránea puede quedar huérfano; la integridad se
  resuelve en la API del dueño (consulta o evento), no en la base de datos, y debe quedar anotado en el ADR.
- **Datos compartidos tentadores** (p. ej., un cliente que aparece en pedidos y tickets): se modelan como identificador
  más una vista propia del módulo, no como tabla compartida.
- **Arranque y pruebas con base de datos**: la detección de bloqueos de LAB-111 no debe dar falsos positivos por las
  migraciones al arrancar; la persistencia JDBC sigue aislada en `infrastructure`.
- **Postgres no disponible al arrancar**: el backend no arranca y el error nombra la causa (conexión o módulo cuya
  migración falló); no queda a medias un arranque parcial. Las pruebas que levantan la aplicación disponen de una
  base de datos real y desechable.
- **Módulo compartido de errores** (`shared`): no es un contexto de negocio ni posee datos; las reglas de capas no deben
  obligarle a una estructura artificial.
- **Regresión del chat**: ni el contrato de `POST /api/chat`, ni las métricas, ni los logs con correlación cambian por
  la reorganización.
- **Primera migración de cada módulo**: hoy no existe ninguna migración en el repositorio; cada módulo parte de su
  migración de base (`V1`) y las historias posteriores (LAB-17, LAB-19) añaden las siguientes sin renumerar.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El backend MUST organizarse en los módulos de negocio `chat`, `retrieval`, `orders`, `catalog` y
  `tickets`, más el módulo compartido de errores; el módulo técnico `tools` MUST NOT existir.
- **FR-002**: Cada módulo que ofrece herramientas del agente MUST publicarlas en su API pública; el módulo `chat` MUST
  limitarse a componerlas y MUST NOT definir lógica de herramientas de otros contextos.
- **FR-003**: Cada módulo que posee datos MUST tener su propio esquema de Postgres, nombrado con el del módulo, y sus
  migraciones en una carpeta propia por módulo, aplicadas automáticamente al arrancar. La conexión a Postgres es
  obligatoria: si la base de datos no está disponible o una migración falla, el arranque MUST fallar con un error que
  indique el módulo afectado; no existe un modo de arranque sin base de datos.
- **FR-004**: Ninguna estructura de base de datos MUST contener claves foráneas entre esquemas, ni ninguna consulta
  del código MUST unir tablas de más de un esquema; las referencias entre módulos MUST guardarse como identificadores y
  resolverse mediante la API del módulo dueño.
- **FR-005**: Una verificación automática MUST detectar claves foráneas o accesos entre esquemas de distintos módulos y
  fallar en tal caso.
- **FR-006**: Cada módulo de negocio MUST separarse en `domain`, `application` e `infrastructure`, conforme al
  principio II de la constitución.
- **FR-007**: Una prueba de arquitectura MUST impedir que cualquier clase de `domain` importe Spring AI, Kafka, JPA o
  Reactor, y MUST producir un mensaje que identifique la clase y la dependencia prohibida; `Mono` y `Flux` MUST poder
  usarse en `application` e `infrastructure`.
- **FR-008**: Las reglas de arquitectura existentes de LAB-111 MUST conservarse y seguir pasando junto a las nuevas.
- **FR-009**: La verificación de módulos de Modulith MUST terminar en verde y la prueba de módulos esperados MUST
  actualizarse al nuevo mapa.
- **FR-010**: La documentación de módulos (diagrama general y ficha por módulo) MUST generarse con el Documenter de
  Modulith en `docs/` a partir del código, sin edición manual.
- **FR-011**: MUST existir un ADR «Límites de contexto y estrategia de repositorios» en `docs/adr/`, con la plantilla
  del proyecto y entrada en el índice, que cubra: un dueño por dato; un repositorio por servicio desde F5; el monolito
  en `shoplab-helpdesk` hasta F6-01; `shoplab-contracts`, `shoplab-ci` y `shoplab-infra` como repositorios de soporte;
  lo que se comparte (paquetes de contratos) y lo que no (librerías con dominio).
- **FR-012**: La reorganización MUST NOT cambiar el contrato externo de `POST /api/chat`, sus métricas, su registro de
  logs ni la propagación del identificador de correlación (LAB-12, LAB-102, LAB-111).
- **FR-013**: Los errores MUST seguir lanzándose como excepciones tipadas con códigos en `ResponseCodeEnum` y
  traducirse a HTTP solo en el manejador global (principio III); los módulos nuevos MUST reutilizar el mecanismo. Los
  fallos de base de datos al arrancar (sin conexión, migración fallida) también MUST usar excepciones tipadas con
  código propio en `ResponseCodeEnum`.
- **FR-014**: La persistencia JDBC MUST seguir aislada en `infrastructure` y ejecutada fuera del bucle de eventos; la
  introducción de la conexión a base de datos y de las migraciones MUST NOT provocar falsos positivos en la detección
  de bloqueos.
- **FR-015**: Los nombres de módulos, esquemas y carpetas de migración MUST seguir una convención única y documentada,
  de modo que un esquema y su carpeta de migraciones se deduzcan del nombre del módulo, sin excepciones: los esquemas
  son `chat`, `retrieval`, `orders`, `catalog` y `tickets`.
- **FR-016**: Quedan fuera de alcance: tablas y lógica de negocio de pedidos, catálogo, tickets o conocimiento (LAB-17
  y LAB-19), eventos entre módulos con broker, la extracción real a repositorios o servicios (F5/F6), la creación de
  los repositorios de soporte y cambios en la interfaz Angular.

### Key Entities *(include if feature involves data)*

- **Módulo de negocio**: unidad con límites claros que agrupa un contexto (chat, recuperación, pedidos, catálogo,
  tickets), con su API pública, sus capas internas y sus datos propios.
- **Esquema de módulo**: espacio de datos exclusivo de un módulo en la base de datos, con su carpeta de migraciones;
  nadie más lo lee ni escribe directamente.
- **Referencia por identificador**: valor que un módulo guarda para señalar un dato de otro, sin restricción de base de
  datos; se resuelve por la API del dueño.
- **Herramienta del agente**: capacidad que un módulo ofrece al agente a través de su API; `chat` las compone.
- **Regla de arquitectura**: restricción ejecutable (capas, dependencias prohibidas, aislamiento de esquemas) que
  falla la suite si se viola.
- **Registro de decisión (ADR)**: documento que fija la política de límites de contexto y de repositorios.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los módulos de negocio acordados existe y el módulo `tools` desaparece; la verificación de
  módulos pasa con 0 ciclos y 0 accesos a elementos internos de otro módulo.
- **SC-002**: Sobre una base de datos vacía, el arranque deja un esquema por cada módulo con datos, con sus migraciones
  aplicadas, en una sola ejecución y sin pasos manuales.
- **SC-003**: 0 claves foráneas y 0 consultas que crucen esquemas; una violación deliberada hace fallar la
  verificación el 100 % de las veces.
- **SC-004**: 0 clases de `domain` con importaciones de Spring AI, Kafka, JPA o Reactor; una violación deliberada hace
  fallar la suite el 100 % de las veces.
- **SC-005**: Un desarrollador identifica en menos de 5 minutos, solo con la documentación generada, a qué módulo
  pertenece una capacidad y de qué otros módulos depende.
- **SC-006**: El contrato de `POST /api/chat` no cambia: el 100 % de los escenarios de LAB-12 y LAB-111 pasa sin
  modificar el cliente, y la cobertura de líneas se mantiene >= 80 %.
- **SC-007**: Un desarrollador nuevo entiende, solo leyendo el ADR, quién es dueño de cada dato, cómo se organizarán
  los repositorios desde F5 y qué se puede compartir, en menos de 10 minutos.
- **SC-008**: Las historias LAB-17 y LAB-19 pueden empezar sin crear módulos ni esquemas nuevos ni mover estructura
  existente.

## Assumptions

- La historia pertenece a la fase F0 (Fundaciones) y a la épica LAB-1; depende de LAB-11 (estructura de monolito
  modular) y LAB-111 (backend reactivo), y va antes de LAB-17 y LAB-19. Sprint 8.
- Es un refactor de arquitectura sin cambio de comportamiento observable; por eso el tipo de cambio es `refactor` y no
  genera versión nueva por sí mismo (como LAB-111).
- Hoy existen los módulos `chat`, `retrieval`, `tickets`, `tools` y `shared`; `retrieval`, `tickets` y `tools` solo
  tienen su declaración de módulo, y `orders` y `catalog` son nuevos. Como `tools` está vacío, su desaparición no migra
  herramientas existentes; las herramientas futuras nacerán en el módulo dueño.
- El backend todavía no tiene conexión a base de datos ni migraciones (supuesto de LAB-111); esta historia introduce lo
  mínimo para que cada módulo pueda tener su esquema y su carpeta de migraciones, con una migración de base que cree el
  esquema vacío. La Postgres local viene de la infraestructura de LAB-10.
- El ambiente dev ya tiene Postgres (`k8s/base/postgres` en `shoplab-infra`) y el backend ya recibe
  `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD` (`k8s/base/backend`), por lo que
  la base de datos obligatoria no debería requerir cambios de infraestructura. Si la implementación revela alguno (en
  `infra/` de este repositorio o en `shoplab-infra`), se hace como parte de esta historia y no se difiere.
- `shared` (errores y utilidades transversales, ADR-0010) se mantiene como módulo técnico sin datos; su existencia no
  contradice «sin módulos técnicos», porque no aloja lógica de herramientas ni de negocio.
- «Esquema por módulo» se aplica a los módulos que poseen datos hoy o a corto plazo (`chat`, `retrieval`, `orders`,
  `catalog`, `tickets`); si alguno aún no tiene tablas, solo se crea el esquema.
- La generación de documentación con Modulith produce artefactos reproducibles; si se versionan en el repositorio o se
  publican desde CI se decide en `/speckit-plan`.
- Las tecnologías nombradas (ArchUnit, Flyway, Modulith Documenter, Postgres) vienen impuestas por la propia historia y
  por ADR-0001/0002/0004; el diseño detallado se resuelve en `/speckit-plan`.
- El ADR llevará el siguiente número libre del registro (después de ADR-0014), redactado en español como los demás.
- Sin autenticación ni cambios de frontend en esta fase; en CI las llamadas al modelo se simulan (principio IV).
