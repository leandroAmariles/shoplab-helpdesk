# Contrato: esquema `retrieval`

Interfaz que el módulo `retrieval` ofrece a sus propias capas (y que ningún otro módulo usa directamente). Es el
contrato verificable de la migración `V2`.

## Objetos garantizados

- Tablas `retrieval.document` y `retrieval.chunk` con las columnas, tipos y restricciones de
  [data-model.md](../data-model.md).
- Índice HNSW `chunk_embedding_hnsw_idx` sobre `chunk.embedding` con `vector_cosine_ops`.
- Índice GIN `chunk_metadata_gin_idx` sobre `chunk.metadata` con `jsonb_path_ops`.
- Historial propio: `retrieval.flyway_schema_history` con las versiones `1` y `2` correctas.

## Rechazos garantizados por la base de datos

| Entrada | Resultado |
|---|---|
| `chunk.embedding` con dimensión distinta de 1024 | error de dimensión del tipo `vector` |
| `chunk` sin `embedding_model` o con texto en blanco | violación de `NOT NULL` / `CHECK` |
| `chunk.content` vacío o en blanco | violación de `CHECK` |
| `chunk` sin `document_id` o con un documento inexistente | violación de `NOT NULL` / clave foránea |
| dos chunks con el mismo `(document_id, chunk_index)` | violación de unicidad |
| `metadata` que no es un objeto JSON (array, escalar, nulo) | violación de `NOT NULL` / `CHECK` |
| dos documentos con el mismo `source` | violación de unicidad |

## Consultas que el esquema soporta

```sql
-- k vecinos más cercanos por coseno (usa chunk_embedding_hnsw_idx)
SELECT id, document_id, content FROM retrieval.chunk ORDER BY embedding <=> :query LIMIT :k;

-- filtro por atributo de metadatos (usa chunk_metadata_gin_idx)
SELECT id FROM retrieval.chunk WHERE metadata @> '{"topic": "returns"}';
```

Son ejemplos del contrato, no código de la historia: la búsqueda llega con la historia de recuperación.

## Restricciones de propiedad

- Solo el módulo `retrieval` ejecuta SQL sobre `retrieval.*` (S2 del lint de LAB-105 lo verifica en fuentes y
  migraciones; Modulith y ArchUnit verifican las dependencias entre módulos).
- La migración no declara claves foráneas hacia otros esquemas ni nombres calificados con el esquema de otro módulo.
  Califica con `public.` solo el tipo y la clase de operadores de la extensión de vectores.
- Los demás módulos guardan, como mucho, el `id` (uuid) de un documento o chunk.
