# Quickstart: validar LAB-105

Guía de validación; no contiene implementación (ver [tasks.md](tasks.md) cuando exista).

## Requisitos

- JDK 21, Docker en marcha (Testcontainers y, opcionalmente, `infra/docker-compose.yml`).
- Desde `backend/`.

## 1. Mapa de módulos y límites (US1, US4)

```bash
./mvnw -B -ntp test -Dtest=ModularityTests
```

Esperado: módulos detectados = `chat`, `retrieval`, `orders`, `catalog`, `tickets`, `shared`; sin `tools`;
`verify()` en verde; se (re)generan los archivos de `docs/modulos/`.

```bash
git diff --stat -- ../docs/modulos   # vacío si la documentación ya estaba al día
```

## 2. Capas y dominio puro (US3)

```bash
./mvnw -B -ntp test -Dtest='ModuleArchitectureTests,ReactiveArchitectureTests'
```

Esperado: producción cumple D1–D5 y A1–A6; cada regla falla contra su fixture (prueba negativa).

## 3. Esquemas por módulo (US2)

```bash
./mvnw -B -ntp test -Dtest='SchemaIsolationTests,ModuleSchemasIntegrationTests'
```

Esperado: un esquema por módulo con su `flyway_schema_history`, 0 FKs entre esquemas, y los fixtures de violación
detectados.

Comprobación manual contra la Postgres local (opcional):

```bash
docker compose -f ../infra/docker-compose.yml up -d postgres
./mvnw -B -ntp spring-boot:run
docker exec -it <contenedor-postgres> psql -U shoplab -d shoplab -c '\dn'
```

Esperado en `\dn`: `chat`, `retrieval`, `orders`, `catalog`, `tickets` (más `public`).

## 4. Sin regresión del chat y base de datos obligatoria (FR-012, D-4)

```bash
./mvnw -B -ntp verify          # incluye Checkstyle, PMD y JaCoCo >= 80 %
docker compose -f ../infra/docker-compose.yml up -d postgres
./mvnw -B -ntp spring-boot:run
curl -N -X POST localhost:8080/api/chat -H 'Content-Type: application/json' -d '{"message":"hola"}'
```

Esperado: el chat responde igual que en LAB-12/LAB-111. Con Postgres detenido (`docker compose stop postgres`), el
arranque falla con un error que nombra la causa y no queda el servidor a medias.

## 5. ADR (US5)

Abrir `docs/adr/0015-limites-de-contexto-y-repositorios.md` y verificar plantilla, fila en `docs/adr/README.md` y los
cuatro puntos del spec (dueño por dato, repo por servicio desde F5 con el monolito hasta F6-01, repos de soporte,
qué se comparte y qué no).
