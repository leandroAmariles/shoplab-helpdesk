# Tasks: Pantalla de evolución de métricas (admin-eval)

**Input**: documentos de diseño en `specs/LAB-32-admin-eval-screen/`
**Prerequisitos**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/eval-history-api.md](contracts/eval-history-api.md), [contracts/admin-eval-screen.md](contracts/admin-eval-screen.md),
[quickstart.md](quickstart.md)

**Pruebas**: SÍ. La Constitución (IV) las exige con cobertura ≥ 80 % del backend, y FR-025 pide poder probar la lógica de
lectura y presentación sin red, sin modelo y sin base real. Cada pieza con lógica lleva su prueba **antes** (tarea `[P]` o
inmediatamente anterior). Ninguna prueba usa Ollama, Langfuse ni red: PostgreSQL real solo vía `@PostgresTest`
(Testcontainers) y `HttpTestingController` en el frontend. **JaCoCo excluye** `dto`, `config`, `model`, `util/mapper` y
`exception`: la lógica va en `application/usecase`, en `domain` (fuera de `model`) o en `infrastructure`, nunca en esos
paquetes (por eso `RunComparator` y `TraceUrlBuilder` no están en `model` ni en `config`).

**Alcance**: **solo lectura**. Ninguna tarea toca el guardado de LAB-31 (`POST /api/eval/runs`), el esquema `eval` (no hay
migración nueva), LAB-30 (CI, línea base, umbrales de bloqueo) ni LAB-33 (feedback). La entrada del token de guardado no se
usa en la lectura (FR-020, FR-024).

**Organización**: por historia de usuario. En cada historia, backend (`backend/`) y frontend (`frontend/`) avanzan en paralelo
una vez terminada la Fase 2; el frontend de cada historia puede probarse con `HttpTestingController` sin esperar al backend.

## Formato: `[ID] [P?] [Historia] Descripción con ruta`

- **[P]**: se puede hacer en paralelo (archivos distintos, sin dependencias pendientes)
- **[US#]**: historia de `spec.md` a la que pertenece
- Rutas relativas a la raíz del repositorio. `BASE` = `backend/src/main/java/com/shoplab/helpdesk`, `TEST` =
  `backend/src/test/java/com/shoplab/helpdesk`, `FE` = `frontend/src/app/features/eval`, `SPEC` =
  `specs/LAB-32-admin-eval-screen`
- Identificadores en inglés; documentación y textos de la pantalla en español; líneas ≤ 121 caracteres; llaves en todos los
  bloques; sin PMD ni Checkstyle; en el frontend ESLint y Prettier limpios

---

## Phase 1: Setup (comprobaciones previas)

**Propósito**: fijar lo que el plan dejó por comprobar y partir de una línea base verde.

- [X] T001 Comprobar el número libre del ADR: listar `docs/adr/` en `main` y en las ramas remotas (`git branch -a`,
  `git log --all -- docs/adr`), confirmar si el 0026 existe en algún sitio y fijar el número (previsto **0029**). Crear
  `SPEC/validation.md` con una sección por escenario de `quickstart.md` (V1–V24, cada una con «Resultado:» y «Evidencia:» vacíos)
  y la línea «ADR: <número>» (la usa T062)
- [X] T002 Comprobar la línea base antes de cambiar nada: `cd backend && ./mvnw -q test -Dtest='Eval*Tests,*Eval*Tests'` y
  `cd frontend && npm ci && npm test -- --watch=false` terminan en verde en la rama `feature/LAB-32-admin-eval-screen`. Anotar en
  `SPEC/validation.md` cualquier fallo preexistente (no se corrige aquí)

---

## Phase 2: Foundational (bloquea todas las historias)

**Propósito**: el error nuevo, los modelos y puertos de lectura, la validación de las peticiones, el generador del enlace a la
traza y la ruta de la pantalla. Ninguna historia empieza antes.

**⚠️ CRÍTICO**: terminar esta fase completa.

- [X] T003 [P] Error nuevo `SLHD_EVAL_00006`: **primero** extender `TEST/shared/infrastructure/in/rest/handler/GlobalExceptionHandlerTests.java`
  (o la prueba que ya cubre los códigos `SLHD_EVAL_*`) con un caso que exige `422` y el cuerpo estándar `code`/`message` para
  `EvalRunsNotComparableException`; después añadir `EVAL_RUNS_NOT_COMPARABLE("SLHD_EVAL_00006",
  HttpStatus.UNPROCESSABLE_CONTENT, "The evaluation runs are not of the same kind")` a `BASE/shared/exception/ResponseCodeEnum.java`
  y crear `BASE/eval/domain/exception/EvalRunsNotComparableException.java` (como las otras cuatro excepciones del módulo, sin
  `PublicDetail`: el mensaje de la pantalla sale del `code`). No cambiar ninguno de los códigos `SLHD_EVAL_00001`–`00005`
- [X] T004 [P] Modelos de lectura en `BASE/eval/domain/model/` (records sin lógica, campos de
  [data-model.md](data-model.md)): `MetricStat(Double mean, int n)` («`mean` nulo cuando ningún caso tiene valor; `n` = casos con
  valor que entraron en la media; `0` ⇔ `mean` nulo»), `OriginCount(String origin, long count)`, `RunListItem` (`runId`, `kind`,
  `startedAt`, `finishedAt`, `origin`, `commitSha` nulo, `assistantModel` nulo, `prompts` como `List<PromptVersion>` ya existente,
  `casesMeasured`, `metrics` como `Map<String, MetricStat>`), `RunListing` (`kind`, `runs`, `total`, `totalAllOrigins`, `origins`),
  `ScopeSummary(String scope, int cases, Map<String, MetricStat> metrics)` («`scope` = `all` o una categoría»),
  `StoredResult` (`caseId`, `revision` = «SHA-256 (64 hex)», `category`, `question`, `referenceAnswer`, `expectedDocuments`,
  `caseKind` `CaseKind` ya existente, `scores` `Map<String, Double>` con nulos permitidos, `retrievedDocuments`, `abstained`
  Boolean nulo, `traceId` nulo) y `StoredRun` (`EvalRun` sin resultados + `recordedAt` + `summary` + `results`; reutilizar los
  records `JudgeInfo`, `EvaluatorInfo`, `RetrievalConfig` de LAB-31). **Ningún campo con el texto de una respuesta del asistente
  ni de un fragmento** (FR-016)
- [X] T005 [P] Propiedad del enlace a la traza: crear `BASE/eval/config/EvalHistoryProperties.java` (record
  `@ConfigurationProperties("shoplab.eval.history")` con `String traceUrlTemplate`, recortado y `""` si es nulo, como
  `EvalProperties`), registrarlo donde se registra `EvalProperties` (`BASE/eval/config/EvalConfig.java`) y añadir a
  `backend/src/main/resources/application.yml`, bajo `shoplab.eval`, `history.trace-url-template:
  ${SHOPLAB_EVAL_HISTORY_TRACE_URL_TEMPLATE:http://localhost:3001/trace/{traceId}}` con un comentario que diga qué hace y que
  vacío = sin enlace
- [X] T006 [P] Prueba de `TraceUrlBuilder` en `TEST/eval/application/usecase/TraceUrlBuilderTests.java`: con la plantilla por
  defecto y el id `4bf92f3577b34da6a3ce929d0e0e4736` devuelve la URL esperada; plantilla vacía → `Optional.empty()`; id nulo →
  vacío; id fuera de `^[A-Za-z0-9_-]{1,64}$` → vacío (nunca se sustituye); plantilla inválida (sin `{traceId}`, con dos, con
  esquema distinto de `http`/`https`, con espacios) → `IllegalStateException` al construir el builder, con un mensaje que nombra
  la propiedad y **no** el valor completo
- [X] T007 Implementar `BASE/eval/application/usecase/TraceUrlBuilder.java` (`@Component`, recibe `EvalHistoryProperties`, valida
  la plantilla en el constructor y expone `Optional<String> urlFor(String traceId)`) hasta que T006 pase
- [X] T008 [P] Puerto de salida de lectura `BASE/eval/application/port/out/EvalHistoryPort.java` con
  `Mono<RunListing> list(RunKind kind, String origin, int limit)` y `Mono<Optional<StoredRun>> find(String runId)`, y su Javadoc
  («solo lectura; las ejecuciones completas; el camino de escritura es `EvalRunStorePort`»). No tocar `EvalRunStorePort`
- [X] T009 [P] Prueba del validador de peticiones en `TEST/eval/application/usecase/EvalHistoryQueryValidatorTests.java`, con las
  reglas de «Validaciones de las peticiones de lectura» de `data-model.md`: `kind` presente y `QUALITY` o `RETRIEVAL`; `limit`
  «entre 1 y 500 (por defecto 100)»; `origin` con el formato de origen de LAB-31 (`EvalConstants.ORIGIN`); `runId`, `base` y
  `target` con `^[A-Za-z0-9._-]{1,32}$`; `base ≠ target`; `metric` con `^[a-z][a-z0-9_]{0,63}$`; `drop` «numérico entre 0 y 1»
  (por defecto 0,05, y 0 y 1 son válidos, `NaN` y negativos no); todos los fallos son `EvalRunInvalidException` con un `detail`
  que nombra el parámetro y **no** repite el valor recibido
- [X] T010 Implementar `BASE/eval/application/usecase/EvalHistoryQueryValidator.java` (`@Component`) con los comandos de entrada
  `ListEvalRunsCommand`, `CompareEvalRunsCommand` en `BASE/eval/application/port/in/` (records) hasta que T009 pase. Añadir a
  `BASE/eval/domain/model/constants/EvalConstants.java` los valores nuevos (`HISTORY_LIMIT_DEFAULT = 100`, `HISTORY_LIMIT_MAX =
  500`, `COMPARE_DROP_DEFAULT = 0.05`, patrón de métrica `^[a-z][a-z0-9_]{0,63}$`)
- [X] T011 [P] Tipos de la API en `FE/eval-history.ts` (camelCase; el cliente traduce `snake_case`): `RunKind`, `MetricStat`
  (`mean: number | null`, `n`), `RunPrompt`, `RunListItem`, `RunListing`, `OriginCount`, `RunDetail`, `ScopeSummary`, `StoredResult`,
  `ComparisonStatus` (`WORSE`, `BETTER`, `SAME`, `NO_VALUE`, `NOT_COMPARABLE`, `ONLY_BASE`, `ONLY_TARGET`), `RunComparison`,
  `ParameterDifference` y `CaseComparison`; más las constantes de la pantalla (`DEFAULT_KIND = 'QUALITY'`, `DEFAULT_LIMIT = 100`,
  `LIMIT_STEPS = [100, 250, 500]`, `MAX_SELECTED_METRICS = 5`, `DEFAULT_THRESHOLD = 0.5`, `THRESHOLD_STEP = 0.05`,
  `DEFAULT_DROP = 0.05`) y las etiquetas en español de cada métrica (`faithfulness` «Fidelidad», `answer_relevancy` «Relevancia de la
  respuesta», `context_precision` «Precisión del contexto», `reciprocal_rank` «MRR», `hit_at_<k>` «Hit rate@k», `recall_at_<k>`
  «Recall@k»)
- [X] T012 Ruta y esqueleto de la pantalla: añadir a `frontend/src/app/app.routes.ts`, entre `documentos` y `**`, la ruta
  `admin-eval` (`title: 'Evaluación'`, `data: { nav: { label: 'Evaluación', icon: 'monitoring' } }`, `loadComponent` perezoso de
  `./features/eval/admin-eval`); crear `FE/admin-eval.ts`, `.html` y `.scss` con la cabecera del contrato (`<h1>Evaluación</h1>`,
  una frase de introducción y el aviso fijo «El dataset golden es un borrador hasta su revisión humana; estos valores no son una
  certificación de calidad.»). **Primero** extender `frontend/src/app/app.routes.spec.ts` y `frontend/src/app/layout/shell/` (su
  spec) para exigir que la ruta existe, es perezosa y aparece en la navegación entre las entradas existentes (FR-001, HU4·1)

**Checkpoint**: error, modelos, validación, enlace a traza, puerto y ruta listos; las historias pueden empezar.

---

## Phase 3: User Story 1 — Ver la evolución de las métricas ejecución a ejecución (Priority: P1) 🎯 MVP

**Objetivo**: una gráfica con un punto por ejecución, orden cronológico, tipo y métricas elegibles, filtro de origen y huecos
honestos. Es el primer criterio de aceptación de Jira.

**Prueba independiente**: con al menos tres ejecuciones de un tipo guardadas, abrir `/admin-eval` y comprobar que la gráfica
tiene un punto por ejecución en orden cronológico, que cada valor coincide con `eval/queries/03-run-summary.sql` y que cambiar de
métrica, de tipo o de origen cambia la gráfica sin mezclar tipos (V1–V8).

### Pruebas de US1 (primero; deben fallar antes de implementar)

- [X] T013 [P] [US1] `TEST/eval/application/usecase/ListEvalRunsUseCaseTests.java` (Mockito, sin Spring ni base): valida con
  `EvalHistoryQueryValidator` y llama al puerto con el `limit` por defecto 100; un `kind` inválido no llega al puerto; **no
  consulta ningún token** (con la persistencia de guardado apagada la lectura funciona, FR-020); el error del puerto se propaga
- [X] T014 [P] [US1] `TEST/eval/infrastructure/out/persistence/JdbcEvalHistoryAdapterListTests.java` (`@PostgresTest`, filas
  `hist-*` y limpieza como `EvalHistoryQueriesTests`; datos con `EvalRunFixtures`): la media y el `n` de cada métrica de cada
  ejecución son los de `eval/queries/03-run-summary.sql` (conjunto completo); una métrica con todos los valores `null` da `mean`
  nulo y `n = 0`, **no** 0; una ejecución de recuperación con otros k solo trae sus métricas (la ausente no figura); orden
  cronológico **ascendente** con desempate por `run_id` y `limit` = las N **más recientes**; el filtro de `origin` cambia `total`
  pero no `totalAllOrigins` ni `origins`; `kind` separa calidad de recuperación; base vacía → lista vacía y totales 0; un caso
  `unanswered` (solo fidelidad) no distorsiona `n` de las otras métricas; humo con 100 ejecuciones sintéticas (< 3 s, SC-006)
- [X] T015 [P] [US1] `TEST/eval/infrastructure/in/rest/EvalHistoryControllerTests.java` (`WebTestClient`, parte de la lista): `GET
  /api/eval/history/runs?kind=QUALITY` responde 200 **sin** cabecera de token (y con el token del módulo sin configurar); el JSON
  tiene exactamente los campos de [contracts/eval-history-api.md](contracts/eval-history-api.md) en `snake_case` y una métrica sin
  valor sale como `"mean": null, "n": 0`; `kind` ausente o desconocido y `limit=0`/`501` → 422 `SLHD_EVAL_00002` con `detail`;
  base caída → 503 `SLHD_EVAL_00005`; el cuerpo no contiene el token

### Implementación de US1 (backend)

- [X] T016 [US1] Puerto de entrada `BASE/eval/application/port/in/ListEvalRunsServicePort.java` y caso de uso
  `BASE/eval/application/usecase/ListEvalRunsUseCase.java` (`@Service`; valida y delega en `EvalHistoryPort`; **no** usa
  `EvalAccessGuard`) hasta que T013 pase
- [X] T017 [US1] `BASE/eval/infrastructure/out/persistence/JdbcEvalHistoryAdapter.java` (`@Component`, `@DependsOn("moduleSchemaMigrator")`,
  `JdbcTemplate` + `BlockingCalls`, errores traducidos a `EvalStoreUnavailableException` como `JdbcEvalRunStoreAdapter`),
  método `list`: (1) una consulta para las `limit` ejecuciones más recientes del tipo y origen, (2) una para `total`,
  `total_all_origins` y las facetas de origen del tipo, (3) **una** consulta agregada sobre `eval.eval_result` con
  `jsonb_each(scores)` para todas las ejecuciones elegidas (`WHERE run_ref = ANY(?)`) con `avg` y `count` filtrados por
  `jsonb_typeof(value) = 'number'`, y el resultado reordenado de forma cronológica ascendente. Solo `SELECT`; ningún texto de
  pregunta ni respuesta en los mensajes de error. Hasta que T014 pase
- [X] T018 [US1] DTOs en `BASE/eval/infrastructure/in/rest/dto/` (`RunListResponse`, `RunListItemResponse`, `MetricStatResponse`,
  `OriginCountResponse`, `RunPromptResponse`, con `@JsonProperty` en `snake_case`), `BASE/eval/infrastructure/in/rest/util/mapper/EvalHistoryMapper.java`
  (`toResponse(RunListing)`) y `BASE/eval/infrastructure/in/rest/EvalHistoryController.java` con `@RequestMapping("/api/eval/history")`
  y `GET /runs` (`kind`, `origin`, `limit` como `@RequestParam`; el controlador solo arma el comando y llama al puerto) hasta que
  T015 pase

### Pruebas de US1 (frontend, primero)

- [X] T019 [P] [US1] `FE/metric-series.spec.ts`: `availableMetrics(runs)` es la **unión** de las métricas de las ejecuciones
  mostradas, en orden estable (calidad: `faithfulness`, `answer_relevancy`, `context_precision`; recuperación: `reciprocal_rank`,
  luego `hit_at_<k>` y `recall_at_<k>` por k ascendente); `buildSeries(runs, metrics)` da un punto por ejecución con
  `value: number | null` y `n`, y una métrica que la ejecución no tiene es `null` (**nunca 0**); `defaultMetrics(kind, runs)` =
  `['faithfulness']` en calidad y `['reciprocal_rank', 'hit_at_<mayor k>']` en recuperación; sin ejecuciones, listas vacías;
  `describeSeries` genera «12 ejecuciones, de 0,6900 a 0,7800» y ignora los huecos
- [X] T020 [US1] Implementar `FE/metric-series.ts` (funciones puras, sin Angular) hasta que T019 pase
- [X] T021 [P] [US1] `FE/chart-scale.spec.ts`: escala X por posición (ejecuciones equiespaciadas, una sola ejecución centrada,
  cero ejecuciones sin dividir por cero), escala Y de 0 a 1 con marcas 0, 0,25, 0,5, 0,75 y 1, `segments(points)` parte la serie
  en tramos de puntos consecutivos con valor (un `null` interrumpe y un punto aislado es un tramo de uno), valores fuera de 0–1
  se limitan a los bordes
- [X] T022 [US1] Implementar `FE/chart-scale.ts` (puro) hasta que T021 pase
- [X] T023 [P] [US1] `FE/eval-api.client.spec.ts` y `FE/eval-api.client.ts`: `EVAL_HISTORY_URL = '/api/eval/history'`,
  `listRuns(kind, origin?, limit?)` con `HttpParams`, contexto `HANDLES_OWN_ERRORS` (la pantalla muestra sus errores, como
  `documents-api.client.ts`) y traducción de `snake_case` a camelCase (`run_id` → `runId`, `started_at` → `startedAt`,
  `total_all_origins` → `totalAllOrigins`…); un `mean: null` se conserva como `null`. **Primero** el spec con
  `HttpTestingController`
- [X] T024 [P] [US1] `FE/eval-store.spec.ts` (parte del listado): estado inicial (`kind` calidad, métricas por defecto, límite
  100, `loaded = false`); `loadListing()` carga y deja `loaded = true`; **cambiar de tipo** vacía las series, pide la lista del
  nuevo tipo y nunca mezcla ejecuciones; elegir métricas respeta el tope de `MAX_SELECTED_METRICS` (5) y no deja la lista vacía;
  filtrar por origen expone `hiddenByOrigin = totalAllOrigins − total`; `shown < total` expone `notShownByRange` y «ver más»
  sube el límite por `LIMIT_STEPS`; si la recarga falla, `listing` **se conserva** y `loadError` se rellena; **no hay sondeo**
  (avanzar el tiempo con temporizadores falsos no lanza peticiones)
- [X] T025 [US1] Implementar `FE/eval-store.ts` (`@Injectable()` con `signal`/`computed`, provisto por la pantalla; destruye
  suscripciones con `DestroyRef`) hasta que T024 pase. Solo la parte del listado: tipo, métricas elegidas, origen, límite,
  `listing`, `series` derivada con `buildSeries`, `loading`, `loaded`, `loadError`, `reload()`
- [X] T026 [P] [US1] `FE/metric-chart/metric-chart.spec.ts`: un `svg` con `role="img"`, título y descripción
  (`describeSeries`); un marcador por ejecución y serie; **un hueco no dibuja ninguna línea ni ningún punto en 0** (SC-007): no
  existe `circle`/`path` en y = 0 para un valor nulo, y sí un símbolo de hueco en el eje; una ejecución aislada entre dos huecos
  es solo marcador; cada serie usa un marcador y un trazo distintos además del color; cada punto es enfocable
  (`tabindex="0"`) con `aria-label` «<ejecución>, <fecha>, <valor con 4 decimales o sin valor>, <n> casos»; el foco o el ratón
  muestra el panel con id, fecha, valor y «calculado con N casos»; con una sola ejecución muestra «Hace falta más de una
  ejecución para ver una evolución»; el evento `runSelected` se emite al activar un punto
- [X] T027 [US1] Componente `FE/metric-chart/metric-chart.ts`, `.html` y `.scss` (standalone, `ChangeDetectionStrategy.OnPush`,
  SVG en línea con `viewBox` y escala por `chart-scale.ts`; colores de los tokens del tema Material, nunca hexadecimales fijos;
  marcadores círculo, cuadrado, rombo, triángulo y cruz; leyenda con los mismos símbolos) hasta que T026 pase
- [X] T028 [P] [US1] `FE/run-list/run-list.spec.ts` y `FE/run-list/run-list.ts`/`.html`/`.scss`: tabla que es **a la vez la
  lista y la alternativa textual de la gráfica** (id, fecha, origen, modelo del asistente o «desconocido», una columna por
  métrica elegida con «sin valor» donde `mean` es nulo y el valor con 4 decimales y `n`), contenedor desplazable con
  `role="region"` y nombre accesible, botón «Ver detalle» por fila que emite `runSelected`; la fila elegida se marca con
  `aria-current` y con texto, no solo con color. **Primero** el spec
- [X] T029 [US1] Integrar en `FE/admin-eval.ts`/`.html`/`.scss`: proveer `EvalStore`, selector de tipo (`mat-button-toggle-group`:
  «Calidad de respuesta» / «Recuperación»), selector múltiple de métricas, filtro de origen (con «Mostrando N de M · K ocultas por
  el filtro de origen») y «Ver más», botón «Actualizar», la gráfica y la lista debajo; leer y escribir `?tipo=` en la dirección
  (valor inválido → `QUALITY`, sin error). **Primero** ampliar `FE/admin-eval.spec.ts` (carga inicial, cambio de tipo, filtro de
  origen, «ver más», `?tipo=` válido e inválido)

**Checkpoint**: US1 completa y demostrable por sí sola (V1–V8, SC-001, SC-003, SC-006, SC-007).

---

## Phase 4: User Story 2 — Detalle de una ejecución y de sus casos que fallan (Priority: P1)

**Objetivo**: al elegir una ejecución, ver con qué se midió, su resumen y los casos que fallan con un umbral que elige quien mira
(calidad) o un k (recuperación). Es el segundo criterio de aceptación de Jira.

**Prueba independiente**: elegir una ejecución con casos de varias categorías, localizar tres casos que fallan y comprobar contra
el CSV de esa ejecución (o la consulta del historial) que valores, documentos esperados y recuperados coinciden, incluidos un caso
con una métrica vacía y un caso sin respuesta (V9–V14).

### Pruebas de US2 (primero)

- [X] T030 [P] [US2] `TEST/eval/application/usecase/GetEvalRunUseCaseTests.java` (Mockito): valida el `runId`
  (`^[A-Za-z0-9._-]{1,32}$`, 422 con `detail`); una ejecución ausente es `EvalRunNotFoundException` (404); **no consulta token**
- [X] T031 [P] [US2] `TEST/eval/infrastructure/out/persistence/JdbcEvalHistoryAdapterFindTests.java` (`@PostgresTest`):
  los parámetros salen completos (prompts con versión numérica y `pinned`, `judge` y `evaluator` solo en calidad,
  `retrieval_config` con `depth` y `ks` solo en recuperación, `commit_sha` y `assistant_model` nulos cuando se guardaron nulos);
  `summary` trae `all` y una entrada por categoría con casos, y sus medias y `n` coinciden con `03-run-summary.sql`; los
  resultados salen ordenados por `case_id` con el contenido de la **revisión que se midió** (una ejecución anterior sigue viendo la
  pregunta antigua de un caso reescrito después); los `null` de `scores` se conservan, los documentos recuperados conservan
  orden y repeticiones, el `abstained` y el `trace_id` salen tal cual; una ejecución inexistente da `Optional.empty()`
- [X] T032 [P] [US2] Ampliar `TEST/eval/infrastructure/in/rest/EvalHistoryControllerTests.java` (detalle): `GET
  /api/eval/history/runs/{runId}` 200 con los campos exactos del contrato, `trace_url` presente solo con plantilla configurada y
  `trace_id` presente (ausente en recuperación y con plantilla vacía), 404 `SLHD_EVAL_00004`, 422 con `runId` inválido, 503, y el
  cuerpo no contiene el token
- [X] T033 [P] [US2] `TEST/eval/EvalHistoryNoTextTests.java` (`@SpringBootTest` + `@PostgresTest`, como `EvalNoTextStoredTests`):
  guarda una ejecución real y recorre el JSON de **los tres endpoints** comprobando que el conjunto de nombres de campo de cada
  objeto es **exactamente** el del contrato (lista cerrada) y que ningún nombre sugiere texto de respuesta o de fragmento
  (`answer`, `response`, `text`, `chunk`, `fragment`, `content` salvo los campos del contrato); falla si alguien añade un campo sin
  actualizar la prueba (FR-016, SC-008)

### Implementación de US2 (backend)

- [X] T034 [US2] `BASE/eval/application/port/in/GetEvalRunServicePort.java` y `BASE/eval/application/usecase/GetEvalRunUseCase.java`
  hasta que T030 pase
- [X] T035 [US2] Método `find` de `BASE/eval/infrastructure/out/persistence/JdbcEvalHistoryAdapter.java`: cabecera y parámetros de
  `eval_run` (los `jsonb` a `PromptVersion`, `JudgeInfo`, `EvaluatorInfo`, `RetrievalConfig` con `ks` y `depth`), resumen con **una**
  consulta agregada por ámbito (`all` y `GROUP BY c.category`) con las mismas reglas del vacío que `list`, y resultados con
  `JOIN eval.eval_case` por `case_ref` ordenados por `case_id`; el mapeo reutiliza el de `list` para `MetricStat`. Hasta que T031
  pase
- [X] T036 [US2] DTOs (`RunDetailResponse`, `RunHeaderResponse`, `JudgeResponse`, `EvaluatorResponse`, `RetrievalConfigResponse`,
  `ScopeSummaryResponse`, `StoredResultResponse`) en `BASE/eval/infrastructure/in/rest/dto/`, `EvalHistoryMapper.toResponse(StoredRun,
  TraceUrlBuilder)` (rellena `trace_url` con `TraceUrlBuilder.urlFor`) y `GET /runs/{runId}` en `EvalHistoryController`, hasta que
  T032 y T033 pasen

### Pruebas de US2 (frontend, primero)

- [X] T037 [P] [US2] `FE/case-failures.spec.ts`: calidad — un caso falla si **alguna** métrica **con valor** es `< umbral`
  (estricto; un valor igual al umbral no falla); una métrica `null` no cuenta y un caso con todas las métricas `null` no falla;
  `failingMetrics` devuelve cuáles bajaron; umbral 0 → ningún caso falla, umbral 1 → fallan todos los que tienen algún valor
  `< 1`; recuperación — falla si `expectedDocuments` no está vacío y `hit_at_<k>` vale 0, un caso sin documentos esperados o con
  `hit_at_<k>` nulo no falla; `maxK(run)` toma el mayor de `retrieval_config.ks`; `selectFailures` filtra por categoría y por
  métrica y ordena (peor valor primero por defecto, por `case_id`, por categoría) con desempate estable; el recuento «F fallan de
  M medidos» usa `casesMeasured`
- [X] T038 [US2] Implementar `FE/case-failures.ts` (puro) hasta que T037 pase
- [X] T039 [P] [US2] Ampliar `FE/eval-api.client.spec.ts` y `FE/eval-store.spec.ts` (detalle): `getRun(runId)` traduce los campos,
  conserva los `null` de `scores` y de parámetros desconocidos; elegir una ejecución la carga **una vez** y la cachea por `runId`
  (elegirla otra vez no pide nada: es inmutable); el criterio de fallo inicia en `DEFAULT_THRESHOLD` (0,5) en calidad y en el mayor
  k en recuperación y cambiarlo **no hace peticiones**; un 404 deja `runNotFound` y limpia la selección; un error de red del
  detalle deja `detailError` sin tocar `listing`
- [X] T040 [US2] Ampliar `FE/eval-api.client.ts` (`getRun`) y `FE/eval-store.ts` (`selectedRunId`, `detail` en caché,
  `failureCriterion`, filtros y orden de fallos, `selectRun`, `clearSelection`) hasta que T039 pase
- [X] T041 [P] [US2] `FE/run-detail/run-detail.spec.ts` y `FE/run-detail/run-detail.ts`/`.html`/`.scss`: lista de definición «Con
  qué se midió» (cada prompt con su versión numérica y «fijada por petición» o «producción»; modelo del asistente o
  «desconocido»; embeddings; juez, sus ajustes y la biblioteca solo en calidad; recuperación con top-k, umbral, presupuesto y, en
  recuperación, profundidad y k; origen; commit o «desconocido»; huella del dataset abreviada; casos medidos, excluidos y sin
  puntuar por métrica = `cases − n`) y tabla de resumen (`Conjunto completo` y cada categoría, una columna por métrica con media
  a 4 decimales y `n`; **«sin valor»** donde `mean` es nulo, nunca 0). **Primero** el spec
- [X] T042 [P] [US2] `FE/failing-cases/failing-cases.spec.ts`: control de umbral en calidad (0–1, paso 0,05, inicial 0,5; un valor
  fuera de rango se limita) y selector de k en recuperación; texto «F casos fallan de M medidos» o «Ningún caso falla con este
  criterio»; filtros por categoría y métrica y orden; cada fila muestra id, categoría, pregunta y el valor de cada métrica con
  **icono y texto** («por debajo del umbral») en las que bajan, y «sin valor» en las nulas; abrir una fila muestra la respuesta de
  referencia, los documentos **esperados frente a recuperados** en orden con marca de acierto/fallo y si el asistente se abstuvo;
  con `traceUrl` el enlace «Ver traza en Langfuse» abre en pestaña nueva con `rel="noopener noreferrer"`, sin `traceUrl` muestra
  el `traceId` con botón «Copiar»; **no existe ningún elemento con texto de respuesta o de fragmentos** y se explica que viven en
  Langfuse; mover el umbral actualiza la lista sin peticiones
- [X] T043 [US2] Componente `FE/failing-cases/failing-cases.ts`, `.html` y `.scss` (usa `case-failures.ts`; tabla en contenedor
  desplazable con nombre accesible; fila expandible accesible por teclado con `aria-expanded`) hasta que T042 pase
- [X] T044 [US2] Integrar en `FE/admin-eval.ts`/`.html`: elegir una ejecución desde la lista o con `Enter` en un punto de la
  gráfica la selecciona, escribe `?ejecucion=<runId>` en la dirección (ignorar un id con formato inválido) y muestra `run-detail` y
  `failing-cases` debajo; un 404 muestra «La ejecución no existe (¿enlace antiguo?)» y limpia la dirección. **Primero** ampliar
  `FE/admin-eval.spec.ts` (selección desde lista y gráfica, `?ejecucion=` válido, inválido e inexistente)

**Checkpoint**: US1 y US2 funcionan; los criterios de aceptación de Jira están cubiertos (V9–V14, SC-002, SC-003, SC-004, SC-008).

---

## Phase 5: User Story 3 — Entender qué cambió entre dos ejecuciones (Priority: P2)

**Objetivo**: comparar dos ejecuciones del mismo tipo: parámetros que difieren, diferencia de métricas y casos que empeoraron,
mejoraron o no son comparables, con la regla exacta de `07-regressions.sql`.

**Prueba independiente**: con dos ejecuciones guardadas, una con un cambio deliberado de prompt, comparar ambas y comprobar que la
pantalla nombra el prompt que cambió, que la lista de empeoramientos coincide con `07-regressions.sql` y que un caso editado
aparece como no comparable (V15–V17).

### Pruebas de US3 (primero)

- [X] T045 [P] [US3] `TEST/eval/domain/RunComparatorTests.java` (JUnit puro, sin Spring ni base): tabla de casos con `drop`
  estricto en `BigDecimal` — `0.80 → 0.75` con `drop = 0.05` es `SAME` (**no** `WORSE`: en `double` daría 0,05000000000000004),
  `0.80 → 0.7499` es `WORSE`, `0.75 → 0.80` es `SAME` y `0.70 → 0.80` es `BETTER`; valor nulo en cualquiera de las dos →
  `NO_VALUE` y sin `delta`; misma `case_id` con otra `revision` → `NOT_COMPARABLE` y sin valores; `case_id` solo en una →
  `ONLY_BASE`/`ONLY_TARGET`; `delta = target − base` solo en `WORSE`/`BETTER`/`SAME`; orden de `cases` por `delta` ascendente,
  luego los que no tienen `delta` al final, desempate por `case_id`; `counts` suma exactamente los casos de la unión; la pregunta
  de un caso es la de `target` (o la de `base` si solo está en `base`); parámetros que difieren —
  `prompts.<nombre>.version`, `assistant_model`, `embedding_model`, `judge.*`, `evaluator.*`, `retrieval_config.*`, `dataset_hash`,
  `commit_sha`, `origin`— con `affects_comparability = true` solo para juez, evaluador, `assistant_model`, `embedding_model`,
  `retrieval_config` y `dataset_hash`; `warnings` = los campos con `affects_comparability`; un `null` frente a un valor es una
  diferencia («desconocido»); `metrics` lista la unión de las métricas con `delta` nulo si alguna media es nula
- [X] T046 [P] [US3] `TEST/eval/application/usecase/CompareEvalRunsUseCaseTests.java` (Mockito): `base = target` → 422 con
  `detail`; `metric` que ninguna de las dos tiene → 422 con `detail` (no una comparación vacía); tipos distintos →
  `EvalRunsNotComparableException`; una ejecución ausente → `EvalRunNotFoundException`; `drop` por defecto 0,05; la validación de
  parámetros ocurre antes de leer ninguna ejecución; no consulta token
- [X] T047 [P] [US3] `TEST/eval/EvalHistoryEquivalenceTests.java` (`@PostgresTest`, mismas fixtures `hist-a`/`hist-b`/`hist-c` que
  `EvalHistoryQueriesTests`; reutilizar su método `run` o extraerlo a un apoyo compartido en `TEST/eval/`): para `drop` 0,05, 0,5
  y 0 la lista de `WORSE` de la comparación es **idéntica** a las filas de `eval/queries/07-regressions.sql` (mismos
  `case_id`, mismos valores); los valores por caso coinciden con `05-compare-runs.sql`; el caso reescrito aparece como
  `NOT_COMPARABLE` donde esa consulta lo marca «no comparable»; las medias de `metrics` coinciden con `03-run-summary.sql`; el
  conjunto de `parameters` con `affects_comparability` coincide con `06-compare-parameters.sql` (FR-019, SC-005)
- [X] T048 [P] [US3] Ampliar `TEST/eval/infrastructure/in/rest/EvalHistoryControllerTests.java` (comparación): `GET
  /api/eval/history/compare` 200 con los campos exactos del contrato; `base` o `target` inexistentes → 404; `metric` o `drop`
  inválidos y `base = target` → 422 `SLHD_EVAL_00002` con `detail`; tipos distintos → 422 `SLHD_EVAL_00006`; 503; sin token

### Implementación de US3 (backend)

- [X] T049 [US3] Modelos en `BASE/eval/domain/model/`: `CaseComparison`, `ParameterDifference` (`field`, `base`, `target`,
  `affectsComparability`), `MetricComparison`, `ComparisonCounts`, `RunHeader` y `RunComparison`; enum
  `BASE/eval/domain/model/enums/CaseComparisonStatus.java` (`WORSE`, `BETTER`, `SAME`, `NO_VALUE`, `NOT_COMPARABLE`, `ONLY_BASE`,
  `ONLY_TARGET`) con su `wire()` en `snake_case` si el contrato lo pide (el JSON usa el nombre en mayúsculas del enum)
- [X] T050 [US3] `BASE/eval/domain/RunComparator.java` (clase pura, sin Spring, aritmética `BigDecimal` con la comparación estricta
  de `07-regressions.sql`, `compareTo`, nunca `double`) hasta que T045 pase
- [X] T051 [US3] `BASE/eval/application/port/in/CompareEvalRunsServicePort.java` y
  `BASE/eval/application/usecase/CompareEvalRunsUseCase.java` (`@Service`: valida, lee las dos ejecuciones con
  `EvalHistoryPort.find`, comprueba el tipo y delega en `RunComparator`) hasta que T046 pase; T047 y T048 pasan con T052
- [X] T052 [US3] DTOs (`RunComparisonResponse`, `MetricComparisonResponse`, `ParameterDifferenceResponse`,
  `CaseComparisonResponse`, `ComparisonCountsResponse`), `EvalHistoryMapper.toResponse(RunComparison)` y `GET /compare` en
  `EvalHistoryController` (`base`, `target`, `metric`, `drop` como `@RequestParam`), hasta que T047 y T048 pasen

### Pruebas de US3 (frontend, primero)

- [X] T053 [P] [US3] Ampliar `FE/eval-api.client.spec.ts` y `FE/eval-store.spec.ts` (comparación): `compare(base, target, metric,
  drop)` traduce la respuesta y conserva los `null`; la referencia por defecto de una ejecución elegida es **la anterior del mismo
  tipo** de la lista mostrada (ninguna si es la primera); `canCompare(a, b)` es falso si los tipos difieren; elegir referencia y
  objetivo pide la comparación una vez y cambiar `metric` o `drop` la repide; un 422 `SLHD_EVAL_00006` o 404 deja
  `comparisonError` sin tocar el resto
- [X] T054 [US3] Ampliar `FE/eval-api.client.ts` y `FE/eval-store.ts` (`comparison`, `setReference`, `compareWith`,
  `comparisonMetric`, `comparisonDrop` con `DEFAULT_DROP = 0.05`, métrica por defecto `faithfulness` o `reciprocal_rank`) hasta que
  T053 pase
- [X] T055 [P] [US3] `FE/run-comparison/run-comparison.spec.ts`: banner de **advertencia** con los campos de `warnings` («Puede no
  reflejar un cambio de calidad: difiere …»); tabla de parámetros con el valor de cada lado, «desconocido» para los nulos y los
  que no afectan marcados como informativos; diferencia de cada métrica del conjunto completo (sin valor donde `delta` es nulo);
  tres listas — **empeoraron**, **mejoraron** y **no comparables** (motivo «el caso cambió» o «solo está en una ejecución») —
  con valor de cada lado y diferencia; `SAME` y `NO_VALUE` solo como cifra; controles de métrica y tolerancia (0–1)
- [X] T056 [US3] Componente `FE/run-comparison/run-comparison.ts`, `.html` y `.scss` hasta que T055 pase
- [X] T057 [US3] Integrar en `FE/run-list/run-list.ts`/`.html` y `FE/admin-eval.ts`/`.html`: acciones «Usar como referencia» y
  «Comparar con la referencia» por fila; con una ejecución de otro tipo la acción está **deshabilitada** con el texto «Sus métricas
  no son las mismas» (HU3·5); mostrar `run-comparison` debajo del detalle. **Primero** ampliar `FE/run-list/run-list.spec.ts` y
  `FE/admin-eval.spec.ts` (referencia por defecto, acción deshabilitada, comparación visible)

**Checkpoint**: US1–US3 funcionan (V15–V17, SC-005).

---

## Phase 6: User Story 4 — Acceso, estados y accesibilidad (Priority: P2)

**Objetivo**: que la pantalla diga con claridad qué pasa cuando no hay datos, carga o falla, que se use con teclado y lector de
pantalla, y que se adapte a teléfono. (La ruta y la entrada de navegación ya están desde T012.)

**Prueba independiente**: abrir la aplicación en un teléfono y en un escritorio, llegar a la pantalla desde la navegación, y
comprobar los cuatro estados y el uso solo con teclado (V18–V21).

### Pruebas de US4 (primero)

- [X] T058 [P] [US4] Ampliar `FE/admin-eval.spec.ts` (estados): primera carga → «Cargando evaluaciones…» con `role="status"` y
  `aria-busy`; recarga → lo mostrado se conserva y «Actualizar» se deshabilita; sin ejecuciones → «Todavía no hay ejecuciones
  guardadas» y cómo generarlas (`eval/` con `quality --save` y el token de guardado, con enlace a `eval/README.md`) sin gráfica
  vacía; filtro sin resultados → «Ninguna ejecución con este origen» y botón «Quitar filtro»; error de la lista → `role="alert"` «No se
  pudo cargar la evaluación» con «Reintentar» que **no vacía** lo mostrado; el mensaje **no** contiene el `detail` ni el código
  interno del servidor (se mapea por `code`); un error del detalle o de la comparación queda en su sección y la gráfica sigue
  utilizable
- [X] T059 [P] [US4] Ampliar `FE/metric-chart/metric-chart.spec.ts` (teclado): `Tab` llega a un punto, `←`/`→` mueven el foco entre
  los puntos de la **misma serie** (sin salir por los extremos), `Enter` y `Space` emiten `runSelected`, `Escape` cierra el panel;
  el panel también aparece al tocar; `prefers-reduced-motion` no activa animaciones
- [X] T060 [P] [US4] `FE/a11y.spec.ts`: la pantalla completa renderizada con datos tiene un único `h1`, cada control con
  etiqueta accesible, los iconos decorativos con `aria-hidden="true"`, las tablas anchas dentro de `role="region"` con nombre
  accesible y `tabindex="0"`, y los estados comunicados con texto además de color (los casos por debajo del umbral y la ejecución
  elegida llevan texto o icono con etiqueta)

### Implementación de US4

- [X] T061 [US4] Estados en `FE/admin-eval.html`/`.ts`/`.scss` (cargando, recargando, vacío, vacío por filtro, error con
  «Reintentar», ejecución inexistente) con los textos de [contracts/admin-eval-screen.md](contracts/admin-eval-screen.md), hasta
  que T058 pase; el mapeo del error usa `ApiError.code` de `frontend/src/app/core/api/api-error.ts`, no `detail`
- [X] T062 [US4] Teclado de la gráfica en `FE/metric-chart/metric-chart.ts` hasta que T059 pase
- [X] T063 [US4] Adaptación y accesibilidad en los `.scss` de la pantalla y de sus componentes: a ≤ 600 px los controles se apilan,
  la gráfica reduce su altura, las tablas viven en contenedores desplazables y **la página no tiene scroll horizontal**; objetivos
  táctiles ≥ 44 × 44 px; foco visible con los tokens del tema; colores solo de tokens Material 3 (contraste ≥ 4,5:1);
  `@media (prefers-reduced-motion: reduce)` sin transiciones. Hasta que T060 pase

**Checkpoint**: las cuatro historias funcionan (V18–V21, SC-009).

---

## Phase 7: Polish & cross-cutting

**Propósito**: documentación, datos de prueba, `requests.http`, puertas de calidad y validación de punta a punta.

- [X] T064 [P] ADR de la decisión: crear `docs/adr/0029-lectura-del-historial-de-evaluacion.md` (usa el número de T001) con la
  plantilla `docs/adr/0000-plantilla.md` y **Complementa a** ADR-0013, ADR-0015 y ADR-0028; recoger R1 (lectura abierta sin token en
  el módulo `eval`), R2 (medias en SQL), R4 (gráfica SVG propia sin biblioteca) y R9 (comparación en el backend con `BigDecimal`)
  con sus alternativas descartadas y consecuencias (riesgo de lectura abierta sin autenticación, la gráfica se reevalúa si crece);
  añadir su fila a `docs/adr/README.md`
- [X] T065 [P] Documentación de uso (FR-026): crear `docs/admin-eval.md` (para qué sirve la pantalla, cómo leer la gráfica y el
  detalle, qué significan un hueco, «sin valor», «no comparable» y «caso que falla», la advertencia del dataset en borrador, qué
  hacer sin historial y la propiedad `SHOPLAB_EVAL_HISTORY_TRACE_URL_TEMPLATE`); actualizar `eval/README.md` para que la pantalla
  sea la forma principal de **leer** el historial y las consultas SQL de `eval/queries/` la secundaria, y añadir en `docs/modulos.md`
  la lectura del módulo `eval` y un enlace a `docs/admin-eval.md`
- [X] T066 [P] Datos de prueba deterministas para el quickstart (R14): crear `SPEC/seed/generate_seed.py` (solo biblioteca
  estándar; lee `eval/golden.jsonl` y escribe en `SPEC/seed/out/` cinco cargas con el formato de
  `specs/LAB-31-eval-run-persistence/contracts/eval-runs-api.md`: tres de calidad —una con un cambio deliberado de la versión de
  `chat-system` y de un caso reescrito, y una con `context_precision` vacío por definición— y dos de recuperación con k
  distintos), `SPEC/seed/.gitignore` (`out/`) y `SPEC/seed/seed.http` con una petición `POST {{baseUrl}}/api/eval/runs` por carga y
  `X-Eval-Token: {{token}}` (variable, **nunca** un secreto real). Las puntuaciones salen de una función determinista de `case_id`
  y número de ejecución, y todos los textos del dataset respetan los límites de LAB-31 (pregunta 10–300, respuesta de referencia
  20–800, 0 a 2 documentos esperados)
- [X] T067 Regla del proyecto «HTTP test requests»: crear `SPEC/requests.http` (sintaxis IntelliJ HTTP Client / VS Code REST
  Client; `baseUrl` por defecto `http://localhost:8080`, sin secretos) con **un caso por escenario de aceptación y regla del
  spec que se pueda ejercitar por HTTP**, cada uno titulado con su origen y el resultado esperado (p. ej. `### FR-019 / Escenario
  «Los casos que empeoraron coinciden con la consulta» — expects 200`): lista por tipo, por origen y con `limit` 1 y 500 (frontera
  de la regla) y 501 y 0 (fuera de ella → 422 `SLHD_EVAL_00002`), detalle existente e inexistente (404 `SLHD_EVAL_00004`),
  comparación con `drop` 0,05 (frontera exacta) y 0,04, tipos distintos (422 `SLHD_EVAL_00006`), `base = target` (422), y que
  **ninguna** necesita `X-Eval-Token`; en un bloque de comentarios al inicio, «Not covered here»: los requisitos de pantalla
  (FR-001–FR-006, FR-010–FR-015, FR-021–FR-023), que no son alcanzables por HTTP. Capturar `runId` de respuestas previas donde el
  orden lo requiera. Terminar el resumen de la implementación con la ruta y el número de peticiones
- [X] T068 Regenerar la documentación de módulos: ejecutar `TEST/ModularityTests` (`cd backend && ./mvnw -q test
  -Dtest=ModularityTests`) y `ModuleArchitectureTests`/`SchemaIsolationTests`/`ModuleSchemasIntegrationTests`; commitear lo que
  cambie en `docs/modulos/` (módulo `eval`); comprobar que `eval` sigue dependiendo solo de `chat.api` y que nadie lee su esquema
- [X] T069 Puertas del backend: `cd backend && ./mvnw verify -Dskip.srcclr=true --no-transfer-progress` en verde con cobertura
  JaCoCo ≥ 80 %; `./mvnw checkstyle:check -Dskip.srcclr=true --no-transfer-progress` y PMD sin infracciones; líneas ≤ 121,
  llaves en todos los bloques, sin imports sin usar. Corregir lo que salga en los archivos de esta historia
- [X] T070 [P] Puertas del frontend: `cd frontend && npm run lint && npm test -- --watch=false && npm run build` en verde (ESLint
  y Prettier limpios, sin avisos de presupuesto de tamaño; la ruta `admin-eval` queda en su propio fragmento perezoso)
- [ ] T071 Validación de punta a punta: cargar los datos de T066 con `SPEC/seed/seed.http` contra el backend local, recorrer los
  escenarios V1–V24 de [quickstart.md](quickstart.md) con `ng serve` en escritorio y en un ancho de 360 px, con teclado y, si hay uno
  disponible, un lector de pantalla, y registrar en `SPEC/validation.md` el resultado y la evidencia de cada uno. Lo que no se
  pueda verificar (p. ej. V24, que exige el primer tag con esta historia en el clúster, o V21 con lector de pantalla) se marca
  «pendiente» con el motivo, **nunca** como pasado
- [X] T072 Higiene antes de commitear (regla 8.4): `git status --short`; el archivo suelto `SPEC/.token-usage.json` debe estar
  ignorado o fuera del commit; ningún `*.orig`, `target/`, `out/` de `seed/`, `*.log` ni cargas de depuración; `git diff --staged`
  solo con cambios de esta historia; commits atómicos en formato Conventional Commits (`feat(eval): …`, `docs(eval): …`). Ejecutar
  la skill `new-properties`: la única propiedad nueva es `shoplab.eval.history.trace-url-template` (opcional, con valor por
  defecto); informar de ello y de que **no** hay cambios de infraestructura ni secretos nuevos

---

## Dependencias y orden de ejecución

### Dependencias entre fases

- **Fase 1 (Setup)**: sin dependencias; T001 antes de T064.
- **Fase 2 (Foundational)**: depende de la Fase 1 y **bloquea** todas las historias. Dentro de ella: T006 → T007; T009 → T010;
  T004 y T008 antes de cualquier tarea de adaptador; T012 antes de cualquier tarea de pantalla.
- **Fase 3 (US1)**, **Fase 4 (US2)**, **Fase 5 (US3)** y **Fase 6 (US4)**: dependen de la Fase 2.
  - US2 reutiliza el store, el cliente, la lista y la página de US1 (los **amplía**): se hace **después** de US1 en la parte del
    frontend. Su backend es independiente de US1 salvo por compartir `JdbcEvalHistoryAdapter` y `EvalHistoryController`
    (T017/T018 antes de T035/T036 para no chocar en los mismos archivos).
  - US3 depende de US2 en el backend (`find` del adaptador) y del store/lista en el frontend.
  - US4 depende de que existan la página y la gráfica (US1) y recorre lo de US2 y US3; puede empezar sus pruebas (T058–T060) en
    cuanto exista la página.
- **Fase 7 (Polish)**: depende de las historias que se vayan a entregar; T068–T070 al final; T071 con todo implementado.

### Dentro de cada historia

- Pruebas **antes** de implementar y deben fallar primero; modelos y puertos → casos de uso → adaptador → controlador;
  funciones puras → cliente → store → componentes → integración en la página.
- Una historia queda completa cuando su checkpoint se cumple, antes de pasar a la siguiente prioridad.

### Oportunidades de paralelismo

- **Fase 2**: T003, T004, T005, T008 y T011 son archivos distintos; T006 y T009 (pruebas) en paralelo entre sí.
- **US1**: backend (T013–T018) y frontend (T019–T029) en paralelo; dentro del frontend, T019/T021/T023/T024/T026/T028 son
  pruebas de archivos distintos.
- **US2**: T030–T033 en paralelo; T037/T039/T041/T042 en paralelo; backend y frontend en paralelo.
- **US3**: T045–T048 en paralelo; T053/T055 en paralelo; backend y frontend en paralelo.
- **US4**: T058–T060 en paralelo.
- **Polish**: T064, T065, T066 y T070 en paralelo.

### Ejemplo de ejecución en paralelo (US1)

```text
Persona/agente A (backend)           Persona/agente B (frontend)
T013 + T014 + T015 (pruebas)         T019 + T021 + T023 + T024 (pruebas)
T016 → T017 → T018                   T020, T022, T025, T026 → T027, T028
                                     T029 (integra, cuando A haya publicado el contrato)
```

---

## Estrategia de implementación

### MVP primero (solo US1)

1. Fase 1 y Fase 2 completas.
2. Fase 3 (US1): la gráfica con tipo, métricas y origen.
3. **Parar y validar** con V1–V8: es una pantalla que ya responde «¿mejora o empeora?» (SC-001).
4. Demostrable aunque US2–US4 no estén.

### Entrega incremental

1. Setup + Foundational → base lista.
2. + US1 → gráfica (MVP, primer criterio de Jira).
3. + US2 → detalle y casos que fallan (**segundo criterio de Jira: con esto la historia cumple lo pedido**).
4. + US3 → comparación (P2; se puede retirar sin afectar a US1 y US2, como dice el checklist del spec).
5. + US4 → estados, teclado y adaptación a teléfono.
6. Polish → ADR, documentación, datos de prueba, `requests.http`, puertas y validación manual.

### Notas

- `[P]` = archivos distintos y sin dependencias pendientes.
- Verificar que las pruebas fallan antes de implementar; commit por unidad lógica (no por historia).
- No hay migraciones, tablas ni infraestructura nuevas: si aparecen en una tarea, parar y revisar el plan.
- Si una tarea revela que `eval/queries/*.sql` y el comparador discrepan, **gana la regla de las consultas documentadas** y se
  corrige el comparador (FR-019).
