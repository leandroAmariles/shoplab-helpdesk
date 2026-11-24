# Research: Workflows reutilizables y plantilla de servicio (shoplab-ci y shoplab-service-template)

**Feature**: `LAB-103-reusable-workflows-service-template`
**Date**: 2026-11-24
**Status**: Completed

---

## 1. Technical Context & Objectives

En consonancia con [ADR-0015](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0015-limites-de-contexto-y-repositorios.md) y [ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md) (extracción temprana de servicios tras F2), el ecosistema de **ShopLab Helpdesk** evoluciona de un monolito modular a un conjunto de servicios soberanos en repositorios individuales (`shoplab-chat-service`, `shoplab-retrieval-service`, `shoplab-eval-service`, `shoplab-catalog-service`, etc.).

Para evitar la duplicación masiva de pipelines de CI/CD y asegurar la homogeneidad arquitectónica y de seguridad, la historia **LAB-103** establece:
1. El repositorio central de soporte **`shoplab-ci`** con workflows reutilizables invocables vía `workflow_call` (`java-service`, `python-service`, `angular-app`, `deploy`).
2. El repositorio plantilla oficial **`shoplab-service-template`** (GitHub Template Repository) como punto de partida estandarizado para cualquier servicio nuevo o extraído.

---

## 2. Decisiones de Investigación (Resolución de Incógnitas)

### D-01: Mecanismo de reusabilidad y versionado en `shoplab-ci`
* **Pregunta**: ¿Cómo deben versionarse y consumirse los workflows reutilizables de GitHub Actions?
* **Opciones evaluadas**:
  1. *Ramas fijas (`@main`)*: Muy frágil. Un cambio experimental en el flujo puede romper inmediatamente la CI de todos los microservicios.
  2. *Tags flotantes / semánticos (`@v1`, `@v1.2.0`)*: Estándar oficial de la industria. Permite a los repositorios anclarse a `@v1` y recibir correcciones de seguridad de forma transparente, o anclarse a una versión fija `@v1.0.0` si requieren congelación estricta.
* **Decisión**: Se adopta el versionado por tags (`@v1`). `shoplab-ci` utilizará `release-please` para gestionar su propio versionado semántico y publicar tags mayores (`v1`) que apunten al último commit de la serie `1.x.y`.

### D-02: Firma criptográfica y procedencia de imágenes OCI (Cosign + SBOM)
* **Pregunta**: ¿Cómo firmar las imágenes generadas en GHCR sin gestionar claves privadas estáticas o rotación manual de secretos?
* **Opciones evaluadas**:
  1. *Cosign con par de claves privada/pública en GitHub Secrets*: Requiere gestionar almacenamiento, rotación y permisos de claves. Riesgo de exfiltración.
  2. *Cosign Keyless con OpenID Connect (OIDC)*: Aprovecha el proveedor de identidad nativo de GitHub Actions (`https://token.actions.githubusercontent.com`) y el libro de registro público/transparencia de Sigstore (Fulcio / Rekor). No requiere gestión de secretos persistentes.
* **Decisión**: Adoptar **Cosign Keyless** con `id-token: write`. Se firma el digest inmutable (`sha256:...`) de la imagen. La verificación se realiza validando la identidad OIDC del workflow llamador (`--certificate-identity-regexp` y `--certificate-oidc-issuer`).
* **SBOM**: Se generará durante el build con `docker/build-push-action` (`sbom: true` y `provenance: mode=max`), adjuntando las atestaciones estándar a la imagen en GHCR.

### D-03: Despliegue GitOps reutilizable sobre `shoplab-infra`
* **Pregunta**: ¿Cómo debe un servicio actualizar su versión en `shoplab-infra` respetando el principio de mínimo privilegio?
* **Opciones evaluadas**:
  1. *Personal Access Token (PAT) con permisos de organización*: Anti-patrón grave; expone acceso a todos los repositorios de la cuenta.
  2. *Deploy Keys por repositorio*: Solo lectura o escritura completa, difícil de compartir de manera dinámica entre 8+ microservicios.
  3. *GitHub App dedicada (`shoplab-deploy[bot]`)*: Estándar recomendado. Emite un token efímero (válido por 1 hora) con permisos restringidos exclusivamente a `shoplab-infra` con alcance `contents: write`.
* **Decisión**: Reutilizar el diseño probado de `LAB-81` (`.github/scripts/deploy-dev.sh`). El job de deploy en `shoplab-ci` invocará la acción `actions/create-github-app-token` utilizando las variables organizacionales `DEPLOY_APP_ID` y `DEPLOY_APP_PRIVATE_KEY` para clonar `shoplab-infra`, ejecutar `kustomize edit set image`, verificar que no existan tags `:latest`, correr `policy-check.sh` y empujar el commit firmado por `shoplab-deploy[bot]`.

### D-04: Gestión de dependencias y paquetes compartidos (`shoplab-contracts`)
* **Pregunta**: ¿Cómo evitar el acoplamiento de dominio al crear nuevos microservicios?
* **Decisión**: Ratificar [ADR-0010](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0010-modulo-shared-errores.md), [ADR-0015](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0015-limites-de-contexto-y-repositorios.md) y [ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md):
  - **Prohibido terminantemente** crear librerías comunes de dominio (entidades, repositorios, reglas de negocio) que actúen como monolito distribuido.
  - La única dependencia externa de comunicación entre servicios es el paquete generado de **`shoplab-contracts`** (LAB-108), que contiene exclusivamente DTOs de red y clientes OpenAPI/MCP.
  - Los componentes transversales de infraestructura no de negocio (excepciones, correlación, logging) se distribuyen como código fuente base dentro del paquete `shared` de la plantilla `shoplab-service-template`.

### D-05: Estructura del paquete `shared` en la plantilla
* **Componentes obligatorios**:
  1. `GlobalExceptionHandler`: Manejo centralizado de excepciones con mapeo estricto a `ResponseDto<T>` y lista de `ErrorDto`.
  2. `ResponseCodeEnum`: Enum tipado con convención `<SERVICIO>_<SUBSISTEMA>_<NNNNN>` (p. ej., `_GE_00001`, `_AUTH_00001`, etc.) parametrizable en tiempo de compilación/instanciación.
  3. `CorrelationFilter` (`WebFilter` reactivo): Inyección y propagación de `X-Request-Id` y `traceparent` en Reactor Context, MDC y headers HTTP.
  4. `SecureLogger`: Wrapper seguro que previene la fuga de datos sensibles y formatea logs estructurados con MDC correlacionado.

### D-06: Aislamiento de base de datos con Flyway mono-esquema
* **Pregunta**: ¿Cómo debe configurarse Flyway en un microservicio autónomo?
* **Decisión**: Cada servicio es dueño exclusivo de un esquema PostgreSQL (`spring.flyway.schemas=<servicio>`), con su historial en `<servicio>.flyway_schema_history` y migraciones en `db/migration/<servicio>`. La plantilla vendrá configurada con un esquema plantilla parametrizable (placeholder `${SERVICE_NAME}`) y un test de arranque que valida la ejecución de Flyway contra PostgreSQL con Testcontainers.

### D-07: Verificación de arquitectura hexagonal con ArchUnit
* **Pregunta**: ¿Qué reglas ejecutables deben garantizar que el código generado no degrade la arquitectura?
* **Decisión**: Se incorporan reglas de ArchUnit ejecutables en `mvn test`:
  1. `domain` no debe depender de `application`, `infrastructure`, ni librerías de infraestructura (Spring Web, JPA, Reactor, Netty).
  2. `application` solo depende de `domain` y de puertos interfaces.
  3. `infrastructure` implementa puertos y se aísla de la lógica de negocio.
  4. Ningún controlador ni use case puede retornar `ResponseEntity` de error directo (obligatorio delegar en `GlobalExceptionHandler`).
  5. Ninguna clase de negocio puede importar paquetes del monolito `shoplab-helpdesk`.

### D-08: Seguridad y Resource Server JWT (LAB-61)
* **Pregunta**: ¿Cómo preparar la plantilla para la autenticación sin acoplar la implementación temprana?
* **Decisión**: Se configura `spring-boot-starter-oauth2-resource-server` reactivo en `infrastructure/in/rest/config/SecurityConfig.java`. Por defecto en perfil local admite modo anónimo o stub para desarrollo inicial, pero incluye la configuración reactiva para decodificar JWTs firmados por Keycloak, extrayendo roles y claims (`sub`, `roles`) hacia el contexto de seguridad reactivo de Spring.

---

## 3. Matriz de Tecnologías y Versiones

| Componente / Herramienta | Versión seleccionada | Propósito |
|---|---|---|
| **Java JDK** | 21 (Temurin / Eclipse) | Lenguaje base backend |
| **Spring Boot** | 4.x (WebFlux) | Framework reactivo de microservicios |
| **Spring AI** | 2.0.x | Integración con LLM/embeddings (cuando aplique) |
| **PostgreSQL / pgvector** | 16-alpine / 0.7.0+ | Base de datos relacional y vectorial |
| **Flyway** | 10.x / compatible SB 4 | Migraciones versionadas mono-esquema |
| **ArchUnit** | 1.3.x | Pruebas de arquitectura ejecutables en CI |
| **Testcontainers** | 1.20.x | Pruebas de integración con PostgreSQL real |
| **JaCoCo** | 0.8.12+ | Cobertura de código (umbral mínimo 80 %) |
| **Qodana** | v2024.x | Análisis estático y calidad de código |
| **Trivy** | v0.36+ | Escaneo de vulnerabilidades en fs y contenedor |
| **Cosign** | v2.4+ / v4.x | Firma criptográfica de contenedores keyless |
| **Kustomize** | v5.4+ | Renderizado declarativo y gestión de overlays GitOps |
| **Release-please** | v4 / v5 | Automatización de Semantic Versioning |
| **Dependabot** | Nativo GitHub | Actualización continua de dependencias |
