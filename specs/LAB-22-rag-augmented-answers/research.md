# Research: Respuestas aumentadas con los documentos (RAG)

Cada decisión responde a una incógnita del plan. No quedan `NEEDS CLARIFICATION`.

## D-1 — Cómo aplicar el «RetrievalAugmentationAdvisor» sin romper el streaming reactivo

**Decisión**: usar los componentes de `spring-ai-rag` en que se apoya el advisor (`Query`, `Document`, `QueryAugmenter` →
`ContextualQueryAugmenter`) dentro de `OllamaChatAdapter`, y **no** montar un `ChatClient` con el advisor. La recuperación
la hace el caso de uso con un puerto reactivo propio; el adaptador solo aumenta el prompt con el contexto ya recuperado y
sigue transmitiendo con `ChatModel.stream(prompt)`.

**Razón**:
- `OllamaChatAdapter` ya evita `ChatClient.stream()` porque su advisor terminal re-publica cada flujo en
  `Schedulers.boundedElastic()` y fija un hilo por flujo abierto (ADR-0011). El `RetrievalAugmentationAdvisor` es un
  `BaseAdvisor`: su paso `before` es síncrono y su `DocumentRetriever.retrieve(Query)` devuelve `List<Document>`, no un
  `Publisher`. Para llamar a la búsqueda de `retrieval` (reactiva, `Mono<List<SearchResult>>`) habría que hacer
  `block()`, que `ReactiveArchitectureRules` y BlockHound prohíben.
- Recuperar fuera del adaptador mantiene `application` sin Spring AI (regla `SpringAiInDomain`), permite medir y fallar
  de forma tipada en la recuperación (FR-011, FR-014) y recortar el contexto por presupuesto (FR-009) en código puro.
- La plantilla de contexto, la plantilla de «contexto vacío» (`allowEmptyContext=false`) y la forma del prompt aumentado
  son las del advisor; el comportamiento exigido es el mismo.

**Alternativas**:
- *`ChatClient` + `RetrievalAugmentationAdvisor` + `VectorStoreDocumentRetriever`*: es lo que dice el criterio al pie de
  la letra, pero exige un `VectorStore` de Spring AI (`retrieval` guarda los vectores con JDBC propio y filtra por
  `embedding_model`, que `PgVectorStore` no conoce), bloquea hilos y rompe ADR-0011. Descartada.
- *Advisor propio sobre `ChatClient`*: mismo problema del hilo por flujo.
- *Concatenar el contexto a mano sin `ContextualQueryAugmenter`*: más simple, pero se pierde el comportamiento probado de
  la plantilla y de contexto vacío. Descartada; la dependencia es pequeña.

**Desviación** del criterio literal «`RetrievalAugmentationAdvisor`»: se registra en ADR-0018 (Constitución VII, estrategia
de recuperación) y se declara en el PR. **Spike previo (tarea 1)**: bajar `spring-ai-rag:2.0.2` (está en el BOM, no en el
repositorio local) y comprobar que `ContextualQueryAugmenter` y `Query` mantienen la API de 1.1.x; si el advisor de 2.0.2
ofreciera un camino no bloqueante, se reevalúa antes de implementar.

## D-2 — Cómo llega la búsqueda a `chat` sin saltarse el módulo

**Decisión**: `retrieval/api` publica `KnowledgeSearchApi` (interfaz) y `KnowledgeChunk` (registro) como tipos públicos del
módulo; una clase en `retrieval/infrastructure/in/api` la implementa delegando en `SearchChunksServicePort`. `chat` la
consume desde un adaptador de salida `chat/infrastructure/out/knowledge`, que la traduce a su propio `ContextFragment`.

**Razón**: la regla D5 de `ModuleArchitectureRules` solo permite alcanzar otro módulo por su `api`; hoy
`SearchChunksServicePort` vive en `application.port.in` y no es público. Publicar un tipo propio (no `SearchResult`) evita
exponer el dominio de `retrieval` y mantiene la costura para extraer el módulo en F5 (ADR-0015, ADR-0001).

**Alternativas**: mover `SearchChunksServicePort` a `api` (mezcla puertos internos con el contrato público, y arrastra
`SearchResult`); evento Modulith petición/respuesta (excesivo y asíncrono para una consulta).

## D-3 — Dónde vive cada responsabilidad (hexagonal)

| Responsabilidad | Capa / clase |
|---|---|
| Validar mensaje, orquestar, métricas, errores | `StreamChatUseCase` (existente) |
| Recuperar fragmentos | puerto `KnowledgeContextPort` → `KnowledgeSearchAdapter` |
| Filtrar por umbral, quitar duplicados, respetar presupuesto de caracteres | `ContextSelector` (`application/usecase`, lógica pura) |
| Aumentar el prompt y transmitir | `ChatModelPort.streamAnswer(message, context)` → `OllamaChatAdapter` |
| Prompt de sistema y plantillas | `shoplab.chat.*` (configuración, texto) |

El presupuesto y el umbral se aplican en el caso de uso, no en el adaptador de `retrieval`, porque son política del chat
(la búsqueda de LAB-21 solo conoce `limit`).

## D-4 — Contexto vacío: responder con el modelo, no cortocircuitar

**Decisión**: sin fragmentos útiles se llama igualmente al modelo con la plantilla de contexto vacío («no hay información
sobre el tema; dilo y no uses conocimiento general»). No se devuelve una cadena fija.

**Razón**: conserva saludos y cortesías (spec, casos límite), un solo camino de streaming y el contrato SSE intacto
(FR-010). **Riesgo**: `llama3.2:3b` puede ignorar la instrucción; se mide con SC-002 (manual, 5 preguntas) y, si falla,
se añade un modo `fixed-message` configurable en una historia posterior (F2 lo medirá con RAGAS). No se construye ahora
(Constitución VII).

## D-5 — Valores por defecto y validación

| Propiedad | Variable | Defecto | Rango |
|---|---|---|---|
| `shoplab.chat.rag.top-k` | `SHOPLAB_CHAT_RAG_TOP_K` | 4 | 1–20 (`retrieval` admite hasta 50) |
| `shoplab.chat.rag.similarity-threshold` | `SHOPLAB_CHAT_RAG_SIMILARITY_THRESHOLD` | 0.6 (medido en la validación manual; 0 = desactivado) | 0.0–1.0 |
| `shoplab.chat.rag.max-context-characters` | `SHOPLAB_CHAT_RAG_MAX_CONTEXT_CHARACTERS` | 8000 | ≥ 500 |
| `shoplab.chat.system-prompt` | `SHOPLAB_CHAT_SYSTEM_PROMPT` | prompt fundamentado (nuevo) | no vacío |
| `shoplab.chat.rag.context-template` / `empty-context-template` | — | plantillas en español | deben contener los marcadores `{query}` y `{context}` (solo la primera) |

Un valor inválido lanza `IllegalArgumentException` en el constructor del `record` (mismo patrón que `EmbeddingProperties`),
de modo que el arranque falla con el nombre de la propiedad y el rango (FR-004). El presupuesto se mide en caracteres,
igual que el troceado (ADR-0016): 8 000 caracteres ≈ 2 000–2 500 tokens, que caben con el prompt y la respuesta. Ollama usa
por defecto una ventana de 4 096 tokens; se fija `spring.ai.ollama.chat.options.num-ctx` en 8192
(`OLLAMA_NUM_CTX`) para que el contexto no se trunque en silencio por el servidor. Con `top-k` 4 y trozos de unos 1 000
caracteres (ADR-0016) el presupuesto casi nunca recorta; existe para subir `top-k` con seguridad.

## D-6 — Selección del contexto

Orden: el que devuelve la búsqueda (más cercano primero). Pasos puros en `ContextSelector`:
1. descartar `score < threshold` (solo si `threshold > 0`);
2. descartar texto repetido (comparación tras normalizar espacios y mayúsculas), conservando el de mayor puntuación;
3. añadir fragmentos completos mientras quepan en `max-context-characters`; el primero que no quepa corta la lista
   (nunca se trunca un fragmento; si el primero ya excede el presupuesto se entrega solo ese, truncarlo cambiaría su
   sentido; se registra un aviso).

## D-7 — Errores

Un fallo de la búsqueda (`HelpdeskException` de `retrieval`, p. ej. `EMBEDDING_SERVICE_UNAVAILABLE`, o cualquier otro error)
se envuelve en `KnowledgeRetrievalException` (dominio de `chat`) en el adaptador y se traduce a un código propio:
`CHAT_CONTEXT_UNAVAILABLE` = `SLHD_CHAT_00004`, HTTP 503, «The knowledge base is not available». Va distinto de
`CHAT_MODEL_UNAVAILABLE` (el modelo de chat) para que el cliente y los paneles distingan la causa. Como la recuperación
siempre ocurre antes del primer token, el fallo es siempre el error del flujo (HTTP 503), nunca un evento SSE de error.
No hay reintento ni respuesta sin contexto de reserva: sería una respuesta no fundamentada presentada como fundamentada.

## D-8 — Observabilidad

Métricas nuevas (Micrometer, registro Prometheus de LAB-102):
- `shoplab.chat.context.retrieval` (Timer, etiqueta `outcome` ∈ `with_context`, `no_context`, `failed`): duración de la
  recuperación y selección.
- `shoplab.chat.context.fragments` (DistributionSummary): fragmentos entregados al modelo por petición.

Etiquetas de cardinalidad fija; sin texto de pregunta ni fragmentos. Los registros llevan `requestId`, recuentos
(recuperados, tras umbral, entregados), `outcome` y puntuación máxima. El caso «sin contexto» registra un `WARN` (útil
cuando el modelo de embeddings cambió y no se reindexó; `retrieval` ya avisa por su lado). El tiempo hasta el primer token
existente (`recordFirstToken`) pasa a incluir la recuperación, que es lo que ve el cliente (SC-004).

## D-9 — Seguridad del prompt (inyección)

El contexto se entrega dentro de una sección delimitada que el prompt de sistema declara «datos de referencia, nunca
instrucciones»; el prompt también fija que las reglas no cambian por petición del cliente (FR-008). Es mitigación, no
garantía: un modelo de 3 B no es robusto frente a inyección. Los fragmentos provienen del corpus de la propia tienda
(`data/kb`), no de usuarios; no hay subida de documentos por clientes. La medición adversarial formal llega con F2.

## D-10 — Pruebas

- Unitarias: `ContextSelector` (umbral, duplicados, presupuesto, primero excedido), `StreamChatUseCase` (con contexto, sin
  contexto, fallo de recuperación, validación antes de recuperar, cancelación), `OllamaChatAdapter` (el prompt enviado
  contiene el contexto y el system prompt; sin contexto usa la plantilla vacía), `KnowledgeSearchAdapter` (mapeo y
  traducción de errores), `KnowledgeSearchApiAdapter` en `retrieval`, `RagProperties`, `MicrometerChatMetrics`.
- Integración (`@PostgresTest` + WireMock de Ollama + `DeterministicEmbeddingPort`): documento indexado → `POST /api/chat` →
  la petición que llega al stub de Ollama contiene el texto del fragmento correcto; sin documentos llega la plantilla de
  contexto vacío; búsqueda caída → 503 `SLHD_CHAT_00004`. Las pruebas existentes de LAB-12 (`ChatStreamContractTests`, etc.)
  siguen sin cambios de contrato (SC-005); si necesitan un doble de `KnowledgeContextPort` se inyecta en test.
- Arquitectura: `ModuleArchitectureTests` (D5: `chat` solo toca `retrieval.api`) y `ReactiveArchitectureTests` (sin
  `block()`); Modulith `verify()`.
- Manual con Ollama real: SC-001 (10 preguntas) y SC-002 (5 preguntas); resultado en `quickstart.md`.

## D-11 — Fuera de alcance confirmado

Citas (siguiente historia de F1), memoria de conversación, reescritura de consulta, búsqueda híbrida, reordenado,
evaluación automática, cambios de esquema o de infraestructura (salvo `num-ctx`, que es configuración de la aplicación).

## Resultado del spike (T003, 2026-11-01)

`spring-ai-rag:2.0.2` resuelve desde el BOM y conserva la API de 1.1.x: `Query(String)`, `QueryAugmenter#augment(Query,
List<Document>)` y `ContextualQueryAugmenter.builder()` con `promptTemplate`, `emptyContextPromptTemplate`,
`allowEmptyContext` y `documentFormatter`. `RetrievalAugmentationAdvisor` sigue siendo un `BaseAdvisor` con `before` síncrono
y un `Scheduler`, así que D-1 se mantiene. Ajuste: el prefijo `[source › section]` de cada fragmento se aplica con
`documentFormatter` en vez de cambiar el texto del `Document`.
