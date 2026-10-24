# Research: Imágenes Docker en GHCR

## 1. Workflow separado de `ci.yml`

- **Decisión**: nuevo `.github/workflows/images.yml`; no se añade a `ci-gate`.
- **Rationale**: necesita disparadores propios (push de tags `v*`), permisos de escritura de paquetes y
  OIDC que no deben existir en el CI de PRs. Un fallo de publicación no debe bloquear PRs de código.
- **Alternativas**: job dentro de `ci.yml` — mezcla permisos y obliga a condicionar cada paso por evento.

## 2. Disparadores y alcance de publicación

- **Decisión**: `push` a `main`, `push` de tags `v*.*.*` y `pull_request` (solo build + scan, filtrado por
  rutas `backend/**`, `frontend/**`, `ingest/**`, `.trivyignore` y el propio workflow). `publish` lleva
  `if: github.event_name != 'pull_request'`.
- **Rationale**: FR-007. El PR valida que los Dockerfiles construyen y pasan el escaneo antes de fusionar
  sin tocar el registro. Los tags creados por release-please con `RELEASE_PLEASE_TOKEN` (PAT, LAB-100)
  sí disparan workflows, a diferencia de `GITHUB_TOKEN`.
- **Alternativas**: `release: published` — depende de que exista el GitHub Release además del tag y
  añade un segundo disparador para el mismo hecho.

## 3. Todo o nada: dos jobs con matriz

- **Decisión**: `build-scan` (matriz backend/frontend/ingest, `fail-fast: false`) y `publish` (matriz) con
  `needs: build-scan`. Como `needs` exige éxito de todas las instancias de la matriz, un solo escaneo
  fallido impide publicar los tres (FR-009, escenario 6 de la historia 3).
- **Rationale**: `fail-fast: false` deja ver todos los hallazgos de una ejecución en lugar de uno a uno.
- **Alternativas**: un job por servicio con build→scan→push — un servicio podría publicarse mientras
  otro falla, rompiendo la coherencia de SHA entre los tres.

## 4. Escanear antes de publicar sin publicar

- **Decisión**: `build-scan` construye con `docker/build-push-action@v6` (`push: false`,
  `outputs: type=docker,dest=<tar>`, `cache-to: type=gha,scope=<servicio>,mode=max`) y Trivy escanea con
  `input: <tar>`, `scanners: vuln`, `severity: HIGH,CRITICAL`, `ignore-unfixed: true`,
  `trivyignores: .trivyignore`, `exit-code: 1`. `publish` reconstruye con `cache-from` del mismo `scope`,
  lo que reutiliza las capas ya escaneadas y publica.
- **Rationale**: SBOM y provenance de BuildKit (decisión 6) solo se generan al publicar el índice de
  manifiestos, por lo que no se puede reusar el `.tar` local sin perderlos. Reconstruir desde caché da el
  mismo contenido de capas. Las imágenes base se fijan por versión + digest (Dependabot las actualiza) y
  las etapas finales no ejecutan gestores de paquetes del sistema, de modo que lo escaneado es lo
  publicado.
- **Riesgo residual**: si la caché `gha` se desaloja entre jobs, `publish` reconstruye de cero. Con bases
  fijadas por digest y dependencias bloqueadas (`pom.xml`, `package-lock.json`, `uv.lock`) el contenido es
  equivalente. Si se quisiera eliminar el riesgo del todo habría que publicar a una referencia temporal,
  lo que contradice FR-009.
- **Alternativas**: publicar con un tag temporal y escanear después — la imagen ya estaría en el registro;
  `crane`/`skopeo` para empujar el `.tar` — pierde las atestaciones de BuildKit que pide la historia.

## 5. Estrategia de tags

- **Decisión**: `docker/metadata-action@v5` con
  `type=sha,prefix=,format=long` (solo en `main`), `type=raw,value=latest,enable={{is_default_branch}}`
  y `type=ref,event=tag` (solo en tags, produce `vX.Y.Z`). Etiquetas OCI estándar (`source`, `revision`,
  `version`) vienen de la misma acción.
- **Rationale**: SHA largo (40 caracteres) sin prefijo cumple literalmente `:sha` y es inequívoco.
  `is_default_branch` es falso en eventos de tag, así que `latest` nunca se mueve en un release (FR-005,
  FR-006). `org.opencontainers.image.source` enlaza el paquete con el repositorio para heredar sus permisos.
- **Alternativas**: SHA corto (`sha-abc1234`) — posible ambigüedad; `type=semver` — descompone la versión
  en `X.Y` y `X`, que la historia no pide.

## 6. SBOM y provenance

- **Decisión**: `sbom: true` y `provenance: mode=max` en el `build-push-action` de `publish`.
- **Rationale**: son las opciones que nombra la historia; se guardan como atestaciones OCI vinculadas al
  digest (FR-012). `mode=max` incluye los argumentos de build y el commit.
- **Alternativas**: SBOM con Trivy/Syft aparte — otro artefacto fuera del digest y más pasos.

## 7. Firma con cosign keyless

- **Decisión**: `sigstore/cosign-installer` y, por cada servicio, `cosign sign --yes <imagen>@<digest>`
  usando el digest que devuelve `build-push-action`; después `cosign verify` con
  `--certificate-identity-regexp` del workflow del repo y `--certificate-oidc-issuer
  https://token.actions.githubusercontent.com` como comprobación inmediata. Permiso `id-token: write`.
- **Rationale**: firmar el digest (no el tag) cubre todos los tags y es lo que Kyverno verificará en LAB-83
  (FR-013, escenario 4 de la historia 5). Sin claves almacenadas. La verificación inmediata convierte un
  firmado defectuoso en fallo visible (FR-014).
- **Alternativas**: firma con clave en secreto — viola "keyless"; solo atestación de GitHub — Kyverno
  espera firmas cosign.

## 8. Autenticación y permisos

- **Decisión**: `docker/login-action@v3` con `registry: ghcr.io`, `username: ${{ github.actor }}`,
  `password: ${{ secrets.GITHUB_TOKEN }}`. Permisos a nivel de workflow `contents: read`; en `publish`:
  `packages: write`, `id-token: write`.
- **Rationale**: FR-015. `build-scan` no necesita login ni permisos de escritura, por lo que PRs de forks y
  Dependabot funcionan igual.
- **Alternativas**: PAT personal — credencial de larga duración.

## 9. Concurrencia y `latest`

- **Decisión**: `concurrency` con grupo por ref en PRs (`cancel-in-progress: true`) y un grupo único
  `images-publish` sin cancelación para `main` y tags.
- **Rationale**: GitHub ejecuta de a un job por grupo y, entre los pendientes, conserva solo el más
  reciente, así que `latest` nunca se mueve hacia atrás (FR-016) y ninguna publicación en curso se
  corta a mitad (evita estados parciales).
- **Alternativas**: cancelar en curso — puede dejar un servicio publicado y otro no.

## 10. Versiones de acciones

- **Decisión**: reutilizar el SHA de `aquasecurity/trivy-action` ya fijado en `ci.yml`. Las acciones de
  Docker y cosign se fijan a la versión exacta vigente en implementación (comentario con la versión),
  y Dependabot (`github-actions`) las mantiene actualizadas.
- **Rationale**: consistencia con LAB-15/LAB-76 y trazabilidad ante ataques de cadena de suministro.

## 11. Inmutabilidad del tag de versión

- **Decisión**: en eventos de tag, antes de publicar, `docker buildx imagetools inspect <imagen>:vX.Y.Z`;
  si existe, el job `publish` falla con un mensaje claro y no sobrescribe.
- **Rationale**: escenario 5 de la historia 2 y borde de LAB-100 ("el workflow no sobrescribe y reporta el
  conflicto"). Las reejecuciones de `main` sí repiten `:<sha>`, que es idempotente por commit.
- **Alternativas**: sobrescribir y anotar en el resumen — rompe el significado de una versión.

## 12. Dockerfiles

- **Backend**: etapa `build` en `eclipse-temurin:21-jdk` con `./mvnw -B -ntp -DskipTests package`
  (caché de `~/.m2` con `--mount=type=cache`), extracción de capas del jar (`java -Djarmode=tools`
  `extract --layers` de Spring Boot) y etapa final en `eclipse-temurin:21-jre` con usuario `10001`,
  `EXPOSE 8080`, `ENTRYPOINT` con `java` y `JarLauncher`/`-jar`. Sin `HEALTHCHECK` (la JRE no trae
  `curl`; el orquestador usará `/actuator/health`, que ya está expuesto).
  Los plugins Checkstyle/PMD/JaCoCo están enlazados a `verify`, por lo que `package` no los ejecuta.
- **Frontend**: etapa `build` en `node:22-alpine`; si existe `package.json` hace `npm ci && npm run build`,
  si no genera una página de relevo; etapa final `nginxinc/nginx-unprivileged` (usuario 101, puerto 8080).
  La carpeta de salida del build de Angular se fijará en la historia del frontend (supuesto: `dist/browser`).
- **Ingest**: etapa `build` en `python:3.12-slim` con `uv sync --frozen --no-dev` si hay `pyproject.toml`;
  etapa final `python:3.12-slim` con usuario `10001`; si no hay proyecto, sirve un directorio con página de
  relevo con `python -m http.server`. Con proyecto real, `CMD` es `uvicorn` con `ARG APP_MODULE`.
- **Rationale**: relevo = cumple "imagen construible para los 3" (spec, Assumptions) sin inventar código de
  aplicación; el `if` en el `RUN` sigue el patrón "detectar proyecto" de `ci.yml`. `.dockerignore` excluye
  `.git`, `target`, `node_modules`, `.venv`, `.env`.
- **Alternativas**: omitir en la matriz los servicios sin código — la historia exige las 3 imágenes.

## 13. CI previo (FR-018)

- **Decisión**: no se añade `workflow_run` ni se reejecuta CI. Se apoya en la protección de rama de LAB-77:
  `main` solo recibe commits cuyo PR pasó `ci-gate`, y los tags `v*` los crea release-please sobre `main`.
- **Rationale**: duplicar CI alarga el flujo sin añadir garantía; `build-scan` ya compila y escanea.
  Documentado como dependencia en el contrato.
- **Alternativas**: `workflow_run` sobre CI — no cubre tags (CI no corre en pushes de tags) y complica el
  SHA a construir.

## 15. Versiones fijadas (verificadas el 2026-10-24)

Acciones (por SHA de commit completo; la historia exige `login-action@v3` y `build-push-action@v6`, por lo
que se fija el último patch de esas líneas aunque existen v4 y v7; Dependabot propondrá los saltos):

| Acción | Tag | SHA |
|---|---|---|
| `docker/setup-buildx-action` | v4.4.1 | f87e5991a6d7451dcb8d9637bfbc97413f497069 |
| `docker/login-action` | v3.7.0 | c94ce9fb468520275223c153574b00df6fe4bcc9 |
| `docker/build-push-action` | v6.19.2 | 10e90e3645eae34f1e60eeb005ba3a3d33f178e8 |
| `docker/metadata-action` | v6.2.0 | dc802804100637a589fabce1cb79ff13a1411302 |
| `sigstore/cosign-installer` | v4.1.2 | 6f9f17788090df1f26f669e9d70d6ae9567deba6 |
| `aquasecurity/trivy-action` | v0.36.0 | ed142fd0673e97e23eac54620cfb913e5ce36c25 (igual que `ci.yml`) |

Imágenes base (etiqueta + digest):

| Imagen | Digest |
|---|---|
| `eclipse-temurin:21-jdk` | sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d |
| `eclipse-temurin:21-jre` | sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99 |
| `node:22-alpine` | sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914 |
| `nginxinc/nginx-unprivileged:stable-alpine` (nginx 1.30.6) | sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03 |
| `python:3.12-slim` | sha256:a0f57064cbdcd6285fa45f51237ab0b35ea8ebaed06a1f5973e89e19dfb86704 |
| `ghcr.io/astral-sh/uv` (0.13.5) | sha256:32a7bb39b496babbd997b0a39a4d66c126bdaf5661b302bf6f06abda8deca6ff |

Supuestos de repositorio comprobados: el ruleset `main-protect` exige `ci-gate` y `pr-title`;
`default_workflow_permissions` es `read`, por lo que el workflow declara `packages: write` explícitamente
en el job `publish` (el token se concede por job, no por la configuración del repo).

## 16. Ajustes surgidos al implementar

- **Concurrencia**: el grupo quedó por ref (`images-main`, `images-<tag>`, `images-<ref del PR>`) en vez de
  un único `images-publish`: GitHub sustituye la ejecución *pendiente* por la más nueva dentro de un
  grupo, y un push a `main` habría podido descartar la publicación pendiente de un tag de versión.
  Consecuencia aceptada: si dos merges llegan mientras corre uno, el intermedio no genera `:<sha>`
  (`latest` siempre termina en el último).
- **Hallazgos reales en el primer escaneo local** (bases fijadas el 2026-10-24): `backend` traía
  `/usr/bin/pebble` (servidor ACME de pruebas en Go, 4 CVE HIGH de la stdlib, sin uso en la app) y
  `frontend` traía `brotli-libs` 1.2.0-r1 (CVE-2026-111497, corregido en 1.2.1-r0). Se resolvió quitando
  `pebble` en la etapa final del backend y con `apk upgrade --no-cache brotli-libs` en la del frontend
  (con comentario para retirarlo cuando la base lo incluya). Esto matiza la decisión 4: la etapa final del
  frontend sí ejecuta `apk` para un paquete concreto. Sin `.trivyignore`.
- **Versiones**: ver decisión 15; `login-action` y `build-push-action` se mantienen en v3/v6 por mandato
  de la historia.

## 14. Riesgos

- Un CVE nuevo en una imagen base puede dejar `main` sin publicar hasta que Dependabot actualice la base o
  se añada una excepción justificada en `.trivyignore`; es el comportamiento buscado del gate.
- Paquetes nuevos en GHCR nacen privados y hay que enlazarlos/ajustar visibilidad una vez; la etiqueta
  `org.opencontainers.image.source` automatiza el enlace al repo.
