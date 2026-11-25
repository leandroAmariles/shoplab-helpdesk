# Quickstart: Despliegue por servicio en dev con ApplicationSet (LAB-104)

Este documento describe cómo probar y validar localmente la estructura modular de Kustomize, la generación automática del ApplicationSet de Argo CD y la resiliencia del job de despliegue con rebase.

---

## 1. Prerrequisitos

- `kubectl` v1.28+
- `kustomize` v5.4+
- `git` 2.40+
- Acceso al clúster de desarrollo local (Kind o EKS local con Argo CD instalado conforme a LAB-101 / LAB-98).

---

## 2. Validación de Manifiestos Kustomize en Local

Para verificar que cada microservicio compila limpiamente y no contiene violaciones de políticas:

```bash
# 1. Clonar o posicionarse en shoplab-infra
cd shoplab-infra

# 2. Validar la base de un servicio (ej. chat-service)
kustomize build apps/chat-service/base

# 3. Validar el overlay dev
kustomize build apps/chat-service/overlays/dev

# 4. Comprobar que no hay tags :latest
kustomize build apps/chat-service/overlays/dev | grep 'image:.*:latest' && echo "ERROR: tag latest detectado" || echo "OK: Sin tag latest"

# 5. Ejecutar la política global
./scripts/policy-check.sh apps/chat-service/overlays/dev
```

---

## 3. Verificación del ApplicationSet en Argo CD

Para desplegar y verificar el ApplicationSet en el clúster:

```bash
# 1. Aplicar el ApplicationSet
kubectl apply -f k8s/bootstrap/applicationset-services-dev.yaml

# 2. Comprobar que el ApplicationSet está activo
kubectl -n argocd get applicationset shoplab-services-dev

# 3. Listar las Applications generadas dinámicamente
kubectl -n argocd get applications -l app.kubernetes.io/part-of=shoplab

# Deberían figurar aplicaciones como:
# NAME                 SYNC STATUS   HEALTH STATUS
# chat-service-dev     Synced        Healthy
# retrieval-service-dev Synced       Healthy
# eval-service-dev     Synced        Healthy
```

---

## 4. Prueba de Alta Desatendida de un Nuevo Servicio

Simular cómo se da de alta un microservicio sin tocar Argo CD:

```bash
# 1. Crear la estructura para un nuevo servicio ficticio (ej. dummy-service)
mkdir -p apps/dummy-service/base apps/dummy-service/overlays/dev

# 2. Copiar y adaptar los manifiestos base desde chat-service
cp apps/chat-service/base/* apps/dummy-service/base/
sed -i 's/chat-service/dummy-service/g' apps/dummy-service/base/*.yaml

cp apps/chat-service/overlays/dev/* apps/dummy-service/overlays/dev/
sed -i 's/chat-service/dummy-service/g' apps/dummy-service/overlays/dev/*.yaml

# 3. Commitear y empujar a main en shoplab-infra
git add apps/dummy-service
git commit -m "feat(infra): alta de dummy-service para dev"
git push origin main

# 4. Observar la aparición automática de la Application en Argo CD (~3 minutos o forzando sync)
kubectl -n argocd get application dummy-service-dev -w
```

---

## 5. Simulación de Despliegue Concurrente con Rebase

Para probar el script de rebase ante colisiones de push:

```bash
# En un entorno de pruebas con dos clones locales de shoplab-infra:
# Terminal 1:
./scripts/deploy-service.sh chat-service v1.1.0

# Terminal 2 (simultáneo):
./scripts/deploy-service.sh retrieval-service v1.0.5

# Ambos jobs deben completar con código 0 y 'git log -2 --oneline' debe mostrar ambos commits ordenados consecutivamente.
```
