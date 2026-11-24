# Quickstart: Creación y Despliegue de un Servicio desde la Plantilla

**Feature**: `LAB-103-reusable-workflows-service-template`
**Date**: 2026-11-24

Este documento describe cómo crear, parametrizar y verificar en local y en CI un nuevo microservicio utilizando `shoplab-service-template` y `shoplab-ci`.

---

## 1. Prerrequisitos

* Java JDK 21 instalado (`java -version`).
* Docker / Docker Compose activo para Testcontainers (`docker info`).
* Maven 3.9+ (`mvn -version`).
* Acceso a GitHub Packages configurado en `~/.m2/settings.xml` para resolver artefactos de `com.shoplab:shoplab-contracts`.

---

## 2. Paso a Paso: Crear un Nuevo Servicio

### Paso 1: Instanciar desde GitHub
1. Ir al repositorio `leandroamariles/shoplab-service-template`.
2. Hacer clic en **Use this template** -> **Create a new repository**.
3. Nombrar el repositorio siguiendo la convención de `docs/mapa-servicios.md`:
   * Ejemplo: `shoplab-catalog-service`
4. Seleccionar visibilidad y clonar en local:
   ```bash
   git clone git@github.com:leandroamariles/shoplab-catalog-service.git
   cd shoplab-catalog-service
   ```

### Paso 2: Parametrizar el Servicio
Ejecutar el script inicial de renombramiento o reemplazar las siguientes variables en el código:
* **Nombre del servicio**: Reemplazar `template` por `catalog` en `pom.xml` (`<artifactId>catalog-service</artifactId>`).
* **Paquete Java**: Renombrar de `com.shoplab.template` a `com.shoplab.catalog`.
* **Prefijo de Códigos de Error**: En `ResponseCodeEnum.java`, actualizar el prefijo a `CATL` (ej: `CATL_GE_00001`).
* **Esquema de Base de Datos**: En `application.yml`:
  ```yaml
  spring:
    application:
      name: catalog-service
    flyway:
      schemas: catalog
      default-schema: catalog
      locations: classpath:db/migration/catalog
  ```
* Renombrar la carpeta de migraciones:
  ```bash
  mv src/main/resources/db/migration/template src/main/resources/db/migration/catalog
  ```

### Paso 3: Verificación Local
Ejecutar la suite completa de pruebas unitarias, de integración con Testcontainers y reglas de ArchUnit:
```bash
mvn clean verify
```
* Verificar que JaCoCo genere el reporte en `target/site/jacoco/jacoco.csv` y que la cobertura sea `>= 80%`.
* Verificar que `HexagonalArchitectureTest` pase en verde.

### Paso 4: Construcción y Validación del Contenedor
Construir la imagen Docker localmente:
```bash
docker build -t ghcr.io/leandroamariles/catalog-service:local .
```

### Paso 5: Primer Push y Activación de `shoplab-ci`
1. Asegurarse de que `.github/workflows/ci.yml` invoque el workflow de `shoplab-ci`:
   ```yaml
   name: CI
   on:
     push:
       branches: [main]
     pull_request:

   jobs:
     pipeline:
       uses: leandroamariles/shoplab-ci/.github/workflows/java-service.yml@v1
       with:
         image-name: 'ghcr.io/leandroamariles/catalog-service'
         coverage-threshold: 80
       secrets:
         github-token: ${{ secrets.GITHUB_TOKEN }}
   ```
2. Realizar commit y push:
   ```bash
   git add .
   git commit -m "feat(init): inicializar servicio desde plantilla shoplab-service-template"
   git push origin main
   ```
3. Verificar en la pestaña **Actions** de GitHub que el workflow `java-service@v1` corre los pasos de build, tests, Qodana, Trivy y validación de políticas.

---

## 3. Registro en `shoplab-infra` para Despliegue GitOps

Cuando el servicio esté listo para desplegarse en el clúster `dev`:
1. Abrir un PR en `shoplab-infra`.
2. Registrar el nuevo servicio en `k8s/overlays/dev/kustomization.yaml`:
   ```yaml
   images:
     - name: ghcr.io/leandroamariles/catalog-service
       newTag: v0.1.0
   ```
3. Crear los manifiestos base del Deployment y Service en `k8s/base/catalog-service/`.
4. Una vez fusionado, cada release `vX.Y.Z` disparará el job `deploy.yml@v1` que actualizará automáticamente el tag en `shoplab-infra`.
