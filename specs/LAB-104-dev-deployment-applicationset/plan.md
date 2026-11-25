# Implementation Plan: Despliegue por servicio en dev con ApplicationSet

**Branch**: `feature/LAB-104-dev-deployment-applicationset` | **Date**: 2026-11-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/LAB-104-dev-deployment-applicationset/spec.md`

---

## Summary

La historia **LAB-104 (F5-13)** habilita el despliegue desacoplado, autónomo y concurrente de cada microservicio en el entorno `dev` desde `shoplab-infra`. Para ello, sustituye el overlay monolítico central previo (LAB-81) por:
1. Una estructura Kustomize modular por servicio (`apps/<servicio>/base` y `apps/<servicio>/overlays/dev`).
2. Un `ApplicationSet` de Argo CD con generador de directorios Git (`git.directories`) que crea y sincroniza automáticamente una `Application` por servicio con autoreparación (`selfHeal`) y poda (`prune`).
3. Soporte de concurrencia en el job reutilizable de despliegue de CI (LAB-103) mediante `git pull --rebase` y reintentos con backoff exponencial.
4. Gestión de credenciales de base de datos soberanas por servicio a través de `ExternalSecret`.

---

## Technical Context

**Language/Version**: Kubernetes Manifests (YAML), Kustomize v5.4+, Argo CD CRDs (`argoproj.io/v1alpha1`), Bash / Shell scripts para CI.

**Primary Dependencies**:
* Argo CD & ApplicationSet Controller (v2.10+)
* External Secrets Operator (v0.9+)
* Kustomize v5.4+
* GitHub Actions (`workflow_call` de `shoplab-ci`)
* GitHub App `shoplab-deploy[bot]` (permisos `contents: write` en `shoplab-infra`)

**Storage**: PostgreSQL 16 (con pgvector) alojando esquemas lógicos y credenciales independientes por servicio (`chat`, `retrieval`, `eval`, `catalog`, `orders`, `tickets`).

**Testing**:
* `kustomize build` local en cada base y overlay
* `policy-check.sh` contra tags `:latest` y convenciones de labels
* Pruebas de integración de concurrencia y rebase (`tests/deploy-rebase.test.sh`)
* Validación en clúster dev local con Argo CD CLI (`argocd app list`)

**Target Platform**: Clúster Kubernetes de desarrollo (`shoplab-dev`, EKS local / Kind conforme a LAB-101 / LAB-98).

**Project Type**: Infraestructura como Código (IaC), GitOps y CI/CD.

**Performance Goals**:
* Detección y creación de `Application` en Argo CD en < 3 minutos tras commit en `main`.
* Tiempo de ejecución del job de despliegue en CI < 45 segundos.
* Cero colisiones o fallos en push de despliegue concurrente entre microservicios.

**Constraints**:
* Cero contraseñas o secretos en texto claro en el repositorio Git.
* Prohibición terminante de imágenes con tag `:latest`.
* Kafka con Strimzi y Schema Registry queda explícitamente fuera de alcance (diferido a LAB-131 y LAB-46).

---

## Constitution Check

*GATE: Evaluation against ShopLab Helpdesk Constitution.*

| Principle | Status | Justification / Implementation Alignment |
|---|---|---|
| **I. Modular Monolith First, Extract Later** | PASS | El despliegue por microservicio con ApplicationSet materializa la infraestructura GitOps requerida por la extracción temprana aprobada en [ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md) (LAB-128). Permite que los servicios extraídos o nacidos desde la plantilla (LAB-103) se desplieguen y prueben en el clúster desde el primer día. |
| **II. Hexagonal Architecture and Domain Purity** | PASS | Aplica al desacoplamiento de infraestructura: ningún servicio depende de manifiestos ni configuraciones internas de otros servicios; interactúan únicamente por red y contratos. |
| **III. English Identifiers, Typed Errors** | PASS | Todos los identificadores, variables de entorno, nombres de recursos y commits siguen el estándar en inglés (`shoplab-services-dev`, `apps/<service>/base`, `ExternalSecret`). |
| **IV. Test-First with Coverage Gate** | PASS | La infraestructura se valida con pruebas automatizadas (`kustomize build`, `policy-check.sh` y tests de simulación de concurrencia para el script de rebase). |
| **V. Evaluation-Driven RAG** | PASS | Facilita el despliegue independiente de `retrieval-service` y `eval-service` para que la evaluación automatizada opere contra endpoints desplegados en dev. |
| **VI. Observability and Safe AI Handling** | PASS | Los deployments configuran probes HTTP para Actuator (`/actuator/health/readiness`), variables de correlación `X-Request-Id` y trazas OTLP hacia la pila de observabilidad del clúster (LAB-102). |
| **VII. Simplicity and Documented Decisions** | PASS | Se adopta el generador más simple y robusto de Argo CD (`Git directories`). Se documenta el contrato en `contracts/` y el manual en `quickstart.md`. Se posterga Kafka a LAB-131 (YAGNI). |

---

## Project Structure

### Documentation & Specifications (`specs/LAB-104-dev-deployment-applicationset/`)

```text
specs/LAB-104-dev-deployment-applicationset/
├── spec.md                                          # Especificación funcional aprobada
├── plan.md                                          # Este plan de implementación
├── research.md                                      # Decisiones técnicas y análisis de alternativas
├── data-model.md                                    # Modelo de datos y entidades declarativas
├── quickstart.md                                    # Guía de pruebas locales y verificación
├── checklists/
│   └── requirements.md                              # Checklist de completitud de requisitos
└── contracts/
    ├── applicationset-contract.yaml                 # Manifiesto CRD del ApplicationSet
    ├── deploy-rebase-contract.md                    # Contrato de concurrencia y rebase en CI
    └── service-k8s-structure-contract.md            # Convención de carpetas apps/<servicio>/
```

### Source Code Targets (Afectación en repositorios)

```text
# En repositorio shoplab-infra:
shoplab-infra/
├── k8s/
│   └── bootstrap/
│       └── applicationset-services-dev.yaml         # Nuevo: ApplicationSet con git.directories
├── apps/
│   ├── chat-service/                                # Nuevo: Base y overlay dev de chat-service
│   │   ├── base/
│   │   │   ├── kustomization.yaml
│   │   │   ├── deployment.yaml
│   │   │   ├── service.yaml
│   │   │   └── externalsecret.yaml
│   │   └── overlays/
│   │       └── dev/
│   │           └── kustomization.yaml
│   ├── retrieval-service/                           # Nuevo: Base y overlay dev de retrieval-service
│   │   ├── base/
│   │   └── overlays/dev/
│   ├── eval-service/                                # Nuevo: Base y overlay dev de eval-service
│   │   ├── base/
│   │   └── overlays/dev/
│   └── backend/                                     # Mantenido: Overlay dev del monolito durante transición
└── scripts/
    └── deploy-service.sh                            # Actualizado con lógica de rebase y reintento

# En repositorio shoplab-ci:
shoplab-ci/
└── .github/workflows/
    └── deploy.yml                                   # Job reutilizable invocando deploy-service.sh
```

---

## Implementation Phases & Milestones

### Phase 1: Estructura Modular Kustomize (`apps/<servicio>/base` y overlays)
- Crear la convención `apps/` en `shoplab-infra`.
- Configurar las bases y overlays iniciales para los servicios del bloque de extracción temprana (`chat-service`, `retrieval-service`, `eval-service`).
- Parametrizar `deployment.yaml`, `service.yaml` y `externalsecret.yaml` por servicio.
- Asegurar compatibilidad con `policy-check.sh`.

### Phase 2: ApplicationSet de Argo CD
- Crear el manifiesto `applicationset-services-dev.yaml` en `k8s/bootstrap/`.
- Configurar el generador de directorios Git (`path: apps/*/overlays/dev`).
- Establecer políticas de sincronización automática (`prune: true`, `selfHeal: true`, `targetRevision: main`).
- Validar en el clúster la instanciación automática de aplicaciones en `shoplab-dev`.

### Phase 3: Concurrencia Segura en Job de CI (Rebase y Reintento)
- Actualizar el script de despliegue en `shoplab-infra` / `shoplab-ci` implementando `git pull --rebase` con bucle de 3 reintentos y backoff aleatorio.
- Implementar suite de pruebas automatizada para validar resolución de colisiones en push concurrente.

### Phase 4: Aprovisionamiento de Secretos y Verificación Synced/Healthy
- Validar la integración con External Secrets Operator para cada esquema de base de datos.
- Probar el alta de un nuevo servicio verificando que Argo CD lo sincroniza en < 3 minutos sin intervención manual.
- Documentar el procedimiento en `docs/despliegue-continuo.md`.
