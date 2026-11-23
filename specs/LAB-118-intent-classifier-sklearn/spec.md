# Feature Specification: Clasificador de intención con scikit-learn

**Feature Branch**: `feature/LAB-118-intent-classifier-sklearn`

**Created**: 2026-11-23

**Status**: Draft

**Input**: User description: "LAB-118 (F8-03) Clasificador de intención con scikit-learn. Como desarrollador, quiero
entrenar un clasificador de intención clásico, para enrutar mensajes de forma rápida, barata y explicable sin llamar al
LLM. Criterios: paquete `ml/intent` con `train.py` y `evaluate.py` (no solo notebook); `Pipeline` de scikit-learn
serializado como un único artefacto; dos variantes comparadas: TF-IDF (palabras y n-gramas de caracteres) +
`LogisticRegression`/`LinearSVC`, y embeddings del modelo de retrieval (LAB-21) + `LogisticRegression`; búsqueda de
hiperparámetros con validación cruzada estratificada sobre train y la partición test solo se usa una vez al final;
métricas en test: F1 macro, precisión y recall por clase, matriz de confusión y calibración (probabilidades calibradas
para usar un umbral de confianza); baseline trivial (clase mayoritaria) y objetivo mínimo F1 macro ≥ 0.85; tests
unitarios del preprocesado y de que la predicción es determinista con la misma semilla. Funciona en local en CPU.
Depende de LAB-117." Tercera historia de la épica F8 · ML y MLOps (LAB-115).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Entrenar un clasificador clásico reproducible desde código y empaquetarlo en un único artefacto (Priority: P1)

Como desarrollador, quiero entrenar un clasificador de intención sobre el dataset etiquetado de LAB-117 mediante un
módulo ejecutable en `ml/` (no un notebook) y obtener un único artefacto serializado que contenga todo el flujo —desde
el preprocesado del mensaje hasta la predicción de clase y sus probabilidades calibradas—, para que las historias
siguientes (registro en MLflow en LAB-119 y servicio de inferencia en LAB-120) puedan cargarlo y clasificar mensajes en
español y portugués en un solo paso.

El entrenamiento consume las particiones fijas (`train` y `val`) de `ml/data/intents/intents.jsonl` sin modificarlas ni
repartirlas de nuevo, realiza búsqueda de hiperparámetros con validación cruzada estratificada sobre `train` respetando
los grupos de origen (`group_id`) para no filtrar paráfrasis entre pliegues, calibra las probabilidades sin tocar `test`
y guarda el pipeline ganador como un único archivo junto con sus metadatos de entrenamiento (semilla, hash del dataset,
hiperparámetros elegidos y resultados de validación).

**Why this priority**: Es el núcleo de la historia y el entregable que consumen LAB-119, LAB-120, LAB-122, LAB-125 y
LAB-126. Sin un pipeline entrenable desde línea de comandos y serializado en un único artefacto no hay modelo que
evaluar, registrar ni servir.

**Independent Test**: Ejecutar el comando de entrenamiento en CPU sobre el dataset de LAB-117 con una semilla fija,
cargar el artefacto generado en un proceso limpio sin acceso a los datos de entrenamiento y verificar que devuelve una
de las cinco etiquetas fijas (`politica`, `producto`, `pedido`, `escalado`, `charla`) y un vector de cinco
probabilidades que suman 1,0 para cualquier mensaje en español o portugués.

**Acceptance Scenarios**:

1. **Given** el dataset `ml/data/intents/intents.jsonl` validado por LAB-117, **When** se ejecuta el entrenamiento con
   una semilla fija, **Then** produce un único artefacto de pipeline serializado que acepta texto crudo de cliente y
   devuelve la intención predicha y las probabilidades de las cinco clases sin requerir pasos manuales previos de
   limpieza o vectorización fuera del artefacto.
2. **Given** la partición `train` con grupos de origen (`group_id`), **When** se ejecuta la búsqueda de hiperparámetros
   con validación cruzada estratificada, **Then** ningún `group_id` queda repartido entre el pliegue de ajuste y el de
   validación, y ningún ejemplo de `test` se lee ni se utiliza durante el ajuste, la selección de hiperparámetros ni la
   calibración.
3. **Given** el mismo dataset, los mismos argumentos y la misma semilla, **When** se ejecuta el entrenamiento dos veces
   en CPU, **Then** selecciona los mismos hiperparámetros y el artefacto resultante produce exactamente las mismas
   etiquetas y las mismas probabilidades (dentro de la tolerancia numérica de punto flotante) sobre cualquier lote de
   mensajes de prueba.
4. **Given** un mensaje con marcas de enmascarado de LAB-117 (`[EMAIL]`, `[PHONE]`, `[CPF]`, `[CNPJ]`, `[CEP]`, `[ID]`,
   `[URL]`, `[NAME]`), signos de puntuación repetidos o mezcla de mayúsculas y acentos, **When** pasa por el
   preprocesado del pipeline, **Then** las marcas se conservan como señales semánticas diferenciadas (p. ej. `[ID]` como
   indicio de consulta de pedido) en lugar de destruirse al limpiar puntuación.

---

### User Story 2 - Comparar variantes de representación (léxica frente a embeddings de retrieval) contra un baseline trivial (Priority: P1)

Como desarrollador, quiero comparar de forma sistemática dos familias de representación de mensajes —una léxica basada
en frecuencia de términos (palabras y n-gramas de caracteres) con clasificadores lineales, y otra densa basada en los
embeddings del modelo de retrieval (LAB-21) con un clasificador lineal—, además de un baseline trivial de clase
mayoritaria, para elegir con evidencia empírica qué enfoque supera el umbral de F1 macro ≥ 0,85 con la menor complejidad
operativa.

La comparación enfrenta al menos:
- **Baseline trivial**: predice siempre la clase mayoritaria en `train` (`pedido`), estableciendo el suelo mínimo.
- **Variante léxica (TF-IDF)**: combinación de n-gramas de palabras y n-gramas de caracteres (que capturan raíces,
  prefijos, sufijos y errores ortográficos en español y portugués) evaluada tanto con regresión logística como con
  máquina de vectores de soporte de margen lineal (`LinearSVC` con calibración de probabilidades).
- **Variante densa (embeddings de retrieval)**: vectores del modelo de embeddings de LAB-21 (obtenidos a través del
  gateway local o de una caché local determinista de vectores del dataset) con regresión logística regularizada.

Todas las variantes se ajustan y comparan con el mismo protocolo de validación sobre `train`/`val` antes de tocar
`test`, registrando sus métricas de validación, tiempo de inferencia en CPU y requisitos de ejecución.

**Why this priority**: Forma parte directa de los criterios de aceptación y responde a una decisión arquitectónica clave
de F8: demostrar si un modelo léxico autocontenido (microsegundos en CPU, cero llamadas de red en inferencia) iguala o
supera a un clasificador sobre embeddings del modelo de retrieval (que exigiría vectorizar cada mensaje con el modelo de
embeddings antes de clasificar).

**Independent Test**: Ejecutar la comparativa de variantes sobre `train`/`val` y comprobar que genera una tabla
estructurada con las métricas del baseline trivial, de las variantes TF-IDF (`LogisticRegression` y `LinearSVC`) y de la
variante con embeddings de retrieval (`LogisticRegression`), e identifica de forma determinista el modelo ganador según
el criterio de selección declarado.

**Acceptance Scenarios**:

1. **Given** las particiones `train` y `val`, **When** se ejecuta el entrenamiento comparativo, **Then** se entrenan y
   evalúan el baseline trivial (clase mayoritaria), la variante TF-IDF con `LogisticRegression`, la variante TF-IDF con
   `LinearSVC` calibrado y la variante de embeddings de retrieval con `LogisticRegression`, bajo las mismas particiones y
   semilla.
2. **Given** la variante de embeddings de retrieval, **When** se ejecuta con una caché local de embeddings del dataset o
   contra el endpoint de embeddings compatible con LAB-21/LAB-84, **Then** entrena y evalúa el clasificador lineal sobre
   esos vectores, y si se usa la caché versionada/local no requiere red ni GPU para reproducir el entrenamiento en CPU.
3. **Given** los resultados de validación de todas las variantes, **When** se selecciona el modelo final para empaquetar,
   **Then** queda registrado el cuadro comparativo completo (F1 macro en validación cruzada y en `val`, precisión/recall,
   error de calibración y latencia media por mensaje en CPU) y la regla objetiva por la que se eligió el ganador.
4. **Given** que dos variantes empaten o queden dentro de un margen declarado en F1 macro de validación, **When** se
   aplica la regla de selección, **Then** se prefiere la variante autocontenida que no depende de invocar al servicio de
   embeddings en tiempo de inferencia, o queda parametrizable por argumento explícito.

---

### User Story 3 - Evaluar una única vez sobre la partición test con métricas por clase, matriz de confusión y calibración (Priority: P1)

Como desarrollador, quiero un módulo de evaluación separado (`evaluate.py`) que cargue el artefacto entrenado y lo mida
sobre la partición `test` reservada, reportando F1 macro, precisión y recall por clase, matriz de confusión, métricas de
calibración de probabilidades y un análisis por umbral de confianza, para verificar que el modelo alcanza F1 macro ≥ 0,85
y proporcionar a LAB-121 una base cuantitativa con la que decidir a partir de qué probabilidad enrutar por intención.

La evaluación comprueba además el rendimiento desglosado por idioma (`es` y `pt`) y reporta por separado la métrica con y
sin los casos de `test` que proceden del dataset golden (`golden_id != null`), tal como exige el contrato de LAB-117.

**Why this priority**: Sin una evaluación aislada en `test` con matriz de confusión y calibración de probabilidades no se
puede verificar el objetivo F1 macro ≥ 0,85 ni usar la confianza del modelo como umbral de decisión en el enrutado del
chat (LAB-121) o en la detección de casos dudosos (LAB-124).

**Independent Test**: Ejecutar `evaluate.py` pasándole el artefacto entrenado y `ml/data/intents/intents.jsonl`, y
verificar que escribe un informe estructurado (JSON y resumen legible) con todas las métricas exigidas sobre los 102
ejemplos de `test` y devuelve código de salida distinto de cero si el F1 macro en `test` cae por debajo de 0,85.

**Acceptance Scenarios**:

1. **Given** un artefacto entrenado y la partición `test`, **When** se ejecuta `evaluate.py`, **Then** calcula y reporta:
   F1 macro global (comparado contra el baseline trivial y contra el umbral mínimo de 0,85), precisión, recall, F1 y
   soporte de cada una de las cinco clases, y la matriz de confusión 5×5 con las etiquetas en orden canónico.
2. **Given** las probabilidades predichas sobre `test`, **When** se evalúa la calibración, **Then** el informe incluye
   métricas cuantitativas de calibración (Brier score multiclase, pérdida logarítmica y error de calibración esperado ECE
   por tramos de confianza) junto con una tabla de cobertura frente a precisión/F1 para una rejilla de umbrales de
   confianza (p. ej. de 0,50 a 0,90).
3. **Given** el objetivo de calidad de la historia, **When** el modelo seleccionado se evalúa en `test`, **Then** alcanza
   un F1 macro ≥ 0,85 y supera ampliamente al baseline de clase mayoritaria; si se invoca con verificación de umbral y
   el F1 macro es < 0,85, el comando termina con código de error e indica la brecha.
4. **Given** que el dataset de LAB-117 es bilingüe (`es`, `pt`) e incluye casos derivados del golden (`golden_id`),
   **When** se genera el informe de evaluación, **Then** desglosa también el F1 macro por idioma y el F1 macro sobre el
   subconjunto de `test` sin ascendencia del golden (`golden_id == null`), además de listar una muestra de errores de
   clasificación (texto, etiqueta real, etiqueta predicha y confianza) para inspección sin exponer datos personales.

---

### User Story 4 - Garantizar por pruebas unitarias el preprocesado, el determinismo y la ausencia de dependencias externas en CI (Priority: P2)

Como desarrollador, quiero una batería de pruebas automáticas que verifique el preprocesado de texto, la reproducibilidad
exacta con semilla fija, la calibración de probabilidades, las guardas contra fuga de datos entre particiones y el
contrato de entrada/salida de `train.py` y `evaluate.py`, para que cualquier cambio futuro en `ml/` se valide en
segundos dentro de la integración continua sin necesitar GPU, base de datos ni servicios de modelos en ejecución.

**Why this priority**: La constitución (Principio IV) exige pruebas deterministas sin llamadas reales a modelos en CI y
cobertura ≥ 80 %. Aunque el modelo podría entrenarse manualmente sin ellas, las pruebas aseguran que el pipeline no se
degrade y preparan la automatización de LAB-122.

**Independent Test**: Ejecutar `pytest` en `ml/` en un entorno sin red ni contenedores activos y comprobar que todas las
pruebas pasan y la cobertura del paquete supera el umbral del proyecto.

**Acceptance Scenarios**:

1. **Given** el preprocesador de texto del paquete, **When** se prueba con casos unitarios en español y portugués
   (espacios múltiples, saltos de línea, signos de interrogación/exclamación de apertura y cierre, acentos, eñes,
   cedillas, marcas `[EMAIL]`/`[ID]`/`[PHONE]` y texto vacío o de solo espacios), **Then** normaliza el texto de forma
   idempotente y conserva las marcas de enmascarado y los caracteres del español y del portugués según su especificación.
2. **Given** una semilla fija y un conjunto de entrenamiento, **When** se entrenan dos instancias del pipeline y se
   comparan sus predicciones (`predict` y `predict_proba`), **Then** los resultados son bit a bit o numéricamente
   idénticos, y persisten idénticos tras serializar y deserializar el artefacto de disco.
3. **Given** un intento de pasar a la función de entrenamiento ejemplos marcados con `split="test"`, **When** se invoca
   el ajuste o la búsqueda de hiperparámetros, **Then** la guarda de aislamiento los excluye o rechaza explícitamente
   con un error claro para impedir cualquier fuga accidental.
4. **Given** el entorno de integración continua (sin Ollama, sin LiteLLM y sin PostgreSQL), **When** se ejecuta la suite
   de pruebas de `ml/`, **Then** todas las pruebas de `intent` (incluidas las de la variante de embeddings mediante
   vectores precalculados o dobles deterministas) terminan en verde y mantienen la cobertura exigida.

---

### Edge Cases

- **Mensajes vacíos, de solo espacios o de solo signos de puntuación**: el artefacto serializado no debe lanzar una
  excepción no controlada de vectorizador vacío al predecir; `predict` y `predict_proba` deben rechazar una entrada vacía
  con un error de validación tipado y claro (o manejarla de forma explícita y documentada antes de llegar al
  vectorizador), mientras que el cargador de datos falla si algún registro del dataset tuviera `text` vacío.
- **Palabras fuera de vocabulario (OOV), errores ortográficos o mezcla de español y portugués**: un mensaje compuesto
  exclusivamente por palabras no vistas en `train` o con faltas («reenbolso», «entregaram errado») no debe producir un
  vector nulo silencioso gracias a los n-gramas de caracteres y al término de intercepción del clasificador, devolviendo
  probabilidades válidas que suman 1,0 (con menor confianza).
- **Marcas de enmascarado de LAB-117 (`[ID]`, `[EMAIL]`, `[PHONE]`, `[CPF]`, `[CNPJ]`, `[CEP]`, `[URL]`, `[NAME]`)**: un
  tokenizador por expresión regular estándar que elimine corchetes las confundiría con las palabras comunes `id`,
  `email` o `name`. El preprocesado/tokenización debe preservar las marcas de enmascarado como tokens distinguibles.
- **Grupos de paráfrasis (`group_id`) durante la validación cruzada en `train`**: si `train` contiene un mensaje
  original y sus paráfrasis (mismo `group_id`), una validación cruzada estratificada simple que ignore `group_id`
  pondría paráfrasis casi idénticas en el pliegue de entrenamiento y en el de validación, inflando la métrica de CV y
  eligiendo hiperparámetros sobreajustados. La validación cruzada sobre `train` debe agrupar por `group_id` además de
  estratificar por clase.
- **Desbalance entre clases (`pedido` tiene 196 ejemplos frente a 80 de `charla`)**: optimizar o medir exactitud
  (*accuracy*) escondería fallos en la clase minoritaria `charla` (solo 12 ejemplos en `val` y 12 en `test`). Tanto la
  búsqueda de hiperparámetros como el criterio de éxito usan **F1 macro** (igual peso a las cinco clases) y comparan el
  ajuste con y sin pesos de clase balanceados.
- **`LinearSVC` no produce probabilidades nativas**: como los criterios exigen probabilidades calibradas para aplicar un
  umbral de confianza, cualquier variante basada en `LinearSVC` (e igualmente `LogisticRegression` cuando se compara su
  calibración) debe envolverse en un calibrador de probabilidades ajustado sin tocar `test`, de modo que el `Pipeline`
  serializado exponga siempre `predict_proba` con cinco probabilidades que sumen 1,0.
- **Indisponibilidad del servicio de embeddings de retrieval en local o en CI**: el modelo de embeddings de LAB-21
  (`bge-m3` / `nomic-embed-text` detrás del gateway de LAB-84) requiere contenedores activos. Para que el entrenamiento y
  la comparativa funcionen siempre en CPU y sin red en CI, el paquete debe soportar una caché local de vectores de
  embeddings del dataset (identificada por modelo y hash de los textos) y permitir recalcularla contra el servicio de
  embeddings cuando esté disponible, o fallar con un mensaje claro que indique cómo generarla o activar el servicio.
- **Dataset aún sin revisión humana completa (`REVIEW.md` de LAB-117)**: el informe de evaluación debe reflejar en sus
  metadatos el estado del dataset (hash SHA-256 de `intents.jsonl` y aviso si la revisión humana sigue pendiente) para
  que las métricas no se interpreten fuera de contexto en LAB-125.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El proyecto `ml/` MUST incluir un paquete Python importable para clasificación de intenciones con los
  puntos de entrada ejecutables `train.py` y `evaluate.py` (invocables desde línea de comandos, no encerrados en un
  notebook).
- **FR-002**: El entrenamiento MUST leer el dataset etiquetado `ml/data/intents/intents.jsonl` cumpliendo el contrato de
  LAB-117: cinco etiquetas fijas en orden canónico (`politica`, `producto`, `pedido`, `escalado`, `charla`), idiomas `es`
  y `pt`, identificador de grupo `group_id` y partición fija `split` (`train`, `val`, `test`).
- **FR-003**: El entrenamiento MUST verificar antes de ajustar ningún modelo que ningún grupo de origen (`group_id`) ni
  texto normalizado cruza entre `train`, `val` y `test`, y MUST rechazar o aislar estrictamente cualquier registro con
  `split == "test"` durante todo el proceso de `train.py` (preprocesado con estado, vectorización, búsqueda de
  hiperparámetros, selección de variante y calibración).
- **FR-004**: El paquete MUST incluir un paso de preprocesado de texto determinista e integrable dentro del `Pipeline`
  de scikit-learn que normalice espacios y caracteres de control, preserve los caracteres propios del español y del
  portugués (vocales acentuadas, `ñ`, `ç`, signos) y conserve intactas las marcas de enmascarado de LAB-117 (`[URL]`,
  `[EMAIL]`, `[ID]`, `[CNPJ]`, `[CPF]`, `[CEP]`, `[PHONE]`, `[NAME]`) como tokens diferenciados.
- **FR-005**: El entrenamiento MUST calcular y registrar las métricas de un **baseline trivial** de clase mayoritaria
  (que predice siempre la clase más frecuente en `train`) evaluado sobre las mismas particiones para servir como suelo de
  comparación.
- **FR-006**: El entrenamiento MUST implementar y comparar la **variante léxica (TF-IDF)** combinando características de
  palabras (n-gramas de palabras) y características de subpalabras (n-gramas de caracteres) dentro de un único flujo de
  transformación de scikit-learn, evaluada con al menos dos familias de clasificadores lineales: `LogisticRegression` y
  `LinearSVC`.
- **FR-007**: El entrenamiento MUST implementar y comparar la **variante densa (embeddings de retrieval)** que transforma
  cada mensaje en su vector del modelo de embeddings de retrieval (LAB-21) y ajusta un clasificador `LogisticRegression`
  dentro de un `Pipeline` de scikit-learn.
- **FR-008**: Para que la variante de embeddings de retrieval sea reproducible en CPU sin requerir servicios externos en
  cada ejecución ni en CI, el paquete MUST soportar la lectura y escritura de vectores precalculados de los ejemplos del
  dataset verificados por hash del texto y nombre del modelo de embeddings, además de poder solicitarlos al endpoint de
  embeddings compatible con el gateway del proyecto cuando se le indique.
- **FR-009**: La búsqueda de hiperparámetros de cada variante MUST realizarse mediante validación cruzada estratificada
  sobre `train` optimizando **F1 macro**, manteniendo juntos en el mismo pliegue todos los ejemplos que compartan
  `group_id` y usando una semilla fija configurable.
- **FR-010**: Todos los modelos candidatos comparados (incluidos los basados en `LinearSVC`) MUST entregar
  probabilidades calibradas para las cinco clases a través de `predict_proba`, de forma que para cada mensaje las cinco
  probabilidades estén en `[0, 1]`, sumen `1.0` y la clase devuelta por `predict` sea consistente con dichas
  probabilidades. La calibración MUST ajustarse únicamente con datos de `train` (por validación cruzada interna) o `val`,
  MUST NOT usar `test`.
- **FR-011**: La selección de la variante ganadora entre TF-IDF + `LogisticRegression`, TF-IDF + `LinearSVC` y
  Embeddings + `LogisticRegression` MUST decidirse exclusivamente con métricas obtenidas sobre `train` (validación
  cruzada) y `val`, aplicando una regla explícita y determinista que se registra en el informe de entrenamiento antes de
  ejecutar la evaluación final en `test`.
- **FR-012**: `train.py` MUST serializar el modelo seleccionado como un **único artefacto de `Pipeline` de scikit-learn**
  en disco, junto con un archivo de metadatos en JSON que registre: semilla utilizada, hash SHA-256 del dataset, tamaño
  de `train` y `val`, orden canónico de las cinco etiquetas, tabla comparativa de todas las variantes probadas (baseline,
  TF-IDF + `LogisticRegression`, TF-IDF + `LinearSVC`, Embeddings + `LogisticRegression`), hiperparámetros evaluados y
  elegidos, versión de las librerías y tiempo de entrenamiento.
- **FR-013**: `evaluate.py` MUST cargar el artefacto de `Pipeline` serializado y evaluarlo una sola vez sobre la
  partición `test` de `ml/data/intents/intents.jsonl` (sin reajustar ningún parámetro), generando un informe estructurado
  en JSON y un resumen legible por consola/Markdown.
- **FR-014**: El informe de `evaluate.py` en `test` MUST incluir:
  - F1 macro global del modelo y del baseline trivial de clase mayoritaria;
  - Precisión, recall, F1 y soporte de cada una de las cinco clases (`politica`, `producto`, `pedido`, `escalado`,
    `charla`);
  - Matriz de confusión 5×5 etiquetada con filas = clase real y columnas = clase predicha;
  - Métricas de calibración de probabilidades: Brier score multiclase, log-loss, error de calibración esperado (ECE) y
    tabla de fiabilidad por tramos de confianza (confianza media frente a tasa real de acierto y número de casos);
  - Tabla de simulación de umbral de confianza (para usar en LAB-121): porcentaje de mensajes retenidos (cobertura) y F1
    macro / precisión sobre los mensajes cuya probabilidad máxima es ≥ umbral, para varios umbrales representativos;
  - Desglose de F1 macro por idioma (`es`, `pt`) y en el subconjunto de `test` sin ascendencia del golden
    (`golden_id == null`).
- **FR-015**: El modelo entrenado MUST alcanzar un **F1 macro ≥ 0.85** sobre la partición `test`, y `evaluate.py` MUST
  validar este objetivo devolviendo un código de salida distinto de cero si el F1 macro en `test` es inferior al mínimo
  configurado (0.85 por defecto).
- **FR-016**: Con la misma semilla y el mismo dataset de entrada, dos ejecuciones de `train.py` y `evaluate.py` en la
  misma plataforma MUST producir predicciones, matrices de confusión y métricas idénticas.
- **FR-017**: El paquete MUST incluir pruebas unitarias y de integración ligera con `pytest` que verifiquen:
  - La función/transformador de preprocesado sobre casos borde en español y portugués y con marcas de enmascarado;
  - El determinismo de entrenamiento y predicción (`predict` y `predict_proba`) con la misma semilla y tras
    serializar/deserializar el artefacto;
  - La validez de las probabilidades calibradas (rango `[0, 1]`, suma `1.0`, correspondencia de columnas con las cinco
    etiquetas);
  - Las guardas que impiden que ejemplos con `split == "test"` entren al ajuste o que un mismo `group_id` se divida en la
    validación cruzada;
  - La ejecución completa de `train.py` y `evaluate.py` sin red ni servicios externos activos.
- **FR-018**: Esta historia MUST NOT modificar el código de producción de `backend/`, `frontend/`, `ingest/` ni `eval/`,
  ni alterar los registros de `ml/data/intents/intents.jsonl` generados en LAB-117.

### Key Entities *(include if feature involves data)*

- **Pipeline de Clasificación de Intención**: objeto compuesto de scikit-learn que encapsula en cadena el preprocesado de
  texto, la extracción de características (TF-IDF de palabras y caracteres, o transformación a vectores de embeddings) y
  el clasificador lineal con calibración de probabilidades, serializado como un único artefacto autocontenido.
- **Variante de Modelo**: cada una de las configuraciones candidatas comparadas bajo el mismo protocolo (`dummy_majority`,
  `tfidf_logreg`, `tfidf_linearsvc_calibrated`, `embedding_logreg`), con su espacio de hiperparámetros, sus métricas en
  validación cruzada y en `val`, su calibración y su latencia de inferencia por mensaje en CPU.
- **Artefacto del Modelo y Manifiesto de Entrenamiento**: el archivo binario del `Pipeline` ganador junto con su
  documento JSON de metadatos (semilla, hash SHA-256 del dataset, etiquetas, hiperparámetros óptimos, comparativa de
  variantes y versiones del entorno) que garantiza la trazabilidad hacia LAB-119 y LAB-125.
- **Informe de Evaluación en Test**: documento JSON (y resumen legible) producido por `evaluate.py` sobre la partición
  `test` con F1 macro, métricas por clase, matriz de confusión 5×5, curva de calibración por tramos, curva de cobertura
  frente a umbral de confianza, desglose por idioma (`es`/`pt`) y desglose sin casos del golden.
- **Caché de Embeddings del Dataset**: almacén local determinista que asocia cada ejemplo del dataset (por identificador
  y hash del texto) con su vector del modelo de embeddings de retrieval (LAB-21), permitiendo entrenar y comparar la
  variante densa en CPU de forma reproducible sin depender de un contenedor de modelos activo en cada corrida.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: El clasificador seleccionado alcanza un **F1 macro ≥ 0.85** en la partición `test` de
  `ml/data/intents/intents.jsonl` (superando en más de 0,50 puntos de F1 macro al baseline trivial de clase mayoritaria)
  y `evaluate.py` lo verifica automáticamente con código de salida `0`.
- **SC-002**: El 100 % de las predicciones de `predict_proba` del artefacto serializado devuelve 5 probabilidades finitas
  en `[0, 1]` cuya suma es `1.0` (±1e-6), con un error de calibración esperado (ECE) y Brier score reportados en `test`
  junto con la tabla de cobertura y precisión para al menos 5 umbrales de confianza entre 0,50 y 0,90.
- **SC-003**: Se comparan y documentan con las mismas particiones y semilla las 4 configuraciones exigidas (baseline de
  clase mayoritaria, TF-IDF palabras+caracteres con `LogisticRegression`, TF-IDF palabras+caracteres con `LinearSVC`
  calibrado y embeddings de retrieval con `LogisticRegression`), y `0` ejemplos de la partición `test` intervienen en el
  ajuste, la validación cruzada, la selección de variante o la calibración.
- **SC-004**: Dos ejecuciones consecutivas de `train.py` y `evaluate.py` con la misma semilla producen exactamente el
  mismo F1 macro, la misma matriz de confusión 5×5, las mismas etiquetas predichas en el 100 % de los ejemplos de `test`
  y probabilidades con diferencia máxima `< 1e-9`.
- **SC-005**: El entrenamiento completo de las variantes y la evaluación en `test` se ejecutan en **CPU en menos de 60
  segundos** (cuando los embeddings están en caché local), y la inferencia del artefacto léxico sobre los 102 mensajes de
  `test` tarda menos de 50 ms en total en CPU.
- **SC-006**: El 100 % de las pruebas unitarias y de determinismo en `ml/` pasa en integración continua sin conexión de
  red, sin base de datos y sin servidor de modelos, manteniendo una cobertura de líneas ≥ 80 % en los paquetes de `ml/`.

## Assumptions

- **Estructura del paquete en `ml/`**: `ml/` usa el layout `src/` con `hatchling` (establecido en LAB-116 con
  `src/olist_eda` y continuado en LAB-117 con `src/intent_dataset`). Para respetar simultáneamente esa convención y el
  criterio literal «Paquete `ml/intent` con `train.py` y `evaluate.py`», el código del paquete vive en
  `ml/src/intent/` (importable como `intent`, con `intent.train` e `intent.evaluate`, p. ej.
  `uv run python -m intent.train` y `uv run python -m intent.evaluate`) y se exponen o soportan también las rutas/comandos
  directos equivalentes documentados en `ml/README.md`. El plan fijará el detalle de empaquetado en `ml/pyproject.toml`.
- **Uso de las particiones `train`, `val` y `test` de LAB-117**: `ml/data/intents/intents.jsonl` ya trae asignada la
  partición fija (`train`: 481 ejemplos, `val`: 102 ejemplos, `test`: 102 ejemplos). La búsqueda de hiperparámetros se
  hace con validación cruzada estratificada por clase y agrupada por `group_id` sobre `train`; `val` se utiliza para la
  calibración y/o comparación y selección entre variantes antes de tocar `test` (o se incluye en el protocolo de
  validación previo al reajuste final antes de `test`), mientras que `test` permanece completamente sellado hasta la
  única evaluación en `evaluate.py`.
- **Obtención de los embeddings de retrieval (LAB-21)**: el modelo de embeddings del proyecto es el configurado en el
  stack local (`bge-m3` por defecto o `nomic-embed-text` en equipos de 8 GB, servidos a través del gateway LiteLLM de
  LAB-84 en `http://localhost:4000/v1/embeddings` o de Ollama). Como en CI y en ejecuciones rápidas de desarrollo esos
  contenedores no están levantados, se incluye un flujo para generar/actualizar y versionar (o almacenar en
  `ml/data/intents/`) los embeddings o características precalculadas de los 685 ejemplos del dataset para la comparativa,
  sin impedir que `train.py` calcule embeddings en vivo cuando el servicio esté disponible. Además, si la variante
  TF-IDF alcanza o supera a la de embeddings en validación, el artefacto principal serializado (`Pipeline` de
  scikit-learn que recibe `list[str]` directamente) no necesita llamar a ningún servicio externo en tiempo de inferencia.
- **Formato de serialización del artefacto**: se utiliza el mecanismo estándar del ecosistema scikit-learn (`joblib`) con
  únicamente componentes nativos de scikit-learn y funciones del propio paquete `intent` (sin lambdas anónimas), de modo
  que LAB-119 (MLflow `sklearn`) y LAB-120 (`ml-service` FastAPI) puedan cargarlo directamente sin código frágil.
- **Explicabilidad y pesos por clase**: los clasificadores lineales sobre TF-IDF permiten inspeccionar los términos y
  n-gramas con mayor coeficiente por cada una de las cinco clases; `train.py` / `evaluate.py` exponen o guardan los
  términos principales por clase en el informe para respaldar el objetivo de explicabilidad de la historia y adelantar el
  insumo que auditará LAB-125.
- **Dependencias nuevas en `ml/pyproject.toml`**: esta historia incorpora `scikit-learn` (que LAB-117 pospuso
  expresamente a LAB-118) dentro de `ml/pyproject.toml` y actualiza `ml/uv.lock`.
- **Fuera de alcance**: registrar experimentos o modelos en MLflow (LAB-119), exponer el modelo por HTTP en `ml-service`
  (LAB-120), conectar el clasificador con el módulo `chat` del backend Java (LAB-121), versionar artefactos con DVC
  (LAB-122), redactar la model card completa y auditoría formal de sesgos (LAB-125) o entrenar un transformer con PyTorch
  (LAB-126).
