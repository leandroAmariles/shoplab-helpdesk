# Contract: reglas de políticas de manifiestos

Interfaz de `scripts/policy-check.sh`. Se ejecuta sobre la salida de `kustomize build` de cada overlay.

## Uso

```text
scripts/policy-check.sh <rendered.yaml> [<overlay-name>]
```

Código de salida: `0` sin violaciones; `1` con al menos una; `2` por error de uso o de lectura.

## Reglas

| Regla | Dónde mira | Falla si | Pasa si |
|---|---|---|---|
| `IMAGE_LATEST` | `containers`, `initContainers`, `ephemeralContainers` de todo workload y de plantillas de pod | la imagen termina en `:latest` | tag fijo distinto de `latest` o digest |
| `IMAGE_NO_TAG` | igual | la imagen no tiene `:tag` ni `@digest` | tiene tag o digest |
| `TEMPLATE_REPO_URL` | todo el texto renderizado | aparece `CAMBIA-ESTE-OWNER` | no aparece |
| `TARGET_REVISION_NOT_MAIN` | `spec.source.targetRevision`, `spec.sources[].targetRevision` de `Application` y `ApplicationSet` (`spec.template.spec`) | el valor es distinto de `main` (incluye `HEAD`, otras ramas, tags, vacío) | el valor es exactamente `main` |

Notas:

- Un puerto de registro (`registry:5000/app:1.0`) no cuenta como tag: el tag es lo que sigue al último `:` si
  este va después del último `/`.
- Un `targetRevision` ausente se reporta como `TARGET_REVISION_NOT_MAIN` con valor `<unset>`.
- Un recurso sin imágenes ni `Application` no genera violaciones.

## Formato de salida

Una línea por violación, a stderr, y un resumen a stdout:

```text
IMAGE_LATEST | dev | Deployment/shoplab/api | spec.template.spec.containers[0].image=ghcr.io/acme/api:latest
TEMPLATE_REPO_URL | prod | Application/argocd/shoplab | spec.source.repoURL=https://github.com/CAMBIA-ESTE-OWNER/shoplab-infra
policy-check: 2 violation(s) in overlay prod
```

En CI cada línea también se emite como anotación `::error` del workflow.

## Casos de prueba (`scripts/tests/policy-check.test.sh`)

| Fixture | Resultado esperado |
|---|---|
| `good/` | salida 0 |
| `bad-latest/` | salida 1, regla `IMAGE_LATEST` |
| `bad-notag/` | salida 1, regla `IMAGE_NO_TAG` |
| `bad-repourl/` | salida 1, regla `TEMPLATE_REPO_URL` |
| `bad-revision/` | salida 1, regla `TARGET_REVISION_NOT_MAIN` |
| imagen con digest, sin tag (en `good/`) | salida 0 |
| imagen con puerto de registro y sin tag | salida 1, `IMAGE_NO_TAG` |
