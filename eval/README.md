# eval/ — dataset golden y evaluación

Aquí vive el **dataset golden** de ShopLab Helpdesk: un conjunto fijo de preguntas de clientes con su respuesta de
referencia y los documentos de la base de conocimiento (`data/kb`) donde está esa respuesta. Es la vara de medir con la que
las historias de F2 juzgan al asistente: métricas de recuperación (LAB-28), calidad de respuesta (LAB-29) y evaluación en CI
(LAB-30). Sin un conjunto fijo, cada cambio de prompt, chunking o recuperación sería una impresión y no un dato.

| Archivo | Para qué sirve |
|---|---|
| `golden.jsonl` | El dataset: 120 casos (96 de conocimiento y 24 de conversación), uno por línea |
| `REVIEW.md` | Revisión manual registrada (quién revisó qué y cuándo) |
| `src/shoplab_eval/` | Lector y validación del dataset (`load_golden`, `validate_golden`) y medición de la recuperación (`retrieval_*.py`, `search_client.py`) |
| `results/` | Salida local de la medición de recuperación (los CSV no se versionan) |
| `store_*.py`, `queries/` | Guardar cada ejecución en el historial del backend (LAB-31) y las consultas SQL para leerlo |
| `tests/` | Pruebas, incluida una contra el `golden.jsonl` y el `data/kb` reales |

Especificación y decisiones: [`specs/LAB-27-golden-dataset/`](../specs/LAB-27-golden-dataset/) (dataset) y
[`specs/LAB-28-retrieval-metrics/`](../specs/LAB-28-retrieval-metrics/) (métricas de recuperación, ADR-0024). y
[`specs/LAB-31-eval-run-persistence/`](../specs/LAB-31-eval-run-persistence/) (historial de ejecuciones, ADR-0028).

## Formato de cada caso

JSON Lines en UTF-8 **sin BOM**: un objeto por línea, sin comentarios ni cabecera. Se toleran líneas vacías.

```json
{"id": "G001", "question": "Mi boleto venció sin que lo pagara, ¿qué puedo hacer?", "reference_answer": "Si el boleto vence sin pago, el pedido pasa a cancelado. Puedes volver a hacer el pedido y elegir otro método de pago.", "expected_documents": ["payments-03-boleto-and-vouchers"], "category": "payments"}
{"id": "G071", "question": "¿Qué porcentaje de interés me cobran en las cuotas que llevan recargo?", "reference_answer": "No tengo información sobre el porcentaje de interés de las cuotas con recargo en los documentos disponibles. Te recomiendo consultarlo con atención al cliente antes de elegir el número de cuotas.", "expected_documents": [], "category": "payments"}
{"id": "G083", "question": "¿Me recomiendas una receta de pasta para esta noche?", "reference_answer": "Solo puedo ayudarte con consultas sobre la tienda: pagos, privacidad, devoluciones, envíos y garantías. No tengo información sobre recetas de cocina.", "expected_documents": [], "category": "off_topic"}
```

| Clave | Qué es |
|---|---|
| `id` | `G` + tres dígitos (`G001`). Único y **estable**: no cambia nunca, aunque el caso se edite o se reordene |
| `question` | La pregunta, en español, tal como la escribiría un cliente. Autocontenida (sin «¿y en cuotas?») |
| `reference_answer` | La respuesta correcta según el corpus, redactada con palabras propias |
| `expected_documents` | De 0 a 2 ids de documento de `data/kb` (el nombre del archivo sin `.md`) donde está la respuesta |
| `category` | `payments`, `privacy`, `returns`, `shipping`, `warranty`, `off_topic`, `small_talk` o `injection` |

Se rechazan las claves desconocidas.

### Casos con y sin respuesta

- **Con respuesta**: `expected_documents` tiene uno o dos documentos. Si dos documentos repiten el mismo dato (una política y
  su FAQ), se listan los dos; si la respuesta exige **juntar** información de dos documentos, también.
- **Sin respuesta del tema**: la pregunta es de un tema de la tienda pero el dato **no está publicado** (p. ej. el
  porcentaje de interés de las cuotas). `expected_documents` va vacío y `category` conserva el tema. La respuesta de
  referencia reconoce que no hay información y **no inventa nada**.
- **Ajena a la tienda** (`off_topic`): nada que ver con la tienda (una receta, el tiempo). Lista vacía y categoría
  `off_topic`.

- **Cortesía** (`small_talk`, LAB-114): un saludo, un agradecimiento, una despedida o «¿qué puedes hacer?». Sin documentos
  esperados. La pregunta debe ser **solo** cortesía con el léxico cerrado del backend (`SmallTalkConstants`); la respuesta de
  referencia es breve, amable e invita a preguntar por los temas de la tienda, sin ningún dato.
- **Inyección** (`injection`, LAB-114): un intento de sacar el prompt o la configuración, de cambiar el rol o una instrucción
  embebida en un saludo. Sin documentos esperados. **Ninguna** pregunta puede ser una cortesía; la respuesta de referencia
  no revela nada, mantiene el rol y redirige a los temas de la tienda.
- **Mixto**: no tiene categoría propia. Es un caso con respuesta de su tema, con sus documentos, cuya pregunta **empieza por
  una cortesía** («Hola, mi boleto venció…»). Lo mide la recuperación como cualquier otro y el backend comprueba que no se
  clasifica como charla.

El tipo se deduce de la lista de documentos y de la categoría; no se guarda aparte. Los casos de conversación **no cuentan**
como «sin respuesta», no entran en la medición de recuperación ni en sus «excluidos», y la prueba `GoldenDatasetClassifierTests`
del backend lee este archivo para comprobar que el clasificador y el dataset no se contradicen.

La respuesta de referencia de un caso sin respuesta describe la **intención** («no hay información»), no el texto exacto del
mensaje fijo que el asistente usa cuando no recupera contexto (LAB-22).

## Validar

```bash
cd eval
uv sync --frozen
uv run python -m shoplab_eval validate     # resumen y código de salida 0, o un fallo por línea y código 1
uv run pytest                              # pruebas + el dataset real contra el corpus real
uv run ruff check .
```

Dos ejecuciones sobre el mismo archivo dan exactamente la misma salida. Para otro archivo o corpus:
`--golden <ruta> --kb <ruta>`. Desde Python: `from shoplab_eval import load_golden` (lanza `InvalidGoldenError` con todos los
fallos juntos).

### Qué significa cada código

| Código | Qué falla |
|---|---|
| `G-JSON` | la línea no es JSON válido, o el archivo tiene BOM o no es UTF-8 |
| `G-FIELDS` | falta una clave, hay una desconocida o un valor tiene el tipo equivocado |
| `G-ID` | el id no es `G` + tres dígitos, o se repite |
| `G-QUESTION-LENGTH` / `G-ANSWER-LENGTH` | pregunta fuera de 10–300 caracteres (2–300 en `small_talk` e `injection`) o respuesta fuera de 20–800 |
| `G-CATEGORY` | la categoría no es una de las ocho |
| `G-DOC-COUNT` | más de dos documentos esperados, o repetidos |
| `G-DOC-MISSING` | un documento esperado no existe en `data/kb` (se renombró o se borró) |
| `G-CATEGORY-TOPIC` | la categoría no es el tema de ninguno de sus documentos, o `off_topic`, `small_talk` o `injection` con documentos |
| `G-NOANSWER-DIGITS` | la respuesta de un caso sin respuesta contiene una cifra (plazo o importe inventado) |
| `G-QUESTION-VERBATIM` | la pregunta es una frase copiada de un documento: reescríbela como un cliente |
| `G-PII` | un correo que no es `@shoplab.example` o una secuencia de 8 o más dígitos |
| `G-COUNT` | los casos de conocimiento (temas y `off_topic`, sin `small_talk` ni `injection`) no son entre 80 y 100 |
| `G-PER-TOPIC` | un tema tiene menos de 12 casos con respuesta |
| `G-UNANSWERED` | los casos sin respuesta son menos de 12 o están fuera del 15–25 % del total |
| `G-UNANSWERED-KINDS` | menos de 5 sin respuesta del tema o menos de 5 `off_topic` |
| `G-MULTI-DOC` | menos de 8 casos con dos documentos |
| `G-COVERAGE` | algún documento del corpus no lo espera ningún caso |
| `G-SMALL-TALK-COUNT` | menos de 10 casos `small_talk` |
| `G-INJECTION-COUNT` | menos de 10 casos `injection` |
| `G-DUPLICATE` | dos preguntas son iguales salvo mayúsculas, espacios o puntuación |

Las reglas de conjunto de conocimiento (`G-COUNT`, `G-PER-TOPIC`, `G-UNANSWERED*`, `G-MULTI-DOC`, `G-COVERAGE`) miran solo los
casos de conocimiento; el resto de reglas se aplican a todos los casos.

El resumen añade un **indicador léxico** (porcentaje de preguntas con respuesta cuyas palabras de contenido aparecen menos de
la mitad en sus documentos). Debe ser al menos el 30 % (FR-013) para que medir la recuperación no se reduzca a coincidencia
de texto; es informativo y no hace fallar la validación.

## Añadir un caso

1. Elige el siguiente `id` libre (`G121`, …). El máximo de 100 se cuenta sobre los casos de conocimiento (hoy 96); subirlo es una decisión de la spec.
2. Abre los documentos del tema en `data/kb/` y redacta la pregunta como un cliente, **con palabras distintas** a las del
   documento. Redacta la respuesta solo con lo que dicen los documentos esperados; **no inventes datos**.
3. Para un caso **sin respuesta del tema**, busca los términos clave en `data/kb` (`grep -ril "<término>" data/kb`, con y sin
   tilde) y confirma que ningún documento permite contestar la pregunta, ni siquiera en parte. Su respuesta no lleva cifras
   **ni plazos o cantidades escritos en palabras**.
4. Añade la línea a `golden.jsonl`, ejecuta `uv run python -m shoplab_eval validate` y corrige lo que señale.
5. Una persona revisa el caso contra sus documentos y lo anota en `REVIEW.md`.

Cambiar un caso: edita su línea **sin tocar el `id`**, para que LAB-31 (ejecuciones) y LAB-33 (feedback) sigan
refiriéndose a él.

Sin datos personales reales: si necesitas un número de pedido, usa uno obviamente ficticio (`PED-0001`); los correos solo
pueden ser `@shoplab.example`.

## Cuando cambia el corpus

La validación se ejecuta en CI también cuando cambia `data/kb/**`.

- Si un documento **se elimina o se renombra**, `G-DOC-MISSING` señala cada caso que lo esperaba: actualiza el id.
- Si un documento **cambia de contenido**, la validación no lo detecta: quien lo cambia busca su id en `golden.jsonl`
  (`grep "<id-del-documento>" eval/golden.jsonl`), revisa que las respuestas de referencia siguen siendo ciertas y lo anota
  en su PR. Una respuesta de referencia falsa contamina todas las métricas.
- Si aparece información que contesta un caso **sin respuesta**, ese caso deja de serlo: cambia la pregunta o conviértelo
  en un caso con respuesta, y mantén el reparto del resumen.

## Revisión manual

Lo que la validación no puede comprobar lo revisa una persona y queda en [`REVIEW.md`](REVIEW.md): que cada respuesta de
referencia es cierta y completa leyendo solo sus documentos, que las preguntas sin respuesta del tema no se pueden contestar
con el corpus, y que ninguna respuesta sin respuesta escribe plazos en palabras. Además se repasa una **muestra de 15 casos
con semilla fija**. Si falla un caso de la muestra, se revisan todos los de su tema.

## Métricas de recuperación

Mide **solo la búsqueda**, sin el modelo que redacta la respuesta: si el asistente falla, esto dice si fue porque no
encontró el documento correcto. Para cada pregunta con respuesta del dataset (76 de los 120 casos: los 70 originales y los 6 mixtos) consulta la búsqueda real
del backend y compara los documentos que devuelve, en orden de cercanía, con los `expected_documents` del caso.

| Métrica | Qué dice | Por caso |
|---|---|---|
| `hit_rate@k` | ¿Apareció algún documento esperado entre los k primeros? | `1` o `0` |
| `recall@k` | ¿Qué parte de los documentos esperados apareció entre los k primeros? | de `0` a `1` |
| `mrr` | ¿Cuántos puestos hubo que bajar hasta el primer documento esperado? | `1 / posición`, o `0` si no aparece |

Cada valor del resumen es la **media** de los casos. Ejemplo con k = 3: un caso con un documento esperado que aparece en
**tercer** lugar aporta `hit@3 = 1`, `recall@3 = 1` y `1/3` al MRR (con k = 1 aportaría `hit@1 = 0`). Un caso con **dos**
documentos esperados de los que solo uno aparece entre los 3 primeros aporta `hit@3 = 1` pero `recall@3 = 0,5`. El MRR se
calcula sobre la lista completa, no cortada a k. Con k = 1 y dos documentos esperados el recall máximo es 0,5: por eso se
informan varios k (por defecto 1, 3 y 5).

**Se mide por documento, no por fragmento.** Los fragmentos devueltos se reducen a documentos distintos (el id es el nombre
del archivo sin extensión, así el Markdown y el PDF del mismo documento cuentan como uno), cada uno en la posición de su
fragmento más cercano. Por eso es algo más indulgente que lo que consume el chat (cuatro fragmentos) y mide el orden de la
búsqueda **antes** de que el chat aplique su umbral de similitud y su presupuesto de contexto (ADR-0018). Los casos sin
respuesta no tienen documentos esperados y quedan fuera; que el asistente no invente ante ellos lo mide LAB-29.

### Ejecutar

Necesita el stack local levantado (`docker compose -f infra/docker-compose.yml up`) y los 24 documentos de `data/kb`
**indexados** (estado «Indexado» en la pantalla de documentos). Tarda menos de un minuto.

```bash
cd eval
uv run python -m shoplab_eval retrieval                       # k = 1, 3, 5; resultado en eval/results/
uv run python -m shoplab_eval retrieval --k 1,5 --out results/antes
```

| Opción | Por defecto | Qué hace |
|---|---|---|
| `--k` | `1,3,5` | Valores de k separados por comas: enteros positivos, sin repetir y no mayores que `--depth` |
| `--depth` | `50` | Fragmentos que se piden por pregunta (1–50). Con 50 hay margen para reunir k documentos distintos |
| `--base-url` | `$SHOPLAB_EVAL_BASE_URL` o `http://localhost:8080` | Backend que sirve la búsqueda |
| `--out` | `eval/results/` | Carpeta de salida (se crea si falta) |
| `--golden`, `--kb` | los de `validate` | Dataset y corpus |
| `--timeout` | `10` | Segundos máximos por petición |

Código de salida: `0` medición completa y CSV escritos; `1` la medición no es válida; `2` uso incorrecto (k o `--depth`
inválidos). Con `1` y `2` **no se escribe ningún CSV** ni se tocan los de una ejecución anterior.

### Leer los CSV

Dos archivos, UTF-8 sin BOM, números con cuatro decimales y punto decimal, sin marcas de tiempo: con el mismo índice, la misma
configuración y el mismo dataset, dos ejecuciones dan archivos idénticos byte a byte. En Excel, impórtalos con
«Datos → Desde texto/CSV» y codificación UTF-8 (con doble clic las tildes pueden verse mal).

**`retrieval-summary.csv`**: una fila `all` y una por categoría con casos medibles (`off_topic` no tiene).

| Columna | Significado |
|---|---|
| `scope` | `all` o la categoría |
| `cases`, `excluded_cases` | Casos medidos y casos del ámbito sin documentos esperados (no medidos) |
| `embedding_model`, `search_depth` | Con qué modelo estaba indexada la base y cuántos fragmentos se pidieron: sin esto dos resultados no son comparables |
| `hit_rate@k`, `recall@k` | Una columna por cada k pedido |
| `mrr` | Media del inverso de la posición del primer acierto |

**`retrieval-detail.csv`**: una fila por caso medible, en el orden del dataset. Es de donde sale cualquier número del resumen.

| Columna | Significado |
|---|---|
| `id`, `category`, `question` | El caso |
| `expected_documents` | Ids esperados, separados por `\|` |
| `retrieved_documents` | Ids recuperados en orden de cercanía, separados por `\|` |
| `first_hit_rank` | Posición del primer documento esperado; vacía si no aparece |
| `hit@k`, `recall@k`, `reciprocal_rank` | Los valores del caso |

Ante una caída del hit rate, ordena el detalle por `first_hit_rank` (vacíos primero) y mira qué devolvió la búsqueda en su
lugar. Para comparar dos ejecuciones, guarda cada una en su carpeta (`--out`) y compara los resúmenes: si cambia
`embedding_model`, el cambio es del modelo.

### Errores

| Qué ves | Causa | Qué hacer |
|---|---|---|
| `No se pudo conectar con el backend en …` o `Tiempo agotado … esperando al backend` | El backend no está en esa dirección (en Windows un puerto cerrado también acaba en tiempo agotado) | Levanta el stack o indica `--base-url`; sube `--timeout` si Ollama es lento |
| `El índice no contiene los documentos que el dataset espera` y la lista | Hay documentos sin subir o en `PENDING`/`ERROR` | Súbelos o reindexa, espera al estado «Indexado» y repite. Se detecta **antes** de buscar |
| `Falló la búsqueda del caso G0xx: El backend respondió 503 (SLHD_RETR_00006)` | El modelo de embeddings no responde | Revisa Ollama y el gateway; la pregunta del caso no se cuenta como fallo de recuperación |
| `El modelo de embeddings cambió durante la medición` | Se reindexó a mitad de ejecución | Repite la medición |
| `G-…` (`G-DUPLICATE`, …) | El dataset no es válido | Corrígelo como indica «Validar» |
| `Respuesta ilegible de …` | El endpoint no responde con la forma esperada | Comprueba que el backend es de esta versión |

Un cero **no** es un error: si la búsqueda funciona pero no devuelve el documento esperado, el caso aporta `0`. Los errores
son solo los que impiden dar un resultado fiable.

## Cambiar un umbral

Los números (80–100 casos de conocimiento, 12 con respuesta por tema, 15–25 % sin respuesta, 8 casos de dos documentos…) vienen de
`specs/LAB-27-golden-dataset/spec.md` y viven en `GoldenRules` (`src/shoplab_eval/constants.py`). Cambiar uno es una decisión
de la spec: se actualizan los dos a la vez.

## Comparar versiones de un prompt (LAB-85)

Los prompts del chat se versionan en Langfuse (ADR-0027). Para medir una versión **sin mover `production`** y compararla con otra:

```bash
cd eval
export SHOPLAB_EVAL_PROMPT_TOKEN=<el SHOPLAB_CHAT_PROMPTS_OVERRIDE_TOKEN del backend>
export SHOPLAB_EVAL_LANGFUSE_PUBLIC_KEY=pk-lf-shoplab-local SHOPLAB_EVAL_LANGFUSE_SECRET_KEY=sk-lf-shoplab-local
uv run python -m shoplab_eval quality --prompt chat-system=1 --run-id prompt-a --out results/prompt-a --assistant-model <modelo>
uv run python -m shoplab_eval quality --prompt chat-system=2 --run-id prompt-b --out results/prompt-b --assistant-model <modelo>
uv run python -m shoplab_eval compare results/prompt-a/quality-summary.csv results/prompt-b/quality-summary.csv
```

| Pieza | Qué hace |
|---|---|
| `quality --prompt NOMBRE=VALOR` (repetible) | Fija el prompt (`chat-system`, `chat-small-talk`, `chat-context`, `chat-empty-context`) en una versión (número) o en la versión a la que apunta una etiqueta. Antes de preguntar nada comprueba en Langfuse que existe y es de tipo texto; con Langfuse caído o sin claves **falla** (no mide con el texto por defecto). Envía `X-Prompt-Versions` y `X-Prompt-Token` en cada petición |
| Columna `prompt_versions` | Último campo de `quality-summary.csv`: las versiones medidas, `nombre=versión` separadas por `\|` y en orden alfabético (una etiqueta queda como la versión a la que apuntaba); `production` si no se fijó ninguna |
| `compare A.csv B.csv [--out comparacion.csv]` | Lee la fila `all` de cada resumen y muestra, por métrica, la media y los casos de cada uno y la diferencia (B − A), los prompts que los distinguen y, si el juez, el modelo del asistente, el de embeddings o el número de casos difieren, la advertencia «No comparables». No consulta ningún modelo, Langfuse ni el backend |

Códigos de salida de `quality --prompt`: `2` uso incorrecto (formato, nombre repetido o desconocido, falta `SHOPLAB_EVAL_PROMPT_TOKEN`),
`1` Langfuse no responde, una versión no existe o el backend rechaza fijar versiones (403). `compare` sale con `0` aunque advierta
y con `2` si un resumen no se puede leer. El formato completo está en
[`specs/LAB-85-versioned-prompts/contracts/eval-compare.md`](../specs/LAB-85-versioned-prompts/contracts/eval-compare.md).

## Guardar las ejecuciones (LAB-31)

Cada medición, la de recuperación (`retrieval`) y la de calidad (`quality`), puede quedar guardada en la base de datos del
backend, en el esquema `eval`. Así hay un **historial** que sobrevive a la carpeta `results/`: se puede ver cuándo empezó a
bajar la fidelidad, qué preguntas empeoraron entre dos ejecuciones y con qué prompt, modelo y configuración se midió cada una.

**La herramienta no tiene credenciales de base de datos** (ADR-0015, ADR-0028): entrega la ejecución entera al backend con
`POST /api/eval/runs` y un token, y el módulo `eval` del backend la guarda en **una transacción**: una ejecución visible es una
ejecución completa. Una prueba (`EvalHasNoDatabaseCredentialsTests`) vigila que no aparezcan credenciales en este directorio.

### Qué se guarda y qué no

| Tabla | Qué guarda |
|---|---|
| `eval.eval_run` | Una ejecución: su identificador, tipo (`RETRIEVAL` o `QUALITY`), cuándo empezó y terminó, **origen** (`local`, `dev`…), commit de `eval/`, huella del dataset, casos medidos y excluidos, modelo del asistente y de embeddings, **versión de cada prompt**, juez y biblioteca (solo calidad) y la **configuración de recuperación** (top-k, umbral, presupuesto de contexto y, en recuperación, la profundidad y los k) |
| `eval.eval_case` | Una **revisión** de un caso del dataset golden: su `id`, categoría, pregunta, documentos esperados y respuesta de referencia. Si editas un caso, lo que midas desde entonces apunta a una revisión nueva y las ejecuciones anteriores siguen apuntando a la suya |
| `eval.eval_result` | Lo que un caso dio en una ejecución: el valor de cada métrica (`null` = no aplica o no se pudo calcular **por definición**, nunca un cero), los documentos recuperados en orden, el tipo de caso, si el asistente se abstuvo y el identificador de la traza de Langfuse |

**No se guarda** el texto de la respuesta del asistente ni el de los fragmentos que recuperó: viven en las trazas de Langfuse, a
las que apunta `trace_id`. Una prueba (`EvalNoTextStoredTests`) comprueba que las tablas no tienen ninguna columna que pueda
contenerlo. Las versiones de prompt son **numéricas**: no `production`, sino la versión concreta que sirvió el backend (o la
que fijaste con `--prompt`, marcada `pinned`). Una ejecución de recuperación guarda `prompts = []`: no usa prompts.

### Activarlo

| Dónde | Qué |
|---|---|
| Backend | `SHOPLAB_EVAL_PERSISTENCE_TOKEN` (`shoplab.eval.persistence.token`): un valor secreto cualquiera. **Vacío = el guardado está apagado** (el backend responde 403): como todavía no hay autenticación, una escritura nunca está abierta. En el clúster de dev lo entrega el secreto `/shoplab/dev/eval` (`make secrets`, en `shoplab-infra`) |
| Quien mide | `SHOPLAB_EVAL_TOKEN` con **el mismo valor**. `make quality` de `shoplab-infra` lo lee del clúster |
| Opcional | `SHOPLAB_EVAL_ORIGIN`: etiqueta corta de dónde se lanza (`local` por defecto; `dev`, `ci`…). Nunca un usuario ni una máquina |

```bash
export SHOPLAB_EVAL_TOKEN=<el valor del backend>
uv run python -m shoplab_eval quality --run-id demo-1        # se guarda: hay token
uv run python -m shoplab_eval retrieval --run-id demo-r1 --save
uv run python -m shoplab_eval quality --no-save              # mide y escribe los CSV, sin guardar
```

Guardar es **opcional por configuración**, con la misma convención que Langfuse: sin opción se guarda si y solo si hay
`SHOPLAB_EVAL_TOKEN`; `--save` sin token es un error de uso; `--no-save` nunca guarda. Sin token todo se comporta como antes (se
escriben los CSV y la salida dice que no se guardó nada).

`--run-id` identifica la ejecución (letras, cifras, `.`, `_` y `-`, hasta 32 caracteres; por defecto el momento UTC). **Un
identificador guardado no se sobrescribe ni se duplica**: repetirlo es un error que se detecta antes de medir.

### En qué orden pasa todo

1. Se validan las opciones y el dataset, y se comprueba lo de siempre (gateway, índice, Langfuse).
2. Con el guardado activo se lee la **configuración del asistente** (`GET /api/eval/settings`) y se comprueba que el
   identificador está libre. Eso prueba el token, la base y el esquema: si algo falla, termina **antes de consultar al asistente
   o al juez**, sin gastar una medición que no se podría guardar.
3. Se mide.
4. Se vuelve a leer la configuración del asistente. Si cambió algo que la medición no fijó (alguien movió la etiqueta
   `production` de un prompt en Langfuse, o se reinició el backend con otro umbral), unos turnos se respondieron con una
   configuración y otros con otra: **la ejecución no es válida**, no se escribe ni se guarda. Repítela sin tocar nada.
5. Se escriben los CSV y **después** se envía la ejecución. Si el envío falla, los CSV ya están escritos, en la base no queda
   nada de esa ejecución y el código de salida es `1`.

Códigos de salida: `0` medida y, si se pidió, guardada; `1` medición inválida, configuración cambiada, o ejecución sin guardar
(con los CSV escritos en este último caso); `2` uso incorrecto.

### Errores

| Mensaje | Causa y qué hacer |
|---|---|
| «El backend rechazó el token (SLHD_EVAL_00001)» | `SHOPLAB_EVAL_TOKEN` no coincide con `shoplab.eval.persistence.token`, o el backend lo tiene vacío (guardado apagado) |
| «Ya hay una ejecución guardada con el identificador …» (409) | Usa otro `--run-id`; una ejecución guardada no se sobrescribe |
| «El backend no aceptó la ejecución (SLHD_EVAL_00002): …» (422) | La ejecución rompe una regla del modelo de datos; el detalle nombra el campo o el caso, nunca su texto. Suele ser un backend y una herramienta de versiones distintas |
| «El historial de evaluación no está disponible» (503) | La base del backend no responde; no se guardó nada |
| «El backend no tiene /api/eval/…» | El backend es anterior a LAB-31: despliega una versión que lo incluya, o usa `--no-save` |
| «La configuración del asistente cambió durante la medición» | Ver el paso 4; repite la medición |
| «La ejecución NO se guardó en el historial …» (código 1) | Falló el envío final; los CSV están escritos. Corrige la causa (el mensaje la da) y repite con otro `--run-id` |

### Qué hay que saber al leer el historial

- El **commit** que se guarda es el de `eval/` (el repositorio de esta herramienta), no el del backend medido; puede ser vacío si
  `git` no está disponible. Nunca se inventa.
- El **modelo del asistente** lo declara quien mide (`--assistant-model` o `SHOPLAB_EVAL_ASSISTANT_MODEL`), porque el backend no
  lo expone; si no se indica queda vacío. Un cambio de modelo en el gateway durante la medición no se detecta.
- Dos ejecuciones solo son comparables si coinciden el juez, el modelo del asistente, el de embeddings, la recuperación y el
  dataset: la consulta `06` lo dice. La variabilidad de cada ejecución es la de LAB-29: decide sobre la fila `all`, no sobre
  una categoría, y repite si la diferencia es pequeña.
- El dataset sigue en borrador hasta la revisión humana ([`REVIEW.md`](REVIEW.md)); las métricas y todo el historial heredan
  esa reserva.
- Las ejecuciones anteriores a LAB-31 (los CSV de `results/`) no se importan: el historial empieza con esta historia. No hay
  borrado ni retención todavía.

## Leer el historial (LAB-31)

Las consultas están en [`queries/`](queries/): una por pregunta, solo lectura, cada una con su cabecera (qué responde, qué
parámetros lleva y cómo ejecutarla). Se ejecutan con `psql` desde cualquier conexión de **quien consulta** (la herramienta no
tiene ninguna), pasando los parámetros con `-v`:

```bash
psql "$DATABASE" -f eval/queries/01-list-runs.sql
psql "$DATABASE" -v run_id=demo-1 -f eval/queries/03-run-summary.sql
psql "$DATABASE" -v run_a=demo-1 -v run_b=demo-2 -v metric=faithfulness -v drop=0.05 -f eval/queries/07-regressions.sql
```

| Archivo | Para qué |
|---|---|
| `01-list-runs.sql` | Las ejecuciones, la más reciente primero, con prompts, modelo y la media de la métrica principal (fidelidad en calidad, MRR en recuperación) |
| `02-run-parameters.sql` | Con qué se midió una ejecución: prompts y versiones, modelos, juez, recuperación, huella del dataset y commit |
| `03-run-summary.sql` | Media, `n` y casos vacíos de cada métrica, del conjunto completo (`all`) y por categoría. Coincide con el CSV de esa ejecución |
| `04-metric-evolution.sql` | La evolución de una métrica a lo largo de las ejecuciones |
| `05-compare-runs.sql` | Dos ejecuciones caso a caso: valor de cada una, diferencia y `comparable` |
| `06-compare-parameters.sql` | Qué parámetros difieren entre dos ejecuciones (juez, modelo, embeddings, recuperación, dataset, prompts) |
| `07-regressions.sql` | Los casos comparables que bajaron más de `drop` puntos |
| `08-find-by-prompt.sql` | Las ejecuciones que usaron una versión de un prompt |
| `09-find-by-model.sql` | Las ejecuciones medidas con un modelo del asistente |

Cómo leer `05-compare-runs.sql`:

```text
 case_id | category | value_a | value_b | delta   | comparable
---------+----------+---------+---------+---------+-----------
 G044    | returns  |  0.8000 |  0.2000 | -0.6000 | t           <- empeoró de verdad
 G012    | payments |  1.0000 |  0.9000 | -0.1000 | f           <- el caso se reescribió: no se compara
 G031    | privacy  |         |         |         | t           <- sin valor en las dos: no aplica o no se calculó
```

`comparable = f` significa que el caso cambió de contenido entre las dos ejecuciones (otra fila de `eval_case`): su diferencia
no es de calidad. `comparable` es nulo si el caso solo estaba en una de las dos. Un valor vacío es «no aplica o no se pudo
calcular por definición» y **no entra en la media**: la media del resumen la recalcula la consulta `03` ignorándolos.

Una prueba del backend (`EvalHistoryQueriesTests`) ejecuta las nueve consultas contra PostgreSQL con tres ejecuciones de
ejemplo, para que no se pudran.
