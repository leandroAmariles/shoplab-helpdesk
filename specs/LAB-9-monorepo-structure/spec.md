# Feature Specification: Monorepo con la estructura acordada

**Feature Branch**: `chore/LAB-9-monorepo-structure`

**Created**: 2026-10-23

**Status**: Draft

**Input**: User description: "LAB-9 (F0-01) Como desarrollador, quiero un monorepo con la estructura acordada para trabajar cada tecnología por separado. Criterios: carpetas frontend, backend, ingest, eval, infra, docs; README con cómo arrancar; .editorconfig y licencia."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Encontrar y trabajar cada tecnología por separado (Priority: P1)

Como desarrollador que clona el repositorio por primera vez, quiero ver una carpeta de primer nivel
por cada tecnología o responsabilidad (interfaz web, servidor, ingesta, evaluación, infraestructura
y documentación) para saber dónde trabajar sin tocar el resto.

**Why this priority**: Es la base de todas las fases; sin la estructura acordada ningún otro trabajo
de F0 tiene dónde ubicarse.

**Independent Test**: Clonar el repositorio en una máquina limpia y listar la raíz; deben existir
las seis carpetas acordadas y cada una debe versionarse aunque aún no tenga código.

**Acceptance Scenarios**:

1. **Given** un clon limpio del repositorio, **When** se lista la raíz, **Then** existen las
   carpetas `frontend`, `backend`, `ingest`, `eval`, `infra` y `docs`.
2. **Given** una carpeta aún sin contenido real, **When** se hace un clon nuevo, **Then** la
   carpeta sigue presente (no se pierde por estar vacía).
3. **Given** el README, **When** se compara con la estructura real, **Then** cada carpeta
   aparece descrita con su propósito y no hay carpetas documentadas que no existan.

---

### User Story 2 - Saber cómo arrancar el proyecto desde el README (Priority: P1)

Como desarrollador nuevo, quiero un README que me explique qué es el proyecto, cómo está
organizado y cómo arrancarlo, para ponerme en marcha sin preguntar a nadie.

**Why this priority**: Es un criterio de aceptación explícito y la primera puerta de entrada al
proyecto.

**Independent Test**: Una persona que no conoce el proyecto sigue solo el README y llega a
entender la estructura y el comando de arranque, o ve claramente qué parte del arranque llega
más adelante.

**Acceptance Scenarios**:

1. **Given** el README, **When** una persona lee la sección de arranque, **Then** encuentra los
   prerrequisitos y los pasos para levantar el entorno local, o una indicación explícita de qué
   historia entrega cada paso que aún no existe.
2. **Given** el README, **When** una persona busca dónde se registran las decisiones y el backlog,
   **Then** encuentra la ubicación de los ADR y del proyecto de Jira `LAB`, y la convención de ramas
   y commits.

---

### User Story 3 - Estilo de edición y licencia consistentes (Priority: P2)

Como contribuyente, quiero un archivo de configuración de editor y una licencia en la raíz para que
todos los editores formateen igual los archivos y quede claro bajo qué términos se puede usar el
código.

**Why this priority**: Evita ruido en los diffs y deja resuelto el aspecto legal desde el inicio,
pero no bloquea el trabajo técnico.

**Independent Test**: Verificar que existen el archivo de configuración de editor y el archivo de
licencia en la raíz, y que la licencia es reconocible por las herramientas de alojamiento del
repositorio.

**Acceptance Scenarios**:

1. **Given** la raíz del repositorio, **When** se abre en cualquier editor compatible, **Then** se
   aplican codificación UTF-8, fin de línea LF, indentación con espacios y salto de línea final.
2. **Given** la raíz del repositorio, **When** se busca el archivo de licencia, **Then** existe,
   contiene el texto completo de la licencia elegida con el titular y el año correctos.
3. **Given** el README, **When** se lee la sección final, **Then** menciona la licencia aplicable.

---

### Edge Cases

- ¿Qué pasa con una carpeta vacía? Debe conservar un marcador para que el sistema de control de
  versiones la mantenga; el marcador se retira cuando la carpeta reciba contenido real.
- ¿Qué pasa si el README describe un comando de arranque que depende de una historia posterior
  (F0-02)? Debe indicar explícitamente que está pendiente en lugar de mostrar un comando que falla.
- ¿Qué pasa con carpetas auxiliares que ya existen (`data`, `notebooks`)? Se conservan y se
  documentan; no se eliminan por no estar en la lista de seis.
- ¿Qué pasa con los archivos generados o locales (datos descargados, telemetría de agentes)? Quedan
  excluidos del control de versiones.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio MUST contener en su raíz las carpetas `frontend`, `backend`,
  `ingest`, `eval`, `infra` y `docs`.
- **FR-002**: Cada carpeta acordada MUST estar versionada aunque no tenga contenido todavía.
- **FR-003**: El README MUST describir el propósito del proyecto y una tabla de carpetas que
  coincida con la estructura real, incluidas `data` y `notebooks`.
- **FR-004**: El README MUST incluir una sección de arranque con prerrequisitos y pasos para el
  entorno local, marcando explícitamente los pasos que entrega una historia posterior.
- **FR-005**: El README MUST indicar la convención de ramas y commits (clave `LAB-<n>`) y dónde
  vive el backlog.
- **FR-006**: La raíz MUST contener un `.editorconfig` que fije codificación, fin de línea,
  indentación y salto de línea final para todos los tipos de archivo del repositorio.
- **FR-007**: La raíz MUST contener un archivo de licencia con el texto completo de la
  licencia MIT, a nombre de Leandro Amariles y año 2026.
- **FR-008**: El README MUST mencionar la licencia aplicable.
- **FR-009**: Los archivos locales o generados (datos descargados, configuración local de
  herramientas, telemetría) MUST estar excluidos del control de versiones.

### Key Entities

- **Carpeta de componente**: unidad de primer nivel asociada a una tecnología o responsabilidad
  (frontend, backend, ingest, eval, infra, docs); tiene un propósito documentado.
- **Documento de entrada (README)**: describe propósito, estructura, arranque, convenciones y
  licencia.
- **Licencia**: términos de uso del código, con titular y año.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un clon limpio contiene las 6 carpetas acordadas, el 100 % de ellas presentes sin
  pasos manuales adicionales.
- **SC-002**: Una persona nueva identifica en menos de 5 minutos en qué carpeta trabajar una tarea
  dada, usando solo el README.
- **SC-003**: El 100 % de las carpetas de primer nivel existentes aparece en la tabla del README y
  viceversa.
- **SC-004**: Cero archivos de datos descargados, configuración local o telemetría aparecen como
  cambios sin seguimiento tras un uso normal del entorno.
- **SC-005**: La licencia es reconocida automáticamente por la plataforma de alojamiento del
  repositorio.

## Assumptions

- Las carpetas, el `.editorconfig` y el README parcial ya existen desde el commit inicial
  (`LAB-1`); esta historia verifica, completa y cierra esos entregables en lugar de rehacerlos.
- El comando real de arranque (`docker compose` de `infra/`) lo entrega la historia F0-02; hasta
  entonces el README lo declara pendiente.
- Las carpetas `data` y `notebooks` forman parte de la estructura acordada en el README aunque no
  figuren en el criterio de aceptación; se conservan.
- Esta historia no incluye código de aplicación, pipelines de CI ni configuración de tecnologías
  concretas.
- Licencia MIT; titular Leandro Amariles; año 2026 (confirmado por el usuario).
