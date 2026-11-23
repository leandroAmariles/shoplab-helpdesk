# Research & Decision Record: Repo shoplab-contracts publicado como paquetes versionados

**Feature**: `LAB-108-contracts-versioned-packages`  
**Date**: 2026-11-23  
**Status**: Completed  

---

## 1. Contexto y Objetivos Técnicos

El objetivo de LAB-108 (adelantado de F5 al bloque de extracción temprana según ADR-0015 y LAB-128) es establecer el repositorio soporte `shoplab-contracts` para gobernar las interfaces entre servicios de ShopLab Helpdesk sin acoplamiento de código ni carpetas compartidas locales.

Este documento de investigación analiza y resuelve las decisiones técnicas para:
1. La estructura física del repositorio `shoplab-contracts` (`openapi/`, `avro/`, `asyncapi/`).
2. La validación automática de compatibilidad en CI mediante `oasdiff` (OpenAPI) y Schema Registry `BACKWARD` (Avro).
3. El ciclo de releases automatizado con `release-please` y versionado semántico estricto.
4. La generación y publicación de paquetes para los tres ecosistemas del proyecto:
   - Artefacto Maven (Java DTOs y modelos) en GitHub Packages.
   - Paquete npm (cliente Angular tipado) en GitHub Packages.
   - Paquete Python instalable por tag git con `uv` (`@vX.Y.Z`).
5. La integración con Dependabot en los servicios consumidores.
6. La guía operativa de cambios de contrato y orden de despliegue productor/consumidor.

---

## 2. Decisiones de Arquitectura e Implementación

### Decision 1: Estructura del Repositorio `shoplab-contracts` y Contrato Semilla

- **Alternativas consideradas**:
  - *Opción A*: Dejar `openapi/` completamente vacío hasta `LAB-106`.
  - *Opción B*: Crear un contrato OpenAPI semilla de modelos comunes (`openapi/common/v1/health.yaml` y `openapi/common/v1/errors.yaml`) que defina esquemas reutilizables (`ErrorResponse`, `HealthStatus`).
- **Decisión**: **Opción B**.
- **Justificación**: El criterio de aceptación y la prueba independiente de LAB-108 exigen probar extremo a extremo la validación de CI, `oasdiff`, la generación de código y la publicación real de la versión `1.0.0` para Maven, npm y Python sin esperar a que `LAB-106` migre las especificaciones completas de negocio (`chat`, `retrieval`, etc.). Un contrato semilla de utilidades comunes proporciona un caso de uso real que permanece válido y reutilizable en el futuro.
- **Estructura raíz acordada**:
  ```text
  shoplab-contracts/
  ├── .github/
  │   ├── workflows/
  │   │   ├── ci.yml                 # Validaciones, oasdiff, avro test, build dry-run
  │   │   ├── pr-title.yml           # Verificación Conventional Commits
  │   │   ├── release-please.yml     # Orquestación de releases automáticas
  │   │   └── publish.yml            # Publicación de paquetes Maven, npm y tag Python
  │   └── dependabot.yml             # Mantenimiento de acciones y generadores
  ├── openapi/
  │   └── common/
  │       └── v1/
  │           └── common-api.yaml    # Contrato semilla (Ping, Health, ErrorDto)
  ├── avro/
  │   └── .gitkeep                   # Vacío de esquemas reales hasta LAB-50
  ├── asyncapi/
  │   └── .gitkeep                   # Vacío de especificaciones hasta LAB-50
  ├── generators/                    # Scripts / configs de generación
  │   ├── java/pom.xml               # Proyecto Maven para compilar y publicar DTOs Java
  │   ├── npm/package.json           # Proyecto npm para generar cliente Angular
  │   └── python/pyproject.toml      # Configuración del paquete Python
  ├── release-please-config.json
  ├── .release-please-manifest.json
  ├── README.md
  └── LICENSE
  ```

---

### Decision 2: Puerta de Compatibilidad OpenAPI con `oasdiff`

- **Herramienta elegida**: `tufin/oasdiff` (ejecutable CLI nativo y action `tufin/oasdiff-action`).
- **Comportamiento en Pull Requests**:
  1. **Determinación de línea base**:
     - Se compara la rama del PR contra el tag de la última release publicada `vX.Y.Z` (o contra `origin/main` si aún no existe tag publicado).
     - Si es la primera versión y no existe especificación previa, se ejecuta validación sintáctica (`oasdiff changelog` o linter OpenAPI) y se aprueba como línea base inicial.
  2. **Detección de incompatibilidades**:
     - Ejecuta: `oasdiff breaking <baseline-spec> <current-spec> --format text`.
  3. **Regla de decisión del Gate**:
     - Si `oasdiff breaking` reporta **0 breaking changes**: El check pasa en verde.
     - Si `oasdiff breaking` reporta **1 o más breaking changes**:
       - Inspecciona el título del PR (o el mensaje del commit) buscando el indicador de cambio mayor (`!` antes de `:` en el tipo/scope o `BREAKING CHANGE:` en el cuerpo).
       - Si **NO** contiene indicador de cambio mayor: La CI falla inmediatamente con mensaje explicativo indicando los cambios incompatibles y recordando que todo cambio no retrocompatible exige versión mayor.
       - Si **SÍ** contiene indicador de cambio mayor: El check emite una advertencia informativa ("Breaking change detected and declared as MAJOR release") y pasa en verde, permitiendo a `release-please` calcular la subida a `X+1.0.0`.

---

### Decision 3: Puerta de Compatibilidad Avro contra Schema Registry (`BACKWARD`)

- **Estrategia para `avro/`**:
  - Mientras `avro/` no contenga archivos `.avsc` (estado actual hasta `LAB-50`):
    - El script/job de CI comprueba si existen archivos con extensión `.avsc` en `avro/`.
    - Al encontrar 0 archivos, registra en el log: `"No Avro schemas found in avro/; skipping Schema Registry compatibility check"` y termina exitoso (`exit 0`).
  - Cuando se añadan esquemas en `LAB-50`:
    - En CI se levanta un contenedor efímero de Confluent Schema Registry (o se ejecuta la herramienta CLI / plugin de compatibilidad de Confluent).
    - Se valida el esquema modificado contra el último esquema registrado bajo la regla `BACKWARD` (un consumidor que lee con el esquema nuevo puede leer datos serializados con el esquema anterior).
    - Si la compatibilidad `BACKWARD` falla y el PR no declara cambio mayor, el job falla.
- **Prueba en LAB-108**:
  - Se incluye una prueba unitaria / fixture en el propio flujo de CI que valida que el detector de esquemas omite limpiamente cuando está vacío, y una prueba con un esquema fixture temporal para certificar que el validador `BACKWARD` rechaza incompatibilidades y acepta esquemas retrocompatibles.

---

### Decision 4: Estrategia de Versionado Semántico con `release-please`

- **Configuración de `release-please`**:
  - El criterio de aceptación exige: *"un cambio incompatible exige subir la versión mayor"* y *"release-please con versionado semántico"*.
  - En `release-please-config.json`:
    ```json
    {
      "$schema": "https://raw.githubusercontent.com/googleapis/release-please/main/schemas/config.json",
      "include-component-in-tag": false,
      "bump-minor-pre-major": false,
      "bump-patch-for-minor-pre-major": false,
      "pull-request-title-pattern": "chore${scope}: release ${version}",
      "packages": {
        ".": {
          "release-type": "simple",
          "changelog-path": "CHANGELOG.md",
          "initial-version": "1.0.0"
        }
      }
    }
    ```
  - Al configurar `"bump-minor-pre-major": false`, cualquier commit con `!` o `BREAKING CHANGE:` incrementará la versión mayor (`2.0.0`), cumpliendo estrictamente con la regla sin degradar a `0.x` minor.
  - La versión inicial arranca en `1.0.0` tras fusionar el primer PR de release de prueba.

---

### Decision 5: Generación y Publicación de Paquetes en Tres Ecosistemas

#### A. Paquete Java (Maven) en GitHub Packages
- **Coordenadas**: `com.shoplab:shoplab-contracts:${version}`.
- **Herramienta**: `openapi-generator-maven-plugin` (versión 7.x) configurado con generador `spring` o `java` (modo library: DTOs inmutables con Jackson / Java records).
- **Publicación**:
  - `distributionManagement` apuntando a `https://maven.pkg.github.com/leandroAmariles/shoplab-contracts`.
  - Autenticación en CI usando `GITHUB_TOKEN` con permisos `packages: write`.
  - En los consumidores: se resuelve mediante `<repository>` en `pom.xml` o servidor en `.m2/settings.xml`.

#### B. Paquete npm (Angular) en GitHub Packages
- **Nombre del paquete**: `@leandroamariles/shoplab-contracts`.
- **Herramienta**: `@openapitools/openapi-generator-cli` generando `typescript-angular`.
- **Empaquetado y publicación**:
  - Compilación a JavaScript + definiciones `.d.ts` mediante `ng-packagr` o TypeScript compiler `tsc`.
  - Publicación a `https://npm.pkg.github.com` con scope `@leandroamariles`.
  - En los consumidores: archivo `.npmrc` con `@leandroamariles:registry=https://npm.pkg.github.com/` y token con permiso `read:packages`.

#### C. Paquete Python (`uv` vía git tag `@vX.Y.Z`)
- **Estructura del paquete**:
  - Ubicado en la raíz o en `python/` con `pyproject.toml` usando `hatchling`.
  - Código generado: Modelos Pydantic v2 a partir de OpenAPI (usando `datamodel-code-generator`).
- **Instalación con `uv`**:
  - El paquete no se sube a PyPI (evita nombres públicos innecesarios y costos de registry privado).
  - Un servicio consumidor en Python instala el paquete ejecutando:
    ```bash
    uv add "shoplab-contracts @ git+https://github.com/leandroAmariles/shoplab-contracts.git@vX.Y.Z"
    ```
    O en `pyproject.toml`:
    ```toml
    [project]
    dependencies = [
      "shoplab-contracts @ git+https://github.com/leandroAmariles/shoplab-contracts.git@v1.0.0"
    ]
    ```
  - `uv` clona y compila el paquete directamente desde el tag inmutable del commit release.

---

### Decision 6: Integración con Dependabot y Fijación de Versiones

- **En `shoplab-contracts`**:
  - Dependabot monitoriza actualizaciones de GitHub Actions, dependencias Maven del generador y dependencias npm.
- **En los servicios consumidores (`shoplab-helpdesk` y futuros microservicios de LAB-103)**:
  - Se fijan versiones exactas (ej. `1.0.0`), prohibiendo rangos (`^`, `~`, `LATEST`, `SNAPSHOT`).
  - Configuración en `.github/dependabot.yml` de los consumidores:
    ```yaml
    registries:
      github-maven:
        type: maven-repository
        url: https://maven.pkg.github.com/leandroAmariles
        token: ${{ secrets.DEPENDABOT_PACKAGES_TOKEN }}
      github-npm:
        type: npm-registry
        url: https://npm.pkg.github.com
        token: ${{ secrets.DEPENDABOT_PACKAGES_TOKEN }}
    ```
  - *Límite documentado*: Dependabot soporta nativamente registries Maven y npm. Para dependencias Python fijadas por git tag en `pyproject.toml`, Dependabot no sondea automáticamente nuevos tags git si no hay un índice PEP 503; se documenta el flujo de actualización para servicios Python mediante `uv lock --upgrade-package shoplab-contracts` o un workflow disparado por webhook/repository_dispatch.

---

### Decision 7: Guía Operativa y Orden de Despliegue (Expand-and-Contract)

- Se documenta en `docs/contracts-publishing-and-deployment-order.md` el ciclo de vida de contratos:
  1. **Cambio retrocompatible (aditivo, minor/patch)**:
     - Paso 1: PR en `shoplab-contracts` con `feat:` o `fix:`. `oasdiff` valida compatibilidad.
     - Paso 2: Merge en `main` -> `release-please` publica `v1.1.0` en Maven, npm y tag git.
     - Paso 3: Desplegar el **Productor** primero (implementa y expone el nuevo campo opcional).
     - Paso 4: Actualizar y desplegar los **Consumidores** (empiezan a leer o enviar el nuevo campo).
  2. **Cambio incompatible (breaking change, major)**:
     - Requiere estrategia **Expand-and-Contract**:
       - *Fase 1 (Expand)*: Añadir el nuevo formato en paralelo al antiguo (ambos soportados en v1.x). Desplegar productor.
       - *Fase 2 (Migrate)*: Migrar todos los consumidores al nuevo formato.
       - *Fase 3 (Contract / Breaking)*: Publicar `v2.0.0` retirando el formato antiguo con `feat!: [LAB-xxx] ...`. Desplegar productor v2 una vez verificado que ningún consumidor usa la v1.

---

## 3. Resumen de Respuestas Técnicas

| Área | Decisión | Justificación |
|---|---|---|
| **Estructura base** | `openapi/`, `avro/`, `asyncapi/` | Cumple el criterio de aceptación; `avro/` y `asyncapi/` limpios hasta LAB-50 |
| **Contrato inicial** | `openapi/common/v1/common-api.yaml` | Permite probar generación y publicación real de v1.0.0 antes de LAB-106 |
| **OpenAPI diff** | `oasdiff breaking` | Estándar de la industria para detección de roturas en especificaciones OpenAPI 3.x |
| **Avro diff** | Script en CI con Schema Registry `BACKWARD` | Omitido limpiamente si no hay `.avsc`; listo para cuando LAB-50 agregue esquemas |
| **Release tool** | `release-please` (v5) | Versionado semántico automático basado en Conventional Commits |
| **Maven publish** | GitHub Packages Maven | Distribución autenticada estándar para el backend Java |
| **npm publish** | GitHub Packages npm | Distribución para Angular bajo `@leandroamariles` |
| **Python package** | `uv` vía git tag `@vX.Y.Z` | No requiere registro PyPI privado; determinista e inmutable |
| **Dependabot** | `registries` autenticados en `dependabot.yml` | Notificación automática de nuevas versiones para Maven y npm |
