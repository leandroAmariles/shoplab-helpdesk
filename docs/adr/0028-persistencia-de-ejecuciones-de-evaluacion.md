# ADR-0028: Persistencia de las ejecuciones de evaluación

- Estado: aceptado
- Fecha: 2026-11-20
- Complementa a: ADR-0015 (límites de contexto), ADR-0017 (embeddings solo en `retrieval`), ADR-0024 (métricas de recuperación por
  HTTP) y ADR-0027 (prompts versionados en Langfuse); no los sustituye

## Contexto

LAB-28 y LAB-29 miden la recuperación y la calidad de las respuestas y dejan el resultado en dos CSV locales (`eval/results/`,
no versionados). Sirven para mirar una ejecución, pero no para responder preguntas que importan: ¿cuándo empezó a bajar la
fidelidad?, ¿esta pregunta ya fallaba hace tres ejecuciones?, ¿con qué versión del prompt se midió? LAB-31 pide guardar cada
ejecución en un esquema `eval` con tres tablas (`eval_run`, `eval_case`, `eval_result`), con la versión de prompt, el modelo y la
configuración de recuperación.

- ADR-0015: un esquema de PostgreSQL por módulo y un dueño por dato; `eval/` es Python y no puede tocar tablas ajenas. ADR-0017 y
  ADR-0024 resolvieron el mismo dilema con `ingest` y con la búsqueda: el servicio Python **no tiene credenciales de base de
  datos** y entrega su salida a un módulo del backend.
- El backend no tiene todavía autenticación (ADR-0024 aceptó ese riesgo para una lectura). Un endpoint que **escribe** no puede
  quedar abierto.
- LAB-85 hizo que los prompts se editen en Langfuse con el chat vivo. El CSV de calidad solo registra `production`, no la versión
  numérica, y una medición de unos diez minutos puede cruzarse con una edición.
- El texto de las respuestas y de los fragmentos vive en las trazas de Langfuse (ADR-0022); el historial no debe duplicarlo.

## Decisión

1. **Un módulo nuevo `eval` del backend es el dueño del esquema `eval`** (módulo = esquema = carpeta de migraciones, ADR-0015).
   La herramienta de `eval/` entrega cada ejecución por HTTP y **no tiene credenciales de base de datos**; una prueba
   (`EvalHasNoDatabaseCredentialsTests`) vigila que no aparezcan, como ya hace `IngestHasNoDatabaseCredentialsTests`.
2. **Una ejecución se envía entera y se guarda en una sola transacción** (`POST /api/eval/runs`, ≈ 100 KB). No hay estado
   intermedio: una ejecución visible es una ejecución completa. `eval_run.run_id` es único: un identificador repetido es un 409
   y no se sobrescribe ni se duplica, incluso con dos guardados a la vez.
3. **La revisión de un caso se identifica por un hash que calcula el backend** (SHA-256 de los cinco campos, con los documentos
   esperados ordenados). `eval_case` guarda una fila por revisión y cada resultado apunta a la suya, así que editar el dataset
   nunca reescribe lo medido antes y las comparaciones marcan como no comparable el caso que cambió. La huella del dataset (los
   bytes de `golden.jsonl`) es un dato opaco de la herramienta.
4. **Las puntuaciones van en un `jsonb`** con el nombre estable de cada métrica; `null` es «no aplica o no se pudo calcular por
   definición» y un cero es una puntuación (una restricción exige 0–1). Las dos mediciones tienen métricas distintas y la de
   recuperación depende de los k pedidos, y añadir una métrica no exige migrar. El resumen no se guarda: se recalcula desde los
   resultados.
5. **El módulo `chat` publica en su `api` una instantánea de solo lectura** (`AssistantSettingsApi`: top-k, umbral, presupuesto
   y la versión numérica servida de cada prompt) y `eval` la expone en `GET /api/eval/settings`. La herramienta la lee **antes y
   después** de medir y, si algo que la medición no fijó cambió, la ejecución es inválida y no se guarda. Es la única dependencia
   `eval → chat.api`.
6. **El guardado está apagado por defecto y protegido por un token** (`shoplab.eval.persistence.token`, cabecera
   `X-Eval-Token`, el patrón del `override-token` de LAB-85), comparado en tiempo constante y nunca escrito en un mensaje. En la
   herramienta es opcional: se guarda si y solo si hay `SHOPLAB_EVAL_TOKEN` (`--save` lo exige, `--no-save` lo evita), con la
   convención de Langfuse en LAB-29.
7. **No se guarda el texto de las respuestas ni de los fragmentos**, solo puntuaciones, identificadores de documentos y el
   contenido del dataset golden, que es sintético. `trace_id` apunta a Langfuse. Una prueba fija la lista de columnas.
8. **El historial se lee con consultas SQL documentadas** (`eval/queries/`, probadas contra PostgreSQL); la pantalla y cualquier
   API de lectura son de LAB-32.
9. **Un fallo del guardado nunca cuesta el CSV**: se escriben primero y la ejecución se envía después; sin guardar, la base no
   queda con nada de ella y el código de salida es distinto de cero.

Alternativas descartadas:

- **Escribir desde Python con `psycopg` y credenciales propias**: es más corto, pero añade un secreto y una dependencia a `eval/`,
  repite validaciones que el backend ya tiene, obliga a exponer PostgreSQL a quien mida y rompe la convención que ADR-0015, 0017
  y 0024 sostienen.
- **Abrir la ejecución, enviar lotes y cerrarla**: necesita un estado «abierta» que luego hay que limpiar y filtrar en cada
  consulta; el cuerpo completo es pequeño.
- **Guardar el CSV como tal**: no lleva los parámetros ni los documentos esperados y obligaría al backend a interpretar texto.
- **Una cuarta tabla en formato largo (`metric`, `value`)** o columnas anchas por métrica: el criterio de aceptación nombra tres
  tablas y las columnas anchas dejarían la mitad vacía y exigirían una migración por métrica.
- **Que la herramienta consulte Langfuse para la versión de los prompts**: exige sus claves para guardar y repite la resolución
  de etiquetas; el backend ya sabe qué versión sirve (ADR-0027, decisión 4).
- **Guardar solo la instantánea inicial**: más simple, pero deja entrar ejecuciones mezcladas que contaminan toda comparación
  posterior.

## Consecuencias

- Un esquema nuevo (`eval`, migraciones `V1` y `V2`) y un módulo más en `docs/modulos/`, generado desde el código.
- **Hay un secreto nuevo, el token**, que debe llegar al backend y a quien mida. Es opcional: sin él el backend arranca y responde
  403 al guardar. En el clúster de desarrollo sale de `/shoplab/dev/eval` (`ExternalSecret backend-eval`, `optional: true`) y lo
  genera `make secrets` de `shoplab-infra`; el cambio de infraestructura se fusiona **antes** de que se despliegue el módulo.
- El **commit** que se guarda es el de `eval/`, no el del backend medido. El **modelo del asistente** lo declara quien mide (el
  backend no lo expone) y un cambio de modelo en el gateway durante la medición no se detecta; la deriva solo se detecta para lo
  que `chat` publica (prompts y parámetros de recuperación).
- No hay retención, archivado ni borrado: el historial solo crece (≈ 100 filas por ejecución). Los CSV anteriores no se importan.
- Una ejecución de CI efímero no tiene dónde guardarse hasta que LAB-30 decida cómo (qué base persistente alcanzan los
  runners); sin token todo se comporta como antes.
- La respuesta de error del proyecto gana un campo opcional `detail` (interfaz `PublicDetail` en `shared`), usado solo por el 422
  de una ejecución inválida; el resto de errores mantiene el mismo JSON.
- Las métricas y el dataset siguen siendo los de LAB-28 y LAB-29, con la reserva de que el dataset es un borrador hasta su
  revisión humana: el historial la hereda.
