# F5 · Mover rutas al gateway (LAB-107)

Cómo se lleva un prefijo de la API del monolito a un servicio extraído sin tocar el frontend. El gateway vive en
`shoplab-gateway`; la decisión está en [ADR-0032](../adr/0032-gateway-estrangulador.md).

## Rutas

| Nombre | Prefijo | Lo mueve |
|---|---|---|
| `documents`, `knowledge` | `/api/documents`, `/api/knowledge` | LAB-129 (`retrieval-service`) |
| `chat`, `assistant` | `/api/chat`, `/api/assistant` | LAB-59 (`chat-service`) |
| `eval` | `/api/eval` | LAB-130 (`eval-service`) |
| nuevas: `catalog`, `orders`, `tickets` | `/api/<nombre>` | cuando nazca cada servicio |

## Antes de mover

- El servicio responde las mismas rutas, métodos y errores que el monolito (el gateway no reescribe nada).
- Está desplegado y sano, y sus datos migrados.

## En dev

En `shoplab-infra`, archivo `apps/gateway/overlays/dev/kustomization.yaml`, añadir al `configMapGenerator`
`gateway-config`:

```yaml
      - SHOPLAB_GATEWAY_ROUTES_DOCUMENTS_URI=http://retrieval-service:8080
      - SHOPLAB_GATEWAY_ROUTES_KNOWLEDGE_URI=http://retrieval-service:8080
```

Fusionar. Argo CD aplica el cambio y el pod se reinicia solo porque cambia el nombre del ConfigMap.

Un prefijo nuevo se declara igual, con su prefijo:

```yaml
      - SHOPLAB_GATEWAY_ROUTES_CATALOG_PREFIX=/api/catalog
      - SHOPLAB_GATEWAY_ROUTES_CATALOG_URI=http://catalog-service:8080
```

## En local

```bash
SHOPLAB_GATEWAY_ROUTES_DOCUMENTS_URI=http://retrieval-service:8080 \
  docker compose -f infra/docker-compose.yml up -d --no-deps gateway
```

También sirve definir la variable en `infra/.env`. Un prefijo nuevo se declara en
`infra/docker-compose.override.yml` (ver el ejemplo).

## Comprobar

El registro del gateway dice a dónde fue cada petición:

```bash
docker compose -f infra/docker-compose.yml logs gateway | grep "access requestId"   # local
kubectl -n shoplab-dev logs deploy/gateway | grep "access requestId"                # dev
```

`routeId=documents destination=http://retrieval-service:8080` confirma el movimiento.

## Revertir

Quitar la variable (o revertir el commit en `shoplab-infra`). El prefijo vuelve al monolito. Los datos escritos en el
servicio mientras estuvo activo **no** vuelven solos: cada historia de extracción define cómo tratarlos.

## Si algo falla

| Síntoma | Causa probable |
|---|---|
| 503 `SLHD_GATE_00002` | el destino no existe, no resuelve o está caído |
| 504 `SLHD_GATE_00003` | no respondió en el tiempo de la ruta (`..._RESPONSETIMEOUT`, 120 s) |
| 404 `SLHD_GATE_00001` | el prefijo no está declarado |
| El gateway no arranca | configuración inválida; el log nombra la ruta |

En dev, el ingress corta antes, a los 60 s sin recibir datos.
