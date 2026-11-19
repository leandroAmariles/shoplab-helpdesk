# ADR-0024: Métricas de recuperación por HTTP

- Estado: aceptado
- Fecha: 2026-11-17
- Complementa a: ADR-0015 (límites de contexto), ADR-0017 (embeddings solo en `retrieval`) y ADR-0018 (recuperación del contexto en el chat); no los sustituye

## Contexto

LAB-28 pide medir la recuperación por separado de la respuesta: hit rate@k, MRR y recall@k contra los documentos esperados
del dataset golden (LAB-27), con el resultado en CSV. La Constitución (principio V) exige que los cambios de chunking,
embeddings o recuperación lleven un resultado de evaluación, y LAB-30 lo automatizará en CI.

- La búsqueda real embebe la consulta con el modelo configurado, que hoy se alcanza a través del gateway (ADR-0023), y
  compara solo con los vectores de ese modelo (ADR-0017). Solo `retrieval` conoce esa consulta; la publica a otros módulos
  como `KnowledgeSearchApi`, una API interna sin HTTP.
- La evaluación vive en `eval/` (Python) y no puede importar Java.
- ADR-0015: ningún módulo ni proceso toca el esquema de otro; `eval/` no puede consultar pgvector.
- El dataset lista **documentos** esperados, no fragmentos, para que el troceado pueda cambiar sin invalidarlo.

## Decisión

1. **`retrieval` publica su búsqueda por HTTP en modo lectura**: `GET /api/knowledge/search?query=&limit=`. Usa el mismo
   caso de uso (`SearchChunksServicePort`) que consume el chat, devuelve los fragmentos en el orden de la búsqueda y el modelo
   de embeddings con el que se buscó, y no aplica el umbral ni la selección del chat (ADR-0018). Los errores son los tipados de
   siempre, mapeados solo por `GlobalExceptionHandler`.
2. **La medición vive en `eval/`** (`python -m shoplab_eval retrieval`) y llega a la búsqueda real solo por ese endpoint, sin
   reimplementarla. Comprueba antes, con `GET /api/documents`, que los documentos esperados están indexados.
3. **Se mide a nivel de documento.** Los fragmentos devueltos se reducen a documentos distintos (id = nombre del archivo sin
   extensión), cada uno en la posición de su fragmento más cercano. Se piden 50 fragmentos por pregunta (el máximo del
   caso de uso) para que k cuente documentos y no se quede corto.
4. **Resultado determinista en CSV** (resumen y detalle): sin marcas de tiempo, con cuatro decimales fijos y orden estable.
   Un fallo del entorno (búsqueda caída, índice incompleto) termina con error y sin CSV; un documento esperado que no aparece
   es un cero, no un error.

Alternativas descartadas:

- **Consultar PostgreSQL desde Python**: obliga a repetir el embedding de la consulta y la SQL de similitud, se desvía en
  silencio de la búsqueda real y rompe ADR-0015.
- **Un ejecutor en Java** (prueba de integración o `CommandLineRunner`): usaría la búsqueda real, pero no se lanza con un
  único comando desde `eval/` y cruza dos lenguajes y dos procesos para algo que un `GET` resuelve.
- **Medir a través del chat y leer las citas**: mezcla la búsqueda con el umbral, el presupuesto de contexto y el modelo de
  lenguaje, que es lo que la historia quiere separar.
- **Una bandera de configuración o un perfil para activar el endpoint**: añadiría una opción que nadie pidió y impediría
  medir contra el ambiente desplegado, que LAB-30 puede necesitar.

## Consecuencias

- **Endpoint sin autenticación**, como el resto de `/api/**` (el backend no tiene capa de seguridad todavía). Devuelve el
  corpus sintético de políticas y FAQs que el chat ya muestra, y no escribe nada. Riesgo aceptado: cuando exista autenticación,
  el endpoint hereda su regla como el resto de la API, y habrá que dar acceso a quien ejecute la evaluación.
- `SearchChunksServicePort` gana `embeddingModel()` para registrar con qué modelo se midió; así dos resultados con modelos
  distintos no se comparan sin darse cuenta.
- **hit@k por documento es más indulgente** que lo que consume el chat (cuatro fragmentos, tras el umbral): varios fragmentos
  del mismo documento ocupan una sola posición. La calidad de la respuesta la mide LAB-29; la selección del chat no se mide aquí.
- El índice HNSW de pgvector es aproximado, así que el orden podría variar entre construcciones del índice. Con 24 documentos
  y unos pocos cientos de fragmentos se espera estable; la garantía de determinismo es la de una misma base indexada. Si
  apareciera ruido, LAB-30 puede fijar `hnsw.ef_search` o medir con tolerancia.
- Cualquier cambio futuro de la búsqueda (híbrida con `tsvector`, otro modelo, otro troceado) se refleja en las métricas sin
  tocar `eval/`.
- Los CSV son resultados locales y no se versionan; la línea base y los umbrales de bloqueo son de LAB-30.
