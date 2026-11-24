# Feature Specification: Separar ingest, eval y frontend en repos propios

**Feature Branch**: `feature/LAB-109-separate-ingest-eval-frontend`

**Created**: 2026-11-24

**Status**: Draft

**Input**: User description: "LAB-109 (F5-17) Como desarrollador, quiero que `ingest`, `eval` y el frontend vivan en sus propios repos, para que cada uno tenga su ciclo de vida, su versión y su pipeline. Criterios de aceptación: Repos `shoplab-ingest`, `shoplab-eval` y `shoplab-frontend` creados desde la plantilla de LAB-103, conservando el historial con `git filter-repo --subdirectory-filter`; Cada repo usa los workflows reutilizables de `shoplab-ci`, publica su imagen con el mismo nombre en GHCR y tiene su propio release-please y Dependabot; Cada repo consume los contratos como paquete versionado de LAB-108 (cliente Angular por npm, modelos Python por `uv`); El deploy de cada repo actualiza su imagen en `shoplab-infra` y dev queda Synced y Healthy; Las carpetas se eliminan de `shoplab-helpdesk` con un enlace al repo nuevo en el README. Depende de: LAB-103, LAB-108."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Extracción de repositorios conservando historial con `git filter-repo` (Priority: P1)

Como desarrollador e ingeniero de mantenimiento, quiero extraer las carpetas `ingest/`, `eval/` y `frontend/` del monorepo a tres repositorios independientes de GitHub (`shoplab-ingest`, `shoplab-eval` y `shoplab-frontend`) conservando el historial completo de commits mediante `git filter-repo --subdirectory-filter`, e integrando las convenciones y estructura base de la plantilla de `LAB-103`, para que cada componente disponga de su propio repositorio limpio, con los archivos en la raíz y manteniendo la trazabilidad histórica de autores, mensajes y cambios.

**Why this priority**: Es la base física e irremplazable de la historia. Perder el historial de commits impediría rastrear decisiones pasadas, culpables (`git blame`) y evolución de código. Sin los tres repositorios creados e inicializados con su árbol de archivos en la raíz, no es posible configurar pipelines, contratos ni despliegues.

**Independent Test**: Clonar en un entorno limpio cada uno de los tres repositorios creados (`shoplab-ingest`, `shoplab-eval`, `shoplab-frontend`), ejecutar `git log --stat` comprobando que existen los commits históricos correspondientes a la subcarpeta original con sus fechas y autores intactos, verificar que la raíz del repositorio contiene directamente los archivos de configuración (`pyproject.toml` o `package.json`) y no una carpeta anidada, y ejecutar las suites de pruebas locales (`uv run pytest` o `npm test`) demostrando que los proyectos construyen y prueban de forma autónoma.

**Acceptance Scenarios**:

1. **Given** el monorepo `shoplab-helpdesk` con historial de cambios en `ingest/`, **When** se ejecuta el proceso de migración con `git filter-repo --subdirectory-filter ingest`, **Then** el repositorio resultante `shoplab-ingest` contiene en su raíz el código del servicio FastAPI (`app/`, `tests/`, `pyproject.toml`, `Dockerfile`), preservando los commits, autores y marcas de tiempo originales relacionados con esa ruta.
2. **Given** el monorepo con historial en `eval/`, **When** se ejecuta `git filter-repo --subdirectory-filter eval`, **Then** el repositorio `shoplab-eval` contiene en su raíz la suite de evaluación (`src/shoplab_eval`, `tests/`, `golden.jsonl`, `pyproject.toml`), conservando el historial de cambios y pruebas de métricas RAG.
3. **Given** el monorepo con historial en `frontend/`, **When** se ejecuta `git filter-repo --subdirectory-filter frontend`, **Then** el repositorio `shoplab-frontend` contiene en su raíz la aplicación Angular completa (`src/`, `package.json`, `angular.json`, `Dockerfile`, `nginx.conf`), conservando el historial de commits de interfaz de usuario.
4. **Given** cualquiera de los tres repositorios extraídos, **When** se inspecciona su estructura respecto a la plantilla de `LAB-103`, **Then** cuenta con `.gitignore` adecuado a su tecnología, `.github/` base, configuración de linter/formateador y documentación de bienvenida en `README.md`.
5. **Given** la rama principal de cada uno de los tres nuevos repositorios, **When** se revisa la configuración en GitHub, **Then** cuenta con el ruleset de protección de rama `main` equivalente al estándar corporativo (exige pull request, checks de CI en verde y prohíbe force-push y eliminación de rama).

---

### User Story 2 - Pipelines de CI desacoplados con `shoplab-ci`, GHCR, `release-please` y Dependabot (Priority: P1)

Como desarrollador, quiero que cada nuevo repositorio use los workflows reutilizables de `shoplab-ci` (`python-service@v1` para `shoplab-ingest` y `shoplab-eval`, y `angular-app@v1` para `shoplab-frontend`), empaquete y publique su imagen de contenedor en GitHub Container Registry (`ghcr.io/leandroamariles/<servicio>`), gestione su ciclo de versiones con `release-please` y mantenga sus dependencias actualizadas mediante Dependabot, para que cada servicio tenga un ciclo de vida, versionado y control de calidad 100 % autónomo.

**Why this priority**: Es el objetivo central del desacoplamiento: eliminar el pipeline monolítico donde un cambio en frontend disparaba validaciones de Python o viceversa, y dotar a cada equipo o componente de releases independientes y automatizadas con firma criptográfica, SBOM y puertas de calidad homogéneas.

**Independent Test**: Abrir un Pull Request en cada uno de los tres repositorios verificando que se ejecutan los checks de CI reutilizables de `shoplab-ci` (linting, tests con cobertura ≥80 %, escaneo Trivy y chequeo de políticas sin `:latest` ni `CAMBIA-ESTE-OWNER`). Fusionar a `main`, validar que `release-please` crea o actualiza el PR de release, fusionar dicho PR y verificar en GHCR la presencia de la imagen firmada con Cosign y con tag `:vX.Y.Z` y `:sha-<commit>`.

**Acceptance Scenarios**:

1. **Given** un Pull Request en `shoplab-ingest` o `shoplab-eval`, **When** corre la CI, **Then** invoca el workflow reutilizable `python-service.yml@v1` de `shoplab-ci`, ejecutando `uv sync`, `ruff check`, pruebas con `pytest` exigiendo cobertura ≥80 %, análisis de vulnerabilidades Trivy (fs e imagen) y rechazo de políticas inseguras.
2. **Given** un Pull Request en `shoplab-frontend`, **When** corre la CI, **Then** invoca el workflow reutilizable `angular-app.yml@v1` de `shoplab-ci`, ejecutando instalación limpia, `ng lint`, tests unitarios con cobertura, build de producción y escaneo Trivy.
3. **Given** un merge en la rama `main` de `shoplab-ingest`, `shoplab-eval` o `shoplab-frontend`, **When** corre el workflow de imágenes, **Then** construye y publica la imagen OCI en `ghcr.io/leandroamariles/<repo>` etiquetada con `:sha-<commit>`, genera el SBOM y firma criptográficamente el digest con Cosign (modo keyless).
4. **Given** commits bajo la convención Conventional Commits en `main` de cualquiera de los repositorios, **When** corre el workflow de release, **Then** `release-please` genera o actualiza un PR de release con incremento semántico y `CHANGELOG.md` automatizado.
5. **Given** la fusión de un PR de release en cualquiera de los tres repositorios, **When** se crea el tag `vX.Y.Z`, **Then** se dispara la publicación de la imagen en GHCR con el tag inmutable `:vX.Y.Z`.
6. **Given** la configuración de dependencias de cada repositorio, **When** se inspecciona `.github/dependabot.yml`, **Then** se encuentra configurado el ecosistema correspondiente (`uv`/pip para Python, `npm` para Frontend, y `github-actions` en todos) con revisiones semanales automáticas.

---

### User Story 3 - Consumo de contratos versionados de `shoplab-contracts` (Priority: P1)

Como desarrollador, quiero que `shoplab-frontend`, `shoplab-ingest` y `shoplab-eval` consuman los contratos de API y esquemas exclusivamente como paquetes versionados provistos por `shoplab-contracts` (LAB-108) —mediante paquete npm para Angular y mediante paquete Python instalado con `uv` para los servicios Python—, para garantizar que ningún repositorio depende de código fuente compartido, rutas relativas ni submódulos entre servicios.

**Why this priority**: El desacoplamiento arquitectónico (ADR-0015, ADR-0031) prohíbe las carpetas compartidas y las dependencias de código entre repositorios. Los contratos publicados formalmente como paquetes con versión inmutable son el único mecanismo autorizado de integración de tipos y clientes entre frontend y backend.

**Independent Test**: Verificar que `package.json` en `shoplab-frontend` declara la dependencia `@leandroamariles/shoplab-contracts: X.Y.Z` descargada de GitHub Packages vía `.npmrc` y que la aplicación compila usando sus tipos y clientes; verificar que `pyproject.toml` en `shoplab-ingest` y `shoplab-eval` declara la dependencia de contratos vía `uv` apuntando a `@vX.Y.Z` de `shoplab-contracts` y que los tests ejecutan sin acceder a archivos fuera del propio repositorio.

**Acceptance Scenarios**:

1. **Given** el repositorio `shoplab-frontend`, **When** se revisa su `package.json` y `.npmrc`, **Then** declara la dependencia `@leandroamariles/shoplab-contracts` con una versión exacta (p. ej. `1.2.0`), configurando el registro `@leandroamariles:registry=https://npm.pkg.github.com` con autenticación por token en CI.
2. **Given** el repositorio `shoplab-ingest`, **When** se revisa `pyproject.toml`, **Then** consume los modelos de datos y esquemas de OpenAPI desde el paquete versionado de `shoplab-contracts` instalado mediante `uv` (referencia inmutable por tag git `@vX.Y.Z`), sin referencias a rutas locales fuera del repositorio.
3. **Given** el repositorio `shoplab-eval`, **When** se revisan sus dependencias, **Then** cualquier estructura de datos o contrato evaluado se resuelve a través de `shoplab-contracts` o interfaces públicas del sistema, sin importar módulos internos de backend.
4. **Given** la publicación de una nueva versión de contratos en `shoplab-contracts`, **When** Dependabot analiza `shoplab-frontend`, **Then** genera automáticamente un Pull Request de actualización de la versión del paquete npm.

---

### User Story 4 - Despliegue continuo GitOps hacia `shoplab-infra` y convergencia en `dev` (Priority: P1)

Como desarrollador u operador de plataforma, quiero que cada release o push a `main` de `shoplab-ingest`, `shoplab-eval` y `shoplab-frontend` ejecute el job reutilizable de despliegue continuo actualizando la imagen en `shoplab-infra` mediante `kustomize edit set image`, y que Argo CD sincronice los manifiestos en el clúster `dev` resultando en estado `Synced` y `Healthy`, para que los tres servicios queden operando en el clúster sin interacción manual.

**Why this priority**: El criterio de aceptación exige explícitamente: «El deploy de cada repo actualiza su imagen en `shoplab-infra` y dev queda Synced y Healthy». Sin este flujo automatizado, el desacoplamiento generaría rotura en la entrega continua y el entorno de integración dejaría de reflejar las últimas versiones.

**Independent Test**: Realizar una publicación de versión en uno de los repositorios extraídos, observar la ejecución del job de deploy con `shoplab-deploy`, verificar que en `shoplab-infra` se genera un commit en `main` actualizando la etiqueta de la imagen correspondiente en los manifiestos de `dev`, y verificar con `kubectl` o la CLI/UI de Argo CD que la aplicación pasa a `Synced` y los pods correspondientes se encuentran `Healthy` y respondiendo a los endpoints de liveness/readiness.

**Acceptance Scenarios**:

1. **Given** la publicación exitosa de una imagen en `shoplab-ingest` o `shoplab-frontend`, **When** se dispara el job de deploy reutilizable de `shoplab-ci`, **Then** clona `shoplab-infra` utilizando el token de la GitHub App `shoplab-deploy` y actualiza la imagen mediante `kustomize edit set image` en el overlay de `dev`.
2. **Given** la actualización del manifiesto en `shoplab-infra`, **When** corre el check de políticas en `shoplab-infra`, **Then** valida que la imagen tiene un tag inmutable (sin `:latest`) y que los manifiestos Kustomize renderizan válidos con `kustomize build`.
3. **Given** el commit aplicado en `main` de `shoplab-infra`, **When** Argo CD efectúa la reconciliación en el clúster `dev`, **Then** las aplicaciones (`ingest`, `frontend`, y si corresponde `eval`) alcanzan el estado `Synced` y sus recursos en Kubernetes quedan en estado `Healthy`.
4. **Given** la arquitectura desplegada en `dev`, **When** un usuario o servicio invoca los endpoints públicos del frontend o ingest, **Then** responden correctamente a través del Ingress y las rutas de red configuradas en Kubernetes.

---

### User Story 5 - Retiro de carpetas en `shoplab-helpdesk` y actualización del monorepo (Priority: P2)

Como desarrollador que trabaja en `shoplab-helpdesk`, quiero que las carpetas `ingest/`, `eval/` y `frontend/` sean eliminadas de este repositorio monorepo, que el `README.md` principal enlace de manera destacada a los tres nuevos repositorios independientes, y que los workflows de CI/CD del monorepo sean actualizados para retirar sus referencias, para mantener el repositorio limpio, evitar duplicidad de código fuente y garantizar que los pipelines de `shoplab-helpdesk` sigan pasando en verde.

**Why this priority**: Dejar copias muertas o desactualizadas en `shoplab-helpdesk` generaría confusión entre los desarrolladores («¿dónde edito el frontend o el ingest?») y provocaría fallos en los workflows de CI del monorepo que intentasen compilar o escanear rutas que ya no deben pertenecer a él.

**Independent Test**: Comprobar en `shoplab-helpdesk` que no existen las carpetas `ingest/`, `eval/` ni `frontend/`; verificar que `README.md` contiene la tabla/sección de enlaces hacia `shoplab-ingest`, `shoplab-eval` y `shoplab-frontend`; ejecutar `git status` y los workflows de CI en GitHub Actions de `shoplab-helpdesk` comprobando que finalizan en verde sin buscar ni filtrar rutas eliminadas.

**Acceptance Scenarios**:

1. **Given** los tres repositorios nuevos operando de forma autónoma en GitHub, **When** se aplica el cambio en `shoplab-helpdesk`, **Then** se eliminan por completo los directorios `ingest/`, `eval/` y `frontend/` mediante `git rm -r`.
2. **Given** el archivo `README.md` de `shoplab-helpdesk`, **When** se revisa la sección de estructura del proyecto y arquitectura, **Then** documenta explícitamente que `ingest`, `eval` y `frontend` han sido extraídos a sus propios repositorios e incluye los hipervínculos directos a `https://github.com/leandroAmariles/shoplab-ingest`, `https://github.com/leandroAmariles/shoplab-eval` y `https://github.com/leandroAmariles/shoplab-frontend`.
3. **Given** los workflows de GitHub Actions en `shoplab-helpdesk` (`ci.yml`, `images.yml`, etc.), **When** se ejecutan ante un PR en `shoplab-helpdesk`, **Then** `dorny/paths-filter` y las matrices de build ya no incluyen `ingest`, `eval` ni `frontend`, y todos los jobs de backend y tooling restante terminan exitosamente.
4. **Given** las referencias en `infra/docker-compose.yml` local o scripts de desarrollo en `shoplab-helpdesk`, **When** se actualizan para el modo monolito/local, **Then** quedan documentadas las instrucciones para clonar los repositorios hermanos o consumir sus imágenes publicadas en GHCR.

---

### Edge Cases

- **¿Qué ocurre si un desarrollador intenta hacer commits en `ingest/`, `eval/` o `frontend/` en `shoplab-helpdesk` tras la extracción?** La eliminación física de las carpetas en `main` previene la confusión; además, el `README.md` advertirá de forma destacada sobre la nueva ubicación de los repositorios.
- **¿Qué ocurre con las etiquetas (tags) históricas de `shoplab-helpdesk` durante el `git filter-repo`?** Si no se filtran, los nuevos repositorios podrían heredar tags de versiones del monorepo antiguo (como `v0.26.0`). La extracción debe limpiar o reescribir los tags para que cada nuevo repositorio arranque su histórico de tags desde `v0.1.0` o `v1.0.0` gestionado por su propio `release-please`.
- **¿Qué ocurre con archivos compartidos de configuración en la raíz del monorepo (`.trivyignore`, `.editorconfig`, licencias)?** `git filter-repo --subdirectory-filter` únicamente extrae el contenido de la subcarpeta. Cada nuevo repositorio debe recibir en su commit inicial o desde la plantilla los archivos indispensables de configuración (`.gitignore`, `.editorconfig`, `.trivyignore`, licencias y plantillas de PR/issue).
- **¿Cómo se gestiona el rol dual de `eval` entre el tooling de evaluación Python y `eval-service` Java?** Según ADR-0031 (punto 6), `shoplab-eval` es el repositorio exclusivo para el tooling de evaluación en Python con `uv` (RAGAS, DeepEval) y el dataset golden (`golden.jsonl`), mientras que la persistencia inmutable de corridas y API administrativa pertenece a `shoplab-eval-service` (Java). Esta historia extrae el contenido de `eval/` hacia `shoplab-eval`.
- **¿Qué ocurre con el acceso a GitHub Packages desde `shoplab-frontend` durante `npm install`?** En CI, el workflow debe inyectar el secreto `GITHUB_TOKEN` (o un PAT con alcance `read:packages`) configurando el archivo `.npmrc` para resolver `@leandroamariles/shoplab-contracts`.
- **¿Qué ocurre con los modelos de contratos en `shoplab-ingest` instalados vía `uv`?** Si `shoplab-contracts` es un repositorio privado o público en GitHub, `uv` requiere autenticación si es privado (`git+https://${GITHUB_TOKEN}@github.com/...`) o instalación directa por tag `@vX.Y.Z` si es público. El plan debe prever el mecanismo de resolución de credenciales en CI y local.
- **¿Qué ocurre con los despliegues en `shoplab-infra` si el clúster se encuentra temporalmente detenido o en mantenimiento?** El job de despliegue en CI actualiza el manifiesto git en `shoplab-infra` de forma desacoplada; una vez que el clúster se inicie o se reactive la sincronización de Argo CD, el estado deseado en git se aplicará automáticamente alcanzando `Synced` y `Healthy`.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Deben existir tres repositorios independientes creados en GitHub bajo la cuenta `leandroAmariles`: `shoplab-ingest`, `shoplab-eval` y `shoplab-frontend`.
- **FR-002**: La extracción del código fuente de `ingest/`, `eval/` y `frontend/` MUST realizarse utilizando `git filter-repo --subdirectory-filter <directorio>`, garantizando la preservación completa del historial de commits, fechas y autores asociados a cada componente.
- **FR-003**: En cada repositorio extraído, los archivos y configuraciones MUST ubicarse directamente en la raíz del árbol de trabajo (no dentro de subcarpetas anidadas con el nombre antiguo).
- **FR-004**: Cada uno de los tres nuevos repositorios MUST adoptar la estructura de configuración y calidad provista por la plantilla y estándares de `LAB-103`, incluyendo `.gitignore`, configuración de linters, formato de código y metadatos de proyecto.
- **FR-005**: Cada uno de los tres nuevos repositorios MUST implementar un workflow de CI en `.github/workflows/ci.yml` que invoque mediante `workflow_call` los workflows reutilizables de `shoplab-ci` (`python-service.yml@v1` para `shoplab-ingest` y `shoplab-eval`, y `angular-app.yml@v1` para `shoplab-frontend`).
- **FR-006**: Los workflows de CI de los tres repositorios MUST aplicar todas las puertas de calidad obligatorias: linters (Ruff / ESLint), suite de pruebas unitarias con cobertura mínima de líneas ≥80 %, escaneo de seguridad Trivy (fs e imagen en severidades HIGH y CRITICAL) y verificación de políticas (prohibición de `:latest` y textos de plantilla sin sustituir).
- **FR-007**: Cada uno de los tres repositorios MUST construir y publicar su imagen de contenedor OCI en GitHub Container Registry (`ghcr.io/leandroamariles/<nombre-repo>`) asignando tags inmutables por commit (`:sha-<commit>`) y por versión semántica (`:vX.Y.Z`).
- **FR-008**: Cada imagen publicada en GHCR MUST incorporar su SBOM y firma criptográfica keyless mediante Cosign generada con la identidad OIDC de GitHub Actions.
- **FR-009**: Cada uno de los tres repositorios MUST integrar `release-please` para la gestión automatizada del versionado semántico y la generación continua de `CHANGELOG.md` a partir de Conventional Commits.
- **FR-010**: Cada uno de los tres repositorios MUST configurar `.github/dependabot.yml` para gestionar actualizaciones automáticas de dependencias del ecosistema correspondiente (`uv`/pip, `npm` y `github-actions`).
- **FR-011**: El repositorio `shoplab-frontend` MUST consumir los contratos y clientes tipados exclusivamente mediante el paquete versionado `@leandroamariles/shoplab-contracts` publicado en GitHub Packages por LAB-108, configurando `.npmrc` adecuadamente.
- **FR-012**: Los repositorios `shoplab-ingest` y `shoplab-eval` MUST consumir los modelos y esquemas de contratos como paquete Python versionado provisto por `shoplab-contracts` (LAB-108) mediante `uv`, sin depender de archivos fuera de su propio repositorio.
- **FR-013**: Cada uno de los tres repositorios MUST integrar el job reutilizable de despliegue continuo de `shoplab-ci` (o acción equivalente con la GitHub App `shoplab-deploy`) que actualice los manifiestos del entorno `dev` en `shoplab-infra` mediante `kustomize edit set image`.
- **FR-014**: La aplicación de los cambios en `shoplab-infra` MUST culminar con la reconciliación exitosa de Argo CD en el clúster `dev`, alcanzando y manteniendo el estado `Synced` y `Healthy` para los servicios correspondientes.
- **FR-015**: Las carpetas `ingest/`, `eval/` y `frontend/` MUST ser eliminadas por completo del repositorio `shoplab-helpdesk`.
- **FR-016**: El archivo `README.md` de `shoplab-helpdesk` MUST actualizarse con una sección visible que indique la extracción de los componentes e incluya enlaces hipertexto a los nuevos repositorios `shoplab-ingest`, `shoplab-eval` y `shoplab-frontend`.
- **FR-017**: Los workflows de GitHub Actions en `shoplab-helpdesk` (`ci.yml`, `images.yml`, etc.) MUST ser actualizados para eliminar filtros de ruta, jobs y matrices relacionados con `ingest`, `eval` y `frontend`, garantizando que la CI de `shoplab-helpdesk` permanezca en verde.
- **FR-018**: Cada nuevo repositorio MUST contar con reglas de protección en su rama principal `main` (bloqueo de push directo, obligatoriedad de PR, checks de CI requeridos e historial lineal).
- **FR-019**: La documentación de arquitectura en `docs/` de `shoplab-helpdesk` MUST reflejar el nuevo mapa de repositorios del ecosistema desacoplado conforme a ADR-0031.
- **FR-020**: Quedan excluidos del alcance de esta historia la extracción de los módulos backend Java (`chat-service`, `retrieval-service`, `eval-service`), que se ejecutarán en sus historias dedicadas de F5 (LAB-59, LAB-129, LAB-130).

---

### Key Entities

- **`shoplab-ingest`**: Repositorio dedicado para el microservicio de ingesta, parseo y chunking de documentos en Python/FastAPI.
- **`shoplab-eval`**: Repositorio dedicado para el dataset golden y el motor de evaluación de calidad RAG en Python con `uv` (RAGAS, DeepEval).
- **`shoplab-frontend`**: Repositorio dedicado para la aplicación web de interfaz de usuario y administración en Angular.
- **`shoplab-ci`**: Repositorio central de integración continua que aloja los workflows reutilizables invocados por los tres nuevos repositorios.
- **`shoplab-contracts`**: Repositorio central de especificaciones de contratos que distribuye los paquetes versionados npm y Python consumidos por los repositorios extraídos.
- **`shoplab-infra`**: Repositorio de GitOps que contiene los manifiestos Kustomize y configuraciones de Argo CD para el clúster `dev`.
- **`git filter-repo`**: Herramienta especializada de Git utilizada para reescribir el historial y extraer subdirectorios a repositorios independientes sin pérdida de datos históricos.
- **Argo CD Application (`dev`)**: Objeto de Kubernetes en `shoplab-infra` que supervisa y sincroniza el estado de los despliegues de cada servicio en el entorno de desarrollo.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Los tres repositorios `shoplab-ingest`, `shoplab-eval` y `shoplab-frontend` están creados y accesibles en GitHub bajo `leandroAmariles`, con el 100 % del historial de commits de sus respectivas carpetas preservado y visible con `git log`.
- **SC-002**: El 100 % de los archivos en los tres nuevos repositorios se encuentran ubicados en la raíz y subcarpetas naturales del proyecto, sin carpetas anidadas residuales.
- **SC-003**: Cada repositorio ejecuta su pipeline de CI delegando en `shoplab-ci` y alcanza una cobertura de pruebas unitarias ≥80 % (JaCoCo/pytest/Karma-Vitest) sin vulnerabilidades HIGH ni CRITICAL reportadas por Trivy.
- **SC-004**: Las imágenes de contenedor de los tres componentes se publican exitosamente en `ghcr.io/leandroamariles/<servicio>` con tags inmutables `:sha-<commit>` y `:vX.Y.Z`, con SBOM y firma criptográfica verificable con `cosign verify`.
- **SC-005**: `release-please` y Dependabot se encuentran activos y operativos en los tres repositorios, con PRs de actualización y versionado semántico funcionando.
- **SC-006**: `shoplab-frontend` resuelve `@leandroamariles/shoplab-contracts` vía npm desde GitHub Packages, y `shoplab-ingest` y `shoplab-eval` consumen los contratos por `uv` sin ninguna dependencia local ni ruta relativa externa.
- **SC-007**: El pipeline de deploy actualiza automáticamente `shoplab-infra` y las aplicaciones en el clúster `dev` alcanzan el estado `Synced` y `Healthy` en Argo CD en menos de 5 minutos tras el despliegue.
- **SC-008**: Las carpetas `ingest/`, `eval/` y `frontend/` están eliminadas de `shoplab-helpdesk`, el README contiene los enlaces a los nuevos repositorios y el 100 % de los workflows de CI en `shoplab-helpdesk` finalizan en verde.

---

## Assumptions

- **Disponibilidad de dependencias previas (LAB-103 y LAB-108)**: Se asume que los workflows reutilizables de `shoplab-ci` (`python-service@v1` y `angular-app@v1`) provistos por LAB-103 y los paquetes versionados de contratos (`@leandroamariles/shoplab-contracts` en npm y paquete Python para `uv`) provistos por LAB-108 se encuentran disponibles o con interfaces contractualmente estables para ser consumidos por los repositorios extraídos.
- **Herramienta `git-filter-repo`**: Se asume el uso de `git-filter-repo` (recomendada oficialmente por Git frente a `git filter-branch`) ejecutada sobre clones limpios para realizar la partición sin degradación del repositorio original.
- **Estrategia de repositorios en GitHub**: Los tres repositorios se crean bajo la cuenta u organización `leandroAmariles` con permisos adecuados para publicación en GHCR (`packages: write`) y actualización en `shoplab-infra` mediante la GitHub App `shoplab-deploy`.
- **Ámbito exclusivo de F5-17**: Esta historia se concentra estrictamente en la extracción de `ingest`, `eval` y `frontend`. Los servicios backend Java (`retrieval`, `chat`, `eval-service`) continúan en el monolito modular de `shoplab-helpdesk` hasta sus respectivas historias de extracción (LAB-129, LAB-59, LAB-130) según la hoja de ruta de ADR-0031.
- **Autenticación en GitHub Packages**: La configuración de `.npmrc` y credenciales de `uv` utilizará `GITHUB_TOKEN` en entornos de CI y PAT personal con scope `read:packages` para desarrollo local según el estándar establecido en LAB-108.
