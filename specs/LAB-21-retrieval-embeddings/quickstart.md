# Quickstart: validar LAB-21

Guía de validación de extremo a extremo. Los detalles de puertos, SQL y configuración están en
[contracts/embedding-indexing.md](contracts/embedding-indexing.md) y [data-model.md](data-model.md).

## Prerrequisitos

- JDK 21 y Maven; Docker (Testcontainers para las pruebas y `docker compose` para la prueba manual).
- Rama `feature/LAB-21-retrieval-embeddings` con LAB-19 y LAB-20 ya en `main`.

## 1. Pruebas automáticas (sin servidor de modelos)

```bash
cd backend
mvn test -Dskip.srcclr=true --no-transfer-progress
mvn verify -Dskip.srcclr=true -Dcheckstyle.skip=true --no-transfer-progress   # JaCoCo >= 80 %
```

Resultado esperado:

- `EmbeddingFlowIntegrationTests`: documento Markdown de `data/kb` → `ingest` simulado → chunks `PENDING` → indexado con el
  doble determinista → `retrieval.document.index_status = 'INDEXED'` y todos los chunks con vector de 1 024 valores y
  `embedding_model` (SC-001, SC-009).
- Búsqueda: solo devuelve chunks con vector y del modelo configurado, aunque haya chunks de otro modelo y pendientes
  (SC-002).
- Reindexado: tras cambiar el modelo del doble, todos los chunks pasan al modelo nuevo, ningún documento mezcla modelos
  y `ingest` no recibe ninguna llamada (SC-003, SC-004).
- Fallo del modelo: el documento queda `ERROR` con causa, conserva sus chunks y los demás se indexan (SC-005).
- Segunda ejecución sobre documentos `INDEXED`: cero llamadas al modelo (SC-008).
- `RetrievalSchemaIntegrationTests`: `V4` aplicada; estados y restricciones del documento.
- `ModuleArchitectureTests` y `ModularityTests` siguen en verde (hexagonal y límites de módulo).

## 2. Prueba manual con Ollama real (opcional)

```bash
docker compose -f infra/docker-compose.yml up -d        # postgres, ollama (descarga bge-m3) e ingest
cd backend && mvn spring-boot:run
```

1. Ingerir un documento de `data/kb` con el caso de uso de LAB-20 (por ejemplo desde una prueba o consola de desarrollo).
   El documento aparece `PENDING` y, segundos después, `INDEXED`:

   ```sql
   SELECT source, index_status, index_error FROM retrieval.document;
   SELECT count(*) FILTER (WHERE embedding IS NULL) AS sin_vector, count(*) AS total FROM retrieval.chunk;
   ```

2. Cambiar de modelo: parar la app, exportar `OLLAMA_EMBED_MODEL=<otro modelo de 1 024 dimensiones>` y arrancar con
   `SHOPLAB_RETRIEVAL_EMBEDDING_REINDEX_ON_STARTUP=true`. Comprobar:

   ```sql
   SELECT embedding_model, count(*) FROM retrieval.chunk GROUP BY 1;   -- un solo modelo
   ```

3. Apagar Ollama y reingerir un documento: queda `ERROR` con la causa en `index_error`; al volver Ollama, reintentar lo
   deja `INDEXED`.

## 3. Comprobaciones de límites

- `ingest/` no cambia: `git diff main -- ingest` vacío.
- Ningún log contiene texto de chunks ni de consultas.
- `/actuator/prometheus` expone `retrieval_embedding_batch_seconds` y `retrieval_embedding_documents_total`.
