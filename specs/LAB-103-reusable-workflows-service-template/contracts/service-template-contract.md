# Contrato: Estructura y Componentes de `shoplab-service-template`

**Repositorio**: `leandroamariles/shoplab-service-template`
**Tipo**: GitHub Template Repository (`is_template: true`)

---

## 1. Estructura de Directorios del Repositorio Plantilla

```text
shoplab-service-template/
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                 # Invoca shoplab-ci/java-service.yml@v1
│   │   ├── pr-title.yml           # Verificación Conventional Commits
│   │   ├── release-please.yml     # Versionado automático de servicio
│   │   └── deploy.yml             # Invoca shoplab-ci/deploy.yml@v1 en releases
│   ├── dependabot.yml             # Mantenimiento de Maven, Actions y GHCR
│   ├── release-please-config.json
│   └── .release-please-manifest.json
├── src/
│   ├── main/
│   │   ├── java/com/shoplab/template/
│   │   │   ├── TemplateServiceApplication.java
│   │   │   ├── shared/            # Paquete técnico transversal
│   │   │   │   ├── exception/
│   │   │   │   │   ├── BusinessException.java
│   │   │   │   │   ├── TechnicalException.java
│   │   │   │   │   ├── ResponseCodeEnum.java
│   │   │   │   │   └── GlobalExceptionHandler.java
│   │   │   │   ├── filter/
│   │   │   │   │   └── CorrelationFilter.java
│   │   │   │   ├── model/
│   │   │   │   │   ├── ResponseDto.java
│   │   │   │   │   └── ErrorDto.java
│   │   │   │   └── logging/
│   │   │   │       └── SecureLogger.java
│   │   │   ├── domain/            # Capa de Dominio puro (cero Spring/infra)
│   │   │   │   ├── model/
│   │   │   │   └── port/
│   │   │   │       ├── in/
│   │   │   │       └── out/
│   │   │   ├── application/       # Casos de uso / orquestación
│   │   │   │   └── usecase/
│   │   │   └── infrastructure/    # Adaptadores de entrada y salida
│   │   │       ├── in/
│   │   │       │   └── rest/
│   │   │       │       ├── controller/
│   │   │       │       ├── dto/
│   │   │       │       ├── mapper/
│   │   │       │       └── config/
│   │   │       │           └── SecurityConfig.java # Resource Server JWT
│   │   │       └── out/
│   │   │           └── persistence/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── logback-spring.xml
│   │       └── db/migration/template/
│   │           └── V1__initial_schema.sql
│   └── test/
│       ├── java/com/shoplab/template/
│       │   ├── architecture/
│       │   │   └── HexagonalArchitectureTest.java # Reglas ArchUnit
│       │   ├── shared/
│       │   │   └── GlobalExceptionHandlerTest.java
│       │   └── persistence/
│       │       └── FlywayMigrationIntegrationTest.java # Testcontainers
│       └── resources/
│           └── application-test.yml
├── pom.xml                        # Maven Parent Spring Boot 4.x
├── Dockerfile                     # Multi-stage seguro distroless/jre
└── README.md                      # Instrucciones de parametrización inicial
```

---

## 2. Contrato Maven (`pom.xml`)

* **Parent**:
  ```xml
  <parent>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-parent</artifactId>
      <version>4.0.0-M1</version> <!-- O versión GA vigente en el proyecto -->
      <relativePath/>
  </parent>
  ```
* **Propiedades**:
  * `java.version`: `21`
  * `contracts.version`: `1.0.0` (fijado sin comodines, dependiente de `shoplab-contracts`)
  * `archunit.version`: `1.3.0`
* **Dependencias Clave**:
  * `spring-boot-starter-webflux`: API reactiva no bloqueante.
  * `spring-boot-starter-actuator`: Health check y Prometheus.
  * `spring-boot-starter-oauth2-resource-server`: Validación de JWT Keycloak.
  * `org.flywaydb:flyway-database-postgresql`: Migrador mono-esquema.
  * `org.postgresql:r2dbc-postgresql` / `org.postgresql:postgresql`: Driver de base de datos.
  * `com.shoplab:shoplab-contracts`: DTOs de red generados.
  * `org.testcontainers:postgresql`: Pruebas de integración con base de datos real.
  * `com.tngtech.archunit:archunit-junit5`: Validación de límites arquitectónicos.
* **Plugins de Calidad**:
  * `jacoco-maven-plugin`: Configurado para generar `jacoco.csv` con exclusiones estándar de DTOs, configs y mappers.

---

## 3. Contrato de Pruebas de Arquitectura (ArchUnit)

La clase `HexagonalArchitectureTest` en la plantilla hace cumplir:
1. **Pureza del Dominio**:
   ```java
   noClasses().that().resideInAPackage("..domain..")
       .should().dependOnClassesThat().resideInAnyPackage(
           "org.springframework..",
           "reactor.core..",
           "jakarta.persistence..",
           "..infrastructure.."
       ).check(importedClasses);
   ```
2. **Aislamiento de Monolito**:
   ```java
   noClasses().should().dependOnClassesThat()
       .resideInAPackage("com.shoplab.helpdesk..")
       .because("A standalone service must never import classes from the monolith")
       .check(importedClasses);
   ```
3. **Manejo Centralizado de Errores**:
   ```java
   noClasses().that().resideInAPackage("..application..")
       .or().resideInAPackage("..infrastructure.out..")
       .should().callMethod(ResponseEntity.class, "status", int.class)
       .because("Errors must be thrown as BusinessException or TechnicalException")
       .check(importedClasses);
   ```

---

## 4. Contrato de Migraciones Flyway

* Carpeta: `src/main/resources/db/migration/${SERVICE_NAME}/`
* Esquema: configurado en `application.yml` mediante:
  ```yaml
  spring:
    flyway:
      enabled: true
      schemas: ${SERVICE_NAME:template}
      default-schema: ${SERVICE_NAME:template}
      locations: classpath:db/migration/${SERVICE_NAME:template}
      table: flyway_schema_history
  ```
* Se verifica mediante `FlywayMigrationIntegrationTest` utilizando Testcontainers para levantar PostgreSQL 16 y asegurar que las migraciones corren sin permisos de superusuario sobre otros esquemas.
