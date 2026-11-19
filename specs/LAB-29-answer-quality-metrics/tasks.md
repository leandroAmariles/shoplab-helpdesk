---

description: "Task list for LAB-29 - Métricas de calidad de respuesta (fidelidad, relevancia y precisión del contexto)"
---

# Tasks: Métricas de calidad de respuesta (fidelidad, relevancia y precisión del contexto)

**Input**: Design documents from `/specs/LAB-29-answer-quality-metrics/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`quality-csv.md`, `external-interfaces.md`),
quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas y cobertura ≥ 80 %; `eval/` ya tiene
`--cov-fail-under=80`. En cada historia las pruebas van primero y deben fallar antes de implementar. Ninguna prueba por defecto
usa Ollama, el gateway, Langfuse ni red externa: se usan funciones puras, dobles inyectados del asistente, del juez y de
Langfuse, y un `http.server` local en un hilo (`eval/tests/fake_backend.py` ya existe desde LAB-28). La única prueba contra el
juez real lleva el marcador `judge` y no se ejecuta por defecto.

**Organization**: Tareas agrupadas por historia de usuario: US1 (medir las tres métricas y escribir el CSV, P1), US2 (juez
configurable y registrado, P1), US3 (adjuntar a Langfuse, P1) y US4 (fallar con claridad, P2). Antes de las historias hay una
fase Foundational con **puertas de verificación con un modelo real** (S-1, S-2, S-3, S-5 de research.md): si el juez local por
defecto no discrimina, el diseño cambia y es mejor saberlo antes de construir el resto.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US4)
- **(manual)**: necesita el stack local levantado o un modelo real; la persona que implementa la ejecuta y anota el resultado en
  `specs/LAB-29-answer-quality-metrics/validation.md`
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **`eval/`**: paquete `eval/src/shoplab_eval/`, pruebas en `eval/tests/` (sin `tests/__init__.py`), comandos desde `eval/`
  con `uv run …`. Al ejecutar solo algunos archivos de prueba durante el TDD, añadir `--no-cov`
  (`uv run pytest --no-cov tests/<archivo>`) para que falle por la razón esperada y no por la cobertura
- **`infra/litellm/`**: pruebas con `cd infra/litellm && uv run pytest` (o el comando que use su `README`/CI)
- Python: líneas ≤ 121, `ruff` con `select = ["E", "F", "I", "B", "UP"]`, identificadores en inglés; mensajes para quien
  ejecuta y documentación en español
- **Ningún cambio en `backend/`**, ni en `data/kb/`, ni en `eval/golden.jsonl`. **Sí** se toca `../shoplab-infra` (fase 7): la
  configuración del gateway de DEV se sincroniza desde `infra/litellm/`, DEV es el entorno local del equipo y la infraestructura
  que una historia necesita se hace en la historia. Allí: rama nueva desde `main`, PR propia, y no tocar cambios locales ajenos
- Solo `quality_judge.py` importa `ragas` y `openai`; el resto de módulos no los conoce (plan, Constitution II)
- Las claves salen solo del entorno y nunca se imprimen, se registran ni llegan al CSV (FR-008)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base verde, dependencia nueva, constantes y el juez en el gateway local

- [X] T001 Línea base en la rama: `cd eval && uv sync --frozen && uv run ruff check . && uv run pytest` en verde y
  `uv run python -m shoplab_eval validate` informa 120 casos (96 de conocimiento: 76 con respuesta y 20 sin respuesta; 24 de
  conversación). `cd infra/litellm` y ejecutar sus pruebas en verde. Anotar fallos previos en `validation.md`; no se corrigen aquí. Si hay tiempo y Docker Desktop está en marcha, **recomendado**:
  línea base de DEV con `main` de `shoplab-infra` tal cual (completando a mano `langfuse` y `litellm` en `secrets.auto.tfvars`
  con los formatos de `docs/observability.md`): `make up`, `make seed` y anotar qué aplicaciones o pods no quedan sanos y si el
  chat responde y deja traza. Separa lo que ya estaba roto de lo que rompamos nosotros; se corrige en T054, no aquí
- [X] T002 [P] En `eval/pyproject.toml`: añadir `[dependency-groups] quality = ["ragas==0.4.4"]` (versión **fijada**, research R1),
  `[tool.uv] default-groups = ["dev", "quality"]` para que `uv sync --frozen` siga bastando, el marcador pytest `judge`
  (`markers = ["judge: usa el juez real; no se ejecuta por defecto"]`) y `-m "not judge"` en `addopts` (conservando
  `--cov=shoplab_eval --cov-fail-under=80`). Regenerar `eval/uv.lock` con `uv lock`. Comprobar `uv sync --frozen`,
  `uv run python -c "import ragas; print(ragas.__version__)"` → `0.4.4` y que las pruebas existentes siguen en verde
- [X] T003 [P] Añadir a `eval/src/shoplab_eval/constants.py`, con comentario del origen en el spec o el plan:
  `QUALITY_SUMMARY_FILE = "quality-summary.csv"`, `QUALITY_DETAIL_FILE = "quality-detail.csv"`;
  `METRIC_FAITHFULNESS = "faithfulness"`, `METRIC_ANSWER_RELEVANCY = "answer_relevancy"`,
  `METRIC_CONTEXT_PRECISION = "context_precision"`; `DEFAULT_JUDGE_MODEL = "judge"`,
  `DEFAULT_JUDGE_BASE_URL = "http://localhost:4000/v1"`, `DEFAULT_JUDGE_TEMPERATURE = 0.0`, `DEFAULT_JUDGE_TIMEOUT_SECONDS = 120`,
  `DEFAULT_MAX_INPUT_CHARS = 24000`, `DEFAULT_CONCURRENCY = 2` con límites `MIN_CONCURRENCY = 1` y `MAX_CONCURRENCY = 16`,
  `DEFAULT_TURN_TIMEOUT_SECONDS = 120`, `MAX_JUDGE_TEMPERATURE = 2.0`; `RUN_ID_PATTERN = r"^[A-Za-z0-9._-]{1,32}$"`;
  `REQUEST_ID_PREFIX = "eval"` (el `X-Request-Id` es `eval-<run_id>-<id del caso>`, ≤ 64 caracteres); `TRACE_ID_LENGTH = 32`, `SCORE_ID_LENGTH = 32`; `DEFAULT_LANGFUSE_HOST =
  "http://localhost:3001"`; `TRACE_WAIT_SECONDS = 30`; variables de entorno `JUDGE_MODEL_ENV = "SHOPLAB_EVAL_JUDGE_MODEL"`,
  `JUDGE_BASE_URL_ENV = "SHOPLAB_EVAL_JUDGE_BASE_URL"`, `JUDGE_API_KEY_ENV = "SHOPLAB_EVAL_JUDGE_API_KEY"`,
  `ASSISTANT_MODEL_ENV = "SHOPLAB_EVAL_ASSISTANT_MODEL"`, `LANGFUSE_HOST_ENV = "SHOPLAB_EVAL_LANGFUSE_HOST"`,
  `LANGFUSE_PUBLIC_KEY_ENV = "SHOPLAB_EVAL_LANGFUSE_PUBLIC_KEY"`, `LANGFUSE_SECRET_KEY_ENV = "SHOPLAB_EVAL_LANGFUSE_SECRET_KEY"`;
  `UNKNOWN = "unknown"`; `ABSTENTION_PROMPT_VERSION = "1"`. Reutilizar `ALL_SCOPE`, `LIST_SEPARATOR`, `DECIMALS` y
  `DEFAULT_RESULTS_DIR` de LAB-28
- [X] T004 [P] Gateway, fuente única en `infra/` de este repo (research R9; DEV lo recibe por sincronización en T045). **Confirmar
  en un commit** antes de la fase 7:
  (a) `infra/litellm/config.yaml`: nuevo `model_name: judge` con `litellm_params.model` =
  `ollama_chat/${LITELLM_JUDGE_MODEL:-${OLLAMA_JUDGE_MODEL:-qwen2.5:7b}}` (**hecho**; por S-1 el 3B del chat no sirve de juez), el mismo `api_base`,
  `num_ctx` y `keep_alive` que `chat`, `model_info.id: judge-primary` y los precios de referencia de `chat`; **sin fallback**
  (el juez no cae a otro modelo: cambiaría las puntuaciones sin avisar);
  (b) `infra/litellm/keys.yaml`: entrada `alias: eval-${GATEWAY_ENVIRONMENT}`, `consumer: eval`, `key_env: LITELLM_KEY_EVAL`,
  `models: [judge, embeddings]`, `max_budget` y `budget_duration` propios (p. ej. `5.00` y `1d`, como `backend`);
  (c) `infra/docker-compose.yml`: `LITELLM_KEY_EVAL: ${LITELLM_KEY_EVAL:-sk-shoplab-eval-local}` en `litellm-bootstrap` y
  `LITELLM_JUDGE_MODEL: ${LITELLM_JUDGE_MODEL:-}` en `litellm`;
  (d) `infra/.env.example`: `LITELLM_KEY_EVAL=sk-shoplab-eval-local`, `LITELLM_JUDGE_MODEL=` y un bloque comentado con
  `SHOPLAB_EVAL_JUDGE_API_KEY`, `SHOPLAB_EVAL_JUDGE_BASE_URL`, `SHOPLAB_EVAL_ASSISTANT_MODEL` (el modelo de chat del gateway; el
  backend no lo expone) y `SHOPLAB_EVAL_LANGFUSE_HOST|PUBLIC_KEY|SECRET_KEY`;
  (e) pruebas de `infra/litellm/tests/` (`test_bootstrap_keys.py`: tercera clave `created alias=eval-local` y modelos
  `["judge", "embeddings"]`; `test_config_invariants.py` y `test_check_models.py` si enumeran los nombres lógicos o los modelos
  que debe tener Ollama; `test_compose_isolation.py` si lista variables). Ejecutar toda la suite de `infra/litellm` en verde

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: resolver el id de documento desde un título, derivar el `traceId`, fijar los pares de calibración y **comprobar con
un modelo real** que el diseño funciona antes de construirlo

**⚠️ CRITICAL**: ninguna historia empieza hasta cerrar el checkpoint de T012

- [X] T005 [P] Pruebas en `eval/tests/test_corpus.py` para el título: `CorpusDocument` expone `title` (del front matter,
  recortado); `title_index(corpus)` devuelve `título → id` (p. ej. «Pago en cuotas» → `payments-02-installments`); un título
  repetido entre dos documentos lanza `ValueError` que nombra los dos ids; un documento sin `title` en el front matter lanza
  `ValueError`; el corpus real (`data/kb`) produce 24 títulos distintos
- [X] T006 Implementar en `eval/src/shoplab_eval/corpus.py` (depende de T005): campo `title: str` en `CorpusDocument`, lectura de
  `title` del front matter en `_read_document` (error `"<archivo>: el front matter no tiene 'title'"` si falta o está vacío) y
  `title_index(corpus) -> dict[str, str]`. No cambiar `id`, `topic` ni `text` (los usa LAB-27 y LAB-28)
- [X] T007 [P] Pruebas en `eval/tests/test_langfuse_client.py` (archivo nuevo; US3 lo amplía) de `trace_id_for(request_id)`:
  `SHA-256(request_id)` en hexadecimal, **primeros 32 caracteres** (ADR-0022 §4); determinista; distinto para distinto id; vector
  fijo calculado a mano con `hashlib` para `eval-20261118T153000Z-G001`
- [X] T008 Crear `eval/src/shoplab_eval/langfuse_client.py` mínimo (depende de T007): `trace_id_for(request_id: str) -> str` y
  el enum `LangfuseStatus` con `ATTACHED`, `NOT_ATTACHED` y `SKIPPED` (data-model). Sin red todavía
- [X] T009 [P] Crear `eval/tests/calibration_pairs.py` con los datos fijados a mano para SC-002 y SC-003, sin dependencias:
  **cinco pares de fidelidad** (misma pregunta y mismo contexto, una respuesta que solo afirma lo que dice el contexto y otra con
  un dato inventado: plazo, importe o condición que ningún fragmento respalda); **dos pares de relevancia** (una respuesta que
  contesta lo preguntado y otra fiel al contexto pero que contesta otra cosa); **tres tripletas de precisión** (los mismos
  fragmentos con el útil primero y con el útil después de dos irrelevantes). Redactarlos a partir de casos reales del dataset
  (p. ej. G002, G003, G004 de `eval/golden.jsonl`) y de los fragmentos de `data/kb`; sin datos personales (Constitución VI)
- [X] T010 (manual) Verificaciones **S-1, S-2 y S-5** de research.md con el stack local (`docker compose -f
  infra/docker-compose.yml up -d --wait` y `litellm-bootstrap`), usando `eval/` y un script desechable en el scratchpad (no se
  versiona) que construya `AsyncOpenAI(base_url="http://localhost:4000/v1", api_key=<clave eval>)`, `llm_factory("judge",
  client=…)` y las tres métricas de `ragas.metrics.collections` sobre `eval/tests/calibration_pairs.py` con `RAGAS_DO_NOT_TRACK=true`.
  Anotar en `validation.md`: (S-1) si `instructor` produce salida estructurada válida con el 3B local y en qué modo (`JSON` si
  el modo de funciones falla), y si discrimina: fidelidad de la respuesta con dato inventado < la de la fiel en 5 de 5 pares,
  relevancia correcta en 2 de 2, precisión mejor con el útil primero en 3 de 3; que la clave `eval` lee el modelo real en
  `GET /model/info`; (S-2) que `AnswerRelevancy` calcula con el nombre lógico `embeddings` por el mismo cliente
  (`embedding_factory`); (S-5) qué devuelve `Faithfulness` sin afirmaciones, ante «no tengo información» y con
  `retrieved_contexts=[]` y una respuesta afirmativa (¿`NaN`, 0 o error?); la regla de contexto vacío de research R6 no depende de
  este resultado, que solo se documenta
- [X] T011 [P] (manual) Verificación **S-3** de research.md con el perfil `llmops` y el backend con
  `SHOPLAB_CHAT_TRACING_ENABLED=true`: hacer un turno con `X-Request-Id: eval-s3-G001`, calcular su `traceId` con
  `trace_id_for`, y con `curl` y las claves de Langfuse comprobar: `GET /api/public/traces/{id}` devuelve 404 hasta que se
  ingiere; `POST /api/public/scores` con `id` fijo asocia el score; repetir el `POST` con el mismo `id` **actualiza** y no duplica;
  publicar sobre un `traceId` aún no ingerido (¿se guarda o falla?). Anotar en `validation.md` los códigos y comportamientos
- [X] T012 **Checkpoint** (depende de T010 y T011): escribir en `validation.md` la conclusión de S-1, S-2, S-3 y S-5. Si el juez
  por defecto **no discrimina** o `instructor` no produce salida válida: parar y decidir con la persona responsable (otro modelo
  local por defecto, otro modo de `instructor`, o documentar que el defecto no sirve para decidir); si cambia el diseño,
  ejecutar `/loop-spec`. Si Langfuse se comporta distinto de lo previsto (R4), ajustar `contracts/external-interfaces.md` con
  `/loop-spec` antes de seguir

**Checkpoint**: fundamentos listos y diseño validado con un modelo real; las historias pueden empezar

---

## Phase 3: User Story 1 - Saber si las respuestas son fieles, pertinentes y están bien fundamentadas (Priority: P1) 🎯 MVP

**Goal**: una ejecución consulta al asistente real con cada caso de conocimiento, hace puntuar cada turno por el juez y escribe
el resumen y el detalle en CSV, con las medias por categoría y en conjunto (FR-001 a FR-006, FR-011, FR-015 a FR-019, FR-021 a
FR-024, FR-026)

**Independent Test**: con el stack levantado y la base indexada, `uv run python -m shoplab_eval quality --no-langfuse` produce
los dos CSV para los 96 casos de conocimiento y excluye los 24 de conversación; las medias del resumen se reproducen desde el
detalle; y una verificación a mano de tres casos (fiel y pertinente, con dato inventado, contesta otra cosa) puntúa en el
sentido esperado

### Tests for User Story 1 ⚠️

> **NOTE: escribirlas primero y comprobar que FALLAN antes de implementar**

- [X] T013 [P] [US1] `eval/tests/test_quality_scores.py` con valores fijados a mano: `classify_case` (conversación →
  `CONVERSATION`; sin documentos esperados → `UNANSWERED`; con ellos → `ANSWERED`); aplicabilidad (`ANSWERED`: las tres métricas;
  `UNANSWERED`: solo `faithfulness`; `CONVERSATION`: ninguna); `MetricStat`: media aritmética de los valores no `None`,
  `entered` = casos con valor, `empty` = casos donde aplica pero es `None`, `mean = None` si `entered = 0`; resumen `all` más una
  fila por categoría con casos medidos (`off_topic` solo con fidelidad; una categoría sin casos medidos no aparece); `excluded`
  cuenta los de conversación; un valor fuera de 0–1 lanza `MeasurementError` (no se recorta); ninguna media sale como `NaN`
- [X] T014 [P] [US1] `eval/tests/test_chat_client.py` contra `eval/tests/fake_backend.py` ampliado con `POST /api/chat` por SSE:
  respuesta = concatenación en orden de los `token`; `sources` con `number`, `title`, `section`, `text` en orden y `document_id`
  resuelto por título (`None` si no está en el corpus); **sin evento `sources` el contexto es vacío** y no es un error; turno
  válido solo si termina con `done`; evento `error` → `MeasurementError` con el id del caso y el `code`; HTTP 400/422/429/503
  antes del flujo → `MeasurementError` con el caso y el `code`; flujo cortado sin `done` → `MeasurementError`; cabecera
  `X-Request-Id` de la respuesta distinta de la enviada → `MeasurementError`; se envían `X-Request-Id` y
  `Accept: text/event-stream`, y el cuerpo es solo `{"message": …}` (**sin `conversationId`**, para no dejar conversaciones
  guardadas en el chat); caracteres con tildes y eñes íntegros; no se reintenta ningún turno
- [X] T015 [P] [US1] `eval/tests/test_ragas_judge.py` del adaptador con las clases de métrica de RAGAS sustituidas por dobles
  (sin red ni modelo): se llama con `user_input`, `response`, `retrieved_contexts` y `reference` según
  [contracts/external-interfaces.md](contracts/external-interfaces.md); `NaN` de `Faithfulness` → `None`; la abstención verdadera
  hace que **no** se llame a `Faithfulness`; el adaptador **solo traduce** (las reglas de contexto vacío son de T022, no de él);
  `RAGAS_DO_NOT_TRACK` queda en `true` antes de importar `ragas`; la clave nunca aparece en `repr` ni en mensajes
- [X] T016 [P] [US1] `eval/tests/test_quality_run.py` con dobles de asistente y juez inyectados: solo se consulta al asistente por
  los casos de conocimiento (los de conversación no); orden de las fases (todos los turnos antes de la primera llamada al juez);
  `request_id` = `eval-<run_id>-<id del caso>` (distinto por caso y por ejecución) y la llamada al asistente no recibe ningún
  `conversationId`; `trace_id` derivado; caso `ANSWERED` con abstención → `faithfulness` vacía y `abstained = true`, relevancia y
  precisión se calculan; caso `UNANSWERED` → solo fidelidad; **contexto vacío** → `context_precision = 0.0` sin llamar al juez;
  **sin contexto y sin abstención** → `faithfulness = 0.0` sin llamar al juez; sin contexto y con abstención → `faithfulness`
  vacía; la concurrencia del juez no supera la
  configurada; el informe mantiene el orden del dataset y cuenta los excluidos
- [X] T017 [P] [US1] `eval/tests/test_quality_report.py`: columnas exactas y orden de
  [contracts/quality-csv.md](contracts/quality-csv.md); una celda vacía nunca se escribe como `0` ni `NaN`; cuatro decimales y
  punto decimal; `\n` y UTF-8 sin BOM; tildes y eñes sin alterar; coma o comillas en una pregunta bien escapadas;
  `context_documents` con un id por fragmento y en orden (repetidos si dos fragmentos son del mismo documento) y `?<título>` si
  no es del corpus; **el CSV no contiene el texto de la respuesta ni el de los fragmentos**; recálculo: `_mean`, `_n` y `_empty`
  del resumen se reproducen desde el detalle para cada ámbito; `context_precision` es `0.0000` (no vacía) si
  `context_fragments = 0` en un caso `answered`, y `faithfulness` es `0.0000` si `context_fragments = 0` y `abstained = false`; el texto de pantalla incluye las medias, los excluidos y los vacíos
- [X] T018 [P] [US1] `eval/tests/test_quality_cli.py` (camino feliz, con dobles por la costura que defina `__main__.py`): `quality
  --no-langfuse` termina con código 0, escribe `quality-summary.csv` y `quality-detail.csv` en `--out` y no escribe nada fuera
  de esa carpeta; imprime el avance de cada fase por la salida de error y un resumen legible por la estándar; el subcomando
  figura en la ayuda; `validate` y `retrieval` siguen funcionando

### Implementation for User Story 1

- [X] T019 [US1] `eval/src/shoplab_eval/quality_scores.py` (depende de T013): `CaseKind` (`ANSWERED`, `UNANSWERED`,
  `CONVERSATION`), `CaseScores` (`faithfulness: float | None`, `answer_relevancy: float | None`, `context_precision: float |
  None`, `abstained: bool`; «`None` = no aplica o vacío por definición (celda vacía); un valor, entre 0 y 1; nunca `NaN`»),
  `MetricStat` (`mean: float | None`, `entered: int`, `empty: int`), `QualityScopeSummary` (`scope`, `cases`, `excluded`, una
  `MetricStat` por métrica y `abstained: int`); funciones puras `classify_case`, `applicable_metrics` y `summarize_quality`
  (ámbito `all` y uno por categoría con casos medidos). Sin red, sin RAGAS
- [X] T020 [P] [US1] `eval/src/shoplab_eval/chat_client.py` (depende de T006 y T014): `ContextItem` (`number`, `title`, `section`,
  `text`, `document_id: str | None`), `ChatTurn` (`request_id`, `answer`, `context: tuple[ContextItem, ...]` — «`context` vacío
  si no hubo evento `sources`»; solo existe si el flujo terminó con `done`), el `Protocol` `ChatClient` con
  `ask(question, request_id) -> ChatTurn` (cuerpo `{"message": …}`, sin `conversationId`) y `SseChatClient` con `urllib.request` (sin dependencias, sin
  reintentos) que lee el flujo línea a línea; todo fallo es `MeasurementError` (conexión rechazada o tiempo agotado:
  «No se pudo conectar con el backend en …»). Resolver `document_id` con `title_index`
- [X] T021 [P] [US1] `eval/src/shoplab_eval/quality_judge.py` (depende de T015): `JudgeSettings` (`model`, `base_url`,
  `temperature`, `timeout`, `max_input_chars`, `concurrency`; la clave **no** es un campo), el `Protocol` `Judge` con
  `abstains(question, answer) -> bool`, `faithfulness(...)`, `answer_relevancy(...)` y `context_precision(...)` asíncronos, y
  `RagasJudge`: fija `RAGAS_DO_NOT_TRACK=true` **antes** de importar `ragas` (importación diferida dentro del constructor), crea
  `AsyncOpenAI(base_url, api_key)` y `llm_factory(model, client=…)`, `Faithfulness(llm)`, `AnswerRelevancy(llm, embeddings)` con
  los embeddings del nombre lógico `embeddings` y `ContextPrecision(llm)`, con `temperature` y `seed` fijos por petición; `NaN`
  → `None`; la comprobación de abstención como una petición estructurada mínima (`{abstains: bool}`) con
  `ABSTENTION_PROMPT_VERSION`. Si `ragas` no está instalado: `MeasurementError` que indica `uv sync`
- [X] T022 [US1] `eval/src/shoplab_eval/quality_run.py` (depende de T019, T020, T021 y T008): `RunInfo`, `QualityCaseResult` y
  `QualityReport` (data-model; `langfuse` = `SKIPPED` por ahora), las identidades derivadas (`request_id` y `trace_id`) y `measure_quality(cases, chat, judge,
  settings, run_id, …)` en **dos fases**: (1) un turno por caso de conocimiento en el orden del dataset, uno a uno, con avance
  `[n/total] <id>`; (2) el juez con `asyncio` y un semáforo de `concurrency`: abstención primero y, si no abstiene,
  fidelidad; relevancia y precisión solo en `ANSWERED`. **Aquí viven las reglas de contexto vacío** (research R6, data-model
  «Reglas»), como funciones puras: sin contexto, `context_precision = 0.0` y, si no abstiene, `faithfulness = 0.0`, en ambos casos
  sin llamar al juez; con abstención, `faithfulness` vacía. Los campos de `RunInfo` que otras historias rellenan (modelo real del
  juez, embeddings, modelo del asistente) valen `unknown` hasta T030. Un valor fuera de 0–1 es `MeasurementError`. Los de conversación se cuentan como excluidos y no se consultan
- [X] T023 [US1] `eval/src/shoplab_eval/quality_report.py` (depende de T017 y T022): `write_quality_report(report, out_dir)` que
  crea la carpeta y escribe `quality-summary.csv` y `quality-detail.csv` **solo con el informe completo**, con las columnas y el
  formato de [contracts/quality-csv.md](contracts/quality-csv.md) (UTF-8 sin BOM, `\n`, cuatro decimales, `|` entre ids; celda
  vacía para `None`; sin textos) y `format_quality_report(report)` con las medias, los vacíos, las abstenciones y los excluidos
- [X] T024 [US1] Subcomando `quality` en `eval/src/shoplab_eval/__main__.py` (depende de T018, T022 y T023): opciones `--base-url`
  (`$SHOPLAB_EVAL_BASE_URL` o `DEFAULT_BASE_URL`), `--out`, `--golden`, `--kb`, `--run-id` (`RUN_ID_PATTERN`; por defecto la
  marca de tiempo UTC `YYYYMMDDTHHMMSSZ`), `--concurrency`, `--turn-timeout`, `--assistant-model`; construye el cliente del chat
  y el juez con la configuración por defecto, llama a `load_golden`, `measure_quality` y `write_quality_report`, e imprime el
  informe. Códigos de salida de [contracts/quality-csv.md](contracts/quality-csv.md): `0` completo, `1` medición no válida, `2`
  uso incorrecto. Los flags del juez y de Langfuse los añaden US2 y US3
- [X] T025 [P] [US1] `eval/tests/test_judge_calibration.py` con `@pytest.mark.judge` (no se ejecuta por defecto): usa
  `RagasJudge` real y `eval/tests/calibration_pairs.py` (T009) y comprueba SC-002 (fidelidad del dato inventado < la de la fiel
  en los 5 pares) y SC-003 (relevancia menor en la respuesta que contesta otra cosa; precisión mayor con el útil primero).
  Saltar con un mensaje claro si no hay `SHOPLAB_EVAL_JUDGE_API_KEY`. Ejecutar con `uv run pytest -m judge`
- [X] T026 [US1] (manual) Medición real sin Langfuse: [quickstart.md](quickstart.md) pasos 3, 4 y 5 (`uv run pytest -m judge`,
  `uv run python -m shoplab_eval quality --no-langfuse` y comprobación del resumen contra el detalle en una hoja de cálculo).
  Anotar en `validation.md`: tiempo total y tiempo por llamada al juez (**S-4**, SC-001), medias obtenidas, número de
  abstenciones y de vacíos por métrica, y revisión a mano de tres casos. Si los 96 casos no caben en 30 minutos en la máquina de
  referencia, ejecutar `/loop-spec` para reformular SC-001 (p. ej. «con GPU» o un tiempo por llamada) antes de seguir

**Checkpoint**: la historia 1 produce un resultado completo con el juez por defecto; ya sirve como entrada de LAB-30

---

## Phase 4: User Story 2 - Un juez configurable y registrado (Priority: P1)

**Goal**: quien ejecuta elige el juez por configuración, el resultado dice con qué juez se calculó (incluido el modelo real
detrás del alias) y la clave nunca se muestra (FR-007 a FR-010 parcial, FR-018)

**Independent Test**: ejecutar con el juez por defecto y con otro modelo cambiado solo por configuración; cada
`quality-summary.csv` nombra su juez y su modelo real, y no hubo que editar código

### Tests for User Story 2 ⚠️

- [X] T027 [P] [US2] `eval/tests/test_quality_config.py`: precedencia flag > variable de entorno > valor por defecto para el
  modelo, la URL, la temperatura y la concurrencia; valores no válidos con mensaje que explica el valor aceptado y **código 2
  antes de consultar nada**: modelo juez vacío, `--judge-timeout` ≤ 0, `--judge-temperature` fuera de 0–2, `--concurrency`
  fuera de 1–16, `--max-input-chars` ≤ 0, `--run-id` que incumple `[A-Za-z0-9._-]` de 1 a 32 caracteres; la clave solo se lee de
  `SHOPLAB_EVAL_JUDGE_API_KEY` (no existe flag), y si falta el error nombra la variable; la clave no aparece en `repr`,
  `str`, mensajes de error, salida estándar ni de error (comprobar con una clave centinela)
- [X] T028 [P] [US2] `eval/tests/test_judge_gateway.py` contra un `http.server` local que simula el gateway: `GET /models` con
  la clave; 401 → `MeasurementError` que nombra el gateway y la clave; conexión rechazada → error que nombra el gateway; el
  modelo juez ausente de la lista → error que nombra el modelo; `GET /model/info` devuelve el modelo real
  (`litellm_params.model`) de la entrada cuyo `model_name` es el pedido; si no se puede leer, devuelve `unknown` y emite un
  aviso, sin error; la clave viaja solo en la cabecera `Authorization`

### Implementation for User Story 2

- [X] T029 [US2] Ampliar `eval/src/shoplab_eval/quality_judge.py` (depende de T027 y T028): `resolve_judge_settings(args, env)`
  con la precedencia y las validaciones de T027 (errores de uso: `ValueError` con el valor aceptado), `read_judge_api_key(env)` y
  `GatewayClient` (`urllib`) con `check_ready(model) -> None` y `underlying_model(model) -> str`. El nombre lógico y el modelo
  real se registran por separado (`judge_model` y `judge_underlying_model`)
- [X] T030 [US2] Conectar en `eval/src/shoplab_eval/__main__.py` y `quality_run.py` (depende de T029): flags `--judge-model`,
  `--judge-base-url`, `--judge-temperature`, `--judge-timeout`, `--max-input-chars`; la comprobación del gateway y del modelo
  juez corre **antes** de consultar al asistente; `RunInfo` lleva `judge_underlying_model`, `ragas_version`
  (`importlib.metadata`), `abstention_prompt_version`, `assistant_model` (`--assistant-model`, `SHOPLAB_EVAL_ASSISTANT_MODEL` o
  `unknown`; la traza lo completa en US3; el backend no lo expone) y `embedding_model` (de `GET /api/knowledge/search` con `KnowledgeSearchClient` de
  LAB-28, una sola consulta), y `quality-summary.csv` los escribe en las columnas de
  [contracts/quality-csv.md](contracts/quality-csv.md). Un `unknown` se avisa por pantalla e indica cómo fijar el valor (`--assistant-model` o `SHOPLAB_EVAL_ASSISTANT_MODEL`)
- [X] T031 [P] [US2] `docs/gateway-de-modelos.md`: sección corta «Juez de evaluación» con el nombre lógico `judge`
  (`LITELLM_JUDGE_MODEL`, por defecto el modelo de chat), la clave virtual `eval` (solo `judge` y `embeddings`, presupuesto
  propio), cómo cambiar el juez (`LITELLM_JUDGE_MODEL=… docker compose … up -d litellm`) y por qué el juez no es el modelo del chat (S-1: el 3B no rellena las salidas estructuradas) y `OLLAMA_JUDGE_MODEL`, que hace que Ollama descargue `qwen2.5:7b`; el aviso de sesgo de un juez que no sea el que responde
  como juez y respondedor (research R2). Añadir al cuadro de archivos si hace falta
- [X] T032 [US2] (manual) [quickstart.md](quickstart.md) pasos 2 y 8: la clave `eval` ve solo `judge` y `embeddings`;
  cambiar `LITELLM_JUDGE_MODEL` y repetir la medición produce un `judge_underlying_model` distinto con el mismo `judge_model`;
  `--judge-model chat` falla con un error que nombra el modelo, antes de consultar al asistente. Anotar en `validation.md`

**Checkpoint**: las historias 1 y 2 funcionan; el resultado es comparable por juez

---

## Phase 5: User Story 3 - Las puntuaciones quedan en la traza de cada respuesta (Priority: P1)

**Goal**: las puntuaciones de cada turno se adjuntan a su traza de Langfuse, reconocibles como de evaluación y separadas por
ejecución, sin duplicarse, y un fallo al adjuntar conserva el CSV (FR-010 parcial, FR-012 a FR-014)

**Independent Test**: con las trazas del chat activadas, `uv run python -m shoplab_eval quality --run-id demo-1`; en Langfuse,
la traza de un caso fallado muestra su prompt, sus fragmentos y las puntuaciones con los valores del CSV; repetir con el mismo
`--run-id` no duplica; otra ejecución no pisa las anteriores

### Tests for User Story 3 ⚠️

- [X] T033 [P] [US3] Ampliar `eval/tests/test_langfuse_client.py` contra un `http.server` local que simula Langfuse:
  comprobación de acceso con `GET /api/public/projects` y autenticación Basic (`public:secret`); 401/403 → `MeasurementError` de configuración;
  `wait_for_trace` consulta `GET /api/public/v2/observations?traceId=<id>&limit=1` y reintenta mientras `data` viene vacío, devuelve cuando hay una observación, y agota `TRACE_WAIT_SECONDS` con un error que nombra la
  traza; `publish_score` envía `{id, traceId, name, value, dataType: "NUMERIC", comment, metadata}` con `metadata.run_id`,
  `case_id` y `judge_model`; `score_id` = `SHA-256("<run_id>:<id del caso>:<métrica>")` en hexadecimal, **primeros 32
  caracteres**, determinista (repetir da el mismo `id`); 4xx/5xx → error acotado, sin reintento; el modelo del asistente se lee
  de la observación `generation` de la traza (campo `model` de `v2/observations`, a confirmar en T038 con una traza real del backend; si no viene, `unknown`); las claves no aparecen en ningún mensaje
- [X] T034 [P] [US3] `eval/tests/test_quality_attach.py` con dobles: la **primera consulta hace de sonda** y, si su traza no
  aparece, termina con error **antes** de los demás turnos y de cualquier llamada al juez; se publica un score por métrica con
  valor y **ninguno por una celda vacía**; un caso `UNANSWERED` publica solo `faithfulness`; un fallo de publicación se acumula,
  el CSV se escribe completo con `langfuse = no` en esos casos y `langfuse = partial` en el resumen, y el resultado pide código
  de salida 1; sin claves y sin `--langfuse` → `skipped` y exit 0; `--langfuse` explícito sin claves → error de uso (código 2);
  `--no-langfuse` aunque haya claves → `skipped`; dos ejecuciones con distinto `run_id` producen trazas y scores distintos

### Implementation for User Story 3

- [X] T035 [US3] Ampliar `eval/src/shoplab_eval/langfuse_client.py` (depende de T033): el `Protocol` `TraceScores` y
  `LangfuseClient` con `urllib` (`check_access` con `/api/public/projects`, `wait_for_trace` y `trace_model` con `/api/public/v2/observations`, `publish_score` con `/api/public/scores`; **no** usa `/api/public/traces`, que no existe en Langfuse 4.77.0 `events_only`), la función `score_id` y la
  construcción de la autenticación Basic. Las claves y el host salen de `SHOPLAB_EVAL_LANGFUSE_*`; nunca en mensajes
- [X] T036 [US3] Ampliar `eval/src/shoplab_eval/quality_run.py` (depende de T034 y T035): tras el primer turno, esperar su traza
  (sonda) y fallar si no aparece; completar `assistant_model` desde la traza si no se indicó; fase 3 «adjuntar» que, por caso,
  espera su traza y publica sus scores acumulando los fallos; `QualityCaseResult.langfuse` y `RunInfo.langfuse`
  (`attached`, `partial` o `skipped`); el informe se escribe **después** de adjuntar
- [X] T037 [US3] Conectar en `eval/src/shoplab_eval/__main__.py` y `quality_report.py` (depende de T036): flags `--langfuse` y
  `--no-langfuse`; la columna `langfuse` del detalle y del resumen; la comprobación de acceso a Langfuse antes de consultar al
  asistente; código de salida 1 con el CSV ya escrito si alguna puntuación no se adjuntó, y el mensaje lista los casos
- [ ] T038 [US3] (manual) [quickstart.md](quickstart.md) pasos 6 y 7 con el perfil `llmops` y las trazas del chat activadas:
  `--run-id demo-1` y luego `--run-id demo-2`. Comprobar en `http://localhost:3001`: una traza por caso filtrable por el
  metadato `request_id` que empiece por `eval-demo-1-`, con las puntuaciones del CSV; repetir `demo-1` no duplica; `demo-2` no
  pisa `demo-1`; parar Langfuse a mitad del cierre deja el CSV completo y exit 1. Anotar en `validation.md`

**Checkpoint**: las historias 1 a 3 funcionan; cada respuesta mala se abre en Langfuse con su puntuación

---

## Phase 6: User Story 4 - Distinguir una respuesta mala de una medición inválida (Priority: P2)

**Goal**: cuando el entorno no permite un resultado fiable, la medición se detiene con la causa, sin CSV y con código distinto de
cero; una respuesta mala puntúa bajo y sigue (FR-003, FR-010, FR-011, FR-020, FR-021)

**Independent Test**: ejecutar con el backend apagado, con el gateway apagado y con un dataset inválido; en cada caso termina con
error que explica la causa, código distinto de cero y sin CSV

### Tests for User Story 4 ⚠️

- [X] T039 [P] [US4] `eval/tests/test_quality_failures.py`: backend apagado → error «No se pudo conectar con el backend en …»
  sin llamadas al juez; el asistente falla en un caso (evento `error`, HTTP 503, flujo cortado) → error que nombra el caso y
  **no** se cuenta como respuesta de baja calidad; el juez no responde, devuelve salida ilegible o el cuerpo supera
  `max_input_chars` → error que nombra el **caso y la métrica**, nunca un cero; dataset inválido → se reproducen los fallos
  `G-…` de LAB-27 y no se mide; **orden de las comprobaciones previas**: gateway/juez y Langfuse se comprueban antes de consultar
  al asistente, y el índice (documentos esperados `INDEXED`, con `check_index` de LAB-28) antes de cualquier turno; en todos los
  casos de error no se escribe ningún CSV **ni se tocan los de una ejecución anterior**; una puntuación baja no es un error
  (código 0); el código de salida es 1 en medición inválida y 2 en uso incorrecto

### Implementation for User Story 4

- [X] T040 [US4] Ampliar `eval/src/shoplab_eval/quality_run.py` (depende de T039): función de comprobaciones previas en el orden
  de T039 (backend → gateway y modelo juez → Langfuse si se pidió → índice con `check_index` y `KnowledgeSearchClient.list_documents`
  → sonda); envolver todo fallo del juez en `MeasurementError` con caso y métrica; comprobar `max_input_chars` sobre pregunta +
  respuesta + contexto + referencia antes de llamar al juez
- [X] T041 [US4] Ampliar `eval/src/shoplab_eval/__main__.py` (depende de T040): mapear `InvalidGoldenError`, `MeasurementError`,
  `FileNotFoundError` y `ValueError` a mensajes por la salida de error y a los códigos de salida; asegurar que
  `write_quality_report` no se invoca si hubo error y que la escritura no deja archivos a medias (escribir ambos CSV solo con el
  informe completo)
- [ ] T042 [US4] (manual) [quickstart.md](quickstart.md) paso 9: provocar cada error de la tabla (backend apagado, gateway
  apagado o clave inválida, trazas apagadas con `--langfuse`, dataset inválido, `--concurrency 0`, Langfuse parado a mitad del
  cierre) y comprobar mensaje, código de salida y ausencia de CSV nuevos. Anotar en `validation.md`

**Checkpoint**: las cuatro historias funcionan; un entorno roto no se confunde con un asistente que responde mal

---

## Phase 7: DEV en `shoplab-infra` (`make up` debe funcionar)

**Purpose**: DEV es el entorno local del equipo y la configuración de su gateway **se sincroniza desde `infra/litellm/`** de
este repo (research R9). El requisito es que **todo el clúster local funcione** tras `make up` y `make seed`, con Ollama como
único componente fuera (en el Docker del host, como ya hace el overlay `dev`), y que `make quality` mida contra él sin pasos
manuales. Estas tareas añaden lo de LAB-29 y verifican y corrigen el resto.
Van en **`../shoplab-infra`** (rama nueva desde `main` y PR propia; comprobar `git status` y no tocar cambios locales sin
commitear que haya en ese clon), salvo T043 y T044, que son de `eval/`.

**⚠️ Orden**: Argo CD sincroniza desde `main`, así que lo de `shoplab-infra` solo llega al clúster al fusionar su PR, **después**
de fusionar la de este repo (el `files/SOURCE` de T045 registra el commit de origen). Dependen de US1–US3 (usan el subcomando
`quality`) y de T004 confirmado en un commit.

- [X] T043 [P] Pruebas de la cabecera `Host` para DEV, en `eval/tests/test_chat_client.py`, `eval/tests/test_search_client.py`,
  `eval/tests/test_quality_cli.py` y `eval/tests/test_retrieval_cli.py`: con `--host-header shoplab.localtest.me` (o
  `SHOPLAB_EVAL_HOST_HEADER`) **todas** las peticiones al backend (chat, búsqueda y catálogo de documentos) llevan
  `Host: shoplab.localtest.me` aunque la URL sea `http://127.0.0.1:8080` (comprobarlo en el `http.server` de prueba); sin la opción
  no se envía ninguna cabecera `Host` propia; precedencia flag > variable de entorno; un valor vacío o con espacios o saltos de
  línea es un error de uso (código 2)
- [X] T044 Implementar `--host-header` (depende de T043): `HOST_HEADER_ENV = "SHOPLAB_EVAL_HOST_HEADER"` en
  `eval/src/shoplab_eval/constants.py`; parámetro `host_header: str | None` en `SseChatClient` (`chat_client.py`) y en
  `KnowledgeSearchClient` (`search_client.py`, de LAB-28), que lo envían como cabecera `Host`; la opción en los subcomandos
  `quality` y `retrieval` de `__main__.py`. Las pruebas de LAB-28 siguen en verde
- [X] T045 [P] `../shoplab-infra`: el clon puede estar en una rama antigua ya fusionada (en la revisión de este plan estaba en
  `feature/LAB-84-litellm-model-gateway`, dos commits por detrás de `origin/main`, que despliega v0.18.0): `git fetch`, `git
  switch main && git pull --ff-only`, comprobar `git status` limpio, crear la rama nueva desde ahí y ejecutar
  `scripts/sync-litellm-files.sh ../shoplab-helpdesk` **con `infra/litellm/` de este repo ya confirmado** (T004 en un commit): si
  `k8s/base/litellm/files/SOURCE` dice «con cambios sin confirmar» no se acepta. Confirmar que `files/config.yaml` trae el modelo
  `judge` y `files/keys.yaml` la clave `eval-${GATEWAY_ENVIRONMENT}`, y que `make kustomize policies` pasa
- [X] T046 [P] `../shoplab-infra`: `k8s/base/litellm/job-bootstrap-keys.yaml` añade `LITELLM_KEY_EVAL` con `secretKeyRef` a
  `litellm-credentials` (clave `LITELLM_KEY_EVAL`, **no** opcional: sin ella el Job no puede crear la clave `eval-dev`), junto a
  las de backend e ingest; actualizar el comentario de cabecera de `k8s/base/litellm/externalsecret.yaml` con la lista de claves
  del JSON (el `dataFrom.extract` ya las trae todas, el recurso no cambia). `make kustomize policies prom-rules` en verde
- [X] T047 [P] `../shoplab-infra`: añadir `LITELLM_KEY_EVAL` al JSON `litellm` de `terraform/envs/dev/secrets.auto.tfvars.example`
  (con la nota del formato `sk-…` y sin marcadores `change-me`/`local` en dev), al `TF_VAR_secrets` de ejemplo de `scripts/lib.sh`
  y a los datos de prueba de `terraform/envs/dev/tests/apply.tftest.hcl` y `terraform/modules/secrets/tests/secrets.tftest.hcl`.
  `make fmt-check validate tf-test` en verde
- [X] T048 `../shoplab-infra`: pruebas de `scripts/ensure-secrets.sh` en `scripts/tests/ensure-secrets.test.sh` (estilo de
  `dev-deploy-only.test.sh`: archivos temporales, sin Docker ni Terraform) enganchadas a `make test-ci-scripts`. Casos: un archivo
  con solo `postgres-password`, `llm-api-key` y `llm-model` (el que hay hoy) → añade `langfuse` (las diez claves de
  `docs/observability.md`) y `litellm` (`LITELLM_MASTER_KEY`, `LITELLM_KEY_BACKEND`, `LITELLM_KEY_INGEST`, `LITELLM_KEY_EVAL` y
  `POSTGRES_PASSWORD`) con valores aleatorios y **no cambia ni una línea previa**; los valores de Langfuse siguen los formatos de
  `docs/observability.md`: `ENCRYPTION_KEY` de 64 hexadecimales (`openssl rand -hex 32`), `LANGFUSE_INIT_PROJECT_PUBLIC_KEY` y
  `LANGFUSE_INIT_PROJECT_SECRET_KEY` con prefijos `pk-lf-` y `sk-lf-`, y ningún valor con caracteres especiales; un `litellm`
  existente sin `LITELLM_KEY_EVAL`
  → la inserta sin tocar las demás claves; un archivo completo → no cambia nada (mismo hash) y no informa de añadidos; los
  valores de las claves del gateway empiezan por `sk-` y no contienen `change-me` ni `local`; **no imprime nunca un valor**, solo
  nombres; archivo inexistente → error que remite al ejemplo; formato irreconocible → error que explica qué añadir a mano, sin
  modificar el archivo; con `TF_VAR_secrets` definida no toca ningún archivo y solo comprueba las claves requeridas, nombrando
  las que falten
- [X] T049 `../shoplab-infra`: `scripts/ensure-secrets.sh` (depende de T048) según research R9: solo añade; valores aleatorios con
  `openssl rand -hex` (o `/dev/urandom` si no hay `openssl`); inserta antes del cierre del bloque `secrets = { … }` y, para
  `litellm`, dentro del JSON existente. Objetivo `secrets` en el `Makefile` (`.PHONY`) y llamada desde `scripts/up.sh` **antes** de
  levantar Floci, tras comprobar que existe el archivo o `TF_VAR_secrets`; `up.sh` muestra los nombres que añadió. `up.sh` avisa
  (sin fallar) si Ollama no responde en `127.0.0.1:11434` y dice cómo levantarlo (`docker compose -f infra/docker-compose.yml -f
  infra/docker-compose.ollama-host.yml up -d ollama` en `shoplab-helpdesk`): sin él, el clúster arranca pero no hay chat ni embeddings
- [X] T050 [P] `../shoplab-infra`: `scripts/forward.sh` añade los túneles `forward litellm shoplab-dev litellm 4000 4000` y
  `forward langfuse shoplab-dev langfuse-web 3001 3000` (los mismos puertos que el compose de este repo, así que los valores por
  defecto de `eval/` valen) y sus líneas del resumen impreso; si el servicio aún no existe solo avisa, como los de
  observabilidad. Comprobar que `forward.sh stop` también los detiene
- [X] T051 `../shoplab-infra`: `scripts/quality.sh` y objetivo `quality` del `Makefile` (como `seed.sh` y `seed`; `.PHONY`;
  argumentos extra con `ARGS="…"`; depende de T044 y T050): comprueba `kubectl`, `uv`, el kubeconfig y los túneles 8080, 4000 y
  3001 (si faltan, indica `make forward`) y que Ollama del host responde (`/api/tags`) con `llama3.2:3b`, `llama3.2:1b` y `bge-m3`, y `qwen2.5:7b` (el juez; `OLLAMA_JUDGE_MODEL`)
  (si no, indica el comando para levantarlo y el `ollama pull` que falte); lee del clúster, **sin imprimirlas**, `LITELLM_KEY_EVAL` de `litellm-credentials` y
  las claves de `backend-langfuse` (`SHOPLAB_CHAT_TRACING_PUBLIC_KEY` y `SECRET_KEY`); exporta `SHOPLAB_EVAL_BASE_URL=http://127.0.0.1:$INGRESS_PORT`,
  `SHOPLAB_EVAL_HOST_HEADER=shoplab.localtest.me`, `SHOPLAB_EVAL_JUDGE_BASE_URL=http://127.0.0.1:4000/v1`,
  `SHOPLAB_EVAL_JUDGE_API_KEY`, `SHOPLAB_EVAL_LANGFUSE_HOST=http://127.0.0.1:3001`, `SHOPLAB_EVAL_LANGFUSE_PUBLIC_KEY`,
  `SHOPLAB_EVAL_LANGFUSE_SECRET_KEY` y `SHOPLAB_EVAL_ASSISTANT_MODEL` (`${ASSISTANT_MODEL:-llama3.2:3b}`, el modelo de chat por
  defecto del gateway; documentar cómo cambiarlo) y ejecuta `uv run python -m shoplab_eval quality "$@"` en
  `$HELPDESK_DIR/eval` (por defecto `../shoplab-helpdesk`, como `KB_DIR` en `seed.sh`)
- [X] T052 [P] `../shoplab-infra`: documentación: `docs/gateway-de-modelos.md` (el nombre lógico `judge`, la clave `eval` y su Job,
  el túnel a LiteLLM), `docs/observability.md` (las puntuaciones en las trazas de DEV, el túnel a Langfuse en 3001 y el filtro por
  `eval-<run>-`), `docs/terraform-local.md` (`make secrets`: qué añade y que nunca cambia valores existentes) y `README.md`
  (objetivos `secrets` y `quality`, y el orden de despliegue: PR de este repo → sync → PR de infra → `make down && make up` →
  `make seed` → `make quality`)
- [ ] T053 (manual) DEV de punta a punta, **todo el clúster** (quickstart paso 12), con Docker Desktop en marcha (≥ 16 GB de RAM y
  4 CPU para Docker, `docs/terraform-local.md`), Ollama del host arriba con `llama3.2:3b`, `llama3.2:1b`, `bge-m3` y `qwen2.5:7b` (`OLLAMA_JUDGE_MODEL`), y ambas PR
  fusionadas: `make down && make secrets && make up && make seed && make quality`. Anotar en `validation.md` el resultado de **cada**
  comprobación (el requisito es que todo lo ya implementado funcione, con Ollama como único componente fuera del clúster):
  - clúster: Argo CD con todas las aplicaciones `Synced` y `Healthy`; todos los pods de `shoplab-dev` y `monitoring` en
    `Running` y `Ready`, sin `CrashLoopBackOff` ni `Pending` por falta de memoria (Langfuse pide unos 2,8 GiB: LAB-64 dejó
    pendiente medir su coste real en el nodo; anotar el uso)
  - secretos: `make secrets` por segunda vez no cambia nada y `postgres-password` conserva su valor; todos los `ExternalSecret`
    (`litellm-credentials`, `backend-litellm`, `langfuse-credentials`, `backend-langfuse` y el de Postgres) en `SecretSynced`
  - gateway (LAB-84): Job `litellm-bootstrap-keys` en `Complete` con `backend-dev`, `ingest-dev` y `eval-dev`; sin avisos
    `model_missing`; `GET /v1/models` con la clave `eval` lista solo `judge` y `embeddings`; un `POST /api/chat` real responde
    por el gateway
  - Ollama del host: lo alcanza el gateway (`172.17.0.1:11434`) y no lo alcanzan directamente backend ni ingest (`NetworkPolicy`)
  - trazas (LAB-64): un turno de chat deja su traza en Langfuse (túnel 3001, entorno `dev`) con prompt, fragmentos y tokens
  - base de conocimiento: `make seed` carga los 24 documentos y quedan indexados; el frontend carga en
    `http://shoplab.localtest.me:8080`
  - túneles: LiteLLM y Langfuse responden **a pesar de la `NetworkPolicy`**
  - `make quality`: código 0, CSV con `langfuse = attached`, puntuaciones en las trazas, y funciona sin conexión a Internet
  - `make check` de `shoplab-infra` en verde, y S-4 medido en DEV (el Ollama del host puede rendir distinto del del compose)
- [ ] T054 `../shoplab-infra` (y `infra/` de este repo si procede): corregir **dentro de esta historia** todo lo que haya fallado en
  T053, sea por nuestros cambios o porque ya estuviera roto en DEV (regla del proyecto: la infraestructura necesaria se hace en
  la historia). Un commit atómico por problema, con prueba o comprobación de `make check` cuando se pueda; repetir T053 desde
  `make down` hasta que todo pase; anotar en `validation.md` cada problema, su causa y su arreglo. Lo que no se pueda resolver sin
  una decisión (p. ej. el nodo no tiene memoria para Langfuse) se consulta con la persona responsable y se anota

**Checkpoint**: tras `make up` y `make seed` el clúster local completo funciona (con Ollama en el Docker del host) y `make quality`
mide contra él sin pasos manuales

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: documentación, ADR, CI y verificación final

- [ ] T055 [P] `eval/README.md`: sección «Calidad de respuesta» (FR-025) con qué mide cada métrica con un ejemplo, cómo ejecutar
  (`quality`) y qué necesita (stack, índice, clave `eval`, trazas), la tabla de opciones, cómo elegir y cambiar el juez, cómo ver las
  puntuaciones en Langfuse (filtro `eval-<run>-`), cómo leer los dos CSV (vacío ≠ cero, `abstained`), cómo estimar el ruido
  (repetir la ejecución), cómo medir con otro `--out` para comparar, la advertencia del juez que es también el asistente, la
  reserva de las respuestas de referencia en borrador (que el resultado **no** registra), indicar `--assistant-model` en toda
  ejecución que vaya a compararse, la advertencia de que las métricas Prometheus del backend sí cuentan los turnos de evaluación
  (solo las trazas se distinguen) y de que no se envía `conversationId`, cómo medir contra DEV (`make quality` en `shoplab-infra`, `--host-header`) y una tabla de errores («Qué ves / Causa / Qué hacer»). Actualizar la tabla de
  archivos y el párrafo inicial (LAB-29 ya no está pendiente)
- [ ] T056 [P] `docs/adr/0026-calidad-de-respuesta-con-ragas.md` (plantilla de `docs/adr/`): RAGAS 0.4.4 frente a DeepEval y a un
  juez propio, juez por el gateway con nombre lógico `judge` y clave `eval` (también en DEV, cuya configuración se sincroniza desde
  `infra/litellm/`, y el completado de secretos de `make up`), turno obtenido del SSE sin tocar el backend,
  correlación por `X-Request-Id` y `traceId` derivado, abstención como vacío con contador, y las consecuencias (dependencias
  pesadas, sesgo del juez, no determinismo). Añadir la fila a `docs/adr/README.md`
- [ ] T057 [P] Comprobar `.github/workflows/ci.yml`: el trabajo `eval` instala con `uv sync --frozen`, que con `default-groups`
  incluye `ragas`; confirmar que sigue en verde y anotar el tiempo de instalación. Si el cambio de `infra/litellm/` o de `eval/`
  no activa los filtros esperados, ajustarlos. Comprobar también que el CI de `shoplab-infra` (`make check`) pasa con los
  cambios de la fase 7. No añadir una ejecución del juez a CI (es de LAB-30)
- [ ] T058 Verificación local completa: `cd eval && uv run ruff check . && uv run pytest` con cobertura ≥ 80 % (y `uv run pytest
  -m judge` con el stack), la suite de `infra/litellm` en verde, y `uv run python -m shoplab_eval validate` y `retrieval` sin
  regresiones
- [ ] T059 (manual) [quickstart.md](quickstart.md) pasos 10 y 11 y SC-010: provocar una respuesta peor (p. ej. un prompt local que
  permita inventar) y comprobar que la fidelidad media baja y el detalle señala los casos; repetir la medición dos veces y
  anotar la diferencia de medias (objetivo ≤ 0,05, SC-009); pedir a otra persona que ejecute la medición, cambie el juez y
  encuentre la traza de un caso siguiendo solo el `README` (< 15 minutos). Si no se cumple una tolerancia, `/loop-spec`
- [ ] T060 Cierre de las ramas (este repo y `shoplab-infra`): `git status --short` sin archivos sueltos (`*.orig`, `*.log`, `target/`, scripts del scratchpad),
  `git diff --staged` revisado, completar `validation.md` con el resultado de cada criterio de éxito SC-001 a SC-010 y las
  reservas conocidas. No hay endpoints HTTP nuevos ni cambiados, así que **no** se genera `requests.http`; no hay
  propiedades de Spring nuevas (las variables `SHOPLAB_EVAL_*` están en `.env.example` y en el `README`). Los commits siguen
  Conventional Commits, atómicos; el título de la PR de este repo, `feat(eval): [LAB-29] métricas de calidad de respuesta
  (fidelidad, relevancia y precisión del contexto)`, y el de la PR de `shoplab-infra`, `feat(dev): [LAB-29] juez de evaluación y
  `make quality` en dev` (comprobar su convención en el workflow `pr-title` de ese repo). Fusionar primero la de este repo

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T002, T003 y T004 son paralelos tras T001
- **Foundational (Phase 2)**: depende de Setup. T005–T009 y T011 son paralelizables entre sí (T006 sigue a T005, T008 a T007);
  T010 necesita T002, T004 y T009; T012 cierra el bloque y **bloquea todas las historias**
- **US1 (Phase 3)**: depende de T012. Es el MVP
- **US2 (Phase 4)**: depende de US1 (amplía `quality_judge.py`, `quality_run.py` y `__main__.py`)
- **US3 (Phase 5)**: depende de US1 y puede ir en paralelo con US2 salvo los archivos compartidos (`__main__.py`,
  `quality_run.py`): conviene hacerla después
- **US4 (Phase 6)**: depende de US1 a US3 (ordena las comprobaciones previas de las tres)
- **DEV (Phase 7)**: depende de US1 a US3 (usa el subcomando `quality`) y de T004 en un commit. T043 y T044 son de `eval/`;
  el resto, de `../shoplab-infra`. T045, T046, T047, T050 y T052 son paralelizables entre sí (archivos distintos); T049 sigue a
  T048 y T051 a T044 y T050; T053 verifica el clúster completo y necesita ambas PR fusionadas, y T054 corrige lo que falle y repite T053
- **Polish (Phase 8)**: depende de las historias que se vayan a entregar y de la fase 7

### User Story Dependencies

- **US1 (P1)**: tras Foundational; sin dependencias de otras historias
- **US2 (P1)**: tras US1; independiente en lo funcional (el resultado ya funciona con el juez por defecto)
- **US3 (P1)**: tras US1; independiente en lo funcional (`--no-langfuse` y la ausencia de claves la dejan inactiva)
- **US4 (P2)**: tras US1 a US3; los fallos propios de cada historia ya se prueban en ella, esta fija el orden y los códigos

### Within Each User Story

- Las pruebas se escriben primero y deben fallar
- Tipos y funciones puras antes de los clientes; clientes antes de la orquestación; orquestación antes del CSV; CSV antes del CLI
- Cada historia termina con una tarea manual contra el stack real

### Parallel Opportunities

- Setup: T002, T003 y T004
- Foundational: T005, T007, T009 y T011 juntas; luego T006 y T008
- US1 pruebas: T013 a T018 (seis archivos distintos); impl: T020 y T021 en paralelo tras T019
- US2 pruebas: T027 y T028
- US3 pruebas: T033 y T034
- DEV: T043 con T045, T046, T047 y T050 a la vez (repos y archivos distintos); luego T044, T048 y T052
- Polish: T055, T056 y T057

---

## Parallel Example: User Story 1

```bash
# Pruebas de la historia 1, todas a la vez (archivos distintos):
Task: "T013 eval/tests/test_quality_scores.py"
Task: "T014 eval/tests/test_chat_client.py"
Task: "T015 eval/tests/test_ragas_judge.py"
Task: "T016 eval/tests/test_quality_run.py"
Task: "T017 eval/tests/test_quality_report.py"
Task: "T018 eval/tests/test_quality_cli.py"

# Implementación independiente tras quality_scores.py (T019):
Task: "T020 eval/src/shoplab_eval/chat_client.py"
Task: "T021 eval/src/shoplab_eval/quality_judge.py"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Fase 1: Setup (T001–T004)
2. Fase 2: Foundational, **incluida la puerta T012**: no se construye nada si el juez local no discrimina
3. Fase 3: US1 (T013–T026)
4. **PARAR y VALIDAR**: medición real sin Langfuse, resumen reproducible desde el detalle, tiempo medido (S-4)
5. Con esto LAB-30 ya puede leer el resultado

### Incremental Delivery

1. Setup + Foundational → diseño validado con modelo real
2. US1 → resultado completo con el juez por defecto (MVP)
3. US2 → juez configurable y registrado → resultados comparables
4. US3 → puntuaciones en las trazas → depuración en Langfuse
5. US4 → fallos claros y orden de comprobaciones
6. DEV → `make up` + `make seed` + `make quality` funcionan en el entorno local del equipo (fase 7, ambas PR)
7. Polish → README, ADR-0026, CI y validación final

### Riesgos que pueden cambiar el plan (se revisan en T012, T026 y T053)

- El juez 3B local no discrimina o `instructor` no da salida válida → elegir otro modelo por defecto o documentar el límite
- `kubectl port-forward` no atraviesa la `NetworkPolicy` del gateway de DEV → ajustar el túnel o la política (T053)
- El archivo de secretos de DEV tiene un formato que `ensure-secrets.sh` no sabe ampliar → el script se detiene y explica qué añadir a
  mano; no modifica el archivo (T048)
- Langfuse no acepta scores sobre trazas aún no ingeridas, o no actualiza por `id` → ajustar la espera o la clave de idempotencia
- Los 96 casos no caben en 30 minutos → reformular SC-001 con `/loop-spec`

---

## Notes

- [P] = archivos distintos y sin dependencias pendientes
- [Story] enlaza la tarea con su historia para la trazabilidad con spec.md
- Cada historia debe poder completarse y probarse por sí sola
- Verificar que las pruebas fallan antes de implementar; commit tras cada tarea o grupo lógico
- Ninguna tarea modifica `backend/`, `data/kb/` ni `eval/golden.jsonl`. Las de la fase 7 modifican `../shoplab-infra` en una rama
  nueva con PR propia, sin tocar cambios locales que haya en ese clon ni imprimir nunca un valor de secreto
- Evitar: tareas vagas, conflictos de archivo entre tareas [P], y dependencias entre historias que rompan su independencia
