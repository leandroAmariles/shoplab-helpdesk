# Modelo de datos: Métricas de retrieval

No hay base de datos nueva ni migraciones. Los tipos son los de la medición en `eval/` (Python, `dataclass(frozen=True)`) y
los dos DTO del endpoint. Formatos de salida en [contracts/retrieval-csv.md](contracts/retrieval-csv.md) y del endpoint en
[contracts/search-endpoint.md](contracts/search-endpoint.md).

## Entrada: caso medible

Se reutiliza `GoldenCase` de LAB-27 (`id`, `question`, `reference_answer`, `expected_documents`, `category`). Es **medible**
cuando `expected_documents` no está vacío; los demás se excluyen.

## Tipos de la medición (`shoplab_eval`)

| Tipo | Campos | Reglas |
|---|---|---|
| `SearchHit` | `source: str`, `score: float` | Un fragmento devuelto por la búsqueda; solo se conserva lo que la medición usa (el texto no se guarda) |
| `SearchResponse` | `model: str`, `hits: tuple[SearchHit, ...]` | `hits` en el orden recibido, sin reordenar |
| `IndexedDocument` | `name: str`, `status: str` | Un documento del catálogo (`GET /api/documents`) |
| `CaseMetrics` | `hit: dict[int, int]`, `recall: dict[int, float]`, `reciprocal_rank: float`, `first_hit_rank: int \| None` | Un valor por cada k pedido; `hit` es 0 o 1; todo entre 0 y 1 |
| `CaseResult` | `case: GoldenCase`, `retrieved: tuple[str, ...]`, `metrics: CaseMetrics` | `retrieved` son ids de documento distintos en orden de cercanía |
| `ScopeSummary` | `scope: str`, `cases: int`, `excluded: int`, `hit_rate: dict[int, float]`, `recall: dict[int, float]`, `mrr: float` | `scope` es `all` o una categoría; medias de los `CaseResult` del ámbito |
| `RetrievalReport` | `model: str`, `depth: int`, `ks: tuple[int, ...]`, `results: tuple[CaseResult, ...]`, `summaries: tuple[ScopeSummary, ...]` | Se construye solo con la medición completa; es lo único que escribe el CSV |

## Reglas de cálculo

Con `E` los documentos esperados (1 o 2), `D` la lista recuperada (distinta, en orden) y `r` la posición 1-indexada del primer
elemento de `D` que está en `E`:

- `hit@k = 1 si E ∩ D[:k] ≠ ∅, si no 0`
- `recall@k = |E ∩ D[:k]| / |E|`
- `reciprocal_rank = 1 / r` si existe `r`, si no `0` (sobre `D` completo, sin truncar a k)
- `first_hit_rank = r` o `None`

`D` se obtiene de los fragmentos recibidos así: se toma `Path(source).stem` de cada uno y se conserva la primera aparición de
cada id (la del fragmento más cercano). Si hay menos documentos distintos que k, `D[:k]` es simplemente más corta.

**Agregación**: cada valor del resumen es la media aritmética del valor por caso sobre los casos del ámbito. `all` agrupa todos
los casos medibles; cada categoría (`payments`, `privacy`, `returns`, `shipping`, `warranty`) agrupa los suyos. `off_topic` no
tiene casos medibles y no genera fila. `excluded` de un ámbito es el número de casos de esa categoría sin documentos esperados
(en `all`, el total de excluidos).

## Entidad del backend: respuesta de la búsqueda

| Tipo | Campos | Origen |
|---|---|---|
| `KnowledgeSearchResponse` | `model: String`, `results: List<KnowledgeSearchHitResponse>` | `model` de `SearchChunksServicePort.embeddingModel()` |
| `KnowledgeSearchHitResponse` | `source`, `title`, `section`, `position: int`, `score: double`, `text` | `SearchResult` sin `chunkId` ni `documentId` (identificadores internos) |

El mapeo se hace con un mapper explícito (`KnowledgeSearchResponseMapper`), como `DocumentResponseMapper`.

## Estados y transiciones

No hay estado persistente. La medición es una función del índice, la configuración de búsqueda y el dataset: un flujo lineal
**validar dataset → comprobar índice → buscar por caso → calcular → escribir**, que se interrumpe con `MeasurementError` en el
primer paso que falle sin haber escrito nada.
