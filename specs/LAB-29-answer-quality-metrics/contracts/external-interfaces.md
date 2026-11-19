# Contrato: interfaces externas que consume `eval/`

`quality` no expone nada nuevo: consume tres interfaces que ya existen. Aquí queda fijado **qué usa de cada una**, para que un
cambio en ellas se detecte (las pruebas del cliente se escriben contra esto).

## 1. Chat: `POST /api/chat` (SSE)

Contrato completo: `specs/LAB-12-chat-streaming-sse/contracts/chat-stream.md` (y LAB-23 para `sources`).

Petición:

```http
POST {baseUrl}/api/chat
Content-Type: application/json
Accept: text/event-stream
X-Request-Id: eval-20261118T153000Z-G001

{ "message": "<question>" }
```

En DEV la petición va al ingress y, si se indica `--host-header`, lleva `Host: shoplab.localtest.me` (el mismo mecanismo de
`shoplab-infra/scripts/seed.sh`); sin la opción no se añade ninguna cabecera.

**No se envía `conversationId`**: sin él el chat no tiene memoria ni guarda nada (LAB-24 conserva las conversaciones sin
caducidad, y cada ejecución dejaría 96 permanentes). Cada turno es independiente.

| Parte del flujo | Qué toma `eval/` | Si falla |
|---|---|---|
| Evento `sources` (0 o 1, antes del primer `token`) | `sources[]` con `number`, `title`, `section`, `text`, en orden | Ausente = contexto vacío (no es un error) |
| Eventos `token` | Concatenación en orden de `content` = respuesta | — |
| Evento `done` | Marca el turno como completo | Sin `done` (corte o `error`) → `MeasurementError` con el caso |
| Evento `error` | `code` y `message` | `MeasurementError`: «El asistente falló en el caso G0xx: SLHD_CHAT_… » |
| HTTP no 200 antes del flujo | `code` del cuerpo JSON | 400, 422, 429 `SLHD_CHAT_00007`, 503 `SLHD_CHAT_00002`: `MeasurementError` con el caso |
| Sin respuesta / conexión rechazada / tiempo agotado | — | `MeasurementError`: «No se pudo conectar con el backend en …» |

Cabecera de respuesta `X-Request-Id`: debe coincidir con la enviada; si el backend la reemplazó (identificador no seguro),
`MeasurementError` (la traza no sería la esperada).

No se reintenta ningún turno.

## 2. Juez: gateway de LiteLLM (API compatible con OpenAI)

```text
base_url = http://localhost:4000/v1      api_key = $SHOPLAB_EVAL_JUDGE_API_KEY
chat.completions  model = <judge-model>  (por defecto el nombre lógico `judge`)   temperature, seed
embeddings        model = embeddings                                              (solo AnswerRelevancy)
```

| Comprobación previa | Cómo | Si falla |
|---|---|---|
| Gateway vivo y clave válida | `GET {base_url}/models` con la clave | `MeasurementError`: nombra el gateway y el código HTTP (401 = clave, conexión = caído) |
| El modelo juez existe para la clave | su nombre está en la lista de `/models` | `MeasurementError` que nombra el modelo |
| Modelo real detrás del nombre lógico | `GET {gateway}/model/info`, entrada cuyo `model_name` es el pedido; se toma `litellm_params.model` | No es un error: se registra `unknown` y se avisa en pantalla |
| Presupuesto agotado durante la medición | HTTP 422 `budget_exceeded` del gateway | `MeasurementError` del caso y la métrica |

La clave no aparece en ningún mensaje, registro ni CSV.

## 3. Langfuse: scores por REST

Autenticación Basic `public:secret`. Verificado contra Langfuse 4.77.0 en modo `events_only` (`validation.md`, T011), donde los
endpoints de lectura de trazas y de scores **no existen** (404). Tres operaciones:

```http
GET  {host}/api/public/projects                                   # comprobar el acceso: 200 con claves válidas, 401 si no
GET  {host}/api/public/v2/observations?traceId={traceId}&limit=1  # ¿existe la traza?: data vacío hasta que se ingiere (~2 s)
POST {host}/api/public/scores
Content-Type: application/json

{
  "id": "<score_id>",
  "traceId": "<trace_id>",
  "name": "faithfulness",
  "value": 0.8333,
  "dataType": "NUMERIC",
  "comment": "judge=judge; ragas=0.4.4",
  "metadata": { "run_id": "20261118T153000Z", "case_id": "G001", "judge_model": "judge" }
}
```

| Nombre de score | Cuándo existe |
|---|---|
| `faithfulness` | Casos `answered` y `unanswered` con valor |
| `answer_relevancy` | Casos `answered` con valor |
| `context_precision` | Casos `answered` (incluido `0` por contexto vacío) |

- No se publica un score para una celda vacía.
- `id` determinista: repetir la publicación **actualiza** el mismo score, no lo duplica (verificado en ClickHouse: dos `POST`
  con el mismo `id` dejan una sola fila con el último valor). La respuesta es 200 con `{"id": "<score_id>"}`.
- Un score se guarda aunque su traza aún no exista (S-3), así que publicar no exige esperarla; sí se comprueba la traza antes
  de publicar para no dejar scores huérfanos, y si no aparece el caso cuenta como no adjuntado.
- **Los scores no se pueden leer por HTTP** en este modo: SC-005 se comprueba con ClickHouse
  (`SELECT id, name, value FROM scores FINAL WHERE id IN (…)`) o en la interfaz de Langfuse.
- `traceId` derivado: `SHA-256(X-Request-Id)[:32]` (ADR-0022 §4).
- Un 401/403 al empezar es un error de configuración (`MeasurementError`, antes de consultar al asistente); un fallo al publicar
  durante el cierre se acumula y se informa (el CSV se escribe y el código de salida es 1).
- Reconocer las trazas de evaluación en la interfaz de Langfuse: filtro por el metadato `request_id` que empiece por `eval-`
  (todas) o por `eval-<run_id>-` (una ejecución).
