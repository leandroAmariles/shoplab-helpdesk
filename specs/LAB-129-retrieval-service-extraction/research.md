# Research: LAB-129

Hallazgos verificados el 2026-11-25 leyendo los repositorios. Lo no comprobado se marca como tal.

## D-1 Cliente generado para el chat

- **Decision**: en `shoplab-contracts/generators/java`, añadir una ejecución con `generatorName=spring`,
  `library=spring-http-interface`, reactiva, solo para el tag `KnowledgeSearch` y reutilizando los modelos
  `com.shoplab.contracts.retrieval.model`. `spring-web` como dependencia opcional. Versión 1.3.0.
- **Rationale**: hoy `generateApis=false`, no hay cliente. Una interfaz `@HttpExchange` se monta sobre el `WebClient`
  existente (tiempo de espera, filtros, observación) y no trae su propio cliente HTTP ni su Jackson.
- **Alternatives**: `java` con `library=webclient` (genera `ApiClient` atado a Jackson 2); `java/native` (bloqueante o
  `CompletableFuture`); quedarse con `HttpKnowledgeSearchClient` escrito a mano (no cumple la historia).
- **Comprobado (T004, 2026-11-25)**: compila con los modelos actuales; bajo Boot 4.1.2 deserializa la respuesta y
  codifica `{ } & + # % ?` y acentos de modo que el servidor recupera el texto exacto. Hacen falta `spring-web` y
  `reactor-core` opcionales.
- **Contexto**: Ningún proyecto Java consume hoy
  este JAR (el monolito solo lo declara en `dependencyManagement`).

## D-2 Cerrojo de indexado

- **Decision**: una fila de arriendo en `retrieval.indexing_lock` (dueño y caducidad, con el reloj de la base). Se toma
  con un `INSERT … ON CONFLICT DO UPDATE … WHERE locked_until < now()`, se renueva antes de cada lote y se libera al
  terminar y en el apagado ordenado. TTL 90 s. `IndexingGuard` pasa a ser un puerto de salida.
- **Rationale**: conserva el comportamiento actual («una ejecución a la vez») entre instancias, sin retener conexión ni
  transacción mientras se llama al modelo, y se libera solo si la instancia muere. La comprobación optimista por
  `updated_at` sigue protegiendo los datos.
- **Consecuencia**: tras una caída, el arriendo puede durar hasta 90 s. El barrido de arranque debe reintentar cuando
  encuentre el cerrojo tomado, y la ventana de reintentos (listener y arranque) debe superar el TTL: 12 intentos cada
  10 s en lugar de 6.
- **Alternatives**: `FOR UPDATE SKIP LOCKED` (propuesto en ADR-0017) retiene una transacción abierta durante la llamada
  al modelo, contra el mismo ADR; arriendo por documento (permite paralelismo que nadie pidió y cambia la semántica);
  `pg_advisory_lock` (retiene una conexión durante toda la ejecución). ADR-0033 sustituye ese punto de ADR-0017.

## D-3 Base de datos propia y su provisión

- **Decision**: base `retrieval` con rol `retrieval` (dueño), en el mismo servidor; extensión `vector` creada por el
  superusuario. Un paso idempotente de un solo disparo en el compose (`retrieval-db-init`) crea rol, base y extensión.
  `REVOKE CONNECT … FROM PUBLIC` en ambas bases.
- **Rationale**: los scripts de `postgres/init` no se ejecutan sobre un volumen que ya existe. `vector` requiere
  superusuario. En dev, `postgres` es el contenedor del host (`postgres-host.yaml`), el mismo que en local.
- **Alternatives**: servidor aparte (más piezas sin necesidad); otro esquema en la misma base (no es «base propia»).

## D-4 Migración de datos

- **Decision**: `pg_dump --schema=retrieval` de `shoplab` y `pg_restore --single-transaction --no-owner
  --role=retrieval` en `retrieval`, incluida `flyway_schema_history`. Verificación por recuento y suma de comprobación
  ordenada de cada tabla en ambas bases. Detalle en [contracts/data-migration.md](./contracts/data-migration.md).
- **Rationale**: copia los vectores tal cual (SC-002) y el servicio encuentra V1–V4 ya aplicadas. Por eso las
  migraciones se copian sin cambiar su contenido.
- **Alternatives**: reingerir el corpus (recalcula embeddings, prohibido por FR-012); `postgres_fdw` o replicación
  lógica (complejidad para unos cientos de filas).

## D-5 Corte y reversión

- **Decision**: expandir y contraer. Release A del monolito: módulo intacto más cliente generado, elegido por
  `shoplab.retrieval.client.mode` (por defecto `in-process`). Corte: un commit en `shoplab-infra` que define las dos
  URIs del gateway, `mode=http`, la URL del servicio y `auto-index=false` en el monolito. Release B: retirada.
- **Rationale**: las rutas y el chat cambian a la vez (spec, Assumptions) y revertir es revertir ese commit (SC-007).
- **Alternatives**: una sola release que retire y conecte (sin reversión).

## D-6 Contratos, stubs y paquetes

- **Decision**: `contracts/retrieval/*.yml` y `RetrievalContractBase` se mueven al servicio, que publica
  `com.shoplab:shoplab-retrieval-service:<versión>:stubs`. La prueba de consumidor del chat pasa a esas coordenadas
  con versión fijada, no `+`.
- **Comprobado (T003, 2026-11-25)**: el `GITHUB_TOKEN` de `shoplab-gateway` con `packages: read` descargó
  `shoplab-contracts` 1.2.0 (run 45678575081). No hace falta un token aparte.

## D-7 Longitud de la consulta

- **Hallazgo**: `FollowUpQuery` conserva el mensaje entero (hasta 4000 caracteres; las preguntas previas se limitan a
  un total de 2000). Codificado en la URL puede ocupar decenas de KB, y Netty admite 4 KB en la línea inicial por
  defecto. En proceso hoy no hay límite.
- **Decision**: `server.netty.max-initial-line-length=64KB` en el servicio. Prueba con 4000 caracteres de 3 bytes y
  con `{ } & + # %`.
- **Alternatives**: cambiar a `POST` (rompe el contrato publicado y a la evaluación).

## D-8 Llamada del chat

- **Decision**: tiempo máximo 15 s, configurable (`shoplab.retrieval.client.timeout`); `X-Request-Id` desde el contexto
  Reactor y `traceparent` si llegó en la petición; cualquier fallo → `KnowledgeRetrievalException`.
- **Rationale**: el cliente actual no tiene tiempo máximo ni propaga cabeceras. 15 s cubre la primera carga del modelo
  de embeddings en CPU (estimación, no medida).
- El servicio debe poner en su contexto la clave `shoplab.gateway.requestId` a partir de `X-Request-Id`; hoy la pone el
  caso de uso del chat y sin ella se pierde el cruce con los registros del gateway de modelos.

## D-9 Plantilla y workflows

Defectos confirmados, además de los de la historia:

| Defecto | Evidencia |
|---|---|
| La plantilla usa R2DBC; `retrieval` usa JDBC (ADR-0011) | `pom.xml`, `application.yml` de la plantilla |
| Sin configuración de Checkstyle/PMD ni umbral JaCoCo en el POM; imagen base sin digest | plantilla; comparar con `shoplab-gateway` |
| `deploy.yml` usa por defecto el overlay `k8s/overlays/dev`, no `apps/<svc>/overlays/dev` | `shoplab-ci/.github/workflows/deploy.yml` |

Hallado al implementar: la imagen de un servicio que depende de `shoplab-contracts` descarga de GitHub Packages
dentro de `docker build`, y `publish-image.yml` no pasaba credencial. Se pasa como secreto de construcción
`github_token` y la plantilla la monta con `.mvn/ci-settings.xml`. La plantilla corregida pasa `./mvnw verify`
(cobertura 94 %), construye su imagen y arranca como UID 10001 (2026-11-25).

- **Decision**: tomar como referencia los workflows y la receta de imagen de `shoplab-gateway`, que ya funcionan.
  Quitar de la plantilla el starter OAuth2 y `SecurityConfig` (LAB-61 los añadirá); no usar `ResponseDto`. En
  `publish-image.yml`, comparar la identidad del certificado sin distinguir mayúsculas (`(?i)`); en
  `java-service.yml`, credenciales Maven (`server-id: github-shoplab-contracts`) y Qodana `v2026.2.2`.

Hallado en la CI del código ya trasladado: con el `mvn` del runner, un proyecto con la extensión de Spring Cloud
Contract falla al descargar (`ClassCastException: BasicAuthCache`). `java-service.yml` usa `./mvnw` si existe; en verde
en el run 45684032699. En local no se reprodujo.

## D-10 `smoke` de shoplab-infra

- **Observado**: en el run 45667096942, `aws_eks_cluster` sigue «Still creating» más de 8 min en los dos intentos,
  aunque k3s arranca en segundos. Docker Hub responde `429 Too Many Requests` al bajar `registry:2` y Floci avisa
  «gets no ECR registry mirror». En el último `smoke` correcto (2026-11-17) el espejo se inyectó y el clúster se creó
  en 16 s.
- **Hipótesis (no confirmada)**: el límite de Docker Hub impide el espejo y el clúster nunca pasa a activo. La historia
  lo sitúa tras LAB-104, pero ese cambio no toca la creación del clúster.
- **Decision** (indicación del mantenedor: si no se puede solucionar, quitar el paso):
  1. Como máximo dos intentos de corrección. El primero: bajar `registry:2` desde `mirror.gcr.io` y etiquetarlo antes
     de `up.sh`. El segundo, según lo que muestre el log del primero. No subir `APPLY_TIMEOUT`.
  2. Se da por solucionado con dos ejecuciones seguidas de CI en verde.
  3. Si no, se quita de `shoplab-infra/.github/workflows/ci.yml`: el job `smoke`, su entrada en `needs` de `ci-gate`,
     la salida `smoke` y el paso «Decide whether smoke is needed» del job `changes`, y su `fetch-depth: 0`. También
     `scripts/is-dev-deploy-only.sh` y su prueba, que solo sirven a esa decisión (revisar `make test-ci-scripts`), y
     se actualizan `README.md` y `docs/gitops.md`.
- **Se conserva** `scripts/smoke.sh` y `make smoke` para ejecutarlo en local.
- **Regla de rama**: `main-protect` exige `ci-gate` y `pr-title`, no `smoke`; no hay que cambiarla.
- **Se pierde** al quitarlo: la CI deja de probar el `apply` completo y la sincronización de Argo CD. Quedan
  `static`, `kustomize`, `trivy` y `plan`.
- **Alternatives**: marcarlo como no bloqueante (un job siempre en rojo deja de mirarse); subir los tiempos (el
  clúster no avanza, solo tardaría más en fallar).

## D-14 Creación de repositorios

- **Decision**: los crea el agente con `gh repo create`, públicos. La plantilla se marca con `is_template`;
  `shoplab-retrieval-service` se genera desde ella.
- **Hallazgo**: `shoplab-gateway` usa la variable `DEPLOY_APP_ID` y los secretos `DEPLOY_APP_PRIVATE_KEY` y
  `RELEASE_PLEASE_TOKEN`. El repositorio nuevo necesita los tres; los valores secretos no se pueden copiar desde GitHub.
- La GitHub App de despliegue no se instala en el repositorio nuevo: `deploy.yml` pide un token limitado a
  `shoplab-infra`, donde ya está instalada. El paquete de la imagen sí hay que hacerlo público, como el del gateway.

**Resultado (2026-11-25)**: hipótesis del `apply` **confirmada** (run 45680227212: con `registry:2` presente el clúster
se crea en 13 s). Quedó el `destroy`: `argocd_initial_app` agota la desinstalación. Intento 2: soltar los *finalizers*
de Argo CD antes de destruir (hipótesis: el borrado en cascada espera recursos que en CI no terminan).

**Cierre (2026-11-25)**: la confirmación anterior **no se sostiene**. En el run 45686618767, con el mismo arreglo y el
espejo de ECR inyectado, el clúster tampoco llegó a activo en ninguno de los dos `apply`. El fallo es intermitente y
su causa se desconoce. Agotados los dos intentos, se quitó el job `smoke` (`shoplab-infra#40`, `ci-gate` en verde) y
se retiró el cambio del intento 2, que nunca llegó a ejecutarse. Queda `make smoke` en local.

## D-11 PostgreSQL del compose

- **Decision**: `litellm-postgres` y `langfuse-postgres` pasan por defecto a `docker.io/postgres:17.7`, el valor de
  `.env.example`; Dependabot ignora los saltos de versión mayor de PostgreSQL.
- **Rationale**: conserva los volúmenes 17. PostgreSQL 18 cambia la ruta de datos y exigiría migrarlos.
- **Por comprobar**: `langfuse-postgres` con 18 (mismo montaje, la historia no lo verificó).

## D-12 Marcador `apps/retrieval-service` en shoplab-infra

Ya existe y el ApplicationSet lo recoge, pero no puede funcionar:

| Problema | Corrección |
|---|---|
| `images:` usa `name: retrieval-service`; `deploy-infra.sh` exige el nombre completo y falla | `ghcr.io/leandroamariles/retrieval-service` |
| ExternalSecret `v1beta1` contra `shoplab-secret-store` | `external-secrets.io/v1`, `aws-secrets-manager`, rutas `/shoplab/dev/…` |
| Solo variables de base de datos | ConfigMap completo ([configuration.md](./contracts/configuration.md)) |
| Sin acceso permitido a LiteLLM ni salida controlada | Ampliar `litellm-ingress` y añadir política de salida como la del backend |
| Sin `securityContext`, `startupProbe` ni `/tmp` | Igualar al `backend`; 768 Mi |

## D-13 Observabilidad

- Métricas `retrieval.*` sin renombrar; la etiqueta `application` pasa a `retrieval-service` y los dashboards ya
  filtran por `$application`. Se añaden `ServiceMonitor`, trabajo en el Prometheus local y registro estructurado como
  en el gateway.
