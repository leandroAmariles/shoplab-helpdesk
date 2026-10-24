# Feature Specification: Pipeline de CI en GitHub Actions para Java, Angular y Python

**Feature Branch**: `chore/LAB-15-ci-github-actions`

**Created**: 2026-10-24

**Status**: Draft

**Input**: User description: "LAB-15 (F0-07) Como desarrollador, quiero un pipeline de CI en GitHub Actions para los tres lenguajes, base de los checks de calidad y seguridad. Criterios: runner ubuntu-latest con actions/checkout@v4, en cada PR y en cada push a main, con filtros por ruta para no construir lo que no cambió; Java: setup-java@v4 con Temurin 21 y caché de Maven, ./mvnw clean verify con Checkstyle, PMD, el test de Modulith y los tests de integración con Testcontainers; Cobertura: JaCoCo con gate de ≥ 80 % de líneas (jacoco:check), el job falla si no se cumple y el reporte se sube con actions/upload-artifact; Angular: lint, test y build con caché de npm; Python (ingest, eval): ruff y pytest con caché de uv; Badge en el README."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Validación automática del backend en cada cambio (Priority: P1)

Como desarrollador, quiero que cada pull request y cada push a `main` que toque el backend compile,
pase el análisis de estilo, las pruebas de arquitectura modular y las pruebas de integración, para
detectar regresiones antes de fusionar.

**Why this priority**: El backend es el único componente con código hoy y concentra los gates de
calidad exigidos por la constitución (estilo sin violaciones, pruebas de integración, límites de
módulos). Es el MVP del pipeline: sin él no hay red de seguridad para ninguna historia posterior.

**Independent Test**: Abrir un pull request que modifique un archivo del backend y comprobar que
se ejecuta el job Java y termina en verde; introducir una violación de estilo y comprobar que el
job falla.

**Acceptance Scenarios**:

1. **Given** un pull request que modifica archivos del backend, **When** se abre o se actualiza,
   **Then** se ejecuta el job Java con JDK 21 y su resultado aparece como check del pull request.
2. **Given** el job Java en ejecución, **When** corre la verificación completa, **Then** incluye
   compilación, análisis de estilo, análisis estático, la prueba de límites de módulos y las pruebas
   de integración con contenedores.
3. **Given** código con una violación de estilo o una prueba fallida, **When** corre el job Java,
   **Then** el job falla y el pull request queda bloqueado por ese check.
4. **Given** una ejecución previa exitosa, **When** corre de nuevo sin cambios en las dependencias,
   **Then** las dependencias se restauran desde caché y la ejecución es más rápida.
5. **Given** un push a `main` que modifica el backend, **When** se completa la fusión, **Then** el
   mismo job Java se ejecuta sobre `main`.

---

### User Story 2 - Gate de cobertura del 80 % con reporte descargable (Priority: P1)

Como desarrollador, quiero que el pipeline falle si la cobertura de líneas del backend baja del
80 % y que me deje el reporte para ver qué falta cubrir.

**Why this priority**: La constitución declara el gate de cobertura como no negociable; sin un
bloqueo automático el umbral solo sería una intención.

**Independent Test**: Ejecutar el pipeline con una cobertura por encima del umbral (verde y
reporte adjunto) y con una cobertura artificialmente por debajo (falla, reporte adjunto igualmente).

**Acceptance Scenarios**:

1. **Given** una cobertura de líneas igual o superior al 80 %, **When** termina el job Java,
   **Then** el job pasa.
2. **Given** una cobertura de líneas inferior al 80 %, **When** termina el job Java, **Then** el job
   falla indicando que no se cumple el umbral.
3. **Given** cualquier resultado del job Java (éxito o fallo por cobertura), **When** termina,
   **Then** el reporte de cobertura queda disponible como artefacto descargable de la ejecución.
4. **Given** una cobertura exactamente del 80 %, **When** corre el gate, **Then** el job pasa.

---

### User Story 3 - Ejecución solo de lo que cambió (Priority: P2)

Como desarrollador, quiero que el pipeline construya únicamente los componentes afectados por el
cambio, para obtener feedback rápido y no gastar minutos de CI en lo que no se tocó.

**Why this priority**: Es un criterio de aceptación explícito y mantiene el pipeline sostenible en
un monorepo con tres lenguajes, pero el pipeline ya aporta valor sin esta optimización.

**Independent Test**: Abrir un pull request que solo modifique documentación y otro que solo
modifique el backend; verificar qué jobs se ejecutan en cada uno.

**Acceptance Scenarios**:

1. **Given** un pull request que solo modifica `backend/`, **When** se abre, **Then** solo se
   ejecuta el job Java.
2. **Given** un pull request que solo modifica `frontend/`, **When** se abre, **Then** solo se
   ejecuta el job Angular.
3. **Given** un pull request que solo modifica `ingest/` o `eval/`, **When** se abre, **Then** solo
   se ejecuta el job Python correspondiente a lo que cambió.
4. **Given** un pull request que solo modifica documentación u otros archivos ajenos a los tres
   componentes, **When** se abre, **Then** no se construye ningún componente.
5. **Given** un cambio en el propio pipeline de CI, **When** se abre el pull request, **Then** se
   ejecutan todos los jobs para validar el cambio del pipeline.

---

### User Story 4 - Validación del frontend Angular (Priority: P2)

Como desarrollador, quiero que el pipeline ejecute lint, pruebas y build del frontend con caché de
dependencias, para detectar errores de la interfaz antes de fusionar.

**Why this priority**: Requerido por la historia, pero la carpeta `frontend/` aún no contiene
proyecto; entrega valor en cuanto exista código Angular.

**Independent Test**: Con un proyecto Angular presente, abrir un pull request que lo modifique y
verificar que lint, pruebas y build se ejecutan y que un fallo en cualquiera falla el job.

**Acceptance Scenarios**:

1. **Given** un pull request que modifica `frontend/`, **When** corre el job Angular, **Then**
   ejecuta lint, pruebas y build, en ese orden, y falla si alguno falla.
2. **Given** una ejecución previa con las mismas dependencias, **When** corre de nuevo, **Then** las
   dependencias se restauran desde caché.

---

### User Story 5 - Validación de los servicios Python (Priority: P2)

Como desarrollador, quiero que el pipeline ejecute el linter y las pruebas de `ingest` y `eval`
con caché de dependencias, para mantener la calidad del código de ingesta y evaluación.

**Why this priority**: Requerido por la historia; `ingest/` y `eval/` están hoy vacíos, por lo que
aporta valor cuando aparezca código Python.

**Independent Test**: Con código Python presente en `ingest/` o `eval/`, abrir un pull request que
lo modifique y verificar que linter y pruebas corren y que un fallo en cualquiera falla el job.

**Acceptance Scenarios**:

1. **Given** un pull request que modifica `ingest/` o `eval/`, **When** corre el job Python,
   **Then** ejecuta el linter y las pruebas del componente modificado y falla si alguno falla.
2. **Given** una ejecución previa con las mismas dependencias, **When** corre de nuevo, **Then** las
   dependencias se restauran desde caché.

---

### User Story 6 - Estado del pipeline visible en el README (Priority: P3)

Como desarrollador o visitante del repositorio, quiero ver en el README un indicador del estado del
pipeline sobre `main`, para saber de un vistazo si la rama principal está sana.

**Why this priority**: Aporta visibilidad pero no afecta la calidad del código.

**Independent Test**: Abrir el README en el repositorio y verificar que el indicador se muestra y
refleja el último resultado en `main`.

**Acceptance Scenarios**:

1. **Given** el README del repositorio, **When** se visualiza, **Then** muestra un indicador con el
   estado de la última ejecución del pipeline en `main`.
2. **Given** el indicador, **When** se hace clic en él, **Then** lleva a las ejecuciones del
   pipeline.

---

### Edge Cases

- ¿Qué pasa cuando `frontend/`, `ingest/` o `eval/` no contienen proyecto todavía? El job
  correspondiente debe omitirse o terminar en verde sin falsos fallos, de modo que el pipeline no se
  rompa antes de que exista ese código.
- ¿Qué pasa si un pull request cambia varios componentes a la vez? Se ejecutan los jobs de todos los
  componentes afectados en paralelo e independientes entre sí.
- ¿Qué pasa si los filtros por ruta dejan sin ejecutar un check que se exige como obligatorio en la
  protección de rama? Un check requerido que no se ejecuta deja el pull request pendiente; el diseño
  debe evitarlo (ver Assumptions).
- ¿Qué pasa si falla la restauración de caché? El job continúa descargando dependencias y no falla
  por ese motivo.
- ¿Qué pasa si las pruebas de integración no pueden levantar contenedores? El job Java falla con un
  error explícito; no se ignora.
- ¿Qué pasa si dos ejecuciones del pipeline se solapan sobre la misma rama o pull request? La
  ejecución obsoleta se cancela para no gastar minutos.
- ¿Qué pasa si el pull request viene de un fork? El pipeline debe ejecutarse sin exponer secretos.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El pipeline MUST ejecutarse en cada pull request y en cada push a `main`.
- **FR-002**: El pipeline MUST ejecutarse en un entorno Linux hospedado por la plataforma de CI
  (`ubuntu-latest`) y obtener el código con `actions/checkout@v4`.
- **FR-003**: El pipeline MUST aplicar filtros por ruta para que cada componente (`backend/`,
  `frontend/`, `ingest/`, `eval/`) solo se construya cuando cambian sus archivos o el propio
  pipeline.
- **FR-004**: Un cambio en la definición del pipeline MUST disparar la validación de todos los
  componentes.
- **FR-005**: El job Java MUST usar `actions/setup-java@v4` con Temurin 21 y caché de dependencias
  Maven.
- **FR-006**: El job Java MUST ejecutar `./mvnw clean verify` desde `backend/`, que incluye
  Checkstyle, PMD, la prueba de límites de módulos (Modulith) y las pruebas de integración con
  Testcontainers.
- **FR-007**: El job Java MUST fallar ante cualquier violación de Checkstyle o PMD, prueba fallida o
  incumplimiento de límites de módulos.
- **FR-008**: El job Java MUST aplicar el gate de cobertura de líneas ≥ 80 % mediante `jacoco:check`
  con la versión de JaCoCo definida en el `pom.xml`, y MUST fallar si no se cumple.
- **FR-009**: El reporte de cobertura MUST subirse con `actions/upload-artifact`, también cuando el
  gate falle.
- **FR-010**: El job Angular MUST ejecutar lint, pruebas y build, con caché de npm, y fallar si
  alguno de los tres falla.
- **FR-011**: El job Python MUST cubrir `ingest/` y `eval/`, ejecutar ruff y pytest con caché de uv,
  y fallar si alguno falla.
- **FR-012**: Los jobs de componentes sin proyecto todavía (carpeta vacía) MUST omitirse o terminar
  sin error, sin generar falsos fallos.
- **FR-013**: Los jobs de distintos componentes MUST ser independientes: el fallo de uno no impide
  que los demás se ejecuten y reporten.
- **FR-014**: Las ejecuciones obsoletas de la misma rama o pull request MUST cancelarse al llegar un
  nuevo commit.
- **FR-015**: El README MUST mostrar un badge con el estado del pipeline sobre `main`, enlazado a las
  ejecuciones.
- **FR-016**: El pipeline MUST usar permisos mínimos y no depender de secretos para ejecutarse en
  pull requests.
- **FR-017**: El pipeline MUST exponer un check de estado estable por componente, apto para exigirse
  como requisito de protección de rama.

### Key Entities

- **Pipeline de CI**: definición versionada en el repositorio que describe cuándo y cómo se valida el
  código; se dispara por pull request y por push a `main`.
- **Job por componente**: unidad de validación independiente (Java, Angular, Python) con su propio
  resultado, caché y condiciones de ejecución.
- **Filtro por ruta**: regla que asocia cada componente con las carpetas cuyo cambio lo activa.
- **Reporte de cobertura**: artefacto producido por el job Java, descargable desde la ejecución.
- **Badge de estado**: indicador en el README del resultado del pipeline sobre `main`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los pull requests y pushes a `main` muestran el resultado del pipeline como
  check sin intervención manual.
- **SC-002**: Un cambio con una violación de estilo, una prueba fallida o cobertura < 80 % nunca
  llega a `main` con el pipeline en verde (0 falsos negativos en los tres casos de prueba).
- **SC-003**: Un pull request que solo toca documentación consume cero ejecuciones de build de
  componentes.
- **SC-004**: Con dependencias en caché, la validación del backend en un pull request termina en
  menos de 10 minutos.
- **SC-005**: El reporte de cobertura está disponible para descarga en el 100 % de las ejecuciones
  del job Java, incluidas las que fallan por cobertura.
- **SC-006**: Un desarrollador nuevo identifica el estado de `main` en menos de 10 segundos desde el
  README.

## Assumptions

- El proyecto backend (`backend/`) ya existe con Maven Wrapper, Checkstyle, PMD, JaCoCo (umbral
  configurable de 0.80 en el `pom.xml`), la prueba de Modulith y pruebas con Testcontainers (LAB-11).
  Esta historia no modifica esos gates, solo los ejecuta en CI.
- `frontend/`, `ingest/` y `eval/` están vacíos a la fecha; la historia define sus jobs, que quedarán
  efectivos cuando esos componentes tengan proyecto (historias posteriores de F0).
- El runner hospedado incluye un daemon de contenedores, requisito para Testcontainers.
- El repositorio está en GitHub y el badge usa el nombre del flujo de trabajo del pipeline.
- Fuera de alcance: análisis de calidad con Qodana, escaneo de seguridad con Trivy, evaluación RAG
  (F2), despliegue/CD y publicación de imágenes. El objetivo del sprint los menciona, pero son
  historias separadas que se apoyan en este pipeline.
- La configuración de protección de rama en GitHub se hace fuera del repositorio; esta historia solo
  garantiza que los checks sean estables y aptos para exigirse.
- Pull requests desde forks no necesitan secretos: el pipeline no los usa.
