# Research: Persistencia de las ejecuciones de evaluación (LAB-31)

Las decisiones de arquitectura de esta historia se registran además como **ADR-0028** (el siguiente número libre; 0026 no
está en `main`, se comprueba al implementar). Este documento recoge lo que se investigó y lo que se descartó.

**Fuera de este plan** (decisión del usuario): LAB-30. No se planifica el CI ni dónde guarda una ejecución de CI efímero; el
guardado se comporta como hoy cuando no hay token configurado (FR-010) y eso es todo lo que esta historia promete para CI.

## R1 · Quién escribe en el esquema `eval`

**Decisión**: un **módulo nuevo `eval` del backend** es el dueño del esquema `eval` y de sus migraciones. La herramienta
Python de `eval/` entrega cada ejecución entera por HTTP; **no tiene credenciales de base de datos**.

**Por qué**:
- ADR-0015 fija «un esquema por módulo, un dueño por dato». ADR-0024 ya resolvió el mismo dilema para la lectura
  («`eval/` no puede consultar pgvector») y lo resolvió con un endpoint. ADR-0017 hace lo mismo con `ingest`, que «entrega su
  salida a `retrieval`» y tiene una prueba (`IngestHasNoDatabaseCredentialsTests`) que vigila que no aparezcan credenciales.
- El esquema, las claves y las restricciones quedan versionados con Flyway junto al resto (`ModuleSchemaMigrator`), y las
  reglas de aislamiento (`SchemaIsolationTests`) ya los cubren.
- La Constitución II exige puertos y adaptadores; el módulo sigue la misma estructura que `chat` y `retrieval`.

**Alternativas descartadas**:
- *Python con `psycopg` y credenciales propias*: añade una dependencia y un secreto nuevos a `eval/`, repite validaciones que
  el backend ya hace, y rompe la convención que ADR-0015/0017/0024 sostienen. En el clúster de desarrollo obligaría a exponer
  PostgreSQL a quien ejecute la evaluación.
- *Guardar en un esquema de `chat` o `retrieval`*: mezcla datos de evaluación con datos de negocio y viola «un dueño por dato».
- *Un servicio nuevo*: la Constitución I prohíbe extraer servicios antes de F4/F5.

## R2 · Cómo viaja la ejecución y cómo se garantiza que es entera o ninguna

**Decisión**: **una sola petición** `POST /api/eval/runs` con la ejecución completa (parámetros y todos los resultados, del
orden de cien filas). El backend la guarda en **una transacción**: inserta la ejecución, crea las revisiones de caso que
falten e inserta los resultados. No existe estado intermedio; una ejecución visible es una ejecución completa (FR-012).

**Por qué**: el cuerpo es pequeño (≈ 100 resultados, unos 100 KB; el límite de decodificación del backend, `max-in-memory-size`, ya es de 11 MB), así que no hace falta abrir la ejecución, enviar lotes y
cerrarla: ese diseño necesitaría un estado «abierta» que luego habría que limpiar y filtrar en cada consulta.

**Identificador repetido** (FR-013): `eval_run.run_id` es `UNIQUE`. Se comprueba antes de medir con
`GET /api/eval/runs/{runId}` y la restricción lo garantiza también frente a dos guardados concurrentes (409).

**Alternativas descartadas**: *abrir / añadir lotes / cerrar* (estado intermedio, limpieza); *subir el CSV* (el CSV no lleva
los parámetros ni los documentos esperados, y obligaría al backend a interpretar texto).

## R3 · Revisión de un caso (qué decía el caso cuando se midió)

**Decisión**: `eval_case` guarda **una fila por revisión**: `UNIQUE (case_id, content_hash)`. El hash es un SHA-256 del
contenido de cinco campos (id, categoría, pregunta, documentos esperados, respuesta de referencia) y **lo calcula el backend**
al recibir el caso; si ya existe esa pareja se reutiliza, si no se crea una revisión nueva. Cada `eval_result` apunta a la
fila concreta, de modo que cambiar el dataset nunca reescribe lo medido antes (FR-004).

**Por qué el backend y no el cliente**: así hay una sola implementación del hash y no hace falta que Java y Python
canonicalicen el contenido igual. La **huella del dataset** (FR-008), en cambio, es un dato opaco del cliente: el SHA-256 de
los bytes de `golden.jsonl`, que solo el cliente tiene.

**Comparar casos**: dos resultados son comparables si apuntan a la misma fila de `eval_case`; si el `case_id` coincide pero la
fila no, la comparación marca el caso como no comparable (historia 3, escenario 4).

## R4 · Dónde van las puntuaciones

**Decisión**: `eval_result.scores` es un **`jsonb`** con el nombre estable de cada métrica y su valor, con `null` explícito
cuando la métrica no aplica o no se pudo calcular por definición. Nombres: `faithfulness`, `answer_relevancy`,
`context_precision` (calidad) y `hit_at_<k>`, `recall_at_<k>`, `reciprocal_rank` (recuperación).

**Por qué**: las dos mediciones tienen métricas distintas y la de recuperación depende de los k pedidos; columnas anchas
dejarían la mitad vacía y cada métrica nueva exigiría una migración (edge case «añadir una métrica no reescribe lo guardado»).
Una cuarta tabla en formato largo (`metric`, `value`) cumpliría lo mismo, pero el criterio de aceptación nombra tres tablas y
una consulta como `(scores->>'faithfulness')::numeric` basta para promediar y comparar.

**Cero ≠ vacío**: un `0` es una puntuación; la ausencia de la clave o un `null` es «no aplica». Una restricción de la tabla
exige que todo valor numérico esté entre 0 y 1.

**Alternativa descartada**: guardar el resumen por ámbito en una tabla. El resumen **se recalcula** desde los resultados
(FR-015, SC-002); guardarlo duplicaría el dato y permitiría que se contradijeran.

## R5 · Parámetros de la ejecución: columnas o `jsonb`

**Decisión**: columnas para lo que se filtra a menudo (`kind`, `run_id`, `assistant_model`, `embedding_model`, `commit_sha`,
`dataset_hash`, `origin`, instantes y contadores) y `jsonb` para lo que tiene forma de lista u objeto: `prompts`, `judge`,
`evaluator` y `retrieval_config`. «Ejecuciones con el prompt `X` versión `n`» (FR-016) se consulta con contención de `jsonb`
(`prompts @> '[{"name":"chat-system","version":3}]'`); con decenas de ejecuciones no hace falta índice.

## R6 · De dónde salen los prompts servidos y la configuración de recuperación

Hoy el CSV de calidad registra `prompt_versions = production` cuando no se fijó ninguna versión: **no dice qué versión
numérica era**. FR-005 pide la versión concreta, y el cliente no la conoce: solo el backend sabe cuál sirvió su caché
(ADR-0027, decisión 4). Tampoco expone el top-k ni el umbral que usa.

**Decisión**: el módulo `chat` **publica en su `api`** una instantánea de solo lectura, `AssistantSettingsApi`, con los
parámetros de recuperación (`top_k`, `similarity_threshold`, `max_context_characters`) y, por cada prompt, nombre, versión
servida, origen (`LANGFUSE` o `DEFAULT`). El módulo `eval` la publica por HTTP en `GET /api/eval/settings` (dependencia
`eval → chat.api`, permitida por Modulith y sin ciclo). La herramienta:

1. la lee **antes** de medir y la lleva en la ejecución;
2. la vuelve a leer **al terminar** y, si algo no fijado por petición cambió (alguien movió la etiqueta `production` o
   reinició el backend con otro umbral), **falla con «medición inválida»** en vez de guardar una ejecución mezclada.

**Por qué la segunda lectura**: LAB-85 existe precisamente para editar prompts en Langfuse con el chat vivo; una medición de
unos diez minutos puede cruzarse con una edición, y sin esta comprobación el historial atribuiría a una versión lo que
respondió otra.

Las versiones **fijadas por petición** (`--prompt`) las conoce el cliente y se guardan con `pinned = true`; no entran en la
comparación de deriva.

**Alternativas descartadas**: *que el cliente consulte Langfuse* (exige claves de Langfuse para guardar y repite la
resolución de etiquetas); *devolver la versión servida en cada turno* (cambia el contrato del chat, FR-018); *instantánea solo al
guardar* (puede ser distinta de la de la medición).

## R7 · Protección del endpoint de escritura

El backend no tiene autenticación todavía (ADR-0024 aceptó ese riesgo para una lectura). Un endpoint que **escribe** no se
deja abierto: se copia el patrón de LAB-85 (`override-token`): el guardado está **apagado por defecto** y solo se activa con
`shoplab.eval.persistence.token` (variable `SHOPLAB_EVAL_PERSISTENCE_TOKEN`); cada petición debe llevar la cabecera
`X-Eval-Token` con ese valor. Sin token configurado o con uno distinto, la respuesta es 403 y no se toca la base. El token
nunca aparece en registros, errores ni en lo guardado (FR-009, Constitución VI). El `GET` del estado de una ejecución y el de
la instantánea llevan la misma cabecera, para que no se pueda sondear el historial.

## R8 · Comportamiento de la herramienta (opcional, explícito, sin sorpresas)

Se copia la convención de Langfuse de LAB-29: **sin opción, se guarda si y solo si hay token en el entorno**
(`SHOPLAB_EVAL_TOKEN`); `--save` sin token es error de uso; `--no-save` nunca guarda. Los CSV se escriben igual. Orden:

1. Validar opciones y dataset; comprobar gateway, índice y Langfuse como hoy.
2. Si se guarda: leer la instantánea, comprobar que el `run_id` está libre (esto prueba base, esquema y token) y **abortar
   antes de consultar al asistente o al juez** si algo falla (FR-011).
3. Medir.
4. Si se guarda: releer la instantánea; si hubo deriva, medición inválida (sin CSV, código 1).
5. Escribir los CSV.
6. Guardar. Si falla: los CSV ya están, no queda nada en la base y el código es 1 (FR-014).

La medición de recuperación **no tiene hoy `--run-id`**; se le añade con el mismo patrón y validación que la de calidad.

## R9 · Origen y commit

- **Origen** (`origin`): etiqueta libre y corta (`local`, `dev`, `ci`) tomada de `SHOPLAB_EVAL_ORIGIN`, por defecto `local`.
  No lleva usuario ni máquina (datos personales). Sirve para filtrar ejecuciones de prueba (edge case «datos de prueba
  mezclados»).
- **Commit** (`commit_sha`): `git rev-parse HEAD` del repositorio de la herramienta si está disponible; si no, `null` (FR-008:
  nunca inventado). Es el commit de `eval/`, no el del backend medido; la documentación lo dice.

## R10 · Infraestructura

El backend usa ya su propia conexión a PostgreSQL: **no hay credenciales nuevas de base de datos**. Lo que sí hay es **un
secreto nuevo, el token** (R7), que debe llegar al backend y a quien ejecute la evaluación. Se comprobó en el código:

- **`infra/docker-compose.yml` de este repositorio no tiene servicio `backend`** (solo `postgres`, `ollama`, `litellm`,
  `ingest` y observabilidad), así que no se toca ni `infra/.env.example` ni el compose. Quien corre el backend desde el IDE
  define `SHOPLAB_EVAL_PERSISTENCE_TOKEN` en su entorno; se documenta en `eval/README.md`.
- **`shoplab-infra`** (repositorio hermano, el entorno de desarrollo = entorno local) maneja los secretos con un JSON por
  secreto en `secrets.auto.tfvars` → Secrets Manager `/shoplab/dev/<nombre>` → `ExternalSecret` → `envFrom` del backend. Se
  añade el secreto **`eval`** con ese mismo patrón y se genera en `scripts/ensure-secrets.sh` (que `make up` ya ejecuta). No se
  añade a `required_secrets` del módulo de Terraform porque el guardado es opcional, y el `envFrom` se declara
  `optional: true` para que el backend no dependa de él para arrancar. El detalle está en «Cableado de infraestructura» de
  `plan.md`.
- **`make quality`** (`scripts/quality.sh` de `shoplab-infra`) ya lee del clúster la clave del juez (`LITELLM_KEY_EVAL`) y las de
  Langfuse; leerá también el token, de modo que medir contra desarrollo guarda sin pasos manuales.
- El `override-token` de LAB-85 **no está cableado** en `shoplab-infra` (solo existe en `application.yml`): no sirve de
  precedente de infraestructura, solo de patrón de código.

## R11 · Alcance de lectura

La historia 3 se resuelve con **consultas SQL documentadas** en `eval/README.md` (FR-016). No se crea API de lectura del
historial: LAB-32 la definirá. Los dos `GET` de este plan existen solo para que la herramienta compruebe el entorno (R6, R8).
