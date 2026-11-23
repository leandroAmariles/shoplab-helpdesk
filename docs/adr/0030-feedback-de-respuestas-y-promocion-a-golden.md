# ADR-0030: Feedback de respuestas y promoción a casos golden

- Estado: aceptado
- Fecha: 2026-11-23

## Contexto

La historia LAB-33 (`F2-07 Feedback 👍/👎 en cada respuesta`) requiere que el cliente pueda valorar cada respuesta completada del asistente con 👍 (`UP`) o 👎 (`DOWN`) y añadir un comentario opcional guardado en `chat.feedback`, y que las respuestas valoradas con 👎 se puedan promover a casos del dataset golden (`eval/golden.jsonl`, LAB-27).

Para lograrlo respetando la arquitectura actual hay cuatro restricciones clave:

1. **Propiedad de esquemas (ADR-0015)**: ningún módulo puede leer ni escribir tablas de otro esquema ni declarar claves foráneas entre esquemas (`ModuleSchemasIntegrationTests`, `SchemaIsolationTests`). El turno de conversación (`chat.conversation_turn`, LAB-24) pertenece al esquema `chat`.
2. **Contrato SSE de `POST /api/chat` (LAB-12)**: las pruebas de contrato sin memoria (`ChatStreamContractTests`, `golden-stream.txt`) exigen que una petición sin `conversationId` termine exactamente con `event:done\ndata:{"fragments":N}\n\n`.
3. **Inmutabilidad del contenedor y flujo Git del dataset golden (LAB-27)**: `eval/golden.jsonl` es un archivo versionado en Git cuya validación (`shoplab_eval validate`) y revisión humana (`eval/REVIEW.md`) ocurren en el repositorio; el backend desplegado en un contenedor no tiene acceso al árbol Git para modificar `eval/golden.jsonl` directamente en disco.
4. **Privacidad en observabilidad (Constitución VI)**: ni las preguntas, ni las respuestas, ni los comentarios del cliente, ni identificadores de alta cardinalidad (`turnId`, `conversationId`) pueden aparecer en logs ni en etiquetas de métricas de Prometheus.

## Decisión

1. **Identificación pública del turno (`turn_id`) y metadatos en `chat.conversation_turn` (migración `V3__answer_feedback.sql`)**:
   - Se amplía `chat.conversation_turn` con `turn_id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE`, `delivered_documents text[] NOT NULL DEFAULT '{}'` (identificadores de documento sin `.md`/`.pdf`, sin duplicados y en orden de entrega) y `trace_id text` (los primeros 32 caracteres hexadecimales de `SHA-256(requestId)`).
   - El evento SSE `done` incorpora el campo opcional `turnId` con `@JsonInclude(NON_NULL)`: cuando la petición incluye `conversationId` y el turno se persiste, `done` emite `{"fragments":N,"turnId":"<uuid>"}`; cuando no hay `conversationId`, `turnId` es `null` y se omite del JSON, preservando byte a byte el contrato sin memoria.

2. **Tabla `chat.feedback` en el esquema `chat` con relación 1:1 por turno**:
   - Se crea `chat.feedback` dentro del esquema `chat` con clave foránea interna `turn_ref bigint NOT NULL UNIQUE REFERENCES conversation_turn(id) ON DELETE CASCADE`, `rating text NOT NULL CHECK (rating IN ('UP', 'DOWN'))`, `comment text` opcional (`1..500` caracteres tras recortar espacios) y los campos de promoción `promoted_case_id` (`^G[0-9]{3}$`, `UNIQUE`), `promoted_case_jsonl` y `promoted_at`.
   - `PUT /api/chat/feedback/{turnId}` realiza un `INSERT ... ON CONFLICT (turn_ref) DO UPDATE` idempotente mientras la valoración no haya sido promovida; una vez promovida (`promoted_case_id IS NOT NULL`), queda bloqueada frente a ediciones o nuevas promociones (`409 SLHD_CHAT_00013`).

3. **Promoción asistida desde `/admin-eval` con validación de reglas de LAB-27**:
   - El módulo `chat` expone `GET /api/chat/feedback` (listado de valoraciones filtrable por `rating` y `promoted`, junto con el siguiente identificador sugerido `G<NNN>` a partir de `shoplab.chat.feedback.base-golden-case-number`, por defecto `120`) y `POST /api/chat/feedback/{turnId}/promote`.
   - `GoldenCaseValidator` valida en el backend las reglas individuales de LAB-27 (`G-ID`, `G-CATEGORY`, `G-QUESTION-LENGTH`, `G-ANSWER-LENGTH`, `G-DOC-COUNT`, `G-CATEGORY-TOPIC`, `G-NOANSWER-DIGITS` y `G-PII`) antes de marcar el feedback como promovido y serializa la línea JSONL canónica (`id`, `question`, `reference_answer`, `expected_documents`, `category`).
   - La sección «Feedback 👎 de clientes y promoción a casos golden» en `/admin-eval` carga bajo demanda las respuestas con 👎, precarga el formulario con la pregunta y los documentos entregados del turno, exige redactar la `reference_answer` curada y entrega la línea JSONL lista para copiar o descargar e incorporar a `eval/golden.jsonl` y `eval/REVIEW.md`.
   - En `eval/`, `GoldenRules.max_cases` se amplía de `100` a `150` para admitir en `eval/golden.jsonl` los casos de conocimiento promovidos desde el feedback real.

4. **Observabilidad sin texto libre ni etiquetas de alta cardinalidad**:
   - Se publican únicamente los contadores `shoplab.chat.feedback` (`rating="up"|"down"`) y `shoplab.chat.feedback.promoted` (sin etiquetas), y los logs registran solo `rating`, `hasComment`, `caseId` y `category`.

## Consecuencias

- Todo el modelo de datos de conversación y valoración permanece dentro del esquema `chat`, con integridad referencial garantizada por PostgreSQL y sin acoplar los esquemas `chat` y `eval`.
- El cliente puede valorar cualquier respuesta completada en un solo clic y añadir o corregir su voto o comentario sin duplicar filas.
- Ningún caso promovido desde `/admin-eval` puede violar las reglas de formato, longitud, categoría, ausencia de cifras en casos sin documentos o ausencia de PII de LAB-27.
- La incorporación final a `eval/golden.jsonl` mantiene la revisión humana explícita en `eval/REVIEW.md` y la validación determinista en CI (`shoplab_eval validate`).
