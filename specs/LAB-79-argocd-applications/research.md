# Research: Argo CD con una Application por ambiente

Fuentes: estado real de `shoplab-infra` (rama `main`, commit `168ffe3`), specs de LAB-80, LAB-98 y LAB-99 de este
repositorio y documentación de Argo CD. Lo que no se pudo comprobar se marca **A validar** y tiene una tarea.

## R1. Estado actual de `k8s/argocd/`

- **Hallazgo**: `shoplab-infra` ya tiene `k8s/argocd/{dev,staging,prod}/application.yaml` (cada uno con su
  `kustomization.yaml`), con `repoURL` real, `targetRevision: main`, `automated` con `prune` y `selfHeal` y
  `destination.namespace` por ambiente (LAB-80, PR #5). Ninguna se aplica: `argocd_initial_app` en
  `terraform/modules/platform/variables.tf` sigue con el ejemplo `guestbook` (`HEAD`).
- **Consecuencia**: la historia no parte de cero; reorganiza lo existente, añade la raíz y conecta el arranque.

## R2. Layout de archivos

- **Decisión**: archivos planos `application-dev.yaml`, `application-staging.yaml` y `application-prod.yaml` en
  `k8s/argocd/`, con un `kustomization.yaml` que los lista. `root.yaml` en el mismo directorio, fuera de la lista.
- **Justificación**: la historia nombra `application-staging.yaml` y `application-prod.yaml`. Con Kustomize, la raíz
  sincroniza `k8s/argocd` y solo ve las tres hijas; `root.yaml` no se gestiona a sí misma.
- **Alternativas**: subdirectorio `apps/` (se aleja de la ruta que pide la historia); mantener `<env>/application.yaml`
  (no cumple los nombres pedidos y obliga a la raíz a recorrer tres rutas); `directory.include` con glob
  (la raíz entendería YAML plano sin validación de Kustomize en CI).
- **Impacto**: `scripts/kustomize-build.sh` hoy construye `$ARGOCD_DIR/<overlay>`; pasa a construir `$ARGOCD_DIR` una
  vez y a validar `root.yaml` aparte (R7).

## R3. Application raíz y arranque desde Terraform

- **Decisión**: `k8s/argocd/root.yaml` define la raíz `shoplab-root` (`path: k8s/argocd`, `targetRevision: main`,
  destino `argocd`). Terraform la crea como `Application` inicial leyendo ese archivo:
  `yamldecode(file("${path.module}/../../../k8s/argocd/root.yaml"))` → `argocd_initial_app`, que pasa a ser el
  **manifiesto completo** (tipo `any`) y el chart local lo renderiza tal cual con `toYaml`. El valor por defecto
  `guestbook` se elimina y la variable valida `kind`, `repoURL` sin plantilla y `targetRevision: main`.
- **Ajuste en la implementación**: el plan pasaba solo `name`, `repo_url`, `path`, `target_revision` y `namespace`;
  eso dejaba `prune`, `selfHeal`, `retry` y el finalizer duplicados en el chart y podían divergir de git. Pasar el
  manifiesto completo elimina esa duplicación.
- **Justificación**: una sola fuente de verdad; `kubernetes_manifest` queda descartado porque el CRD de
  `Application` no existe en el plan (LAB-98 ya resolvió esto con un chart local).
- **Alternativas**: valores duplicados en `variables.tf` (divergen); aplicar la raíz con `kubectl` en un script
  (paso manual fuera de Terraform, rompe el "un solo apply" de LAB-98 FR-012); raíz gestionada por sí misma
  (bucle con `prune`).
- **Limitación aceptada**: cambiar `root.yaml` exige `terraform apply`; el resto cambia solo con git.

## R4. Argo CD por ingress local

- **Decisión**: el chart `argo-cd` crea el ingress (`server.ingress`, `ingressClassName: nginx`, host
  `argocd.localtest.me`) y `configs.params.server.insecure=true` para servir HTTP tras el ingress. Se accede con
  `kubectl -n ingress-nginx port-forward svc/ingress-nginx-controller 8080:80` y `http://argocd.localtest.me:8080`.
  La salida `argocd_url` pasa a esa dirección.
- **Justificación**: ingress-nginx es `NodePort` dentro del contenedor de k3s y no hay mapeo de puertos al host
  (`ingress_nginx.tf`, LAB-98); el `port-forward` al controlador cumple "accesible por ingress" sin tocar la creación
  del clúster, y sirve igual a `shoplab.localtest.me` de `dev`. `localtest.me` resuelve a 127.0.0.1.
- **Alternativas**: `port-forward` directo a `argo-cd-argocd-server` (es lo que hay hoy y no usa ingress); mapeo de
  puertos del contenedor de k3s (cambia LAB-98 y depende de cómo Floci lo crea); `LoadBalancer` (no recibe IP en
  k3s/kind, ya descartado en LAB-98).
- **A validar** (T001): que la versión fijada del chart acepte `server.ingress.hostname` y que el ingress responda por
  `Host` sin TLS. El ingress no lleva credenciales ni dominios de producción (FR-008).

## R5. Política de sincronización de las Application

- **Decisión**: todas con `syncPolicy.automated { prune: true, selfHeal: true }`, `retry` con backoff (5 intentos,
  de 10 s a 3 min) y `metadata.finalizers: [resources-finalizer.argocd.argoproj.io]`. Las hijas no usan
  `CreateNamespace`: el namespace lo crea cada overlay (LAB-80). `project: default`.
- **Justificación**: sin el finalizer, quitar una `Application` de git deja sus recursos huérfanos y el escenario
  "ambiente eliminado se retira del clúster" (US2.3) no se cumple. El `retry` cubre el CRD de External Secrets que aún
  no está registrado. `AppProject` propio queda fuera de alcance (VII).
- **Alternativas**: `ApplicationSet` (excluido del alcance y innecesario con 3 ambientes); sin `retry` (fallo
  intermitente al arrancar en frío).

## R6. Incorporar `dev` a la raíz

- **Decisión**: `application-dev.yaml` entra en la raíz junto a `staging` y `prod`; la `Application` inicial de
  Terraform pasa a ser la raíz, de modo que `dev` queda definida una sola vez (FR-011).
- **Justificación**: `dev` es el ambiente que verifica la prueba de humo de LAB-99; excluirlo dejaría el caso de uso
  principal sin Application.
- **Alternativas**: dejar `dev` fuera de la raíz (dos mecanismos de arranque); aplicar solo `dev` en local y
  `staging`/`prod` bajo una variable (añade configuración sin necesidad; se reevalúa si la memoria local no alcanza).

## R7. Adaptación del CI de LAB-99

- **Decisión**: `kustomize-build.sh` construye `$ARGOCD_DIR` (`k8s/argocd`) en `argocd.yaml` y copia `root.yaml` a
  `argocd-root.yaml`; `kubeconform` y `policy-check.sh` leen ambos. `versions.env` cambia `ARGOCD_DIR` a
  `k8s/argocd` (sin sufijo por ambiente). Se añade una regla `APPLICATION_NO_FINALIZER` en la política y fixtures de
  `scripts/tests/`. Las reglas existentes (`TEMPLATE_REPO_URL`, `TARGET_REVISION_NOT_MAIN`) ya cubren `repoURL` y
  `targetRevision` (FR-010).
- **Justificación**: reutiliza el pipeline y hace que cada violación de FR-005/FR-006 falle el PR (SC-007).
- **Alternativas**: validar solo en Terraform (no detecta cambios en `k8s/argocd`); regla de finalizer opcional (no
  garantiza el retiro de recursos).

## R8. Prueba de humo con tres ambientes

- **Hallazgo**: `smoke.sh` esperaba que *todas* las `Application` estuvieran `Synced/Healthy` (hoy: una). Con la raíz
  serían cuatro, y en un runner de CI ni `staging`/`prod` (más réplicas, HPA) ni `dev` pueden quedar Healthy: el k3s
  de Floci no trae `StorageClass`, así que los PVC de Postgres y Ollama quedan `Pending` (ver `docs/kustomize.md`).
- **Hallazgo 2 (CI de la PR #8)**: Argo CD sincroniza git y una PR no está en `main`, así que la raíz sincronizaba un
  `k8s/argocd` sin los cambios y no creaba ninguna hija. Como `ci-gate` es obligatorio para fusionar, el smoke nunca
  habría podido pasar antes de fusionar.
- **Decisión**: el smoke (a) apunta `shoplab-root` a la revisión de la PR con `kubectl patch` solo durante la prueba
  (`SMOKE_ROOT_REVISION`, que el workflow toma de `github.event.pull_request.head.sha`); `root.yaml` y las políticas
  siguen exigiendo `targetRevision: main`, y las hijas siguen en `main`; y (b) exige `shoplab-root` `Synced/Healthy` y
  que existan `shoplab-dev`, `shoplab-staging` y `shoplab-prod` (`ARGOCD_READY_APPS` y `ARGOCD_PRESENT_APPS` en
  `versions.env`). La convergencia completa de los ambientes se prueba en local, con una `StorageClass`.
- **Verificado**: el smoke reproducido en local con el commit de la PR pasa (la raíz queda `Synced/Healthy` aunque sus
  hijas no estén Healthy: Argo CD no evalúa la salud de las `Application` hijas por defecto).
- **Alternativas**: pasar solo en `push` a `main` (la PR que rompe la estructura se enteraría después de fusionar);
  sobrescribir `targetRevision` en Terraform (obliga a relajar la validación de `main` del módulo).

## R9. Guía y ADR

- **Decisión**: guía en `shoplab-infra/docs/gitops.md` (junto a `docs/kustomize.md`) con el flujo, la frontera
  Terraform/Argo CD, acceso y diagnóstico; ADR-0006 en `shoplab-helpdesk/docs/adr/` (donde está el registro, ver
  ADR-0005) comparando GitOps pull con repo separado y despliegue push desde Actions, y fila en `docs/adr/README.md`.
- **Frontera**: Terraform = Floci, clúster, secretos en Secrets Manager, ingress-nginx, External Secrets,
  monitorización y Argo CD (más la `Application` raíz). Argo CD = backend, frontend, ingest, Postgres y Ollama de cada
  ambiente.
- **Contenido del ADR**: push desde Actions exige dar al CI credenciales de escritura sobre el clúster y no detecta
  deriva; pull mantiene las credenciales dentro del clúster, audita con git, revierte la deriva (`selfHeal`) y permite
  `git revert` como rollback. Costo: componente extra que operar y un repo adicional.
