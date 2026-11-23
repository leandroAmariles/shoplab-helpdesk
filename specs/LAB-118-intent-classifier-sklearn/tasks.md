---

description: "Task list for LAB-118 - Clasificador de intención con scikit-learn"
---

# Tasks: Clasificador de intención con scikit-learn

**Input**: Design documents from `/specs/LAB-118-intent-classifier-sklearn/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/cli-and-artifacts.md](contracts/cli-and-artifacts.md), [quickstart.md](quickstart.md)

**Tests**: Incluidos y obligatorios (Principio IV de la Constitución y criterio explícito de la historia: «Tests
unitarios del preprocesado y de que la predicción es determinista con la misma semilla» + gate `--cov-fail-under=80` en
`ml/`). En cada fase las pruebas se escriben primero y deben fallar antes de implementar el módulo correspondiente.
Ninguna prueba abre red, ni conecta a PostgreSQL ni depende de Ollama/LiteLLM.

**Organization**: Tareas agrupadas por historia de usuario (US1, US2, US3 — todas P1 — y US4 — P2) para permitir
implementar y validar cada incremento de forma aislada.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Ejecutable en paralelo (archivos distintos, sin dependencias de tareas incompletas)
- **[Story]**: Historia de usuario a la que pertenece (`[US1]`, `[US2]`, `[US3]`, `[US4]`); sin etiqueta en Setup,
  Foundational y Polish
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Proyecto Python (`uv`)**: `ml/` (`shoplab-ml`)
- **Paquete principal**: `ml/src/intent/` + wrappers ejecutables directos en `ml/intent/train.py` y
  `ml/intent/evaluate.py`
- **Pruebas**: `ml/tests/intent/`
- **Datos de entrada**: `ml/data/intents/intents.jsonl` (LAB-117) y `ml/data/intents/embeddings_cache.npz`
- **Artefactos e informes**: `ml/artifacts/intent/` (`intent_classifier.joblib`, `train_metadata.json`,
  `eval_report.json`)
- **Restricción de alcance (FR-018)**: no se modifica nada en `backend/`, `frontend/`, `ingest/`, `eval/`, `data/`,
  `infra/` ni `.github/workflows/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verificar la línea base de `ml/`, incorporar `scikit-learn` al proyecto `uv` y preparar la estructura de
directorios del paquete `intent`.

- [X] T001 Verificar la línea base en la rama `feature/LAB-118-intent-classifier-sklearn`: `git status --short` limpio
  fuera de `specs/LAB-118-intent-classifier-sklearn/` y `.specify/feature.json`, y ejecutar
  `cd ml && uv sync && uv run ruff check . && uv run pytest` comprobando que las suites de `olist_eda` e
  `intent_dataset` pasan en verde
- [X] T002 Actualizar `ml/pyproject.toml` para añadir `"scikit-learn>=1.5"` en `dependencies`, incluir `"src/intent"` en
  `[tool.hatch.build.targets.wheel].packages` y añadir `--cov=intent` en `[tool.pytest.ini_options].addopts`; crear
  `ml/src/intent/__init__.py`, `ml/intent/__init__.py` y el directorio `ml/tests/intent/`, y sincronizar `ml/uv.lock`
  con `cd ml && uv sync`
- [X] T003 [P] Añadir `ml/artifacts/intent/*.joblib` a `.gitignore` (para no versionar binarios generados mientras se
  mantienen versionables `ml/artifacts/intent/train_metadata.json` y `ml/artifacts/intent/eval_report.json`) y crear
  `ml/artifacts/intent/.gitkeep`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Constantes canónicas, jerarquía de errores tipados, carga segura de particiones con guardas anti-fuga
(`DataLeakageError`) y transformador `TextPreprocessor` integrable en `Pipeline`.

**⚠️ CRITICAL**: Ninguna historia de usuario puede comenzar hasta completar esta fase.

- [X] T004 [P] Crear `ml/src/intent/constants.py` con las constantes del modelo de datos (`CANONICAL_LABELS =
  ("politica", "producto", "pedido", "escalado", "charla")`, `LANGUAGES = ("es", "pt")`, `DEFAULT_SEED = 118`,
  `MIN_TEST_F1_MACRO = 0.85`, `CV_FOLDS = 5`, `CONFIDENCE_THRESHOLDS = (0.50, 0.60, 0.70, 0.75, 0.80, 0.85, 0.90)`,
  `CALIBRATION_BINS = 10`, `MASK_TOKENS` mapeando `[URL]`, `[EMAIL]`, `[ID]`, `[CNPJ]`, `[CPF]`, `[CEP]`, `[PHONE]` y
  `[NAME]` a `__mask_url__`..`__mask_name__`, `VARIANT_NAMES = ("dummy_majority", "tfidf_logreg", "tfidf_linearsvc",
  "embedding_logreg")` y rutas por defecto relativas a `ml/`)
- [X] T005 [P] Crear `ml/src/intent/errors.py` con las excepciones tipadas `IntentClassifierError`,
  `EmptyTextError(IntentClassifierError, ValueError)`, `DataLeakageError(IntentClassifierError)`,
  `EmbeddingUnavailableError(IntentClassifierError)` y `EvaluationTargetError(IntentClassifierError)` con mensajes
  accionables en español
- [X] T006 [P] Escribir `ml/tests/intent/conftest.py` y `ml/tests/intent/test_data.py`: fixtures sintéticas bilingües
  (`es`, `pt`) con las 5 etiquetas de `CANONICAL_LABELS`, grupos `group_id` y paráfrasis; pruebas de
  `load_split_dataset` (separa `train`, `val`, `test`, calcula `dataset_sha256` y lee el estado de revisión humana de
  `REVIEW.md`), rechazo con `DataLeakageError` si un `group_id` o texto normalizado aparece en más de un `split`, y
  guarda `assert_no_test_examples` que lanza `DataLeakageError` en cuanto recibe un ejemplo con `split == "test"`;
  verificar que falla antes de T007
- [X] T007 Implementar `ml/src/intent/data.py` con `SplitDataset`, `load_split_dataset(dataset_path, review_path=None)`,
  `assert_no_test_examples(examples)`, `compute_file_sha256(path)` e `is_dataset_human_reviewed(review_path)`
  reutilizando `intent_dataset.dataset.load_examples` e `intent_dataset.splitting.effective_groups`
- [X] T008 [P] Escribir `ml/tests/intent/test_preprocess.py`: pruebas unitarias de `preprocess_text` y
  `TextPreprocessor` (`fit`, `transform`, `sklearn.base.clone`, ida y vuelta con `joblib.dumps`/`joblib.loads`):
  normalización Unicode `NFC`, paso a minúsculas conservando vocales acentuadas, `ñ` y `ç`, preservación de las 8 marcas
  de `MASK_TOKENS` (`[URL]`, `[EMAIL]`, `[ID]`, `[CNPJ]`, `[CPF]`, `[CEP]`, `[PHONE]`, `[NAME]`) como tokens
  `__mask_<tipo>__` incluso pegadas a signos de puntuación (`#[ID].`, `([EMAIL])`), idempotencia
  (`preprocess_text(preprocess_text(x)) == preprocess_text(x)`) y lanzamiento de `EmptyTextError` ante `None`, tipos no
  `str`, cadena vacía o de solo espacios/control; verificar que falla antes de T009
- [X] T009 Implementar `ml/src/intent/preprocess.py` con `preprocess_text(text: str) -> str` y
  `TextPreprocessor(BaseEstimator, TransformerMixin)` sin funciones `lambda` ni estado no serializable

**Checkpoint**: `cd ml && uv run ruff check . && uv run pytest tests/intent/test_data.py tests/intent/test_preprocess.py`
en verde.

---

## Phase 3: User Story 1 - Entrenar un clasificador clásico reproducible y empaquetarlo en un único artefacto (Priority: P1) 🎯 MVP

**Goal**: Disponer de un `Pipeline` de scikit-learn reproducible con semilla fija que una preprocesado, extracción TF-IDF
de palabras y caracteres, búsqueda de hiperparámetros con validación cruzada estratificada por grupos (`group_id`) sobre
`train`, calibración de probabilidades y serialización en un único archivo `.joblib`.

**Independent Test**: Entrenar el pipeline sobre datos de entrenamiento con semilla fija, serializarlo a disco con
`joblib`, cargarlo en memoria y comprobar que `predict` y `predict_proba` aceptan texto crudo con marcas `[ID]`/`[EMAIL]`
en español y portugués, devuelven etiquetas de `CANONICAL_LABELS` y 5 probabilidades en `[0, 1]` que suman `1.0`, y dan
exactamente el mismo resultado en dos entrenamientos con la misma semilla.

### Tests for User Story 1 ⚠️

- [X] T010 [P] [US1] Escribir las pruebas de pipeline, validación cruzada agrupada, determinismo y serialización en
  `ml/tests/intent/test_models_determinism.py`:
  - `build_tfidf_union` extrae n-gramas de palabras `(1, 2)` y caracteres `char_wb (3, 5)` y conserva `__mask_id__` en
    `get_feature_names_out()`;
  - `run_grouped_grid_search` usa `StratifiedGroupKFold` sobre `train` sin dividir ningún `group_id` entre pliegues y
    lanza `DataLeakageError` si recibe algún ejemplo con `split == "test"`;
  - Dos entrenamientos con la misma semilla (`118`) producen los mismos mejores hiperparámetros, las mismas etiquetas en
    `predict` y las mismas probabilidades en `predict_proba` (diferencia `< 1e-9`);
  - `save_pipeline_artifact` y `load_pipeline_artifact` conservan intactas las predicciones y verifican que
    `pipeline.classes_` contiene las 5 etiquetas de `CANONICAL_LABELS`;
  verificar que falla antes de T011–T013

### Implementation for User Story 1

- [X] T011 [P] [US1] Crear `ml/src/intent/features.py` con `build_tfidf_union(word_ngram_range=(1, 2),
  char_ngram_range=(3, 5), word_min_df=1, char_min_df=2) -> FeatureUnion` combinando `TfidfVectorizer` de palabras
  (`token_pattern=r"(?u)\b\w+\b"`, `sublinear_tf=True`, `lowercase=False`) y `TfidfVectorizer` de caracteres
  (`analyzer="char_wb"`, `sublinear_tf=True`, `lowercase=False`)
- [X] T012 [US1] Crear `ml/src/intent/models.py` (núcleo de entrenamiento de US1): constructor
  `build_tfidf_logreg_pipeline(seed=DEFAULT_SEED, c=1.0, class_weight=None, word_min_df=1, char_min_df=2)`, rejilla de
  hiperparámetros, búsqueda `run_grouped_grid_search(pipeline, param_grid, train_examples, *, seed=DEFAULT_SEED,
  cv_folds=CV_FOLDS)` con `StratifiedGroupKFold(n_splits=cv_folds, shuffle=True, random_state=seed)` y
  `assert_no_test_examples`
- [X] T013 [US1] Crear `ml/src/intent/artifacts.py` con `save_pipeline_artifact(pipeline, artifact_path)`,
  `load_pipeline_artifact(artifact_path)`, `extract_top_features_by_class(pipeline, top_k=15)` y
  `write_train_metadata(metadata, path)` con escritura atómica UTF-8

**Checkpoint**: `uv run pytest tests/intent/test_models_determinism.py` en verde; un `Pipeline` completo se entrena,
serializa y predice de forma determinista.

---

## Phase 4: User Story 2 - Comparar variantes de representación (léxica frente a embeddings de retrieval) contra un baseline trivial (Priority: P1)

**Goal**: Comparar bajo el mismo protocolo y semilla las cuatro variantes exigidas (`dummy_majority`, `tfidf_logreg`,
`tfidf_linearsvc` calibrado y `embedding_logreg` con embeddings de retrieval de LAB-21), registrar el cuadro comparativo
en `train_metadata.json` y seleccionar de forma determinista el modelo ganador desde `train.py`.

**Independent Test**: Ejecutar `train.py` sobre `train` y `val` con la caché local de embeddings y comprobar que evalúa
las 4 variantes, genera la tabla comparativa con métricas de CV (`train`) y `val` (F1 macro, Brier, log-loss, ECE,
latencia en CPU) y guarda el artefacto ganador sin leer `test`.

### Tests for User Story 2 ⚠️

- [X] T014 [P] [US2] Escribir `ml/tests/intent/test_embeddings.py`:
  - Guardado y carga de `EmbeddingCache` en `.npz` indexado por `sha256(texto_preprocesado)` con `model_name` y
    `dimension`;
  - `EmbeddingTransformer` con `offline_only=True` resuelve vectores desde la caché en memoria/disco sin red y lanza
    `EmbeddingUnavailableError` si un texto no existe en la caché;
  - `EmbeddingGatewayClient` con función HTTP `post` falsa envía el cuerpo OpenAI `/embeddings`, maneja errores HTTP sin
    filtrar `SHOPLAB_ML_LLM_API_KEY` y alimenta la caché;
  - `build_deterministic_embeddings_cache` genera vectores `float32` normalizados L2 reproducibles con la misma semilla;
  verificar que falla antes de T015
- [X] T015 [US2] Ampliar `ml/tests/intent/test_models_determinism.py` con pruebas de las variantes `dummy_majority`,
  `tfidf_linearsvc` (verificando que `CalibratedClassifierCV(LinearSVC, method="sigmoid")` expone `predict_proba` con 5
  columnas que suman `1.0` y que `predict` coincide con `argmax(predict_proba)`) y `embedding_logreg`, más la prueba de
  `compare_and_select_variants` (compara las 4 variantes sobre `train`/`val` y aplica la regla de selección de
  `research.md` R5); verificar que falla antes de T016–T017

### Implementation for User Story 2

- [X] T016 [US2] Implementar `ml/src/intent/embeddings.py` con `EmbeddingCache`, `EmbeddingGatewayClient` (variables
  `SHOPLAB_ML_EMBED_BASE_URL` por defecto `http://localhost:4000/v1`, `SHOPLAB_ML_EMBED_MODEL` por defecto `embed`,
  `SHOPLAB_ML_LLM_API_KEY` sin valor por defecto), `build_deterministic_embeddings_cache(examples, cache_path, *,
  dim=256, seed=DEFAULT_SEED)` y `EmbeddingTransformer(BaseEstimator, TransformerMixin)`
- [X] T017 [US2] Ampliar `ml/src/intent/models.py` con `build_dummy_pipeline(seed)`,
  `build_tfidf_linearsvc_pipeline(seed, cv_folds)`, `build_embedding_logreg_pipeline(cache_path, seed)`,
  `VariantComparisonEntry`, `compare_and_select_variants(train_examples, val_examples, *, embeddings_cache_path,
  seed=DEFAULT_SEED, cv_folds=CV_FOLDS, forced_variant="auto")` y el reajuste final del pipeline ganador sobre
  `train + val` (sin ningún ejemplo de `test`)
- [X] T018 [US2] Implementar `ml/src/intent/train.py` y el wrapper `ml/intent/train.py` según el contrato de
  [contracts/cli-and-artifacts.md](contracts/cli-and-artifacts.md) (`--dataset`, `--embeddings-cache`, `--artifact`,
  `--metadata-out`, `--seed`, `--cv-folds`, `--variant`, `--allow-remote-embeddings`), imprimiendo la tabla comparativa
  en Markdown y escribiendo `intent_classifier.joblib` y `train_metadata.json`
- [X] T019 [US2] Generar el archivo de caché `ml/data/intents/embeddings_cache.npz` para los 685 ejemplos de
  `ml/data/intents/intents.jsonl` (usando el gateway local si está activo o la proyección determinista del paquete) para
  que `embedding_logreg` sea ejecutable en CPU y en CI sin contenedores

**Checkpoint**: `uv run pytest tests/intent/test_embeddings.py tests/intent/test_models_determinism.py` en verde y
`uv run python -m intent.train` compara las 4 variantes y genera `train_metadata.json`.

---

## Phase 5: User Story 3 - Evaluar una única vez sobre la partición test con métricas por clase, matriz de confusión y calibración (Priority: P1)

**Goal**: Evaluar el artefacto `.joblib` entrenado sobre los 102 ejemplos de la partición `test` mediante `evaluate.py`,
reportando F1 macro (y verificando `≥ 0.85`), precisión/recall/F1 por clase, matriz de confusión 5×5, calibración de
probabilidades (Brier, log-loss, ECE y tabla por tramos), curva de cobertura frente a precisión/F1 por umbral de
confianza, desglose por idioma (`es`/`pt`) y subconjunto sin casos del golden.

**Independent Test**: Ejecutar `uv run python -m intent.evaluate --min-f1-macro 0.85` sobre el artefacto generado y
verificar que escribe `ml/artifacts/intent/eval_report.json` con todas las secciones del contrato y termina con código
de salida `0`.

### Tests for User Story 3 ⚠️

- [X] T020 [P] [US3] Escribir `ml/tests/intent/test_metrics_calibration.py`:
  - `compute_per_class_metrics` y `compute_confusion_matrix` respetan el orden exacto de `CANONICAL_LABELS` (5×5);
  - `compute_calibration_metrics` calcula `brier_score` multiclase, `log_loss`, `ece` y los `CALIBRATION_BINS` tramos de
    confianza (con `0.0 <= ece <= 1.0`, `ece == 0.0` en predicciones perfectamente calibradas);
  - `simulate_confidence_thresholds` calcula `retained_count`, `fallback_count`, `coverage`, `accuracy_retained` y
    `f1_macro_retained` para `CONFIDENCE_THRESHOLDS` (`0.50`..`0.90`), con `coverage` monótonamente decreciente;
  - `evaluate_on_test` calcula `by_language` (`es`, `pt`), `non_golden_subset` (`golden_id is None`),
    `baseline_f1_macro` y `misclassified_examples`;
  verificar que falla antes de T021

### Implementation for User Story 3

- [X] T021 [US3] Implementar `ml/src/intent/metrics.py` (`compute_per_class_metrics`, `compute_confusion_matrix`,
  `multiclass_brier_score`, `compute_calibration_metrics`, `simulate_confidence_thresholds`, `evaluate_predictions`) y
  añadir en `ml/src/intent/artifacts.py` `write_eval_report(report, path)` y `render_eval_markdown(report)`
- [X] T022 [US3] Implementar `ml/src/intent/evaluate.py`, el wrapper `ml/intent/evaluate.py` y el despachador CLI
  `ml/src/intent/__main__.py` (`train`, `evaluate`, `embed-cache`) según
  [contracts/cli-and-artifacts.md](contracts/cli-and-artifacts.md), devolviendo código `0` cuando
  `test_f1_macro >= --min-f1-macro` (`0.85` por defecto), `1` cuando no alcanza el umbral y `2` ante errores de uso o
  archivo ausente
- [X] T023 [US3] Ejecutar `cd ml && uv run python -m intent.train --seed 118` seguido de
  `uv run python -m intent.evaluate --min-f1-macro 0.85` para producir `ml/artifacts/intent/train_metadata.json` y
  `ml/artifacts/intent/eval_report.json`, verificando que el modelo seleccionado alcanza **F1 macro ≥ 0.85** en `test`

**Checkpoint**: `uv run python -m intent.evaluate --min-f1-macro 0.85` devuelve código `0` y escribe
`ml/artifacts/intent/eval_report.json` con todas las métricas exigidas.

---

## Phase 6: User Story 4 - Garantizar por pruebas unitarias el preprocesado, el determinismo y la ausencia de dependencias externas en CI (Priority: P2)

**Goal**: Cubrir de extremo a extremo la CLI (`train.py`, `evaluate.py`, wrappers en `ml/intent/` y `python -m intent`),
el determinismo en dos corridas seguidas y el cumplimiento del objetivo F1 macro ≥ 0.85 sobre el dataset real dentro de
la suite `pytest` que corre en integración continua sin red.

**Independent Test**: Ejecutar `cd ml && uv run ruff check . && uv run pytest` sin contenedores activos y comprobar que
todas las pruebas pasan con cobertura ≥ 80 %.

- [X] T024 [US4] Escribir `ml/tests/intent/test_train_evaluate_cli.py`:
  - Invocación de `intent.train:main` e `intent.evaluate:main` sobre un directorio temporal reproduce exactamente el
    mismo `test_f1_macro`, la misma matriz de confusión y las mismas probabilidades en dos corridas con `--seed 118`
    (SC-004);
  - Los wrappers `ml/intent/train.py` y `ml/intent/evaluate.py` y `python -m intent {train,evaluate,embed-cache}`
    exponen la misma interfaz y códigos de salida (`0`, `1` cuando `--min-f1-macro 0.999` no se cumple, `2` cuando falta
    un archivo);
  - Prueba sobre `ml/data/intents/intents.jsonl` y `ml/artifacts/intent/eval_report.json` reales que verifica que
    `passed_target is True`, `test_f1_macro >= 0.85`, `test_count == 102`, suma de probabilidades `1.0` y coherencia del
    hash SHA-256 del dataset
- [X] T025 [US4] Ejecutar `cd ml && uv run ruff check . && uv run pytest` y ajustar cualquier borde hasta tener 0
  avisos de `ruff` y cobertura ≥ 80 % en `olist_eda`, `intent_dataset` e `intent`

**Checkpoint**: Suite completa de `ml/` en verde con cobertura ≥ 80 % y sin red.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Documentación en `ml/README.md` y `README.md`, validación de `quickstart.md` con registro en
`validation.md` y lista previa a commit.

- [X] T026 [P] Documentar la sección «Clasificador de intención con scikit-learn (LAB-118)» en `ml/README.md` (comandos
  de entrenamiento y evaluación, tabla de resultados de las 4 variantes, métricas en `test`, matriz de confusión,
  calibración, umbral de confianza recomendado para LAB-121 y top términos por clase) y actualizar la fila de `ml/` en
  el `README.md` de la raíz
- [X] T027 Ejecutar los pasos A–E de [quickstart.md](quickstart.md) y registrar los resultados reales en
  `specs/LAB-118-intent-classifier-sklearn/validation.md` (comparativa de las 4 variantes en CV y `val`, métricas por
  clase y matriz de confusión 5×5 en `test`, Brier/log-loss/ECE, tabla de umbrales de confianza, F1 por idioma `es`/`pt`
  y subconjunto sin golden, latencia en CPU y prueba de determinismo)
- [X] T028 Ejecutar la lista previa a commit (`.ai/rules/development-guidelines.md` §8):
  `cd ml && uv run ruff check . && uv run pytest`, verificar `git status --short` (sin archivos temporales ni `.joblib`
  versionados) y confirmar que `git diff --stat main -- backend ingest frontend infra eval data .github` está vacío
  (FR-018)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)** → **Phase 2 (Foundational)** → **Phases 3–6 (User Stories)** → **Phase 7 (Polish)**.
- **Phase 2 (Foundational)** bloquea todas las historias: define `constants.py`, `errors.py`, `data.py` (con las guardas
  anti-fuga) y `preprocess.py` (`TextPreprocessor`).
- **US1 (Phase 3)** construye el pipeline TF-IDF base, la búsqueda `StratifiedGroupKFold` y la serialización en
  `artifacts.py`.
- **US2 (Phase 4)** extiende `models.py` con las otras tres configuraciones (`dummy_majority`, `tfidf_linearsvc`
  calibrado y `embedding_logreg` con `embeddings.py`) y añade `train.py`.
- **US3 (Phase 5)** añade `metrics.py` y `evaluate.py`, y ejecuta el entrenamiento y la evaluación oficial sobre `test`.
- **US4 (Phase 6)** cierra las pruebas integrales de CLI y determinismo sobre los artefactos reales.

### Parallel Opportunities

- **Phase 1**: `T002` y `T003` pueden ejecutarse en paralelo.
- **Phase 2**: `T004` (`constants.py`) y `T005` (`errors.py`) en paralelo; luego `T006` (`test_data.py`) y `T008`
  (`test_preprocess.py`) en paralelo; y `T007` (`data.py`) con `T009` (`preprocess.py`) en paralelo.
- **US1 / US2 / US3**: `T010` (`test_models_determinism.py`), `T014` (`test_embeddings.py`) y `T020`
  (`test_metrics_calibration.py`) tocan archivos de test distintos y pueden adelantarse en paralelo una vez terminada
  la Phase 2.

---

## Parallel Example: Foundational & Core Transformers

```text
# Tras T004 y T005:
Task T006: "Escribir ml/tests/intent/conftest.py y ml/tests/intent/test_data.py"
Task T008: "Escribir ml/tests/intent/test_preprocess.py"

# Implementación en paralelo en archivos distintos:
Task T007: "Implementar ml/src/intent/data.py"
Task T009: "Implementar ml/src/intent/preprocess.py"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Completar Phase 1 (Setup) y Phase 2 (Foundational).
2. Completar Phase 3 (US1): pipeline TF-IDF palabras+caracteres + `LogisticRegression` con `StratifiedGroupKFold` y
   serialización `.joblib`.
3. **Parar y validar**: comprobar con `pytest` que el artefacto se serializa, conserva marcas `[ID]`/`[EMAIL]` y es 100 %
   determinista con la misma semilla.

### Incremental Delivery

1. Añadir Phase 4 (US2): `EmbeddingTransformer`, caché local `.npz`, `LinearSVC` calibrado, `DummyClassifier` y CLI
   `train.py`.
2. Añadir Phase 5 (US3): métricas en `test`, matriz 5×5, calibración (Brier, log-loss, ECE, umbrales) y CLI
   `evaluate.py` verificando F1 macro ≥ 0.85.
3. Añadir Phase 6 (US4) y Phase 7 (Polish): tests de CLI/determinismo sobre el dataset real, documentación en
   `ml/README.md` y reporte `validation.md`.

---

## Notes

- **Total de tareas**: 28 (`Setup`: 3, `Foundational`: 6, `US1`: 4, `US2`: 6, `US3`: 4, `US4`: 2, `Polish`: 3).
- La regla `http-test-requests` no aplica a esta historia porque no añade ni modifica ningún endpoint HTTP (el servicio
  FastAPI `ml-service` llega en LAB-120).
