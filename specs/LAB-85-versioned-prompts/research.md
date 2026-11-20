# Investigación: Prompts versionados en Langfuse

Cada decisión indica qué se verificó y qué no. Lo no verificado se comprueba en `quickstart.md` contra el Langfuse local
(imagen fijada en `infra/docker-compose.yml`) antes de implementar lo que depende de ello.

## Hallazgos del código actual

- Los prompts son **cuatro**, no tres: el de sistema (`shoplab.chat.system-prompt`, con su texto en `application.yml`), el de
  cortesía (`SmallTalkConstants.DEFAULT_SYSTEM_PROMPT`, con el marcador `{topics}`), la plantilla con contexto
  (`ChatConstants.DEFAULT_CONTEXT_TEMPLATE`, con `{query}` y `{context}`) y la plantilla sin contexto
  (`ChatConstants.DEFAULT_EMPTY_CONTEXT_TEMPLATE`, con `{query}`; solo se usa con `no-context-mode=model`). La spec se ajustó.
- `GatewayChatAdapter` lee los textos **una vez en el constructor** y construye un `ContextualQueryAugmenter` con la plantilla.
  Con prompts que cambian en caliente, eso debe pasar a resolverse por turno.
- El prompt de sistema se envía **sin renderizar** (`new SystemMessage(text)`): sus llaves son literales. La de cortesía se
  rellena con `String.replace("{topics}", ...)`; la plantilla con contexto se renderiza con `PromptTemplate` de Spring AI;
  la sin contexto con `String.replace("{query}", ...)`. Por eso «las variables que el sistema sabe rellenar» es distinto por
  prompt (R4).
- El backend ya habla con Langfuse por OTLP con `publicKey`/`secretKey` (`TracingProperties`), la traza de cada turno se abre
  en `LangfuseChatTrace` y el turno propaga datos por el contexto de Reactor (`GatewayHeaders.REQUEST_ID_CONTEXT_KEY`).
- Referencias a las propiedades que se retiran: `ChatProperties`, `SmallTalkProperties`, `RagProperties`, `ChatConstants`,
  `SmallTalkConstants`, `application.yml`, ocho clases de prueba y `docs/chat-streaming.md` (y menciones en los ADR-0018,
  0019 y 0025, que no se editan: son históricos).

## R1. Cliente: API REST de Langfuse con `WebClient`

- **Decisión**: leer con `GET /api/public/v2/prompts/{name}?label=production` (o `?version=N`) y publicar con
  `POST /api/public/v2/prompts`, autenticando con Basic (clave pública y secreta del proyecto).
- **Verificado**: la ruta, los parámetros `version` y `label` y los campos de respuesta `type`, `prompt`, `version`, `labels`,
  `config` figuran en la referencia de la API de Langfuse.
- **No verificado**: cuerpo exacto de `POST` (se espera `name`, `type: "text"`, `prompt`, `labels`, `commitMessage`), el código
  de respuesta cuando la etiqueta no existe (se espera 404) y qué devuelve una consulta sin `label` ni `version`. Se comprueba
  en `quickstart.md` §1; el contrato [langfuse-prompts-api.md](contracts/langfuse-prompts-api.md) se corrige si difiere.
- **Razón**: el backend ya es WebFlux y ya tiene las claves; no añade dependencias. La API de prompts es estable y simple.
- **Alternativas**: un SDK de Langfuse (no se encontró uno de Java para prompts y añadiría una dependencia para dos
  llamadas); leer los prompts de la base de Langfuse (acopla a su esquema interno).

## R2. Caché: lectura de memoria en el turno y refresco en segundo plano

- **Decisión**: cada prompt vive en una referencia atómica con su última versión **válida**; el turno solo lee esa referencia
  (cero red). Un refrescador pide los cuatro prompts en paralelo cada `refresh-interval` (60 s por omisión) y reemplaza la
  referencia solo si lo recibido es válido y distinto. La entrada **nunca caduca**: «vencer» solo significa «toca volver a
  preguntar» (FR-008: la última válida se sirve aunque haya vencido).
- **Razón**: cumple FR-006, FR-007 y FR-009 sin esperar en el turno: no hay tope de espera que ajustar porque no hay espera.
  Un cambio de etiqueta llega a cada instancia en ≤ `refresh-interval` sin coordinar instancias (FR-007, historia 4/5).
- **Alternativas**: consulta con TTL en el turno que la dispara (el primer turno tras vencer pagaría la latencia o el tope; con
  varias instancias, ráfagas de consultas); invalidación por *webhook* de Langfuse (más piezas, y no hay en el entorno local).

## R3. Arranque: carga inicial bloqueante pero acotada

- **Decisión**: un `ApplicationRunner` carga los cuatro prompts en paralelo y espera como mucho `request-timeout` (2 s). Si
  Langfuse no responde, el backend arranca igual con los valores por defecto y el refrescador lo reintenta en el siguiente
  intervalo. Un `ApplicationRunner` se ejecuta antes de que la aplicación se declare lista, de modo que, con Langfuse sano, el
  primer turno ya usa la versión `production`.
- **Razón**: evita una ventana en la que los primeros turnos usarían el valor por defecto sin motivo; el peor caso (Langfuse
  caído) cuesta como mucho `request-timeout` al arrancar.
- **Alternativa**: carga perezosa en el primer turno (lo bloquearía o lo serviría con el valor por defecto).

## R4. Validación antes de sustituir (FR-004)

- **Decisión**: un `PromptValidator` puro aplica, por prompt:
  1. el tipo es `text` (un prompt `chat` de Langfuse se rechaza);
  2. no está en blanco y no supera `max-prompt-characters` (20 000 por omisión);
  3. contiene sus marcadores obligatorios (`chat-small-talk`: `{topics}`; `chat-context`: `{query}` y `{context}`;
     `chat-empty-context`: `{query}`; `chat-system`: ninguno);
  4. la plantilla con contexto debe **poder renderizarse** con solo `query` y `context` (hoy `RagProperties.requireRenderable`),
     lo que rechaza una variable desconocida como `{cliente}`.
- **Interpretación de FR-004**: «las variables que el sistema necesita y ninguna otra que impida construir el mensaje». Las
  llaves de los prompts que no se renderizan (sistema, cortesía, sin contexto) son literales y no estorban.
- **Razón**: son exactamente las comprobaciones que hoy hacen las propiedades al arrancar, movidas al punto donde ahora puede
  entrar un texto malo en caliente.

## R5. Renderizar la plantilla sin `ContextualQueryAugmenter`

- **Decisión**: renderizar con un `PromptRenderer` propio (sin dependencias de Spring) que comparten el validador y el adaptador, y el mismo
  formateador de fragmentos que hoy (`formatDocuments`). El augmentador solo hacía eso con contexto (su camino de contexto vacío ya se evitaba
  a propósito), y construirlo por turno con una plantilla variable no aporta nada.
- **Riesgo**: diferencia de formato entre ambos (si aparece, se corrige el renderizador, no las pruebas). Se cubre con las pruebas actuales (`PromptDelimitingCustomTemplatesTests`,
  `PromptInjectionTests`) que deben pasar sin cambiar sus expectativas, y con la comparación de S-8.

## R6. Valores por defecto en recursos, y retirada de propiedades

- **Decisión**: los cuatro textos actuales pasan **sin modificar** a `backend/src/main/resources/prompts/<nombre>.txt`
  (UTF-8; sin BOM). `PromptDefaults` los lee al arrancar y falla el arranque si falta uno o no es válido (el valor por defecto
  es la última red de seguridad y debe ser siempre correcto). Se retiran las cuatro propiedades y sus variables
  `SHOPLAB_CHAT_*_PROMPT`.
- **Razón**: FR-002 (una sola copia, fuera de la lógica) y FR-028 (el texto no cambia). Un arranque con propiedades retiradas
  que siguen definidas en el entorno no falla: Spring ignora las claves desconocidas; se documenta en el contrato de
  configuración y se avisa en el registro si el entorno aún define `SHOPLAB_CHAT_SYSTEM_PROMPT` (aviso de migración, una vez).
- **Alternativa**: conservar las propiedades como sobrescritura local (dos fuentes de verdad; descartado en Complexity Tracking).

## R7. Trazabilidad: enlace nativo más metadatos

- **Verificado** (documentación de OpenTelemetry de Langfuse): una observación de tipo generación se enlaza con una versión de
  prompt con `langfuse.observation.prompt.name` (cadena) y `langfuse.observation.prompt.version` (entero); sin enlace, Langfuse no
  da métricas por versión. La documentación de enlace de prompts dice además que **con un prompt de reemplazo no se crea enlace**.
- **Decisión**:
  - En la generación, se enlaza el **prompt principal** del turno con los atributos nativos: `chat-system` en un turno de
    conocimiento, `chat-small-talk` en una cortesía. Solo cabe un enlace por generación.
  - Los cuatro (los que ese turno usó) se registran como **metadatos**: en la generación `langfuse.observation.metadata.prompt.<nombre>`
    y en la traza `langfuse.trace.metadata.prompt_<nombre>` (con guion bajo), con valor `<versión>` o `default`. El metadato de
    traza permite filtrar la lista de trazas (FR-013, escenario 5 de la historia 2).
  - Con el valor por defecto no se escribe el enlace nativo y el metadato dice `default` (FR-014).
  - Con una versión fijada para evaluar, además `prompt_override=true` en la traza.
- **No verificado**: que Langfuse 4.77 acepte filtrar por esos metadatos desde la lista de trazas (se comprueba en
  `quickstart.md` §4). Si no, el filtro se hace con la columna de metadatos, que sigue cumpliendo el criterio de aceptación.
- **Mecanismo**: `TurnTrace.promptsUsed(List<PromptReference>)` como método por defecto (no hace nada) para no romper a los demás
  implementadores ni los dobles de prueba.

## R8. Fijar versiones solo para evaluar (FR-017, FR-018, FR-023)

- **Decisión**: la petición puede llevar `X-Prompt-Versions: chat-system=3,chat-context=2` y `X-Prompt-Token: <token>`. El
  controlador (solo traducción) crea un `PromptOverrides`; la comprobación del token y el formato viven en
  `PromptOverrideHeader`. Reglas:
  - sin cabecera → comportamiento normal, sin coste;
  - cabecera con `override-token` sin configurar, o con un token que no coincide (comparación en tiempo constante) → **403**;
  - formato inválido, nombre desconocido o versión no numérica → **400**;
  - versión que no existe o no es válida en Langfuse → el turno falla con un error tipado (evento `error`), **nunca** cae al
    valor por defecto ni a `production` (FR-023).
  El valor de cada versión pedida se lee con `GET ...?version=N` y se guarda en una caché acotada (las versiones no cambian).
- **Solo versiones numéricas** en el backend: la evaluación (fase B) resuelve las etiquetas a su versión **antes** de empezar,
  lo que además deja registrada la versión concreta en el resultado (FR-020).
- **Razón del 403 explícito**: una evaluación mal configurada que ignorara la cabecera mediría con `production` sin avisar y
  falsearía la comparación; un cliente normal nunca envía la cabecera, así que rechazar no le cuesta nada.
- **Alternativas**: etiqueta propia por instancia (`label=staging` en el entorno; obliga a reiniciar entre versiones);
  endpoint aparte de evaluación (otra superficie y duplica el flujo del chat); mover `production` (afecta a todos).

## R9. Publicación inicial por el propio backend (FR-005)

- **Decisión**: con `publish-missing=true`, tras la carga inicial el backend crea, con `POST`, cada prompt cuya consulta devolvió
  «no existe» (404), con el valor por defecto, tipo `text` y etiqueta `production`. Nunca crea sobre un prompt que ya existe ni
  mueve etiquetas. Apagado por omisión; encendido en el compose local y en DEV por variable de entorno.
- **Razón**: una sola vía sirve al entorno local y a DEV sin añadir scripts ni Jobs de Kubernetes, y es idempotente por
  construcción. Mantiene el valor por defecto del repositorio como semilla única.
- **Riesgo conocido**: con varias réplicas arrancando a la vez sobre un Langfuse vacío podrían crear dos versiones idénticas. DEV
  tiene una réplica; se documenta y no se mitiga (el efecto es inocuo: dos versiones con el mismo texto).
- **Alternativas**: script que lee los `.txt` y llama a la API (`bash`, `curl`, `jq`: otra herramienta que mantener y un paso
  manual); Job de Kubernetes en `shoplab-infra` (más infraestructura para el mismo efecto); versiones nuevas desde el
  repositorio cuando el texto cambia (pisa ediciones hechas en Langfuse; descartado en la spec).

## R10. Claves e interruptores

- **Decisión**: la gestión de prompts tiene sus propias propiedades (`shoplab.chat.prompts.*`) con su interruptor
  `enabled` (apagado por omisión) **independiente** de `shoplab.chat.tracing.enabled` (FR-011). Las claves por omisión son las
  del envío de trazas (`${SHOPLAB_CHAT_TRACING_PUBLIC_KEY}` / `..._SECRET_KEY`), de modo que en DEV no hace falta ningún secreto
  nuevo para leer y publicar prompts. La URL base es propia (la de las trazas termina en `/api/public/otel`).
- **Apagado** (`enabled=false`): se usa `DefaultPromptCatalog`; no se crean clientes ni hilos ni conexiones.

## R11. Métricas y avisos

- **Decisión**: contador `shoplab.chat.prompts.refresh` con etiquetas `prompt` y `result` (`updated`, `unchanged`, `invalid`,
  `missing`, `failed`) y gauge de la versión cargada por prompt; los avisos pasan por `WarnThrottle` (el estrangulador que ya usan
  las trazas) con la clave `prompt:<nombre>:<motivo>`, y solo llevan nombre, versión y motivo (FR-010).
- **Si `WarnThrottle` no es visible desde el paquete nuevo** (es del paquete de trazas), se mueve a un paquete compartido del
  módulo `chat` y las trazas lo importan de allí, sin cambiar su comportamiento.

## R12. Evaluación contra dos versiones (fase B, FR-017 a FR-024)

- **Decisión**:
  - `quality --prompt chat-system=3 --prompt chat-context=staging` (repetible; el valor es una versión o una etiqueta). Un
    módulo puro `prompt_selection` analiza y valida los argumentos; `prompt_client` consulta a Langfuse la versión a la que
    apunta cada etiqueta y comprueba que cada versión existe, **antes** de consultar al asistente en cantidad (FR-019; con
    Langfuse caído, error claro sin medir, FR-023).
  - `chat_client` envía `X-Prompt-Versions` con las versiones **ya resueltas** y `X-Prompt-Token` desde
    `SHOPLAB_EVAL_PROMPT_TOKEN`.
  - El resumen de LAB-29 gana la columna `prompt_versions` (`chat-system=3|chat-context=2`, o `production` si no se fijó nada).
    Es una columna **añadida al final**: los lectores por nombre de LAB-30 y LAB-31 no se rompen.
  - `compare` (puro, sobre dos `quality-summary.csv`) muestra por métrica la media de A, la de B, la diferencia y los `n`, y
    advierte de incomparabilidad si difieren `judge_model`, `judge_underlying_model`, `judge_temperature`, `assistant_model`,
    `embedding_model` o los `cases` del ámbito `all` (FR-022). Muestra qué prompts distinguen a un resultado del otro.
- **Qué compara**: solo métricas de **calidad** (LAB-29). Las de recuperación (LAB-28) no dependen del prompt.
- **Alternativas**: comparar dentro de `quality` ejecutando las dos versiones seguidas (alarga cada ejecución y mezcla dos
  mediciones en un resultado); hoja de cálculo manual (sin advertencias de comparabilidad).

## R13. Infraestructura (en la historia, no se difiere)

- **Local**: el backend corre en el host; basta con variables de entorno documentadas. El compose no cambia.
- **DEV** (`../shoplab-infra/k8s/overlays/dev/kustomization.yaml`): `SHOPLAB_CHAT_PROMPTS_ENABLED=true`,
  `SHOPLAB_CHAT_PROMPTS_BASE_URL=http://langfuse-web:3000`, `SHOPLAB_CHAT_PROMPTS_PUBLISH_MISSING=true`. Las claves ya llegan
  por el secreto `externalsecret-langfuse.yaml`.
- **Token de evaluación en DEV (resuelto en T044)**: el secreto `/shoplab/dev/langfuse` es un JSON de diez claves fijas (lo
  valida `terraform/modules/secrets`) que Langfuse lee entero con `dataFrom`; añadirle una undécima mezclaría un valor del
  backend con los de Langfuse, y un secreto nuevo exige cambiar el módulo de Terraform y el `secrets.auto.tfvars` de cada
  persona. Por eso **DEV no lleva `SHOPLAB_CHAT_PROMPTS_OVERRIDE_TOKEN`**: no admite fijar versiones y las evaluaciones con
  `--prompt` se hacen contra el backend local (que usa el Langfuse local). Si se necesita en DEV, el camino es un secreto
  `/shoplab/dev/backend-prompts` propio con su `ExternalSecret`, en una historia aparte.

## R14. ADR

- **ADR-0027** «Prompts versionados en Langfuse»: fuente de verdad, caché con refresco en segundo plano y reemplazo, enlace en la
  traza, cabecera de evaluación, publicación inicial y retirada de propiedades. LAB-29 reservó el **0026** y no llegó a crearlo; se usa el 0027.
