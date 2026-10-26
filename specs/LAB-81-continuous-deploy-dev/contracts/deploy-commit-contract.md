# Contrato: commit de despliegue en `shoplab-infra`

## Formato

```text
chore(dev): [LAB-81] despliega <sha7> a dev

Aplicación: leandroAmariles/shoplab-helpdesk@<sha completo>
CI: <URL de la ejecución de GitHub Actions>
Servicios: backend, frontend, ingest
```

## Reglas

| Regla | Detalle |
|---|---|
| Tipo | `chore` con ámbito `dev`; no dispara release en `shoplab-infra` |
| Prefijo | `[LAB-81]` mientras el despliegue lo gestione esa historia |
| SHA | el completo de 40 caracteres, en el cuerpo; 7 en el asunto |
| Enlace | la URL de la ejecución que publicó las imágenes (`run_id`) |
| Archivos | solo `k8s/overlays/dev/kustomization.yaml` |
| Autor | la App (`<app>[bot]`), no una persona |
| Unicidad | un commit por SHA; si ya está fijado no se crea otro |

## Cómo se usa en un rollback

`git log --format=%B -n 5 -- k8s/overlays/dev/kustomization.yaml` identifica el despliegue por SHA y ejecución;
`git revert <commit>` lo deshace. Ver `docs/despliegue-continuo.md`.
