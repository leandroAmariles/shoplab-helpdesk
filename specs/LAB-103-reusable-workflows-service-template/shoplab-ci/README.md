# shoplab-ci

Repositorio central de soporte con workflows reutilizables de GitHub Actions (`workflow_call`) para el ecosistema **ShopLab Helpdesk**.

## Workflows Disponibles (@v1)

| Workflow | Propósito | Tecnología | Gates de Calidad |
|---|---|---|---|
| `.github/workflows/java-service.yml` | Backend microservicios | Java 21, Spring Boot 4.x, Maven | Testcontainers, JaCoCo ≥80 %, Qodana, Trivy |
| `.github/workflows/python-service.yml` | Ingesta, búsqueda RAG, eval | Python 3.11+, `uv`, pytest | Ruff linter/format, pytest coverage, Trivy |
| `.github/workflows/angular-app.yml` | Frontend web | Angular, Node.js 22, npm | Linter, Headless tests, Trivy |
| `.github/workflows/publish-image.yml` | Publicación OCI segura | Docker Buildx, GHCR | Inmutabilidad de tags, SBOM SPDX, Cosign keyless |
| `.github/workflows/deploy.yml` | Despliegue GitOps | Kustomize, GitHub App | Policy check, kustomize build, git pull --rebase |

## Uso desde un Microservicio

```yaml
name: CI
on: [push, pull_request]

jobs:
  pipeline:
    uses: leandroamariles/shoplab-ci/.github/workflows/java-service.yml@v1
    with:
      image-name: 'ghcr.io/leandroamariles/mi-servicio'
      coverage-threshold: 80
    secrets:
      github-token: ${{ secrets.GITHUB_TOKEN }}
```
