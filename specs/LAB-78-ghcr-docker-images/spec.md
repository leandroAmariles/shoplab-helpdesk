# Feature Specification: Imágenes Docker publicadas en GHCR con escaneo y SBOM

**Feature Branch**: `feature/LAB-78-ghcr-docker-images`

**Created**: 2026-10-24

**Status**: Draft

**Input**: User description: "LAB-78 (F0-14) Como desarrollador, quiero que cada merge a `main` y cada release produzcan imágenes versionadas y seguras listas para desplegar. Criterios: Dockerfiles multi-stage para backend, frontend e ingest, con usuario no root; publicación en GHCR (`ghcr.io/leandroamariles/<servicio>`); tags `:sha` en cada merge a `main`, `:latest` solo en `main` y `:vX.Y.Z` cuando release-please crea el tag (LAB-100); escaneo de imagen con severidad HIGH y CRITICAL que falla antes de publicar; SBOM y provenance; imágenes firmadas con cosign keyless (lo exige Kyverno en LAB-83). Depende de: LAB-15, LAB-76."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Imágenes mínimas y no root para cada servicio (Priority: P1)

Como desarrollador, quiero que backend, frontend e ingest se puedan empaquetar en imágenes de contenedor
reproducibles, pequeñas y que no se ejecuten como root, para tener artefactos desplegables y con una
superficie de ataque reducida.

**Why this priority**: Sin imágenes construibles no existe nada que publicar, escanear ni firmar; es la
base de las demás historias.

**Independent Test**: Construir localmente la imagen de cada servicio, arrancarla y comprobar que el
proceso corre con un usuario sin privilegios y que la imagen final no contiene herramientas de compilación.

**Acceptance Scenarios**:

1. **Given** el código de un servicio (backend, frontend o ingest), **When** se construye su imagen,
   **Then** la construcción termina sin pasos manuales y produce una imagen que arranca el servicio.
2. **Given** una imagen construida, **When** se inspecciona el usuario con el que corre el proceso,
   **Then** es un usuario distinto de root.
3. **Given** una imagen construida, **When** se inspeccionan sus contenidos, **Then** no incluye código
   fuente de compilación, cachés de dependencias ni herramientas de build (solo lo necesario para ejecutar).
4. **Given** un servicio cuyo código no ha cambiado, **When** se reconstruye su imagen, **Then** se
   reutilizan las capas ya construidas y el resultado funcional es equivalente.

---

### User Story 2 - Publicación versionada en GHCR en cada merge y release (Priority: P1)

Como desarrollador, quiero que cada merge a `main` y cada release publiquen automáticamente las imágenes
de los tres servicios en GHCR con tags predecibles, para poder desplegar cualquier commit o versión
publicada sin construir nada a mano.

**Why this priority**: Es el objetivo central de la historia: convertir el código fusionado en artefactos
publicados y trazables.

**Independent Test**: Fusionar un cambio a `main` y verificar que existen en GHCR las tres imágenes con tag
del commit y `latest`; luego fusionar el PR de release y verificar el tag de versión.

**Acceptance Scenarios**:

1. **Given** un merge a `main`, **When** termina el pipeline, **Then** cada servicio queda publicado en
   `ghcr.io/leandroamariles/<servicio>` con el tag del SHA del commit fusionado.
2. **Given** un merge a `main`, **When** termina el pipeline, **Then** el tag `latest` de cada servicio
   apunta a la imagen recién publicada.
3. **Given** un pull request sin fusionar o una rama distinta de `main`, **When** corre el pipeline,
   **Then** no se publica ninguna imagen ni se mueve `latest`.
4. **Given** que release-please crea el tag `vX.Y.Z` (LAB-100), **When** el pipeline corre para ese tag,
   **Then** cada servicio queda publicado con el tag `vX.Y.Z` y sin mover `latest`.
5. **Given** un tag de versión ya publicado, **When** se vuelve a ejecutar el pipeline para el mismo tag,
   **Then** el resultado no deja dos imágenes distintas con ese mismo tag sin que quede constancia.
6. **Given** un cambio que solo afecta a un servicio, **When** el pipeline corre, **Then** las imágenes de
   los tres servicios mantienen tags consistentes con el mismo SHA.

---

### User Story 3 - Bloqueo de imágenes con vulnerabilidades graves antes de publicar (Priority: P1)

Como desarrollador, quiero que cada imagen sea escaneada antes de publicarse y que el pipeline falle si
contiene vulnerabilidades altas o críticas, para que nunca se publique una imagen conocida como insegura.

**Why this priority**: Un registro con imágenes vulnerables disponibles para despliegue anula el objetivo
de "seguras"; el escaneo debe ser un gate previo a la publicación, no un reporte posterior.

**Independent Test**: Construir una imagen con un paquete con vulnerabilidad crítica corregible y comprobar
que el pipeline falla y que la imagen no aparece en GHCR; con una imagen limpia, comprobar que se publica.

**Acceptance Scenarios**:

1. **Given** una imagen construida, **When** se escanea antes de publicar, **Then** el escaneo cubre las
   dependencias del sistema operativo base y de la aplicación.
2. **Given** una imagen con una vulnerabilidad HIGH o CRITICAL con corrección disponible, **When** termina
   el escaneo, **Then** el pipeline falla, indica paquete, versión afectada y corregida, y **no** publica
   ninguna imagen.
3. **Given** una imagen sin hallazgos HIGH ni CRITICAL, **When** termina el escaneo, **Then** el pipeline
   continúa hacia la publicación.
4. **Given** una vulnerabilidad HIGH o CRITICAL sin corrección publicada, **When** termina el escaneo,
   **Then** no bloquea (mismo criterio que LAB-76) y queda registrada en el reporte.
5. **Given** una excepción justificada ya versionada para el escaneo del repositorio (LAB-76), **When** se
   escanea la imagen, **Then** se respeta la misma lista de excepciones.
6. **Given** que falla el escaneo de uno de los tres servicios, **When** termina el pipeline, **Then** no
   se publica ninguna de las tres imágenes, de modo que los tres servicios comparten siempre el mismo SHA
   publicado.

---

### User Story 4 - SBOM y provenance adjuntos a cada imagen (Priority: P2)

Como desarrollador, quiero que cada imagen publicada lleve su lista de componentes (SBOM) y su
procedencia de construcción, para auditar qué contiene y cómo se construyó.

**Why this priority**: Aporta trazabilidad y cumplimiento, pero las imágenes ya son utilizables y seguras
sin ello.

**Independent Test**: Tras una publicación, inspeccionar la imagen en el registro y verificar que tiene
adjuntos un SBOM y una atestación de provenance que referencian el commit y el workflow de origen.

**Acceptance Scenarios**:

1. **Given** una imagen publicada, **When** se consulta el registro, **Then** tiene un SBOM asociado que
   lista los paquetes del sistema operativo y de la aplicación.
2. **Given** una imagen publicada, **When** se consulta el registro, **Then** tiene una atestación de
   provenance que identifica repositorio, commit y ejecución del pipeline que la construyó.
3. **Given** un SBOM y provenance generados, **When** se descargan, **Then** corresponden al digest exacto
   de la imagen publicada.

---

### User Story 5 - Imágenes firmadas sin claves gestionadas (Priority: P2)

Como desarrollador, quiero que cada imagen publicada esté firmada con la identidad del pipeline y sin
claves que custodiar, para que el clúster pueda exigir imágenes firmadas (política de LAB-83).

**Why this priority**: Es requisito de la admisión del clúster en LAB-83, pero no bloquea publicar y
escanear imágenes mientras tanto.

**Independent Test**: Verificar la firma de una imagen publicada indicando la identidad esperada del
workflow; debe validar. Verificar una imagen sin firmar o firmada por otra identidad; debe fallar.

**Acceptance Scenarios**:

1. **Given** una imagen publicada, **When** se verifica su firma contra la identidad del workflow de este
   repositorio y su emisor de identidad, **Then** la verificación es correcta.
2. **Given** una imagen publicada, **When** se revisa la configuración del repositorio, **Then** no existe
   ninguna clave o secreto de firma almacenado.
3. **Given** que falla el paso de firma, **When** termina el pipeline, **Then** el pipeline falla y la
   imagen no queda marcada como lista para desplegar.
4. **Given** una imagen publicada con varios tags, **When** se firma, **Then** la firma se asocia al
   digest, de modo que es válida para todos los tags que apuntan a él.

---

### Edge Cases

- Servicio todavía sin código (`frontend/` e `ingest/` existen vacíos): su imagen no puede construirse con
  contenido real; ver Assumptions sobre el alcance.
- Dos merges consecutivos a `main` en poco tiempo: `latest` debe terminar apuntando al más reciente, nunca
  a una ejecución anterior que termine después.
- Publicación parcial: el pipeline falla tras publicar algunos servicios (p. ej. caída del registro); debe
  poder reejecutarse sin dejar tags ambiguos.
- El merge del PR de release de release-please crea el tag `vX.Y.Z` y debe producir además las imágenes de
  versión.
- Pull request desde un fork o de Dependabot: no dispone de permisos de publicación; no debe publicar ni
  firmar, pero sí puede construir y escanear.
- Vulnerabilidad nueva publicada tras la publicación de una imagen: fuera del alcance del gate (que solo
  actúa al construir); ver Assumptions.
- Tag de versión creado sobre un commit cuyas imágenes `:sha` ya existen: el contenido de la imagen de
  versión debe ser el del commit etiquetado.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio MUST proveer una definición de imagen de contenedor para cada servicio
  (backend, frontend e ingest), de varias etapas, de modo que la imagen final solo contenga lo necesario
  para ejecutar.
- **FR-002**: Cada imagen MUST ejecutar su proceso con un usuario no root.
- **FR-003**: Cada merge a `main` MUST construir y publicar las imágenes de los tres servicios en
  `ghcr.io/leandroamariles/<servicio>`.
- **FR-004**: Cada imagen publicada desde un merge a `main` MUST llevar el tag del SHA del commit.
- **FR-005**: El tag `latest` MUST publicarse únicamente desde `main` y MUST apuntar siempre a la última
  imagen publicada con éxito desde `main`.
- **FR-006**: Cuando release-please cree el tag `vX.Y.Z` (LAB-100), el sistema MUST publicar cada imagen
  con el tag `vX.Y.Z`, sin mover `latest`.
- **FR-007**: El sistema MUST NOT publicar imágenes desde pull requests ni desde ramas distintas de `main`
  y de los tags de versión.
- **FR-008**: Antes de publicar, el sistema MUST escanear cada imagen construida en busca de
  vulnerabilidades de severidad HIGH y CRITICAL (sistema operativo y dependencias de la aplicación).
- **FR-009**: Si el escaneo de cualquiera de los servicios encuentra vulnerabilidades HIGH o CRITICAL con
  corrección disponible, el pipeline MUST fallar y MUST NOT publicar ninguna de las imágenes de esa
  ejecución.
- **FR-010**: Las vulnerabilidades HIGH o CRITICAL sin corrección publicada MUST NOT bloquear el pipeline
  y MUST quedar visibles en el reporte del escaneo.
- **FR-011**: El escaneo de imágenes MUST respetar la misma lista de excepciones justificadas que el
  escaneo del repositorio (LAB-76).
- **FR-012**: Cada imagen publicada MUST llevar adjunto un SBOM y una atestación de provenance asociados a
  su digest.
- **FR-013**: Cada imagen publicada MUST estar firmada de forma que la firma identifique al workflow de
  este repositorio como emisor, sin claves ni secretos de firma almacenados en el repositorio.
- **FR-014**: Si falla la firma, el pipeline MUST fallar de forma visible.
- **FR-015**: El pipeline MUST autenticarse en el registro con la identidad del propio workflow, sin
  credenciales personales de larga duración, con los mínimos permisos necesarios.
- **FR-016**: Las ejecuciones concurrentes sobre `main` MUST NOT dejar `latest` apuntando a una imagen más
  antigua que la última fusionada.
- **FR-017**: El resultado de cada ejecución (imágenes, tags y digests publicados) MUST ser consultable
  desde el resumen de la ejecución del pipeline.
- **FR-018**: Las imágenes MUST publicarse únicamente si las comprobaciones de CI existentes (LAB-15) han
  pasado para ese commit.

### Key Entities

- **Imagen de servicio**: artefacto desplegable de backend, frontend o ingest; identificada por servicio y
  digest, con uno o varios tags.
- **Tag**: etiqueta sobre un digest: SHA del commit, `latest` o versión `vX.Y.Z`.
- **SBOM**: inventario de componentes de la imagen, asociado a su digest.
- **Provenance**: atestación de cómo, dónde y desde qué commit se construyó la imagen.
- **Firma**: prueba criptográfica de que la imagen la publicó el workflow de este repositorio.
- **Reporte de escaneo**: hallazgos de vulnerabilidades de la imagen y su resultado (pasa/falla).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El 100 % de los merges a `main` con CI en verde terminan con las tres imágenes disponibles
  en el registro bajo el tag del commit, sin intervención manual.
- **SC-002**: Desde el merge hasta que las imágenes están publicadas, firmadas y con SBOM transcurren menos
  de 20 minutos en el caso habitual.
- **SC-003**: 0 imágenes con vulnerabilidades HIGH o CRITICAL corregibles llegan al registro, verificado
  introduciendo deliberadamente una y comprobando que no se publica.
- **SC-004**: El 100 % de las imágenes publicadas pasan la verificación de firma contra la identidad del
  workflow y tienen SBOM y provenance consultables.
- **SC-005**: Un desarrollador puede arrancar cualquier versión publicada con un solo comando de ejecución
  de contenedor, sin construir nada localmente.
- **SC-006**: 0 imágenes publicadas ejecutan su proceso como root.
- **SC-007**: Cada release (`vX.Y.Z`) tiene sus tres imágenes etiquetadas con esa versión en menos de
  30 minutos desde la creación del tag.

## Assumptions

- Propietario del registro: `leandroamariles`; los paquetes de GHCR se asocian a este repositorio y su
  visibilidad se gestiona en GitHub.
- Nombres de servicio: `backend`, `frontend` e `ingest`, formando `ghcr.io/leandroamariles/<servicio>`.
- Hoy solo `backend/` tiene código; `frontend/` e `ingest/` están vacíos. Esta historia entrega la
  definición de imagen y el pipeline para los tres; si un servicio aún no tiene código ejecutable, su
  imagen se construye sobre un contenido mínimo y se completa cuando llegue su historia. No se implementan
  funcionalidades de aplicación.
- Los tags de versión se crean con un token dedicado en LAB-100 precisamente para que disparen otros
  workflows; esta historia depende de ese comportamiento.
- Se reutiliza el mismo criterio de severidad y la lista de excepciones de LAB-76; el escaneo del
  repositorio y el de imágenes son complementarios.
- Fuera de alcance: despliegue al clúster, política de admisión que exige la firma (LAB-83), reescaneo
  periódico de imágenes ya publicadas, retención/limpieza de imágenes antiguas y multi-arquitectura
  (solo la del runner).
- Depende de LAB-15 (CI en GitHub Actions) y LAB-76 (escaneo Trivy y excepciones); relacionada con LAB-100
  (versionado) y LAB-83 (Kyverno).
