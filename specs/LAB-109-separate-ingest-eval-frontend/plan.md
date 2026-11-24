# Implementation Plan: Separar ingest, eval y frontend en repos propios

**Branch**: `feature/LAB-109-separate-ingest-eval-frontend` | **Date**: 2026-11-24 | **Spec**: [specs/LAB-109-separate-ingest-eval-frontend/spec.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-109-separate-ingest-eval-frontend/spec.md)

**Input**: Feature specification from `/specs/LAB-109-separate-ingest-eval-frontend/spec.md`

---

## Summary

Ejecutar la separación física de los componentes `ingest/`, `eval/` y `frontend/` del monorepo `shoplab-helpdesk` hacia tres repositorios independientes de GitHub (`shoplab-ingest`, `shoplab-eval` y `shoplab-frontend`), dando cumplimiento a la estrategia de extracción temprana ratificada en [ADR-0015](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0015-limites-de-contexto-y-repositorios.md) y [ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md) (LAB-128). La solución implementa:

1. **Extracción con historial**: Uso de `git filter-repo --subdirectory-filter` sobre clones de trabajo limpios para extraer `ingest/`, `eval/` y `frontend/` a sus respectivos repositorios preservando intacto el historial de commits, fechas y autores.
2. **Plantilla y gobernanza estándar**: Inicialización de cada repositorio con los estándares corporativos de [LAB-103](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-103-reusable-workflows-service-template/spec.md) (estructura base, linters, formato, `.gitignore` y ruleset de protección en `main`).
3. **Pipelines reutilizables de CI/CD**: Integración con los workflows de `shoplab-ci@v1` (`python-service` para `ingest` y `eval`, y `angular-app` para `frontend`), garantizando pruebas automáticas con cobertura ≥80 %, escaneo de seguridad con Trivy (fs y contenedor), empaquetado OCI en GHCR con SBOM y firma criptográfica keyless con Cosign.
4. **Versionado y dependencias autónomas**: Incorporación de `release-please` para Semantic Versioning y `dependabot.yml` para actualizaciones automatizadas en cada repositorio.
5. **Consumo de contratos publicados**: Eliminación de dependencias de código local; `shoplab-frontend` consume `@leandroamariles/shoplab-contracts` desde GitHub Packages vía npm, mientras que `shoplab-ingest` y `shoplab-eval` consumen modelos y clientes Python con `uv` anclados a tags semánticos `@vX.Y.Z` de [LAB-108](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-108-contracts-versioned-packages/spec.md).
6. **Entrega continua GitOps**: Job de despliegue reutilizable con la GitHub App `shoplab-deploy` que actualiza `shoplab-infra` (`kustomize edit set image`) y logra convergencia automática en el clúster `dev` verificado en Argo CD (`Synced` y `Healthy`).
7. **Limpieza del monorepo**: Retiro físico de las carpetas en `shoplab-helpdesk`, actualización del [README.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/README.md) con enlaces a los repositorios independientes y depuración de workflows de CI en el monorepo.

---

## Technical Context

**Language/Version**: 
* Python 3.12 (`shoplab-ingest`, `shoplab-eval`)
* TypeScript 5.x / Angular 19+ (`shoplab-frontend`)
* YAML / Shell / PowerShell (GitHub Actions, Kustomize, GitOps scripts)

**Primary Dependencies**:
* **`shoplab-ingest`**: FastAPI, Uvicorn, Pydantic, python-multipart, pdfplumber, markdown-it-py, Prometheus Client, `shoplab-contracts` (modelos Python vía `uv`).
* **`shoplab-eval`**: PyYAML, RAGAS 0.4.4, pytest, pytest-cov, `shoplab-contracts` (esquemas de evaluación).
* **`shoplab-frontend`**: Angular Material, Angular CDK, RxJS, Marked, DOMPurify, Vitest, `@leandroamariles/shoplab-contracts` (paquete npm en GitHub Packages).
* **CI/CD Platform**: GitHub Actions (`workflow_call` a `shoplab-ci@v1`), GHCR, Cosign (Sigstore OIDC keyless), Trivy, Release-please, Dependabot.
* **GitOps**: `shoplab-infra` (Kustomize overlays), Argo CD, GitHub App `shoplab-deploy`.

**Storage**:
* `shoplab-ingest`: Sin almacenamiento persistente propio (servicio stateless de procesamiento y chunking de documentos).
* `shoplab-eval`: Dataset estático versionado (`golden.jsonl`).
* `shoplab-frontend`: Cliente web estático servido por NGINX Alpine en contenedor.

**Testing**:
* `shoplab-ingest`: `pytest` con `pytest-cov` (umbral estricto `--cov-fail-under=80`).
* `shoplab-eval`: `pytest` para validación de corpus y formato de dataset (pruebas de juez bajo marker `-m judge`).
* `shoplab-frontend`: `vitest` / Karma para componentes y servicios Angular; build de producción.
* Integración y seguridad: Trivy (fs y contenedores), verificación de políticas (`CAMBIA-ESTE-OWNER`, `:latest`).

**Target Platform**: Kubernetes (clúster `dev` administrado por Argo CD desde `shoplab-infra`); imágenes OCI en Linux amd64/arm64 en GHCR (`ghcr.io/leandroamariles/*`).

**Project Type**: Microservicios y aplicación web extraídos a repositorios dedicados.

**Performance Goals**:
* Duración de pipeline CI en PR < 4 minutos para cada repositorio.
* Tiempo de despliegue GitOps desde tag release hasta Argo CD Synced < 3 minutos.
* Ingesta de documentos PDF/Markdown sin degradación de latencia respecto al monolito.

**Constraints**:
* **Cero código compartido entre repositorios**: Prohibido importar rutas relativas o librerías de dominio del monorepo (ADR-0015, ADR-0031).
* **Preservación estricta de historial**: Uso mandatario de `git filter-repo` para conservar trazabilidad histórica.
* **Mínimo privilegio en despliegue**: GitHub App dedicada con acceso restringido exclusivamente a `shoplab-infra:contents:write`.
* **Imágenes inmutables y firmadas**: Sin tags mutables `:latest` en producción/dev; firma obligatoria con Cosign OIDC.

**Scale/Scope**: 3 repositorios extraídos (`shoplab-ingest`, `shoplab-eval`, `shoplab-frontend`), 3 aplicaciones en Argo CD actualizadas, 1 repositorio monorepo depurado (`shoplab-helpdesk`).

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio Constitucional | Estado | Justificación y Cumplimiento |
|---|---|---|
| **I. Modular Monolith First, Extract Later** | PASS | La extracción física de `ingest`, `eval` y `frontend` responde a la excepción y calendario de extracción temprana formalmente ratificado en [ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md) (LAB-128). Los módulos backend transaccionales (`chat`, `retrieval`) se mantuvieron cohesionados hasta el cierre de F2 y su extracción se realiza de forma ordenada. |
| **II. Hexagonal Architecture and Domain Purity** | PASS | Los tres repositorios interactúan con el resto del sistema exclusivamente a través de contratos formales de red y DTOs publicados en `shoplab-contracts` (LAB-108). Ningún repositorio aloja lógica de negocio ajena a su responsabilidad. |
| **III. English Identifiers, Typed Errors** | PASS | Todos los identificadores, archivos de configuración, nombres de variables y pipelines se escriben en inglés. Los mensajes de error de la API de Ingest siguen los contratos de error tipados del sistema. |
| **IV. Test-First with Coverage Gate** | PASS | Cada repositorio incorpora en su pipeline de `shoplab-ci` la puerta obligatoria de cobertura ≥80 % (pytest-cov para Python, pruebas unitarias para Angular) y escaneo de vulnerabilidades Trivy sin hallazgos no gestionados. |
| **V. Evaluation-Driven RAG** | PASS | `shoplab-eval` se independiza como repositorio exclusivo para la herramienta de evaluación automática (RAGAS) y el dataset golden (`golden.jsonl`), asegurando que las evaluaciones sigan auditando cada cambio de recuperación y prompts. |
| **VI. Observability and Safe AI Handling** | PASS | `shoplab-ingest` expone métricas Prometheus (`/metrics`) y logs estructurados correlacionados; las imágenes de contenedor se firman criptográficamente y generan SBOM. |
| **VII. Simplicity and Documented Decisions** | PASS | Se emplea `git filter-repo` estándar sin scripts oscuros. Todo el flujo de extracción, configuración de repositorios y despliegue se documenta en `quickstart.md` y `contracts/`. |

---

## Project Structure

### Documentation (this feature)

```text
specs/LAB-109-separate-ingest-eval-frontend/
├── spec.md              # Feature specification
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (decisiones de arquitectura y herramientas)
├── data-model.md        # Phase 1 output (metadatos, manifiestos Kustomize y esquemas de dependencias)
├── quickstart.md        # Phase 1 output (guía paso a paso para desarrolladores)
├── contracts/           # Phase 1 output (contratos de extracción, CI y despliegue GitOps)
│   ├── repo-extraction-contract.md
│   ├── ci-integration-contract.md
│   └── gitops-deploy-contract.md
└── tasks.md             # Phase 2 output (/speckit-tasks command)
```

### Source Code Architecture & Layout (Post-Extraction)

#### 1. Repositorio `shoplab-ingest`
```text
shoplab-ingest/
├── .github/
│   ├── dependabot.yml
│   └── workflows/
│       ├── ci.yml                 # Invoca shoplab-ci/.github/workflows/python-service.yml@v1
│       └── release-please.yml     # Semantic versioning y trigger de imagen/deploy
├── app/
│   ├── __init__.py
│   ├── main.py                    # FastAPI app con endpoints /parse, /chunk, /health, /metrics
│   ├── parser.py                  # Extracción de texto PDF y Markdown
│   └── chunker.py                 # Segmentación semántica de documentos
├── tests/
│   ├── test_parser.py
│   └── test_chunker.py
├── Dockerfile                     # Multi-stage container optimizado
├── pyproject.toml                 # Configuración uv, dependencias y ruff
├── uv.lock
└── README.md
```

#### 2. Repositorio `shoplab-eval`
```text
shoplab-eval/
├── .github/
│   ├── dependabot.yml
│   └── workflows/
│       ├── ci.yml                 # Invoca shoplab-ci/.github/workflows/python-service.yml@v1
│       └── release-please.yml
├── src/
│   └── shoplab_eval/
│       ├── __init__.py
│       ├── dataset.py             # Carga y validación de dataset
│       └── quality_judge.py       # Evaluación con RAGAS/DeepEval
├── data/
│   └── golden.jsonl               # Dataset golden inmutable
├── tests/
│   └── test_dataset.py
├── pyproject.toml
├── uv.lock
└── README.md
```

#### 3. Repositorio `shoplab-frontend`
```text
shoplab-frontend/
├── .github/
│   ├── dependabot.yml
│   └── workflows/
│       ├── ci.yml                 # Invoca shoplab-ci/.github/workflows/angular-app.yml@v1
│       └── release-please.yml
├── src/
│   ├── app/
│   │   ├── chat/                  # Vista de conversación y streaming
│   │   ├── admin/                 # Pantallas administrativas de evaluación y documentos
│   │   └── core/services/         # Clientes HTTP generados desde shoplab-contracts
│   ├── index.html
│   └── main.ts
├── .npmrc                         # Configuración de registro GitHub Packages (@leandroamariles)
├── angular.json
├── package.json
├── package-lock.json
├── nginx.conf                     # Reverse proxy y servidor de estáticos
├── Dockerfile
└── README.md
```

#### 4. Monorepo depurado: `shoplab-helpdesk`
```text
shoplab-helpdesk/
├── backend/                       # Módulos Java Spring Modulith (chat, retrieval, eval-service)
├── infra/                         # Docker compose y stacks locales
├── docs/                          # Documentación y ADRs
├── specs/                         # Especificaciones de features
└── README.md                      # Actualizado con enlaces a los repositorios extraídos
# (ingest/, eval/ y frontend/ quedan eliminados)
```

---

## Complexity Tracking

> *Sin violaciones constitucionales que requieran justificación excepcional. La extracción temprana está amparada por ADR-0031.*
