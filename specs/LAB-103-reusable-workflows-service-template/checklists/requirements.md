# Specification Quality Checklist: Workflows reutilizables y plantilla de servicio

**Purpose**: Validate specification completeness and implementation quality
**Created**: 2026-11-24
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details leaking into domain boundaries
- [x] Focused on user value and developer platform velocity
- [x] Written for technical and architectural stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable (SC-001 to SC-008)
- [x] All acceptance scenarios are defined (US1 to US6)
- [x] Edge cases are identified and mitigated
- [x] Scope is clearly bounded (reusable CI + service template + docs)
- [x] Dependencies and assumptions identified (LAB-99, LAB-108, LAB-128 / ADR-0031)

## Implementation Verification

- [x] **Repo `shoplab-ci`**: workflows `java-service`, `python-service`, `angular-app` invocables vía `workflow_call` (@v1).
- [x] **Quality Gates**: compilación, tests con Testcontainers, JaCoCo ≥80 %, Qodana, Trivy fs/image HIGH/CRITICAL.
- [x] **Policies**: validación anti `:latest` y anti `CAMBIA-ESTE-OWNER`.
- [x] **Images & Cosign**: publicación en GHCR (`:sha` y `:vX.Y.Z`), firma keyless con Cosign vía OIDC y atestaciones SBOM.
- [x] **Deploy GitOps**: job reutilizable que ejecuta `kustomize edit set image` sobre `shoplab-infra` con token de GitHub App de alcance mínimo.
- [x] **Repo `shoplab-service-template`**: GitHub template repository con `spring-boot-starter-parent` (SB 4 / Java 21), Dockerfile, CI, `release-please`, Dependabot y dependencia fijada a `shoplab-contracts`.
- [x] **Paquete `shared`**: `GlobalExceptionHandler`, `ResponseCodeEnum` con prefijo de servicio, `X-Request-Id`, Flyway mono-esquema, ArchUnit por capas y JWT resource server (LAB-61).
- [x] **Regla de oro**: ningún servicio depende de código de otro, solo de contratos.
- [x] **Documentación**: `docs/fases/F5-creacion-de-servicios-desde-plantilla.md` y actualización en `docs/mapa-servicios.md`.
