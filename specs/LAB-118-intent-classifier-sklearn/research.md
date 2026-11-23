# Research: Clasificador de intención con scikit-learn

Decisiones técnicas para implementar `LAB-118` cumpliendo la constitución del proyecto y los contratos de `LAB-117`. No
queda ningún `NEEDS CLARIFICATION`.

## R1. Ubicación y organización del paquete `ml/intent`

- **Decisión**: añadir el paquete `intent` en `ml/src/intent/` dentro del proyecto `uv` existente (`ml/pyproject.toml`,
  `shoplab-ml`), con los módulos ejecutables `intent.train` (`ml/src/intent/train.py`) e `intent.evaluate`
  (`ml/src/intent/evaluate.py`), un punto de entrada unificado `ml/src/intent/__main__.py` y wrappers ligeros en
  `ml/intent/train.py` y `ml/intent/evaluate.py` que delegan en `intent.train:main` e `intent.evaluate:main`.
- **Razón**:
  - `ml/pyproject.toml` ya usa `hatchling` con layout `src/` (`src/olist_eda`, `src/intent_dataset`), `pythonpath = ["src"]`
    en `pytest` y `ruff` sobre `src` y `tests`. Poner la lógica en `ml/src/intent/` mantiene el 100 % de consistencia con
    las dos historias anteriores de F8 y activa automáticamente el gate de cobertura (`--cov=intent`) en el job
    `python (ml)` de GitHub Actions sin tocar `.github/workflows/ci.yml`.
  - Incluir además los puntos de entrada en `ml/intent/train.py` y `ml/intent/evaluate.py` satisface literalmente el
    criterio de Jira («Paquete `ml/intent` con `train.py` y `evaluate.py`») tanto si se invoca como
    `uv run python -m intent.train` / `uv run python -m intent.evaluate` como si se ejecuta `uv run python intent/train.py`
    desde `ml/`.
- **Alternativas descartadas**:
  - Crear otro proyecto `uv` dentro de `ml/intent/pyproject.toml`: duplicaría lockfiles y obligaría a modificar el
    workflow de CI.
  - Poner toda la lógica fuera de `src/`: rompería la convención `src/` de `shoplab-ml`.

## R2. Preprocesado de texto y preservación de las marcas de enmascarado de LAB-117

- **Decisión**: implementar un transformador de scikit-learn `TextPreprocessor(BaseEstimator, TransformerMixin)` en
  `ml/src/intent/preprocess.py` como primer paso del `Pipeline`, que:
  1. Valida que cada entrada sea texto no vacío tras eliminar espacios y caracteres de control.
  2. Normaliza Unicode a `NFC`.
  3. Sustituye las marcas de enmascarado de LAB-117 (`[URL]`, `[EMAIL]`, `[ID]`, `[CNPJ]`, `[CPF]`, `[CEP]`, `[PHONE]`,
     `[NAME]`) por tokens alfanuméricos canónicos (`__mask_url__`, `__mask_email__`, `__mask_id__`, `__mask_cnpj__`,
     `__mask_cpf__`, `__mask_cep__`, `__mask_phone__`, `__mask_name__`).
  4. Pasa el texto a minúsculas conservando los caracteres propios del español y del portugués (vocales con tilde,
     diéresis, circunflejo o virgulilla, `ñ`, `ç`) y colapsa espacios múltiples en uno solo.
- **Razón**:
  - El tokenizador por defecto de `TfidfVectorizer` (`r"(?u)\b\w\w+\b"`) elimina los corchetes `[` y `]`, convirtiendo
    `[ID]` en la palabra `id` y `[NAME]` en `name`. En el dominio de ShopLab, la presencia de `[ID]` (hash de 32 hex de
    un pedido enmascarado en LAB-117) o `[CEP]` es un predictor fuerte de la clase `pedido`. Al convertirlos en
    `__mask_id__` / `__mask_cep__`, tanto el vectorizador de palabras como el de n-gramas de caracteres los conservan
    como características propias y aparecen de forma transparente en los coeficientes de explicabilidad.
  - Al no eliminar tildes ni `ñ`/`ç` y combinar palabras con n-gramas de caracteres, el clasificador aprovecha tanto la
    ortografía correcta como la similitud de subpalabras cuando el cliente escribe sin tildes o con errores tipográficos.
- **Alternativas descartadas**:
  - Quitar todos los acentos en el preprocesador: pierde distinción entre formas verbales/sustantivos y no hace falta
    cuando ya se incluyen n-gramas de caracteres `(3, 5)` con `strip_accents=None` o rama paralela.
  - Usar una función `lambda` en `FunctionTransformer`: impide serializar el `Pipeline` con `joblib`/`pickle` de forma
    portable para MLflow (LAB-119) y FastAPI (LAB-120).

## R3. Diseño de las variantes comparadas y del baseline trivial

- **Decisión**: comparar cuatro configuraciones bajo el mismo protocolo y semilla (`118` por defecto):
  1. **`dummy_majority` (Baseline trivial)**: `Pipeline([("preprocess", TextPreprocessor()), ("clf", DummyClassifier(strategy="prior", random_state=seed))])`. En `predict` asigna siempre la clase mayoritaria en `train` (`pedido`, ~28,7 % de `train`), fijando el suelo de F1 macro (~0,09).
  2. **`tfidf_logreg` (TF-IDF palabras + caracteres + Regresión Logística)**:
     - Extracción con `FeatureUnion`:
       - `word_tfidf`: `TfidfVectorizer(analyzer="word", ngram_range=(1, 2), sublinear_tf=True, token_pattern=r"(?u)\b\w+\b")`
       - `char_tfidf`: `TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), sublinear_tf=True)`
     - Clasificador: `LogisticRegression(solver="lbfgs", max_iter=2000, random_state=seed)` (envuelto opcionalmente en calibración sigmoide según validación; ver R6).
  3. **`tfidf_linearsvc` (TF-IDF palabras + caracteres + LinearSVC calibrado)**:
     - Misma `FeatureUnion` de palabras y n-gramas de caracteres.
     - Clasificador: `LinearSVC(dual="auto", max_iter=5000, random_state=seed)` envuelto en `CalibratedClassifierCV(method="sigmoid", cv=...)` para disponer de `predict_proba` calibrado dentro del mismo `Pipeline`.
  4. **`embedding_logreg` (Embeddings del modelo de retrieval LAB-21 + Regresión Logística)**:
     - `Pipeline([("preprocess", TextPreprocessor()), ("embed", EmbeddingTransformer(...)), ("norm", Normalizer(norm="l2")), ("clf", LogisticRegression(solver="lbfgs", max_iter=2000, random_state=seed))])`.
- **Razón**:
  - Cubre literalmente los criterios de aceptación de LAB-118: TF-IDF de palabras y n-gramas de caracteres con
    `LogisticRegression` y `LinearSVC`, embeddings del modelo de retrieval (LAB-21) con `LogisticRegression`, y el
    baseline de clase mayoritaria.
  - Todas las variantes implementan la misma interfaz de `Pipeline` de scikit-learn (`fit(texts, labels)`,
    `predict(texts)`, `predict_proba(texts)`, `classes_`).
- **Alternativas descartadas**:
  - Separar TF-IDF de palabras y TF-IDF de caracteres en pipelines desconectados sin `FeatureUnion`: la historia pide
    «TF-IDF (palabras y n-gramas de caracteres) + `LogisticRegression`/`LinearSVC`», y la unión de ambos en un solo
    vector disperso es el estándar clásico en clasificación de texto corto multilingüe.

## R4. Embeddings del modelo de retrieval (LAB-21) y reproducibilidad en CPU / CI

- **Decisión**: implementar `EmbeddingTransformer(BaseEstimator, TransformerMixin)` con soporte de **caché local
  determinista** (`ml/data/intents/embeddings_cache.npz`) y cliente HTTP opcional compatible con el endpoint
  `/v1/embeddings` del gateway LiteLLM (LAB-84) / Ollama:
  - El archivo de caché almacena los vectores (`float32`, normalizados L2) indexados por `sha256(texto_normalizado)`
    junto con el identificador del modelo de embeddings y su dimensión.
  - Se incluye un subcomando/utilidad en el paquete (`embed-cache`) para poblar o actualizar la caché llamando al
    modelo de embeddings de LAB-21 (`bge-m3` / `nomic-embed-text` vía `http://localhost:4000/v1/embeddings`) o para
    generar vectores precalculados deterministas del dataset versionados junto a `ml/data/intents/`.
  - Durante `train.py` y `evaluate.py`, `EmbeddingTransformer` resuelve primero cada texto contra la caché local; solo si
    un texto no está en la caché y se habilita el cliente HTTP (`--allow-remote-embeddings`), invoca al endpoint. En CI y
    por defecto en local (`offline_only=True`), nunca abre conexiones de red.
- **Razón**:
  - La constitución (Principio IV y sección CI) prohíbe llamadas a modelos o red en CI y el job `python (ml)` no levanta
    Ollama ni LiteLLM.
  - Con los vectores de los 685 ejemplos de `intents.jsonl` precalculados en `ml/data/intents/embeddings_cache.npz`
    (~685 × 384 o 1024 `float16`/`float32` ≈ 0,5–1,3 MB comprimido), la variante `embedding_logreg` entrena y hace
    búsqueda de hiperparámetros con validación cruzada en CPU en menos de 2 segundos, sin red y de forma 100 %
    reproducible.
- **Alternativas descartadas**:
  - Exigir Ollama levantado para correr `train.py`: impediría ejecutar y probar la comparativa en CI y haría el
    entrenamiento lento en máquinas sin GPU.
  - Añadir `sentence-transformers` y `torch` en LAB-118: PyTorch pertenece a LAB-126 (F8-11) y añade > 800 MB de
    dependencias que no corresponden al clasificador clásico con scikit-learn.

## R5. Protocolo de validación cruzada sin fuga de grupos y aislamiento de `test`

- **Decisión**:
  1. **Aislamiento de `test`**: `train.py` carga `intents.jsonl`, verifica que no haya fuga de `group_id` ni de textos
     normalizados entre particiones, y descarta inmediatamente toda fila con `split == "test"` antes de crear cualquier
     objeto de búsqueda o transformador. Una guarda explícita (`assert_no_test_split`) lanza `DataLeakageError` si
     recibe algún ejemplo con `split == "test"`.
  2. **Búsqueda de hiperparámetros en `train`**: sobre los 481 ejemplos de `train`, se utiliza
     `StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=seed)` pasando `groups=[ex.group_id for ex in train]`
     dentro de `GridSearchCV(..., scoring="f1_macro", refit=True, n_jobs=1)` (`n_jobs=1` para determinismo estricto de
     orden de suma en punto flotante y menos consumo en CI; tarda ~3–5 segundos en total).
  3. **Espacio de hiperparámetros**:
     - `tfidf_logreg`: `C ∈ {0.5, 1.0, 2.0, 5.0, 10.0}`, `class_weight ∈ {None, "balanced"}`,
       `features__word__min_df ∈ {1, 2}`, `features__char__min_df ∈ {2, 3}`.
     - `tfidf_linearsvc`: `C ∈ {0.25, 0.5, 1.0, 2.0, 5.0}`, `class_weight ∈ {None, "balanced"}`,
       `features__word__min_df ∈ {1, 2}`, `features__char__min_df ∈ {2, 3}`.
     - `embedding_logreg`: `C ∈ {0.1, 0.5, 1.0, 2.0, 5.0, 10.0}`, `class_weight ∈ {None, "balanced"}`.
  4. **Comparación y calibración en `val`**: cada variante con sus mejores hiperparámetros de CV en `train` se evalúa
     sobre los 102 ejemplos de `val` (F1 macro, precisión/recall macro, log-loss, Brier score y ECE).
  5. **Regla de selección de la variante ganadora**:
     - Se elige la variante con mayor **F1 macro en `val`** (siempre que supere el suelo en CV de `train`).
     - Si la diferencia en F1 macro de `val` entre la mejor variante TF-IDF y `embedding_logreg` es `≤ 0.01`, se prefiere
       la variante TF-IDF porque es 100 % autocontenida en inferencia (no requiere vectorizar con el servicio de
       embeddings antes de clasificar en LAB-120) y ofrece explicabilidad directa por coeficientes de términos (LAB-125).
     - En empate entre `tfidf_logreg` y `tfidf_linearsvc`, desempata el mayor F1 macro de CV en `train` y luego el menor
       Brier score en `val`.
  6. **Ajuste final del artefacto**: una vez elegida la variante y sus hiperparámetros sin haber mirado `test`, se ajusta
     el pipeline final con calibración de probabilidades usando únicamente `train` y `val` (nunca `test`) y se serializa.
- **Razón**:
  - Respeta estrictamente el criterio «Búsqueda de hiperparámetros con validación cruzada estratificada sobre train; la
    partición test solo se usa una vez al final» y la invariante de grupos (`group_id`) de LAB-117.
- **Alternativas descartadas**:
  - `StratifiedKFold` sin `groups`: filtraría paráfrasis del mismo `group_id` entre los pliegues de entrenamiento y
    validación dentro de `train`, favoreciendo hiperparámetros con `C` alto que memorizan paráfrasis.

## R6. Calibración de probabilidades y análisis de umbral de confianza

- **Decisión**:
  - Para `LinearSVC`, envolver el estimador en `CalibratedClassifierCV(estimator=LinearSVC(...), method="sigmoid", cv=5)`
    (con pliegues estratificados deterministas) dentro del `Pipeline`, de manera que `pipeline.predict_proba(texts)` esté
    siempre disponible y devuelva probabilidades calibradas por Platt scaling.
  - Para `LogisticRegression`, evaluar en `val` tanto las probabilidades softmax directas como `CalibratedClassifierCV`
    (`method="sigmoid"`), conservando la configuración con mejor calibración (menor Brier score / log-loss) sin perder F1
    macro.
  - Para garantizar consistencia total entre `predict(X)` y `predict_proba(X)` cuando se usa un umbral de confianza, el
    clasificador final predice siempre `classes_[argmax(predict_proba(X))]`.
  - En `evaluate.py`, calcular sobre `test`:
    - **Brier score multiclase**: media sobre los ejemplos de $\sum_{k=1}^5 (p_{ik} - y_{ik})^2$.
    - **Log-loss**: entropía cruzada multiclase (`sklearn.metrics.log_loss`).
    - **ECE (Expected Calibration Error)**: agrupando las predicciones en tramos de confianza máxima
      ($\hat{p}_i = \max_k p_{ik}$) y midiendo la diferencia ponderada entre confianza media y exactitud empírica por
      tramo, junto con la tabla completa de tramos.
    - **Curva de umbral de confianza**: para umbrales $\tau \in \{0.50, 0.60, 0.70, 0.75, 0.80, 0.85, 0.90\}$, calcular
      `coverage` (fracción de mensajes con $\hat{p}_i \ge \tau$), `retained_count`, `fallback_count`, `accuracy_retained`
      y `f1_macro_retained`.
- **Razón**:
  - LAB-121 necesita saber qué umbral configurar (`confianza ≥ umbral aplica filtro; por debajo usa fallback`) y LAB-124
    enviará los casos de baja confianza a la cola de revisión. Sin la tabla de cobertura frente a F1 por umbral, la
    calibración sería solo un número decorativo.

## R7. Serialización del artefacto y preparación para MLflow (LAB-119) y FastAPI (LAB-120)

- **Decisión**:
  - Serializar el `Pipeline` ganador con `joblib.dump(pipeline, path, compress=3)` en un único archivo
    (`ml/artifacts/intent/intent_classifier.joblib` por defecto).
  - Fijar `classes_` del estimador final o verificar que contiene exactamente las cinco etiquetas de `LABELS`
    (`politica`, `producto`, `pedido`, `escalado`, `charla`).
  - Generar junto al artefacto:
    - `ml/artifacts/intent/train_metadata.json`: semilla, SHA-256 de `intents.jsonl`, conteos por partición, resultados
      de todas las variantes comparadas, hiperparámetros ganadores, top términos por clase (explicabilidad) y versiones de
      librerías.
    - `ml/artifacts/intent/eval_report.json` (generado por `evaluate.py`): todas las métricas de `test` (F1 macro, por
      clase, matriz de confusión 5×5, calibración, umbrales, desglose `es`/`pt`, desglose `non_golden` y muestra de
      errores).
- **Razón**:
  - `joblib` es el formato nativo que `mlflow.sklearn` (LAB-119) y `scikit-learn` recomiendan para pipelines con arrays
    de NumPy y matrices dispersas de SciPy.
  - Guardar `train_metadata.json` y `eval_report.json` con esquema fijo permite que LAB-119 registre todos los parámetros
    y métricas en MLflow sin reescribir la lógica de entrenamiento ni de evaluación.
