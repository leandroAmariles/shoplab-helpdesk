# Quickstart: validar el feedback 👍/👎 y su promoción a casos golden (LAB-33)

Guía de verificación de punta a punta para la historia LAB-33. Las rutas y el modelo de datos están en
[contracts/feedback-api.md](contracts/feedback-api.md) y [data-model.md](data-model.md).

## Requisitos

- Stack local arriba (`docker compose -f infra/docker-compose.yml up -d --wait`) con los documentos de `data/kb` indexados.
- Backend corriendo en `http://localhost:8080` (`cd backend && ./mvnw spring-boot:run`).
- Frontend corriendo en `http://localhost:4200` (`cd frontend && npm start`).

## Escenarios de verificación

| # | Qué se hace | Resultado esperado | Spec |
|---|---|---|---|
| V1 | En `/chat`, enviar una pregunta sobre devoluciones y mirar la burbuja mientras aún está en streaming | No aparecen los botones 👍 ni 👎 mientras el asistente escribe; al terminar (`done`), aparecen bajo la burbuja | US1·1-2, FR-002 |
| V2 | Pulsar 👍 en la primera respuesta completada | El botón 👍 queda marcado (`aria-pressed="true"`), aparece la confirmación y existe una fila en `chat.feedback` con `rating = 'UP'` y `comment IS NULL` | US1·3, SC-001 |
| V3 | En la misma burbuja, cambiar a 👎, escribir el comentario «No menciona si cuenta desde la entrega» y guardarlo | La misma fila de `chat.feedback` pasa a `rating = 'DOWN'` y `comment = 'No menciona...'`; sigue habiendo 1 sola fila para ese `turn_id` | US1·4-5, SC-003 |
| V4 | Hacer una segunda pregunta de cortesía («hola») o de seguimiento («¿y si es internacional?») y valorarla con 👎 | El turno tiene `turnId`, permite valorarlo con 👎 y en `chat.feedback` / `chat.conversation_turn` quedan registrados la pregunta, la respuesta y los documentos entregados | US2·1-2, SC-002 |
| V5 | Abrir `/admin-eval` y revisar la sección de feedback con 👎 | Aparecen las valoraciones con 👎 (y no las 👍), con filtro «Pendientes de promover / Todas», mostrando fecha, pregunta, respuesta del asistente, documentos entregados y comentario del cliente | US3·1, FR-009 |
| V6 | Pulsar «Promover a caso golden» sobre la valoración 👎 de V3 | El formulario se abre precargado con el siguiente id sugerido (`G121`), la pregunta del cliente y los documentos entregados en aquel turno, con `reference_answer` vacía para que el administrador redacte la respuesta correcta | US3·2, FR-010, FR-011 |
| V7 | Intentar promover con un dato inválido (p. ej. `reference_answer` con una cifra cuando `expected_documents` está vacía, o correo `juan@gmail.com`) | El backend responde 422 `SLHD_CHAT_00014` indicando el código de regla (`G-NOANSWER-DIGITS` o `G-PII`); la pantalla muestra el motivo y el feedback sigue como no promovido | US3·4, FR-012, SC-005 |
| V8 | Completar una `reference_answer` válida, categoría `returns` y documento `returns-01-return-policy`, y confirmar la promoción | La valoración pasa a estado «Promovido a G121», se muestra la línea JSONL exacta con botones «Copiar JSONL» y «Descargar», y el siguiente id sugerido pasa a `G122` | US3·2,5, SC-004 |
| V9 | Pegar la línea JSONL generada al final de `eval/golden.jsonl` y ejecutar `cd eval && uv run python -m shoplab_eval validate` | El comando `validate` termina con código 0 y cuenta el nuevo caso sin errores | US3·2, SC-004 |
| V10 | Intentar volver a promover el mismo feedback 👎 o cambiar su voto a 👍 | El backend responde 409 `SLHD_CHAT_00013` (`FEEDBACK_ALREADY_PROMOTED`); en `admin-eval` sigue disponible la línea JSONL de `G121` para copiarla | US3·5, FR-011 |
| V11 | Consultar `http://localhost:8080/actuator/prometheus` y los logs del backend | Aparecen `shoplab_chat_feedback_total{...,rating="up"}`, `shoplab_chat_feedback_total{...,rating="down"}` y `shoplab_chat_feedback_promoted_total` sin texto de preguntas, respuestas ni comentarios | US4·1-3, SC-007 |

## Verificación automática (sin servidor de modelos real)

- **Backend**: `cd backend && ./mvnw clean verify`
  - Migración `V3__answer_feedback.sql` en el esquema `chat` y pruebas de `ModuleSchemasIntegrationTests`.
  - Pruebas unitarias de `GoldenCaseValidator`, `SubmitFeedbackUseCase`, `ListFeedbackUseCase` y `PromoteFeedbackUseCase`.
  - Pruebas del controlador `FeedbackControllerTests` (`WebTestClient`) y del adaptador `JdbcFeedbackAdapterTests` (`@PostgresTest`).
  - `ChatStreamContractTests`, `ChatMemoryIntegrationTests`, `ChatCitationsIntegrationTests`, `ModularityTests` y `ModuleArchitectureTests` en verde con BlockHound activo y cobertura ≥ 80 %.
- **Frontend**: `cd frontend && npm test -- --watch=false && npm run lint && npm run build`
  - Pruebas de `message-bubble` y `chat-store` (envío de 👍/👎, actualización de comentario, manejo de errores).
  - Pruebas de `feedback-promotion` y `admin-eval` (listado de 👎, formulario de promoción, errores de validación y copia/descarga de la línea JSONL).
- **Eval (Python)**: `cd eval && uv run pytest && uv run ruff check .`
