# Feature Specification: Despliegue por servicio en dev con ApplicationSet

**Feature Branch**: `feature/LAB-104-dev-deployment-applicationset`

**Created**: 2026-11-25

**Status**: Draft

**Input**: User description: "LAB-104 (F5-13) Despliegue por servicio en dev con ApplicationSet. Como desarrollador, quiero que cada microservicio se despliegue solo en dev desde `shoplab-infra`, para probar los servicios en el clúster desde que nacen y no esperar a F7. Criterios de aceptación: Una base de Kustomize por servicio (`apps/<servicio>/base`) y el overlay `dev` que las compone; Un ApplicationSet de Argo CD (generador de directorios) crea una Application por servicio en dev, con automated, prune y selfHeal y `targetRevision: main`; Cada repo de servicio despliega con el job reutilizable de LAB-103, que actualiza solo su imagen en `shoplab-infra`; dos repos que despliegan a la vez no se pisan (rebase y reintento del commit); Base de datos por servicio con su ExternalSecret; Dar de alta un servicio nuevo es crear su carpeta: el ApplicationSet lo recoge sin tocar Argo CD; Todas las Applications de dev quedan Synced y Healthy. Depende de: LAB-103, LAB-101. Va antes de LAB-107 (gateway) y LAB-129 (retrieval-service). Se adelantó de F5 al bloque de extracción temprana (ADR-0029, LAB-128). Kafka con Strimzi y Schema Registry en dev pasa a LAB-131, junto a LAB-46."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Estructura modular de Kustomize por servicio (`apps/<servicio>/base` y overlays `dev`) (Priority: P1)

Como ingeniero de plataforma o desarrollador de microservicios, quiero que la configuración declarativa de despliegue en Kubernetes esté organizada de manera modular en `shoplab-infra`, asignando a cada microservicio su propia base Kustomize (`apps/<servicio>/base/`) y su respectivo overlay para el entorno de desarrollo (`apps/<servicio>/overlays/dev/`), de modo que cada componente gestione sus propios recursos (Deployment, Service, ServiceAccount, ConfigMap, Probes) sin interferir con los demás servicios del ecosistema.

**Why this priority**: Es la base estructural del desacoplamiento GitOps tras la extracción temprana (ADR-0015, ADR-0031, LAB-128). Sin una partición modular por servicio en Kustomize, no es posible habilitar despliegues atómicos ni activar la generación automática de aplicaciones mediante Argo CD.

**Independent Test**: Ejecutar `kustomize build apps/<servicio>/overlays/dev` para un servicio dado (p. ej. `chat-service` o `retrieval-service`) y verificar que genera manifiestos válidos con namespace `shoplab-dev`, labels estandarizadas, probes, límites de CPU/memoria y sin tags mutables `:latest`.

**Acceptance Scenarios**:

1. **Given** el repositorio `shoplab-infra`, **When** se crea o migra un microservicio (p. ej. `chat-service`), **Then** sus manifiestos se ubican bajo la ruta estándar `apps/<servicio>/base/` conteniendo `deployment.yaml`, `service.yaml`, `kustomization.yaml` y recursos específicos.
2. **Given** el directorio `apps/<servicio>/overlays/dev/`, **When** se ejecuta `kustomize build`, **Then** hereda de `../../base`, aplica parches de entorno (réplicas = 1, límites ajustados para dev, variables específicas) y referencia la imagen inmutable del contenedor.
3. **Given** la estructura `apps/`, **When** se evalúa la política de gobernanza `policy-check.sh`, **Then** ningún manifiesto utiliza `:latest`, todos declaran `readinessProbe` y `livenessProbe` HTTP, y definen labels de trazabilidad obligatorias (`app.kubernetes.io/name`, `app.kubernetes.io/instance`, `app.kubernetes.io/part-of: shoplab`).

---

### User Story 2 - ApplicationSet de Argo CD con generador de directorios Git y ciclo de vida autónomo (Priority: P1)

Como operador de infraestructura y desarrollador, quiero que un `ApplicationSet` de Argo CD configurado con un generador de directorios (`Git directory generator`) descubra automáticamente cualquier carpeta bajo `apps/*/overlays/dev` en la rama `main` de `shoplab-infra`, instanciando y sincronizando una `Application` de Argo CD independiente por cada microservicio con políticas de autoreparación (`selfHeal`), poda automática (`prune`) y sincronización desatendida (`automated`).

**Why this priority**: Elimina el cuello de botella de administración manual en Argo CD. Garantiza que el alta de un nuevo servicio o la eliminación de uno obsoleto sea un acto puramente declarativo en Git, manteniendo la convergencia continua del clúster sin intervención humana.

**Independent Test**: Añadir un nuevo directorio `apps/dummy-service/overlays/dev/` en `shoplab-infra` y comprobar que en el siguiente ciclo de sondeo de Argo CD aparece automáticamente la `Application` `dummy-service-dev` en el clúster, quedando en estado `Synced` y desplegando sus recursos en `shoplab-dev`.

**Acceptance Scenarios**:

1. **Given** el clúster con Argo CD instalado en el ambiente dev (LAB-101), **When** se despliega el manifiesto `applicationset-services-dev.yaml`, **Then** el controlador de ApplicationSet crea una `Application` por cada servicio encontrado en `apps/*/overlays/dev`.
2. **Given** una `Application` generada por el ApplicationSet, **When** se inspecciona su especificación `syncPolicy`, **Then** cuenta con `automated: { prune: true, selfHeal: true }` y `targetRevision: main`.
3. **Given** la creación de una nueva carpeta de servicio en Git (`apps/<nuevo-servicio>/overlays/dev`), **When** el commit llega a `main`, **Then** el ApplicationSet detecta el nuevo directorio y genera la `Application` correspondiente sin requerir modificaciones en la configuración raíz de Argo CD.
4. **Given** la eliminación de una carpeta de servicio en Git, **When** Argo CD reconcilia el estado, **Then** la `Application` huérfana y sus recursos asociados en el clúster son eliminados limpiamente (`prune: true`).

---

### User Story 3 - Despliegue concurrente desacoplado con rebase y reintento en el job de CI (Priority: P1)

Como desarrollador de un microservicio cuyo pipeline de CI/CD publica una nueva versión, quiero que el job reutilizable de despliegue (LAB-103) actualice exclusivamente la imagen de mi servicio en `apps/<servicio>/overlays/dev/kustomization.yaml` en `shoplab-infra`, manejando colisiones de commits concurrentes mediante `git pull --rebase` con reintentos exponenciales, para que múltiples microservicios desplegando simultáneamente no se pisen ni cancelen mutuamente.

**Why this priority**: En una arquitectura de 8+ microservicios desacoplados con releases independientes, las ejecuciones de CI concurrentes en distintos repositorios competirán por hacer commit a la rama `main` de `shoplab-infra`. Sin concurrencia segura con rebase y reintento, los pipelines fallarán sistemáticamente por rechazo de push (`non-fast-forward`).

**Independent Test**: Disparar dos ejecuciones paralelas del job de despliegue sobre `shoplab-infra` para dos servicios distintos (`chat-service` y `retrieval-service`), simulando colisión en el push; verificar que ambas ejecuciones resuelven el conflicto rebasando sus cambios y ambos commits quedan registrados en `main` sin pérdida de versiones.

**Acceptance Scenarios**:

1. **Given** la publicación de una versión `vX.Y.Z` de un microservicio, **When** corre el job de despliegue, **Then** ejecuta `kustomize edit set image <servicio>=<imagen>:<tag>` acotado estrictamente a `apps/<servicio>/overlays/dev/`.
2. **Given** un rechazo de `git push` por concurrencia (otro servicio hizo commit segundos antes), **When** el script captura el error, **Then** descarta el commit local temporal, ejecuta `git pull --rebase origin main`, vuelve a aplicar el cambio de Kustomize y reintenta el push hasta 3 veces con retroceso exponencial.
3. **Given** el commit resultante en `shoplab-infra`, **When** se examina su diff, **Then** únicamente modifica el archivo `apps/<servicio>/overlays/dev/kustomization.yaml` correspondiente al servicio emisor, sin tocar líneas ni imágenes de servicios vecinos.
4. **Given** el mensaje del commit, **When** se inspecciona su formato, **Then** cumple con el estándar de trazabilidad: `chore(dev): [LAB-104] despliega <servicio> <vX.Y.Z> a dev`.

---

### User Story 4 - Aprovisionamiento seguro de credenciales de base de datos con `ExternalSecret` por servicio (Priority: P1)

Como desarrollador o administrador de seguridad, quiero que cada microservicio disponga de su propio recurso `ExternalSecret` en Kubernetes que resuelva las credenciales exclusivas de su base de datos/esquema PostgreSQL desde el gestor de secretos (Secrets Manager / Local Provider) hacia un `Secret` nativo de Kubernetes en `shoplab-dev`, garantizando el principio de menor privilegio y la soberanía de datos (ADR-0015, ADR-0031).

**Why this priority**: Cada servicio es dueño exclusivo de su esquema de base de datos (ADR-0015). Compartir un único Secret global de base de datos entre microservicios viola el aislamiento de credenciales y permite que un servicio acceda indebidamente a las tablas de otro.

**Independent Test**: Desplegar el `ExternalSecret` de `chat-service` y `retrieval-service` en `shoplab-dev`; comprobar que External Secrets Operator (ESO) sincroniza los secretos `SecretStoreRef`, generando los secretos locales `chat-db-credentials` y `retrieval-db-credentials` con credenciales distintas para cada esquema y sin exponer contraseñas en Git.

**Acceptance Scenarios**:

1. **Given** los manifiestos base de un microservicio en `apps/<servicio>/base/`, **When** se revisan sus definiciones, **Then** incluye un `externalsecret.yaml` apuntando al `SecretStore` configurado en el clúster dev.
2. **Given** el `ExternalSecret`, **When** ESO reconcilia con el backend de secretos, **Then** genera en el namespace `shoplab-dev` un `Secret` con las claves requeridas (`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATASOURCE_URL`).
3. **Given** el `Deployment` del servicio, **When** se monta la configuración de base de datos, **Then** las credenciales se inyectan como variables de entorno referenciando exclusivamente su propio Secret (`<servicio>-db-credentials`).
4. **Given** el repositorio `shoplab-infra`, **When** se auditan los archivos versionados, **Then** no existen contraseñas en texto claro ni tokens de conexión hardcodeados.

---

### User Story 5 - Monitorización, salud y convergencia Synced y Healthy en dev (Priority: P2)

Como ingeniero de confiabilidad (SRE) o desarrollador en turno, quiero que la consola de Argo CD y las herramientas de CLI reporten todas las `Applications` de desarrollo en estado `Synced` y `Healthy`, con verificaciones de salud específicas para probes HTTP de Actuator (`/actuator/health/readiness`), permitiendo diagnosticar inmediatamente cualquier fallo de despliegue o arranque de contenedor.

**Why this priority**: Un despliegue continuo solo es confiable si el estado de convergencia y la salud real de los pods son observables de forma inequívoca en tiempo real.

**Independent Test**: Ejecutar `argocd app list -o wide` o consultar la API de Argo CD en dev; verificar que cada aplicación descubierta por el ApplicationSet reporta `Sync Status: Synced` y `Health Status: Healthy`.

**Acceptance Scenarios**:

1. **Given** todos los microservicios activos en `apps/`, **When** Argo CD reconcilia los manifiestos, **Then** todas las aplicaciones finalizan con estado de sincronización `Synced`.
2. **Given** el despliegue de un pod en `shoplab-dev`, **When** el contenedor levanta satisfactoriamente y supera su `readinessProbe`, **Then** Argo CD reporta la aplicación con estado de salud `Healthy`.
3. **Given** un pod que entra en `CrashLoopBackOff` o falla su probe de salud, **When** Argo CD evalúa el recurso, **Then** marca la `Application` como `Degraded` y genera un evento visible en la UI/CLI sin bloquear la sincronización de los demás microservicios.

---

### Edge Cases

- **Colisión múltiple y sostenida en push a `shoplab-infra`**: Si más de tres servicios intentan empujar en la misma ventana de segundos, el script de rebase debe implementar retroceso aleatorio (jitter) para evitar resonancia en los reintentos.
- **Creación de carpeta de servicio sin overlay dev válido**: Si un desarrollador añade `apps/<nuevo-servicio>/` pero omite `overlays/dev/kustomization.yaml`, el generador de Argo CD debe filtrar o ignorar la carpeta para no generar una `Application` rota.
- **ExternalSecret no resuelve por credencial inexistente en el gestor de secretos**: La `Application` en Argo CD debe mantenerse en estado `Progressing` y reportar el error en el recurso `ExternalSecret` sin afectar la operatividad de los servicios ya sincronizados.
- **Retiro o desmantelamiento de un servicio**: Al borrar el directorio `apps/<servicio>` en Git, Argo CD debe aplicar el prune eliminando el `Deployment`, `Service` y `ConfigMap`, pero preservando las tablas o volúmenes persistentes si así lo dictan las políticas de retención.
- **Transición desde el despliegue monolítico (LAB-81)**: Durante la fase de migración temprana (ADR-0031), el overlay general de `shoplab-infra` debe coexistir limpiamente con el nuevo ApplicationSet sin duplicar puertos ni generar colisiones de `Ingress`/`Service`.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `shoplab-infra` DEBE estructurar los manifiestos de Kubernetes por servicio bajo la ruta `apps/<servicio>/base/` y `apps/<servicio>/overlays/dev/`.
- **FR-002**: Cada carpeta `base/` DEBE contener manifiestos desacoplados para `deployment.yaml`, `service.yaml`, `kustomization.yaml` y `externalsecret.yaml`.
- **FR-003**: Debe existir un recurso `ApplicationSet` de Argo CD en `shoplab-infra` (p. ej. `k8s/bootstrap/applicationset-services-dev.yaml`) apuntando a la rama `main` del repositorio `shoplab-infra`.
- **FR-004**: El `ApplicationSet` DEBE utilizar el generador de directorios Git (`git.directories`) con el patrón `apps/*/overlays/dev`.
- **FR-005**: El template del `ApplicationSet` DEBE generar recursos `Application` con nombre `{{path.basename}}-dev` (o `<servicio>-dev`) en el namespace `argocd`.
- **FR-006**: Cada `Application` generada DEBE tener activada la política de sincronización automática con `prune: true` y `selfHeal: true`.
- **FR-007**: El destino de despliegue (`destination`) de cada `Application` DEBE ser el clúster local (`https://kubernetes.default.svc`) y el namespace `shoplab-dev`.
- **FR-008**: El job reutilizable de despliegue de `shoplab-ci` (LAB-103) DEBE parametrizar el path de edición hacia `apps/<servicio>/overlays/dev/` y actualizar la imagen con `kustomize edit set image`.
- **FR-009**: El script de despliegue en CI DEBE implementar un bucle de reintento (mínimo 3 intentos) con `git pull --rebase origin main` antes de abortar por colisiones de concurrencia.
- **FR-010**: El script de despliegue DEBE validar con `kustomize build` y ejecutar `policy-check.sh` antes de efectuar cualquier commit o push a `shoplab-infra`.
- **FR-011**: Cada microservicio con base de datos propia DEBE incluir un `ExternalSecret` que sincronice las credenciales del esquema desde el `ClusterSecretStore` / `SecretStore` hacia un `Secret` nativo en `shoplab-dev`.
- **FR-012**: El alta de un nuevo microservicio en el entorno `dev` DEBE completarse exclusivamente creando su carpeta `apps/<servicio>/overlays/dev` en Git, sin requerir cambios en el manifiesto del `ApplicationSet` ni reinicio de Argo CD.
- **FR-013**: Todas las `Applications` activas generadas por el `ApplicationSet` DEBEN alcanzar el estado `Synced` y `Healthy` una vez convergido el clúster.
- **FR-014**: La configuración DEBE excluir del alcance de LAB-104 el aprovisionamiento de Strimzi / Kafka / Schema Registry, los cuales quedan diferidos a LAB-131 y LAB-46.
- **FR-015**: Se DEBE documentar el procedimiento de alta y estructura de `apps/` en la guía de despliegue de la infraestructura (`docs/despliegue-continuo.md` o `docs/fases/`).

---

## Key Entities & Declarative Schema

```text
shoplab-infra/
├── k8s/
│   ├── bootstrap/
│   │   └── applicationset-services-dev.yaml    <-- ApplicationSet con generador Git
│   └── overlays/
│       └── dev/                                <-- Configuración transversal del namespace shoplab-dev
└── apps/
    ├── chat-service/
    │   ├── base/
    │   │   ├── deployment.yaml
    │   │   ├── service.yaml
    │   │   ├── externalsecret.yaml
    │   │   └── kustomization.yaml
    │   └── overlays/
    │       └── dev/
    │           └── kustomization.yaml          <-- Actualizado por el CI del servicio
    ├── retrieval-service/
    │   ├── base/
    │   └── overlays/dev/
    ├── eval-service/
    │   ├── base/
    │   └── overlays/dev/
    └── <futuro-servicio>/
        ├── base/
        └── overlays/dev/
```

### ApplicationSet Declarativo (Arquetipo base)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: shoplab-services-dev
  namespace: argocd
spec:
  generators:
    - git:
        repoURL: https://github.com/leandroAmariles/shoplab-infra.git
        revision: main
        directories:
          - path: apps/*/overlays/dev
  template:
    metadata:
      name: "{{path[1]}}-dev"
    spec:
      project: default
      source:
        repoURL: https://github.com/leandroAmariles/shoplab-infra.git
        targetRevision: main
        path: "{{path}}"
      destination:
        server: https://kubernetes.default.svc
        namespace: shoplab-dev
      syncPolicy:
        automated:
          prune: true
          selfHeal: true
        syncOptions:
          - CreateNamespace=true
```

---

## Success Criteria *(measurable)*

- **SC-001**: El 100 % de los servicios extraídos o nuevos definidos en `docs/mapa-servicios.md` cuentan con su estructura `apps/<servicio>/base` y `apps/<servicio>/overlays/dev` sin compartir manifiestos entre sí.
- **SC-002**: El `ApplicationSet` de Argo CD descubre automáticamente cualquier nueva carpeta creada bajo `apps/*/overlays/dev` en un tiempo menor a 3 minutos (sondeo por defecto de Argo CD) sin intervención manual.
- **SC-003**: Dos ejecuciones concurrentes del job reutilizable de despliegue sobre dos servicios distintos completan satisfactoriamente con 0 commits perdidos gracias a la estrategia de `rebase` y reintentos.
- **SC-004**: Ningún archivo en `apps/` ni en `shoplab-infra` contiene contraseñas en claro; el 100 % de las credenciales de base de datos se inyectan mediante `ExternalSecret`.
- **SC-005**: El 100 % de las aplicaciones desplegadas en `shoplab-dev` a través del ApplicationSet reportan estado `Synced` y `Healthy` en la CLI/UI de Argo CD.
- **SC-006**: La política `policy-check.sh` pasa en verde sobre todas las bases y overlays de `apps/` certificando que no existe ningún tag `:latest`.
