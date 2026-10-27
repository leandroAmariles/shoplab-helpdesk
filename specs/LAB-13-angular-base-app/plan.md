# Implementation Plan: App Angular base

**Branch**: `feature/LAB-13-angular-base-app` | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-13-angular-base-app/spec.md`

## Summary

Se genera en `frontend/` una app Angular (standalone components, signals, control flow nativo) con **Angular
Material** y un tema propio, un layout base (cabecera + navegación lateral + `router-outlet`) con rutas cargadas de
forma diferida, página 404 y diseño adaptable. En desarrollo, un proxy del servidor de desarrollo redirige `/api` al
backend en `localhost:8080` sin buffering para que el SSE de LAB-12 fluya. La cadena existente se reutiliza: el job
`frontend` de CI (ya detecta `package.json`) pasa a ejecutar lint, pruebas y build reales, y el `Dockerfile` ya
compila con `npm run build` y copia `dist/browser`; solo se añade una configuración de nginx con *fallback* a
`index.html` para las rutas internas. Decisiones y riesgos por verificar en [research.md](research.md).

## Technical Context

**Language/Version**: TypeScript (modo estricto) sobre Angular 22.x; Node.js `^22.22.3` (requisito de Angular CLI 22.2)

**Primary Dependencies**: `@angular/core|router|forms`, `@angular/material` + `@angular/cdk` (22.x, misma línea que
el core), `angular-eslint` (lint); sin NgRx ni librerías de estado (signals bastan)

**Storage**: N/A

**Testing**: pruebas unitarias del *runner* por defecto de Angular 22 (a confirmar en T001; si es Vitest, el paso de
CI `--browsers=ChromeHeadless` se ajusta); lint con ESLint

**Target Platform**: navegadores modernos (evergreen) servidos por nginx sin privilegios en contenedor; desarrollo en
local con `ng serve`

**Project Type**: web-application (parte `frontend/` del monorepo; el backend ya existe)

**Performance Goals**: *bundle* inicial dentro del presupuesto por defecto del CLI (`angular.json` → `budgets`, que
hace fallar el build si se excede; rutas con `loadComponent`); sin objetivo de tiempo de arranque en esta historia

**Constraints**: sin CORS en desarrollo (proxy); proxy sin buffering para SSE; versión de Node declarada
(`.nvmrc` y `engines`); UI en español, identificadores en inglés; una sola librería de UI

**Scale/Scope**: 1 layout, 2 pantallas marcadoras (inicio y 404) más el hueco para `chat`; ~10 archivos de código

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación | Estado |
|-----------|------------|--------|
| I. Monolito modular | No toca el backend; `frontend/` es un componente separado del monorepo | N/A |
| II. Hexagonal | Aplica al backend. En el frontend se separa `core/` (servicios, interceptores), `layout/` y `features/` para que el acceso a `/api` quede tras servicios y no en componentes | Pasa |
| III. Identificadores en inglés | Código en inglés; textos de interfaz en español | Pasa |
| IV. Test-first y cobertura | El umbral del 80 % es del backend; en el frontend hay pruebas por componente/servicio nuevos y se ejecutan en CI (ver Assumptions del spec) | Pasa |
| V. RAG evaluado | No aplica | N/A |
| VI. Observabilidad y secretos | Sin secretos ni datos reales; el proxy usa un destino local, no credenciales | Pasa |
| VII. Simplicidad y ADR | Sin gestor de estado ni SSR (YAGNI). La elección Material vs PrimeNG es una decisión de arquitectura de UI: se registra en `docs/adr/0013-...` | Pasa, con ADR pendiente |

**Re-evaluación tras el diseño (Phase 1)**: sin cambios. No se introducen servicios ni persistencia.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-13-angular-base-app/
├── plan.md              # Este archivo
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/           # Phase 1
│   ├── dev-proxy.md
│   └── routes.md
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
frontend/
├── .nvmrc                       # versión de Node
├── angular.json                 # outputPath dist/browser (lo espera el Dockerfile)
├── package.json                 # scripts: start, build, test, lint
├── proxy.conf.json              # /api -> backend (sin buffering)
├── nginx.conf                   # fallback a index.html para rutas internas
├── Dockerfile                   # (existente) copia nginx.conf
├── src/
│   ├── index.html               # lang="es", <noscript>
│   ├── styles.scss              # tema Material
│   ├── main.ts
│   └── app/
│       ├── app.ts / app.config.ts / app.routes.ts
│       ├── core/api/api-error.interceptor.ts   # errores de conexión con /api
│       ├── layout/shell/        # cabecera + sidenav + router-outlet (signals, BreakpointObserver)
│       └── features/
│           ├── home/            # pantalla inicial
│           └── not-found/       # 404 dentro del layout
docs/adr/0013-biblioteca-ui-angular-material.md
.github/workflows/ci.yml         # ajustes de test (si el runner no es Karma)
README.md                        # sección frontend
```

**Structure Decision**: aplicación Angular única en `frontend/` (opción web-application del monorepo; el backend ya
vive en `backend/`). Capas `core` / `layout` / `features` con carga diferida por ruta para que el chat (LAB-14) se
añada como una carpeta nueva en `features/` y una línea en `app.routes.ts`, cumpliendo SC-004.

## Complexity Tracking

Sin violaciones de la constitución que justificar.
