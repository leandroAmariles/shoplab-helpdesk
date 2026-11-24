# Feature Specification: Workflows reutilizables y plantilla de servicio (shoplab-ci y shoplab-service-template)

**Feature Branch**: `feature/LAB-103-reusable-workflows-service-template`

**Created**: 2026-11-24

**Status**: Draft

**Input**: User description: "LAB-103 (F5-12) Como desarrollador, quiero un pipeline común y una plantilla de repo, para que crear un servicio nuevo en su propio repo tome minutos y todos sigan las mismas reglas. Criterios de aceptación: Repo `shoplab-ci` con workflows reutilizables (`workflow_call`) versionados por tag (`@v1`): `java-service`, `python-service` y `angular-app`; Cada workflow corre build, tests con Testcontainers, JaCoCo ≥80 %, Qodana y Trivy (fs e imagen HIGH/CRITICAL), y las políticas que fallan con `:latest` o `CAMBIA-ESTE-OWNER`; Imagen por servicio en `ghcr.io/leandroamariles/<servicio>` con tags `:sha` y `:vX.Y.Z`, firmada con cosign y con SBOM; Job de deploy reutilizable: `kustomize edit set image` sobre la base del servicio y commit a `shoplab-infra` con un token de alcance mínimo (solo ese repo); Repo plantilla `shoplab-service-template` (GitHub template repository): `pom.xml` con `spring-boot-starter-parent`, Dockerfile, workflow que llama a `shoplab-ci`, release-please, Dependabot y dependencia fijada a `shoplab-contracts`; La plantilla trae el paquete `shared` de errores y correlación (`GlobalExceptionHandler`, `ResponseCodeEnum` con el prefijo del servicio, `X-Request-Id`), el migrador de Flyway de un esquema, las reglas de ArchUnit por capas y la configuración de resource server JWT lista para LAB-61; Regla: cada servicio nuevo o extraído nace en su propio repo desde la plantilla; ningún repo de servicio depende de código de otro, solo de los paquetes de contratos; Documentado en `docs/fases/` cómo crear un servicio desde la plantilla. Depende de: LAB-99, LAB-108. Va antes de LAB-104, LAB-107, LAB-129 (retrieval-service) y LAB-59 (chat-service). Se adelantó de F5 al bloque de extracción temprana (ADR-0029, LAB-128)."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Workflows reutilizables en `shoplab-ci` con puertas de calidad integradas (Priority: P1)

Como ingeniero de plataforma o desarrollador de un microservicio, quiero disponer de un repositorio
central `shoplab-ci` con workflows reutilizables de GitHub Actions invocables vía `workflow_call` y
versionados mediante tags inmutables (iniciando en `@v1`) para los tres tipos de artefactos del
proyecto (`java-service`, `python-service` y `angular-app`), asegurando que cualquier servicio ejecute
de manera homogénea compilación, pruebas unitarias e integración con Testcontainers, control de
cobertura JaCoCo ≥80 %, análisis estático con Qodana, escaneo de vulnerabilidades Trivy (sistema de
archivos e imagen con corte en HIGH/CRITICAL) y comprobación estricta de políticas de seguridad.

**Why this priority**: Es la base de gobernanza de integración continua (CI) para todo el ecosistema
desacoplado. Sin este repositorio y sus flujos reutilizables, cada nuevo microservicio extraído o
creado tendría que duplicar cientos de líneas de configuración de CI, provocando divergencias en las
puertas de calidad y elevando el riesgo de brechas de seguridad o caídas de cobertura.

**Independent Test**: Invocar desde un repositorio consumidor de prueba cada uno de los tres workflows
(`java-service@v1`, `python-service@v1` y `angular-app@v1`) y verificar que: (1) ante un código
correcto se ejecutan y superan los pasos de build, tests, cobertura ≥80 %, Qodana y Trivy; (2) ante un
fallo de cobertura (<80 %), una vulnerabilidad HIGH/CRITICAL sin mitigar, un tag de imagen `:latest` o
la presencia del texto prohibido `CAMBIA-ESTE-OWNER`, el workflow falla y bloquea el merge.

**Acceptance Scenarios**:

1. **Given** un repositorio de servicio Java invocando `shoplab-ci/.github/workflows/java-service.yml@v1`,
   **When** se abre o actualiza un Pull Request, **Then** el workflow ejecuta la compilación con Java 21,
   corre pruebas unitarias e integración levantando dependencias con Testcontainers, y verifica que la
   cobertura de líneas calculada por JaCoCo sea mayor o igual al 80 %, fallando el job si no alcanza
   dicho umbral.
2. **Given** la ejecución del workflow `java-service`, **When** finalizan las pruebas, **Then** corre el
   análisis de calidad de código con Qodana y el escaneo de dependencias y código fuente con Trivy
   (`scan-type: fs`, severidades HIGH y CRITICAL), fallando si existen hallazgos no remediados ni
   excluidos justificadamente en `.trivyignore`.
3. **Given** un repositorio Python (p. ej. `ingest` o `retrieval-service`) invocando
   `python-service.yml@v1`, **When** corre el workflow, **Then** instala dependencias con `uv`, ejecuta
   linters (Ruff), tests con pytest, escaneo con Trivy y evaluación de políticas.
4. **Given** un repositorio de aplicación web invocando `angular-app.yml@v1`, **When** corre el
   workflow, **Then** ejecuta la compilación de Angular, tests unitarios con Karma/Jest con cobertura,
   linter de frontend y escaneo de vulnerabilidades npm con Trivy.
5. **Given** cualquier flujo reutilizable de `shoplab-ci`, **When** se detecta una referencia a imagen
   con tag `:latest` o la cadena literal `CAMBIA-ESTE-OWNER` en los archivos de configuración o
   manifiestos, **Then** el paso de verificación de políticas falla de inmediato con un mensaje de error
   explícito.
6. **Given** una actualización en `shoplab-ci`, **When** se etiqueta una release con el tag `v1` (o
   `v1.x.y`), **Then** los servicios consumidores anclados a `@v1` resuelven la versión estable sin
   modificar su sintaxis de llamada.

---

### User Story 2 - Construcción, escaneo, SBOM y firma criptográfica de imágenes OCI con Cosign (Priority: P1)

Como desarrollador, quiero que los workflows de publicación empaqueten automáticamente la imagen de
contenedor bajo el registro `ghcr.io/leandroamariles/<servicio>` asignando tags inmutables de commit
(`:sha-<commit>`) y de versión semántica (`:vX.Y.Z`), generen su lista de materiales de software (SBOM)
y firmen criptográficamente el digest de la imagen mediante Cosign en modalidad keyless, garantizando
la integridad y trazabilidad de la cadena de suministro de software (DevSecOps).

**Why this priority**: La extracción a microservicios distribuidos requiere que cada componente
distribuya artefactos inmutables, auditables y verificables en el clúster. La firma criptográfica y el
SBOM previenen la adulteración de contenedores y cumplen con los estándares de seguridad corporativa.

**Independent Test**: Ejecutar el flujo de publicación tras fusionar un tag `vX.Y.Z` en un servicio de
prueba, inspeccionar en GitHub Packages (GHCR) que existe la imagen
`ghcr.io/leandroamariles/<servicio>:vX.Y.Z`, verificar que contiene el SBOM adjunto y ejecutar
`cosign verify` contra el certificado OIDC de GitHub Actions confirmando que la firma es válida.

**Acceptance Scenarios**:

1. **Given** un push a la rama principal `main` de un servicio, **When** concluye la fase de CI,
   **Then** se genera la imagen con el tag inmutable del commit (`:sha-<commit>`) sin sobrescribir tags
   de versiones de producción.
2. **Given** un evento de release con tag semántico `vX.Y.Z`, **When** el workflow compila el
   contenedor, **Then** valida previamente que el tag `vX.Y.Z` no exista en GHCR para impedir
   sobrescrituras de versiones ya publicadas.
3. **Given** la construcción exitosa de la imagen de contenedor, **When** finaliza `docker/build-push-action`,
   **Then** se adjuntan las atestaciones de procedencia (provenance `mode=max`) y el SBOM en formato
   estándar (SPDX / CycloneDX).
4. **Given** el digest inmutable (`sha256:...`) de la imagen publicada, **When** se ejecuta el paso de
   firma con Cosign, **Then** se realiza la firma keyless utilizando la identidad OIDC del workflow
   (`https://token.actions.githubusercontent.com`) sin requerir claves privadas persistentes ni estáticas.
5. **Given** la imagen firmada, **When** un cliente ejecuta `cosign verify` con la expresión regular
   correspondiente al repositorio de origen, **Then** la verificación criptográfica resulta exitosa.

---

### User Story 3 - Job de despliegue reutilizable GitOps sobre `shoplab-infra` (Priority: P1)

Como desarrollador, quiero que `shoplab-ci` provea un job reutilizable de despliegue continuo que
actualice la imagen del servicio en el repositorio `shoplab-infra` mediante `kustomize edit set image` y
efectúe el commit utilizando un token de alcance mínimo (acceso exclusivo a `shoplab-infra`),
garantizando la entrega continua hacia el entorno `dev` sin intervención humana ni elevación de privilegios.

**Why this priority**: Cierra el ciclo de GitOps desacoplado (ADR-0006, ADR-0012, LAB-81). Cada
microservicio debe poder promover su nueva versión en el clúster de Kubernetes actualizando el estado
deseado en `shoplab-infra`, sin tener acceso a repositorios ajenos ni permisos destructivos.

**Independent Test**: Invocar el job reutilizable de despliegue pasando el nombre del servicio, el tag de
versión recién publicado (`vX.Y.Z`) y el token efímero de GitHub App; comprobar que se genera un commit
en la rama `main` de `shoplab-infra` modificando únicamente `k8s/overlays/dev/kustomization.yaml` con la
imagen correcta, validando que no contiene tags `:latest` y verificando que el commit no expone secretos.

**Acceptance Scenarios**:

1. **Given** la publicación exitosa de la versión `vX.Y.Z` de un servicio, **When** se dispara el job
   reutilizable de despliegue, **Then** obtiene un token efímero de GitHub App con permisos restringidos
   exclusivamente a `shoplab-infra:contents:write`.
2. **Given** el repositorio `shoplab-infra` clonado en el job de despliegue, **When** se ejecuta el
   script de actualización, **Then** aplica `kustomize edit set image <servicio>=<registro>/<servicio>:<tag>`
   sobre la base y overlay del entorno `dev`.
3. **Given** la actualización del manifiesto `kustomization.yaml`, **When** se valida la salida con
   `kustomize build`, **Then** se comprueba que el render no utiliza `:latest` y cumple las políticas
   declaradas en `policy-check.sh` de `shoplab-infra`.
4. **Given** la validación en verde, **When** se realiza el push a `shoplab-infra`, **Then** se genera un
   commit trazable firmado con el autor `shoplab-deploy[bot]` indicando la versión, el SHA del servicio
   y la URL del run de CI.
5. **Given** un fallo durante la edición o verificación de políticas en `shoplab-infra`, **When** aborta
   el job, **Then** descarta los cambios locales en el workspace, enmascara tokens en los logs y finaliza
   con código de error descriptivo sin modificar la rama remota.

---

### User Story 4 - Repositorio plantilla `shoplab-service-template` configurado y desacoplado (Priority: P1)

Como desarrollador que necesita crear o extraer un nuevo microservicio, quiero instanciar un
repositorio de GitHub configurado como plantilla oficial (`shoplab-service-template`) que contenga la
estructura Maven base (`pom.xml` con `spring-boot-starter-parent`), `Dockerfile` multi-stage optimizado,
workflow de CI que delega en `shoplab-ci@v1`, versionado semántico con `release-please`, configuración de
Dependabot y la dependencia fijada a los paquetes de `shoplab-contracts`, para arrancar un servicio
productivo en cuestión de minutos siguiendo la arquitectura estándar.

**Why this priority**: Es la herramienta operativa clave para cumplir con la regla de oro de la
extracción temprana (ADR-0015, ADR-0031): «cada nuevo servicio nace en su propio repositorio desde la
plantilla; ningún servicio depende de código de otro, solo de contratos».

**Independent Test**: Crear un nuevo repositorio en GitHub pulsando «Use this template» desde
`shoplab-service-template`, parametrizar el nombre del servicio, ejecutar en local `mvn test` y
`docker build`, y verificar que el workflow invoca limpiamente a `shoplab-ci` pasando todos los checks.

**Acceptance Scenarios**:

1. **Given** el repositorio `shoplab-service-template` en GitHub, **When** se inspeccionan sus ajustes,
   **Then** está marcado explícitamente como "Template repository" (`is_template: true`).
2. **Given** el archivo `pom.xml` de la plantilla, **When** se revisan sus dependencias, **Then** utiliza
   como padre `spring-boot-starter-parent` (Spring Boot 4.x / Java 21), incluye el starter WebFlux
   reactivo, Actuator para métricas y salud, Testcontainers para PostgreSQL, y declara como dependencia
   fijada el artefacto de contratos de `shoplab-contracts`.
3. **Given** la configuración de dependencias de la plantilla, **When** se inspecciona el árbol de Maven,
   **Then** no existe ninguna dependencia hacia el monolito `shoplab-helpdesk` ni hacia código fuente de
   otros servicios (cero acoplamiento de dominio).
4. **Given** el archivo `.github/workflows/ci.yml` de la plantilla, **When** se procesa un cambio,
   **Then** delega la ejecución en `leandroamariles/shoplab-ci/.github/workflows/java-service.yml@v1`
   pasando los parámetros propios del servicio.
5. **Given** la plantilla, **When** se revisa el soporte de ciclo de vida de software, **Then** incluye
   `.github/release-please-config.json` y `.release-please-manifest.json` para versionado automático, y
   `.github/dependabot.yml` configurado para actualizar dependencias de Maven, GitHub Actions y el
   registro de GitHub Packages.

---

### User Story 5 - Paquete transversal base (`shared`), Flyway mono-esquema, ArchUnit y JWT Resource Server (Priority: P1)

Como desarrollador de un microservicio, quiero que la plantilla traiga incorporados los componentes
técnicos transversales sin lógica de negocio: el paquete base `shared` con `GlobalExceptionHandler`,
códigos de error tipados (`ResponseCodeEnum` con prefijo configurable), correlación `X-Request-Id`,
migrador Flyway acotado a un único esquema propio de base de datos, reglas de arquitectura ejecutables
con ArchUnit (separación estricta entre capas `domain`, `application` e `infrastructure`) y la
configuración de Spring Security OAuth2 Resource Server para validación de tokens JWT lista para LAB-61.

**Why this priority**: Evita el antipatrón de crear librerías comunes de dominio (ADR-0010, ADR-0015,
ADR-0031). En lugar de un JAR compartido que acople los ciclos de vida, la infraestructura técnica no de
negocio se inicializa como código fuente base en cada servicio y evoluciona con él de forma soberana.

**Independent Test**: Ejecutar la suite de pruebas unitarias y de arquitectura de la plantilla y
comprobar que: (1) ArchUnit falla si se introduce una violación de capas; (2) un request sin token o con
token inválido es rechazado por el resource server JWT; (3) un error no controlado retorna el sobre
estándar `ResponseDto` con código tipado y `X-Request-Id`; (4) Flyway crea y migra exclusivamente el
esquema configurado del servicio.

**Acceptance Scenarios**:

1. **Given** el paquete `shared` de la plantilla, **When** un controlador o use case lanza una excepción
   técnica o de negocio, **Then** `GlobalExceptionHandler` intercepta el error y retorna una respuesta
   HTTP con la estructura estándar `ResponseDto<T>` conteniendo la lista de errores `ErrorDto` (código,
   descripción y atributos).
2. **Given** el enum `ResponseCodeEnum` en la plantilla, **When** se inicializa un servicio, **Then**
   contiene los códigos comunes (`_GE_00001`, `_AUTH_00001`, etc.) parametrizables con el prefijo
   específico del microservicio (p. ej. `CHAT_GE_00001`, `RETR_GE_00001`).
3. **Given** una petición HTTP entrante, **When** atraviesa el filtro reactivo de correlación, **Then**
   propaga y asegura el encabezado `X-Request-Id` tanto en el contexto reactivo (`Reactor Context`) como
   en el contexto de logging (MDC) y en los encabezados de respuesta.
4. **Given** el módulo de base de datos de la plantilla, **When** arranca la aplicación con PostgreSQL,
   **Then** el migrador de Flyway opera estrictamente sobre el esquema asignado al servicio
   (`db/migration/<servicio>`), creando su propia tabla `<servicio>.flyway_schema_history` sin tocar
   otros esquemas.
5. **Given** las pruebas de arquitectura con ArchUnit, **When** se evalúan las reglas de capas,
   **Then** valida que:
   - El paquete `domain` no importe dependencias de Spring, Reactor, JPA ni librerías de infraestructura.
   - Las llamadas a adaptadores salientes se realicen únicamente a través de puertos interfaces (`ports/out`).
   - Los controladores residan en `infrastructure/in/rest` y no contengan lógica de negocio.
6. **Given** la configuración de seguridad, **When** se activa el perfil de seguridad OAuth2 / JWT,
   **Then** Spring Security valida la firma y claims del token JWT conforme a la especificación de
   Keycloak / LAB-61.

---

### User Story 6 - Guía operativa de extracción y creación de servicios en `docs/fases/` (Priority: P2)

Como desarrollador o nuevo integrante del equipo, quiero contar con un documento claro en
`docs/fases/` que describa el procedimiento paso a paso para crear un nuevo microservicio o extraer uno
existente a partir de `shoplab-service-template` y conectarlo a `shoplab-ci` y `shoplab-infra`, para que
el proceso sea reproducible, autónomo y se complete en menos de 15 minutos.

**Why this priority**: Una plantilla y workflows sin documentación de onboarding provocan errores de
configuración, olvido de secretos requeridos (GitHub App, tokens de GHCR) o desviaciones de la
topología oficial fijada en `docs/mapa-servicios.md`.

**Independent Test**: Seguir exclusivamente los pasos del documento en un ejercicio de simulación para
crear un servicio hipotético y verificar que se configuran con éxito los secretos, repositorios,
manifiestos de `shoplab-infra` y ejecución del pipeline.

**Acceptance Scenarios**:

1. **Given** el directorio `docs/fases/`, **When** se consulta la documentación de la fase, **Then**
   existe una guía detallada (p. ej. `docs/fases/F5-creacion-de-servicios-desde-plantilla.md`).
2. **Given** un desarrollador creando un repositorio desde la plantilla, **When** sigue la guía,
   **Then** encuentra la lista exacta de variables y secretos requeridos en GitHub (`DEPLOY_APP_ID`,
   `DEPLOY_APP_PRIVATE_KEY`, permisos en GHCR).
3. **Given** los pasos de extracción de un módulo desde el monolito hacia su propio repositorio,
   **When** se ejecuta el procedimiento, **Then** documenta cómo mover las migraciones de Flyway, cómo
   sustituir llamadas internas por clientes de `shoplab-contracts` y cómo dar de alta el overlay en
   `shoplab-infra`.

---

### Edge Cases

- **Fallo en Testcontainers en entornos de CI sin socket Docker accesible**: Los workflows de
  `shoplab-ci` deben validar la disponibilidad del daemon de Docker y configurar variables de entorno
  adecuadas (`TESTCONTAINERS_RYUK_DISABLED` si aplica) para evitar timeouts en runners de GitHub Actions.
- **Sobrescritura accidental de tags de imagen**: Si un workflow de publicación recibe un tag de release
  que ya existe en GHCR, el job debe abortar con error explícito antes de construir o subir capas,
  evitando alterar imágenes ya desplegadas.
- **Firma de imágenes sin identidad OIDC en forks**: Si un PR proviene de un fork que no tiene permisos
  de OIDC id-token write, el paso de firma de Cosign y despliegue a dev se desactiva limpiamente,
  ejecutándose únicamente las pruebas y escaneos de validación estática.
- **Conflicto de concurrencia en `shoplab-infra`**: Si dos servicios intentan desplegar simultáneamente
  sobre `shoplab-infra`, el job de deploy reutilizable debe implementar reintentos con backoff exponencial
  (`git pull --rebase` antes de push) para resolver colisiones de commit de forma no destructiva.
- **Divergencia entre esquema de base de datos y nombre del servicio**: La plantilla debe forzar mediante
  prueba unitaria / ArchUnit que el prefijo del paquete, el esquema de Flyway y la tabla de historial
  coincidan exactamente con el nombre asignado al servicio.
- **Inyección de código de dominio o utilidades compartidas indebidas**: La plantilla y sus reglas
  ArchUnit deben impedir la adición de dependencias Maven hacia el repositorio raíz `shoplab-helpdesk` o
  entre proyectos hermanos.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Debe existir el repositorio central `shoplab-ci` conteniendo workflows reutilizables
  (`workflow_call`) accesibles para todos los servicios de la organización bajo la ruta
  `.github/workflows/`.
- **FR-002**: `shoplab-ci` DEBE ofrecer tres flujos reutilizables especializados: `java-service.yml`,
  `python-service.yml` y `angular-app.yml`, versionados mediante tags semánticos (iniciando en `@v1`).
- **FR-003**: El workflow `java-service` DEBE ejecutar compilación con JDK 21, ejecución de tests
  unitarios e integración con Testcontainers (PostgreSQL), verificación estricta de cobertura JaCoCo
  (umbral mínimo del 80 % de líneas), análisis de calidad estática con Qodana y escaneo de vulnerabilidades
  con Trivy (`fs` e `image`).
- **FR-004**: El workflow `python-service` DEBE ejecutar instalación de dependencias y pruebas con `uv`,
  análisis de calidad de código y escaneo de seguridad con Trivy.
- **FR-005**: El workflow `angular-app` DEBE ejecutar compilación con Node.js, linter, tests con
  cobertura y escaneo de dependencias npm con Trivy.
- **FR-006**: Todos los workflows de `shoplab-ci` DEBEN incluir un paso de validación de políticas que
  prohíba y falle el job si se detecta el tag `:latest` en imágenes o la presencia del marcador
  `CAMBIA-ESTE-OWNER` en cualquier archivo del repositorio.
- **FR-007**: Los workflows DEBEN construir y publicar imágenes de contenedor en el registro
  `ghcr.io/leandroamariles/<servicio>` etiquetadas con `:sha-<commit>` para ramas y `:vX.Y.Z` para releases.
- **FR-008**: Cada imagen publicada en GHCR DEBE generar y adjuntar un SBOM (Software Bill of Materials)
  completo y atestación de procedencia (SLSA provenance mode max).
- **FR-009**: Cada imagen publicada DEBE ser firmada criptográficamente mediante Cosign en modo keyless
  aprovechando el proveedor OIDC de GitHub Actions (`id-token: write`).
- **FR-010**: `shoplab-ci` DEBE proveer un job reutilizable de despliegue (`deploy.yml` o equivalente)
  que clone `shoplab-infra` utilizando un token de GitHub App de alcance mínimo limitado exclusivamente a
  dicho repositorio (`contents: write`).
- **FR-011**: El job de despliegue DEBE actualizar la imagen en el overlay `dev` mediante
  `kustomize edit set image`, verificar que el render con `kustomize build` compile sin errores, validar
  las políticas locales con `policy-check.sh` y realizar el commit y push firmado en `shoplab-infra`.
- **FR-012**: Debe existir el repositorio `shoplab-service-template` configurado como GitHub Template
  Repository para la generación de nuevos microservicios.
- **FR-013**: La plantilla DEBE incluir un `pom.xml` basado en `spring-boot-starter-parent` (Spring Boot 4.x),
  Java 21, Spring WebFlux, Actuator, y la dependencia declarada y fijada a los paquetes de
  `shoplab-contracts` (LAB-108).
- **FR-014**: La plantilla DEBE contener la configuración de `release-please` para versionado semántico
  automático, `dependabot.yml` para actualización de dependencias, y un `Dockerfile` multi-stage
  optimizado y sin privilegios de root.
- **FR-015**: La plantilla DEBE implementar el paquete `shared` desacoplado que incluya:
  - Manejador centralizado de errores `GlobalExceptionHandler` con soporte para `BusinessException` y
    `TechnicalException`.
  - Enum tipado `ResponseCodeEnum` parametrizable con el prefijo del servicio.
  - Envoltorio de respuestas estándar `ResponseDto<T>` y `ErrorDto`.
  - Filtro web reactivo de correlación para captura y propagación de `X-Request-Id` y `traceparent`.
- **FR-016**: La plantilla DEBE incluir configuración de Flyway acotada estrictamente a un único esquema
  propio (`db/migration/<servicio>`), aislando completamente sus migraciones y tablas de historial.
- **FR-017**: La plantilla DEBE incluir reglas de arquitectura ejecutables mediante ArchUnit que
  garanticen la arquitectura hexagonal (dominio puro sin dependencias de infraestructura ni framework,
  comunicación exclusiva por puertos, y controladores sin lógica).
- **FR-018**: La plantilla DEBE incluir la configuración de seguridad lista para Spring Security OAuth2
  Resource Server, validando tokens JWT conforme a Keycloak (LAB-61).
- **FR-019**: DEBE crearse la documentación en `docs/fases/` detallando el flujo para instanciar,
  configurar y poner en marcha un nuevo microservicio a partir de la plantilla y los workflows reutilizables.
- **FR-020**: Se DEBE hacer cumplir la regla de arquitectura fundamental: ningún repositorio de servicio
  depende de código de otro servicio ni del monolito; la integración inter-servicio se realiza
  únicamente a través de paquetes de contratos (`shoplab-contracts`) y protocolos de red (OpenAPI y MCP).

---

### Key Entities

- **Reusable Workflow (`shoplab-ci`)**: Definición modular de GitHub Actions invocable por otros
  repositorios mediante `workflow_call`. Recibe entradas parametrizadas (nombre del servicio, rutas de
  contexto, umbrales de cobertura) y secretos protegidos, garantizando la homogeneidad de los pipelines.
- **Deploy Job**: Tarea de entrega continua que opera de forma desacoplada sobre `shoplab-infra`
  modificando las declaraciones de Kustomize y garantizando la trazabilidad GitOps sin exponer accesos
  globales.
- **Service Template (`shoplab-service-template`)**: Repositorio arquetipo que proporciona el esqueleto
  físico, dependencias, estándares de seguridad, pruebas y estructura modular requerida para cualquier
  nuevo servicio backend en el ecosistema.
- **Transversal Package (`shared`)**: Conjunto de clases técnicas replicadas en cada servicio
  (manejadores de excepción, filtros de contexto y correlación, utilidades seguras de log) que operan sin
  acoplar lógica de negocio.
- **Service OCI Image**: Artefacto final empaquetado en GHCR, acompañado de su firma Cosign y su
  documento SBOM atestiguando su procedencia e integridad criptográfica.
- **Isolated Database Schema**: Esquema relacional en PostgreSQL asignado de forma exclusiva a un
  servicio, gestionado autónomamente por Flyway sin claves foráneas cruzadas.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El repositorio `shoplab-ci` existe y contiene los workflows `java-service.yml`,
  `python-service.yml` y `angular-app.yml`, accesibles vía `@v1`, ejecutando en verde para repositorios
  válidos y bloqueando el pipeline ante incumplimiento de calidad o políticas.
- **SC-002**: El 100 % de los builds de `java-service` comprueban JaCoCo y fallan automáticamente si la
  cobertura de líneas es inferior al 80 %.
- **SC-003**: El 100 % de las imágenes generadas se publican en `ghcr.io/leandroamariles/<servicio>` con
  tags `:sha-<commit>` y `:vX.Y.Z`, acompañadas de SBOM y firma Cosign keyless verificable.
- **SC-004**: El job reutilizable de despliegue actualiza con éxito el overlay `dev` en `shoplab-infra`
  en menos de 3 minutos tras la publicación de un tag de versión, utilizando exclusivamente el token de
  la GitHub App de despliegue.
- **SC-005**: El repositorio `shoplab-service-template` existe como GitHub Template Repository, compila
  de forma limpia (`mvn clean verify`) en local y en CI con Java 21, y levanta pruebas de integración
  con Testcontainers sin dependencias del monolito.
- **SC-006**: La plantilla incluye el paquete `shared`, Flyway mono-esquema, ArchUnit y Resource Server
  JWT con una cobertura de pruebas unitarias superior al 85 % sobre su código base.
- **SC-007**: El tiempo total requerido por un desarrollador para instanciar un servicio desde la
  plantilla, configurar su repositorio y ver su primer commit verificado por `shoplab-ci` es inferior a
  15 minutos siguiendo la guía de `docs/fases/`.
- **SC-008**: Cero dependencias hacia `shoplab-helpdesk` o código de otros servicios en la plantilla:
  la única dependencia externa compartida es `shoplab-contracts` (LAB-108).

---

## Assumptions

- **Dependencia de `LAB-99` y `LAB-108`**: Se asume que `LAB-99` ha provisto la estructura base del
  repositorio `shoplab-infra` y sus scripts de validación (`policy-check.sh`, overlay `dev`), y que
  `LAB-108` provee el repositorio `shoplab-contracts` y la publicación de artefactos Maven en GitHub
  Packages. La plantilla fijará la dependencia al paquete generado por `LAB-108`.
- **Gobernanza de secretos y GitHub App**: Para el despliegue automático hacia `shoplab-infra`, se asume
  la existencia o provisión de la GitHub App `shoplab-deploy[bot]` (o el uso de `DEPLOY_APP_ID` y
  `DEPLOY_APP_PRIVATE_KEY` a nivel de organización o repositorio), con permisos de escritura acotados
  únicamente al repositorio `shoplab-infra`.
- **Firma keyless con Cosign en GitHub Actions**: Cosign operará mediante el token OIDC nativo de
  GitHub Actions (`permissions: id-token: write`), lo que requiere que los repositorios que publiquen
  imágenes tengan habilitada la emisión de tokens OIDC hacia Sigstore Fulcio / Rekor.
- **Aislamiento absoluto de repositorios (ADR-0015, ADR-0031)**: Se ratifica que no se crearán librerías
  JAR compartidas de dominio ni frameworks propietarios inter-servicio. La infraestructura base
  (`shared`) se distribuye mediante la plantilla (`shoplab-service-template`) y es mantenida
  independientemente por cada microservicio.
- **Evolución hacia microservicios extraídos**: Los servicios extraídos tempranamente según ADR-0031
  (`retrieval-service`, `chat-service` y `eval-service`) y los nuevos que nazcan en F3/F4 adoptarán esta
  plantilla y estos workflows reutilizables como estándar mandatorio de plataforma.
