# Contrato interno: caso de uso de ingesta en `retrieval`

Sin endpoint REST en esta historia (la subida del administrador es LAB-25, que lo añadirá delante de este puerto).

## Puerto de entrada

`IngestDocumentServicePort` (`retrieval/application/port/in`), implementado por `IngestDocumentUseCase` (`application/usecase`)

```text
Mono<IngestedDocument> ingest(IngestDocumentCommand command)
```

- `IngestDocumentCommand(name, content)`: `name` no vacío; `content` no vacío.
- `IngestedDocument(documentId, source, title, chunkCount)`.
- Errores (todos por `Mono.error`, tipados y traducidos solo por `GlobalExceptionHandler`):

| Situación | Excepción | `ResponseCodeEnum` |
|---|---|---|
| `ingest` rechaza por formato | `BusinessException` | `DOCUMENT_UNSUPPORTED_FORMAT` |
| `ingest` rechaza por tamaño | `BusinessException` | `DOCUMENT_TOO_LARGE` |
| `ingest` rechaza por contenido o configuración | `BusinessException` | `DOCUMENT_INVALID` |
| sin conexión o tiempo de espera | `TechnicalException` | `INGEST_SERVICE_UNAVAILABLE` |
| 5xx o respuesta ilegible | `TechnicalException` | `INGEST_SERVICE_FAILED` |
| fallo de base de datos | `TechnicalException` | `DATABASE_UNAVAILABLE` (existente) |

## Puertos de salida

- `DocumentParserPort` — `Mono<ParsedDocument> parse(String name, byte[] content)`; adaptador `IngestWebClientAdapter`
  (`infrastructure/out/ingest`).
- `KnowledgeBasePort` — `Mono<IngestedDocument> replaceDocument(ParsedDocument parsed)`; adaptador
  `JdbcKnowledgeBaseAdapter` (`infrastructure/out/persistence`), transacción única y `BlockingCalls`.

## Garantías

1. Si `parse` falla, `replaceDocument` no se invoca: no se escribe nada.
2. `replaceDocument` es atómico: documento y chunks juntos o ninguno.
3. Reingerir el mismo `name` conserva `documentId` y reemplaza todos los chunks.
4. Los chunks se guardan con `embedding` y `embedding_model` nulos.
5. Ningún otro módulo accede a `retrieval.*`; los demás usarán este puerto (o el evento/consulta que defina su historia).

## Configuración

| Propiedad | Variable | Defecto |
|---|---|---|
| `shoplab.retrieval.ingest.base-url` | `SHOPLAB_RETRIEVAL_INGEST_BASE_URL` | `http://localhost:8000` |
| `shoplab.retrieval.ingest.timeout` | `SHOPLAB_RETRIEVAL_INGEST_TIMEOUT` | `30s` |
| `shoplab.retrieval.ingest.max-response-bytes` | `SHOPLAB_RETRIEVAL_INGEST_MAX_RESPONSE_BYTES` | `33554432` |
