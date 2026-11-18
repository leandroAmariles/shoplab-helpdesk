# Data Model: Dataset golden de preguntas y respuestas

**Sin base de datos ni migraciones.** El dataset es un archivo; todos los tipos viven en memoria dentro de
`eval/src/shoplab_eval/`. El corpus (`data/kb`) se lee, no se modifica. Reglas completas y códigos en
[contracts/golden-format.md](contracts/golden-format.md).

## Tipos

### `GoldenCase` (inmutable)

Un caso de referencia: la unidad que miden LAB-28 a LAB-30.

| Campo | Tipo | Reglas |
|---|---|---|
| `id` | `str` | `G` + tres dígitos (`G001`…`G999`); único; estable: no cambia si el caso se reordena o se edita |
| `question` | `str` | español, 10–300 caracteres, autocontenida; no repetida en el conjunto (tras normalizar) ni copia literal de una frase del corpus |
| `reference_answer` | `str` | 20–800 caracteres; verificable con los documentos esperados; en un caso sin respuesta, reconoce la falta de información y no contiene dígitos |
| `expected_documents` | `tuple[str, ...]` | 0, 1 o 2 ids de documento, sin repetir y existentes en el corpus; vacío ⇔ caso sin respuesta |
| `category` | `str` | uno de `payments`, `privacy`, `returns`, `shipping`, `warranty`, `off_topic` |

Propiedades derivadas (no se guardan):

- `answerable`: `expected_documents` no está vacío.
- `kind`: `"answered"` (con respuesta), `"unanswered_topic"` (sin respuesta, categoría de tema) o `"off_topic"`.
- `is_multi_document`: tiene dos documentos esperados.

### `CorpusDocument` (inmutable)

Un documento de la base de conocimiento, leído de `data/kb/*.md` (se excluyen `INDEX.md` y `REVIEW.md`).

| Campo | Tipo | Origen |
|---|---|---|
| `id` | `str` | nombre del archivo sin `.md` |
| `topic` | `str` | `topic` del front matter; uno de los cinco temas |
| `text` | `str` | cuerpo normalizado (espacios colapsados) para la regla de «frase copiada» |

### `Issue` (inmutable) e `InvalidGoldenError`

| Campo | Tipo | Descripción |
|---|---|---|
| `code` | `str` | código de regla, p. ej. `G-DOC-MISSING` (tabla en el contrato) |
| `case_id` | `str` | `id` del caso afectado, o `SET` si la regla es del conjunto, o `LINE n` si la línea no se pudo leer |
| `message` | `str` | texto en español, accionable |

`InvalidGoldenError` agrega **todas** las `Issue` (nunca solo la primera) y su mensaje las lista una por línea, ordenadas
por línea del archivo y luego por código, para que dos ejecuciones sobre el mismo archivo den el mismo texto.

### `GoldenRules` (constantes, `constants.py`)

Los umbrales de la especificación en un solo lugar. Cambiarlos es una decisión de la spec, no del código.

| Constante | Valor | Origen |
|---|---|---|
| `MIN_CASES` / `MAX_CASES` | 80 / 100 | FR-001 |
| `MIN_ANSWERED_PER_TOPIC` | 12 | FR-007 |
| `MIN_UNANSWERED` | 12 | FR-009 |
| `UNANSWERED_MIN_SHARE` / `UNANSWERED_MAX_SHARE` | 0,15 / 0,25 | FR-009 |
| `MIN_UNANSWERED_TOPIC` / `MIN_OFF_TOPIC` | 5 / 5 | FR-010 |
| `MIN_MULTI_DOCUMENT` | 8 | FR-008 |
| `QUESTION_LENGTH` | 10–300 | plan R5 |
| `ANSWER_LENGTH` | 20–800 | plan R5 |
| `MAX_EXPECTED_DOCUMENTS` | 2 | spec, edge «dos documentos» |

### `GoldenSummary` (inmutable)

Resultado de la validación correcta, para el comando y para las pruebas: total de casos; conteo por categoría; conteo por
tipo (`answered`, `unanswered_topic`, `off_topic`); número de casos de dos documentos; documentos del corpus sin caso (debe
estar vacío); y el porcentaje informativo de preguntas con poco solapamiento léxico con sus documentos (plan R5).

## Relaciones

```text
GoldenCase 1 ──────── 0..2 ──▶ CorpusDocument      (expected_documents, por id)
GoldenCase  n ──────── 1  ──▶ Category              (category; off_topic ⇒ 0 documentos)
CorpusDocument n ──── 1  ──▶ topic                  (front matter de data/kb)
```

Coherencia categoría–documentos: en un caso con respuesta, `category` es el `topic` de al menos uno de sus documentos
esperados; en uno de dos documentos de distinto tema basta con que coincida con uno de los dos.

## Validación en tres capas

1. **Lectura** (`load_golden`): decodifica cada línea no vacía como un objeto JSON; un fallo es una `Issue` `LINE n` y no
   detiene la lectura de las demás líneas.
2. **Caso** (`validation.py`): reglas que miran un solo caso y, para los documentos, el corpus.
3. **Conjunto** (`validation.py`): conteos, reparto, duplicados y cobertura del corpus.

No hay estados ni transiciones: el archivo es válido o no lo es.

## Composición objetivo (90 casos)

| Tema | Con respuesta | Sin respuesta del tema | Total |
|---|---|---|---|
| `payments` | 14 | 3 | 17 |
| `privacy` | 14 | 2 | 16 |
| `returns` | 14 | 3 | 17 |
| `shipping` | 14 | 2 | 16 |
| `warranty` | 14 | 2 | 16 |
| `off_topic` | 0 | 8 | 8 |
| **Total** | **70** | **20** (22 %) | **90** |

De los 70 con respuesta, al menos 8 combinan dos documentos. La tabla es un objetivo de autoría; la validación solo exige
los mínimos y los rangos de la spec (por eso 14 y 90 no son umbrales).
