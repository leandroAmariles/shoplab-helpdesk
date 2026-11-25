# Quickstart: validar LAB-107

Contratos: [routing.md](contracts/routing.md), [configuration.md](contracts/configuration.md). Son guías de validación;
los comandos del gateway solo existen después de implementar.

## Requisitos

JDK 21, Docker, `shoplab-gateway` clonado junto a `shoplab-helpdesk`. Para dev: el clúster de `shoplab-infra`
(`make up`).

## 1. Pruebas automáticas (en `shoplab-gateway`)

```bash
./mvnw verify
```

Esperado: pasan las pruebas de enrutamiento (R1-R6), streaming, cancelación, tiempos, errores, cabeceras, validación de
configuración y movimiento de ruta; cobertura ≥ 80 %; sin avisos de Checkstyle ni PMD.

## 2. Entorno local completo (en `shoplab-helpdesk`)

```bash
cp infra/.env.example infra/.env
docker compose -f infra/docker-compose.yml up -d --wait
curl -i http://localhost:8080/api/documents          # 200, una sola X-Request-Id
curl -i http://localhost:8080/api/assistant/settings # 200
curl -i http://localhost:8080/api/catalog            # 404 SLHD_GATE_00001
curl -N -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
  -d '{"message":"¿Cómo devuelvo un producto?"}'     # fragmentos que aparecen poco a poco
```

Abrir `http://localhost:4200` y recorrer chat, feedback, documentos y evaluaciones.

## 3. Paridad (SC-001)

Con el sistema levantado: subir un documento de casi 10 MiB (aceptado) y otro mayor (error tipado del backend), y
ejecutar las métricas de retrieval de `shoplab-eval` contra `:8080` y contra `:8081`. Deben coincidir.

## 4. Mover una ruta (SC-004)

Levantar un sustituto, apuntar `SHOPLAB_GATEWAY_ROUTES_DOCUMENTS_URI` hacia él y recrear solo `gateway`.
Esperado: `/api/documents` lo atiende el sustituto, `/api/knowledge/search` sigue en el monolito y el contenedor
`frontend` no se recrea. Quitar la variable lo devuelve al monolito.

## 5. Destino caído (SC-005)

`docker compose stop backend` → `/api/documents` responde 503 `SLHD_GATE_00002` sin detalles internos y
`http://localhost:8080/actuator/health` sigue `UP`.

## 6. Rendimiento (SC-002, SC-003)

Comparar 100 peticiones a `GET /api/documents` por `:8080` y `:8081` (p95 añadido < 50 ms). En el chat, comparar la hora
de llegada de los fragmentos contra un destino simulado de pausas conocidas (retraso añadido < 200 ms).

## 7. Correlación (SC-006)

Enviar un chat con `-H "X-Request-Id: lab107-check"` y buscar `lab107-check` en los logs de `gateway` y de `backend`.

## 8. Dev

```bash
kustomize build apps/gateway/overlays/dev | bash scripts/policy-check.sh /dev/stdin dev   # en shoplab-infra
kubectl -n argocd get application gateway-dev      # Synced / Healthy
curl -i http://shoplab.localtest.me:8080/api/documents
kubectl -n shoplab-dev logs deploy/gateway         # una línea por petición de API
```

Si `/dev/stdin` no está disponible (Git Bash), renderizar a un archivo temporal y pasarlo a `policy-check.sh`.

Repetir el chat en el navegador y comprobar que el texto aparece de forma progresiva a través de ingress y gateway.
