# Validation Evidence: LAB-59 Extraer chat-service del monolito

**Branch**: `feature/LAB-59-chat-service-extraction` | **Spec**: [spec.md](./spec.md) | **Runbook**: [quickstart.md](./quickstart.md)

Registro de evidencias por paso de `quickstart.md` y puertas del plan.

---

## 1. Contratos (`shoplab-contracts` `1.4.0`)

- **Estado**: Publicado (`v1.4.0`)
- **Evidencia**: PR #17 y PR #18 (`release 1.4.0`) fusionados; workflow `Publish Packages` (#45734841669) en verde (`com.shoplab:shoplab-contracts:1.4.0` en GitHub Packages con `com.shoplab.contracts.chat.client.AssistantSettingsApi`).

## 2. Servicio `shoplab-chat-service` (CI, cobertura, imagen y stubs)

- **Estado**: Release `v0.1.0` publicada y firmada (pendiente confirmación de stubs y paquete público en GHCR `T019`)
- **Evidencia**:
  - Local: `./mvnw -B -ntp -s .mvn/ci-settings.xml verify` → **BUILD SUCCESS** (`Tests run: 970, Failures: 0, Errors: 0, Skipped: 0`; JaCoCo ≥ 80 % en 75 clases; 0 violaciones Checkstyle; 0 violaciones PMD).
  - CI PR #1 (`45738713139`): `pipeline / verify` en verde (5m57s).
  - Release `v0.1.0` (`45739691345`): `publish / build-sign-publish` en verde (Cosign verificado) + `deploy / deploy` en verde hacia `shoplab-infra`.
  - Stubs `v0.1.0` (`45739691133`): `Verify contracts and publish stubs` en verde (4m5s) — `com.shoplab:shoplab-chat-service:0.1.0:stubs` publicado en GitHub Packages.

## 3. Pruebas de scripts de migración (`chat-db-init`, `migrate-chat`, `verify-chat-migration`)

- **Estado**: Aprobado
- **Evidencia**: Probado contra contenedor PostgreSQL 16 desechable (`pgvector/pgvector:0.8.7-pg16`): provisión `chat-db-init.sh` OK; `migrate-chat.ps1` + `verify-chat-migration.ps1` OK (recuentos, checksums MD5, Flyway y secuencias `IDENTITY` `last_value=max_id`); segunda ejecución sin `--replace` rechazada con código `3`; `INSERT` como rol `chat` obtuvo `id=3` sin colisión; alteración detectada con código `1`.

## 4. Despliegue de `chat-service` en `dev` (sin tráfico)

- **Estado**: Manifiestos fusionados (`shoplab-infra` PR #45); pendiente cargar secretos `T027` (`chat-db-password`, `LITELLM_KEY_CHAT`, Langfuse y `SHOPLAB_CHAT_PROMPTS_OVERRIDE_TOKEN`) y verificación de ArgoCD en `shoplab-dev`
- **Evidencia**: `shoplab-infra` PR #45 fusionado (`apps/chat-service/base`, `apps/chat-service/overlays/dev` con `newTag: v0.1.0`, clave `chat-dev` en `k8s/base/litellm/`).

## 5. Monolito Release A (`eval` con `HttpAssistantSettingsClient` en modo `in-process`)

- **Estado**: Fusionado y publicado (`v0.31.0`)
- **Evidencia**: `shoplab-helpdesk` PR #190 (todos los checks CI en verde: `backend`, `qodana`, `build-scan`, `trivy`, `python`, `observability`, `ci-gate`) y release PR #191 (`v0.31.0`) fusionados. `EvalChatContractConsumerTest` contra `com.shoplab:shoplab-chat-service:0.1.0:stubs`, `HttpAssistantSettingsClientTests` (WireMock: mapeo, propagación `X-Request-Id`/`traceparent`, 503 y timeout) y `ModularityTests` en verde, 0 Checkstyle, 0 PMD.

## 6. Línea base de calidad (LAB-29) antes del corte

- **Estado**: Pendiente
- **Evidencia**:

## 7. Migración y verificación del esquema `chat`

- **Estado**: Aprobado
- **Evidencia**: `chat-db-init` + `litellm-bootstrap` + `scripts/migrate-chat.ps1` + `scripts/verify-chat-migration.ps1` sobre el PostgreSQL local: `conversation_turn` (17 filas, MD5 idéntico), `feedback` (0 filas), `flyway_schema_history` (4 filas, MD5 idéntico) y secuencias `IDENTITY` (`conversation_turn` `last_value=47, max_id=47`; `feedback` tabla vacía).

## 8. Corte en `dev` y local (gateway + `mode=http`) y comparación LAB-29

- **Estado**: Corte aplicado en `dev` y local (pendiente comparación formal LAB-29 cuando se ejecute `shoplab-eval` con juez)
- **Evidencia**:
  - `shoplab-helpdesk` PR #190 (Release A) y PR #191 (`v0.31.0`) fusionados.
  - `shoplab-infra` PR #46 (corte único: `chat` y `assistant` en gateway → `http://chat-service:8080` + `mode=http` en `backend`) fusionado.
  - Local: `gateway` enruta `/api/assistant/settings` → `destination=http://chat-service:8080` (`status=200`); `chat-service:v0.1.0` responde SSE RAG con 3 citas de `retrieval-service:v0.2.0` y streaming vía LiteLLM (`chat-local`).

## 9. Verificación de reversión (antes de Release B)

- **Estado**: Pendiente
- **Evidencia**:

## 10. Monolito Release B (retirada de `chat`, `orders`, `catalog`, `tickets` y preservación de Olist)

- **Estado**: Verificado localmente; listo para PR y publicación
- **Evidencia**:
  - `mvn -s .mvn/ci-settings.xml verify -Dskip.srcclr=true --no-transfer-progress` en `backend/` → **BUILD SUCCESS** (`Tests run: 470, Failures: 0, Errors: 0, Skipped: 0`; JaCoCo ≥ 80 % en 33 clases; 0 violaciones Checkstyle; 0 violaciones PMD; `ModularityTests` regeneró `docs/modulos/` con los módulos `eval` y `shared`).
  - `SchemaIsolationTests` confirmó `db/migration` reducido únicamente a `eval`.
  - Esquemas Olist verificados intactos en PostgreSQL local (`catalog`: 4 tablas; `orders`: 6 tablas; ningún `DROP SCHEMA` ejecutado).
  - Contratos HTTP sin endpoints nuevos respecto a la API ya existente (`specs/LAB-59-chat-service-extraction/contracts/http-api.md`); `docs/adr/0034-extraccion-de-chat-service.md`, `docs/adr/README.md`, `docs/mapa-servicios.md` y `docs/fases/F5-mover-rutas-al-gateway.md` actualizados.
