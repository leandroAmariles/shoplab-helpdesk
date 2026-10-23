# Feature Specification: Backend modular con Spring Modulith y Spring AI

**Feature Branch**: `feature/LAB-11-backend-spring-modulith`

**Created**: 2026-10-23

**Status**: Draft

**Input**: User description: "LAB-11 (F0-03) Como desarrollador, quiero un backend Spring Boot 4 / Java 21+ con Spring Modulith y Spring AI 2.0. Criterios: proyecto Maven con Spring Boot 4.x y el BOM de Spring AI 2.0; módulos chat, retrieval, tools, tickets vacíos; test de arquitectura de Modulith en verde; ADR que explica la elección de Spring Boot 4 + Spring AI 2.0 (GA junio 2026)."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Contar con un backend que compila y arranca (Priority: P1)

Como desarrollador, quiero un proyecto backend con la plataforma y las versiones acordadas para
poder construirlo y ejecutarlo desde el primer día y sobre él desarrollar las historias de chat,
recuperación, herramientas y tickets.

**Why this priority**: Es la base de todo el desarrollo del backend; sin un proyecto que compile y
arranque ninguna historia posterior (RAG, herramientas, tickets) puede avanzar.

**Independent Test**: En una máquina con los prerrequisitos del README, construir el proyecto con un
solo comando y arrancarlo; verificar que la construcción termina sin errores y que la aplicación
inicia y se detiene limpiamente.

**Acceptance Scenarios**:

1. **Given** el repositorio recién clonado y los prerrequisitos instalados, **When** el
   desarrollador ejecuta el comando de construcción del backend, **Then** el proyecto compila y sus
   pruebas pasan sin pasos manuales.
2. **Given** el proyecto construido, **When** el desarrollador lo arranca, **Then** la aplicación
   inicia sin errores y puede detenerse limpiamente.
3. **Given** el proyecto, **When** se inspecciona su definición de dependencias, **Then** declara
   Spring Boot 4.x, una versión de Java 21 o superior y gestiona las versiones de Spring AI 2.0
   mediante su BOM, sin fijar versiones sueltas de sus componentes.
4. **Given** el backend en la carpeta `backend/` del monorepo, **When** se revisa la estructura
   del repositorio, **Then** el proyecto vive ahí y respeta las convenciones definidas en LAB-9.

---

### User Story 2 - Módulos de negocio vacíos con fronteras explícitas (Priority: P1)

Como desarrollador, quiero los módulos `chat`, `retrieval`, `tools` y `tickets` ya creados (vacíos)
y con fronteras verificables, para que cada capacidad futura se construya en su sitio sin
acoplarse a las demás.

**Why this priority**: Es un criterio de aceptación explícito y materializa la decisión del ADR 0001
(monolito modular primero): las fronteras deben existir antes de que haya código que las rompa.

**Independent Test**: Listar los módulos reconocidos por la aplicación y comprobar que son
exactamente los cuatro esperados; añadir una dependencia no permitida entre ellos y comprobar que
la verificación la rechaza.

**Acceptance Scenarios**:

1. **Given** el proyecto, **When** se listan sus módulos de aplicación, **Then** existen
   exactamente `chat`, `retrieval`, `tools` y `tickets`.
2. **Given** los cuatro módulos, **When** se revisa su contenido, **Then** no contienen lógica de
   negocio, solo la declaración mínima que los hace reconocibles como módulos.
3. **Given** un módulo, **When** otro módulo intenta acceder a sus detalles internos, **Then** la
   verificación de arquitectura lo detecta y falla.

---

### User Story 3 - Test de arquitectura en verde que protege las fronteras (Priority: P1)

Como desarrollador, quiero un test automático que verifique la estructura modular, para detectar
de inmediato cualquier acoplamiento indebido entre módulos y que forme parte de la construcción.

**Why this priority**: Es un criterio de aceptación explícito; sin él las fronteras son solo una
convención que se erosiona con el tiempo.

**Independent Test**: Ejecutar la suite de pruebas y verificar que el test de arquitectura se
ejecuta y pasa; introducir deliberadamente una dependencia cíclica o hacia internos de otro módulo
y verificar que el test falla.

**Acceptance Scenarios**:

1. **Given** el proyecto con los cuatro módulos vacíos, **When** se ejecutan las pruebas, **Then**
   el test de arquitectura se ejecuta y pasa.
2. **Given** una dependencia cíclica entre dos módulos, **When** se ejecuta el test de
   arquitectura, **Then** falla indicando los módulos implicados.
3. **Given** el test de arquitectura, **When** se ejecuta el comando de construcción completo,
   **Then** forma parte de él, de modo que una violación bloquea la construcción.

---

### User Story 4 - Entender por qué se eligió esta plataforma (Priority: P2)

Como desarrollador (actual o futuro), quiero un ADR que explique la elección de Spring Boot 4 con
Spring AI 2.0, para conocer el contexto, las alternativas descartadas y las consecuencias antes de
cuestionar o extender la decisión.

**Why this priority**: Es un criterio de aceptación explícito; no bloquea el uso del backend (P1),
pero deja trazabilidad de una decisión que condiciona todo el proyecto.

**Independent Test**: Una persona nueva lee el ADR y puede explicar por qué se eligió esta
plataforma, qué alternativas se consideraron y qué riesgos se aceptaron.

**Acceptance Scenarios**:

1. **Given** la carpeta de ADR del repositorio, **When** se busca la decisión de plataforma del
   backend, **Then** existe un ADR numerado a continuación del último, con la plantilla del
   repositorio.
2. **Given** el ADR, **When** se lee, **Then** recoge contexto, decisión, alternativas
   consideradas, consecuencias y menciona que Spring AI 2.0 alcanzó GA en junio de 2026.
3. **Given** el ADR, **When** se contrasta con los ADR existentes, **Then** es coherente con el de
   monolito modular y no contradice las decisiones de base de datos ni de modelos locales.

---

### Edge Cases

- ¿Qué pasa si la máquina tiene una versión de Java inferior a la requerida? La construcción debe
  fallar con un mensaje claro que indique la versión mínima, y el README debe documentarla.
- ¿Qué pasa si no está levantada la infraestructura local (base de datos, modelos)? Las pruebas de
  esta historia no deben depender de ella; el backend vacío debe poder construirse y verificarse
  sin servicios externos.
- ¿Qué pasa si una versión del BOM de Spring AI no es compatible con la versión de Spring Boot?
  La construcción debe detectarlo, y el ADR debe registrar la combinación de versiones validada.
- ¿Qué pasa si se añade un quinto módulo o se renombra uno? El test de arquitectura debe seguir
  verificando fronteras; el conjunto esperado de módulos queda definido por esta spec.
- ¿Qué pasa con secretos o credenciales? Este backend aún no se conecta a servicios, por lo que no
  se versionan secretos ni configuración local.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio MUST contener, en `backend/`, un proyecto Maven que se construye y
  ejecuta con un único comando documentado.
- **FR-002**: El proyecto MUST usar Spring Boot 4.x y Java 21 o superior.
- **FR-003**: El proyecto MUST gestionar las versiones de Spring AI exclusivamente mediante el BOM
  de Spring AI 2.0, sin versiones fijas por componente.
- **FR-004**: El proyecto MUST adoptar Spring Modulith para estructurar y verificar los módulos.
- **FR-005**: El backend MUST exponer los módulos `chat`, `retrieval`, `tools` y `tickets`, vacíos
  de lógica de negocio.
- **FR-006**: MUST existir un test de arquitectura de Modulith que verifique la estructura modular
  (sin dependencias cíclicas ni accesos a internos de otro módulo) y que pase con la base entregada.
- **FR-007**: El test de arquitectura MUST ejecutarse como parte de la construcción estándar, de
  modo que una violación la haga fallar.
- **FR-008**: La construcción y las pruebas de esta historia MUST NOT requerir servicios externos
  (base de datos, servidor de modelos) levantados.
- **FR-009**: MUST existir un ADR en `docs/adr/`, creado con la plantilla vigente y numerado a
  continuación del último, que explique la elección de Spring Boot 4 + Spring AI 2.0 (GA junio
  2026) con contexto, decisión, alternativas y consecuencias.
- **FR-010**: El README del repositorio MUST documentar los prerrequisitos (versión mínima de Java y
  Maven) y el comando para construir y arrancar el backend, y MUST dejar de marcar el backend como
  pendiente.
- **FR-011**: Los archivos generados por la construcción MUST estar excluidos del control de
  versiones.
- **FR-012**: Los identificadores del código (paquetes, clases, métodos) MUST estar en inglés,
  conforme a las guías de desarrollo del proyecto.

### Key Entities

- **Módulo de aplicación**: unidad con frontera explícita (`chat`, `retrieval`, `tools`,
  `tickets`); declara qué expone y de qué depende; en esta historia no contiene lógica.
- **Verificación de arquitectura**: comprobación automática de que los módulos respetan sus
  fronteras y no tienen ciclos.
- **ADR de plataforma**: registro de la decisión de usar Spring Boot 4 y Spring AI 2.0, con su
  contexto, alternativas y consecuencias.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un desarrollador con los prerrequisitos construye el backend con un solo comando y
  sin pasos manuales, con éxito el 100 % de las veces en una máquina limpia.
- **SC-002**: La construcción completa, incluidas las pruebas, termina en menos de 3 minutos en una
  máquina de desarrollo estándar.
- **SC-003**: El test de arquitectura pasa con los cuatro módulos y falla en el 100 % de los casos
  en que se introduce una dependencia cíclica o un acceso a internos de otro módulo.
- **SC-004**: El backend arranca y se detiene sin errores y sin ningún servicio externo levantado.
- **SC-005**: Una persona nueva identifica en menos de 5 minutos, solo con el ADR, por qué se eligió
  la plataforma y qué alternativas se descartaron.
- **SC-006**: Cero archivos generados por la construcción aparecen como cambios versionados tras
  un uso normal.

## Assumptions

- El desarrollador tiene instalados Java 21 o superior y Maven; las versiones mínimas exactas se
  documentan en el README y se fijan en la planificación.
- Spring Boot 4.x y Spring AI 2.0 están disponibles como versiones estables (Spring AI 2.0 GA en
  junio de 2026); la combinación exacta de versiones se verifica contra los catálogos vigentes en
  la planificación y se registra en el ADR.
- Esta historia entrega solo el esqueleto: los módulos permanecen vacíos y la lógica de chat,
  recuperación, herramientas y tickets llega en historias de fases posteriores.
- La conexión a base de datos y a modelos locales (LAB-10) queda fuera de alcance; se integrará en
  historias posteriores.
- El proyecto vive en `backend/` conforme a la estructura de monorepo de LAB-9, y la numeración del
  ADR continúa la de `docs/adr/`.
