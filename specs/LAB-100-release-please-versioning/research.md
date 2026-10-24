# Research: Versionado semántico automático con release-please

## 1. Un paquete raíz con `extra-files` en lugar de tres paquetes

- **Decision**: `release-please-config.json` define un único paquete raíz `.` (sin `component`,
  `include-component-in-tag: false`) y actualiza las versiones de cada componente con `extra-files`:
  `backend/pom.xml` (tipo `xml`, XPath al `<version>` del proyecto, no al del parent), y, cuando
  existan, `frontend/package.json` (tipo `json`, `$.version`) e `ingest/pyproject.toml` (tipo
  `toml`, `project.version`). Las entradas de frontend e ingest se añaden en la historia que crea
  cada proyecto, o ahora mismo si la verificación del §1.1 demuestra que release-please tolera
  archivos ausentes.
- **Rationale**: El criterio de aceptación exige un único tag `vX.Y.Z` y un GitHub Release. Con tres
  paquetes y `include-component-in-tag: false` los tres intentarían crear el mismo tag y la
  documentación de release-please no garantiza que `linked-versions` evite la colisión (guías de la
  comunidad reportan "Duplicate release tag"). Un paquete raíz produce un tag y un changelog por
  construcción. Cada componente sigue teniendo su "entrada" de versión (su archivo nativo), que es
  la intención de "un paquete por componente": versiones coherentes en backend, frontend e ingest.
  Además `frontend/` e `ingest/` son hoy carpetas vacías: crear `package.json` o `pyproject.toml`
  solo para el release haría que `ci.yml` los detecte como proyectos y falle en `npm ci` /
  `uv sync --frozen`.
- **Alternatives considered**: (a) Tres paquetes + `linked-versions` + `include-component-in-tag:
  false`: es la lectura literal del criterio, pero con riesgo de colisión de tags; se deja como
  plan B si se necesitara versionado independiente (fuera de alcance). (b) Tres paquetes con tags
  por componente (`backend-v0.1.0`): incumple el tag `vX.Y.Z` que dispara LAB-78.
- **Desviación**: el criterio dice "un paquete por componente" en el manifiesto; este plan lo
  cumple a nivel de archivos de versión, no de entradas del manifiesto. Se informa en el resumen
  para su confirmación.

### 1.1 Verificación pendiente (primer push de prueba)

Comprobar si `extra-files` con un archivo inexistente hace fallar la acción. Si lo tolera, se
declaran ya las tres entradas; si falla, solo `backend/pom.xml` hasta que existan los otros
proyectos. Resultado a anotar en este documento tras la prueba.

## 2. Token del workflow: `RELEASE_PLEASE_TOKEN`

- **Decision**: El workflow pasa `token: ${{ secrets.RELEASE_PLEASE_TOKEN }}` a la acción, con un
  token de acceso personal de grano fino limitado a este repositorio (`Contents` y `Pull requests`:
  lectura/escritura). Además, habilitar "Allow GitHub Actions to create and approve pull requests".
- **Rationale**: Los PRs, tags y releases creados con `GITHUB_TOKEN` no disparan otros workflows
  (documentado por GitHub y por la acción). Sin un token propio, el PR de release no recibiría
  `ci-gate` ni `pr-title`, que son checks requeridos de `main` (LAB-77): quedaría imposible de
  fusionar. Y un tag creado con `GITHUB_TOKEN` tampoco dispararía el workflow de imagen de LAB-78.
- **Alternatives considered**: GitHub App instalada en el repo: más robusta (sin caducidad ligada a
  una persona) pero añade infraestructura desproporcionada para un único desarrollador; se anota
  como evolución. Fusionar a mano forzando checks: contradice "sin pasos manuales" y el ruleset sin
  bypass. Disparar la CI desde release-please con `workflow_dispatch`: los checks requeridos se
  asocian al PR por evento `pull_request`, no encajan.
- **Riesgo**: caducidad del token (se documenta fecha y procedimiento de rotación en el README).

## 3. Título del PR de release compatible con `pr-title`

- **Decision**: `"pull-request-title-pattern": "chore${scope}: [LAB-100] release ${version}"` en la
  configuración.
- **Rationale**: El PR de release es un PR más y debe pasar `pr-title` (`type(scope): [LAB-<n>]
  descripción`, LAB-77). El título por defecto (`chore(main): release 0.1.0`) falla por falta de
  clave LAB. Con squash merge, ese título pasa a ser el commit en `main` (`chore: [LAB-100] release
  0.1.0`) y es el que release-please reconoce para marcar el release como publicado. El tipo
  `chore` no genera versión, así que el commit del release no dispara otro PR de release.
- **Alternatives considered**: Excluir al bot de `pr-title` (como Dependabot): obliga a modificar
  LAB-77 y debilita la regla. Una clave LAB fija se acepta porque el release pertenece a esta
  historia; si el tablero define otra clave de "releases", se cambia en el patrón.

## 4. Primer release `v0.1.0`

- **Decision**: Manifiesto inicial `{ ".": "0.0.0" }` y `"release-as": "0.1.0"` en la configuración
  hasta que se publique `v0.1.0`; después, un PR `chore` lo retira.
- **Rationale**: El historial actual es casi todo `chore`, que no sube versión, y el squash merge
  descarta el cuerpo del commit (`squash_merge_commit_message=BLANK`), por lo que el pie
  `Release-As:` no es utilizable. `release-as` fuerza `0.1.0` con independencia de los commits. La
  documentación advierte de que debe retirarse tras el release o seguiría imponiendo la versión.
- **Alternatives considered**: Esperar a un `feat` natural: no determinista para la prueba de la US.
  `bootstrap-sha`/`initial-version`: acotan historial pero no garantizan `0.1.0` si no hay commits
  que suban versión.

## 5. Reglas de versión en `0.x`

- **Decision**: `"bump-minor-pre-major": true` y `"bump-patch-for-minor-pre-major": false`.
- **Rationale**: `feat` sube la minor y `fix` la patch (US). Con `bump-minor-pre-major`, un cambio
  breaking antes de `1.0.0` sube la minor en lugar de saltar a `1.0.0`, comportamiento habitual
  mientras el producto se estabiliza (spec, Assumptions). A partir de `1.0.0` rige la regla estándar
  (`!` → major).
- **Alternatives considered**: Dejar los valores por defecto (breaking → `1.0.0` desde `0.x`):
  produciría un salto accidental a `1.0.0`.

## 6. Qué tipos entran al changelog

- **Decision**: Secciones visibles: `feat` (Features), `fix` (Bug Fixes), `perf` (Performance),
  `revert` (Reverts); `docs`, `refactor`, `test`, `build`, `ci`, `chore` ocultos.
- **Rationale**: Coincide con los tipos permitidos por `pr-title` y con el contrato de LAB-77, donde
  solo `feat`, `fix`, `perf` y `revert` generan entradas. Los ocultos no abren PR de release por sí
  solos (FR-006).
- **Alternatives considered**: Mostrar todos los tipos: ruido en el changelog y PRs de release por
  cambios internos.

## 7. Acción fijada por SHA y workflow aparte

- **Decision**: `.github/workflows/release-please.yml` independiente de `ci.yml`, con la acción
  fijada por SHA del último v4.x (comentario con la versión), y `concurrency` sin cancelar
  ejecuciones en curso.
- **Rationale**: Mismo criterio que `pr-title.yml` (LAB-77): acciones de terceros fijadas por SHA y
  actualizadas por Dependabot. Un workflow propio evita acoplar el release a los filtros de rutas de
  la CI y permite permisos de escritura solo donde hacen falta (`ci.yml` queda en
  `contents: read`). Dos pushes casi simultáneos se serializan para evitar PRs o tags duplicados
  (FR-009). El criterio de aceptación dice `@v4`: se cumple fijando el SHA de un v4.x.
- **Alternatives considered**: Referencia móvil `@v4`: cumple literalmente la US pero sin
  inmutabilidad; el resto del repositorio ya adopta el SHA para acciones de terceros no oficiales de
  GitHub.

## 8. Qué dispara la imagen de LAB-78

- **Decision**: Esta historia solo garantiza que el tag `vX.Y.Z` y el GitHub Release se crean con
  `RELEASE_PLEASE_TOKEN` (por eso sí disparan workflows). El workflow de imagen se define en LAB-78,
  escuchando `push` de tags `v*` o `release: published`.
- **Rationale**: Fuera de alcance (spec). Dejar el contrato documentado en
  [contracts/release-flow.md](contracts/release-flow.md) evita ambigüedad entre historias.
- **Alternatives considered**: Encadenar la publicación de imagen dentro del workflow de release:
  acopla historias y rompe el alcance de LAB-78.

## 9. Documentación sin contradicciones

- **Decision**: Ampliar `README.md` (sección "Versionado y releases" junto a "Convenciones"),
  `CLAUDE.md` (§5) y `.ai/rules/development-guidelines.md` (§5, fuente de `CLAUDE.md`) con la misma
  tabla de tipos → bump y el mismo ejemplo `feat(chat): [LAB-12] ...`.
- **Rationale**: El README ya fija el formato del título del PR (LAB-77) y avisa de que los commits
  de rama no se validan. Ahora hay que dejar claro que **solo el título del PR (commit de squash)**
  determina la versión, y que el §5 de las guías (`type(scope): description`) se refiere a commits
  de rama. La constitución dice `LAB-<n> type(scope): ...` para commits; se mantiene la
  reconciliación ya hecha en LAB-77.
- **Alternatives considered**: Documentar solo en el README: incumple el criterio de aceptación.

## 10. Prerrequisitos del repositorio (verificados 2026-10-24)

- `gh auth status`: sesión de `leandroAmariles` con alcances `repo` y `workflow`; permisos de
  administrador sobre el repositorio (`permissions.admin: true`), repositorio público.
- Sin tags ni releases previos (`git tag --list` y `gh release list` vacíos).
- Nombres de check exactos: job `ci-gate` en `ci.yml` y job `pr-title` en `pr-title.yml`.
- Ruleset `main-protect` activo (`enforcement: active`).
- Secretos del repositorio: ninguno (`RELEASE_PLEASE_TOKEN` pendiente, tarea T003).
- `can_approve_pull_request_reviews: false`: hay que habilitar "Allow GitHub Actions to create and
  approve pull requests" (tarea T003).
- Última versión de la acción: `v4.4.1`, commit `5c625bfb5d1ff62eadeeb3772007f7f66fdcf071`.
- `actionlint` no está instalado en esta máquina; la validación del workflow se apoya en el PR.
