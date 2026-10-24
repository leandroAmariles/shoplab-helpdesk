# Research: Pipeline de CI en GitHub Actions

## 1. Filtros por ruta sin bloquear checks requeridos

- **Decision**: Un solo workflow que siempre se dispara; un job `changes` usa `dorny/paths-filter@v3`
  y publica salidas `backend`, `frontend`, `ingest`, `eval`, `ci`. Los jobs de componente usan
  `if: needs.changes.outputs.<x> == 'true' || needs.changes.outputs.ci == 'true'`. Un job `ci-gate`
  con `if: always()` falla si algún `needs` terminó en `failure` o `cancelled`.
- **Rationale**: Con `paths:` a nivel de trigger, si el workflow no corre sus checks quedan
  "pendientes" y, si son requeridos, el PR no se puede fusionar. Un job omitido por `if` cuenta como
  éxito, y `ci-gate` da un único nombre estable para la protección de rama (FR-017).
- **Alternatives considered**: `paths:` por trigger (bloquea PRs, descartado); workflows separados
  con un job complementario (duplica definiciones); filtrar con `git diff` manual en shell
  (reinventa la acción y falla en pushes iniciales).

## 2. Permiso de ejecución de `mvnw`

- **Decision**: Registrar `backend/mvnw` con modo ejecutable (`git update-index --chmod=+x`) y fijar
  `mvnw text eol=lf` en `.gitattributes`.
- **Rationale**: Hoy el índice lo guarda como `100644`; en Linux `./mvnw` falla con *Permission
  denied*. Con `text=auto` y `eol` sin especificar, un checkout con CRLF rompería el shebang.
- **Alternatives considered**: `chmod +x backend/mvnw` dentro del workflow (oculta el defecto del
  repo; quien use Linux o macOS seguiría sin poder ejecutarlo); `sh mvnw` (funciona pero se aparta
  del comando exigido `./mvnw`).

## 3. Job Java

- **Decision**: `actions/setup-java@v4` con `distribution: temurin`, `java-version: 21`,
  `cache: maven`, `cache-dependency-path: backend/pom.xml`. Paso: `./mvnw -B -ntp clean verify` con
  `working-directory: backend`. Paso final `actions/upload-artifact@v4` con `if: always()` y ruta
  `backend/target/site/jacoco/`, `if-no-files-found: warn`, retención 14 días.
- **Rationale**: El `pom.xml` ya enlaza Checkstyle, PMD y `jacoco:check` a la fase `verify`, así
  que `verify` los ejecuta todos sin configuración extra. Dentro de la fase, `jacoco:report` está
  declarado antes de `jacoco:check`, por lo que el reporte existe aunque el gate falle y
  `if: always()` lo sube. `ModularityTests` corre en surefire.
- **Alternatives considered**: Invocar `jacoco:check` por separado (redundante, ya está en verify);
  fijar la ruta del reporte en el `pom.xml` (innecesario, la ruta por defecto es estable).

## 4. Testcontainers

- **Decision**: No añadir configuración en el workflow; confiar en el Docker del runner. La versión
  de Testcontainers la gestiona el BOM de Spring Boot 4.1.2 cuando se agregue la dependencia.
- **Rationale**: Hoy el `pom.xml` no declara Testcontainers y ningún test lo usa (LAB-11 solo trae
  `ModularityTests` y la prueba de humo). El pipeline ejecuta lo que exista y queda listo para la
  primera prueba de integración. Declarar la dependencia corresponde a la historia que introduzca
  la base de datos real.
- **Alternatives considered**: Añadir ahora la dependencia sin tests que la usen (código muerto,
  contrario a YAGNI de la constitución).

## 5. Job Angular

- **Decision**: `actions/setup-node@v4` con `node-version: 22`, `cache: npm`,
  `cache-dependency-path: frontend/package-lock.json`. Pasos: detectar `frontend/package.json`; si
  existe, `npm ci`, `npm run lint`, `npm test -- --watch=false --browsers=ChromeHeadless` y
  `npm run build`. Sin proyecto: el job termina en verde con un aviso.
- **Rationale**: `frontend/` está vacío (FR-012). `setup-node` con `cache` falla si no existe el
  lockfile, por eso la detección va antes y el resto de pasos se condiciona a ella.
- **Alternatives considered**: Omitir el job con `if` por existencia de carpeta (los filtros por
  ruta saben si cambió algo, no si hay proyecto); `npm install` (no reproducible).

## 6. Jobs Python (`ingest`, `eval`)

- **Decision**: Job `python` con matriz `component: [ingest, eval]` y `fail-fast: false`, cada
  entrada condicionada a su filtro. `astral-sh/setup-uv@v6` con `enable-cache: true` y
  `cache-dependency-glob` sobre `<component>/uv.lock`. Detección por `pyproject.toml`; con proyecto:
  `uv sync --frozen`, `uv run ruff check .`, `uv run pytest`. Sin proyecto: verde con aviso.
- **Rationale**: Ambos servicios comparten tooling; la matriz evita duplicar YAML y mantiene
  resultados independientes (FR-013). `pytest` sin tests devuelve código 5 y falla: un proyecto
  Python sin pruebas es un defecto que la constitución (IV) no permite.
- **Alternatives considered**: Dos jobs copiados (duplicación); `pip` + `actions/cache` (más lento y
  sin lockfile); tolerar código 5 de pytest (ocultaría proyectos sin pruebas).

## 7. Concurrencia, permisos y forks

- **Decision**: `permissions: contents: read` a nivel de workflow; `concurrency` con grupo
  `ci-${{ github.workflow }}-${{ github.ref }}` y `cancel-in-progress` solo en `pull_request`;
  `timeout-minutes` por job; trigger `pull_request` (no `pull_request_target`).
- **Rationale**: Cumple FR-014 y FR-016. No cancelar en `main` conserva el resultado de cada commit
  fusionado. `pull_request` desde forks corre sin secretos y con token de solo lectura.
- **Alternatives considered**: Cancelar también en `main` (pierde trazabilidad por commit);
  `pull_request_target` (expone secretos a código no confiable).

## 8. Badge del README

- **Decision**: Badge de GitHub Actions del workflow `ci.yml` filtrado por rama `main`, enlazado a
  `https://github.com/leandroAmariles/shoplab-helpdesk/actions/workflows/ci.yml`.
- **Rationale**: El remoto es `leandroAmariles/shoplab-helpdesk`; el workflow se llama `CI`.
- **Alternatives considered**: Servicios de badges de terceros (dependencia externa innecesaria).

## Fuera de alcance confirmado

Qodana y Trivy (historias hermanas del sprint), evaluación RAG (F2), CD, Dependabot.
