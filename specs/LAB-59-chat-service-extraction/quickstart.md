# Quickstart & Verification Runbook: LAB-59 Extraer chat-service

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Guía de verificación paso a paso en local y en `dev` para validar la extracción sin degradación ni pérdida de datos.

---

## 1. Línea base antes del corte (LAB-29)

Con el monolito aún atendiendo `/api/chat/**` y `/api/assistant/**`:

1. Confirmar que `retrieval-service`, `litellm`, `langfuse` (si aplica) y `backend` están sanos.
2. Ejecutar la evaluación de calidad de respuesta (`shoplab-eval`, LAB-29) sobre el dataset golden vigente y guardar el
   informe como línea base (resumen + detalle por pregunta + modelo + versiones de prompts).
3. Anotar el recuento actual de `chat.conversation_turn` y `chat.feedback` en la base `shoplab`.

---

## 2. Provisión y migración de la base `chat`

```bash
# 1. Levantar postgres y crear rol/base `chat` (idempotente)
docker compose -f infra/docker-compose.yml up -d --wait postgres chat-db-init

# 2. Copiar el esquema `chat` de `shoplab` a `chat`
bash scripts/migrate-chat.sh
# (En Windows PowerShell: pwsh scripts/migrate-chat.ps1)

# 3. Verificar recuentos, checksums, Flyway y secuencias de identidad
bash scripts/verify-chat-migration.sh
# Debe terminar con código 0 y "las dos bases coinciden"
```

---

## 3. Levantar `chat-service` y probar en local

```bash
# Levantar el stack completo (incluye chat-service, retrieval-service, gateway, litellm, backend)
docker compose -f infra/docker-compose.yml up -d --wait

# Verificar salud directa de chat-service (puerto local de depuración 8083)
curl -sf http://localhost:8083/actuator/health

# Verificar que el gateway enruta /api/chat y /api/assistant a chat-service
curl -sf http://localhost:8080/api/assistant/settings | jq .
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: lab59-smoke-01" \
  -d '{"message":"¿Cuál es la política de devoluciones?"}'

# Confirmar en logs del gateway el destino
docker compose -f infra/docker-compose.yml logs gateway | grep "lab59-smoke-01"
# Esperado: routeId=chat destination=http://chat-service:8080
```

---

## 4. Validar integración `eval` → `chat-service`

```bash
# Consultar settings a través de eval (en el monolito, puerto 8080 vía gateway)
curl -sf http://localhost:8080/api/eval/settings | jq .

# Probar degradación: detener chat-service y verificar error controlado en eval
docker compose -f infra/docker-compose.yml stop chat-service
curl -i http://localhost:8080/api/eval/settings
docker compose -f infra/docker-compose.yml start chat-service
```

---

## 5. Verificación en `dev` tras el corte

1. Fusionar el commit de corte en `shoplab-infra` (`gateway`: `chat` y `assistant` → `http://chat-service:8080`;
   `backend`: `SHOPLAB_CHAT_SETTINGS_CLIENT_MODE=http` y `BASE_URL=http://chat-service:8080`).
2. Esperar sincronización sana de Argo CD en `shoplab-dev` (`chat-service`, `gateway`, `backend`).
3. Probar en el frontend:
   - Saludo de cortesía (sin retrieval, LAB-114).
   - Pregunta sobre políticas con streaming SSE y citas a fuentes (LAB-23).
   - Repregunta en la misma conversación (memoria multi-turno, LAB-24).
   - Valoración 👍/👎, listado de feedback y promoción a golden (LAB-33).
   - Confirmar trazas en Langfuse (LAB-64) y métricas de chat en Prometheus/Grafana (LAB-102).
4. Repetir la evaluación de calidad (LAB-29) sobre el mismo dataset golden y modelo, y guardar la comparación frente a
   la línea base.
5. **Reversión (solo antes de Release B)**: revertir el commit de corte en `shoplab-infra` y comprobar que `/api/chat/**`
   y `/api/assistant/**` vuelven al monolito en < 10 minutos.

---

## 6. Verificación tras Release B (retirada de módulos en `backend/`)

```bash
# En shoplab-helpdesk/backend: compilar, pasar pruebas, Checkstyle, PMD y JaCoCo >= 80 %
mvn verify -Dskip.srcclr=true --no-transfer-progress

# Confirmar que los esquemas Olist siguen intactos en PostgreSQL
docker compose -f infra/docker-compose.yml exec -T postgres \
  psql -U shoplab -d shoplab -c "\dt catalog.*" -c "\dt orders.*"
```
