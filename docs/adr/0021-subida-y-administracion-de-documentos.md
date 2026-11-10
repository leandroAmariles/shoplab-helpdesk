# ADR-0021: Subida y administración de documentos desde la aplicación

- Estado: aceptado
- Fecha: 2026-11-10

## Contexto

Hasta LAB-24 un documento solo llegaba a la base de conocimiento llamando al caso de uso de ingesta (LAB-20) desde
código o pruebas; los embeddings se calculan después, por evento (LAB-21, ADR-0017). LAB-25 pide que un administrador
suba PDF y Markdown desde una pantalla, vea el progreso del envío y una lista con el estado de cada documento
(pendiente, indexado, error) y su número de chunks. Hay cinco decisiones con alternativas reales:

- **Dónde vive la API** y cómo se lee el archivo sin bloquear el hilo reactivo (ADR-0011).
- **Qué límite de tamaño** se aplica y dónde, incluido el Ingress.
- **Cómo se sabe si una subida reemplazó a un documento** del mismo nombre.
- **Cómo se entera la pantalla** de que un documento terminó de indexarse.
- **Qué control de acceso** tiene la operación, sin autenticación todavía.

## Decisión

- **Dos operaciones REST en `retrieval`**: `POST /api/documents` (multipart, parte `file`) y `GET /api/documents`, en
  un `DocumentController` que solo arma el comando y llama a los puertos de entrada. Se descarta un módulo `admin`:
  tendría que leer tablas de `retrieval` (ADR-0015) sin aportar nada en F1. El listado va detrás de un puerto de
  lectura propio (`DocumentCatalogPort`), separado del de escritura (`KnowledgeBasePort`).
- **El archivo se lee en memoria con límite**: `FilePart` + `DataBufferUtils.join(contenido, max-bytes)`, liberando
  los buffers. El lector multipart guarda la parte entera en memoria (`spring.webflux.multipart.max-in-memory-size`) para
  no escribir temporales, con un tope duro un poco por encima del límite. Ni el controlador ni la ingesta tocan disco.
  `ingest` sigue decidiendo formatos y contenido; el backend no duplica sus reglas.
- **Límite de 10 MiB** (`shoplab.retrieval.upload.max-bytes`), el mismo que `ingest`. Superarlo es
  `SLHD_RETR_00002` (413), lo detecte el controlador o el lector multipart (`DataBufferLimitException`, que el
  `GlobalExceptionHandler` mapea). La pantalla valida formato y tamaño antes de enviar, como ayuda. El Ingress del
  repositorio de infraestructura sube `proxy-body-size` a `12m`: con el `1m` por defecto de ingress-nginx un PDF
  mediano recibiría un 413 sin el contrato de errores.
- **Nuevo o reemplazo en la misma sentencia**: el `UPSERT` del documento devuelve `(xmax = 0) AS inserted`, e
  `IngestedDocument` gana `replaced`. La respuesta es `201` si es nuevo y `200` si reemplaza. Se descarta consultar antes
  (carrera entre dos subidas y un viaje más) y comparar fechas (frágil).
- **Embeddings sin cambios**: la subida no llama a la indexación; la dispara el `DocumentIngestedEvent` existente. La
  respuesta sale con el documento `PENDING`.
- **Estado por consulta periódica acotada**: la pantalla relee la lista cada 3 s solo mientras haya documentos
  pendientes, con un máximo de 10 min, espaciando a 10 s tras 5 fallos seguidos y parando al salir de la pantalla. Se
  descartan SSE/WebSocket (más superficie sin necesidad en F1) y el sondeo permanente.
- **La causa del error se traduce en la pantalla**: la API devuelve el texto por defecto del código que guardó LAB-21
  y el frontend traduce los textos conocidos; cualquier otro se muestra como «Falló la indexación del documento».
- **Métricas sin nombres**: `retrieval.upload{outcome}` (timer) y `retrieval.documents{status}` (gauge con los
  recuentos de la última lectura de la lista; no se consulta la base desde el *scrape*, que corre en el hilo de
  eventos). El registro de la subida lleva nombre, resultado, chunks y `requestId`, nunca el texto.
- **Sin control de acceso**: como `/api/chat`, la operación queda abierta en el único entorno de desarrollo (ADR-0008).

## Consecuencias

- Ninguna migración ni dependencia nueva; dos endpoints, un puerto de entrada y dos de salida más en `retrieval`.
- **Deuda de seguridad**: cualquiera con acceso a la URL puede añadir o reemplazar documentos del corpus. Debe cerrarse
  con la autenticación (Keycloak) antes de exponer la aplicación fuera de dev.
- El documento entero vive en memoria durante la subida (hasta ~11 MiB por petición). Es asumible con un administrador a
  la vez; si crece el tamaño o la concurrencia, habrá que pasar a *streaming* hacia `ingest` (nuevo ADR).
- La lista no se pagina: vale para las decenas de documentos de F1 (prueba con 100). Paginar es una historia propia.
- Guardar un código estable de error en `retrieval.document` (en lugar del texto por defecto) evitaría el diccionario
  del frontend; queda como mejora con su migración.
- El gauge de documentos por estado solo se actualiza cuando alguien abre la lista.
