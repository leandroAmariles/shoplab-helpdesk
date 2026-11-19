# Contrato: CSV de calidad de respuesta y comando

Mismas convenciones que [LAB-28](../../LAB-28-retrieval-metrics/contracts/retrieval-csv.md): UTF-8 sin BOM, cabecera, `\n`,
coma como separador, comillas dobles estándar, punto decimal con **cuatro decimales**, listas de ids separadas por `|`. Una celda
**vacía** significa «no aplica» o «no se pudo calcular por definición»; nunca se escribe `NaN` ni `0` en su lugar. Los archivos
no contienen el texto de respuestas ni de fragmentos (FR-019).

A diferencia de LAB-28, **no son idénticos entre ejecuciones** (el asistente y el juez son modelos): para comparar dos, se
miran los parámetros de comparabilidad del resumen.

## Comando

```bash
cd eval
uv run python -m shoplab_eval quality
uv run python -m shoplab_eval quality --judge-model judge-grande --concurrency 4 --out results/antes
```

| Opción | Por defecto | Qué hace |
|---|---|---|
| `--base-url` | `$SHOPLAB_EVAL_BASE_URL` o `http://localhost:8080` | Backend del chat y de la búsqueda |
| `--host-header` | `$SHOPLAB_EVAL_HOST_HEADER` o ninguna | Valor de la cabecera `Host` de las peticiones al backend. En DEV el ingress enruta por ella (`shoplab.localtest.me`), y así se entra por `127.0.0.1` sin depender del DNS. También lo admite `retrieval` |
| `--judge-model` | `$SHOPLAB_EVAL_JUDGE_MODEL` o `judge` | Nombre lógico del modelo juez en el gateway |
| `--judge-base-url` | `$SHOPLAB_EVAL_JUDGE_BASE_URL` o `http://localhost:4000/v1` | Gateway (API compatible con OpenAI) |
| `--judge-temperature` | `0` | Temperatura del juez (0 a 2) |
| `--judge-timeout` | `120` | Segundos máximos por llamada al juez |
| `--max-input-chars` | `24000` | Tope de caracteres de lo que se envía al juez por caso |
| `--concurrency` | `2` | Llamadas simultáneas al juez (1 a 16) |
| `--turn-timeout` | `120` | Segundos máximos por turno del asistente |
| `--langfuse` / `--no-langfuse` | según el entorno | Adjuntar las puntuaciones a las trazas. Sin ninguna de las dos, se adjunta si hay claves en el entorno |
| `--run-id` | marca de tiempo UTC | Identificador de la ejecución (`[A-Za-z0-9._-]`, 1 a 32) |
| `--assistant-model` | `$SHOPLAB_EVAL_ASSISTANT_MODEL`, o el de la traza si se adjunta a Langfuse, o `unknown` | Modelo del asistente que se registra. El backend no lo expone: indícalo en toda ejecución que vaya a compararse (si queda `unknown` se avisa por pantalla) |
| `--out` | `eval/results/` | Carpeta de salida (se crea si falta) |
| `--golden`, `--kb` | los de `validate` | Dataset y corpus |

Variables de entorno: `SHOPLAB_EVAL_JUDGE_API_KEY` (clave virtual `eval`), `SHOPLAB_EVAL_LANGFUSE_HOST` (por defecto
`http://localhost:3001`), `SHOPLAB_EVAL_LANGFUSE_PUBLIC_KEY`, `SHOPLAB_EVAL_LANGFUSE_SECRET_KEY`.

**Código de salida**: `0` medición completa, CSV escritos y, si se pidió, puntuaciones adjuntadas; `1` la medición no es válida
(no se escribe ningún CSV ni se tocan los de una ejecución anterior) **o** alguna puntuación no se adjuntó (el CSV sí se
escribe, y el mensaje lista los casos); `2` uso incorrecto (configuración inválida, antes de consultar nada).

## `quality-summary.csv`

Una fila `all` y una por cada categoría con casos medidos.

| Columna | Significado |
|---|---|
| `scope` | `all` o la categoría |
| `cases` | Casos de conocimiento medidos en el ámbito |
| `excluded_cases` | Casos de conversación del ámbito, no medidos |
| `faithfulness_mean`, `faithfulness_n`, `faithfulness_empty` | Media de fidelidad; casos que entraron en ella; casos donde aplicaba pero quedó vacía |
| `answer_relevancy_mean`, `answer_relevancy_n`, `answer_relevancy_empty` | Ídem para relevancia de la respuesta |
| `context_precision_mean`, `context_precision_n`, `context_precision_empty` | Ídem para precisión del contexto |
| `abstained` | Casos del ámbito en los que el asistente se limitó a decir que no tenía la información |
| `run_id` | Identificador de la ejecución |
| `judge_model`, `judge_underlying_model`, `judge_temperature` | Con qué juez y qué ajuste se puntuó: el nombre lógico pedido al gateway, el modelo real detrás de él (`unknown` si no se pudo leer) y la temperatura. Dos resultados solo son comparables si coinciden los tres |
| `ragas_version`, `abstention_prompt_version` | Versión de la biblioteca y de la comprobación de abstención |
| `assistant_model`, `embedding_model` | Modelos con los que se midió (`unknown` si no se supo) |
| `langfuse` | `attached`, `partial` (alguna no se adjuntó) o `skipped` |

Una media vacía (`faithfulness_mean` sin valor) significa `faithfulness_n = 0`: no hubo casos con valor, por ejemplo porque el
asistente se abstuvo en todos los casos sin respuesta (mirar `abstained`).

## `quality-detail.csv`

Una fila por caso de conocimiento medido, en el orden del dataset. Es de donde sale cualquier número del resumen.

| Columna | Significado |
|---|---|
| `id`, `category`, `question` | El caso |
| `kind` | `answered` o `unanswered` |
| `faithfulness`, `answer_relevancy`, `context_precision` | Valores del caso; vacío si no aplica o quedó vacío por definición |
| `abstained` | `true` o `false` |
| `context_documents` | Ids de documento de los fragmentos entregados al modelo, **uno por fragmento y en orden** (se repite si dos fragmentos son del mismo documento); `?<título>` si no es del corpus; vacío si no hubo contexto |
| `context_fragments` | Número de fragmentos entregados |
| `trace_id` | Id de la traza en Langfuse (existe aunque no se haya adjuntado) |
| `langfuse` | `yes`, `no` o `skipped` |

## Reglas de coherencia (las prueba `test_quality_report.py`)

- `<métrica>_mean` del resumen = media de las celdas no vacías de esa columna del detalle en el mismo ámbito; `_n` = número de
  esas celdas; `_empty` = casos del ámbito donde la métrica aplica y la celda está vacía.
- `faithfulness` solo es vacía en un caso `answered` o `unanswered` con `abstained = true` o sin afirmaciones; `answer_relevancy`
  y `context_precision` son siempre vacías en un caso `unanswered`.
- `context_precision` es `0.0000` (no vacía) si `context_fragments = 0` en un caso `answered`.
- `faithfulness` vacía con `abstained = false` indica una **respuesta sin contenido** (solo marcadores de cita): no afirma nada
  que comprobar y su `answer_relevancy` es `0.0000` si el caso tiene respuesta.
- Todos los valores están redondeados a cuatro decimales **antes** de promediar: la media de las celdas de una columna del
  detalle (ignorando las vacías) coincide con la del resumen hasta el cuarto decimal sin discrepancias de redondeo.
- `faithfulness` es `0.0000` (no vacía) si `context_fragments = 0` y `abstained = false`: la respuesta afirma algo y no hubo
  contexto que lo respalde.
