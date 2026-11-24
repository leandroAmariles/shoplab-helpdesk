# Contrato de Integración de CI con `shoplab-ci`

**Feature**: `LAB-109-separate-ingest-eval-frontend`
**Target**: Pipelines de GitHub Actions

---

## 1. Interfaz del Workflow de CI para Servicios Python (`shoplab-ingest`, `shoplab-eval`)

Invocación mediante `workflow_call`:
```yaml
name: CI

on:
  pull_request:
  push:
    branches: [main]

jobs:
  ci:
    uses: leandroAmariles/shoplab-ci/.github/workflows/python-service.yml@v1
    with:
      python-version: "3.12"
      coverage-threshold: 80
      image-name: "ghcr.io/leandroamariles/${{ github.event.repository.name }}"
    secrets:
      deploy-app-id: ${{ secrets.DEPLOY_APP_ID }}
      deploy-app-private-key: ${{ secrets.DEPLOY_APP_PRIVATE_KEY }}
```

### Inputs Requeridos
* `python-version`: Versión exacta del runtime (e.g., `"3.12"`).
* `coverage-threshold`: Porcentaje mínimo obligatorio de cobertura de código (mínimo `80`).
* `image-name`: URI completa de la imagen en GHCR sin tag.

---

## 2. Interfaz del Workflow de CI para Frontend Angular (`shoplab-frontend`)

Invocación mediante `workflow_call`:
```yaml
name: CI

on:
  pull_request:
  push:
    branches: [main]

jobs:
  ci:
    uses: leandroAmariles/shoplab-ci/.github/workflows/angular-app.yml@v1
    with:
      node-version: "22"
      image-name: "ghcr.io/leandroamariles/shoplab-frontend"
    secrets:
      node-auth-token: ${{ secrets.GITHUB_TOKEN }}
      deploy-app-id: ${{ secrets.DEPLOY_APP_ID }}
      deploy-app-private-key: ${{ secrets.DEPLOY_APP_PRIVATE_KEY }}
```

### Reglas de Calidad Obligatorias
* El job compila la aplicación Angular en modo de producción.
* Ejecuta pruebas unitarias y falla si hay regresiones.
* Escanea el sistema de archivos con Trivy (`fs`, severidades `HIGH,CRITICAL`).
* Compila la imagen OCI, genera SBOM y firma criptográfica keyless con Cosign.
