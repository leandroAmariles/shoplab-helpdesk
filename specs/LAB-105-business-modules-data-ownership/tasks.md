# Tasks: Módulos por negocio con dueño de datos y reglas verificadas

**Input**: Design documents from `/specs/LAB-105-business-modules-data-ownership/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: se piden (constitución, principio IV; FR-005, FR-007 y FR-008). Cada prueba nueva se escribe antes del código
que la satisface y se comprueba que falla. Toda regla ejecutable lleva su prueba negativa (fixture de violación).

**Organization**: tareas agrupadas por historia de usuario en orden de prioridad: **US1 (P1) → US2 (P1) → US3 (P1) →
US4 (P2) → US5 (P2)**. La fase 2 deja la base de datos cableada sin romper el arranque ni las pruebas actuales.

Abreviaturas de rutas (relativas a la raíz del repositorio):

- `MAIN` = `backend/src/main/java/com/shoplab/helpdesk`
- `TEST` = `backend/src/test/java/com/shoplab/helpdesk`
- `RES` = `backend/src/main/resources`
- `TRES` = `backend/src/test/resources`
- `SPEC` = `specs/LAB-105-business-modules-data-ownership`

Decisiones y riesgos: [research.md](research.md). Contratos: [module-structure](contracts/module-structure.md),
[schema-isolation](contracts/schema-isolation.md), [architecture-rules](contracts/architecture-rules.md).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de `spec.md` a la que pertenece (US1…US5)
- Identificadores de código en inglés, líneas ≤ 121 caracteres, llaves en todos los bloques (Checkstyle/PMD).
- Los comandos Maven se ejecutan en `backend/` con `./mvnw -B -ntp ...`.
- Los módulos de negocio son `chat`, `retrieval`, `orders`, `catalog` y `tickets`.

## Phase 1: Setup

**Purpose**: línea base y dependencias. Sin cambios de comportamiento.

- [X] T001 Ejecutar `./mvnw -B -ntp clean verify` sin cambios y anotar resultado y cobertura de líneas JaCoCo como «Línea base» en `SPEC/validation.md` (créalo)
- [X] T002 Añadir a `backend/pom.xml` las dependencias `spring-boot-starter-jdbc`, `spring-boot-starter-flyway`, `org.flywaydb:flyway-database-postgresql`, `org.postgresql:postgresql` (runtime) y, en scope test, `spring-boot-testcontainers` y `org.testcontainers:testcontainers-postgresql`; si algún artefacto no existe con ese nombre en Boot 4.1.2, ajustar y anotar el nombre real en `SPEC/research.md` §D-9; comprobar que `./mvnw -B -ntp -DskipTests compile` pasa
- [X] T002a [P] Verificar en `../shoplab-infra` (solo lectura) que en dev: el backend recibe `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD` (`k8s/base/backend/kustomization.yaml`, `externalsecret.yaml`), existe `k8s/base/postgres` con la imagen pgvector y no hay NetworkPolicy que bloquee backend → postgres; y en `infra/docker-compose.yml` de este repositorio que el servicio `postgres` arranca con la base `shoplab` y el usuario por defecto que usa `application.yml` (T004). Anotar el resultado y cualquier hueco en `SPEC/validation.md`: si hay hueco, se corrige en T045a

---

## Phase 2: Foundational (bloquea todas las historias)

**Purpose**: base de datos obligatoria (aclaración 2026-10-27, research D-4): que el backend y las pruebas actuales funcionen con Postgres, y que existan `ModuleSchema` y el migrador que usan US1–US2.

**⚠️ CRITICAL**: ninguna historia empieza hasta cerrar esta fase.

- [X] T003 [P] Crear `TEST/support/PostgresTest.java` (meta-anotación para `@SpringBootTest`) y `TEST/support/SharedPostgres.java`: contenedor `pgvector/pgvector:0.8.7-pg16` (`asCompatibleSubstituteFor("postgres")`) **único por JVM de pruebas** (campo `static`, arranque perezoso) cuyas URL, usuario y contraseña se inyectan con `@DynamicPropertySource`; no usar un contenedor por contexto
- [X] T004 Editar `RES/application.yml`: añadir `spring.datasource.url/username/password` con valores por defecto de la Postgres local (`jdbc:postgresql://localhost:5432/shoplab`, usuario `shoplab`, contraseña por variable `POSTGRES_PASSWORD` con el valor local-only de `infra/docker-compose.yml`, nunca otro secreto) y `spring.flyway.enabled: false` (el migrador por módulo sustituye al autoconfigurado); no añadir interruptores para desactivar la BD
- [X] T005 Localizar con `grep -rl "@SpringBootTest" backend/src/test` todas las pruebas que arrancan contexto y añadirles `@PostgresTest`; ejecutar `./mvnw -B -ntp test` (requiere Docker) y comprobar que sigue todo en verde (incluidas las pruebas BlockHound y `MetricsEndpointTests`); con BlockHound activo el contexto debe arrancar con la conexión y el pool JDBC sin reportes de bloqueo en hilos del bucle de eventos (FR-014); si hubiera una exclusión legítima, debe ser explícita, mínima y justificada en `TEST/blockhound/HelpdeskBlockHoundIntegration.java`
- [X] T006 [P] Escribir `TEST/shared/infrastructure/out/database/ModuleSchemaMigratorFailureTests.java` (falla): con un `DataSource` hacia un puerto cerrado, `migrate()` lanza `ModuleMigrationException` (código `DATABASE_UNAVAILABLE`) cuyo mensaje nombra el primer módulo afectado; con una migración inválida (fixture en `TRES/schema/broken/`) lanza `ModuleMigrationException` (código `DATABASE_MIGRATION_FAILED`) cuyo mensaje nombra ese módulo y conserva la causa; no deja el arranque a medias
- [X] T007 [P] Escribir `TEST/shared/infrastructure/out/database/ModuleSchemaTests.java` (falla): `ModuleSchema` acepta `^[a-z][a-z0-9_]*$`, rechaza `public`, vacío, mayúsculas y guiones; `location()` devuelve `classpath:db/migration/<name>`
- [X] T008 Crear `MAIN/shared/infrastructure/out/database/ModuleSchema.java` (record con `name`; método `location()` que devuelve `classpath:db/migration/<name>`; constructor compacto que valida la regla de `data-model.md` lanzando `IllegalArgumentException`: es un error de programación del cableado, no un error de negocio ni de infraestructura, y nunca se traduce a HTTP) hasta que T007 pase
- [X] T009 [P] Escribir `TEST/shared/infrastructure/out/database/ModuleSchemaMigratorTests.java` (falla): `orderedSchemas()` devuelve los módulos en orden alfabético, falla con mensaje que nombra el módulo si hay nombres duplicados y no hace nada con lista vacía
- [X] T010a Añadir a `MAIN/shared/exception/ResponseCodeEnum.java` los códigos `DATABASE_UNAVAILABLE("SLHD_DBAS_00001", HttpStatus.SERVICE_UNAVAILABLE, "The database is not available")` y `DATABASE_MIGRATION_FAILED("SLHD_DBAS_00002", HttpStatus.INTERNAL_SERVER_ERROR, "A module schema migration failed")` (formato `<SERVICE>_XXXX_NNNNN`), y crear `MAIN/shared/exception/ModuleMigrationException.java` (subclase de `TechnicalException`, campo `module`, mensaje que incluye el módulo; leer `HelpdeskException` y, si su constructor no admite mensaje propio, añadir la sobrecarga mínima) más el método `ExceptionUtil.moduleMigration(String module, ResponseCodeEnum code, Throwable cause)`; realizar esta tarea **antes** de T006 y T010 (los paquetes `exception` están excluidos de JaCoCo y no llevan lógica)
- [X] T010 Crear `MAIN/shared/infrastructure/out/database/ModuleSchemaMigrator.java` con `migrate()` que por cada `ModuleSchema` (orden alfabético) ejecuta `Flyway.configure().dataSource(ds).schemas(name).defaultSchema(name).createSchemas(true).locations(location).load().migrate()` según research D-2, registrando en log el módulo y el nº de migraciones (sin datos de negocio) y capturando cualquier fallo de cada módulo para relanzarlo como `ModuleMigrationException` (T010a) que nombra el módulo; hasta que T009 y T006 pasen
- [X] T011 Crear `MAIN/shared/config/DatabaseConfig.java`: bean `ModuleSchemaMigrator` con `@Bean(initMethod = "migrate")`, recibiendo `List<ModuleSchema>` y el `DataSource`, sin condiciones; comentar en Javadoc que los beans con acceso a datos futuros deben declarar `@DependsOn` del migrador

**Checkpoint**: `./mvnw -B -ntp test` en verde con Postgres desechable; el migrador existe pero aún no tiene módulos que migrar.

---

## Phase 3: User Story 1 - Mapa de módulos por contexto de negocio (Priority: P1) 🎯 MVP

**Goal**: módulos `chat`, `retrieval`, `orders`, `catalog`, `tickets` (+ `shared`), sin `tools`; las herramientas se publican en el `api` de cada módulo y `chat` solo las compone.

**Independent Test**: `./mvnw -B -ntp test -Dtest='ModularityTests,ToolCallbackComposerTests,ChatStreamContractTests'` en verde (módulos esperados, composición de herramientas y chat sin cambios).

### Tests for User Story 1 (escribir primero, deben fallar)

- [X] T012 [US1] Editar `TEST/ModularityTests.java`: `EXPECTED_MODULES = Set.of("chat", "retrieval", "orders", "catalog", "tickets", "shared")` y añadir una prueba que afirme que ningún módulo se llama `tools`; ejecutar y comprobar que falla por `orders`/`catalog`/`tools`
- [X] T013 [P] [US1] Crear `TEST/chat/infrastructure/out/ollama/ToolCallbackComposerTests.java` (falla): con dos `ToolCallbackProvider` de prueba devuelve la unión ordenada de sus callbacks sin conocer su módulo de origen; sin proveedores devuelve lista vacía; un proveedor sin callbacks no aporta nada

### Implementation for User Story 1

- [X] T014 [US1] Eliminar `MAIN/tools/package-info.java` y comprobar con `grep -rn "helpdesk.tools" backend/src` que nada lo referencia
- [X] T015 [P] [US1] Crear `MAIN/orders/package-info.java` y `MAIN/catalog/package-info.java` con `@ApplicationModule(displayName = "Orders")` / `"Catalog"` siguiendo el patrón de `MAIN/chat/package-info.java`
- [X] T016 [P] [US1] Para cada módulo de negocio crear `MAIN/<modulo>/api/package-info.java` con `@NamedInterface("api")` (Modulith) y, donde falten (`retrieval`, `tickets`, `orders`, `catalog`; `chat` ya los tiene), `package-info.java` con Javadoc de una línea en `domain`, `application` e `infrastructure`, para que la estructura de capas sea visible y las reglas D3 la reconozcan
- [X] T017 [US1] Crear `MAIN/chat/infrastructure/out/ollama/ToolCallbackComposer.java` que recibe `ObjectProvider<ToolCallbackProvider>` y expone `List<ToolCallback> compose()` (`orderedStream()` + `getToolCallbacks()`), hasta que T013 pase
- [X] T018 [US1] Leer `MAIN/chat/infrastructure/out/ollama/OllamaChatAdapter.java` y conectar `ToolCallbackComposer` para que las herramientas compuestas se pasen al `ChatClient` **solo si la lista no está vacía**; con la lista vacía el comportamiento debe ser idéntico al actual
- [X] T019 [P] [US1] Crear `TEST/chat/ToolCompositionIntegrationTests.java` (`@SpringBootTest` + `@PostgresTest` + `@TestConfiguration` que declara un `ToolCallbackProvider` de un «módulo» ficticio): el contexto compone esa herramienta en `chat` sin ninguna dependencia de código hacia el módulo ficticio (US1/3)
- [X] T020 [US1] Ejecutar `./mvnw -B -ntp test -Dtest='ModularityTests,ToolCallbackComposerTests,ToolCompositionIntegrationTests,ChatStreamContractTests'` y comprobar que pasan (el golden del chat valida que `POST /api/chat` no cambió, FR-012)

**Checkpoint**: US1 entregable por sí sola (mapa de módulos y composición de herramientas).

---

## Phase 4: User Story 2 - Cada módulo es dueño de sus datos (Priority: P1)

**Goal**: un esquema de Postgres por módulo, migraciones en `db/migration/<modulo>`, sin FKs ni referencias cruzadas.

**Independent Test**: `./mvnw -B -ntp test -Dtest='SchemaIsolationTests,ModuleSchemasIntegrationTests'`; contra una BD vacía quedan 5 esquemas con su historial y 0 FKs cruzadas.

### Tests for User Story 2 (escribir primero, deben fallar)

- [X] T021 [P] [US2] Crear fixtures SQL en `TRES/schema/violations/`: `cross-schema-fk.sql` (S1: `REFERENCES orders.order_ref(id)` dentro de `catalog`), `qualified-foreign-name.sql` (S2: `SELECT … FROM tickets.ticket` en `chat`) y `foreign-search-path.sql` (S3: `SET search_path TO orders` y `CREATE SCHEMA other` en `chat`)
- [X] T022 [US2] Crear `TEST/schema/SchemaIsolationTests.java` (falla): (a) cada fixture de T021 hace fallar el lint nombrando archivo y regla S1/S2/S3; (b) el SQL real de `RES/db/migration` y de `MAIN` pasa el lint
- [X] T023 [P] [US2] Crear `TEST/schema/ModuleSchemasIntegrationTests.java` (falla): `@SpringBootTest` + `@PostgresTest` (contenedor compartido de T003); afirma (S4) que existen los esquemas `chat`, `retrieval`, `orders`, `catalog`, `tickets` y que cada uno tiene `flyway_schema_history` con la versión `1` aplicada; afirma (S5) que la consulta de `contracts/schema-isolation.md` devuelve 0 filas; prueba negativa: crea dos esquemas temporales con una FK cruzada y exige que esa misma consulta devuelva 1 fila

### Implementation for User Story 2

- [X] T024 [US2] Crear `TEST/schema/SchemaIsolationRules.java` (lint S1–S3 sobre los archivos `.sql` bajo `db/migration/<modulo>` y sobre `.sql` y bloques de texto `"""` de `MAIN/<modulo>`; rechaza referencias al nombre de cualquier otro esquema de módulo y `SET search_path`/`CREATE SCHEMA`/`SET SCHEMA`) hasta que T022 pase
- [X] T025 [P] [US2] Crear `RES/db/migration/chat/V1__baseline.sql`, `RES/db/migration/retrieval/V1__baseline.sql`, `RES/db/migration/orders/V1__baseline.sql`, `RES/db/migration/catalog/V1__baseline.sql` y `RES/db/migration/tickets/V1__baseline.sql`, cada uno solo con un comentario que declara el esquema y su módulo dueño (sin tablas)
- [X] T026 [P] [US2] Crear `MAIN/chat/config/ChatSchemaConfig.java`, `MAIN/retrieval/config/RetrievalSchemaConfig.java`, `MAIN/orders/config/OrdersSchemaConfig.java`, `MAIN/catalog/config/CatalogSchemaConfig.java` y `MAIN/tickets/config/TicketsSchemaConfig.java`, cada una con un `@Bean ModuleSchema` con el nombre de su módulo (crear los paquetes `config` que falten)
- [X] T027 [US2] Ejecutar `./mvnw -B -ntp test -Dtest='SchemaIsolationTests,ModuleSchemasIntegrationTests,ModuleSchemaMigratorTests'` (requiere Docker) y comprobar que pasan, incluidas las pruebas negativas
- [X] T028 [US2] Verificación manual del arranque real: `docker compose -f infra/docker-compose.yml up -d postgres`, arrancar el backend y comprobar `\dn` (5 esquemas: `chat`, `retrieval`, `orders`, `catalog`, `tickets`); luego detener Postgres, arrancar de nuevo y comprobar que falla con un error que nombra la causa; anotar resultados en `SPEC/validation.md`

**Checkpoint**: US1 y US2 funcionan de forma independiente.

---

## Phase 5: User Story 3 - Capas verificadas automáticamente (Priority: P1)

**Goal**: `domain` no importa Spring AI, Kafka, JPA ni Reactor; capas y ubicación de `@Tool` verificadas.

**Independent Test**: `./mvnw -B -ntp test -Dtest='ModuleArchitectureTests,ReactiveArchitectureTests'`: producción cumple D1–D5 y A1–A6 y cada regla nueva falla contra su fixture.

### Tests for User Story 3 (escribir primero, deben fallar)

- [X] T029 [P] [US3] Crear fixtures en `TEST/architecture/violations/` siguiendo el patrón de los existentes: `domain/SpringAiInDomain`, `domain/KafkaInDomain`, `domain/JpaInDomain`, `domain/ReactorInDomain`, `layers/DomainUsesInfrastructure`, `layers/ApplicationUsesInfrastructure`, `layers/ClassOutsideLayers`, `tools/ToolOutsideApi` y `modules/CrossModuleInternals`, en paquetes con forma `…violations.<modulo-ficticio>.<capa>` para casar con los patrones de `contracts/architecture-rules.md`
- [X] T030 [US3] Crear `TEST/architecture/ModuleArchitectureTests.java` (falla) con la estructura de `ReactiveArchitectureTests`: una prueba que exige que `PRODUCTION` cumple todas las reglas D1–D5 y una parametrizada que exige que cada regla falle contra `VIOLATIONS`

### Implementation for User Story 3

- [X] T031 [US3] Crear `TEST/architecture/ModuleArchitectureRules.java` con D1–D5 según `contracts/architecture-rules.md` (reglas estáticas con `.because(...)` y `allowEmptyShould(true)`; `shared` y la clase raíz exentos en D3) hasta que T030 pase
- [X] T032 [US3] Si T030 revela infracciones en producción (p. ej. `chat` accediendo a internals de otro módulo), corregirlas en `MAIN/` sin cambiar comportamiento; si la regla D3 marca `config` de `chat`, confirmar que `config` figura en la lista de paquetes permitidos
- [X] T033 [US3] Ejecutar `./mvnw -B -ntp test -Dtest='ModuleArchitectureTests,ReactiveArchitectureTests'` y comprobar el resultado completo, incluido que A1–A6 de LAB-111 siguen pasando (FR-008)

**Checkpoint**: las tres historias P1 completas.

---

## Phase 6: User Story 4 - Límites de módulo verificados y documentados (Priority: P2)

**Goal**: `verify()` en verde, documentación generada en `docs/modulos/` y detección de deriva en CI.

**Independent Test**: `./mvnw -B -ntp test -Dtest=ModularityTests` genera `docs/modulos/` y `git diff --exit-code -- docs/modulos` queda vacío en una segunda ejecución.

### Tests for User Story 4 (escribir primero, deben fallar)

- [X] T034 [P] [US4] Crear fixtures de Modulith en `TEST/modularity/fixtures/` (paquete raíz propio con una clase `@SpringBootApplication` ficticia y dos módulos donde uno accede a una clase interna del otro) y en `TEST/modularity/ModuleViolationsTests.java` afirmar que `ApplicationModules.of(<fixture>).verify()` lanza `Violations` (prueba negativa de US4/3)
- [X] T035 [US4] Añadir a `TEST/ModularityTests.java` la prueba `writesModuleDocumentation` que, tras `verify()`, ejecuta `new Documenter(modules, Documenter.Options.defaults().withOutputFolder(...)).writeDocumentation()` hacia `../docs/modulos` y afirma que existen el diagrama de componentes y una ficha por cada módulo de negocio; confirmar primero en la API de Modulith 2.1.2 el nombre exacto de la opción de carpeta de salida y anotarlo en `SPEC/research.md` §D-8

### Implementation for User Story 4

- [X] T036 [US4] Ejecutar `./mvnw -B -ntp test -Dtest='ModularityTests,ModuleViolationsTests'`, revisar que `docs/modulos/` contiene solo la salida esperada (diagrama de componentes y fichas de `chat`, `retrieval`, `orders`, `catalog`, `tickets`, `shared`) y volver a ejecutar para comprobar que la segunda pasada no produce diferencias (`git status --short docs/modulos`)
- [X] T037 [US4] Editar `.github/workflows/ci.yml`: tras el paso que ejecuta las pruebas del backend añadir un paso `git diff --exit-code -- docs/modulos` con un mensaje de error que indique ejecutar `./mvnw test -Dtest=ModularityTests` y commitear el resultado; respetar el estilo de pines y permisos del workflow existente
- [X] T038 [P] [US4] Crear `docs/modulos/README.md` (a mano, una pantalla) que explique qué contiene la carpeta, que está generada, cómo regenerarla y enlace al ADR 0015; asegurar que `ModularityTests` no lo sobrescribe ni lo borra

**Checkpoint**: documentación de módulos generada y vigilada.

---

## Phase 7: User Story 5 - Decisión de límites y repositorios registrada (Priority: P2)

**Goal**: ADR 0015 en el registro.

**Independent Test**: el ADR sigue la plantilla, figura en el índice y cubre los cuatro puntos de US5.

- [X] T039 [US5] Leer `docs/adr/0000-plantilla.md` y `docs/adr/0011-web-reactiva-persistencia-jdbc.md` y crear `docs/adr/0015-limites-de-contexto-y-repositorios.md` en español con el contenido de `SPEC/research.md` §D-10: un dueño por dato (los demás consultan por API o eventos), un repo por servicio desde F5, el monolito en `shoplab-helpdesk` hasta F6-01, `shoplab-contracts`/`shoplab-ci`/`shoplab-infra` como repositorios de soporte, qué se comparte (paquetes de contratos) y qué no (librerías con dominio), la convención única módulo = esquema = carpeta de migraciones (`db/migration/<modulo>`, historial `<modulo>.flyway_schema_history`, sin excepciones: FR-015), integridad por identificador sin FK, límite conocido del `JOIN` dinámico, roles por esquema diferidos a F5 y que dev ya dispone de Postgres y de las variables `SPRING_DATASOURCE_*` (verificado en `shoplab-infra`)
- [X] T040 [US5] Añadir la fila `0015` (estado «aceptado») a la tabla de `docs/adr/README.md` con el mismo formato que las anteriores y comprobar que el enlace resuelve

---

## Phase 8: Polish & Cross-Cutting

- [X] T041 Ejecutar `./mvnw -B -ntp clean verify` completo (pruebas, Checkstyle, PMD y JaCoCo >= 80 %), comparar cobertura con la línea base de T001 y registrar el resultado en `SPEC/validation.md`; corregir cualquier violación de estilo (líneas ≤ 121, llaves, imports sin usar)
- [X] T042 [P] Comprobar que `POST /api/chat`, `/actuator/prometheus` y los logs con correlación no cambiaron: `ChatStreamContractTests` y `MetricsEndpointTests` en verde y arranque manual con Postgres local + `curl -N` del chat (quickstart §4)
- [X] T043 [P] Recorrer `SPEC/quickstart.md` de principio a fin y marcar en `SPEC/validation.md` cada escenario como verificado o con la desviación observada
- [X] T044 [P] Actualizar `docs/chat-streaming.md` y cualquier nota de arquitectura que mencione el módulo `tools` (`grep -rn "tools" docs README.md`) y las instrucciones de arranque local para indicar que Postgres es obligatorio (`docker compose -f infra/docker-compose.yml up -d postgres`)
- [ ] T045 Revisar `git status --short` y `git diff --staged` (sin `target/`, sin archivos temporales, sin cambios de formato masivos) y dejar anotado en la descripción de PR: que dev ya tiene Postgres y las variables `SPRING_DATASOURCE_*` (resultado de T002a/T045a), que la release que incluya LAB-105 llega a dev por LAB-81 (solo versiones de release) y que tras desplegarla hay que comprobar en los logs del pod `backend` que se aplicaron las migraciones de los 5 módulos; además, comentar en Jira LAB-19 (o ajustar su descripción) que el esquema del módulo de recuperación se llama `retrieval` y no `kb` (aclaración 2026-10-27)
- [X] T045a Aplicar los cambios de infraestructura que haya revelado la implementación (T002a, T005, T028 o la ejecución real de las migraciones): en `infra/` de este repositorio dentro de esta misma rama, y en `shoplab-infra` (clon local `../shoplab-infra`, rama nueva desde `main`, **sin tocar ni revertir sus cambios locales sin confirmar**: `.gitignore`, `Makefile`, `docker-compose.yml`, `docs/terraform-local.md`, `scripts/*`) con su propia PR, `kustomize build k8s/overlays/dev` y `policy-check.sh` en verde y commit `type(scope): [LAB-105] ...`; si T002a concluyó que no hace falta ningún cambio, dejar constancia en `SPEC/validation.md` y no abrir PR en `shoplab-infra`
- [X] T046 Documentar que esta historia no añade endpoints HTTP (no se genera `requests.http`) en `SPEC/validation.md`

---

## Dependencies & Execution Order

- **Fase 1** → **Fase 2** → fases de historia. La fase 2 bloquea todo (arranque y pruebas dependen de la Postgres de pruebas).
- **US1** (módulos y herramientas) y **US3** (reglas ArchUnit) son casi independientes; **US3 debería ir después de US1** para que D3/D5 se evalúen sobre el mapa final.
- **US2** necesita de US1 los módulos `orders`/`catalog` (T015) para crear sus esquemas.
- **US4** necesita US1 (mapa final) y, para ser útil, US2/US3; **US5** es independiente y puede hacerse en cualquier momento tras la fase 2.
- **Polish** al final.

```text
Fase 1 → Fase 2 → US1 → { US2, US3 } → US4 → Polish
                     └──────── US5 (en paralelo) ──┘
```

## Parallel Opportunities

- Fase 1: T002a en paralelo con T002.
- Fase 2: T003, T006, T007 y T009 en paralelo (archivos distintos); T010a va antes de T006 y T010.
- US1: T013 con T012; T015, T016 y T019 en paralelo entre sí.
- US2: T021 y T023 en paralelo; T025 y T026 en paralelo.
- US3: T029 en paralelo con cualquier tarea de US2.
- US5 (T039) puede hacerla otra persona mientras avanzan US2–US4.

## Implementation Strategy

- **MVP**: Fases 1–2 + **US1** (mapa de módulos y composición de herramientas, sin cambio de contrato del chat).
- **Incremento 2**: US2 (esquemas) y US3 (reglas), que dan las garantías verificables de la historia.
- **Incremento 3**: US4 (documentación y deriva en CI) y US5 (ADR).
- Commits atómicos por historia (`refactor(...)`/`test(...)`/`docs(...)`); el título de PR sigue
  `refactor(backend): [LAB-105] módulos por negocio con dueño de datos`.
- Cada tarea de prueba se ejecuta y debe **fallar** antes de implementar; cada regla se demuestra con su fixture.
