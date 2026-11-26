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

- **Estado**: Pendiente
- **Evidencia**:

## 5. Monolito Release A (`eval` con `HttpAssistantSettingsClient` en modo `in-process`)

- **Estado**: Verificado localmente (PR abierto para CI y despliegue)
- **Evidencia**: `EvalChatContractConsumerTest` (contra `com.shoplab:shoplab-chat-service:0.1.0:stubs` desde GitHub Packages), `HttpAssistantSettingsClientTests` (WireMock: mapeo, propagación `X-Request-Id`/`traceparent`, 503 y timeout) y `ModularityTests` en verde (`8/8`), 0 violaciones Checkstyle, 0 violaciones PMD.

## 6. Línea base de calidad (LAB-29) antes del corte

- **Estado**: Pendiente
- **Evidencia**:

## 7. Migración y verificación del esquema `chat`

- **Estado**: Pendiente
- **Evidencia**:

## 8. Corte en `dev` y local (gateway + `mode=http`) y comparación LAB-29

- **Estado**: Pendiente
- **Evidencia**:

## 9. Verificación de reversión (antes de Release B)

- **Estado**: Pendiente
- **Evidencia**:

## 10. Monolito Release B (retirada de `chat`, `orders`, `catalog`, `tickets` y preservación de Olist)

- **Estado**: Pendiente
- **Evidencia**:
