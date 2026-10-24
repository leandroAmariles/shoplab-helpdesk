# Contract: flujo de release

## Secuencia

1. Un PR se fusiona en `main` por squash; el título del PR (`type(scope): [LAB-<n>] descripción`)
   es el mensaje del commit.
2. `release-please.yml` se ejecuta con ese push.
3. Si hay commits que suben versión desde el último release, crea o actualiza el PR de release
   `chore(main): [LAB-100] release <versión>` en la rama `release-please--branches--main`.
4. `ci-gate` y `pr-title` corren sobre ese PR (posible porque se crea con `RELEASE_PLEASE_TOKEN`).
5. Al fusionarlo (squash), `release-please.yml` crea el tag `vX.Y.Z` y el GitHub Release.
6. El tag/Release dispara el workflow de imagen versionada de LAB-78.

## Cálculo de versión

| Título del PR fusionado | `0.x` | `>= 1.0.0` |
|---|---|---|
| `feat(...)` | minor | minor |
| `fix(...)`, `perf(...)` | patch | patch |
| `feat(...)!`, `fix(...)!` | minor | major |
| `chore`, `docs`, `test`, `ci`, `build`, `refactor` | sin release | sin release |

## Garantías para LAB-78

| Garantía | Detalle |
|---|---|
| Tag | `vX.Y.Z` (sin prefijo de componente), creado con un token que dispara workflows |
| Release | GitHub Release con las notas del changelog, publicado (no borrador) |
| Disparador sugerido | `on: push: tags: ['v*']` o `on: release: types: [published]` |

## Casos límite

| Caso | Comportamiento |
|---|---|
| Re-ejecución del workflow | No crea tag ni release duplicados |
| Tag ya existente | El workflow falla y no sobrescribe |
| Dos pushes simultáneos | Serializados por `concurrency` |
| Edición manual del PR de release | Se pierde en el siguiente push que lo regenera; los cambios van en commits a `main` |
| Commit sin Conventional Commits | Ignorado para el cálculo; no rompe el workflow |
