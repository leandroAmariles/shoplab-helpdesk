# Tasks: App Angular base

**Input**: Design documents from `/specs/LAB-13-angular-base-app/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: se piden (constitución, principio IV): cada componente y servicio nuevo lleva su prueba unitaria, escrita
antes o junto al código. Ninguna prueba necesita el backend real ni un navegador instalado a mano.

**Organization**: tareas agrupadas por historia de usuario en orden de prioridad: **US1 (P1) → US2 (P1) → US3 (P1) →
US4 (P2) → US5 (P3)**. Lo que bloquea a todas (proyecto generado, lint, runner de pruebas, salida del build) va en
las fases 1 y 2.

Abreviaturas de rutas (relativas a la raíz del repositorio):

- `FE` = `frontend`
- `APP` = `frontend/src/app`
- `SPEC` = `specs/LAB-13-angular-base-app`

Estructura, contratos y modelo: [plan.md](plan.md), [contracts/dev-proxy.md](contracts/dev-proxy.md),
[contracts/routes.md](contracts/routes.md), [data-model.md](data-model.md).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de `spec.md` a la que pertenece (US1…US5)
- Identificadores de código en inglés; textos de interfaz en español. Conservar `FE/.gitkeep` y `FE/.dockerignore`.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: entorno correcto y proyecto generado.

- [X] T001 Verificar `node -v` ≥ `22.22.3` (Angular CLI 22.2.4 lo exige; la máquina tenía 22.18.0, actualizarlo con nvm/instalador si hace falta) y anotar en `SPEC/research.md` (R1) la versión usada y si la imagen `node:22-alpine` fijada en `FE/Dockerfile` cumple el mínimo (`docker run --rm node:22-alpine@<digest> node -v`); si no, dejar anotado el bump de digest como parte de T030
- [X] T002 Generar el proyecto en `FE/` sin sobrescribir `Dockerfile`, `.dockerignore` ni `.gitkeep`: `npx @angular/cli@22 new shoplab-helpdesk --directory frontend --style=scss --routing --ssr=false --skip-git` (ejecutar desde un directorio temporal y mover los archivos si el CLI rechaza una carpeta no vacía); confirmar que `FE/package.json` tiene `"name": "shoplab-helpdesk"` y que los componentes generados son standalone con signals
- [X] T003 [P] Crear `FE/.nvmrc` con `22.22.3` , añadir `"engines": { "node": "^22.22.3 || ^24.15.0" }` en `FE/package.json` y crear `FE/.npmrc` con `engine-strict=true` para que `npm ci` falle con un mensaje claro si la versión de Node no cumple (FR-012, edge case del spec)
- [X] T004 [P] Poner `<html lang="es">` y un `<noscript>` con el aviso «Activa JavaScript para usar ShopLab Helpdesk» en `FE/src/index.html`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: lo que bloquea a todas las historias: lint, pruebas en CI y ruta de salida del build.

**⚠️ CRITICAL**: ninguna historia puede empezar hasta terminar esta fase.

- [X] T005 Añadir lint con `cd FE && npx ng add angular-eslint` y comprobar que `npm run lint` existe en `FE/package.json` y termina en verde sobre el proyecto generado (FR-010)
- [X] T006 Fijar en `FE/angular.json` `build.options.outputPath` como `{ "base": "dist", "browser": "browser" }` para que el artefacto quede en `FE/dist/browser/` (lo espera `FE/Dockerfile`, R6) y comprobar con `npm run build`
- [X] T007 Comprobar el runner de pruebas que generó el CLI (R4): ejecutar `npm test -- --watch=false --browsers=ChromeHeadless` en `FE/`; si el runner no acepta ese comando, ajustar el script `test` de `FE/package.json` y el paso `Test` del job `frontend` de `.github/workflows/ci.yml` para que corran sin interfaz y sin navegador instalado a mano; anotar la decisión en `SPEC/research.md` (R4)
- [X] T008 Ejecutar en `FE/` `npm run lint`, `npm test` (comando de T007) y `npm run build`: los tres deben terminar con 0 errores; anotar esta línea base en `SPEC/validation.md` (créalo)

**Checkpoint**: proyecto base reproducible; las historias pueden empezar.

---

## Phase 3: User Story 1 - Proyecto Angular que arranca y compila (Priority: P1) 🎯 MVP

**Goal**: el proyecto en `frontend/` arranca, pasa pruebas y compila, y el job `frontend` de CI deja de ser un paso de
relevo.

**Independent Test**: en una copia limpia, `npm ci && npm run lint && npm test && npm run build && npm start`; la app
abre sin errores en la consola ([quickstart.md](quickstart.md), escenario 1).

- [X] T009 [US1] Comprobar que `FE/src/app/app.config.ts` usa `provideRouter` y `provideHttpClient(withInterceptors([]))`, que el proyecto no tiene `NgModule` (`grep -r "NgModule" FE/src` sin resultados) y que el estado local de la interfaz usa signals (`grep -rE "signal\(|toSignal\(" FE/src/app` devuelve resultados una vez hecha T020); dejar la detección de cambios como la generó el CLI
- [ ] T010 [US1] Verificar que el job `frontend` de `.github/workflows/ci.yml` detecta `FE/package.json` y ejecuta `npm ci`, `npm run lint`, test y build reales; en el PR comprobar que ningún paso termina en «skipping Angular checks» (FR-010, SC-005)
- [X] T011 [P] [US1] Añadir `frontend/package.json` a `extra-files` de `release-please-config.json` con `{ "type": "json", "path": "frontend/package.json", "jsonpath": "$.version" }` y dos entradas más para `frontend/package-lock.json` (`$.version` y `$.packages[""].version`), para que `npm ci` en CI no falle por versiones divergentes (R9); no tocar `CHANGELOG.md` ni el manifiesto
- [X] T012 [US1] Documentar en `README.md` (sustituir la frase «Los pasos de arranque de `frontend/` … se documentan cuando cada uno tenga código» por una sección propia para `frontend/`): requisito de Node, `npm ci`, `npm start`, `npm test`, `npm run lint`, `npm run build` (FR-013)

**Checkpoint**: US1 completa y demostrable.

---

## Phase 4: User Story 2 - Librería de componentes visuales integrada (Priority: P1)

**Goal**: Angular Material instalado y tematizado; una sola librería.

**Independent Test**: un componente Material se muestra con el tema del proyecto
([quickstart.md](quickstart.md), escenario 2, paso 1).

- [X] T013 [US2] Instalar Angular Material con `cd FE && npx ng add @angular/material` eligiendo tema personalizado y tipografía; verificar que `@angular/material` y `@angular/cdk` quedan en la misma línea (22.x) que `@angular/core` y que **no** hay dependencias de `primeng` (`grep -c primeng FE/package.json` = 0)
- [X] T014 [US2] Definir el tema (paleta, tipografía) en `FE/src/styles.scss` con los tokens de Material 3 y un color primario acorde al proyecto
- [X] T015 [P] [US2] Escribir el ADR `docs/adr/0013-biblioteca-ui-angular-material.md` con la plantilla `docs/adr/0000-plantilla.md`: contexto, decisión (Angular Material), alternativa descartada (PrimeNG) y consecuencias, usando R2 de `SPEC/research.md`; añadir la fila al índice de `docs/adr/README.md`

**Checkpoint**: US2 completa; la decisión de librería queda documentada (FR-003).

---

## Phase 5: User Story 3 - Layout base de la aplicación (Priority: P1)

**Goal**: layout con cabecera, navegación y contenido, rutas diferidas, 404 y diseño adaptable.

**Independent Test**: [quickstart.md](quickstart.md), escenario 2 completo.

### Tests for User Story 3 ⚠️ escribir primero y comprobar que fallan

- [X] T016 [P] [US3] Prueba del layout en `FE/src/app/layout/shell/shell.spec.ts`: en ancho amplio el menú lateral está abierto y en modo `side`; en ancho de móvil está cerrado y en modo `over`; navegar cierra el menú solo en móvil (simular `BreakpointObserver` con un doble)
- [X] T017 [P] [US3] Prueba de rutas en `FE/src/app/app.routes.spec.ts`: `''` carga Inicio, una ruta inexistente carga «No encontrado» y ambas se renderizan dentro del `Shell`; la ruta `**` es la última de la configuración

### Implementation for User Story 3

- [X] T018 [P] [US3] Crear la pantalla de inicio standalone `FE/src/app/features/home/home.ts` (+ plantilla/estilos) con título «ShopLab Helpdesk» y un texto breve de estado en español, usando componentes Material; escribir antes `FE/src/app/features/home/home.spec.ts` (se crea y muestra el título)
- [X] T019 [P] [US3] Crear la pantalla `FE/src/app/features/not-found/not-found.ts` con el mensaje «Página no encontrada» y un enlace de vuelta al inicio; escribir antes `FE/src/app/features/not-found/not-found.spec.ts` (muestra el mensaje y el enlace apunta a `/`)
- [X] T020 [US3] Crear `FE/src/app/layout/shell/shell.ts` (+ plantilla/estilos): `mat-toolbar` con título y botón de menú, `mat-sidenav` con los `NavItem` ([data-model.md](data-model.md)) y `router-outlet`; `isHandset` con `toSignal(BreakpointObserver.observe(Breakpoints.Handset))` y `sidenavOpen` como `signal`; sin desbordes a 360 px (FR-004, FR-005)
- [X] T021 [US3] Declarar en `FE/src/app/app.routes.ts` el `Shell` como ruta padre con hijas `''` (Inicio) y `**` (No encontrado, **última**), ambas con `loadComponent` y `title` en español; derivar de esa configuración la lista de `NavItem` del menú (una sola fuente de verdad, SC-004) (FR-006)
- [X] T022 [US3] Dejar la plantilla de `FE/src/app/app.ts` como solo `<router-outlet />` y ajustar `FE/src/app/app.spec.ts` generado por el CLI para que siga pasando; ejecutar T016 y T017 hasta que estén en verde

**Checkpoint**: US3 completa; el chat (LAB-14) se añade con una carpeta en `features/` y una línea en `app.routes.ts`.

---

## Phase 6: User Story 4 - Llamadas al backend sin configurar CORS (Priority: P2)

**Goal**: `/api` redirigido al backend en desarrollo, con SSE sin buffering y errores de conexión controlados.

**Independent Test**: [quickstart.md](quickstart.md), escenario 3.

### Tests for User Story 4 ⚠️ escribir primero y comprobar que fallan

- [X] T023 [P] [US4] Prueba del interceptor en `FE/src/app/core/api/api-error.interceptor.spec.ts` con `HttpTestingController`: un fallo de red produce `ApiError { kind: 'network' }`, un 500 produce `{ kind: 'http', status: 500 }`, ambos con mensaje en español, y un 200 pasa sin tocar la respuesta

### Implementation for User Story 4

- [X] T024 [US4] Crear `FE/proxy.conf.json` con `"/api": { "target": "http://localhost:8080", "secure": false, "changeOrigin": false }` y registrarlo en `FE/angular.json` en `serve.options.proxyConfig` (FR-007); el destino queda en este único archivo
- [X] T025 [P] [US4] Crear el tipo `ApiError` en `FE/src/app/core/api/api-error.ts` según [data-model.md](data-model.md)
- [X] T026 [US4] Crear `FE/src/app/core/api/api-error.interceptor.ts` (interceptor funcional) que normaliza el error a `ApiError` y avisa con `MatSnackBar` sin tocar el layout; registrarlo en `FE/src/app/app.config.ts` con `withInterceptors([...])` (FR-009); T023 en verde
- [X] T027 [US4] Validar el proxy con el backend levantado (`cd backend && ./mvnw spring-boot:run`, `cd FE && npm start`) siguiendo [quickstart.md](quickstart.md) escenario 3: `curl -N` a `http://localhost:4200/api/chat` debe mostrar eventos uno a uno; si el proxy los acumula, pasar a `FE/proxy.conf.js` con desactivación de buffering/compresión (R5) y actualizar `angular.json` y `SPEC/research.md`; registrar el resultado en `SPEC/validation.md`

**Checkpoint**: US4 completa; `/api` funciona en desarrollo sin CORS.

---

## Phase 7: User Story 5 - Imagen de contenedor con la app real (Priority: P3)

**Goal**: la imagen de `frontend` sirve la app compilada y resuelve rutas internas en recarga.

**Independent Test**: [quickstart.md](quickstart.md), escenario 4.

- [X] T028 [P] [US5] Crear `FE/nginx.conf` para nginx sin privilegios (puerto 8080, `root /usr/share/nginx/html`, `location / { try_files $uri $uri/ /index.html; }`, caché larga solo para archivos con hash) (FR-011)
- [X] T029 [US5] Editar `FE/Dockerfile`: copiar `nginx.conf` a `/etc/nginx/conf.d/default.conf` en la etapa final con `--chown=101:101`; reemplazar el bloque `RUN if [ -f package.json ] ...` por `RUN npm ci && npm run build` y borrar el comentario de «placeholder»; mantener digests, usuario 101 y el `apk upgrade`
- [X] T030 [US5] Construir y probar la imagen (`docker build -t shoplab-frontend frontend && docker run --rm -p 8081:8080 shoplab-frontend`): `/` sirve la app y recargar `/algo` devuelve la app y no un 404 de nginx; si T001 detectó que la imagen Node fijada no cumple el mínimo, subir su digest y registrarlo; anotar el resultado en `SPEC/validation.md`

**Checkpoint**: US5 completa.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T031 Ejecutar la verificación completa en `FE/`: `npm ci && npm run lint && npm test && npm run build` (0 errores, 0 advertencias de lint, SC-002) y revisar el ancho 360 px y 1440 px en el navegador (SC-006); registrarlo en `SPEC/validation.md`
- [X] T032 Revisar `git status --short` y `git diff --staged`: sin `node_modules/`, `dist/`, `.angular/` ni archivos temporales, y con `FE/.gitignore` generado por el CLI incluyendo esas rutas
- [ ] T033 Confirmar que el workflow `.github/workflows/images.yml` construye la imagen de `frontend` con el nuevo `Dockerfile` (revisar la ejecución del PR) y actualizar la mención de «página de relevo» en `README.md` (sección de imágenes) para que refleje que ya contiene la app real
- [ ] T034 Marcar los criterios de aceptación en Jira LAB-13 solo después de que el PR esté fusionado y el job `frontend` de CI haya pasado sobre el proyecto real

---

## Dependencies & Execution Order

- **Fase 1 → Fase 2**: la fase 2 necesita el proyecto generado (T002).
- **Fase 2 bloquea todas las historias**.
- **US1** (T009–T012) depende solo de la fase 2.
- **US2** (T013–T015) depende de la fase 2; puede ir en paralelo con US1.
- **US3** (T016–T022) depende de US2 (usa componentes Material).
- **US4** (T023–T027) depende de la fase 2; T026 usa `MatSnackBar` (US2); T027 necesita el backend de LAB-12.
- **US5** (T028–T030) depende de US1 (T006 deja `dist/browser`).
- **Polish** (T031–T034) al final.

## Parallel Opportunities

- Fase 1: T003 y T004 en paralelo tras T002.
- US1: T011 en paralelo con T009/T010.
- US2: T015 en paralelo con T013/T014.
- US3: T016 y T017 (pruebas) en paralelo; T018 y T019 en paralelo.
- US4: T023 y T025 en paralelo.
- US5: T028 en paralelo con cualquier tarea de US3/US4.

## Implementation Strategy

- **MVP**: Fases 1–2 + US1 (proyecto reproducible y CI real). Con US2 y US3 se obtiene la base sobre la que LAB-14 monta
  el chat.
- **Entrega incremental**: US1 → US2 → US3 → US4 → US5; cada checkpoint deja la app arrancable.
- **Riesgos a cerrar pronto**: versión de Node (T001), comando de pruebas en CI (T007) y buffering del proxy con SSE
  (T027).
