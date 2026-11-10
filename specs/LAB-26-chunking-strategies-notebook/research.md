# Research: Notebook comparativo de estrategias de chunking (LAB-26)

Cada decisión indica qué se eligió, por qué y qué se descartó. No quedan puntos `NEEDS CLARIFICATION`. Los puntos
marcados **[verificar]** son supuestos sobre librerías o entorno que se comprueban en la primera tarea de implementación.

## R1 — Dónde vive y cómo se empaqueta

- **Decisión**: proyecto `uv` en `notebooks/` (`pyproject.toml`, `uv.lock`), con layout `src/chunking_lab/` y `tests/`,
  igual que `data/olist` y `data/kb-tools`. El notebook solo importa el paquete y narra.
- **Por qué**: la constitución reserva `notebooks/` a los experimentos y prohíbe que el código de producción los importe.
  Un proyecto propio aísla las dependencias de Jupyter, pandas y matplotlib de `ingest/` y del backend (FR-020) y encaja
  en la matriz de Python de CI (`uv sync --frozen`, `ruff check .`, `pytest`).
- **Alternativas**: dependencias en `ingest/pyproject.toml` (contamina un servicio de producción con pandas y Jupyter);
  un único `requirements.txt` (sin lock, no reproducible); notebook con toda la lógica en celdas (sin pruebas ni lint).

## R2 — Cómo se reutiliza el troceado por encabezados de `ingest`

- **Decisión**: `strategies/headings.py` añade `ingest/` **al final** de `sys.path` e importa
  `app.parsing.markdown.parse_markdown` y `app.chunking.splitter.split_sections`; construye `ChunkingConfig` con el
  tamaño y el solape del experimento. El texto se lee en UTF-8 tal cual (el parser quita el front matter).
- **Por qué**: FR-004 pide el mismo comportamiento que producción sin reimplementarlo. Esas dos funciones son el núcleo
  de `ingest_document` y solo exigen `markdown-it-py`. Se añade **al final** de `sys.path` para que el paquete `tests/` de
  `ingest/` no tape al `tests/` de `notebooks/`.
- **Guarda contra el desvío**: `test_headings.py` compara, para los 24 documentos, los chunks del adaptador con los de
  `ingest.app.service.ingest_document` (texto, sección y posición). Esa prueba es la única que necesita `pdfplumber`
  (`service.py` importa el parser de PDF), por eso está en el grupo `dev`.
- **Alternativas**: copiar el código (se desincroniza en silencio); llamar a `POST /ingest` (exige el servicio levantado
  y un `multipart` por documento; sirve de menos al lector del notebook que ve el código); instalar `ingest` como paquete
  (`package = false` y el nombre de módulo `app` es demasiado genérico).
- **Riesgo asumido**: el módulo se llama `app`; basta con que ningún otro paquete del entorno use ese nombre.

## R3 — Embeddings: mismo modelo que el sistema, por HTTP a Ollama

- **Decisión**: `OllamaEmbedder` usa `httpx` contra `POST {OLLAMA_BASE_URL}/api/embed` con `{"model", "input": [textos]}`
  por lotes (32 por defecto) y `GET /api/tags` para comprobar que el modelo está descargado. Variables:
  `OLLAMA_BASE_URL` (por defecto `http://localhost:11434`) y `OLLAMA_EMBED_MODEL` (por defecto `bge-m3`), las mismas
  que usa el sistema (ADR-0003, ADR-0017). Un `Protocol` `Embedder` permite inyectar un falso en pruebas.
- **Por qué**: los resultados solo son representativos si se usa el modelo de producción (supuesto de la spec). La API
  `/api/embed` acepta lotes y devuelve un vector por texto (verificado el 2026-11-10 contra el Ollama del compose:
  2 textos → 2 vectores de 1024 números); `/api/tags` lista el modelo como `bge-m3:latest` con su `digest` (verificado),
  así que el preflight compara por nombre base. El digest del modelo se guarda en los resultados para saber con qué versión se midió.
- **Preflight (FR-018, SC-007)**: segunda celda del notebook, antes de leer el corpus. Distingue tres casos con mensaje
  propio: Ollama inalcanzable (`docker compose -f infra/docker-compose.yml up -d --wait`), modelo ausente
  (`ollama pull bge-m3`) y respuesta inesperada. Lanza `PreflightError`; no hay resultados parciales porque aún no se ha
  calculado nada.
- **Alternativas**: `sentence-transformers` con `bge-m3` (cientos de MB de PyTorch y vectores que pueden diferir de los
  del sistema); Spring AI (obliga a levantar el backend); un modelo más pequeño solo para el notebook (resultados no
  representativos).

## R4 — Caché de embeddings

- **Decisión**: caché en `notebooks/.cache/embeddings/`, un archivo `.npy`/JSON por clave `sha256(modelo + "\n" + texto)`,
  activada por defecto y desactivable (`use_cache=False` en `ExperimentConfig`). Ignorada por git.
- **Por qué**: ≈ 650 textos por ejecución; repetir el notebook (por ejemplo, para comprobar SC-004 o ajustar el umbral)
  no debe costar minutos. La clave incluye el modelo, así que cambiar `OLLAMA_EMBED_MODEL` no reutiliza vectores ajenos.
- **Alternativas**: sin caché (iteración lenta); caché en memoria (se pierde al reiniciar el kernel).

## R5 — Estrategia de tamaño fijo

- **Decisión**: sobre el texto del documento **sin front matter** (igual que producción, para no penalizar a esta
  estrategia con metadatos), ventanas de `chunk_size` caracteres con paso `chunk_size − chunk_overlap` (1 000 y 100 por
  defecto). Corte duro en el carácter: no busca fronteras de palabra ni de frase. La última ventana puede ser más corta;
  nunca se genera una ventana vacía o solo de espacios. Posición consecutiva desde 0 y sin sección.
- **Por qué**: representa la línea base ingenua; dejar que corte a media frase o a media tabla es justo lo que el
  notebook quiere mostrar (spec, historia 1).
- **Alternativas**: cortar en el último espacio (ya es otra estrategia, más parecida al «recursivo»); quitar el solape
  (la spec lo declara configurable y 100 es el valor de producción).

## R6 — Estrategia semántica

- **Decisión**: por documento, sin front matter:
  1. **Unidades**: el Markdown se divide en bloques por línea en blanco; cada párrafo se parte en frases
     (`(?<=[.!?])\s+`), cada elemento de lista es una unidad y cada tabla es una sola unidad. Un bloque que es solo un
     encabezado se antepone a la unidad siguiente para que no sea una unidad aislada y ruidosa. Una unidad mayor que el
     tamaño máximo se corta por caracteres como último recurso.
  2. **Distancias**: embedding de cada unidad; distancia coseno entre unidades consecutivas.
  3. **Cortes**: se corta tras la unidad *i* si su distancia con la siguiente es ≥ el percentil `breakpoint_percentile`
     (80 por defecto) de las distancias **de ese documento**. Es el esquema habitual del troceado semántico por
     percentil.
  4. **Tope y fusión**: un grupo mayor que `chunk_size` se vuelve a partir en su punto de mayor distancia interna hasta
     caber; un grupo menor que `min_chunk_chars` (200 por defecto) se une al vecino con menor distancia siempre que el
     resultado no supere el tope. El tamaño máximo es el mismo que el de las otras estrategias (FR-006).
  5. **Sin solape** (declarado en el resumen): el corte semántico busca fronteras de significado; repetir texto las
     difuminaría.
- **Por qué**: el percentil por documento no depende de la escala absoluta de similitud de `bge-m3` y hace que cada
  documento reciba cortes proporcionales a su estructura; el umbral es un único parámetro declarado (FR-005, FR-016).
  Embeber unidades y chunks con el mismo modelo reutiliza la caché.
- **Alternativas**: umbral absoluto de similitud (frágil al cambiar de modelo y a documentos de distinta densidad);
  ventana móvil de varias frases (más parámetros para un corpus de documentos cortos); embeber con otro modelo (no
  representativo); el troceo semántico de librerías como LangChain/LlamaIndex (dependencias grandes para ~60 líneas de
  lógica que además hay que poder leer en el notebook).
- **Límite conocido**: documentos muy cortos (pocas unidades) dan percentiles poco informativos; se documenta en las
  conclusiones.

## R7 — Preguntas de prueba: formato y validación

- **Decisión**: `notebooks/data/questions.toml`, un `[[question]]` por pregunta con `id` (`Q01`…`Q20`), `topic`,
  `question`, `document` (id del documento, p. ej. `returns-01-return-policy`) y `passage` (texto literal del documento que
  contiene la respuesta). Se lee con `tomllib` de la biblioteca estándar. Formato completo en
  [contracts/questions-format.md](contracts/questions-format.md).
- **Validación previa (FR-010)**: exactamente 20 preguntas, 4 por cada tema que existe en el corpus, `id` únicos, el
  documento existe, **el pasaje aparece en ese documento** y **no aparece en ningún otro** (la respuesta no debe estar
  duplicada). Todos los fallos se listan juntos en un `InvalidQuestionsError`.
- **Cómo se comparan textos**: ambos lados se normalizan colapsando espacios en blanco; la comparación distingue
  mayúsculas y tildes. Así un chunk que une bloques con `\n\n` o con un espacio contiene el mismo pasaje.
- **Cómo se redactan los pasajes**: la frase o fila de tabla que contiene el dato, entre 40 y 300 caracteres. Con un
  solape de 100, un pasaje de 100 caracteres o menos siempre cabe entero en alguna ventana de tamaño fijo; para que el
  corte del límite pueda ocurrir de verdad, al menos 5 de los 20 pasajes superan los 100 caracteres (p. ej. dos frases
  seguidas o una fila de tabla con su encabezado). Una prueba lo comprueba.
- **TOML frente a YAML/JSON**: TOML se lee con la biblioteca estándar, admite tildes y cadenas multilínea, y se edita a
  mano mejor que JSON; YAML añadiría una dependencia.

## R8 — Criterio de acierto, recuperación y métricas

- **Decisión**:
  - Un chunk es **correcto** si su `document` es el esperado y su texto normalizado contiene el pasaje normalizado.
  - **Recuperación**: se embeben los chunks de cada estrategia y las 20 preguntas (texto de la pregunta tal cual, sin
    prefijo de instrucción) y se toman los `k` (4) chunks de mayor coseno de **todo el corpus**.
  - **Desempate** determinista: puntuación descendente, luego documento, luego posición.
  - **Posición** = lugar (1 a `k`) del primer correcto o `None` («no encontrado»).
  - **hit rate@k** = fracción de preguntas con posición; **MRR** = media de `1/posición` (0 si no hay).
- **Por qué**: `k = 4` es el `SHOPLAB_CHAT_RAG_TOP_K` de producción (FR-014). Medir sobre todo el corpus, no solo sobre el
  documento esperado, es lo que hace el chat. Exigir el pasaje (y no solo el documento) evita premiar chunks del
  documento correcto que no responden.
- **Alternativas**: acierto por documento (demasiado laxo, oculta lo que el notebook quiere ver); nDCG o recall@k
  (con una sola respuesta relevante por pregunta, MRR y hit rate bastan); LLM como juez (no determinista, fuera de
  alcance: F2).
- **Sin umbral de similitud**: el chat tiene `similarity-threshold` 0,6, pero aquí se mide el orden de los candidatos, no
  la decisión de contestar; aplicar el umbral mezclaría dos efectos. Queda anotado como límite.

## R9 — Reproducibilidad y ejecución

- **Decisión**:
  - El notebook se ejecuta con `uv run jupyter nbconvert --to notebook --execute --inplace chunking-strategies.ipynb`
    (o celda a celda en Jupyter/VS Code/IntelliJ) y se versiona con sus salidas (SC-005).
  - Tamaño fijo y encabezados son deterministas. Los embeddings de Ollama son estables en CPU para un mismo texto y modelo
    pero no se garantiza bit a bit entre máquinas; por eso `chunking-results.json` guarda la versión del modelo
    (digest) y el notebook declara que las **posiciones** (no las puntuaciones decimales) deben coincidir entre
    ejecuciones, y que dos ejecuciones en frío sobre la misma máquina se comparan con
    [quickstart.md §4](quickstart.md#4-reproducibilidad).
  - `results/chunking-results.json` contiene los parámetros, el resumen por estrategia y las 20 filas; el notebook lo
    escribe al terminar. [contracts/results-format.md](contracts/results-format.md).
  - La prueba `test_notebook.py` comprueba sin ejecutar nada: celda de parámetros presente y única, preflight como
    segunda celda de código, celdas de código con salidas, y que la tabla y las métricas del `.ipynb` coinciden con
    `chunking-results.json` (detecta un notebook guardado a medias o desactualizado).
- **Alternativas**: papermill (otra dependencia para solo inyectar parámetros); ejecutar el notebook en CI (exige Ollama y
  `bge-m3` en el runner: ~1,2 GB y minutos); no guardar JSON (la coherencia notebook↔resultados no sería comprobable).

## R10 — Conclusiones del notebook

- **Decisión**: la primera celda de la sección de conclusiones se **genera por código** a partir de los resultados
  (estrategia con mejor MRR, mejor hit rate y la diferencia con las demás); la interpretación (al menos dos preguntas
  concretas, con su chunk recuperado y la causa) y los límites se escriben a mano **después de la primera ejecución real**
  y citan las preguntas por `id`.
- **Por qué**: así el titular nunca contradice la tabla; la parte narrativa es de una persona porque explica el *por qué*.
  El README avisa de que, si se cambian los parámetros o el corpus, esa narrativa debe releerse.
- **Límites que se declaran** (spec, historia 3): 20 preguntas es una muestra pequeña (un solo acierto cambia el hit rate
  5 puntos), corpus sintético en español y de documentos cortos, estrategia semántica dependiente de su percentil, sin
  umbral de similitud, sin calidad de generación, y **granularidad distinta** entre estrategias con el tamaño por
  defecto (documentos de ≈ 1,4 k caracteres: el tamaño fijo da ≈ 40 chunks casi del tamaño del documento; los
  encabezados, 138 chunks de unos 250 caracteres de media). El tamaño de chunk es parte de lo que se compara.

## R11 — Sensibilidad del umbral semántico (opcional)

- **Decisión**: una sección final opcional repite la medición con (a) la estrategia semántica en los percentiles 60, 80 y
  95 y (b) las tres estrategias con `chunk_size` 400, 700 y 1000. Las unidades ya están embebidas (caché); solo se embeben
  los chunks nuevos.
- **Por qué**: la spec menciona la comparación de umbrales como opcional. El eje (b) se añade por un hecho medido: el
  corpus mide 34 629 caracteres en 24 documentos (≈ 1,4 k de media), así que con `chunk_size = 1000` el troceado de tamaño
  fijo produce solo ≈ 40 chunks (1 o 2 por documento, casi documentos enteros) frente a 138 por encabezados. Esa
  diferencia de granularidad, y no solo el criterio de corte, explicará parte de los resultados; el notebook debe decirlo
  y la sensibilidad a `chunk_size` permite ver si la conclusión se sostiene con chunks más pequeños. Se implementa solo si
  el resto está terminado (prioridad P3 en las tareas).

## R12 — CI, lint y seguridad

- **Decisión**:
  - `ci.yml`: filtro `notebooks` con `notebooks/**`, `ingest/app/**` y `data/kb/**`, y `"notebooks"` en la matriz de
    Python. El job existente ejecuta `uv sync --frozen`, `ruff check .` y `uv run pytest`.
  - `ruff` excluye `*.ipynb` (la prosa y las celdas de exposición no se lintan; la lógica está en el paquete).
  - **Trivy** escanea el sistema de archivos y verá `notebooks/uv.lock`. Jupyter arrastra muchas dependencias
    transitivas: al generar el lock se usan las últimas versiones y se corre el escáner (o se revisa el informe del primer
    PR). Si aparece un HIGH/CRITICAL con corrección disponible, se actualiza la dependencia; una excepción en
    `.trivyignore` solo con motivo y responsable (como pide el propio archivo).
  - `.gitignore`: `notebooks/.cache/`.
- **Por qué**: el job de Python ya tiene el patrón y no requiere servicios; el riesgo real es la superficie de Trivy, que
  se anticipa aquí.
- **Alternativa**: no añadir CI (las pruebas del paquete y la validación de preguntas contra `data/kb` dejarían de
  protegerse).
