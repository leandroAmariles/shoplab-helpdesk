# ADR-0006: GitOps pull con repositorio de infraestructura separado

- Estado: aceptado
- Fecha: 2026-10-25

## Contexto

LAB-79 pide que cada ambiente (`dev`, `staging`, `prod`) se despliegue desde git y que el clúster refleje siempre lo que está en `shoplab-infra`. Ya existen el clúster local con Argo CD instalado por Terraform (ADR-0005) y los manifiestos Kustomize por ambiente (LAB-80). Falta decidir quién aplica esos manifiestos al clúster y dónde viven.

Hay dos modelos de despliegue:

- **Push desde GitHub Actions**: el pipeline hace `kubectl apply` o `helm upgrade` contra el clúster tras fusionar. El CI necesita credenciales con permisos de escritura sobre el clúster (un kubeconfig o un rol), y lo desplegado solo se corrige cuando se vuelve a ejecutar el pipeline: una modificación manual en el clúster queda sin detectar. La auditoría está repartida entre el historial de git y los logs de las ejecuciones. Para desplegar tres ambientes hay que mantener esas credenciales por ambiente en el CI.
- **Pull con GitOps (Argo CD)**: un controlador dentro del clúster lee git y converge el estado. Las credenciales de escritura no salen del clúster; el CI solo valida. Argo CD detecta la deriva y, con `selfHeal`, la revierte; con `prune` retira lo que ya no está en git. Volver atrás es un `git revert`.

En cuanto al repositorio, los manifiestos de despliegue pueden vivir junto al código de la aplicación (`shoplab-helpdesk`) o en un repositorio de infraestructura aparte (`shoplab-infra`, que ya existe con Terraform, Kustomize y el pipeline de LAB-99).

## Decisión

Se adopta **GitOps pull con Argo CD y un repositorio de infraestructura separado** (`shoplab-infra`), y se descarta el despliegue push desde Actions.

- Argo CD lo instala Terraform (plataforma). Terraform también crea una única `Application` raíz (`shoplab-root`), cuyo manifiesto está en `k8s/argocd/root.yaml` y que Terraform lee, de modo que git es la única fuente de verdad.
- La raíz (app-of-apps) sincroniza `k8s/argocd`, que contiene una `Application` por ambiente (`application-dev.yaml`, `application-staging.yaml`, `application-prod.yaml`), cada una apuntando a su overlay y a su namespace.
- Todas las `Application` sincronizan `main` de forma automática con `prune` y `selfHeal`, y llevan el finalizer de Argo CD para que quitar una `Application` de git retire también sus recursos.
- El CI de `shoplab-infra` (LAB-99) solo valida: construye cada overlay y cada `Application`, comprueba el esquema y rechaza `:latest`, `Secret` en claro, `repoURL` de plantilla, `targetRevision` distinto de `main` y `Application` sin finalizer. No tiene credenciales del clúster.
- La frontera queda así: Terraform gestiona la plataforma (clúster, ingress, secretos externos, monitorización, Argo CD y la raíz); Argo CD gestiona las aplicaciones de ShopLab.

## Consecuencias

- Las credenciales de escritura sobre el clúster no están en GitHub Actions; el despliegue es una lectura de git desde dentro del clúster.
- Git es el registro de auditoría de lo desplegado y `git revert` es el mecanismo de reversión. Una modificación manual en el clúster se revierte sola, así que cambiar un ambiente exige una PR.
- Se opera un componente más (Argo CD) y un repositorio adicional; los cambios que afectan a la aplicación y a su despliegue van en dos PR distintas.
- La raíz es lo único que no se actualiza sola: cambiar `root.yaml` exige `terraform apply`.
- El repositorio se lee sin credenciales porque es público; si pasa a privado habrá que registrar un secreto de repositorio en Argo CD.
- En local los tres ambientes comparten clúster y se aíslan por namespace. La prueba de humo del CI exige que la raíz quede `Synced` y `Healthy` y que cree las `Application` de ambiente, pero no que estas queden `Healthy` (el k3s de Floci no trae `StorageClass`); como Argo CD sincroniza git, el smoke apunta la raíz a la revisión de la PR solo durante la prueba. La convergencia completa de los ambientes se verifica en un entorno local con memoria y almacenamiento suficientes.
- La promoción automática entre ambientes, la aprobación manual para `prod` y la actualización automática de las imágenes tras un release no están cubiertas por esta decisión; pertenecen a historias posteriores y se resolverán sin volver al despliegue push.
