# Contrato: `GET /api/knowledge/search`

Búsqueda de solo lectura que `retrieval` publica por HTTP. Es la misma que consume el chat a través de `KnowledgeSearchApi`:
embebe la consulta con el modelo configurado y devuelve los fragmentos más cercanos entre los indexados con ese modelo. La
usa `eval/` para medir la recuperación (LAB-28); no aplica el umbral ni la selección del chat (ADR-0018).

## Petición

`GET /api/knowledge/search?query=<texto>&limit=<n>`

| Parámetro | Obligatorio | Descripción |
|---|---|---|
| `query` | sí | Texto a buscar. Vacío o solo espacios: error 422 sin llamar al modelo de embeddings |
| `limit` | no | Número de **fragmentos** a devolver. Sin valor usa `shoplab.retrieval.embedding.search-limit` (5). El caso de uso lo acota a 1–50 |

Sin cuerpo. Sin cabeceras obligatorias; si llega `X-Request-Id` se propaga como en el resto de la API.

## Respuesta 200

```json
{
  "model": "bge-m3",
  "results": [
    {
      "source": "payments-03-boleto-and-vouchers.md",
      "title": "Boleto y vouchers",
      "section": "Boleto > Vencimiento",
      "position": 2,
      "score": 0.8123,
      "text": "Si el boleto vence sin pago, el pedido pasa a cancelado…"
    }
  ]
}
```

| Campo | Significado |
|---|---|
| `model` | Modelo de embeddings con el que se buscó; solo se comparan los vectores de ese modelo (ADR-0017) |
| `results` | Fragmentos de **más cercano a más lejano**. Lista vacía si ninguno califica (p. ej. nada indexado con ese modelo) |
| `results[].source` | Nombre del archivo del documento (con extensión). El id de documento del dataset es este nombre sin extensión |
| `results[].title` | Título legible del documento |
| `results[].section` | Ruta de encabezados del fragmento; vacía si no hay |
| `results[].position` | Posición del fragmento en el documento |
| `results[].score` | Similitud coseno con la consulta; cuanto mayor, más cercano |
| `results[].text` | Texto del fragmento |

El orden es el que devuelve la búsqueda y **no se reordena** en ningún punto.

## Errores

Los mapea solo `GlobalExceptionHandler`; el controlador no construye respuestas de error (guía de desarrollo, sección 6).

| Estado | Código | Cuándo |
|---|---|---|
| 422 | `SLHD_RETR_00009` (`SEARCH_QUERY_INVALID`) | `query` ausente, vacía o solo espacios |
| 503 | `SLHD_RETR_00006` (`EMBEDDING_SERVICE_UNAVAILABLE`) | El modelo de embeddings no responde |
| 502 | `SLHD_RETR_00007` / `SLHD_RETR_00008` | El modelo falla o devuelve una dimensión distinta de la de la columna |
| 429 | `SLHD_RETR_00010` (`EMBEDDING_BUDGET_EXCEEDED`) | Se superó el presupuesto de embeddings configurado |

## Reglas

- Solo lectura: no escribe, no reindexa y no cambia estado.
- Observabilidad: pasa por `SearchChunksUseCase`, que ya registra la métrica `retrieval.search`; no se escribe en el registro
  la consulta ni el texto de los fragmentos.
- Sin autenticación hoy, como el resto de `/api/**`; ver research R2 y ADR-0024.
