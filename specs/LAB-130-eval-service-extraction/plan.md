# Implementation Plan: LAB-130 Extraer eval-service del monolito

**Branch**: `feature/LAB-130-eval-service-extraction` | **Date**: 2026-11-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/LAB-130-eval-service-extraction/spec.md`

## Summary

Sacar el módulo `eval` (historial de ejecuciones de evaluación) de `backend/` a `shoplab-eval-service`, con base de
datos propia (`eval`), sin que cambie lo que ven `admin-eval` ni la herramienta Python de `shoplab-eval`, y retirar
después `backend/` entero. Es el último módulo del monolito: tras LAB-129 (`retrieval`) y LAB-59 (`chat`), `backend/`
contiene solo `eval` + `shared`. Se aplica *expand-and-contract* como en LAB-129 y LAB-59: el servicio se despliega en
paralelo y se migran los datos; el corte es un solo commit de configuración en `shoplab-infra` (ruta `/api/eval/**`
del gateway); y una segunda fase retira `backend/`. La dependencia de `chat-service` (`GET /api/assistant/settings`)
pasa a un cliente que sale del contrato publicado, con prueba de contrato eval → chat. Decisiones en
[research.md](./research.md).

## Technical Context

**Language/Version**: Java 21; Bash y PowerShell para scripts de migración e infraestructura

**Primary Dependencies**: Spring Boot 4.1.2 (WebFlux), Flyway, JDBC, Micrometer, Spring Cloud Contract 5.0.3,
`shoplab-contracts` (generador de cliente/modelos), Kustomize y Argo CD. Sin Spring AI, sin OpenTelemetry/Langfuse y
sin `pgvector`: `eval` no llama a modelos ni calcula vectores

**Storage**: PostgreSQL 16. Base `eval` con rol propio `eval` en el mismo servidor que `shoplab`, `retrieval` y
`chat` (compartido por local y dev). Esquema `eval` con `eval_run`, `eval_case` y `eval_result` (claves
`GENERATED ALWAYS AS IDENTITY`, columnas `jsonb`)

**Testing**: JUnit 5, Reactor Test, Testcontainers, WireMock, BlockHound, Spring Cloud Contract (productor de
`eval-service` y consumidor de `chat-service`), ArchUnit, Checkstyle, PMD, JaCoCo ≥ 80 %. Las pruebas actuales de
`eval` (equivalencia del historial, consultas SQL, no-texto) viajan con el código

**Target Platform**: Docker Compose en local; Kubernetes (`shoplab-dev`) en dev

**Project Type**: Servicio web reactivo + cambios en cinco repositorios (`shoplab-eval-service`, `shoplab-helpdesk`,
`shoplab-infra`, `shoplab-contracts`, `shoplab-gateway` solo si hace falta) y la URL en `shoplab-eval`

**Performance Goals**: sin objetivo nuevo; la llamada a `chat-service` está acotada por tiempo máximo (5 s) y no
bloquea el hilo reactivo

**Constraints**: paridad de la API y de los códigos `SLHD_EVAL_*`; el historial no guarda texto de preguntas ni
respuestas (ADR-0028); reversión con un commit de configuración antes de retirar `backend/` (SC-007); guardado
suspendido durante la migración; sin autenticación de usuario, con el token `X-Eval-Token` actual; datos de Olist y
esquemas ajenos intactos al retirar `backend/` (ADR-0034)

**Scale/Scope**: ~1.400 líneas de `eval` (2 migraciones Flyway, 3 controladores REST, 2 adaptadores JDBC); decenas de
ejecuciones guardadas; una réplica en dev

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Antes de Fase 0 | Tras Fase 1 | Cómo se cumple |
|---|---|---|---|
| I. Monolito modular primero | PASS | PASS | Extracción en F5 cubierta por ADR-0031; ADR-0035 registra esta (el siguiente número libre es 0035) |
| II. Hexagonal | PASS | PASS | Se conservan `application/port` y `infrastructure`; el cliente de chat sigue detrás de `AssistantSettingsPort`; el cliente deja de depender de `AssistantSettingsApi` del monolito |
| III. Identificadores en inglés, errores tipados | PASS | PASS | Se copian el subconjunto de `ResponseCodeEnum` (`SLHD_EVAL_*` + `SLHD_GENE_*`) y `GlobalExceptionHandler`; sin renombrar códigos |
| IV. Pruebas y cobertura ≥ 80 % | PASS | PASS | Las pruebas de `eval` viajan; se añade la de contrato eval → chat y la de productor; cobertura medida en el repo nuevo |
| V. RAG guiado por evaluación | PASS | PASS | Se toma una línea base del historial antes de migrar y se compara después (FR-026); `eval` es quien guarda las métricas de las demás extracciones |
| VI. Observabilidad y secretos | PASS | PASS | `X-Request-Id` y `traceparent` propagados a chat; token y credenciales por Secrets Manager / `.env`, nunca en el repositorio |
| VII. Simplicidad y decisiones documentadas | PASS | PASS | Sin Kafka, sin doble escritura, sin clave de LiteLLM ni `pgvector`; ADR-0035 con base propia, corte, retiro y reversión |

Sin violaciones. Nota de gobernanza: la constitución (Technology & Architecture Constraints) sigue nombrando `backend/`
como el backend Spring Modulith; al retirarlo hay que enmendarla (PATCH) en la misma historia (ver D-9).

## Project Structure

### Documentation (this feature)

```text
specs/LAB-130-eval-service-extraction/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── http-api.md
│   ├── chat-client.md
│   ├── configuration.md
│   └── data-migration.md
├── checklists/requirements.md
└── tasks.md             # lo crea /speckit-tasks
```

### Source Code (por repositorio)

```text
shoplab-eval-service/                    # nuevo, generado desde shoplab-service-template
├── src/main/java/com/shoplab/eval/
│   ├── api/ application/ domain/ infrastructure/ config/   # movidos de backend/.../helpdesk/eval
│   └── shared/                                              # copia mínima: errores, X-Request-Id, BlockingCalls, ModuleSchema*
├── src/main/resources/db/migration/eval/                    # V1, V2 idénticas (sin V3)
├── src/test/java/…  src/test/resources/contracts/eval/      # contratos productor; consumidor de chat con sus stubs
├── config/  qodana.yaml  Dockerfile  mvnw  .mvn
└── .github/workflows/{ci,release,release-please,publish-stubs}.yml

shoplab-helpdesk/                        # este repo
├── scripts/{migrate-eval,verify-eval-migration}.{sh,ps1}    # modelados en los de chat
├── infra/postgres/{eval-db-init,eval-migrate,eval-verify}.sh
├── infra/docker-compose.yml             # + eval-db-init, eval-service; − backend (fase final)
├── infra/prometheus/…                   # el objetivo de scraping pasa de backend:8081 a eval-service
├── docs/adr/0035-extraccion-de-eval-service.md
├── docs/{mapa-servicios,modulos,contratos,admin-eval}.md    # actualizados
├── .github/workflows/                   # fase final: se retiran filtros y flujos de backend/
├── backend/                             # se retira en la fase final
└── specs/LAB-130-eval-service-extraction/baseline/          # línea base antes de migrar

shoplab-contracts/                       # + OpenAPI de eval-service; cliente/modelos de chat (ya en 1.4.0)
shoplab-infra/apps/eval-service/         # completar el esqueleto de LAB-104; corte en apps/gateway/overlays/dev
shoplab-eval/                            # solo la URL base de guardado
shoplab-gateway/                         # sin código; la ruta `eval` es configuración
```

**Structure Decision**: un servicio por repositorio, igual que `retrieval-service` y `chat-service`. El paquete pasa
de `com.shoplab.helpdesk.eval` a `com.shoplab.eval` como en las extracciones anteriores (comprobar la convención en
`shoplab-retrieval-service`).

## Fases

Se entrega en este orden; cada puerta es verificable antes de seguir. Solo la fase D es irreversible por
configuración.

| Fase | Contenido | Puerta de salida |
|---|---|---|
| A. Servicio en paralelo | Repo `shoplab-eval-service` desde la plantilla; mover código y migraciones; cliente de chat desde contrato; OpenAPI en `shoplab-contracts`; contratos productor y consumidor; imagen y release | CI verde, imagen firmada y verificada, contrato publicado, prueba eval → chat pasa con los stubs de `chat-service` |
| B. Datos y despliegue | `eval-db-init`; scripts de migración y verificación; `apps/eval-service` completo; línea base del historial | Servicio sano en dev junto al monolito; `verify-eval-migration` en verde; paridad de lectura contra ambos |
| C. Corte | Ventana: suspender guardado, migrar, un commit en `shoplab-infra` mueve `/api/eval/**`; cambiar la URL en `shoplab-eval`; reanudar | `admin-eval` y una ejecución completa de la herramienta funcionan sobre el servicio; SC-001…SC-004 |
| D. Retiro de `backend/` | Quitar `backend/`, su imagen, CI, Qodana/Trivy, compose, scraping y despliegue; enmendar la constitución; docs y ADR | CI y compose en verde sin `backend/`; 0 referencias; Olist intacto |

## Riesgos y mitigaciones

| Riesgo | Efecto | Mitigación |
|---|---|---|
| Los repositorios hermanos (`shoplab-infra`, `shoplab-gateway`, `shoplab-contracts`, `shoplab-eval`) no se pudieron inspeccionar al planificar | Supuestos sobre su estado pueden fallar | Cada supuesto está marcado «por verificar» en research.md; la primera tarea de cada fase lo comprueba |
| Ventana de migración con la herramienta guardando | Ejecución perdida | La herramienta falla visiblemente con 503/403; se avisa y se reintenta tras el corte; sin doble escritura |
| Secuencias `IDENTITY` con `pg_restore` | Choques de `id` en la primera ejecución nueva | `verify-eval-migration` exige `last_value >= max(id)` en las tres secuencias |
| El monolito también lo usa el compose y Prometheus | Retiro deja objetivos rotos | Inventario previo en D-8 y prueba de arranque del compose sin `backend/` |
| `chat-service` cambia su respuesta | `GET /api/eval/settings` rompe en silencio | Prueba de contrato con stubs versionados (FR-015) |
| Borrar `backend/` borra el productor de stubs de `eval` que usaba el propio monolito | Se pierde el contrato | Los contratos productor viajan al repo nuevo en la fase A, antes del retiro |
| Local y dev comparten PostgreSQL | La migración afecta a ambos | Una sola vez, con copia previa y origen intacto |
| `shoplab` es superusuario del servidor | «Solo el servicio lee su base» es por configuración | Se documenta en ADR-0035 |

## Creación del repositorio y reparto de trabajo

| Paso | Quién |
|---|---|
| Crear `shoplab-eval-service` desde `shoplab-service-template` con `gh` | Agente |
| `DEPLOY_APP_ID`, fusión por squash, `RELEASE_PLEASE_TOKEN` y `DEPLOY_APP_PRIVATE_KEY` en el repo nuevo | Mantenedor (o el agente si lo autoriza) |
| Hacer público el paquete `eval-service` en GHCR (no reversible) | Mantenedor |
| Añadir `EVAL_DB_PASSWORD` y el token `SHOPLAB_EVAL_PERSISTENCE_TOKEN` a los secretos de dev | Mantenedor |
| Fusionar el commit del corte y el del retiro | Mantenedor |

## Complexity Tracking

Sin violaciones que justificar.
