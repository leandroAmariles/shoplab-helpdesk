# Feature Specification: Argo CD con una Application por ambiente

**Feature Branch**: `feature/LAB-79-argocd-applications`

**Created**: 2026-10-25

**Status**: Draft

**Input**: User description: "LAB-79 (F0-18) Como desarrollador, quiero que Argo CD sincronice cada ambiente desde git, para que el clúster refleje siempre lo que está en `shoplab-infra`. Criterios: Argo CD lo instala Terraform (LAB-98); esta historia define sus Applications en `shoplab-infra/k8s/argocd/`; Application raíz (app-of-apps) y una Application por ambiente (`application-staging.yaml` y `application-prod.yaml`) apuntando a su overlay; `syncPolicy.automated` con `prune` y `selfHeal`; `repoURL` real del repo y `targetRevision: main` (lo verifica CI en LAB-99); Argo CD accesible por ingress local; guía en `docs/` con el flujo GitOps y la frontera entre Terraform (plataforma) y Argo CD (aplicaciones); ADR: GitOps pull con repo de infraestructura separado frente a despliegue push desde Actions. Depende de: LAB-80."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Un ambiente se despliega solo desde git (Priority: P1)

Como desarrollador, quiero una Application de Argo CD por ambiente que apunte a su overlay de
`shoplab-infra` y se sincronice sola, para que lo que hay en `main` sea lo que corre en el clúster
sin ejecutar ningún comando de despliegue.

**Why this priority**: Es el objetivo de la historia. Sin las Applications por ambiente, Argo CD
está instalado pero no despliega nada de ShopLab.

**Independent Test**: Con el entorno de LAB-98 levantado, aplicar las Applications de `staging` y
`prod`, esperar y comprobar que cada una queda Synced y Healthy con los recursos de su overlay en
su propio namespace; cambiar un valor del overlay en `main` y comprobar que el clúster lo refleja
sin intervención manual.

**Acceptance Scenarios**:

1. **Given** `application-staging.yaml` y `application-prod.yaml` en `k8s/argocd/`, **When** Argo
   CD las procesa, **Then** cada una apunta al overlay de su ambiente (`k8s/overlays/staging` y
   `k8s/overlays/prod`) y despliega sus recursos en el namespace de ese ambiente.
2. **Given** una Application de ambiente, **When** se inspecciona su política de sincronización,
   **Then** es automática y tiene `prune` y `selfHeal` activos.
3. **Given** un cambio fusionado en `main` que modifica un overlay, **When** Argo CD detecta el
   cambio, **Then** el clúster converge al nuevo estado sin acción manual.
4. **Given** un recurso eliminado del overlay en `main`, **When** Argo CD sincroniza, **Then** el
   recurso desaparece del clúster (`prune`).
5. **Given** un recurso modificado a mano en el clúster, **When** Argo CD detecta la deriva,
   **Then** lo revierte al estado de git (`selfHeal`).
6. **Given** las Applications, **When** se inspecciona su origen, **Then** `repoURL` es la URL
   real del repositorio (sin valores de plantilla) y `targetRevision` es `main`.

---

### User Story 2 - Application raíz (app-of-apps) (Priority: P1)

Como desarrollador, quiero una Application raíz que gestione a las Applications de cada ambiente,
para que añadir o quitar un ambiente sea un cambio en git y el arranque del clúster necesite un
único punto de entrada.

**Why this priority**: Sin la raíz, cada Application hay que aplicarla a mano y el "clúster
refleja siempre git" no se cumple para las propias Applications.

**Independent Test**: Aplicar solo la Application raíz y comprobar que Argo CD crea por sí mismo
las Applications de ambiente y las deja Synced y Healthy; añadir un archivo de Application en
`k8s/argocd/` y comprobar que aparece sin tocar el clúster.

**Acceptance Scenarios**:

1. **Given** la Application raíz aplicada, **When** Argo CD la sincroniza, **Then** crea las
   Applications de ambiente definidas en `k8s/argocd/`.
2. **Given** una nueva Application añadida a `k8s/argocd/` en `main`, **When** la raíz
   sincroniza, **Then** la Application aparece en Argo CD sin acciones manuales.
3. **Given** una Application de ambiente eliminada de `k8s/argocd/` en `main`, **When** la raíz
   sincroniza, **Then** esa Application y sus recursos se retiran del clúster.
4. **Given** la raíz, **When** se inspecciona, **Then** tiene sincronización automática con
   `prune` y `selfHeal` y no se gestiona a sí misma de forma que provoque un bucle.

---

### User Story 3 - Argo CD accesible por ingress local (Priority: P2)

Como desarrollador, quiero abrir la interfaz de Argo CD desde mi navegador mediante el ingress
local, para ver el estado de sincronización de cada ambiente sin túneles ni port-forward.

**Why this priority**: Aporta visibilidad y diagnóstico, pero el despliegue funciona sin ella.

**Independent Test**: Con el entorno levantado, abrir la dirección documentada y comprobar que
carga la interfaz y lista la raíz y las Applications de ambiente.

**Acceptance Scenarios**:

1. **Given** el entorno local levantado, **When** el desarrollador abre la dirección de Argo CD
   documentada, **Then** carga su interfaz a través del ingress local.
2. **Given** la interfaz abierta, **When** se autentica con las credenciales iniciales descritas
   en la guía, **Then** ve la Application raíz y las de cada ambiente con su estado.
3. **Given** el ingress de Argo CD, **When** se inspecciona su manifiesto, **Then** no contiene
   credenciales ni dominios de producción.

---

### User Story 4 - Guía del flujo GitOps y la frontera con Terraform (Priority: P2)

Como desarrollador, quiero una guía en `docs/` que explique el flujo GitOps y qué gestiona
Terraform (plataforma) y qué gestiona Argo CD (aplicaciones), para saber dónde cambiar cada cosa y
cómo diagnosticar un ambiente que no sincroniza.

**Why this priority**: Evita cambios en el lugar equivocado (p. ej. editar a mano el clúster o
mover aplicaciones a Terraform) y facilita incorporar a otra persona.

**Independent Test**: Una persona que no participó en la historia sigue la guía para cambiar las
réplicas de `staging`, verlo sincronizar y revertirlo, sin ayuda adicional.

**Acceptance Scenarios**:

1. **Given** la guía, **When** se lee, **Then** describe el flujo: cambio en git, revisión,
   fusión a `main`, sincronización de Argo CD y verificación.
2. **Given** la guía, **When** se busca quién gestiona un componente, **Then** una tabla o lista
   indica qué pertenece a Terraform (clúster, ingress, secretos externos, Argo CD, monitorización)
   y qué a Argo CD (backend, frontend, ingest, Postgres, Ollama).
3. **Given** la guía, **When** un ambiente queda OutOfSync o Degraded, **Then** explica cómo
   diagnosticarlo y por qué no se debe corregir editando el clúster a mano.
4. **Given** la guía, **When** se busca cómo acceder a Argo CD, **Then** indica la dirección local
   y cómo obtener las credenciales iniciales sin escribirlas en el repositorio.

---

### User Story 5 - ADR: GitOps pull frente a despliegue push (Priority: P2)

Como desarrollador, quiero un ADR que registre por qué se elige GitOps pull con un repositorio de
infraestructura separado en lugar de desplegar desde Actions, para que la decisión y sus
consecuencias queden trazables.

**Why this priority**: Es una decisión de plataforma que la constitución exige registrar y que
condiciona LAB-99 y las historias de promoción posteriores.

**Independent Test**: Revisar el ADR y comprobar que tiene contexto, decisión y consecuencias con
contenido, compara ambas alternativas y figura en el índice.

**Acceptance Scenarios**:

1. **Given** `docs/adr/`, **When** se añade el ADR, **Then** sigue la plantilla con estado,
   contexto, decisión y consecuencias, y se numera a continuación del último existente.
2. **Given** el ADR, **When** se lee, **Then** compara GitOps pull con repo separado frente a
   despliegue push desde Actions en seguridad de credenciales del clúster, auditoría, deriva y
   reversión.
3. **Given** el índice de `docs/adr/README.md`, **When** se revisa, **Then** incluye el nuevo ADR.

---

### Edge Cases

- La Application de un ambiente apunta a un overlay que no existe o no construye: queda en estado
  de error visible en Argo CD y no afecta a los demás ambientes.
- Un ambiente tarda en quedar Healthy (p. ej. descarga de modelos de Ollama): la sincronización
  no se marca como fallida mientras progresa; el tiempo esperado se documenta.
- `repoURL` con el valor de plantilla (`CAMBIA-ESTE-OWNER`) o `targetRevision` distinto de
  `main`: se rechaza en el CI de LAB-99 antes de fusionar.
- `prune` elimina un recurso gestionado a propósito fuera de git: la guía advierte que todo lo
  desplegado en un ambiente debe estar en su overlay.
- Cambio manual en el clúster: `selfHeal` lo revierte; no se admite como vía de despliegue.
- El overlay `dev` ya lo despliega la Application inicial de LAB-98: no debe haber dos
  Applications gestionando los mismos recursos.
- Secretos: las Applications no contienen valores sensibles; los secretos siguen llegando por
  ExternalSecret (LAB-80). Si el secreto no resuelve, la Application queda Degraded sin exponer
  valores.
- Argo CD aún no instalado o aún no Healthy cuando se aplica la raíz: la raíz converge en cuanto
  Argo CD está disponible, sin reintentos manuales.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `shoplab-infra/k8s/argocd/` MUST contener la Application raíz (app-of-apps) y una
  Application por ambiente: `application-staging.yaml` y `application-prod.yaml`.
- **FR-002**: Cada Application de ambiente MUST apuntar al overlay de su ambiente
  (`k8s/overlays/staging`, `k8s/overlays/prod`) y desplegar en el namespace de ese ambiente
  definido en LAB-80, sin compartir namespace entre ambientes.
- **FR-003**: La Application raíz MUST gestionar las Applications de ambiente almacenadas en
  `k8s/argocd/`, de modo que añadir o quitar una Application sea un cambio en git.
- **FR-004**: La raíz y cada Application de ambiente MUST declarar sincronización automática con
  `prune` y `selfHeal` activos.
- **FR-005**: `repoURL` de todas las Applications MUST ser la URL real del repositorio
  `shoplab-infra`, sin valores de plantilla.
- **FR-006**: `targetRevision` de todas las Applications MUST ser `main`.
- **FR-007**: Las Applications MUST NOT contener secretos ni credenciales; el acceso al
  repositorio y los secretos de las aplicaciones se resuelven por los mecanismos de LAB-98 y
  LAB-80.
- **FR-008**: Argo CD MUST quedar accesible desde el navegador mediante el ingress local, con su
  manifiesto de ingress versionado en el repositorio y sin dominios de producción.
- **FR-009**: Argo CD MUST seguir instalándose por Terraform (LAB-98); esta historia MUST NOT
  añadir una segunda vía de instalación ni mover componentes de plataforma a Argo CD.
- **FR-010**: Las Applications MUST ser compatibles con la validación del pipeline de LAB-99
  (rechazo de `repoURL` de plantilla y de `targetRevision` distinto de `main`) y con el esquema
  de Kubernetes.
- **FR-011**: La Application inicial de `dev` creada en LAB-98 MUST convivir con la raíz sin
  duplicar la gestión de los mismos recursos; si la raíz la incorpora, MUST quedar definida una
  sola vez.
- **FR-012**: Una guía en `docs/` MUST describir el flujo GitOps, la frontera entre Terraform
  (plataforma) y Argo CD (aplicaciones), cómo acceder a Argo CD y cómo diagnosticar un ambiente
  OutOfSync o Degraded.
- **FR-013**: Un ADR en `docs/adr/` MUST registrar la decisión GitOps pull con repositorio de
  infraestructura separado frente a despliegue push desde Actions, con contexto, decisión y
  consecuencias, y MUST añadirse al índice.
- **FR-014**: Un fallo en una Application de ambiente MUST NOT impedir la sincronización de las
  demás ni de la raíz.

### Key Entities *(include if feature involves data)*

- **Application raíz (app-of-apps)**: Application de Argo CD cuyo contenido son las Applications
  de ambiente; único punto de entrada para el arranque del clúster.
- **Application de ambiente**: Application de Argo CD que enlaza un overlay de `shoplab-infra`
  con el namespace de su ambiente y su política de sincronización.
- **Política de sincronización**: reglas de convergencia (automática, `prune`, `selfHeal`) que
  mantienen el clúster igual a git.
- **Frontera plataforma/aplicaciones**: reparto de responsabilidades: Terraform gestiona la
  plataforma del clúster; Argo CD gestiona las aplicaciones de ShopLab.
- **ADR de GitOps**: registro de la decisión de despliegue pull y sus consecuencias.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Con solo la raíz aplicada, el 100 % de las Applications de ambiente (2) se crean y
  quedan Synced y Healthy sin acciones manuales adicionales.
- **SC-002**: Un cambio fusionado en `main` sobre un overlay se refleja en el clúster en menos de
  5 minutos sin intervención.
- **SC-003**: Una modificación manual de un recurso gestionado se revierte automáticamente en
  menos de 5 minutos en el 100 % de los casos.
- **SC-004**: El 100 % de las Applications tienen `targetRevision: main`, un `repoURL` sin
  plantilla y sincronización automática con `prune` y `selfHeal`.
- **SC-005**: La interfaz de Argo CD carga desde el navegador por el ingress local en el 100 % de
  las pruebas del entorno recién levantado.
- **SC-006**: Una persona que no participó en la historia cambia un valor de `staging` y lo ve
  sincronizado, solo con la guía, en menos de 15 minutos.
- **SC-007**: Una violación introducida a propósito (`repoURL` de plantilla o `targetRevision`
  distinto de `main`) hace fallar el PR en el 100 % de los intentos.
- **SC-008**: 0 credenciales o valores secretos en los manifiestos de `k8s/argocd/`.

## Assumptions

- LAB-98 está completada: el cluster local, ingress-nginx, External Secrets y Argo CD están
  instalados por Terraform; esta historia solo aporta las Applications y el acceso por ingress.
- LAB-80 está completada: existen los overlays `dev`, `staging` y `prod` en `k8s/overlays/` y
  cada uno define su namespace (`shoplab-dev`, `shoplab-staging`, `shoplab-prod`).
- LAB-99 aporta el pipeline que valida `repoURL` y `targetRevision`; esta historia solo asegura
  compatibilidad.
- `dev` ya se despliega con la Application inicial de LAB-98; las Applications nuevas cubren
  `staging` y `prod`, y la raíz puede incorporar la de `dev` sin duplicarla (ver FR-011). La
  forma exacta se decide en el plan.
- El repositorio `shoplab-infra` es público o Argo CD ya tiene acceso de lectura configurado por
  LAB-98; la gestión de credenciales de repositorios privados queda fuera de alcance.
- En el clúster local solo se despliega `dev` (decisión tras la validación en vivo): el único nodo de k3s de Floci tiene
  unos 2 CPU y los tres ambientes juntos dejaban 17 pods `Pending` por falta de CPU. La raíz del clúster local
  sincroniza `k8s/argocd-local`, que retira `staging` y `prod`; estas `Application` siguen definidas y se validan en CI
  para un clúster real, cuya raíz sincronizaría `k8s/argocd` (los tres). Donde este documento dice que la raíz crea las
  `Application` de ambiente, en local eso es solo `dev`; SC-001 se entiende por tanto sobre `dev` en local y sobre los
  tres en un clúster real.
- La dirección local de Argo CD usa un host de prueba que resuelve a la máquina local (patrón
  usado en LAB-80); el nombre concreto se fija en el plan.
- La promoción automática entre ambientes, la aprobación manual para `prod` y los despliegues
  desde CI pertenecen a historias posteriores del sprint y quedan fuera de alcance.
- Fuera de alcance: notificaciones de Argo CD, ApplicationSets, SSO, TLS gestionado y Argo CD
  Image Updater.
- En la prueba de humo de CI solo se exige que la `Application` de `dev` exista, no que quede `Healthy`: el k3s de
  Floci no trae `StorageClass` y los PVC de Postgres y Ollama no se enlazan. `dev` llega a `Healthy` en local cuando
  hay una `StorageClass` (verificado con una clase sin provisionador y PV `hostPath`); automatizarla queda fuera de
  alcance.
