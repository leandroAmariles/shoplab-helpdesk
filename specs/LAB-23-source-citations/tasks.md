---

description: "Task list for LAB-23 - Citas a la fuente en cada respuesta"
---

# Tasks: Citas a la fuente en cada respuesta

**Input**: Design documents from `/specs/LAB-23-source-citations/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`sources-event.md`), quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas antes o junto con la implementación. En cada historia
las pruebas van primero y deben fallar antes de implementar. Backend: Ollama simulado con WireMock (`OllamaStubs`),
PostgreSQL con pgvector real (`@PostgresTest`) y `DeterministicEmbeddingPort`. Frontend: Vitest (`ng test`). Ninguna
prueba automática necesita Ollama real. La tabla de vectores de [contracts/sources-event.md](contracts/sources-event.md) §3
es la fuente de los casos de la gramática en backend y frontend.

**Organization**: Tareas agrupadas por historia de usuario. US1 (backend: numerar fuentes, prompt y evento `sources`) y US2
(frontend: citas y lista) forman el MVP junto con US3 (abrir el fragmento): las tres son P1 y el criterio de la historia
las exige juntas. US4 es P2 y US5 es P3.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US5)
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Main**: `backend/src/main/java/com/shoplab/helpdesk/` (en las tareas, `main/`)
- **Tests**: `backend/src/test/java/com/shoplab/helpdesk/` (en las tareas, `test/`)
- **Frontend**: `frontend/src/app/` (en las tareas, `fe/`); las pruebas `*.spec.ts` van junto al archivo
- Java: líneas ≤ 121 caracteres, llaves en todos los bloques, identificadores en inglés, constantes en `@UtilityClass`
  de `domain/model/constants`, sin `block()` en el event loop, Spring AI solo en `infrastructure`, `chat` solo importa
  `retrieval.api`
- Frontend: texto de interfaz en español; todo HTML pasa por `MarkdownRenderer` (ADR-0014), nada más usa `[innerHTML]`
- Sin texto de preguntas, respuestas ni fragmentos en logs ni métricas (backend) ni en `console` (frontend)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base verde antes de tocar nada

- [X] T001 Ejecutar la línea base del backend: `cd backend && ./mvnw test -Dskip.srcclr=true --no-transfer-progress` debe
  pasar en la rama; anotar fallos previos si los hay
- [X] T002 Ejecutar la línea base del frontend: `cd frontend && npm test -- --watch=false` y `npm run lint` deben pasar; anotar
  fallos previos si los hay

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: título legible en la API de `retrieval`, tipos de dominio y gramática de citas que necesitan todas las historias

**CRITICAL**: ninguna historia puede empezar hasta terminar esta fase

- [X] T003 [P] Test: la búsqueda devuelve el título del documento. Ampliar
  `test/retrieval/EmbeddingFlowIntegrationTests.java` (el resultado de la búsqueda lleva `title` = `document.title`) y
  `test/retrieval/infrastructure/in/api/KnowledgeSearchApiAdapterTests.java` (`KnowledgeChunk.title` copia el del
  resultado)
- [X] T004 Añadir `title` a la búsqueda de `retrieval` (sin migración; la columna `document.title` ya existe):
  `main/retrieval/domain/model/SearchResult.java` (+ `title`), `main/retrieval/api/KnowledgeChunk.java`
  (`KnowledgeChunk(source, title, section, position, text, score)`),
  `main/retrieval/infrastructure/in/api/KnowledgeChunkMapper.java`, y en
  `main/retrieval/infrastructure/out/persistence/JdbcEmbeddingStoreAdapter.java` la consulta `SEARCH` (`d.title`) y su
  `RowMapper`. Ajustar los constructores de `test/retrieval/application/usecase/SearchChunksUseCaseTests.java`
- [X] T005 [P] Test: título en `chat`. Ampliar `test/chat/infrastructure/out/knowledge/KnowledgeSearchAdapterTests.java`:
  el título del chunk llega al fragmento; con título vacío se deriva del nombre del archivo (sin extensión, guiones por
  espacios, primera letra en mayúscula: `returns-01-return-policy.md` → `Returns 01 return policy`)
- [X] T006 Añadir `title` al fragmento de contexto: `main/chat/domain/model/ContextFragment.java`
  (`ContextFragment(source, title, section, position, text, score)`; `title` no vacío, mismo estilo de validación que el
  resto del registro) y `main/chat/infrastructure/out/knowledge/ContextFragmentMapper.java` (copia el título y aplica la
  derivación de T005 si llega vacío). Ajustar los constructores de `test/chat/application/usecase/ContextSelectorTests.java`,
  `test/chat/application/usecase/StreamChatUseCaseTests.java` y `test/chat/infrastructure/out/ollama/OllamaChatAdapterTests.java`
- [X] T007 [P] Tipos de dominio de las fuentes: `main/chat/domain/model/AnswerSource.java` (`number` entero ≥ 1, `title` no
  vacío, `section` que puede ser vacío, `text` no vacío; inmutable; se crea desde un `ContextFragment` y su posición + 1),
  `ChatEvent.SourcesEvent(List<AnswerSource> sources)` en `main/chat/domain/model/ChatEvent.java` y la constante
  `SOURCES_EVENT = "sources"` en `main/chat/domain/model/constants/ChatConstants.java`
- [X] T008 [P] Test de la gramática de citas del frontend en `fe/features/chat/citations.spec.ts`, con los casos de la tabla
  de contracts §3 (`[1]`, `[1][2]`, `[1, 2]`, `[1,2]`, `[1-3]`, `[3-1]`, `[1-30]`, `[1, 9]`, `[7]`, `[0]`, `[]`, `[abc]`, `[1a]`,
  `[2024]`, `(1)`, `【1】`) y de `citedNumbers(content, sources)` (válidos, únicos, en orden de primera aparición)
- [X] T009 Implementar `fe/features/chat/citations.ts`: expresión de referencia
  `\[\s*(\d{1,2}(?:\s*-\s*\d{1,2})?(?:\s*,\s*\d{1,2}(?:\s*-\s*\d{1,2})?)*)\s*\]`, expansión de listas y rangos (`a ≤ b`, como
  máximo 20 números), `parseCitations(text, sourceCount)` y `citedNumbers(content, sources)`; funciones puras sin Angular

**Checkpoint**: la búsqueda lleva el título, existen los tipos de dominio y la gramática; las historias pueden empezar

---

## Phase 3: User Story 1 - Respuestas con citas numeradas (Priority: P1) 🎯 MVP

**Goal**: el backend numera los fragmentos entregados al modelo, le pide citar `[n]` y emite el evento `sources` antes del
primer token, sin cambiar los eventos existentes.

**Independent Test**: con `curl` (quickstart §2), una pregunta cubierta por el corpus devuelve `event: sources` con las
fuentes numeradas desde 1, luego tokens con marcas `[n]` y `done`; sin contexto no hay evento `sources`.

### Tests for User Story 1 (write first; they MUST fail)

- [X] T010 [P] [US1] Ampliar `test/chat/application/usecase/StreamChatUseCaseTests.java`: con contexto el primer evento es
  `SourcesEvent` con `number` 1…k contiguos tras `ContextSelector` (en el orden entregado al modelo), seguido de los
  `TokenEvent` y un `DoneEvent` cuyo `fragments` cuenta solo tokens; sin contexto (modo `fixed-message` y modo `model`) no
  hay `SourcesEvent`; un fallo de la recuperación no emite `SourcesEvent` (escenarios 1.3, 1.5)
- [X] T011 [P] [US1] Crear `test/chat/infrastructure/in/rest/util/mapper/ChatEventMapperTests.java`: `SourcesEvent` →
  evento SSE `sources` con `data` `{"sources":[{"number","title","section","text"}]}`; `token`, `done` y `error` conservan su
  formato
- [X] T012 [P] [US1] Ampliar `test/chat/infrastructure/out/ollama/OllamaChatAdapterTests.java`: la cabecera de cada
  fragmento es `[n] Título › sección` (sin `›` cuando no hay sección) y el texto en la línea siguiente; el prompt de sistema
  por defecto contiene la regla de citas y la plantilla de contexto por defecto la línea «Cita los fragmentos usados con su
  número»; ampliar `test/chat/config/RagConfigurationBindingTests.java` para comprobar que las plantillas por defecto siguen
  renderizando (sin llaves distintas de `{query}` y `{context}`)
- [X] T013 [P] [US1] `test/chat/ChatStreamContractTests.java` queda sin cambios (su golden file sin contexto sigue idéntico: el
  contrato existente no cambia) y se crea `test/chat/ChatCitationsIntegrationTests.java` con el orden `sources`, `token`\*, `done`
  (`@PostgresTest`, documento indexado con `DeterministicEmbeddingPort`, Ollama con WireMock): la respuesta HTTP trae
  `sources` primero con el título legible y la petición a Ollama contiene la cabecera `[1] Título › sección`

### Implementation for User Story 1

- [X] T014 [US1] En `main/chat/application/usecase/StreamChatUseCase.java` construir los `AnswerSource` desde el contexto
  seleccionado y emitir `SourcesEvent` antes de los tokens solo si hay contexto, sin retrasar el primer token ni alterar la
  métrica de primer token ni `done.fragments`
- [X] T015 [P] [US1] En `main/chat/infrastructure/in/rest/dto/ChatEventPayload.java` añadir `Sources(List<Source>)` y
  `Source(number, title, section, text)`, y en `main/chat/infrastructure/in/rest/util/mapper/ChatEventMapper.java` el caso
  `SourcesEvent` con `ChatConstants.SOURCES_EVENT`
- [X] T016 [US1] En `main/chat/infrastructure/out/ollama/OllamaChatAdapter.java` formatear cada fragmento como
  `[n] Título › sección` (el número es la posición + 1 en la lista recibida; pasar título y número en los metadatos del
  `Document`) conservando la separación entre fragmentos
- [X] T017 [P] [US1] En `main/chat/domain/model/constants/ChatConstants.java` añadir la regla 5 al
  `DEFAULT_SYSTEM_PROMPT` (cada afirmación apoyada en el CONTEXTO termina con el número del fragmento entre corchetes, solo
  números presentes, sin inventar citas) y la línea de citas a `DEFAULT_CONTEXT_TEMPLATE`, sin llaves nuevas

**Checkpoint**: el backend emite las fuentes y pide citas; verificable con `curl`

---

## Phase 4: User Story 2 - Ver la fuente de cada cita en la pantalla de chat (Priority: P1)

**Goal**: la pantalla recibe las fuentes, pinta las marcas válidas como citas y muestra bajo la respuesta la lista de
fuentes citadas con título legible y sección.

**Independent Test**: en la pantalla de chat, una pregunta cubierta por el corpus muestra las citas `[n]` como botones y bajo
la respuesta «Fuentes» con «N. Título › sección», con números que coinciden con las citas.

### Tests for User Story 2 (write first; they MUST fail)

- [X] T018 [P] [US2] Ampliar `fe/features/chat/chat-stream.client.spec.ts`: el evento `sources` se convierte en
  `{ type: 'sources', sources }`; un evento `sources` mal formado (`number` no entero ≥ 1, `title`/`text` vacíos, `sources`
  no es un array) se ignora con un aviso que no incluye su contenido; el orden `sources`, `token`\*, `done` se conserva y
  `sources` NO completa el flujo (los tokens posteriores siguen llegando)
- [X] T019 [P] [US2] Ampliar `fe/features/chat/chat-store.spec.ts`: las fuentes quedan en el mensaje del asistente de esa
  respuesta (`ChatMessage.sources`); cada respuesta tiene las suyas; un reintento empieza con la lista vacía; las fuentes de
  una petición cancelada o antigua no se aplican (mismo control `requestId`); recibir `sources` no marca la respuesta como
  fallida ni cambia su estado `streaming`
- [X] T020 [P] [US2] Ampliar `core/markdown/markdown-renderer.spec.ts` (`fe/core/markdown/markdown-renderer.spec.ts`): una
  marca válida produce `<button type="button" class="citation" data-source="N" aria-label="Fuente N: Título">[N]</button>`;
  un número fuera de rango, `[0]`, `[abc]`, `(1)` y `[2024]` quedan como texto; `[1, 2]` y `[1-2]` se expanden a botones; no
  hay botones dentro de código en línea, bloques de código ni texto de enlaces; un título con HTML o comillas queda escapado
  en `aria-label`; `[1](https://…)` sigue siendo un enlace Markdown y no se convierte en botón; `streaming: true` oculta un
  final incompleto (`[`, `[1`, `[1,`)
- [X] T021 [P] [US2] Crear `fe/features/chat/source-list/source-list.spec.ts` y ampliar
  `fe/features/chat/message-bubble/message-bubble.spec.ts`: bajo una respuesta con citas aparece una región «Fuentes» con una
  entrada por fuente citada («N. Título › sección», sin la sección si es vacía) y sin repetidos; sin fuentes o en mensajes del
  usuario no hay región ni elementos vacíos (escenarios 2.1, 2.4, 2.5, 2.6)

### Implementation for User Story 2

- [X] T022 [US2] En `fe/features/chat/chat-message.ts` añadir `ChatSource` (`number`, `title`, `section`, `text`), el evento
  `{ type: 'sources'; sources: ChatSource[] }` en `ChatStreamEvent` y `sources: ChatSource[]` (por defecto `[]`) en
  `ChatMessage`
- [X] T023 [US2] En `fe/features/chat/chat-stream.client.ts` parsear el evento `sources` con la validación de T018 (tipo
  estricto, sin registrar contenido) y cambiar `pump()`: hoy completa el flujo con cualquier evento distinto de `token`;
  debe completar solo con `done` o `error`, de modo que `sources` (y `token`) sigan emitiéndose
- [X] T024 [US2] En `fe/features/chat/chat-store.ts` guardar las fuentes en el mensaje del asistente al recibir `sources`
  (si llegan dos, gana la última) respetando el control de `requestId`; `onEvent` hoy manda todo lo que no es `token` ni
  `done` a `fail('stream')`: añadir la rama `sources` y dejar `fail` solo para `error`
- [X] T025 [US2] En `fe/core/markdown/markdown-renderer.ts` añadir `render(markdown, options?)` con
  `options.sources` (número → título) y `options.streaming`; extensión inline de `marked` basada en `parseCitations` que
  emite los botones de T020 solo para números válidos; el *renderer* de enlaces desactiva las citas en su texto; ampliar la
  lista blanca de DOMPurify con la etiqueta `button` y los atributos `type`, `data-source` y `aria-label`, y el hook
  `afterSanitizeAttributes` para conservar `class="citation"` solo en un `button` y no añadir `target`/`rel` a ese botón;
  ocultar el final incompleto con `streaming`
- [X] T026 [P] [US2] Crear `fe/features/chat/source-list/source-list.{ts,html,scss}`: región con título «Fuentes», una
  entrada-botón por fuente citada («N. Título › sección») con nombre accesible «Fuente N: Título»; emite un `open` con la
  fuente (sin abrir nada todavía)
- [X] T027 [US2] En `fe/features/chat/message-bubble/message-bubble.{ts,html,scss}` pasar `sources` y `streaming` al
  renderizador, calcular las citadas con `citedNumbers` y mostrar `SourceList` bajo la respuesta del asistente; estilo del
  botón `.citation` (superíndice discreto, foco visible, contraste suficiente)

**Checkpoint**: la pantalla muestra citas y fuentes con el flujo real

---

## Phase 5: User Story 3 - Abrir el fragmento al hacer clic en una cita (Priority: P1)

**Goal**: activar una cita del texto o una entrada de la lista abre el fragmento con su documento, sección y texto, de forma
accesible y sin perder la conversación.

**Independent Test**: clic en `[1]` y en la fuente 1 abren el mismo fragmento; Escape lo cierra y el foco vuelve al
elemento que lo abrió; el borrador del campo de entrada no cambia.

### Tests for User Story 3 (write first; they MUST fail)

- [X] T028 [P] [US3] Crear `fe/features/chat/source-preview/source-preview.spec.ts`: muestra título, sección (oculta si es
  vacía) y el texto completo; el texto se renderiza con `MarkdownRenderer` sin citas, de modo que un `<script>`, un `onerror`
  o una imagen remota del fragmento no se ejecutan ni se cargan; el diálogo tiene rol `dialog` etiquetado con el título y un
  botón «Cerrar»
- [X] T029 [P] [US3] Ampliar `fe/features/chat/message-bubble/message-bubble.spec.ts` y `fe/features/chat/chat.spec.ts`: el
  clic y Enter/Espacio en una cita abren el fragmento correcto; activar la entrada de la lista abre el mismo; abrir otro
  fragmento deja un único diálogo; Escape, «Cerrar» y clic fuera cierran; el foco vuelve al elemento que abrió; el texto del
  borrador y la conversación siguen igual; con la respuesta aún en streaming se puede abrir una cita ya recibida

### Implementation for User Story 3

- [X] T030 [US3] Crear `fe/features/chat/source-preview/source-preview.{ts,html,scss}`: contenido del `MatDialog`
  (título, sección, texto saneado con desplazamiento para fragmentos largos, botón «Cerrar»), ancho casi completo y altura
  máxima con scroll en móvil (≤ 480 px)
- [X] T031 [US3] En `fe/features/chat/message-bubble/message-bubble.ts` y `.html` abrir el fragmento con `MatDialog`
  (`closeAll()` antes de `open`) desde la delegación de clic en `.markdown` (`closest('button[data-source]')`, buscando la
  fuente por número en `message().sources`) y desde el evento `open` de `SourceList`; ignorar números sin fuente

**Checkpoint**: MVP completo (US1 + US2 + US3): el cliente ve y abre las fuentes de cada respuesta

---

## Phase 6: User Story 4 - Citas coherentes y sin falsas fuentes (Priority: P2)

**Goal**: las fuentes listadas son las citadas, las marcas inválidas no se presentan como citas y una respuesta con contexto
sin citas muestra «Fuentes consultadas».

**Independent Test**: con un modelo simulado que escribe `[9]` (4 fuentes) y otro que no cita, la pantalla deja `[9]` como texto
y muestra «Fuentes consultadas» solo en el segundo caso.

### Tests for User Story 4 (write first; they MUST fail)

- [X] T032 [P] [US4] Ampliar `fe/features/chat/source-list/source-list.spec.ts` y
  `fe/features/chat/message-bubble/message-bubble.spec.ts`: citas 1 y 3 de cuatro fuentes listan solo 1 y 3; una cita repetida
  lista la fuente una vez y todas las marcas abren el mismo fragmento; con la respuesta completa o interrumpida, con fuentes y
  sin ninguna cita válida, aparece «Fuentes consultadas» con todas; durante el streaming sin citas todavía no aparece;
  `failed` conserva las ya citadas; `[7]` con cuatro fuentes y `[1, 9]` (1 válida, 9 como texto) no producen botones
  inválidos; `[opcional]` y `[2024]` quedan como texto

### Implementation for User Story 4

- [X] T033 [US4] En `fe/features/chat/source-list/source-list.{ts,html}` y `message-bubble.ts` añadir el modo «consultadas»:
  etiqueta «Fuentes consultadas» cuando el mensaje está `complete` o `stopped`, hay fuentes y `citedNumbers` está vacío;
  derivado en tiempo de ejecución, sin guardar estado nuevo en el almacén

**Checkpoint**: las fuentes mostradas son verificables y nunca se sugiere una atribución que el modelo no hizo

---

## Phase 7: User Story 5 - Observabilidad y límites de las citas (Priority: P3)

**Goal**: el servidor mide cuántas respuestas citan bien, mal o nada, sin retener ni registrar texto.

**Independent Test**: tres preguntas con modelo simulado (cita válida, cita fuera de rango, sin cita) dejan
`shoplab_chat_citations_total` con `valid`, `invalid` y `none` en 1, y los registros no contienen texto.

### Tests for User Story 5 (write first; they MUST fail)

- [X] T034 [P] [US5] Crear `test/chat/application/usecase/CitationTrackerTests.java` con los vectores de contracts §3 para
  `k = 4`: `NONE` (0 marcas), `VALID` (≥ 1 válida y ninguna inválida), `INVALID` (alguna con número > `k` o rango fuera de
  rango); `[1, 9]` es `INVALID`; `[1-2]` y `[1, 2]` cuentan como dos válidas; una marca partida entre tokens (`"[" + "1]"`,
  `"[1" + ", 2]"`) se reconoce al completarse; el texto no se expone
- [X] T035 [P] [US5] Ampliar `test/chat/infrastructure/out/metrics/MicrometerChatMetricsTests.java`: `recordCitations`
  incrementa `shoplab.chat.citations` con `outcome` = `valid` | `invalid` | `none`, y no crea otras etiquetas
- [X] T036 [P] [US5] Ampliar `test/chat/application/usecase/StreamChatUseCaseTests.java`: se registra exactamente un
  resultado por respuesta completada con contexto; no se registra en respuestas sin contexto, canceladas ni con error;
  `INVALID` deja un `WARN` con `requestId` y recuentos y sin texto (comprobar el mensaje capturado); ampliar
  `test/chat/ChatCitationsIntegrationTests.java` para comprobar el contador en `/actuator/prometheus` tras una respuesta con
  cita válida y otra sin citas

### Implementation for User Story 5

- [X] T037 [US5] Crear `main/chat/domain/model/enums/CitationOutcome.java` (`VALID`, `INVALID`, `NONE`, con `tagValue()` en
  minúsculas como `ContextOutcome`) y `main/chat/application/usecase/CitationTracker.java` (puro, sin Spring AI:
  `append(String)`, `outcome(int sourceCount)`, misma gramática que el frontend; acumula solo para extraer marcas)
- [X] T038 [P] [US5] Añadir `recordCitations(CitationOutcome)` a `main/chat/application/port/out/ChatMetricsPort.java` y su
  implementación en `main/chat/infrastructure/out/metrics/MicrometerChatMetrics.java` (contador `shoplab.chat.citations`,
  etiqueta `outcome`; constante `CITATIONS_METRIC` en `ChatConstants`)
- [X] T039 [US5] En `main/chat/application/usecase/StreamChatUseCase.java` alimentar un `CitationTracker` con los tokens de las
  respuestas con contexto y, al completarse, registrar el resultado una vez; `INVALID` escribe `WARN` con `requestId`,
  `valid=<n>` e `invalid=<n>`; sin retener tokens ni retrasar el flujo

**Checkpoint**: todas las historias funcionan y son medibles

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: decisión documentada, documentación, verificación completa

- [X] T040 [P] Crear `docs/adr/0019-citas-a-la-fuente-en-el-chat.md` con la plantilla `docs/adr/0000-plantilla.md` (evento
  `sources` aditivo, título desde `retrieval.api`, validación en el cliente y medición en el servidor, `MatDialog`; ver
  research D-1, D-4, D-5, D-6) y añadir la fila al índice en `docs/adr/README.md`
- [X] T041 [P] Actualizar `docs/chat-streaming.md` (fila `sources` en la tabla de eventos, regla de citas del prompt y aviso
  para quien defina su propio prompt, métrica `shoplab_chat_citations_total`, y sustituir «Las respuestas todavía no citan las
  fuentes» en «Alcance actual») y `docs/modulos.md` (`KnowledgeChunk` lleva `title`)
- [X] T042 Generar `specs/LAB-23-source-citations/requests.http` (regla `http test requests`): un caso por escenario de
  US1 (con contexto trae `sources` antes de los tokens; sin contexto no), la validación de mensaje vacío que no cambia, el
  borde de `sources` para una pregunta ajena al corpus; `baseUrl` = `http://localhost:8080`; listar en un comentario superior
  lo no alcanzable por HTTP («Not covered here»: citas, lista y diálogo de la interfaz, métricas)
- [X] T043 Backend completo: `cd backend && ./mvnw verify -Dskip.srcclr=true --no-transfer-progress` (Checkstyle, PMD,
  Modulith/ArchUnit/BlockHound, JaCoCo ≥ 80 %); corregir lo que falle sin tocar contratos de LAB-12 ni LAB-22
- [X] T044 Frontend completo: `cd frontend && npm run lint && npm test -- --watch=false && npm run build` sin errores ni
  avisos nuevos
- [ ] T045 (PARCIAL: hecho lo comprobable por API, ver validation.md; falta la revisión manual de la interfaz y SC-001 no se cumple con el prompt por defecto) Verificación manual con Ollama real según [quickstart.md](quickstart.md) §3, §5 y §6: ≥ 10 preguntas del corpus
  (SC-001 ≥ 90 % con cita válida, SC-002 ≥ 80 % de citas con el dato), revisión de teclado, lector de pantalla y móvil
  (SC-007), y registros sin texto (SC-008); anotar resultados y capturas en
  `specs/LAB-23-source-citations/validation.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias.
- **Foundational (Phase 2)**: depende de Setup; **bloquea** todas las historias.
- **US1 (Phase 3)**: depende de T004–T007. Backend puro.
- **US2 (Phase 4)**: depende de T008–T009; para probar con el flujo real necesita US1, pero sus pruebas usan eventos simulados.
- **US3 (Phase 5)**: depende de US2 (las citas, la lista y el mensaje con fuentes).
- **US4 (Phase 6)**: depende de US2 y US3 (lista y apertura).
- **US5 (Phase 7)**: depende de US1 (T014) y de T007; independiente del frontend.
- **Polish (Phase 8)**: depende de las historias que se entreguen; T045 necesita US1–US3.

### Orden dentro de cada historia

Pruebas (que fallan) → tipos/dominio → casos de uso → adaptadores/UI. Los archivos compartidos
(`StreamChatUseCase.java`, `message-bubble.ts`, `source-list.*`, `ChatConstants.java`) se tocan en orden de tarea.

### Oportunidades de paralelismo

- Foundational: T003, T005, T007 y T008 en paralelo (archivos distintos); T004 tras T003, T006 tras T005, T009 tras T008.
- US1: T010–T013 en paralelo; T015 y T017 en paralelo con T014/T016.
- US2: T018–T021 en paralelo; T026 en paralelo con T022–T025.
- US1 (backend) y US2 (frontend) pueden avanzar en paralelo por personas distintas tras la fase 2.
- US5 puede hacerse en paralelo con US2–US4 (backend frente a frontend).

---

## Implementation Strategy

### MVP (US1 + US2 + US3)

1. Setup y Foundational.
2. US1: `curl` muestra `sources` y tokens con `[n]`.
3. US2 + US3: la pantalla pinta las citas, lista las fuentes y abre el fragmento. **Parar y validar** con el quickstart §2 y
   §3; es lo que pide el criterio de la historia («la respuesta lleva citas», «Angular muestra la fuente y abre el
   fragmento»).

### Entrega incremental

4. US4: coherencia («Fuentes consultadas», marcas inválidas).
5. US5: métrica de calidad de citas para la evaluación de F2.
6. Polish: ADR-0019, documentación, `requests.http`, verificación completa y validación manual.

## Notes

- Commits: Conventional Commits en la rama (`feat(chat): [LAB-23] …`); el título de la PR sigue
  `type(scope): [LAB-23] descripción`. Un commit = un cambio lógico.
- Antes de cada commit: pruebas del módulo afectado, Checkstyle y revisión de `git status` y `git diff --staged`.
- No editar `CHANGELOG.md`, `.release-please-manifest.json` ni la versión a mano.
- La historia no se considera lista para producción hasta que la evaluación de F2 mida la fidelidad de las citas
  (Constitución V); aquí solo se garantiza que existen, se validan y se miden.
