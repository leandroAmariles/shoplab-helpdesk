# Implementation Plan: Imágenes Docker publicadas en GHCR con escaneo y SBOM

**Branch**: `feature/LAB-78-ghcr-docker-images` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-78-ghcr-docker-images/spec.md`

## Summary

Se añade un workflow nuevo `.github/workflows/images.yml` y un `Dockerfile` (+ `.dockerignore`) por
servicio (`backend/`, `frontend/`, `ingest/`). El workflow tiene dos jobs con matriz de servicios:
`build-scan` construye cada imagen **sin publicarla** y la escanea con Trivy (HIGH/CRITICAL,
`ignore-unfixed`, mismo `.trivyignore` que LAB-76); `publish` solo corre si **los tres** escaneos
pasaron (`needs`), reconstruye desde la caché de capas, publica en `ghcr.io/leandroamariles/<servicio>`
con `sbom` y `provenance`, y firma el digest con cosign keyless. Tags con `docker/metadata-action`:
SHA completo y `latest` en `main`, `vX.Y.Z` en el push del tag de release-please (LAB-100). En
pull requests solo se construye y escanea. Los Dockerfiles de `frontend` e `ingest` incluyen un
contenido mínimo de relevo mientras no exista su código. Decisiones en [research.md](research.md).

## Technical Context

**Language/Version**: YAML de GitHub Actions y Dockerfiles (BuildKit). Sin código de aplicación nuevo.
Imágenes base: Eclipse Temurin 21 (backend, alineado con `java.version=21`), Node 22 + nginx sin privilegios
(frontend, alineado con `ci.yml`), Python 3.12 slim + uv (ingest).

**Primary Dependencies**: `docker/setup-buildx-action`, `docker/login-action@v3`,
`docker/build-push-action@v6`, `docker/metadata-action@v5`, `aquasecurity/trivy-action` (mismo SHA fijado
que `ci.yml`), `sigstore/cosign-installer`, `actions/checkout@v7`. Versiones exactas y SHA se fijan en
implementación (research.md, decisión 10).

**Storage**: Registro GHCR (paquetes `backend`, `frontend`, `ingest`); caché de capas en GitHub Actions
cache (`type=gha`, un `scope` por servicio). Sin base de datos.

**Testing**: Validación del pipeline: construcción local de las 3 imágenes (`docker build`), comprobación
de usuario no root, ejecución del workflow en PR (build + scan), en `main` (publicación) y en un tag;
verificación con `cosign verify` y `docker buildx imagetools inspect` (ver [quickstart.md](quickstart.md)).
`actionlint` local opcional. JaCoCo no se ve afectado.

**Target Platform**: Runner `ubuntu-latest`; imágenes `linux/amd64` (una sola arquitectura).

**Project Type**: Infraestructura de CI/CD de un monorepo; extiende LAB-15 (CI), LAB-76 (Trivy) y se
dispara con LAB-100 (tags).

**Performance Goals**: `main` → imágenes publicadas, firmadas y con SBOM en < 20 min (SC-002); tag de
versión → imágenes en < 30 min (SC-007).

**Constraints**: Sin claves ni PAT de larga duración (login con `GITHUB_TOKEN`, firma OIDC); permisos
mínimos por job (`packages: write` y `id-token: write` solo en `publish`); PRs, forks y Dependabot nunca
publican; todo o nada entre servicios; los tags `vX.Y.Z` son inmutables; las acciones se fijan a versión
exacta o SHA.

**Scale/Scope**: 1 workflow nuevo, 3 Dockerfiles, 3 `.dockerignore`, ampliación de `dependabot.yml`
(Docker para los 3 directorios), nota en README. Sin cambios en `ci.yml`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / regla | Estado | Nota |
|---|---|---|
| I. Monolito modular primero | Pass | Empaqueta el monolito `backend` tal cual; no altera módulos. Imágenes de `frontend`/`ingest` son independientes por diseño (constitución, stack). |
| II. Hexagonal / dominio puro | N/A | Sin código de aplicación. |
| III. Identificadores en inglés | Pass | Jobs, pasos, variables y archivos en inglés; documentación en español. |
| IV. Test-first con gate 80 % | Pass | La imagen se construye con `-DskipTests` porque CI ya ejecuta `verify` con JaCoCo (FR-018); no relaja el gate. |
| V. RAG evaluado por PR (desde F2) | N/A | Fuera de alcance. |
| VI. Observabilidad y secretos | Pass | Sin secretos en el repo; login con `GITHUB_TOKEN`; firma keyless; imágenes sin credenciales embebidas (`.dockerignore` excluye `.env`). |
| VII. Simplicidad y ADR | Pass | Un workflow, sin herramientas extra; la rebuild-desde-caché y la firma keyless están justificadas. No se requiere ADR (no cambia base de datos, mensajería ni límites de módulos). |
| Style gates (PMD/Checkstyle) | Pass | Sin código Java modificado. |
| Branch/commit naming | Pass | `feature/LAB-78-ghcr-docker-images`; directorio igual al último segmento. |
| Stack: arranque local con un compose | Pass | No se toca `infra/docker-compose.yml`. |

**Re-check post-diseño**: Pass. Sin violaciones; Complexity Tracking vacío.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-78-ghcr-docker-images/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── image-pipeline.md
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks (no creado aquí)
```

### Source Code (repository root)

```text
.github/
├── dependabot.yml            # + docker en /backend, /frontend, /ingest
└── workflows/
    └── images.yml            # nuevo: build-scan (matriz) → publish (matriz, firma, SBOM, provenance)
backend/
├── Dockerfile                # nuevo: build JDK + runtime JRE, usuario no root
└── .dockerignore             # nuevo
frontend/
├── Dockerfile                # nuevo: build Node + nginx sin privilegios (relevo si no hay package.json)
└── .dockerignore             # nuevo
ingest/
├── Dockerfile                # nuevo: build uv + runtime Python slim (relevo si no hay pyproject.toml)
└── .dockerignore             # nuevo
README.md                     # nota breve: imágenes, tags y cómo verificar la firma
```

**Structure Decision**: Workflow separado de `ci.yml` (decisión 1): los disparadores (`push` de tags,
permisos de escritura de paquetes, OIDC) y el ciclo de vida difieren del CI; no se añade al `ci-gate`
porque publicar no debe bloquear PRs. El contexto de build de cada imagen es su propio directorio.

## Complexity Tracking

Sin violaciones de la constitución que justificar.
