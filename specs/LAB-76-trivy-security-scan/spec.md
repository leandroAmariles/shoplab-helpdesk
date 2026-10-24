# Feature Specification: Escaneo de vulnerabilidades y secretos con Trivy

**Feature Branch**: `chore/LAB-76-trivy-security-scan`

**Created**: 2026-10-24

**Status**: Draft

**Input**: User description: "LAB-76 (F0-11) Como desarrollador, quiero detectar vulnerabilidades en el código, las dependencias y los secretos en cada PR. Criterios: aquasecurity/trivy-action en modo filesystem sobre todo el repo, con escáneres de vulnerabilidades (dependencias de Maven, npm y Python), secretos y configuración; severidad HIGH y CRITICAL: el job falla si las encuentra (exit-code 1, ignore-unfixed true); resultados en SARIF visibles en la pestaña Security de GitHub; .trivyignore versionado y comentado para excepciones justificadas; Dependabot para PRs de actualización (Maven, npm, pip, GitHub Actions y Docker); el escaneo de la imagen se hace en LAB-78. Depende de: LAB-15."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Bloqueo de vulnerabilidades y secretos graves en cada PR (Priority: P1)

Como desarrollador, quiero que cada pull request sea escaneado en todo el repositorio y que falle si
introduce vulnerabilidades de severidad alta o crítica, secretos expuestos o configuración insegura,
para no fusionar riesgos de seguridad conocidos.

**Why this priority**: Es el núcleo de la historia: un escaneo que no bloquea solo informa. Sin este
gate los demás entregables (reportes, actualizaciones automáticas) no protegen la rama principal.

**Independent Test**: Abrir un pull request sin hallazgos y comprobar que el check pasa; abrir otro
que añada deliberadamente una dependencia con una vulnerabilidad crítica con corrección disponible (o
un secreto de prueba) y comprobar que el check falla.

**Acceptance Scenarios**:

1. **Given** un pull request, **When** se abre o se actualiza, **Then** se ejecuta un escaneo del
   sistema de archivos completo del repositorio y su resultado aparece como check del pull request.
2. **Given** un pull request que no introduce hallazgos HIGH ni CRITICAL, **When** termina el
   escaneo, **Then** el check pasa.
3. **Given** un pull request que introduce una dependencia (Maven, npm o Python) con una
   vulnerabilidad HIGH o CRITICAL que ya tiene corrección publicada, **When** termina el escaneo,
   **Then** el check falla indicando paquete, versión afectada y versión corregida.
4. **Given** una vulnerabilidad HIGH o CRITICAL sin corrección publicada, **When** termina el
   escaneo, **Then** no provoca el fallo del check.
5. **Given** un pull request que añade un secreto (token, clave privada, contraseña) al código o a la
   configuración, **When** termina el escaneo, **Then** el check falla indicando el archivo y la
   regla, sin imprimir el valor del secreto.
6. **Given** un pull request que añade configuración insegura de severidad HIGH o CRITICAL (p. ej. en
   Dockerfile o manifiestos), **When** termina el escaneo, **Then** el check falla.
7. **Given** un pull request con hallazgos de severidad inferior a HIGH, **When** termina el
   escaneo, **Then** el check pasa y esos hallazgos no bloquean.

---

### User Story 2 - Resultados visibles en la pestaña Security de GitHub (Priority: P2)

Como desarrollador, quiero ver los hallazgos del escaneo en la pestaña Security de GitHub, para
revisarlos y hacerles seguimiento sin descargar reportes ni leer logs.

**Why this priority**: Mejora la accionabilidad, pero el gate de la historia 1 ya protege la rama
aunque los hallazgos solo se consulten en el log del job.

**Independent Test**: Tras un escaneo con hallazgos, abrir la pestaña Security del repositorio y
verificar que aparecen asociados al commit analizado.

**Acceptance Scenarios**:

1. **Given** un escaneo completado, **When** termina, **Then** sus resultados se publican en formato
   SARIF y son visibles en la pestaña Security del repositorio.
2. **Given** un fallo del gate por hallazgos, **When** termina el escaneo, **Then** los resultados se
   publican igualmente.
3. **Given** un push a `main`, **When** termina el escaneo, **Then** los resultados de la pestaña
   Security se actualizan para reflejar el estado de la rama principal.

---

### User Story 3 - Excepciones justificadas y versionadas con `.trivyignore` (Priority: P2)

Como desarrollador, quiero suprimir hallazgos concretos mediante un archivo versionado y comentado,
para gestionar falsos positivos o riesgos aceptados de forma explícita y revisable.

**Why this priority**: Sin un mecanismo de excepciones el gate acabaría desactivándose ante el primer
falso positivo; es condición para que el gate sea sostenible.

**Independent Test**: Añadir una entrada con justificación al archivo de excepciones y verificar que
el hallazgo correspondiente deja de bloquear; eliminarla y verificar que vuelve a bloquear.

**Acceptance Scenarios**:

1. **Given** el repositorio, **When** se revisa, **Then** existe un `.trivyignore` versionado cuyo
   encabezado explica cómo añadir excepciones.
2. **Given** una excepción en `.trivyignore`, **When** corre el escaneo, **Then** ese hallazgo no
   provoca el fallo del gate.
3. **Given** un pull request que añade una excepción sin comentario de justificación, **When** se
   revisa, **Then** incumple la convención documentada (cada excepción indica motivo y responsable o
   fecha de revisión).

---

### User Story 4 - Actualizaciones automáticas de dependencias con Dependabot (Priority: P2)

Como desarrollador, quiero que Dependabot abra pull requests de actualización para todos los
ecosistemas del proyecto, para corregir vulnerabilidades y mantener las dependencias al día sin
trabajo manual.

**Why this priority**: Reduce la causa raíz de los fallos del gate (dependencias vulnerables), pero
el escaneo funciona sin él.

**Independent Test**: Verificar que la configuración de Dependabot cubre los cinco ecosistemas y que,
tras su activación, aparecen pull requests de actualización en cada uno que tenga dependencias
desactualizadas.

**Acceptance Scenarios**:

1. **Given** el repositorio, **When** se revisa, **Then** la configuración de Dependabot cubre Maven,
   npm, pip, GitHub Actions y Docker, apuntando a los directorios donde existen sus manifiestos.
2. **Given** una dependencia con versión nueva disponible, **When** corre Dependabot, **Then** abre un
   pull request de actualización que pasa por el mismo pipeline (incluido este escaneo).
3. **Given** varias actualizaciones del mismo ecosistema, **When** Dependabot las propone, **Then**
   el volumen de pull requests abiertos simultáneamente está limitado para no saturar al equipo.

---

### Edge Cases

- ¿Qué pasa si la base de datos de vulnerabilidades no puede descargarse? El check falla con un error
  explícito distinguible de un fallo del gate; no se interpreta como éxito.
- ¿Qué pasa si el pull request viene de un fork o de un bot con permisos reducidos? El escaneo se
  ejecuta; la publicación de SARIF que requiera permisos se omite sin provocar un fallo falso.
- ¿Qué pasa si aparece una vulnerabilidad nueva en una dependencia ya existente sin tocarla? El
  siguiente escaneo (pull request o push a `main`) la detecta; el equipo la corrige o la exceptúa con
  justificación.
- ¿Qué pasa si el repositorio aún no contiene manifiestos de algún ecosistema (npm, Python, Docker)?
  El escaneo y Dependabot no fallan; esos ecosistemas se cubren en cuanto aparezcan sus manifiestos.
- ¿Qué pasa con rutas que no deben escanearse (`node_modules/`, `target/`, entornos virtuales)? Se
  excluyen para evitar ruido y tiempos excesivos.
- ¿Qué pasa si dos ejecuciones se solapan sobre el mismo pull request? La obsoleta se cancela.
- ¿Qué pasa con una excepción cuya vulnerabilidad ya fue corregida? Debe poder detectarse y retirarse
  en una revisión; no debe acumularse indefinidamente.
- ¿Qué pasa con la imagen de contenedor? Queda fuera: su escaneo se hace en LAB-78.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El pipeline MUST ejecutar un escaneo con `aquasecurity/trivy-action` en modo filesystem
  sobre todo el repositorio en cada pull request.
- **FR-002**: El escaneo MUST ejecutarse también en cada push a `main`, para mantener actualizados los
  resultados publicados.
- **FR-003**: El escaneo MUST incluir los escáneres de vulnerabilidades (dependencias Maven, npm y
  Python), de secretos y de configuración.
- **FR-004**: El job MUST fallar (`exit-code: 1`) cuando encuentre hallazgos de severidad HIGH o
  CRITICAL, y MUST NOT fallar por severidades inferiores.
- **FR-005**: Las vulnerabilidades sin corrección publicada MUST ignorarse (`ignore-unfixed: true`)
  para el cálculo del fallo.
- **FR-006**: Los resultados MUST publicarse en formato SARIF para que sean visibles en la pestaña
  Security de GitHub, incluso cuando el gate falle.
- **FR-007**: El repositorio MUST incluir un `.trivyignore` versionado, con un encabezado que explique
  su uso y con cada excepción comentada con su justificación.
- **FR-008**: El escaneo MUST respetar las excepciones de `.trivyignore`.
- **FR-009**: Ningún log ni reporte MUST exponer el valor de un secreto detectado.
- **FR-010**: El repositorio MUST incluir una configuración de Dependabot para los ecosistemas Maven,
  npm, pip, GitHub Actions y Docker.
- **FR-011**: La configuración de Dependabot MUST limitar el número de pull requests abiertos por
  ecosistema y definir una frecuencia de revisión.
- **FR-012**: El job de Trivy MUST ser independiente de los demás jobs del pipeline (LAB-15, LAB-75):
  el fallo de uno no impide que el otro se ejecute y reporte.
- **FR-013**: El job MUST usar permisos mínimos, otorgando escritura sobre la pestaña Security solo a
  este job.
- **FR-014**: El job MUST exponer un check de estado estable, apto para exigirse como requisito de
  protección de rama.
- **FR-015**: Las ejecuciones obsoletas del mismo pull request MUST cancelarse al llegar un nuevo
  commit.
- **FR-016**: Un fallo de infraestructura del escaneo (herramienta o base de datos no disponible) MUST
  hacer fallar el check con un mensaje distinguible de un fallo del gate.
- **FR-017**: El escaneo de la imagen de contenedor MUST NOT formar parte de esta historia (LAB-78).

### Key Entities

- **Escaneo de Trivy**: ejecución del análisis sobre el sistema de archivos del repositorio asociada a
  un commit y, cuando aplica, a un pull request; produce hallazgos y un veredicto.
- **Hallazgo**: vulnerabilidad de dependencia, secreto o configuración insegura, con severidad,
  ubicación y, si existe, versión corregida.
- **Gate de seguridad**: regla que convierte los hallazgos HIGH/CRITICAL con corrección disponible en
  un fallo del check.
- **`.trivyignore`**: archivo versionado de excepciones, cada una con justificación.
- **Reporte SARIF**: formato estándar de resultados publicado en la pestaña Security de GitHub.
- **Configuración de Dependabot**: definición versionada de qué ecosistemas, directorios y frecuencia
  se actualizan automáticamente.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los pull requests muestran el resultado del escaneo de seguridad como check,
  sin intervención manual.
- **SC-002**: Un pull request que introduce una vulnerabilidad HIGH/CRITICAL con corrección
  disponible, o un secreto, nunca llega a `main` con el check en verde (0 falsos negativos en el caso
  de prueba).
- **SC-003**: Los hallazgos de cada escaneo son visibles en la pestaña Security en el 100 % de las
  ejecuciones completadas, incluidas las que fallan el gate.
- **SC-004**: El 100 % de las excepciones del `.trivyignore` incluyen una justificación.
- **SC-005**: Los cinco ecosistemas (Maven, npm, pip, GitHub Actions, Docker) están cubiertos por la
  configuración de Dependabot.
- **SC-006**: Ningún valor de secreto aparece en los logs ni reportes del escaneo (0 hallazgos en una
  revisión de logs).
- **SC-007**: El escaneo de un pull request típico termina en menos de 10 minutos.

## Assumptions

- LAB-15 está implementado: existe `.github/workflows/ci.yml`; esta historia añade el escaneo sin
  modificar los gates existentes (Checkstyle, PMD, Modulith, JaCoCo, linters). LAB-75 (Qodana) es un
  job independiente.
- El escaneo corre sobre todo el repositorio en cada pull request, sin filtros por ruta, porque
  vulnerabilidades y secretos pueden aparecer en cualquier archivo.
- Algunos ecosistemas (npm, Python, Docker) pueden no tener aún manifiestos en el repositorio; la
  configuración los cubre de antemano y no falla mientras no existan.
- La visualización en la pestaña Security requiere que Code Scanning esté habilitado; si el plan del
  repositorio no lo permite, el SARIF se conserva como artefacto de la ejecución y se documenta la
  limitación.
- La configuración de protección de rama en GitHub se hace fuera del repositorio; esta historia solo
  garantiza que el check sea estable y apto para exigirse.
- Dependabot opera con frecuencia semanal y un límite bajo de pull requests abiertos por ecosistema,
  valores ajustables en la configuración versionada.
- Fuera de alcance: escaneo de la imagen de contenedor (LAB-78), análisis estático de calidad
  (LAB-75), gate de cobertura (LAB-15), despliegue y publicación de imágenes.
