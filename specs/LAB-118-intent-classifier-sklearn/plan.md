# Implementation Plan: Clasificador de intención con scikit-learn

**Branch**: `feature/LAB-118-intent-classifier-sklearn` | **Date**: 2026-11-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-118-intent-classifier-sklearn/spec.md`

## Summary

Tercera historia de `ml/` (épica F8 · ML y MLOps, LAB-115): entrenar, comparar y evaluar un **clasificador clásico de
intención del cliente con scikit-learn** sobre el dataset etiquetado de LAB-117 (`ml/data/intents/intents.jsonl`, 685
ejemplos en español y portugués, 5 clases fijas y partición `train`/`val`/`test`), estructurado como paquete Python con
módulos ejecutables `train.py` y `evaluate.py` y empaquetado como un **único artefacto `Pipeline` de scikit-learn**.

- **Preprocesado y representación en un solo `Pipeline`** (US1): un transformador propio `TextPreprocessor` (compatible
  con `joblib`, sin lambdas) normaliza Unicode y espacios, preserva tildes, `ñ` y `ç`, y convierte las marcas de
  enmascarado de LAB-117 (`[ID]`, `[EMAIL]`, `[PHONE]`, `[CPF]`, `[CNPJ]`, `[CEP]`, `[URL]`, `[NAME]`) en tokens
  alfanuméricos canónicos (`__mask_id__`, etc.) para que los vectorizadores no las destruyan al separar puntuación.
- **Comparativa de variantes y baseline** (US2): `train.py` compara bajo la misma semilla (`118`) y las mismas
  particiones cuatro configuraciones:
  1. `dummy_majority`: baseline trivial que predice la clase mayoritaria en `train` (`pedido`);
  2. `tfidf_logreg`: `FeatureUnion` de TF-IDF de palabras `(1, 2)` y n-gramas de caracteres `char_wb (3, 5)` +
     `LogisticRegression`;
  3. `tfidf_linearsvc`: misma unión TF-IDF + `LinearSVC` envuelto en `CalibratedClassifierCV(method="sigmoid")`;
  4. `embedding_logreg`: vectores del modelo de embeddings de retrieval (LAB-21, leídos desde una caché local
     determinista del dataset o desde el gateway de LAB-84) + `Normalizer("l2")` + `LogisticRegression`.
- **Búsqueda de hiperparámetros sin fuga y aislamiento de `test`** (US1, US2): `GridSearchCV` optimiza `f1_macro` sobre
  `train` con `StratifiedGroupKFold(n_splits=5)` agrupando por `group_id` para que ninguna paráfrasis cruce pliegues;
  la comparación y calibración se deciden con `val`, y una guarda explícita prohíbe que cualquier ejemplo con
  `split == "test"` entre en `train.py`.
- **Evaluación única en `test` y calibración para umbral de confianza** (US3): `evaluate.py` carga el artefacto
  `.joblib` y mide una sola vez sobre los 102 ejemplos de `test` el F1 macro (verificando el objetivo **≥ 0.85**),
  precisión/recall/F1 por clase, matriz de confusión 5×5, calibración de probabilidades (Brier score, log-loss, ECE y
  tabla por tramos), curva de cobertura frente a precisión/F1 por umbral de confianza (para LAB-121), desglose por
  idioma (`es`, `pt`) y subconjunto sin ascendencia del golden (`golden_id == null`).
- **Pruebas unitarias y determinismo en CI** (US4): suite `pytest` en `ml/tests/intent/` que verifica preprocesado,
  idempotencia, determinismo bit a bit con semilla fija, calibración, guardas anti-fuga y ejecución completa en CPU sin
  red ni servicios externos.

Decisiones detalladas en [research.md](research.md); modelo de datos en [data-model.md](data-model.md); contratos de CLI
y artefactos en [contracts/cli-and-artifacts.md](contracts/cli-and-artifacts.md); guía de verificación en
[quickstart.md](quickstart.md).

## Technical Context

**Language/Version**: Python 3.12 o superior, gestionado con `uv` dentro del proyecto existente `ml/` (`shoplab-ml`)

**Primary Dependencies**: `scikit-learn>=1.5` (y `joblib`, incluido con scikit-learn), añadido a `ml/pyproject.toml`
junto a `numpy>=2.0` y `pandas>=2.2` ya presentes; reutiliza `intent_dataset` (`ml/src/intent_dataset`) para leer y
validar `intents.jsonl`; biblioteca estándar (`argparse`, `hashlib`, `json`, `time`, `urllib.request`) para CLI,
metadatos y cliente HTTP opcional de embeddings

**Storage**: archivos locales bajo `ml/`:
- Entrada: `ml/data/intents/intents.jsonl` (LAB-117, solo lectura) y `ml/data/intents/embeddings_cache.npz` (caché local
  comprimida de vectores del dataset para la variante `embedding_logreg`).
- Salida: `ml/artifacts/intent/intent_classifier.joblib` (`Pipeline` serializado),
  `ml/artifacts/intent/train_metadata.json` (comparativa de variantes, hiperparámetros, top términos por clase) y
  `ml/artifacts/intent/eval_report.json` (métricas en `test`, matriz de confusión y calibración)

**Testing**: `pytest` + `pytest-cov` en `ml/tests/intent/`, 100 % determinista en CPU sin red, sin PostgreSQL y sin
Ollama/LiteLLM; gate de cobertura `--cov=olist_eda --cov=intent_dataset --cov=intent --cov-fail-under=80`

**Target Platform**: CPU local (Windows / Linux / macOS) y runner Ubuntu de GitHub Actions (`python (ml)`)

**Project Type**: paquete Python + CLI de entrenamiento y evaluación (`ml/src/intent/` + wrappers en `ml/intent/`)

**Performance Goals**: F1 macro en `test` **≥ 0.85**; entrenamiento completo de las 4 variantes con CV en CPU < 30 s;
inferencia del artefacto sobre los 102 ejemplos de `test` < 50 ms en total en CPU

**Constraints**: la partición `test` solo se lee en `evaluate.py`; ningún `group_id` se divide entre pliegues de CV;
cero llamadas de red en pruebas y en el flujo por defecto en CI; identificadores de código en inglés; textos de
documentación y mensajes de CLI en español; longitud máxima de línea 121 caracteres (`ruff`)

**Scale/Scope**: 685 ejemplos etiquetados (481 `train`, 102 `val`, 102 `test`), 5 clases, 2 idiomas, 4 variantes
comparadas, ~10 módulos en `ml/src/intent/`, 2 wrappers en `ml/intent/` y ~7 módulos de pruebas en `ml/tests/intent/`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | No aplica: el trabajo vive íntegramente en `ml/` (F8) y no toca ningún módulo de `backend/` ni la base de datos. |
| II. Arquitectura hexagonal y pureza | Cumple la separación de responsabilidades adaptada a un paquete de ML: carga y validación de datos (`data.py`), transformadores puros del pipeline (`preprocess.py`, `features.py`, `embeddings.py`), búsqueda y calibración (`models.py`), métricas puras (`metrics.py`) y adaptadores de entrada/salida (`artifacts.py`, `train.py`, `evaluate.py`). El proveedor HTTP de embeddings es inyectable y opcional. |
| III. Identificadores en inglés y errores tipados | Cumple: todos los nombres de módulos, clases, funciones, variables y campos JSON están en inglés (las 5 etiquetas `politica`, `producto`, `pedido`, `escalado`, `charla` son datos de dominio fijados por LAB-36/LAB-117). Errores tipados en `intent.errors` (`IntentClassifierError`, `EmptyTextError`, `DataLeakageError`, `EmbeddingUnavailableError`, `EvaluationTargetError`). |
| IV. Test-First con gate de cobertura | Cumple: pruebas unitarias y de integración ligera sin red ni contenedores en `ml/tests/intent/`, deterministas con semilla fija y cobertura ≥ 80 % verificada por `pytest-cov` en CI. |
| V. RAG guiado por evaluación | Cumple: no modifica prompts, chunking, embeddings de producción ni retrieval. Prepara la tabla de umbrales de confianza y el desglose con/sin casos del golden para que LAB-121 evalúe el enrutado sin contaminación. |
| VI. Observabilidad y manejo seguro de IA | Cumple: consume únicamente datos ya enmascarados y validados por LAB-117, preserva las marcas `[ID]`/`[EMAIL]`/etc., no almacena secretos en código (las variables opcionales del gateway se leen del entorno) y registra el hash SHA-256 del dataset y del artefacto en los informes JSON. |
| VII. Simplicidad y decisiones documentadas | Cumple: añade únicamente `scikit-learn` a `ml/pyproject.toml` (previsto desde LAB-117), sin MLflow todavía (LAB-119), sin FastAPI (LAB-120), sin DVC (LAB-122) y sin PyTorch (LAB-126). No requiere ADR nuevo (el ADR de registro y comparación de modelos se redacta en LAB-119 y LAB-126 según el backlog). |

**Gate inicial**: PASA sin violaciones.

**Re-evaluación post-diseño (Phase 1)**: PASA. No se modifica ningún archivo fuera de `ml/`,
`specs/LAB-118-intent-classifier-sklearn/`, `.gitignore` y `README.md`. El job `python (ml)` existente en
`.github/workflows/ci.yml` ejecutará automáticamente `ruff` y `pytest` sobre el nuevo paquete `intent`.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-118-intent-classifier-sklearn/
├── plan.md                              # Este archivo (/speckit-plan)
├── research.md                          # Phase 0: decisiones R1–R7
├── data-model.md                        # Phase 1: transformadores, entidades y esquemas de métricas
├── quickstart.md                        # Phase 1: recorrido de validación en CPU
├── contracts/
│   └── cli-and-artifacts.md             # Phase 1: CLI de train.py / evaluate.py y contrato del .joblib y JSON
├── checklists/
│   └── requirements.md                  # Checklist de calidad de la especificación
└── tasks.md                             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
ml/
├── README.md                            # + sección «Clasificador de intención con scikit-learn (LAB-118)»
├── pyproject.toml                       # + dependencia scikit-learn>=1.5, paquete src/intent y --cov=intent
├── uv.lock                              # lockfile actualizado con scikit-learn y scipy/joblib/threadpoolctl
├── data/
│   └── intents/
│       ├── intents.jsonl                # (existente, LAB-117) 685 ejemplos con train/val/test
│       └── embeddings_cache.npz         # caché local determinista de vectores del dataset para embedding_logreg
├── artifacts/
│   └── intent/
│       ├── train_metadata.json          # comparativa de las 4 variantes, hiperparámetros ganadores, top términos
│       └── eval_report.json             # informe oficial en test (F1 macro, por clase, confusión 5x5, calibración)
├── intent/                              # puntos de entrada directos exigidos por el criterio «ml/intent/{train,evaluate}.py»
│   ├── __init__.py
│   ├── train.py                         # delega en intent.train:main
│   └── evaluate.py                      # delega en intent.evaluate:main
├── src/
│   └── intent/
│       ├── __init__.py
│       ├── __main__.py                  # CLI unificada: python -m intent {train,evaluate,embed-cache}
│       ├── constants.py                 # CANONICAL_LABELS, MASK_TOKENS, DEFAULT_SEED, MIN_TEST_F1_MACRO, etc.
│       ├── errors.py                    # IntentClassifierError, EmptyTextError, DataLeakageError, etc.
│       ├── data.py                      # carga de intents.jsonl, verificación anti-fuga y separación train/val/test
│       ├── preprocess.py                # TextPreprocessor(BaseEstimator, TransformerMixin) + preprocess_text
│       ├── features.py                  # build_tfidf_union (palabras 1..2 + caracteres char_wb 3..5)
│       ├── embeddings.py                # EmbeddingTransformer, caché .npz y cliente HTTP opcional al gateway
│       ├── models.py                    # constructores de las 4 variantes, GridSearchCV con StratifiedGroupKFold, selección y calibración
│       ├── metrics.py                   # F1 macro, por clase, matriz de confusión 5x5, Brier, log-loss, ECE, umbrales y subgrupos
│       ├── artifacts.py                 # guardado/carga con joblib, extracción de top términos por clase y serialización JSON/Markdown
│       ├── train.py                     # CLI y orquestación de entrenamiento (python -m intent.train)
│       └── evaluate.py                  # CLI y orquestación de evaluación en test (python -m intent.evaluate)
└── tests/
    └── intent/
        ├── conftest.py                  # fixtures sintéticos bilingües con grupos y caché de embeddings de prueba
        ├── test_preprocess.py           # normalización, acentos/ñ/ç, marcas [ID]/[EMAIL]/[PHONE], idempotencia, vacíos
        ├── test_data.py                 # carga de particiones y guardas DataLeakageError ante split="test" o grupos cruzados
        ├── test_embeddings.py           # EmbeddingTransformer con caché .npz, modo offline y cliente HTTP falso
        ├── test_models_determinism.py   # determinismo bit a bit con la misma semilla e ida y vuelta con joblib
        ├── test_metrics_calibration.py  # Brier, ECE, matriz 5x5, probabilidades que suman 1.0 y tabla de umbrales
        └── test_train_evaluate_cli.py   # flujo completo train.py + evaluate.py y verificación F1 macro ≥ 0.85 sobre el dataset real

.gitignore                               # + ml/artifacts/intent/*.joblib (el binario se genera con train.py; los JSON de informe sí se versionan)
README.md                                # fila de `ml/`: añade referencia al clasificador de intención (LAB-118)
```

**Structure Decision**: se mantiene el único proyecto `uv` en `ml/` añadiendo `src/intent` a
`[tool.hatch.build.targets.wheel].packages` y `--cov=intent` a `pytest`, más el directorio `ml/intent/` con `train.py` y
`evaluate.py` como wrappers ejecutables para que tanto `uv run python -m intent.train` como
`uv run python intent/train.py` funcionen indistintamente.

## Complexity Tracking

> Sin violaciones del Constitution Check.

| Decisión técnica | Por qué es necesaria | Alternativa más simple descartada |
|---|---|---|
| Caché local `embeddings_cache.npz` para la variante `embedding_logreg` | Permite comparar la variante de embeddings de retrieval (LAB-21) en CPU y en CI sin requerir contenedores de Ollama/LiteLLM levantados (R4) | Llamar siempre por HTTP en vivo a `localhost:4000`: rompería el determinismo y fallaría en CI donde no corre Ollama |
| `StratifiedGroupKFold` en lugar de `StratifiedKFold` sobre `train` | `train` contiene paráfrasis del mismo `group_id` (LAB-117); sin agrupar, las paráfrasis se filtran entre pliegues de CV e inflan la selección de hiperparámetros (R5) | `StratifiedKFold` estándar: viola la garantía de no separar grupos de origen |
