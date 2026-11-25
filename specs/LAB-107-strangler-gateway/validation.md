# Validación: LAB-107

Fecha: 2026-11-25. Máquina local (Windows 11, Docker 29.6.1) y GitHub Actions.

## Hecho

| Qué | Cómo | Resultado |
|---|---|---|
| Pruebas del gateway | `./mvnw verify` | 118 sin fallos; 98,8 % de líneas; Checkstyle y PMD limpios |
| CI del gateway | PR `shoplab-gateway#1` | verde (pruebas, cobertura, Qodana, Trivy sin vulnerabilidades) |
| Cadena nginx → gateway → backend `v0.26.0` | contenedores en una red desechable | `/api/documents` 200, `/api/catalog` 404 `SLHD_GATE_00001` |
| Subida > 10 MiB (D-6) | directa, por gateway y por nginx | en los tres, 413 `SLHD_RETR_00002` del backend |
| Mover una ruta (US3) | recrear solo el gateway, destino simulado | `documents` movida, `eval` en el monolito, frontend sin recrear |
| Streaming (SC-002) | destino simulado, un fragmento por segundo, a través de nginx | llegan a 0,1 / 1,1 / 2,1 / 3,1 s |
| Destino caído (SC-005) | parar el backend | 503 `SLHD_GATE_00002`; la ruta movida y la salud siguen bien |
| Latencia añadida (SC-003) | 500 `GET /api/documents` alternadas | p50 13,0 → 17,3 ms; p95 28,9 → 28,6 ms |
| Correlación (SC-006) | chat con `X-Request-Id: lab107-corr` | aparece en los logs del gateway y del backend |
| Alta en dev | `kustomize build` y `scripts/policy-check.sh` | sin violaciones; no desplegado |

## Sin hacer

- `docker compose up` completo sobre el entorno del desarrollador (T056): necesita la imagen
  `ghcr.io/leandroamariles/gateway:v0.1.0`, que aún no existe, y no se tocaron sus contenedores.
- Paridad con las métricas de retrieval de `shoplab-eval` y recorrido por la web (SC-001): necesitan el modelo.
- Publicación, paquete público y despliegue en dev (T045–T047, T059, T060, T062, T063).

## Cambios respecto al plan

- **`Forwarded` y `X-Forwarded-*`**: el gateway los descarta sin `trusted-proxies`. Se mantiene ese valor seguro.
- **Pruebas**: una JVM por clase; cerrar un contexto de Spring destruye los recursos globales de Reactor Netty.
- **`qodana.yaml`**: nombre completo de la imagen, `2025.2` y `projectJDK: "21"`; con la `2024.2` los `record` se leían
  como métodos. Qodana avisa de que esa imagen no casa con la versión de su CLI en `shoplab-ci`.
- **Dockerfile**: el endurecido del backend en lugar del de la plantilla.

## Problemas encontrados fuera de esta historia

### Plantilla de LAB-103 y `shoplab-ci` (abrir una historia)

- La plantilla nunca publica la imagen `:vX.Y.Z` que su `deploy.yml` exige.
- Su `SecurityConfig` exige JWT en toda petición; el Dockerfile copia `mvnw` y `.mvn`, que no incluye.
- Declara Spring Boot 3.4.0; la constitución exige 4.x.
- No existe el repositorio `shoplab-service-template` que cita su guía.
- `java-service.yml@v1` usa una versión antigua de la acción de Qodana y no pasa credenciales a Maven.

### Dev (`shoplab-infra`, de LAB-104) — bloquea T059

`apps/backend` y `k8s/overlays/dev` declaran el mismo Deployment `backend` en `shoplab-dev`, con las imágenes `v1.0.0`
(no existe) y `v0.26.0`. Comprobado con `kubectl kustomize`; no se miró el clúster, que estaba apagado.

### Publicación del backend

La imagen `v0.27.0` no se publicó: la construcción falló al descargar Maven. La última es `v0.26.0`, anterior a
LAB-106, así que `/api/assistant/settings` responde 404 desde el propio backend.
