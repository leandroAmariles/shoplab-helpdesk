# Research: Módulos por negocio con dueño de datos

No quedaban marcadores `NEEDS CLARIFICATION`; estas son las decisiones de diseño que el spec delegó a `/speckit-plan`.

## D-1. Convención de nombres de esquema y migraciones (FR-015)

- **Decision**: esquema = nombre del paquete del módulo (`chat`, `retrieval`, `orders`, `catalog`, `tickets`);
  migraciones en `classpath:db/migration/<modulo>/`; historial de Flyway en `<modulo>.flyway_schema_history`.
- **Rationale**: esquema y carpeta se deducen del módulo; el historial dentro del esquema viaja con él cuando F5 lo
  extraiga a su propio servicio.
- **Alternatives**: un historial global (`public.flyway_schema_history`) acopla el orden de todos los módulos y obliga a
  separarlo al extraer; prefijos (`app_chat`) añaden ruido sin ganancia.

## D-2. Un Flyway por módulo, no el autoconfigurado

- **Decision**: desactivar el Flyway autoconfigurado (`spring.flyway.enabled=false`). Cada módulo aporta un bean
  `ModuleSchema(name)`; `ModuleSchemaMigrator` (en `shared`) recorre los beans en orden alfabético y ejecuta
  `Flyway.configure().dataSource(ds).schemas(name).defaultSchema(name).createSchemas(true)
  .locations("classpath:db/migration/" + name).load().migrate()`.
- **Rationale**: Flyway autoconfigurado asume un solo conjunto de ubicaciones, un esquema por defecto y un historial.
  El migrador por módulo hace imposible, por construcción, que una migración de un módulo caiga en otro esquema
  (`defaultSchema` aísla el `search_path`).
- **Alternatives**: varias `locations` en el Flyway estándar (un historial compartido, esquemas escritos a mano en cada
  script); `FlywayMigrationStrategy` (sigue siendo una sola instancia).
- **Nota**: el orden alfabético basta porque los módulos no dependen entre sí a nivel de datos (es la regla). Si un
  módulo necesitara datos de otro al migrar, la regla ya está rota.

## D-3. Migración baseline por módulo vacío

- **Decision**: cada módulo lleva `V1__baseline.sql` con solo un comentario que declara su propiedad del esquema.
  LAB-17/LAB-19 añaden `V2…`.
- **Rationale**: Flyway avisa pero no falla si falta la carpeta; un baseline explícito hace reproducible la creación del
  esquema, deja historial y evita inventar tablas (edge case del spec).
- **Alternatives**: crear esquemas en `infra/postgres/init` (rompe la propiedad del módulo y no viaja con él).

## D-4. Base de datos obligatoria (aclaración 2026-10-27, opción A)

- **Decision**: no hay interruptor ni perfil sin BD. Sin Postgres, o si una migración falla, el arranque aborta con un
  error que nombra el módulo (`ModuleSchemaMigrator` envuelve el fallo). Las pruebas que arrancan la aplicación
  (`@SpringBootTest`) llevan la meta-anotación `@PostgresTest`, que usa un contenedor pgvector **único por JVM** de
  pruebas (inicialización perezosa en un `static`, `@DynamicPropertySource`), de modo que no se arranca uno por contexto.
- **Rationale**: decisión del equipo; evita un modo de arranque más que probar y mantiene dev y local iguales.
  Un contenedor compartido limita el coste de las ~10 pruebas de contexto. H2 se descarta: no refleja Postgres y
  esconde fallos de esquemas.
- **Infraestructura (verificado el 2026-10-27)**: `shoplab-infra` ya despliega Postgres en dev
  (`k8s/base/postgres`, imagen `pgvector/pgvector:0.8.7-pg16`, usuario `shoplab`, contraseña desde
  `/shoplab/dev/postgres-password` por ExternalSecret) y el backend ya recibe `SPRING_DATASOURCE_URL=
  jdbc:postgresql://postgres:5432/shoplab`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD`
  (`k8s/base/backend`). No hay NetworkPolicy que bloquee el tráfico. El usuario de la imagen oficial es superusuario,
  así que `CREATE SCHEMA` funciona. **Conclusión: no se prevén cambios de infraestructura**; el nombre de las variables
  coincide con las propiedades estándar de Spring que usa `application.yml`. Regla del equipo: si la implementación
  revela un cambio necesario en `infra/` o en `shoplab-infra`, se hace en esta historia (tarea T045a).
- **Riesgo residual**: si el backend arranca antes que Postgres, el pod reinicia con retroceso hasta que Postgres esté
  lista (comportamiento aceptable; la sonda de arranque admite ~180 s). Se vigila en la verificación posterior a la
  release de LAB-81.
- **Alternatives**: perfil `no-db` temporal (descartado por el equipo); BD opcional por configuración (descartado).
- **Consecuencia en CI**: todas las pruebas de contexto requieren Docker (ya disponible en `ubuntu-latest`).

## D-5. Dónde viven las herramientas `@Tool`

- **Decision**: subpaquete `api` por módulo, declarado como interfaz nombrada de Modulith. Cada módulo expone un bean
  `ToolCallbackProvider` (`MethodToolCallbackProvider`) con sus herramientas; `chat` inyecta
  `ObjectProvider<ToolCallbackProvider>` y solo compone la lista. Una regla ArchUnit limita `@Tool` a `..api..`.
- **Rationale**: Spring AI ya entrega un contrato de composición; no se inventa un puerto propio. Hoy no existe ninguna
  herramienta (verificado: sin `@Tool`/`ToolCallback` en `src/main`), así que se entrega el mecanismo y su verificación,
  no herramientas ficticias (YAGNI).
- **Alternatives**: puerto `ToolPort` propio en cada módulo (duplica el contrato de Spring AI); herramientas en
  `chat` (contradice la historia).
- **Pureza**: `api` puede usar Spring AI (no es `domain`); delega en puertos de `application`.

## D-6. Reglas de arquitectura

- **Decision**: `ModuleArchitectureRules` con el mismo patrón de LAB-111 (reglas + fixtures de violación + prueba
  parametrizada que exige que cada regla falle contra su fixture):
  - D1: `..domain..` no depende de `org.springframework.ai..`, `org.springframework.kafka..`/`org.apache.kafka..`,
    `jakarta.persistence..`/`org.springframework.data.jpa..` ni `reactor..`.
  - D2: `domain` no depende de `application`, `infrastructure`, `api` ni `config`; `application` no depende de
    `infrastructure`.
  - D3: toda clase de un módulo de negocio vive en `api`, `domain`, `application`, `infrastructure` o `config`
    (`shared` y la clase raíz exentos; los `package-info` no cuentan).
  - D4: `@Tool` solo en `..api..`.
  - D5: `chat` no depende de `..infrastructure..` ni `..domain..` de otro módulo (refuerza a Modulith).
- **Rationale**: ArchUnit llega con `spring-modulith-starter-test`; `Mono`/`Flux` quedan permitidos en `application` e
  `infrastructure` por no listarlos en reglas de esos paquetes. Se verificó que el dominio y la aplicación actuales de
  `chat` ya cumplen D1/D2 (sin imports prohibidos).
- **Alternatives**: `layeredArchitecture()` de ArchUnit (mensajes menos claros para D1 y no cubre D4); dejarlo a
  Modulith (no ve capas internas).

## D-7. Aislamiento de esquemas verificable

- **Decision**: dos verificaciones.
  1. **Lint estático** (`SchemaIsolationRules`): recorre `db/migration/<modulo>/*.sql` y los `*.sql`/bloques de texto
     de `src/main` del módulo y rechaza (a) `REFERENCES` a un esquema ajeno, (b) nombres calificados `otroEsquema.`,
     (c) scripts cuyo `CREATE SCHEMA`/`SET search_path` apunte fuera de su carpeta. Prueba negativa con fixtures.
  2. **Integración con Postgres real** (Testcontainers, imagen pgvector de LAB-10): migra todos los módulos y comprueba
     que existe un esquema por módulo con su historial y que `pg_constraint` no tiene FKs entre esquemas; prueba
     negativa aplicando un script que crea una FK cruzada en un esquema temporal y exigiendo que la comprobación lo
     detecte.
- **Rationale**: el lint es instantáneo y corre siempre; la integración prueba el comportamiento real de Flyway y el
  catálogo. GitHub Actions `ubuntu-latest` trae Docker, así que no requiere servicios nuevos en CI.
- **Limitación aceptada**: un `JOIN` armado dinámicamente en cadenas de Java no se detecta; lo cubre la revisión y el
  lint de nombres calificados. Se documenta en el contrato.
- **Alternatives**: roles de BD por módulo con permisos solo sobre su esquema (garantía fuerte pero exige
  gestión de credenciales por módulo; se difiere a F5, recogido en el ADR).

## D-8. Documentación de módulos

- **Decision**: `ModularityTests` ejecuta `new Documenter(modules, options).writeDocumentation()` con salida
  `../docs/modulos` (diagrama de componentes PlantUML + ficha `.adoc` por módulo), versionada. CI ejecuta las pruebas y
  luego `git diff --exit-code -- docs/modulos`; si cambian los módulos sin regenerar, falla.
- **Rationale**: cumple «generada con el Documenter en `docs/`» y detecta deriva (US4/4). El directorio de salida
  se verifica en la primera tarea (la opción exacta del `Documenter.Options` en 2.1.x).
- **Alternatives**: publicar solo como artefacto de CI (no se ve en el repositorio); generar a `target/` (no cumple
  `docs/`).

## D-9. Dependencias de Boot 4

- **Decision**: usar los starters modulares `spring-boot-starter-jdbc` y `spring-boot-starter-flyway`; clases de
  exclusión no aplican; se usan las autoconfiguraciones de Boot 4 (`org.springframework.boot.jdbc.autoconfigure…`,
  `org.springframework.boot.flyway.autoconfigure…`).
- **Verificación pendiente**: nombres exactos de starters y autoconfiguraciones se confirman al compilar (tarea 1);
  si difieren, se ajusta sin cambiar el diseño.

## D-10. Estrategia de repositorios (contenido del ADR 0015)

- **Decision**: ADR «Límites de contexto y estrategia de repositorios»: un único módulo dueño por dato; el resto lo
  consulta por la API del módulo o por eventos; un repositorio por servicio desde F5; el monolito permanece en
  `shoplab-helpdesk` hasta F6-01; `shoplab-contracts`, `shoplab-ci` y `shoplab-infra` como repositorios de soporte;
  se comparten paquetes de contratos (DTO/eventos versionados, sin lógica) y no librerías con dominio (acoplan
  despliegues y filtran el modelo de un contexto en otro). Registra también la integridad por identificador sin FK
  y la opción diferida de roles por esquema.
- **Rationale**: es la decisión de extracción que exige la constitución (principio VII) y alimenta a F5/F6.
