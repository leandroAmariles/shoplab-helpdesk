# Quickstart: validar la pantalla `admin-eval` (LAB-32)

Guía para comprobar de punta a punta que la historia funciona. No contiene código de implementación: las rutas y las formas
están en [contracts/eval-history-api.md](contracts/eval-history-api.md) y [contracts/admin-eval-screen.md](contracts/admin-eval-screen.md);
los modelos, en [data-model.md](data-model.md).

## Requisitos

- Base de datos del entorno local arriba (`make up` de `shoplab-infra` o el compose de este repositorio) y el backend con el
  módulo `eval` (desde el IDE o `./mvnw spring-boot:run` en `backend/`) con `SHOPLAB_EVAL_PERSISTENCE_TOKEN=<token de prueba>`
  **solo para cargar datos**: la pantalla no lo usa.
- Frontend: `cd frontend && npm ci && npm start` (proxy de `/api` a `http://localhost:8080`).
- **Datos de prueba**: cinco ejecuciones sintéticas y deterministas (tres de calidad —una con un caso editado y una con una
  métrica sin valor por definición— y dos de recuperación) en `specs/LAB-32-admin-eval-screen/seed/`, que se cargan con
  `seed.http` (una petición `POST /api/eval/runs` por ejecución, con el token de prueba). Sirven ejecuciones reales de `eval/`
  igual (`quality --save`, ver el quickstart de LAB-31), pero la medición de calidad tarda unos 30 minutos.
- Para el enlace a la traza: Langfuse (perfil `llmops`, `http://localhost:3001`) o la plantilla de la configuración.

## Escenarios

| # | Qué se hace | Resultado esperado | Spec |
|---|---|---|---|
| V1 | Abrir `http://localhost:4200/admin-eval` desde la navegación (sin escribir la dirección) | La entrada «Evaluación» está junto a «Chat» y «Documentos»; abre la pantalla con calidad y fidelidad | FR-001, HU4·1 |
| V2 | Con las 3 ejecuciones de calidad, mirar la gráfica | Un punto por ejecución en orden cronológico; el valor de cada punto coincide con `03-run-summary.sql` y con el CSV de esa ejecución a 4 decimales | FR-002, FR-007, SC-003 |
| V3 | Enfocar con `Tab` un punto, moverse con ← → y pulsar `Enter` | El panel dice id, fecha, valor y «calculado con N casos»; `Enter` elige la ejecución y pone `?ejecucion=` en la dirección | FR-004 |
| V4 | Añadir `answer_relevancy` y `context_precision` | Las tres series se distinguen por marcador y trazo además del color; ninguna oculta a otra | HU1·3 |
| V5 | Elegir la ejecución con la métrica sin valor por definición | La serie queda **interrumpida** en esa ejecución; la tabla dice «sin valor»; no hay línea hasta 0 | FR-005, SC-007 |
| V6 | Cambiar a «Recuperación» | Solo ejecuciones de recuperación; series `hit_at_<k>`, `recall_at_<k>` y `reciprocal_rank`; nunca mezcladas con calidad | FR-003, HU1·4 |
| V7 | Filtrar por origen `local` | Gráfica y lista cambian; el texto dice cuántas ejecuciones quedan ocultas por el filtro | FR-006, HU1·6 |
| V8 | Quitar todas salvo una ejecución (o filtrar a una) | Un punto, el texto «Hace falta más de una ejecución…» y la pantalla sigue legible | HU1·7 |
| V9 | Elegir una ejecución de calidad | Se ven prompts con **versión numérica**, modelo (o «desconocido»), embeddings, juez, biblioteca, recuperación, origen, commit (o «desconocido») y casos medidos/excluidos/sin puntuar | FR-008, HU2·1 |
| V10 | Comparar la media por categoría del detalle con `03-run-summary.sql` | Coinciden a 4 decimales y con el mismo `n` | FR-009, SC-003 |
| V11 | Con umbral 0,5, listar los casos que fallan y contrastar tres con el CSV de detalle (uno con las tres métricas, uno sin respuesta con solo fidelidad, uno con una métrica vacía) | Valores, documentos esperados y recuperados idénticos; la métrica vacía dice «sin valor» y no cuenta como fallo | FR-010, FR-013, SC-004 |
| V12 | Mover el umbral a 0,9 y a 0 | La lista se actualiza al instante, sin peticiones nuevas (pestaña Red); con 0 «Ningún caso falla con este criterio» | FR-010, HU2·7 |
| V13 | En recuperación, cambiar el k | Fallan los casos con `hit_at_<k> = 0`; los que no tienen documentos esperados no aparecen | FR-011 |
| V14 | Abrir un caso y seguir «Ver traza en Langfuse» (o ver el id con «Copiar» sin plantilla) | Llega a la traza del turno; la pantalla **no** muestra texto de respuesta ni de fragmentos | FR-014, FR-015, FR-016 |
| V15 | Marcar como referencia la ejecución 1 y comparar con la 2 (cambio deliberado de prompt) | Nombra el prompt y las dos versiones; la lista de **empeoraron** coincide con `07-regressions.sql` (`run_a`, `run_b`, `metric`, `drop` 0,05) | FR-017, FR-019, SC-005 |
| V16 | En esa comparación, mirar el caso editado entre las dos ejecuciones y las advertencias | El caso está en «no comparables»; si difieren juez, modelo, embeddings, recuperación o dataset, hay advertencia con el campo | FR-018, HU3·3-4 |
| V17 | Intentar comparar una ejecución de calidad con una de recuperación | La acción está deshabilitada con «Sus métricas no son las mismas»; por API devuelve 422 `SLHD_EVAL_00006` | HU3·5 |
| V18 | Base sin ejecuciones (o `kind` sin datos) | Estado vacío con explicación y cómo generar un historial; ningún error | FR-021, HU4·2 |
| V19 | Parar el backend y pulsar «Actualizar» | Aviso de error con «Reintentar» que no vacía lo mostrado; al arrancarlo, «Reintentar» recupera | FR-021, HU4·3 |
| V20 | Abrir `?ejecucion=no-existe` | Aviso «La ejecución no existe» y la dirección se limpia | contrato |
| V21 | Recorrer toda la pantalla solo con teclado y con un lector de pantalla; reducir la ventana a 360 px | Todo es alcanzable; la gráfica tiene alternativa textual; no hay scroll horizontal de la página | FR-022, SC-009 |
| V22 | Buscar en las respuestas de los tres endpoints un fragmento del texto de una respuesta del asistente | Ninguna lo contiene; los campos son exactamente los del contrato | FR-016, SC-008 |
| V23 | Cargar 100 ejecuciones sintéticas y abrir la pantalla | Gráfica y resumen en menos de 3 s; detalle en menos de 2 s; «Mostrando 100 de N» si hay más | SC-006, FR-023 |
| V24 | En el entorno de desarrollo del clúster, con el primer tag que incluya la historia | La pantalla funciona contra la base del entorno local; sin pasos extra de infraestructura | FR-027 |

## Verificación automática (sin red ni modelo)

- `cd backend && ./mvnw verify`: comparador, casos de uso, controlador, adaptador JDBC contra PostgreSQL real, equivalencia
  con `eval/queries/*.sql`, reglas de arquitectura y `docs/modulos/` al día (commitear lo que regenere `ModularityTests`).
- `cd frontend && npm test && npm run lint && npm run build`: funciones puras, store, cliente y componentes; ESLint y Prettier
  limpios; la ruta carga perezosa.
- Cobertura del backend ≥ 80 % (JaCoCo) y sin infracciones de Checkstyle ni PMD.

## Si algo falla

- La lista sale vacía pero hay ejecuciones: el tipo elegido no es el de las ejecuciones guardadas, o un filtro de origen oculta
  todo (el texto de estado lo dice).
- 503 en todo: el backend no migró el esquema `eval` o la base está caída; se ve en su registro de arranque.
- No hay enlace a la traza: falta la plantilla (`SHOPLAB_EVAL_HISTORY_TRACE_URL_TEMPLATE`) o la ejecución es de recuperación
  (no tiene traza); la pantalla muestra el identificador para copiarlo.
- La gráfica «cambia» tras guardar una ejecución nueva: es lo esperado al pulsar «Actualizar»; la pantalla no sondea.
