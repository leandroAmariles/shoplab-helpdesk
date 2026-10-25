# Contrato: estructura de Kustomize

## Árbol

```text
k8s/
├── base/
│   ├── kustomization.yaml          # resources: backend, frontend, ingest, postgres, ollama, ingress
│   └── <componente>/kustomization.yaml
└── overlays/<dev|staging|prod>/
    ├── kustomization.yaml          # resources: ../../base, namespace.yaml, hpa.yaml
    ├── namespace.yaml
    ├── hpa.yaml
    ├── config.env                  # configMapGenerator (behavior: merge)
    └── patches/                    # réplicas, recursos, host del ingress, remoteRef.key
```

## Reglas de composición

1. Los overlays solo usan `resources`, `patches`, `images`, `replicas`, `configMapGenerator` y `namespace`; nunca copian
   manifiestos de la base.
2. La base no define `namespace`; lo fija el overlay (`namespace: shoplab-<ambiente>`).
3. Imágenes: la base declara el nombre sin tag efectivo; el overlay lo fija con `images:` (tag `vX.Y.Z` o digest).
4. Los tres overlays tienen la misma estructura de archivos; añadir un parche a uno exige evaluar los otros dos.
5. Nombres de recursos y claves en inglés; etiquetas `app.kubernetes.io/*`.
6. Añadir un servicio (F4+): directorio en `base/<servicio>/`, línea en `base/kustomization.yaml`, entradas de `images`,
   `replicas`/`resources` y HPA en cada overlay, ExternalSecret si lleva credenciales.

## Comandos de verificación (paridad con LAB-99)

| Comando | Resultado esperado |
|---|---|
| `kustomize build k8s/overlays/<ambiente>` | Construye sin error y emite el conjunto completo de recursos |
| `kustomize build k8s/overlays/<ambiente> \| kubeconform -strict -summary` | Todos los recursos válidos |
| `scripts/policy-check.sh` | Pasa; falla ante `:latest`, imagen sin tag o `kind: Secret` |
| `make check` | Ejecuta lo anterior para los tres overlays |
