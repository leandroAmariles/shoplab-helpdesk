# ADR-0018: Recuperación del contexto en el caso de uso del chat

- Estado: aceptado
- Fecha: 2026-11-01

## Contexto

LAB-22 hace que el bot responda con los documentos de la tienda. El criterio de la historia nombra el
`RetrievalAugmentationAdvisor` de Spring AI, pero ese advisor solo existe dentro de un `ChatClient`, y hay dos razones para
no usarlo aquí:

- `OllamaChatAdapter` transmite con `ChatModel.stream` porque el advisor terminal de `ChatClient.stream()` re-publica cada
  flujo en `Schedulers.boundedElastic()` y fija un hilo por flujo abierto, lo que anula la web reactiva (ADR-0011).
- El `RetrievalAugmentationAdvisor` es un `BaseAdvisor`: su paso `before` es síncrono y su `DocumentRetriever.retrieve`
  devuelve una `List<Document>`, no un `Publisher`. La búsqueda de `retrieval` es reactiva y filtra por modelo de embeddings
  (ADR-0017); para llamarla desde el advisor habría que bloquear, lo que prohíben las reglas de arquitectura reactiva y
  BlockHound. Además `VectorStoreDocumentRetriever` exige un `VectorStore` de Spring AI, que no conoce ese filtro.

## Decisión

- **La recuperación vive en el caso de uso.** `StreamChatUseCase` pide los candidatos al puerto `KnowledgeContextPort`, los
  filtra con `ContextSelector` (umbral de similitud, texto repetido y presupuesto de caracteres, siempre con fragmentos
  completos) y entrega la lista a `ChatModelPort.streamAnswer(message, context)`. Spring AI no entra en `application`.
- **El aumento del prompt usa el `QueryAugmenter` del advisor.** `OllamaChatAdapter` construye la consulta con
  `ContextualQueryAugmenter` (de `spring-ai-rag`, la pieza en la que se apoya el advisor) y sigue transmitiendo con
  `ChatModel.stream`. Sin contexto no usa el aumentador, porque su camino de contexto vacío sustituye la pregunta por un
  texto fijo; aplica la plantilla de contexto vacío conservando la pregunta.
- **`retrieval` publica su búsqueda** en `retrieval.api` (`KnowledgeSearchApi`, `KnowledgeChunk`); `chat` la consume en un
  adaptador de salida y la traduce a su propio `ContextFragment`. Ningún módulo toca tablas ajenas (ADR-0015).
- **Sin contexto útil el modelo se llama igual**, con la plantilla de contexto vacío, para conservar saludos y cortesías y un
  solo camino de streaming. No se cortocircuita con una cadena fija.
- **Un fallo de la recuperación es un error propio** (`SLHD_CHAT_00004`, 503, distinto de `SLHD_CHAT_00002`) antes del
  primer token; nunca se responde sin contexto como si lo hubiera.
- **Todo es configuración** bajo `shoplab.chat.rag.*`: `top-k` (1–20, por defecto 4), `similarity-threshold` (0–1, por
  defecto 0,6; 0 = desactivado), `max-context-characters` (≥ 500, por defecto 8000) y las plantillas; el prompt de sistema es
  `shoplab.chat.system-prompt`. Un valor inválido impide arrancar. `spring.ai.ollama.chat.options.num-ctx` se fija en 8192
  para que el servidor no recorte el contexto en silencio.

## Consecuencias

- Se cumple la intención del criterio (recuperar con top-k configurable y obligar a responder solo con el contexto) sin
  el advisor literal; es una desviación declarada en el PR.
- Dependencia nueva: `spring-ai-rag`, gestionada por el BOM de Spring AI.
- La calidad de «no tengo esa información» depende del modelo local (`llama3.2:3b`) y se comprueba a mano hasta que
  exista la evaluación automática de F2 (RAGAS / DeepEval); si no basta se estudiará un modo de mensaje fijo.
- Las respuestas fundamentadas todavía no citan sus fuentes (principio V de la constitución). Los fragmentos conservan
  documento, sección y posición para que la historia de citas no toque la recuperación; mientras tanto la funcionalidad solo
  se despliega en `dev`.
- Cada petición recupera su contexto de su propio texto: no hay memoria de conversación ni reescritura de la consulta.
- El tiempo hasta el primer token incluye ahora la búsqueda (un embedding y una consulta SQL).
