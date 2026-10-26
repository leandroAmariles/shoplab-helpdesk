# ADR-0008: Un solo ambiente dev en shoplab-infra

- Estado: aceptado
- Fecha: 2026-10-26
- Sustituye a: ADR-0006 y ADR-0007, solo en lo relativo a los ambientes `staging` y `prod` (el resto de ambos sigue vigente)

## Contexto

LAB-80 y LAB-79 dejaron `shoplab-infra` con tres ambientes (`dev`, `staging`, `prod`): un overlay de Kustomize y una
`Application` de Argo CD por cada uno, validados en cada pull request por el pipeline de LAB-99. En la práctica solo
corre `dev`: el único nodo de k3s de Floci tiene unos 2 CPU y los tres ambientes juntos dejaban 17 pods `Pending`
(ADR-0006). Para que eso funcionara se añadió `k8s/argocd-local`, un kustomization que retira las `Application` de
`staging` y `prod` con `$patch: delete`, y la raíz de Argo CD del clúster local sincroniza ese directorio en lugar de
`k8s/argocd`. Además, el entorno raíz de Terraform se llama `local` aunque levanta el ambiente `dev`, y la variable
`app_environments` permite crear claves de secretos de ambientes que nunca se despliegan.

El resultado es configuración de `staging` y `prod` que se escribe, se revisa y se valida sin ejecutarse nunca, y una
capa (`argocd-local`) que existe solo para ocultarla. Es un laboratorio para una sola persona en su máquina (LAB-101):
no hay promoción entre ambientes, aprobación manual de `prod` ni clúster real que los aloje.

## Decisión

`shoplab-infra` define un único ambiente, `dev`:

- `k8s/` contiene `base/`, el overlay `k8s/overlays/dev` y, en `k8s/argocd/`, la `Application` raíz y la de `dev`. Se
  eliminan los overlays `staging` y `prod`, sus `Application` y `k8s/argocd-local`. La raíz sincroniza `k8s/argocd`.
- El pipeline construye y valida solo `dev` (`OVERLAYS=dev`). Los jobs y el gate `ci-gate` conservan sus nombres, así que
  la protección de `main` no cambia.
- Terraform tiene un único entorno raíz, `terraform/envs/dev` (antes `envs/local`): clúster `shoplab-dev`, bucket de
  estado `shoplab-tfstate-dev` y secretos bajo `/shoplab/dev/`. Se eliminan `app_environments` y `module.app_secrets`,
  que con el nuevo nombre crearían los mismos secretos que `module.secrets`.
- El overlay `dev` no cambia: lo que se despliega es idéntico, y el despliegue continuo de ADR-0007 sigue escribiendo
  solo en `k8s/overlays/dev/kustomization.yaml`.

Se descartan:

- **Mantener los tres ambientes y seguir ocultando dos en local**: conserva el coste de mantenimiento y el parche
  `argocd-local` sin ningún uso real.
- **Dejar `staging` y `prod` como plantillas comentadas**: reintroduce configuración muerta que se desactualiza.
- **Conservar `environment = "local"` dentro de `envs/dev`**: evita recrear el clúster, pero deja dos nombres para el
  mismo ambiente y mantiene el choque de secretos con `app_environments`.

## Consecuencias

- Hay menos que mantener y revisar: tres overlays y tres `Application` pasan a uno, y el pipeline construye menos.
- Quien tenga un entorno local anterior debe destruirlo antes de actualizar (`make down` con la versión previa) y volver
  a levantarlo: el clúster y el estado cambian de nombre y no se migran. El estado vive en un Floci desechable, así que
  se pierden solo los datos del laboratorio (Postgres y modelos de Ollama), como en cualquier `make down`. El
  procedimiento está en `docs/terraform-local.md` de `shoplab-infra`.
- Si un clúster llegó a sincronizar `staging` o `prod`, la raíz elimina sus `Application` al fusionar; si quedara algo
  huérfano, `docs/gitops.md` de `shoplab-infra` indica cómo limpiarlo.
- ADR-0006 y ADR-0007 siguen vigentes salvo donde mencionan tres ambientes o dejan `staging` y `prod` para historias
  posteriores; su línea de estado lo indica.
- La promoción entre ambientes y la aprobación manual de `prod` dejan de ser un objetivo del laboratorio.

## Cómo añadir otro ambiente

Si hiciera falta (por ejemplo, un clúster real), los pasos mínimos son:

1. **Overlay**: crear `k8s/overlays/<env>/` sobre `base/` con su `kustomization.yaml`, `namespace.yaml`, imágenes,
   recursos (`patches/resources.yaml`), HPA y host, usando `k8s/overlays/dev` como modelo.
2. **Application**: crear `k8s/argocd/application-<env>.yaml` (copiando la de `dev`: `targetRevision: main`, `repoURL`
   real y finalizer) y listarla en `k8s/argocd/kustomization.yaml`.
3. **Pipeline**: añadir `<env>` a `OVERLAYS` en `versions.env`; el job `kustomize` y las políticas lo cubren sin más
   cambios.
4. **Secretos**: tener las claves `/shoplab/<env>/...` en Secrets Manager (el overlay sustituye `ENVIRONMENT` por su
   nombre).
5. **Terraform**: si el ambiente necesita su propio clúster y estado, crear `terraform/envs/<env>/` (con su bucket
   `shoplab-tfstate-<env>`) a partir de `envs/dev` y apuntar su raíz de Argo CD al directorio de sus `Application`; si
   comparte clúster con `dev`, basta con los pasos 1 a 4. En el nodo de Floci un segundo ambiente no cabe por CPU.

No hay generador ni plantilla: este procedimiento es la referencia.
