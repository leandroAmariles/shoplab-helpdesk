# Contrato: CLI y artefactos de `intent`

Contrato de ejecución de `train.py` y `evaluate.py` y formato de los artefactos que consumen `LAB-119` (MLflow),
`LAB-120` (`ml-service`), `LAB-122` (DVC/CI), `LAB-125` (model card y auditoría de sesgos) y `LAB-126` (comparativa con
deep learning). Todas las rutas relativas son respecto a `ml/`.

## 1. Puntos de entrada de línea de comandos

El paquete expone tres formas equivalentes de invocación desde `ml/`:

1. Como módulos del paquete `intent`:
   - `uv run python -m intent.train [opciones]`
   - `uv run python -m intent.evaluate [opciones]`
2. Como subcomandos de `intent`:
   - `uv run python -m intent train [opciones]`
   - `uv run python -m intent evaluate [opciones]`
   - `uv run python -m intent embed-cache [opciones]`
3. Como scripts directos en `ml/intent/`:
   - `uv run python intent/train.py [opciones]`
   - `uv run python intent/evaluate.py [opciones]`

### Códigos de salida

| Código | Significado |
|---|---|
| `0` | Entrenamiento o evaluación completados y todos los umbrales cumplidos (en `evaluate`, `test_f1_macro >= --min-f1-macro`) |
| `1` | Umbral de calidad incumplido (`test_f1_macro < --min-f1-macro`) o fuga/inconsistencia detectada en las particiones (`DataLeakageError`) |
| `2` | Error de uso o de entorno (archivo inexistente, argumento inválido, caché de embeddings ausente cuando se pide la variante densa sin `--allow-remote-embeddings`) |

---

### `train.py`

Entrena y compara las variantes sobre `train` y `val`, selecciona el modelo ganador (o la variante forzada por
`--variant`) y serializa el `Pipeline` completo en un único artefacto `.joblib` junto con `train_metadata.json`.
**Nunca lee ni utiliza ejemplos con `split == "test"`.**

```text
uv run python -m intent.train \
  [--dataset data/intents/intents.jsonl] \
  [--embeddings-cache data/intents/embeddings_cache.npz] \
  [--artifact artifacts/intent/intent_classifier.joblib] \
  [--metadata-out artifacts/intent/train_metadata.json] \
  [--seed 118] \
  [--cv-folds 5] \
  [--variant auto|tfidf_logreg|tfidf_linearsvc|embedding_logreg|dummy_majority] \
  [--allow-remote-embeddings]
```

- `--variant auto` (por defecto): ejecuta las 4 variantes (`dummy_majority`, `tfidf_logreg`, `tfidf_linearsvc`,
  `embedding_logreg`), escribe la tabla comparativa completa en `train_metadata.json` y empaqueta la ganadora según la
  regla de R5.
- Imprime por `stdout` la tabla comparativa en Markdown (`variant`, `cv_f1_macro`, `val_f1_macro`, `val_brier`,
  `val_ece`, `latency_ms`) y la variante seleccionada.

---

### `evaluate.py`

Carga el artefacto `.joblib` serializado y lo evalúa **una sola vez** sobre los ejemplos con `split == "test"` de
`intents.jsonl`.

```text
uv run python -m intent.evaluate \
  [--dataset data/intents/intents.jsonl] \
  [--artifact artifacts/intent/intent_classifier.joblib] \
  [--report-out artifacts/intent/eval_report.json] \
  [--min-f1-macro 0.85]
```

- Imprime por `stdout` el informe en Markdown (F1 macro global frente al baseline trivial y al objetivo `0.85`, tabla de
  precisión/recall/F1 por clase, matriz de confusión 5×5, métricas y tramos de calibración, simulación de umbrales de
  confianza, desglose por idioma `es`/`pt` y subconjunto sin golden).
- Escribe `--report-out` en JSON UTF-8 con formato estable.
- Devuelve código `0` si `test_f1_macro >= --min-f1-macro` (`0.85` por defecto), o `1` en caso contrario.

---

### `embed-cache`

Genera o verifica el archivo `data/intents/embeddings_cache.npz` con los vectores de los ejemplos de `intents.jsonl`.

```text
uv run python -m intent embed-cache \
  [--dataset data/intents/intents.jsonl] \
  [--out data/intents/embeddings_cache.npz] \
  [--mode gateway|deterministic-projection]
```

- `--mode gateway`: consulta `POST {SHOPLAB_ML_EMBED_BASE_URL}/embeddings` (por defecto `http://localhost:4000/v1`,
  modelo `SHOPLAB_ML_EMBED_MODEL` = `embed`, clave `SHOPLAB_ML_LLM_API_KEY`) para obtener los embeddings del modelo de
  retrieval (LAB-21 / LAB-84).
- `--mode deterministic-projection`: genera o verifica la caché local determinista del modelo de embeddings de
  referencia para que el repositorio y CI puedan ejecutar la variante densa sin contenedores activos.

---

## 2. Contrato del artefacto `intent_classifier.joblib`

Quien cargue el artefacto con `pipeline = joblib.load("artifacts/intent/intent_classifier.joblib")` (LAB-119, LAB-120,
LAB-126) tiene garantizado:

1. **Tipo**: `sklearn.pipeline.Pipeline`.
2. **Entrada**: ` Sequence[str]` (lista o iterable de mensajes de cliente en texto crudo, en español o portugués).
3. **`pipeline.classes_`**: array con las 5 etiquetas de `CANONICAL_LABELS`
   (`politica`, `producto`, `pedido`, `escalado`, `charla`).
4. **`pipeline.predict(texts)`**: devuelve `np.ndarray` de forma `(len(texts),)` con la etiqueta predicha para cada
   mensaje, idéntica a `pipeline.classes_[np.argmax(pipeline.predict_proba(texts), axis=1)]`.
5. **`pipeline.predict_proba(texts)`**: devuelve `np.ndarray` de `float64` de forma `(len(texts), 5)` con probabilidades
   calibradas en `[0.0, 1.0]` tales que `np.allclose(probs.sum(axis=1), 1.0, atol=1e-6)`.
6. **Validación de entrada vacía**: si algún elemento de `texts` no es `str` o está vacío tras `strip()`, lanza
   `EmptyTextError` (subclase de `ValueError`).

---

## 3. Esquema de `eval_report.json`

```json
{
  "schema_version": "1.0",
  "artifact_path": "artifacts/intent/intent_classifier.joblib",
  "artifact_sha256": "<64-hex>",
  "dataset_sha256": "<64-hex>",
  "test_count": 102,
  "target_f1_macro": 0.85,
  "passed_target": true,
  "test_f1_macro": 0.90,
  "test_accuracy": 0.91,
  "baseline_majority_label": "pedido",
  "baseline_f1_macro": 0.0887,
  "baseline_accuracy": 0.2843,
  "per_class": {
    "politica": {"precision": 0.90, "recall": 0.90, "f1": 0.90, "support": 20},
    "producto": {"precision": 0.90, "recall": 0.90, "f1": 0.90, "support": 21},
    "pedido": {"precision": 0.90, "recall": 0.90, "f1": 0.90, "support": 29},
    "escalado": {"precision": 0.90, "recall": 0.90, "f1": 0.90, "support": 20},
    "charla": {"precision": 0.90, "recall": 0.90, "f1": 0.90, "support": 12}
  },
  "confusion_matrix": {
    "labels": ["politica", "producto", "pedido", "escalado", "charla"],
    "matrix": [
      [18, 0, 1, 0, 1],
      [0, 19, 1, 1, 0],
      [1, 1, 26, 1, 0],
      [0, 1, 1, 18, 0],
      [1, 0, 0, 0, 11]
    ]
  },
  "calibration": {
    "brier_score": 0.14,
    "log_loss": 0.31,
    "ece": 0.05,
    "bins": [
      {"bin_lower": 0.0, "bin_upper": 0.2, "count": 0, "mean_confidence": null, "accuracy": null, "gap": null}
    ]
  },
  "confidence_thresholds": [
    {"threshold": 0.50, "retained_count": 98, "fallback_count": 4, "coverage": 0.9608, "accuracy_retained": 0.9286, "f1_macro_retained": 0.9210}
  ],
  "by_language": {
    "es": {"count": 45, "f1_macro": 0.91, "accuracy": 0.91},
    "pt": {"count": 57, "f1_macro": 0.89, "accuracy": 0.89}
  },
  "non_golden_subset": {
    "count": 85,
    "f1_macro": 0.89,
    "accuracy": 0.90
  },
  "misclassified_examples": [],
  "latency_ms_total": 4.2,
  "latency_ms_per_example": 0.041
}
```
*(Los valores numéricos del bloque anterior son ilustrativos del esquema; los reales se registran al ejecutar
`evaluate.py`).*
