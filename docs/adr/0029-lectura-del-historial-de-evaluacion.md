# ADR-0029: Lectura del historial de evaluación

- Estado: aceptado
- Fecha: 2026-11-23
- Complementa a: ADR-0013 (Angular Material como única biblioteca de componentes), ADR-0015 (un esquema por módulo y un dueño
  por dato) y ADR-0028 (persistencia de las ejecuciones de evaluación); no los sustituye

## Contexto

LAB-31 guarda cada ejecución de evaluación en el esquema `eval` y dejó la lectura para el frontend fuera de su alcance (ADR-0028,
decisión 8). LAB-32 la define: una pantalla `admin-eval` que muestra cómo evolucionan las métricas de una ejecución a la
siguiente, qué casos fallan en una ejecución y qué cambió entre dos.

- El módulo `eval` es el dueño del esquema `eval` (ADR-0015): el navegador no accede a la base y ningún otro módulo la lee.
- El token de guardado (`X-Eval-Token`) protege lo que **escribe**; llevarlo al navegador filtraría un secreto de escritura a
  todos los que abran la pantalla.
- El historial no contiene el texto de las respuestas ni de los fragmentos (viven en las trazas de Langfuse, ADR-0022).
- Las consultas de `eval/queries/` son la forma documentada de leer el historial a mano; la pantalla no puede contradecirlas.
- ADR-0013 mantiene Angular Material como única biblioteca de componentes y exige un ADR para mezclar otra. Material no trae
  gráficas.
- Un valor que no existe por definición (por ejemplo, la precisión del contexto de un caso sin documentos) no es un cero: dibujarlo
  como cero haría pasar un hueco por un fallo.

## Decisión

1. **Lectura abierta y de solo lectura en el módulo `eval`**: tres endpoints bajo `/api/eval/history` (`/runs`, `/runs/{runId}`
   y `/compare`), sin token. El prefijo separa sin ambigüedad el camino que escribe (`/api/eval/runs`, con token) del que lee.
   No hay migración ni tabla nueva; solo `SELECT`.
2. **Las medias se calculan en SQL** sobre `eval_result.scores`: `avg` de los valores numéricos ignorando los vacíos, y `n` es
   cuántos son. Una métrica sin ningún valor devuelve `mean = null, n = 0` (un hueco). Es la misma definición que
   `eval/queries/03-run-summary.sql`; el cliente no recalcula nada.
3. **La comparación se calcula en el backend** con `BigDecimal` y la regla de `eval/queries/07-regressions.sql` (`base − target`
   estrictamente mayor que la tolerancia). Así el caso límite 0,80 → 0,75 con tolerancia 0,05 no cuenta como empeoramiento, y lo
   comprueba una prueba contra PostgreSQL real con las mismas consultas SQL. Dos ejecuciones de tipos distintos no se comparan
   (422 `SLHD_EVAL_00006`).
4. **La gráfica es un componente SVG propio, sin biblioteca**: una polilínea por serie que se interrumpe en cada hueco,
   marcadores distintos además del color, puntos enfocables con teclado y una tabla equivalente. Sin dependencias nuevas.
5. **El criterio de «caso que falla» vive en el cliente** (umbral o k que elige quien mira): la ejecución llega entera y filtrar es
   una función pura, así que mover el umbral no hace peticiones.
6. **El enlace a la traza de Langfuse lo construye el backend** con una plantilla configurable
   (`shoplab.eval.history.trace-url-template`); sin plantilla, la pantalla muestra el identificador para copiarlo.

## Consecuencias

- **Riesgo aceptado**: la lectura del historial queda abierta, sin autenticación, igual que ADR-0024 aceptó para otra lectura.
  Lo que se expone son métricas, parámetros y el texto del dataset golden (sintético); nunca respuestas del asistente ni
  fragmentos, y una prueba cierra la lista de campos de cada respuesta. Cuando el backend tenga autenticación, estos endpoints se
  protegen con ella y no con el token de guardado.
- La pantalla y las consultas SQL no pueden divergir sin que falle una prueba de equivalencia.
- La gráfica propia es suficiente para una línea por métrica con hasta 5 series y 500 puntos. **Si la pantalla crece** (varias
  vistas de gráficas, zoom, selección por área), se reevalúa una biblioteca en un ADR que sustituya a este apartado.
- Un campo nuevo en una respuesta de lectura obliga a actualizar la lista cerrada de la prueba de contenido; es a propósito.
- Recomputar las medias en cada lectura cuesta una consulta agregada por petición (≈ 10 000 filas para 100 ejecuciones), dentro
  del objetivo de 3 s; si el historial creciera mucho, guardar el resumen sería una decisión nueva (ADR-0028 la descartó).
- No cambia el asistente, las métricas ni el guardado de LAB-31.
