# Implementation Plan: LAB-129 Extraer retrieval-service del monolito

**Branch**: `feature/LAB-129-retrieval-service-extraction` | **Date**: 2026-11-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/LAB-129-retrieval-service-extraction/spec.md`

## Summary

Sacar `retrieval` del monolito a `shoplab-retrieval-service` con su propia base de datos, sin que cambie lo que ven el
frontend, la evaluación ni el cliente del chat. Se hace en expandir y contraer: primero se corrige la plataforma; el
servicio se despliega en paralelo; el monolito publica una release que sabe usarlo pero no lo usa; el corte es un solo
commit de configuración en `shoplab-infra`; y una segunda release del monolito retira el módulo. Decisiones en
[research.md](./research.md).

## Technical Context

**Language/Version**: Java 21; Bash para scripts de migración e infraestructura

**Primary Dependencies**: Spring Boot 4.1.2 (WebFlux), Spring AI 2.0.2, Flyway, JDBC, Micrometer, Spring Cloud
Contract 5.0.3, openapi-generator (en `shoplab-contracts`), Kustomize y Argo CD

**Storage**: PostgreSQL 16 + pgvector. Base `retrieval` con rol propio en el mismo servidor que `shoplab`; en dev el
servidor es el contenedor `postgres` del compose del host

**Testing**: JUnit 5, Reactor Test, Testcontainers, WireMock, BlockHound, Spring Cloud Contract, ArchUnit; evaluación
de LAB-28 (`python -m shoplab_eval retrieval`)

**Target Platform**: Docker compose en local; Kubernetes (`shoplab-dev`) en dev

**Project Type**: Servicio web reactivo + cambios en seis repositorios

**Performance Goals**: la búsqueda del chat por red añade < 100 ms en p95 (SC-005)

**Constraints**: paridad de la API y de las métricas de LAB-28; reversión con un commit de configuración (SC-007);
subidas suspendidas durante la migración; sin autenticación

**Scale/Scope**: 24 documentos del corpus y unos cientos de fragmentos; una réplica en dev

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Antes de Fase 0 | Tras Fase 1 | Cómo se cumple |
|---|---|---|---|
| I. Monolito modular primero | PASS | PASS | Extracción en F5, cubierta por ADR-0031; ADR-0033 registra cómo |
| II. Hexagonal | PASS | PASS | Se conservan capas y puertos; el cerrojo entra como puerto de salida; el cliente HTTP es un adaptador de `chat` |
| III. Identificadores en inglés, errores tipados | PASS | PASS | Se copian `ResponseCodeEnum` (subconjunto) y `GlobalExceptionHandler` |
| IV. Pruebas y cobertura ≥ 80 % | PASS | PASS | Las pruebas de `retrieval` viajan con el código; se comprueba la cobertura del monolito tras retirarlo |
| V. RAG guiado por evaluación | PASS | PASS | Línea base de LAB-28 antes y comparación después (FR-023) |
| VI. Observabilidad y secretos | PASS | PASS | `X-Request-Id` y `traceparent` propagados; secretos por Secrets Manager |
| VII. Simplicidad y decisiones documentadas | PASS | PASS | Un solo cerrojo con arriendo; sin Kafka, sin doble escritura; ADR-0033 |

Sin violaciones.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-129-retrieval-service-extraction/
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
shoplab-retrieval-service/              # nuevo
├── src/main/java/com/shoplab/retrieval/
│   ├── application/ domain/ infrastructure/ config/   # movidos del módulo
│   └── shared/                                        # copia: errores, X-Request-Id, BlockingCalls
├── src/main/resources/db/migration/retrieval/         # V1-V4 idénticas + V5__indexing_lock.sql
├── src/test/java/…  src/test/resources/contracts/retrieval/
├── config/  qodana.yaml  Dockerfile  mvnw  .mvn
└── .github/workflows/{ci,release,release-please,publish-stubs}.yml

shoplab-service-template/               # nuevo repo plantilla, desde la carpeta de LAB-103 corregida
shoplab-ci/.github/workflows/           # java-service.yml, publish-image.yml
shoplab-contracts/generators/java/      # interfaz HTTP generada para KnowledgeSearch
shoplab-infra/
├── apps/retrieval-service/             # se corrige el marcador existente
├── k8s/base/{backend,litellm}/  k8s/overlays/dev/
├── terraform/  scripts/  versions.env
shoplab-helpdesk/
├── backend/src/main/java/com/shoplab/helpdesk/chat/infrastructure/out/knowledge/
├── infra/{docker-compose.yml,litellm/,observability/,postgres/}
├── scripts/                            # provisión y migración de la base
└── docs/{adr/0033-*.md,fases/,mapa-servicios.md,…}
```

**Structure Decision**: un repositorio por servicio (ADR-0031). El paquete base del servicio pasa a
`com.shoplab.retrieval`. Los scripts de migración viven en `shoplab-helpdesk` porque ahí están el compose y el
servidor PostgreSQL que usan local y dev.

## Fases de entrega

Cada fase termina en algo verificable y desplegable. El orden importa.

| Fase | Contenido | Repos | Historia | Puerta para seguir |
|---|---|---|---|---|
| A. Plataforma | Crear `shoplab-service-template` con la plantilla corregida; generar `shoplab-retrieval-service` desde ella; workflows, arreglar o quitar el `smoke`, PostgreSQL del compose | template, retrieval-service, ci, infra, helpdesk | US1 | `shoplab-retrieval-service`, aún sin el módulo, publica y firma `:vX.Y.Z`; `ci-gate` en verde en un PR que toque `k8s/` |
| B. Contratos | Cliente generado, `shoplab-contracts` 1.3.0 | contracts | US2/US4 | La interfaz compila y deserializa en una prueba con Boot 4 |
| C. Servicio | Mover código y pruebas; cerrojo, stubs, primera release funcional | retrieval-service | US2, US5 | CI verde, imagen y stubs publicados |
| D. Entornos | Base y rol, secretos, claves del gateway, manifiestos, compose | infra, helpdesk | US2/US3 | Servicio sano en local y en dev, sin tráfico |
| E. Monolito, release A | Cliente generado tras `mode=http`; por defecto sigue en proceso | helpdesk | US4 | Release desplegada sin cambio de comportamiento |
| F. Corte | Línea base, suspender subidas, migrar, verificar, un commit: gateway + `mode=http` | infra, helpdesk | US3, US4 | Métricas idénticas; chat con citas; reversión ensayada |
| G. Retirada, release B | Quitar módulo, modo en proceso, contratos y claves sobrantes; ADR y documentación | helpdesk, infra | US4 | Monolito verde sin `retrieval` |

El borrado del esquema `retrieval` antiguo queda como paso manual documentado, fuera de las releases (FR-013).

## Riesgos

| Riesgo | Efecto | Mitigación |
|---|---|---|
| La causa del `smoke` es una hipótesis (429 de Docker Hub al bajar `registry:2`) | Bloquea los cambios en `k8s/` (fases D y F) | Intento de corrección acotado; si no pasa, se quita el job de la CI (research D-10) |
| Sin el job `smoke`, la CI no prueba `terraform apply` ni que Argo CD sincronice | Un manifiesto válido pero que no arranca se detecta ya en dev | Solo si se quita: `make smoke` sigue disponible en local y se ejecuta antes de fusionar los cambios de las fases D y F |
| Leer paquetes de otro repo con `GITHUB_TOKEN` puede no estar permitido | La CI del servicio y del monolito no descarga contratos o stubs | Probarlo en la fase A; si falla, dar acceso al paquete o usar un token `read:packages` |
| La interfaz generada no compila junto a los modelos actuales (Jackson 2) en Boot 4 | Retrasa FR-016 | Prueba de compilación en la fase B antes de publicar |
| Local y dev comparten el mismo PostgreSQL | La migración afecta a los dos a la vez | Se hace una sola vez, con copia previa y el origen intacto |
| `shoplab` es superusuario del servidor | «Solo el servicio lee su base» se cumple por configuración, no lo impide el motor | Se documenta en ADR-0033 |
| El índice HNSW se reconstruye al migrar | Las métricas podrían variar | Medir antes y después; investigar cualquier diferencia |

## Creación de los repositorios

Los crea el agente durante la implementación con `gh` (la sesión tiene los permisos `repo` y `workflow`), públicos como
los demás repositorios `shoplab-*`.

| Repositorio | Cuándo | Contenido inicial | Ajustes |
|---|---|---|---|
| `shoplab-service-template` | Fase A | La carpeta de LAB-103 con las correcciones de research D-9 | Marcado como repositorio plantilla |
| `shoplab-retrieval-service` | Final de la fase A, con la plantilla corregida | Generado desde la plantilla | Variable `DEPLOY_APP_ID` y fusión por squash |

La verificación de SC-008 se hace con `shoplab-retrieval-service` recién generado, antes de mover código: no se crea un
repositorio desechable.

## Reparto del trabajo en GitHub y secretos

| Paso | Quién |
|---|---|
| Crear y configurar los dos repositorios | Agente |
| Mover el tag `v1` de `shoplab-ci` (afecta a todos los consumidores) | Agente, tras confirmación |
| Cargar `DEPLOY_APP_PRIVATE_KEY` en el repositorio nuevo | Mantenedor, o el agente si lo autoriza e indica el archivo |
| Crear `RELEASE_PLEASE_TOKEN` (token personal) y cargarlo | Mantenedor |
| Hacer público el paquete `retrieval-service` en GHCR (no es reversible) | Mantenedor |
| Añadir los secretos nuevos a `secrets.auto.tfvars` | Mantenedor, o el agente si lo autoriza |
| Fusionar el commit del corte | Mantenedor |

La puerta de la fase A depende de `RELEASE_PLEASE_TOKEN`: sin él no hay release del repositorio nuevo. El job de
despliegue de esa primera release no forma parte de la puerta, porque `apps/retrieval-service` se corrige en la fase D.

## Complexity Tracking

Sin violaciones que justificar.
