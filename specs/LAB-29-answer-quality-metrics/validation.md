# Validación: LAB-29 Métricas de calidad de respuesta

Registro de lo comprobado durante la implementación. Cada sección indica la tarea de `tasks.md` y lo que se observó.

## T001 · Línea base (2026-11-18)

| Comprobación | Resultado |
|---|---|
| `eval`: `uv sync --frozen`, `ruff check .`, `pytest` | En verde: 219 pruebas, cobertura 98,87 % |
| `eval`: `python -m shoplab_eval validate` | 120 casos: 76 con respuesta, 12 sin respuesta del tema, 8 `off_topic`, 12 `small_talk`, 12 `injection`; 15 casos de dos documentos |
| `infra/litellm`: `uv sync --frozen`, `ruff check .`, `pytest` | En verde: 72 pruebas |
| Línea base de DEV (`make up` sobre `main` de `shoplab-infra`, recomendada) | **No realizada**: Docker Desktop estaba parado. Se hará en T053 |

Aviso benigno de `uv` en la máquina de desarrollo: la variable `VIRTUAL_ENV` apunta a `eval/.venv` y `uv` la ignora al trabajar en
`infra/litellm`.

## T010 · Verificaciones S-1, S-2 y S-5 con modelo real (2026-11-18)

Entorno: `docker compose` de este repo con GPU (RTX 5070, 12 GB): Ollama, LiteLLM y su base; clave virtual `eval-local` creada por
`litellm-bootstrap`. Script desechable (no versionado) con `AsyncOpenAI` apuntando al gateway, `llm_factory("judge", client=…,
temperature=0)`, `embedding_factory("openai", "embeddings", client=…, interface="modern")` y las tres métricas de
`ragas.metrics.collections` sobre `eval/tests/calibration_pairs.py`.

**Acceso de la clave `eval`**: `GET /v1/models` lista solo `['embeddings', 'judge']` y `GET /model/info` devuelve el modelo real
detrás de cada nombre lógico (`judge -> ollama_chat/…`), así que `judge_underlying_model` se puede leer con esa clave.

### S-1 · ¿El juez por defecto discrimina?

| Juez | Modo de `instructor` | Fidelidad (inventada < fiel) | Relevancia (otra cosa < pertinente) | Precisión (útil al final < útil primero) | Resultado |
|---|---|---|---|---|---|
| `llama3.2:3b` (el del chat) | por defecto (herramientas) | no guardé la salida completa | no guardé la salida completa | errores de salida estructurada en 4 de las 5 llamadas que vi (G057, G016 y las dos de S-5) | **No sirve** (fallos en precisión y en S-5; solo vi el tramo final de la ejecución) |
| `llama3.2:3b` | `JSON` | **0 de 5**: 8 de 10 llamadas con error y 2 con `NaN` | **0 de 2**: las dos respuestas pertinentes fallan y las «otra cosa» puntúan 0,000 | **0 de 3**: una tripleta empata en 1,000 y las otras fallan o no discriminan | **No sirve** |
| **`qwen2.5:7b`** | por defecto | **5 de 5** (1,00 frente a 0,50–0,83) | **2 de 2** (0,695 frente a 0,587; 0,739 frente a 0,479) | **3 de 3** (1,000 frente a 0,333) | **Sirve** |

Causa del fallo del 3B: con salida estructurada devuelve el **esquema JSON** (`{'description': 'Structured output…'}`) en lugar
de los valores, y `instructor` agota los reintentos (errores `ContextPrecisionOutput`, `StatementGeneratorOutput`). No es un
problema de modo: pasa igual con `JSON`.

**Decisión**: el juez por defecto pasa a ser **`qwen2.5:7b`** (~4,7 GB; caben en la GPU de 12 GB junto al chat de 3B y los
embeddings). Se aplicó en `infra/litellm/config.yaml` (`judge` → `${LITELLM_JUDGE_MODEL:-${OLLAMA_JUDGE_MODEL:-qwen2.5:7b}}`) y,
como el modelo de respaldo, Ollama solo lo descarga si se define `OLLAMA_JUDGE_MODEL` (`infra/ollama/entrypoint.sh`, healthcheck
y `.env.example`). No hace falta el modo `JSON`: basta el de por defecto.

Tiempos con `qwen2.5:7b`: 24 llamadas en 1 min 48 s; una llamada tarda unos 2–5 s (la primera, 34 s por cargar el modelo; la
relevancia, 5–13 s por sus tres generaciones y los embeddings). Estimación por caso con respuesta: abstención + fidelidad +
relevancia + precisión ≈ 15 s, es decir ≈ 25 min para los 96 casos: a confirmar en T026 (S-4).

### S-2 · `AnswerRelevancy` con los embeddings del gateway

Funciona con `embedding_factory("openai", "embeddings", client=client, interface="modern")` sobre el mismo cliente: valores
0,48–0,74, con la dimensión de `bge-m3`. Aviso: relevancia pertinente/otra cosa se separa poco en un par (0,695 frente a 0,587),
así que la relevancia tiene el menor margen de las tres métricas; la dirección es correcta en 2 de 2.

### S-5 · Fidelidad en casos límite

| Entrada | Resultado de RAGAS | Consecuencia |
|---|---|---|
| Negativa («No tengo información…») con contexto | **0,5** (ni vacío ni 1) | Confirma que hace falta la comprobación de abstención: sin ella la negativa puntúa un 0,5 arbitrario |
| Sin afirmaciones («¡Hola! Gracias…») | 0,667 (no `NaN`) | La regla «`NaN` → vacío» es defensiva: no se observó `NaN` |
| `retrieved_contexts=[]` (respuesta afirmativa o negativa) | **`ValueError: retrieved_contexts is missing`** | Confirma que **no se puede llamar a RAGAS sin contexto**: lo resuelve la orquestación (fidelidad 0 sin llamar al juez, o vacía si abstiene) |

## T011 · Verificación S-3 con Langfuse 4.77.0 (2026-11-18)

Entorno: perfil `llmops` de `docker compose`; claves del proyecto locales `pk-lf-shoplab-local` / `sk-lf-shoplab-local` (marcadores
del compose, no secretos). Traza creada por OTLP JSON (`POST /api/public/otel/v1/traces`, cabecera `x-langfuse-ingestion-version: 4`)
con el `traceId` derivado de `X-Request-Id = eval-s3-G001` (`SHA-256[:32]`).

Esta versión corre en modo **`events_only`** y cambia varias cosas respecto a lo supuesto en el plan:

| Qué | Resultado |
|---|---|
| `GET /api/public/health` | 200 |
| Comprobación de acceso | `GET /api/public/traces?limit=1` → **404** (no existe en este modo). Sirve **`GET /api/public/projects`**: 200 con claves válidas, 401 sin ellas |
| Esperar la traza | `GET /api/public/traces/{id}` → **404 siempre** (no disponible en `events_only`). Sirve **`GET /api/public/v2/observations?traceId={id}&limit=1`**: devuelve la observación ~2 s después de enviar el OTLP (campos `traceId`, `name`, `environment`) |
| `POST /api/public/ingestion` con `trace-create` | Rechazado (400 «Event type not accepted»): las trazas solo entran por OTLP |
| `POST /api/public/scores` sobre una traza existente | 200 con `{"id": …}` |
| `POST /api/public/scores` **antes** de que exista la traza | 200; el score queda guardado con el mismo `trace_id` y la traza ingerida después lo recoge (no se pierde) |
| Idempotencia por `id` | Verificada en ClickHouse: dos `POST` con el mismo `id` (valores 0,25 y 0,75) dejan **una sola fila con 0,75**, con y sin `FINAL` |
| Leer scores por API (`/api/public/scores`, `/api/public/v2/scores`, `…/{id}`) | **404** en `events_only` («Scores can still be sent via POST /api/public/scores»): los scores **no se pueden leer por HTTP** |

Consecuencias para el diseño (aplicadas en `research.md`, `contracts/external-interfaces.md` y `tasks.md`):
1. Comprobación de acceso con `GET /api/public/projects`; esperar la traza con `GET /api/public/v2/observations?traceId=…`.
2. La espera de la traza ya **no es necesaria para publicar** un score (se guarda igual); solo sirve para la sonda («¿las trazas del
   chat están activas?») y para leer el modelo del asistente.
3. SC-005 (puntuaciones de la traza = detalle del CSV) no se puede comprobar por la API de Langfuse: se comprueba contra
   ClickHouse (`SELECT id, name, value FROM scores FINAL WHERE …`) o en la interfaz.
4. En el spike quedaron en el Langfuse local una traza `chat-turn` (`eval-s3-G001`) y dos scores `s3-*`; son datos de prueba.

## T012 · Checkpoint (2026-11-18)

- **S-1**: cumplida **con cambio de diseño**: el juez por defecto es `qwen2.5:7b`, no el modelo del chat.
- **S-2**: cumplida.
- **S-3**: cumplida con ajustes de los endpoints de Langfuse (arriba).
- **S-5**: cumplida; confirma las reglas de abstención y de contexto vacío del plan.
- Pendiente de medir: S-4 (tiempo total y ruido) en T026.

## T033 · Hallazgo con una traza real: el modelo de la traza es el nombre lógico (2026-11-18)

Con el backend local (trazas activadas) y una traza real (`eval-smoke-G002`), `GET /api/public/v2/observations?traceId=…&fields=core,basic,model`
devuelve tres observaciones (`chat-turn`, `retrieval`, `generation`) y la `generation` trae **`model: 'chat'`**: el nombre lógico
que el backend pide al gateway, no el modelo real (`llama3.2:3b`).

**Consecuencia**: no se lee el modelo del asistente de la traza (sería una etiqueta engañosa que dos resultados compartirían
aunque cambie el modelo detrás). `assistant_model` sale solo de `--assistant-model` o `SHOPLAB_EVAL_ASSISTANT_MODEL`
(si falta, `unknown` y se avisa). La traza se usa únicamente como sonda (¿están activas las trazas del chat?).
Se retira `trace_model` del plan y de las tareas.

## T026 · Medición real completa sin Langfuse (2026-11-18)

Entorno: backend local (Spring Boot, `mvn spring-boot:run`) con una base **desechable** en el puerto 5433 (la contraseña de
`infra/.env` no autentica contra el volumen `pgdata` existente y no se tocó), los 24 documentos de `data/kb` indexados, gateway
con `judge = qwen2.5:7b`, Ollama **con GPU** (RTX 5070) y trazas del backend activadas. Comando:
`quality --no-langfuse --run-id t026-2` con `SHOPLAB_EVAL_ASSISTANT_MODEL=llama3.2:3b`.

**Una lección de entorno**: la primera pasada (`t026-1`) corrió con Ollama **en CPU** porque un `docker compose up` sin el archivo
`docker-compose.gpu.yml` recreó el contenedor sin GPU (el juez avanzaba un caso cada varios minutos). Se detuvo y se repitió con
GPU. Toda medición de tiempo de este documento es con GPU; en CPU no es viable (la calibración del juez tardó 7 min 40 s para 11
pruebas).

| | Resultado |
|---|---|
| **S-4 · tiempo total (96 casos, GPU)** | **520 s (8 min 40 s)**, código de salida 0. SC-001 pide menos de 30 minutos: **cumplido con holgura** (con GPU) |
| Casos medidos / excluidos | 96 / 24 (conversación) |
| Fidelidad (media, n, vacías) | 0,7648 (n=63, vacías=33) |
| Relevancia de la respuesta | 0,4594 (n=76) |
| Precisión del contexto | 0,7343 (n=76) |
| Abstenciones | 33 (de ellas, 16 en casos sin respuesta y **17 en casos con respuesta**) |
| Fidelidad vacía sin abstención | 0 (RAGAS no devolvió `NaN` en ningún caso) |
| Casos con el contexto vacío | 24 |

**Lectura de los números**: el asistente actual (llama3.2:3b, umbral de similitud del chat) se abstiene en 17 de los 76 casos que
sí tienen respuesta y la relevancia media es 0,46; es la línea base que LAB-30 usará, no un fallo de la medición. En los casos
`off_topic` la fidelidad es 0,0000 con n=2: G088 (traducir una frase) y G090 (ayuda con un currículum) son peticiones ajenas a
la tienda que el asistente **atendió** en lugar de rechazarlas; su respuesta no tiene respaldo en ningún fragmento (G088 llegó
con el contexto vacío, así que sale 0 por la regla; G090 con un fragmento, y el juez le dio 0). Es justo lo que la fidelidad
debe señalar.

### SC-004 · el resumen se reproduce desde el detalle

Un script recalculó cada media, `_n` y `_empty` del resumen desde el detalle: **una discrepancia** (`shipping`, relevancia:
resumen 0,5904 y recálculo 0,590453). No era un error de cálculo sino **doble redondeo** (la media real redondeada frente a la
media de celdas ya redondeadas). Como SC-004 y SC-005 piden coincidencia, se corrigió en origen: cada puntuación se redondea a
los 4 decimales del CSV al calcularla (`quality_run._rounded`, con su prueba), de modo que el CSV, las medias y los scores de
Langfuse son los mismos números.

### Revisión a mano de tres casos (respuestas reales)

| Caso | Qué se vio | Consecuencia |
|---|---|---|
| G025 (privacidad, con respuesta) | Respuesta correcta y fiel, con citas; puntuada fidelidad 1,0000, relevancia 0,9004, precisión 1,0000 | Las tres métricas se comportan |
| G005 (pagos, con respuesta) | El asistente afirma que se puede combinar un vale con tarjeta y solo dice que lo de las cuotas «no se indica». El juez la marcó como **abstención** (fidelidad vacía): **falso positivo** | Prompt de abstención v2: el juez lista antes los datos concretos y el código impone la coherencia. Comprobado con el juez real: negativa → abstención, afirmación → no, **respuesta parcial → no** |
| G077 (devoluciones, sin respuesta) | La respuesta del asistente es solo `[2]`; RAGAS le dio **fidelidad 1,0000** | Regla de «respuesta sin contenido»: fidelidad vacía, relevancia 0 en casos con respuesta, sin abstención |

Los dos defectos de diseño (B y C) solo aparecieron al leer respuestas reales; no los habría encontrado ninguna prueba con
dobles. Tras corregirlos se volverá a medir (T059).

## T032 · Juez configurable y registrado, contra el gateway real (2026-11-18)

- **La clave `eval` solo ve `judge` y `embeddings`** (`GET /v1/models`) y lee el modelo real detrás de cada nombre en
  `GET /model/info` (`judge -> ollama_chat/qwen2.5:7b`).
- **Cambiar de juez no toca código**: con `LITELLM_JUDGE_MODEL=qwen2.5:7b` el gateway recreado respondió `judge ->
  ollama_chat/qwen2.5:7b`; el resumen de la medición real registra `judge_model = judge` y `judge_underlying_model =
  ollama_chat/qwen2.5:7b`.
- **`--judge-model chat`** (un modelo que la clave no puede llamar): `El gateway http://localhost:4000/v1 no deja a esta clave
  llamar al modelo 'chat' del juez; modelos disponibles: embeddings, judge`; código 1, **sin preguntar al asistente** y sin
  escribir nada.
- **Clave equivocada**: `El gateway … rechazó la clave del juez (401): comprueba la variable SHOPLAB_EVAL_JUDGE_API_KEY`;
  código 1, nada escrito y la clave no aparece en la salida.

## S-4 · Ruido entre dos ejecuciones idénticas (SC-009) (2026-11-18)

Dos ejecuciones seguidas (`t026-2` y `t026-3`), mismo dataset, mismo backend, mismo asistente (`llama3.2:3b`) y mismo juez
(`qwen2.5:7b`, temperatura 0, `seed` fijo), con el prompt de abstención v1. La segunda tardó también unos 8,7 minutos.

| Ámbito | Fidelidad | Relevancia | Precisión del contexto |
|---|---|---|---|
| **Conjunto completo (`all`)** | 0,7648 → 0,7562 (**0,0086**) | 0,4594 → 0,4576 (**0,0018**) | 0,7343 → 0,7346 (**0,0003**) |
| Mayor diferencia en una categoría | 0,2836 (`returns`; `off_topic` 0,25 con n=2) | 0,0870 (`payments`) | 0,0037 (`warranty`) |

- **El conjunto completo cumple SC-009** con mucha holgura: las tres medias globales difieren menos de 0,01 (el criterio es 0,05).
- **Las categorías, no**: con 15 a 19 casos cada una, la fidelidad llega a variar 0,28 y la relevancia 0,09. La precisión del contexto
  es casi determinista (la recuperación apenas cambia; diferencia máxima por categoría 0,0037).
- **Por caso hay mucho ruido**: la fidelidad cambia de media 0,25 entre ejecuciones y la relevancia 0,22; 13 de 96 casos
  cambian de abstención. La fuente principal es que **el asistente (3B) no contesta igual dos veces** (otra respuesta, otra
  decisión de abstenerse o no) más la variabilidad del juez; además, una abstención saca el caso de la media de fidelidad y
  cambia su `n` (en `returns` pasó de 12 a 14), lo que mueve la media de la categoría.

**Qué implica para LAB-30**: fijar umbrales y comparar líneas base sobre la fila **`all`**, no sobre una categoría; para
categorías, usar una tolerancia que dependa de `n` (no menos de ±0,15 en fidelidad con unos 15 casos) o repetir la medición y
promediar. Un caso aislado no se puede usar para decidir.

**Pendiente de decisión**: SC-009 dice «medias por métrica» sin precisar el ámbito. Se cumple para el conjunto completo y no para
cada categoría; conviene precisar el criterio (ver el resumen de la implementación).
