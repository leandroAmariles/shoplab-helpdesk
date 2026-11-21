# Validación: Persistencia de las ejecuciones de evaluación (LAB-31)

Registro de lo que se comprobó, cómo, y de lo que **queda pendiente**. Cada escenario es el de [quickstart.md](quickstart.md). Nada
de aquí contiene secretos: los tokens se citan como `<token>`.

**ADR**: 0028 (no existe ningún 0026 en `main` ni en ninguna rama remota, y la plantilla dice que los números no se reutilizan;
se toma el siguiente tras el 0027).

**Orden de fusión entre repositorios**: primero el PR de `shoplab-infra` (rama `feature/LAB-31-eval-token`, en el árbol de trabajo
aparte `../shoplab-infra-lab31`), después el de `shoplab-helpdesk`. **Ninguno de los dos está commiteado ni abierto todavía.**

## Pruebas automáticas (2026-11-20)

| Qué | Comando | Resultado |
|---|---|---|
| Herramienta `eval/` | `cd eval && uv run pytest && uv run ruff check .` | **639 pasan** (546 anteriores sin modificar + 93 nuevas), cobertura 97,64 % (umbral 80 %), ruff limpio |
| Backend, módulo `eval` y lo que toca | `./mvnw test -Dtest='Eval*,…'` (ver `tasks.md`) | **251 pasan** (esquema, validador, hash, caso de uso, adaptador JDBC con PostgreSQL real incluida la carrera de dos guardados, controladores, API completa, privacidad, consultas SQL, reglas de arquitectura y Modulith) |
| Backend completo | `cd backend && ./mvnw -B -ntp clean verify "-Dtest=!PromptDefaultsTests,!PromptInjectionTests"` | **1398 pasan**, Checkstyle 0 violaciones, JaCoCo («All coverage checks have been met»). PMD marcó un parámetro sin usar en mi adaptador; corregido y revalidado con `pmd:check` + `checkstyle:check` (limpios) y las pruebas del adaptador, la API y las consultas (47 pasan). **No se repitió el `verify` completo después de ese último arreglo** |
| Backend: **2 pruebas excluidas** | `PromptDefaultsTests`, `PromptInjectionTests` (módulo `chat`, no tocado) | Fallan **igual en un `main` limpio** de esta máquina Windows: `core.autocrlf=true` deja `prompts/*.txt` con CRLF en el árbol de trabajo y comparan bytes (hash de `chat-system`). En CI (Linux) pasan. No son de LAB-31 |
| Infra: script de secretos | `bash scripts/tests/ensure-secrets.test.sh` | **44 comprobaciones ok** (incluye las 10 nuevas de `eval`: añade, no cambia un valor existente, no imprime, TF_VAR_secrets avisa sin fallar) |
| Infra: Kustomize | `kustomize build k8s/overlays/dev` | exit 0; `backend-eval` en el `envFrom` del backend con `optional: true`; la clave se renderiza `/shoplab/dev/eval` y no queda ningún `ENVIRONMENT` sin sustituir |
| Infra: otros | `terraform fmt -check -recursive`, `scripts/test.sh checks`, `dev-deploy-only.test.sh`, `tflint` | ok |
| Infra: **no ejecutado** | `kubeconform` y `conftest` (`make kustomize` completo, `make policies`) | **no están instalados en esta máquina**: los correrá CI en el PR de infra |

## Validación de punta a punta con el backend real (2026-11-20)

Se levantó el backend real (`mvnw spring-boot:run`, puerto 18081, `SHOPLAB_EVAL_PERSISTENCE_TOKEN=<token>`) contra un
PostgreSQL 16 con pgvector de Docker, y la herramienta de `eval/` le envió, con su propio cliente, una ejecución de calidad y una
de recuperación **construidas con el dataset golden real** (96 casos de conocimiento; los valores de las puntuaciones son
inventados porque no hay modelo, la forma es la real). Después se apagó todo.

| Qué se comprobó | Resultado |
|---|---|
| `GET /api/eval/settings` con y sin token | 200 con `top_k 4`, umbral `0.6`, `8000` y los 4 prompts (`source: DEFAULT`, `version: null`: Langfuse apagado); **403** sin token |
| Carga de calidad (96 resultados, 59 508 bytes, 24 excluidos, `commit_sha` real de `eval/`) | **201**, `results = 96`, `new_case_revisions = 96` |
| Misma ejecución otra vez | `StoreError` «Ya hay una ejecución guardada con el identificador 'e2e-q1' (SLHD_EVAL_00003)»; la base no cambió |
| Carga de recuperación (76 resultados, k = 1, 3, 5) | **201**, `new_case_revisions = 0`: reutiliza las 76 revisiones de caso de la ejecución de calidad |
| Token distinto | `StoreError` «El backend rechazó el token (SLHD_EVAL_00001)…» y el token no aparece en el mensaje |
| **Paridad con el CSV** (consulta `03-run-summary.sql` ejecutada con `psql` en el contenedor) | `all`: fidelidad **0,7326** (n = 86, vacías = 10), relevancia **0,6447** (n = 76), precisión **0,6579** (n = 76): **idéntico** al `quality-summary.csv` del mismo informe |
| Consulta `01-list-runs.sql` | lista `e2e-r1` y `e2e-q1` con `chat-system=1` (el prompt fijado, `pinned`) y los otros tres como `default` |
| Texto de respuestas o fragmentos en la base | **0 filas** de `eval_result` ni de `eval_run` contienen las cadenas centinela que se pusieron en el contexto y en la respuesta del informe |
| Filas | 2 ejecuciones, 96 revisiones de caso, 172 resultados (96 + 76) |

## Escenarios del quickstart

| # | Escenario | Estado | Evidencia |
|---|---|---|---|
| V1 | `quality --save` guarda una ejecución y un resultado por caso | **Parcial** | Contrato con backend y PostgreSQL reales y el dataset real (arriba). El CLI completo (`quality --save`) con el asistente y el juez reales **no se ejecutó**: necesita Ollama, el gateway y la base de conocimiento indexada. El flujo del CLI lo cubren `test_store_cli.py` (backend simulado) |
| V2 | `retrieval --save` guarda otra ejecución `RETRIEVAL` | **Parcial** | Igual que V1: 76 resultados guardados contra el backend real; el CLI con la búsqueda real, pendiente |
| V3 | Medias recalculadas en SQL = `quality-summary.csv` | **Cumplido** | Tabla de arriba (paridad exacta). También `JdbcEvalRunStoreAdapterTests` y `test_store_payload.py` |
| V4 | Tres casos al azar contra `quality-detail.csv` | Pendiente | Necesita una ejecución real con los CSV |
| V5 | Ejecución con prompts de versión numérica, modelos y configuración | **Parcial** | Se guardan y se leen con la consulta `02`; con Langfuse apagado el backend sirve el texto por defecto (`version = null`). Las **versiones numéricas servidas por Langfuse** no se vieron con Langfuse real |
| V6 | `--prompt` fijado y consulta por prompt y versión | **Parcial** | `chat-system=1` quedó `pinned` y la consulta `08` está probada (`EvalHistoryQueriesTests`); el flujo `--prompt` con Langfuse real, pendiente |
| V7 | Comparación de dos ejecuciones | Automático | `EvalHistoryQueriesTests` (05, 06, 07). Manual, pendiente |
| V8 | Caso editado marcado como no comparable | Automático | `EvalHistoryQueriesTests` y `JdbcEvalRunStoreAdapterTests`. Manual, pendiente |
| V9 | `run_id` repetido falla antes de medir | **Cumplido** | Contra el backend real (409) y `test_store_cli.py` (falla antes de pedir nada al asistente, al juez o a la búsqueda) |
| V10 | Backend caído falla antes de consultar al asistente o al juez | Automático | `test_store_cli.py` (`127.0.0.1:1`); manual, pendiente |
| V11 | Medición que falla a mitad no deja filas | Automático | `test_store_cli.py` y `JdbcEvalRunStoreAdapterTests` (rollback de la ejecución y de las revisiones); manual, pendiente |
| V12 | Etiqueta de prompt movida durante la medición invalida la ejecución | Automático | `test_store_cli.py` y `test_store_payload.py` (deriva); **con Langfuse real, pendiente** |
| V13 | Sin token ni `--save`: mismos CSV y código de salida | **Cumplido** | Los 546 tests anteriores de `eval/` pasan sin tocar sus expectativas (SC-006) y `test_store_cli.py` |
| V14 | `--save` sin token: error de uso | **Cumplido** | `test_store_cli.py`: código 2 antes de pedir nada |
| V15 | Backend sin token configurado: 403 | **Cumplido** | `curl` contra el backend real (sin cabecera → 403) y `EvalNoTextStoredTests` (token vacío, distinto y en mayúsculas) |
| V16 | Ninguna fila contiene texto de respuestas ni de fragmentos | **Cumplido** | 0 filas con las cadenas centinela en la base real y `EvalNoTextStoredTests` (lista blanca de columnas) |
| V17 | `make up` + `make quality` en desarrollo | **Pendiente** | Espera al primer release (`vX.Y.Z`) que incluya el módulo `eval` y a la fusión del PR de infra (ADR-0012: desarrollo solo despliega versiones) |
| V18 | `make up` añade la entrada `eval` sin cambiar valores existentes | **Parcial** | `ensure-secrets.test.sh` (10 comprobaciones sobre archivos temporales); `make up` real, pendiente |
| V19 | Sin el Secret `backend-eval` el backend arranca y responde 403 | **Parcial** | `optional: true` en el `envFrom` (visible en `kustomize build`) y el 403 por token vacío probado; en el clúster, pendiente |

## Hallazgos durante la implementación

- **El overlay de dev sustituye `ENVIRONMENT` con un parche por `ExternalSecret`.** El `backend-eval` salió con
  `/shoplab/ENVIRONMENT/eval` sin sustituir hasta añadir su parche en `k8s/overlays/dev/kustomization.yaml`; el plan no lo
  mencionaba.
- **El manejador global solo devolvía mensajes fijos**, así que el «mensaje que nombra el campo» del 422 exigió el campo opcional
  `detail` (`PublicDetail` en `shared`); el JSON del resto de errores no cambia (probado).
- **`retrieval` imprime los errores de su medición por la salida estándar** (comportamiento anterior que no se cambió); los de
  guardado van por la salida de error. Las pruebas lo tienen en cuenta.
- **En Windows un puerto cerrado acaba en timeout**, no en conexión rechazada (ya documentado en el cliente de búsqueda): el
  mensaje de «backend inalcanzable» nombra el backend en ambos casos.
- **`PromptCatalogPort` no necesitó cambios**: `current(name).reference()` ya da la versión y el origen.
- **Módulo extra respecto al plan**: `eval/src/shoplab_eval/store_session.py` (el orden de operaciones comprobar → medir →
  releer → escribir → guardar), para no inflar `__main__.py`.

## Pendiente antes de dar la historia por cerrada

1. `quality --save` y `retrieval --save` reales (Ollama, gateway, Langfuse con trazas, base indexada): V1, V2, V4, V5, V6, V12.
2. Commit y PR de `shoplab-infra` (CI ejecutará `kubeconform` y `conftest`) y luego el de `shoplab-helpdesk`.
3. Primer release con el módulo `eval` desplegado en dev: V17 y V19, y `make quality` guardando sin pasos manuales.
4. **No cerrar LAB-31 en Jira** hasta validar ese primer tag.
