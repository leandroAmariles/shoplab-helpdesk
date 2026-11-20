# Contrato: evaluar y comparar versiones de un prompt (fase B)

Extiende el comando `quality` de [LAB-29](../../LAB-29-answer-quality-metrics/contracts/quality-csv.md) (ya fusionada en `main`).
Mismas convenciones de CSV: UTF-8 sin BOM, coma, cuatro decimales, celda vacía = no aplica.

## `quality --prompt`

```bash
cd eval
uv run python -m shoplab_eval quality --prompt chat-system=3 --run-id prompt-a --out results/prompt-a
uv run python -m shoplab_eval quality --prompt chat-system=staging --prompt chat-context=2 --run-id prompt-b --out results/prompt-b
```

| Opción | Por defecto | Qué hace |
|---|---|---|
| `--prompt NOMBRE=VALOR` (repetible) | ninguna | Fija el prompt `NOMBRE` en la versión `VALOR` (entero) o en la versión a la que apunta la etiqueta `VALOR` |

Variables de entorno: `SHOPLAB_EVAL_PROMPT_TOKEN` (el `override-token` del backend). Para resolver etiquetas y comprobar
versiones se usan las mismas claves de Langfuse de LAB-29 (`SHOPLAB_EVAL_LANGFUSE_*`).

**Antes de consultar al asistente** (y sin escribir nada si falla):
1. se valida el nombre y el formato de cada `--prompt` y que no se repita un nombre;
2. se resuelve cada etiqueta a su versión y se comprueba que cada versión existe y es de tipo texto;
3. con Langfuse caído o sin claves, **error** (no se mide con valores por defecto: FR-023).

Código de salida: `2` por uso incorrecto (paso 1), `1` si Langfuse no responde o una versión no existe (pasos 2 y 3, nombrando
el prompt y la versión), `0` si todo bien.

El comando envía `X-Prompt-Versions` con las versiones **ya resueltas** y `X-Prompt-Token` en cada petición al chat; un `403` del
backend (token ausente o incorrecto, o capacidad apagada) detiene la medición con un mensaje que lo explica.

## Cambio en `quality-summary.csv`

Una columna **añadida al final**:

| Columna | Significado |
|---|---|
| `prompt_versions` | Versiones con las que se midió, `nombre=versión` separadas por `|` y en orden alfabético (`chat-context=2|chat-system=3`); `production` si no se fijó ninguna |

Sin `--prompt`, la columna es `production` (no se resuelve la versión: el valor refleja que se midió con lo que servía `production`
en ese momento; para reproducir una medición se recomienda fijar versiones).

## `compare`

```bash
uv run python -m shoplab_eval compare results/prompt-a/quality-summary.csv results/prompt-b/quality-summary.csv
uv run python -m shoplab_eval compare A.csv B.csv --out results/comparacion.csv
```

Lee el ámbito `all` de cada resumen y escribe en pantalla (y en `--out`, si se da) una tabla:

| Columna | Significado |
|---|---|
| `metric` | `faithfulness`, `answer_relevancy`, `context_precision` |
| `a_mean`, `a_n` | media y casos del resultado A |
| `b_mean`, `b_n` | media y casos del resultado B |
| `delta` | `b_mean − a_mean` con cuatro decimales; vacío si falta una media |

Debajo de la tabla, **«Prompts que difieren»** (nombres cuyo valor distinto separa A de B, o `ninguno`) y, si procede,
**«Advertencias»**: una línea por cada parámetro de comparabilidad distinto —`judge_model`, `judge_underlying_model`,
`judge_temperature`, `assistant_model`, `embedding_model`, o `cases` del ámbito `all`— con los dos valores. Con advertencias, el
código de salida es `0` (la tabla se muestra igual, pero precedida de «No comparables: la diferencia puede no deberse al prompt»).
Un resumen ilegible o sin la fila `all` termina con `2`.

`compare` no consulta ningún modelo, ni a Langfuse ni al backend: solo lee dos archivos.
