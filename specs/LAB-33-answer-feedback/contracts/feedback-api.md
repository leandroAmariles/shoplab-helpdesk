# Contracts: API de feedback y promoción a casos golden (LAB-33)

Todos los endpoints pertenecen al módulo `chat` (dueño de `chat.conversation_turn` y `chat.feedback`, ADR-0015).

---

## 1. Ampliación aditiva de `POST /api/chat` (evento SSE `done`)

Cuando la petición de chat lleva `conversationId` y la respuesta termina de generarse con éxito, el turno se guarda en `chat.conversation_turn` antes de emitir `done` y el evento `done` incluye el campo `turnId` (UUID en texto canónico):

```text
event:done
data:{"fragments":6,"turnId":"2b6f1a04-9c44-4d30-9f51-8f2a10c4c7a9"}
```

- Cuando la petición **no** lleva `conversationId` (sin memoria), `turnId` es `null` y se omite del JSON (`@JsonInclude(NON_NULL)`):
  ```text
  event:done
  data:{"fragments":6}
  ```
- Así se mantiene intacto byte a byte el contrato sin memoria (`ChatStreamContractTests`, LAB-12, LAB-22, LAB-23, LAB-111).

---

## 2. `PUT /api/chat/feedback/{turnId}` — Registrar o actualizar la valoración de un turno

Registra por primera vez o actualiza la valoración (👍 `UP` o 👎 `DOWN`) y el comentario opcional de un turno completado.

### Path parameter

| Parámetro | Formato | Descripción |
|---|---|---|
| `turnId` | UUID canónico (`^[0-9a-fA-F]{8}-...`) | Identificador del turno recibido en el evento SSE `done` |

### Request body (`application/json`)

```json
{
  "rating": "DOWN",
  "comment": "No indica el plazo de devolución en días naturales."
}
```

| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `rating` | string | sí | `"UP"` o `"DOWN"` |
| `comment` | string \| null | no | Se recorta con `strip()`; si queda vacío se guarda como `null`. Máximo 500 caracteres tras recortar |

### Response `200 OK` (`application/json`)

```json
{
  "turnId": "2b6f1a04-9c44-4d30-9f51-8f2a10c4c7a9",
  "rating": "DOWN",
  "comment": "No indica el plazo de devolución en días naturales.",
  "createdAt": "2026-11-23T15:10:00Z",
  "updatedAt": "2026-11-23T15:10:05Z",
  "promotedCaseId": null,
  "promotedAt": null
}
```

### Códigos de respuesta

| HTTP | Código | Cuándo |
|---|---|---|
| `200 OK` | — | Valoración creada o actualizada |
| `400 Bad Request` | `SLHD_CHAT_00011` (`INVALID_FEEDBACK`) | `turnId` no es un UUID válido, `rating` falta o no es `UP`/`DOWN`, o `comment` supera 500 caracteres |
| `404 Not Found` | `SLHD_CHAT_00012` (`FEEDBACK_TURN_NOT_FOUND`) | No existe ningún turno completado con ese `turnId` |
| `409 Conflict` | `SLHD_CHAT_00013` (`FEEDBACK_ALREADY_PROMOTED`) | La valoración de ese turno ya fue promovida a un caso golden `G<NNN>` y no puede modificarse |
| `503 Service Unavailable` | `SLHD_CHAT_00006` (`CHAT_MEMORY_UNAVAILABLE`) | Fallo de acceso a la base de datos |

---

## 3. `GET /api/chat/feedback` — Listar valoraciones para revisión y promoción en `admin-eval`

Devuelve las valoraciones guardadas en `chat.feedback` junto con los datos del turno de `chat.conversation_turn`, ordenadas de más reciente a más antigua (`created_at DESC, id DESC`), además del siguiente identificador `G<NNN>` sugerido para promover.

### Query parameters

| Parámetro | Obligatorio | Por defecto | Reglas |
|---|---|---|---|
| `rating` | no | `DOWN` | `DOWN` o `UP` |
| `promoted` | no | *(todos)* | `true` (solo promovidos), `false` (solo pendientes de promover) o ausente (ambos) |
| `limit` | no | `100` | Entero entre `1` y `500` |

### Response `200 OK` (`application/json`)

```json
{
  "total": 2,
  "pendingCount": 1,
  "promotedCount": 1,
  "suggestedNextCaseId": "G122",
  "items": [
    {
      "turnId": "2b6f1a04-9c44-4d30-9f51-8f2a10c4c7a9",
      "conversationId": "9c3a2011-7e51-4a0e-88a4-1a1c80f7c111",
      "question": "¿Y si el producto ya lo abrí para probarlo?",
      "answer": "No encontré información sobre ese tema en la base de conocimiento de ShopLab.",
      "deliveredDocuments": ["returns-01-return-policy"],
      "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
      "rating": "DOWN",
      "comment": "Venía hablando de devoluciones y no indicó la condición del empaque.",
      "createdAt": "2026-11-23T15:10:00Z",
      "updatedAt": "2026-11-23T15:10:05Z",
      "promotedCaseId": null,
      "promotedCaseJsonl": null,
      "promotedAt": null
    },
    {
      "turnId": "7d10e9a2-3b12-4c99-81b1-04d2f3c89a01",
      "conversationId": "9c3a2011-7e51-4a0e-88a4-1a1c80f7c111",
      "question": "¿Cuántos días tengo para devolver una compra?",
      "answer": "Tienes 15 días.",
      "deliveredDocuments": ["returns-01-return-policy", "returns-05-faq-returns"],
      "traceId": "8a1c3d9022e14f5590ab12cd34ef5678",
      "rating": "DOWN",
      "comment": "La política dice 30 días, no 15.",
      "createdAt": "2026-11-23T14:20:00Z",
      "updatedAt": "2026-11-23T14:20:00Z",
      "promotedCaseId": "G121",
      "promotedCaseJsonl": "{\"id\": \"G121\", \"question\": \"¿Cuántos días tengo para devolver una compra?\", \"reference_answer\": \"Puedes solicitar la devolución dentro de los 30 días naturales siguientes a la fecha de entrega del pedido.\", \"expected_documents\": [\"returns-01-return-policy\"], \"category\": \"returns\"}",
      "promotedAt": "2026-11-23T14:25:00Z"
    }
  ]
}
```

### Códigos de respuesta

| HTTP | Código | Cuándo |
|---|---|---|
| `200 OK` | — | Listado devuelto |
| `400 Bad Request` | `SLHD_CHAT_00011` (`INVALID_FEEDBACK`) | `rating` desconocido o `limit` fuera de `1..500` |
| `503 Service Unavailable` | `SLHD_CHAT_00006` (`CHAT_MEMORY_UNAVAILABLE`) | Fallo de acceso a la base de datos |

---

## 4. `POST /api/chat/feedback/{turnId}/promote` — Promover un feedback 👎 a caso golden

Valida el caso golden propuesto con las reglas de LAB-27 (`GoldenCaseValidator`), genera la línea JSONL canónica, marca la valoración en `chat.feedback` como promovida a ese `G<NNN>` y devuelve el elemento actualizado con `promotedCaseJsonl`.

### Request body (`application/json`)

```json
{
  "caseId": "G121",
  "question": "¿Puedo devolver un producto si ya abrí la caja para probarlo?",
  "referenceAnswer": "Sí, puedes devolverlo dentro del plazo de 30 días naturales siempre que el producto esté sin uso más allá de la revisión básica, con todos sus accesorios, manuales y empaque original.",
  "expectedDocuments": ["returns-01-return-policy"],
  "category": "returns"
}
```

| Campo | Tipo | Obligatorio | Reglas (`GoldenCaseValidator`) |
|---|---|---|---|
| `caseId` | string | sí | `^G[0-9]{3}$` (`G-ID`), no utilizado en otra promoción |
| `question` | string | sí | 10–300 caracteres tras `strip()` (2–300 en `small_talk` / `injection`) (`G-QUESTION-LENGTH`); sin PII (`G-PII`) |
| `referenceAnswer` | string | sí | 20–800 caracteres tras `strip()` (`G-ANSWER-LENGTH`); sin cifras si `expectedDocuments` es vacía (`G-NOANSWER-DIGITS`); sin PII (`G-PII`) |
| `expectedDocuments` | string[] | sí | De 0 a 2 ids de documento (`^[a-z0-9]+(?:-[a-z0-9]+)*$`), sin duplicados (`G-DOC-COUNT`). Vacía en `off_topic`, `small_talk` e `injection`; con prefijo `<category>-` si tiene documentos en un tema (`G-CATEGORY-TOPIC`) |
| `category` | string | sí | `payments`, `privacy`, `returns`, `shipping`, `warranty`, `off_topic`, `small_talk` o `injection` (`G-CATEGORY`) |

### Response `200 OK` (`application/json`)

Devuelve el mismo objeto de elemento del listado (`FeedbackItemResponse`), con `promotedCaseId`, `promotedCaseJsonl` y `promotedAt` rellenos:

```json
{
  "turnId": "2b6f1a04-9c44-4d30-9f51-8f2a10c4c7a9",
  "conversationId": "9c3a2011-7e51-4a0e-88a4-1a1c80f7c111",
  "question": "¿Y si el producto ya lo abrí para probarlo?",
  "answer": "No encontré información sobre ese tema en la base de conocimiento de ShopLab.",
  "deliveredDocuments": ["returns-01-return-policy"],
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "rating": "DOWN",
  "comment": "Venía hablando de devoluciones y no indicó la condición del empaque.",
  "createdAt": "2026-11-23T15:10:00Z",
  "updatedAt": "2026-11-23T15:10:05Z",
  "promotedCaseId": "G121",
  "promotedCaseJsonl": "{\"id\": \"G121\", \"question\": \"¿Puedo devolver un producto si ya abrí la caja para probarlo?\", \"reference_answer\": \"Sí, puedes devolverlo dentro del plazo de 30 días naturales siempre que el producto esté sin uso más allá de la revisión básica, con todos sus accesorios, manuales y empaque original.\", \"expected_documents\": [\"returns-01-return-policy\"], \"category\": \"returns\"}",
  "promotedAt": "2026-11-23T15:12:00Z"
}
```

### Códigos de respuesta

| HTTP | Código | Cuándo |
|---|---|---|
| `200 OK` | — | Caso validado y feedback marcado como promovido |
| `400 Bad Request` | `SLHD_CHAT_00011` (`INVALID_FEEDBACK`) | `turnId` no es un UUID válido |
| `404 Not Found` | `SLHD_CHAT_00012` (`FEEDBACK_TURN_NOT_FOUND`) | No existe feedback registrado para ese `turnId` |
| `409 Conflict` | `SLHD_CHAT_00013` (`FEEDBACK_ALREADY_PROMOTED`) | Ese feedback ya fue promovido anteriormente, o el `caseId` `G<NNN>` ya está asignado a otro feedback promovido |
| `422 Unprocessable Content` | `SLHD_CHAT_00014` (`INVALID_GOLDEN_CASE`) | El caso golden viola alguna regla de LAB-27; el campo `detail` de la respuesta de error incluye el código `G-...` y la regla incumplida |
| `422 Unprocessable Content` | `SLHD_CHAT_00015` (`FEEDBACK_NOT_NEGATIVE`) | La valoración de ese turno es `UP` (👍); solo las valoraciones `DOWN` (👎) se pueden promover |
| `503 Service Unavailable` | `SLHD_CHAT_00006` (`CHAT_MEMORY_UNAVAILABLE`) | Fallo de acceso a la base de datos |

---

## 5. Nuevos códigos en `ResponseCodeEnum`

| Enum | Código | HTTP | Mensaje por defecto |
|---|---|---|---|
| `INVALID_FEEDBACK` | `SLHD_CHAT_00011` | `400 Bad Request` | `"The feedback request is not valid"` |
| `FEEDBACK_TURN_NOT_FOUND` | `SLHD_CHAT_00012` | `404 Not Found` | `"There is no completed chat turn or feedback with that id"` |
| `FEEDBACK_ALREADY_PROMOTED` | `SLHD_CHAT_00013` | `409 Conflict` | `"The feedback has already been promoted to a golden case"` |
| `INVALID_GOLDEN_CASE` | `SLHD_CHAT_00014` | `422 Unprocessable Content` | `"The golden case does not satisfy the dataset rules"` (con `PublicDetail`) |
| `FEEDBACK_NOT_NEGATIVE` | `SLHD_CHAT_00015` | `422 Unprocessable Content` | `"Only negative feedback can be promoted to a golden case"` |

---

## 6. Métricas y configuración

### Métricas de Prometheus (`MicrometerChatMetrics`)

| Nombre | Tipo | Etiquetas | Descripción |
|---|---|---|---|
| `shoplab.chat.feedback` (`shoplab_chat_feedback_total`) | `Counter` | `rating="up"\|"down"` | Valoraciones registradas o actualizadas en `chat.feedback`, por sentido del voto |
| `shoplab.chat.feedback.promoted` (`shoplab_chat_feedback_promoted_total`) | `Counter` | *(ninguna)* | Valoraciones con 👎 promovidas a casos golden |

### Propiedad de configuración (`application.yml`)

| Propiedad | Variable de entorno | Valor por defecto | Qué hace |
|---|---|---|---|
| `shoplab.chat.feedback.base-golden-case-number` | `SHOPLAB_CHAT_FEEDBACK_BASE_GOLDEN_CASE_NUMBER` | `120` | Último número `G<NNN>` del archivo `eval/golden.jsonl` base, a partir del cual se sugiere el siguiente identificador libre (`G121`, …) |
