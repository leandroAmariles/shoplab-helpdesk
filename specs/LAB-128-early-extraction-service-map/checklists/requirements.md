# Specification Quality Checklist: ADR de extracción temprana y mapa de servicios

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-11-23
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation code leaks into the specification: focused on architecture decisions, service boundaries, protocols (MCP, REST, Kafka) and documentation deliverables.
- [x] Focused on team value, system sustainability and architectural clarity.
- [x] Written in clear Spanish following repository conventions for documentation.
- [x] All mandatory sections completed (User Scenarios, Requirements, Key Entities, Success Criteria, Assumptions).

## Requirement Completeness

- [x] All 5 Jira acceptance criteria of LAB-128 explicitly covered in User Stories and Functional Requirements.
- [x] Clear resolution of ADR numbering discrepancy (ADR-0029 in Jira vs ADR-0031 in repository sequential order).
- [x] Exact scope of the 8 destination services defined (`chat`, `retrieval`, `eval`, `catalog`, `order`, `ticket`, `gateway`, `notification`).
- [x] Database schema sovereignty clearly stated for each service (no cross-service joins or FKs).
- [x] Protocol for agent tools explicitly defined as Model Context Protocol (MCP) in network mode.
- [x] Clear ban on shared domain libraries; service template approach (LAB-103) mandated for technical base.
- [x] Strict separation between `eval-service` (Java) and `shoplab-eval` (Python).
- [x] Requirements are testable, verifiable and unambiguous.
- [x] Success criteria are measurable and technology-agnostic.
- [x] Edge cases identified and addressed.

## Feature Readiness

- [x] Precedence correctly noted: unlocks LAB-108, LAB-103, and the service extraction block.
- [x] Traceability to Jira LAB-128 (F5-21) and Constitution Core Principles (Principle I & II).
- [x] Ready to proceed to planning and tasks generation.

## Notes

- **Criterios de Jira (LAB-128 / F5-21)**:
  - *ADR-0029/ADR-0031 «Extracción temprana de servicios» que sustituye el calendario de ADR-0001 y ADR-0015.*
  - *Mapa de servicios en `docs/`: chat, retrieval, eval, catalog, order y ticket, más gateway y notification.*
  - *Las herramientas del agente se publican por MCP en el servicio dueño y `chat` es cliente MCP.*
  - *Decisión sobre el módulo `shared` (ADR-0010): cada servicio lleva su copia desde la plantilla de LAB-103, sin librería común con dominio.*
  - *eval-service en Java con repo propio, a partir del módulo `eval`; el repo `shoplab-eval` queda para la herramienta Python.*
  - *Va antes de: LAB-108, LAB-103 y el resto del bloque de extracción.*
