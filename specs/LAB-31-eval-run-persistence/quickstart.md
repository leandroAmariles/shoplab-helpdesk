# Quickstart: validar la persistencia de las ejecuciones de evaluación (LAB-31)

Guía para comprobar de punta a punta que la historia funciona. No contiene código de implementación: las rutas y los datos
están en [contracts/eval-runs-api.md](contracts/eval-runs-api.md) y [data-model.md](data-model.md).

## Requisitos

- Stack local arriba (`make up` o compose) con la base de conocimiento indexada (los mismos requisitos de LAB-28/29), el
  gateway con el juez local y, para ver el vínculo con las trazas, Langfuse y `shoplab.chat.tracing.enabled`.
- El backend arrancado con `SHOPLAB_EVAL_PERSISTENCE_TOKEN=<token de prueba>`; en el entorno de quien mide,
  `SHOPLAB_EVAL_TOKEN=<el mismo valor>`.
- `cd eval && uv sync --frozen`.

## Escenarios

| # | Qué se hace | Resultado esperado | Spec |
|---|---|---|---|
| V1 | `uv run python -m shoplab_eval quality --save --run-id v1` | Código 0, el resumen dice «guardada en la base» y existe una fila `eval_run` y un `eval_result` por cada caso medido (96) | HU1·1, SC-001 |
| V2 | `uv run python -m shoplab_eval retrieval --save --run-id v2` | Otra ejecución de tipo `RETRIEVAL`, con `hit_at_k`, `recall_at_k` y `reciprocal_rank` por caso | HU1·2 |
| V3 | Recalcular en SQL la media de `faithfulness` de `v1` ignorando `null`, global y por categoría | Coincide con `quality-summary.csv` a 4 decimales y el `n` coincide | HU1·6, SC-002 |
| V4 | Tres casos al azar (uno con tres métricas, uno `unanswered`, uno con métrica vacía) contra `quality-detail.csv` | Valores idénticos; los vacíos son `null`, no `0` | HU1·3-4, SC-003 |
| V5 | Consultar la ejecución `v1` | Se leen prompts con **versión numérica**, modelo del asistente, juez, embeddings y `retrieval_config` | HU2·1,3, SC-004 |
| V6 | `quality --save --prompt chat-system=<otra versión> --assistant-model <otro> --run-id v6` | `v6` marca ese prompt como `pinned`, y una consulta por prompt y versión devuelve `v6` y no `v1` | HU2·2, FR-016 |
| V7 | Comparar `v1` y `v6` con la consulta documentada | Diferencia por caso y lista de casos que empeoraron; avisa de los parámetros distintos | HU3·2-3, SC-007 |
| V8 | Editar la respuesta de referencia de un caso, repetir con otro `--run-id` y comparar | Ese caso aparece como **no comparable**; hay dos filas `eval_case` con el mismo `case_id` | HU3·4 |
| V9 | `quality --run-id v1 --save` otra vez | Error antes de medir («identificador ya guardado»), código distinto de 0, sin filas nuevas | HU4·5 |
| V10 | Parar el backend y `quality --save` | Error que nombra el backend, antes de consultar al asistente o al juez; sin CSV | HU4·2 |
| V11 | Medición que falla a mitad (apagar el gateway) | Sin CSV y sin filas nuevas en `eval_run` ni `eval_result` | HU4·3, SC-005 |
| V12 | Mover la etiqueta `production` de un prompt en Langfuse durante una medición | «Medición inválida: la configuración del asistente cambió»; sin CSV ni filas | R6 |
| V13 | Sin `SHOPLAB_EVAL_TOKEN` ni `--save` | Mismos CSV y mismo código de salida que antes de la historia; dice «no se guardó nada» | HU4·1, SC-006 |
| V14 | `--save` sin token en el entorno | Error de uso (código 2), antes de medir | FR-010 |
| V15 | Backend sin `SHOPLAB_EVAL_PERSISTENCE_TOKEN` | 403 en `POST /api/eval/runs`; nada en la base | R7 |
| V16 | Buscar en todas las columnas de las tres tablas un fragmento del texto de una respuesta del asistente | Ninguna fila lo contiene | FR-009, SC-008 |
| V17 | En `shoplab-infra`: `make up` y después `make quality` | `make up` deja el Secret `backend-eval` creado con un valor generado (sin imprimirlo) y `make quality` guarda la ejecución sin pasos extra; con `ARGS="--no-save"` no guarda. **Requiere un release que incluya el módulo `eval`** (ADR-0012: desarrollo solo despliega versiones) | FR-021 |
| V18 | En `shoplab-infra`: `make up` con un `secrets.auto.tfvars` que ya tenía `postgres-password` y `llm-api-key` | Añade la entrada `eval` sin cambiar ningún valor existente ni imprimirlo; repetirlo no cambia nada | R10 |
| V19 | Borrar el Secret `backend-eval` y reiniciar el backend del clúster | Arranca igual; `POST /api/eval/runs` responde 403 y `make quality` avisa y mide sin guardar | R10 |

## Verificación automática (sin red ni modelo)

- `cd backend && ./mvnw verify`: migraciones y aislamiento del esquema `eval`, controlador, caso de uso, adaptador JDBC contra
  PostgreSQL real, reglas de arquitectura y `docs/modulos/` al día (hay que commitear lo que regenere `ModularityTests`).
- `cd eval && uv run pytest && uv run ruff check .`: construcción de la carga, huella del dataset, cliente HTTP contra el
  backend simulado y orden de las operaciones (preflight antes de medir, guardado después de escribir los CSV).

## Si algo falla

- 403 en todo: el token del backend y el del entorno no coinciden o el backend arrancó sin él.
- «El esquema no existe» / 503: el backend no migró; se ve en su registro de arranque, que nombra el módulo.
- «La configuración del asistente cambió»: alguien movió una etiqueta de prompt o reinició el backend durante la medición;
  se repite.
