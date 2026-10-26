# Research: Despliegue continuo a dev

## R1 - Cómo entra el commit en `main` de `shoplab-infra`

**Hallazgo**: el ruleset `main-protect` de `shoplab-infra` exige PR (squash), checks `ci-gate` y `pr-title`,
historial lineal y **no tiene actores de bypass**. Un `git push` directo, incluso con token de escritura, se rechaza.

**Decisión**: añadir la GitHub App de despliegue como único actor de bypass (modo `always`) y empujar directo a `main`.

**Rationale**: es lo que piden los criterios (commit del nuevo tag, sin pasos manuales, SC-002 < 5 min). El riesgo se
acota porque la edición es determinista (solo el tag de tres imágenes) y el job valida antes de empujar (R4).

**Alternativas**:
- PR automática con auto-merge: cumple la política de PR, pero `strict_required_status_checks_policy` obliga a
  actualizar la rama con cada merge concurrente, `ci-gate` añade minutos y la App necesitaría `pull_requests: write`.
  Descartada; se reconsidera si `shoplab-infra` gana más colaboradores.
- PAT personal: sin bypass posible para un usuario sin ser administrador y con alcance ligado a una persona. Descartado.

## R2 - Credencial

**Decisión**: GitHub App propia, instalada solo en `shoplab-infra`, permiso `contents: write` y `metadata: read`.
El job genera un token de instalación efímero (1 h) con `actions/create-github-app-token`. Clave privada y ID en el
`Environment` `dev` del repositorio de la aplicación.

**Rationale**: cumple "token de alcance mínimo" mejor que un PAT fine-grained: no depende de una persona, el token
caduca solo, y rotar es regenerar la clave. Un `Environment` limita el secreto al job `deploy` y nunca llega a
ejecuciones de PR (el job no corre en ellas y los secretos no se pasan a forks).

**Alternativa**: PAT fine-grained con solo `shoplab-infra` y `contents: write`; válido como plan B si no se puede
crear la App (caduca como máximo en 1 año, requiere recordatorio de rotación).

## R3 - Reemplazo de digest por tag

**Hallazgo (verificado con Kustomize 5.8.1 local)**: el overlay `dev` fija hoy las tres imágenes por `digest`. Al
ejecutar `kustomize edit set image <img>=<img>:<tag>`, la entrada pasa a `newName`/`newTag` y se elimina el `digest`
(si no, el digest prevalecería sobre el tag). Los comentarios de la sección `images:` pueden desplazarse.

**Decisión**: el script usa `set image` con `ghcr.io/leandroamariles/<svc>=ghcr.io/leandroamariles/<svc>:<sha>` y
verifica después con `yq` que cada imagen tiene `newTag == <sha>` y ningún `digest`. El comentario de LAB-80 se
reescribe una vez en la PR hermana para que no quede obsoleto.

**Alternativa**: fijar por digest (`@sha256`) obtenido del registro: más estricto, pero la historia pide el `:sha`
inmutable y los tags SHA no se reasignan (LAB-78). El digest queda en el resumen para trazabilidad.

## R4 - Validar antes de empujar

**Decisión**: tras editar, el script ejecuta `kustomize build` del overlay `dev` y `scripts/policy-check.sh` del
propio `shoplab-infra` (sin `:latest`, sin `Secret` en claro, etc.), y solo entonces hace commit. Las herramientas
usan las versiones de `versions.env` de infra.

**Rationale**: sustituye parcialmente a la PR obligatoria que se evita en R1. Si falla, `dev` conserva su versión.

## R5 - Disparo, orden y concurrencia

**Decisión**: `deploy` en `images.yml` con `needs: publish` y `if: github.event_name == 'push' && github.ref_type == 'branch'`.
`needs` de una matriz exige las tres publicaciones correctas, lo que cubre la publicación parcial. Grupo propio
`deploy-dev` con `cancel-in-progress: false`: GitHub conserva solo el pendiente más reciente, de modo que un merge
intermedio puede omitirse pero `dev` termina en el SHA más nuevo (FR-011). El grupo de `images.yml` para `main` ya
serializa las publicaciones.

**Alternativa**: workflow aparte con `workflow_run`; descartado por añadir un salto y perder el contexto de la
ejecución (SHA y URL de CI son directos en el mismo workflow).

## R6 - Idempotencia, conflictos y servicios no declarados

**Decisión**: si tras `set image` no hay diferencia (`git diff --quiet`), sale en verde sin commit. Ante rechazo del
push: `git pull --rebase` y reintento, máximo 3, con espera breve creciente; luego falla. El script comprueba antes que
cada servicio publicado figura en `images:` del overlay y falla si no (edge case de la spec).

## R7 - Existencia del tag

**Decisión**: `docker buildx imagetools inspect ghcr.io/leandroamariles/<svc>:<sha>` para cada servicio antes de
editar (FR-006). Como el job corre tras `publish` ya debería existir; la comprobación protege contra reejecuciones
manuales y contra tags purgados.

## R8 - Rollback

**Decisión**: `git revert <commit de despliegue>` en `shoplab-infra`, que es la única acción; Argo CD
(`selfHeal`) converge. Como el ruleset exige PR para humanos, el revert se hace por PR; el siguiente merge de la
aplicación vuelve a avanzar `dev` porque el despliegue siempre fija el SHA actual (no depende del estado previo).
Documentado en `docs/despliegue-continuo.md`; la guía recuerda que, si `dev` sigue roto, un nuevo merge sobrescribirá
el revert y por eso conviene corregir o revertir también en la aplicación.

## R9 - Riesgo conocido

El bypass permite a la App saltarse `ci-gate`. Mitigaciones: validación previa (R4), alcance de la App a un solo
repositorio, `Environment` protegido, y que solo corre desde `main` de la aplicación (que sí exige PR y CI).

## Acciones fijadas (T003)

Resueltas el 2026-10-26 con `gh api repos/<acción>/commits/<tag>`; versiones con más de dos semanas de antigüedad.

| Acción | Versión | SHA |
|---|---|---|
| `imranismail/setup-kustomize` | v3.0.0 | `53f941b41dca13ed61874bbc6b4b6e1562877530` |
| `actions/create-github-app-token` | v3.2.0 | `bcd2ba49218906704ab6c1aa796996da409d3eb1` |

`KUSTOMIZE_VERSION` no se fija aquí: el job la lee de `versions.env` de `shoplab-infra` tras clonarlo, para usar
exactamente la que valida su CI (hoy 5.8.2).

## Ajustes de implementación respecto al plan

- El script no usa `yq` para comprobar el resultado: lo hace sobre la salida de `kustomize build` (la imagen
  renderizada de cada Deployment debe ser `<prefijo>/<servicio>:<sha>`), que es la prueba real de que el overlay
  produce el tag y no el digest. `yq` solo lo necesita `policy-check.sh` de infra, ya preinstalado en `ubuntu-latest`.
- El clonado de `shoplab-infra` lo hace `actions/checkout` con el token de la App; el script solo usa el remoto
  `origin` ya configurado y nunca imprime su URL.
- El job ejecuta `kustomize build` y `policy-check.sh`, no `kubeconform` (necesita descargar esquemas); el esquema lo
  sigue validando el CI de `shoplab-infra` sobre `main`.
