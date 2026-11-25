# Specification Quality Checklist: Despliegue por servicio en dev con ApplicationSet

**Purpose**: Validate specification completeness and implementation quality
**Created**: 2026-11-25
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details leaking into domain boundaries
- [x] Focused on developer platform velocity, autonomous deployments, and reliability
- [x] Written for infrastructure, platform, and backend stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous (FR-001 to FR-015)
- [x] Success criteria are measurable (SC-001 to SC-006)
- [x] All acceptance scenarios are defined (US1 to US5) with Given/When/Then format
- [x] Edge cases are identified and mitigated (concurrency, missing overlays, secret resolution failure)
- [x] Scope is clearly bounded (Kustomize modular + Argo CD ApplicationSet + ExternalSecrets + CI job rebase)
- [x] Dependencies and assumptions identified (LAB-103, LAB-101, ADR-0031 / LAB-128)
- [x] Exclusions explicitly documented (Kafka/Strimzi/Schema Registry deferred to LAB-131 and LAB-46)

## Implementation Verification

- [x] **Estructura Kustomize**: `apps/<servicio>/base` y `apps/<servicio>/overlays/dev`.
- [x] **Argo CD ApplicationSet**: generador de directorios `apps/*/overlays/dev`, `automated`, `prune: true`, `selfHeal: true`, `targetRevision: main`.
- [x] **Concurrencia en CI**: integración con el job reutilizable de LAB-103 con `git pull --rebase` y reintentos exponenciales.
- [x] **ExternalSecrets por servicio**: credenciales dedicadas por esquema/base de datos sin contraseñas en Git.
- [x] **Alta desatendida**: nuevos servicios se incorporan al clúster simplemente creando su carpeta en Git.
- [x] **Salud y Sincronización**: todas las Applications en dev convergen a `Synced` y `Healthy`.
