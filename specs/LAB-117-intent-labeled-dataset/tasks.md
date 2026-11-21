---

description: "Task list for LAB-117 - Dataset etiquetado de intenciones del cliente"
---

# Tasks: Dataset etiquetado de intenciones del cliente

**Input**: Design documents from `/specs/LAB-117-intent-labeled-dataset/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`dataset-format.md`, `cli-contract.md`),
quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas antes o junto con la implementación y `ml/` mantiene
`--cov-fail-under=80`. En cada historia las pruebas van primero y deben **fallar** antes de implementar. Ninguna prueba necesita
red, base de datos ni modelo: usan un conector falso, una función de envío HTTP falsa y ejemplos en memoria. Solo las tareas de
**construcción de datos** (T029, T033) necesitan la base local con Olist y el gateway de modelos.

**Organization**: Tareas agrupadas por historia de usuario. **El orden de las fases no sigue la numeración de las historias**,
porque todo texto debe entrar enmascarado: US4 (enmascarado) va primero, luego US1 (dataset), US3 (partición), US2 (acuerdo) y
US5 (ficha y validación). Las cuatro primeras son P1; US5 es P2.

> **⚠ persona**: la tarea la tiene que hacer una persona (etiquetar, revisar, firmar, ser el segundo anotador). El asistente puede
> preparar borradores y comandos, pero **no puede firmar la revisión** ni contar como anotador independiente de sí mismo. Hasta
> que esas tareas estén hechas, el dataset es un **borrador**, como el golden en LAB-27.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece (US1–US5); sin etiqueta en Setup, Foundational y Polish
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Proyecto**: `ml/` (uv `shoplab-ml`). **Paquete**: `ml/src/intent_dataset/`. **Pruebas**: `ml/tests/intent_dataset/` (sin
  `__init__.py`; los nombres de archivo no chocan con los de `ml/tests/`). **Datos**: `ml/data/intents/`. **Trabajo local
  (ignorado por git)**: `ml/data/intents/work/`.
- Python: líneas ≤ 121, identificadores en inglés, `ruff` con `select = ["E","F","I","B","UP"]`; guía, ficha y mensajes de error
  para quien ejecuta, en español. Las **etiquetas** (`politica`, `producto`, `pedido`, `escalado`, `charla`) son valores de
  datos, no identificadores.
- Comandos desde `ml/`: `uv run ruff check .` y `uv run pytest`.
- **No se modifican** `backend/`, `ingest/`, `frontend/`, `infra/`, `eval/`, `data/` ni `.github/workflows/` (FR-025). Las únicas
  ediciones fuera de `ml/` y `specs/` son `.gitignore` y `README.md`.
- **Nunca** se escribe en disco un comentario de Olist sin enmascarar, ni se versionan `work/`, claves, ni el CSV/tabla de Olist
  (FR-020, FR-026). La clave `SHOPLAB_ML_LLM_API_KEY` solo vive en el entorno.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base verde y estructura del paquete

- [X] T001 Verificar la línea base: rama `feature/LAB-117-intent-labeled-dataset`, árbol sin cambios ajenos
  (`git status --short`), `cd ml && uv sync && uv run ruff check . && uv run pytest` en verde (anotar la cobertura actual). Para
  la construcción de datos (T029, T033) comprobar también que la base local con Olist completo responde y que el gateway
  (`http://localhost:4000/v1`) y el modelo del juez están disponibles; si falta alguno, anotarlo, no bloquea el código
  **Resultado (2026-11-21)**: código en verde (184 pruebas, 99 %). **Docker apagado: no hay base de datos con Olist ni
  gateway**, así que T029 y T033 no se pueden ejecutar hasta levantar la pila.
- [X] T002 Editar `ml/pyproject.toml`: `packages = ["src/olist_eda", "src/intent_dataset"]` y
  `addopts = "--cov=olist_eda --cov=intent_dataset --cov-fail-under=80"`; sin dependencias nuevas. Crear
  `ml/src/intent_dataset/__init__.py` (docstring de una línea) y `ml/tests/intent_dataset/` vacío; `uv sync` y `ruff` en verde
- [X] T003 [P] Añadir `ml/data/intents/work/` a `.gitignore` y crear `ml/data/intents/annotations/.gitkeep`; comprobar con
  `git check-ignore -v ml/data/intents/work/x.csv`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: constantes, errores, normalización de texto, lectura/escritura del dataset, esqueleto del validador y de la CLI, y la
guía de etiquetado (las personas etiquetan con ella)

**⚠️ CRITICAL**: ninguna historia empieza antes de terminar esta fase

- [X] T004 [P] Crear `ml/src/intent_dataset/constants.py`: `LABELS = ("politica","producto","pedido","escalado","charla")`,
  `LANGUAGES = ("es","pt")`, `SOURCES = ("golden","olist_review","manual_seed","paraphrase")`,
  `SPLITS = ("train","val","test")`, `EXAMPLE_KEYS` en el orden de `contracts/dataset-format.md` (`id`, `text`, `label`,
  `language`, `source`, `group_id`, `parent_id`, `golden_id`, `split`, `reviewed`), dataclass congelada `DatasetRules`
  (`min_examples=600`, `min_per_label=60`, `min_per_label_language=20`, `min_text_chars=2`, `max_text_chars=500`,
  `ratios=(0.70, 0.15, 0.15)`, `seed=117`, `agreement_sample_size=50`, `kappa_reference=0.70`) y rutas (`INTENTS_DIR`,
  `DATASET_PATH`, `WORK_DIR`, `ANNOTATIONS_DIR`, `GUIDE_PATH`, `DATASHEET_PATH`, `GOLDEN_PATH`) relativas a la raíz del
  repositorio calculada como en `olist_eda.config`
- [X] T005 [P] Crear `ml/src/intent_dataset/errors.py`: `InvalidDatasetError`, `ReviewError`, `ParaphraseError`,
  `ConfigurationError` (variable de entorno u origen ausente; es el código de salida 2) y `Issue(rule, example_id, message)`
  inmutable con `sort_issues` (por regla, luego por `example_id`); los mensajes son en español y no incluyen claves ni
  contraseñas
- [X] T006 [P] Escribir `ml/tests/intent_dataset/test_text.py`: `normalize_text` pasa a minúsculas, quita acentos y signos
  (`¿Qué tal?` y `que tal` dan lo mismo), colapsa espacios y saltos, conserva `ñ` como `n` y `ç` como `c`, es idempotente y trata
  igual `Olá` y `ola`; debe fallar antes de T007
- [X] T007 Crear `ml/src/intent_dataset/text.py` con `normalize_text(text) -> str` (NFKD, sin marcas combinantes, `casefold`,
  solo letras, dígitos y espacios, espacios colapsados) y `tokens(text) -> frozenset[str]` (lo usa el filtro de similitud de
  T024)
- [X] T008 [P] Escribir `ml/tests/intent_dataset/test_dataset.py`: ida y vuelta de `Example`; el archivo escrito lleva las
  claves en el orden de `EXAMPLE_KEYS`, ordenado por `id`, UTF-8 sin BOM y `\n`; la lectura tolera líneas vacías, rechaza claves
  desconocidas o ausentes y un JSON roto con el **número de línea**; `parent_id`/`golden_id`/`split` admiten `null`;
  `next_id` devuelve `I0001` en vacío y el siguiente consecutivo sin reutilizar ids; debe fallar antes de T009
- [X] T009 Crear `ml/src/intent_dataset/dataset.py`: `Example` (dataclass congelada con los diez campos de
  `contracts/dataset-format.md`), `load_examples(path)`, `dump_examples(examples, path)` (escritura atómica: archivo temporal y
  renombrado), `next_id(examples)` y `replace_split(...)`; lanza `InvalidDatasetError` con la línea y la causa
- [X] T010 Escribir `ml/tests/intent_dataset/test_validation.py` (esqueleto): `validate_dataset(examples, rules)` devuelve una
  lista de `Issue` ordenada, vacía para un dataset válido mínimo, y **recoge todos los fallos** en lugar de parar en el primero
  (dos defectos provocados dan dos `Issue`); incluir los constructores de ejemplos de prueba en
  `ml/tests/intent_dataset/conftest.py` (`make_example(**cambios)`, `make_dataset(rules)` con un dataset en memoria que cumple
  las reglas con mínimos reducidos, 5 clases × 2 idiomas × 3 particiones); debe fallar antes de T011
- [X] T011 Crear `ml/src/intent_dataset/validation.py`: `validate_dataset(examples, rules=DatasetRules(), *, golden_ids=None,
  agreement=None, datasheet=None, guide=None) -> list[Issue]` que ejecuta una lista de funciones de regla
  `Rule = Callable[[context], list[Issue]]` y devuelve todas las incidencias ordenadas; sin reglas todavía (las añade cada
  historia)
- [X] T012 Crear `ml/src/intent_dataset/__main__.py` con `main(argv=None) -> int`: subcomandos con `argparse` registrados en un
  diccionario `COMMANDS`, códigos de salida `0/1/2` de `contracts/cli-contract.md` (`InvalidDatasetError`/`ReviewError` → 1;
  `ConfigurationError`, argumentos o archivos ausentes → 2), mensajes en español sin claves; añadir
  `ml/tests/intent_dataset/test_cli.py` (subcomando desconocido → 2, `--help` → 0, una excepción tipada produce su código y su
  mensaje) escrita antes
- [ ] T013 ⚠ persona Redactar `ml/data/intents/LABELING-GUIDE.md` **v1** (la necesitan quienes etiquetan en T031 y T034):
  un apartado por clase con definición, qué incluye, qué excluye y **≥ 2 ejemplos en español y ≥ 2 en portugués**; la
  **prioridad** `escalado` > `pedido` > `producto` > `politica` > `charla`; `charla` ampliada a lo ajeno a la tienda y a la
  inyección (FR-007); la regla de los **mensajes mixtos** (FR-009); y la sección «Casos límite» con los cuatro pares de FR-010
  (`politica`/`pedido`, `producto`/`pedido`, `pedido`/`escalado`, `charla`/`politica` en mensajes mixtos), cada uno con regla de
  desempate explícita y ejemplos en ambos idiomas. Punto de partida: `research.md` R11. El asistente puede dar el borrador;
  la persona lo lee entero y lo ajusta

**Checkpoint**: `uv run ruff check . && uv run pytest` en verde; el paquete importa; la guía v1 existe

---

## Phase 3: User Story 4 - Sin datos personales en el dataset (Priority: P1) 🎯 prerrequisito de US1

**Goal**: ningún texto entra al dataset sin pasar por el enmascarado, y el validador detecta cualquier dato personal que se cuele
(FR-017, FR-019).

**Independent Test**: un texto con un ejemplo de cada tipo sale con sus marcas y el resto intacto; aplicar el enmascarado dos
veces no cambia nada; `validate_dataset` falla con la regla `pii` cuando un ejemplo contiene un correo.
  **Estado (2026-11-21)**: el asistente redactó la v1 y, al etiquetar, la amplió a la **v2** (criterios añadidos y casos resueltos
  de la muestra de acuerdo). **Ninguna persona la ha leído**: la tarea sigue abierta y es la primera del apartado 3 de `REVIEW.md`.
- [X] T014 [P] [US4] Escribir `ml/tests/intent_dataset/test_masking.py`, un caso por fila de la tabla de `research.md` R3:
  enlace `http(s)://…` y `www.…` → `[URL]` (antes que el correo); correo → `[EMAIL]`; 32 hexadecimales en minúsculas y mayúsculas
  → `[ID]`; CNPJ `00.000.000/0000-00` → `[CNPJ]`; CPF `000.000.000-00` → `[CPF]`; CEP `00000-000` → `[CEP]`; teléfonos
  `+55 (11) 91234-5678`, `11 91234-5678`, `(11) 3333-4444` y `3333-4444` → `[PHONE]`; **11 dígitos sin formato** y cualquier
  **racha de 8 o más dígitos** → `[PHONE]`; varios datos en un mismo texto; un texto sin datos personales
  (`Mi pedido llegó el 12/03 y pagué R$ 120,50 en 4 cuotas`) queda **idéntico**; idempotencia
  (`mask_text(mask_text(x)) == mask_text(x)`); las marcas no se vuelven a enmascarar; `find_personal_data(texto)` devuelve
  tipo y fragmento y **no devuelve nada** sobre un texto ya enmascarado; debe fallar antes de T015
- [X] T015 [US4] Crear `ml/src/intent_dataset/masking.py` sin dependencias del resto del paquete: `PLACEHOLDERS`,
  `mask_text(text) -> str` (patrones aplicados en el orden de `research.md` R3: URL, correo, ID de 32 hex, CNPJ, CPF con
  formato, CEP con guion, teléfono / racha de ≥ 8 dígitos) y `find_personal_data(text) -> list[PersonalDataMatch]`; docstring
  que declara que **los nombres de persona no se reconocen por patrón** y que los cubre la revisión manual (T031)
- [X] T016 [US4] Añadir a `ml/tests/intent_dataset/test_validation.py` el caso de la regla `pii` (FR-019): un ejemplo con
  `escríbeme a ana@correo.com` y otro con un teléfono generan un `Issue(rule="pii", example_id=…)` cada uno con el tipo de dato
  en el mensaje; un dataset sin ellos no genera ninguno; debe fallar antes de T017
- [X] T017 [US4] Añadir la regla `pii` a `ml/src/intent_dataset/validation.py` usando `masking.find_personal_data` sobre cada
  `text`
- [X] T018 [P] [US4] Crear `ml/data/intents/REVIEW.md` en estado **BORRADOR** siguiendo `eval/REVIEW.md`: estado y revisor
  «pendiente»; apartados con tabla de estado por lote para (1) revisión de nombres de persona en comentarios de Olist
  (`pii_checked`), (2) revisión de paráfrasis (intención conservada, natural, sin datos inventados), (3) comprobación de que
  ningún `charla` contiene una petición real (FR-009), (4) lectura de la guía tras resolver los desacuerdos, (5) quién fue el
  anotador B y si fue persona o modelo; todo en «pendiente»

**Checkpoint**: `uv run pytest tests/intent_dataset/test_masking.py` y `test_validation.py` en verde; US4 comprobable de forma
aislada

---

## Phase 4: User Story 1 - Disponer de un dataset de mensajes etiquetados por intención (Priority: P1) 🎯 MVP

**Goal**: ≥ 600 ejemplos revisados, en español y portugués, con las cinco etiquetas, de los cuatro orígenes y con su trazabilidad
(FR-001 a FR-009).

**Independent Test**: cargar `intents.jsonl` y comprobar que cada ejemplo tiene texto, idioma, una de las cinco etiquetas y
`reviewed: true`, que hay ≥ 600, que cada clase tiene ≥ 20 por idioma y que los orígenes están presentes.

### Tests for User Story 1

- [X] T019 [P] [US1] Escribir `ml/tests/intent_dataset/test_sources.py`: **golden real** (`eval/golden.jsonl`): 120 candidatos,
  `proposed_label = politica` para `payments`, `privacy`, `returns`, `shipping` y `warranty` (88) y `charla` para `small_talk`,
  `off_topic` e `injection` (32); `language = es`; `golden_id == group_id == id del caso`; reseñas con un **conector falso**
  (sin base de datos): el comentario con un correo sale ya enmascarado, los vacíos y los de menos de 20 o más de 280 caracteres
  se descartan, los duplicados por texto normalizado se quitan, `language = pt`, `group_id` = `R` + 8 hexadecimales derivados
  del hash del `review_id` (**el identificador real no aparece en ninguna salida**), mismo resultado con la misma semilla y
  `limit` respetado; `hint` por palabras clave (`entreg|chegou|prazo` → `pedido`, `defeito|qualidade` → `producto`,
  `reclam|procon|cancel|atendente` → `escalado`) y vacío si ninguna casa; debe fallar antes de T020
- [X] T020 [US1] Crear `ml/src/intent_dataset/sources.py`: `Candidate` (los campos del CSV de `contracts/dataset-format.md`),
  `read_golden(path)`, `read_reviews(settings, *, connector=connect, limit, seed)` que **reutiliza `olist_eda.config` y
  `olist_eda.db.connect`** (conexión de solo lectura), enmascara con `mask_text` **antes de devolver** y no escribe nada, y
  `hint_for(text)`. Sin importar `shoplab_eval` (el golden se lee como JSON)
- [X] T021 [P] [US1] Escribir `ml/tests/intent_dataset/test_review.py`: el CSV exportado tiene **exactamente** las columnas de
  `contracts/dataset-format.md`; `decision = accept` con `label` válida crea un ejemplo con `reviewed = true`, `split = null` e
  `id` consecutivo desde `next_id`; `reject` y vacío (pendiente) no entran y se **cuentan**; una `label` fuera de las cinco, un
  `accept` sin `label` o una fila `olist_review` sin `pii_checked = si` producen `ReviewError` que **lista todas** las filas
  malas y **no escribe nada**; `text_edit` sustituye a `text` y se **vuelve a enmascarar**; el texto normalizado ya existente en
  el dataset se omite y se cuenta; una paráfrasis con `label` distinta de la de su `parent_id` es error; el CSV con BOM
  (`utf-8-sig`) se lee bien; el resumen cuenta por origen y clase; debe fallar antes de T022
- [X] T022 [US1] Crear `ml/src/intent_dataset/review.py`: `write_candidates_csv(candidates, path, *, force)` (se niega a pisar
  un archivo existente sin `force`), `read_review_csv(path)` y `build_examples(rows, existing, rules) -> BuildResult`
  (ejemplos nuevos + resumen), con validación atómica y mensajes en español por fila
- [X] T023 [P] [US1] Escribir `ml/tests/intent_dataset/test_paraphrase.py` con una función de envío HTTP **falsa** (sin red): el
  cuerpo enviado contiene el modelo, `temperature`, `seed`, el mensaje, su intención, el idioma de destino y la instrucción de
  **no incluir nombres, correos, teléfonos ni números de pedido**; se acepta un array JSON, también dentro de un bloque
  ```` ```json ````; se descartan, contando el motivo, el JSON inválido, las variantes vacías o fuera de 3–280 caracteres, las
  idénticas al original o entre sí tras normalizar, las de similitud de tokens (Jaccard) ≥ 0,85 con el original en el mismo
  idioma y las que **`mask_text` cambia** (dato personal inventado); con `translate` se pide también el otro idioma; las filas
  resultantes llevan `source = paraphrase`, `parent_id`, el `group_id` y el `golden_id` del padre, `proposed_label` = la del
  padre y `decision` vacío; sin `SHOPLAB_ML_LLM_API_KEY` se lanza `ConfigurationError` que **nombra la variable y no imprime
  ningún valor**; un error HTTP lanza `ParaphraseError` sin la clave; debe fallar antes de T024
- [X] T024 [US1] Crear `ml/src/intent_dataset/paraphrase.py`: `LlmSettings.from_env` (`SHOPLAB_ML_LLM_BASE_URL` por defecto
  `http://localhost:4000/v1`, `SHOPLAB_ML_LLM_MODEL` por defecto `judge`, `SHOPLAB_ML_LLM_API_KEY` **sin valor por defecto**),
  `LlmClient` con `urllib.request` y `post` inyectable (como `shoplab_eval`), `PARAPHRASE_PROMPT_VERSION`,
  `generate_paraphrases(parents, client, *, per_parent, translate, seed)` y los filtros de `research.md` R5; el cliente también
  expone `complete(system, user)` para T044
- [X] T025 [US1] Añadir a `ml/tests/intent_dataset/test_validation.py` un defecto provocado por regla de este bloque, con el
  texto de `data-model.md`: `schema` (clave desconocida o ausente), `id` (formato `I` + 4 dígitos y unicidad), `label`,
  `language`, `source`, `split` (valor permitido; **nulo** también es un error al publicar), `text` («2–500 caracteres» y no
  vacío tras `strip`), `reviewed` («`true` en todo ejemplo publicado»), `provenance` (`source: golden` ⇒
  `golden_id == group_id`; una paráfrasis tiene `parent_id` que **existe**, mismo `group_id`, mismo `golden_id` y **la misma
  `label` que su padre**), `min_total` (600), `min_label` (60) y `min_label_language` (20); cada defecto produce una sola
  incidencia con la regla y el ejemplo; debe fallar antes de T026
- [X] T026 [US1] Implementar esas reglas en `ml/src/intent_dataset/validation.py` leyendo los umbrales de `DatasetRules`
- [X] T027 [US1] Añadir a `ml/tests/intent_dataset/test_cli.py` los comandos de este bloque con dependencias inyectadas:
  `candidates --skip-reviews` escribe el CSV del golden real en un directorio temporal, se niega a pisarlo sin `--force` y
  resume por origen y pista; sin base de datos accesible, `candidates` devuelve 2 con el mensaje de cómo levantarla;
  `paraphrase` sin clave devuelve 2; `build --dry-run` no escribe y `build` con una fila inválida devuelve 1 sin escribir;
  `build` imprime el resumen; debe fallar antes de T028
- [X] T028 [US1] Registrar en `ml/src/intent_dataset/__main__.py` los comandos `candidates`, `paraphrase` y `build` según
  `contracts/cli-contract.md` (argumentos, valores por defecto, `--force`, `--dry-run`, resumen y códigos de salida)

### Construcción de los datos (local, una vez)

- [X] T029 [US1] Con Olist cargado y la base levantada, ejecutar `uv run python -m intent_dataset candidates --out
  data/intents/work/candidates.csv` desde `ml/`; comprobar el resumen (120 del golden, reseñas por pista) y que
  `work/candidates.csv` **no contiene ningún correo, teléfono ni identificador de 32 hex** (`rg` sobre el archivo). Si no hay
  Olist completo, usar `--skip-reviews` y anotarlo: el dataset no llegará al mínimo de portugués sin reseñas
  **Estado (2026-11-21)**: probado el flujo real con `--skip-reviews` sobre el golden (120 candidatos, 88/32); **falta ejecutarlo
  con reseñas de Olist**: Docker estaba apagado.
  **Hecho (2026-11-21)** con 120 del golden y 240 reseñas; como la contraseña de Postgres local no coincide con la del compose, las
  reseñas se leyeron por el socket del contenedor (solo lectura) con el mismo `read_reviews`; el enmascarado se aplicó al leerlas.
- [X] T030 [US1] ⚠ persona Redactar a mano las **semillas** (`source = manual_seed`, `group_id` `S001`…, español y portugués) como
  filas nuevas de `work/candidates.csv`: unas **15–20 por clase débil e idioma** (`pedido`, `producto`, `escalado`) y las que
  hagan falta de `politica` y `charla` (cortesías, «¿qué puedes hacer?», mixtos de FR-009, ajenos a la tienda), imitando mensajes
  reales a un chat y **sin datos personales reales**. El asistente puede proponer un borrador; la persona lo revisa
  **Hecha por el asistente (un modelo), no por una persona** (2026-11-21); la revisión humana sigue pendiente (`REVIEW.md` §3). 114 semillas.
- [X] T031 [US1] ⚠ persona Revisar `work/candidates.csv` con la guía (T013): por fila `decision`, `label`, `text_edit` y, en las
  de Olist, `pii_checked = si` **solo tras leer el comentario** y sustituir los nombres por `[NAME]`; confirmar o corregir la
  etiqueta propuesta del golden (preguntas sobre un pedido o producto concretos, o que piden una persona, no son `politica`);
  descartar comentarios sin intención o con dos. Anotar el lote en `ml/data/intents/REVIEW.md` (quién, cuándo, cuántas filas)
  **Hecha por el asistente (un modelo), no por una persona** (2026-11-21); la revisión humana sigue pendiente (`REVIEW.md` §3). 120 del golden y 240 reseñas (169 aceptadas, 71 descartadas); nombres de persona: 2 sustituidos.
- [X] T032 [US1] Ejecutar `uv run python -m intent_dataset build data/intents/work/candidates.csv` (primero con `--dry-run`) para
  crear `ml/data/intents/intents.jsonl` sin partición; guardar el resumen para `validation.md`
  403 ejemplos (120 golden, 169 reseñas, 114 semillas).
- [X] T033 [US1] Con el gateway levantado y `SHOPLAB_ML_LLM_API_KEY` definida (clave virtual `eval`), ejecutar `uv run python -m
  intent_dataset paraphrase --per-parent 2 --translate` y guardar las cifras (generadas, descartadas por filtro y su motivo) para
  `validation.md`. **Medir si la calidad en portugués es aceptable** (`research.md` R5); si no, cambiar
  `SHOPLAB_ML_LLM_MODEL` y repetir
  354 variantes con `judge` (qwen2.5:7b, CPU), 3 descartadas por el filtro. Calidad en portugués peor: 26 % de rechazo frente a 12 % en español.
- [X] T034 [US1] ⚠ persona Revisar `work/paraphrases.csv` (cada variante conserva la intención de su padre, es natural en su
  idioma y no inventa datos; una que cambia de clase se rechaza), ejecutar `build` con ese CSV y anotar el lote en `REVIEW.md`
  **Hecha por el asistente (un modelo), no por una persona** (2026-11-21); la revisión humana sigue pendiente (`REVIEW.md` §3). 71 rechazadas (20 %), 60 editadas, 282 incorporadas.
- [X] T035 [US1] Comprobar los mínimos con `uv run python -m intent_dataset validate --no-docs` (aún sin partición: ignorar solo
  `split`); si faltan ejemplos en alguna clase o idioma, volver a T030/T033 hasta llegar a **≥ 600, ≥ 60 por clase y ≥ 20 por
  clase e idioma** (objetivo orientativo ~700 y ≥ 40 por celda, `research.md` R4)

**Checkpoint**: `intents.jsonl` con ≥ 600 ejemplos revisados; US1 comprobable de forma aislada
  685 ejemplos; la celda más pequeña tiene 40 (mínimos del spec: 600 / 60 / 20).
---

## Phase 5: User Story 3 - Partición fija y sin fuga (Priority: P1)

**Goal**: partición train/val/test estratificada por clase e idioma, por grupos, determinista y estable al añadir ejemplos
(FR-013 a FR-016).

**Independent Test**: ningún grupo ni texto normalizado aparece en más de una partición, las tres tienen las cinco clases y los dos
idiomas, y regenerar con la misma semilla no cambia nada.

- [X] T036 [P] [US3] Escribir `ml/tests/intent_dataset/test_splitting.py`: el **grupo efectivo** une los ejemplos con el mismo
  `group_id` y los de igual texto normalizado (cadena `A~B`, `B~C` ⇒ un solo grupo); ningún grupo efectivo queda en más de una
  partición; cada estrato (clase, idioma) se reparte cerca de 70/15/15 con tolerancia declarada; **determinismo** (misma entrada
  y semilla ⇒ mismo resultado); **estabilidad** (añadir ejemplos no cambia el `split` de los existentes y un ejemplo nuevo de un
  grupo ya asignado hereda su partición); un grupo con miembros ya asignados a particiones distintas lanza `InvalidDatasetError`;
  si una partición quedaría sin alguna clase o idioma se lanza el error **sin devolver una asignación parcial**; casos límite
  (estrato con 1 ejemplo, grupo mayor que el objetivo de una partición); debe fallar antes de T037
- [X] T037 [US3] Crear `ml/src/intent_dataset/splitting.py` con el algoritmo de `research.md` R6: `effective_groups` (union-find),
  objetivos por estrato con `round(total × ratio)` y mínimo 1, grupos ordenados por tamaño descendente con desempate por
  mezcla de `random.Random(seed)`, asignación a la partición de **mayor déficit relativo**, grupos ya asignados fijos y
  comprobación final de cobertura; devuelve ejemplos con `split` sin tocar los que ya lo tenían
- [X] T038 [US3] Añadir a `ml/tests/intent_dataset/test_validation.py` un defecto provocado por cada regla: `group_split` (un
  grupo con miembros en dos particiones, incluido el unido por texto normalizado), `duplicates` («ningún texto normalizado en
  dos particiones»: un duplicado exacto con distinto `group_id` y distinta partición) y `split_coverage` («cada partición tiene
  las 5 clases y los 2 idiomas»); debe fallar antes de T039
- [X] T039 [US3] Implementar `group_split`, `duplicates` y `split_coverage` en `ml/src/intent_dataset/validation.py`
  reutilizando `splitting.effective_groups`
- [X] T040 [US3] Añadir a `ml/tests/intent_dataset/test_cli.py` el comando `split`: asigna solo los ejemplos sin `split`, una
  segunda ejecución **no cambia el archivo**, `--ratios` y `--seed` se respetan, un fallo de cobertura devuelve 1 y **no
  escribe**; debe fallar antes de T041
- [X] T041 [US3] Registrar `split` en `ml/src/intent_dataset/__main__.py` según `contracts/cli-contract.md`
- [X] T042 [US3] Ejecutar `uv run python -m intent_dataset split` sobre `intents.jsonl`, repetirlo y comprobar con `git diff` que
  la segunda vez no cambia nada; ejecutar `validate --no-docs`; anotar para `validation.md` los ejemplos por **partición × clase ×
  idioma** y cuántos casos del golden hay en cada partición (`research.md` R9); señalar las celdas de val o test con pocos
  ejemplos (< 5) para la sección de limitaciones de la ficha

**Checkpoint**: dataset con partición fija; **LAB-118 ya podría empezar** con US4 + US1 + US3
  train 481 · val 102 · test 102; casos del golden: train 84, val 19, test 17; segunda ejecución sin cambios.
---

## Phase 6: User Story 2 - Etiquetas consistentes, con guía y acuerdo medido (Priority: P1)

**Goal**: medir el acuerdo entre dos anotadores sobre 50 casos con el kappa de Cohen y dejar la guía corregida por los
desacuerdos (FR-010 a FR-012).

**Independent Test**: un anotador que solo tiene la guía etiqueta la muestra sin ver las etiquetas existentes, se calcula el kappa
y cada desacuerdo queda listado y resuelto.

- [X] T043 [P] [US2] Escribir `ml/tests/intent_dataset/test_agreement.py`: **kappa con valores conocidos** (acuerdo total = 1;
  la tabla clásica `[[20, 5], [10, 15]]` con n = 50 da pₒ = 0,7, pₑ = 0,5, **κ = 0,4**; un caso de azar ≈ 0; acuerdo esperado
  igual a 1 devuelve `None` con `kappa_note` en lugar de dividir por cero); `select_sample` devuelve **50 casos, 10 por clase**, con
  los dos idiomas presentes, determinista con la semilla, ordenado por `id`, prefiere origen no `golden` cuando hay elección y
  puede reservar casos marcados como límite; `write_sample` congela `label_a` y deja `label_b = null`, y se **niega a
  sobrescribir** sin `--force`; la hoja del anotador B contiene `id, text, label` **sin ninguna etiqueta de A**; `compute_agreement`
  rellena `label_b`, calcula `n`, `kappa`, `observed_agreement`, `expected_agreement`, la matriz de confusión (celdas en cero
  omitidas) y el número de desacuerdos, exige que la hoja cubra los 50 ids con etiquetas válidas y registra
  `annotator_b = person | local_model` (+ modelo); `annotate_sheet` con un cliente falso usa **la guía como instrucción**, **no
  recibe `label_a`**, y deja vacía (y la lista) cualquier etiqueta inválida del modelo en lugar de adivinarla; debe fallar antes
  de T044
- [X] T044 [US2] Crear `ml/src/intent_dataset/agreement.py`: `cohen_kappa`, `select_sample`, `write_sample`,
  `write_annotator_sheet`, `compute_agreement`, `write_report` (formato de `annotations/agreement.json`) y `annotate_sheet`
  (usa `LlmClient.complete` de T024); sin dependencias nuevas
- [X] T045 [US2] Añadir a `ml/tests/intent_dataset/test_validation.py` el caso de la regla `agreement` (FR-011, FR-012): la
  muestra tiene 50 ids que **existen** y son distintos, las 5 clases (10 por clase) y los 2 idiomas; `final` presente en los 50;
  `reason` obligatorio cuando `label_a != label_b`; la etiqueta de cada ejemplo en el dataset **es** su `final`; `agreement.json`
  tiene `annotator_b` y su `kappa` coincide con el **recalculado** desde la muestra (si se altera a mano, falla); debe fallar
  antes de T046
- [X] T046 [US2] Implementar la regla `agreement` en `ml/src/intent_dataset/validation.py`
- [X] T047 [US2] Añadir a `ml/tests/intent_dataset/test_cli.py` los comandos `sample`, `annotate` y `agreement`: `sample` escribe
  la muestra y la hoja y se niega a repetirse sin `--force`; `annotate` sin clave devuelve 2; `agreement` lista cada desacuerdo,
  se niega a recalcular si ya hay `final` sin `--force`, y exige `--kind`; debe fallar antes de T048
- [X] T048 [US2] Registrar `sample`, `annotate` y `agreement` en `ml/src/intent_dataset/__main__.py` según
  `contracts/cli-contract.md`
- [X] T049 [US2] Con el dataset ya partido (T042), ejecutar `uv run python -m intent_dataset sample`: congela
  `annotations/agreement-sample.jsonl` y crea `work/annotator-b.csv` sin etiquetas
  Primera muestra demasiado fácil (40 semillas de 50): se cambió la prioridad y se volvió a congelar (16 reseñas, 15 golden, 19 semillas).
- [X] T050 [US2] ⚠ persona **El segundo anotador** etiqueta `work/annotator-b.csv` usando **solo** `LABELING-GUIDE.md`, sin ver
  `agreement-sample.jsonl`. Si no hay una segunda persona, usar `uv run python -m intent_dataset annotate` y registrar que el
  anotador B es el **modelo local**: el kappa resultante **no** es un acuerdo entre personas y la ficha lo dirá. No usar el modelo
  si él mismo propuso etiquetas en alguna parte del flujo (no las propuso: `research.md` R7)
  **El anotador B fue el modelo local `judge`, no una persona**; ejecutó una petición de inyección y se corrigió el prompt.
- [X] T051 [US2] Ejecutar `uv run python -m intent_dataset agreement --annotator-b data/intents/work/annotator-b.csv --kind
  person|local_model`; guardar el **kappa inicial** (antes de resolver) y la lista de desacuerdos para `validation.md`
  κ = 0,85 (n = 50, acuerdo observado 0,88), 6 desacuerdos; anotador B = `local_model` (judge).
- [X] T052 [US2] ⚠ persona Resolver cada desacuerdo en `annotations/agreement-sample.jsonl` (`final` y `reason`), corregir en
  `intents.jsonl` las etiquetas que cambien (la partición no se mueve) y, si el desacuerdo revela una regla ausente, **actualizar
  `LABELING-GUIDE.md`** (v2) con el caso límite. Si el kappa inicial es < 0,70 (`kappa_reference`), revisar la guía, reetiquetar
  las clases implicadas y **conservar el primer valor en la ficha**; `agreement.json` no se reescribe. Anotarlo en `REVIEW.md`

**Checkpoint**: `validate --no-docs` en verde con la regla `agreement`; US2 comprobable de forma aislada
  **Hecha por el asistente (un modelo), no por una persona** (2026-11-21); la revisión humana sigue pendiente (`REVIEW.md` §3). Cambió una etiqueta (`I0007` y su paráfrasis); guía actualizada.
---

## Phase 7: User Story 5 - Ficha del dataset y verificación reproducible (Priority: P2)

**Goal**: ficha con cifras que nunca se desactualizan y un `validate` único que corre en CI (FR-021 a FR-024).

**Independent Test**: la ficha tiene todos sus apartados y su bloque de cifras coincide con el dataset; los defectos provocados
hacen fallar `validate`; todo sin red, base de datos ni modelo.

- [X] T053 [P] [US5] Escribir `ml/tests/intent_dataset/test_datasheet.py`: `compute_stats` da el total, clase × idioma,
  partición × clase, origen, casos del golden por partición, `kappa`, `n`, desacuerdos y tipo del anotador B;
  `render_block` produce la tabla Markdown entre `<!-- stats:begin -->` y `<!-- stats:end -->`; `check_block` detecta que el
  bloque difiere del regenerado (se cambia una cifra) y que **faltan las marcas**; `write_block` reemplaza **solo** lo que hay
  entre las marcas y deja el resto byte a byte; debe fallar antes de T054
- [X] T054 [US5] Crear `ml/src/intent_dataset/datasheet.py` con esas funciones y la lista `REQUIRED_SECTIONS` (**Origen**,
  **Composición**, **Licencias**, **Generación y revisión de paráfrasis**, **Acuerdo entre anotadores**, **Enmascarado**,
  **Particiones**, **Limitaciones**, **Uso previsto y no previsto**) y `REQUIRED_GUIDE_SECTIONS` (un apartado por clase y
  «Casos límite»)
- [X] T055 [US5] Añadir a `ml/tests/intent_dataset/test_validation.py` un defecto por regla: `datasheet` (bloque alterado,
  encabezado obligatorio ausente), `guide` (falta una clase o la sección de casos límite) y `golden_ids` (un `golden_id` que no
  existe en un golden en memoria; solo se evalúa cuando se pasa el conjunto de ids); debe fallar antes de T056
- [X] T056 [US5] Implementar `datasheet`, `guide` y `golden_ids` en `ml/src/intent_dataset/validation.py`
- [X] T057 [US5] Añadir a `ml/tests/intent_dataset/test_cli.py` los comandos `stats` y `validate`: `stats` imprime las cifras y
  `stats --write` actualiza el bloque; `validate` devuelve 0 con un dataset válido y **1 imprimiendo todas las incidencias**
  ordenadas (regla, ejemplo, mensaje) con uno roto; `--no-docs` omite guía y ficha; `--golden` activa `golden_ids`; no abre red
  ni base de datos (se comprueba con un conector que falla si se usa); debe fallar antes de T058
- [X] T058 [US5] Registrar `stats` y `validate` en `ml/src/intent_dataset/__main__.py` según `contracts/cli-contract.md`
- [X] T059 [US5] ⚠ persona Escribir `ml/data/intents/DATASHEET.md` con los apartados obligatorios de T054 y las marcas
  `<!-- stats:begin -->` / `<!-- stats:end -->`: origen de cada parte; licencias (Olist **CC BY-NC-SA 4.0**: atribución a
  Olist/Kaggle, uso no comercial, compartir igual, y que paráfrasis y traducciones heredan esas condiciones; `research.md` R10);
  generación de paráfrasis (modelo, `PARAPHRASE_PROMPT_VERSION`, fecha, pasos de filtrado y revisión); acuerdo (qué anotador B
  fue y por qué, kappa inicial, resolución); enmascarado (tabla de `research.md` R3 y que los nombres se cubren a mano);
  particiones (semilla 117, 70/15/15, reparto del golden); **limitaciones** (FR-022: dominio de los comentarios de Olist, peso
  de las paráfrasis sintéticas, tamaño del dataset, solape con el golden; además que `charla` es más amplia que la cortesía y las
  celdas pequeñas de T042); uso previsto (entrenar y evaluar el clasificador de LAB-118/LAB-126) y no previsto
  Escrita por el asistente con las cifras reales; declara que etiquetado, revisión y segundo anotador no son humanos.
- [X] T060 [US5] Ejecutar `uv run python -m intent_dataset stats --write` y después `uv run python -m intent_dataset validate
  --golden ../eval/golden.jsonl` hasta devolver 0 con **todas** las reglas
  `validate --golden ../eval/golden.jsonl` → 0 incidencias, 685 ejemplos.
- [X] T061 [US5] Escribir `ml/tests/intent_dataset/test_real_dataset.py`: sobre los archivos **reales** de
  `ml/data/intents/` y `eval/golden.jsonl`, `validate_dataset` con guía, ficha y `golden_ids` devuelve **cero incidencias**, y se
  comprueban los umbrales del spec (≥ 600, 5 clases, mínimos por clase e idioma). Este es el control de FR-019 y FR-023 en cada
  PR que toque `ml/**`; se añade **al final**, cuando los datos existen, para no dejar la rama en rojo

**Checkpoint**: las cinco historias funcionan; `uv run pytest` ejecuta también el control sobre los datos reales
  4 pruebas; `ml/tests/intent_dataset/test_real_dataset.py`.
---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T062 [P] Documentar: sección «Dataset de intenciones (LAB-117)» en `ml/README.md` (qué es, cómo construirlo, revisarlo y
  validarlo, variables `SHOPLAB_ML_LLM_*`, aviso de que es borrador hasta la revisión humana y que el golden se solapa) y
  actualizar la estructura; añadir al final de la fila de `ml/` del `README.md` raíz el dataset de intenciones
- [ ] T063 ⚠ persona Completar y **firmar** `ml/data/intents/REVIEW.md` (revisor, fecha, lotes, estado de cada apartado). **Esta
  tarea no la puede cerrar el asistente** (como en LAB-27): mientras esté pendiente, el estado del dataset sigue siendo BORRADOR y
  no se debe dar la historia por terminada
- [X] T064 Ejecutar el recorrido de `quickstart.md` (A–F) y crear `specs/LAB-117-intent-labeled-dataset/validation.md` con: ejemplos
  por clase e idioma y por partición, kappa inicial y tipo de anotador B, paráfrasis generadas / descartadas por filtro (con
  motivo) / rechazadas en la revisión, la salida de `validate` y la comprobación `git diff --stat main -- backend ingest frontend
  infra eval data .github` **vacía**
  **Estado**: `validation.md` creado con lo ejecutado y lo **no** ejecutado; queda completarlo cuando existan los datos.
  `validation.md` completo (cifras, hallazgos y lo que ningún programa comprueba).
- [X] T065 Lista previa a commit (`.ai/rules/development-guidelines.md` §8): `cd ml && uv run ruff check . && uv run pytest`
  (cobertura combinada ≥ 80 %), `git status --short` (sin `work/`, sin `*.csv` de Olist, sin `__pycache__`),
  `rg -n "SHOPLAB_ML_LLM_API_KEY\s*=|sk-|Bearer " ml specs` sin valores, `git diff --staged` solo con cambios de la historia, y
  una búsqueda de datos personales sobre `ml/data/intents/` con `find_personal_data` (debe dar cero)
  **Estado**: ejecutado lo que no depende de los datos (ruff, pytest 444 en verde, FR-025 vacío, sin secretos, `work/` ignorado);
  se repite antes del commit final, con los datos.
  ruff limpio, 456 pruebas en verde (97,93 %), FR-025 vacío, sin secretos, `work/` ignorado, 0 datos personales en `text`.
- [ ] T066 Commit y PR con la skill `commit-and-push`: commits `type(scope): description` en inglés; **título del PR**
  `feat(ml): [LAB-117] dataset etiquetado de intenciones del cliente`. No se cierra LAB-117 en Jira hasta que T063 esté firmada
  y se valide el primer tag `vX.Y.Z`, como en las historias anteriores

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)** → **Foundational (2)** → resto. T013 (guía v1) debe existir antes de T031 y T034.
- **US4 (3)** antes que **US1 (4)**: `build` y `sources` usan `mask_text` y `validate` usa `find_personal_data`.
- **US1 (4)** antes que **US3 (5)** (necesita ejemplos que partir) y que **US2 (6)** (la muestra sale del dataset partido, T049
  depende de T042).
- **US5 (7)** al final: su validador y su ficha agregan las reglas e información de todas las demás; T061 solo cuando existan
  los datos.
- **Polish (8)** tras US5; T063 es independiente del código y puede avanzar mientras tanto, pero es la condición para cerrar.

### Dentro de cada historia

Pruebas (deben fallar) → implementación → registro del comando en la CLI → construcción de datos. `validation.py`,
`test_validation.py`, `test_cli.py` y `__main__.py` los comparten varias historias: sus tareas se hacen **en orden**, no en
paralelo.

### Parallel Opportunities

- Setup/Foundational: T004 y T005 a la vez; T006 y T008 a la vez; T013 (persona) corre en paralelo con todo el código.
- US4: T014 y T018 a la vez.
- US1: T019, T021 y T023 (pruebas de `sources`, `review` y `paraphrase`) a la vez; luego T020, T022 y T024 a la vez (módulos
  distintos). T030 (semillas) puede avanzar mientras tanto.
- US3: T036 en paralelo con las tareas de datos de US1 si ya hay un dataset provisional.
- US2: T043 en paralelo con T036–T041 (archivos distintos).
- US5: T053 en paralelo con T055.

```text
# Ejemplo US1 (código):  T019 + T021 + T023  →  T020 + T022 + T024  →  T025 → T026 → T027 → T028
# Ejemplo de datos:      T029 → T030 ∥ T031 → T032 → T033 → T034 → T035
```

---

## Implementation Strategy

### MVP First (lo que desbloquea a LAB-118)

1. Setup + Foundational (T001–T013).
2. **US4** (T014–T018): sin enmascarado no se escribe ningún dato.
3. **US1** (T019–T035): el dataset existe y cumple los mínimos.
4. **US3** (T036–T042): partición fija. **Parar y validar**: con esto LAB-118 ya puede entrenar y evaluar.
5. Después US2 (acuerdo), US5 (ficha y control en CI) y Polish.

### Qué NO es verificable por código

El validador comprueba formato, mínimos, fugas, patrones de datos personales, kappa y coherencia de la ficha. **No puede
comprobar** que una etiqueta sea la correcta, que un nombre de persona no se haya colado, que una paráfrasis conserve la
intención ni que un `charla` no contenga una petición real: eso lo cubren T031, T034, T050, T052 y T063. Un `validate` en verde
no sustituye esa revisión.

---

## Notes

- 66 tareas: Setup 3, Foundational 10, US4 5, US1 17, US3 7, US2 10, US5 9, Polish 5.
- La regla de «solicitudes HTTP tras implementar» no aplica: esta historia **no añade ni cambia ningún endpoint** (no habrá
  `requests.http`); `/speckit-implement` debe decirlo en una línea.
- Si se decide crear una clave de gateway propia para `ml` (alternativa descartada en `research.md` R5), es una historia de
  `infra/` aparte, no una tarea de esta.
