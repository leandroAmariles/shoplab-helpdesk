# Implementation Plan: Métricas de calidad de respuesta (fidelidad, relevancia y precisión del contexto)

**Branch**: `feature/LAB-29-answer-quality-metrics` | **Date**: 2026-11-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-29-answer-quality-metrics/spec.md`

## Summary

Un comando nuevo en el paquete `shoplab_eval` de `eval/` (`python -m shoplab_eval quality`) que consulta al **asistente real**
(`POST /api/chat`) con cada pregunta de conocimiento del dataset golden, recoge la respuesta y el contexto que recibió el
modelo, y hace que un **modelo juez** puntúe fidelidad, relevancia de la respuesta y precisión del contexto con **RAGAS**.
Escribe dos CSV (resumen y detalle), adjunta las puntuaciones a las trazas de Langfuse por su `traceId` y falla con claridad
cuando el entorno no permite una medición fiable.

Tres decisiones de diseño sostienen el plan; todas evitan tocar el backend:

1. **Biblioteca: RAGAS 0.4.4** (API `ragas.metrics.collections`). Sus tres métricas reciben exactamente lo que el turno
   entrega (`user_input`, `response`, `retrieved_contexts`, `reference`) y el juez se construye con un cliente
   OpenAI-compatible, es decir, el gateway de LiteLLM (LAB-84). DeepEval se descarta (telemetría por `posthog`, plugins de
   pytest como dependencia). Coste aceptado: unas 95 dependencias, con la telemetría de RAGAS apagada por código.
2. **El turno se obtiene del stream SSE existente, sin cambios en el backend.** El evento `sources` ya lleva el texto de los
   fragmentos «tal como se entregaron al modelo» (LAB-23); los eventos `token` forman la respuesta. El `traceId` de Langfuse
   es derivable: `SHA-256(X-Request-Id)[:32]` (ADR-0022), y `eval/` elige el `X-Request-Id` (`eval-<run>-<caso>`), de modo que
   conoce la traza de cada turno sin consultarla. Ese prefijo queda en el metadato `request_id` de la traza, y es lo que
   reconoce y filtra las trazas de evaluación por ejecución.
3. **Fases separadas: primero todos los turnos del asistente, después todos los del juez.** Evita que Ollama alterne dos
   modelos en memoria por caso y permite fallar pronto con una sola consulta de prueba (la primera) si las trazas no existen.

- **Gateway y DEV** (`infra/` y `../shoplab-infra`): nombre lógico `judge` y clave virtual `eval` en `infra/litellm/` y en el
  compose, **y lo mismo en DEV**: la configuración del gateway de DEV se **sincroniza desde `infra/litellm/`**
  (`scripts/sync-litellm-files.sh`), así que un cambio aquí llega allí y, sin acompañarlo, rompería el Job de claves. DEV se
  usa como entorno local: tras `make up` y `make seed`, un `make quality` mide contra él sin pasos manuales (túneles a LiteLLM y
  Langfuse, claves leídas del clúster, cabecera `Host` del ingress). Como el archivo local de secretos de DEV hoy no define
  `langfuse` ni `litellm`, `make up` además los completa (R9). El requisito es que **todo el clúster local funcione** con Ollama
  como único componente fuera (en el Docker del host, como ya hace el overlay `dev`): T053 lo verifica entero y T054 corrige lo
  que falle, también si ya estaba roto. **No está verificado todavía** (Docker Desktop estaba parado al planificar).
- **Medición** (`eval/`, Python): cliente del chat por SSE, juez RAGAS tras un puerto, cálculo puro de aplicabilidad y medias,
  cliente de Langfuse por REST con la biblioteca estándar, escritura de CSV y subcomando `quality`.
- **Puntuación vacía ≠ cero** (FR-011): lo que no se puede calcular por definición queda vacío y se cuenta; un fallo del juez
  detiene la medición.
- **CI**: sin cambios de flujo. Todas las pruebas usan dobles del asistente, del juez y de Langfuse; la medición real es
  manual hoy y automática en LAB-30.

Decisiones y alternativas en [research.md](research.md); tipos en [data-model.md](data-model.md); CSV e interfaces externas en
[contracts/](contracts/); verificación en [quickstart.md](quickstart.md).

## Technical Context

**Language/Version**: Python 3.12 o superior en `eval/` (gestionado con `uv`, como LAB-27 y LAB-28). Sin cambios en
`backend/`. YAML y compose en `infra/` para el gateway local; kustomize, Terraform y scripts de bash en `../shoplab-infra`
(DEV)

**Primary Dependencies**: `ragas==0.4.4` (fijada; arrastra `openai`, `instructor`, `langchain*`, `datasets`, `pandas`, unas 95
en total, verificado instalándola en un entorno aislado) en un grupo de dependencias `quality` incluido por defecto. Para lo
demás, solo biblioteca estándar (`urllib.request`, `json`, `csv`, `hashlib`, `uuid`, `asyncio`, `argparse`, `dataclasses`);
ya existe `pyyaml` de LAB-27

**Storage**: ninguno. Entrada: `eval/golden.jsonl`, `data/kb` (títulos), el backend por HTTP, el gateway y Langfuse. Salida:
dos CSV en `eval/results/` (no versionados) y puntuaciones en Langfuse

**Testing**: `pytest` con cobertura ≥ 80 % (ya configurada). Cálculo y agregación con valores fijados a mano; cliente del chat
y de Langfuse contra un `http.server` local en un hilo; orquestación con dobles del asistente y del juez. Una prueba de
**calibración** del juez real (`-m judge`, fuera de la ejecución por defecto) con pares fijados a mano para SC-002 y SC-003.
Ninguna prueba por defecto usa Ollama, el gateway, Langfuse ni red externa

**Target Platform**: máquina del desarrollador con **uno de dos entornos** levantado: (a) el stack de `docker compose` de este
repo (backend, PostgreSQL, Ollama, gateway y, para adjuntar, el perfil `llmops` con las trazas del chat activadas); o (b) **DEV**,
el clúster local de `shoplab-infra` (`make up`: Floci, Argo CD, backend, LiteLLM, Langfuse y Ollama en el Docker del host), que el
equipo usa como entorno local. En DEV el ingress, LiteLLM y Langfuse se alcanzan por `kubectl port-forward` (`make forward` ya
lo hace). No corre en CI hasta LAB-30

**Project Type**: herramienta de línea de comandos en un proyecto Python existente (más configuración del gateway en `infra/` y
el despliegue de DEV en `shoplab-infra`)

**Performance Goals**: SC-001 fija 30 minutos para los 96 casos con el juez local por defecto. Es una **hipótesis a medir**
(~1000 llamadas al juez; en CPU puede ser varias horas, con GPU cabe). Se mide en la verificación S-4 y, si no se cumple en
la máquina de referencia, se ajusta el criterio con `loop-spec` antes de implementar el resto. Pruebas de `eval` por debajo de
5 segundos

**Constraints**: no cambiar el asistente, el dataset ni el backend; el CSV no contiene el texto de respuestas ni fragmentos;
las claves salen solo del entorno y nunca de CSV, pantalla ni registros; la telemetría de RAGAS apagada; si falla algo que
impide un resultado fiable, no se escribe CSV (salvo el fallo de adjuntar, que lo conserva); líneas ≤ 121; identificadores en
inglés, documentación en español

**Scale/Scope**: 120 casos del dataset: 76 con respuesta (3 métricas), 20 sin respuesta (fidelidad), 24 de conversación
(excluidos). Unas 10 a 12 llamadas al juez por caso con respuesta

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | Cumple: ningún cambio en `backend/`. `eval/` usa el chat por su API pública y las trazas por la API de Langfuse; no toca esquemas ni módulos. |
| II. Hexagonal | Cumple en lo que aplica a `eval/`: la lógica pura (aplicabilidad, medias, formato) no depende de red ni de RAGAS; el asistente, el juez y Langfuse están tras `Protocol` (`ChatClient`, `Judge`, `TraceScores`) y solo `quality_judge.py` importa RAGAS, de modo que cambiar de biblioteca o de proveedor no toca el resto. El acceso a modelos pasa por el gateway. |
| III. Inglés y errores tipados | Cumple: identificadores en inglés; todo fallo de la medición es `MeasurementError` con causa, caso y métrica. |
| IV. Pruebas con cobertura | Cumple: pruebas unitarias de cada regla y cada fallo, dobles para asistente, juez y Langfuse, cobertura ≥ 80 % en `eval/`. La prueba contra el juez real queda fuera de la ejecución por defecto y no entra en la cobertura exigida. Las llamadas a LLM están simuladas en CI. |
| V. RAG con evaluación | Cumple y desarrolla: esta historia es la mitad de calidad de respuesta del resultado de evaluación que la constitución exige (fidelidad, relevancia, precisión del contexto). La puerta en los PR llega con LAB-30. Queda fuera el *context recall*, que cubre LAB-28 por documento. |
| VI. Observabilidad y datos seguros | Cumple: las puntuaciones se adjuntan a las trazas (el contenido sensible ya vive en Langfuse, ADR-0022); el CSV no lleva texto; claves desde el entorno; telemetría de RAGAS apagada; corpus sintético y público. El juez es local por defecto, de modo que ningún dato sale de la máquina. |
| VII. Simplicidad y ADR | Cumple con ADR: elegir RAGAS, el juez tras el gateway y la correlación por `X-Request-Id` son decisiones de arquitectura de la evaluación; se registran en **ADR-0026**. Se descartan DeepEval, un juez propio sin biblioteca y cambiar el backend. |

**Gate**: PASA. Desviaciones declaradas y justificadas en Complexity Tracking (dependencia pesada, comprobación de abstención
propia, nombre lógico y clave nuevos en el gateway, cambios en `shoplab-infra` y completado de los secretos de DEV).

**Re-evaluación tras el diseño**: PASA. No hay cambios en `backend/`, ni migraciones, ni nuevos servicios. `eval/` gana módulos
y un subcomando; el gateway gana un nombre lógico y una clave, también en DEV. El cambio en `shoplab-infra` no añade recursos
nuevos al clúster: una variable más en un Job, scripts y documentación. El riesgo que quedaba abierto (la calidad del juez) se midió
en S-1: el 3B no sirve y el juez por defecto es `qwen2.5:7b` (ver R2 y `validation.md`).

## Project Structure

### Documentation (this feature)

```text
specs/LAB-29-answer-quality-metrics/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── quality-csv.md          # columnas y significado del resumen y del detalle, y el comando
│   └── external-interfaces.md  # lo que eval/ consume: chat SSE, juez por el gateway y scores de Langfuse
├── checklists/requirements.md
└── tasks.md                    # lo genera /speckit-tasks
```

### Source Code (repository root)

```text
eval/
├── pyproject.toml                              # + grupo `quality` (ragas==0.4.4) en default-groups; marcador pytest `judge`
├── uv.lock                                     # regenerado
├── README.md                                   # + sección «Calidad de respuesta» (FR-025)
├── src/shoplab_eval/
│   ├── constants.py                            # + QualityDefaults, nombres de métricas y variables de entorno
│   ├── corpus.py                               # + CorpusDocument.title y búsqueda título → id de documento
│   ├── chat_client.py                          # POST /api/chat por SSE: respuesta, contexto, errores (urllib)
│   ├── langfuse_client.py                      # trace_id_for(), comprobación de acceso, esperar traza, publicar score
│   ├── quality_judge.py                        # puerto Judge + RagasJudge (único módulo que importa ragas/openai)
│   ├── quality_scores.py                       # puro: aplicabilidad por tipo de caso, vacíos, medias por ámbito
│   ├── quality_run.py                          # comprobaciones previas, fases (turnos → juez → adjuntar), QualityReport
│   ├── quality_report.py                       # CSV de resumen y detalle, y texto para la pantalla
│   └── __main__.py                             # + subcomando `quality`
├── results/.gitkeep                            # ya existe; el contenido no se versiona
└── tests/
    ├── test_chat_client.py                     # http.server SSE: éxito, sin sources, error en flujo, HTTP 4xx/5xx, corte
    ├── test_langfuse_client.py                 # traceId derivado, publicar score, idempotencia, 4xx/5xx, traza que no aparece
    ├── test_quality_scores.py                  # aplicabilidad, vacío por definición, medias, exclusiones, categoría sin casos
    ├── test_quality_run.py                     # fases, comprobaciones previas, fallos del asistente/juez, adjuntar parcial
    ├── test_quality_report.py                  # columnas, recálculo del resumen desde el detalle, sin texto, UTF-8
    ├── test_quality_cli.py                     # códigos de salida, configuración inválida, no escribe CSV al fallar
    ├── test_ragas_judge.py                     # adaptador con las métricas de RAGAS sustituidas; telemetría apagada
    └── test_judge_calibration.py               # @pytest.mark.judge: pares fijados a mano contra el juez real (manual)

infra/
├── litellm/config.yaml                         # + nombre lógico `judge` (LITELLM_JUDGE_MODEL)
├── litellm/keys.yaml                           # + clave virtual `eval` (judge y embeddings, presupuesto propio)
├── litellm/tests/test_bootstrap_keys.py        # + la clave nueva
├── docker-compose.yml                          # + LITELLM_KEY_EVAL en litellm-bootstrap
└── .env.example                                # + LITELLM_KEY_EVAL, LITELLM_JUDGE_MODEL y SHOPLAB_EVAL_* del juez y Langfuse

../shoplab-infra/                               # rama nueva desde `main` y PR propia (no se tocan cambios locales ajenos)
├── k8s/base/litellm/files/*, SOURCE            # copia sincronizada de infra/litellm (`scripts/sync-litellm-files.sh`)
├── k8s/base/litellm/job-bootstrap-keys.yaml    # + LITELLM_KEY_EVAL (secretKeyRef)
├── k8s/base/litellm/externalsecret.yaml        # comentario: ahora también LITELLM_KEY_EVAL (dataFrom ya la extrae)
├── terraform/envs/dev/secrets.auto.tfvars.example   # + LITELLM_KEY_EVAL en el JSON `litellm`
├── terraform/envs/dev/tests/apply.tftest.hcl, terraform/modules/secrets/tests/secrets.tftest.hcl   # + la clave en los datos de prueba
├── scripts/lib.sh                              # + la clave en el TF_VAR_secrets de ejemplo
├── scripts/ensure-secrets.sh                   # completa `langfuse`/`litellm`/`LITELLM_KEY_EVAL` que falten, sin tocar valores existentes
├── scripts/up.sh, Makefile                     # `make up` llama a ensure-secrets; objetivos `secrets` y `quality`
├── scripts/forward.sh                          # + túneles a `litellm` (4000) y `langfuse-web` (3001)
├── scripts/quality.sh                          # lanza `quality` de eval/ contra DEV con las claves leídas del clúster
├── scripts/tests/ensure-secrets.test.sh        # pruebas del script, enganchadas a `make test-ci-scripts`
└── docs/gateway-de-modelos.md, docs/observability.md, docs/terraform-local.md, README.md   # juez, túneles, `make quality`, secretos

docs/adr/0026-calidad-de-respuesta-con-ragas.md # RAGAS, juez por el gateway, correlación por X-Request-Id
docs/adr/README.md                              # + fila del ADR-0026
docs/gateway-de-modelos.md                      # + el nombre lógico `judge` y la clave `eval`
```

**Structure Decision**: la medición vive en el paquete `shoplab_eval`, junto a la de recuperación, y reutiliza `load_golden`
(FR-001) y el lector del corpus. Las funciones puras de aplicabilidad y medias están separadas del acceso a red y del juez para
probarlas sin nada de eso (FR-023). RAGAS entra solo por `quality_judge.py`; el resto trabaja con tipos propios. El resultado
solo se escribe con la medición completa, igual que en LAB-28, con la única excepción declarada en el spec: el fallo al adjuntar
a Langfuse conserva el CSV, porque las puntuaciones ya costaron cientos de llamadas al juez. No se toca `backend/`: el stream del
chat y el `X-Request-Id` actuales bastan para obtener el turno, el contexto y la traza. No se envía `conversationId`: LAB-24
guarda sin caducidad cada conversación identificada y la medición dejaría 96 por ejecución.

## Complexity Tracking

| Elemento | Por qué es necesario | Alternativa más simple descartada porque |
|---|---|---|
| `ragas` y sus ~95 dependencias | El criterio de aceptación pide RAGAS o DeepEval, y la constitución (V) los nombra. Implementa las tres métricas con prompts ya probados por la comunidad | Un juez propio sin biblioteca: obliga a diseñar, calibrar y mantener los prompts y el reparto de afirmaciones, y las puntuaciones dejarían de ser comparables con las de cualquier otra herramienta |
| Comprobación de abstención propia (`abstained`), no es una métrica de RAGAS | FR-011 y los escenarios 6 de la historia 1 y 5 de la historia 4 exigen que una negativa no se penalice como afirmación sin respaldo. RAGAS puntuaría «no tengo información» como infiel | Detectarla por el texto del mensaje fijo de LAB-22: solo cubre la negativa del asistente sin contexto, no las que el modelo redacta con contexto |
| Nombre lógico `judge` y clave `eval` en el gateway local | El juez debe pasar por el mismo gateway (FR-009), con presupuesto propio para que medir no gaste el del backend, y poder cambiarse por configuración | Usar la clave del backend: mezcla presupuestos y da al juez privilegios de producción |
| Prueba de calibración con el juez real fuera de la ejecución por defecto | SC-002 y SC-003 solo se pueden demostrar con un juez real; CI no puede depender de un modelo | Verificarlo solo a mano: no queda repetible al cambiar de juez |
| Cambios en `../shoplab-infra` (Job de claves, secretos, túneles, `make quality`) | La configuración del gateway de DEV se sincroniza desde este repo: añadir `eval` aquí sin acompañarlo rompería el Job `litellm-bootstrap-keys` de DEV. Además, el equipo usa DEV como entorno local y `make up` debe dejar la medición funcionando. Es la regla del proyecto: la infraestructura que una historia necesita se hace en la historia | Dejar DEV para LAB-30: deja el repo de infraestructura roto en la próxima sincronización |
| `scripts/ensure-secrets.sh` y el objetivo `make secrets` | El `secrets.auto.tfvars` local de DEV no define `langfuse` ni `litellm`, de modo que hoy `make up` deja el gateway y Langfuse sin credenciales; sin completar eso, la medición no puede funcionar tras `make up`. Solo **añade** lo que falta con valores aleatorios y nunca cambia los existentes (`postgres-password` debe seguir coincidiendo con el Postgres de Docker) | Pedir que cada persona edite el HCL a mano: es el estado actual y el motivo por el que DEV no arranca completo |
| Opción `--host-header` (`SHOPLAB_EVAL_HOST_HEADER`) en `eval/` | El ingress de DEV enruta por `Host` (`shoplab.localtest.me`) y `seed.sh` ya evita depender del DNS público entrando por `127.0.0.1` con esa cabecera; `quality` y `retrieval` necesitan lo mismo para funcionar sin red | Exigir `*.localtest.me` resuelto por DNS: falla sin conexión a Internet |
| ADR-0026 | Elegir la biblioteca de evaluación y fijar la correlación con Langfuse son decisiones que LAB-30 y LAB-31 heredan; la constitución (VII) obliga a registrarlas | Dejarlo en el PR: se perdería |
