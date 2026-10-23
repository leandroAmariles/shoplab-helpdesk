# Implementation Plan: Infraestructura local con docker compose

**Branch**: `chore/LAB-10-local-infra-compose` | **Date**: 2026-10-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-10-local-infra-compose/spec.md`

## Summary

Entregar `infra/docker-compose.yml` que levanta con un único comando el perfil `core`: PostgreSQL 16
con pgvector y Ollama con un modelo de chat y uno de embeddings descargados automáticamente. Cada
servicio define un healthcheck; Ollama solo pasa a saludable cuando ambos modelos están
disponibles. Los perfiles `llmops`, `observability` y `kafka` quedan reservados (sin servicios
aún) y el README documenta requisitos de hardware, tabla de modelos por RAM/GPU y operación
(levantar, estado, cambiar modelo, reiniciar desde cero). Detalle de decisiones en
[research.md](research.md).

## Technical Context

**Language/Version**: N/A (configuración declarativa). Compose Specification; probado con Docker
Compose v2+ (local: v5.3.0). Un script `sh` POSIX para el entrypoint de Ollama.

**Primary Dependencies**: imágenes `pgvector/pgvector:pg16` y `ollama/ollama` (versión fijada por
variable, ver research D1/D2).

**Storage**: volúmenes con nombre para datos de Postgres y modelos de Ollama.

**Testing**: validación operativa con `docker compose config`, `up --wait`, consultas SQL y API de
Ollama (guion en [quickstart.md](quickstart.md)); no hay código de aplicación ni JaCoCo aplicable.

**Target Platform**: Windows 11 (WSL2/Docker Desktop), Linux y macOS; GPU NVIDIA opcional.

**Project Type**: infraestructura (carpeta `infra/` del monorepo).

**Performance Goals**: `core` saludable en < 2 min con modelos ya descargados (SC-002).

**Constraints**: un solo comando `docker compose -f infra/docker-compose.yml up` (constitución);
sin secretos reales versionados; CPU-only debe funcionar.

**Scale/Scope**: 2 servicios en `core`, 3 perfiles reservados, 1 override opcional de GPU.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / restricción | Evaluación |
|---|---|
| I. Monolito modular primero | N/A: no hay código de módulos. PASS |
| II. Hexagonal | N/A. Consecuencia: el acceso a LLM/embeddings seguirá detrás de puertos; aquí solo se provee el servidor. PASS |
| III. Identificadores en inglés | Servicios, variables y archivos de infraestructura en inglés; documentación en español. PASS |
| IV. Test-first / cobertura | Sin código de aplicación; la verificación es el guion de [quickstart.md](quickstart.md) (healthchecks como pruebas ejecutables). PASS |
| V. RAG evaluado | Fuera de alcance (desde F2). PASS |
| VI. Observabilidad y secretos | Credenciales por variables; solo valores de ejemplo en `infra/.env.example`; `.env` ya está en `.gitignore`. Perfil `observability` reservado. PASS |
| VII. Simplicidad / ADR | Solo `core` con servicios reales (YAGNI). PostgreSQL+pgvector único almacén (ADR-0002). Elección de Ollama como servidor de modelos local: ver abajo. PASS (con ADR propuesto) |
| Infraestructura | Comando único `docker compose -f infra/docker-compose.yml up` se respeta. PASS |
| Flujo | Rama `chore/LAB-10-local-infra-compose` = directorio de la spec. PASS |

**Decisión que requiere ADR**: usar Ollama como servidor local de modelos (reemplazable detrás de
puertos). Se registra como `docs/adr/0003-ollama-modelos-locales.md` durante la implementación
(Principio VII).

Re-evaluación tras el diseño (Fase 1): sin cambios; no hay violaciones, la sección de seguimiento de
complejidad no aplica.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-10-local-infra-compose/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── compose-interface.md
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks (no lo crea /speckit-plan)
```

### Source Code (repository root)

```text
infra/
├── docker-compose.yml          # servicios core + perfiles reservados
├── docker-compose.gpu.yml      # override opcional para GPU NVIDIA
├── .env.example                # variables con valores de ejemplo (versionado)
├── postgres/
│   └── init/
│       └── 01-extensions.sql   # CREATE EXTENSION vector (solo primer init)
└── ollama/
    └── entrypoint.sh           # arranca el servidor, descarga modelos, queda en primer plano
.gitattributes                  # fuerza LF en *.sh (el contenedor falla con CRLF)
docs/adr/0003-ollama-modelos-locales.md
README.md                       # arranque real, hardware, tabla de modelos, operación
```

**Structure Decision**: todo el entorno vive bajo `infra/` (restricción de la constitución). Se
retira `infra/.gitkeep` al añadir archivos reales (convención de LAB-9). `.gitattributes` es nuevo en
la raíz porque `.editorconfig` no protege contra la conversión CRLF de Git en Windows.

## Complexity Tracking

Sin violaciones de la constitución; no aplica.
