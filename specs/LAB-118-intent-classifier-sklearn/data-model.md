# Data Model: Clasificador de intención con scikit-learn

Entidades, transformadores, estructuras de métricas y reglas de validación del paquete `intent` (`ml/src/intent/`). Los
esquemas exactos de archivo y línea de comandos están en [contracts/cli-and-artifacts.md](contracts/cli-and-artifacts.md).

## Constantes (`constants.py`)

| Constante | Valor | Origen |
|---|---|---|
| `CANONICAL_LABELS` | `("politica", "producto", "pedido", "escalado", "charla")` | FR-002 / LAB-117 |
| `LANGUAGES` | `("es", "pt")` | FR-002 / LAB-117 |
| `DEFAULT_SEED` | `118` | FR-009, FR-016 |
| `MIN_TEST_F1_MACRO` | `0.85` | FR-015, SC-001 |
| `CV_FOLDS` | `5` | R5 (`StratifiedGroupKFold`) |
| `CONFIDENCE_THRESHOLDS` | `(0.50, 0.60, 0.70, 0.75, 0.80, 0.85, 0.90)` | FR-014, SC-002 |
| `CALIBRATION_BINS` | `10` | FR-014, R6 |
| `MASK_TOKENS` | Mapeo `[URL]` → `__mask_url__`, `[EMAIL]` → `__mask_email__`, `[ID]` → `__mask_id__`, `[CNPJ]` → `__mask_cnpj__`, `[CPF]` → `__mask_cpf__`, `[CEP]` → `__mask_cep__`, `[PHONE]` → `__mask_phone__`, `[NAME]` → `__mask_name__` | FR-004, R2 |
| `VARIANT_NAMES` | `("dummy_majority", "tfidf_logreg", "tfidf_linearsvc", "embedding_logreg")` | FR-005..FR-007 |

## Entrada: Registro del Dataset (`Example` de `intent_dataset.dataset`)

Se reutiliza el lector `intent_dataset.dataset.load_examples` sobre `ml/data/intents/intents.jsonl`:

| Campo | Tipo | Uso en `intent` |
|---|---|---|
| `id` | `str` (`I0001`…) | Trazabilidad en errores de clasificación e índice de caché de embeddings |
| `text` | `str` | Entrada cruda al `Pipeline` (`TextPreprocessor`) |
| `label` | `str` ∈ `CANONICAL_LABELS` | Variable objetivo `y` |
| `language` | `str` (`es` \| `pt`) | Desglose de métricas por idioma en `evaluate.py` |
| `group_id` | `str` | Agrupación obligatoria en `StratifiedGroupKFold` sobre `train` y verificación anti-fuga |
| `golden_id` | `str \| None` | Desglose en `test` con y sin ascendencia del golden (`golden_id is None`) |
| `split` | `str` (`train` \| `val` \| `test`) | `train` y `val` en `train.py`; `test` exclusivamente en `evaluate.py` |

### Invariantes verificadas antes de entrenar (`data.py`)

1. Ningún ejemplo tiene `label` fuera de `CANONICAL_LABELS` ni `text` vacío.
2. Ningún `group_id` ni texto normalizado aparece en más de un `split` (`DataLeakageError`).
3. Las funciones de ajuste y validación cruzada ejecutan `assert_no_test_examples(examples)` y lanzan `DataLeakageError`
   si algún elemento tiene `split == "test"`.

## Componentes del `Pipeline` de scikit-learn

### 1. `TextPreprocessor(BaseEstimator, TransformerMixin)` (`preprocess.py`)

Transformador sin estado aprendible (`fit` valida la entrada y devuelve `self`; `transform` procesa una secuencia de
`str` y devuelve `list[str]`):

- Rechaza `None`, tipos no `str` o cadenas que tras `strip()` queden vacías (`EmptyTextError`, subclase de `ValueError`).
- Normaliza Unicode a `NFC` y reemplaza caracteres de control/saltos de línea por espacio.
- Sustituye cada marca de `MASK_TOKENS` (case-insensitive) por su token alfanumérico (`__mask_id__`, etc.) rodeado de
  espacios para que ningún signo adyacente (`[ID].` o `#[ID]`) se pegue al token.
- Convierte a minúsculas y colapsa espacios repetidos.
- **Invariante**: `preprocess_text(preprocess_text(x)) == preprocess_text(x)` (idempotente).

### 2. Extracción de características léxicas (`features.py`)

`FeatureUnion` con dos ramas sobre la salida de `TextPreprocessor`:

- `word`: `TfidfVectorizer(analyzer="word", ngram_range=(1, 2), sublinear_tf=True, token_pattern=r"(?u)\b\w+\b", lowercase=False)`
- `char`: `TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), sublinear_tf=True, lowercase=False)`

### 3. `EmbeddingTransformer(BaseEstimator, TransformerMixin)` (`embeddings.py`)

Transformador de `list[str]` a matriz densa `np.ndarray` de forma `(n_samples, embedding_dim)` (`dtype=float32`):

| Atributo | Tipo | Descripción |
|---|---|---|
| `cache_path` | `str` | Ruta al archivo `.npz` con vectores precalculados indexados por `sha256(text)` |
| `model_name` | `str` | Nombre lógico del modelo de embeddings (p. ej. `embed` / `bge-m3`) |
| `offline_only` | `bool` | Si es `True` (defecto en entrenamiento y CI), nunca realiza llamadas HTTP; falla con `EmbeddingUnavailableError` si falta un texto en la caché |
| `base_url` | `str` | URL del gateway (`http://localhost:4000/v1`) cuando `offline_only=False` |

### 4. Clasificador y calibración (`models.py`)

Cada variante es un `sklearn.pipeline.Pipeline` cuyo último paso expone `predict(texts)` y `predict_proba(texts)`:

| Variante (`variant_name`) | Pasos del `Pipeline` | Hiperparámetros buscados en CV (`train`) |
|---|---|---|
| `dummy_majority` | `preprocess` → `DummyClassifier(strategy="prior")` | — (sin búsqueda) |
| `tfidf_logreg` | `preprocess` → `features (word+char)` → `LogisticRegression` (con calibración opcional evaluada en `val`) | `C`, `class_weight`, `word__min_df`, `char__min_df` |
| `tfidf_linearsvc` | `preprocess` → `features (word+char)` → `CalibratedClassifierCV(LinearSVC, method="sigmoid")` | `C`, `class_weight`, `word__min_df`, `char__min_df` |
| `embedding_logreg` | `preprocess` → `embed` → `Normalizer("l2")` → `LogisticRegression` | `C`, `class_weight` |

## Entidades de Resultado y Persistencia

### `VariantComparisonEntry` (en `train_metadata.json`)

| Campo | Tipo | Regla |
|---|---|---|
| `variant` | `str` ∈ `VARIANT_NAMES` | Identificador de la variante |
| `best_params` | `dict[str, Any]` | Mejores hiperparámetros hallados por `GridSearchCV` en `train` (`{}` en dummy) |
| `cv_f1_macro_mean` | `float` | Media de F1 macro en los 5 pliegues de `StratifiedGroupKFold` sobre `train` |
| `cv_f1_macro_std` | `float` | Desviación estándar entre los 5 pliegues |
| `val_f1_macro` | `float` | F1 macro sobre los 102 ejemplos de `val` |
| `val_accuracy` | `float` | Exactitud sobre `val` |
| `val_brier_score` | `float` | Brier score multiclase sobre `val` |
| `val_log_loss` | `float` | Log-loss sobre `val` |
| `val_ece` | `float` | Expected Calibration Error sobre `val` |
| `val_latency_ms_per_msg` | `float` | Tiempo medio de inferencia por mensaje en CPU sobre `val` |
| `requires_external_service` | `bool` | `False` en `dummy_majority`, `tfidf_logreg`, `tfidf_linearsvc`; `True` en `embedding_logreg` para textos nuevos fuera de caché |

### `TrainMetadata` (`train_metadata.json`)

| Campo | Tipo | Descripción |
|---|---|---|
| `schema_version` | `str` (`"1.0"`) | Versión del esquema de metadatos |
| `seed` | `int` | Semilla utilizada en CV, estimadores y calibración |
| `dataset_path` | `str` | Ruta relativa al dataset leído |
| `dataset_sha256` | `str` | Hash SHA-256 de `intents.jsonl` |
| `dataset_human_reviewed` | `bool` | Estado de la revisión humana según `REVIEW.md` |
| `split_counts` | `dict[str, int]` | Conteo de ejemplos en `train` y `val` (`test` no se toca) |
| `labels` | `list[str]` | `["politica", "producto", "pedido", "escalado", "charla"]` |
| `selected_variant` | `str` | Variante ganadora según la regla de R5 |
| `selection_reason` | `str` | Explicación determinista de por qué ganó `selected_variant` |
| `selected_params` | `dict[str, Any]` | Hiperparámetros del modelo ganador |
| `variants` | `list[VariantComparisonEntry]` | Los 4 resultados comparados |
| `top_features_by_class` | `dict[str, list[dict[str, float]]]` | Top términos/n-gramas con mayor peso positivo por cada una de las 5 clases (explicabilidad) |
| `artifact_path` | `str` | Ruta al `.joblib` generado |
| `artifact_sha256` | `str` | Hash SHA-256 del artefacto `.joblib` |
| `environment` | `dict[str, str]` | Versiones de `python`, `scikit_learn`, `numpy`, `joblib` |

### `EvaluationReport` (`eval_report.json`)

Generado exclusivamente por `evaluate.py` sobre los ejemplos con `split == "test"`:

| Campo | Tipo | Descripción |
|---|---|---|
| `schema_version` | `str` (`"1.0"`) | Versión del esquema |
| `artifact_path` | `str` | Artefacto evaluado |
| `artifact_sha256` | `str` | Hash SHA-256 del `.joblib` cargado |
| `dataset_sha256` | `str` | Hash SHA-256 de `intents.jsonl` |
| `test_count` | `int` | Número de ejemplos en `test` (102) |
| `target_f1_macro` | `float` | Umbral exigido (`0.85` por defecto) |
| `passed_target` | `bool` | `test_f1_macro >= target_f1_macro` |
| `test_f1_macro` | `float` | F1 macro global del modelo sobre `test` |
| `test_accuracy` | `float` | Exactitud global sobre `test` |
| `baseline_majority_label` | `str` | Clase mayoritaria de referencia (`pedido`) |
| `baseline_f1_macro` | `float` | F1 macro del baseline trivial sobre `test` |
| `baseline_accuracy` | `float` | Exactitud del baseline trivial sobre `test` |
| `per_class` | `dict[str, ClassMetrics]` | Para cada clase en `CANONICAL_LABELS`: `precision`, `recall`, `f1`, `support` |
| `confusion_matrix` | `dict` | `labels`: `CANONICAL_LABELS`, `matrix`: lista 5×5 de enteros (`matrix[i][j]` = real `i`, predicha `j`) |
| `calibration` | `CalibrationMetrics` | `brier_score`, `log_loss`, `ece`, `bins` (lista de tramos con `bin_lower`, `bin_upper`, `count`, `mean_confidence`, `accuracy`, `gap`) |
| `confidence_thresholds` | `list[ThresholdSimulation]` | Para cada umbral en `CONFIDENCE_THRESHOLDS`: `threshold`, `retained_count`, `fallback_count`, `coverage`, `accuracy_retained`, `f1_macro_retained` |
| `by_language` | `dict[str, SubgroupMetrics]` | Para `es` y `pt`: `count`, `f1_macro`, `accuracy` |
| `non_golden_subset` | `SubgroupMetrics` | Métricas en `test` excluyendo casos con `golden_id != null` (`count`, `f1_macro`, `accuracy`) |
| `misclassified_examples` | `list[MisclassifiedCase]` | Casos de `test` donde `y_true != y_pred`: `id`, `language`, `source`, `text`, `true_label`, `predicted_label`, `confidence` |
| `latency_ms_total` | `float` | Tiempo total de inferencia sobre `test` en CPU |
| `latency_ms_per_example` | `float` | Tiempo medio por ejemplo en CPU |
