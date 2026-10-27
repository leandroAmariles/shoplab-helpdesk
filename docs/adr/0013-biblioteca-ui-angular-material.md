# ADR-0013: Angular Material como biblioteca de componentes del frontend

- Estado: aceptado
- Fecha: 2026-10-26

## Contexto

LAB-13 crea la app Angular base del proyecto (`frontend/`) y su criterio de aceptación pide «Angular Material o
PrimeNG». La elección condiciona el tema, la accesibilidad y el aspecto de todas las pantallas posteriores (chat con
streaming, consola de agentes, administración), por lo que es más barato decidirla ahora que migrar después.

Se evaluaron:

- **Angular Material (+ CDK)**: lo mantiene el equipo de Angular y sus versiones van sincronizadas con el core; la
  accesibilidad viene incluida y el tema se define con tokens Material 3. Tiene menos componentes «de negocio» (por
  ejemplo, tablas con filtrado y ordenación avanzados).
- **PrimeNG**: catálogo más amplio de componentes ricos (DataTable, árbol, gráficos), pero con tema y ciclo de
  versiones propios, y una dependencia adicional fuera del ecosistema oficial.

## Decisión

Se usa **Angular Material** (con `@angular/cdk`) como única biblioteca de componentes de `frontend/`, en la misma
línea de versión que `@angular/core`. No se instala PrimeNG.

## Consecuencias

- Las pantallas de F0 (layout, chat) se construyen con componentes Material y un tema propio en `src/styles.scss`.
- Si la consola de agentes o el panel de administración (F3/F4) necesitan componentes avanzados que Material no
  ofrece, se evalúa en un ADR nuevo que sustituya a este; hasta entonces no se mezclan dos bibliotecas.
- Las actualizaciones de Angular y de Material deben hacerse juntas (`ng update`) para mantener las versiones
  alineadas.
