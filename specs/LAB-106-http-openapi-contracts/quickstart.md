# Quickstart: Validación de Contratos HTTP, Generación Angular y Spring Cloud Contract (LAB-106)

**Feature**: `feature/LAB-106-http-openapi-contracts`  
**Date**: 2026-11-24

Esta guía describe cómo reproducir localmente y en CI todas las verificaciones de `LAB-106` entre `shoplab-contracts` y `shoplab-helpdesk`.

---

## 1. Validación OpenAPI y `oasdiff` en `shoplab-contracts`

```bash
cd ../shoplab-contracts

# Verificar compatibilidad contra origin/main (debe fallar si hay breaking change sin !: o BREAKING CHANGE:)
bash scripts/check-openapi-compatibility.sh

# Validar pureza (cero código de negocio)
bash scripts/verify-contracts-purity.sh

# Generar y compilar modelos Java + cliente Angular npm
cd generators/java && mvn clean compile -B && cd ../..
cd generators/npm && npm ci && npm run build && cd ../..
```

### Prueba manual de puerta `oasdiff` (cambio incompatible vs compatible)
1. Añadir un campo opcional en `openapi/chat/v1/chat-api.yaml` → `check-openapi-compatibility.sh` termina en `0`.
2. Eliminar un campo requerido o cambiar un tipo sin `PR_TITLE="feat(chat)!: ..."` → `check-openapi-compatibility.sh` falla con código `1`.
3. Repetir con `PR_TITLE="feat(chat)!: [LAB-106] breaking test"` → `check-openapi-compatibility.sh` pasa para versión mayor.

---

## 2. Consumo de `@leandroamariles/shoplab-contracts` en `frontend/`

### Con paquete publicado en GitHub Packages
Requiere variable `GITHUB_TOKEN` (o token en `~/.npmrc`) con permiso `read:packages`:

```bash
cd frontend
npm ci
npm run lint
npm test -- --watch=false
npm run build
```

### En prueba local previa a release (tarball desde `shoplab-contracts`)
```bash
cd ../shoplab-contracts/generators/npm
npm run build
npm pack   # genera leandroamariles-shoplab-contracts-X.Y.Z.tgz

cd ../../shoplab-helpdesk/frontend
npm install --no-save ../../shoplab-contracts/generators/npm/leandroamariles-shoplab-contracts-*.tgz
npm run lint && npm test -- --watch=false && npm run build
```

---

## 3. Verificación Backend, Spring Cloud Contract y Stubs (`backend/`)

```bash
cd backend

# 1. Compilar, generar tests de contrato, verificar productores, empaquetar stubs e instalar en ~/.m2
./mvnw -B -ntp clean install

# 2. Ejecutar específicamente tests de consumidor contra stubs (modo LOCAL)
./mvnw -B -ntp test -Dtest="*ContractConsumerTest"

# 3. Ejecutar verificación completa (Checkstyle, PMD, Modulith, JaCoCo >= 80%)
./mvnw -B -ntp clean verify

# 4. Regenerar y verificar docs/modulos (si se añadieron clases en chat/eval)
./mvnw -B -ntp test -Dtest=ModularityTests
```

### Verificación de stubs remotos desde GitHub Packages
Con credenciales de GitHub Packages en `~/.m2/settings.xml` (`read:packages`):

```bash
./mvnw -B -ntp test -Dtest="*ContractConsumerTest" -Dshoplab.contracts.stubs-mode=REMOTE
```

---

## 4. Verificación manual de `GET /api/assistant/settings`

Con el backend levantado en local (`http://localhost:8080`):

```bash
curl -s http://localhost:8080/api/assistant/settings | jq .
```

También puede ejecutarse directamente el archivo [`specs/LAB-106-http-openapi-contracts/requests.http`](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/requests.http) tras la implementación.

---

## 5. Revisión documental

Confirmar que [`docs/fases/F5-contratos-http-versionado-y-retiro.md`](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/fases/F5-contratos-http-versionado-y-retiro.md) existe y enlaza correctamente con [`docs/contratos.md`](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/contratos.md).
