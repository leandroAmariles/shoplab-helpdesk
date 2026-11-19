# Research: Memoria de la conversación (LAB-24)

Cada decisión indica qué se eligió, por qué y qué se descartó. Los puntos marcados **[verificar]** se basan en la
documentación de Spring AI conocida; **la decisión de R1 está confirmada por el responsable (2026-11-07)** y no depende de esa verificación.

## R1 — Dónde y cómo se guarda el historial

- **Decisión (confirmada por el responsable el 2026-11-07)**: tabla propia `chat.conversation_turn` (migración `V2` del esquema `chat`), un adaptador JDBC propio
  detrás de `ConversationMemoryPort`, con las llamadas en `BlockingCalls` (ADR-0011).
- **Por qué**: el esquema `chat` es del módulo (ADR-0015, `V1__baseline.sql` ya anuncia que las tablas llegan con la
  historia que las necesita). El adaptador sigue el patrón de `JdbcKnowledgeBaseAdapter` (`JdbcTemplate`,
  `@DependsOn("moduleSchemaMigrator")`, `DataAccessException` → error tipado).
- **Alternativas**:
  - `spring-ai-starter-model-chat-memory-repository-jdbc` (`JdbcChatMemoryRepository` + `MessageWindowChatMemory`)
    **[verificar]**: crea `SPRING_AI_CHAT_MEMORY` en el esquema por defecto (no en `chat`), con su propio inicializador
    en vez de Flyway; su `saveAll` borra y reinserta la conversación entera; el recorte ocurre en memoria tras leer
    todo el historial; y exige que `application` o el adaptador conozcan `ChatMemory`. Es la lectura literal del
    criterio de aceptación («`ChatMemory` en JDBC»); se descarta por romper la propiedad del esquema y reescribir la
    conversación en cada turno. **Si el responsable prefiere la literalidad, el cambio queda acotado a un adaptador
    nuevo detrás del mismo puerto** (el resto del diseño no cambia).
  - R2DBC: descartado en ADR-0011.

## R2 — Granularidad: una fila por turno

- **Decisión**: columnas `conversation_id uuid`, `question text`, `answer text`, `id bigint identity` (orden),
  `created_at`. Un turno se inserta en una sola sentencia.
- **Por qué**: atómico por construcción; con dos peticiones simultáneas de la misma conversación cada turno queda
  entero y el orden lo da `id` (edge case de la spec). Leer los N últimos turnos es `ORDER BY id DESC LIMIT N`, servido
  por el índice `(conversation_id, id DESC)`.
- **Alternativa**: una fila por mensaje con rol y posición: dos inserciones por turno, riesgo de intercalado, y la
  posición exige bloqueo o secuencia por conversación.

## R3 — Cuándo se guarda

- **Decisión**: el turno se guarda cuando el flujo del modelo **completa** y **antes** de emitir `done`
  (`concatWith(Mono.defer(save).then(Mono.just(Done)))`). Una cancelación o un error del modelo no llegan a guardar
  (FR-007). Una respuesta de texto vacío no se guarda. La respuesta fija de «sin información» sí se guarda.
- **Por qué**: el cliente envía el siguiente mensaje solo tras ver `done`; guardar antes elimina la carrera entre «el
  turno terminó» y «el seguimiento lee el historial». Si el guardado falla, el flujo ya empezó: se emite `error`
  (`SLHD_CHAT_00006`) en lugar de `done`, y la pantalla trata esa respuesta como fallida (honesto: el bot no recordará
  ese turno).
- **Alternativas**: guardar en `doOnComplete` sin esperar (carrera); guardar la pregunta al recibirla y la respuesta
  al terminar (pregunta huérfana ante fallo, FR-007).

## R4 — Comprensión del seguimiento al buscar contexto (FR-006)

- **Decisión**: la consulta de búsqueda es `pregunta anterior del cliente + "\n" + pregunta actual` cuando hay
  historial (`FollowUpQuery`, pura, con tope de 2000 caracteres que nunca recorta la pregunta actual). `shoplab.chat.memory.retrieval-turns` (por defecto 1) fija cuántas preguntas
  anteriores se anteponen; 0 desactiva la composición. El mensaje que se entrega al modelo sigue siendo la pregunta
  actual sin tocar.
- **Por qué**: un seguimiento como «¿y si es internacional?» no tiene palabras del tema; su embedding aislado recupera
  documentos al azar. Anteponer la pregunta previa devuelve el tema sin una segunda llamada al modelo. Es determinista
  (pruebas con modelo simulado) y no añade latencia apreciable.
- **Riesgo conocido**: si el cliente cambia de tema, la pregunta previa contamina la búsqueda. Mitigaciones: la pregunta
  actual siempre va incluida y al final; el umbral de similitud y el presupuesto de contexto de LAB-22 siguen
  filtrando; el valor `0` permite desactivarlo. La calibración queda para F2 (evaluación).
- **Alternativas**:
  - Reformular la pregunta con una llamada al modelo (query condensation): mejor calidad en cambios de tema, pero añade
    segundos al primer token con `llama3.2:3b`, hace la prueba no determinista y duplica la superficie de fallo.
    Reconsiderar con la evaluación de F2.
  - Buscar dos veces (actual y compuesta) y fusionar: más código y más latencia para un beneficio no medido.

## R5 — Cómo se entrega el historial al modelo

- **Decisión**: mensajes alternados `UserMessage`/`AssistantMessage` entre `SystemMessage` y el último `UserMessage`
  (el que lleva el contexto de la base de conocimiento). Nunca como texto del sistema (edge case de la spec). En el
  historial, las preguntas se guardan y entregan **sin** contexto; de las respuestas se **quitan las marcas `[n]`**
  (`HistoryWindow`) para que el modelo no cite números de turnos anteriores (FR-013). El texto guardado en la tabla
  conserva las marcas tal como se emitió.
- **Por qué**: es la forma estándar de conversación del modelo; entregar el contexto antiguo repetiría hasta 8 000
  caracteres por turno y desbordaría el presupuesto.
- El prompt de sistema por defecto gana una frase: los mensajes anteriores son la conversación, los datos solo salen del
  contexto del último mensaje, y no se citan números de mensajes anteriores. Sigue siendo configurable
  (`SHOPLAB_CHAT_SYSTEM_PROMPT`).

## R6 — Límites del historial (FR-008)

- **Decisión**: dos límites en `MemoryProperties`: `max-turns` (por defecto 5, de 0 a 50; 0 desactiva la memoria
  aunque llegue `conversationId`) y `max-history-characters` (por defecto 4 000, mínimo 200). El caso de uso lee `max-turns + 1` turnos para saber si hubo recorte y `HistoryWindow` toma los
  `max-turns` últimos turnos y, mientras la suma de caracteres de pregunta + respuesta supere el presupuesto, descarta
  el **más antiguo** entero (nunca recorta un texto por la mitad). Si ni el último turno cabe, el historial queda vacío.
- **Por qué**: contexto (≤ 8 000) + historial (≤ 4 000) + mensaje (≤ 4 000) + sistema es del orden de 4 000 tokens, dentro
  de `num-ctx` 8192. El recorte no borra nada del almacén (US3.1).
- **Alternativa**: contar tokens reales: exige un tokenizador del modelo; los caracteres siguen el criterio de
  `ContextSelector` (LAB-22).

## R7 — Identificador de conversación

- **Decisión**: UUID en texto canónico (36 caracteres) generado con `crypto.randomUUID()` en el navegador. El servidor
  lo valida (`ConversationId.of`), lo normaliza a minúsculas y rechaza otros formatos con
  `SLHD_CHAT_00005` (400) antes de tocar la base de datos ni el modelo. Ausente o `null` → sin memoria. Cadena vacía
  → inválido.
- **Por qué**: 122 bits aleatorios son impredecibles en la práctica (edge case de adivinar identificadores). No hay
  autenticación en F1; el identificador actúa como capacidad secreta, y esto queda documentado como límite (FR-017).
- **Alternativa**: que el servidor genere el id y lo devuelva en un evento: cambia el contrato del flujo y obliga al
  cliente a guardarlo tras el primer mensaje, sin beneficio hoy.

## R8 — Fallos de la memoria (FR-010)

- **Decisión**: `ConversationMemoryException` (infraestructura → dominio) se traduce a `CHAT_MEMORY_UNAVAILABLE`
  (`SLHD_CHAT_00006`, 503). Fallo de **lectura**: es el error del flujo antes de empezar (respuesta HTTP 503); no se
  responde sin historial. Fallo de **escritura**: evento `error` en lugar de `done`. Ambos se registran con `requestId`
  y operación, sin texto, y suben `shoplab.chat.memory.failures{operation}`.
- **Por qué**: responder «como si no hubiera contexto previo» da respuestas engañosas; es el mismo criterio que
  LAB-22 aplicó al contexto de la base de conocimiento.

## R9 — BlockHound y reglas de arquitectura

- **Decisión**: el adaptador usa solo `BlockingCalls.call/run/stream`; no menciona `Schedulers`, así que las reglas
  ArchUnit de LAB-111 lo cubren sin cambios. Una prueba de integración con PostgreSQL real recorre lectura y
  escritura bajo BlockHound, tanto en el hilo de pruebas como por `WebTestClient` contra el servidor.
- **Cancelación y `boundedElastic`** (R12 de LAB-111): cancelar el flujo mientras se guarda interrumpe el hilo; el
  `INSERT` es una sola sentencia atómica, así que no deja filas a medias. Cancelar antes del guardado no escribe nada.

## R10 — Métricas y registros (FR-015)

- **Decisión**: `shoplab.chat.history.messages` (resumen de distribución: mensajes entregados al modelo por petición),
  `shoplab.chat.history.trimmed` (contador: peticiones en que se recortó historial) y
  `shoplab.chat.memory.failures{operation=read|write}`. Sin `conversationId` como etiqueta. Un único registro INFO por
  petición con `requestId`, `turns` y `characters`, nunca texto.

## R11 — Frontend

- **Decisión**: `ChatStore` crea el UUID al construirse y lo envía en el cuerpo de cada `POST`. `newConversation()`
  cancela el flujo en curso (como ya hace al salir), vacía los mensajes y regenera el UUID. El botón «Nueva
  conversación» va en el encabezado de la pantalla de chat, con `aria-label`, deshabilitado si la conversación está
  vacía. Recargar la página crea un UUID nuevo (la conversación visible no se persiste, spec).
- **Por qué**: la lógica va en el almacén (probable con Vitest) y la vista solo lo invoca. Cancelar en curso evita
  mezclar una respuesta tardía en la conversación nueva (US4.3).

## R12 — Datos personales y retención

- **Decisión**: el texto de los mensajes solo existe en `chat.conversation_turn`; no se registra ni se mide. Sin caducidad
  en esta historia (FR-017). Se documenta en `docs/modulos.md` que la tabla contiene texto libre del cliente y que la
  retención y el borrado son trabajo posterior (candidato a historia propia antes de producción).
