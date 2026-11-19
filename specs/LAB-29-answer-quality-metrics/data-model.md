# Modelo de datos: Métricas de calidad de respuesta

No hay base de datos nueva, migraciones ni cambios en el backend. Los tipos son los de la medición en `eval/` (Python,
`dataclass(frozen=True)`). Formato de los CSV en [contracts/quality-csv.md](contracts/quality-csv.md); lo que `eval/` consume de
fuera (chat, gateway, Langfuse) en [contracts/external-interfaces.md](contracts/external-interfaces.md).

## Entrada

Se reutiliza `GoldenCase` de LAB-27 (`id`, `question`, `reference_answer`, `expected_documents`, `category`). Se añade a
`CorpusDocument` el campo `title` (del front matter de `data/kb`) para resolver el id de documento desde el título que trae el
evento `sources`.

## Tipos de la medición (`shoplab_eval`)

| Tipo | Campos | Reglas |
|---|---|---|
| `CaseKind` | enum `ANSWERED`, `UNANSWERED`, `CONVERSATION` | Se deduce del caso: `CONVERSATION` si `GoldenCase.is_conversation`; si no, `ANSWERED` con `expected_documents` y `UNANSWERED` sin ellos |
| `ContextItem` | `number: int`, `title: str`, `section: str`, `text: str`, `document_id: str \| None` | Un fragmento del evento `sources`, en orden; `document_id` sale del título (`None` si no está en el corpus) |
| `ChatTurn` | `request_id: str`, `answer: str`, `context: tuple[ContextItem, ...]` | Solo existe si el flujo terminó con `done`. `context` vacío si no hubo evento `sources`. La respuesta y los textos **no** se escriben en el CSV |
| `JudgeSettings` | `model: str`, `base_url: str`, `temperature: float`, `timeout: float`, `max_input_chars: int`, `concurrency: int` | La clave no forma parte: sale del entorno y nunca se guarda ni se imprime |
| `CaseScores` | `faithfulness: float \| None`, `answer_relevancy: float \| None`, `context_precision: float \| None`, `abstained: bool` | `None` = no aplica o vacío por definición (celda vacía); un valor, entre 0 y 1; nunca `NaN` |
| `QualityCaseResult` | `case: GoldenCase`, `kind: CaseKind`, `turn: ChatTurn`, `scores: CaseScores`, `trace_id: str`, `langfuse: LangfuseStatus` | Un resultado por caso de conocimiento medido, en el orden del dataset |
| `LangfuseStatus` | enum `ATTACHED`, `NOT_ATTACHED`, `SKIPPED` | `SKIPPED` si no se pidió adjuntar; `NOT_ATTACHED` si se pidió y falló |
| `MetricStat` | `mean: float \| None`, `entered: int`, `empty: int` | `entered` = casos con valor en la media; `empty` = casos donde la métrica aplica pero quedó vacía; `mean` es `None` si `entered = 0` |
| `QualityScopeSummary` | `scope: str`, `cases: int`, `excluded: int`, `faithfulness: MetricStat`, `answer_relevancy: MetricStat`, `context_precision: MetricStat`, `abstained: int` | `scope` es `all` o una categoría con casos medidos; `excluded` son los casos de conversación del ámbito |
| `RunInfo` | `run_id: str`, `judge: JudgeSettings`, `judge_underlying_model: str`, `ragas_version: str`, `abstention_prompt_version: str`, `assistant_model: str`, `embedding_model: str`, `langfuse: str` | Parámetros que hacen comparable el resultado (FR-018); `judge_underlying_model`, `assistant_model` y `embedding_model` valen `unknown` si no se pudieron obtener; `judge.model` es el nombre lógico pedido y `judge_underlying_model` el modelo real detrás de él |
| `QualityReport` | `info: RunInfo`, `results: tuple[QualityCaseResult, ...]`, `summaries: tuple[QualityScopeSummary, ...]`, `excluded: int` | Se construye solo con la medición completa; es lo único que escribe el CSV |

## Reglas

Con `A` el conjunto de casos `ANSWERED`, `U` el de `UNANSWERED` y `C` el de `CONVERSATION`:

| Regla | Detalle |
|---|---|
| Aplicabilidad | `ANSWERED`: las tres métricas. `UNANSWERED`: solo `faithfulness`. `CONVERSATION`: ninguna y no se consulta al asistente |
| Abstención | `abstained` lo decide la comprobación del juez; si es `true`, `faithfulness` es `None` y no se llama a la métrica |
| Contexto vacío en `ANSWERED` | `context_precision = 0.0` sin llamar al juez; `answer_relevancy` se calcula igualmente |
| Respuesta sin contexto y sin abstención | `faithfulness = 0.0` sin llamar al juez (ninguna afirmación puede estar respaldada); con abstención, `faithfulness` es `None` |
| Respuesta sin contenido | Una respuesta solo de marcadores de cita (`[2]`) y puntuación no afirma nada y no contesta nada: `faithfulness` vacía, `answer_relevancy = 0.0` (solo en casos con respuesta), `abstained = false`, y la precisión del contexto se calcula igual. No se llama al juez para la abstención, la fidelidad ni la relevancia. Se vio en la medición real (G077: `[2]` puntuaba fidelidad 1,00) |
| Redondeo | Cada puntuación se redondea a los 4 decimales del CSV al calcularla: el CSV, las medias del resumen y los scores de Langfuse manejan exactamente los mismos números (si no, una hoja de cálculo y el resumen discrepaban en el cuarto decimal) |
| Abstención | El juez primero **lista** los datos concretos que da por ciertos la respuesta y después decide; si lista alguno, no es abstención aunque marque `abstains` (versión 2 del prompt). La versión 1 tomaba por abstención una respuesta parcial |
| Dónde se aplican | Las reglas de abstención y de contexto vacío son de la orquestación (`quality_run.py`); el adaptador de RAGAS solo llama a las métricas y convierte `NaN` en `None` |
| Sin afirmaciones | `Faithfulness` devuelve `NaN` → `None` |
| Media | `mean` = media aritmética de los valores no `None` del ámbito; las celdas vacías no entran y se cuentan en `empty` |
| Ámbitos | `all` y una fila por categoría con casos medidos (`off_topic` solo tiene `UNANSWERED`: solo fidelidad) |
| Excluidos | `excluded = |C|`; se informan en la fila `all` y por categoría de conversación |
| Rango | todo valor, entre 0 y 1; un valor fuera de rango del juez es `MeasurementError`, no se recorta |
| Fallo del juez | sin respuesta, salida ilegible o entrada mayor que `max_input_chars` → `MeasurementError` con caso y métrica |
| Fallo del asistente | HTTP no 200, evento `error` o flujo sin `done` → `MeasurementError` con el caso y el código |

## Identidades derivadas

| Dato | Cálculo |
|---|---|
| `request_id` | `eval-<run_id>-<id del caso>` (≤ 64 caracteres; `run_id` de hasta 32 de `[A-Za-z0-9._-]`) |
| `trace_id` | `SHA-256(request_id)` en hexadecimal, primeros 32 caracteres (ADR-0022) |
| `score_id` | `SHA-256("<run_id>:<id del caso>:<métrica>")` en hexadecimal, primeros 32 caracteres |
| `run_id` por defecto | marca de tiempo UTC `YYYYMMDDTHHMMSSZ` |

## Transiciones de una ejecución

```text
validar configuración → cargar dataset (LAB-27) → comprobar backend, juez y Langfuse → comprobar índice
  → turno sonda (primer caso) → ¿existe su traza?  no → error (nada más consultado)
  → turnos restantes → juez (abstención → métricas) → adjuntar a Langfuse → escribir CSV → código de salida
```

Cualquier error antes de «escribir CSV» termina sin archivos; el único estado intermedio que conserva el CSV es «alguna
puntuación no se adjuntó», con código de salida 1.
