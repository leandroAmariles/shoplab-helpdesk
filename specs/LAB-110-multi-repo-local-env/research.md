# Research: LAB-110 Entorno local con varios repos

**Feature**: `LAB-110-multi-repo-local-env`  
**Date**: 2026-11-25  
**Status**: Completed  

---

## 1. Technical Context & Objectives

A raíz de la decisión arquitectónica de extracción temprana ([ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md)) y la posterior separación física ejecutada en [LAB-109](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-109-separate-ingest-eval-frontend/spec.md), el código del ecosistema ShopLab Helpdesk reside en múltiples repositorios autónomos en GitHub (`shoplab-frontend`, `shoplab-ingest`, `shoplab-eval`, `shoplab-contracts`, `shoplab-ci`, `shoplab-infra`).

Esta dispersión introduce el desafío de la experiencia de desarrollo (DevEx):
1. ¿Cómo puede un desarrollador levantar y probar el sistema completo sin tener que clonar y compilar cada uno de los repositorios?
2. ¿Cómo puede un desarrollador que modifica un único servicio (p. ej. `shoplab-frontend` o `shoplab-ingest`) probar sus cambios locales integrados con el resto del ecosistema?
3. ¿Cómo facilitar la clonación sincronizada de todos los repositorios en carpetas adyacentes?
4. ¿Cómo consolidar a `shoplab-helpdesk` como el repositorio paraguas (*umbrella repo*) sin duplicar código de negocio?

---

## 2. Decisiones de Investigación (Resolución Técnica)

### D-01: Composición del entorno completo mediante imágenes GHCR y `.env`
* **Pregunta**: ¿Qué servicios deben formar parte de `infra/docker-compose.yml` y cómo se versionan sus imágenes?
* **Decisión**:
  * `infra/docker-compose.yml` define la orquestación integral de la plataforma:
    * **Infraestructura**: `postgres` (con `pgvector`), `ollama`, pasarela `litellm` (con su postgres y script bootstrap).
    * **Servicios de Aplicación**:
      * `backend`: Expuesto en el puerto `8080`, imagen `${BACKEND_IMAGE:-ghcr.io/leandroamariles/shoplab-backend:v0.1.0}`.
      * `frontend`: Expuesto en el puerto `4200` (o `80` mediante Nginx), imagen `${FRONTEND_IMAGE:-ghcr.io/leandroamariles/shoplab-frontend:v0.1.0}`.
      * `ingest`: Expuesto en el puerto `8000`, imagen `${INGEST_IMAGE:-ghcr.io/leandroamariles/shoplab-ingest:v0.1.0}`.
    * **Perfiles opcionales**: `observability` (Prometheus en 9090, Grafana en 3000), `llmops` (Langfuse en 3001).
  * **Versionado**:
    * Se actualiza `infra/.env.example` y `infra/.env` para incluir las variables `BACKEND_IMAGE`, `FRONTEND_IMAGE` e `INGEST_IMAGE`.
    * Prohibición absoluta de `:latest`: las variables se fijan a tags inmutables semánticos generados por los pipelines de CI de cada repositorio (`:vX.Y.Z`).

### D-02: Mecanismo de override de Compose para desarrollo local en repositorios hermanos
* **Pregunta**: ¿Cómo permitir que un desarrollador construya un servicio desde su código fuente local clonado al lado en lugar de usar la imagen de GHCR?
* **Decisión**:
  * Utilizar el estándar nativo de Docker Compose: `docker-compose.override.yml`.
  * Cuando `docker-compose.override.yml` está presente en la carpeta `infra/` (o se pasa explícitamente con `-f`), Docker Compose fusiona sus directivas con `infra/docker-compose.yml`.
  * **Rutas relativas**: Dado que `docker-compose.yml` reside en `infra/`, la carpeta raíz del monorepo está en `../`, y las carpetas hermanas de los demás repositorios se ubican en `../../<nombre-repo>`:
    ```yaml
    services:
      frontend:
        build:
          context: ../../shoplab-frontend
        image: shoplab-frontend:local

      ingest:
        build:
          context: ../../shoplab-ingest
        image: shoplab-ingest:local

      backend:
        build:
          context: ../../shoplab-helpdesk/backend
        image: shoplab-backend:local
    ```
  * **Plantilla documentada**: Se crea `infra/docker-compose.override.yml.example` con todos los bloques comentados. El desarrollador solo debe copiarlo a `infra/docker-compose.override.yml` y descomentar el servicio en el que está trabajando.
  * **Aislamiento en git**: `infra/docker-compose.override.yml` se añade a `.gitignore` para no filtrar personalizaciones locales.

### D-03: Automatización de clonación `make clone-all` y script PowerShell para Windows
* **Pregunta**: ¿Cómo orquestar la clonación de todos los repositorios hermanos en carpetas adyacentes de forma idempotente y multiplataforma?
* **Decisión**:
  * **`Makefile` en la raíz de `shoplab-helpdesk`**:
    * Target `clone-all`: Define la lista de repositorios oficiales:
      * `shoplab-frontend`
      * `shoplab-ingest`
      * `shoplab-eval`
      * `shoplab-contracts`
      * `shoplab-ci`
      * `shoplab-infra`
    * Soporta la variable `GIT_BASE_URL ?= https://github.com/leandroAmariles` (permite cambiar a SSH `git@github.com:leandroAmariles`).
    * Para cada repositorio, comprueba si `../<repo>` ya existe (`test -d ../$$repo`). Si existe, emite `[SKIP] <repo> already cloned`, evitando sobrescrituras y fallos.
  * **`scripts/clone-all.ps1` para Windows PowerShell**:
    * Implementa exactamente la misma lista e idempotencia usando `Test-Path "../$repo"`.
    * Garantiza paridad operativa para desarrolladores que trabajan en entornos Windows sin `make`.

### D-04: Consolidación de `shoplab-helpdesk` como repositorio paraguas
* **Pregunta**: ¿Qué responsabilidades mantiene `shoplab-helpdesk` tras la extracción de componentes?
* **Decisión**:
  * Repositorio paraguas oficial del proyecto ShopLab Helpdesk:
    1. **Gobernanza Arquitectónica**: Archivos ADR en `docs/adr/`, mapa oficial de servicios en `docs/mapa-servicios.md`, especificaciones de fase en `docs/fases/`.
    2. **Orquestación del Entorno Local**: `infra/docker-compose.yml`, configuraciones de LiteLLM, Ollama y perfiles de observabilidad.
    3. **Pruebas de Extremo a Extremo (E2E)**: Harness de verificación integral que comprueba la salud e interoperabilidad de todos los servicios.
    4. **Backend Transicional**: Aloja temporalmente el backend modular en `backend/` hasta su extracción programada en S19–S24 (LAB-59, LAB-129, LAB-130).

### D-05: Rediseño del flujo de inicio en `README.md`
* **Pregunta**: ¿Cómo debe presentarse la guía de inicio rápido en el `README.md` para reflejar el nuevo modelo multi-repo?
* **Decisión**: Estructurar la sección de inicio en 4 pasos secuenciales y autónomos:
  1. **Clonar**: `make clone-all` o `pwsh scripts/clone-all.ps1`.
  2. **Configurar y Levantar**: Copiar `.env.example` a `.env` y ejecutar `docker compose -f infra/docker-compose.yml up -d --wait`.
  3. **Desarrollar sobre un servicio**: Habilitar `docker-compose.override.yml` o apagar el contenedor específico para ejecutarlo en el IDE local contra la infraestructura Docker.
  4. **Probar**: Ejecución de pruebas integradas y consultas directas a los endpoints expuestos.
