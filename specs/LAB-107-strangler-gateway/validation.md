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
| Con el modelo local | imagen publicada `sha-cf394e8`, base de datos desechable, litellm e ingest locales | 24 documentos cargados por nginx y gateway |
| Paridad de búsqueda (SC-001) | 120 consultas de `golden.jsonl`, directas y por el gateway | idénticas (ver nota) |
| Chat real (US2) | `POST /api/chat` por nginx y gateway | 217 líneas `data`, de 9,0 s a 20,0 s |
| `scripts/test-e2e.ps1` | sobre ese entorno | gateway, backend, API e ingest bien; fallaba la comprobación de LiteLLM (corregida, sin repetir la pasada) |
| Paquete en GHCR | descarga de etiquetas sin credenciales | público desde la primera publicación |

Nota de paridad: la primera pasada, justo después de cargar los documentos, dio 9 diferencias de 120. La repetición dio
cero, tanto entre dos llamadas directas como entre directa y gateway. La causa probable es que aún se indexaba; no se
comprobó.

## En dev (clúster local con `make up`)

| Qué | Cómo | Resultado |
|---|---|---|
| Publicación | tag `v0.1.0` en `shoplab-gateway` | imagen publicada, firmada y verificada |
| Alta del gateway | `shoplab-infra#35` | `gateway-dev` Synced y Healthy |
| Paridad antes del corte | túnel al Service `gateway` frente al ingress | respuestas idénticas; `/api/catalog` 404 propio |
| Corte del ingress | `shoplab-infra#36` | `/api` en el gateway 76 s después del merge |
| API por ingress y gateway | `curl` con `Host: shoplab.localtest.me` | documentos, búsqueda y evaluaciones 200; archivo > 10 MiB 413 del backend |
| Chat en streaming | `POST /api/chat` por ingress y gateway (Ollama expuesto durante la prueba) | 102 líneas `data`, de 18,9 s a 23,8 s |
| Cambio de configuración (SC-004) | `shoplab-infra#38` y su reversión `#39` | activo en 231 s; revertido en 183 s |

## Sin hacer

- `docker compose up` completo (T056): la base `shoplab` local rechaza la contraseña que toma el compose, así que el
  backend no arranca contra ella. No se modificó; se probó con contenedores sueltos y una base desechable.
- Métricas de retrieval con la herramienta de `shoplab-eval` (se compararon las respuestas crudas) y recorrido por la web
  en el navegador.
- Despliegue automático desde `shoplab-gateway` (T003, T004, T060): faltan `DEPLOY_APP_ID` y `DEPLOY_APP_PRIVATE_KEY`.
  La `v0.1.0` se desplegó porque el overlay la declara; **la próxima versión no se desplegará sola**.

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

### Dev (`shoplab-infra`, de LAB-104)

- `apps/backend` y `k8s/overlays/dev` declaraban el mismo Deployment `backend` en `shoplab-dev`. **Resuelto**: el backend
  queda solo en `k8s/` (`shoplab-infra#37`). No se miró el clúster, que estaba apagado.
- El `smoke` de CI falla desde el merge de LAB-104 en `main`: `terraform apply` agota los 480 s.

### `shoplab-ci`: la firma nunca se verifica a través de `java-service.yml@v1`

`publish-image.yml` compara el certificado distinguiendo mayúsculas con `github.com/leandroAmariles/`, y
`java-service.yml` lo invoca como `leandroamariles`. La imagen se sube y se firma, y el job falla después. El gateway lo
llama directamente con la grafía correcta.

### Publicación del backend

La imagen `v0.27.0` no se publicó: la construcción falló al descargar Maven. La última es `v0.26.0`, anterior a
LAB-106, así que `/api/assistant/settings` responde 404 desde el propio backend.
