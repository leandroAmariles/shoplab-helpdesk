# Research: Pantalla de evolución de métricas (admin-eval) — LAB-32

Las decisiones de arquitectura de esta historia se registran además como **ADR-0029** (el siguiente número libre tras
ADR-0028; 0026 no está en `main`, se comprueba al implementar). Este documento recoge lo que se investigó y lo que se descartó.

**Fuera de este plan**: LAB-30 (evaluación en CI, línea base, umbrales de bloqueo), LAB-33 (feedback 👍/👎) y cualquier control
de acceso (no hay autenticación en el proyecto, ADR-0008).

## R1 · Cómo lee la pantalla el historial

**Decisión**: tres endpoints de **solo lectura** en el módulo `eval`, bajo `/api/eval/history`, **sin token**:

- `GET /api/eval/history/runs?kind=…&origin=…&limit=…` → las ejecuciones de un tipo con sus medias (para la gráfica y la lista).
- `GET /api/eval/history/runs/{runId}` → una ejecución con sus parámetros, su resumen y todos sus resultados con el contenido
  del caso tal como se midió.
- `GET /api/eval/history/compare?base=…&target=…&metric=…&drop=…` → la comparación de dos ejecuciones del mismo tipo.

**Por qué**:
- LAB-31 dejó expresamente la lectura para el frontend en esta historia (spec de LAB-31, *Fuera de alcance*; ADR-0028,
  decisión 8). El módulo ya es el dueño del esquema (ADR-0015): el navegador **no** accede a la base y ningún otro módulo lee
  `eval`.
- Un prefijo propio (`/history`) separa sin ambigüedad el camino de escritura (`/api/eval/runs`, con token) del de lectura
  (sin token). Así ninguna regla de acceso futura tiene que distinguirlos por el verbo.
- Tres endpoints cubren las tres vistas de la pantalla (gráfica y lista, detalle, comparación) con una petición cada una. El
  detalle pesa ≈ 100 KB (≈ 100 resultados), lo mismo que la carga de LAB-31; no hace falta paginar los resultados.

**Alternativas descartadas**:
- *Reutilizar `GET /api/eval/runs/{runId}` de LAB-31*: está protegido por token (sirve a la herramienta) y devuelve solo la
  existencia; ensancharlo mezclaría el camino con token y el abierto.
- *GraphQL o un endpoint genérico de consultas*: sobredimensionado para tres vistas fijas (Constitución VII) y abriría la
  base a consultas arbitrarias.
- *Endpoints por métrica o por categoría*: más peticiones por pantalla y el resumen dejaría de salir de la misma consulta.
- *Que el navegador reciba el token de guardado*: filtra un secreto de escritura a todos los que abran la pantalla.

## R2 · Dónde se calculan las medias

**Decisión**: **en SQL**, agregando `eval_result.scores` con `jsonb_each` por ejecución (y por categoría para el detalle), de
modo que la media es `avg` de los valores numéricos ignorando los `null`, y `n` es su número. Una métrica sin ningún valor
numérico devuelve `mean = null, n = 0` (un **hueco**, no un cero).

**Por qué**: es exactamente la definición de `eval/queries/03-run-summary.sql` y del CSV (FR-007, SC-003), con una sola
implementación para el listado, la gráfica y el resumen; el cliente no recalcula nada que pueda divergir. Las medias se
devuelven como números sin redondear; la pantalla las muestra con cuatro decimales (la precisión de los CSV).

**Alternativas descartadas**:
- *Calcular las medias en Java al leer todos los resultados*: duplica la regla del vacío y obliga a traer ≈ 10 000 filas para
  una lista de 100 ejecuciones.
- *Calcular en el navegador*: igual, y lo peor: la gráfica dependería de bajar los resultados de todas las ejecuciones.
- *Guardar el resumen en `eval_run`*: ADR-0028 (decisión 4) lo descartó a propósito («el resumen no se guarda: se recalcula
  desde los resultados»); esta historia no lo reabre.

## R3 · Forma de la lista y del límite de ejecuciones

**Decisión**: la lista devuelve las `limit` ejecuciones **más recientes** del tipo pedido (`limit` por defecto 100, máximo 500),
**en orden cronológico ascendente** (desempate por `run_id`), con `total` (las que cumplen los filtros), `total_all_origins`
(las del tipo sin filtro de origen) y la lista de `origins` con su recuento. La pantalla dice «mostrando N de M» y ofrece
ampliar el rango, nunca trunca en silencio (FR-023).

**Por qué**: el tipo es obligatorio porque las métricas de los dos tipos no se mezclan (FR-003), así que una ejecución de cada
tipo nunca comparte serie. Las facetas de origen se calculan sobre el tipo, sin el filtro de origen, para poder decir cuántas
ejecuciones oculta el filtro (FR-006). El límite protege contra el crecimiento sin retención de LAB-31.

**Alternativas descartadas**: *paginación por página/cursor* (la gráfica necesita el rango contiguo, no páginas); *sin límite*
(el historial solo crece, ADR-0028); *límite fijo sin parámetro* (impide «ampliar el rango»).

## R4 · La gráfica: componente SVG propio, sin biblioteca

**Decisión**: un componente `metric-chart` que dibuja un **SVG** en línea: ejes con rejilla, una polilínea por serie que se
**interrumpe** en cada hueco, marcadores distintos por serie (círculo, cuadrado, rombo, triángulo, cruz) además del color, y
puntos enfocables con teclado. Debajo, una **tabla equivalente** con los mismos valores. Sin dependencias nuevas.

**Por qué**:
- ADR-0013 mantiene Angular Material como **única** biblioteca de componentes y dice que mezclar otra exige un ADR nuevo.
  Material no incluye gráficas.
- La pantalla necesita poco: una línea por métrica con ≤ 5 series y ≤ 500 puntos. Eso son unas 200 líneas de TypeScript puro
  y dos funciones puras (`chart-scale`, `metric-series`) fáciles de probar sin DOM (FR-025).
- Lo que más importa de esta gráfica es la **honestidad del vacío**: un valor sin valor jamás se debe dibujar como cero
  (FR-005, SC-007). Con SVG propio el hueco es una decisión explícita del código y de sus pruebas, no una opción de una
  biblioteca.
- La accesibilidad (FR-004, FR-022) se resuelve con la estructura del SVG (`role="img"` con título y descripción, puntos con
  `tabindex` y `aria-label`) y la tabla equivalente.

**Alternativas descartadas**:
- *Chart.js / `ng2-charts`*: canvas, sin árbol accesible; los huecos exigen `spanGaps: false` y datos `null` correctos; ≈ 200 KB
  de dependencia nueva que además habría que auditar.
- *ngx-charts / ECharts*: SVG o canvas con mucho más de lo necesario y temas propios que chocan con los tokens de Material 3.
- *PrimeNG* (que ADR-0013 ya descartó): mezclar dos bibliotecas.
- *Solo la tabla*: no cumple «gráfica por ejecución» (criterio de Jira).

**Límite asumido**: si la pantalla crece (varias vistas de gráficas, zoom, brushing), se vuelve a evaluar una biblioteca en un
ADR que sustituya a este apartado.

**Colores y marcadores**: la paleta sale de los tokens del tema (no hardcoded) y cada serie lleva además un marcador y un
trazo distintos; el estado nunca se comunica solo con el color (FR-022). Por defecto se dibujan 1–3 series: en calidad,
`faithfulness`; en recuperación, `reciprocal_rank` y `hit_at_<mayor k>`.

## R5 · Series con huecos y métricas que no están en todas las ejecuciones

**Decisión**: la serie de una métrica es un arreglo alineado con las ejecuciones mostradas (`mean | null` por ejecución). Un
`null` (media sin ningún caso, o métrica que esa ejecución no tiene) **rompe la línea**: no se dibuja ni se conecta como cero.
Un punto aislado entre dos huecos se dibuja solo como marcador. La métrica de recuperación depende de los k (`hit_at_<k>`):
la lista de métricas disponibles es la **unión** de las de las ejecuciones mostradas, y la que una ejecución no tiene queda
como hueco (LAB-31: añadir una métrica no reescribe lo anterior).

**Por qué**: es el contrato de vacío de LAB-29/LAB-31 («vacío ≠ cero») llevado a la gráfica (FR-005), y evita que un cambio de
los k pedidos entre ejecuciones deforme la serie.

## R6 · Enlace a la traza de Langfuse

**Decisión**: el backend añade `trace_url` a cada resultado **si** hay una plantilla configurada
(`shoplab.eval.history.trace-url-template`, con el marcador `{traceId}`; por defecto `http://localhost:3001/trace/{traceId}`,
la interfaz del perfil `llmops`). Sin plantilla, o en una ejecución de recuperación (sin traza), no hay `trace_url` y la pantalla
muestra el identificador para copiarlo (FR-015). La plantilla se valida al arrancar: debe ser `http(s)` y contener
`{traceId}` una sola vez; si no, el arranque falla con un mensaje claro.

**Por qué**: la dirección de Langfuse depende del entorno y el navegador es quien la abre, así que no puede deducirse de
`chat.tracing.endpoint` (que es la de **ingesta** y está en la red del backend). Una plantilla explícita evita adivinar la
estructura de rutas de Langfuse (que cambia con el proyecto) y nunca incluye claves. El identificador de traza ya está
validado por LAB-31 (`^[A-Za-z0-9_-]{1,64}$`), así que la sustitución no puede inyectar caracteres de ruta.

**Alternativas descartadas**: *construir el enlace en el navegador* (necesita la dirección en el frontend, otro punto de
configuración); *reutilizar el `endpoint` de tracing* (apunta a la API pública, no a la interfaz); *dejar solo el id* (cumple
la letra de FR-015 pero obliga a copiar y pegar en cada revisión).

## R7 · Acceso: lectura abierta, escritura con token

**Decisión**: la lectura **no** exige token y no lo conoce. El navegador nunca recibe `shoplab.eval.persistence.token`.

**Por qué**: el proyecto no tiene autenticación (ADR-0008; igual que LAB-25 con los documentos) y la lectura solo expone lo que
ADR-0028 ya decidió guardar: puntuaciones, identificadores de documentos del corpus sintético, el contenido del dataset golden
(sintético) y parámetros del asistente. Se verificó que **no** hay texto de respuestas, de fragmentos, claves, tokens ni
direcciones internas en ninguna columna (ADR-0028 decisión 7; `EvalNoTextStoredTests`). Esta historia añade una prueba que
fija la lista de campos de las respuestas (SC-008). El control de acceso se aborda en la fase de seguridad y no se simula aquí.

**Riesgo aceptado**: cualquiera con acceso a la red del entorno de desarrollo ve el historial y el dataset golden. El dataset
es un borrador sintético y la ejecución de CI no existe todavía; si LAB-30 o la fase de seguridad cambian eso, la lectura se
protege con el mecanismo que se adopte allí.

## R8 · Criterio de «caso que falla» y dónde se calcula

**Decisión** (aclaración resuelta con la opción A): en **calidad**, un caso falla si **alguna** de sus métricas **con valor**
queda por debajo de un umbral que elige quien mira (control numérico/deslizador 0–1, paso 0,05, valor inicial 0,5); en
**recuperación**, falla si `hit_at_<k>` vale 0 para el **k elegido** (por defecto, el mayor k de la ejecución). Un valor vacío
no cuenta como fallo; tampoco un caso sin documentos esperados en recuperación. Se calcula **en el cliente**, con una función
pura sobre los resultados ya cargados.

**Por qué**: el detalle ya trae todos los resultados (R1), así que mover el umbral es instantáneo y no genera peticiones
(FR-010: «cambiarlo actualiza la lista sin recargar»). Una función pura es trivial de probar sin red (FR-025). El umbral es
informativo: no bloquea nada (los umbrales de bloqueo son de LAB-30).

**Alternativas descartadas**: *umbral fijo del proyecto* (decisión de calidad que choca con LAB-30); *solo empeoramientos*
(no mostraría un caso que siempre falló, que es el caso más interesante); *calcular en el backend* (una petición por cada
cambio del control).

## R9 · Comparación: en el backend, con la regla de `07-regressions.sql`

**Decisión**: `RunComparator` (dominio puro, sin Spring) compara los resultados de dos ejecuciones del mismo tipo para **una
métrica** elegida, con `drop` (por defecto 0,05, entre 0 y 1), y devuelve por caso un estado:

| Estado | Condición |
|---|---|
| `WORSE` | misma revisión, ambos valores, `base − target > drop` (estricto) |
| `BETTER` | misma revisión, ambos valores, `target − base > drop` (estricto) |
| `SAME` | misma revisión, ambos valores, diferencia dentro de `drop` |
| `NO_VALUE` | misma revisión y al menos uno de los dos valores es nulo (no se afirma nada) |
| `NOT_COMPARABLE` | el `case_id` está en las dos pero la revisión (el `content_hash`) difiere |
| `ONLY_BASE` / `ONLY_TARGET` | el `case_id` solo está en una de las dos |

La aritmética es con **`BigDecimal`** (los `numeric` de PostgreSQL), y la comparación es estricta como en
`07-regressions.sql` (`… > :'drop'`). La respuesta incluye además las **medias de cada métrica** de las dos ejecuciones con su
diferencia, tomadas de la misma consulta de R2, la **lista de parámetros que difieren** y la lista de **advertencias**
(juez, modelo del asistente, embeddings, configuración de recuperación o dataset distintos: FR-018; el prompt, el commit y el
origen se listan como diferencias informativas, porque cambiarlos es justamente lo que se quiere medir).

**Por qué en el backend**: así FR-019 se cumple por construcción y se **prueba** contra las consultas SQL del repositorio con
las mismas fixtures; en `double` el caso límite (0,80 − 0,75 = 0,05000000000000004 > 0,05) diría «empeoró» donde SQL con
`numeric` dice «no». Los resultados de la misma ejecución se leen una vez y el cálculo es lineal (≈ 100 casos).

**Alternativas descartadas**: *comparar en el cliente* (replica la regla en TypeScript con el problema de precisión y habría
que mantener las dos); *una consulta SQL única con `FULL JOIN`* (más corta, pero la lógica de estados, parámetros y
advertencias queda en SQL difícil de probar de forma aislada, FR-025); *comparar más de dos ejecuciones* (fuera de alcance).

**Tipos distintos**: devuelve **422 `SLHD_EVAL_00006`** («las ejecuciones no son del mismo tipo»); la pantalla ni siquiera
ofrece la comparación en ese caso (FR-017), pero el backend no depende de que el cliente se porte bien.

## R10 · Estado de la pantalla y de la dirección

**Decisión**: el estado vive en un **store de signals** provisto por la pantalla (patrón de `DocumentsStore`: sus temporizadores
y peticiones mueren con ella). La ejecución abierta y el tipo se reflejan en **parámetros de la dirección**
(`/admin-eval?tipo=QUALITY&ejecucion=<runId>`), de modo que un enlace lleva a la misma vista y el botón «atrás» funciona.
Las métricas elegidas, el umbral y el filtro de origen son estado local de la sesión (no van en la dirección).

**Por qué**: reabrir «la ejecución que falló ayer» con un enlace es el uso real de la pantalla; el resto de controles son
preferencias. Mantiene la dirección corta y sin datos sensibles (solo un identificador de ejecución, `^[A-Za-z0-9._-]{1,32}$`).

**Alternativas descartadas**: *todo en la dirección* (muchos parámetros y estado que se pierde igual al recargar si cambia el
modelo); *`localStorage`* (no se comparte, y fuente de estados difíciles de depurar).

## R11 · Actualización y estados

**Decisión**: **sin sondeo**. La pantalla lee al abrir y al pulsar «Actualizar»; mantiene lo ya mostrado mientras recarga y
muestra el error con «Reintentar» sin vaciar la vista. Cambios de tipo, origen y rango disparan una lectura de la lista; elegir
una ejecución dispara la del detalle (se cachea en el store por `runId`: una ejecución guardada no cambia, ADR-0028).

**Por qué**: una ejecución se guarda de golpe y no se edita; el historial cambia a lo sumo unas veces al día. El spec lo pide
así («la nueva aparece al actualizar, nunca por sorpresa»). Cachear el detalle por identificador es seguro porque una
ejecución es inmutable.

## R12 · Rendimiento

El listado hace **una** consulta agregada para las ejecuciones elegidas (`WHERE id = ANY(?)`) sobre `eval_result` (≈ 10 000
filas con 100 ejecuciones) con `jsonb_each`; el índice `eval_result_case_ref_idx` y la clave `UNIQUE (run_ref, case_ref)` de
LAB-31 cubren el acceso por ejecución. La lista de ejecuciones usa `eval_run_started_at_idx`. Está muy por debajo de SC-006; se
mide en el adaptador con 100 ejecuciones sintéticas (test de humo, no de rendimiento estricto).

No se añade ningún índice ni migración. Si el volumen lo pidiera, será otra historia (sin retención, ADR-0028).

## R13 · Accesibilidad y adaptación a teléfono

**Decisión**: la gráfica es `role="img"` con título y descripción resumida («Fidelidad, 12 ejecuciones, de 0,71 a 0,78») y sus
puntos son **enfocables** (`tabindex`) con `aria-label` que dice ejecución, fecha, valor y casos; las flechas izquierda/derecha
mueven el foco entre puntos de la misma serie. La **tabla equivalente** es la alternativa textual completa (FR-004, SC-009) y se
muestra siempre debajo (en teléfono es la vista principal con la gráfica reducida). Estados con `role="status"`/`role="alert"`,
contraste de los tokens Material 3, objetivos táctiles de ≥ 44 px, sin scroll horizontal de la página (la tabla ancha tiene su
propio contenedor desplazable con nombre accesible).

**Alternativas descartadas**: *gráfica sin teclado* (incumple FR-004); *tooltip solo con hover* (inaccesible en teléfono).

## R14 · Estrategia de pruebas y datos de prueba

- **Backend**: las fixtures de `EvalRunFixtures`/`EvalHistoryQueriesTests` (tres ejecuciones con un caso reescrito y un caso solo
  en una) se **reutilizan** para el adaptador y para la equivalencia con las consultas SQL. El `RunComparator` se prueba con
  tablas de casos límite sin base de datos.
- **Frontend**: funciones puras con tablas de casos; store con un `HttpTestingController`; componentes con Vitest + jsdom. Sin
  navegador real ni red en CI.
- **Datos para verificar a mano** (quickstart): un conjunto sintético y determinista de **cinco ejecuciones** (tres de calidad,
  dos de recuperación; una con un caso editado y una con una métrica vacía) que se carga con el endpoint de guardado de LAB-31.
  Se versiona como cargas JSON en `specs/LAB-32-admin-eval-screen/seed/` y un `requests.http`; no entra en `src/` ni se usa en
  producción. Las ejecuciones reales de `eval/` valen igual, pero la medición de calidad tarda unos 30 minutos.

## R15 · Qué se deja fuera a propósito

- Ordenar por métrica distinta de la elegida y exportar a CSV: no están en el spec.
- Zoom, rango de fechas libre y agrupación por commit o por prompt: la gráfica por ejecución y el rango por «más recientes» bastan
  (si se necesitan, es otra historia).
- Marcar en la gráfica los cambios de prompt/modelo entre ejecuciones consecutivas: tentador, pero ya lo da la comparación
  (historia 3) y exigiría una convención de agrupación. Se documenta como siguiente paso.
- Resaltar visualmente que el dataset es un borrador: se muestra un aviso fijo en la pantalla con el texto de `eval/REVIEW.md`
  (edge case del spec), sin lógica.
