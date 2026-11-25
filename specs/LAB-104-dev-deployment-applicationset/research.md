# Research: Despliegue por servicio en dev con ApplicationSet

**Feature**: `LAB-104-dev-deployment-applicationset`
**Date**: 2026-11-25
**Status**: Completed

---

## 1. Technical Context & Objectives

Tras la decisión de extracción temprana de microservicios ([ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md) / LAB-128) y la definición de la topología distribuida de 8 servicios ([docs/mapa-servicios.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/mapa-servicios.md)), cada componente de negocio (`chat-service`, `retrieval-service`, `eval-service`, `catalog-service`, `order-service`, `ticket-service`, `gateway`, `notification-service`) pasa a poseer su propio repositorio, ciclo de vida de CI en `shoplab-ci` (LAB-103) y esquema de base de datos exclusivo.

En la arquitectura previa (LAB-81), el despliegue a `dev` operaba de forma monolítica mediante un único overlay `k8s/overlays/dev/kustomization.yaml` gestionado por una única `Application` de Argo CD (`shoplab-dev`). Este esquema presenta bloqueos críticos en una topología distribuida:
1. **Acoplamiento de despliegue**: Un único manifiesto central exige coordinar el despliegue de todos los servicios simultáneamente.
2. **Colisiones de CI**: Múltiples repositorios de microservicios intentando commitear concurrentemente al mismo archivo central en `shoplab-infra` provocan rechazos de push por `non-fast-forward`.
3. **Gestión manual en Argo CD**: Incorporar un nuevo microservicio requería dar de alta manualmente una nueva `Application` o reconfigurar el monolito.
4. **Fuga de secretos de base de datos**: Un secret compartido viola el principio de aislamiento de datos y soberanía de esquemas ([ADR-0015](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0015-limites-de-contexto-y-repositorios.md)).

La historia **LAB-104** resuelve estos problemas mediante la transición hacia un modelo declarativo descentralizado en `shoplab-infra` basado en **Argo CD ApplicationSet** y **Kustomize modular por servicio**, permitiendo que cada microservicio se despliegue y pruebe en el clúster `dev` desde su nacimiento sin esperar a F7.

---

## 2. Decisiones de Investigación (Resolución de Incógnitas)

### D-01: Estructura de directorios Kustomize en `shoplab-infra`
* **Pregunta**: ¿Cómo estructurar los manifiestos de Kubernetes en `shoplab-infra` para soportar despliegues independientes por servicio y descubrimiento automático?
* **Opciones evaluadas**:
  1. *Estructura plana centralizada (`k8s/services/<servicio>.yaml`)*: No permite separar bases de overlays ni versionar imágenes de forma atómica.
  2. *Estructura monorepo tradicional (`apps/<servicio>/base` y `apps/<servicio>/overlays/<env>`)*: Estándar de la industria para Kustomize + Argo CD. Permite aislar completamente los manifiestos de cada servicio (`Deployment`, `Service`, `ExternalSecret`, `ConfigMap`) en su carpeta `base/`, mientras que `overlays/dev/kustomization.yaml` gestiona exclusivamente las réplicas, variables y el tag de la imagen (`kustomize edit set image`).
* **Decisión**: Se adopta la estructura modular:
  ```text
  apps/
  ├── <servicio>/
  │   ├── base/
  │   │   ├── deployment.yaml
  │   │   ├── service.yaml
  │   │   ├── externalsecret.yaml
  │   │   └── kustomization.yaml
  │   └── overlays/
  │       └── dev/
  │           └── kustomization.yaml
  ```
  La carpeta raíz transversal `k8s/overlays/dev/` se reserva para configuraciones compartidas del clúster (Namespaces, SecretStores, Ingress global).

---

### D-02: Generador de Argo CD ApplicationSet
* **Pregunta**: ¿Qué tipo de generador dentro de `ApplicationSet` garantiza el alta automática de servicios sin tocar Argo CD?
* **Opciones evaluadas**:
  1. *Generador de listas (`List generator`)*: Requiere editar el `ApplicationSet` cada vez que se crea un microservicio (anti-patrón).
  2. *Generador Git de directorios (`Git directory generator`)*: Sondea el repositorio `shoplab-infra` y genera una `Application` por cada subdirectorio que coincida con una ruta patrón (`path: apps/*/overlays/dev`).
  3. *Generador Git de archivos (`Git files generator`)*: Requiere un archivo `config.json` por servicio. Introduce sobrecarga innecesaria.
* **Decisión**: Adoptar el **Generador Git de Directorios** (`git.directories`).
  - Patrón de búsqueda: `apps/*/overlays/dev`.
  - Nombre de la aplicación resultante: `{{path[1]}}-dev` (donde `path[1]` extrae el nombre del microservicio, ej. `chat-service-dev`).
  - Namespace de destino: `shoplab-dev`.
  - Servidor: `https://kubernetes.default.svc`.
  - Políticas de sincronización: `automated: { prune: true, selfHeal: true }` y `targetRevision: main`.

---

### D-03: Estrategia de concurrencia en el Job de CI de `shoplab-ci`
* **Pregunta**: ¿Cómo prevenir colisiones y pérdida de commits cuando múltiples microservicios despliegan simultáneamente hacia `shoplab-infra`?
* **Opciones evaluadas**:
  1. *Serialización global con colas / mutex en GitHub Actions*: Inflexible; bloquea pipelines de servicios distintos e incrementa drásticamente la latencia de entrega.
  2. *Pull Request automatizado*: Retrasa la sincronización inmediata hacia dev y requiere pipelines adicionales de merge.
  3. *Push directo con rebase y reintento exponencial (`git pull --rebase` + backoff)*: Estándar GitOps de alto rendimiento. Cada servicio modifica únicamente su propio archivo `apps/<servicio>/overlays/dev/kustomization.yaml`. Como los cambios son ortogonales (tocan rutas de archivo completamente distintas), un `git pull --rebase origin main` siempre resuelve el commit limpio sin conflictos semánticos.
* **Decisión**: Adoptar **rebase con reintento y backoff exponencial**:
  - El script del job de despliegue en `shoplab-ci` (definido en LAB-103) clona `shoplab-infra`, aplica el cambio con `kustomize edit set image`, valida políticas y realiza commit.
  - Al ejecutar `git push`, si el remoto es rechazado (`non-fast-forward` por push concurrente de otro servicio), ejecuta:
    ```bash
    git pull --rebase origin main
    ```
  - Reintenta el push hasta 3 veces con una espera progresiva (jitter aleatorio entre 2 y 7 segundos) antes de declarar fallo.
  - Debido a la partición por directorios en `apps/`, nunca existirá un conflicto de fusión en git entre dos servicios distintos.

---

### D-04: Gestión de secretos de base de datos por servicio con External Secrets Operator (ESO)
* **Pregunta**: ¿Cómo aprovisionar credenciales de base de datos garantizando soberanía de datos y cero secretos en Git?
* **Opciones evaluadas**:
  1. *Kubernetes Secrets manuales o sellados (SealedSecrets)*: Requiere cifrado manual de claves por los desarrolladores y dificulta la rotación.
  2. *Secret global compartido de PostgreSQL*: Viola [ADR-0015](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0015-limites-de-contexto-y-repositorios.md) y [ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md) al dar acceso a un servicio sobre esquemas ajenos.
  3. *`ExternalSecret` por servicio con ESO (External Secrets Operator)*: Cada servicio declara un recurso `ExternalSecret` en su carpeta `base/` apuntando a la ruta de secretos de su esquema (ej. `shoplab/dev/<servicio>/database`). ESO se encarga de materializar el `Secret` nativo en el namespace `shoplab-dev`.
* **Decisión**: Adoptar **`ExternalSecret` por servicio**.
  - Cada servicio en `apps/<servicio>/base/externalsecret.yaml` define la extracción de `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` y `SPRING_DATASOURCE_URL`.
  - El `Deployment` monta las credenciales mapeadas directamente a variables de entorno de Spring Boot.

---

### D-05: Estado de salud y sincronización en dev (`Synced` y `Healthy`)
* **Pregunta**: ¿Cómo asegurar que Argo CD refleje fielmente el estado operativo real de cada servicio?
* **Decisión**:
  - Cada microservicio debe exponer endpoints estándar de Actuator: `/actuator/health/liveness` y `/actuator/health/readiness`.
  - El `deployment.yaml` en `base/` declara `livenessProbe` y `readinessProbe` HTTP apuntando a dichos endpoints.
  - La sincronización en Argo CD incluye `syncOptions: [CreateNamespace=true, PrunePropagationPolicy=foreground]`.
  - Si un servicio no arranca por error de base de datos o fallo interno, el Pod entra en estado `NotReady`, impidiendo que el `Service` dirija tráfico y haciendo que Argo CD reporte `Health: Degraded`.

---

### D-06: Acotación de alcance y exclusiones
* **Pregunta**: ¿Se debe incluir la infraestructura de Kafka / Strimzi en LAB-104?
* **Decisión**: **No**. Siguiendo la definición de Jira LAB-104:
  - Kafka con Strimzi y Schema Registry en el entorno `dev` queda explícitamente diferido a **LAB-131** (junto con la implementación funcional de eventos en LAB-46).
  - LAB-104 se enfoca exclusivamente en el motor de despliegue descentralizado GitOps: estructura Kustomize de `apps/`, `ApplicationSet`, job con rebase y `ExternalSecret` de bases de datos.

---

## 3. Matriz Tecnológica

| Componente | Versión / Tipo | Función en LAB-104 |
|---|---|---|
| **Argo CD** | v2.10+ / v2.12+ | Motor GitOps y orquestador de ApplicationSet |
| **Argo CD ApplicationSet** | Controlador nativo | Generador de directorios Git (`apps/*/overlays/dev`) |
| **Kustomize** | v5.4+ | Renderizador declarativo modular de manifiestos |
| **External Secrets Operator** | v0.9+ | Operador de sincronización de secretos de base de datos |
| **PostgreSQL** | 16-alpine (pgvector) | Base de datos con esquemas soberanos por servicio |
| **GitHub Actions** | Runner estándar / `shoplab-ci` | Ejecutor del job reutilizable de despliegue con rebase |
| **shoplab-deploy[bot]** | GitHub App | Identidad con bypass en ruleset y permiso exclusivo a `shoplab-infra` |
