# Implementation Plan: LAB-107 Gateway estrangulador antes de extraer servicios

**Branch**: `feature/LAB-107-strangler-gateway` | **Date**: 2026-11-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-107-strangler-gateway/spec.md`

## Summary

Un servicio nuevo y sin estado, `shoplab-gateway`, recibe todo `/api` y lo reenvía por prefijo al monolito. El destino de
cada prefijo es configuración, de modo que LAB-129, LAB-59 y LAB-130 extraen servicios sin tocar el frontend ni
publicar el gateway. El trabajo toca tres repositorios: el gateway (código), `shoplab-infra` (alta y cambio del ingress)
y `shoplab-helpdesk` (compose, ADR y documentación). Las decisiones están en [research.md](research.md).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1.2, Spring Cloud 2025.1.5 (Gateway server WebFlux), Actuator, Micrometer
Prometheus

**Storage**: N/A (sin estado)

**Testing**: JUnit 5, `WebTestClient`, destinos simulados con Reactor Netty, ArchUnit, JaCoCo

**Target Platform**: contenedor Linux; dev en EKS local (Floci) con Argo CD; local con docker compose

**Project Type**: servicio web (proxy inverso) más cambios de infraestructura y entorno

**Performance Goals**: menos de 200 ms añadidos por fragmento en streaming; menos de 50 ms en p95 en el resto

**Constraints**: no bloquear el event loop; no leer ni acumular cuerpos; límites no más estrictos que los actuales;
sin autenticación ni rate limiting

**Scale/Scope**: 5 rutas, 1 destino inicial, 1 réplica en dev

## Constitution Check

*GATE: evaluado antes de la investigación y de nuevo tras el diseño.*

| Principio | Estado | Cómo se cumple |
|---|---|---|
| I. Monolito primero, extraer después | PASA | habilita la extracción de ADR-0031; la decisión se registra en ADR-0032 |
| II. Hexagonal y dominio puro | DESVIACIÓN | no hay dominio ni casos de uso; ver Complexity Tracking |
| III. Inglés y errores tipados | PASA | identificadores en inglés, un `ResponseCodeEnum`, un único manejador |
| IV. Pruebas primero y 80 % | PASA | pruebas de contrato R1-R6, streaming y errores antes del código; puerta JaCoCo |
| V. RAG guiado por evaluación | PASA | no cambia prompt, troceado, embeddings ni retrieval; paridad medida con `shoplab-eval` |
| VI. Observabilidad y seguridad | PASA | logs estructurados con correlación, métricas por ruta, `traceparent`; sin secretos. No exporta spans (no hay colector para estos servicios) |
| VII. Simplicidad y decisiones | PASA | sin descubrimiento, config server ni resiliencia avanzada; ADR-0032 |

Tras el diseño: sin cambios; las desviaciones quedan justificadas abajo.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-107-strangler-gateway/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── routing.md
│   └── configuration.md
├── checklists/requirements.md
└── tasks.md                 # lo genera /speckit-tasks
```

### Source Code

```text
shoplab-gateway/                      # repositorio nuevo
├── pom.xml, mvnw, .mvn/, Dockerfile, qodana.yaml
├── release-please-config.json, .release-please-manifest.json
├── .github/{dependabot.yml, workflows/{ci,release-please,release}.yml}
└── src/
    ├── main/java/com/shoplab/gateway/
    │   ├── GatewayApplication.java
    │   ├── config/                   # solo propiedades; sin lógica (fuera de cobertura)
    │   ├── infrastructure/route/     # validación y tabla de rutas -> RouteLocator
    │   ├── infrastructure/filter/    # correlación, rutas inseguras, registro de acceso
    │   └── shared/error/             # ResponseCodeEnum, ApiError, GlobalExceptionHandler
    ├── main/resources/application.yml
    └── test/java/com/shoplab/gateway/

shoplab-infra/
├── apps/gateway/base/                # deployment, service, servicemonitor, kustomization
├── apps/gateway/overlays/dev/        # kustomization (config e imagen)
└── k8s/base/ingress/ingress.yaml     # /api -> gateway

shoplab-helpdesk/
├── infra/{docker-compose.yml, docker-compose.override.yml.example, .env.example}
├── infra/frontend/nginx.conf         # nuevo: /api/ -> gateway sin buffering
├── infra/observability/prometheus/prometheus.yml
├── scripts/{clone-all.ps1, test-e2e.sh, test-e2e.ps1}, Makefile, README.md
└── docs/{adr/0032-gateway-estrangulador.md, adr/README.md, mapa-servicios.md, fases/F5-mover-rutas-al-gateway.md}
```

**Structure Decision**: repositorio propio para el gateway (FR-001, ADR-0031). `shoplab-ci` y `shoplab-frontend` no se
modifican.

## Orden de entrega

Cada paso deja el sistema funcionando.

1. **Gateway**: repositorio, primero las tres pruebas de riesgo (vaciado SSE, tiempo de espera en flujo largo,
   cancelación), después enrutamiento, errores, cabeceras y observabilidad.
2. **Publicación**: `v0.1.0` en GHCR (requiere los pasos manuales de [configuration.md](contracts/configuration.md)).
3. **Local**: compose, nginx del frontend, scripts y Prometheus.
4. **Dev, sin tráfico**: comprobar el conflicto de `backend` (research D-11) y dar de alta `apps/gateway`.
5. **Dev, corte**: ingress `/api` → `gateway`; validar; si falla, revertir ese commit.
6. **Documentación**: ADR-0032, mapa de servicios y guía para mover rutas.

## Riesgos

| Riesgo | Efecto | Mitigación |
|---|---|---|
| El streaming se retiene o se corta (no documentado en 5.0) | rompe el chat | pruebas antes que el resto; si fallan, revisar D-5 |
| La cadena de publicación de la plantilla no despliega | el gateway no llega a dev | flujo `release.yml` propio (D-10); es la primera vez que se usará |
| Dos Applications gestionan `backend` en `shoplab-dev` | dev inestable al añadir el gateway | comprobarlo antes del paso 4 |
| Cambio de puerto local del monolito | arranques desde el IDE fallan | documentarlo en README y `.env.example` |
| Gateway como punto único de fallo | caída de toda la API en dev | sondas, sin estado; revertir el ingress |

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Sin capas `domain`/`application` (principio II) | un proxy no tiene reglas de negocio | puertos y casos de uso vacíos añadirían código sin comportamiento; ArchUnit solo comprueba que `shared` no dependa de `infrastructure` y que no haya dependencias del monolito |
| Sin dependencia de `shoplab-contracts` (regla de LAB-103) | el CI reutilizable no tiene credenciales para GitHub Packages | arreglarlo exige cambiar `shoplab-ci@v1`; el formato se verifica con una prueba (D-9) |
| Sin resource server JWT de la plantilla | exigiría token en todo el tráfico antes de existir el login | activarlo en modo permisivo es código muerto hasta LAB-60 |
| Flujo `release.yml` propio en vez del `deploy.yml` de la plantilla | el de la plantilla no publica la imagen que despliega | arreglar `shoplab-ci` queda fuera de esta historia |
| Corregir compose de LAB-110 aquí | sin ello no se puede validar US4 en local | una historia aparte bloquearía LAB-107 |
