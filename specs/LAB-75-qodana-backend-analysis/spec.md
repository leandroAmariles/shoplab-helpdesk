# Feature Specification: Análisis estático con Qodana en el backend

**Feature Branch**: `chore/LAB-75-qodana-backend-analysis`

**Created**: 2026-10-24

**Status**: Draft

**Input**: User description: "LAB-75 (F0-10) Como desarrollador, quiero un análisis de calidad del backend en cada PR para detectar bugs, code smells y deuda técnica antes de fusionar. Criterios: JetBrains/qodana-action sobre backend/ con el linter de JVM (qodana.yaml versionado en el repo); perfil de inspecciones recomendado y baseline inicial para no bloquear por deuda heredada; quality gate: el PR falla si aparecen problemas nuevos de severidad alta; resultados en SARIF visibles en la pestaña Security de GitHub y como comentario del PR; QODANA_TOKEN (si se usa Qodana Cloud) como secreto del repo, nunca en el código; Angular y Python quedan cubiertos por sus linters en LAB-15. Depende de: LAB-15."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Bloqueo de problemas nuevos de severidad alta en el backend (Priority: P1)

Como desarrollador, quiero que cada pull request que toque el backend sea analizado con Qodana y
que falle si introduce problemas nuevos de severidad alta, para detectar bugs, code smells y deuda
técnica antes de fusionar.

**Why this priority**: Es el núcleo de la historia: un análisis que no bloquea no cambia el
comportamiento del equipo. Sin este gate los demás entregables (reportes, comentarios) solo
informan.

**Independent Test**: Abrir un pull request que modifique el backend sin añadir problemas y
comprobar que el análisis termina en verde; abrir otro que introduzca deliberadamente un problema de
severidad alta (p. ej. una desreferencia nula evidente) y comprobar que el check falla.

**Acceptance Scenarios**:

1. **Given** un pull request que modifica archivos del backend, **When** se abre o se actualiza,
   **Then** se ejecuta el análisis de Qodana sobre `backend/` y su resultado aparece como check del
   pull request.
2. **Given** un pull request que no introduce problemas nuevos, **When** termina el análisis,
   **Then** el check pasa.
3. **Given** un pull request que introduce al menos un problema nuevo de severidad alta, **When**
   termina el análisis, **Then** el check falla indicando qué problemas lo provocaron.
4. **Given** un pull request que introduce solo problemas de severidad inferior a alta, **When**
   termina el análisis, **Then** el check pasa y los problemas quedan reportados como informativos.
5. **Given** un pull request que solo modifica documentación u otro componente ajeno al backend,
   **When** se abre, **Then** el análisis de Qodana no se ejecuta.

---

### User Story 2 - Baseline inicial para no bloquear por deuda heredada (Priority: P1)

Como desarrollador, quiero que el análisis parta de un baseline que registre los problemas ya
existentes, para que solo bloqueen los problemas nuevos y no la deuda heredada.

**Why this priority**: Sin baseline, el primer pull request fallaría por código previo y el gate
sería inutilizable; es condición para que la historia 1 sea adoptable.

**Independent Test**: Con el baseline versionado, ejecutar el análisis sobre un pull request que no
toca los problemas heredados y comprobar que pasa; eliminar el baseline y comprobar que los
problemas heredados de severidad alta (si los hay) harían fallar el gate.

**Acceptance Scenarios**:

1. **Given** problemas de severidad alta ya presentes en el código al adoptar Qodana, **When** corre
   el análisis con el baseline inicial, **Then** esos problemas se marcan como heredados y no
   provocan el fallo del gate.
2. **Given** un problema heredado que el equipo corrige, **When** corre el análisis, **Then** deja de
   aparecer y no se reporta como regresión.
3. **Given** un problema heredado que reaparece en otro lugar del código como código nuevo, **When**
   corre el análisis, **Then** se considera nuevo y se evalúa contra el gate.

---

### User Story 3 - Resultados visibles en GitHub: pestaña Security y comentario del PR (Priority: P2)

Como desarrollador, quiero ver los hallazgos del análisis dentro de GitHub, en la pestaña Security y
como comentario resumen del pull request, para revisarlos sin descargar reportes ni salir de la
plataforma.

**Why this priority**: Mejora la accionabilidad de los hallazgos, pero el gate de la historia 1 ya
protege la rama aunque los resultados solo se consulten en el log del job.

**Independent Test**: Tras una ejecución del análisis sobre un pull request con hallazgos, abrir la
pestaña Security del repositorio y el propio pull request y verificar que ambos muestran los
resultados.

**Acceptance Scenarios**:

1. **Given** un análisis completado, **When** termina, **Then** sus resultados se publican en formato
   SARIF y son visibles en la pestaña Security del repositorio, asociados al commit analizado.
2. **Given** un análisis completado sobre un pull request, **When** termina, **Then** el pull request
   recibe un comentario resumen con los problemas nuevos encontrados (o con la indicación de que no
   hay ninguno).
3. **Given** nuevas ejecuciones sobre el mismo pull request, **When** terminan, **Then** el comentario
   se actualiza en lugar de acumular uno nuevo por ejecución.
4. **Given** un fallo del gate de calidad, **When** termina el análisis, **Then** los resultados y el
   comentario se publican igualmente.

---

### User Story 4 - Configuración versionada y secretos fuera del código (Priority: P2)

Como desarrollador, quiero que la configuración del análisis (linter, perfil de inspecciones,
umbrales) viva versionada en el repositorio y que cualquier token se gestione como secreto, para que
el análisis sea reproducible y no filtre credenciales.

**Why this priority**: Garantiza reproducibilidad y cumple la regla de la constitución de no
versionar secretos, pero el análisis puede funcionar sin token.

**Independent Test**: Revisar el repositorio y verificar que existe el archivo de configuración con
el linter JVM y el perfil recomendado, y que ningún archivo versionado contiene un token; ejecutar
el análisis con y sin el secreto configurado.

**Acceptance Scenarios**:

1. **Given** el repositorio, **When** se revisa, **Then** existe un archivo de configuración de
   Qodana versionado que declara el linter de JVM, el perfil de inspecciones recomendado, el
   baseline y el umbral del gate.
2. **Given** el uso de Qodana Cloud, **When** se ejecuta el análisis, **Then** el token se lee
   únicamente desde un secreto del repositorio y nunca aparece en el código ni en los logs.
3. **Given** un pull request abierto desde un fork, donde los secretos no están disponibles,
   **When** corre el análisis, **Then** el job no falla por la ausencia del token y no expone
   secretos.
4. **Given** un cambio en la configuración de Qodana o en la definición del pipeline, **When** se
   abre el pull request, **Then** se ejecuta el análisis para validar el cambio.

---

### Edge Cases

- ¿Qué pasa si el análisis no puede ejecutarse (servicio no disponible, fallo de la herramienta)? El
  check falla con un error explícito distinguible de un fallo del gate; no se interpreta como éxito.
- ¿Qué pasa si el pull request viene de un fork o de un bot con permisos reducidos? El análisis se
  ejecuta sin secretos; la publicación de SARIF o comentarios que requiera permisos se omite sin
  provocar un fallo falso.
- ¿Qué pasa si el baseline queda obsoleto tras un refactor grande? Los problemas heredados que
  cambian de ubicación pueden reaparecer como nuevos; el equipo debe poder regenerar el baseline de
  forma explícita y revisada en un pull request.
- ¿Qué pasa si un pull request cambia a la vez backend y otros componentes? El análisis de Qodana
  corre de forma independiente y no bloquea ni es bloqueado por los jobs de LAB-15.
- ¿Qué pasa si dos ejecuciones se solapan sobre el mismo pull request? La obsoleta se cancela.
- ¿Qué pasa si un problema nuevo es un falso positivo? El equipo debe poder suprimirlo de forma
  explícita y versionada (exclusión o anotación revisada en el pull request), no desactivando el
  gate.
- ¿Qué pasa con las rutas excluidas (código generado, `target/`)? No se analizan ni generan ruido.
- ¿Qué pasa en un push a `main`? El análisis se ejecuta también para mantener actualizados los
  resultados de la pestaña Security y la referencia contra la que se comparan los pull requests.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El pipeline MUST ejecutar un análisis con `JetBrains/qodana-action` sobre `backend/` en
  cada pull request que modifique el backend, la configuración de Qodana o la definición del
  pipeline.
- **FR-002**: El análisis MUST ejecutarse también en cada push a `main`, para mantener actualizada la
  referencia y los resultados publicados.
- **FR-003**: El análisis MUST usar el linter de JVM de Qodana con un archivo de configuración
  `qodana.yaml` versionado en el repositorio.
- **FR-004**: La configuración MUST usar el perfil de inspecciones recomendado de Qodana.
- **FR-005**: El repositorio MUST incluir un baseline inicial que registre los problemas existentes
  al adoptar la herramienta, de modo que no provoquen el fallo del gate.
- **FR-006**: El gate de calidad MUST hacer fallar el check cuando aparezca al menos un problema
  nuevo de severidad alta respecto al baseline, y MUST NOT fallar por problemas de severidad
  inferior ni por problemas heredados.
- **FR-007**: Los resultados MUST publicarse en formato SARIF para que sean visibles en la pestaña
  Security de GitHub, incluso cuando el gate falle.
- **FR-008**: En pull requests, el análisis MUST publicar un comentario resumen con los problemas
  nuevos, actualizándolo en cada ejecución en lugar de duplicarlo.
- **FR-009**: Si se usa Qodana Cloud, `QODANA_TOKEN` MUST leerse únicamente desde un secreto del
  repositorio; ningún archivo versionado ni log MUST contener su valor.
- **FR-010**: El análisis MUST poder ejecutarse sin `QODANA_TOKEN` (p. ej. en pull requests desde
  forks) sin fallar por ese motivo ni exponer secretos.
- **FR-011**: El job de Qodana MUST ser independiente de los jobs de LAB-15: el fallo de uno no
  impide que el otro se ejecute y reporte.
- **FR-012**: El job MUST usar permisos mínimos, otorgando escritura sobre la pestaña Security y los
  comentarios del pull request solo a este job.
- **FR-013**: El job MUST exponer un check de estado estable, apto para exigirse como requisito de
  protección de rama.
- **FR-014**: Las ejecuciones obsoletas del mismo pull request MUST cancelarse al llegar un nuevo
  commit.
- **FR-015**: La configuración MUST excluir del análisis el código generado y los artefactos de
  compilación.
- **FR-016**: Un fallo de infraestructura del análisis (herramienta o servicio no disponible) MUST
  hacer fallar el check con un mensaje distinguible de un fallo del gate de calidad.
- **FR-017**: La cobertura de Angular y Python MUST NOT formar parte de esta historia; sus linters
  permanecen en el pipeline de LAB-15.

### Key Entities

- **Análisis de Qodana**: ejecución de la inspección estática sobre `backend/` asociada a un commit y,
  cuando aplica, a un pull request; produce hallazgos y un veredicto del gate.
- **Configuración de Qodana (`qodana.yaml`)**: archivo versionado que declara linter, perfil de
  inspecciones, exclusiones, baseline y umbral del gate.
- **Baseline**: instantánea versionada de los problemas existentes al adoptar la herramienta; define
  qué es "heredado" frente a "nuevo".
- **Hallazgo**: problema detectado (bug, code smell, deuda técnica) con severidad y ubicación.
- **Quality gate**: regla que convierte los hallazgos nuevos de severidad alta en un fallo del check.
- **Reporte SARIF**: formato estándar de resultados publicado en la pestaña Security de GitHub.
- **Secreto `QODANA_TOKEN`**: credencial opcional de Qodana Cloud, almacenada solo en los secretos
  del repositorio.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los pull requests que modifican el backend muestran el resultado del
  análisis de Qodana como check, sin intervención manual.
- **SC-002**: Un pull request que introduce un problema nuevo de severidad alta nunca llega a `main`
  con el check en verde (0 falsos negativos en el caso de prueba).
- **SC-003**: El primer pull request posterior a la adopción no falla por deuda heredada (0
  problemas heredados bloqueantes con el baseline aplicado).
- **SC-004**: Los hallazgos de cada análisis son visibles en la pestaña Security y en el pull request
  en el 100 % de las ejecuciones completadas, incluidas las que fallan el gate.
- **SC-005**: Ningún secreto aparece en el repositorio ni en los logs del análisis (0 hallazgos en
  una revisión de secretos).
- **SC-006**: Un pull request que solo toca documentación u otros componentes no consume
  ejecuciones de Qodana.
- **SC-007**: El análisis de un pull request típico del backend termina en menos de 15 minutos.

## Assumptions

- LAB-15 está implementado: existe `.github/workflows/ci.yml` con filtros por ruta y el check
  agregador; esta historia añade el análisis de Qodana sin modificar los gates de Checkstyle, PMD,
  Modulith ni JaCoCo.
- El backend (`backend/`) ya contiene código Java 21 con Maven (LAB-11), por lo que el análisis tiene
  contenido real que inspeccionar y un baseline inicial no vacío es posible.
- Por defecto se usa Qodana sin Cloud (edición gratuita del linter JVM); `QODANA_TOKEN` es opcional y
  solo se configura si el equipo decide usar Qodana Cloud. La historia debe funcionar sin él.
- La severidad "alta" se corresponde con las severidades más altas que reporta Qodana (críticas y
  altas); el umbral exacto queda en la configuración versionada y es ajustable.
- La visualización en la pestaña Security requiere que Code Scanning esté habilitado en el
  repositorio; si el plan del repositorio no lo permite, el SARIF se conserva como artefacto de la
  ejecución y se documenta la limitación.
- La configuración de protección de rama en GitHub se hace fuera del repositorio; esta historia solo
  garantiza que el check sea estable y apto para exigirse.
- Fuera de alcance: Angular y Python (linters en LAB-15), escaneo de seguridad con Trivy, gate de
  cobertura (LAB-15), despliegue y publicación de imágenes.
