# Quickstart: validar el gateway de modelos

Guía de validación de extremo a extremo. Los detalles de configuración están en [contracts/](contracts/) y el modelo en
[data-model.md](data-model.md). Todo corre en local sin claves de nube.

## Requisitos

- Docker con Compose v2, `jq`, JDK 21 y Maven (backend).
- Opcional (fallback): `OLLAMA_FALLBACK_MODEL=llama3.2:1b` en `infra/.env` antes del primer arranque, para que Ollama
  descargue el modelo de respaldo.

## 1. Levantar el entorno (US1)

```bash
docker compose -f infra/docker-compose.yml up -d --wait
docker compose -f infra/docker-compose.yml ps        # litellm y litellm-postgres healthy; litellm-bootstrap exited (0)
curl -s http://localhost:4000/health/liveliness
```

**Esperado**: el gateway responde; las claves `backend-local` e `ingest-local` existen
(`docker compose … logs litellm-bootstrap` → `created alias=…` la primera vez, `unchanged` las siguientes).

## 2. Todo pasa por el gateway (US1, SC-001)

```bash
cd backend && mvn spring-boot:run
# En otra terminal: una pregunta al chat y la subida de un documento (genera embeddings)
curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"¿Cuál es el plazo de devolución?"}'
docker compose -f infra/docker-compose.yml logs --no-log-prefix litellm | jq -c 'select(.event=="llm_request")' | tail
```

**Esperado**: líneas `operation: chat` y `operation: document-index|search-query`; Ollama solo recibe peticiones de
`litellm` (`docker compose … logs ollama`). `docker compose … port ollama 11434` no devuelve un puerto publicado, y `docker compose … exec ingest python -c "import socket; socket.create_connection(('ollama', 11434), 2)"` falla (`ingest` no alcanza a Ollama).

## 3. Cambiar de modelo sin tocar el backend (US2, SC-002)

1. En `infra/.env` cambia `LITELLM_CHAT_PRIMARY_MODEL` por otro modelo ya descargado (o edita `config.yaml`).
2. `docker compose -f infra/docker-compose.yml up -d litellm` (recrea el gateway; el backend sigue arrancado). Si solo editaste el `config.yaml` montado, usa `docker compose -f infra/docker-compose.yml restart litellm`.
3. Repite la pregunta del paso 2.

**Esperado**: `served_model` del log y de la traza (si `llmops` está activo) muestran el modelo nuevo; el backend no se
reinició.

## 4. Fallback demostrable (US3, SC-003)

```bash
# Hace inalcanzable el principal: apunta a un modelo que no está descargado
LITELLM_CHAT_PRIMARY_MODEL=modelo-que-no-existe docker compose -f infra/docker-compose.yml up -d litellm
curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' -d '{"message":"Hola"}'
docker compose -f infra/docker-compose.yml logs --no-log-prefix litellm | jq -c 'select(.fallback==true)' | tail -1
```

**Esperado**: respuesta completa y sin error; la línea de log muestra `fallback: true` y
`served_model_id: chat-fallback`. Al restaurar la variable y recargar, `fallback` vuelve a `false`. El procedimiento
completo (incluido el caso «contenedor parado») está en `docs/gateway-de-modelos.md`.

## 5. Presupuesto (US4, SC-005)

```bash
# Presupuesto mínimo para la clave del backend (keys.yaml → max_budget: 0.0001) y recarga de claves
docker compose -f infra/docker-compose.yml run --rm litellm-bootstrap
# Varias preguntas hasta agotarlo
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"message":"Hola"}'
```

**Esperado**: tras agotarlo, el chat responde `429` con `SLHD_CHAT_00007` (distinto del `503 SLHD_CHAT_00002`), el log del
gateway muestra `result: budget_exceeded`, y `ingest-local` sigue aceptando peticiones. Restaura `max_budget` y vuelve a
ejecutar `litellm-bootstrap`.

## 6. Coste por conversación (US5, SC-006)

Haz una conversación de 3 turnos enviando el mismo `conversationId` (un UUID, p. ej. `-d '{"message":"…","conversationId":"<uuid>"}'`;
sin él el turno no se agrupa) y ejecuta la consulta de
[contracts/log-schema.md](contracts/log-schema.md#coste-y-tokens-de-una-conversación-fr-027-sc-006).

**Esperado**: `turns: 3`; tokens y coste iguales a la suma de las tres líneas; el log no contiene ni la pregunta ni la
respuesta (`grep -c "devolución"` sobre el log del gateway devuelve 0).

## 7. Proveedor cloud opcional (US6)

- **Sin clave** (`CLOUD_PROVIDER_API_KEY` vacío): `curl -s http://localhost:4000/v1/models -H "Authorization: Bearer $LITELLM_KEY_BACKEND" | jq '.data[].id'`
  no lista `chat-cloud`; pedirlo devuelve un error claro y el gateway no sale a Internet.
- **Con clave** (o con el servidor simulado de las pruebas): tras reiniciar el gateway, `chat-cloud` aparece y su uso queda
  en el log con su coste. La suite automática lo verifica con un servidor simulado, sin cuenta real.

## 8. Clúster DEV (US7, SC-008)

```bash
kubectl -n shoplab-dev get pods -l app.kubernetes.io/name=litellm
kubectl -n shoplab-dev logs deploy/litellm | jq -c 'select(.event=="llm_request")' | tail -3
kubectl -n shoplab-dev get job litellm-bootstrap-keys
```

**Esperado**: gateway y Postgres `Ready`; una pregunta al chat de DEV aparece en el log con `consumer: backend-dev`; tras
`destroy`/`apply`, Argo CD vuelve a dejarlo funcionando y el Job recrea las claves sin pasos manuales.

## 9. Automatizado (CI)

```bash
cd backend && mvn test -Dskip.srcclr=true --no-transfer-progress           # GatewayStubs, errores, fallback, presupuesto
cd ../infra/litellm && uv run pytest                                         # bootstrap_keys.py y shoplab_callbacks.py
docker compose -f infra/docker-compose.yml config -q                         # core
docker compose -f infra/docker-compose.yml --profile llmops --profile observability config -q
```

**Esperado**: todo en verde, cobertura del backend ≥ 80 %, sin imágenes `latest` ni secretos nuevos
(`git grep -nE ':latest|sk-[A-Za-z0-9]{20,}'` sin resultados añadidos).

## Medición de SC-004 (sobrecoste)

Mide el tiempo hasta el primer token de la misma pregunta 20 veces con el gateway y 20 con Ollama directo (rama anterior o
`curl` a `/api/chat`); la mediana con gateway debe estar < 10 % por encima. El resultado se anota en
`validation.md` al cerrar.
