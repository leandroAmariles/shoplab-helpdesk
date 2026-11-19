# Research: Métricas de calidad de respuesta

Decisiones que el plan necesitaba resolver. No quedan `NEEDS CLARIFICATION`; lo que depende de un modelo real se lista en
«Verificaciones previas» y se ejecuta **antes** de construir el resto. Cada decisión indica qué se decidió, por qué y qué se
descartó.

## R1. RAGAS o DeepEval

**Decisión**: **RAGAS 0.4.4**, fijada (`ragas==0.4.4`), con la API `ragas.metrics.collections`: `Faithfulness`,
`AnswerRelevancy` y `ContextPrecision`.

**Por qué**: se comprobó en un entorno aislado (Python 3.12) qué expone esa versión:

| Métrica | Constructor | `ascore(...)` |
|---|---|---|
| `Faithfulness` | `llm` | `user_input`, `response`, `retrieved_contexts` |
| `AnswerRelevancy` | `llm`, `embeddings` (`strictness=3`) | `user_input`, `response` |
| `ContextPrecision` | `llm` | `user_input`, `reference`, `retrieved_contexts` |

Son exactamente los datos que entrega un turno más la respuesta de referencia del dataset (FR-005). El juez es un
`InstructorBaseRagasLLM` que `llm_factory(model, client=AsyncOpenAI(base_url=…))` construye sobre cualquier cliente
OpenAI-compatible, lo que permite apuntarlo al gateway (FR-009) y cambiarlo por configuración (FR-007). La telemetría se
apaga con `RAGAS_DO_NOT_TRACK=true`, variable que el código fija antes de importar la biblioteca.

**Alternativas descartadas**:
- *DeepEval 4.3.4*: declara `posthog` (telemetría) y `confident-trace` como dependencias, y arrastra `pytest`, `pytest-xdist` y
  otros plugins en una herramienta de ejecución; su juez propio exige una subclase de `DeepEvalBaseLLM`. Mismo coste en
  dependencias que RAGAS con más superficie de privacidad.
- *Juez propio sin biblioteca*: ver Complexity Tracking del plan.

**Coste aceptado**: instalar `ragas` trae unas 95 dependencias (`langchain*`, `datasets`, `pandas`, `instructor`, `openai`).
Se mitiga con el grupo `quality` fijado en `uv.lock` y aislado en `quality_judge.py`. Se incluye en `default-groups` para que
`uv sync --frozen` (CI, README de LAB-27 y LAB-28) siga funcionando sin cambios; el precio es más tiempo de instalación en CI,
que `uv` cachea.

## R2. Juez: acceso, defecto y sesgo

**Decisión**: el juez es un modelo del gateway de LiteLLM, pedido con el cliente `AsyncOpenAI` (`base_url`
`http://localhost:4000/v1`) bajo el nombre lógico **`judge`**, con una clave virtual propia **`eval`** (modelos `judge` y
`embeddings`, presupuesto propio). `judge` apunta por defecto a **`qwen2.5:7b`**
(`${LITELLM_JUDGE_MODEL:-${OLLAMA_JUDGE_MODEL:-qwen2.5:7b}}`), **no** al modelo del chat: la verificación S-1 mostró que
`llama3.2:3b` no rellena las salidas estructuradas del juez (ver abajo). Los embeddings de `AnswerRelevancy` salen por el nombre
lógico `embeddings` ya existente.

Configuración por quien ejecuta (FR-007): `--judge-model` / `SHOPLAB_EVAL_JUDGE_MODEL`, `--judge-base-url` /
`SHOPLAB_EVAL_JUDGE_BASE_URL`, `--judge-temperature` (0 por defecto), `--judge-timeout`, `--max-input-chars`. La clave sale de
`SHOPLAB_EVAL_JUDGE_API_KEY` (FR-008).

**Por qué**: es lo que la historia 2 pide (juez configurable, local por defecto, sin cuenta externa) y lo que la Constitución VI
permite (el corpus es público y sintético). Cambiar de juez es cambiar una variable del gateway o del comando.

**El nombre lógico no identifica al juez.** `judge` es un alias: cambiar `LITELLM_JUDGE_MODEL` cambia el modelo real sin cambiar
el nombre, y dos resultados con `judge_model = judge` no serían comparables (FR-018). Por eso el resumen registra **dos
valores**: el nombre pedido (`judge_model`) y el modelo real detrás (`judge_underlying_model`), que `eval/` lee del gateway
(`GET /model/info`, filtrando por el nombre lógico) antes de empezar. Si la clave no puede leerlo, se escribe
`unknown` y el aviso lo dice en pantalla; S-1 comprueba que la lectura funciona con la clave `eval`.

**Resultado de S-1 (2026-11-18, `validation.md`)**: el plan proponía como defecto el mismo modelo del chat (3B) y declaraba el
riesgo de que no discriminara. **No discrimina**: con salida estructurada `llama3.2:3b` devuelve el esquema JSON en lugar de los
valores (también con el modo `JSON` de `instructor`), y las tres métricas fallan. **`qwen2.5:7b` sí**: fidelidad 5 de 5 pares,
relevancia 2 de 2 y precisión 3 de 3, con el modo por defecto. Por eso el defecto es `qwen2.5:7b` (~4,7 GB; caben en una GPU de
12 GB junto al chat y los embeddings). Consecuencias: (a) el juez ya no es el mismo modelo que responde, lo que además reduce la
indulgencia de un modelo que se puntúa a sí mismo; (b) Ollama carga dos modelos, y por eso la ejecución separa los turnos del
asistente de las llamadas al juez (R5); (c) como el modelo de respaldo, **solo se descarga si se define `OLLAMA_JUDGE_MODEL`**,
para que quien no evalúa no pague 4,7 GB; (d) el resultado sigue registrando el juez (FR-018) y un juez más fuerte se elige con
`LITELLM_JUDGE_MODEL`.

**Alternativas descartadas**: usar la clave `backend` (mezcla presupuestos, da privilegios de producción); un proveedor de
nube por defecto (exige cuenta y envía datos fuera; queda disponible por configuración con el modelo `chat-cloud` del gateway).

## R3. Cómo obtiene `eval/` el turno del asistente

**Decisión**: `POST /api/chat` por SSE con la biblioteca estándar, **sin cambios en el backend**:

- **Respuesta** = concatenación en orden de los `content` de los eventos `token`; el turno es válido solo si termina con
  `done`. Un evento `error`, una respuesta HTTP 4xx/5xx previa al flujo, o un corte sin `done` son `MeasurementError` que
  nombra el caso y el código (FR-003, FR-020). 429 `SLHD_CHAT_00007` (presupuesto del gateway agotado) es uno de ellos.
- **Contexto** = los fragmentos del evento `sources`, en orden (`number` 1…n): `title`, `section` y `text` «tal como se
  entregó al modelo» (LAB-23). Si no hay evento `sources`, el contexto está vacío (no hubo fragmentos que superaran el umbral).
- **`X-Request-Id`** = `eval-<run>-<id del caso>` (≤ 64 caracteres, caracteres seguros del `RequestIdFilter`).
- **Sin `conversationId`**: sin él el chat no tiene memoria (`ConversationId.optional`) y no guarda nada. Enviar uno por caso
  dejaría en el esquema `chat` una conversación por pregunta en cada ejecución, y LAB-24 las conserva **sin caducidad ni
  borrado** hasta que una historia futura defina la retención. Tampoco hace falta: cada turno es independiente y la traza se
  localiza por el `X-Request-Id`, no por la sesión.

**Por qué**: el contrato del chat ya entrega todo lo que las tres métricas necesitan, y juzgar lo que el cliente realmente
recibió (tras umbral y presupuesto, ADR-0018) es lo que la spec fija. `eval/` ya usa solo la biblioteca estándar (R1 de LAB-28)
y un fallo no se reintenta: reintentar escondería exactamente lo que hay que informar.

**El id del documento**: el evento `sources` trae el *título* (`Pago en cuotas`), no el nombre del archivo. El detalle del CSV
(FR-017) lleva ids de documento; se resuelven con el título del front matter de `data/kb`, que `eval/` ya lee (los 24 títulos
son únicos, comprobado). Un título que no está en el corpus (p. ej. un documento subido a mano) sale como `?<título>` y no
rompe nada. Se descartó añadir `source` al evento SSE: es un cambio de contrato del chat por un dato que ya se puede resolver.

**Alternativas descartadas**: leer el contexto de la traza de Langfuse (haría obligatorio Langfuse para medir y ataría la
medición a su retraso de ingesta); una prueba Java que escriba los turnos (dos lenguajes y dos procesos, como en R1 de LAB-28).

## R4. Reconocer las trazas de evaluación y adjuntar las puntuaciones

**Decisión**:

- **`traceId`** = `SHA-256(X-Request-Id)` en hexadecimal, primeros 32 caracteres (ADR-0022 §4). `eval/` lo calcula; no lo
  busca.
- **Reconocer y filtrar** (FR-013): el backend ya guarda el `X-Request-Id` en el metadato `request_id` de la traza; con el
  prefijo `eval-<run>-` se filtra por «es de evaluación» (`eval-`) y por ejecución (`eval-<run>-`). Las puntuaciones llevan
  además `metadata.run_id`, el juez y la versión de RAGAS. No se pueden poner etiquetas (`langfuse.trace.tags`) sin cambiar el
  backend, y no hace falta: el metadato basta. Si LAB-30 necesita etiquetas, se añade entonces una cabecera `X-Eval-Run`.
- **Puntuación**: `POST /api/public/scores` (autenticación Basic con la clave pública y secreta), cuerpo `{id, traceId, name,
  value, dataType: NUMERIC, comment, metadata}`. Nombres estables: `faithfulness`, `answer_relevancy`, `context_precision`.
- **Sin duplicados** (FR-012): `id` determinista = `SHA-256("<run>:<caso>:<métrica>")[:32]`; el campo `id` es la clave de
  idempotencia del endpoint, así que repetir la publicación actualiza la misma puntuación. Otra ejecución tiene otro `run`, y
  por tanto otras trazas y otros ids (FR-013).
- **Opcional** (FR-014): se adjunta si hay `SHOPLAB_EVAL_LANGFUSE_PUBLIC_KEY` y `…_SECRET_KEY` (más `…_HOST`, por defecto
  `http://localhost:3001`) y no se pasa `--no-langfuse`; con `--langfuse` explícito, faltar la configuración es un error de
  uso.
- **Comprobación previa** (FR-010, historia 3 escenario 6): (1) acceso a Langfuse con las claves, con
  `GET /api/public/projects` (200 con claves válidas, 401 sin ellas); (2) el **primer turno** de la ejecución hace de sonda: se
  espera su traza con `GET /api/public/v2/observations?traceId=<id>&limit=1` (hasta 30 s, la exportación del backend tarda unos
  2 s); si no aparece, las trazas del chat están apagadas o Langfuse no recibe, y se termina **antes** de los demás turnos y de
  cualquier llamada al juez. Cuesta una consulta al asistente.
- **Endpoints de Langfuse 4.77.0 (verificación S-3)**: el servidor corre en modo `events_only`, donde `GET /api/public/traces`,
  `GET /api/public/traces/{id}` y toda lectura de scores devuelven 404 («Scores can still be sent via POST
  /api/public/scores»). Por eso la existencia de una traza se consulta por **`v2/observations`**, y los scores **no se pueden
  leer por HTTP**: SC-005 se comprueba contra ClickHouse o en la interfaz. `POST /api/public/scores` devuelve 200 con `{"id"}`,
  guarda el score aunque la traza aún no exista y es idempotente por `id` (dos `POST` con el mismo `id` dejan una fila).
- **Orden de cierre**: calcular todo → adjuntar (acumulando fallos) → escribir el CSV con una columna `langfuse` por caso
  (`yes`, `no`, `skipped`) → salir con código 1 si alguna no se adjuntó (FR-014). Publicar no exige que la traza exista (se
  guardaría igual), pero un score huérfano no sirve para depurar: antes de publicar se comprueba la traza del caso (corta, ya
  exportada); si no aparece, ese caso cuenta como no adjuntado y se informa, sin publicar.

**Alternativas descartadas**: el SDK de Langfuse (otra dependencia, y ADR-0022 ya descartó un SDK propietario); consultar
Langfuse para saber el `traceId` (no hace falta, es derivable); una puntuación por ejecución en vez de por traza (pierde la
razón de la historia: abrir la respuesta mala y ver su puntuación).

## R5. Fases, concurrencia y tiempo

**Decisión**: tres fases secuenciales.

1. **Turnos del asistente** (96 casos de conocimiento, en orden del dataset, uno a uno): el chat sale por un modelo que Ollama
   atiende de uno en uno; paralelizar no gana nada y mezcla trazas.
2. **Juez** con concurrencia acotada (`--concurrency`, 2 por defecto): RAGAS es asíncrono y el juez es el cuello de botella.
3. **Adjuntar** a Langfuse, secuencial.

Cada fase imprime el avance (`[12/96] G012 …`) por la salida de error (FR-022).

**Por qué**: separar turnos de juez evita que Ollama alterne dos modelos distintos en memoria por caso cuando el juez es otro
modelo, y permite fallar pronto: si el asistente está roto, no se gasta ninguna llamada al juez.

**Tiempo**: por caso con respuesta, `Faithfulness` hace unas 2 llamadas (extraer afirmaciones y veredictos), `AnswerRelevancy`
3 generaciones de preguntas más embeddings, `ContextPrecision` una por fragmento entregado (hasta 4), y la comprobación de
abstención 1: del orden de 10 a 12. Para 96 casos son unas 1000 llamadas; cabe en 30 minutos con unos 2 s por llamada y no cabe
en una CPU lenta. **S-4 lo mide**; si no se cumple en la máquina de referencia, el criterio SC-001 se reformula con
`loop-spec` (p. ej. «con GPU» o un tiempo por llamada) antes de seguir.

## R6. Qué puntúa cada tipo de caso y qué significa «vacío»

**Decisión**: la aplicabilidad la decide una función pura (`quality_scores.py`) por tipo de caso, derivado de
`expected_documents` y `category` (`GoldenCase.is_conversation` ya existe):

| Tipo | Se consulta al asistente | Métricas |
|---|---|---|
| Con respuesta (`expected_documents` no vacío) | sí | `faithfulness`, `answer_relevancy`, `context_precision` |
| Sin respuesta (`expected_documents` vacío, categoría de conocimiento) | sí | solo `faithfulness` |
| Conversación (`small_talk`, `injection`) | no | ninguna; se cuentan como excluidas |

Entradas a RAGAS: `user_input` = pregunta del caso, `response` = respuesta del asistente, `retrieved_contexts` = textos de los
fragmentos en orden, `reference` = `reference_answer` del dataset.

Reglas de **vacío por definición** (FR-011), distintas de un fallo del juez:

- **Abstención**: una comprobación propia y mínima con el mismo juez («¿esta respuesta se limita a decir que no tiene la
  información?») produce `abstained`. Si es verdadera, `faithfulness` queda **vacía** (no afirma nada de la tienda que
  verificar), se registra `abstained = true` y el resumen lo cuenta. Sin esto RAGAS puntuaría la negativa como infiel. Se
  ejecuta primero y, si abstiene, se ahorra la llamada de fidelidad. En un caso con respuesta, una negativa deja
  `answer_relevancy` baja de forma natural (historia 4, escenario 5).
- **Sin afirmaciones**: si RAGAS no extrae ninguna afirmación (devuelve NaN), `faithfulness` queda vacía.
- **Contexto vacío en un caso con respuesta**: `context_precision` = 0 sin llamar al juez (no hubo nada útil que entregar,
  edge case de la spec).
- **Respuesta que afirma algo sin contexto** (no es una abstención): `faithfulness` = 0 sin llamar al juez, porque ninguna
  afirmación puede estar respaldada por un contexto que no existe; es lo que se quiere detectar (un dato inventado). Si la
  respuesta es una abstención, `faithfulness` queda vacía por la primera regla. Es una regla determinista propia: no depende de
  cómo trate RAGAS un `retrieved_contexts` vacío (S-5 lo comprueba solo para documentarlo).
- **Respuesta sin contenido** (solo marcadores de cita como `[2]`, sin una letra o cifra): no afirma ni contesta nada.
  `faithfulness` queda vacía, `answer_relevancy` es 0 en un caso con respuesta y `abstained` es `false`; la precisión del
  contexto se calcula igual. Surgió de leer respuestas reales de la primera medición completa: el 3B terminó un turno con
  solo `[2]` y RAGAS le puso fidelidad 1,00.
- **Abstención con el prompt v2**: el juez lista antes los datos concretos que la respuesta da por ciertos y el código
  impone que, si lista alguno, no es una abstención. La v1 solo pedía sí o no y tomó por abstención una respuesta parcial
  («puedes combinarlo con un vale, pero no se indica lo de las cuotas»), dejando su fidelidad sin comprobar.
- **Redondeo en origen**: cada puntuación se redondea a los 4 decimales del CSV al calcularla, de modo que el CSV, las medias
  y los scores de Langfuse son los mismos números (una media de celdas redondeadas no es la media redondeada y SC-004 y SC-005
  piden coincidencia).

Las tres reglas de contexto vacío y la de abstención las aplica la **orquestación** (`quality_run.py`), como reglas puras
que se prueban sin juez. El adaptador de RAGAS (`quality_judge.py`) solo traduce: llama a las métricas y convierte `NaN` en vacío.

Un fallo del juez (sin respuesta, salida ilegible tras los reintentos de `instructor`, entrada que supera `--max-input-chars`)
**nunca** es una puntuación: es `MeasurementError` con caso y métrica.

**Por qué**: es el único reparto consistente con el spec. La abstención es una decisión de diseño de esta historia, no de
RAGAS; se declara en Complexity Tracking.

**Alternativa descartada**: tratar la abstención en un caso sin respuesta como fidelidad 1: convierte «no dijo nada» en «dijo la
verdad» y mezcla dos cosas; con el vacío y el contador `abstained`, un asistente que siempre se niega queda a la vista (20 de 20
abstenciones, sin media de fidelidad) y no se premia.

## R7. Ruido del juez y determinismo

**Decisión**: temperatura 0 y semilla fija por petición (`seed` en el cuerpo, que LiteLLM pasa a Ollama), y se asume que el
resultado **no** es idéntico entre ejecuciones (spec, Assumptions). La documentación explica cómo medir el ruido: repetir la
ejecución y comparar las medias; SC-009 fija 0,05 como tolerancia a confirmar en S-4. El resultado registra juez, temperatura,
versión de RAGAS y versión de la comprobación de abstención (FR-018).

## R8. Formato del CSV

**Decisión**: seguir la convención de LAB-28 (UTF-8 sin BOM, cabecera, cuatro decimales, punto decimal, `\n`, ids separados por
`|`) con dos archivos `quality-summary.csv` y `quality-detail.csv`; las columnas están en
[contracts/quality-csv.md](contracts/quality-csv.md). Un valor vacío es una celda vacía; nunca se escribe `NaN` ni `0` por un
vacío. El CSV no lleva textos (FR-019): ni la respuesta ni los fragmentos; sí el id de traza.

**Por qué**: LAB-30 y LAB-31 ya leen el formato de LAB-28; mantener la convención evita un segundo lector. A diferencia de
LAB-28, la ejecución lleva un `run_id` (con marca de tiempo) y el resultado no es idéntico entre ejecuciones por diseño (R7).

## R9. Gateway y DEV (`make up`)

**Corrección**: una versión anterior de este plan decía que DEV tenía su propia copia de la configuración del gateway y que no
se tocaba. Era falso: `../shoplab-infra/scripts/sync-litellm-files.sh` **copia** `config.yaml` y `keys.yaml` desde
`infra/litellm/` de este repo (lo dice también `docs/gateway-de-modelos.md` de DEV). Añadir `eval` aquí sin acompañarlo allí
haría fallar, en la siguiente sincronización, el Job `litellm-bootstrap-keys`, que aborta si falta la variable de una clave.
Además, el equipo usa DEV como entorno local y `make up` debe dejar la medición funcionando.

**Decisión**: la medición funciona en los dos entornos, y los cambios de infraestructura que exige se hacen en esta historia
(regla del proyecto), en `infra/` y en `../shoplab-infra` (rama nueva desde `main` y PR propia).

En `infra/litellm/` (fuente única) se añaden el nombre lógico `judge` y la clave `eval`. En `../shoplab-infra`:

| Cambio | Por qué |
|---|---|
| Ejecutar `scripts/sync-litellm-files.sh` y confirmar `files/*` y `SOURCE` | Lleva `judge` y `eval` al `ConfigMap` del gateway de DEV |
| `LITELLM_KEY_EVAL` en `job-bootstrap-keys.yaml` (`secretKeyRef`, obligatoria) | El Job la necesita para crear la clave; el `ExternalSecret litellm-credentials` ya extrae **todo** el JSON (`dataFrom`), así que no cambia salvo el comentario |
| `LITELLM_KEY_EVAL` en el JSON `litellm` de `secrets.auto.tfvars.example`, `scripts/lib.sh` y los datos de las pruebas de Terraform | Documenta el secreto y mantiene `make tf-test` en verde |
| Túneles a `litellm` (4000) y `langfuse-web` (3001) en `scripts/forward.sh` | Ninguno tiene Ingress; `make up` ya lanza los túneles. Son los mismos puertos que el compose, así que los valores por defecto de `eval/` valen sin cambios. `kubectl port-forward` no pasa por la `NetworkPolicy` del gateway (entra por el netns del pod), pero se comprueba en T053 |
| `scripts/quality.sh` y `make quality` | Lee del clúster la clave `eval` (`litellm-credentials`) y las claves de Langfuse (`backend-langfuse`), fija la URL del ingress y la cabecera `Host`, y llama a `uv run python -m shoplab_eval quality` de `shoplab-helpdesk/eval`. Mismo patrón que `seed.sh` |
| Opción `--host-header` en `eval/` | El ingress enruta por `Host`; `seed.sh` entra por `127.0.0.1` con `Host: shoplab.localtest.me` para no depender del DNS público |

**El archivo de secretos local no define `langfuse` ni `litellm`.** Se comprobó (solo nombres, no valores) que
`terraform/envs/dev/secrets.auto.tfvars` tiene únicamente `postgres-password`, `llm-api-key` y `llm-model`, y que no hay
`TF_VAR_secrets`. Hoy `make up` deja sin credenciales a LiteLLM y a Langfuse (los `ExternalSecret` no resuelven) y el backend no
llega a los modelos. Sin arreglarlo, «`make up` debe funcionar» no se cumple con independencia de esta historia.

**`scripts/ensure-secrets.sh`** (lo llama `make up` y existe como `make secrets`), sobre `secrets.auto.tfvars`:
- **Solo añade**: si falta la entrada `langfuse` o `litellm`, la crea con valores aleatorios; si el JSON `litellm` existe pero
  no trae `LITELLM_KEY_EVAL`, se la inserta. **Nunca cambia un valor existente** (`postgres-password` debe seguir coincidiendo con
  el Postgres de Docker del host) y nunca imprime valores: solo los nombres de lo que añadió.
- Los valores de las claves del gateway son `sk-` + 32 hexadecimales aleatorios: el Job rechaza en `dev` los marcadores
  `change-me` y `local`. Los de Langfuse siguen `docs/observability.md`: `ENCRYPTION_KEY` de 64 hexadecimales
  (`openssl rand -hex 32`), las claves del proyecto con prefijos `pk-lf-` y `sk-lf-`, y ningún valor con caracteres especiales.
- Si el archivo no existe, se mantiene el aviso actual de `up.sh` (copiarlo desde el ejemplo): el valor de `postgres-password` lo
  decide la persona. Si los secretos llegan por `TF_VAR_secrets`, el script no lo modifica: comprueba que las claves requeridas
  estén y, si faltan, se detiene nombrando cuáles.
- Si el archivo no tiene el formato del ejemplo y no se puede insertar con seguridad, se detiene y explica qué añadir a mano.

**Alcance: todo el clúster, con Ollama fuera.** El requisito del equipo es que tras `make up` funcione **todo lo ya implementado**
(backend, frontend, ingest, LiteLLM, Langfuse, observabilidad), con Ollama como único componente fuera del clúster: el overlay `dev`
ya lo sirve desde el Docker del host (`ollama-host.yaml`, `172.17.0.1:11434`), igual que Postgres. Esta historia no puede dar por
bueno nada de eso sin verlo funcionar: LAB-64 y LAB-84 se fusionaron con la validación en clúster pendiente, y Langfuse (~2,8 GiB)
nunca se midió en el nodo. Por eso T053 verifica el clúster completo y T054 corrige, dentro de la historia, lo que falle,
también si ya estaba roto. Docker Desktop estaba parado cuando se revisó este plan, así que **nada de esto está verificado todavía**.
La revisión estática solo confirmó dos cosas: faltan los secretos `langfuse` y `litellm` en el archivo local, y el clon de
`shoplab-infra` estaba en una rama antigua dos commits por detrás de `main` (que despliega v0.18.0, la primera versión con el
gateway; el overlay del clon aún decía v0.17.0).

**Qué mide `quality` en DEV**: la imagen desplegada (hoy v0.18.0, con LAB-23, LAB-64 y LAB-84), no el código de la rama; basta, porque
solo usa el SSE del chat y el `X-Request-Id`. LAB-114 (charla de cortesía) aún no está desplegada: los casos de conversación se
excluyen igualmente.

**Orden de despliegue**: Argo CD sincroniza `shoplab-infra` desde `main`, así que los cambios de infraestructura solo llegan al
clúster al fusionar su PR. La secuencia es: PR de este repo fusionada → `sync-litellm-files.sh` (registra el commit de origen en
`SOURCE`) → PR de `shoplab-infra` fusionada → `make down && make up` → `make seed` → `make quality`.

**Alternativas descartadas**: dejar DEV para LAB-30 (rompe la próxima sincronización); usar la clave `backend` como juez (mezcla
presupuestos); generar los secretos con el proveedor `random` de Terraform (añade un proveedor, su bloqueo y sus pruebas para un
problema que un script de siete líneas de lógica resuelve); regenerar el archivo entero (pisaría `postgres-password`).

## Verificaciones previas

Dependen de un modelo, del gateway o de Langfuse reales; se ejecutan al empezar la implementación y su resultado se anota en
`validation.md` antes de construir el resto. Si una falla, se corrige el plan con `loop-spec`.

| Id | Qué se comprueba | Si falla |
|---|---|---|
| S-1 **(verificada 2026-11-18: el 3B falla, `qwen2.5:7b` pasa; ver R2)** | El juez por defecto (`judge` = 3B local) devuelve salida estructurada válida para las tres métricas vía `instructor` (modo `JSON` si el modo de funciones falla) y **discrimina**: los pares de SC-002 y SC-003 salen en el sentido esperado. Además, la clave `eval` puede leer el modelo real detrás de `judge` en `/model/info` | Elegir otro modelo local por defecto (p. ej. de 7B) o documentar que el defecto no sirve para decidir |
| S-2 **(verificada: funciona)** | `AnswerRelevancy` funciona con `embeddings` por el gateway (`embedding_factory` sobre el mismo cliente) y con la dimensión de `bge-m3` | Calcular la similitud con el cliente directo del gateway y pasarlo como `BaseRagasEmbedding` propio |
| S-3 **(verificada con ajustes; ver R4)** | Langfuse 4.x: `POST /api/public/scores` sobre una traza existente la asocia; repetir el `id` actualiza y no duplica; publicar antes de que la traza esté ingerida no se pierde o devuelve un error claro; `GET /api/public/traces/{id}` sirve para esperar la traza (**no existe en `events_only`; sirve `v2/observations`**) | Esperar la traza antes de publicar (ya previsto) o reintentar acotado |
| S-4 | Tiempo total de los 96 casos con el juez local, y diferencia de medias entre dos ejecuciones | Reformular SC-001 (tiempo o requisito de GPU) y SC-009 (tolerancia) con `loop-spec` |
| S-5 **(verificada: 0,5 ante una negativa; `ValueError` sin contexto)** | Qué devuelve `Faithfulness` cuando no hay afirmaciones, ante una negativa y con `retrieved_contexts=[]` y una respuesta afirmativa (¿NaN, 0, error?) | Ajustar la regla de «sin afirmaciones» de R6 al comportamiento real. La regla de contexto vacío no depende de este resultado (la aplica la orquestación) |
