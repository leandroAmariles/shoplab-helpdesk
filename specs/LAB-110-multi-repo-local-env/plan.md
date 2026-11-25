# Implementation Plan: LAB-110 Entorno local con varios repos

**Branch**: `feature/LAB-110-multi-repo-local-env` | **Date**: 2026-11-25 | **Spec**: [specs/LAB-110-multi-repo-local-env/spec.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-110-multi-repo-local-env/spec.md)

**Input**: Feature specification from `/specs/LAB-110-multi-repo-local-env/spec.md`

---

## Summary

Establecer la arquitectura de orquestación y desarrollo local para el ecosistema multi-repositorio de **ShopLab Helpdesk**, garantizando que cualquier desarrollador pueda levantar la totalidad del sistema a partir de imágenes publicadas en GitHub Container Registry (GHCR) sin compilar el código de cada servicio, y que pueda trabajar de forma aislada en un servicio específico utilizando overrides de Docker Compose contra repositorios hermanos clonados en carpetas adyacentes.

La solución implementa:
1. **Consolidación del repositorio paraguas**: `shoplab-helpdesk` asume formalmente la custodia de los ADRs, guías de fase, docker compose central y arnés de pruebas de integración E2E.
2. **Orquestación completa con imágenes GHCR**: `infra/docker-compose.yml` incorpora todos los servicios de aplicación (`backend`, `frontend`, `ingest`) parametrizados con versiones fijadas e inmutables en `infra/.env` (prohibiendo `:latest`).
3. **Mecanismo de Compose Override**: Creación de `infra/docker-compose.override.yml.example` e incorporación a `.gitignore`, permitiendo compilar localmente cualquier servicio desde carpetas hermanas (`../../shoplab-*`) sin alterar el archivo base.
4. **Automatización de clonación multiplataforma**: Target `make clone-all` en `Makefile` y script equivalente `scripts/clone-all.ps1` en PowerShell para clonar los repositorios hermanos de forma idempotente.
5. **Documentación del flujo DevEx**: Actualización de [README.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/README.md) con el flujo de 4 pasos (clonar, levantar todo, cambiar un servicio con override, probar).

---

## Technical Context

**Language/Version**: 
* YAML (Docker Compose v2 specification)
* GNU Makefile / Bash
* PowerShell Core 7+ / Windows PowerShell 5.1
* Markdown (Documentación técnica y ADRs)

**Primary Dependencies**:
* Docker Engine 24+ & Docker Compose v2+
* Git 2.30+
* GNU Make (para Linux/macOS/Git Bash) y PowerShell (para Windows)
* Imágenes GHCR: `ghcr.io/leandroamariles/shoplab-backend`, `ghcr.io/leandroamariles/shoplab-frontend`, `ghcr.io/leandroamariles/shoplab-ingest`

**Storage**:
* PostgreSQL 16 con extensión `pgvector` (volumen Docker `pgdata`).
* MinIO / Redis / ClickHouse para perfil `llmops` (Langfuse).
* Sin base de datos compartida entre microservicios (esquemas independientes según ADR-0015 y ADR-0031).

**Testing**:
* `docker compose config`: Validación sintáctica estricta de manifiestos y variables de entorno.
* Script de verificación E2E / health checks contra endpoints expuestos (`http://localhost:8080/actuator/health`, `http://localhost:8000/health`, `http://localhost:4200`).
* Pruebas de idempotencia en scripts de clonación (`make clone-all` y `scripts/clone-all.ps1`).

**Target Platform**: Máquinas de desarrollo locales en Linux, macOS y Windows (WSL2 / PowerShell nativo).

**Project Type**: Plataforma de orquestación, scripts de automatización e infraestructura local.

**Performance Goals**:
* Arranque en frío de todos los servicios mediante imágenes de GHCR < 60 segundos (excluyendo tiempo inicial de descarga de modelos Ollama).
* Reconstrucción e inicio de un servicio individual con override < 30 segundos.
* Clonación del ecosistema completo (`clone-all`) en menos de 2 minutos.

**Constraints**:
* **Cero dependencias cruzadas en tiempo de compilación**: La construcción de cada servicio mediante override debe ser 100 % autónoma desde su carpeta hermana.
* **Prohibición de `:latest`**: Todos los contenedores de aplicación deben especificar tags inmutables en `.env`.
* **Idempotencia estricta**: Los scripts de clonación nunca deben sobreescribir ni interrumpir repositorios locales existentes.
* **Preservación del inicio simple**: Mantener la regla constitucional de que levantar todo el entorno local sea un único comando `docker compose -f infra/docker-compose.yml up -d --wait`.

**Scale/Scope**: 1 repositorio paraguas (`shoplab-helpdesk`), 6 repositorios hermanos orquestados, 3 servicios de aplicación integrados en Compose.

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio Constitucional | Estado | Justificación y Validación |
|---|---|---|
| **I. Modular Monolith First, Extract Later** | ✅ CUMPLE | Respeta la extracción temprana de `ingest` y `frontend` dictada por [ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md) mientras mantiene `shoplab-helpdesk` como paraguas y contenedor del backend transicional hasta F5. |
| **II. Hexagonal Architecture & Domain Purity** | ✅ CUMPLE | La orquestación en Docker respeta los límites de dominio y contratos de red; cero mezcla de lógica o librerías compartidas. |
| **III. English Identifiers, Typed Errors** | ✅ CUMPLE | Todos los nombres de servicios, variables de entorno, targets de Makefile y scripts están en inglés (`clone-all`, `BACKEND_IMAGE`, `FRONTEND_PORT`). |
| **IV. Test-First with Coverage Gate** | ✅ CUMPLE | Se establecen pruebas automatizadas de validación de sintaxis de Compose, pruebas de idempotencia en scripts y verificación E2E. |
| **V. Evaluation-Driven RAG** | ✅ CUMPLE | Permite ejecutar las evaluaciones RAG locales contra el sistema completo levantado con imágenes de GHCR. |
| **VI. Observability & Safe AI Handling** | ✅ CUMPLE | Mantiene intactos los perfiles de observabilidad (`observability` con Prometheus/Grafana y `llmops` con Langfuse) con claves y marcadores locales seguros. |
| **VII. Simplicity and Documented Decisions** | ✅ CUMPLE | Cumple el principio de simplicidad: un único `docker compose up` levanta todo el sistema con imágenes preconstruidas, sin forzar la clonación de todos los repos. |

---

## Project Structure

### Documentation & Specification (this feature)

```text
specs/LAB-110-multi-repo-local-env/
├── spec.md              # Especificación funcional y criterios de aceptación
├── plan.md              # Este plan de implementación
├── research.md          # Fase 0: Decisiones y resoluciones técnicas
├── data-model.md        # Fase 1: Matriz de servicios, puertos y variables .env
├── quickstart.md        # Fase 1: Guía paso a paso para el desarrollador
└── contracts/           # Fase 1: Contratos formales de Compose y clonación
    ├── compose-spec-contract.md
    └── clone-scripts-contract.md
```

### Source Code Impact (repository root)

```text
shoplab-helpdesk/
├── Makefile                                       # Target clone-all (idempotente)
├── scripts/
│   └── clone-all.ps1                              # Script equivalente para Windows PowerShell
├── infra/
│   ├── docker-compose.yml                         # Adición de backend y frontend con imágenes GHCR
│   ├── docker-compose.override.yml.example        # Plantilla documentada para build local en carpetas hermanas
│   ├── .env.example                               # Variables BACKEND_IMAGE, FRONTEND_IMAGE, INGEST_IMAGE
│   └── .gitignore                                 # Ignorar docker-compose.override.yml
└── README.md                                      # Guía de inicio rápido en 4 pasos (clonar, levantar, override, probar)
```

---

## Complexity Tracking

> Ninguna violación constitucional identificada. El diseño utiliza exclusivamente herramientas estándar de la industria (Docker Compose overrides y Makefile/PowerShell) sin añadir dependencias complejas ni herramientas propietarias.
