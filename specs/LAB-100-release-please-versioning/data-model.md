# Data Model: Versionado semántico automático con release-please

No hay base de datos ni entidades de dominio. Las "entidades" son artefactos de configuración y de
GitHub.

## Manifiesto de release (`.release-please-manifest.json`)

| Campo | Descripción | Regla |
|---|---|---|
| `"."` | Versión actual del paquete raíz | Semver `X.Y.Z`; inicial `0.0.0`; solo lo modifica el PR de release |

## Configuración (`release-please-config.json`)

| Campo | Valor | Origen |
|---|---|---|
| `packages["."].release-type` | `simple` | research §1 |
| `include-component-in-tag` | `false` (tag `vX.Y.Z`) | FR-007 |
| `extra-files` | `backend/pom.xml` (+ `frontend/package.json`, `ingest/pyproject.toml` cuando existan) | FR-003, research §1 |
| `bump-minor-pre-major` | `true` | research §5 |
| `pull-request-title-pattern` | `chore${scope}: [LAB-100] release ${version}` | research §3 |
| `release-as` | `0.1.0` (temporal) | research §4 |
| `changelog-sections` | `feat`, `fix`, `perf`, `revert` visibles; el resto ocultos | research §6 |

## PR de release

| Atributo | Valor |
|---|---|
| Rama | `release-please--branches--main` |
| Título | `chore(main): [LAB-100] release <versión>` |
| Etiquetas | `autorelease: pending` → `autorelease: tagged` (las gestiona release-please) |
| Contenido | `CHANGELOG.md`, manifiesto y archivos de `extra-files` |
| Estados | abierto/actualizado → fusionado (publica) → etiquetado. Como máximo uno abierto |

## Release publicado

| Atributo | Valor |
|---|---|
| Tag | `vX.Y.Z`, sobre el commit de la fusión |
| GitHub Release | Notas = entrada de `CHANGELOG.md` de esa versión |
| Idempotencia | Re-ejecutar el workflow no crea otro tag ni release para la misma versión |

## Transiciones de versión (mientras `0.x`)

| Commit en `main` desde el último release | Resultado |
|---|---|
| Solo `chore`/`docs`/`test`/`ci`/`build`/`refactor` | Sin PR de release |
| `fix` (sin `feat`) | patch |
| `feat` | minor |
| `feat!` / `fix!` / `BREAKING CHANGE:` | minor (por `bump-minor-pre-major`); major desde `1.0.0` |
