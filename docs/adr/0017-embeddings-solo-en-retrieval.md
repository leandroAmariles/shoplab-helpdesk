# ADR-0017: Embeddings calculados solo en `retrieval`

- Estado: aceptado
- Fecha: 2026-10-30

## Contexto

`ingest` trocea los documentos y `retrieval` los guarda sin vector (ADR-0016, migración `V3`). Falta decidir quién calcula
los embeddings, cómo se sabe en qué estado está cada documento y cómo se cambia de modelo. Si cada servicio conociera el
modelo, cambiarlo exigiría tocar dos servicios a la vez; además, vectores de modelos distintos no son comparables entre
sí (ADR-0003), así que mezclarlos produciría resultados sin sentido y sin error visible.

## Decisión

- **Un solo lugar.** `retrieval` calcula todos los embeddings, al indexar los chunks y al buscar, con el `EmbeddingModel`
  de Spring AI (Ollama, `bge-m3` por defecto) detrás de un puerto de salida (`EmbeddingPort`). `ingest` no conoce ni
  configura ningún modelo.
- **Nombre del modelo desde una sola propiedad** (`spring.ai.ollama.embedding.options.model`, variable
  `OLLAMA_EMBED_MODEL`). Ese nombre se guarda en `chunk.embedding_model` y filtra la búsqueda: solo se comparan vectores
  del mismo modelo y con `embedding IS NOT NULL`.
- **Estado del documento** en `retrieval.document` (migración `V4`): `PENDING` (recién ingerido o reingerido),
  `INDEXED` (todos sus chunks tienen vector) y `ERROR` (con la causa en `index_error`, sin texto del documento).
- **Cálculo fuera de la transacción, guardado atómico por documento.** Los vectores se calculan por lotes sin conexión de
  base de datos retenida; después una transacción corta bloquea el documento, comprueba que su `updated_at` sigue siendo
  el que se leyó y guarda todos los vectores y el estado `INDEXED`. Si hubo una reingesta entre medias, el resultado se
  descarta. Un fallo del modelo marca `ERROR` y no toca los vectores previos.
- **Reindexar es el mismo algoritmo con otro alcance**: documentos con chunks de otro modelo (o sin vector) o todos. Un
  documento pasa entero al modelo nuevo de una vez, así que nunca mezcla modelos. Se dispara por configuración
  (`reindex-on-startup`: `false`, `true`, `force`) y por el caso de uso `ReindexDocumentsServicePort`; no llama a `ingest`.
- **Disparo sin acoplar la ingesta**: un evento en proceso tras guardar el documento y un barrido de pendientes al
  arrancar. Un fallo del modelo nunca impide guardar un documento ni arrancar el servicio. No hay reintento automático de
  los documentos en `ERROR`.
- **Una ejecución a la vez** con un cerrojo en memoria (hay una sola instancia hasta F5); la comprobación optimista
  mantiene los datos correctos aunque el cerrojo falle.
- **Búsqueda mínima** por similitud de coseno con el índice HNSW de LAB-19; filtros por metadatos, búsqueda híbrida,
  reordenado y citas son historias posteriores.

## Consecuencias

- Cambiar de modelo es cambiar una variable y reiniciar con `reindex-on-startup=true`; ningún cambio en `ingest` ni en
  código. La dimensión sigue fijada en `vector(1024)`: un modelo de otra dimensión exige una migración y un ADR nuevos.
- Durante un reindexado la búsqueda sigue funcionando, pero los documentos aún no reindexados dejan de aparecer
  temporalmente. Tras un cambio de modelo sin reindexar la búsqueda responde vacío y deja un aviso en el log.
- Los documentos en `ERROR` se reintentan de forma explícita (`indexPending(true)`) y la pantalla de administración
  (LAB-25) puede leer el estado y la causa tal cual.
- **Riesgo conocido**: el filtro por modelo se aplica después del recorrido HNSW, por lo que con varios modelos
  conviviendo una búsqueda puede devolver menos filas que el límite. Con el corpus actual (cientos de chunks) no
  importa; si crece se activará el escaneo iterativo de pgvector (`hnsw.iterative_scan`) o un índice parcial por modelo.
- **Al extraer `retrieval` (F5)** el cerrojo en memoria se sustituirá por uno en base de datos
  (`SELECT ... FOR UPDATE SKIP LOCKED`).
