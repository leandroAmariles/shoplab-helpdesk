# Quickstart: medir la calidad de las respuestas

Guía para comprobar de principio a fin que LAB-29 funciona. Formato de los archivos en
[contracts/quality-csv.md](contracts/quality-csv.md); interfaces que consume en
[contracts/external-interfaces.md](contracts/external-interfaces.md); tipos en [data-model.md](data-model.md).

## Requisitos

- Python 3.12 o superior y [`uv`](https://docs.astral.sh/uv/).
- Para los pasos 3 a 8: el stack local levantado (`docker compose -f infra/docker-compose.yml up -d --wait`: backend,
  PostgreSQL, Ollama, gateway), los 24 documentos de `data/kb` **indexados**, y las variables `LITELLM_KEY_EVAL` y
  `SHOPLAB_EVAL_JUDGE_API_KEY` (los valores locales están en `infra/.env.example`). Define también
  `SHOPLAB_EVAL_ASSISTANT_MODEL` con el modelo de chat del gateway (`LITELLM_CHAT_PRIMARY_MODEL`, o `OLLAMA_CHAT_MODEL`;
  por defecto `llama3.2:3b`): el backend no lo expone y sin él el resultado registra `unknown`.
- Para adjuntar a Langfuse (pasos 6 y 7): perfil `llmops` (`--profile llmops`), el backend con
  `SHOPLAB_CHAT_TRACING_ENABLED=true` y las claves de Langfuse en `SHOPLAB_EVAL_LANGFUSE_*`.
- Los pasos 1 y 2 no necesitan Docker, modelos ni red.

## 1. Pruebas de `eval/`

```bash
cd eval
uv sync --frozen
uv run pytest
uv run ruff check .
```

**Resultado esperado**: en verde con cobertura ≥ 80 %. Cubre el reparto de métricas por tipo de caso, los vacíos por definición,
las medias, el cliente del chat y de Langfuse contra un servidor local, la orquestación con dobles y la coherencia
resumen-detalle. No usa modelos.

## 2. Verificación del gateway local (juez y clave `eval`)

```bash
docker compose -f infra/docker-compose.yml up -d litellm
docker compose -f infra/docker-compose.yml run --rm litellm-bootstrap      # created alias=eval-local
curl -s -H "Authorization: Bearer $SHOPLAB_EVAL_JUDGE_API_KEY" http://localhost:4000/v1/models | jq '.data[].id'
```

**Resultado esperado**: `judge` y `embeddings`, y ninguno más (la clave `eval` no puede llamar a `chat`).

## 3. Verificaciones previas con modelos reales (S-1, S-2, S-5)

```bash
cd eval
uv run pytest -m judge
```

**Resultado esperado**: pasan los pares fijados a mano contra el juez real: en cada uno de los cinco pares, la respuesta con un
dato inventado puntúa menos fidelidad que la fiel (SC-002), y la respuesta que contesta otra cosa puntúa menos relevancia, y el
contexto desordenado menos precisión (SC-003). Si falla, el juez por defecto no discrimina: ver «Si una verificación falla» en
[research.md](research.md).

## 4. Medición completa sin Langfuse

```bash
uv run python -m shoplab_eval quality --no-langfuse
```

**Resultado esperado**: avance `[n/96]` por caso en tres fases (turnos, juez, cierre); al terminar, un resumen legible en
pantalla y dos archivos en `eval/results/`: `quality-summary.csv` y `quality-detail.csv`; código de salida 0. El detalle tiene 96
filas; el resumen, `all` más las categorías de conocimiento. Los 24 casos de conversación aparecen como excluidos.

## 5. Coherencia resumen-detalle (SC-004)

Abrir los dos CSV en una hoja de cálculo (importar como UTF-8) y comprobar que la media de cada columna del detalle, ignorando
las celdas vacías, coincide con la media del resumen, y que `*_n` coincide con el número de celdas con valor.

## 6. Adjuntar a Langfuse (SC-005)

```bash
uv run python -m shoplab_eval quality --run-id demo-1
```

**Resultado esperado**: `langfuse = attached` en el resumen y `yes` en cada fila del detalle. En
`http://localhost:3001`, en las trazas, filtrar por el metadato `request_id` que empiece por `eval-demo-1-`: hay una traza por
caso con sus puntuaciones (`faithfulness`, `answer_relevancy`, `context_precision`) con los valores del CSV. Repetir el comando con
el mismo `--run-id` no duplica ninguna puntuación.

## 7. Otra ejecución no pisa la anterior

```bash
uv run python -m shoplab_eval quality --run-id demo-2
```

**Resultado esperado**: nuevas trazas `eval-demo-2-…` con sus puntuaciones; las de `demo-1` siguen intactas.

## 8. Cambiar el juez solo por configuración (SC-006)

```bash
# El juez es otro modelo local: se cambia en el gateway, sin editar código
LITELLM_JUDGE_MODEL=<otro-modelo-de-ollama> docker compose -f infra/docker-compose.yml up -d litellm
uv run python -m shoplab_eval quality --no-langfuse --out results/otro-juez
```

**Resultado esperado**: el nuevo `quality-summary.csv` indica un `judge_underlying_model` distinto del de la ejecución del paso
4, con el mismo `judge_model = judge`. El modelo debe estar descargado en Ollama. Pedir un nombre que la clave `eval` no puede
llamar (`--judge-model chat`) debe fallar con un error que nombra el modelo, antes de consultar al asistente.

## 9. Errores (SC-007)

| Qué se provoca | Qué debe ocurrir |
|---|---|
| Apagar el backend | Error «No se pudo conectar con el backend en …», código 1, sin CSV, sin llamadas al juez |
| Apagar el gateway, o clave inválida | Error que nombra el gateway (o la clave) **antes** de consultar al asistente, código 1, sin CSV |
| Trazas del chat apagadas con `--langfuse` | Error tras la consulta sonda: «La traza del primer turno no apareció en Langfuse», código 1, sin CSV |
| Dataset inválido | Los `G-…` de LAB-27, código 1, sin CSV |
| `--concurrency 0` o `--judge-temperature -1` | Mensaje con el valor aceptado, código 2, antes de consultar nada |
| Parar Langfuse a mitad del cierre | CSV completo con `langfuse = partial` y los casos no adjuntados listados, código 1 |

## 10. Una respuesta peor se nota (SC-008)

Con el backend en `SHOPLAB_CHAT_NO_CONTEXT_MODE=model` y un prompt de sistema que permita inventar (en una rama local),
repetir el paso 4 con otro `--out`: la fidelidad media baja de forma visible respecto a la medición anterior, y el detalle
muestra qué casos empeoraron.

## 11. Ruido del juez (SC-009)

Repetir el paso 4 dos veces sin cambiar nada y comparar las medias de cada métrica: la diferencia debe ser ≤ 0,05. El valor
observado se anota en `validation.md`.

## 12. DEV con `make up` (en `../shoplab-infra`)

DEV es el entorno local del equipo: tras `make up` debe funcionar **todo lo implementado** (backend, frontend, ingest, LiteLLM,
Langfuse, observabilidad) y la medición, sin pasos manuales. Ollama es lo único fuera del clúster: corre en el Docker del host.
Requisitos: Docker Desktop en marcha (≥ 16 GB de RAM y 4 CPU), las PR de este repo y de `shoplab-infra` fusionadas (Argo CD
sincroniza desde `main`), `sync-litellm-files.sh` ejecutado y Ollama del host arriba con `llama3.2:3b`, `llama3.2:1b`, `bge-m3` y el juez `qwen2.5:7b` (defínelo con `OLLAMA_JUDGE_MODEL=qwen2.5:7b` en `infra/.env`
para que Ollama lo descargue)
(`docker compose -f infra/docker-compose.yml -f infra/docker-compose.ollama-host.yml up -d ollama` en este repo; con GPU, añade
`-f infra/docker-compose.gpu.yml`).

```bash
cd ../shoplab-infra
make secrets     # idempotente: la primera vez añade a secrets.auto.tfvars lo que falte (solo nombres en pantalla)
make up          # Floci, Terraform, Argo CD y los túneles (ingress, Grafana…, LiteLLM:4000, Langfuse:3001)
make seed        # carga data/kb en la base de conocimiento de DEV
make quality     # mide contra DEV; admite las opciones de `quality` (p. ej. make quality ARGS="--run-id dev-1")
```

**Resultado esperado**:
- Argo CD con todas las aplicaciones `Synced` y `Healthy`, y todos los pods de `shoplab-dev` y `monitoring` en `Running` y `Ready`
  (`kubectl --kubeconfig terraform/envs/dev/kubeconfig.yaml get pods -A`); un chat en `http://shoplab.localtest.me:8080` responde
  con citas y deja su traza en Langfuse.
- `make secrets` por segunda vez no añade nada y no cambia ningún valor; `postgres-password` conserva su valor.
- `kubectl --kubeconfig terraform/envs/dev/kubeconfig.yaml -n shoplab-dev get job litellm-bootstrap-keys` está `Complete` y su
  registro dice `created alias=eval-dev` (o `unchanged`). Argo CD muestra `shoplab-dev` en `Synced` y `Healthy`.
- El resumen de `make forward` marca LiteLLM y Langfuse como accesibles.
- `make quality` termina con código 0, escribe los CSV en `shoplab-helpdesk/eval/results/` con `langfuse = attached` y
  `assistant_model`, `judge_underlying_model` y `embedding_model` informados, y las trazas aparecen en
  `http://localhost:3001` con el entorno `dev` y las puntuaciones del CSV.
- Sin red a Internet también funciona: el ingress se alcanza por `127.0.0.1` con la cabecera `Host` (`--host-header`).
