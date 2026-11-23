# Contrato: la pantalla `admin-eval`

Qué ve y qué puede hacer el administrador. Describe comportamiento observable, no la estructura de los componentes (esa está en
[plan.md](../plan.md)). El texto de la pantalla es español.

## Ruta y navegación

- Ruta: `/admin-eval` (carga perezosa), título de la página «Evaluación».
- Entrada en la navegación lateral, junto a «Chat» y «Documentos», con el icono `monitoring` y la etiqueta «Evaluación»
  (FR-001). En teléfono se abre y se cierra con el mismo menú que las demás.
- Parámetros de la dirección: `?tipo=QUALITY|RETRIEVAL` y `?ejecucion=<run_id>`. Un valor inválido se ignora (se usa el
  predeterminado), nunca rompe la pantalla.

## Estructura de la página (de arriba abajo)

1. **Cabecera**: título, una frase de introducción, botón «Actualizar» y un **aviso fijo**: «El dataset golden es un borrador
   hasta su revisión humana; estos valores no son una certificación de calidad.» (edge case del spec).
2. **Controles**: selector de **tipo de medición** (Calidad de respuesta / Recuperación), selector múltiple de **métricas**
   (1–5), filtro de **origen** con el recuento y rango («Mostrando 12 de 12 · 3 ocultas por el filtro de origen»; «Ver más» si
   `total > shown`).
3. **Gráfica de evolución**: un punto por ejecución, orden cronológico. Debajo, la **tabla equivalente** (mismas filas y
   valores), en un contenedor desplazable con nombre accesible.
4. **Lista de ejecuciones** (la misma tabla, con acciones): elegir una, marcarla como **referencia** y **comparar con…**.
5. **Detalle de la ejecución elegida**: parámetros, resumen (conjunto completo y por categoría) y **casos que fallan**.
6. **Comparación** (cuando hay referencia y objetivo): parámetros que difieren, diferencia de métricas y casos que empeoraron,
   mejoraron o no son comparables.

## Gráfica

- Eje X: ejecuciones por fecha de inicio (etiqueta corta; el valor completo está en el punto). Eje Y: 0 a 1 (rejilla cada 0,25).
- **Una serie por métrica elegida**, cada una con color **y** marcador distintos (círculo, cuadrado, rombo, triángulo, cruz).
  Leyenda con los mismos símbolos.
- Un valor sin valor (`mean = null` o métrica ausente) **interrumpe** la línea y se marca en el eje con un símbolo de hueco;
  jamás se dibuja en 0 (FR-005). Un punto aislado se dibuja como marcador sin línea (escenario 7).
- Con una sola ejecución: un punto y el texto «Hace falta más de una ejecución para ver una evolución». Sin ejecuciones: el
  estado vacío.
- Cada punto es enfocable (`tab`), con las flechas ← → entre puntos de la serie y `Enter` para **elegir esa ejecución**; el foco
  y el ratón muestran un panel con: id de la ejecución, fecha, valor exacto (4 decimales) o «sin valor», y «calculado con N
  casos». En teléfono, tocar el punto hace lo mismo.
- `role="img"` con título («Evolución de fidelidad») y una descripción resumida («12 ejecuciones, de 0,6900 a 0,7800»).
- La **tabla equivalente** lleva una fila por ejecución (id, fecha, origen y una columna por métrica elegida con «sin valor» donde
  corresponda) y es la alternativa textual completa (FR-004).

## Detalle de la ejecución

- **Con qué se midió** (lista de definición): prompts con su versión numérica (y «fijada por petición» o «producción»);
  modelo del asistente (o «desconocido»); embeddings; juez y sus ajustes y biblioteca (solo calidad); recuperación (top-k,
  umbral, presupuesto de contexto y, en recuperación, profundidad y k); origen; commit (o «desconocido»); huella del dataset
  abreviada; casos medidos / excluidos / sin puntuar por métrica.
- **Resumen**: tabla con una fila por ámbito (`Conjunto completo` y cada categoría) y una columna por métrica con media y
  `n`; «sin valor» donde `mean` es nulo.
- **Casos que fallan**:
  - Calidad: un control de **umbral** (0–1, paso 0,05, inicial 0,5). Recuperación: un selector de **k**.
  - Texto de estado: «F casos fallan de M medidos» (o «Ningún caso falla con este criterio», FR-012, escenario 7).
  - Filtros por categoría y por métrica; orden por peor valor (por defecto), por `case_id` o por categoría.
  - Una fila por caso: `case_id`, categoría, pregunta (cortada con acceso al texto completo), el valor de cada métrica con las
    que están por debajo del umbral destacadas con **icono y texto** («por debajo del umbral») además del color, y «sin valor»
    donde sea nulo.
  - Al abrir una fila: pregunta, respuesta de referencia, documentos **esperados** frente a **recuperados** (en orden, con
    marca de acierto/fallo), si el asistente se abstuvo y el enlace «Ver traza en Langfuse» (se abre en pestaña nueva con
    `rel="noopener noreferrer"`) o, sin `trace_url`, el `trace_id` con un botón «Copiar».
  - **Nunca** se muestra el texto de la respuesta del asistente ni el de los fragmentos; junto a la traza se explica que ese
    contenido vive en Langfuse.

## Comparación

- Se activa desde la lista: «Usar como referencia» en una ejecución y «Comparar con la referencia» en otra. Por defecto, la
  referencia es la **anterior del mismo tipo** respecto de la elegida. Una de otro tipo tiene la acción deshabilitada con el
  texto «Sus métricas no son las mismas» (escenario 5).
- Controles: métrica de clasificación (por defecto `faithfulness` o `reciprocal_rank`) y tolerancia (`drop`, inicial 0,05).
- Muestra: **advertencia** destacada si hay `warnings` («Puede no reflejar un cambio de calidad: difiere el juez, el modelo
  del asistente…» con los campos), tabla de **parámetros que difieren** (valor de cada lado), diferencia de cada **métrica**
  del conjunto completo, y tres listas: **empeoraron**, **mejoraron** y **no comparables** (con el motivo: «el caso cambió» /
  «solo está en una ejecución»). `NO_VALUE` y `SAME` se resumen con su número, sin lista.

## Estados (FR-021)

| Estado | Qué se ve | Rol / aria |
|---|---|---|
| Cargando por primera vez | «Cargando evaluaciones…» | `role="status"`, `aria-busy` |
| Recargando | Lo ya mostrado se conserva; el botón «Actualizar» se deshabilita y muestra el progreso | `aria-busy` en la sección |
| Sin ejecuciones | «Todavía no hay ejecuciones guardadas» + cómo generarlas (`eval/`: `quality --save`, con el token de guardado, enlace a la documentación) | `role="status"` |
| Sin ejecuciones tras filtrar | «Ninguna ejecución con este origen» + botón «Quitar filtro» | `role="status"` |
| Con datos | La pantalla completa | — |
| Error de la lista | Aviso «No se pudo cargar la evaluación» con «Reintentar»; no vacía lo ya mostrado | `role="alert"` |
| Ejecución inexistente (`404`) | «La ejecución no existe (¿enlace antiguo?)» y se quita de la dirección | `role="alert"` |
| Error del detalle o de la comparación | El aviso queda en su sección; la gráfica y la lista siguen utilizables | `role="alert"` |

Los errores del servidor no muestran el `detail` ni códigos internos: el mensaje es el de la pantalla (por `code`), como en
`documents`.

## Accesibilidad y adaptación (FR-022, SC-009)

- Todo se maneja con teclado; el orden de tabulación sigue el visual; el foco visible usa el estilo del tema.
- Cada control tiene etiqueta; los estados se dicen con texto además del color; los iconos decorativos van `aria-hidden`.
- Contraste ≥ 4,5:1 con los tokens del tema; objetivos táctiles ≥ 44 × 44 px.
- A ≤ 600 px: controles apilados, gráfica con la altura reducida, tablas dentro de contenedores desplazables con nombre
  accesible y **sin scroll horizontal de la página**; el detalle de un caso se abre en una sección debajo de la fila.
- Respeta `prefers-reduced-motion` (sin animaciones en la gráfica ni en los paneles).

## Lo que la pantalla no hace

No lanza mediciones, no edita casos, prompts ni dataset, no borra ni archiva ejecuciones, no exporta datos, no muestra texto de
respuestas ni de fragmentos y no usa ni conoce el token de guardado (FR-024).
