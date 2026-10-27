# Research: App Angular base

Verificado el 2026-10-26 con `npm view` (Angular CLI `22.2.4` es la última versión estable) y con el repositorio.

## R1. Versión de Node

- **Decision**: fijar Node `22.22.3` o superior de la línea 22 en `frontend/.nvmrc` y `engines` de `package.json`.
- **Rationale**: Angular CLI 22.2.4 declara `node: ^22.22.3 || ^24.15.0 || >=26`. La máquina de desarrollo actual
  tiene Node 22.18.0, **por debajo del mínimo**: hay que actualizarlo antes de generar el proyecto. CI usa
  `node-version: 22` (última 22.x, cumple) y el `Dockerfile` usa `node:22-alpine` fijado por digest (**verificar en
  T001 que esa imagen trae ≥ 22.22.3**; si no, Dependabot/bump del digest).
- **Alternatives**: Node 24 LTS (también soportado; se descarta para no cambiar CI y Dockerfile en esta historia).

## R2. Angular Material frente a PrimeNG

- **Decision**: Angular Material (+ CDK).
- **Rationale**: mantenido por el equipo de Angular, versiones sincronizadas con el core, accesibilidad incluida,
  theming con tokens Material 3, bien cubierto por `ng add`. El proyecto no necesita tablas/grids avanzados en F0.
- **Alternatives**: PrimeNG (más componentes ricos, p. ej. DataTable, pero tema y versionado propios). Revisable en
  F3/F4 si la consola de agentes lo pide; se documenta en ADR-0013.

## R3. Lint

- **Decision**: `angular-eslint` (`ng add angular-eslint`), script `npm run lint`.
- **Rationale**: el CLI ya no trae `ng lint` por defecto y el job de CI ejecuta `npm run lint`.
- **Alternatives**: sin lint (incumple FR-010).

## R4. Runner de pruebas y paso de CI

- **Decision**: usar el runner por defecto que genere el CLI 22 y adaptar el paso `Test` de CI si hace falta.
- **Rationale**: el paso actual es `npm test -- --watch=false --browsers=ChromeHeadless` (sintaxis de Karma). Desde
  Angular 21 el generador usa Vitest por defecto, que no entiende ese flag de la misma forma. **Riesgo a confirmar
  en T001**: si el comando falla, ajustar `ci.yml` (y `package.json`) para que corra sin interfaz y sin navegador
  instalado a mano (edge case del spec).
- **Alternatives**: forzar Karma/Jasmine al generar el proyecto (mantiene CI, pero va contra el valor por defecto).

## R5. Proxy de desarrollo y SSE

- **Decision**: `proxy.conf.json` con `/api` → `http://localhost:8080`, `changeOrigin: false`, `secure: false`; el
  objetivo se lee de un único archivo. Registrado en `angular.json` (`serve.options.proxyConfig`).
- **Rationale**: evita CORS (FR-007). El proxy del servidor de desarrollo reenvía el cuerpo en *streaming*; se valida
  con `POST /api/chat` de LAB-12 (cabecera `Accept: text/event-stream`) en el quickstart. Si el proxy acumulara,
  se pasa a un *proxy* en `proxy.conf.js` con `onProxyRes` que desactive compresión/buffering.
- **Alternatives**: CORS en el backend (obliga a configuración por entorno; descartado por el criterio de la
  historia).

## R6. Salida del build y despliegue

- **Decision**: `angular.json` → `outputPath: { base: "dist", browser: "browser" }` para que el artefacto quede en
  `dist/browser`, como espera el `Dockerfile`; añadir `nginx.conf` con `try_files $uri $uri/ /index.html` y copiarlo
  al directorio de configuración de nginx.
- **Rationale**: por defecto el CLI emite `dist/<proyecto>/browser`, y el nginx de la imagen devolvería 404 en
  recargas de rutas internas (US5). La imagen es *unprivileged* y escucha en 8080: la configuración debe respetarlo.
- **Alternatives**: cambiar la ruta en el `Dockerfile` (toca una cadena ya estable); se prefiere ajustar el proyecto.

## R7. Layout adaptable y estado

- **Decision**: `Shell` standalone con `mat-toolbar`, `mat-sidenav` y `router-outlet`; el modo (lateral/superpuesto)
  se calcula con `BreakpointObserver` convertido a signal (`toSignal`), y el estado abierto/cerrado es un `signal`.
- **Rationale**: cumple FR-002/FR-005 con APIs nativas, sin librerías extra.
- **Alternatives**: CSS puro con media queries (no gestiona el foco ni el cierre del menú en móvil).

## R8. Errores de conexión con `/api`

- **Decision**: un interceptor funcional que normaliza los errores de red y HTTP en un tipo `ApiError` y notifica con
  un `MatSnackBar`, sin tocar el layout.
- **Rationale**: FR-009 y edge case de 4xx/5xx. La lógica de streaming (`fetch` + SSE) llega con LAB-14 y no usa
  `HttpClient`; esta historia solo deja el cliente HTTP base.
- **Alternatives**: manejar errores en cada componente (duplicación).

## R9. Versionado y release

- **Decision**: añadir `frontend/package.json` a `extra-files` de `release-please-config.json` (el README ya lo
  anticipa), sin tocar `CHANGELOG.md` ni el manifiesto a mano.
- **Rationale**: una sola versión del repositorio (README, sección de versionado).
- **Alternatives**: dejar `package.json` en `0.0.0` (la versión de la imagen y la del paquete divergen).

## Verificación de T001 (2026-10-26)

- Node local: `22.22.3` (actualizado desde 22.18.0), npm `10.9.8`.
- Imagen `node:22-alpine` fijada en `frontend/Dockerfile` (digest `fe4f6d74…`): Node `22.23.4`, cumple el mínimo
  `22.22.3`. No hace falta subir el digest.

## Verificación de R4 (T007, 2026-10-26)

- El CLI 22 genera **Vitest + jsdom** (`@angular/build:unit-test`). `npm test -- --watch=false --browsers=ChromeHeadless`
  falla: la opción `browsers` exige instalar `@vitest/browser-*`.
- **Decisión**: no instalar un proveedor de navegador. El paso de CI pasa a `npm test -- --watch=false` (jsdom, sin
  navegador instalado a mano). Ver `.github/workflows/ci.yml`, job `frontend`.

## Dependencias vulnerables (hallazgo de T031, 2026-10-26)

- `@angular/build@22.2.4` fija `vite@8.3.0` exacto, con avisos HIGH de `npm audit` (vite y launch-editor). Trivy los
  marcaría en CI.
- **Decisión**: `overrides: { "vite": "8.3.5" }` en `frontend/package.json` (mismo minor, parche ya presente en el árbol
  por `vitest`). `npm audit`: 0 vulnerabilidades. Retirar el override cuando una versión de `@angular/build` dependa de
  `vite ≥ 8.3.5`.
