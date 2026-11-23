# Feature Specification: Repo shoplab-contracts publicado como paquetes versionados

**Feature Branch**: `feature/LAB-108-contracts-versioned-packages`

**Created**: 2026-11-23

**Status**: Draft

**Input**: User description: "LAB-108 (F5-16) Como desarrollador, quiero los contratos en un repo propio y publicados como paquetes con versión, para que cada repo de servicio dependa de una versión concreta y no de una carpeta compartida. Criterios de aceptación: Repo `shoplab-contracts` con `openapi/`, y `avro/` y `asyncapi/` vacíos hasta LAB-50; CI que valida cada cambio: oasdiff para OpenAPI y, cuando haya esquemas, compatibilidad BACKWARD de Avro contra Schema Registry; un cambio incompatible exige subir la versión mayor; release-please con versionado semántico; cada release publica: un artefacto Maven con las clases generadas en GitHub Packages, un paquete npm con el cliente Angular generado en GitHub Packages, un paquete Python instalable por tag con `uv` (dependencia git `@vX.Y.Z`); Los servicios fijan la versión de contratos que usan y Dependabot les propone las nuevas; Documentado en `docs/` cómo se publica un cambio de contrato y en qué orden se actualizan productor y consumidores. Depende de: LAB-128. Va antes de LAB-106 y LAB-103, y de LAB-50, que escribe sus esquemas Avro en este repo. Se adelantó de F5 al bloque de extracción temprana (ADR-0029, LAB-128)."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Repositorio independiente `shoplab-contracts` con estructura base para contratos (Priority: P1)

Como desarrollador, quiero disponer de un repositorio dedicado `shoplab-contracts` con la estructura
de carpetas para contratos síncronos (`openapi/`), esquemas de eventos (`avro/`) y especificaciones
asíncronas (`asyncapi/`), manteniendo `avro/` y `asyncapi/` vacíos hasta LAB-50, para que los
futuros servicios extraídos puedan referenciar un origen único de contratos separado del monolito
y sin compartir carpetas locales ni librerías con lógica de dominio.

**Why this priority**: Es la base física y organizativa sobre la que operan las validaciones de CI,
la publicación de paquetes y las historias dependientes (`LAB-106`, `LAB-103` y `LAB-50`). Sin el
repositorio separado y su estructura definida, no existe unidad versionable que publicar ni validar.

**Independent Test**: Clonar el repositorio `shoplab-contracts`, verificar que existen `openapi/`,
`avro/` y `asyncapi/` (con `avro/` y `asyncapi/` sin esquemas de eventos hasta `LAB-50`), confirmar
la ausencia de lógica de negocio y verificar que el proyecto construye de forma reproducible en local
y en CI.

**Acceptance Scenarios**:

1. **Given** el nuevo repositorio `shoplab-contracts`, **When** se inspecciona su árbol de
   directorios, **Then** existen las carpetas raíz `openapi/`, `avro/` y `asyncapi/`.
2. **Given** que `LAB-50` aún no se ha ejecutado, **When** se revisan `avro/` y `asyncapi/`,
   **Then** no contienen esquemas ni especificaciones de eventos definitivas (permanecen vacíos o
   únicamente con marcadores que no disparan registro de eventos).
3. **Given** un cambio en `shoplab-contracts`, **When** se inspecciona el contenido distribuido en los
   artefactos generados, **Then** únicamente expone contratos y tipos generados (DTOs, clientes y
   modelos de datos), sin entidades de dominio ni lógica de aplicación.
4. **Given** la rama principal de `shoplab-contracts`, **When** se revisa su configuración de
   protección, **Then** exige pull request y checks de CI en verde antes de fusionar, de forma
   equivalente al resto de repositorios del proyecto.

---

### User Story 2 - Validación de compatibilidad de contratos en CI (`oasdiff` y Avro `BACKWARD`) (Priority: P1)

Como desarrollador, quiero que cada pull request en `shoplab-contracts` valide automáticamente la
sintaxis y la compatibilidad hacia atrás de los contratos OpenAPI con `oasdiff` y, cuando existan
esquemas en `avro/`, la compatibilidad `BACKWARD` contra Schema Registry, bloqueando la fusión si
un cambio incompatible no declara un incremento de versión mayor, para detectar rupturas entre
productores y consumidores antes de publicar una versión.

**Why this priority**: Publicar paquetes versionados sin una puerta de compatibilidad en CI permitiría
introducir cambios rupturistas en versiones `minor` o `patch`, rompiendo a los consumidores que confían
en el versionado semántico.

**Independent Test**: Abrir dos PRs de prueba sobre `shoplab-contracts`: (1) un cambio compatible
(p. ej. añadir un campo opcional en OpenAPI o un esquema compatible) y comprobar que pasa el check;
(2) un cambio incompatible (p. ej. eliminar un campo requerido o cambiar su tipo) sin marcar cambio
mayor, y comprobar que falla el check señalando la ruptura; luego marcarlo como cambio mayor (`!` o
`BREAKING CHANGE:`) y verificar que el gate de compatibilidad lo acepta para subida de versión mayor.

**Acceptance Scenarios**:

1. **Given** un PR que modifica o añade una especificación en `openapi/` de forma retrocompatible,
   **When** se ejecuta el workflow de CI, **Then** `oasdiff` valida la diferencia contra la línea base
   vigente y el check finaliza en verde.
2. **Given** un PR que introduce un cambio incompatible en `openapi/` sin marcar incremento de
   versión mayor (`!` en el título/commit o `BREAKING CHANGE:` en el cuerpo), **When** corre la CI,
   **Then** el check de `oasdiff` falla impidiendo el merge e indicando el detalle de la ruptura.
3. **Given** un PR con un cambio incompatible en `openapi/` correctamente marcado como breaking
   change (`!` o `BREAKING CHANGE:`), **When** corre la CI, **Then** el check reconoce la declaración
   de cambio mayor y permite continuar el flujo para publicar la siguiente versión mayor.
4. **Given** que `avro/` está vacío (estado anterior a `LAB-50`), **When** corre la CI en un PR de
   `shoplab-contracts`, **Then** el paso de compatibilidad Avro no falla por ausencia de esquemas y
   reporta explícitamente que no hay esquemas Avro que validar.
5. **Given** la presencia de al menos un esquema en `avro/`, **When** un PR modifica un esquema Avro,
   **Then** la CI levanta o consulta una instancia de Schema Registry, valida compatibilidad `BACKWARD`
   contra la versión previa del esquema y falla si hay ruptura sin declaración de versión mayor.

---

### User Story 3 - Release automático y publicación de paquetes Maven, npm y Python (Priority: P1)

Como desarrollador, quiero que `release-please` gestione el versionado semántico de
`shoplab-contracts` y que cada release publicada genere y distribuya automáticamente un artefacto
Maven y un paquete npm (cliente Angular) en GitHub Packages, además de dejar un paquete Python
instalable por tag `@vX.Y.Z` con `uv`, para que cada servicio en Java, Angular o Python consuma una
versión inmutable desde su gestor de paquetes estándar.

**Why this priority**: Es el propósito central de la historia: transformar los archivos de contrato en
paquetes consumibles y versionados para los tres ecosistemas del proyecto (backend Java, frontend
Angular y servicios/herramientas Python) sin pasos manuales de empaquetado ni publicación.

**Independent Test**: Fusionar un cambio en `main` de `shoplab-contracts`, fusionar el PR de release
generado por `release-please` creando el tag `vX.Y.Z` y verificar que: (a) el artefacto Maven se
encuentra en GitHub Packages con la versión `X.Y.Z` y contiene las clases Java generadas; (b) el
paquete npm se encuentra en GitHub Packages con la versión `X.Y.Z` y contiene el cliente Angular
generado; (c) un proyecto Python puede resolver e instalar el paquete mediante `uv` apuntando al
tag `@vX.Y.Z`.

**Acceptance Scenarios**:

1. **Given** uno o más commits siguiendo Conventional Commits fusionados en `main` de
   `shoplab-contracts`, **When** corre el workflow de release, **Then** `release-please` abre o
   actualiza un PR de release con la nueva versión semántica (`patch` para `fix`, `minor` para
   `feat`, `major` para `!` / `BREAKING CHANGE:`) y actualiza `CHANGELOG.md`.
2. **Given** un PR de release fusionado en `main`, **When** `release-please` crea el tag `vX.Y.Z` y
   el GitHub Release, **Then** se dispara automáticamente la generación y publicación de los paquetes
   de la versión `X.Y.Z`.
3. **Given** la publicación de la versión `vX.Y.Z`, **When** se consulta GitHub Packages del
   propietario del repositorio, **Then** existe el artefacto Maven publicado con versión `X.Y.Z`
   conteniendo las clases/modelos generados desde los contratos.
4. **Given** la publicación de la versión `vX.Y.Z`, **When** se consulta el registro npm de GitHub
   Packages, **Then** existe el paquete npm publicado con versión `X.Y.Z` conteniendo el cliente
   Angular tipado generado desde OpenAPI (compilado/empaquetado listo para consumo).
5. **Given** el tag `vX.Y.Z` en `shoplab-contracts`, **When** un proyecto Python lo referencia en sus
   dependencias con `uv` mediante URL git y sufijo `@vX.Y.Z`, **Then** `uv sync` / `uv pip install`
   instala el paquete Python con los modelos/clientes generados sin errores.
6. **Given** una ejecución repetida del workflow de publicación para un tag `vX.Y.Z` ya publicado,
   **When** vuelve a correr, **Then** el flujo maneja de forma segura o idempotente la versión
   existente sin corromper los paquetes ya publicados.

---

### User Story 4 - Fijación de versiones en consumidores y actualización automática con Dependabot (Priority: P2)

Como desarrollador de un servicio consumidor (Java, Angular o Python), quiero fijar explícitamente la
versión de `shoplab-contracts` en las dependencias del servicio y recibir pull requests automáticos
de Dependabot cuando se publique una nueva versión, para controlar cuándo adopto cada cambio de
contrato y mantener el historial de dependencias actualizado sin vigilancia manual.

**Why this priority**: Desacoplar en un repositorio propio solo aporta seguridad si los consumidores
anclan versiones concretas (nunca rangos abiertos ni ramas móviles) y tienen un mecanismo automático
de actualización como Dependabot, tal como ya se usa en el monorepo.

**Independent Test**: Configurar un consumidor con una versión fijada de `shoplab-contracts`
(Maven, npm y/o Python) y la regla de Dependabot correspondiente; comprobar que resuelve la versión
fijada desde GitHub Packages / tag git y que Dependabot detecta versiones nuevas disponibles en los
registros configurados.

**Acceptance Scenarios**:

1. **Given** un repositorio de servicio o consumidor configurado con Dependabot, **When** se revisa su
   declaración de dependencia sobre `shoplab-contracts`, **Then** fija una versión exacta (p. ej.
   `X.Y.Z` en Maven/npm o `@vX.Y.Z` en `uv`), sin depender de carpetas compartidas ni snapshots
   móviles.
2. **Given** una nueva versión publicada en GitHub Packages (Maven y npm), **When** Dependabot evalúa
   las dependencias del consumidor configurado, **Then** dispone de la configuración de registros y
   credenciales necesaria para proponer un PR de actualización de versión.
3. **Given** el propio repositorio `shoplab-contracts`, **When** se revisan sus dependencias de
   herramientas y GitHub Actions, **Then** cuenta con Dependabot configurado igual que `shoplab-helpdesk`
   y `shoplab-infra`.

---

### User Story 5 - Documentación del flujo de cambios de contrato y orden de despliegue (Priority: P2)

Como desarrollador, quiero encontrar en `docs/` una guía clara que explique cómo proponer, validar y
publicar un cambio de contrato en `shoplab-contracts`, y en qué orden deben actualizarse el servicio
productor y los servicios consumidores (especialmente ante cambios compatibles frente a cambios
mayores), para evitar fallos en tiempo de ejecución al desplegar servicios de forma independiente.

**Why this priority**: En una arquitectura distribuida, incluso con paquetes versionados, actualizar el
consumidor antes que el productor (o retirar un campo antes de migrar consumidores) rompe producción.
La guía operativa en `docs/` es un criterio explícito de aceptación para todo el equipo.

**Independent Test**: Un desarrollador lee la documentación en `docs/` y puede enumerar sin
ambigüedad: qué pasos seguir para añadir un campo opcional vs. eliminar un campo requerido, qué
título/commit usar para activar `oasdiff` con cambio mayor, y en qué secuencia exacta se actualizan y
despliegan productor y consumidores.

**Acceptance Scenarios**:

1. **Given** la documentación del proyecto en `docs/`, **When** un desarrollador busca cómo publicar
   un cambio de contrato, **Then** encuentra el flujo paso a paso desde el PR en `shoplab-contracts`
   hasta la publicación de los paquetes por `release-please`.
2. **Given** un cambio compatible (aditivo) y un cambio incompatible (breaking/major), **When** se
   consulta la guía en `docs/`, **Then** detalla en qué orden exacto se actualizan productor y
   consumidores en cada escenario (incluyendo la estrategia expand-and-contract para cambios sin caída).
3. **Given** los requisitos de consumo en desarrollo y CI, **When** se consulta la documentación,
   **Then** explica cómo autenticar Maven y npm contra GitHub Packages y cómo fijar la dependencia git
   con `@vX.Y.Z` en `uv` (y sus límites conocidos).

---

### Edge Cases

- ¿Qué ocurre si `avro/` y `asyncapi/` están vacíos antes de `LAB-50`? La CI debe validar que la
  ausencia de archivos `.avsc` / `.yaml` no provoca un fallo falso positivo ni impide generar los
  paquetes desde `openapi/`, dejando trazado en el log del job que la comprobación Avro se omitió por
  no haber esquemas todavía.
- ¿Qué ocurre cuando `openapi/` aún no tiene especificaciones de negocio (porque `LAB-106` las añade
  después de `LAB-108`)? Debe existir al menos un contrato semilla o verificación mínima demostrable
  (o generación válida de artefactos base) para probar extremo a extremo `oasdiff`, `release-please`
  y la publicación real de los tres paquetes (Maven, npm y Python) dentro de `LAB-108` sin esperar a
  `LAB-106`.
- ¿Qué ocurre en el primer PR o en la primera publicación cuando todavía no existe ningún tag `vX.Y.Z`
  previo contra el que comparar en `oasdiff` o Schema Registry? El workflow debe detectar la ausencia
  de versión base publicada y validar la sintaxis/esquema del contrato sin fallar por «tag no
  encontrado», estableciendo la línea base desde la primera release.
- ¿Qué ocurre con el cálculo de versión mayor en `release-please` mientras la versión inicial sea
  `0.x` vs. `1.x`? Por defecto `release-please` puede promocionar un breaking change en `0.x` solo a
  `minor` (`bump-minor-pre-major`); dado que el criterio de aceptación exige explícitamente que «un
  cambio incompatible exige subir la versión mayor», la configuración debe garantizar que el primer
  release arranque en `1.0.0` (o desactivar `bump-minor-pre-major` para que `!` / `BREAKING CHANGE:`
  incremente siempre la versión **mayor**).
- ¿Qué ocurre si un PR combina cambios compatibles e incompatibles, o el título del PR usa `!` en
  formato `feat(contracts)!: ...` frente a `BREAKING CHANGE:` en el cuerpo? La detección en CI debe
  soportar la convención estándar del proyecto (título de PR con `!` o cuerpo con `BREAKING CHANGE:`)
  y asegurar coherencia entre el gate de CI y el cálculo de versión de `release-please`.
- ¿Qué ocurre si falla la publicación de uno de los paquetes (p. ej. npm falla tras haberse publicado
  Maven y creado el tag `vX.Y.Z`)? El workflow de publicación debe permitir reejecución sin errores
  fatales por artefacto existente o documentar el mecanismo de recuperación sin reutilizar etiquetas
  mutables.
- ¿Qué ocurre con Dependabot y paquetes Python instalados por URL git `@vX.Y.Z`? Dependabot soporta
  nativamente ecosistemas `maven`, `npm`, `github-actions` y `uv`/`pip` estándar, mientras que las
  dependencias git con tag en `pyproject.toml`/`uv.lock` tienen soporte limitado en Dependabot; este
  límite debe quedar documentado explícitamente junto con el comportamiento esperado para los tres
  ecosistemas.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Debe existir el repositorio independiente `shoplab-contracts` (bajo la cuenta/organización
  `leandroAmariles`) con las carpetas raíz `openapi/`, `avro/` y `asyncapi/`, manteniendo `avro/` y
  `asyncapi/` vacíos de esquemas reales hasta `LAB-50`.
- **FR-002**: El repositorio `shoplab-contracts` MUST contener únicamente especificaciones de contrato,
  configuración de generación de código y automatización de CI/release; MUST NOT incluir código ni
  lógica de negocio de ningún microservicio (en cumplimiento de ADR-0015).
- **FR-003**: El workflow de CI de `shoplab-contracts` MUST ejecutarse en cada pull request y en cada
  push a `main`, validando que todas las especificaciones en `openapi/` son sintácticamente válidas.
- **FR-004**: El workflow de CI de `shoplab-contracts` MUST ejecutar `oasdiff` sobre los cambios en
  `openapi/` comparando contra la última versión publicada (o `main` cuando corresponda), fallando el
  PR ante cualquier cambio incompatible que no declare incremento de versión mayor (`!` o
  `BREAKING CHANGE:`).
- **FR-005**: Cuando existan esquemas en `avro/`, el workflow de CI MUST validar compatibilidad
  `BACKWARD` contra Schema Registry, fallando el PR si se detecta una incompatibilidad sin
  declaración de versión mayor; mientras `avro/` esté vacío, el paso MUST completarse sin error
  dejando constancia de que no hay esquemas presentes.
- **FR-006**: El repositorio `shoplab-contracts` MUST usar `release-please` con versionado semántico
  (`vX.Y.Z`) y `CHANGELOG.md` generado desde Conventional Commits; cualquier cambio incompatible MUST
  corresponderse con un incremento de versión mayor (`X+1.0.0`).
- **FR-007**: Cada release publicada en `shoplab-contracts` (`vX.Y.Z`) MUST generar y publicar en
  GitHub Packages un artefacto Maven con las clases Java compiladas/generadas desde los contratos.
- **FR-008**: Cada release publicada en `shoplab-contracts` (`vX.Y.Z`) MUST generar y publicar en
  GitHub Packages un paquete npm con el cliente Angular tipado generado desde los contratos OpenAPI.
- **FR-009**: Cada release publicada en `shoplab-contracts` (`vX.Y.Z`) MUST mantener actualizado y
  preparado el paquete Python en el repositorio de modo que cualquier servicio Python pueda
  instalarlo de forma determinista con `uv` apuntando al tag exacto `@vX.Y.Z`.
- **FR-010**: La generación de los tres paquetes (Maven, npm y Python) MUST validarse también en PR
  (build/verificación en seco sin publicar) para garantizar que ningún cambio roto en un contrato
  rompe la compilación o el empaquetado tras fusionarse.
- **FR-011**: El repositorio `shoplab-contracts` MUST contar con protección de la rama `main`
  (ruleset equivalente a `LAB-77`), validación de título de PR (`pr-title`) compatible con
  Conventional Commits y `release-please`, y configuración de Dependabot para mantener actualizadas sus
  propias acciones y dependencias de generación.
- **FR-012**: La fijación de versión de `shoplab-contracts` y la configuración de Dependabot en los
  servicios consumidores (Maven/npm y configuración documentada/aplicable en `shoplab-helpdesk` y
  futuros repositorios de `LAB-103`) MUST exigir versiones exactas y proponer actualizaciones mediante
  PRs automáticos cuando existan nuevas versiones publicadas.
- **FR-013**: Debe quedar documentado en `docs/` (en `shoplab-helpdesk` y referenciado desde
  `shoplab-contracts`) el procedimiento completo para proponer, validar y publicar un cambio de
  contrato, así como las reglas de compatibilidad y cómo consumir los tres paquetes desde Java,
  Angular y Python.
- **FR-014**: La documentación en `docs/` MUST especificar el orden obligatorio de actualización y
  despliegue entre productor y consumidores tanto para cambios retrocompatibles (productor primero o
  estrategia expand-and-contract) como para cambios mayores incompatibles.
- **FR-015**: La historia MUST incluir una validación de extremo a extremo con un primer release real
  publicado desde `shoplab-contracts`, comprobando la disponibilidad e instalación de los tres
  paquetes (Maven en GitHub Packages, npm en GitHub Packages y Python vía `uv` por tag `@vX.Y.Z`),
  además de la prueba de rechazo de un cambio incompatible sin versión mayor.
- **FR-016**: Quedan fuera del alcance de esta historia las especificaciones OpenAPI de los servicios de
  negocio (`chat`, `retrieval`, `eval`, `catalog`, `orders`, `tickets`, que pertenecen a `LAB-106`),
  la plantilla de servicio y workflows compartidos (`LAB-103`) y los esquemas Avro/AsyncAPI de eventos
  (`LAB-50`).

### Key Entities

- **Repositorio `shoplab-contracts`**: Repositorio independiente que actúa como única fuente de verdad
  para contratos síncronos y asíncronos entre servicios, sin código de dominio.
- **Contrato de interfaz (`openapi/`, `avro/`, `asyncapi/`)**: Archivo de especificación formal que
  define la frontera de comunicación entre productor y consumidores.
- **Puerta de compatibilidad (`oasdiff` / Schema Registry `BACKWARD`)**: Validación automatizada en PR
  que compara la especificación modificada frente a la versión publicada y bloquea cambios rupturistas
  que no declaren incremento de versión mayor.
- **Release semántica (`vX.Y.Z`)**: Versión inmutable generada por `release-please` con registro de
  cambios (`CHANGELOG.md`), tag git y GitHub Release que dispara la publicación simultánea de los
  paquetes.
- **Paquetes de contratos (Maven, npm, Python)**: Artefactos generados y versionados para cada stack
  tecnológico (`GitHub Packages` para Maven/npm y paquete instalable vía git tag `@vX.Y.Z` para `uv`).
- **Política de evolución productor/consumidores**: Secuencia documentada de actualización y
  despliegue que garantiza compatibilidad continua durante las transiciones de versión.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El repositorio `shoplab-contracts` existe con `openapi/`, `avro/` y `asyncapi/`, con
  `avro/` y `asyncapi/` vacíos de esquemas reales hasta `LAB-50` y el 100 % de las protecciones de
  rama y validación de título activas en `main`.
- **SC-002**: El 100 % de los PRs que introducen un cambio incompatible en `openapi/` sin marcar
  versión mayor (`!` o `BREAKING CHANGE:`) son rechazados automáticamente por `oasdiff` en menos de
  5 minutos; cuando se declara cambio mayor o el cambio es compatible, el check termina en verde.
- **SC-003**: El workflow de compatibilidad Avro contra Schema Registry (`BACKWARD`) está integrado y
  ejecuta sin errores tanto cuando `avro/` está vacío (omite limpiamente) como ante un escenario de
  prueba con esquemas compatibles vs. incompatibles.
- **SC-004**: Tras fusionar un PR de release generado por `release-please`, los artefactos Maven y
  npm quedan publicados en GitHub Packages bajo la versión `X.Y.Z` y el paquete Python queda
  instalable por `uv` usando `@vX.Y.Z` en menos de 10 minutos sin pasos manuales.
- **SC-005**: Un proyecto consumidor de verificación para cada stack (Maven, npm/Angular y `uv`/Python)
  resuelve e importa las clases/clientes generados de la versión exacta `vX.Y.Z` publicada.
- **SC-006**: La configuración de Dependabot en el repositorio de consumo y en `shoplab-contracts`
  detecta versiones nuevas de los paquetes publicados en GitHub Packages y es validada sin errores de
  sintaxis ni de acceso al registro.
- **SC-007**: Un desarrollador que lee únicamente la documentación creada en `docs/` puede completar
  el ciclo de cambio de contrato y determinar correctamente el orden de actualización entre productor y
  consumidores en el 100 % de los casos (cambio compatible vs. incompatible).

## Assumptions

- **Relación con `LAB-128` y numeración ADR**: El enunciado de `LAB-108` indica que depende de
  `LAB-128` («F5-21 ADR de extracción temprana y mapa de servicios», actualmente en estado *Por hacer* en
  Jira) y cita `(ADR-0029, LAB-128)`. Sin embargo, en `docs/adr/` el número `0029` ya fue utilizado
  por `LAB-32` (`0029-lectura-del-historial-de-evaluacion.md`) y `0030` por `LAB-33`. Esta
  especificación asume las decisiones arquitectónicas ya definidas en `LAB-128` y `ADR-0015` (repositorios
  separados, `shoplab-contracts` como repo de soporte sin lógica de dominio), y cuando `LAB-128` se
  implemente registrará su ADR en el siguiente número libre (`0031` o superior), sin bloquear el avance
  del diseño de `LAB-108`.
- **Contrato inicial antes de `LAB-106`**: Como `LAB-106` es la historia que mueve y crea las
  especificaciones OpenAPI reales de los servicios (`chat`, `retrieval`, `eval`, etc.), `LAB-108`
  incluirá una especificación OpenAPI mínima/semilla (o contrato base común, p. ej. esquema de error o
  health/ping de contratos) para que los generadores Maven, npm/Angular y Python produzcan paquetes
  reales válidos, `oasdiff` tenga línea base que comparar y se pruebe la publicación real de principio
  a fin.
- **Contenido de `avro/` y `asyncapi/`**: Siguiendo literalmente el criterio de aceptación («`avro/` y
  `asyncapi/` vacíos hasta LAB-50»), estos directorios permanecerán vacíos (solo `.gitkeep` o similar)
  en el repositorio; las pruebas del validador Avro contra Schema Registry en CI se verificarán con
  fixtures temporales en el entorno de pruebas o tests del propio workflow antes de dar por cerrada la
  historia, sin dejar esquemas ficticios versionados en `avro/`.
- **Visibilidad y autenticación de GitHub Packages**: Bajo la cuenta personal `leandroAmariles`, los
  paquetes Maven y npm publicados en GitHub Packages requieren token (`GITHUB_TOKEN` en workflows o PAT
  con `read:packages` en desarrollo/Dependabot). La estrategia concreta de autenticación en Maven
  (`settings.xml`), npm (`.npmrc`), `uv` (repo público o token en entorno) y Dependabot (`registries` en
  `dependabot.yml`) se definirá en la fase de planificación (`/speckit-plan`).
- **Paquete Python instalable por tag con `uv`**: El repositorio `shoplab-contracts` expondrá su código
  Python generado (o estructura instalable mediante PEP 517 / `pyproject.toml` en raíz o subdirectorio
  `subdirectory=...`) de modo que `uv` pueda instalarlo directamente desde el tag `@vX.Y.Z` sin
  requerir publicación en PyPI ni índice privado adicional.
- **Versionado mayor ante cambios incompatibles**: Para cumplir estrictamente que «un cambio
  incompatible exige subir la versión mayor», el plan determinará si el repositorio arranca en
  `1.0.0` tras la prueba inicial o si `release-please` se configura con `bump-minor-pre-major: false`
  para que los breaking changes incrementen la versión mayor desde el inicio.
- **Impacto en `shoplab-helpdesk` (este repositorio)**: Mientras los servicios sigan en el monolito
  hasta `LAB-129`/`LAB-59`/`LAB-130`, la especificación, la documentación operativa en `docs/` y la
  verificación de consumo/Dependabot se reflejan aquí, mientras que los archivos propios de contratos,
  generadores y workflows de publicación viven en el nuevo repositorio hermano `shoplab-contracts`.
