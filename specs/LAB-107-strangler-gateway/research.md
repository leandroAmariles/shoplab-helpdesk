# Research: LAB-107 Gateway estrangulador

Fecha: 2026-11-25. Cada decisión indica de dónde sale. Lo que no está comprobado se marca **por verificar** y tiene
una prueba asignada.

> **Tras implementar**: los puntos «por verificar» de D-4, D-5 y D-6 se cumplen y tienen prueba. En D-7, sin
> `trusted-proxies` las cabeceras `Forwarded` y `X-Forwarded-*` **sí se descartan**; se acepta. Ver
> [validation.md](validation.md).

## D-1. Plataforma

- **Decision**: Java 21, Spring Boot 4.1.2, Spring Cloud 2025.1.5, starter `spring-cloud-starter-gateway-server-webflux`.
- **Rationale**: el backend ya usa Boot 4.1.2 (`backend/pom.xml`) y la constitución exige 4.x. El tren 2025.1 admite
  Boot 4.0.x y 4.1.x ([versiones soportadas](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)).
- **Alternatives**: Boot 3.4.0 de la plantilla (contradice la constitución); variante WebMVC del gateway (la pila del
  proyecto es reactiva, ADR-0011).

## D-2. Origen del repositorio

- **Decision**: crear `shoplab-gateway` copiando `specs/LAB-103-reusable-workflows-service-template/shoplab-service-template/`
  y recortando lo que un gateway no usa.
- **Rationale**: no hay repositorio plantilla publicado en GitHub.
- **Se conserva**: estructura Maven, Dockerfile, flujo `ci.yml`, release-please, Dependabot, idea de `shared` (errores y
  correlación), ArchUnit.
- **Se retira**: R2DBC, JDBC, Flyway, migraciones; **`SecurityConfig` y el resource server OAuth2** (exige JWT en toda
  petición y cortaría el 100 % del tráfico; llega con LAB-60); `ResponseDto`; `logstash-logback-encoder` (Boot ya trae
  logs estructurados); dependencia `shoplab-contracts` (D-9).
- **Se corrige**: el Dockerfile copia `mvnw` y `.mvn`, que la plantilla no incluye; `CorrelationFilter` usa
  `UUID.randomUUID()`, bloqueante en el event loop (el backend ya lo evita en `RequestIdFilter`).

## D-3. Tabla de rutas

- **Decision**: propiedades propias `shoplab.gateway.routes.<nombre>.{prefix,uri,response-timeout}` como **mapa**, y un
  `RouteLocator` que las convierte en rutas. Cada entrada por defecto apunta a `shoplab.gateway.monolith-uri`.
- **Rationale**: los mapas se fusionan entre `application.yml` y variables de entorno, así que cambiar un destino o dar de
  alta `catalog` es solo configuración (FR-005, FR-006). Permite validar al arrancar (FR-010).
- **Alternatives**: `spring.cloud.gateway.server.webflux.routes` (una lista: sobrescribir un elemento por entorno
  reemplaza la lista entera); archivo de rutas externo montado (sin validación propia y duplicado por entorno);
  Spring Cloud Config o descubrimiento de servicios (YAGNI).

## D-4. Coincidencia de prefijos

- **Decision**: predicado `Path` con los dos patrones `<prefix>` y `<prefix>/**`.
- **Rationale**: la semántica de `PathPattern` ya cubre la ruta exacta con `/**`, pero la
  [referencia](https://docs.spring.io/spring-cloud-gateway/reference/5.0/spring-cloud-gateway-server-webflux/request-predicates-factories.html)
  no lo afirma. **Por verificar** con prueba (exacta, subruta, `/api/chatbot`).
- **Además**: rechazar con 404 las rutas con segmentos `.` o `..` (también codificados), para que ningún prefijo permita
  alcanzar `/actuator` del destino (FR-009).

## D-5. Streaming y tiempos de espera

- **Decision**: `connect-timeout` 2 s; `response-timeout` 120 s por defecto, ajustable por ruta. Sin filtros que lean el
  cuerpo, sin compresión, sin reintentos.
- **Rationale**: en dev manda el `proxy-read-timeout` de 60 s de ingress-nginx, que no se toca; 120 s no añade una
  restricción nueva (FR-013). El backend ya envía `X-Accel-Buffering: no` y el gateway debe dejarla pasar.
- **Por verificar** (la documentación 5.0 no lo dice): que `text/event-stream` se vacíe fragmento a fragmento; que
  `response-timeout` no corte un flujo cuyas cabeceras ya llegaron; que cancelar en el cliente cierre la conexión con el
  destino. Las tres pruebas se escriben primero: si alguna falla, el diseño se revisa antes de seguir.

## D-6. Subida de documentos

- **Decision**: el gateway no impone tamaño máximo; el cuerpo se retransmite en flujo.
- **Por verificar**: con un archivo mayor de 10 MiB, que el cliente reciba el error tipado del backend y no un fallo del
  proxy al rechazar antes de terminar de leer.

## D-7. Cabeceras

- **Decision**: conservar `Host`; no configurar `trusted-proxies`; `X-Request-Id` se conserva si cumple
  `^[A-Za-z0-9._-]{1,64}$` (la regla del backend) y si no se genera; la respuesta lleva **una sola** `X-Request-Id`;
  `traceparent` se conserva o se genera con formato W3C.
- **Rationale**: conservar `Host` deja al destino viendo lo mismo que hoy. `trusted-proxies` activa los filtros
  `Forwarded`/`X-Forwarded-*`
  ([referencia](https://docs.spring.io/spring-cloud-gateway/reference/5.0/spring-cloud-gateway-server-webflux/httpheadersfilters.html));
  no se encontró consumidor en la configuración del backend. **Por verificar** que sin él las cabeceras entrantes no
  rompan FR-007. LAB-60 lo revisará si limita por IP.
- **Alternatives**: Micrometer Tracing con OpenTelemetry (no hay colector para estos servicios; se añadirá cuando lo
  haya).

## D-8. Errores propios

- **Decision**: un `ErrorWebExceptionHandler` único con `ResponseCodeEnum` propio; ver
  [contracts/routing.md](contracts/routing.md#errores-del-gateway).
- **Rationale**: los errores nacen en filtros, donde `@ControllerAdvice` no llega. Las respuestas de error del destino no
  son excepciones y pasan sin tocarse.

## D-9. Formato de error sin dependencia de `shoplab-contracts`

- **Decision**: un `record` local `{code, message, detail}` y una prueba contra el esquema `ApiErrorResponse`.
- **Rationale**: el JAR está en GitHub Packages y `java-service.yml@v1` ejecuta `mvn clean verify` sin credenciales de
  Maven. El gateway solo necesita tres campos.
- **Alternatives**: depender del JAR y arreglar el workflow en `shoplab-ci` (amplía el alcance a otro repo y su tag `v1`).
- **Coste**: se aparta de la regla de LAB-103 de fijar `shoplab-contracts`; queda anotado en el plan.

## D-10. Publicación y despliegue

Leído en `shoplab-ci@v1` y en la plantilla:

1. `ci.yml` solo corre en `main` y PR, así que `publish-image.yml` nunca etiqueta `:vX.Y.Z`.
2. `deploy.yml` corre en tags y `deploy-infra.sh` exige que `:vX.Y.Z` exista. Tal como está la plantilla, **todo
   despliegue fallaría**.
3. La plantilla no trae flujo de release-please.

- **Decision**: en `shoplab-gateway`, `release-please.yml` (con `RELEASE_PLEASE_TOKEN`, como `shoplab-helpdesk`, para
  que el tag dispare flujos) y `release.yml` en tags `v*`, que llama a `publish-image.yml@v1` y después a `deploy.yml@v1`
  con `service-name: gateway` y `overlay: apps/gateway/overlays/dev`. No se modifica `shoplab-ci`.
- **Nombre**: servicio e imagen `gateway` (`ghcr.io/leandroamariles/gateway`), igual que `backend`, `frontend`, `ingest`.
  `deploy-infra.sh` exige que imagen y servicio coincidan.

## D-11. Dev

- **Decision**: `apps/gateway/{base,overlays/dev}` en `shoplab-infra`, sin ExternalSecret; destinos en un
  `configMapGenerator` (el hash en el nombre reinicia el pod al cambiar); endurecimiento igual al del backend
  (`runAsUser` numérico, raíz de solo lectura, `/tmp` en `emptyDir`); ServiceMonitor.
- **Dos cambios en orden**: (1) alta del gateway sin tráfico y comprobación; (2) `/api` del ingress pasa de `backend` a
  `gateway`. Revertir (2) devuelve el tráfico directo.
- **Riesgo**: `apps/backend` y `k8s/base/backend` declaran ambos un Deployment `backend`, y las dos Applications apuntan a
  `shoplab-dev`. No se comprobó en el clúster (no había contexto de `kubectl`). Hay que revisarlo antes de (1).
- El CI de `shoplab-infra` construye los overlays de `k8s/`; no se vio que valide `apps/`. Se valida a mano.

## D-12. Local

Gateway publicado en el **8080** del host y monolito en el **8081**.

- **Rationale**: 8080 ya es «la API» para `proxy.conf.json` del frontend, `SHOPLAB_EVAL_BASE_URL` y los scripts; así no
  se toca ningún otro repo.
- **Coste**: quien arranque el backend desde el IDE usa `SERVER_PORT=8081`; hay que ajustar Prometheus local y la
  prueba e2e.
- **Alternatives**: gateway en 8090 (obliga a cambiar `shoplab-frontend` y `shoplab-eval`).

Fallos de LAB-110 vistos al leer los archivos (no se ejecutó nada), que impiden validar esta historia:

- Compose publica el frontend contra el puerto 80 y comprueba salud en el 80; su nginx escucha en 8080.
- Ese nginx no reenvía `/api`.
- Los valores por defecto apuntan a `ghcr.io/leandroamariles/shoplab-backend|frontend|ingest`; los paquetes listados en
  GHCR son `backend`, `frontend` e `ingest`.

Se corrigen dentro de esta historia, solo en `shoplab-helpdesk`, con un nginx montado por compose que reenvía `/api/` al
gateway sin buffering.

## D-13. Pruebas

- **Decision**: JUnit 5 + `WebTestClient`, con destinos simulados por el servidor HTTP de Reactor Netty en el propio
  proceso (control de pausas, fragmentos y cancelación). Sin Testcontainers: no hay estado.
- **Métricas**: la guía 5.0 da el filtro de métricas por activo y el apéndice 5.1 por inactivo; se activa de forma
  explícita y una prueba comprueba `spring.cloud.gateway.requests`.
