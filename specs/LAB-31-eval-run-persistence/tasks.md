# Tasks: Persistencia de las ejecuciones de evaluación

**Input**: documentos de diseño en `specs/LAB-31-eval-run-persistence/`
**Prerequisitos**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/eval-runs-api.md](contracts/eval-runs-api.md), [quickstart.md](quickstart.md)

**Pruebas**: SÍ. La Constitución (IV) las exige y pide cobertura ≥ 80 %; FR-019 pide poder probar la traducción de una ejecución
sin red, sin modelo y sin base real. Cada pieza con lógica lleva su prueba **antes** (tarea `[P]` o inmediatamente anterior).
Ninguna prueba usa Ollama, Langfuse ni red: PostgreSQL real solo vía `@PostgresTest` (Testcontainers) y backend simulado
(`ThreadingHTTPServer`) en `eval/`. **JaCoCo excluye** `dto`, `config`, `model`, `util/mapper` y `exception`: la lógica va en
`application/usecase` o `infrastructure`, nunca en esos paquetes.

**Alcance**: LAB-30 queda fuera (indicación del usuario): ninguna tarea toca el CI ni dónde guarda una ejecución de CI efímero.

**Organización**: por historia de usuario. Backend (`backend/`), herramienta (`eval/`) e infraestructura (`../shoplab-infra`, rama y
PR propios) avanzan en paralelo una vez terminada la Fase 2.

## Formato: `[ID] [P?] [Historia] Descripción con ruta`

- **[P]**: se puede hacer en paralelo (archivos distintos, sin dependencias pendientes)
- **[US#]**: historia de `spec.md` a la que pertenece
- Rutas relativas a la raíz del repositorio. `BASE` = `backend/src/main/java/com/shoplab/helpdesk`, `TEST` =
  `backend/src/test/java/com/shoplab/helpdesk`, `EV` = `eval/src/shoplab_eval`, `EVT` = `eval/tests`,
  `INFRA` = el repositorio hermano `shoplab-infra` (ver T057)
- Identificadores en inglés; documentación y mensajes de usuario en español; líneas ≤ 121 caracteres; llaves en todos los bloques

---

## Phase 1: Setup (módulo vacío y verificación previa)

**Propósito**: el esqueleto del módulo `eval` y lo que el plan dejó por comprobar.

- [X] T001 Comprobar el número libre del ADR: listar `docs/adr/` en `main` y en las ramas remotas (`git branch -a`, `git log --all
  -- docs/adr`), confirmar si el 0026 existe en algún sitio y fijar el número (previsto **0028**). Crear
  `specs/LAB-31-eval-run-persistence/validation.md` con una sección por escenario de `quickstart.md` (V1–V19) y la línea
  «ADR: <número>» (la usa T066)
- [X] T002 Crear el esqueleto del módulo: `BASE/eval/package-info.java` (`@ApplicationModule(displayName = "Eval")`, igual que
  `BASE/chat/package-info.java`) y `BASE/eval/api/package-info.java` (`@NamedInterface("api")`, mismo comentario que
  `BASE/chat/api/package-info.java`; el módulo todavía no publica nada)
- [X] T003 [P] Crear `BASE/eval/config/EvalSchemaConfig.java` (`@Configuration` con un `@Bean ModuleSchema` llamado
  `evalSchema` que devuelve `new ModuleSchema("eval")`, como `ChatSchemaConfig`) y
  `backend/src/main/resources/db/migration/eval/V1__baseline.sql` (solo comentarios, con el mismo estilo de
  `db/migration/chat/V1__baseline.sql`: esquema `eval`, dueño el módulo `eval`, las tablas llegan en `V2`)

---

## Phase 2: Foundational (bloquea todas las historias)

**Propósito**: tipos, errores, propiedades, puertos, control de acceso y las tres tablas. Nada de las historias empieza antes.

**⚠️ CRÍTICO**: terminar esta fase completa.

- [X] T004 [P] Añadir `"eval"` a `MODULES` de `TEST/schema/ModuleSchemasIntegrationTests.java` y revisar con
  `grep -rn '"tickets"' backend/src/test` cualquier otra lista de módulos (`SchemaIsolationTests`, pruebas de arquitectura) para
  añadirlo también; la prueba existente «cada módulo tiene su propio historial de Flyway» debe cubrir `eval`
- [X] T005 [P] Crear en `BASE/eval/domain/model/enums/`: `RunKind` (`RETRIEVAL`, `QUALITY`), `CaseKind` (`ANSWERED`,
  `UNANSWERED`, con `value()` en minúsculas: `answered`, `unanswered`) y `PromptSource` (`LANGFUSE`, `DEFAULT`). Cada enum con
  `fromValue(String)` que devuelve `Optional` vacío si el texto no coincide (la validación decide el error)
- [X] T006 [P] Crear `BASE/eval/domain/model/constants/EvalConstants.java` (`@UtilityClass`, agrupado en clases internas como
  `GeneralConstants`): patrón de `run_id` `^[A-Za-z0-9._-]{1,32}$`, de `case_id` `^G[0-9]{3}$`, de SHA-256 `^[0-9a-f]{64}$`, de commit
  `^[0-9a-f]{40}$`; cabecera `X-Eval-Token`; nombres de métrica (`faithfulness`, `answer_relevancy`, `context_precision`,
  `reciprocal_rank` y los prefijos `hit_at_` y `recall_at_`); categorías medibles (`payments`, `privacy`, `returns`, `shipping`,
  `warranty`, `off_topic`); los cuatro nombres de prompt de LAB-85 (`chat-system`, `chat-small-talk`, `chat-context`,
  `chat-empty-context`); límites de texto (pregunta 10–300, respuesta de referencia 20–800, 0 a 2 documentos esperados);
  límites de configuración (`top_k` 1–20, `similarity_threshold` 0–1, `max_context_characters` ≥ 500, `depth` 1–50)
- [X] T007 [P] Crear en `BASE/eval/domain/model/` los records (sin lógica): `EvalRun`, `EvalCase`, `EvalResult`, `PromptVersion`
  (`name`, `version` Integer, `source`, `pinned`), `RetrievalConfig`, `JudgeInfo`, `EvaluatorInfo`,
  `AssistantSettingsSnapshot` (parámetros de recuperación + lista de prompts servidos), `StoredRunSummary` (`runId`, `kind`,
  `startedAt`, `recordedAt`) y `SavedRun` (`runId`, `kind`, `results`, `newCaseRevisions`). Los campos son los de
  `data-model.md` y `contracts/eval-runs-api.md`
- [X] T008 [P] Módulo abierto `shared`: crear `BASE/shared/exception/PublicDetail.java` (interfaz con `String publicDetail()`),
  añadir a `BASE/shared/infrastructure/in/rest/dto/ErrorResponse.java` un tercer componente `detail` con
  `@JsonInclude(JsonInclude.Include.NON_NULL)` (el JSON de los demás errores no debe cambiar ni siquiera con `"detail":null`) y
  hacer que `GlobalExceptionHandler.handleTyped` lo rellene cuando la excepción implementa `PublicDetail`. Pruebas primero en
  `TEST/shared/infrastructure/in/rest/handler/GlobalExceptionHandlerTests.java`: sin `PublicDetail` el cuerpo es idéntico al de
  hoy; con `PublicDetail` incluye `detail`; el `detail` no se escribe en el registro de la petición
- [X] T009 Crear las cinco excepciones en `BASE/eval/domain/exception/` y los cinco códigos en
  `BASE/shared/exception/ResponseCodeEnum.java` con exactamente la tabla del contrato: `EVAL_PERSISTENCE_DISABLED`
  `SLHD_EVAL_00001` 403; `EVAL_RUN_INVALID` `SLHD_EVAL_00002` 422; `EVAL_RUN_ALREADY_EXISTS` `SLHD_EVAL_00003` 409;
  `EVAL_RUN_NOT_FOUND` `SLHD_EVAL_00004` 404; `EVAL_STORE_UNAVAILABLE` `SLHD_EVAL_00005` 503 (mensajes por defecto en inglés,
  como los demás). `EvalPersistenceDisabledException`, `EvalRunAlreadyExistsException` y `EvalRunNotFoundException` extienden
  `BusinessException`; `EvalStoreUnavailableException` extiende `TechnicalException` y conserva la causa;
  `EvalRunInvalidException` extiende `BusinessException`, implementa `PublicDetail` y recibe el detalle («campo: motivo») en el
  constructor. Depende de T008
- [X] T010 [P] Crear `BASE/eval/config/EvalProperties.java` (`@ConfigurationProperties("shoplab.eval.persistence")`, record con
  `String token`, `null` → vacío), `BASE/eval/config/EvalConfig.java` con `@EnableConfigurationProperties(EvalProperties.class)`
  y la propiedad en `backend/src/main/resources/application.yml` con el comentario y el valor del contrato:
  `token: ${SHOPLAB_EVAL_PERSISTENCE_TOKEN:}` bajo `shoplab.eval.persistence`
- [X] T011 [P] Crear los puertos en `BASE/eval/application/port/`: entrada `in/SaveEvalRunServicePort`
  (`Mono<SavedRun> save(String token, EvalRun run)`), `in/FindEvalRunServicePort`
  (`Mono<StoredRunSummary> find(String token, String runId)`), `in/ReadAssistantSettingsServicePort`
  (`Mono<AssistantSettingsSnapshot> read(String token)`); salida `out/EvalRunStorePort`
  (`Mono<SavedRun> save(EvalRun run)`, `Mono<Optional<StoredRunSummary>> find(String runId)`) y `out/AssistantSettingsPort`
  (`Mono<AssistantSettingsSnapshot> current()`)
- [X] T012 [P] Prueba `TEST/eval/application/usecase/EvalAccessGuardTests.java`: sin token configurado (vacío) se rechaza **aunque**
  la petición traiga un valor vacío o cualquier otro; token distinto, `null` y token correcto; el error es
  `EvalPersistenceDisabledException`; el valor presentado y el configurado no aparecen en el mensaje de la excepción
- [X] T013 Implementar `BASE/eval/application/usecase/EvalAccessGuard.java` (`@Component`): `void check(String presented)` con
  comparación en tiempo constante (`MessageDigest.isEqual` sobre los bytes UTF-8). Hace pasar T012
- [X] T014 [P] Prueba `TEST/schema/EvalSchemaIntegrationTests.java` (`@SpringBootTest`, `@PostgresTest`, patrón de
  `ChatSchemaIntegrationTests`): existen `eval.eval_run`, `eval.eval_case` y `eval.eval_result`; `eval.flyway_schema_history`
  tiene dos filas con éxito; ninguna clave foránea sale del esquema; y se rechazan (con `DataIntegrityViolationException`) un
  `run_id` con espacio o de 33 caracteres, `run_id` repetido, `finished_at < started_at`, `kind` desconocido, `commit_sha` que
  no es de 40 hexadecimales, `dataset_hash` que no es de 64, un `judge` en una ejecución `RETRIEVAL`, un `eval_case` repetido
  por `(case_id, content_hash)`, un `eval_result` repetido por `(run_ref, case_ref)` y un `scores` con un número fuera de 0–1
  (un `null` y un `0` sí se aceptan)
- [X] T015 Crear `backend/src/main/resources/db/migration/eval/V2__evaluation_history.sql` con las tres tablas de
  `data-model.md`, citando sus restricciones tal cual. **`eval_run`**: `id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY`;
  `run_id text NOT NULL UNIQUE CHECK (run_id ~ '^[A-Za-z0-9._-]{1,32}$')`; `kind` ∈ `RETRIEVAL`, `QUALITY`; `started_at` y
  `finished_at timestamptz NOT NULL` con `finished_at >= started_at`; `recorded_at timestamptz NOT NULL DEFAULT now()`; `origin
  text NOT NULL`; `commit_sha text NULL` (40 hex o nulo); `dataset_hash text NOT NULL` (64 hex); `cases_measured` y
  `cases_excluded int NOT NULL CHECK (>= 0)`; `assistant_model text NULL`; `embedding_model text NOT NULL`; `prompts jsonb NOT NULL`
  con `jsonb_typeof(prompts) = 'array'`; `judge` y `evaluator jsonb NULL`, presentes si y solo si `kind = 'QUALITY'`;
  `retrieval_config jsonb NOT NULL` objeto. **`eval_case`**: `id` identidad PK; `case_id text NOT NULL CHECK (case_id ~
  '^G[0-9]{3}$')`; `content_hash text NOT NULL` (64 hex); `category` ∈ las ocho del dataset; `question`, `reference_answer text NOT
  NULL`; `expected_documents text[] NOT NULL`; `created_at timestamptz NOT NULL DEFAULT now()`; `UNIQUE (case_id, content_hash)`.
  **`eval_result`**: `id` identidad PK; `run_ref bigint NOT NULL REFERENCES eval_run(id)`; `case_ref bigint NOT NULL REFERENCES
  eval_case(id)`; `case_kind` ∈ `answered`, `unanswered`; `scores jsonb NOT NULL` objeto sin ningún valor numérico fuera de
  0–1 (por ejemplo `NOT jsonb_path_exists(scores, '$.* ? (@.type() == "number" && (@ < 0 || @ > 1))')`; los `null` valen);
  `retrieved_documents text[] NOT NULL`; `abstained boolean NULL`; `trace_id text NULL`; `UNIQUE (run_ref, case_ref)`. Índice
  en `eval_result (case_ref)` y en `eval_run (started_at DESC)`. Comentario de cabecera como el de `chat/V2`: solo este módulo
  lee y escribe; **no hay texto de respuestas ni de fragmentos**; sin borrado ni retención por ahora. Hace pasar T014 y T004

**Checkpoint**: `cd backend && ./mvnw -B -ntp test -Dtest='EvalSchemaIntegrationTests,ModuleSchemasIntegrationTests,EvalAccessGuardTests,GlobalExceptionHandlerTests'` en verde.

---

## Phase 3: User Story 1 — Guardar cada ejecución con el resultado de cada caso (P1) 🎯 MVP

**Meta**: que una medición de calidad o de recuperación termine con su ejecución y un resultado por caso guardados en el esquema
`eval`, enteros o ninguno, sin texto de respuestas y sin credenciales de base de datos en `eval/`.

**Prueba independiente** (`quickstart.md` V1–V4, V16): con el backend arrancado con token y la persistencia pedida, ejecutar
`quality --save` y `retrieval --save`; hay dos ejecuciones, un resultado por caso medido, y las medias recalculadas desde la base
coinciden con los CSV. Hasta la Fase 4 la herramienta envía los parámetros que ella conoce (la instantánea del asistente llega en US2).

### Pruebas primero (backend)

- [X] T016 [P] [US1] `TEST/eval/application/usecase/EvalRunValidatorTests.java`: casos válidos de calidad y de recuperación y un
  rechazo (con el campo en el `detail`) por cada regla de `data-model.md`: formato de `run_id`, `case_id`, hashes y commit;
  `kind` desconocido; `finished_at < started_at`; `case_id` repetido en la ejecución; `cases_measured` distinto del número de
  resultados; categoría de conversación (`small_talk`, `injection`) o desconocida; pregunta fuera de 10–300 o respuesta de
  referencia fuera de 20–800; más de dos documentos esperados o repetidos; en `QUALITY` un caso `answered` solo con `faithfulness`,
  `answer_relevancy` y `context_precision`, y uno `unanswered` solo con `faithfulness`; en `RETRIEVAL` solo casos `answered` con
  `hit_at_<k>` y `recall_at_<k>` para cada `k` de `retrieval_config.ks` y `reciprocal_rank`; un valor fuera de 0–1; una
  métrica desconocida; `judge`/`evaluator` en `RETRIEVAL` o ausentes en `QUALITY`; clave `abstained`/`trace_id` no nulos en
  `RETRIEVAL`. (Los vacíos `null` son válidos donde la métrica aplica)
- [X] T017 [US1] Implementar `BASE/eval/application/usecase/EvalRunValidator.java` (`@Component`, `void validate(EvalRun run)` que
  lanza `EvalRunInvalidException` con el primer fallo, nombrando el campo o el caso, **nunca** el texto de una pregunta o de una
  respuesta). En esta historia valida la **forma** de `prompts` y `retrieval_config` (lista y objeto); la exigencia de sus campos
  llega en T044. Hace pasar T016
- [X] T018 [P] [US1] `TEST/eval/application/usecase/CaseRevisionHasherTests.java`: determinista; cambia al cambiar cada uno de los
  cinco campos (id, categoría, pregunta, respuesta de referencia, documentos); **no** cambia al reordenar los documentos esperados;
  dos casos que se distinguirían solo por dónde cae un separador (`"ab","c"` frente a `"a","bc"`) dan hashes distintos; la salida
  es de 64 hexadecimales en minúscula; acentos y `ñ` estables (UTF-8)
- [X] T019 [US1] Implementar `BASE/eval/application/usecase/CaseRevisionHasher.java`: SHA-256 de los cinco campos en UTF-8, con
  separadores de unidad (`\u001f`) entre campos y de registro (`\u001e`) entre documentos esperados ordenados alfabéticamente.
  Hace pasar T018
- [X] T020 [P] [US1] `TEST/eval/application/usecase/SaveEvalRunUseCaseTests.java` (Mockito, `StepVerifier`): el guardián se
  comprueba **antes** que nada (con token malo no se llama al validador ni al puerto); una ejecución inválida no llega al
  puerto; una válida llega con los hashes de revisión calculados; el `EvalRunAlreadyExistsException` y el
  `EvalStoreUnavailableException` del puerto se propagan tal cual; el registro de la operación no contiene preguntas,
  respuestas ni el token (capturar con el apéndice de registro de prueba que ya usan otras pruebas, o comprobando que el caso de
  uso solo registra `run_id`, tipo y recuentos)
- [X] T021 [US1] Implementar `BASE/eval/application/usecase/SaveEvalRunUseCase.java` (implementa `SaveEvalRunServicePort`):
  `guard.check` → `validator.validate` → calcular el hash de cada caso → `store.save`; registra `run_id`, tipo y número de casos
  y de revisiones nuevas, sin contenido. Hace pasar T020
- [X] T022 [US1] `TEST/eval/infrastructure/out/persistence/JdbcEvalRunStoreAdapterTests.java` (`@SpringBootTest`, `@PostgresTest`):
  guardar una ejecución de calidad deja 1 `eval_run` y un `eval_result` por caso, con `null` guardado como JSON `null` y `0` como
  `0`; las **medias por ámbito recalculadas en SQL** (global y por categoría, ignorando `null`) y su `n` coinciden con los valores
  de entrada (FR-015, SC-002); dos ejecuciones sobre el mismo caso comparten la fila de `eval_case`; el mismo `case_id` con la
  pregunta cambiada crea una **revisión nueva** y el resultado antiguo sigue apuntando a la anterior; **atomicidad**: forzar un
  fallo a mitad (por ejemplo un resultado con un `case_ref` que hace saltar una restricción tras haber insertado la ejecución) y
  comprobar que no queda ninguna fila en las tres tablas; `run_id` repetido → `EvalRunAlreadyExistsException` sin filas nuevas;
  **dos guardados concurrentes** con el mismo `run_id` (dos hilos con un `CountDownLatch`): uno gana y el otro recibe
  `EvalRunAlreadyExistsException`; con un `JdbcTemplate` simulado que lanza `CannotGetJdbcConnectionException` →
  `EvalStoreUnavailableException`
- [X] T023 [US1] Implementar `BASE/eval/infrastructure/out/persistence/JdbcEvalRunStoreAdapter.java` (`@Component`,
  `@DependsOn("moduleSchemaMigrator")`, implementa `EvalRunStorePort`, mismo patrón que `JdbcEmbeddingStoreAdapter`): una sola
  transacción (`TransactionTemplate`) dentro de `BlockingCalls.call`; inserta `eval_run` (`RETURNING id`), por cada resultado crea
  o reutiliza la revisión con `INSERT … ON CONFLICT (case_id, content_hash) DO UPDATE SET case_id = EXCLUDED.case_id RETURNING id,
  (xmax = 0) AS inserted` y cuenta las nuevas, e inserta los resultados con `batchUpdate`. Los `jsonb` se serializan con el
  `JsonMapper` del contexto y se envían como `?::jsonb`; los `text[]` con `Connection.createArrayOf`. Traducción: violación de la
  restricción única de `run_id` → `EvalRunAlreadyExistsException`; otra violación de integridad → `EvalRunInvalidException`
  (`detail` genérico: «la base rechazó la ejecución»); el resto de `DataAccessException` → `EvalStoreUnavailableException`. Sin
  texto de pregunta o respuesta en ningún registro. Hace pasar T022

### Endpoint de guardado

- [X] T024 [P] [US1] Crear en `BASE/eval/infrastructure/in/rest/dto/` los DTO del cuerpo del contrato: `EvalRunRequest` y sus
  anidados (`PromptVersionRequest`, `JudgeRequest`, `EvaluatorRequest`, `RetrievalConfigRequest`, `ResultRequest`,
  `CaseRequest`) con nombres JSON en `snake_case` (`@JsonProperty` o `@JsonNaming`) y **rechazo de claves desconocidas**
  (`@JsonIgnoreProperties(ignoreUnknown = false)`; verificarlo en T025), y `EvalRunSavedResponse` (`run_id`, `kind`, `results`,
  `new_case_revisions`). Mapeador sin lógica en `BASE/eval/infrastructure/in/rest/util/mapper/EvalRunRequestMapper.java`
  (de DTO a `EvalRun`; los textos de `kind` y `case_kind` desconocidos pasan como inválidos al validador, no lanzan aquí)
- [X] T025 [US1] `TEST/eval/infrastructure/in/rest/EvalRunControllerTests.java` (`@WebFluxTest` o el patrón de
  `KnowledgeSearchControllerTests`, con el puerto simulado): `POST /api/eval/runs` devuelve **201** y el cuerpo del contrato; sin la
  cabecera `X-Eval-Token` el puerto recibe `null` y la respuesta es **403** `SLHD_EVAL_00001`; JSON ilegible o con una **clave
  desconocida** (por ejemplo `"answer"` dentro de un resultado) → **400** `SLHD_GENE_00002`; la excepción de ejecución inválida →
  **422** `SLHD_EVAL_00002` con `detail`; `EvalRunAlreadyExistsException` → **409**; `EvalStoreUnavailableException` → **503**;
  el token nunca aparece en el cuerpo de ninguna respuesta
- [X] T026 [US1] Implementar `BASE/eval/infrastructure/in/rest/EvalRunController.java` (`@RestController`, `@RequestMapping
  ("/api/eval/runs")`): `@PostMapping` con `@RequestHeader(name = "X-Eval-Token", required = false)`, construye el comando con el
  mapeador y llama a `SaveEvalRunServicePort`; **sin lógica** (Constitución II); responde `201`. Hace pasar T025
- [X] T027 [P] [US1] `TEST/eval/EvalNoTextStoredTests.java` (`@SpringBootTest`, `@PostgresTest`; FR-009, SC-008): (a) **lista
  blanca de columnas**: las columnas de `eval_run`, `eval_case` y `eval_result` leídas de `information_schema.columns` son
  exactamente las de `data-model.md`, de modo que añadir un día una columna de texto de respuesta obliga a tocar esta prueba y a
  revisarlo; (b) un cuerpo de `POST /api/eval/runs` con una clave `answer`, `fragments` o `context` dentro de un resultado se
  rechaza con **400** y no deja filas; (c) tras guardar una ejecución completa, las únicas columnas de texto libre con contenido
  del cliente son `eval_case.question`, `reference_answer` y `expected_documents`, que son del dataset golden
- [X] T028 [P] [US1] `TEST/schema/EvalHasNoDatabaseCredentialsTests.java`, a imagen de `IngestHasNoDatabaseCredentialsTests`:
  recorre `../eval/src`, `../eval/pyproject.toml` y `../eval/uv.lock` (no `queries/`, `README.md` ni `tests/`, que pueden citar
  `psql`) y falla si aparece `DATABASE_URL`, `POSTGRES_`, `PGHOST`, `PGPASSWORD`, `SPRING_DATASOURCE`, `jdbc:`, `postgres://`,
  `postgresql://`, `psycopg` o `asyncpg`; con pruebas de `violationsIn` sobre texto sintético. Es el guardarraíl de R1

### Herramienta `eval/` (guardar lo que ya se mide)

- [X] T029 [P] [US1] `EVT/test_store_payload.py`: a partir de un `QualityReport` y de un `RetrievalReport` construidos con los mismos
  ayudantes que `test_quality_report.py` y `test_retrieval_report.py`, la carga tiene los campos del contrato; **vacío ≠ cero**
  (`None` → `null`, `0.0` → `0.0`); un caso sin respuesta solo lleva `faithfulness`; los de conversación no aparecen y cuentan
  en `cases_excluded`; `retrieved_documents` conserva el orden y las repeticiones del CSV de calidad; en recuperación lleva
  `hit_at_<k>`, `recall_at_<k>` por cada `k` y `reciprocal_rank`, y `abstained`/`trace_id` son `null`; `dataset_hash` es el
  SHA-256 de los bytes de `golden.jsonl` y cambia al cambiar un byte; `commit_sha` sale de `git rev-parse HEAD` y es `None` si
  `git` no está o no es un repositorio (sin lanzar); `origin` es `local` por defecto y se toma de `SHOPLAB_EVAL_ORIGIN`; y la
  **paridad con el CSV**: la media de cada métrica recalculada desde `results` de la carga es igual, a 4 decimales, a la fila
  `all` y a las de cada categoría de `summary_csv` (SC-002)
- [X] T030 [US1] Crear `EV/store_payload.py` (funciones puras, sin red): `quality_payload(...)`, `retrieval_payload(...)`,
  `dataset_hash(path)` y `git_commit(cwd)` (`subprocess` con tiempo máximo; `None` ante cualquier fallo). Añadir a
  `EV/constants.py` las constantes nuevas: ruta `/api/eval/runs`, `/api/eval/settings`, cabecera `X-Eval-Token`, variables
  `SHOPLAB_EVAL_TOKEN` y `SHOPLAB_EVAL_ORIGIN`, origen por defecto `local` y los tipos `RETRIEVAL`/`QUALITY`. Hace pasar T029
- [X] T031 [US1] Ampliar `EVT/fake_backend.py` con los endpoints `POST /api/eval/runs`, `GET /api/eval/runs/{id}` y
  `GET /api/eval/settings`: guardan las peticiones recibidas (método, ruta, cabeceras y cuerpo, para que las pruebas comprueben el
  orden) y responden lo que el test indique con el mismo mecanismo `Reply` de los demás manejadores
- [X] T032 [P] [US1] `EVT/test_store_client.py` contra el servidor falso: `save` envía JSON UTF-8 con `X-Eval-Token` y devuelve el
  resumen del 201; **403** → error que explica «token del backend distinto o guardado apagado» sin imprimir el token; **409** →
  «identificador ya guardado»; **422** → muestra el `detail`; **503** y servidor caído → error que nombra el backend y la URL; el
  token no aparece en ningún mensaje de error
- [X] T033 [US1] Crear `EV/store_client.py` (`EvalStoreClient(base_url, timeout, token, host_header)` con `save(payload)`; estándar
  `urllib`, mismo estilo que `search_client.py`) y `StoreError(Exception)` en `EV/errors.py`. Hace pasar T032
- [X] T034 [P] [US1] `EVT/test_store_cli.py` (parte US1): `quality --save` y `retrieval --save` con el stack simulado terminan con
  código 0, escriben los CSV **antes** de enviar, envían una carga con el `run_id` indicado y la salida dice «Guardada» con el
  identificador; si el guardado falla (503) los CSV existen y el código es 1; `retrieval` acepta `--run-id` con la misma
  validación que `quality` (patrón `^[A-Za-z0-9._-]{1,32}$`, error de uso con código 2 si no cumple) y por defecto usa
  `new_run_id()`
- [X] T035 [US1] Cablear en `EV/__main__.py` (mínimo para US1): `--save` en `quality` y `retrieval`; `--run-id` en `retrieval`; el
  token sale de `SHOPLAB_EVAL_TOKEN`; se anotan `started_at`/`finished_at` (UTC) alrededor de la medición; tras escribir los CSV se
  construye la carga con `store_payload` y se envía con `EvalStoreClient`; un `StoreError` se imprime en el error estándar y el
  código es 1. No se toca el cálculo de métricas ni los CSV (FR-018). Hace pasar T034

**Checkpoint (MVP)**: `cd backend && ./mvnw -B -ntp test -Dtest='Eval*Tests,JdbcEvalRunStoreAdapterTests,EvalNoTextStoredTests,EvalHasNoDatabaseCredentialsTests'` y
`cd eval && uv run pytest -k store` en verde; V1–V4 y V16 del quickstart a mano contra un backend local con token.

---

## Phase 4: User Story 2 — Saber con qué prompt, modelo y recuperación se midió (P1)

**Meta**: que cada ejecución guarde la versión numérica de cada prompt servido, el modelo del asistente, el juez, los embeddings y
la configuración de recuperación **reales**, y que una medición cuya configuración cambió a mitad no se guarde.

**Prueba independiente** (V5, V6, V12): dos ejecuciones con otro prompt fijado y otro modelo dicen cuál usó cada una, una consulta
por prompt y versión las distingue, y mover una etiqueta de Langfuse durante la medición la invalida.

- [X] T036 [P] [US2] Crear en `BASE/chat/api/` el contrato de solo lectura: `AssistantSettingsApi` (`AssistantSettings current()`
  síncrono: lee memoria) y los tipos públicos `AssistantSettings` (`topK`, `similarityThreshold`, `maxContextCharacters` y
  `List<ServedPrompt>`) y `ServedPrompt` (`name`, `version` Integer nulo si es el texto por defecto, `source` como texto
  `LANGFUSE`/`DEFAULT`). Sin tipos internos de `chat` en la firma
- [X] T037 [P] [US2] `TEST/chat/infrastructure/in/api/AssistantSettingsApiAdapterTests.java`: con un `PromptCatalogPort` simulado
  que devuelve versiones de Langfuse para tres prompts y el texto por defecto para uno, la instantánea lleva los cuatro en el
  orden de `PromptName` con su nombre de Langfuse, la versión (`null` para el defecto) y su origen, y los tres parámetros de
  `RagProperties`; no llama a la red ni espera (solo `current(...)`)
- [X] T038 [US2] Implementar `BASE/chat/infrastructure/in/api/AssistantSettingsApiAdapter.java` (`@Component`, patrón de
  `retrieval/infrastructure/in/api/KnowledgeSearchApiAdapter`): recorre `PromptName.values()` con
  `PromptCatalogPort.current(name).reference()` y lee `RagProperties`. **No cambia** `PromptCatalogPort` ni el comportamiento
  del chat (FR-018). Hace pasar T037
- [X] T039 [P] [US2] Pruebas `TEST/eval/application/usecase/ReadAssistantSettingsUseCaseTests.java` (guardián primero; delega en el
  puerto) y `TEST/eval/infrastructure/out/chat/ChatAssistantSettingsAdapterTests.java` (traduce `AssistantSettings` de `chat.api`
  al `AssistantSettingsSnapshot` del módulo; no importa nada de `chat` que no sea `chat.api`)
- [X] T040 [US2] Implementar `BASE/eval/application/usecase/ReadAssistantSettingsUseCase.java` y
  `BASE/eval/infrastructure/out/chat/ChatAssistantSettingsAdapter.java`. Hace pasar T039 y los tests de Modulith
  (`ModularityTests`: la dependencia `eval → chat.api` es válida y no hay ciclos)
- [X] T041 [P] [US2] `TEST/eval/infrastructure/in/rest/EvalSettingsControllerTests.java`: `GET /api/eval/settings` devuelve 200 con
  la forma exacta del contrato (`retrieval.top_k`, `similarity_threshold`, `max_context_characters`, `prompts[]` con `name`,
  `version`, `source`); 403 sin token válido
- [X] T042 [US2] Implementar `BASE/eval/infrastructure/in/rest/EvalSettingsController.java` (`GET /api/eval/settings`), su DTO de
  respuesta y su mapeador. Hace pasar T041
- [X] T043 [US2] Ampliar `EvalRunValidatorTests` con la exigencia de contenido: `QUALITY` exige en `retrieval_config` `top_k`
  (1–20), `similarity_threshold` (0–1) y `max_context_characters` (≥ 500), y `prompts` con los cuatro nombres conocidos, cada uno
  con `source` (`LANGFUSE` ⇒ `version` ≥ 1; `DEFAULT` ⇒ `version` nula) y `pinned`; `RETRIEVAL` exige `prompts` igual a `[]` y
  `depth` (1–50) y `ks` (enteros positivos, sin repetir, ninguno mayor que `depth`) además de los tres parámetros del asistente
- [X] T044 [US2] Endurecer `EvalRunValidator` con las reglas de T043. Hace pasar T043 sin romper T016
- [X] T045 [P] [US2] `EVT/test_store_settings.py`: la respuesta de `GET /api/eval/settings` se convierte en un `AssistantSettings`
  (error claro si falta un campo); **mezcla de prompts**: sin `--prompt` todos salen con la versión del servidor y `pinned=false`;
  con `--prompt chat-system=3` ese sale con `version=3`, `pinned=true` y los demás igual; `settings_drift(inicio, fin,
  fijados)` devuelve vacío si solo cambian prompts **fijados** y devuelve el nombre de cada diferencia si cambia un prompt no
  fijado o cualquier parámetro de recuperación; la carga de calidad incluye `judge` (`model`, `underlying_model`, `temperature`,
  `seed`, `max_input_chars`) y `evaluator` (`library` = `ragas`, `library_version`, `judgment_instructions_version`) y la de
  recuperación no; `assistant_model` es `null` cuando no se indicó (FR-008)
- [X] T046 [US2] Implementar en `EV/store_payload.py` el `AssistantSettings`, la mezcla de prompts, `settings_drift` y los bloques
  `judge` y `evaluator` (el valor de `judgment_instructions_version` es `RunInfo.abstention_prompt_version`, la columna del CSV de
  LAB-29; si LAB-29 lo nombra distinto, seguir su columna), hacer obligatoria la instantánea en los constructores de carga y
  añadir `EvalStoreClient.settings()` en `EV/store_client.py`. Hace pasar T045 y mantiene T029 (actualizar sus datos de entrada)
- [X] T047 [P] [US2] Ampliar `EVT/test_store_cli.py` (parte US2): con guardado activo, la instantánea se lee **antes** de pedir nada
  al asistente o al juez y de nuevo **al terminar**; si cambió un prompt no fijado, `quality` y `retrieval` terminan con código 1,
  el mensaje dice «la configuración del asistente cambió durante la medición» y **no** se escriben CSV ni se envía nada; si solo
  cambió un prompt fijado con `--prompt`, se guarda; la carga enviada lleva los prompts mezclados y la `retrieval_config` con los
  parámetros del asistente (y `depth`/`ks` en recuperación)
- [X] T048 [US2] Cablear la lectura inicial y final de la instantánea y la deriva en `EV/__main__.py`. Hace pasar T047

**Checkpoint**: `./mvnw -B -ntp test -Dtest='AssistantSettings*,ReadAssistantSettings*,EvalSettings*,EvalRunValidatorTests,ModularityTests'`
y `uv run pytest -k store` en verde; V5, V6 y V12 a mano.

---

## Phase 5: User Story 3 — Comparar ejecuciones y ver qué casos cambiaron (P1)

**Meta**: consultas SQL documentadas, probadas contra PostgreSQL, para listar, resumir, ver la evolución, comparar caso a caso y
buscar por prompt, versión o modelo (FR-016, FR-017).

**Prueba independiente** (V7, V8): con dos ejecuciones guardadas (una con otro prompt y un caso editado) las consultas dan la lista
de casos que empeoraron, marcan el caso editado como no comparable y avisan de los parámetros distintos.

- [X] T049 [US3] `TEST/eval/EvalHistoryQueriesTests.java` (`@SpringBootTest`, `@PostgresTest`): siembra tres ejecuciones con el
  adaptador (A y B de calidad que difieren en el prompt `chat-system` y el modelo del asistente, con un caso editado en B; C de
  recuperación) y ejecuta cada archivo de `../eval/queries/*.sql` sustituyendo los parámetros `:'nombre'` de estilo `psql` por
  literales. Comprueba: el listado va por fecha descendente con tipo, prompts, modelo y media del conjunto completo; el resumen
  de una ejecución reproduce media y `n` globales y por categoría; la evolución de `faithfulness` sale en orden cronológico; la
  comparación da el valor de cada ejecución y su diferencia por caso, marca `comparable = false` en el caso editado y lista como
  advertencia cada parámetro distinto (juez, modelo del asistente, embeddings, `retrieval_config`, huella del dataset); la
  consulta de regresiones devuelve exactamente los casos cuya `faithfulness` bajó más del umbral dado; la búsqueda por
  prompt+versión y por modelo devuelve A o B según corresponda y no C
- [X] T050 [US3] Crear `eval/queries/` con un archivo por pregunta, cada uno con una cabecera que dice qué responde, sus parámetros
  y cómo ejecutarlo (`psql "$DATABASE" -v run_id=... -f ...`): `01-list-runs.sql`, `02-run-parameters.sql`,
  `03-run-summary.sql`, `04-metric-evolution.sql`, `05-compare-runs.sql`, `06-compare-parameters.sql`,
  `07-regressions.sql`, `08-find-by-prompt.sql`, `09-find-by-model.sql`. Solo lectura (`SELECT`), parámetros con la sintaxis
  `:'nombre'`, y el promedio ignora los `null` (`(scores->>'faithfulness')::numeric`).
  Hace pasar T049
- [X] T051 [P] [US3] Sección «Leer el historial» de `eval/README.md`: una entrada por consulta de T050 con un ejemplo de salida, cómo
  leer `comparable`, las advertencias y qué significa un valor vacío; cómo conectarse desde el IDE o `psql` al Postgres local
  (sin credenciales en la herramienta: es otra conexión, la de quien consulta)

**Checkpoint**: `./mvnw -B -ntp test -Dtest=EvalHistoryQueriesTests` en verde; V7 y V8 a mano.

---

## Phase 6: User Story 4 — Una ejecución se guarda entera o no se guarda, y nunca por duplicado (P2)

**Meta**: preparación explícita y sin sorpresas: guardar es opcional, se comprueba todo **antes** de gastar una medición, y un
fallo nunca deja algo a medias ni cambia el comportamiento de quien no guarda.

**Prueba independiente** (V9–V11, V13–V15): identificador repetido, backend caído, medición que falla, guardado que falla, sin
token y token malo; en ninguno queda una ejecución parcial o duplicada.

- [X] T052 [P] [US4] Pruebas del estado de una ejecución: `TEST/eval/application/usecase/FindEvalRunUseCaseTests.java` (guardián
  primero; `runId` que no cumple el patrón → `EvalRunInvalidException`; ausente → `EvalRunNotFoundException`; presente → resumen),
  casos `find` en `JdbcEvalRunStoreAdapterTests` (presente, ausente, base caída → `EvalStoreUnavailableException`) y casos
  `GET /api/eval/runs/{runId}` en `EvalRunControllerTests`: 200 con `run_id`, `kind`, `started_at`, `recorded_at`; 404
  `SLHD_EVAL_00004`; 403; 422 por patrón; 503
- [X] T053 [US4] Implementar `FindEvalRunUseCase`, `JdbcEvalRunStoreAdapter.find` y el `@GetMapping("/{runId}")` de
  `EvalRunController` (sin lógica en el controlador). Hace pasar T052
- [X] T054 [P] [US4] Ampliar `EVT/test_store_cli.py` (parte US4): **activación** (sin opción y con `SHOPLAB_EVAL_TOKEN` guarda; sin
  token no guarda y la salida dice «no se guardó nada» con los mismos CSV y el mismo código que antes de la historia; `--save`
  sin token → error de uso, código 2, **antes de medir**; `--no-save` con token no hace ninguna petición a `/api/eval`);
  **orden** (con el servidor falso que registra peticiones: la instantánea y el estado del `run_id` se piden antes de cualquier
  petición al chat, a la búsqueda o al juez; el `POST` es posterior a la escritura de los CSV); **fallos** (backend caído →
  error que nombra el backend, antes de consultar al asistente o al juez, sin CSV; `run_id` ya guardado → error antes de medir,
  código 1, sin CSV; medición que falla a mitad → no hay `POST`, ni CSV; token malo → error de 403 con la explicación; guardado
  que falla al final → CSV completos, código 1 y mensaje que dice que no quedó nada en la base)
- [X] T055 [US4] Implementar en `EV/store_client.py` `run_exists(run_id)` (200 → `True`, 404 → `False`, resto → `StoreError`) y en
  `EV/__main__.py` la configuración `_store_config(args, env)` (misma convención que `_langfuse_config`: automático con token,
  `--save` lo exige, `--no-save` lo evita), la comprobación previa (instantánea + `run_exists`) **antes** de consultar al
  asistente o al juez, los mensajes en español y los códigos de salida 0/1/2 del contrato. Hace pasar T054
- [X] T056 [US4] Regresión de SC-006: ejecutar **todas** las pruebas existentes de `eval/` (`cd eval && uv run pytest && uv run
  ruff check .`) y comprobar que `test_quality_cli.py`, `test_retrieval_cli.py` y `test_summary_cli.py` pasan **sin modificar sus
  expectativas** (solo se permite ampliar fixtures); si alguna cambia, es una desviación que hay que justificar en
  `validation.md`

**Checkpoint**: `uv run pytest` y `./mvnw -B -ntp test -Dtest='Eval*'` en verde; V9–V11 y V13–V15 a mano.

---

## Phase 7: Infraestructura de DEV (FR-021; repositorio `shoplab-infra`)

**Meta**: que el token llegue al backend del clúster de desarrollo y a `make quality`, sin pasos manuales. Rama y PR **propios** del
repositorio `shoplab-infra`, que debe fusionarse **antes** del release que despliega el módulo `eval`. Se puede hacer en paralelo con
las Fases 3 a 6 (solo necesita el nombre de la variable, T010).

- [X] T057 [US1] Preparar el repositorio de infra sin tocar el trabajo pendiente: hoy `../shoplab-infra` está en
  `fix/LAB-29-dev-langfuse-tunnel-ollama-check` con `scripts/up.sh` modificado sin confirmar, que **no es de esta historia**.
  Ejecutar `git -C ../shoplab-infra fetch origin` y crear un árbol de trabajo aparte:
  `git -C ../shoplab-infra worktree add ../shoplab-infra-lab31 -b feature/LAB-31-eval-token origin/main`. Todas las rutas `INFRA`
  de las tareas siguientes son `../shoplab-infra-lab31`. No hacer `stash` ni `checkout` en el árbol original
- [X] T058 [P] [US1] `INFRA/scripts/tests/ensure-secrets.test.sh`: casos nuevos con el estilo de los existentes: añade la entrada
  `eval` con `EVAL_PERSISTENCE_TOKEN` cuando falta; **no cambia** un valor `eval` que ya existe; el valor es de 64 hexadecimales; no
  imprime el valor (solo `eval.EVAL_PERSISTENCE_TOKEN`); repetirlo no cambia nada; con `TF_VAR_secrets` sin la clave **avisa y no
  falla** (el guardado es opcional) mientras que sí falla si faltan las de langfuse o litellm como hoy
- [X] T059 [US1] `INFRA/scripts/ensure-secrets.sh`: añadir `EVAL_KEYS=(EVAL_PERSISTENCE_TOKEN)`, `re_ev` (`^[[:space:]]*eval[[:space:]]*=`)
  y el mismo tratamiento que ya reciben `langfuse` y `litellm` (entrada nueva si falta, ampliación si falta la clave);
  `value_for EVAL_PERSISTENCE_TOKEN` → `rand_hex 32`; en modo `TF_VAR_secrets` solo avisa. Actualizar el comentario de cabecera.
  Hace pasar T058
- [X] T060 [P] [US1] `INFRA/terraform/envs/dev/secrets.auto.tfvars.example`: añadir
  `eval = "{\"EVAL_PERSISTENCE_TOKEN\":\"CHANGE_ME\"}"` con un comentario (LAB-31: token con el que `eval/` guarda ejecuciones en el
  backend; `make secrets` lo genera). **No** tocar `required_secrets` de `terraform/modules/secrets/variables.tf` (opcional);
  comprobar que `terraform/envs/dev/tests/apply.tftest.hcl` sigue pasando sin la entrada
- [X] T061 [P] [US1] `INFRA/k8s/base/backend/externalsecret-eval.yaml` (`ExternalSecret` `backend-eval`, mismo formato y
  comentario que `externalsecret-langfuse.yaml`: `secretKey: SHOPLAB_EVAL_PERSISTENCE_TOKEN`, `remoteRef.key:
  /shoplab/ENVIRONMENT/eval`, `property: EVAL_PERSISTENCE_TOKEN`), añadirlo a `k8s/base/backend/kustomization.yaml` y a
  `envFrom` de `k8s/base/backend/deployment.yaml` como `secretRef: {name: backend-eval, optional: true}` (el backend arranca
  aunque el secreto no exista; entonces `POST /api/eval/runs` responde 403)
- [X] T062 [US1] `INFRA/scripts/quality.sh`: leer `SHOPLAB_EVAL_PERSISTENCE_TOKEN` del Secret `backend-eval` con el mismo
  `secret_value` que ya usa para `LITELLM_KEY_EVAL`; si existe, exportar `SHOPLAB_EVAL_TOKEN` y `SHOPLAB_EVAL_ORIGIN=dev` (sin
  imprimir el valor); si no existe, **avisar y medir sin guardar**, nunca abortar. Actualizar el comentario de cabecera y la
  nota de que `ARGS="--no-save"` lo evita
- [X] T063 [P] [US1] `INFRA/docs/gateway-de-modelos.md`: sección «Historial de evaluación (LAB-31)» con el secreto
  `/shoplab/dev/eval`, el `ExternalSecret backend-eval`, qué hace `make quality` con el token, cómo comprobar que el Secret existe
  (`kubectl -n shoplab-dev get secret backend-eval`) y cómo apagar el guardado (borrar el secreto o `--no-save`)
- [X] T064 [US1] Verificar en `INFRA`: `bash scripts/tests/ensure-secrets.test.sh`, `make check` (o, si faltan herramientas, `make
  kustomize policies test-ci-scripts`) y `kustomize build k8s/overlays/dev` mostrando `backend-eval` en el `envFrom` del backend
  con `optional: true`. Con el clúster local, `make up` deja el Secret `backend-eval` creado (V17, V18, V19; la verificación
  contra el backend desplegado espera al release)
- [X] T065 [US1] Abrir el PR de `shoplab-infra` con la skill `commit-and-push` en ese repositorio (rama
  `feature/LAB-31-eval-token`, título `feat(eval): [LAB-31] token de persistencia de evaluación`), enlazando el PR de
  `shoplab-helpdesk`. Anotar en `validation.md` el orden de fusión: infra **primero**
  _Hecho: PR abierto en https://github.com/leandroAmariles/shoplab-infra/pull/33 (commit `6646cd0`, rama `feature/LAB-31-eval-token`)._

---

## Phase 8: Polish y transversales

- [X] T066 [P] Escribir `docs/adr/0028-persistencia-de-ejecuciones-de-evaluacion.md` (o el número de T001) con la plantilla
  `docs/adr/0000-plantilla.md`: contexto (LAB-31, ADR-0015/0017/0024), decisión (módulo `eval` dueño del esquema; una petición,
  una transacción; revisión de caso con hash calculado por el backend; `jsonb` para puntuaciones; instantánea del asistente leída
  al inicio y al final; token apagado por defecto) y alternativas descartadas de `research.md` (R1–R7); añadir su fila a
  `docs/adr/README.md`
- [X] T067 [P] `docs/modulos.md`: fila `eval` en la tabla de módulos (esquema `eval`, migraciones `db/migration/eval`) y sección
  «Datos de `eval`» (las tres tablas, quién las escribe y que `eval/` no tiene credenciales de base de datos)
- [X] T068 Regenerar la documentación de módulos: `cd backend && ./mvnw test -Dtest=ModularityTests` y confirmar los cambios en
  `docs/modulos/` (aparece `module-eval.adoc` y su `.puml`; `components.puml` y `all-docs.adoc` se actualizan). CI falla si no se
  commitea
- [X] T069 [P] `eval/README.md` (completar): fila nueva de la tabla de archivos (`store_*.py`, `queries/`), sección «Guardar las
  ejecuciones» (qué se guarda y qué **no**: sin texto de respuestas ni de fragmentos; cómo activarlo: `SHOPLAB_EVAL_PERSISTENCE_TOKEN` en el
  backend y `SHOPLAB_EVAL_TOKEN` en el entorno de quien mide, `--save`/`--no-save`; `--run-id`; `SHOPLAB_EVAL_ORIGIN`; que el
  `commit_sha` es el de `eval/` y no el del backend medido; que la medición de recuperación guarda `prompts = []`), tabla de
  errores con la causa y el arreglo (403, 409, 422, 503, «la configuración del asistente cambió», «ejecución sin guardar» con
  código 1), y la nota de que el dataset sigue en borrador hasta la revisión humana (`eval/REVIEW.md`). Actualizar el enlace a la
  spec y al ADR
- [X] T070 [P] Generar `specs/LAB-31-eval-run-persistence/requests.http` (regla «HTTP test requests»; sintaxis IntelliJ/REST Client;
  `baseUrl` = `http://localhost:` + `server.port` de `application.yml`, `token` como marcador `change-me-local-only`; sin secretos
  reales): una petición por escenario del contrato, en orden de ejecución y con el origen anotado — `GET settings` (200) → `GET
  runs/demo-1` (404) → `POST runs` de calidad (201) → `GET runs/demo-1` (200) → `POST` otra vez (409) → `POST` de recuperación
  (201) → `POST` con una métrica fuera de 0–1 (422 con `detail`) → `POST` con una clave `answer` (400) → `GET runs/malo id` (422)
  → `POST` sin la cabecera (403). Comentario inicial «No cubierto aquí»: la herramienta Python y los escenarios de infra. Terminar la
  respuesta de implementación con la ruta del archivo y el número de peticiones
- [X] T071 Verificación del backend: `cd backend && ./mvnw -B -ntp clean verify` (Checkstyle, PMD, Modulith, JaCoCo ≥ 80 %); corregir lo
  que salga sin relajar ninguna regla. Comprobar a mano `git status --short` y `git diff --staged` buscando archivos sueltos
- [X] T072 Verificación de la herramienta: `cd eval && uv sync --frozen && uv run pytest && uv run ruff check .` (cobertura ≥ 80 %
  del propio `pyproject.toml`)
- [ ] T073 Validación manual de `quickstart.md` V1–V19 con el stack real (Ollama, gateway, Langfuse, backend local con token) y registrar
  cada resultado en `specs/LAB-31-eval-run-persistence/validation.md` con fecha, comando y evidencia sin secretos. Lo que no pueda
  probarse todavía (V17 y V19 en el clúster: esperan al release que incluya el módulo) se deja anotado como **pendiente**, no como hecho
  _Parcial: validado de punta a punta contra el backend real y PostgreSQL con el dataset real (ver `validation.md`); faltan los escenarios que necesitan Ollama, el gateway, Langfuse y el clúster (V1, V2, V4–V6, V12, V17, V19)._
- [ ] T074 Abrir el PR de `shoplab-helpdesk` con la skill `commit-and-push`: título `feat(eval): [LAB-31] persistencia de las
  ejecuciones de evaluación`, descripción con el resumen, el enlace al PR de infra (T065) y el orden de fusión; la casilla «La
  evaluación RAG no empeora» no aplica (no cambia el asistente ni las métricas). **No cerrar LAB-31 en Jira** hasta validar en
  desarrollo el primer tag `vX.Y.Z` que incluya el módulo (V17, V19)
  _No hecha: commit, push y PR solo cuando lo pidas._

---

## Dependencias y orden de ejecución

### Entre fases

- **Fase 1** → **Fase 2** (bloquea todo) → **Fases 3 a 6** → **Fase 8**. La **Fase 7** (infra) solo necesita T010 y puede ir en paralelo.
- **US1** (Fase 3) es el MVP y es independiente de las demás; entrega «guardar» con los parámetros que la herramienta conoce.
- **US2** (Fase 4) necesita US1 (extiende la carga, el validador y el CLI); sin ella el historial no puede decir qué versión de
  prompt se usó, así que **el criterio de aceptación de Jira solo se cumple con US1 + US2**.
- **US3** (Fase 5) necesita el adaptador de US1 y, para sembrar parámetros reales, US2.
- **US4** (Fase 6) necesita US1; sus tareas de CLI (T054–T055) modifican los mismos archivos que T035 y T048, así que van **después**.
- **Fase 7** no depende de las historias; su PR se fusiona **antes** del release.

### Dentro de cada fase

- Prueba antes que implementación (T012→T013, T014→T015, T016→T017, T018→T019, T020→T021, T022→T023, T025→T026, T029→T030, T032→T033,
  T034→T035, T037→T038, T039→T040, T041→T042, T043→T044, T045→T046, T047→T048, T049→T050, T052→T053, T054→T055, T058→T059).
- T009 depende de T008 (`PublicDetail`); T023 depende de T015; T026 depende de T021 y T024; T031 antes de T032 y T034.

### Oportunidades de paralelismo

- Fase 2: T004–T008, T010, T011, T012 y T014 son archivos distintos y se hacen a la vez.
- Fase 3 backend: T016, T018, T020, T024, T027, T028 en paralelo; la **herramienta** (T029–T035) corre en paralelo con todo el backend
  de la fase porque solo comparte el contrato.
- Fase 4: T036/T037/T039/T041 en paralelo; la parte Python (T045–T048) en paralelo con la del backend.
- Fase 7 completa en paralelo con las Fases 3–6 (otro repositorio).

### Ejemplo de ejecución en paralelo (Fase 3)

```text
Persona A (backend):  T016 → T017, T018 → T019, T020 → T021, T022 → T023, T024, T025 → T026, T027, T028
Persona B (eval/):    T029 → T030, T031, T032 → T033, T034 → T035
Persona C (infra):    T057, T058 → T059, T060, T061, T062, T063, T064, T065
```

## Estrategia de implementación

1. **MVP** = Fases 1, 2 y 3: ya se pueden guardar las ejecuciones y comprobar V1–V4 y V16. No se anuncia como terminada la historia.
2. **Incremento 2** = Fase 4 (US2): cumple el segundo criterio de aceptación de Jira (versión de prompt, modelo y configuración
   de recuperación). Con US1 + US2 el esquema y el guardado están completos.
3. **Incremento 3** = Fases 5 y 6 (US3 y US4): historial consultable y garantías de fiabilidad; puede entregarse en el mismo PR.
4. **Infra** (Fase 7) se fusiona primero, **Polish** (Fase 8) cierra, y la validación en desarrollo espera al primer release.
5. Riesgo conocido: el commit que se guarda es el de `eval/`, no el del backend medido; la deriva solo se detecta para lo que `chat`
   publica (prompts y parámetros de recuperación). Ambos están documentados en `plan.md` y en T069.

## Notas

- Cada tarea cita el archivo exacto; las restricciones de `data-model.md` están copiadas en T015, T016 y T043 para que no queden a
  criterio de quien implemente.
- No hay tarea que toque `ModuleSchemaMigrator`, `PromptCatalogPort`, el cálculo de métricas ni el dataset (FR-018).
- Si una prueba de otra historia ya existente falla por esta historia, es un fallo de esta historia: se arregla, no se relaja.
