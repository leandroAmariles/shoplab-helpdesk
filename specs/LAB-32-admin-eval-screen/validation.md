# Validación de LAB-32 (admin-eval)

ADR: 0029

**Cómo leer este archivo.** «Resultado» es el de la **validación manual** de `quickstart.md` en un navegador. Ninguna se ha
recorrido todavía: se marcan **pendiente** con el motivo, nunca como pasadas. En «Evidencia» solo se anota lo que sí se ejecutó:
las pruebas automáticas que cubren lo mismo (no sustituyen al recorrido manual) y su resultado.

## Línea base (T002)

- Frontend, antes de integrar el detalle en la pantalla: 535 pruebas pasaban y 8 fallaban (`admin-eval.detail.spec.ts`, la
  tarea T044 sin hacer). No era un fallo preexistente de la rama base.
- Backend, primera ejecución de CI de la rama: `ChatSmallTalkMetricsIntegrationTests` falló una vez (módulo `chat`, que esta
  historia no toca) y **pasó al repetir el mismo trabajo**: intermitente, no se corrige aquí. Además falló Checkstyle por una línea de
  122 caracteres en una prueba de esta historia (corregida).

## Escenarios de quickstart.md

### V1 — Abrir /admin-eval desde la navegación

Resultado: pendiente (hace falta el frontend en un navegador)

Evidencia: `app.routes.spec.ts` y la spec del `shell` comprueban que la ruta existe, es perezosa y aparece en la navegación.

### V2 — Gráfica con 3 ejecuciones de calidad = 03-run-summary

Resultado: pendiente (hace falta el navegador y las ejecuciones de `seed/`)

Evidencia: `EvalHistoryEquivalenceTests` comprueba contra PostgreSQL real que las medias y el `n` de cada métrica son los de
`eval/queries/03-run-summary.sql` (verde).

### V3 — Punto con teclado, flechas y Enter

Resultado: pendiente (navegador)

Evidencia: `metric-chart.spec.ts` (`keyboard`): flechas dentro de la misma serie, `Enter`, `Espacio`, `Esc` y toque (verde).

### V4 — Varias métricas de calidad a la vez

Resultado: pendiente (navegador)

Evidencia: `admin-eval.spec.ts` («adds a metric to the chart») y `metric-chart.spec.ts` (marcador y trazo por serie) (verde).

### V5 — Métrica sin valor por definición = hueco

Resultado: pendiente (navegador, con `seed-q3`)

Evidencia: `metric-chart.spec.ts` («never draws a line through a gap…») y `JdbcEvalHistoryAdapterListTests` (media nula y `n = 0`,
no cero) (verdes). La carga `seed-q3` se aceptó y su detalle devuelve `context_precision` con `mean: null, n: 0`.

### V6 — Cambiar a recuperación

Resultado: pendiente (navegador)

Evidencia: `admin-eval.spec.ts` («changes to retrieval…») (verde).

### V7 — Filtro de origen

Resultado: pendiente (navegador)

Evidencia: `admin-eval.spec.ts` («filters by origin and says how many runs the filter hides») (verde).

### V8 — Una sola ejecución

Resultado: pendiente (navegador)

Evidencia: `metric-chart.spec.ts` («asks for more than one run when there is only one») (verde).

### V9 — Parámetros de una ejecución

Resultado: pendiente (navegador)

Evidencia: `run-detail.spec.ts` y `JdbcEvalHistoryAdapterFindTests` (verdes).

### V10 — Resumen por categoría = 03-run-summary

Resultado: pendiente (navegador)

Evidencia: `JdbcEvalHistoryAdapterFindTests` (el resumen trae el conjunto completo y cada categoría con sus medias y `n`) y
`EvalHistoryEquivalenceTests` (el conjunto completo coincide con `03-run-summary.sql`) (verdes). La equivalencia con la consulta
**por categoría** no tiene prueba automática: es lo que queda por contrastar a mano.

### V11 — Casos que fallan vs CSV

Resultado: pendiente (navegador y el CSV de una ejecución real)

Evidencia: `case-failures.spec.ts` y `failing-cases.spec.ts` (verdes). Los CSV reales no se han contrastado.

### V12 — Mover el umbral sin peticiones

Resultado: pendiente (navegador, pestaña Red)

Evidencia: `eval-store.run.spec.ts` y `failing-cases.spec.ts` («mover el umbral actualiza la lista sin peticiones») (verdes).

### V13 — Recuperación: k elegido

Resultado: pendiente (navegador)

Evidencia: `case-failures.spec.ts` y `failing-cases.spec.ts` (selector de k) (verdes).

### V14 — Abrir caso y traza de Langfuse

Resultado: pendiente (hace falta Langfuse en el perfil `llmops`)

Evidencia: `failing-cases.spec.ts` (enlace con `rel="noopener noreferrer"`, o identificador con «Copiar», sin texto de
respuesta) y `EvalHistoryNoTextTests` (`trace_url` solo con plantilla) (verdes). No se ha abierto una traza real.

### V15 — Comparar con cambio de prompt = 07-regressions

Resultado: pendiente (navegador)

Evidencia: `EvalHistoryEquivalenceTests`: la lista de casos que empeoraron es idéntica a las filas de `07-regressions.sql` con
tolerancia 0,05, 0,5 y 0, para tres métricas (verde). Con `seed-q1` y `seed-q2` la API devuelve 200, nombra
`prompts.chat-system.version` 3 → 4 y marca un caso no comparable.

### V16 — Caso editado no comparable y advertencias

Resultado: pendiente (navegador)

Evidencia: `RunComparatorTests` (revisión distinta → no comparable; advertencias solo por los campos que cambian lo que se mide)
y `run-comparison.spec.ts` (verdes). Las cargas de `seed/` solo cambian el prompt, así que la advertencia con datos reales no se
ha visto.

### V17 — Tipos distintos no comparables

Resultado: pendiente (navegador)

Evidencia: `run-list.spec.ts` (acción deshabilitada con «Sus métricas no son las mismas») y `CompareEvalRunsUseCaseTests`
(verdes). Con `seed-q1` y `seed-r1` la API devuelve 422 `SLHD_EVAL_00006`.

### V18 — Sin ejecuciones

Resultado: pendiente (navegador)

Evidencia: `admin-eval.spec.ts` (`states`: estado vacío con cómo generarlas, y vacío por filtro) (verde).

### V19 — Backend caído y reintento

Resultado: pendiente (navegador, parando el backend)

Evidencia: `admin-eval.spec.ts` (`states`: error de la lista con «Reintentar», sin vaciar lo mostrado y sin el detalle del
servidor) (verde).

### V20 — Ejecución inexistente en la dirección

Resultado: pendiente (navegador)

Evidencia: `admin-eval.detail.spec.ts` («says that the run does not exist and cleans the address…») (verde).

### V21 — Solo teclado, lector de pantalla y 360 px

Resultado: pendiente (navegador a 360 px y lector de pantalla: no se ha probado ninguno)

Evidencia: `a11y.spec.ts` (un solo `h1`, controles con nombre, iconos decorativos ocultos, tablas en regiones con nombre y
`tabindex`, identificadores únicos) (verde). Las reglas de adaptación (`@media (max-width: 600px)`) y de
`prefers-reduced-motion` están en los `.scss`, pero **no hay prueba automática** de su efecto ni de los contrastes.

### V22 — Ningún texto de respuesta en la API

Resultado: pendiente (la comprobación manual con una ejecución real)

Evidencia: `EvalHistoryNoTextTests` recorre los tres endpoints con una ejecución guardada de verdad y falla si aparece un campo
fuera de la lista cerrada del contrato (verde).

### V23 — 100 ejecuciones: tiempos

Resultado: pendiente (con 100 ejecuciones reales)

Evidencia: `JdbcEvalHistoryAdapterListTests` incluye un humo con 100 ejecuciones sintéticas (< 3 s) (verde). Con las cinco
cargas de `seed/` la lista de calidad respondió en 46 ms (una medición, no un benchmark).

### V24 — Clúster de desarrollo con el primer tag

Resultado: pendiente (requiere el primer tag `vX.Y.Z` con esta historia desplegado en el clúster de desarrollo)

Evidencia:
