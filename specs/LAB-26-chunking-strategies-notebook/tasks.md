---

description: "Task list for LAB-26 - Notebook comparativo de estrategias de chunking"
---

# Tasks: Notebook comparativo de estrategias de chunking

**Input**: Design documents from `/specs/LAB-26-chunking-strategies-notebook/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`questions-format.md`, `results-format.md`),
quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas antes o junto con la implementación y `notebooks/`
usará `--cov-fail-under=80` como `ingest/`. En cada historia las pruebas van primero y deben fallar antes de implementar.
Ninguna prueba necesita Ollama: usan un `Embedder` falso con vectores deterministas y `httpx.MockTransport`. Solo ejecutar
el notebook (T023, T037, T048, T049) necesita Ollama con `bge-m3`.

**Organization**: Tareas agrupadas por historia de usuario. US1 (estrategias) y US2 (preguntas y tabla) son P1 y forman
el MVP; US3 (conclusiones) y US4 (reproducibilidad, README, CI) son P2. US2 usa las estrategias de US1 y US3 usa los
resultados de US2, así que el orden de entrega es US1 → US2 → US3, con US4 casi en paralelo desde que existe el notebook.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US4)
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Proyecto**: `notebooks/` (proyecto `uv` `chunking-lab`); en las tareas, `nb/` = `notebooks/`
- **Paquete**: `nb/src/chunking_lab/`; **pruebas**: `nb/tests/` (sin `tests/__init__.py`, para no chocar con el `tests/`
  de `ingest/`); **notebook**: `nb/chunking-strategies.ipynb`
- Python: líneas ≤ 121 caracteres, identificadores en inglés, `ruff` con `select = ["E","F","I","B","UP"]` como
  `data/olist`; el texto del notebook y los mensajes de error para quien lo ejecuta, en español
- Nada de `notebooks/` se importa desde `backend/` ni `ingest/`; `ingest/` no se modifica (FR-021, FR-022)
- Sin datos personales, rutas locales (`C:\Users\…`), secretos ni claves en el notebook, sus salidas ni los datos (FR-023)
- El notebook se escribe con `nbformat` desde un script desechable fuera del repositorio (o en Jupyter) y se ejecuta con
  `uv run jupyter nbconvert --to notebook --execute --inplace chunking-strategies.ipynb`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base verde, supuestos de Ollama comprobados y proyecto `uv` creado

- [X] T001 Línea base de `ingest/`: `cd ingest && uv run pytest` debe pasar en la rama (no se modifica `ingest/`); anotar
  fallos previos si los hay
- [X] T002 Comprobar los supuestos **[verificar]** de [research.md](research.md) R3 con Ollama levantado
  (`docker compose -f infra/docker-compose.yml up -d --wait`): `POST http://localhost:11434/api/embed` con
  `{"model":"bge-m3","input":["a","b"]}` devuelve `embeddings` con 2 vectores de 1024 números; `GET /api/tags` lista el
  modelo (comprobar si el nombre es `bge-m3:latest`) con su `digest`. Anotar el resultado en research.md R3 y quitar la
  marca **[verificar]**; si algo difiere, ajustar R3 y los contratos antes de seguir
- [X] T003 Crear `nb/pyproject.toml` (proyecto `chunking-lab`, `requires-python >= 3.12`, build `hatchling` con
  `packages = ["src/chunking_lab"]`): dependencias `httpx`, `numpy`, `pandas`, `matplotlib`, `markdown-it-py`,
  `ipykernel`; grupo `dev` con `pytest`, `pytest-cov`, `ruff`, `nbformat`, `nbconvert`, `nbclient`, `pdfplumber`.
  `[tool.pytest.ini_options]`: `testpaths = ["tests"]`, `pythonpath = ["src"]`,
  `addopts = "--cov=chunking_lab --cov-fail-under=80"`. `[tool.ruff]`: `line-length = 121`, `target-version = "py312"`,
  `src = ["src", "tests"]`, `extend-exclude = ["*.ipynb"]`, `[tool.ruff.lint] select = ["E", "F", "I", "B", "UP"]`.
  Ejecutar `cd notebooks && uv sync` para generar `nb/uv.lock`. No tocar `ingest/pyproject.toml`
- [X] T004 [P] Crear el esqueleto vacío: `nb/src/chunking_lab/__init__.py`, `nb/src/chunking_lab/strategies/__init__.py`,
  `nb/tests/conftest.py` (vacío por ahora), carpetas `nb/data/` y `nb/results/` (con un `.gitkeep` cada una hasta que haya
  contenido) y borrar `nb/.gitkeep`
- [X] T005 [P] Añadir `notebooks/.cache/` a `.gitignore` (caché de embeddings, R4)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: configuración, errores, corpus, cliente de embeddings y tipos comunes que usan todas las historias

**⚠️ CRITICAL**: ninguna historia puede empezar hasta terminar esta fase

### Tests (escribirlos primero; deben fallar)

- [X] T006 [P] `nb/tests/test_config.py`: valores por defecto de [data-model.md](data-model.md) (`chunk_size` 1000,
  `chunk_overlap` 100, `top_k` 4, `breakpoint_percentile` 80, `min_chunk_chars` 200, `embed_batch_size` 32,
  `use_cache` True, `embed_model` `bge-m3`, `ollama_base_url` `http://localhost:11434`); `OLLAMA_EMBED_MODEL` y
  `OLLAMA_BASE_URL` los sobrescriben; cada regla inválida lanza `ValueError` que nombra el campo: `chunk_size` < 100,
  `chunk_overlap` < 0 o ≥ `chunk_size`, `top_k` < 1, `breakpoint_percentile` fuera de 1–99, `min_chunk_chars` < 0 o
  ≥ `chunk_size`, `embed_batch_size` < 1, `embed_model` vacío, URL que no empieza por `http://` o `https://`; las rutas
  por defecto se resuelven desde la raíz del repositorio, no desde el directorio de trabajo
- [X] T007 [P] `nb/tests/test_corpus.py`: con un directorio temporal, `load_corpus` excluye `INDEX.md` y `REVIEW.md`,
  quita el front matter en `body` y conserva `raw`, deduce `topic` del prefijo del id, ordena por id y lee UTF-8; con el
  corpus real (`data/kb`) devuelve exactamente 24 documentos y los cinco temas `payments`, `privacy`, `returns`,
  `shipping`, `warranty`; un directorio sin `.md` lanza error claro
- [X] T008 [P] `nb/tests/test_embeddings.py` con `httpx.MockTransport`: `OllamaEmbedder.embed` envía lotes de
  `embed_batch_size`, conserva el orden y devuelve un `numpy.ndarray` (n, dim); un texto en caché no vuelve a pedirse; la
  clave de caché incluye el modelo (otro modelo no reutiliza vectores); con `use_cache=False` no lee ni escribe
  `.cache/`; `preflight` distingue tres casos con mensaje propio y accionable en español: Ollama inalcanzable (menciona
  `docker compose -f infra/docker-compose.yml up -d --wait`), modelo ausente (menciona `ollama pull <modelo>`) y respuesta
  inesperada; el nombre `bge-m3` coincide con `bge-m3:latest`; `preflight` devuelve el digest del modelo (o cadena vacía);
  una respuesta con distinto número de vectores o un HTTP ≠ 200 lanza `EmbeddingError`

### Implementation

- [X] T009 [P] `nb/src/chunking_lab/errors.py`: `PreflightError`, `InvalidQuestionsError` (guarda la lista de fallos con su
  código y el `id` de la pregunta; `str()` los muestra todos) y `EmbeddingError`, todos derivados de `ChunkingLabError`
- [X] T010 `nb/src/chunking_lab/config.py`: `ExperimentConfig` (dataclass inmutable) con los campos, defectos y reglas de
  T006, validación en `__post_init__` y rutas resueltas desde la raíz del repositorio (`kb_dir`, `questions_path`,
  `results_path`); mismas reglas de tamaño y solape que `ChunkingConfig` de `ingest`
- [X] T011 [P] `nb/src/chunking_lab/corpus.py`: `Document` (`id`, `topic`, `raw`, `body`) y `load_corpus(kb_dir)`; quita
  el front matter con el mismo criterio que el parser de `ingest` (bloque inicial `---` … `---`)
- [X] T012 `nb/src/chunking_lab/embeddings.py`: `Embedder` (`Protocol` con `embed(texts) -> ndarray`) y `OllamaEmbedder`
  (`httpx`, `POST /api/embed` por lotes, caché en `nb/.cache/embeddings/` con clave `sha256(modelo + "\n" + texto)`,
  `preflight() -> str` con el digest) según R3 y R4. Depende de T009, T010
- [X] T013 `nb/src/chunking_lab/strategies/__init__.py`: `Strategy` (enum `fixed`, `headings`, `semantic`, en ese orden),
  `Chunk` (dataclass inmutable: `strategy`, `document`, `position`, `section`, `text`; `text` no vacío) y
  `build_chunks(strategy, documents, config, embedder)` que despacha a cada estrategia. Depende de T010, T011
- [X] T014 `nb/tests/conftest.py`: añade `ingest/` **al final** de `sys.path` (R2); fixtures `FakeEmbedder` (vector
  determinista por texto a partir de `sha256`, y modo con diccionario `texto → vector` para controlar la similitud),
  `tmp_kb` (corpus mínimo temporal) y `real_kb_dir`. Depende de T012

**Checkpoint**: `cd notebooks && uv run pytest tests/test_config.py tests/test_corpus.py tests/test_embeddings.py` pasa;
`uv run ruff check .` limpio

---

## Phase 3: User Story 1 - Trocear el corpus con tres estrategias y ver en qué se diferencian (Priority: P1) 🎯 MVP

**Goal**: el notebook trocea `data/kb` con tamaño fijo, por encabezados y semántica en las mismas condiciones y muestra,
por estrategia, el número de chunks y su distribución de tamaño, más un mismo pasaje cortado de tres maneras.

**Independent Test**: ejecutar las secciones 0–6 del notebook y comprobar que muestran las tres estrategias con
`chunk_count`, tamaño mínimo, medio y máximo, y un pasaje cortado por cada una (spec, historia 1).

### Tests for User Story 1 (escribirlos primero; deben fallar)

- [X] T015 [P] [US1] `nb/tests/test_fixed.py`: ventanas con paso `chunk_size − chunk_overlap`; cada chunk después del
  primero empieza repitiendo los últimos `chunk_overlap` caracteres del anterior; ningún chunk supera `chunk_size`; la
  última puede ser más corta; nunca hay un chunk vacío o solo de espacios; posiciones consecutivas desde 0; `section`
  vacía; con `chunk_overlap = 0` no hay repetición; usa `body` (sin front matter); corta a mitad de palabra cuando toca
  (no ajusta a fronteras); misma entrada ⇒ mismos chunks
- [X] T016 [P] [US1] `nb/tests/test_headings.py`: para los 24 documentos de `data/kb`, los chunks del adaptador coinciden
  (texto, sección y posición) con los de `app.service.ingest_document` de `ingest/` con
  `ChunkingConfig(chunk_size, chunk_overlap)` (FR-004); ningún chunk mezcla dos secciones; un documento sin encabezados
  cae a trocear por tamaño con sección vacía; `ingest/` está al final de `sys.path` y `import tests` sigue resolviendo al
  `tests/` de `notebooks/`
- [X] T017 [P] [US1] `nb/tests/test_semantic.py` con `FakeEmbedder` de vectores controlados: corta donde cambia el tema
  (distancia ≥ percentil de ese documento); un bloque que solo es un encabezado se antepone a la unidad siguiente; una
  tabla es una sola unidad; cada elemento de lista es una unidad; los párrafos se parten en frases; un grupo mayor que
  `chunk_size` se vuelve a partir en su mayor distancia interna hasta caber; un grupo menor que `min_chunk_chars` se une
  al vecino de menor distancia si cabe; una unidad mayor que `chunk_size` se corta por caracteres; ningún chunk supera
  `chunk_size`; sin solape; documento de una sola unidad ⇒ un chunk; un percentil mayor produce igual o menos cortes que
  uno menor; posiciones consecutivas; misma entrada ⇒ mismos chunks
- [X] T018 [P] [US1] `nb/tests/test_report.py` (parte 1): `size_summary` devuelve por estrategia `chunk_count`, `size_min`,
  `size_mean` (redondeado a 1 decimal) y `size_max`, siempre en el orden `fixed`, `headings`, `semantic`; `cut_example`
  devuelve, por estrategia, el/los chunks que contienen el inicio de un pasaje dado e indica si el pasaje quedó partido
  (ningún chunk lo contiene entero)

### Implementation for User Story 1

- [X] T019 [P] [US1] `nb/src/chunking_lab/strategies/fixed.py`: ventanas de caracteres sobre `Document.body` según R5
- [X] T020 [P] [US1] `nb/src/chunking_lab/strategies/headings.py`: adaptador que añade `ingest/` al final de `sys.path`,
  importa `app.parsing.markdown.parse_markdown` y `app.chunking.splitter.split_sections`, construye `ChunkingConfig` y
  convierte los `Chunk` de `ingest` en los del paquete (R2). Sin copiar código de `ingest`
- [X] T021 [P] [US1] `nb/src/chunking_lab/strategies/semantic.py`: unidades → embeddings → distancias coseno → cortes por
  `breakpoint_percentile` de ese documento → tope `chunk_size` y fusión `min_chunk_chars`, sin solape, según R6; expone
  `semantic_units(document)` y `adjacent_distances(units, embedder)` para poder dibujarlas en el notebook. Depende de T012
- [X] T022 [P] [US1] `nb/src/chunking_lab/report.py` (parte 1): `size_summary(chunks_by_strategy)` y `cut_example(...)`
  según T018, y un gráfico (`matplotlib`) del histograma de tamaños por estrategia
- [X] T023 [US1] Crear `nb/chunking-strategies.ipynb` con las secciones 0–6, en español: (0) objetivo, qué se compara y
  cómo leerlo; (1) **una sola celda de parámetros**, etiquetada `parameters`, con `ExperimentConfig` y una línea de
  explicación por parámetro (FR-016); (2) **segunda celda de código = preflight** (FR-018); (3) corpus: 24 documentos y
  tamaños; (4) las tres estrategias, cada una con una explicación breve, y para la semántica la curva de distancias de un
  documento con sus cortes; (5) tabla resumen de chunks y histograma de tamaños; (6) un mismo pasaje de un documento
  cortado por las tres estrategias, lado a lado. Ejecutarlo con Ollama levantado y dejar las salidas guardadas. Depende de
  T019–T022

**Checkpoint**: `uv run pytest` pasa; el notebook ejecuta las secciones 0–6 y muestra las tres estrategias. US1 se puede
demostrar sola

---

## Phase 4: User Story 2 - Medir con 20 preguntas de prueba y leer una tabla de resultados (Priority: P1) 🎯 MVP

**Goal**: 20 preguntas validadas se lanzan contra los chunks de cada estrategia y el notebook muestra la tabla de 20
filas con hit rate@k y MRR por estrategia, y guarda `results/chunking-results.json`.

**Independent Test**: ejecutar las secciones 7–8 y comprobar que la tabla tiene 20 filas, una columna de resultado por
estrategia y un resumen con hit rate y MRR; que `chunking-results.json` cumple
[contracts/results-format.md](contracts/results-format.md).

### Tests for User Story 2 (escribirlos primero; deben fallar)

- [X] T024 [P] [US2] `nb/tests/test_questions.py`: con un corpus temporal, un caso por cada regla de
  [contracts/questions-format.md](contracts/questions-format.md) — `Q-COUNT` (exactamente 20), `Q-TOPICS` (exactamente 4
  por cada tema del corpus), `Q-ID` (formato `Q` + dos dígitos, único), `Q-FIELDS` (todas las claves presentes, no vacías,
  claves desconocidas rechazadas), `Q-DOC` (existe y su `topic` coincide), `Q-PASSAGE-MISSING`, `Q-PASSAGE-AMBIGUOUS`
  (aparece en otro documento), `Q-PASSAGE-LENGTH` (40–300 caracteres normalizados), `Q-LONG-PASSAGES` (al menos 5 de más
  de 100); `InvalidQuestionsError` agrega todos los fallos con el `id`; un pasaje que cruza un salto de línea del
  Markdown se encuentra tras colapsar espacios; mayúsculas y tildes se respetan; **prueba contractual**: el
  `nb/data/questions.toml` real valida contra `data/kb`
- [X] T025 [P] [US2] `nb/tests/test_retrieval_metrics.py`: el índice por estrategia devuelve los `top_k` chunks de mayor
  coseno de **todo el corpus**; desempate determinista por puntuación descendente, luego documento, luego posición; un
  chunk es correcto solo si su `document` es el esperado **y** contiene el pasaje con espacios normalizados (documento
  correcto sin el pasaje ⇒ incorrecto); `rank` es la posición (1 a `top_k`) del primer correcto o `None`; pasaje partido
  entre dos chunks sin solape suficiente ⇒ `None`; `hit_rate` = preguntas con `rank` / 20; `mrr` = media de `1/rank` con 0
  para `None`; misma entrada ⇒ mismo resultado
- [X] T026 [P] [US2] `nb/tests/test_report.py` (parte 2, resultados): construir las filas y el resumen a partir de los
  `rank`; `write_results`/`read_results` redondean `hit_rate` y `mrr` a 4 decimales y `size_mean` a 1; claves en el orden
  del contrato y estrategias en el orden `fixed`, `headings`, `semantic`; `validate_results` rechaza si `rows` no tiene
  exactamente 20 filas, `id` repetidos, `rank` fuera de [1, `top_k`], `misses` ≠ número de `null`, `hit_rate` ≠
  (20 − `misses`) / 20 o `mrr` ≠ media de `1/rank`; `format_version` es 1; no se guardan puntuaciones

### Implementation for User Story 2

- [X] T027 [P] [US2] `nb/src/chunking_lab/questions.py`: `Question` (dataclass inmutable), `load_questions(path)` con
  `tomllib` y `validate_questions(questions, documents)` con las reglas y códigos de T024; también la normalización de
  espacios compartida (`normalize`), que usará `metrics.py`
- [X] T028 [P] [US2] `nb/src/chunking_lab/retrieval.py`: `ChunkIndex` (embeddings de los chunks de una estrategia) y
  `search(question_vector, top_k)` por coseno con desempate determinista (R8)
- [X] T029 [P] [US2] `nb/src/chunking_lab/metrics.py`: `is_correct(chunk, question)`, `rank_of_first_correct`, `hit_rate`,
  `mean_reciprocal_rank` (R8)
- [X] T030 [US2] `nb/src/chunking_lab/report.py` (parte 2): `results_table` (DataFrame de 20 filas con una columna por
  estrategia y fila final de resumen), `build_results`, `write_results`, `read_results`, `validate_results`, gráfico de
  barras de hit rate y MRR por estrategia. Depende de T022, T029

### Datos: las 20 preguntas (cuatro por tema)

Cada tarea lee los documentos del tema en `data/kb/` y añade 4 bloques `[[question]]` a `nb/data/questions.toml` según
[contracts/questions-format.md](contracts/questions-format.md): pregunta en español como la escribiría un cliente, sin
copiar la frase del documento; `document` = id del documento; `passage` = texto literal (una sola línea) del documento,
40–300 caracteres, que contiene el dato que responde; la respuesta no debe estar repetida en otro documento. Mezclar tipos:
un dato en un párrafo, una fila de tabla, un elemento de lista, una pregunta de FAQ. Los `id` siguen el orden de las tareas
(`Q01`–`Q04`, `Q05`–`Q08`, …). Entre las 20 hay **al menos 5 pasajes de más de 100 caracteres** (dos frases seguidas o una
fila de tabla con su contexto), para que el corte por límite de chunk pueda ocurrir de verdad (R7). Se ejecutan en orden
porque editan el mismo archivo.

- [X] T031 [US2] Preguntas `Q01`–`Q04`, tema `payments` (`data/kb/payments-*.md`), en `nb/data/questions.toml`
- [X] T032 [US2] Preguntas `Q05`–`Q08`, tema `privacy` (`data/kb/privacy-*.md`), en `nb/data/questions.toml`
- [X] T033 [US2] Preguntas `Q09`–`Q12`, tema `returns` (`data/kb/returns-*.md`), en `nb/data/questions.toml`
- [X] T034 [US2] Preguntas `Q13`–`Q16`, tema `shipping` (`data/kb/shipping-*.md`), en `nb/data/questions.toml`
- [X] T035 [US2] Preguntas `Q17`–`Q20`, tema `warranty` (`data/kb/warranty-*.md`), en `nb/data/questions.toml`
- [X] T036 [US2] Validar el archivo completo: `cd notebooks && uv run pytest tests/test_questions.py` debe pasar (20
  preguntas, 4 por tema, pasajes presentes y únicos, ≥ 5 de más de 100 caracteres); corregir las preguntas hasta que pase.
  Revisar a mano que ninguna pregunta se pueda contestar igual de bien desde otro documento

### Notebook

- [X] T037 [US2] Ampliar `nb/chunking-strategies.ipynb` con las secciones 7–8: (7) las 20 preguntas y su validación
  previa (se detiene y lista las afectadas si falla); (8) recuperación de `top_k` para las tres estrategias con las mismas
  preguntas, la tabla de 20 filas con el resumen (hit rate@k y MRR por estrategia), el gráfico y una celda final que
  escribe `nb/results/chunking-results.json`. Ejecutar de arriba abajo con Ollama y dejar las salidas y el JSON
  guardados. Depende de T030, T036

**Checkpoint**: `uv run pytest` pasa; la tabla tiene 20 filas; `chunking-results.json` válido. US1 + US2 son el MVP

---

## Phase 5: User Story 3 - Sacar conclusiones y conocer los límites de la comparación (Priority: P2)

**Goal**: el notebook interpreta los resultados: nombra la estrategia con mejores métricas, explica con al menos dos
preguntas concretas por qué difieren y declara los límites.

**Independent Test**: leer la sección de conclusiones del notebook ejecutado: nombra la estrategia con mejores métricas,
relaciona al menos dos preguntas de la tabla (por `id`) con una causa de acierto o fallo y enumera los límites.

### Tests for User Story 3 (escribirlos primero; deben fallar)

- [X] T038 [P] [US3] `nb/tests/test_report.py` (parte 3): `headline(results)` nombra la estrategia con mejor MRR y la de
  mejor hit rate, con la diferencia respecto a las otras; con empate lo dice en lugar de elegir una; `differing_cases(rows)`
  devuelve las preguntas en las que los `rank` difieren entre estrategias, ordenadas por mayor diferencia y luego por `id`,
  e incluye las que fallan en una sola estrategia; si ninguna difiere, devuelve lista vacía

### Implementation for User Story 3

- [X] T039 [US3] `nb/src/chunking_lab/report.py` (parte 3): `headline` y `differing_cases` según T038, y un ayudante que,
  dada una pregunta y una estrategia, devuelve los chunks recuperados con su puntuación y marca cuál es el correcto o que
  no lo hay. Depende de T030
- [X] T040 [US3] Ampliar el notebook con la sección 9 «Dónde difieren las estrategias»: **al menos dos preguntas** de
  `differing_cases` con el chunk recuperado (o el que faltó) por cada estrategia y una explicación escrita a mano, citando
  el `id`, de por qué una acertó y otra falló (p. ej. una tabla cortada por la mitad, una sección diluida en un chunk
  grande). Escribirla **después de ver los resultados reales** de T037
- [X] T041 [US3] Ampliar el notebook con la sección «Conclusiones y límites»: titular generado por `headline` (nunca
  contradice la tabla), interpretación escrita a mano que cita al menos dos preguntas por `id`, y la lista de límites de
  la spec: 20 preguntas es una muestra pequeña (un acierto cambia el hit rate 5 puntos), corpus sintético en español, la
  semántica depende de su percentil, sin umbral de similitud ni calidad de generación, y **granularidad distinta** entre
  estrategias con el tamaño por defecto (documentos de ≈ 1,4 k caracteres; ≈ 40 chunks fijos frente a 138 por
  encabezados). Dejar explícito que **no cambia el chunking de producción** y que adoptar otra estrategia es otra historia
  que exige la evaluación de F2 (FR-022)

### Sensibilidad (opcional, P3 — solo si lo anterior está terminado)

- [X] T042 [P] [US3] `nb/tests/test_report.py` (parte 4): `sensitivity` repite la medición variando un parámetro
  (percentil 60/80/95 de la semántica y `chunk_size` 400/700/1000 para las tres) y devuelve una fila por valor con hit
  rate y MRR; reutiliza las unidades ya embebidas (comprobar con un `FakeEmbedder` que cuenta llamadas que no se vuelven a
  embeber las unidades)
- [X] T043 [US3] Implementar `sensitivity` en `nb/src/chunking_lab/report.py` y añadir al notebook la sección opcional
  «¿Cambia la conclusión si cambio el umbral o el tamaño?» con las dos tablas y una frase de lectura (R11). Depende de
  T042

**Checkpoint**: la conclusión nombra una estrategia, cita ≥ 2 preguntas y declara los límites (SC-006)

---

## Phase 6: User Story 4 - Ejecutarlo desde cero y leerlo sin ejecutarlo (Priority: P2)

**Goal**: cualquiera puede ejecutar el notebook siguiendo un README corto, leer sus resultados sin ejecutarlo, y CI protege
el paquete.

**Independent Test**: desde un clon limpio, seguir `nb/README.md`, ejecutar todas las celdas y obtener la misma tabla que la
guardada en el repositorio (quickstart §2 y §4); sin Ollama, falla en la segunda celda de código con un mensaje accionable
(quickstart §3).

### Tests for User Story 4 (escribirlos primero; deben fallar)

- [X] T044 [P] [US4] `nb/tests/test_notebook.py` (sin ejecutar nada, con `nbformat`): hay **una sola** celda etiquetada
  `parameters`; la segunda celda de código es el preflight; todas las celdas de código tienen `execution_count` y ninguna
  salida de tipo `error`; la celda final imprime el `sha256` de `results/chunking-results.json` y coincide con el del
  archivo (detecta un notebook o un JSON guardados a medias); los parámetros de la celda `parameters` coinciden con
  `meta.params` del JSON; el notebook no contiene rutas absolutas locales (`C:\Users`, `/Users/`, `/home/`) en código ni
  salidas; ningún archivo de `backend/src` ni `ingest/app` contiene `notebooks` ni `chunking_lab` (FR-021)

### Implementation for User Story 4

- [X] T045 [P] [US4] `nb/README.md` en español (FR-019): qué es, requisitos (Python, `uv`, Ollama con `bge-m3` y cómo
  levantarlo con el compose), instalación con `uv sync` (un solo comando), cómo ejecutarlo (nbconvert o Jupyter/VS
  Code/IntelliJ), cómo leer la tabla y las métricas (hit rate@k, MRR, qué cuenta como acierto), variables
  `OLLAMA_BASE_URL`/`OLLAMA_EMBED_MODEL`, caché, aviso de que al cambiar parámetros o corpus hay que releer las
  conclusiones escritas a mano, y cómo correr las pruebas
- [X] T046 [US4] Ampliar el notebook con la sección «Reproducibilidad»: versión y digest del modelo, qué debe coincidir
  entre ejecuciones (las posiciones, no las puntuaciones decimales), la tolerancia declarada y cómo comparar dos
  ejecuciones; la celda final imprime el `sha256` del JSON guardado (T044). Depende de T037
- [X] T047 [US4] Verificar el fallo temprano (quickstart §3, SC-007): con Ollama parado, ejecutar el notebook en un
  directorio de salida temporal y comprobar que falla en la segunda celda de código con `PreflightError` y un mensaje que
  dice qué falta y cómo arrancarlo; repetir con `OLLAMA_EMBED_MODEL=no-existe` (debe sugerir `ollama pull`). Restaurar
  Ollama. Si el mensaje no es claro, corregir `embeddings.py` y T008
- [X] T048 [US4] Verificar la reproducibilidad (quickstart §4, SC-003, SC-004): borrar `nb/.cache`, ejecutar el notebook en
  frío, **anotar el tiempo** (debe ser < 15 min sin GPU) y comparar `summary` y `rank` de `rows` con el JSON anterior. Si
  alguna posición de `semantic` cambia, ajustar el texto de tolerancia de la sección de reproducibilidad (T046) con lo
  observado. Dejar guardados el notebook y el JSON de esta última ejecución
- [X] T049 [US4] CI en `.github/workflows/ci.yml`: añadir el filtro `notebooks` (`notebooks/**`, `ingest/app/**`,
  `data/kb/**`), su salida en `outputs` del job `changes`, la variable `NOTEBOOKS` y la línea
  `items+=('"notebooks"')` en el paso «Select Python components». El job `python` existente ya hace
  `uv sync --frozen`, `uv run ruff check .` y `uv run pytest`. No cambiar nada más del workflow
- [X] T050 [US4] Seguridad de dependencias (R12): `cd notebooks && uv sync --frozen` desde cero debe funcionar; escanear
  `nb/uv.lock` con Trivy si está disponible (`trivy fs --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed notebooks`)
  y, si aparece un HIGH/CRITICAL con corrección, subir esa dependencia y regenerar `uv.lock`; solo si no hay corrección y
  se justifica, añadir una excepción en `.trivyignore` con motivo y responsable

**Checkpoint**: las historias 1–4 funcionan; el README permite repetir el experimento; CI cubre el paquete

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: documentación, verificación completa y limpieza

- [X] T051 [P] `README.md` de la raíz: actualizar la fila de `notebooks/` de la tabla de carpetas (enlazar a
  `notebooks/README.md` y decir que contiene el notebook comparativo de chunking) y la frase de CI (≈ línea 177) para
  incluir `notebooks/` entre los componentes que se construyen por separado
- [X] T052 Verificación completa del proyecto: `cd notebooks && uv sync --frozen && uv run ruff check . && uv run pytest`
  (cobertura del paquete ≥ 80 %, líneas ≤ 121); `cd ../ingest && uv run pytest` sigue verde (T001)
- [X] T053 Comprobación de alcance (quickstart §6): `git diff --stat main -- ingest backend frontend infra` sin cambios;
  `grep -rn "notebooks\|chunking_lab" backend/src ingest/app` sin resultados; solo cambian `notebooks/`, `specs/LAB-26-…`,
  `.github/workflows/ci.yml`, `.gitignore` y `README.md`
- [X] T054 Lista previa al commit (`CLAUDE.md` §8): `git status --short` sin `.cache/`, `.ipynb_checkpoints/`, `.tmp/`,
  `*.bak` ni salidas sueltas; `git diff --staged` solo con cambios intencionados; abrir el `.ipynb` y confirmar que no
  hay rutas locales, nombres de usuario ni claves en el código ni en las salidas (FR-023); revisar que el tamaño del
  notebook es razonable (< 1 MB)
- [X] T055 Recorrer [quickstart.md](quickstart.md) completo (§1 a §6) y comprobar que cada resultado esperado se cumple;
  releer la conclusión del notebook contra los números finales de la tabla
- [X] T056 Marcar las tareas hechas en este archivo y anotar al final de «Notes» cualquier desvío respecto al plan

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias. T002 debe terminar antes de fijar el diseño de `embeddings.py` (T012).
- **Foundational (Phase 2)**: depende de Setup; bloquea todas las historias. Las pruebas (T006–T008) van antes que la
  implementación (T009–T014).
- **US1 (Phase 3)**: depende de Foundational. **US2 (Phase 4)**: depende de US1 (necesita las estrategias y `report.py`).
  **US3 (Phase 5)**: depende de US2 (necesita los resultados). **US4 (Phase 6)**: README y CI (T045, T049) pueden empezar
  tras Foundational; las pruebas y verificaciones del notebook (T044, T046–T048) necesitan el notebook de US2.
- **Polish (Phase 7)**: depende de las historias deseadas.

### Within Each User Story

- Las pruebas se escriben primero y deben fallar.
- `Chunk`/`Strategy`/configuración → estrategias y utilidades → notebook.
- El notebook se ejecuta (con Ollama) solo cuando el paquete ya tiene sus pruebas en verde.
- Las preguntas (T031–T035) editan el mismo archivo: en orden; la validación completa es T036.

### Parallel Opportunities

- Setup: T004 y T005.
- Foundational: T006–T008 (pruebas) y luego T009, T011 en paralelo; T010, T012, T013 después de sus dependencias.
- US1: T015–T018 (pruebas) y T019–T022 (implementaciones, archivos distintos).
- US2: T024–T026 y T027–T029.
- US4: T044 y T045.

---

## Parallel Example: User Story 1

```bash
# Pruebas de US1 juntas:
Task: "nb/tests/test_fixed.py"
Task: "nb/tests/test_headings.py"
Task: "nb/tests/test_semantic.py"
Task: "size_summary y cut_example en nb/tests/test_report.py"

# Implementaciones de US1 juntas (archivos distintos):
Task: "nb/src/chunking_lab/strategies/fixed.py"
Task: "nb/src/chunking_lab/strategies/headings.py"
Task: "nb/src/chunking_lab/strategies/semantic.py"
Task: "nb/src/chunking_lab/report.py (parte 1)"
```

---

## Implementation Strategy

### MVP (US1 + US2)

1. Setup y Foundational.
2. US1: las tres estrategias y las secciones 0–6 del notebook. **Parar y validar** con el quickstart §2 (parcial).
3. US2: las 20 preguntas, la tabla y `chunking-results.json`. **Parar y validar** con el quickstart §1 y §2; ya cumple los
   dos criterios de aceptación de la historia («compara tamaño fijo, por encabezados y semántico» y «tabla de resultados
   con 20 preguntas de prueba»).

### Entrega incremental

4. US3: casos que difieren, conclusiones y límites (y, si hay tiempo, la sensibilidad opcional).
5. US4: README, pruebas de coherencia del notebook, fallo temprano, reproducibilidad, CI y Trivy.
6. Polish: README raíz, verificación completa, alcance y limpieza.

## Notes

- Cada tarea se marca `[X]` al terminar y se hace un commit atómico por tarea o grupo lógico (Conventional Commits, p. ej.
  `feat(notebooks): add fixed-size chunking strategy`). El título del PR llevará `[LAB-26]` (`CLAUDE.md` §5.1).
- Las pruebas de cada historia deben fallar antes de implementar.
- Esta historia no añade ningún endpoint HTTP, por lo que no genera `requests.http`; tampoco añade propiedades de
  configuración a ningún `application*.yml` ni cambios en `infra/` (las variables `OLLAMA_*` ya existen).
- La conclusión y los casos que difieren (T040, T041) se redactan a mano **después** de la primera ejecución real; el
  titular se genera por código para no contradecir la tabla.
- Si T002 revela que la API de Ollama difiere de lo supuesto, actualizar research.md R3 y
  [contracts/results-format.md](contracts/results-format.md) antes de seguir.
- No modificar `ingest/`: si el adaptador de encabezados necesitara un cambio allí, parar y consultarlo (FR-021, FR-022).
- Desvíos respecto a lo escrito arriba, sin cambiar el resultado:
  - La normalización de espacios (`normalize`) vive en `chunking_lab/text.py` y no en `questions.py`, para que `report.py`
    y `metrics.py` la usen sin depender de `questions.py`. El doble de prueba `FakeEmbedder` está en `tests/helpers.py` (no en
    `conftest.py`), porque `tests/` no es un paquete y `from tests...` resolvería al `tests/` de `ingest/`.
  - El umbral de la estrategia semántica es **estricto** (distancia `>` percentil, no `>=`): con distancias todas iguales no
    hay cortes. Recogido en research.md R6.
  - T031–T035 se escribieron de una vez en `questions.toml`. Se eligieron hechos que aparecen en un solo documento (las FAQs
    repiten casi todo); aun así queda un solape semántico (p. ej. Q03 con la FAQ de pagos) que el notebook declara como límite.
  - La sensibilidad (T042–T043) es la sección 8 del notebook y desplaza a «Conclusiones» (9) y «Reproducibilidad» (10). Además
    del percentil mide `chunk_size` 400/700/1000: el orden fijo/semántica cambia con el tamaño; encabezados queda último.
  - El notebook se generó con un script desechable fuera del repositorio y se ejecutó con `nbconvert`. Las verificaciones de
    T047 se hicieron con `OLLAMA_BASE_URL`/`OLLAMA_EMBED_MODEL` en lugar de parar Docker.
  - Medido: 112 pruebas, cobertura 98 % del paquete, ejecución en frío del notebook 33 s, 544 textos embebidos, mismas
    métricas y posiciones en dos ejecuciones; Trivy y pip-audit sin hallazgos en `uv.lock`.
