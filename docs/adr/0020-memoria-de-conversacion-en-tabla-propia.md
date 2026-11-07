# ADR-0020: Memoria de la conversación en una tabla propia del módulo `chat`

- Estado: aceptado
- Fecha: 2026-11-07

## Contexto

Hasta LAB-23 cada mensaje se respondía por separado: una pregunta de seguimiento como «¿y si es internacional?» no se
entendía porque el bot no sabía de qué se hablaba. LAB-24 le da memoria. El criterio de aceptación pide un «`ChatMemory`
en JDBC por `conversationId`» con las llamadas aisladas en `Schedulers.boundedElastic()` (ADR-0011). Hay cuatro decisiones
con alternativas reales:

- **Dónde y cómo se guarda el historial.**
- **Cuándo se guarda un turno** respecto a la señal de fin del flujo.
- **Cómo se entiende un seguimiento** al buscar los documentos que apoyan la respuesta.
- **Quién identifica la conversación**, sin autenticación todavía.

## Decisión

- **Tabla propia `chat.conversation_turn`, una fila por turno, detrás de un puerto.** `ConversationMemoryPort`
  (`recent`, `saveTurn`) es un puerto de salida de `application`; su adaptador `JdbcConversationMemoryAdapter` usa
  `JdbcTemplate` a través de `BlockingCalls`. Cada turno (pregunta + respuesta completa) se inserta en una sola
  sentencia: nunca hay una pregunta sin su respuesta y el orden lo da la identidad aun con peticiones simultáneas de la
  misma conversación. Se **descarta** el `JdbcChatMemoryRepository` de Spring AI: crea su propia tabla fuera del esquema
  del módulo (rompe ADR-0015), reescribe la conversación entera en cada guardado y haría que `application` conociera
  Spring AI. El contrato de `ChatMemory` (historial por `conversationId`, en JDBC) se cumple; cambia el tipo que lo
  implementa. La decisión la confirmó el responsable de la historia.
- **El turno se guarda antes de emitir `done`.** El cliente envía su siguiente mensaje al recibir `done`; guardar antes
  elimina la carrera entre «el turno terminó» y «el seguimiento lee el historial». Una respuesta fallida o cancelada no
  guarda nada. Si falla el guardado, el último evento es `error` (`SLHD_CHAT_00006`) en lugar de `done`. Si falla la
  lectura, la petición falla con un 503 antes de empezar: no se responde «como si no hubiera historial».
- **El seguimiento se entiende componiendo la consulta de búsqueda**, no con una segunda llamada al modelo: la búsqueda
  usa `pregunta anterior + "\n" + pregunta actual` (`shoplab.chat.memory.retrieval-turns`, por defecto 1; 0 la
  desactiva). Es determinista, no añade latencia al primer token y se prueba sin Ollama. Su límite conocido es el
  cambio de tema (la pregunta previa contamina la búsqueda); la reformulación con el modelo se reconsiderará con la
  evaluación de F2.
- **El historial llega al modelo como mensajes `user`/`assistant`**, nunca como instrucciones del sistema, acotado por
  `max-turns` (5) y `max-history-characters` (4000); se descarta el turno más antiguo entero. A las respuestas anteriores
  se les quitan las marcas `[n]` para que el modelo no cite números de turnos pasados; las fuentes y el contexto no se
  guardan.
- **El identificador lo genera el cliente** (UUID en texto canónico, `crypto.randomUUID()`), es opcional y se valida en el
  servidor (`SLHD_CHAT_00005`). Sin autenticación en F1 actúa como una capacidad secreta: no se registra, no es etiqueta de
  métrica y no se devuelve. La propiedad por usuario llega con la autenticación.

## Consecuencias

- Una migración nueva en el esquema `chat` (V2) y un puerto más; ninguna dependencia nueva.
- La tabla contiene texto libre del cliente. No hay caducidad ni borrado todavía: queda como trabajo antes de producción
  (retención y borrado de conversaciones).
- `done` tarda lo que tarda una inserción más; el primer token no cambia.
- La calidad del seguimiento depende de la pregunta anterior; hay que medirla en F2 y, si no basta, sustituir la consulta
  compuesta por una reformulación con el modelo (ADR nuevo que sustituya el apartado correspondiente).
- Cambiar a otra implementación de memoria (p. ej. el repositorio de Spring AI) solo exige otro adaptador del puerto.
