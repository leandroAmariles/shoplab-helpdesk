# Gateway de modelos (LiteLLM)

Toda llamada a un modelo —el chat del backend y los embeddings de `retrieval-service`— sale hacia un único gateway: [LiteLLM](https://docs.litellm.ai/)
con API compatible con OpenAI. Decide qué modelo responde, cae a un modelo de respaldo si el principal falla, aplica un
presupuesto por clave virtual y deja en sus logs los tokens y el coste de cada petición. La decisión está en
[ADR-0023](adr/0023-gateway-de-modelos-litellm.md).

```text
backend ──(su clave virtual, nombre lógico chat)──────────────▶ litellm ──▶ ollama (local, único camino)
retrieval-service ──(su clave virtual, embeddings)────────────▶    │
ingest  ──(su clave virtual, hoy sin llamadas a modelos)──────▶    │
                                                                   └──▶ proveedor cloud (opcional, sin clave = no existe)
```

Funciona al 100 % en local sin claves de nube. Los archivos están en `infra/litellm/`:

| Archivo | Para qué |
|---|---|
| `config.yaml` | Nombres lógicos, modelos reales, precios de referencia, fallback y ajustes de logs (plantilla: `${VAR:-defecto}`) |
| `keys.yaml` | Una clave virtual por consumidor y entorno, con presupuesto y periodo de reinicio |
| `bootstrap_keys.py` | Crea o actualiza esas claves (idempotente); lo ejecuta `litellm-bootstrap` |
| `shoplab_callbacks.py` | Escribe una línea JSON por petición, sin contenido |
| `check_models.py` | Avisa al arrancar de los modelos de Ollama que faltan |
| `render_config.py`, `entrypoint.sh` | Renderizan la plantilla y lanzan el gateway |

## Levantar el entorno local

```bash
docker compose -f infra/docker-compose.yml up -d --wait
docker compose -f infra/docker-compose.yml ps        # litellm y litellm-postgres healthy; litellm-bootstrap exited (0)
curl -s http://localhost:4000/health/liveliness
```

`litellm-bootstrap` crea las claves `backend-local`, `retrieval-local`, `ingest-local` y `eval-local` la primera vez (`created alias=…`) y no hace nada
las siguientes (`unchanged`). El backend usa `http://localhost:4000/v1` y la clave `sk-shoplab-backend-local`
(`SPRING_AI_OPENAI_BASE_URL`, `SPRING_AI_OPENAI_API_KEY`); son marcadores que solo valen en local.

Ollama **no publica puerto**: solo comparte la red `models` con el gateway, así que ni `ingest` ni el backend llegan a él.
Para publicarlo (depuración, mediciones o el Ollama del host que consume el clúster DEV):
`-f infra/docker-compose.yml -f infra/docker-compose.ollama-host.yml`.

## Nombres lógicos y cambiar de modelo

El backend pide solo `chat` (más `chat-cloud` si activas un proveedor) y `retrieval-service`, solo `embeddings`. El modelo real vive en el gateway:

| Nombre lógico | Variable | Defecto |
|---|---|---|
| `chat` | `LITELLM_CHAT_PRIMARY_MODEL` (o `OLLAMA_CHAT_MODEL`) | `llama3.2:3b` |
| `chat-fallback` | `LITELLM_CHAT_FALLBACK_MODEL` | `llama3.2:1b` |
| `embeddings` | `LITELLM_EMBED_MODEL` (o `OLLAMA_EMBED_MODEL`) | `bge-m3` |

Para cambiar el modelo de chat sin tocar ni reiniciar el backend: pon la variable en `infra/.env` y
`docker compose -f infra/docker-compose.yml up -d litellm` (recrea solo el gateway). Si editas el `config.yaml` montado en
lugar de una variable, usa `docker compose -f infra/docker-compose.yml restart litellm`. El modelo debe estar descargado en
Ollama; si falta, el gateway lo avisa al arrancar con una línea `WARN model_missing model=… name=…` y no se rompe.

> **Cambiar el modelo de embeddings con otra dimensión de vector exige reindexar** (ADR-0003, ADR-0017): la columna es
> `vector(1024)` y `retrieval-service` rechaza un vector de otro tamaño (`EMBEDDING_DIMENSION_MISMATCH`) en lugar de mezclarlos.
> Si cambias el modelo, ajusta también en `retrieval-service` `SHOPLAB_RETRIEVAL_EMBEDDING_MODEL_NAME` (el nombre que
> se guarda con cada vector).

`notebooks/` sigue usando Ollama directamente: son experimentos y nunca los importa código de producción.

## Juez de evaluación (`judge`)

La evaluación de calidad de respuesta (LAB-29, [`eval/README.md`](../eval/README.md)) pide a un modelo que puntúe cada
respuesta del asistente. Ese **juez** es otro nombre lógico del gateway, `judge`, con su propia clave virtual `eval`
(solo llama a `judge` y a `embeddings`, con presupuesto propio, así que medir no gasta el del backend ni puede salir a un
proveedor externo).

| Nombre lógico | Variable | Defecto |
|---|---|---|
| `judge` | `LITELLM_JUDGE_MODEL` (o `OLLAMA_JUDGE_MODEL`) | `qwen2.5:7b` |

- **No es el modelo del chat.** `llama3.2:3b` no sirve de juez: con salida estructurada devuelve el esquema JSON en lugar de
  rellenarlo y las tres métricas fallan (`specs/LAB-29-answer-quality-metrics/validation.md`, S-1). `qwen2.5:7b` sí
  discrimina (fidelidad, relevancia y precisión del contexto en los pares de calibración) y cabe en una GPU de 12 GB junto al
  chat y los embeddings.
- **Ollama solo lo descarga si lo pides**, igual que el modelo de respaldo: pon `OLLAMA_JUDGE_MODEL=qwen2.5:7b` en `infra/.env`
  y `docker compose -f infra/docker-compose.yml up -d ollama` (unos 4,7 GB). Sin la variable el gateway arranca igual, pero
  avisa con `WARN model_missing … name=judge` y la evaluación falla al llamar al juez.
- **Cambiar de juez no toca código**: `LITELLM_JUDGE_MODEL=<otro modelo de Ollama> docker compose -f infra/docker-compose.yml up
  -d litellm`. El nombre lógico sigue siendo `judge`, así que el resultado de la evaluación registra también el modelo real
  detrás (`judge_underlying_model`, que la clave `eval` lee de `GET /model/info`): dos resultados solo son comparables si
  coinciden.
- **Sin fallback**: caer a otro modelo cambiaría las puntuaciones sin avisar.

## Demostrar el fallback (menos de 5 minutos, sin claves de nube)

1. Define el modelo de respaldo **antes del primer arranque**, para que Ollama lo descargue:
   `OLLAMA_FALLBACK_MODEL=llama3.2:1b` en `infra/.env`, y `docker compose -f infra/docker-compose.yml up -d --wait`.
2. Haz inalcanzable el principal apuntándolo a un modelo que no existe, sin editar código:
   `LITELLM_CHAT_PRIMARY_MODEL=modelo-que-no-existe docker compose -f infra/docker-compose.yml up -d litellm`.
3. Haz una pregunta al chat. La respuesta llega completa y sin error. En el log del gateway:

   ```bash
   docker compose -f infra/docker-compose.yml logs --no-log-prefix litellm | jq -c 'select(.fallback==true)' | tail -1
   ```

   muestra `fallback: true` y `served_model_id: chat-fallback`. En la traza de Langfuse (perfil `llmops`) el modelo del turno es
   `chat-fallback` y el turno lleva `fallback = true`.
4. Restaura la variable y recrea el gateway: las preguntas vuelven al principal (`fallback: false`). Tras un fallo, el
   despliegue caído sale de rotación `cooldown_time` (30 s) y vuelve solo.

Reglas: el fallback solo ocurre **antes del primer fragmento** (fallo, o más de `stream_timeout` sin empezar). Un fallo a
mitad de respuesta no reinicia con otro modelo: el usuario recibe el error final con el texto parcial. Si principal y
respaldo fallan, el usuario recibe «modelo no disponible» (503) tras un número acotado de intentos. Los embeddings no tienen
fallback (otra dimensión corrompería el índice).

## Claves virtuales y presupuestos

Cada consumidor (`backend`, `retrieval`, `ingest` y `eval`) tiene una clave por entorno (`backend-local`, `backend-dev`…) con un presupuesto en USD
de referencia y un periodo de reinicio (por defecto `1d`), definidos en `keys.yaml`. Los modelos, incluso los locales, tienen
un precio de referencia en `config.yaml` (no es dinero real), así que el gasto sube y el presupuesto se puede ejercitar sin
nube. Para cambiar un presupuesto, edita `keys.yaml` y ejecuta:

```bash
docker compose -f infra/docker-compose.yml run --rm litellm-bootstrap      # created / updated / unchanged
```

Cuando una clave agota su presupuesto, el gateway rechaza sus peticiones (HTTP 422, `type: budget_exceeded`) hasta el reinicio del
periodo; el chat responde **429 `SLHD_CHAT_00007`** (los embeddings, `SLHD_RETR_00010`), distinto del 503 `SLHD_CHAT_00002` de
«modelo no disponible». Las demás claves no se ven afectadas. Una clave inexistente es 401 y un modelo que la clave no puede
llamar es 403; ambos llegan al usuario como «modelo no disponible».

Las claves, los presupuestos y el gasto viven en `litellm-postgres` y sobreviven a los reinicios. Si se pierde esa base,
las claves y los presupuestos se recrean con `litellm-bootstrap`; el gasto acumulado del periodo puede perderse.
Se tolera un pequeño exceso de las peticiones ya en vuelo cuando se agota el presupuesto.

## Coste y tokens por conversación

Cada petición deja una línea JSON en el log del gateway (`event: llm_request`) con consumidor, conversación, id de la petición
del chat (el mismo `X-Request-Id` del backend y de las trazas), modelo pedido y servido, fallback, tokens, coste, latencia y
resultado. **Nunca** lleva texto de preguntas, respuestas ni contexto, ni claves ni cabeceras de autorización. Las
búsquedas y la indexación de documentos van atribuidas a la operación (`operation: search-query | document-index`), no a una
conversación. Los rechazos (presupuesto, clave, modelo) también dejan su línea, con tokens y coste `null`.

Tokens y coste de una conversación (menos de 1 minuto):

```bash
docker compose -f infra/docker-compose.yml logs --no-log-prefix litellm \
  | jq -s --arg c "<conversation-id>" '
      map(select(.event=="llm_request" and .conversation_id==$c))
      | { turns: length,
          prompt_tokens: (map(.prompt_tokens // 0) | add),
          completion_tokens: (map(.completion_tokens // 0) | add),
          cost_usd: (map(.cost_usd // 0) | add),
          unpriced_or_unknown: (map(select(.cost_status!="priced")) | length) }'
```

`unpriced_or_unknown` > 0 avisa de que la suma no es completa. El `conversation_id` es el que envías en `POST /api/chat`
(`conversationId`); sin él, el turno no se agrupa. Esquema completo: `specs/LAB-84-litellm-model-gateway/contracts/log-schema.md`.

> **Dos mediciones de coste.** El gateway usa precios de referencia para todos los modelos; las trazas de LAB-64 conservan su
> propia tabla (Ollama = 0, ADR-0022). Los números pueden no coincidir; el del gateway es el que se presupuesta.

## Proveedor cloud opcional

Sin `CLOUD_PROVIDER_API_KEY` el modelo `chat-cloud` **no existe** (ni sale a Internet). Para activarlo pon en `infra/.env`
`CLOUD_PROVIDER_API_KEY`, `CLOUD_PROVIDER_MODEL` (p. ej. `openai/<modelo>`) y, si hace falta, `CLOUD_PROVIDER_API_BASE`;
recrea `litellm` y ejecuta `litellm-bootstrap` (la clave del backend pasa a poder llamarlo). Queda sujeto a presupuesto y
a registro de coste como cualquier modelo, y **ninguna petición del chat lo usa** salvo que lo configures como principal o
respaldo. No versiones nunca esa clave. Las pruebas usan un servidor simulado compatible, no una cuenta real.

## Gateway del clúster (DEV)

El gateway corre en `shoplab-dev` (repo `shoplab-infra`, `k8s/base/litellm/`), con su Postgres y un Job de Argo CD
(`PostSync`) que crea las claves; el backend de DEV lo usa con la clave `backend-dev` entregada por `ExternalSecret`. No se
publica fuera del clúster: se accede con `kubectl -n shoplab-dev port-forward svc/litellm 4000:4000`. Los logs y el coste por
conversación se leen igual con `kubectl -n shoplab-dev logs deploy/litellm | jq …`. Ver `docs/gateway-de-modelos.md` de
`shoplab-infra`. El Ollama de DEV corre en el Docker del host: levántalo con
`docker compose -f infra/docker-compose.yml -f infra/docker-compose.ollama-host.yml up -d ollama`.
