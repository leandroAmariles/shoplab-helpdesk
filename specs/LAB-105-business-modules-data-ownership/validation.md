# Validación: LAB-105

## Línea base (T001, 2026-10-27, antes de cambiar código)

`./mvnw -B -ntp clean verify` en `backend/`: BUILD SUCCESS.

| Indicador | Valor |
|---|---|
| Pruebas | 103, 0 fallos, 0 errores |
| Checkstyle | 0 violaciones |
| PMD | sin violaciones |
| Cobertura de líneas (JaCoCo, BUNDLE) | 100 % (120/120), umbral 80 % |

## Infraestructura (T002a, 2026-10-27)

Verificado en modo lectura:

- `../shoplab-infra/k8s/base/backend/kustomization.yaml`: el backend recibe `SPRING_DATASOURCE_URL`
  (`jdbc:postgresql://postgres:5432/shoplab`) y `SPRING_DATASOURCE_USERNAME` (`shoplab`);
  `externalsecret.yaml` aporta `SPRING_DATASOURCE_PASSWORD` desde `/shoplab/ENVIRONMENT/postgres-password`.
- `../shoplab-infra/k8s/base/postgres`: StatefulSet con `pgvector/pgvector:0.8.7-pg16`; la base `shoplab` y el usuario
  `shoplab` coinciden con los del backend. Sin NetworkPolicy en `k8s/`.
- `infra/docker-compose.yml` de este repositorio: servicio `postgres` con base `shoplab`, usuario `shoplab` y la misma
  imagen.

Conclusión: sin cambios de infraestructura necesarios; confirmado con la ejecución real en T045a (ver más abajo).

## Arranque real (T028, 2026-10-27)

Contra la Postgres de `infra/docker-compose.yml`:

- Con Postgres: el backend arranca en ~2 s, el log muestra `Schema '<modulo>' ready` para `catalog`, `chat`, `orders`,
  `retrieval` y `tickets`, y `\dn` lista los cinco esquemas más `public`.
- Sin Postgres: el arranque aborta con `ModuleMigrationException: Schema of module 'catalog': The database is not
  available` (causa: `Connection to localhost:5432 refused`); no queda el servidor a medias.

## Resultado final (T041, 2026-10-27)

`./mvnw -B -ntp clean verify` en `backend/`: BUILD SUCCESS.

| Indicador | Línea base | Final |
|---|---|---|
| Pruebas | 103 | 147, 0 fallos, 0 errores |
| Checkstyle | 0 | 0 |
| PMD | limpio | limpio |
| Cobertura de líneas | 100 % (120/120) | 99,4 % (176/177), umbral 80 % |

## Quickstart (T043)

| Escenario | Resultado |
|---|---|
| 1. Mapa de módulos y límites | `ModularityTests` (4) en verde; `docs/modulos/` generado y estable en 4 ejecuciones seguidas (md5 idéntico, solo LF). |
| 2. Capas y dominio puro | `ModuleArchitectureTests` (9) y `ReactiveArchitectureTests` (7) en verde; cada regla falla contra su fixture. |
| 3. Esquemas por módulo | `SchemaIsolationTests` (6) y `ModuleSchemasIntegrationTests` (3) en verde; arranque manual verificado (ver arriba). |
| 4. Sin regresión del chat | `ChatStreamContractTests` (golden byte a byte) y `MetricsEndpointTests` en verde. Arranque manual con Ollama apagado: `POST /api/chat` → 503 `SLHD_CHAT_00002`, con `X-Accel-Buffering: no`; `/actuator/prometheus` expone `http_server_requests_seconds_*` con las mismas etiquetas y ahora `hikaricp_*`. |
| 5. ADR | `docs/adr/0015-...md` con la plantilla, fila en `docs/adr/README.md` y los cuatro puntos del spec. |

## Infraestructura (T045a, 2026-10-27)

Sin cambios necesarios, ni en `infra/` de este repositorio ni en `shoplab-infra`: el backend en dev ya recibe
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD` (las variables de entorno
sustituyen a los valores por defecto de `application.yml`) y dev ya tiene Postgres. No se abre PR en `shoplab-infra`.

Pendiente de comprobar tras la release que incluya LAB-105 (la despliega LAB-81): en los logs del pod `backend` deben
aparecer las cinco líneas `Schema '<modulo>' ready`. Si Postgres no estuviera lista el pod reiniciaría con retroceso.

## Endpoints HTTP (T046)

Esta historia no añade ni cambia endpoints HTTP, así que no se genera `requests.http`.

## Desviaciones respecto a las tareas

- T003: `@PostgresTest` usa un `ApplicationContextInitializer` (`@ContextConfiguration`) en vez de
  `@DynamicPropertySource`, que no puede declararse en una meta-anotación; así convive con el `@DynamicPropertySource`
  propio de cada prueba.
- T006: la migración inválida está en `src/test/resources/db/migration/broken/` (el migrador deduce la carpeta del
  nombre del módulo), no en `schema/broken/`.
- T018: las herramientas se pasan sobre una copia de las opciones por defecto del modelo
  (`ChatModel.getDefaultOptions().mutate()`); las opciones por petición no se fusionan con las por defecto en Spring AI
  2.0 y sin esa copia el modelo llega vacío.
- T029: no hay fixtures de Kafka ni de JPA porque esas librerías no están en el classpath; la prueba comprueba que la
  lista de familias prohibidas las incluye y que Spring AI y Reactor se rechazan con el nombre de la clase.
- T034: el fixture de Modulith se carga con `ImportOption.OnlyIncludeTests`, porque `ApplicationModules.of` excluye
  las clases de test por defecto.
- T035/T038: el `Documenter` limpia su carpeta de salida, así que la guía escrita a mano está en `docs/modulos.md` y no
  dentro de `docs/modulos/`. Las líneas `Rel(...)` de los `.puml` se ordenan tras generar porque el orden del
  `Documenter` cambia entre ejecuciones y haría fallar la comprobación de CI.
