# Feature Specification: Infraestructura local con docker compose

**Feature Branch**: `chore/LAB-10-local-infra-compose`

**Created**: 2026-10-23

**Status**: Draft

**Input**: User description: "LAB-10 (F0-02) Como desarrollador, quiero levantar la infraestructura local con docker compose. Criterios: Postgres 16 + pgvector; Ollama con un modelo de chat y uno de embeddings descargados; healthchecks en verde; perfiles de compose (core, llmops, observability, kafka) para levantar solo lo necesario; README con requisitos de hardware y modelos recomendados según RAM y GPU."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Levantar la base de datos y el modelo de lenguaje con un solo comando (Priority: P1)

Como desarrollador, quiero ejecutar un único comando que levante la base de datos relacional con
búsqueda vectorial y el servidor de modelos locales, con un modelo de chat y uno de embeddings ya
descargados, para empezar a desarrollar sin instalar nada a mano.

**Why this priority**: Es el mínimo viable del entorno local: sin base de datos ni modelos ninguna
historia posterior de F0 en adelante (backend, ingesta, RAG) puede ejecutarse.

**Independent Test**: En una máquina limpia con los prerrequisitos instalados, ejecutar el comando
de arranque del perfil `core` y comprobar que la base de datos acepta conexiones, que la extensión
vectorial está disponible y que el servidor de modelos responde y lista un modelo de chat y uno de
embeddings.

**Acceptance Scenarios**:

1. **Given** una máquina limpia con los prerrequisitos, **When** el desarrollador ejecuta el
   comando de arranque del perfil `core`, **Then** se levantan la base de datos y el servidor de
   modelos sin pasos manuales adicionales.
2. **Given** el perfil `core` levantado, **When** el desarrollador consulta la base de datos,
   **Then** está disponible la versión 16 con la extensión de búsqueda vectorial habilitada.
3. **Given** el perfil `core` levantado por primera vez, **When** termina la preparación de modelos,
   **Then** el servidor de modelos tiene descargados un modelo de chat y un modelo de embeddings
   listos para usar.
4. **Given** el perfil `core` ya levantado y detenido antes, **When** se vuelve a levantar,
   **Then** los datos de la base de datos y los modelos descargados se conservan y no se vuelven a
   descargar.

---

### User Story 2 - Saber de un vistazo si cada servicio está sano (Priority: P1)

Como desarrollador, quiero que cada servicio informe su estado de salud para saber cuándo el entorno
está realmente listo y distinguir un servicio caído de uno que aún está arrancando.

**Why this priority**: Es un criterio de aceptación explícito y evita fallos confusos en las
historias que dependen de la infraestructura.

**Independent Test**: Tras levantar el perfil `core`, consultar el estado de los servicios y
verificar que todos pasan a "saludable" dentro del tiempo esperado; detener uno y verificar que se
reporta como no saludable.

**Acceptance Scenarios**:

1. **Given** el perfil `core` recién levantado, **When** el desarrollador consulta el estado de los
   servicios, **Then** todos pasan a saludable sin intervención manual.
2. **Given** un servicio que aún no está listo, **When** otro servicio depende de él, **Then** el
   dependiente espera a que esté saludable antes de arrancar.
3. **Given** un servicio caído o que no responde, **When** se consulta el estado, **Then** se
   muestra como no saludable.

---

### User Story 3 - Levantar solo lo necesario mediante perfiles (Priority: P2)

Como desarrollador, quiero perfiles (`core`, `llmops`, `observability`, `kafka`) para activar solo
los grupos de servicios que necesito y no consumir recursos de más en mi máquina.

**Why this priority**: Reduce el consumo de memoria y el tiempo de arranque, y permite crecer el
entorno por fases sin romper lo ya existente. No bloquea el uso básico (P1).

**Independent Test**: Levantar cada perfil por separado y en combinación, y comprobar que solo se
inician los servicios que le corresponden y que `core` funciona de forma autónoma.

**Acceptance Scenarios**:

1. **Given** el repositorio recién clonado, **When** se levanta únicamente el perfil `core`,
   **Then** no se inicia ningún servicio de los perfiles `llmops`, `observability` ni `kafka`.
2. **Given** el perfil `core` activo, **When** se añade otro perfil, **Then** se inician solo los
   servicios de ese perfil y los de `core` no se reinician ni se duplican.
3. **Given** un perfil cuyos servicios aún no se han incorporado (llegan en fases posteriores),
   **When** se activa, **Then** el comando no falla y el README indica qué historia o fase entrega
   su contenido.
4. **Given** el comando de arranque sin perfil explícito, **When** se ejecuta, **Then** el
   comportamiento está documentado y el desarrollador sabe qué servicios obtiene.

---

### User Story 4 - Elegir hardware y modelos según mi máquina (Priority: P2)

Como desarrollador, quiero un README con los requisitos de hardware y los modelos recomendados según
mi RAM y mi GPU para elegir una configuración que mi equipo pueda ejecutar.

**Why this priority**: Es un criterio de aceptación explícito; sin esta guía un equipo modesto puede
bloquearse al intentar cargar un modelo demasiado grande.

**Independent Test**: Una persona con una máquina concreta (por ejemplo, 16 GB de RAM sin GPU) lee
el README y determina, sin ayuda, qué modelos usar y qué perfiles puede levantar.

**Acceptance Scenarios**:

1. **Given** el README, **When** el desarrollador busca los requisitos, **Then** encuentra los
   prerrequisitos de software, la RAM, el espacio en disco y el uso de GPU por perfil.
2. **Given** una RAM y una GPU concretas (o ausencia de GPU), **When** consulta la tabla de
   recomendaciones, **Then** obtiene un modelo de chat y uno de embeddings recomendados para esa
   combinación.
3. **Given** el desarrollador quiere usar otro modelo, **When** lee la guía, **Then** sabe cómo
   cambiar el modelo configurado sin modificar los archivos de definición del entorno.

---

### Edge Cases

- ¿Qué pasa si el puerto de un servicio ya está ocupado en la máquina? Debe poder cambiarse por
  configuración local, y el README debe indicar cómo.
- ¿Qué pasa si no hay GPU? El servidor de modelos debe funcionar solo con CPU, y el README debe
  indicar el impacto en el rendimiento y los modelos aconsejados.
- ¿Qué pasa si la descarga de modelos falla o se interrumpe (sin red, disco lleno)? El servicio
  debe reportar el fallo de forma clara y permitir reintentar sin dejar datos corruptos.
- ¿Qué pasa si la máquina no tiene RAM suficiente para el modelo configurado? El README debe
  advertirlo y recomendar un modelo menor.
- ¿Qué pasa con las credenciales de la base de datos? No se versionan secretos reales: se usan
  valores de ejemplo en un archivo de plantilla y la configuración local queda excluida del control
  de versiones.
- ¿Qué pasa con una instalación previa con datos antiguos? El README debe indicar cómo reiniciar el
  entorno desde cero.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio MUST permitir levantar la infraestructura local con un único comando de
  `docker compose` desde la carpeta `infra/`.
- **FR-002**: El perfil `core` MUST incluir una base de datos PostgreSQL 16 con la extensión
  pgvector habilitada y lista para usar.
- **FR-003**: El perfil `core` MUST incluir un servidor Ollama con al menos un modelo de chat y un
  modelo de embeddings descargados automáticamente en el primer arranque.
- **FR-004**: Los datos de la base de datos y los modelos descargados MUST persistir entre
  reinicios del entorno.
- **FR-005**: Cada servicio MUST definir una comprobación de salud, y todos los servicios del perfil
  `core` MUST llegar al estado saludable sin intervención manual.
- **FR-006**: Un servicio MUST esperar a que sus dependencias estén saludables antes de arrancar.
- **FR-007**: La definición MUST ofrecer los perfiles `core`, `llmops`, `observability` y `kafka`;
  `core` MUST funcionar de forma autónoma y los demás MUST poder combinarse con él.
- **FR-008**: Activar un perfil sin servicios incorporados todavía MUST NOT provocar errores y MUST
  quedar documentado qué historia o fase lo completa.
- **FR-009**: Los modelos de chat y de embeddings MUST ser configurables por variables, con
  valores por defecto, sin editar el archivo de definición del entorno.
- **FR-010**: Puertos, usuarios y contraseñas MUST ser configurables por variables con valores de
  ejemplo en una plantilla versionada; la configuración local MUST estar excluida del control de
  versiones y no se versionan secretos reales.
- **FR-011**: El README MUST documentar los requisitos de hardware (RAM, disco, GPU opcional) y los
  prerrequisitos de software para cada perfil.
- **FR-012**: El README MUST incluir una tabla de modelos recomendados de chat y embeddings según
  RAM y GPU, incluyendo la opción solo CPU.
- **FR-013**: El README MUST documentar cómo levantar, detener, ver el estado de salud, cambiar de
  modelo y reiniciar el entorno desde cero.
- **FR-014**: El README del repositorio MUST dejar de marcar el arranque como pendiente y apuntar
  al comando real entregado por esta historia.

### Key Entities

- **Perfil**: agrupación de servicios que se activan juntos (`core`, `llmops`, `observability`,
  `kafka`); define qué se levanta y cuánto consume.
- **Servicio de infraestructura**: componente del entorno local (base de datos, servidor de modelos)
  con estado de salud, puerto y datos persistentes.
- **Modelo local**: modelo de chat o de embeddings descargado en el servidor de modelos; tiene
  nombre, rol (chat/embeddings) y requisitos de memoria.
- **Perfil de hardware**: combinación de RAM y GPU (o ausencia de ella) asociada a modelos
  recomendados.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un desarrollador con los prerrequisitos obtiene el perfil `core` completamente
  saludable con un solo comando y sin pasos manuales, el 100 % de las veces en una máquina limpia.
- **SC-002**: Tras el primer arranque (con modelos ya descargados), el perfil `core` está saludable
  en menos de 2 minutos.
- **SC-003**: Reiniciar el entorno no pierde datos ni vuelve a descargar modelos en el 100 % de los
  casos.
- **SC-004**: Levantar solo `core` no inicia ningún servicio de los otros tres perfiles (0 servicios
  extra).
- **SC-005**: Una persona identifica en menos de 5 minutos, usando solo el README, qué modelos y
  perfiles puede ejecutar en su máquina.
- **SC-006**: Cero credenciales reales ni archivos de configuración local aparecen como cambios
  versionados tras un uso normal.

## Assumptions

- El desarrollador tiene instalado un motor de contenedores compatible con `docker compose`
  (versión mínima a documentar en el README).
- Esta historia entrega la infraestructura del perfil `core`; los servicios concretos de `llmops`,
  `observability` y `kafka` llegan en historias de fases posteriores, y aquí solo se reserva el
  perfil (sin errores al activarlo).
- Los modelos por defecto son de tamaño moderado, aptos para una máquina de desarrollo; los
  nombres exactos y la tabla por RAM/GPU se fijan en la planificación y se verifican contra el
  catálogo vigente de modelos.
- La GPU es opcional: el entorno funciona solo con CPU, con menor rendimiento.
- La carpeta `infra/` contiene la definición del entorno, en línea con la restricción de la
  constitución de que el arranque local sea un único `docker compose -f infra/docker-compose.yml
  up`.
- Esta historia no incluye el backend, la ingesta de datos, esquemas de base de datos ni
  migraciones; solo deja la infraestructura lista para ellos.
- El perfil por defecto al no indicar ninguno es `core` (decisión por defecto razonable; se
  confirma en la planificación).
- Los puertos estándar de cada servicio se usan por defecto y son configurables.
