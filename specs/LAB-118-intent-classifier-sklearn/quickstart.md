# Quickstart: entrenar y evaluar el clasificador de intención (`LAB-118`)

Guía para comprobar de extremo a extremo en CPU que el clasificador de intención clásico se entrena, compara las
variantes exigidas, serializa el `Pipeline` en un único artefacto y supera el objetivo de **F1 macro ≥ 0.85** en la
partición `test`.

Los esquemas y banderas de línea de comandos están en
[contracts/cli-and-artifacts.md](contracts/cli-and-artifacts.md) y las entidades en [data-model.md](data-model.md).

## Prerrequisitos

- Python 3.12+ y `uv`.
- El dataset etiquetado de LAB-117 en `ml/data/intents/intents.jsonl` (685 ejemplos con partición fija `train`/`val`/`test`).
- **No requiere GPU, PostgreSQL ni contenedores activos** cuando se usa la caché local de embeddings del dataset
  (`ml/data/intents/embeddings_cache.npz`).
- *(Opcional)* Para recalcular los embeddings contra el modelo de retrieval en vivo (LAB-21 / LAB-84): `docker compose
  -f infra/docker-compose.yml up -d --wait` y `SHOPLAB_ML_LLM_API_KEY` con la clave virtual `eval`.

## A. Sincronizar dependencias y ejecutar lint + tests (lo que corre en CI)

```bash
cd ml
uv sync
uv run ruff check .
uv run pytest
```

**Debe verse**:
- `ruff check .` sin errores.
- Todas las pruebas de `olist_eda`, `intent_dataset` e `intent` en verde, con cobertura total ≥ 80 % (SC-006).
- Incluye las pruebas unitarias de `TextPreprocessor` (español, portugués y marcas `[ID]`/`[EMAIL]`/`[PHONE]`), el test
  de determinismo con semilla fija y las guardas que rechazan ejemplos con `split="test"` durante el entrenamiento.

## B. Entrenar y comparar variantes (`train.py`)

```bash
cd ml
uv run python -m intent.train --seed 118
# Equivalente directo:
# uv run python intent/train.py --seed 118
```

**Debe verse**:
1. Tabla comparativa con las 4 variantes sobre `train` (5-fold `StratifiedGroupKFold`) y `val` (102 ejemplos):
   - `dummy_majority` (baseline trivial: clase mayoritaria `pedido`)
   - `tfidf_logreg` (TF-IDF palabras + n-gramas de caracteres + `LogisticRegression`)
   - `tfidf_linearsvc` (TF-IDF palabras + n-gramas de caracteres + `LinearSVC` calibrado)
   - `embedding_logreg` (Embeddings de retrieval + `LogisticRegression`)
2. Selección determinista de la variante ganadora sin haber leído ningún ejemplo de `test` (SC-003).
3. Artefacto único serializado en `ml/artifacts/intent/intent_classifier.joblib` y metadatos en
   `ml/artifacts/intent/train_metadata.json` (con los términos con más peso por clase).

## C. Evaluar una única vez en la partición `test` (`evaluate.py`)

```bash
cd ml
uv run python -m intent.evaluate --min-f1-macro 0.85
# Equivalente directo:
# uv run python intent/evaluate.py --min-f1-macro 0.85
```

**Debe verse**:
1. **F1 macro en `test` ≥ 0.85** (frente a ~0,09 del baseline de clase mayoritaria) y código de salida `0` (SC-001).
2. Tabla de precisión, recall, F1 y soporte de las 5 clases (`politica`, `producto`, `pedido`, `escalado`, `charla`).
3. Matriz de confusión 5×5 sobre los 102 casos de `test`.
4. Métricas de calibración de probabilidades (`brier_score`, `log_loss`, `ece` y tabla por tramos de confianza) y tabla
   de cobertura frente a F1/precisión para umbrales de confianza de `0.50` a `0.90` (SC-002).
5. Desglose de F1 macro por idioma (`es`, `pt`) y en el subconjunto de `test` sin ascendencia del golden
   (`golden_id == null`).
6. Informe completo escrito en `ml/artifacts/intent/eval_report.json`.

## D. Comprobar el determinismo con la misma semilla (SC-004)

```bash
cd ml
uv run python -m intent.train --seed 118 --artifact /tmp/m1.joblib --metadata-out /tmp/meta1.json
uv run python -m intent.evaluate --artifact /tmp/m1.joblib --report-out /tmp/eval1.json
uv run python -m intent.train --seed 118 --artifact /tmp/m2.joblib --metadata-out /tmp/meta2.json
uv run python -m intent.evaluate --artifact /tmp/m2.joblib --report-out /tmp/eval2.json
```

**Debe verse**: las métricas, la matriz de confusión y las predicciones de `/tmp/eval1.json` y `/tmp/eval2.json`
coinciden en el 100 % de los ejemplos de `test`.

## E. Comprobar que no cambió ningún módulo ajeno (FR-018)

```bash
git diff --stat main -- backend ingest frontend infra eval data .github
```

**Debe verse**: vacío. Todos los cambios viven en `ml/`, `specs/LAB-118-intent-classifier-sklearn/`, `.gitignore` y
`README.md`.
