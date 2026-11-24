# Implementation Plan: Workflows reutilizables y plantilla de servicio (shoplab-ci y shoplab-service-template)

**Branch**: `feature/LAB-103-reusable-workflows-service-template` | **Date**: 2026-11-24 | **Spec**: [specs/LAB-103-reusable-workflows-service-template/spec.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-103-reusable-workflows-service-template/spec.md)

**Input**: Feature specification from `/specs/LAB-103-reusable-workflows-service-template/spec.md`

---

## Summary

Establecer la plataforma de estandarización de pipelines de CI/CD y el arquetipo de microservicios para el ecosistema distribuido de ShopLab Helpdesk (adelantado de F5 al bloque de extracción temprana según [ADR-0015](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0015-limites-de-contexto-y-repositorios.md) y [ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md)). La solución implementa:
1. El repositorio central de soporte **`shoplab-ci`** con workflows reutilizables (`workflow_call`) versionados mediante tags inmutables (`@v1`) para `java-service`, `python-service` y `angular-app`.
2. Puertas de calidad integradas: compilación, tests unitarios e integración con Testcontainers, control de cobertura JaCoCo ≥80 %, análisis estático Qodana, escaneo de vulnerabilidades Trivy (fs y contenedor con corte en HIGH/CRITICAL) y políticas anti `:latest` y `CAMBIA-ESTE-OWNER`.
3. Empaquetado, generación de SBOM y firma criptográfica keyless con Cosign vía OIDC en GHCR (`ghcr.io/leandroamariles/<servicio>`).
4. Job de despliegue reutilizable GitOps que actualiza `shoplab-infra` mediante `kustomize edit set image` con token de alcance mínimo de GitHub App.
5. El repositorio plantilla oficial **`shoplab-service-template`** (GitHub Template Repository) con Spring Boot 4.x, Java 21, WebFlux, Flyway mono-esquema, paquete transversal `shared` (errores tipados, `X-Request-Id`, logging seguro), reglas ArchUnit por capas, Resource Server JWT y dependencia fijada a `shoplab-contracts` (cero acoplamiento de dominio entre repositorios).
6. Guía operativa en `docs/fases/` documentando la creación de servicios desde la plantilla en menos de 15 minutos.

---

## Technical Context

**Language/Version**: Java 21 (Eclipse Temurin), Python 3.11+, TypeScript 5.x / Angular, Bash / YAML (GitHub Actions).

**Primary Dependencies**:
* **Backend Template**: Spring Boot 4.x (WebFlux), Spring Security OAuth2 Resource Server, Flyway (PostgreSQL), ArchUnit 1.3.x, Testcontainers 1.20.x, `shoplab-contracts` (LAB-108).
* **CI/CD Platform**: GitHub Actions (`workflow_call`), JetBrains Qodana Action, Aqua Security Trivy Action, Sigstore Cosign Installer, Docker Buildx / Build-Push Action, Imre/Kustomize Action, Actions Create GitHub App Token.

**Storage**: PostgreSQL 16 + pgvector (esquema exclusivo por servicio administrado por Flyway).

**Testing**:
* Tests unitarios y de integración reactivos (`StepVerifier`, `@WebFluxTest`, Mockito).
* Pruebas de integración con Testcontainers (PostgreSQL real sin mocks).
* Pruebas ejecutables de arquitectura con ArchUnit (`HexagonalArchitectureTest`).
* Verificación de cobertura con JaCoCo (umbral estricto ≥80 %).
* Escaneo continuo con Trivy y Qodana.

**Target Platform**: Kubernetes / Amazon EKS (despliegue continuo gestionado por Argo CD desde `shoplab-infra`); ejecución local vía Docker Compose.

**Project Type**: Repositorios de infraestructura/soporte (`shoplab-ci`), plantilla arquetipo (`shoplab-service-template`) e integración de entrega continua (`shoplab-infra`).

**Performance Goals**:
* Ejecución de CI (`java-service`) en PR < 4 minutos.
* Instanciación y verificación local de un nuevo servicio desde la plantilla < 15 minutos.
* Despliegue GitOps automático en `shoplab-infra` tras publicación de release < 2 minutos.

**Constraints**:
* Aislamiento absoluto de microservicios: **cero dependencias de código entre servicios ni hacia el monolito** (ADR-0010, ADR-0015, ADR-0031).
* Token de despliegue con alcance estrictamente acotado a `shoplab-infra:contents:write`.
* Firma keyless sin gestión de claves estáticas.
* Todo commit y tag de versión gobernado por `release-please` y Conventional Commits.

**Scale/Scope**: Plataforma común que gobernará los 8 servicios del ecosistema (`chat-service`, `retrieval-service`, `eval-service`, `catalog-service`, `order-service`, `ticket-service`, `notification-service`, `gateway`).

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio Constitucional | Estado | Justificación y Cumplimiento |
|---|---|---|
| **I. Modular Monolith First, Extract Later** | PASS | La creación de `shoplab-ci` y `shoplab-service-template` materializa la extracción temprana aprobada en [ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md) (LAB-128). Provee los cimientos de soporte para que los servicios extraídos en S19–S24 nazcan con estándares homogéneos sin duplicar pipelines. |
| **II. Hexagonal Architecture and Domain Purity** | PASS | La plantilla `shoplab-service-template` estructura físicamente las capas `domain`, `application` e `infrastructure`, e incorpora reglas de ArchUnit ejecutables que impiden que el dominio importe dependencias de Spring, Reactor, Netty o bases de datos. |
| **III. English Identifiers, Typed Errors** | PASS | Todos los identificadores, workflows y scripts se escriben en inglés. El paquete `shared` de la plantilla implementa `ResponseCodeEnum` con prefijos parametrizables (`<PREFIX>_<SUBSYSTEM>_<NNNNN>`) y `GlobalExceptionHandler` con envelopes `ResponseDto` y `ErrorDto`. |
| **IV. Test-First with Coverage Gate** | PASS | `shoplab-ci` incluye la puerta no negociable de JaCoCo ≥80 % y ejecuta pruebas de integración reales con Testcontainers en cada PR. La plantilla viene con pruebas unitarias y de integración de ejemplo que superan el 85 % de cobertura base. |
| **V. Evaluation-Driven RAG** | N/A | La plataforma de CI y la plantilla de servicio proporcionan la infraestructura técnica; los servicios con lógica RAG (`retrieval`, `chat`) ejecutan la evaluación sobre estos workflows. |
| **VI. Observability and Safe AI Handling** | PASS | La plantilla incluye `SecureLogger` con enmascaramiento, filtro reactivo de correlación `X-Request-Id`, configuración de logging JSON con Logstash para producción y preparación para trazas OTLP / OpenTelemetry. |
| **VII. Simplicity and Documented Decisions** | PASS | En lugar de un complejo generador Maven archetype interactivo o CLI propietario, se utiliza la funcionalidad nativa de GitHub "Template Repository", simple, intuitiva y estándar. Todo el flujo queda documentado en `docs/fases/`. |

---

## Project Structure

### Documentation (this feature)

```text
specs/LAB-103-reusable-workflows-service-template/
├── spec.md              # Feature specification
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   ├── reusable-workflows-contract.md
│   ├── deploy-job-contract.md
│   └── service-template-contract.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code Architecture & Layout

#### 1. Repositorio de Soporte: `shoplab-ci`
```text
shoplab-ci/
├── .github/
│   ├── workflows/
│   │   ├── java-service.yml       # Workflow reutilizable Java 21 / Spring Boot 4.x
│   │   ├── python-service.yml     # Workflow reutilizable Python / uv
│   │   ├── angular-app.yml        # Workflow reutilizable Angular / Node.js
│   │   ├── publish-image.yml      # Job reutilizable build, SBOM y firma Cosign
│   │   ├── deploy.yml             # Job reutilizable GitOps sobre shoplab-infra
│   │   ├── release-please.yml     # Versionado semántico de los workflows
│   │   └── pr-title.yml           # Verificación Conventional Commits
│   └── dependabot.yml
└── README.md                      # Documentación técnica de inputs, outputs y secretos
```

#### 2. Repositorio Plantilla: `shoplab-service-template`
```text
shoplab-service-template/
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                 # Invoca shoplab-ci/java-service.yml@v1
│   │   ├── pr-title.yml           # Verificación Conventional Commits
│   │   ├── release-please.yml     # Versionado automático de servicio
│   │   └── deploy.yml             # Invoca shoplab-ci/deploy.yml@v1
│   ├── dependabot.yml
│   ├── release-please-config.json
│   └── .release-please-manifest.json
├── src/
│   ├── main/
│   │   ├── java/com/shoplab/template/
│   │   │   ├── TemplateServiceApplication.java
│   │   │   ├── shared/            # Paquete transversal sin lógica de dominio
│   │   │   │   ├── exception/     # BusinessException, TechnicalException, ResponseCodeEnum
│   │   │   │   ├── filter/        # CorrelationFilter (X-Request-Id, traceparent)
│   │   │   │   ├── model/         # ResponseDto, ErrorDto
│   │   │   │   └── logging/       # SecureLogger
│   │   │   ├── domain/            # Capa Domain pura (cero dependencias externas)
│   │   │   ├── application/       # Capa Application (puertos y use cases)
│   │   │   └── infrastructure/    # Adaptadores IN (REST, Security) y OUT (Persistence)
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── logback-spring.xml
│   │       └── db/migration/template/ # Migraciones Flyway mono-esquema
│   └── test/
│       └── java/com/shoplab/template/
│           ├── architecture/      # HexagonalArchitectureTest (ArchUnit)
│           └── persistence/       # FlywayMigrationIntegrationTest (Testcontainers)
├── pom.xml                        # Maven Parent Spring Boot 4.x + shoplab-contracts
├── Dockerfile                     # Multi-stage distroless/jre sin root
└── README.md
```

#### 3. Repositorio Monolito: `shoplab-helpdesk` (Documentación Operativa)
```text
docs/fases/
└── F5-creacion-de-servicios-desde-plantilla.md # Guía operativa de extracción y creación
```

---

## Complexity Tracking

> **No violations**: El diseño se ajusta estrictamente a la Constitución de ShopLab Helpdesk y a los ADRs aprobados ([ADR-0010](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0010-modulo-shared-errores.md), [ADR-0015](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0015-limites-de-contexto-y-repositorios.md) y [ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md)).
