# Pantalla de evaluación (`/admin-eval`)

La pantalla **Evaluación** muestra cómo evolucionan las métricas del asistente de una ejecución de evaluación a la siguiente, qué
casos fallan en una ejecución y qué cambió entre dos. Es **solo lectura**: no lanza mediciones, no edita casos, prompts ni
dataset, no borra ejecuciones y no usa el token de guardado. Las ejecuciones las guarda `eval/` (LAB-31); la decisión de cómo se
leen está en [ADR-0029](adr/0029-lectura-del-historial-de-evaluacion.md).

> **El dataset golden es un borrador hasta su revisión humana.** Estos valores no son una certificación de calidad.

Se abre desde la navegación (**Evaluación**) o en la dirección `/admin-eval`.

## La gráfica

Un punto por ejecución, de la más antigua a la más reciente.

- **Tipo**: *Calidad de respuesta* (fidelidad, relevancia de la respuesta y precisión del contexto) o *Recuperación* (MRR, hit rate
  y recall por k). Cambiar de tipo vacía la gráfica y pide las ejecuciones del otro: nunca se mezclan, porque sus métricas no
  son las mismas.
- **Métricas**: hasta cinco a la vez. Cada una lleva su propio marcador y trazo, además del color.
- **Origen**: filtra por dónde se midió (`local`, `dev`…). La pantalla dice cuántas ejecuciones oculta el filtro.
- **Rango**: las 100 ejecuciones más recientes; *Ver más ejecuciones* sube a 250 y a 500. «Actualizar» vuelve a leer; la pantalla
  nunca lo hace por sí sola.
- **Un punto**: al enfocarlo, pasar el ratón o tocarlo muestra la ejecución, la fecha, el valor exacto y con cuántos casos se
  calculó. Con teclado: `Tab` llega a un punto, `←` y `→` se mueven por los puntos de la misma serie, `Enter` o `Espacio` abren la
  ejecución y `Esc` cierra el panel.
- **La tabla de debajo** tiene las mismas ejecuciones y valores que la gráfica: es su equivalente textual.

### Qué significan los huecos

- Un símbolo **∅** bajo el eje y la línea cortada marcan una ejecución **sin valor** en esa métrica. **No es un cero**: la métrica
  no se pudo calcular por definición (por ejemplo, la precisión del contexto cuando ningún caso tiene documentos esperados) o esa
  ejecución no la tiene.
- Una media se calcula solo con los casos que tienen valor; la pantalla dice cuántos («calculado con 96 casos»).

## El detalle de una ejecución

Elige una con **Ver detalle** en la tabla o activando un punto de la gráfica. La dirección guarda la elección
(`?ejecucion=…`), así que se puede compartir el enlace.

- **Con qué se midió**: prompts y su versión numérica (*fijada por petición* o *producción*), modelo del asistente, embeddings,
  juez y su configuración (solo en calidad), recuperación (top-k, umbral, presupuesto de contexto y, en recuperación, profundidad
  y k), origen, commit, huella del dataset y casos medidos, excluidos y sin puntuar. Lo que no se guardó se escribe
  **«desconocido»**.
- **Resumen**: la media de cada métrica del conjunto completo y de cada categoría, con su `n`.

### Casos que fallan

Lo decide quien mira, y es solo informativo: no bloquea nada.

- En **calidad**, un caso falla si **alguna** de sus métricas con valor queda **por debajo del umbral** (inicial 0,5; un valor igual
  al umbral no falla). Una métrica sin valor no cuenta.
- En **recuperación**, un caso falla si tiene documentos esperados y ninguno aparece entre los **primeros k** recuperados.
- Mover el umbral o el k no hace peticiones. Se puede filtrar por categoría y por métrica y ordenar (peor primero por defecto).
- Al abrir un caso se ven la respuesta de referencia, los documentos **esperados frente a recuperados** (con marca de acierto o
  fallo) y si el asistente se abstuvo. **No se muestra el texto de la respuesta del asistente ni el de los fragmentos**: viven en
  Langfuse. Con la plantilla configurada hay un enlace **Ver traza en Langfuse**; sin ella, el identificador de la traza con un
  botón para copiarlo.

## Comparar dos ejecuciones

Solo entre ejecuciones **del mismo tipo**. En la tabla, **Usar como referencia** en una y **Comparar con la referencia** en
otra; sin referencia elegida, se compara con la **ejecución anterior**. Una ejecución de otro tipo tiene la acción deshabilitada
con el texto «Sus métricas no son las mismas».

- **Advertencia**: si difiere algo que cambia lo que se mide (juez, modelo del asistente, embeddings, recuperación o dataset), la
  comparación avisa «Puede no reflejar un cambio de calidad». Un cambio de prompt, de commit o de origen es lo que normalmente se
  quiere medir y solo se lista como informativo.
- **Parámetros que difieren** y la diferencia de cada **métrica** del conjunto completo (*sin valor* donde una media no existe).
- Tres listas de casos: **empeoraron**, **mejoraron** y **no comparables** (*el caso cambió* o *solo está en una ejecución*).
  Los casos sin cambio o sin valor se resumen con su número.
- **Tolerancia**: un caso empeora o mejora cuando su valor cambia **más** que la tolerancia (inicial 0,05); un cambio exactamente
  igual cuenta como sin cambio. La regla es la de [`eval/queries/07-regressions.sql`](../eval/queries/07-regressions.sql): lo que
  muestra la pantalla y lo que sale con `psql` coincide.

## Sin historial, o con un error

| Lo que ves | Qué pasa y qué hacer |
|---|---|
| **Todavía no hay ejecuciones guardadas** | No se ha guardado ninguna de ese tipo. Mide con `quality --save` (o `retrieval --save`) desde `eval/`, con el token de guardado: [`eval/README.md`](../eval/README.md) |
| **Ninguna ejecución con este origen** | El filtro de origen no deja ninguna. «Quitar filtro» las vuelve a mostrar |
| **No se pudo cargar la evaluación** | El backend o la base no responden. Lo ya mostrado se conserva; «Reintentar» vuelve a leer |
| **La ejecución no existe (¿enlace antiguo?)** | La dirección apuntaba a una ejecución que no está. Se quita de la dirección |
| Aviso en el detalle o en la comparación | El error queda en su sección; la gráfica y la lista siguen utilizables |

La pantalla no enseña el detalle técnico del servidor, solo qué falló.

## Configuración

| Propiedad | Variable | Por defecto | Qué hace |
|---|---|---|---|
| `shoplab.eval.history.trace-url-template` | `SHOPLAB_EVAL_HISTORY_TRACE_URL_TEMPLATE` | `http://localhost:3001/trace/{traceId}` | Plantilla del enlace a la traza de Langfuse. **Vacía = sin enlace** (se muestra el identificador). Debe ser `http` o `https` y contener `{traceId}` una vez; si no, el arranque falla |
| `shoplab.chat.feedback.base-golden-case-number` | `SHOPLAB_CHAT_FEEDBACK_BASE_GOLDEN_CASE_NUMBER` | `120` | Último número `G<NNN>` del archivo `eval/golden.jsonl` base, a partir del cual se sugiere el siguiente identificador libre (`G121`, …) al promover feedback con 👎 |

No hay cambios de infraestructura ni secretos nuevos.

## Feedback 👎 de clientes y promoción a casos golden (LAB-33)

Al final de `/admin-eval` se encuentra la sección **Feedback 👎 de clientes y promoción a casos golden** ([ADR-0030](adr/0030-feedback-de-respuestas-y-promocion-a-golden.md)):

1. **Cargar respuestas con 👎**: consulta bajo demanda `GET /api/chat/feedback?rating=DOWN` y muestra las respuestas valoradas negativamente por los clientes (ordenadas de más reciente a más antigua), con filtro entre *Pendientes de promover*, *Todas* y *Ya promovidas*.
2. **Promover a caso golden**: sobre cualquier valoración 👎 pendiente, abre el formulario precargado con el siguiente identificador sugerido (`G121`, `G122`…), la pregunta del cliente y hasta 2 documentos entregados en aquel turno (`delivered_documents`), dejando `referenceAnswer` vacía para que quien administra redacte la respuesta de referencia curada.
3. **Validación LAB-27**: al confirmar (`POST /api/chat/feedback/{turnId}/promote`), el backend valida las reglas del dataset golden (`G-ID`, `G-CATEGORY`, `G-QUESTION-LENGTH`, `G-ANSWER-LENGTH`, `G-DOC-COUNT`, `G-CATEGORY-TOPIC`, `G-NOANSWER-DIGITS` y `G-PII`). Si alguna falla, responde `422 SLHD_CHAT_00014` con el código `G-...` en `detail` para corregirla sin perder lo escrito.
4. **Incorporación a `eval/golden.jsonl`**: una vez promovida, la valoración queda vinculada de forma inmutable al `G<NNN>` en `chat.feedback` y la pantalla muestra la línea JSONL canónica con botones **Copiar JSONL** y **Descargar .jsonl**. Pega la línea al final de `eval/golden.jsonl`, anota la revisión humana en `eval/REVIEW.md` y ejecuta `cd eval && uv run python -m shoplab_eval validate`.

## Para quien desarrolla

- API de lectura: [`specs/LAB-32-admin-eval-screen/contracts/eval-history-api.md`](../specs/LAB-32-admin-eval-screen/contracts/eval-history-api.md).
- Contrato de la pantalla: [`specs/LAB-32-admin-eval-screen/contracts/admin-eval-screen.md`](../specs/LAB-32-admin-eval-screen/contracts/admin-eval-screen.md).
- API de feedback y promoción a golden: [`specs/LAB-33-answer-feedback/contracts/feedback-api.md`](../specs/LAB-33-answer-feedback/contracts/feedback-api.md).
- Datos de prueba para verla con datos: `specs/LAB-32-admin-eval-screen/seed/` (ver su `quickstart.md`).
