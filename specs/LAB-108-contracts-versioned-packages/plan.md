# Implementation Plan: Repo shoplab-contracts publicado como paquetes versionados

**Branch**: `feature/LAB-108-contracts-versioned-packages` | **Date**: 2026-11-23 | **Spec**: [specs/LAB-108-contracts-versioned-packages/spec.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-108-contracts-versioned-packages/spec.md)

**Input**: Feature specification from `/specs/LAB-108-contracts-versioned-packages/spec.md`

## Summary

Establecer el repositorio dedicado `shoplab-contracts` como única fuente de verdad para los contratos síncronos y asíncronos del ecosistema ShopLab Helpdesk (adelantado de F5 al bloque de extracción temprana según ADR-0015 y LAB-128). La solución implementa:
1. Una estructura física limpia con `openapi/`, `avro/` y `asyncapi/` (manteniendo `avro/` y `asyncapi/` vacíos de esquemas reales hasta `LAB-50`).
2. Una puerta de compatibilidad en CI que valida especificaciones OpenAPI con `oasdiff` e inspecciona esquemas Avro contra Schema Registry (`BACKWARD`), bloqueando el PR ante incompatibilidades a menos que se declare explícitamente un incremento de versión mayor (`!` o `BREAKING CHANGE:`).
3. Automatización de versionado semántico con `release-please` configurado para garantizar que los cambios incompatibles incrementen la versión mayor (`bump-minor-pre-major: false`).
4. Generación y publicación simultánea de tres paquetes inmutables por cada release:
   - Artefacto Maven con clases Java compiladas en GitHub Packages (`com.shoplab:shoplab-contracts`).
   - Paquete npm con el cliente Angular tipado en GitHub Packages (`@leandroamariles/shoplab-contracts`).
   - Paquete Python instalable por tag git con `uv` (`@vX.Y.Z`).
5. Fijación de versiones exactas y configuración de Dependabot en los servicios consumidores.
6. Guía operativa en `docs/` con el procedimiento para publicar cambios y la secuencia de despliegue entre productor y consumidores (estrategia expand-and-contract).

## Technical Context

**Language/Version**: Java 21, TypeScript 5.x (Angular 18/19), Python 3.11+, Go (para `oasdiff` CLI)

**Primary Dependencies**:
- `tufin/oasdiff` (v1.10+) para análisis de diferencias y compatibilidad OpenAPI
- `googleapis/release-please-action` (v5.0.0) para orquestación de Semantic Versioning y Changelog
- `openapi-generator-maven-plugin` (v7.x) para generación de DTOs y modelos Java
- `@openapitools/openapi-generator-cli` para generación del cliente TypeScript/Angular
- `datamodel-code-generator` y `hatchling` para empaquetado Python PEP 517 / PEP 621
- GitHub Packages (Maven & npm) y GitHub Actions

**Storage**: N/A para almacenamiento persistente en el repositorio de contratos; GitHub Packages y Git Tags actúan como registros de artefactos inmutables.

**Testing**: Matriz de GitHub Actions CI con:
- Validación sintáctica y linter OpenAPI
- Verificación de compatibilidad hacia atrás con `oasdiff breaking`
- Script de comprobación de esquemas Avro (omite si `avro/` está vacío; valida `BACKWARD` si hay esquemas)
- Verificación en seco (dry-run) de compilación de los tres generadores (Maven `clean compile`, npm `build`, Python `uv build`)

**Target Platform**: GitHub Actions CI/CD en Ubuntu Linux; artefactos distribuidos compatibles con JVM 21, Node.js 22 / Angular, y entornos Python 3.11+ gestionados por `uv`.

**Project Type**: Repositorio de soporte para contratos, automatización de CI/CD y publicación multiesquema de paquetes.

**Performance Goals**:
- Ejecución completa del workflow de CI en PR < 3 minutos
- Flujo de release y publicación de los tres paquetes tras merge en `main` < 7 minutos

**Constraints**:
- Repositorio de soporte puro: cero lógica de negocio, cero entidades de dominio de microservicios (ADR-0015).
- Fijación estricta de versiones en los consumidores (prohibidos comodines `^`, `~`, `LATEST` o `SNAPSHOT`).
- Todo cambio no retrocompatible exige versión mayor (`bump-minor-pre-major: false` en `release-please`).

**Scale/Scope**: Fuente central de interfaces compartidas para todos los microservicios actuales y extraídos en F5 (`chat`, `retrieval`, `tickets`, `orders`, `catalog`, `eval`).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio Constitucional | Estado | Justificación y Cumplimiento |
|---|---|---|
| **I. Modular Monolith First, Extract Later** | PASS | `shoplab-contracts` es un repositorio de soporte previsto en ADR-0015 y adelantado por LAB-128 para preparar los contratos versionados antes de extraer los servicios individuales (LAB-103, LAB-106). No introduce acoplamiento distribuido ni servicios prematuros. |
| **II. Hexagonal Architecture and Domain Purity** | PASS | El repositorio de contratos contiene únicamente especificaciones DTOs y clientes de transporte generados. No contiene lógica de dominio, casos de uso ni entidades persistentes. Las capas de dominio de los microservicios permanecen puras. |
| **III. English Identifiers, Typed Errors** | PASS | Todas las rutas, identificadores de campos, operaciones y modelos en OpenAPI se definen en inglés. El contrato semilla incluye el modelo estándar `ErrorDto` y `ErrorResponse` en inglés. |
| **IV. Test-First with Coverage Gate** | PASS | Se validan automáticamente en CI todos los contratos, la detección de roturas con `oasdiff` y la compilación de los paquetes generados en dry-run antes del merge. |
| **V. Evaluation-Driven RAG** | N/A | No involucra componentes de inferencia LLM ni evaluación RAG. |
| **VI. Observability and Safe AI Handling** | PASS | La distribución y consumo se realiza mediante tokens protegidos en GitHub Secrets (`GITHUB_TOKEN`, `DEPENDABOT_PACKAGES_TOKEN`). No se exponen secretos ni datos reales en los contratos. |
| **VII. Simplicity and Documented Decisions** | PASS | Se selecciona la opción más simple y directa: GitHub Packages para Maven y npm (infraestructura existente de GitHub) y dependencia git tag para Python con `uv` (evitando hospedar un PyPI privado innecesario). Todo documentado operativamente en `docs/`. |

## Project Structure

### Documentation (this feature)

```text
specs/LAB-108-contracts-versioned-packages/
├── spec.md              # Feature specification
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   ├── common-api-seed.yaml
│   ├── ci-validation-workflow-contract.md
│   └── package-distribution-contract.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code Architecture & Layout

#### 1. Nuevo Repositorio Soporte: `shoplab-contracts`
```text
shoplab-contracts/
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                 # oasdiff, avro check, dry-run compile
│   │   ├── pr-title.yml           # Verificación Conventional Commits
│   │   ├── release-please.yml     # Orquestación de releases automáticas
│   │   └── publish.yml            # Publicación Maven, npm y confirmación tag Python
│   └── dependabot.yml             # Mantenimiento de GitHub Actions y generadores
├── openapi/
│   └── common/
│       └── v1/
│           └── common-api.yaml    # Contrato semilla inicial (Health, Ping, ErrorDto)
├── avro/
│   └── .gitkeep                   # Vacío de esquemas reales hasta LAB-50
├── asyncapi/
│   └── .gitkeep                   # Vacío de especificaciones hasta LAB-50
├── generators/
│   ├── java/
│   │   ├── pom.xml                # Generador openapi-generator-maven-plugin
│   │   └── src/
│   ├── npm/
│   │   ├── package.json           # Generador typescript-angular
│   │   └── tsconfig.json
│   └── python/
│       └── pyproject.toml         # Configuración del paquete Python para uv
├── release-please-config.json     # bump-minor-pre-major: false
├── .release-please-manifest.json
├── README.md
└── LICENSE
```

#### 2. Actualizaciones en Repositorio Principal: `shoplab-helpdesk`
```text
shoplab-helpdesk/
├── docs/
│   └── architecture/
│       └── contracts-publishing-and-deployment-order.md  # Guía operativa y expand-and-contract
├── .github/
│   └── dependabot.yml             # Configuración de registries github-maven y github-npm
└── backend/
    └── pom.xml                    # Configuración de repository y dependencia de verificación
```

**Structure Decision**: El proyecto desacopla las especificaciones de interfaz en el repositorio de soporte `shoplab-contracts` según ADR-0015, mientras que las guías de arquitectura, documentación operativa y políticas de consumo se integran y versionan en `shoplab-helpdesk`.

## Complexity Tracking

> No hay violaciones constitucionales ni desviaciones de complejidad no justificadas. Todas las decisiones responden directamente a los criterios de aceptación de LAB-108 y a los ADRs aceptados (ADR-0015, LAB-128).
