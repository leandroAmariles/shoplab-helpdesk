# Validación y Evidencias de Implementación: LAB-118

**Historia**: LAB-118 (F8-03: *Clasificador de intención con scikit-learn*)  
**Rama**: `feature/LAB-118-intent-classifier-sklearn`  
**Fecha de validación**: 2026-11-23  
**Semilla**: `118`  
**Dataset SHA-256**: `3f9c7df348abd52f6fbf0389fe319e1b212f18376f9c968f9e612f026a7f34c2`  

---

## 1. Resumen de Cumplimiento de Criterios de Aceptación

| Criterio | Requisito | Resultado Obtenido | Estado |
|---|---|---|:---:|
| **F1 macro global en `test`** | **`≥ 0.85`** | **`0.8788`** | ✅ CUMPLE |
| **Exactitud global en `test`** | Superior a baseline trivial | **`0.8725`** (vs baseline `0.2843`) | ✅ CUMPLE |
| **Baseline trivial** | Clase mayoritaria (`pedido`) | F1 macro = `0.0886`, exactitud = `0.2843` | ✅ CUMPLE |
| **Latencia de inferencia en CPU** | `< 15.0 ms/mensaje` | **`0.1711 ms/mensaje`** (17.45 ms total / 102 msg) | ✅ CUMPLE |
| **Serialización** | Único artefacto `joblib` | `ml/artifacts/intent/intent_classifier.joblib` (519 KB) | ✅ CUMPLE |
| **Trazabilidad de entrenamiento** | Metadata en JSON UTF-8 | `ml/artifacts/intent/train_metadata.json` | ✅ CUMPLE |
| **Informe de evaluación en `test`** | Reporte en JSON UTF-8 | `ml/artifacts/intent/eval_report.json` | ✅ CUMPLE |
| **Protocolo anti-leakage** | Cero fuga `train`/`val`/`test` | `assert_no_test_examples` y `StratifiedGroupKFold` | ✅ CUMPLE |
| **Cobertura de pruebas** | `≥ 80 %` | **`95.57 %`** (498 tests pasados, 0 fallos) | ✅ CUMPLE |
| **Linter / formateo** | `ruff check .` sin warnings | **0 errores** (All checks passed!) | ✅ CUMPLE |
| **Aislamiento de arquitectura** | No tocar backend/frontend/eval | Solo archivos en `ml/`, `specs/` y `README.md` | ✅ CUMPLE |

---

## 2. Comparativa de Variantes de Representación (Entrenamiento y Selección)

Ejecutado con:
```bash
uv run python -m intent.train --seed 118
```

Particiones empleadas: `train=481`, `val=102` (`test` estrictamente no leído).

| Variante | CV F1 macro (train) | Val F1 macro | Val Accuracy | Val Brier | Val Log-Loss | Val ECE | Latencia CPU (ms/msg) |
|---|---|---|---|---|---|---|---|
| `dummy_majority` | 0.0892 ± 0.0011 | **0.0886** | 0.2843 | 0.7861 | 1.5738 | 0.0026 | 0.0123 |
| **`tfidf_logreg`** | **0.8407 ± 0.0414** | **`0.8447`** | **0.8333** | **0.3567** | **0.7284** | **0.2679** | **0.1303** |
| `tfidf_linearsvc` | 0.8489 ± 0.0259 | **0.8246** | 0.8137 | 0.2548 | 0.4860 | 0.1207 | 0.1694 |
| `embedding_logreg` | 0.6411 ± 0.0283 | **0.5859** | 0.5784 | 0.5601 | 1.0656 | 0.0894 | 0.0814 |

### Justificación de selección
> **Variante seleccionada**: `tfidf_logreg`  
> **Motivo**: Alcanza el mejor rendimiento en validación (`val_f1_macro = 0.8447`, `cv_f1_macro = 0.8407`) y supera con creces a la variante de embeddings densos locales (`0.5859`), siendo 100 % autocontenida en CPU sin llamadas externas en inferencia.  
> **Configuración óptima reajustada sobre `train + val`**:  
> `C=1.0`, `class_weight='balanced'`, `word__min_df=1`, `char__min_df=2`.

---

## 3. Resultados de Evaluación en la Partición `test` (102 ejemplos)

Ejecutado con:
```bash
uv run python -m intent.evaluate --min-f1-macro 0.85
```

- **Muestra evaluada**: 102 ejemplos independientes.
- **F1 macro alcanzado**: **`0.8788`** (Objetivo `>= 0.85` → **CUMPLE**).
- **Exactitud alcanzada**: **`0.8725`**.
- **Latencia media en CPU**: **`0.1711 ms/mensaje`**.

### 3.1. Métricas por clase

| Clase (`label`) | Precisión | Recall | F1 Score | Soporte (`test`) |
|---|---|---|---|---|
| `politica` | 0.9000 | 0.9000 | **0.9000** | 20 |
| `producto` | 0.8000 | 0.7619 | **0.7805** | 21 |
| `pedido` | 0.7941 | 0.9310 | **0.8571** | 29 |
| `escalado` | 1.0000 | 0.9000 | **0.9474** | 20 |
| `charla` | 1.0000 | 0.8333 | **0.9091** | 12 |

### 3.2. Matriz de confusión 5×5 (filas = real, columnas = predicha)

| Real \ Pred | `politica` | `producto` | `pedido` | `escalado` | `charla` |
|---|:---:|:---:|:---:|:---:|:---:|
| **`politica`** | **18** | 1 | 1 | 0 | 0 |
| **`producto`** | 1 | **16** | 4 | 0 | 0 |
| **`pedido`** | 0 | 2 | **27** | 0 | 0 |
| **`escalado`** | 0 | 0 | 2 | **18** | 0 |
| **`charla`** | 1 | 1 | 0 | 0 | **10** |

### 3.3. Calibración de Probabilidades

- **Brier Score Multiclase**: `0.3132`
- **Log-Loss**: `0.6573`
- **Expected Calibration Error (ECE)**: `0.2967`

### 3.4. Simulación de Umbrales de Confianza (para LAB-121)

| Umbral ($T$) | Retenidos | Fallback | Cobertura | Exactitud retenidos | F1 macro retenidos |
|:---:|:---:|:---:|:---:|:---:|:---:|
| `0.50` | 67 | 35 | 65.69 % | 0.9552 | 0.9597 |
| `0.60` | 47 | 55 | 46.08 % | 0.9787 | 0.9666 |
| `0.70` | 23 | 79 | 22.55 % | 1.0000 | 1.0000 |
| `0.75` | 19 | 83 | 18.63 % | 1.0000 | 1.0000 |
| `0.80` | 11 | 91 | 10.78 % | 1.0000 | 1.0000 |
| `0.85` | 3 | 99 | 2.94 % | 1.0000 | 1.0000 |
| `0.90` | 1 | 101 | 0.98 % | 1.0000 | 1.0000 |

### 3.5. Subgrupos Lingüísticos y Golden

- **Español (`es`)**: F1 macro = **`0.9158`**, Exactitud = `0.9111` ($n=45$).
- **Portugués (`pt`)**: F1 macro = **`0.8349`**, Exactitud = `0.8421` ($n=57$).
- **Subconjunto sin golden (`golden_id == null`)**: F1 macro = **`0.7303`**, Exactitud = `0.8219` ($n=73$).

---

## 4. Cobertura de Pruebas Unitarias e Integración

Resultado de `cd ml && pytest tests/`:
- **Total de pruebas ejecutadas**: **498 tests**
- **Pruebas fallidas**: **0**
- **Cobertura total**: **`95.57 %`** (mínimo requerido 80 %)
- **Tiempo de ejecución**: `49.17 s` en CPU local sin red ni contenedores.

---

## 5. Verificación de Límites de Arquitectura (FR-018)

Verificado mediante `git status` y `git diff --stat main`:
- Ningún archivo modificado ni creado en:
  - `backend/`
  - `frontend/`
  - `ingest/`
  - `eval/`
  - `infra/`
- Toda la lógica reside estrictamente dentro de `ml/src/intent/`, `ml/intent/`, `ml/tests/intent/`, `ml/artifacts/intent/`, `specs/LAB-118-intent-classifier-sklearn/` y los READMEs documentales.
