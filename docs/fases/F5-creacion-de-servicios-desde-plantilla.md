# F5 · Creación de nuevos servicios desde `shoplab-service-template` y CI/CD con `shoplab-ci`

Esta guía define el procedimiento oficial para crear un nuevo microservicio en su propio repositorio o extraer un módulo existente a partir del arquetipo oficial **`shoplab-service-template`**, conectarlo a los pipelines reutilizables de **`shoplab-ci`** y habilitar su entrega continua en **`shoplab-infra`**.

Forma parte del bloque de extracción temprana establecido en [ADR-0031](../adr/0031-extraccion-temprana-de-servicios.md) (LAB-128) y da cumplimiento a los estándares de arquitectura y soberanía de datos fijados en [ADR-0015](../adr/0015-limites-de-contexto-y-repositorios.md) y [docs/mapa-servicios.md](../mapa-servicios.md) (LAB-103).

---

## 1. Principios Mandatorios de Arquitectura

1. **Un repositorio por servicio**: Todo servicio nuevo o extraído nace en su propio repositorio GitHub (`shoplab-<servicio>-service`).
2. **Cero dependencias de código entre servicios**: Queda estrictamente prohibido que un servicio dependa de clases, JARs o código del monolito `shoplab-helpdesk` o de repositorios hermanos. La única dependencia externa permitida es el paquete de contratos de **`shoplab-contracts`** (`com.shoplab:shoplab-contracts`).
3. **Soberanía estricta de base de datos**: Cada servicio es dueño exclusivo de su esquema en PostgreSQL (`spring.flyway.schemas=<servicio>`), con sus propias migraciones en `db/migration/<servicio>` y su tabla `<servicio>.flyway_schema_history`. Están prohibidas las claves foráneas inter-esquema y los `JOIN` entre contextos de negocio.
4. **Infraestructura técnica replicada (`shared`)**: En lugar de una librería común Maven de dominio, el andamiaje técnico transversal (`GlobalExceptionHandler`, `ResponseCodeEnum`, `CorrelationFilter`, `SecureLogger`) se distribuye como código fuente dentro de la plantilla y evoluciona de forma autónoma en cada servicio.

---

## 2. Paso a Paso: Crear un Servicio desde la Plantilla

El proceso completo está optimizado para ejecutarse en menos de 15 minutos:

### Paso 1: Instanciar el Repositorio en GitHub
1. Navegar al repositorio plantilla [`leandroamariles/shoplab-service-template`](https://github.com/leandroamariles/shoplab-service-template).
2. Hacer clic en **Use this template** -> **Create a new repository**.
3. Asignar el nombre oficial según la matriz de `docs/mapa-servicios.md`:
   * Ejemplo para catálogo: `shoplab-catalog-service`
   * Ejemplo para pedidos: `shoplab-order-service`
   * Ejemplo para tickets: `shoplab-ticket-service`
4. Clonar el nuevo repositorio en local:
   ```bash
   git clone git@github.com:leandroamariles/shoplab-catalog-service.git
   cd shoplab-catalog-service
   ```

### Paso 2: Parametrizar Identificadores y Esquema
Reemplazar los placeholders estándar `template` por el nombre asignado al microservicio:

1. **`pom.xml`**:
   * `<artifactId>catalog-service</artifactId>`
   * `<name>catalog-service</name>`
   * También `release-please-config.json`, `README.md` y los dos workflows de `.github/workflows/`.
2. **Paquetes Java**:
   * Renombrar `src/main/java/com/shoplab/template` a `src/main/java/com/shoplab/catalog`.
   * Renombrar `TemplateServiceApplication.java` a `CatalogServiceApplication.java`.
3. **Catálogo de Códigos de Error (`ResponseCodeEnum.java`)**:
   * Ajustar el prefijo a la convención del servicio (`CATL`, `ORDR`, `TCKT`, `CHAT`, `RETR`):
     ```java
     INVALID_REQUEST("CATL_GE_00001", "Invalid request parameters", HttpStatus.BAD_REQUEST),
     RESOURCE_NOT_FOUND("CATL_GE_00002", "Requested resource not found", HttpStatus.NOT_FOUND),
     DATABASE_ERROR("CATL_DB_00001", "Persistence layer failure", HttpStatus.INTERNAL_SERVER_ERROR);
     ```
4. **Esquema de Base de Datos y Flyway (`application.yml`)**: persistencia JDBC (ADR-0011), un esquema propio.
   ```yaml
   spring:
     application:
       name: catalog-service
     flyway:
       schemas: catalog
       default-schema: catalog
       locations: classpath:db/migration/catalog
   ```
5. **Carpeta de Migraciones**:
   ```bash
   mv src/main/resources/db/migration/template src/main/resources/db/migration/catalog
   ```

---

## 3. Verificación Local Obligatoria

Antes de enviar el primer commit, ejecutar la validación completa en local:

```bash
./mvnw clean verify
```

Necesita Docker y credenciales para GitHub Packages: un servidor `github-shoplab-contracts` en `~/.m2/settings.xml`
con un token `read:packages`. Para la imagen, la credencial va como secreto de construcción:

```bash
GITHUB_TOKEN=... docker build --secret id=github_token,env=GITHUB_TOKEN .
```

Este comando verifica automáticamente:
1. **Compilación y Tests Unitarios**: Suite reactiva con JUnit 5, Mockito y StepVerifier.
2. **Integración con Testcontainers**: Ejecución de migraciones Flyway contra PostgreSQL 16 real en contenedor desechable.
3. **Puerta de Cobertura JaCoCo (≥80 %)**: Verifica que la cobertura de líneas alcance o supere el 80 %.
4. **Pruebas de Arquitectura Hexagonal con ArchUnit**:
   * Comprueba que la capa `domain` no importe dependencias de Spring, Reactor ni JPA.
   * Comprueba que no existan dependencias hacia `com.shoplab.helpdesk..` (aislamiento del monolito).
   * Comprueba que los controladores deleguen en puertos y no construyan `ResponseEntity` de error directo.

---

## 4. Configuración de CI/CD con `shoplab-ci`

El repositorio generado trae tres workflows; solo hay que cambiar el nombre del servicio:

| Archivo | Qué hace |
|---|---|
| `ci.yml` | `java-service.yml@v1` en cada PR; en `main` publica y firma la imagen |
| `release-please.yml` | abre el PR de release y crea el tag `vX.Y.Z` |
| `release.yml` | con el tag, publica la imagen `:vX.Y.Z` y después la despliega |

La publicación llama a `leandroAmariles/shoplab-ci/.github/workflows/publish-image.yml@v1`. El repositorio plantilla
no publica ni crea releases (`is_template`). No hay autenticación hasta que exista el login (LAB-61).

### Puertas de Calidad Ejecutadas en CI:
* **Compilación y Tests**: Java 21 + Testcontainers.
* **Gate de Cobertura JaCoCo**: Falla si `< 80%`; Checkstyle y PMD en `verify`.
* **Análisis Estático Qodana**.
* **Escaneo de Seguridad Trivy**: Bloquea con vulnerabilidades `HIGH` o `CRITICAL` con corrección disponible.
* **Gate de Políticas**: Falla si detecta tags `:latest` o la cadena `CAMBIA-ESTE-OWNER`.

---

## 5. Publicación, Firma Cosign y Despliegue GitOps

### Publicación y Firma en GHCR
Cuando se publica una release mediante `release-please` (tag `vX.Y.Z`) o un merge en `main`:
1. La imagen se compila y etiqueta con `:sha-<commit>` y `:vX.Y.Z`.
2. Se generan automáticamente atestaciones SLSA (provenance mode max) y el documento SBOM.
3. Cosign firma el digest inmutable de la imagen en modo **keyless** utilizando la identidad OIDC de GitHub Actions:
   ```bash
   cosign sign --yes "ghcr.io/leandroamariles/catalog-service@sha256:..."
   ```

### Despliegue Automático hacia `shoplab-infra`
1. **Variable y secretos del repositorio**:
   * `DEPLOY_APP_ID` (variable): ID de la GitHub App de despliegue. La App solo tiene que estar instalada en
     `shoplab-infra`.
   * `DEPLOY_APP_PRIVATE_KEY` (secreto): llave privada PEM de la App.
   * `RELEASE_PLEASE_TOKEN` (secreto): token personal limitado al repositorio (Contents y Pull requests). Sin él los
     tags no disparan `release.yml`.
   * Tras la primera publicación, hacer **público** el paquete de la imagen (no es reversible).
2. **Job de despliegue (`deploy.yml`)**:
   Al crearse el tag `vX.Y.Z`, el workflow solicita un token efímero de alcance mínimo (exclusivo para `shoplab-infra:contents:write`), clona `shoplab-infra`, ejecuta:
   ```bash
   kustomize edit set image ghcr.io/leandroamariles/catalog-service=ghcr.io/leandroamariles/catalog-service:vX.Y.Z
   ```
   Valida el render con `policy-check.sh` y genera el commit firmado por `shoplab-deploy[bot]` en la rama `main` de `shoplab-infra`.
3. **Sincronización GitOps**:
   Argo CD detecta el commit en `shoplab-infra` y sincroniza el nuevo pod en el clúster `dev`.

---

## 6. Registro Inicial en `shoplab-infra`

Antes del primer despliegue el servicio debe estar declarado en `shoplab-infra` (LAB-104):

1. Copiar `apps/_template/` a `apps/catalog-service/base/` y crear `apps/catalog-service/overlays/dev/`.
2. En el `kustomization.yaml` del overlay, declarar la imagen **con su nombre completo** (el job de despliegue lo
   exige):
   ```yaml
   images:
     - name: ghcr.io/leandroamariles/catalog-service
       newName: ghcr.io/leandroamariles/catalog-service
       newTag: v0.1.0
   ```
3. Los secretos salen de `aws-secrets-manager` (`external-secrets.io/v1`), en `/shoplab/dev/...`.
4. Fusionar el PR. El ApplicationSet crea la Application `catalog-service-dev` y cada release actualiza su versión.
