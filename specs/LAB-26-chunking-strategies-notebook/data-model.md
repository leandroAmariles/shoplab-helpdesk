# Data Model: Notebook comparativo de estrategias de chunking (LAB-26)

No hay base de datos: son tipos del paquete `chunking_lab` (dataclasses inmutables) y dos archivos de datos. Los formatos
de los archivos están en [contracts/](contracts/).

## ExperimentConfig

Todos los parámetros del experimento, declarados en una sola celda del notebook (FR-016).

| Campo | Tipo | Defecto | Regla |
|---|---|---|---|
| `chunk_size` | int | 1000 | ≥ 100; tamaño máximo común a las tres estrategias |
| `chunk_overlap` | int | 100 | ≥ 0 y < `chunk_size`; lo usan tamaño fijo y encabezados (como producción); la semántica no solapa |
| `top_k` | int | 4 | ≥ 1; igual a `SHOPLAB_CHAT_RAG_TOP_K` |
| `breakpoint_percentile` | int | 80 | 1–99; percentil de distancia a partir del cual la estrategia semántica corta |
| `min_chunk_chars` | int | 200 | 0 ≤ valor < `chunk_size`; la semántica une fragmentos menores que esto |
| `embed_model` | str | `OLLAMA_EMBED_MODEL` o `bge-m3` | no vacío |
| `ollama_base_url` | str | `OLLAMA_BASE_URL` o `http://localhost:11434` | URL http(s) |
| `embed_batch_size` | int | 32 | ≥ 1 |
| `use_cache` | bool | `True` | si es `False`, no lee ni escribe `.cache/` |
| `kb_dir`, `questions_path`, `results_path` | Path | rutas relativas al repositorio | resueltas desde la raíz, no desde el directorio de trabajo |

Una combinación inválida lanza `ValueError` con un mensaje que nombra el campo (mismas reglas de tamaño y solape que
`ChunkingConfig` de `ingest`).

## Document

Un documento Markdown del corpus.

| Campo | Tipo | Notas |
|---|---|---|
| `id` | str | nombre del archivo sin `.md` (p. ej. `returns-01-return-policy`); coincide con `data/kb/INDEX.md` |
| `topic` | str | prefijo del id: `payments`, `privacy`, `returns`, `shipping`, `warranty` |
| `raw` | str | contenido UTF-8 tal cual (con front matter) |
| `body` | str | `raw` sin el front matter; es lo que trocean tamaño fijo y semántica |

Se excluyen `INDEX.md` y `REVIEW.md`. El corpus esperado tiene 24 documentos y no se modifica nunca.

## Chunk

| Campo | Tipo | Notas |
|---|---|---|
| `strategy` | `"fixed"` \| `"headings"` \| `"semantic"` | enum `Strategy` |
| `document` | str | `Document.id` |
| `position` | int | consecutivo desde 0 dentro del documento |
| `section` | str | ruta de encabezados; solo la estrategia por encabezados la rellena, el resto `""` |
| `text` | str | no vacío; `len(text) ≤ chunk_size` en las tres estrategias |

## Unit (interno de la estrategia semántica)

| Campo | Tipo | Notas |
|---|---|---|
| `text` | str | frase, elemento de lista, tabla o párrafo con su encabezado antepuesto |
| `kind` | `"sentence"` \| `"list_item"` \| `"table"` | solo informativo |

Las distancias entre unidades consecutivas son un `numpy.ndarray` de longitud `n − 1`. Un documento de una sola unidad
produce un único grupo.

## Question

| Campo | Tipo | Regla |
|---|---|---|
| `id` | str | `Q01`…`Q20`, único |
| `topic` | str | uno de los cinco temas del corpus; exactamente 4 preguntas por tema |
| `question` | str | español, no vacío, termina en `?` |
| `document` | str | existe en el corpus y su `topic` coincide con el de la pregunta |
| `passage` | str | 40–300 caracteres tras normalizar espacios; aparece en `document` y **solo** en él |

Reglas del conjunto: 20 preguntas; al menos 5 pasajes de más de 100 caracteres (R7). `InvalidQuestionsError` agrega
todos los fallos con el `id` de la pregunta afectada.

## Retrieval (resultado de una consulta)

| Campo | Tipo | Notas |
|---|---|---|
| `question_id` | str | |
| `strategy` | `Strategy` | |
| `hits` | lista de `(Chunk, score)` | `top_k` elementos, ordenados por `score` descendente con desempate por `document` y `position` |
| `rank` | int \| None | posición (1 a `top_k`) del primer chunk correcto; `None` = no encontrado |

Invariante: `rank` solo es no nulo si `hits[rank − 1]` cumple «documento esperado y contiene el pasaje».

## StrategySummary

| Campo | Tipo | Notas |
|---|---|---|
| `strategy` | `Strategy` | |
| `chunk_count` | int | |
| `size_min`, `size_mean`, `size_max` | int, float, int | en caracteres |
| `hit_rate` | float | preguntas con `rank` / 20, en [0, 1] |
| `mrr` | float | media de `1/rank` (0 si `None`), en [0, 1] |
| `misses` | int | preguntas con `rank = None` |

## ResultsFile

Documento JSON `results/chunking-results.json` con `meta` (versión de formato, fecha, modelo y digest, parámetros),
`summary` (un `StrategySummary` por estrategia) y `rows` (20 filas: pregunta, documento esperado y el `rank` de cada
estrategia). Formato exacto en [contracts/results-format.md](contracts/results-format.md).

## Relaciones y ciclo de vida

```text
Document (24) ──trocea con──▶ Strategy (3) ──produce──▶ Chunk (N por estrategia)
Question (20) ─consulta─▶ índice de Chunk de una Strategy ─▶ Retrieval (60 = 20 × 3) ─▶ StrategySummary + ResultsFile
```

Sin estados ni transiciones: cada ejecución del notebook recalcula todo; solo la caché de embeddings sobrevive entre
ejecuciones y es opcional.
