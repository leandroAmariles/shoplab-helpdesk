# Contrato de Estructura de Manifiestos por Servicio (LAB-104)

Este contrato define la convención inmutable que todo microservicio (extraído o nuevo) debe cumplir en el repositorio `shoplab-infra` para que el ApplicationSet de Argo CD y el pipeline de CI lo reconozcan y desplieguen correctamente.

---

## 1. Convención de Nomenclatura

El identificador `<servicio>` DEBE coincidir con el nombre oficial del microservicio registrado en `docs/mapa-servicios.md` (ej. `chat-service`, `retrieval-service`, `eval-service`, `catalog-service`, `order-service`, `ticket-service`, `gateway`, `notification-service`).

---

## 2. Contenido de `apps/<servicio>/base/`

Cada carpeta `base` debe ser autónoma y compilar limpiamente con `kustomize build apps/<servicio>/base`:

1. **`kustomization.yaml`**:
   ```yaml
   apiVersion: kustomize.config.k8s.io/v1beta1
   kind: Kustomization
   resources:
     - deployment.yaml
     - service.yaml
     - externalsecret.yaml
   commonLabels:
     app.kubernetes.io/name: <servicio>
     app.kubernetes.io/part-of: shoplab
   ```

2. **`deployment.yaml`**:
   * Contenedor con nombre idéntico al servicio.
   * `livenessProbe` en `/actuator/health/liveness` (puerto 8080).
   * `readinessProbe` en `/actuator/health/readiness` (puerto 8080).
   * `resources` mínimos para dev (p. ej., requests: 256Mi RAM, 100m CPU; limits: 512Mi RAM, 500m CPU).
   * Variables de entorno de base de datos extraídas de `<servicio>-db-secret`.

3. **`service.yaml`**:
   * Tipo: `ClusterIP`.
   * Puerto: `8080` mapeado a `targetPort: 8080`.

4. **`externalsecret.yaml`**:
   * Apuntando a `shoplab/dev/<servicio>/database`.
   * Generando el Secret nativo `<servicio>-db-secret`.

---

## 3. Contenido de `apps/<servicio>/overlays/dev/`

El overlay es el punto de enganche directo para el ApplicationSet de Argo CD:

1. **`kustomization.yaml`**:
   ```yaml
   apiVersion: kustomize.config.k8s.io/v1beta1
   kind: Kustomization
   resources:
     - ../../base
   namespace: shoplab-dev
   replicas:
     - name: <servicio>
       count: 1
   images:
     - name: <servicio>
       newName: ghcr.io/leandroamariles/<servicio>
       newTag: v1.0.0 # Actualizado automáticamente por CI
   ```
