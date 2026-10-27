# Tasks: Pantalla de chat con streaming y Markdown

**Input**: Design documents from `/specs/LAB-14-chat-streaming-markdown-ui/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: se piden (constitución, principio IV y FR-019 del spec): cada pieza nueva lleva su prueba unitaria,
escrita antes o junto al código (Vitest + jsdom). Ninguna prueba necesita el backend real ni un navegador instalado
a mano; el `fetch` y el planificador de fotogramas se sustituyen por dobles.

**Organization**: tareas agrupadas por historia de usuario en orden de prioridad: **US1 (P1) → US2 (P1) → US3 (P1) →
US4 (P2) → US5 (P2) → US6 (P3)**. Lo que bloquea a todas (dependencias, modelos, parser SSE, cliente de streaming)
va en las fases 1 y 2.

Abreviaturas de rutas (relativas a la raíz del repositorio):

- `FE` = `frontend`
- `APP` = `frontend/src/app`
- `CHAT` = `frontend/src/app/features/chat`
- `SPEC` = `specs/LAB-14-chat-streaming-markdown-ui`

Estructura, contratos y modelo: [plan.md](plan.md), [contracts/chat-stream-client.md](contracts/chat-stream-client.md),
[contracts/markdown-rendering.md](contracts/markdown-rendering.md), [data-model.md](data-model.md).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de `spec.md` a la que pertenece (US1…US6)
- Identificadores de código en inglés; textos de interfaz en español. Componentes standalone, estado con signals,
  control flow nativo (`@if`, `@for`), Angular Material como única librería de UI (ADR-0013). Ningún componente
  llama a `fetch` ni a `bypassSecurityTrustHtml`.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: dependencias y decisión registrada.

- [X] T001 Instalar las dependencias de renderizado en `FE/`: `npm install marked dompurify` (comprobar con `npm view` la última versión estable ≥ 2 semanas; `dompurify` trae sus tipos, **no** instalar `@types/dompurify`); verificar que `FE/package.json` y `FE/package-lock.json` quedan actualizados y que `npm run build` sigue en verde
- [X] T002 [P] Crear `docs/adr/0014-markdown-seguro-en-el-chat.md` con el formato de `docs/adr/0013-biblioteca-ui-angular-material.md` (Estado: aceptado, Fecha, Contexto, Decisión, Consecuencias): `marked` + `DOMPurify` con lista blanca, punto único de confianza (`MarkdownRenderer`), `fetch` + `ReadableStream` en lugar de `EventSource`/librería SSE; alternativas de `SPEC/research.md` (R1, R3, R4); y añadir su fila al índice de `docs/adr/README.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: tipos compartidos, parser SSE y cliente de streaming. Ninguna historia puede empezar antes.

**⚠️ CRITICAL**: el parser y el cliente son donde se pierden o duplican fragmentos; se prueban a fondo aquí.

- [X] T003 [P] Crear `CHAT/chat-message.ts` con los tipos de `SPEC/data-model.md`: `MessageAuthor = 'user' | 'assistant'`, `MessageStatus = 'streaming' | 'complete' | 'stopped' | 'failed'`, `ChatMessage { id; author; content; status; error? }` y la unión discriminada `ChatStreamEvent` (`token {content}`, `done {fragments}`, `error {code, message}`); y las constantes `MAX_MESSAGE_LENGTH = 4000` y `COUNTER_THRESHOLD = 3500` (sin números mágicos en otros archivos)
- [X] T004 [P] Crear `CHAT/chat-error.ts` con `ChatErrorKind = 'network' | 'validation' | 'unavailable' | 'server' | 'stream'`, la clase `ChatStreamError { kind; status?; code?; detail? }` y `toUserMessage(kind)` que devuelve exactamente los textos en español de la tabla de `SPEC/data-model.md`; nunca devuelve `code` ni `detail`. Prueba en `CHAT/chat-error.spec.ts` (un caso por `kind`; ningún mensaje contiene `code`)
- [X] T005 [P] Escribir primero `CHAT/sse-parser.spec.ts` con los casos de `SPEC/contracts/chat-stream-client.md` (§`parseSse`): `\n` y `\r\n` dan el mismo resultado; evento partido entre llamadas a `push`, incluso un carácter por llamada, se emite una sola vez y completo; varias líneas `data:` se unen con `\n`; línea `:` ignorada; línea en blanco sin datos no emite; solo se elimina el primer espacio tras `data:`; evento por defecto `message`; alimentar un flujo de 200 eventos `token` en cortes aleatorios reproduce exactamente la misma lista
- [X] T006 Implementar `CHAT/sse-parser.ts` (`createSseParser()` → `{ push(chunk: string): SseEvent[] }`, con `SseEvent { event: string; data: string }`) hasta que `T005` pase. Función pura, sin dependencias, sin Angular
- [X] T007 [P] Escribir primero `CHAT/chat-stream.client.spec.ts` con un `fetch` simulado que devuelve un `ReadableStream<Uint8Array>` entregado en cortes arbitrarios: (a) la petición es `POST /api/chat`, `Content-Type: application/json`, `Accept: text/event-stream`, cuerpo `{"message": <texto>}`; (b) emite `token`s en orden y completa tras `done`; (c) un carácter multibyte (`ñ`, emoji) partido entre dos bloques llega íntegro; (d) un `error` del flujo se emite como `ChatStreamEvent` y completa; (e) HTTP 400 → `ChatStreamError('validation')`, 503 → `'unavailable'`, 500 → `'server'`, `fetch` rechazado → `'network'`; (f) el cuerpo termina sin `done`/`error` → `'stream'`; (g) desuscribirse llama a `abort()` y a `reader.cancel()` y no emite error; (h) `data` con JSON inválido se ignora sin contenido en el aviso
- [X] T008 Implementar `CHAT/chat-stream.client.ts`: servicio `@Injectable({ providedIn: 'root' })` `ChatStreamClient` con `stream(message: string): Observable<ChatStreamEvent>` según `SPEC/contracts/chat-stream-client.md` (`fetch` + `AbortController`, un `TextDecoder` con `stream: true`, `createSseParser`, mapeo de errores HTTP a `ChatStreamError`, un solo evento final, liberar el lector al terminar/fallar/desuscribirse) hasta que `T007` pase. No usar `HttpClient` ni `EventSource`

**Checkpoint**: el flujo SSE se consume y se prueba sin interfaz.

---

## Phase 3: User Story 1 - Conversar viendo la respuesta escribirse (Priority: P1) 🎯 MVP

**Goal**: escribir un mensaje y ver burbujas de usuario y asistente, con la respuesta creciendo fragmento a fragmento
e indicador de escritura (FR-001…FR-005, FR-015, FR-018, FR-020).

**Independent Test**: escenarios 1–3 de `SPEC/quickstart.md` con el backend real (o el simulado de §4).

### Tests for User Story 1

- [X] T009 [P] [US1] Escribir `CHAT/chat-store.spec.ts` (cliente falso que emite eventos controlados y planificador de fotogramas manual): `send('hola')` añade de inmediato un mensaje `user` `complete` y uno `assistant` `streaming` vacío y llama al cliente una vez con el texto; los `token` se acumulan en orden y **no** se vuelcan al *signal* hasta el siguiente fotograma (varios `token` en un fotograma → una sola actualización); `done` vuelca el búfer pendiente y deja `complete`; respuesta con cero `token` + `done` queda `complete` con `content` vacío; `isStreaming()` es verdadero entre el envío y el estado final; `draft` se vacía al enviar; los `id` son únicos
- [X] T010 [P] [US1] Escribir `CHAT/message-bubble/message-bubble.spec.ts`: el mensaje `user` se alinea como cliente y el `assistant` como asistente (clases distintas); el contenido del `user` se muestra literal (`<b>x</b>` aparece como texto); un `assistant` con `status = 'streaming'` muestra el indicador de escritura (`role="status"`), incluso con `content` vacío, y uno `complete` no; cada burbuja tiene `aria-label` con el autor («Tú» / «Asistente»); un `assistant` `complete` con `content` vacío muestra «Sin respuesta» (respuesta vacía del modelo, no es un error) y no muestra el indicador de escritura
- [X] T011 [P] [US1] Escribir `CHAT/chat.spec.ts` (con `ChatStore` falso): carga la pantalla vacía con la invitación a escribir, el campo y el control de enviar; enviar añade las dos burbujas; la lista usa `@for` con `track` por `id`

### Implementation for User Story 1

- [X] T012 [US1] Implementar `CHAT/chat-store.ts`: `@Injectable()` (se provee en el componente, no en `root`) con signals `messages`, `draft`, y derivados `isStreaming` y `lastUserMessage`; `send(text)` crea los dos mensajes, se suscribe a `ChatStreamClient.stream`, acumula `token` en un búfer y lo vuelca como máximo una vez por fotograma (`requestAnimationFrame` inyectable mediante un token para poder sustituirlo en pruebas), y al `done` vuelca y marca `complete`. Hasta que `T009` pase
- [X] T013 [P] [US1] Crear `CHAT/message-bubble/message-bubble.ts` (+ `.html`/`.scss`): componente standalone con `input()` de `ChatMessage`; muestra el contenido como texto (el Markdown llega en US3), el indicador de escritura animado con `role="status"` y `aria-label` de autor; el texto «Sin respuesta» cuando el asistente está `complete` con `content` vacío; estilos diferenciados por autor, `overflow-wrap: anywhere` y ancho máximo relativo. Hasta que `T010` pase
- [X] T014 [P] [US1] Crear `CHAT/message-input/message-input.ts` (+ `.html`/`.scss`): componente de presentación standalone con contrato fijo: `draft = model<string>('')` (enlazado al `draft` del store), `canSend = input<boolean>(false)`, `streaming = input<boolean>(false)`, `send = output<void>()` y `stop = output<void>()` (los dos últimos se conectan en `T021`/`T035`); `textarea` (`cdkTextareaAutosize`), botón de enviar (icono Material); Enter dispara `send` y Mayús+Enter inserta salto de línea; texto en español («Escribe tu mensaje…», «Enviar»). La validación llega en US5
- [X] T015 [US1] Crear `CHAT/chat.ts` (+ `chat.html`/`chat.scss`): contenedor con `providers: [ChatStore]`; historial con `role="log"` y `aria-live="polite"`, `@for` de `app-message-bubble` con `track message.id`, invitación a escribir cuando está vacío, y `app-message-input`; seguimiento del final: `pinnedToBottom` (signal) se actualiza en el evento `scroll` del contenedor y, con cada fragmento nuevo, se hace `scrollTop = scrollHeight` solo si está anclado (FR-015); botón «Ir al final» cuando no lo está. Hasta que `T011` pase
- [X] T016 [US1] Registrar la ruta en `APP/app.routes.ts` antes del comodín `**`: `{ path: 'chat', title: 'Chat', data: { nav: { label: 'Chat', icon: 'chat' } }, loadComponent: () => import('./features/chat/chat').then((m) => m.Chat) }`; actualizar `APP/app.routes.spec.ts` para comprobar que `/chat` existe, tiene título y aparece en la navegación (sin tocar el layout)
- [X] T017 [US1] Devolver el foco al campo de texto tras enviar (`afterNextRender`/`viewChild` en `CHAT/message-input/message-input.ts`) y probarlo en `CHAT/chat.spec.ts`

**Checkpoint**: se puede conversar y ver el streaming en burbujas. MVP demostrable.

---

## Phase 4: User Story 2 - Detener la respuesta (Priority: P1)

**Goal**: cortar una respuesta en curso conservando lo recibido (FR-006, FR-016).

**Independent Test**: escenarios 4, 5 y 18 de `SPEC/quickstart.md`.

### Tests for User Story 2

- [X] T018 [P] [US2] Ampliar `CHAT/chat-store.spec.ts`: `stop()` desuscribe (el cliente falso registra el `abort`), vuelca el búfer pendiente, marca el mensaje `stopped` conservando el texto y `isStreaming()` pasa a falso; una cancelación del usuario no produce estado `failed`; tras `stop()` un nuevo `send` crea un asistente nuevo sin mezclar fragmentos de la respuesta anterior (un `token` tardío del flujo anterior se ignora); `stop()` sin respuesta en curso no hace nada; `stop()` justo después de `done` deja `complete` (un único mensaje, sin estado duplicado); `ngOnDestroy`/`DestroyRef` cancela la conexión abierta
- [X] T019 [P] [US2] Ampliar `CHAT/message-input/` con su prueba (`message-input.spec.ts`): con `streaming = true` se muestra el botón «Detener» (accesible, con `aria-label`) y emite `stop`; con `streaming = false` no existe en el DOM

### Implementation for User Story 2

- [X] T020 [US2] Implementar `stop()` y la limpieza en `CHAT/chat-store.ts` (guardar la `Subscription` actual, volcar el búfer, marcar `stopped`, ignorar eventos de suscripciones obsoletas con un identificador de petición; `inject(DestroyRef).onDestroy` cancela la conexión). Hasta que `T018` pase
- [X] T021 [US2] Añadir a `CHAT/message-input/message-input.ts`/`.html` la entrada `streaming` y la salida `stop`: el botón «Detener» sustituye al de enviar mientras `streaming` es verdadero; conectarlos en `CHAT/chat.html`. Hasta que `T019` pase
- [X] T022 [US2] Marcar visualmente la burbuja `stopped` en `CHAT/message-bubble/` (etiqueta «Respuesta interrumpida» bajo el texto, igual que en el spec, sin estilo de error) y cubrirlo en `message-bubble.spec.ts`

**Checkpoint**: US1 y US2 funcionan juntas; el backend registra la cancelación.

---

## Phase 5: User Story 3 - Markdown renderizado de forma segura (Priority: P1)

**Goal**: respuestas con formato y ningún HTML activo en el DOM (FR-007…FR-010, FR-019).

**Independent Test**: escenarios 6–9 de `SPEC/quickstart.md` y la batería de `SPEC/contracts/markdown-rendering.md`.

### Tests for User Story 3

- [X] T023 [P] [US3] Escribir primero `APP/core/markdown/markdown-renderer.spec.ts` con **la batería completa** de `SPEC/contracts/markdown-rendering.md` (tabla «Batería de pruebas obligatoria»), montando el resultado en un contenedor del DOM y comprobando elementos y atributos (no solo la cadena): sin `script`, `iframe`, `svg`, `style`, `img` ni atributos `on*`; sin `href` con `javascript:`/`data:`; enlaces con `target="_blank"` y `rel="noopener noreferrer"`; imagen reemplazada por su texto alternativo como enlace; HTML dentro de código escapado. Más: Markdown estándar (encabezados, listas, tablas, citas, código en línea y en bloque) conserva su estructura; entradas parciales (``` sin cerrar, `**texto`, tabla a medias) no lanzan errores; cadena vacía → cadena vacía; `render(T)` igual para el mismo `T` en llamadas repetidas

### Implementation for User Story 3

- [X] T024 [US3] Implementar `APP/core/markdown/markdown-renderer.ts`: `@Injectable({ providedIn: 'root' })` `MarkdownRenderer` con `render(markdown: string): SafeHtml`; `marked` con GFM y modo síncrono; `DOMPurify.sanitize` con `ALLOWED_TAGS`/`ALLOWED_ATTR` exactamente los de la lista blanca del contrato y `ALLOWED_URI_REGEXP` para `http`, `https`, `mailto` y anclas; *hook* `afterSanitizeAttributes` que fuerza `target="_blank"` y `rel="noopener noreferrer"` en `<a>`; las imágenes se transforman antes en enlace con su texto alternativo (renderer de `marked`); es el **único** archivo que llama a `bypassSecurityTrustHtml`. Hasta que `T023` pase
- [X] T025 [US3] Ampliar `CHAT/message-bubble/message-bubble.spec.ts`: el `assistant` renderiza Markdown (lista → `<ul>`, bloque ``` → `<pre><code>`); el `user` sigue literal; un `assistant` con `<script>` no lo inserta; durante `streaming` con un bloque sin cerrar el DOM sigue válido; el resultado final de renderizar por prefijos sucesivos coincide con renderizar el texto completo
- [X] T026 [US3] Integrar en `CHAT/message-bubble/message-bubble.ts`/`.html`: para `author = 'assistant'` usar `computed(() => renderer.render(message().content))` y `[innerHTML]`; el autor `user` mantiene la interpolación `{{ }}`. Hasta que `T025` pase
- [X] T027 [P] [US3] Añadir estilos de contenido Markdown en `CHAT/message-bubble/message-bubble.scss`: `pre` con `overflow-x: auto` y fuente monoespaciada, `code` en línea con fondo suave, `table` con borde y `overflow-x: auto` en contenedor, `blockquote`, listas y encabezados con tamaños acotados a la burbuja; sin desbordar el ancho de la burbuja a 360 px
- [X] T028 [US3] Buscar con `grep` en `FE/src` que `bypassSecurityTrustHtml` y `[innerHTML]` solo aparecen en `markdown-renderer.ts` y `message-bubble.html`, y añadir una guardia contra regresiones: preferir una regla de lint (`no-restricted-syntax`/regla de plantilla de `angular-eslint` que prohíba `[innerHTML]` fuera de `message-bubble.html`); si no es viable, una prueba en `APP/core/markdown/markdown-renderer.spec.ts` que lea `message-bubble.html` y falle si aparece otro `[innerHTML]` (comprobar que la lectura de archivos funciona bajo el runner)

**Checkpoint**: las tres historias P1 funcionan; el chat es seguro y legible.

---

## Phase 6: User Story 4 - Errores claros en el chat (Priority: P2)

**Goal**: avisos en español sin indicador colgado y con reintento (FR-011, FR-012).

**Independent Test**: escenarios 10–13 de `SPEC/quickstart.md`.

### Tests for User Story 4

- [X] T029 [P] [US4] Ampliar `CHAT/chat-store.spec.ts`: error previo al flujo (`ChatStreamError` `network`/`validation`/`unavailable`/`server`) deja el mensaje del asistente `failed` con su `error` y `isStreaming()` falso; un evento `error` o un corte tras recibir texto conserva el texto parcial, estado `failed` y `error = 'stream'`; `retry()` reenvía `lastUserMessage` y crea un asistente **nuevo** (el fallido no se reabre) sin duplicar la burbuja del usuario; `retry()` sin mensaje previo no hace nada; el estado es utilizable (se puede enviar) tras cada tipo de error
- [X] T030 [P] [US4] Ampliar `CHAT/message-bubble/message-bubble.spec.ts`: un `assistant` `failed` muestra el texto parcial, un aviso con `role="alert"` y el mensaje de `toUserMessage(error)`, sin `code` ni `detail`, y el botón «Reintentar» (emite `retry`); el indicador de escritura no se muestra

### Implementation for User Story 4

- [X] T031 [US4] Implementar en `CHAT/chat-store.ts` el manejo de errores (`catchError` → `failed` + `error`; evento `error` → `failed` con `kind = 'stream'`) y `retry()`; el `ChatStreamError` se registra en consola solo con `kind` y `status`, nunca con el contenido del mensaje (constitución VI). Hasta que `T029` pase
- [X] T032 [US4] Mostrar el aviso y «Reintentar» en `CHAT/message-bubble/message-bubble.html` (`role="alert"`, icono de error, texto de `toUserMessage`) y conectar `retry` a `ChatStore.retry()` en `CHAT/chat.html`; solo la última burbuja fallida ofrece reintentar. Hasta que `T030` pase

**Checkpoint**: ningún fallo deja la pantalla colgada.

---

## Phase 7: User Story 5 - Entrada de mensajes cómoda y controlada (Priority: P2)

**Goal**: validación en el cliente espejo de LAB-12 (FR-013, FR-014).

**Independent Test**: escenarios 14–16 de `SPEC/quickstart.md`.

### Tests for User Story 5

- [X] T033 [P] [US5] Ampliar `CHAT/message-input/message-input.spec.ts` y `CHAT/chat-store.spec.ts`: `draft` vacío o solo espacios (`'   '`, `'\n'`) → enviar deshabilitado y `send` no llama al cliente; `draft.length = 4000` se envía; `4001` queda bloqueado; el contador aparece a partir de `COUNTER_THRESHOLD` (3500) con formato `n / 4000` y pasa a estado de error por encima de 4000; con `isStreaming()` el envío está bloqueado; Enter envía una vez y un segundo Enter inmediato no envía otra (doble envío); Mayús+Enter no envía; el mensaje enviado conserva los espacios internos (solo `trim()` para decidir si está vacío)

### Implementation for User Story 5

- [X] T034 [US5] Implementar en `CHAT/chat-store.ts` el derivado `canSend` (`draft().trim().length > 0 && draft().length <= MAX_MESSAGE_LENGTH && !isStreaming()`) y hacer que `send` sea un no-op si `canSend()` es falso (cubre el doble Enter). Hasta que `T033` pase
- [X] T035 [US5] Implementar en `CHAT/message-input/message-input.ts`/`.html`: botón de enviar deshabilitado según el input `canSend` (que `chat.html` enlaza a `ChatStore.canSend()`), contador `n / 4000` visible desde 3500 y con estilo de error (`mat-error` o clase) por encima de 4000 con texto «Máximo 4000 caracteres», y `aria-describedby` hacia el contador; el `keydown.enter` sin Mayús llama a `preventDefault()` y envía solo si `canSend`. Hasta que `T033` pase

**Checkpoint**: las reglas del backend se respetan sin ir a la red.

---

## Phase 8: User Story 6 - Pantalla accesible y adaptable (Priority: P3)

**Goal**: 360–1440 px sin desbordes, teclado y lector de pantalla (FR-017).

**Independent Test**: escenario 17 de `SPEC/quickstart.md`.

- [X] T036 [P] [US6] Revisar `CHAT/chat.scss` y `CHAT/message-bubble/message-bubble.scss`: el contenedor ocupa el alto disponible con el historial desplazable y el área de entrada fija abajo (`height` sin desbordes dentro de `mat-sidenav-content`), burbujas con `max-width` relativo (≈ 85 % en móvil, 70 % en escritorio), sin scroll horizontal de página a 360 px; los bloques `pre`/`table` desplazan dentro de la burbuja
- [X] T037 [P] [US6] Revisar accesibilidad en `CHAT/chat.html`, `CHAT/message-bubble/message-bubble.html` y `CHAT/message-input/message-input.html`: `role="log"` + `aria-live="polite"` en el historial, `aria-label` de autor en cada burbuja, `aria-label` en enviar/detener/ir al final, foco visible en todos los controles, orden de tabulación lógico (historial → campo → enviar/detener), contraste del tema Material; el indicador de escritura sin animación excesiva con `prefers-reduced-motion`; evitar anuncios dobles en lectores de pantalla (si `role="log"` + `role="status"` duplican el aviso, dejar un solo punto de anuncio)
- [X] T038 [US6] Ampliar `CHAT/chat.spec.ts` con las comprobaciones verificables en jsdom: atributos ARIA presentes, botones con nombre accesible, todo el flujo (escribir, enviar, detener) alcanzable con eventos de teclado y el seguimiento del final (FR-015): con el historial anclado abajo, un fragmento nuevo deja `scrollTop = scrollHeight`; tras subir (evento `scroll` alejado del final) no se mueve y aparece «Ir al final»; al pulsarlo vuelve a seguir

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: documentación, verificación completa y limpieza.

- [X] T039 [P] Documentar en `README.md` (sección frontend) la pantalla de chat: ruta `/chat`, cómo probarla en local (backend LAB-12 + `npm start`), cómo funciona el consumo SSE con `POST`, y el aviso de que el renderizado de Markdown pasa solo por `MarkdownRenderer` (ver ADR-0014); incluir el límite conocido de `/api` en la imagen del contenedor
- [X] T040 [P] Crear la matriz de trazabilidad FR → prueba/escenario en `SPEC/validation.md`: comprobar que cada FR-001…FR-021 tiene al menos una prueba o un escenario de `SPEC/quickstart.md`, y anotar en `SPEC/validation.md` la matriz FR → prueba/escenario (formato de `specs/LAB-13-angular-base-app/validation.md`)
- [X] T041 Ejecutar en `FE/`: `npm run lint`, `npm test -- --watch=false` y `npm run build`; resolver cualquier advertencia, fallo o exceso de presupuesto (0 advertencias de lint, SC-008)
- [ ] T042 Recorrer los 18 escenarios de `SPEC/quickstart.md` contra el backend real de LAB-12 (y el simulado para XSS/errores a mitad de flujo); medir el mensaje visible (<100 ms), el primer fragmento (<300 ms tras emitirse), las ≥10 actualizaciones visibles y la respuesta de «Detener» (<500 ms) (SC-001…SC-003), y cronometrar con una persona que no conozca la app la primera conversación (<30 s, SC-007); registrar cada medida, o «no verificado» si no se pudo, en `SPEC/validation.md`; comprobar en `/actuator/prometheus` que la cancelación incrementa `outcome="cancelled"` y no `error`
- [X] T043 Confirmar que el job `frontend` de CI (`.github/workflows/ci.yml`) pasa en el PR sin cambios de configuración; si falla por las nuevas dependencias o por el runner, corregirlo en el mismo PR
- [X] T044 Pre-commit (`.ai/rules/development-guidelines.md` §8): `git status --short` sin archivos sueltos (`dist/`, `node_modules/`, logs, servidor SSE simulado de pruebas manuales), `git diff --staged` solo con cambios intencionales, commits atómicos con Conventional Commits; título del PR con el formato `feat(chat): [LAB-14] pantalla de chat con streaming y Markdown`
- [X] T045 Nota sobre `requests.http`: esta historia **no añade ni cambia endpoints HTTP** (consume `POST /api/chat` de LAB-12); no se genera `requests.http` (regla «HTTP test requests after implementation»). Indicarlo en una línea en el resumen final de `/speckit-implement`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; `T001` y `T002` en paralelo.
- **Foundational (Phase 2)**: depende de `T001` (tipos y parser no necesitan `marked`, pero comparten el mismo árbol de dependencias); **bloquea todas las historias**.
- **US1 (Phase 3)** → depende de Phase 2. **US2** depende de US1 (extiende `ChatStore` y `MessageInput`). **US3** depende de US1 (extiende `MessageBubble`) y no de US2. **US4** depende de US1 (y toca `ChatStore`/`MessageBubble`, por lo que va después de US2/US3 si se trabaja en serie). **US5** depende de US1 y US2 (usa `isStreaming` y `streaming`). **US6** depende de US1–US3.
- **Polish (Phase 9)**: depende de todas las historias que se vayan a entregar.

### Within Each User Story

- La prueba se escribe antes o junto a la implementación y debe pasar al cerrar la tarea.
- Modelos y servicios (`ChatStore`) antes de componentes; componentes antes de la pantalla (`chat.ts`) y de la ruta.
- Varias tareas tocan los mismos archivos (`chat-store.ts`, `message-bubble.*`, `message-input.*`): **no** se marcan `[P]` entre sí.

### Parallel Opportunities

- Phase 1: `T001` ‖ `T002`.
- Phase 2: `T003` ‖ `T004` ‖ `T005` ‖ `T007` (archivos distintos); `T006` tras `T005`; `T008` tras `T003`, `T004`, `T006` y `T007`.
- US1: `T009` ‖ `T010` ‖ `T011`; luego `T013` ‖ `T014` (tras `T012` si usan el store); `T015` después de ambos.
- US3: `T023` ‖ (`T027` estilos) mientras `T024` avanza.
- US4: `T029` ‖ `T030`.
- Phase 8: `T036` ‖ `T037`.
- Phase 9: `T039` ‖ `T040`.

### Parallel Example: Phase 2

```text
Task: "T003 chat-message.ts (tipos)"            ┐
Task: "T004 chat-error.ts + spec"               ├─ en paralelo
Task: "T005 sse-parser.spec.ts"                 │
Task: "T007 chat-stream.client.spec.ts"         ┘
Después: T006 (parser) → T008 (cliente)
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 y 2 (dependencias, tipos, parser, cliente).
2. Phase 3 (US1): conversar con streaming en burbujas.
3. **PARAR y VALIDAR**: escenarios 1–3 del quickstart. Es demostrable aunque aún no haya «Detener» ni Markdown.

### Incremental Delivery

1. Base (Phases 1–2) → US1 (MVP) → US2 («Detener») → US3 (Markdown seguro): las tres P1 cierran los criterios de
   aceptación de la historia de Jira (burbujas + indicador, botón detener, Markdown seguro).
2. US4 y US5 (P2) → US6 (P3) → Polish.
3. Cada fase deja `npm run lint`, `npm test` y `npm run build` en verde.

### Notes

- No se modifican el backend, el layout de LAB-13 (salvo la entrada de navegación vía `data.nav`), `nginx.conf` ni el proxy.
- Si algún comportamiento del contrato de LAB-12 no coincide con la realidad del backend al probar (`T042`), corregir el contrato/cliente y anotarlo en `SPEC/research.md`; no cambiar el backend en esta historia.
- Commit sugerido por fase: `feat(chat): [LAB-14] <qué>` en ramas; el PR se squashea.
