# Contrato: `notebooks/results/chunking-results.json`

Resultado de la última ejecución, escrito por el notebook al terminar y versionado junto a él. Sirve para (1) que
`test_notebook.py` compruebe que el `.ipynb` guardado no está desactualizado, (2) comparar dos ejecuciones y (3) que una
historia posterior (F2) lea los números sin ejecutar el notebook. Lo escribe y lo lee `chunking_lab.report`.

## Formato

JSON UTF-8, sangría de 2 espacios, claves en el orden mostrado, estrategias siempre en el orden `fixed`, `headings`,
`semantic`, preguntas en el orden de `id`. Las puntuaciones no se guardan (cambian en decimales de una máquina a otra); sí
las posiciones, que es lo que debe reproducirse.

```json
{
  "meta": {
    "format_version": 1,
    "generated_at": "2026-11-10T22:15:00Z",
    "embed_model": "bge-m3",
    "embed_model_digest": "<sha256 del modelo según Ollama>",
    "corpus_documents": 24,
    "params": {
      "chunk_size": 1000,
      "chunk_overlap": 100,
      "top_k": 4,
      "breakpoint_percentile": 80,
      "min_chunk_chars": 200
    }
  },
  "summary": {
    "fixed":     { "chunk_count": 0, "size_min": 0, "size_mean": 0.0, "size_max": 0, "hit_rate": 0.0, "mrr": 0.0, "misses": 0 },
    "headings":  { "chunk_count": 0, "size_min": 0, "size_mean": 0.0, "size_max": 0, "hit_rate": 0.0, "mrr": 0.0, "misses": 0 },
    "semantic":  { "chunk_count": 0, "size_min": 0, "size_mean": 0.0, "size_max": 0, "hit_rate": 0.0, "mrr": 0.0, "misses": 0 }
  },
  "rows": [
    { "id": "Q01", "topic": "returns", "document": "returns-01-return-policy",
      "rank": { "fixed": 1, "headings": 1, "semantic": null } }
  ]
}
```

(Los ceros del ejemplo son marcadores; el archivo real contiene los valores medidos.)

## Reglas

| Campo | Regla |
|---|---|
| `meta.format_version` | entero; sube si cambia la forma del archivo |
| `meta.embed_model_digest` | cadena; vacía si Ollama no lo informa |
| `summary.*.hit_rate`, `mrr` | en [0, 1], redondeados a 4 decimales |
| `summary.*.size_mean` | redondeado a 1 decimal |
| `rows` | exactamente 20 elementos, `id` únicos y los mismos que `questions.toml` |
| `rows[].rank.<estrategia>` | entero en [1, `top_k`] o `null` (no encontrado) |
| Coherencia | `summary.*.misses` = número de `null`; `hit_rate` = (20 − `misses`) / 20; `mrr` = media de `1/rank` con 0 para `null` |

## Compatibilidad

Solo se añaden claves nuevas (y se sube `format_version` si el lector tiene que cambiar). Nadie en producción lo consume;
el contrato existe para las pruebas y para reutilizar los números.
