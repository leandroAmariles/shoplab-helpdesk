# ADR-0016: Troceado por encabezados en `ingest` y contrato síncrono con `retrieval`

- Estado: aceptado
- Fecha: 2026-10-27

## Contexto

La base de conocimiento (ADR-0002) necesita que cada documento, PDF o Markdown, llegue a `retrieval.chunk` troceado
en fragmentos que se puedan buscar y citar. ADR-0015 fija quién es dueño de qué: `retrieval` es el único que lee y
escribe el esquema `retrieval`, y `ingest` (Python, sin estado) no tiene credenciales de base de datos. Falta decidir
cómo se trocea, qué viaja entre los dos servicios y cómo se versiona ese contrato. El tamaño de los chunks y el
solape condicionan la calidad de la recuperación (Constitución V), así que deben poder cambiarse y medirse.

## Decisión

- **`ingest` expone `POST /ingest`**: recibe el documento como `multipart/form-data` (parte `file`, más `chunk_size` y
  `chunk_overlap` opcionales) y devuelve `{document, chunks[{text, metadata{document, section, position}}]}`. No guarda
  nada: dada la misma entrada y configuración, da la misma respuesta.
- **Troceado por encabezados.** Un chunk nunca mezcla dos secciones; la sección es la ruta de encabezados
  (`Política > Plazos`). Una sección más larga que `chunk_size` se divide en párrafos, líneas, frases, palabras y, como
  último recurso, caracteres. Los chunks consecutivos de una sección repiten los últimos `chunk_overlap` caracteres del
  anterior y el tamaño máximo incluye ese solape. Se mide en caracteres porque el tokenizador depende del modelo de
  embeddings. Valores por defecto: 1 000 y 100, configurables (`INGEST_*`).
- **PDF:** los títulos se reconocen por el tamaño de letra (el más frecuente es el cuerpo; una línea corta mayor en
  al menos 1 pt es título y el orden de los tamaños da el nivel). Sin títulos reconocibles se trocea por tamaño. No hay
  OCR.
- **Errores con código estable** (`UNSUPPORTED_FORMAT` 415, `DOCUMENT_TOO_LARGE` 413, `EMPTY_DOCUMENT`,
  `NO_EXTRACTABLE_TEXT`, `INVALID_DOCUMENT`, `INVALID_CHUNKING_CONFIG` 422, `INTERNAL_ERROR` 500), de modo que
  `retrieval` distingue un documento rechazado (error de negocio) de un servicio que falla (error técnico).
- **`retrieval` llama a `ingest` de forma síncrona** por un puerto (`DocumentParserPort`) con `WebClient`, sin
  reintentos automáticos, y guarda el resultado en una transacción: o documento y chunks, o nada. Reingerir el mismo
  nombre conserva el `id` del documento y reemplaza sus chunks. Los chunks se guardan sin vector (`V3` hace opcionales
  `embedding` y `embedding_model`, siempre juntos) y la historia de embeddings los completa.
- **Contrato:** FastAPI genera el esquema OpenAPI y se versiona en `ingest/openapi.json`. La CI de `ingest` falla si el
  servicio lo contradice y la del backend falla si el cliente de `retrieval` lo contradice.

Alternativas descartadas:

- **Troceo semántico con embeddings en `ingest`**: mejor corte en teoría, pero `ingest` necesitaría el modelo, y los
  embeddings se calculan en un solo sitio, `retrieval` (LAB-21).
- **Cola o evento entre `ingest` y `retrieval`**: desacopla mejor, pero añade mensajería antes de F4 (ADR-0001) para
  documentos que se procesan en segundos.
- **JSON con el documento en base64**: +33 % de tamaño y de memoria sin ventaja sobre multipart.
- **Que `ingest` escriba en la base de datos**: rompe el único dueño del esquema (ADR-0015).
- **`PyMuPDF`, `unstructured` o `docling`** para el PDF: licencia AGPL o cientos de MB de dependencias para un problema
  que aquí se resuelve con reglas sobre tamaños de letra.

## Consecuencias

- Ingerir un documento exige que `ingest` esté disponible; si no lo está, no se guarda nada y el administrador puede
  reintentar (503 o 502 según el caso).
- Hasta LAB-21 hay chunks sin vector: no participan en la búsqueda, que debe filtrar `embedding IS NOT NULL`.
- Cambiar el tamaño, el solape o la unidad de medida de los chunks es un cambio de estrategia de recuperación: desde F2
  exige el resultado de la evaluación (Constitución V).
- Los PDF con títulos solo en negrita y del mismo tamaño que el cuerpo, de varias columnas o escaneados no se trocean por
  encabezados; se documenta en `ingest/README.md`.
- Cuando `retrieval` se extraiga como servicio (F5), `ingest/openapi.json` pasa a `shoplab-contracts` (ADR-0015).
- Las métricas de `ingest` (`/metrics`) se recogen en Prometheus; las trazas distribuidas se añadirán a backend e
  `ingest` a la vez, en una historia de observabilidad.
