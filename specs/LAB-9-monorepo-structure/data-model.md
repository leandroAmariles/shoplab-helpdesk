# Data Model: Monorepo con la estructura acordada

La historia no maneja datos de aplicación. Las entidades son artefactos del repositorio.

## Carpeta de componente

| Campo | Descripción | Regla de validación |
|---|---|---|
| name | `frontend`, `backend`, `ingest`, `eval`, `infra`, `docs` (más `data`, `notebooks`) | Existe en la raíz y está versionada (FR-001, FR-002) |
| purpose | Responsabilidad de la carpeta | Aparece en la tabla del README (FR-003) |
| placeholder | `.gitkeep` mientras la carpeta no tenga contenido real | Se retira al añadir contenido |

Relación: cada carpeta se describe exactamente una vez en el README; el README no describe
carpetas inexistentes (SC-003).

## Documento de entrada (README)

| Sección | Requisito |
|---|---|
| Propósito y stack | FR-003 |
| Estructura (tabla de carpetas) | FR-003 |
| Fases | contexto existente |
| Arrancar (prerrequisitos y pasos, pendientes marcados) | FR-004 |
| Convenciones (ramas `<type>/LAB-<n>-<slug>`, commits `LAB-<n> ...`, backlog Jira) | FR-005 |
| Licencia | FR-008 |

## Licencia

| Campo | Valor |
|---|---|
| tipo | MIT (SPDX `MIT`) |
| titular | Leandro Amariles |
| año | 2026 |
| archivo | `LICENSE` en la raíz (FR-007) |

## Configuración de editor

`.editorconfig` raíz con `root = true`: UTF-8, LF, espacios, salto de línea final (FR-006).
Estado: ya presente; solo se verifica.

## Transiciones de estado

Carpeta: `vacía (con .gitkeep)` -> `con contenido` (otra historia). Sin otras transiciones.
