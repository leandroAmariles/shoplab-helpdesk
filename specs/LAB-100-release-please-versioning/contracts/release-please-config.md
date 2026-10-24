# Contract: configuración de release-please

Archivos en la raíz del repositorio. Forma esperada (los valores definitivos y el SHA de la acción
se fijan en la implementación):

## `release-please-config.json`

```json
{
  "$schema": "https://raw.githubusercontent.com/googleapis/release-please/main/schemas/config.json",
  "include-component-in-tag": false,
  "bump-minor-pre-major": true,
  "bump-patch-for-minor-pre-major": false,
  "pull-request-title-pattern": "chore${scope}: [LAB-100] release ${version}",
  "changelog-sections": [
    { "type": "feat", "section": "Features" },
    { "type": "fix", "section": "Bug Fixes" },
    { "type": "perf", "section": "Performance" },
    { "type": "revert", "section": "Reverts" },
    { "type": "docs", "section": "Documentation", "hidden": true },
    { "type": "refactor", "section": "Refactoring", "hidden": true },
    { "type": "test", "section": "Tests", "hidden": true },
    { "type": "build", "section": "Build", "hidden": true },
    { "type": "ci", "section": "CI", "hidden": true },
    { "type": "chore", "section": "Chores", "hidden": true }
  ],
  "packages": {
    ".": {
      "release-type": "simple",
      "changelog-path": "CHANGELOG.md",
      "extra-files": [
        {
          "type": "xml",
          "path": "backend/pom.xml",
          "xpath": "//*[local-name()='project']/*[local-name()='version']"
        }
      ]
    }
  }
}
```

Entradas que se añaden a `extra-files` cuando el proyecto exista (o ya, según research §1.1):

```json
{ "type": "json", "path": "frontend/package.json", "jsonpath": "$.version" }
{ "type": "toml", "path": "ingest/pyproject.toml", "jsonpath": "$.project.version" }
```

## `.release-please-manifest.json`

```json
{ ".": "0.0.0" }
```

## Invariantes

| Invariante | Motivo |
|---|---|
| El XPath apunta al `<version>` hijo directo de `<project>`, nunca al del `<parent>` | No tocar la versión de Spring Boot |
| `release-as: "0.1.0"` se usó solo para el primer release y ya está retirado | Si no, impone la versión para siempre |
| El título del PR de release cumple `type(scope): [LAB-<n>] descripción` | Debe pasar `pr-title` |
| `CHANGELOG.md` no se edita a mano | Lo regenera release-please |

## Workflow `.github/workflows/release-please.yml`

| Aspecto | Contrato |
|---|---|
| Disparador | `push` a `main` |
| Permisos | `contents: write`, `pull-requests: write`, `issues: write` |
| Acción | `googleapis/release-please-action` v4 fijada por SHA, `config-file` y `manifest-file` por defecto |
| Token | `secrets.RELEASE_PLEASE_TOKEN` |
| Concurrencia | grupo `release-please`, `cancel-in-progress: false` |
| Timeout | 10 minutos |
