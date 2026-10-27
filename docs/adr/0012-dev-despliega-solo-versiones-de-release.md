# ADR-0012: Dev despliega solo versiones de release

- Estado: aceptado
- Fecha: 2026-10-27
- Sustituye a: ADR-0007, solo en lo relativo a qué dispara el despliegue y qué tag se fija (la GitHub App, el bypass y
  las validaciones previas siguen vigentes)

## Contexto

LAB-81 hizo que cada merge a `main` terminara en `dev`: el job `deploy` fijaba en el overlay el tag del SHA completo del
commit (ADR-0007). Con release-please (LAB-100) ya existen versiones `vX.Y.Z` con sus imágenes publicadas, y el SHA
dejaba a `dev` ejecutando código que no corresponde a ninguna versión: en Argo CD solo se veía un hash, sin forma de
saber a qué release pertenecía ni si estaba por delante o por detrás de la última.

Se evaluaron:

- **Mostrar la versión como etiqueta (`0.4.0-<sha7>`) sin dejar de desplegar el SHA**: no cambia el flujo, pero `dev`
  seguiría ejecutando commits intermedios que nadie versionó.
- **Desplegar solo los tags de release**: lo que ve el clúster es siempre una versión publicada y reproducible.

## Decisión

El job `deploy` de `images.yml` corre solo en el `push` de un tag `v*.*.*` (el tag que crea release-please al fusionar su
PR) y fija en `k8s/overlays/dev/kustomization.yaml` el tag `vX.Y.Z`. Un merge a `main` sigue publicando imágenes con el
tag del SHA y `latest`, pero ya no modifica `dev`.

- `deploy-dev.sh` recibe `APP_VERSION` (un tag `vX.Y.Z` sin sufijos) y `APP_SHA` (el commit del tag, solo para la
  trazabilidad). El asunto del commit de despliegue es `chore(dev): [LAB-81] despliega vX.Y.Z a dev` y el cuerpo conserva
  el SHA, la ejecución de CI y los servicios.
- El `Environment` `dev` de `shoplab-helpdesk` debe admitir el patrón de tags `v*.*.*` además de la rama `main`; sin eso
  GitHub bloquea el job.
- En `shoplab-infra`, `is-dev-deploy-only.sh` reconoce también `newTag: vX.Y.Z` para que el push de despliegue siga sin
  ejecutar el `smoke` completo.

## Consecuencias

- `dev` solo cambia cuando se fusiona un PR de release. Un `feat` o `fix` fusionado a `main` no se ve en `dev` hasta
  entonces; los `docs`, `chore`, `test`, `ci`, `build` y `refactor` no generan release (LAB-100) y
  por tanto nunca llegan.
- Probar un cambio en el clúster exige publicar una versión, o ejecutar la imagen por SHA en otro lugar.
- El rollback sigue siendo un `git revert` del commit de despliegue en `shoplab-infra`; el siguiente tag lo vuelve a
  avanzar.
- Los tags son inmutables (el job `publish` rechaza sobrescribirlos), así que una versión desplegada siempre apunta a la
  misma imagen.
- La primera versión posterior a este cambio es la que mueve `dev` de su SHA actual a un tag; hasta entonces `dev`
  conserva el último SHA desplegado.
- Si hubiera dos tags casi simultáneos, el grupo de concurrencia `deploy-dev` conserva solo el pendiente más reciente.
  No hay protección contra volver a ejecutar el job de una versión anterior, que haría retroceder `dev`.

