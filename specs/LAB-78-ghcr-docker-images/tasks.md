---

description: "Task list for LAB-78 - Imágenes Docker publicadas en GHCR con escaneo y SBOM"
---

# Tasks: Imágenes Docker publicadas en GHCR con escaneo y SBOM

**Input**: Design documents from `/specs/LAB-78-ghcr-docker-images/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/image-pipeline.md, quickstart.md

**Tests**: No se pidieron tests de código. Las tareas de validación ejecutan los escenarios de
[quickstart.md](quickstart.md) (construcción local, `trivy` local, `actionlint` y ejecuciones reales del
workflow en un PR, en `main` y en un tag).

**Organization**: Tareas agrupadas por historia de usuario. Casi todo el cambio vive en
`.github/workflows/images.yml`, por eso las tareas que lo editan son secuenciales y no llevan `[P]`;
solo se marcan `[P]` las que tocan archivos distintos. Las historias US1, US3 y US2 son todas P1; se
ordenan por dependencia técnica (Dockerfiles → escaneo previo a publicar → publicación), porque publicar
sin el gate de escaneo violaría FR-009.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: Historia de usuario a la que pertenece (US1..US5)

## Path Conventions

- Workflow nuevo: `.github/workflows/images.yml`; Dependabot: `.github/dependabot.yml`
- Dockerfiles: `backend/Dockerfile`, `frontend/Dockerfile`, `ingest/Dockerfile` (+ `.dockerignore` en cada uno)
- Escaneo: reutiliza `.trivyignore` de la raíz (LAB-76), sin modificarlo salvo excepciones justificadas
- Documentación del feature: `specs/LAB-78-ghcr-docker-images/`
- Estilo: YAML con 2 espacios; comentarios solo donde el motivo no sea obvio; `aquasecurity/trivy-action`
  con el mismo SHA que `.github/workflows/ci.yml`; el resto de acciones fijadas a la versión exacta con el
  tag en un comentario; identificadores en inglés
- Commits: `LAB-78 type(scope): description in english`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirmar versiones y supuestos externos antes de escribir configuración que dependa de ellos.

- [X] T001 Obtener la última versión estable y el SHA de commit completo de `docker/setup-buildx-action`,
  `docker/login-action` (v3), `docker/build-push-action` (v6), `docker/metadata-action` (v5) y
  `sigstore/cosign-installer` con `gh api repos/<owner>/<repo>/releases/latest --jq .tag_name` y
  `gh api repos/<owner>/<repo>/git/ref/tags/<tag> --jq .object.sha` (resolver tags anotados al commit);
  leer también el SHA de `aquasecurity/trivy-action` ya fijado en `.github/workflows/ci.yml`; anotar
  acción, tag y SHA en `specs/LAB-78-ghcr-docker-images/research.md` (decisión 10)
- [X] T002 Resolver las etiquetas y digests actuales de las imágenes base con
  `docker buildx imagetools inspect <imagen>:<tag>`: `eclipse-temurin:21-jdk` y `21-jre`,
  `node:22-alpine`, `nginxinc/nginx-unprivileged` (variante alpine, versión estable), `python:3.12-slim`
  y la imagen de `uv` (`ghcr.io/astral-sh/uv`); anotar `imagen:tag@sha256:digest` en
  `specs/LAB-78-ghcr-docker-images/research.md` (decisión 12) para fijarlos en los Dockerfiles
- [X] T003 Confirmar con `gh api repos/<owner>/<repo>/rulesets` que `ci-gate` es un check exigido en
  `main` (supuesto de FR-018, research.md decisión 13) y con
  `gh api repos/<owner>/<repo>/actions/permissions/workflow` que `GITHUB_TOKEN` puede escribir paquetes;
  anotar el resultado en `specs/LAB-78-ghcr-docker-images/research.md` (decisión 13). Si `ci-gate` no es
  exigido, detenerse y reportarlo antes de continuar

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Esqueleto del workflow y archivos de exclusión que todas las historias necesitan.

**⚠️ CRITICAL**: Ninguna historia puede construir una imagen sin su `.dockerignore` ni ejecutarse sin el
workflow base.

- [X] T004 [P] Crear `backend/.dockerignore` excluyendo `target/`, `.git`, `.idea/`, `*.iml`, `.env`,
  `*.local`, `qodana.sarif.json` y `qodana.yaml`
- [X] T005 [P] Crear `frontend/.dockerignore` excluyendo `node_modules/`, `dist/`, `.angular/`, `.git`,
  `.env`, `*.local` y `.gitkeep`
- [X] T006 [P] Crear `ingest/.dockerignore` excluyendo `.venv/`, `__pycache__/`, `*.pyc`,
  `.ipynb_checkpoints/`, `.git`, `.env`, `*.local` y `.gitkeep`
- [X] T007 Crear `.github/workflows/images.yml` con `name: Images`, disparadores `push` a `main`, `push` de
  tags `v*.*.*` y `pull_request` con `paths` (`backend/**`, `frontend/**`, `ingest/**`, `.trivyignore`,
  `.github/workflows/images.yml`); `permissions: contents: read` a nivel de workflow; `concurrency` con
  grupo por ref y `cancel-in-progress: true` en PRs y un grupo único `images-publish` con
  `cancel-in-progress: false` para `main` y tags (research.md decisión 9); `env` con
  `IMAGE_NAMESPACE: ghcr.io/leandroamariles`; esqueleto de job `build-scan` con `timeout-minutes: 20`,
  `strategy.fail-fast: false` y `matrix.service: [backend, frontend, ingest]`, solo con
  `actions/checkout@v7` (el contenido llega en las historias)

**Checkpoint**: Workflow base y exclusiones listos; las historias pueden empezar.

---

## Phase 3: User Story 1 - Imágenes mínimas y no root (Priority: P1) 🎯 MVP

**Goal**: Backend, frontend e ingest se construyen en imágenes multi-stage, pequeñas y con usuario no root.

**Independent Test**: `docker build` de las tres imágenes; `docker run --rm --entrypoint id <imagen>`
muestra un uid distinto de 0; el backend responde `UP` en `/actuator/health` (quickstart §1).

### Implementation for User Story 1

- [X] T008 [P] [US1] Crear `backend/Dockerfile`: etapa `build` con la imagen JDK 21 fijada por digest
  (T002), copia de `.mvn`/`mvnw`/`pom.xml` antes de `src` para cachear dependencias,
  `RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package` (Checkstyle, PMD y JaCoCo
  están enlazados a `verify`, no se ejecutan), extracción de capas del jar con
  `java -Djarmode=tools -jar target/*.jar extract --layers --destination extracted`; etapa final con la
  imagen JRE 21 fijada por digest, grupo y usuario `10001`, `COPY --chown` de las capas
  (`dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`), `USER 10001`,
  `EXPOSE 8080` y `ENTRYPOINT ["java","-jar","application/<jar>"]` o `JarLauncher` según la salida de
  `extract`; sin `HEALTHCHECK` y sin gestores de paquetes en la etapa final (research.md decisión 12)
- [X] T009 [P] [US1] Crear `frontend/Dockerfile`: etapa `build` en `node:22-alpine` fijada por digest;
  copia `package*.json` y, solo si existe `package.json`, ejecuta `npm ci` y `npm run build`; si no
  existe, genera `dist/browser/index.html` con una página de relevo (texto "ShopLab Helpdesk — frontend
  pendiente") para que la imagen sea construible hoy; etapa final `nginxinc/nginx-unprivileged` fijada
  por digest sirviendo `dist/browser` en `/usr/share/nginx/html`, usuario por defecto (101), `EXPOSE 8080`
  y un comentario indicando que la ruta `dist/browser` se confirma en la historia del frontend
- [X] T010 [P] [US1] Crear `ingest/Dockerfile`: etapa `build` en `python:3.12-slim` fijada por digest con
  `uv` copiado de su imagen fijada; si existe `pyproject.toml` ejecuta
  `uv sync --frozen --no-dev` en `/app/.venv` y copia el código, si no, crea `/app/site/index.html` de
  relevo; etapa final `python:3.12-slim` fijada por digest con grupo y usuario `10001`, `ARG APP_MODULE=app.main:app`,
  `CMD` con `uvicorn` sobre `${APP_MODULE}` cuando hay proyecto o `python -m http.server 8000` sobre
  `/app/site` cuando no, `EXPOSE 8000`; sin `pip install` ni gestores de paquetes en la etapa final
- [X] T011 [US1] Construir las tres imágenes localmente (`docker build -t shoplab/<servicio>:local <servicio>`)
  y comprobar con `docker run --rm --entrypoint id shoplab/<servicio>:local` que ninguna corre como uid 0;
  arrancar `shoplab/backend:local` y comprobar `curl localhost:8080/actuator/health` (quickstart §1);
  corregir los Dockerfiles según el resultado, documentando en `quickstart.md` cualquier variable de
  entorno que el backend necesite para arrancar en el contenedor
- [X] T012 [US1] Comprobar el tamaño y el contenido de la etapa final de cada imagen
  (`docker history`, `docker run --rm --entrypoint sh <imagen> -c 'ls /'` o equivalente): sin código
  fuente, sin `.m2`, sin `node_modules` ni herramientas de build (escenario 3 de la historia 1); ajustar
  los `COPY` si aparece algo sobrante

**Checkpoint**: Las tres imágenes se construyen y corren sin privilegios; sin workflow aún.

---

## Phase 4: User Story 3 - Bloqueo de imágenes vulnerables antes de publicar (Priority: P1)

**Goal**: Cada imagen se construye y escanea en el pipeline; un hallazgo HIGH/CRITICAL corregible falla el
pipeline y bloquea la publicación de las tres.

**Independent Test**: Un PR con una base vulnerable falla en `build-scan`; con la imagen limpia pasa; en
ningún caso se escribe en GHCR (quickstart §2, §3 y §6).

### Implementation for User Story 3

- [X] T013 [US3] En `.github/workflows/images.yml`, completar el job `build-scan` con
  `docker/setup-buildx-action` y `docker/build-push-action` (SHA de T001): `context: ${{ matrix.service }}`,
  `push: false`, `outputs: type=docker,dest=${{ runner.temp }}/${{ matrix.service }}.tar`,
  `cache-from` y `cache-to: type=gha,scope=${{ matrix.service }},mode=max`, `provenance: false`
- [X] T014 [US3] Añadir a `build-scan` el paso Trivy con `aquasecurity/trivy-action` (mismo SHA que
  `ci.yml`): `input: ${{ runner.temp }}/${{ matrix.service }}.tar`, `scanners: vuln`,
  `severity: HIGH,CRITICAL`, `ignore-unfixed: true`, `trivyignores: .trivyignore`, `format: table`,
  `exit-code: '1'`; antes de él, un paso de reporte con `format: sarif`/`exit-code: '0'` y subida con
  `actions/upload-artifact` (`trivy-image-<servicio>`, `if: always()`, retención 14 días), siguiendo el
  patrón de dos pasadas de `ci.yml` (reporte que nunca falla, luego gate)
- [X] T015 [US3] Añadir al workflow un job `publish` vacío de comprobación, `needs: build-scan`,
  `if: github.event_name != 'pull_request'`, con un único paso `echo` (marcador que T018 sustituye), solo
  para verificar que las tres instancias de la matriz condicionan su arranque (FR-009)
- [X] T016 [US3] Validar con `actionlint .github/workflows/images.yml` y ensayar el escaneo local
  `trivy image --severity HIGH,CRITICAL --ignore-unfixed --ignorefile .trivyignore --exit-code 1 shoplab/backend:local`
  (quickstart §2); abrir un PR de prueba que toque `backend/**` y comprobar que corren los tres
  `build-scan` y que `publish` no corre
- [X] T017 [US3] En el mismo PR de prueba (no se fusiona), cambiar temporalmente la imagen base del
  `backend/Dockerfile` por una con un CVE HIGH/CRITICAL corregible conocido y comprobar que
  `build-scan (backend)` falla con la tabla de hallazgos; añadir su id a `.trivyignore` con motivo y
  responsable y comprobar que pasa; revertir ambos cambios (quickstart §6, escenarios 2 y 5 de la historia 3)

**Checkpoint**: El gate de escaneo funciona y condiciona la publicación; todavía no se publica nada.

---

## Phase 5: User Story 2 - Publicación versionada en GHCR (Priority: P1)

**Goal**: Cada merge a `main` y cada tag `vX.Y.Z` publican las tres imágenes con los tags correctos.

**Independent Test**: Tras fusionar a `main` existen las tres imágenes con tag SHA y `latest`; tras el
tag de release existen con `vX.Y.Z` y `latest` no cambia (quickstart §4 y §5).

### Implementation for User Story 2

- [X] T018 [US2] En `.github/workflows/images.yml`, sustituir el marcador de `publish` por el job real:
  `needs: build-scan`, `if: github.event_name != 'pull_request'`, `timeout-minutes: 20`, misma matriz,
  `permissions: contents: read` y `packages: write`, `actions/checkout@v7`, `docker/setup-buildx-action`
  y `docker/login-action@v3` con `registry: ghcr.io`, `username: ${{ github.actor }}`,
  `password: ${{ secrets.GITHUB_TOKEN }}` (research.md decisión 8)
- [X] T019 [US2] Añadir a `publish` el paso `docker/metadata-action@v5` (id `meta`) con
  `images: ${{ env.IMAGE_NAMESPACE }}/${{ matrix.service }}` y los tags
  `type=sha,prefix=,format=long`, `type=raw,value=latest,enable={{is_default_branch}}` y
  `type=ref,event=tag`; el tag SHA se limita a eventos sobre rama con
  `enable=${{ github.ref_type == 'branch' }}` (research.md decisión 5)
- [X] T020 [US2] Añadir a `publish`, antes de construir y solo en eventos de tag
  (`if: github.ref_type == 'tag'`), un paso que ejecute
  `docker buildx imagetools inspect ${IMAGE_NAMESPACE}/${{ matrix.service }}:${{ github.ref_name }}` y,
  si la imagen ya existe, falle con `::error::` indicando que el tag de versión es inmutable y no se
  sobrescribe (research.md decisión 11)
- [X] T021 [US2] Añadir a `publish` el paso `docker/build-push-action@v6` (id `build`):
  `context: ${{ matrix.service }}`, `push: true`, `tags: ${{ steps.meta.outputs.tags }}`,
  `labels: ${{ steps.meta.outputs.labels }}`, `cache-from: type=gha,scope=${{ matrix.service }}`; sin
  `cache-to` (solo lectura de la caché escaneada). `sbom` y `provenance` se añaden en T024 (US4)
- [X] T022 [US2] Añadir a `publish` un paso final que escriba en `$GITHUB_STEP_SUMMARY` una tabla
  servicio / tags / digest (`steps.build.outputs.digest`, `steps.meta.outputs.tags`) para cumplir FR-017
- [X] T023 [US2] Validar con `actionlint`; fusionar el PR de la funcionalidad a `main` y comprobar con
  `docker buildx imagetools inspect ghcr.io/leandroamariles/<servicio>:latest` y `:<sha-completo>` que
  las tres imágenes existen con el mismo digest y que el job summary lista tags y digests (quickstart §4);
  verificar en GitHub → Packages que los tres paquetes están enlazados al repositorio (etiqueta
  `org.opencontainers.image.source`) y ajustar su visibilidad si hace falta

**Checkpoint**: `main` publica las tres imágenes con tag SHA y `latest`; el flujo de tag queda por validar
en la fase final.

---

## Phase 6: User Story 4 - SBOM y provenance (Priority: P2)

**Goal**: Cada imagen publicada lleva SBOM y provenance asociados a su digest.

**Independent Test**: `docker buildx imagetools inspect ... --format '{{ json .SBOM }}'` y
`'{{ json .Provenance }}'` devuelven contenido con el commit fusionado (quickstart §4, paso 3).

### Implementation for User Story 4

- [X] T024 [US4] En `.github/workflows/images.yml`, añadir al paso `docker/build-push-action@v6` de
  `publish` las opciones `sbom: true` y `provenance: mode=max`
- [X] T025 [US4] Tras el siguiente merge a `main`, comprobar con `docker buildx imagetools inspect`
  (`.SBOM` y `.Provenance`) que cada una de las tres imágenes tiene SBOM con paquetes del SO y de la
  aplicación y provenance que identifica repositorio, commit y ejecución, y que ambos corresponden al
  digest publicado (escenarios 1-3 de la historia 4)

**Checkpoint**: Las imágenes publicadas llevan SBOM y provenance.

---

## Phase 7: User Story 5 - Firma cosign keyless (Priority: P2)

**Goal**: Cada digest publicado está firmado con la identidad del workflow, sin claves almacenadas.

**Independent Test**: `cosign verify` con la identidad del workflow y el emisor de GitHub Actions es
correcto para las tres imágenes (quickstart §4, paso 4).

### Implementation for User Story 5

- [X] T026 [US5] En `.github/workflows/images.yml`, añadir a `publish` el permiso `id-token: write` (junto
  a `packages: write`) y, tras el paso de build, `sigstore/cosign-installer` (SHA de T001) y un paso
  `cosign sign --yes ${IMAGE_NAMESPACE}/${{ matrix.service }}@${{ steps.build.outputs.digest }}`; firmar
  el digest y no los tags (research.md decisión 7)
- [X] T027 [US5] Añadir a `publish` un paso `cosign verify` sobre el mismo digest con
  `--certificate-identity-regexp '^https://github.com/${{ github.repository }}/\.github/workflows/images\.yml@.*'`
  y `--certificate-oidc-issuer https://token.actions.githubusercontent.com`; un fallo de firma o de
  verificación debe hacer fallar el job (FR-014); extender la tabla de T022 con la columna "firma"
- [X] T028 [US5] Tras el siguiente merge a `main`, ejecutar localmente el `cosign verify` de
  `contracts/image-pipeline.md` para `backend`, `frontend` e `ingest`; comprobar que verificar con otra
  identidad falla y que el repositorio no tiene secretos de firma (Settings → Secrets) (escenarios 1 y 2
  de la historia 5)

**Checkpoint**: Todas las historias funcionan en `main`.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Release, mantenimiento y documentación.

- [X] T029 [P] Ampliar `.github/dependabot.yml` con tres entradas `package-ecosystem: docker` para
  `/backend`, `/frontend` e `/ingest`, mismo `schedule: weekly`, `open-pull-requests-limit: 5` y
  `commit-message.prefix: "chore(deps)"` que las entradas existentes, para que las bases fijadas por
  digest se actualicen solas (research.md decisión 4)
- [X] T030 [P] Añadir a `README.md` una sección breve "Imágenes Docker" en español: registro y nombres,
  política de tags (`<sha>`, `latest`, `vX.Y.Z`), cómo verificar la firma con `cosign verify` y cómo
  añadir una excepción justificada en `.trivyignore` si un CVE de la base bloquea el pipeline
- [ ] T031 Validar el flujo de release (quickstart §5): fusionar el PR de release de release-please
  (LAB-100) y comprobar que el tag `vX.Y.Z` dispara `Images`, que las tres imágenes quedan con ese tag,
  que `latest` no cambia de digest y que reejecutar la ejecución del tag falla informando que el tag ya
  existe sin sobrescribirlo (escenarios 4 y 5 de la historia 2)
- [ ] T032 Recorrer `specs/LAB-78-ghcr-docker-images/quickstart.md` completo y la checklist de la spec
  (`checklists/requirements.md`); anotar en `quickstart.md` cualquier ajuste surgido durante la
  implementación y marcar como hechas las tareas completadas en este archivo
- [ ] T033 Revisar antes de cada commit (CLAUDE.md §8): `git status --short` sin archivos sueltos y
  `git diff --staged` solo con cambios del feature; commits `LAB-78 type(scope): ...`; el título del PR
  usa `feat(images): [LAB-78] <descripción>` o `ci(images): [LAB-78] <descripción>` según se quiera o no
  disparar una release (ver CLAUDE.md §5.1 y §5.2)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T001-T003 pueden hacerse en paralelo de forma manual.
- **Foundational (Phase 2)**: depende de T001 (versiones) solo para T007; T004-T006 son independientes.
  Bloquea todas las historias.
- **US1 (Phase 3)**: depende de Foundational y de T002; sin dependencias de otras historias.
- **US3 (Phase 4)**: depende de US1 (necesita Dockerfiles) y de T007.
- **US2 (Phase 5)**: depende de US3 (el job `publish` cuelga de `build-scan`).
- **US4 (Phase 6)** y **US5 (Phase 7)**: dependen de US2 (modifican el job `publish`); entre sí son
  independientes pero editan el mismo archivo, así que se aplican secuencialmente.
- **Polish (Phase 8)**: T029 y T030 pueden hacerse desde Foundational; T031 requiere todas las historias.

### User Story Dependencies

- **US1 (P1)**: independiente.
- **US3 (P1)**: requiere US1.
- **US2 (P1)**: requiere US3.
- **US4 (P2)** y **US5 (P2)**: requieren US2.

### Within Each User Story

- Dockerfiles antes de la construcción en CI; construcción antes del escaneo; escaneo antes de publicar;
  publicación antes de SBOM/provenance y firma.
- Las validaciones con PR o `main` van al final de cada historia.

### Parallel Opportunities

- T004, T005 y T006 (`.dockerignore`) en paralelo.
- T008, T009 y T010 (los tres Dockerfiles) en paralelo.
- T029 y T030 (Dependabot y README) en paralelo con cualquier fase posterior a Foundational.

---

## Parallel Example: User Story 1

```bash
# Los tres Dockerfiles tocan archivos distintos:
Task: "Crear backend/Dockerfile (JDK build + JRE final, usuario 10001)"
Task: "Crear frontend/Dockerfile (Node build + nginx-unprivileged)"
Task: "Crear ingest/Dockerfile (uv build + Python slim, usuario 10001)"
```

---

## Implementation Strategy

### MVP First (User Story 1 → 3 → 2)

1. Completar Setup y Foundational.
2. Completar US1 (Dockerfiles) y validar localmente.
3. Completar US3 (build + escaneo en CI, sin publicar).
4. Completar US2 (publicación con tags): **STOP y VALIDAR** en `main` (imágenes con SHA y `latest`).
5. Este es el MVP: imágenes versionadas y escaneadas. SBOM, provenance y firma se añaden después.

### Incremental Delivery

1. US1 + US3 + US2 → imágenes escaneadas y publicadas (MVP).
2. US4 → SBOM y provenance.
3. US5 → firma, lo que habilita la política de admisión de LAB-83.
4. Polish → Dependabot, README y validación del flujo de release.

### Notas

- Una sola PR con todo es viable; si se divide, hacerlo en los puntos de checkpoint de US3 (sin
  publicar) y US2 (publicar). No fusionar a `main` un `publish` sin `build-scan` previo.
- Las pruebas negativas (T017) usan un PR que no se fusiona.
- Mantener `publish` sin `cache-to` para que solo `build-scan` escriba la caché escaneada.
- [P] tasks = archivos distintos, sin dependencias
- [Story] mapea cada tarea a su historia para trazabilidad
- Commit tras cada tarea o grupo lógico
