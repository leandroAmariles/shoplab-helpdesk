# Quickstart: validar LAB-79

Guía de validación de la historia. Los comandos se ejecutan en `shoplab-infra` salvo que se indique otra cosa.
Contratos: [application-contract.md](contracts/application-contract.md) y
[bootstrap-contract.md](contracts/bootstrap-contract.md). Entidades: [data-model.md](data-model.md).

## Prerrequisitos

- LAB-98 y LAB-80 aplicados: Docker, Terraform, `kubectl`, `kustomize`, `kubeconform` y `yq` (ver
  `scripts/check-tools.sh`); `secrets.auto.tfvars` creado.
- Los cambios de LAB-79 fusionados en `main` de `shoplab-infra` (Argo CD sincroniza `main`, no la rama local).
- Memoria libre para el entorno local (16 GB recomendados para Docker); en local solo se despliega `dev`.

## 1. Estáticos (sin clúster)

```bash
make kustomize          # construye k8s/argocd y los 3 overlays; kubeconform + políticas
make test-policies      # fixtures de políticas (incluye APPLICATION_NO_FINALIZER)
make lint && make tf-test    # terraform fmt/validate/tflint y terraform test (ingress, initial_app)
```

Esperado: todo en verde; `.rendered/argocd.yaml` contiene 3 `Application` y `.rendered/argocd-root.yaml` la raíz.
Violaciones a propósito (cada una debe fallar con archivo y regla): `repoURL` con `CAMBIA-ESTE-OWNER`,
`targetRevision: HEAD`, `Application` sin finalizer, `root.yaml` listado en el kustomization.

## 2. Arranque desde cero (US1, US2)

```bash
make up
export KUBECONFIG=terraform/envs/local/kubeconfig.yaml
kubectl -n argocd get applications
```

Esperado: `shoplab-root` y, creadas por ella, `shoplab-dev`, `shoplab-staging` y `shoplab-prod`; con el tiempo
todas `Synced/Healthy`. La raíz tiene la política automática; `guestbook` ya no existe.

## 3. Convergencia, `prune` y `selfHeal` (US1)

```bash
# deriva manual: debe revertirse en < 5 min
kubectl -n shoplab-staging scale deploy/frontend --replicas=7
# cambio en git: editar un valor de overlays/staging, fusionar en main y observar
kubectl -n argocd get application shoplab-staging -w
```

Esperado: la réplica manual vuelve al valor de git (SC-003) y el cambio fusionado aparece en < 5 min (SC-002).
Quitar un recurso del overlay en `main` y comprobar que desaparece del clúster (`prune`).

## 4. App-of-apps (US2)

- Añadir `application-demo.yaml` y listarlo en `k8s/argocd/kustomization.yaml`; fusionar. Esperado: la `Application`
  aparece sin tocar el clúster.
- Quitar la línea del `kustomization.yaml`; fusionar. Esperado: la `Application` y sus recursos se retiran
  (finalizer).
- Confirmar que `kubectl -n argocd get application shoplab-root -o yaml` no lista a la raíz entre sus recursos.

## 5. Acceso por ingress (US3)

```bash
kubectl -n ingress-nginx port-forward svc/ingress-nginx-controller 8080:80 &
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: argocd.localtest.me' http://127.0.0.1:8080/
```

Esperado: respuesta 200 (o redirección a la interfaz) y `http://argocd.localtest.me:8080` carga la interfaz con las
cuatro `Application`. Contraseña inicial con el comando de `bootstrap-contract.md`; ningún valor queda en git (SC-008).

## 6. Documentación y ADR (US4, US5)

- Una persona ajena sigue `docs/gitops.md` para cambiar las réplicas de `staging` y revertirlo (SC-006, < 15 min).
- En `shoplab-helpdesk`: `docs/adr/0006-gitops-pull-repo-infra-separado.md` con Contexto, Decisión y Consecuencias, y su
  fila en `docs/adr/README.md`.

## 7. Limpieza

```bash
make down       # destruye todo y ejecuta verify-clean.sh
```

## Resultados

Ejecutado el 2026-10-25 con los cambios aún sin fusionar en `main` de `shoplab-infra` (Argo CD sincroniza `main` de
GitHub, así que las `Application` hijas todavía no existen en el clúster; ver "Pendiente").

| Verificación | Resultado |
| --- | --- |
| §1 Estáticos | `kustomize-build.sh` (3 overlays + `k8s/argocd` + `root.yaml`), `policies.sh`, `policy-check.test.sh` (10 casos), `terraform test` (platform: 13 casos), `validate`, `lint` y Trivy en verde; gitleaks sin fugas |
| §1 Violaciones a propósito | `repoURL` de plantilla, `targetRevision: HEAD` y finalizer ausente fallan en `policies`; raíz listada en el kustomization y raíz ausente fallan en `kustomize-build` |
| §2 Arranque desde cero | `up.sh` termina con 30 recursos creados; `shoplab-root` existe, `Synced/Healthy`, con finalizer, `prune`, `selfHeal` y `retry` idénticos a `root.yaml`; `guestbook` ya no existe |
| §5 Ingress | Ingress `argo-cd-argocd-server` (clase `nginx`, host `argocd.localtest.me`); con el `port-forward` al controlador: HTTP 200 por `Host` y por nombre, título "Argo CD", login con la contraseña inicial y la API lista `shoplab-root` |
| Smoke de CI reproducido en local (`SMOKE_ROOT_REVISION` = commit de la PR) | pasa: la raíz se apunta a la revisión, queda `Synced/Healthy` y crea `shoplab-dev`, `shoplab-staging` y `shoplab-prod`; destroy limpio |
| Limpieza | `down.sh`: 30 + 2 recursos destruidos, sin contenedores ni volumen del clúster |

Incidencia: el primer `up.sh` falló con `cannot re-use a name that is still in use` porque el volumen k3s
`floci-aws-eks-shoplab-local` conservaba el clúster de una sesión anterior de LAB-80 que no se destruyó. `down.sh`
(con su `terraform destroy`) lo eliminó y el segundo `up.sh` funcionó desde cero. Si vuelve a ocurrir: `make down`
antes de `make up`.

### Validación en vivo con los cambios ya en `main` (2026-10-25)

Entorno levantado desde cero con `up.sh`, sin ningún override de revisión (la raíz sincroniza `main`).

| Verificación | Resultado |
| --- | --- |
| §2 Arranque desde cero | 30 recursos creados. `shoplab-root` `Synced/Healthy` crea `shoplab-dev`, `shoplab-staging` y `shoplab-prod` y sus namespaces en segundos, sin pasos manuales |
| `selfHeal` | el host del ingress de `dev` editado a mano volvió al valor de git en menos de 10 s |
| Finalizer y raíz | borrar `shoplab-staging` a mano dejó sus workloads en 0 en 10 s (cascada del finalizer) y la raíz recreó la `Application` (nuevo `uid`) y sus recursos en 20 s |
| §5 Ingress | por `port-forward` al controlador: `argocd.localtest.me` 200 con la interfaz de Argo CD y la API lista las 4 `Application`; `shoplab.localtest.me` (dev) y `shoplab.example` (prod) responden 503 (ruta válida, sin `frontend` listo); `staging.shoplab.example` 200; un host inventado 404 de nginx |
| Limpieza | `down.sh`: 30 + 2 recursos destruidos, sin contenedores ni volumen del clúster |

**Los tres ambientes quedan `Synced` pero `Degraded`.** Dos causas, ninguna es de las `Application`:

- **CPU (T003):** el único nodo de k3s de Floci tiene 1930m asignables y los tres ambientes juntos piden más: con 1900m (98 %) reservados quedan 17 pods `Pending` (`Insufficient cpu`). La memoria no es el límite (39 % usada, 2960 Mi).
- **Almacenamiento:** el k3s de Floci no trae `StorageClass`; los PVC de Postgres y Ollama quedan `Pending` en los tres ambientes (ya documentado en `docs/kustomize.md`).

Los `ExternalSecret` de los tres ambientes sí sincronizaron (`SecretSynced`), de modo que las claves de `app_environments` funcionan.

### Segunda validación: solo `dev` en local (2026-10-25)

Tras la decisión de desplegar solo `dev` (research R10). Entorno levantado desde cero y la raíz apuntada al commit de la
rama `fix/LAB-79-solo-dev-en-local` (en `main` aún no existía `k8s/argocd-local`).

| Verificación | Resultado |
| --- | --- |
| Alcance | solo `shoplab-root` y `shoplab-dev`; no hay namespaces de `staging` ni `prod` |
| CPU reservada | 44 % (850m) con `backend`, `frontend` e `ingest` `1/1 Running` (antes, con tres ambientes, 98 % y 17 pods `Pending`) |
| `dev` sin `StorageClass` | `Synced/Progressing`: Postgres y Ollama esperan sus PVC `Pending` |
| `dev` con una `StorageClass` sin provisionador y PV `hostPath` | `Synced/Healthy`, 5 pods `1/1 Running`; hizo falta `chmod 777` de los directorios `hostPath` en el nodo porque Postgres no corre como root |
| Aplicación por el ingress (`port-forward` al 18080) | `shoplab.localtest.me` 200 con el título «ShopLab Helpdesk»; readiness del backend `{"status":"UP"}`; `argocd.localtest.me` 200 |
| Limpieza | `down.sh`: 22 + 2 recursos destruidos, sin contenedores ni volumen del clúster |

`/api/actuator/health` por el ingress devuelve 404: el ingress enruta `/api` al backend sin reescribir la ruta y Actuator
no cuelga de `/api`. No es un fallo de esta historia.

### Pendiente

- `prune` y alta/baja de una `Application` por git: requieren una PR que cambie `k8s/argocd` o un overlay en `main`
  (el cambio de `main` ya hace que la raíz cree y recree las `Application`, y la baja manual activa el finalizer).
- §3 «cambio fusionado reflejado en < 5 min» y la prueba de lectura de la guía (T033).
- Que `dev` quede `Healthy` sin pasos manuales: falta una `StorageClass` en el clúster local de LAB-98 (hoy solo
  se probó con una creada a mano).

### Aviso sobre el puerto 8080

`docs/gitops.md` y `argocd_url` usan el puerto 8080 para el `port-forward`. En esta máquina otra aplicación ya lo
ocupaba y las respuestas eran de ella (no del ingress). Si el 8080 está ocupado, usa otro puerto local
(`kubectl -n ingress-nginx port-forward svc/ingress-nginx-controller 18080:80`) y `curl -H 'Host: argocd.localtest.me'`.
