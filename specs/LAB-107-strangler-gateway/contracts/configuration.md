# Contrato de configuración y entrega

## Variables de entorno del gateway

| Variable | Por defecto | Efecto |
|---|---|---|
| `GATEWAY_MONOLITH_URI` | `http://localhost:8081` | destino de las rutas que no se hayan movido |
| `SHOPLAB_GATEWAY_ROUTES_<NOMBRE>_URI` | monolito | mueve esa ruta |
| `SHOPLAB_GATEWAY_ROUTES_<NOMBRE>_PREFIX` | — | necesaria solo para una ruta nueva |
| `SHOPLAB_GATEWAY_ROUTES_<NOMBRE>_RESPONSETIMEOUT` | `120s` | tiempo máximo hasta las cabeceras de respuesta |
| `SERVER_PORT` | `8080` | puerto del gateway |

Mover `documents` y `knowledge` (LAB-129):

```text
SHOPLAB_GATEWAY_ROUTES_DOCUMENTS_URI=http://retrieval-service:8080
SHOPLAB_GATEWAY_ROUTES_KNOWLEDGE_URI=http://retrieval-service:8080
```

Dar de alta `catalog`:

```text
SHOPLAB_GATEWAY_ROUTES_CATALOG_PREFIX=/api/catalog
SHOPLAB_GATEWAY_ROUTES_CATALOG_URI=http://catalog-service:8080
```

Revertir es borrar la variable.

## Dev (`shoplab-infra`)

- `apps/gateway/base`: Deployment, Service `gateway` (puerto `http` 8080), ServiceMonitor, Kustomization. Imagen
  `ghcr.io/leandroamariles/gateway` sin tag.
- `apps/gateway/overlays/dev`: namespace `shoplab-dev`; `configMapGenerator` `gateway-config` con
  `GATEWAY_MONOLITH_URI=http://backend:8080`; sección `images:` con `- name: ghcr.io/leandroamariles/gateway` y
  `newTag` (la edita el pipeline).
- `k8s/base/ingress/ingress.yaml`: `/api` apunta al Service `gateway`; `/` sigue en `frontend`;
  `proxy-body-size: "12m"` no cambia.

## Entorno local (`shoplab-helpdesk`)

| Servicio | Puerto host → contenedor | Nota |
|---|---|---|
| `gateway` | 8080 → 8080 (`GATEWAY_PORT`) | imagen `GATEWAY_IMAGE` fijada en `.env` |
| `backend` | 8081 → 8080 (`BACKEND_PORT`) | publicado solo para depurar |
| `frontend` | 4200 → 8080 | nginx montado que reenvía `/api/` a `gateway:8080` sin buffering |

Backend desde el IDE: `SERVER_PORT=8081` y `GATEWAY_MONOLITH_URI=http://host.docker.internal:8081`.

## Requisitos previos manuales (una vez)

Los hace la persona dueña de las cuentas; no son parte del código.

1. Crear el repositorio `leandroAmariles/shoplab-gateway`.
2. En él: secretos `RELEASE_PLEASE_TOKEN` y `DEPLOY_APP_PRIVATE_KEY`, variable `DEPLOY_APP_ID`, entorno `dev`.
3. Dar a la GitHub App de despliegue acceso a `shoplab-infra` (si no lo tiene ya).
4. Tras la primera publicación, hacer **público** el paquete `gateway` en GHCR: `deploy-infra.sh` inspecciona la imagen
   sin iniciar sesión en el registro.
