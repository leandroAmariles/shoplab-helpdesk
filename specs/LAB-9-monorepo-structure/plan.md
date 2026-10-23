# Implementation Plan: Monorepo con la estructura acordada

**Branch**: `chore/LAB-9-monorepo-structure` | **Date**: 2026-10-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-9-monorepo-structure/spec.md`

## Summary

El commit inicial (`LAB-1`) ya creó las carpetas, el `.editorconfig` y un README parcial. Esta
historia es de documentación y repositorio, sin código de aplicación: (1) añadir `LICENSE` MIT a
nombre de Leandro Amariles (2026), (2) completar el README con prerrequisitos, arranque con los
pasos pendientes de F0-02 marcados, convenciones y licencia, y (3) verificar que estructura,
README y exclusiones de `.gitignore` coinciden. Enfoque técnico: ediciones de archivos de texto y
una verificación con comandos de git/shell; sin dependencias nuevas.

## Technical Context

**Language/Version**: N/A (Markdown y archivos de configuración de texto)

**Primary Dependencies**: Ninguna

**Storage**: N/A

**Testing**: Verificación por script de shell sobre un clon limpio (existencia de carpetas, licencia,
coincidencia README/estructura); detallada en [quickstart.md](quickstart.md)

**Target Platform**: Repositorio git alojado en GitHub; desarrollo en Windows, macOS y Linux

**Project Type**: Monorepo (frontend Angular, backend Spring Boot, ingest FastAPI, eval Python)

**Performance Goals**: N/A

**Constraints**: Fin de línea LF y UTF-8 según `.editorconfig`; carpetas vacías conservadas con
`.gitkeep`; sin secretos ni datos reales

**Scale/Scope**: 6 carpetas obligatorias + `data` y `notebooks`; 3 archivos tocados (`LICENSE`,
`README.md`, y `.gitignore` solo si la verificación lo exige)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular primero | N/A: no se crea código ni módulos; la estructura respeta ADR-0001. PASA |
| II. Hexagonal / pureza de dominio | N/A: sin código. PASA |
| III. Identificadores en inglés | N/A: sin identificadores de código; documentación en español permitida. PASA |
| IV. Test-first y cobertura | Sin código ejecutable que cubrir; la verificación se define antes de editar (quickstart). PASA |
| V. RAG guiado por evaluación | N/A: no cambia prompts ni retrieval. PASA |
| VI. Observabilidad y manejo seguro | La licencia y el README no contienen secretos; `.gitignore` excluye datos y config local. PASA |
| VII. Simplicidad y decisiones documentadas | Cambio mínimo; no hay decisión arquitectónica nueva, no requiere ADR. PASA |
| Flujo: rama y commits | Rama `chore/LAB-9-monorepo-structure`; commits `LAB-9 type(scope): ...`. PASA |

Resultado: sin violaciones; no se completa Complexity Tracking. Re-evaluación tras el diseño: sin
cambios, PASA.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-9-monorepo-structure/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

No se genera `contracts/`: la historia no expone interfaces a usuarios ni a otros sistemas.

### Source Code (repository root)

```text
.
├── LICENSE              # NUEVO: MIT, Leandro Amariles, 2026
├── README.md            # MODIFICADO: arranque, convenciones, licencia
├── .editorconfig        # existente: se verifica, sin cambios previstos
├── .gitignore           # existente: se verifica (data/raw, config local, telemetría)
├── frontend/.gitkeep
├── backend/.gitkeep
├── ingest/.gitkeep
├── eval/.gitkeep
├── infra/.gitkeep
├── docs/
│   ├── adr/             # ADR-0000 plantilla, 0001, 0002
│   └── fases/.gitkeep
├── data/kb/.gitkeep
└── notebooks/.gitkeep
```

**Structure Decision**: Se conserva el monorepo plano de primer nivel ya presente en el repo, sin
mover ni renombrar carpetas. Solo se añade `LICENSE` y se edita `README.md`.

## Complexity Tracking

Sin violaciones de la constitución; no aplica.
