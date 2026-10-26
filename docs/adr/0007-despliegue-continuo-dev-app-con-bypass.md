# ADR-0007: Despliegue continuo a dev con una GitHub App con bypass

- Estado: aceptado
- Fecha: 2026-10-26

## Contexto

LAB-81 pide que un merge a `main` termine desplegado en `dev` sin pasos manuales y sin que el CI toque el clúster. El
ADR-0006 ya decidió el modelo pull: Argo CD sincroniza `shoplab-infra`, así que el pipeline de la aplicación solo debe
registrar la nueva versión en ese repositorio (`kustomize edit set image` con el SHA inmutable de LAB-78).

El obstáculo es el ruleset `main-protect` de `shoplab-infra`: exige PR (squash), los checks `ci-gate` y `pr-title`, historial
lineal y **no tiene actores de bypass**. Con ese ruleset un token de escritura no puede empujar a `main`.

## Decisión

El job `deploy` de `images.yml` escribe en `shoplab-infra` con una **GitHub App propia** (`shoplab-deploy`), instalada solo
en ese repositorio con el permiso `contents: write`, y la App es el **único actor de bypass** del ruleset. El token es de
instalación, dura una hora y se genera en cada ejecución; la clave privada vive en el `Environment` `dev`.

Como el bypass elude `ci-gate`, el job valida antes de empujar: construye el overlay `dev`, exige que cada imagen
renderice con el tag del SHA y ejecuta `policy-check.sh` de `shoplab-infra` (sin `:latest`, sin `Secret` en claro). Solo
modifica `k8s/overlays/dev/kustomization.yaml`.

Se descartan:

- **PR automática con auto-merge**: respeta la política de PR, pero `strict_required_status_checks_policy` obliga a
  actualizar la rama con cada merge concurrente, `ci-gate` añade minutos y se incumple el objetivo de menos de 5 minutos
  desde la publicación; además requeriría el permiso de PR.
- **PAT fine-grained personal**: no admite ser actor de bypass sin ser administrador, depende de una persona y caduca como
  mucho al año.

## Consecuencias

- Un merge a `main` de la aplicación se refleja en `shoplab-infra` en minutos, con un commit auditable que enlaza el SHA y
  la ejecución de CI; el rollback es `git revert` de ese commit.
- El CI de la aplicación no tiene credenciales del clúster; la superficie de escritura se limita a un repositorio y un archivo.
- El bypass es un riesgo acotado, no nulo: la App podría empujar contenido que `ci-gate` rechazaría. Se mitiga con la
  validación previa, el alcance de un solo repositorio, el `Environment` limitado a `main` y que el despliegue solo corre
  desde `main` de la aplicación, que sí exige PR y CI.
- Los humanos siguen necesitando PR para cambiar `shoplab-infra`; un rollback también pasa por PR.
- Hay que mantener la App: rotar su clave y reaplicar el ruleset con `DEPLOY_APP_ID` si se recrea.
- `staging` y `prod` quedan fuera: su promoción (y la aprobación manual de `prod`) pertenece a historias posteriores.
