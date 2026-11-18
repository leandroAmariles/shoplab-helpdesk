# Contrato: comando `retrieval` y sus dos CSV

Es lo que LAB-30 (comparación con línea base) y LAB-31 (persistencia) leerán sin transformar (FR-018). Definiciones de las
métricas en [data-model.md](../data-model.md); decisiones de formato en [research.md](../research.md) (R7).

## Comando

```bash
cd eval
uv run python -m shoplab_eval retrieval [--k 1,3,5] [--depth 50] [--base-url http://localhost:8080] \
    [--out results] [--golden <ruta>] [--kb <ruta>] [--timeout 10]
```

| Opción | Por defecto | Descripción |
|---|---|---|
| `--k` | `1,3,5` | Valores de k separados por comas; enteros positivos, sin repetir, no mayores que `--depth` |
| `--depth` | `50` | Fragmentos que se piden a la búsqueda por pregunta (1–50). Ver research R4 |
| `--base-url` | `$SHOPLAB_EVAL_BASE_URL` o `http://localhost:8080` | Backend que sirve la búsqueda |
| `--out` | `eval/results/` | Carpeta de salida (se crea si falta) |
| `--golden`, `--kb` | los de `validate` | Dataset y corpus |
| `--timeout` | `10` | Segundos máximos por petición |

**Salida por pantalla**: tabla legible del resumen (ámbito, casos, hit rate@k, recall@k, MRR), número de casos excluidos, modelo
de embeddings y las rutas de los dos archivos escritos.

**Código de salida**: `0` medición completa y CSV escritos; `1` la medición no es válida (cualquier `MeasurementError` o
`InvalidGoldenError`); `2` uso incorrecto (opción desconocida, k inválido). Con `1` y `2` **no se escribe ningún CSV**.

## `retrieval-summary.csv`

UTF-8 sin BOM, `\n`, cabecera. Una fila por ámbito: `all` primero y después cada categoría con casos medibles en el orden
`payments, privacy, returns, shipping, warranty`.

Columnas (con los k por defecto; hay un grupo `hit_rate@k` y `recall@k` por cada k pedido, en el orden dado):

| Columna | Significado |
|---|---|
| `scope` | `all` o la categoría |
| `cases` | Casos medidos en el ámbito (con documentos esperados) |
| `excluded_cases` | Casos del ámbito sin documentos esperados y por eso no medidos |
| `embedding_model` | Modelo con el que está indexada la base y con el que se buscó |
| `search_depth` | Fragmentos pedidos por pregunta |
| `hit_rate@1`, `hit_rate@3`, `hit_rate@5` | Proporción de casos con algún documento esperado entre los k primeros |
| `recall@1`, `recall@3`, `recall@5` | Media de la proporción de documentos esperados entre los k primeros |
| `mrr` | Media del inverso de la posición del primer documento esperado (0 si no aparece) |

Los números llevan cuatro decimales y punto decimal (`0.8571`). Ejemplo:

```csv
scope,cases,excluded_cases,embedding_model,search_depth,hit_rate@1,hit_rate@3,hit_rate@5,recall@1,recall@3,recall@5,mrr
all,70,20,bge-m3,50,0.7143,0.8857,0.9286,0.6000,0.8143,0.9000,0.8110
payments,14,2,bge-m3,50,0.7143,0.9286,1.0000,0.6071,0.8571,1.0000,0.8333
```

(Los valores son ilustrativos del formato, no un resultado.)

## `retrieval-detail.csv`

UTF-8 sin BOM, `\n`, cabecera. Una fila por caso medible, en el orden del dataset.

| Columna | Significado |
|---|---|
| `id` | Identificador del caso (`G001`) |
| `category` | Categoría del caso |
| `question` | Pregunta, con comillas si contiene comas |
| `expected_documents` | Ids esperados, separados por `\|` |
| `retrieved_documents` | Ids de documento distintos recuperados en orden de cercanía, separados por `\|` |
| `first_hit_rank` | Posición (desde 1) del primer documento esperado en `retrieved_documents`; vacía si no aparece |
| `hit@1`, `hit@3`, `hit@5` | `1` o `0` por caso |
| `recall@1`, `recall@3`, `recall@5` | Proporción de documentos esperados entre los k primeros (`0.0000`–`1.0000`) |
| `reciprocal_rank` | `1/first_hit_rank`, o `0.0000` si no aparece |

```csv
id,category,question,expected_documents,retrieved_documents,first_hit_rank,hit@1,hit@3,hit@5,recall@1,recall@3,recall@5,reciprocal_rank
G001,payments,"Mi boleto venció sin que lo pagara, ¿qué puedo hacer?",payments-03-boleto-and-vouchers,payments-03-boleto-and-vouchers|payments-04-payment-issues|returns-03-refunds,1,1,1,1,1.0000,1.0000,1.0000,1.0000
```

## Invariantes (las comprueban las pruebas)

1. Cada valor del resumen es la media de la columna correspondiente del detalle, filtrando por `scope` (SC-003).
2. `cases` de `all` es el número de filas del detalle; `cases + excluded_cases` de `all` es el tamaño del dataset.
3. Con las mismas entradas dos ejecuciones producen archivos idénticos byte a byte (SC-004): sin marcas de tiempo, sin rutas.
4. No aparece texto de fragmentos ni de respuestas de referencia en ningún archivo.
5. Un caso con dos documentos esperados y un solo acierto entre los k primeros tiene `hit@k = 1` y `recall@k = 0.5000`.
6. Las tildes, eñes y comas de las preguntas se conservan y no desplazan columnas (lectura con `csv` estándar).

## Errores del comando

Cada uno termina con código `1`, sin CSV, y con un mensaje que nombra la causa (research R8).

| Situación | Mensaje debe incluir |
|---|---|
| Dataset inválido | los fallos de LAB-27 (`G-…`), uno por línea |
| Backend inalcanzable o sin respuesta | la URL base y la causa; cómo levantarlo |
| Documentos esperados sin indexar | los ids que faltan o están en `PENDING`/`ERROR`, y que se indexen o reindexen |
| Una búsqueda falla | el id del caso y el estado HTTP o la causa |
| El modelo de embeddings cambia durante la ejecución | los dos nombres y que se vuelva a ejecutar |
| `--k` o `--depth` inválidos | el valor rechazado y el rango aceptado |
