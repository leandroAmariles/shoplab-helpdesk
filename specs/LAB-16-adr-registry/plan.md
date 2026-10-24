# Implementation Plan: Registro de decisiones de arquitectura (ADR)

**Branch**: `docs/LAB-16-adr-registry` | **Date**: 2026-10-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-16-adr-registry/spec.md`

## Summary

Historia solo de documentación. `docs/adr/` ya contiene la plantilla (`0000-plantilla.md`) y los
ADR 0001–0004. El enfoque es una **auditoría de brechas** contra la spec y un conjunto mínimo de
ediciones Markdown: reforzar la plantilla (estados, regla de numeración, cómo se sustituye un ADR),
añadir un índice `docs/adr/README.md` con la guía de uso, enlazarlo desde el `README.md` raíz y
dejar los ADR 0001 y 0002 conformes a la plantilla sin cambiar su decisión. No se toca código ni
configuración.

## Technical Context

**Language/Version**: Markdown (UTF-8, LF, sangría con espacios según `.editorconfig`)

**Primary Dependencies**: Ninguna

**Storage**: Archivos versionados en `docs/adr/`

**Testing**: Verificación manual guiada por [quickstart.md](quickstart.md) (comprobaciones de
archivos con `ls`/`grep`); sin pruebas automatizadas ni cobertura JaCoCo, no hay código

**Target Platform**: Repositorio Git (lectura en GitHub/IDE)

**Project Type**: Documentación de un monorepo

**Performance Goals**: N/A

**Constraints**: ADR en español; numeración de cuatro dígitos; ADR aceptados no se editan ni se
borran (solo se sustituyen); no renumerar 0003 ni 0004

**Scale/Scope**: 1 plantilla, 4 ADR existentes, 1 índice nuevo, 1 línea en el README raíz

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|-----------|------------|
| I. Monolito modular primero | Pasa. La historia documenta la decisión (ADR-0001); no la altera. |
| II. Arquitectura hexagonal | N/A. Sin código. |
| III. Identificadores en inglés, errores tipados | N/A. Sin código; la documentación puede ir en español. |
| IV. Pruebas y cobertura 80 % | N/A. Sin código; la verificación es el quickstart. |
| V. RAG guiado por evaluación | N/A. |
| VI. Observabilidad y datos seguros | Pasa. No se añaden secretos ni datos reales. |
| VII. Simplicidad y decisiones documentadas | Pasa. Es exactamente este principio: ADR en `docs/adr/` con la plantilla. Sin herramientas extra. |
| Flujo de trabajo | Pasa. Rama `docs/LAB-16-…` = tipo `docs`, nombre de carpeta de la spec = último segmento de la rama; commits `LAB-16 docs(adr): …`. |

Resultado: sin violaciones; no se requiere Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-16-adr-registry/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks (no lo crea este comando)
```

`contracts/` se omite: la historia no expone interfaces externas.

### Source Code (repository root)

```text
docs/adr/
├── README.md                              # NUEVO: índice, estados, numeración, cómo añadir/sustituir
├── 0000-plantilla.md                      # MODIFICA: estados, regla de numeración, campo "Sustituye a"
├── 0001-monolito-modular-primero.md       # AUDITA/AJUSTA: conformidad con la plantilla
├── 0002-postgres-pgvector.md              # AUDITA/AJUSTA: conformidad con la plantilla
├── 0003-ollama-modelos-locales.md         # AUDITA (solo coherencia, FR-010)
└── 0004-spring-boot-4-spring-ai-2.md      # AUDITA (solo coherencia, FR-010)
README.md                                  # MODIFICA: enlace al índice de ADR
```

**Structure Decision**: Se mantiene la carpeta plana `docs/adr/` existente; el índice vive en
`docs/adr/README.md` para que GitHub lo muestre al abrir la carpeta.

## Complexity Tracking

Sin violaciones de la constitución que justificar.

## Post-design Constitution Re-check

Tras el diseño (research, modelo, quickstart) el resultado no cambia: sigue sin violaciones.
