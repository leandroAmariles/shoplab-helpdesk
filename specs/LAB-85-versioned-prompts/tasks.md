# Tasks: Prompts versionados en Langfuse

**Input**: documentos de diseño en `specs/LAB-85-versioned-prompts/`
**Prerequisitos**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Pruebas**: SÍ. La spec las exige (FR-025) y la Constitución (IV) pide cobertura ≥ 80 %. Cada tarea de lógica lleva su prueba en
la misma tarea o en una `[P]` justo al lado; ninguna prueba usa un Langfuse ni un modelo reales (WireMock en el backend,
servidor local falso en `eval/`). La lógica NO va en los paquetes `config`, `model` ni `exception` (excluidos de JaCoCo).

**Organización**: por historia de usuario. Fase A = historias 1 a 4 (`backend/` + infra); fase B = historia 5 (`backend/` para
la cabecera y `eval/` para `--prompt` y `compare`). LAB-29 ya está fusionada en `main`, así que la fase B no está bloqueada.

## Formato: `[ID] [P?] [Historia] Descripción con ruta`

- **[P]**: se puede hacer en paralelo (archivos distintos, sin dependencias pendientes)
- **[US#]**: historia de `spec.md` a la que pertenece
- Rutas relativas a la raíz del repositorio. `BASE` = `backend/src/main/java/com/shoplab/helpdesk/chat` y
  `TEST` = `backend/src/test/java/com/shoplab/helpdesk/chat`
- Identificadores en inglés; documentación y mensajes de usuario en español; líneas ≤ 121 caracteres

---

## Phase 1: Setup (verificación previa)

**Propósito**: comprobar lo que el plan dejó sin verificar y fijar los textos que no pueden cambiar.

- [X] T001 Ejecutar `quickstart.md` S-1 contra el Langfuse local (perfil `llmops`, `http://localhost:3001`): código y cuerpo de
  `GET /api/public/v2/prompts/{name}?label=production` para un prompt inexistente y para uno sin la etiqueta, cuerpo aceptado
  por `POST /api/public/v2/prompts` y versión devuelta al repetirlo. Anotar el resultado y **corregir**
  `specs/LAB-85-versioned-prompts/contracts/langfuse-prompts-api.md` (secciones «Leer» y «Publicar») y `research.md` R1 si
  difiere. Si la API no distingue «prompt inexistente» de «etiqueta inexistente», dejar escrita la regla que usará el cliente
- [X] T002 Registrar el SHA-256 (UTF-8, sin BOM) de los cuatro textos actuales **antes de moverlos**: `shoplab.chat.system-prompt`
  en `backend/src/main/resources/application.yml`, `SmallTalkConstants.DEFAULT_SYSTEM_PROMPT`,
  `ChatConstants.DEFAULT_CONTEXT_TEMPLATE` y `ChatConstants.DEFAULT_EMPTY_CONTEXT_TEMPLATE` (en
  `BASE/domain/model/constants/`). Calcularlos sobre el valor **ya enlazado** por Spring (no sobre el YAML crudo) con una prueba temporal que arranque el
  contexto, y guardarlos en `specs/LAB-85-versioned-prompts/validation.md` (crear aquí, sección «Textos originales»); los usa
  T011, que los copia a `PromptDefaultsTests` (FR-028: el texto no cambia)

---

## Phase 2: Foundational (bloquea todas las historias)

**Propósito**: tipos, puertos, validación, propiedades y valores por defecto que usan las cinco historias.

**⚠️ CRÍTICO**: ninguna historia empieza hasta terminar esta fase.

- [X] T003 [P] Crear `BASE/domain/model/enums/PromptName.java` con los cuatro valores de `data-model.md` (`CHAT_SYSTEM` →
  `chat-system`, sin marcadores; `SMALL_TALK` → `chat-small-talk`, marcador `{topics}`; `CONTEXT` → `chat-context`, marcadores
  `{query}` y `{context}`; `EMPTY_CONTEXT` → `chat-empty-context`, marcador `{query}`), cada uno con su recurso
  `prompts/<nombre>.txt`, y `BASE/domain/model/enums/PromptSource.java` (`LANGFUSE`, `DEFAULT`). Incluir `fromLangfuseName(String)`
  que devuelve vacío si el nombre es desconocido
- [X] T004 [P] Crear `BASE/domain/model/constants/PromptConstants.java` (`@UtilityClass`): etiqueta por omisión `production`,
  nombres de cabecera `X-Prompt-Versions` y `X-Prompt-Token`, `OVERRIDES_CONTEXT_KEY`, tope de la caché de versiones fijadas
  (32), texto `default` para trazas, nombre del contador `shoplab.chat.prompts.refresh`
- [X] T005 [P] Crear los records `PromptReference` (`name`, `version` Integer, `source`; «número ≥ 1 si `source = LANGFUSE`;
  `null` si `DEFAULT`»; método `asTraceValue()` → `<versión>` o `default`), `ResolvedPrompt` (`text` no vacío + `reference`) y
  `PromptOverrides` («mapa `PromptName → versión (≥ 1)`; vacío por omisión», constante `NONE`) en `BASE/domain/model/`.
  Validar los invariantes en el constructor compacto
- [X] T006 [P] Crear `BASE/domain/exception/PromptOverrideException.java` (400 / evento `error`) y
  `BASE/domain/exception/PromptOverrideForbiddenException.java` (403), añadir sus códigos a
  `backend/src/main/java/com/shoplab/helpdesk/shared/exception/ResponseCodeEnum.java` con el formato de los existentes, y
  mapearlos **solo** en `backend/src/main/java/com/shoplab/helpdesk/shared/infrastructure/in/rest/handler/GlobalExceptionHandler.java`.
  Prueba del mapeo en `backend/src/test/java/com/shoplab/helpdesk/shared/infrastructure/in/rest/handler/` (estado HTTP y código)
- [X] T007 Crear el puerto `BASE/application/port/out/PromptCatalogPort.java`: `ResolvedPrompt current(PromptName)` **sin bloqueo
  ni red** y `Mono<ResolvedPrompt> pinned(PromptName, int version)`. Depende de T003 y T005
- [X] T008a [P] Crear `BASE/application/usecase/PromptRenderer.java` (puro, sin dependencias de Spring): `render(String template,
  Map<String, String> values)` sustituye `{clave}` y lanza `PromptRenderException` si queda una variable sin valor o desconocida
  (por ejemplo `{cliente}`); las llaves que no son una variable válida se tratan como literales solo si el motor actual de
  plantillas las trata igual (comprobarlo con los textos de `PromptDelimitingCustomTemplatesTests`). Prueba
  `TEST/application/usecase/PromptRendererTests.java`: variable presente, ausente, desconocida, repetida, y el formato de los
  fragmentos de contexto que hoy produce el adaptador
- [X] T008 [P] Crear `BASE/application/usecase/PromptValidator.java` (puro) con las cinco reglas de `data-model.md`, en este
  orden y con estos motivos: 1 tipo `text` → `not-text`; 2 «texto no vacío ni en blanco» → `blank`; 3 «longitud ≤
  `max-prompt-characters`» → `too-long`; 4 marcadores obligatorios del `PromptName` → `missing-placeholder`; 5 (solo `CONTEXT`)
  «se renderiza con `query` y `context`» → `not-renderable` (rechaza `{cliente}`; usa `PromptRenderer` de T008a, sin Spring AI). Devuelve un resultado con el motivo, nunca
  lanza. Prueba `TEST/application/usecase/PromptValidatorTests.java`: una por regla, el caso feliz de cada nombre, y que el
  motivo no contiene el texto del prompt. Depende de T003, T008a
- [X] T009 [P] Crear `BASE/config/PromptProperties.java` (`shoplab.chat.prompts.*`, ver `contracts/prompts-config.md`): `enabled`
  (false), `base-url` (`http://localhost:3001`), `public-key`, `secret-key` (por omisión las de
  `SHOPLAB_CHAT_TRACING_*`), `label` (`production`, no vacía), `refresh-interval` (`60s`, «≥ 5 s»), `request-timeout` (`2s`,
  «entre 100 ms y 30 s»), `max-prompt-characters` (`20000`, «≥ 100»), `publish-missing` (false), `override-token` (vacío =
  apagado); expone `overrideEnabled()` = `enabled` y token no vacío (sin gestión de prompts no hay evaluación). `toString` muestra `keys=set|missing` y `override-token=set|unset`, nunca los valores. Con `enabled=true` y sin
  claves: aviso y comportamiento apagado. Prueba `TEST/config/PromptPropertiesTests.java` (validaciones, toString sin secretos,
  enlace desde YAML, `overrideEnabled()` en sus cuatro combinaciones). Registrar la clase en la configuración de propiedades que ya usa el módulo
- [X] T010 Crear `BASE/infrastructure/support/WarnThrottle.java` (público) moviéndolo desde
  `BASE/infrastructure/out/tracing/WarnThrottle.java` sin cambiar su comportamiento, y añadir
  `BASE/infrastructure/support/KeyedWarnThrottle.java` («como mucho un aviso por intervalo y por clave»,
  clave `prompt:<nombre>:<motivo>`). Actualizar los imports de `LangfuseChatTrace` y sus pruebas; añadir
  `TEST/infrastructure/support/KeyedWarnThrottleTests.java` con reloj controlable (o intervalo mínimo) que pruebe: primer aviso
  pasa, segundo del mismo intervalo no, otra clave sí
- [X] T011 Crear `backend/src/main/resources/prompts/chat-system.txt`, `chat-small-talk.txt`, `chat-context.txt` y
  `chat-empty-context.txt` con los cuatro textos **sin modificarlos** (UTF-8, sin BOM, mismos saltos de línea), y
  `BASE/infrastructure/out/prompts/PromptDefaults.java` que los lee del classpath y los pasa por `PromptValidator`; si falta uno
  o no es válido, **el arranque falla** (error de programación). Prueba `TEST/infrastructure/out/prompts/PromptDefaultsTests.java`
  que comprueba los cuatro SHA-256 de T002 (FR-028) y que cumplen sus marcadores. Depende de T002, T003, T008
- [X] T012 Crear `BASE/infrastructure/out/prompts/DefaultPromptCatalog.java` (implementa `PromptCatalogPort`: siempre el valor por
  defecto con `PromptSource.DEFAULT`; `pinned` falla con `PromptOverrideException`) y prueba
  `TEST/infrastructure/out/prompts/DefaultPromptCatalogTests.java`. Depende de T007, T011

**Checkpoint**: la base está lista; las historias pueden empezar.

---

## Phase 3: User Story 1 — Los prompts viven en Langfuse y el chat los carga de allí (P1) 🎯 MVP

**Objetivo**: el chat usa los cuatro prompts de la versión `production` de Langfuse, ya no hay texto de prompt en el código y un
Langfuse vacío se siembra solo con lo que falta.

**Prueba independiente**: `quickstart.md` S-2 y S-3 pasos 1 a 3: tras arrancar con `publish-missing=true` aparecen los cuatro
prompts en Langfuse (v1, `production`); editar una palabra distintiva y moverle la etiqueta cambia la respuesta sin reiniciar.

### Pruebas de la historia 1

- [X] T013 [P] [US1] `TEST/infrastructure/out/prompts/LangfusePromptClientTests.java` con WireMock: `GET ?label=production` y
  `?version=N` (200 `text`, 200 `chat` → `not-text`, 404, 401/403, 5xx, conexión rechazada, tiempo agotado, redirección no
  seguida); cabecera `Authorization: Basic` correcta y **ausente de cualquier mensaje de error**; `POST` con el cuerpo de
  `contracts/langfuse-prompts-api.md` (el verificado en T001). Depende de T001
- [X] T014 [P] [US1] `TEST/infrastructure/out/prompts/CachedPromptCatalogTests.java`: estados `VACÍO → CARGADO → CARGADO'` de
  `data-model.md`; un refresco inválido, ausente o fallido conserva la versión cargada; `CARGADO` nunca vuelve a `VACÍO`;
  sin carga válida se sirve `DEFAULT`; el reemplazo es atómico (un lector concurrente ve la versión vieja o la nueva entera); leer `current` N veces seguidas no hace
  ninguna petición a Langfuse (cero red)
- [X] T015 [P] [US1] `TEST/infrastructure/out/prompts/PromptPublisherTests.java`: con WireMock, crea **solo** el prompt cuya
  lectura dio «no existe» (según la regla fijada en T001) con el valor por defecto, `type: text` y etiqueta `production`; no
  hace `POST` si el prompt existe aunque su texto difiera o no tenga la etiqueta; ejecutarlo dos veces seguidas no crea
  versiones nuevas; con `publish-missing=false` no hace nada; un `POST` fallido se registra y no rompe el arranque. Depende de T001
- [X] T016 [P] [US1] `TEST/NoPromptTextInCodeTests.java`: recorre `backend/src/main` (salvo `resources/prompts/`) y comprueba que
  no aparece ninguna frase distintiva de los cuatro textos (SC-001, FR-002); falla con la ruta y la frase encontrada

### Implementación de la historia 1

- [X] T017 [US1] Crear `BASE/infrastructure/out/prompts/LangfusePromptClient.java` (`WebClient`, Basic con las claves,
  `request-timeout`, sin seguir redirecciones): `Mono<FetchResult> fetchLabel(name, label)`,
  `fetchVersion(name, version)` y `create(name, text)`; `FetchResult` distingue `FOUND(type, text, version)`, `MISSING` y
  `FAILED(httpStatus|motivo)`. Ni las claves ni la cabecera aparecen en excepciones, `toString` ni registros. Depende de T009, T001
- [X] T018 [US1] Crear `BASE/infrastructure/out/prompts/CachedPromptCatalog.java`: una `AtomicReference` por `PromptName` con el
  último `ResolvedPrompt` válido; `current` solo lee memoria (cero red), devolviendo el valor por defecto mientras no haya una
  carga válida; `accept(name, fetchResult)` valida con `PromptValidator` y reemplaza solo si es válido y distinto. Sin caducidad.
  `pinned(name, version)` se implementa en US5 (T053); hasta entonces devuelve `Mono.error(new PromptOverrideException(...))`.
  Depende de T007, T008, T012, T017
- [X] T019 [US1] Crear `BASE/infrastructure/out/prompts/PromptRefresher.java`: carga inicial de los cuatro prompts **en
  paralelo** esperando como mucho `request-timeout` (si Langfuse no responde, el arranque sigue con los valores por defecto),
  y refresco periódico cada `refresh-interval` en un planificador propio de un solo hilo (`@PreDestroy` lo detiene). Se arranca
  desde un `ApplicationRunner` (la aplicación no se declara lista hasta terminar la carga inicial; el arranque espera solo la carga, ≤ `request-timeout`, y no la
  publicación de T020). Prueba
  `TEST/infrastructure/out/prompts/PromptRefresherTests.java` con cliente falso y planificador/reloj falsos: carga paralela,
  tope de espera, refresco en cada intervalo, un fallo de un prompt no detiene a los otros. Depende de T018
- [X] T020 [US1] Crear `BASE/infrastructure/out/prompts/PromptPublisher.java` (R9): tras la carga inicial y solo con
  `publish-missing=true`, crea con `POST` los prompts que no existen; **nunca** crea una versión sobre un prompt existente ni
  mueve etiquetas; un fallo se registra y se reintenta en el siguiente arranque. Lanzarlo en segundo plano (sin bloquear) justo
  después de la carga del `ApplicationRunner` de T019, para no alargar el arranque. Depende de T017, T019
- [X] T021 [US1] Crear `BASE/config/PromptConfig.java` (`@Configuration`): con `shoplab.chat.prompts.enabled=true` y claves
  presentes registra `LangfusePromptClient`, `CachedPromptCatalog`, `PromptRefresher` y `PromptPublisher`; si no, solo
  `DefaultPromptCatalog` (sin clientes, hilos ni conexiones; FR-011). Prueba `TEST/config/PromptConfigTests.java` con
  `ApplicationContextRunner`: apagado → solo valores por defecto; encendido sin claves → aviso y apagado; encendido → caché; trazas apagadas con prompts encendidos → caché igualmente
  (FR-011).
  Depende de T018, T019, T020
- [X] T022 [US1] Refactorizar `BASE/infrastructure/out/gateway/GatewayChatAdapter.java`: quitar el texto de prompt del
  constructor y el `ContextualQueryAugmenter`; pedir cada prompt a `PromptCatalogPort.current(...)` **una sola vez al empezar el
  turno**; renderizar la plantilla de contexto con `PromptRenderer` (T008a, el mismo que usa el validador) y el
  mismo formateador de fragmentos de hoy (R5); el prompt de sistema se envía sin renderizar; la cortesía sustituye `{topics}` con
  `String.replace`; la plantilla vacía sustituye `{query}` con `String.replace`. Actualizar sus pruebas
  (`GatewayChatAdapterTests`, `GatewayChatAdapterSmallTalkTests`, `GatewayChatAdapterTraceTests`,
  `PromptDelimitingCustomTemplatesTests`, `PromptInjectionTests` en `TEST/infrastructure/out/gateway/`) inyectando un catálogo
  falso **sin cambiar sus expectativas de formato**: si el resultado difiere, se corrige el renderizador, no las pruebas. Depende de
  T007, T008a
- [X] T023 [US1] Retirar las cuatro propiedades: `systemPrompt` de `BASE/config/ChatProperties.java` y
  `BASE/config/SmallTalkProperties.java`, `contextTemplate` y `emptyContextTemplate` (con su `requireRenderable`) de
  `BASE/config/RagProperties.java`, las constantes `DEFAULT_*` de `ChatConstants` y `SmallTalkConstants`, y las claves
  correspondientes de `backend/src/main/resources/application.yml`; añadir allí `shoplab.chat.prompts.*` con los valores de
  `contracts/prompts-config.md`. Actualizar `ChatPropertiesTests`, `RagPropertiesTests`, `SmallTalkPropertiesTests` y
  `RagConfigurationBindingTests` en `TEST/config/`. Depende de T022 y T011
- [X] T024 [US1] Aviso de migración (una sola vez al arrancar): si el entorno aún define `SHOPLAB_CHAT_SYSTEM_PROMPT` o
  `SHOPLAB_CHAT_SMALL_TALK_SYSTEM_PROMPT`, registrar un aviso que nombra la variable y dice dónde se edita ahora el prompt; no
  se usa su valor ni se imprime. Implementar en `BASE/infrastructure/out/prompts/RetiredPromptVariablesCheck.java` (lee
  `Environment`) con prueba `TEST/infrastructure/out/prompts/RetiredPromptVariablesCheckTests.java`. Depende de T023
- [ ] T025 [US1] Arrancar el backend en local con `SHOPLAB_CHAT_PROMPTS_ENABLED=true` y `PUBLISH_MISSING=true` y ejecutar
  `quickstart.md` S-2 y S-3 pasos 1 a 3; anotar el resultado en `specs/LAB-85-versioned-prompts/validation.md` (creado en T002). Depende
  de T021 a T024

**Checkpoint**: el chat usa Langfuse y no queda texto de prompt en el código. MVP entregable.

---

## Phase 4: User Story 2 — Cada traza dice qué prompt y qué versión se usó (P1)

**Objetivo**: cada turno registra, por cada prompt usado, nombre y versión (o `default`), con enlace nativo en la generación.

**Prueba independiente**: `quickstart.md` S-4: una pregunta de conocimiento y un «Hola» muestran, en Langfuse, el enlace a la
versión del prompt principal y los metadatos filtrables; sin enlace y con `default` si se usó el valor por defecto.

- [X] T026 [P] [US2] `TEST/infrastructure/out/tracing/LangfuseChatTracePromptsTests.java`: conocimiento con fragmentos →
  `langfuse.observation.prompt.name/version` de `chat-system` + metadatos de generación `prompt.chat-system`,
  `prompt.chat-context` + metadatos de traza `prompt_chat_system`, `prompt_chat_context`; cortesía → enlace a `chat-small-talk` y
  **sin** `prompt_chat_context`; sin fragmentos con `no-context-mode=model` → `prompt_chat_empty_context`; respuesta fija sin
  llamar al modelo → ningún prompt; valor por defecto → **sin** atributos nativos y metadato `default`; versión fijada →
  `prompt_override=true`; un fallo al registrar no rompe el turno (regla de LAB-64, `safely`)
- [X] T027 [P] [US2] Ampliar `TEST/infrastructure/out/gateway/GatewayChatAdapterTraceTests.java`: el adaptador llama a
  `TurnTrace.promptsUsed(...)` **antes** de `startGeneration`, con las referencias de la instantánea del turno (una por prompt
  usado, las mismas que se enviaron al modelo) y `override=true` solo si la petición fijó versiones
- [X] T028 [US2] Añadir `default void promptsUsed(List<PromptReference> used, boolean override)` (no hace nada) a
  `BASE/application/port/out/TurnTrace.java` y llamarlo desde `GatewayChatAdapter` (obtiene el `TurnTrace` del contexto de Reactor, `TURN_TRACE_KEY`) con la
  instantánea del turno, **antes** de `startGeneration`. El caso de uso no interviene: no conoce las versiones. Depende de T005, T022
- [X] T029 [US2] Implementar `promptsUsed` en `BASE/infrastructure/out/tracing/LangfuseChatTrace.java` según
  `contracts/trace-prompts.md` (atributos nativos del prompt principal solo si su origen es Langfuse; metadatos de generación
  `langfuse.observation.metadata.prompt.<nombre>`; metadatos de traza `langfuse.trace.metadata.prompt_<nombre con _>`; sin texto
  de prompt; todo con `safely`). Reutilizar los nombres de `LangfuseAttributes`. `LangfuseChatTrace` guarda la lista recibida y la aplica al
  abrir la generación en `startGeneration`: los atributos nativos van en la generación y los metadatos de traza en el span raíz;
  si `promptsUsed` no se llamó, no escribe nada. Depende de T028
- [ ] T030 [US2] Ejecutar `quickstart.md` S-4 (incluido el filtro por metadato y el cambio de etiqueta a mitad de una respuesta) con 20
  preguntas de conocimiento y cortesía (las 20 trazas deben llevar nombre y versión, SC-005) y cronometrando cuánto tarda
  alguien en responder «¿con qué versión se generó esta respuesta?» (< 1 min); anotar el resultado en `validation.md`; si el filtro de la lista de trazas no admite el metadato, anotar cómo se filtra por la
  columna de metadatos (criterio del escenario 5). Depende de T029, T025

**Checkpoint**: las historias 1 y 2 funcionan; las trazas son atribuibles.

---

## Phase 5: User Story 3 — El chat no depende de Langfuse para tener prompt (P1)

**Objetivo**: Langfuse caído, lento o con ediciones malas nunca produce un error ni un retraso en el chat; los avisos están
acotados y no filtran contenido.

**Prueba independiente**: `quickstart.md` S-5: arrancar sin Langfuse y preguntar (valores por defecto); detenerlo después de
cargar y seguir preguntando (última versión); poner ediciones malas en `production` y comprobar que no se usan.

- [X] T031 [P] [US3] `TEST/infrastructure/out/prompts/PromptResilienceTests.java` con WireMock y planificador falso, un
  escenario por criterio: (a) Langfuse caído al arrancar → arranca en ≤ `request-timeout` y sirve valores por defecto;
  (b) cae después de cargar → sigue la última versión aunque haya pasado más de un intervalo; (c) respuesta lenta → `current`
  no espera (tiempo de lectura independiente de la lentitud) y SC-006; (d) Langfuse vuelve → el siguiente refresco recupera `production`; (e) prompt inexistente → valor por defecto; (f) ediciones
  malas: vacía, sin `{topics}`, `{cliente}` en `chat-context`, de más de `max-prompt-characters` y de tipo `chat` → se conserva
  la última válida
- [X] T032 [P] [US3] `TEST/infrastructure/out/prompts/PromptWarningTests.java` con un appender de registro: los avisos nombran
  prompt, versión y motivo (`blank`, `missing-placeholder`, `not-renderable`, `too-long`, `not-text`, `missing`, `failed`) y
  **nunca** el texto del prompt, las claves ni cabeceras; no más de un aviso por intervalo y por `prompt:<nombre>:<motivo>`
  aunque el refresco falle muchas veces
- [X] T033 [P] [US3] `TEST/infrastructure/out/prompts/PromptRefreshMetricsTests.java`: el contador
  `shoplab.chat.prompts.refresh` con etiquetas `prompt` y `result` (`updated`, `unchanged`, `invalid`, `missing`, `failed`) y el
  gauge de la versión cargada por prompt, con `SimpleMeterRegistry`; sin etiquetas con texto libre
- [X] T034 [US3] Usar `KeyedWarnThrottle` (T010) en `CachedPromptCatalog` y `PromptRefresher` para todos los avisos (clave
  `prompt:<nombre>:<motivo>`), registrando solo nombre, versión y motivo. Depende de T010, T018, T019
- [X] T035 [US3] Registrar el contador y el gauge de T033 en `CachedPromptCatalog`/`PromptRefresher` a través de
  `ChatMetricsPort` (o de `MeterRegistry` si el puerto no encaja; seguir lo que hacen las demás métricas del módulo). Depende de T018
- [X] T036 [US3] Garantizar FR-008 en el camino del turno: en `GatewayChatAdapter`, ninguna excepción del catálogo llega al
  cliente (el catálogo no lanza; el orden es última válida → valor por defecto). Añadir a `GatewayChatAdapterTests` un caso con
  catálogo que devuelve el valor por defecto y comprobar que el stream completa sin `error`. Depende de T022
- [ ] T037 [US3] Ejecutar `quickstart.md` S-5 (pasos 1 a 6, con un proxy que retrase 5 s para el paso 6) y anotar el resultado y
  ≥ 20 muestras del tiempo hasta el primer token con Langfuse activo y con los valores por defecto (mediana y diferencia;
  SC-006: < 5 %) en `validation.md`. Depende de T034, T035, T036

**Checkpoint**: Langfuse deja de ser un punto de fallo del chat.

---

## Phase 6: User Story 4 — Cambiar la etiqueta `production` cambia el comportamiento sin redesplegar (P1)

**Objetivo**: publicar, promover y revertir una versión en minutos, sin reiniciar; un turno en curso conserva su versión.

**Prueba independiente**: `quickstart.md` S-3 completo: v2 sin etiqueta no cambia nada; mover `production` a v2 cambia la
respuesta en ≤ `refresh-interval`; moverla de vuelta la revierte; una conversación con memoria conserva su historial.

- [X] T038 [P] [US4] `TEST/infrastructure/out/gateway/PromptSnapshotTests.java`: el adaptador toma los prompts al empezar el turno
  y los mantiene hasta terminar aunque el catálogo cambie de versión a mitad del stream (FR-012); el siguiente turno usa la
  nueva; las plantillas con y sin contexto salen de la misma instantánea
- [X] T039 [P] [US4] `TEST/application/usecase/StreamChatUseCaseMemoryPromptTests.java` (o ampliar
  `StreamChatUseCaseMemoryTests`): con una conversación abierta (`conversationId`), un cambio de versión entre dos turnos aplica
  la nueva al segundo y conserva el historial
- [X] T040 [P] [US4] `TEST/infrastructure/out/prompts/PromptLabelMoveTests.java` con WireMock: la versión sin etiquetar no se
  usa; mover `production` a otra versión se refleja tras un intervalo; mover de vuelta revierte exactamente al texto anterior
  (SC-004); retirar la etiqueta conserva la última válida con aviso
- [X] T041 [US4] Verificar que `GatewayChatAdapter` toma la instantánea una sola vez por turno y la usa para todo lo del turno
  (mensaje de sistema, plantilla y `promptsUsed`; no relee el catálogo en medio del stream) y dejarlo dicho en el Javadoc de la
  clase. Si T038 o T039 fallan, corregirlo ahí. Depende de T038, T039
- [X] T042 [P] [US4] Redactar en `docs/chat-streaming.md` la guía de FR-026: editar un prompt, probarlo (versión sin etiqueta),
  compararlo, ponerlo en producción, revertirlo y el tiempo máximo (`refresh-interval`); reemplazar lo que el documento dice del
  prompt de sistema en la configuración; incluir que un prompt no debe contener claves ni datos de clientes
- [ ] T043 [US4] Ejecutar `quickstart.md` S-3 con `REFRESH_INTERVAL=10s` y **dos instancias** del backend (puertos 8080 y 8081): ambas
  deben pasar a la versión nueva en ≤ `refresh-interval` (historia 4.5, FR-007). Cronometrar de «quiero cambiar una instrucción»
  a verla funcionando (SC-002: < 5 min) y comprobar que el 100 % de las preguntas hechas pasado el intervalo usa la versión
  nueva (SC-003). Anotar el resultado en `validation.md`. Depende de T041

**Checkpoint**: las historias 1 a 4 funcionan; **fin de la fase A del backend**.

---

## Phase 7: Infraestructura de DEV (US1, fase A)

**Propósito**: el cambio funciona en DEV, que el equipo usa como entorno local (los cambios de infraestructura no se difieren).

- [X] T044 [US1] Averiguar cómo llega un valor nuevo al secreto de Langfuse en `../shoplab-infra` (`externalsecret-langfuse.yaml`, el
  archivo local `secrets.auto.tfvars` de DEV y la guía de `docs/despliegue-continuo.md`); anotar la ruta en `research.md` R13 y
  decidir si `SHOPLAB_CHAT_PROMPTS_OVERRIDE_TOKEN` entra por ese camino. Sin respuesta, dejar el token fuera de DEV y decirlo
  en `validation.md`
- [X] T045 [US1] Añadir a `../shoplab-infra/k8s/overlays/dev/kustomization.yaml` `SHOPLAB_CHAT_PROMPTS_ENABLED=true`,
  `SHOPLAB_CHAT_PROMPTS_BASE_URL=http://langfuse-web:3000` y `SHOPLAB_CHAT_PROMPTS_PUBLISH_MISSING=true` (valores de
  `contracts/prompts-config.md`), y, según T044, el token por el secreto. Comprobar con `kustomize build` que el backend
  recibe las variables. Es un cambio en el repositorio `shoplab-infra` (PR aparte, mencionarlo en el PR de esta historia)
- [ ] T046 [US1] Tras el despliegue, ejecutar `quickstart.md` S-9 (publicación inicial en DEV, una traza con su versión, mover la
  etiqueta y ver el cambio en ≤ 1 minuto) y anotarlo en `validation.md`. Depende de T045 y de que la versión se despliegue

---

## Phase 8: User Story 5 — Evaluar y comparar dos versiones de un prompt (P2)

**Objetivo**: ejecutar `quality` fijando versiones sin tocar `production` y comparar dos resultados con advertencias de
comparabilidad.

**Prueba independiente**: `quickstart.md` S-6 (backend) y S-7 (eval).

### Backend: cabecera de evaluación (FR-017, FR-018, FR-023)

- [X] T047 [P] [US5] `TEST/infrastructure/in/rest/PromptOverrideHeaderTests.java`: sin cabecera → `PromptOverrides.NONE`; con
  `override-token` vacío → `PromptOverrideForbiddenException`; token ausente o distinto → la misma excepción, con comparación en
  **tiempo constante**; `X-Prompt-Token` se ignora si no hay `X-Prompt-Versions`; formato inválido, nombre desconocido o
  repetido, versión no entera o < 1 → `PromptOverrideException`; versiones válidas → `PromptOverrides` correcto; ni la cabecera ni
  el token aparecen en los mensajes (con un appender de registro, el valor de `X-Prompt-Token` no sale en ningún mensaje); con
  `enabled=false` y token configurado → `PromptOverrideForbiddenException` (`overrideEnabled()` falso)
- [X] T048 [P] [US5] Ampliar `TEST/infrastructure/in/rest/ChatControllerTests.java`: 403 sin token y con token malo, 400 con
  formato malo, **sin abrir el stream** en ambos casos; un cliente normal sin cabeceras no cambia; un campo extra en el cuerpo o un
  mensaje con «chat-system=3» no cambia ninguna versión (historia 5.7, FR-018)
- [X] T049 [P] [US5] `TEST/infrastructure/out/prompts/PinnedPromptsTests.java` con WireMock: `pinned(name, v)` lee
  `?version=v`, valida, guarda en la caché acotada («a lo sumo 32 entradas, sin caducidad porque una versión no cambia»);
  versión inexistente, no válida o Langfuse caído → error `PromptOverrideException` (nunca el valor por defecto ni `production`);
  dos peticiones concurrentes con versiones distintas no se afectan entre sí ni a `current`
- [X] T050 [P] [US5] `TEST/infrastructure/out/gateway/GatewayChatAdapterOverrideTests.java`: con `PromptOverrides` en el contexto
  de Reactor se usan las versiones fijadas para los prompts nombrados y `production` para los demás; la traza lleva
  `prompt_override=true`; versión inexistente → evento `error` con el código de `PromptOverrideException` (SC-010: ninguna otra
  petición cambia)
- [X] T051 [US5] Crear `BASE/infrastructure/in/rest/util/PromptOverrideHeader.java` (sin lógica de negocio: comprueba que
  `overrideEnabled()` sea verdadero, el token y el formato, y produce `PromptOverrides`) y usarlo en `BASE/infrastructure/in/rest/ChatController.java` (solo traduce la cabecera y la pasa
  al comando). Depende de T005, T006
- [X] T052 [US5] Añadir `PromptOverrides` a `BASE/domain/model/StreamChatCommand.java` (por omisión `NONE`) y propagarlo por el
  contexto de Reactor del turno con `PromptConstants.OVERRIDES_CONTEXT_KEY`, igual que `GatewayHeaders.REQUEST_ID_CONTEXT_KEY`.
  Actualizar los usos del comando en pruebas existentes. Depende de T051
- [X] T053 [US5] Implementar `pinned` en `CachedPromptCatalog` (reemplaza el marcador de T018) con la caché acotada de 32
  entradas, y hacer que `GatewayChatAdapter` use `pinned(...)` para los prompts nombrados en `PromptOverrides` y `current(...)`
  para el resto. Con la gestión apagada el override ya se rechazó con 403 en T051 (`overrideEnabled()`), así que `DefaultPromptCatalog.pinned`
  no se alcanza por HTTP.
  Depende de T049, T050, T052
- [X] T054 [US5] Ejecutar `quickstart.md` S-6 (curl con y sin token, 403, 400, versión inexistente) y anotarlo en `validation.md`.
  Depende de T053

### `eval/`: `quality --prompt` y `compare` (LAB-29 ya en `main`)

- [X] T055 [P] [US5] `eval/tests/test_prompt_selection.py`: analiza `--prompt NAME=VALOR` (versión entera o etiqueta); rechaza nombre
  desconocido, formato sin `=`, nombre repetido y versión < 1 con código de uso `2`; sin `--prompt` la selección está vacía
- [X] T056 [P] [US5] `eval/tests/test_prompt_client.py` con un servidor local falso (ampliar `eval/tests/fake_langfuse.py` con
  `GET /api/public/v2/prompts/{name}`): etiqueta → versión, versión existente, versión inexistente, tipo no `text`, Langfuse caído
  o sin claves → `MeasurementError` que nombra prompt y versión (FR-019, FR-023); no mide con valores por defecto
- [X] T057 [P] [US5] Ampliar `eval/tests/test_chat_client.py`: `SseChatClient` envía `X-Prompt-Versions` (resueltas, ordenadas) y
  `X-Prompt-Token` solo si hay selección; un `403` del backend detiene la medición con un mensaje que explica token ausente o
  incorrecto o capacidad apagada
- [X] T058 [P] [US5] Ampliar `eval/tests/test_quality_report.py`: el resumen gana `prompt_versions` **al final** (formato
  `chat-context=2|chat-system=3`, en orden alfabético; `production` si no se fijó nada); las columnas anteriores no cambian de
  nombre ni de orden (lectores de LAB-30/LAB-31)
- [X] T059 [P] [US5] `eval/tests/test_compare.py`: tabla con `metric`, `a_mean`, `a_n`, `b_mean`, `b_n`, `delta` (`b_mean − a_mean`
  con cuatro decimales, vacío si falta una media) para las tres métricas del ámbito `all`; «Prompts que difieren»; advertencias
  (una línea por parámetro distinto: `judge_model`, `judge_underlying_model`, `judge_temperature`, `assistant_model`,
  `embedding_model`, `cases`) con «No comparables» y código `0`; resumen ilegible o sin fila `all` → código `2`; **no abre red**
- [X] T060 [P] [US5] Ampliar `eval/tests/test_quality_cli.py`: `--prompt` con Langfuse caído, versión inexistente o formato malo
  termina antes de consultar al asistente, **sin escribir ningún archivo**, con los códigos de `contracts/eval-compare.md`
  (`2` uso, `1` Langfuse/versión); `--prompt` + `SHOPLAB_EVAL_PROMPT_TOKEN` envía las cabeceras en cada petición
- [X] T061 [US5] Crear `eval/src/shoplab_eval/prompt_selection.py` (puro: analiza y valida los argumentos) y
  `eval/src/shoplab_eval/prompt_client.py` (REST de Langfuse: etiqueta → versión y comprobación de existencia, con las claves
  `SHOPLAB_EVAL_LANGFUSE_*` de LAB-29). Añadir las constantes (nombres válidos, `SHOPLAB_EVAL_PROMPT_TOKEN`, cabeceras) a
  `eval/src/shoplab_eval/constants.py`. Depende de T055, T056
- [X] T062 [US5] Añadir a `eval/src/shoplab_eval/chat_client.py` el envío de `X-Prompt-Versions` y `X-Prompt-Token` en
  `SseChatClient.ask` y el mensaje claro ante `403`; añadir `prompt_versions` a `RunInfo` en
  `eval/src/shoplab_eval/quality_run.py` y la columna al final de `summary_csv` en `eval/src/shoplab_eval/quality_report.py`
  (más una línea en el informe de pantalla). Depende de T057, T058, T061
- [X] T063 [US5] Añadir `--prompt` (repetible) al comando `quality` en `eval/src/shoplab_eval/__main__.py`: validar → resolver
  etiquetas y comprobar versiones **antes** de `check_ready`/consultar al asistente → construir `SseChatClient` con las versiones
  resueltas. Verificar que las trazas de la ejecución llevan sus versiones (FR-024; lo hace el backend, T029). Depende de T060, T062
- [X] T064 [US5] Crear `eval/src/shoplab_eval/compare.py` (puro) y el subcomando `compare A.csv B.csv [--out ...]` en
  `eval/src/shoplab_eval/__main__.py` según `contracts/eval-compare.md`; no consulta modelos, Langfuse ni backend. Depende de T059
- [ ] T065 [US5] Ejecutar `quickstart.md` S-7 y S-8 pasos 1 y 2 (dos mediciones `quality` con versiones distintas y `compare`; la
  etiqueta `production` queda intacta; `--prompt chat-system=99` falla antes de medir; en Langfuse, las trazas de cada ejecución llevan `prompt_chat_system` con la versión fijada y el identificador de
  ejecución de LAB-29, FR-024) y anotar los números en `validation.md`.
  Depende de T053, T063, T064
- [X] T066 [P] [US5] Documentar `--prompt`, `SHOPLAB_EVAL_PROMPT_TOKEN`, `compare` y la columna `prompt_versions` en
  `eval/README.md` y añadir una nota a `specs/LAB-29-answer-quality-metrics/contracts/quality-csv.md` que apunte a
  `specs/LAB-85-versioned-prompts/contracts/eval-compare.md`

**Checkpoint**: las cinco historias funcionan.

---

## Phase 9: Polish y transversales

- [X] T067 [P] Crear `docs/adr/0027-prompts-versionados-en-langfuse.md` con la plantilla de `docs/adr/0000-plantilla.md` (fuente de
  verdad en Langfuse, caché con refresco en segundo plano y reemplazo, enlace en la traza, cabecera de evaluación con token,
  publicación inicial, retirada de propiedades; alternativas de `research.md`) y añadir su fila a `docs/adr/README.md`. Se usa
  el 0027 (decisión tomada): LAB-29 reservó el 0026 y no llegó a crearlo; si LAB-29 lo añade después, no hay colisión
- [X] T068 [P] Actualizar `README.md` (sección de Langfuse de LAB-64): variables `SHOPLAB_CHAT_PROMPTS_*` con sus valores locales
  (`contracts/prompts-config.md`), la publicación inicial en menos de 5 minutos (SC-008) y cómo se edita/promueve un prompt;
  actualizar `docs/gateway-de-modelos.md` o `docs/observabilidad.md` solo si mencionan los prompts retirados
- [X] T069 Generar `specs/LAB-85-versioned-prompts/requests.http` según la regla «HTTP test requests after implementation»: un
  caso por escenario de la historia 5 y de FR-017/FR-018 (con token válido, sin token → 403, token malo → 403, formato malo →
  400, nombre desconocido → 400, versión inexistente → evento `error`) y los límites (versión 1 y 0); `baseUrl` =
  `http://localhost:` + `server.port` de `application.yml`; sin secretos reales. Anotar en el encabezado qué no se cubre por HTTP
  (cache, trazas, refresco). Depende de T053
- [X] T070 Ejecutar `./mvnw test` en `backend/` y revisar fallos; luego `./mvnw checkstyle:check` y `./mvnw verify` para
  JaCoCo ≥ 80 % (comprobar que `chat.infrastructure.out.prompts` y `PromptValidator` cuentan en la cobertura), y los tests de
  `eval/` con `uv run pytest`. Corregir PMD/Checkstyle (líneas ≤ 121, llaves obligatorias, imports sin usar)
- [ ] T071 Ejecutar `quickstart.md` S-8 completo (recuperación antes/después con `retrieval`, calidad antes/después con `compare`,
  y el `git grep` de SC-001) y completar `specs/LAB-85-versioned-prompts/validation.md` con el resultado de cada escenario y lo
  que quedó sin comprobar (por ejemplo, el token de DEV). Depende de todas las anteriores
- [X] T072 Revisar el diff completo (`git status`, `git diff`) buscando archivos sueltos, formato masivo o imports reordenados;
  confirmar que no hay secretos ni el texto de un prompt fuera de `resources/prompts/`. No se hace commit ni push sin que lo pida
  la persona; el título del PR será `feat(chat): [LAB-85] prompts versionados en Langfuse` con el pie de las instrucciones del repo

---

## Dependencias y orden de ejecución

### Dependencias entre fases

- **Setup (1)**: sin dependencias. T001 desbloquea el cliente (T017); T002 desbloquea T011.
- **Foundational (2)**: depende de Setup; **bloquea** todas las historias.
- **US1 (3)**: depende de la fase 2. MVP.
- **US2 (4)**: depende de US1 solo por T022 (el adaptador ya pide los prompts al catálogo) y T025 para validar en vivo.
- **US3 (5)** y **US4 (6)**: dependen de US1 (caché, refresco y adaptador). Pueden ir en paralelo entre sí y con US2.
- **Infra DEV (7)**: depende de US1; no bloquea a las demás.
- **US5 (8)**: la parte de backend depende de US1 (T018, T022); la parte de `eval/` depende de la cabecera del backend (T053)
  solo para la validación de T065; sus pruebas (T055 a T060) pueden hacerse antes con servidores falsos.
- **Polish (9)**: depende de las historias que se vayan a entregar.

### Dependencias dentro de las historias

- Pruebas primero (deben **fallar** antes de implementar), luego tipos → servicios → adaptadores → integración.
- T017 → T018 → T019 → T020/T021; T022 → T023 → T024.
- T028 → T029 (trazas); T051 → T052 → T053 (cabecera); T061 → T062 → T063 (eval); T059 → T064 (`compare`).

### Oportunidades de paralelismo

- Fase 2: T003, T004, T005, T006, T008a, T008, T009 y T010 son archivos distintos y pueden hacerse a la vez (T007 espera a T003 y
  T005; T008 a T008a; T011 a T008; T012 a T011).
- US1: las pruebas T013 a T016 son independientes entre sí; T022 (adaptador) corre en paralelo con T017 a T020 (cliente y
  caché) porque ambos dependen solo del puerto de la fase 2.
- US3 y US4 completas en paralelo; T026/T027, T031 a T033, T038 a T040 y T047 a T050 son pruebas de archivos distintos.
- US5: backend (T047 a T054) y `eval/` (T055 a T066) son dos carriles independientes hasta T065.

### Ejemplo de paralelo: fase 2

```text
T003 PromptName / PromptSource        T006 excepciones + ResponseCodeEnum + handler
T004 PromptConstants                  T008 PromptValidator + pruebas
T005 PromptReference, ResolvedPrompt  T008a PromptRenderer, T008 PromptValidator, T009 PromptProperties
T010 WarnThrottle compartido y KeyedWarnThrottle
```

### Ejemplo de paralelo: historia 5

```text
Carril backend: T047 T048 T049 T050  →  T051 → T052 → T053 → T054
Carril eval:    T055 T056 T057 T058 T059 T060  →  T061 → T062 → T063 / T064 → T065
```

---

## Estrategia de implementación

### MVP primero (solo historia 1)

1. Fase 1 (T001, T002) y fase 2 completa.
2. Fase 3 (US1) → **parar y validar** con `quickstart.md` S-2/S-3 (T025). Ya no hay prompts en el código y el chat usa Langfuse.
3. Esto por sí solo **no es seguro para producción**: falta la resiliencia medida de US3. Entregar US1 + US3 juntas.

### Entrega incremental

1. Setup + Foundational → base lista.
2. US1 → valida (MVP técnico).
3. US3 → resiliencia (condición para quitar los prompts del código con seguridad) → **primera entrega recomendada**.
4. US2 → trazabilidad. US4 → guía y pruebas del ciclo de cambio.
5. Infra DEV (fase 7) cuando la versión esté lista para desplegarse.
6. US5 (backend + `eval/`) → cierra el ciclo de la Constitución (V).
7. Polish, ADR y validación final.

### Si se trabaja en equipo

Tras la fase 2: A toma US1 (T013 a T025), B toma US2 y US3 (apoyándose en T022 a T024 cuando estén), C toma la fase B de `eval/`
(T055 a T066) con servidores falsos; la integración final es T053 → T065.

---

## Notas

- `[P]` = archivos distintos y sin dependencias pendientes. Verificar que cada prueba falla antes de implementar.
- No cambiar el texto de ningún prompt (FR-028): cualquier diferencia con los SHA-256 de T002 es un error.
- Ni el texto de un prompt, ni las claves, ni el token salen en avisos, métricas, trazas de error ni `toString`.
- Hacer commit por tarea o grupo lógico **solo cuando la persona lo pida** (commits convencionales con `feat(chat): [LAB-85] …`).
- Pendientes de validar con una persona (no se resuelven en código): cómo llega el token a DEV (T044) y los resultados de S-5/S-7
  con el modelo real.
