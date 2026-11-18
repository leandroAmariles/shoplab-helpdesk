# Quickstart: medir la recuperación

Guía para comprobar de principio a fin que LAB-28 funciona. Formato de los archivos en
[contracts/retrieval-csv.md](contracts/retrieval-csv.md); endpoint en [contracts/search-endpoint.md](contracts/search-endpoint.md);
tipos en [data-model.md](data-model.md).

## Requisitos

- Python 3.12 o superior y [`uv`](https://docs.astral.sh/uv/); JDK 21 y Maven para las pruebas del backend.
- Para la medición real (pasos 3 a 6): el stack local levantado con `docker compose -f infra/docker-compose.yml up` (backend,
  PostgreSQL con pgvector, Ollama con `bge-m3`) y los 24 documentos de `data/kb` **indexados** (estado «Indexado» en la
  pantalla de documentos o en `GET /api/documents`).
- Los pasos 1 y 2 no necesitan Docker, Ollama ni red.

## 1. Pruebas del backend (endpoint)

```bash
cd backend
mvn test -Dtest=KnowledgeSearchControllerTests,SearchChunksUseCaseTests -Dskip.srcclr=true --no-transfer-progress
```

**Resultado esperado**: en verde. Cubre 200 con resultados en orden, consulta vacía → 422, modelo de embeddings caído → 503 y
`embeddingModel()` del caso de uso.

## 2. Pruebas de `eval/`

```bash
cd eval
uv sync --frozen
uv run ruff check .
uv run pytest
```

**Resultado esperado**: `ruff` sin avisos y `pytest` en verde con cobertura ≥ 80 %. Incluye las métricas con casos fijados a mano
(SC-002), el recálculo del resumen desde el detalle (SC-003), el determinismo (SC-004) y los errores (SC-005) contra un servidor
HTTP de juguete.

## 3. Medir (US1 y US2)

```bash
uv run python -m shoplab_eval retrieval
```

**Resultado esperado** (código de salida `0`, menos de 1 minuto): tabla con una fila `all` y una por tema, hit rate@1/3/5,
recall@1/3/5 y MRR; «70 casos medidos, 20 excluidos»; el modelo `bge-m3`; y las rutas de `eval/results/retrieval-summary.csv`
y `eval/results/retrieval-detail.csv` (SC-001). Ábrelos en una hoja de cálculo (importación UTF-8): el detalle tiene 70 filas.

## 4. Comprobar a mano tres casos (SC-002)

En `retrieval-detail.csv` elige un caso con `first_hit_rank = 1`, uno con `first_hit_rank > 1` y uno con la celda vacía. Para
cada uno, calcula `hit@k`, `recall@k` y `reciprocal_rank` con las definiciones de
[data-model.md](data-model.md) a partir de `expected_documents` y `retrieved_documents`.

**Resultado esperado**: coinciden con las columnas del CSV. Recalcula también la media de una columna para un tema y compara con
el resumen (SC-003).

## 5. Determinismo (SC-004)

```bash
uv run python -m shoplab_eval retrieval --out results/a
uv run python -m shoplab_eval retrieval --out results/b
diff -r results/a results/b && echo "idénticos"
```

**Resultado esperado**: `idénticos`. Borra `results/a` y `results/b` después.

## 6. Errores visibles (US3, SC-005)

| Prueba | Cómo | Resultado esperado |
|---|---|---|
| Backend caído | parar el backend y ejecutar `retrieval` | código `1`, mensaje con la URL base, sin CSV nuevo |
| Índice incompleto | con un documento en `PENDING` o sin subir, ejecutar | código `1`, el mensaje lista los documentos que faltan, sin CSV |
| Dataset inválido | `--golden <copia con una pregunta duplicada>` | código `1`, el fallo `G-DUPLICATE` de LAB-27, sin CSV |
| k inválido | `--k 0` y `--k 3,x` | código `2` antes de consultar nada |
| Ollama caído | parar Ollama y ejecutar | código `1`, el id del primer caso y el estado 503, sin CSV |

## 7. Ver cómo cambia una métrica (SC-006)

Guarda un resultado (`--out results/antes`), reindexa con un troceado o modelo distinto (p. ej. cambiando
`SHOPLAB_RETRIEVAL_EMBEDDING_MODEL_NAME` y reindexando), mide de nuevo (`--out results/despues`) y compara los dos
`retrieval-summary.csv`. **Resultado esperado**: `embedding_model` distinto y el hit rate@k o el MRR cambian; el detalle señala
los casos que empeoraron o mejoraron.
