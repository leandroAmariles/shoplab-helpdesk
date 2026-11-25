# Feature Specification: LAB-110 Entorno local con varios repos

**Feature Branch**: `feature/LAB-110-multi-repo-local-env`

**Created**: 2026-11-25

**Status**: Draft

**Input**: User description: "LAB-110 (F5-18) Como desarrollador, quiero levantar todo el sistema en local aunque el código esté repartido en varios repos, trabajando solo en el servicio que estoy cambiando. Criterios de aceptación: `shoplab-helpdesk` queda como repo paraguas: ADRs, guías de fase, docker compose del sistema completo y pruebas de extremo a extremo; El compose levanta todos los servicios con imágenes de GHCR en versiones fijadas en un `.env`; Un override de compose construye un servicio desde su carpeta local (repo clonado al lado) en vez de usar la imagen; Script `make clone-all` que clona todos los repos en carpetas hermanas; README con el flujo: clonar, levantar todo, cambiar un servicio, probar. Depende de: LAB-109."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Levantamiento completo del sistema con imágenes fijadas de GHCR (Priority: P1)

Como desarrollador que se incorpora al proyecto o que necesita interactuar con el sistema integrado, quiero ejecutar un único comando (`docker compose up`) desde `shoplab-helpdesk` para levantar todos los servicios de la plataforma (infraestructura, backend, frontend e ingesta) consumiendo imágenes oficiales publicadas en GitHub Container Registry (GHCR) con versiones fijadas en `.env`, para disponer de un entorno funcional completo en mi máquina en minutos sin necesidad de compilar el código de cada repositorio individualmente.

**Why this priority**: Es el caso de uso central y fundamental. Tras la partición de repositorios realizada en LAB-109, los desarrolladores ya no tienen el código de todos los servicios en un único monorepo. Sin la capacidad de levantar el ecosistema completo a partir de imágenes de GHCR, ningún desarrollador podría probar de forma integrada o ejecutar flujos end-to-end sin una laboriosa compilación manual de múltiples repositorios.

**Independent Test**: En una máquina limpia sin repositorios clonados adicionales (solo `shoplab-helpdesk`), copiar `infra/.env.example` a `infra/.env`, ejecutar `docker compose -f infra/docker-compose.yml up -d --wait` y verificar que todos los contenedores (`postgres`, `litellm`, `ingest`, `backend`, `frontend`) alcanzan estado `healthy`/`running`, que la interfaz web del frontend carga en `http://localhost:4200` (o `http://localhost:80`), que el backend responde a `http://localhost:8080/actuator/health` y que el chat responde a consultas de usuario.

**Acceptance Scenarios**:

1. **Given** un entorno local con Docker y solo el repositorio `shoplab-helpdesk` clonado, **When** se ejecuta `docker compose -f infra/docker-compose.yml up -d --wait`, **Then** Docker descarga y arranca los contenedores de infraestructura (`postgres`, `ollama`/`litellm`) y los contenedores de aplicación (`backend`, `frontend`, `ingest`) utilizando exclusivamente imágenes publicadas en GHCR (`ghcr.io/leandroamariles/*`) con tags semánticos inmutables definidos en `infra/.env`.
2. **Given** el archivo `infra/.env.example`, **When** se examinan las variables de imágenes de aplicación, **Then** define explícitamente `BACKEND_IMAGE`, `FRONTEND_IMAGE`, e `INGEST_IMAGE` con sus tags versionados correspondientes (p. ej. `ghcr.io/leandroamariles/shoplab-backend:v0.1.0`, `ghcr.io/leandroamariles/shoplab-frontend:v0.1.0`, `ghcr.io/leandroamariles/shoplab-ingest:v0.1.0`), prohibiendo terminantemente el uso de `:latest`.
3. **Given** los servicios levantados con la configuración por defecto de GHCR, **When** un usuario accede a la URL del frontend y envía un mensaje por el chat, **Then** la petición fluye desde el frontend hacia el backend, se apoya en LiteLLM y PostgreSQL, y retorna la respuesta en streaming de forma exitosa.
4. **Given** un desarrollador que desea cambiar la versión de un servicio desplegado, **When** modifica el tag correspondiente en `infra/.env` y ejecuta `docker compose -f infra/docker-compose.yml up -d`, **Then** Docker Compose recrea únicamente el servicio afectado con la nueva versión de imagen sin alterar el resto del entorno.

---

### User Story 2 - Desarrollo local sobre un servicio específico mediante override de compose (Priority: P1)

Como desarrollador que trabaja en una funcionalidad o corrección para un servicio concreto (p. ej., `shoplab-frontend` o `shoplab-ingest`), quiero habilitar un archivo de override en Docker Compose que reemplace la imagen de GHCR de ese servicio por una construcción local (`build`) apuntando a su carpeta hermana clonada al lado, mientras el resto del sistema sigue operando con las imágenes de GHCR, para poder iterar, compilar y probar mis cambios locales en tiempo real con el resto del ecosistema funcionando sin fricción.

**Why this priority**: Es la razón de ser del desarrollo multi-repo: permitir a cada desarrollador concentrarse en un único repositorio sin la sobrecarga de clonar y compilar todo, pero garantizando que sus cambios locales se prueban contra el resto de componentes reales del sistema.

**Independent Test**: Clonar `shoplab-frontend` en la carpeta hermana `../shoplab-frontend`, activar en `docker-compose.override.yml` la sección de build para `frontend` (`context: ../shoplab-frontend`), realizar un cambio cosmético visible en el código fuente de Angular, ejecutar `docker compose -f infra/docker-compose.yml up -d --build frontend`, y comprobar en el navegador que el cambio se refleja inmediatamente sin haber tocado los demás contenedores que siguen corriendo sus imágenes de GHCR.

**Acceptance Scenarios**:

1. **Given** una carpeta hermana clonada `../shoplab-ingest` con modificaciones locales, **When** se activa el archivo `infra/docker-compose.override.yml` con la directiva `build.context: ../../shoplab-ingest` (o `../shoplab-ingest` según la ruta base), **Then** Docker Compose ignora la imagen de GHCR para `ingest` y construye la imagen a partir del código fuente local de la carpeta hermana.
2. **Given** un desarrollador con el repositorio `shoplab-frontend` clonado al lado, **When** ejecuta `docker compose up` con el override activo, **Then** el servicio `frontend` se construye localmente mientras `backend`, `ingest` y la infraestructura permanecen intactos consumiendo las imágenes remotas de GHCR.
3. **Given** el repositorio `shoplab-helpdesk`, **When** se revisa la carpeta `infra/`, **Then** incluye un archivo de plantilla documentado `docker-compose.override.yml.example` con bloques comentados listos para habilitar el build local de `backend`, `frontend` o `ingest` según las necesidades del desarrollador.
4. **Given** que `infra/docker-compose.override.yml` es un archivo de personalización local del desarrollador, **When** se revisa `.gitignore`, **Then** dicho archivo se encuentra ignorado para evitar que configuraciones personales se suban al control de versiones.

---

### User Story 3 - Clonación orquestada del ecosistema con `make clone-all` (Priority: P2)

Como desarrollador que necesita trabajar con múltiples servicios o preparar su entorno de trabajo integral, quiero disponer del comando `make clone-all` (y su equivalente multiplataforma en PowerShell para Windows) que clone automáticamente todos los repositorios hermanos del ecosistema ShopLab en carpetas adyacentes a `shoplab-helpdesk`, para no tener que buscar manualmente los enlaces en GitHub ni clonar uno por uno cada repositorio.

**Why this priority**: Reduce sustancialmente el coste de incorporación (onboarding) y previene errores humanos en nombres de carpetas o rutas relativas esperadas por los overrides de Docker Compose.

**Independent Test**: En un directorio que contenga únicamente `shoplab-helpdesk`, ejecutar `make clone-all` (o el script equivalente en PowerShell), y verificar que en el directorio padre aparecen clonados los repositorios hermanos `shoplab-frontend`, `shoplab-ingest`, `shoplab-eval`, `shoplab-contracts`, `shoplab-ci` y `shoplab-infra` con sus ramas `main` actualizadas y sin sobrescribir repositorios que ya existiesen.

**Acceptance Scenarios**:

1. **Given** una terminal en la raíz de `shoplab-helpdesk`, **When** el usuario ejecuta `make clone-all`, **Then** el target identifica los repositorios oficiales de la organización/usuario (`shoplab-frontend`, `shoplab-ingest`, `shoplab-eval`, `shoplab-contracts`, `shoplab-ci`, `shoplab-infra`) y los clona mediante `git clone` en carpetas hermanas (`../<nombre-repo>`).
2. **Given** que uno o más de los repositorios hermanos ya han sido clonados previamente por el usuario, **When** se ejecuta `make clone-all`, **Then** el script detecta su presencia, muestra un mensaje informativo indicando que el repositorio ya existe y no interrumpe la clonación de los repositorios restantes.
3. **Given** un entorno Windows donde `make` puede no estar instalado de forma nativa en PowerShell, **When** se inspecciona la solución, **Then** se proporciona un script complementario `scripts/clone-all.ps1` (o invocable desde Makefile/PowerShell) con idéntico comportamiento e idempotencia.

---

### User Story 4 - `shoplab-helpdesk` como repositorio paraguas, documentación y pruebas E2E (Priority: P2)

Como arquitecto o líder técnico, quiero que `shoplab-helpdesk` consolide formalmente su rol como repositorio paraguas ("umbrella repo"), albergando los Registros de Decisiones de Arquitectura (ADRs), las guías de fase, la orquestación central de Docker Compose y el arnés de pruebas de extremo a extremo (E2E) del sistema integrado, para mantener la coherencia arquitectónica global del ecosistema desacoplado.

**Why this priority**: Asegura que el desacoplamiento de servicios en múltiples repositorios no disperse ni destruya la visión unificada del sistema, sus decisiones globales y la capacidad de validar la integración completa de extremo a extremo.

**Independent Test**: Inspeccionar la estructura de `shoplab-helpdesk` comprobando la presencia y vigencia de `docs/adr/`, `docs/mapa-servicios.md`, `docs/fases/`, `infra/docker-compose.yml`, y ejecutar una suite de prueba E2E / verificación de salud integrada que interactúe con todos los servicios orquestados.

**Acceptance Scenarios**:

1. **Given** la documentación de arquitectura en `docs/`, **When** se revisa el contenido de `docs/mapa-servicios.md` y `README.md`, **Then** declaran inequívocamente a `shoplab-helpdesk` como el repositorio paraguas responsable de la gobernanza arquitectónica, los ADRs, las pruebas de extremo a extremo y la composición del entorno local.
2. **Given** los servicios del sistema levantados en local, **When** se ejecuta la suite o script de pruebas de extremo a extremo (E2E), **Then** valida los flujos críticos transversales (ingesta de documento -> búsqueda en retrieval -> generación conversacional en chat con citas -> visualización en frontend) reportando éxito.
3. **Given** el archivo `README.md`, **When** un desarrollador lo consulta, **Then** detalla de forma clara el flujo de 4 pasos: (1) Clonar (`make clone-all`), (2) Levantar todo (`docker compose up`), (3) Cambiar un servicio localmente (override), y (4) Probar y verificar.

---

### Edge Cases

- **¿Qué ocurre si un desarrollador no tiene permisos en GHCR para descargar imágenes privadas?** Las imágenes deben ser públicas en GHCR o el `README.md` debe documentar el procedimiento de autenticación previa (`docker login ghcr.io -u <usuario> -p <token>`) con un Personal Access Token (PAT) con alcance `read:packages`.
- **¿Qué ocurre si un desarrollador activa el override para un servicio pero la carpeta hermana no existe?** Docker Compose fallará con un error descriptivo de ruta no encontrada (`context directory does not exist`). Por ello, `docker-compose.override.yml` NO se versiona activo por defecto; se provee como `docker-compose.override.yml.example` con instrucciones paso a paso.
- **¿Qué ocurre con la resolución de red entre un servicio levantado en el host (p. ej. en el IDE) y los servicios en Docker?** Los puertos clave de cada servicio (`8080` backend, `8000` ingest, `4200` frontend, `5432` postgres, `4000` litellm) se publican en el host (`localhost`), permitiendo que un desarrollador apague el contenedor de un servicio específico (`docker compose stop backend`) y ejecute ese servicio directamente en su IDE o terminal conectándose a los demás por `localhost`.
- **¿Qué ocurre con los nombres de repositorios en `make clone-all` si se clona mediante SSH en lugar de HTTPS?** El script debe soportar una variable de protocolo (`GIT_PROTOCOL=https` o `GIT_PROTOCOL=ssh`, por defecto `https` para evitar fallos si el usuario no tiene claves SSH cargadas en GitHub).
- **¿Cómo se sincronizan las versiones en `.env` cuando un servicio publica una nueva release en GHCR?** Las versiones en `infra/.env.example` representan una línea base probada y compatible entre servicios. Dependabot o un workflow de actualización periódica en `shoplab-infra` / `shoplab-helpdesk` mantiene las versiones sincronizadas con las releases estables de cada repositorio.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio `shoplab-helpdesk` MUST actuar como repositorio paraguas del proyecto, centralizando la documentación arquitectónica (`docs/adr/`, `docs/mapa-servicios.md`), la orquestación del sistema completo en Docker Compose y las pruebas integradas de extremo a extremo (E2E).
- **FR-002**: El archivo `infra/docker-compose.yml` MUST definir la composición de todos los componentes del sistema: infraestructura base (`postgres` con pgvector, `ollama`, pasarela `litellm`), servicios de aplicación (`backend`, `frontend`, `ingest`) y perfiles complementarios (`observability` con Prometheus/Grafana, `llmops` con Langfuse).
- **FR-003**: En `infra/docker-compose.yml`, todos los servicios de aplicación (`backend`, `frontend`, `ingest`) MUST utilizar por defecto imágenes publicadas en GitHub Container Registry (`ghcr.io/leandroamariles/<servicio>:<tag>`), parametrizadas mediante variables de entorno.
- **FR-004**: El archivo `infra/.env.example` MUST declarar las variables de configuración de imágenes (`BACKEND_IMAGE`, `FRONTEND_IMAGE`, `INGEST_IMAGE`) fijando versiones semánticas explícitas e inmutables (prohibiendo `:latest`).
- **FR-005**: La configuración de Docker Compose MUST soportar un mecanismo de override (`docker-compose.override.yml` o `-f infra/docker-compose.override.yml`) que permita reemplazar la imagen de GHCR por la directiva de compilación local (`build`) apuntando a la carpeta hermana del servicio (`../shoplab-<servicio>`).
- **FR-006**: Debe existir un archivo plantilla `infra/docker-compose.override.yml.example` que sirva de guía para habilitar de forma aislada la compilación local de cualquiera de los servicios extraídos.
- **FR-007**: El archivo `infra/docker-compose.override.yml` MUST estar incluido en `.gitignore` para no contaminar el repositorio con personalizaciones locales de cada desarrollador.
- **FR-008**: Debe implementarse un archivo `Makefile` en la raíz de `shoplab-helpdesk` que incluya el target `clone-all` para clonar automáticamente todos los repositorios hermanos (`shoplab-frontend`, `shoplab-ingest`, `shoplab-eval`, `shoplab-contracts`, `shoplab-ci`, `shoplab-infra`) en el directorio adyacente (`../`).
- **FR-009**: El comando `clone-all` MUST ser idempotente: si una carpeta hermana ya existe, debe omitirla sin fallar ni destruir cambios locales no comprometidos.
- **FR-010**: Debe proveerse un script equivalente para entornos Windows PowerShell (`scripts/clone-all.ps1`) para garantizar paridad operativa entre desarrolladores en Linux, macOS y Windows.
- **FR-011**: El archivo `README.md` MUST ser actualizado para reflejar el flujo estándar de desarrollo en 4 fases: (1) Clonar repositorios, (2) Levantar entorno completo con Compose, (3) Modificar un servicio individual mediante override o ejecución en host, y (4) Probar y validar flujos integrados.
- **FR-012**: La red interna de Docker Compose MUST permitir la comunicación entre servicios utilizando sus nombres canónicos (`backend`, `ingest`, `litellm`, `postgres`, `frontend`), mientras publica los puertos pertinentes al host para permitir la sustitución de cualquier contenedor por un proceso local en depuración.
- **FR-013**: Las pruebas de extremo a extremo (E2E) en `shoplab-helpdesk` MUST validar la interoperabilidad entre los servicios desplegados con las imágenes de GHCR.

---

### Key Entities

- **Repositorio Paraguas (`shoplab-helpdesk`)**: Repositorio raíz que no contiene el código de todos los servicios, pero alberga la configuración global de despliegue local, la arquitectura del sistema y los contratos de integración.
- **Repositorios Hermanos**: Conjunto de repositorios autónomos (`shoplab-frontend`, `shoplab-ingest`, `shoplab-eval`, `shoplab-contracts`, `shoplab-ci`, `shoplab-infra`) ubicados físicamente al mismo nivel de directorio (`../`).
- **Imágenes GHCR**: Artefactos inmutables de contenedor OCI etiquetados semánticamente (`:vX.Y.Z`) generados por los pipelines de CI de cada repositorio individual y consumidos por el Compose paraguas.
- **Compose Override (`docker-compose.override.yml`)**: Mecanismo de Docker Compose que permite redefinir atributos de servicios (cambiando `image` por `build`) sin modificar el archivo base `docker-compose.yml`.
- **Target `make clone-all`**: Automatización que descarga el ecosistema completo con un único comando.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Un desarrollador puede clonar el ecosistema completo de repositorios ejecutando un único comando (`make clone-all` o `pwsh scripts/clone-all.ps1`) en menos de 2 minutos sobre una conexión estándar.
- **SC-002**: Un desarrollador nuevo puede clonar únicamente `shoplab-helpdesk` y levantar el 100 % del sistema funcional ejecutando `docker compose -f infra/docker-compose.yml up -d` sin requerir la presencia local de los códigos fuente de frontend ni de ingest.
- **SC-003**: El 100 % de los servicios de aplicación en `docker-compose.yml` apuntan a imágenes GHCR con versiones fijadas en `.env` sin ninguna referencia a `:latest`.
- **SC-004**: Al activar `docker-compose.override.yml` para un servicio específico (p. ej. `frontend`), Docker Compose compila exitosamente desde la carpeta hermana sin requerir que los otros servicios estén clonados.
- **SC-005**: El `README.md` documenta de forma inequívoca el flujo de trabajo multi-repo, y un desarrollador puede seguirlo de punta a punta sin asistencia externa.
- **SC-006**: La ejecución de `docker compose -f infra/docker-compose.yml config` valida sintácticamente sin errores tanto con el archivo base como con la plantilla de override aplicada.

---

## Assumptions

- **Disponibilidad de imágenes en GHCR**: Se asume que LAB-109 ha publicado (o publicará en sus pipelines de CI) las primeras versiones semánticas de `shoplab-ingest` y `shoplab-frontend` en `ghcr.io/leandroamariles/*`. Mientras tanto, las variables de entorno permiten sobreescribir la imagen o usar el override de compilación local.
- **Organización de directorios**: Se asume la convención estándar de carpetas hermanas donde todos los repositorios de ShopLab se clonan dentro de un mismo directorio raíz de proyectos (p. ej., `~/IdeaProjects/` o `C:\Users\<user>\IdeaProjects\`).
- **Herramientas locales**: Se asume que el desarrollador cuenta con Docker y Docker Compose v2 instalados, además de `git` y `make` (o PowerShell en Windows).
- **Backend transicional**: Mientras los módulos `chat`, `retrieval` y `eval-service` completan su extracción en F5 (LAB-59, LAB-129, LAB-130), el contenedor `backend` puede ejecutarse a partir de la imagen construida de `shoplab-backend` o ejecutarse directamente en el host mediante `./mvnw spring-boot:run` conectándose a la infraestructura de Compose.
- **Acceso a GHCR**: Las imágenes públicas de GHCR no requieren autenticación previa para `docker pull`; si la organización requiere imágenes privadas, se documentará el uso de `GITHUB_TOKEN` o PAT con `read:packages`.
