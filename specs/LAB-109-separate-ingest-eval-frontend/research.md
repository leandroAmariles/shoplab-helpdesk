# Research: Separar ingest, eval y frontend en repos propios

**Feature**: `LAB-109-separate-ingest-eval-frontend`
**Date**: 2026-11-24
**Status**: Completed

---

## 1. Technical Context & Objectives

En cumplimiento de [ADR-0015](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0015-limites-de-contexto-y-repositorios.md) y [ADR-0031](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/docs/adr/0031-extraccion-temprana-de-servicios.md), el proyecto ShopLab Helpdesk ejecuta la extracción temprana de los componentes periféricos (`ingest`, `eval` y `frontend`) para desacoplar sus ciclos de vida, dependencias tecnológicas y pipelines de despliegue antes de acometer la extracción de los módulos centrales del backend (`chat`, `retrieval`, `eval-service`).

Esta fase de investigación resuelve todas las incógnitas técnicas sobre:
1. Procedimiento exacto y reproducible de partición con `git filter-repo`.
2. Integración y parametrización de workflows de `shoplab-ci@v1`.
3. Consumo y resolución de dependencias desde `shoplab-contracts` (npm y uv).
4. Despliegue continuo hacia `shoplab-infra` respetando el modelo GitOps con Argo CD.
5. Protocolo de retirada limpia de carpetas en `shoplab-helpdesk` sin romper la CI restante.

---

## 2. Decisiones de Investigación (Resolución de Incógnitas)

### D-01: Método de extracción y preservación de historial (`git filter-repo`)
* **Pregunta**: ¿Cómo extraer cada subcarpeta (`ingest/`, `eval/`, `frontend/`) a un repositorio nuevo sin perder los commits históricos ni arrastrar código ajeno?
* **Opciones evaluadas**:
  1. *Copiar archivos manualmente (`cp -r` / git init)*: Rechazado rotundamente. Destruye el historial de commits, pierde autores, fechas y la trazabilidad de `git blame`.
  2. *`git subtree split`*: Funcional, pero tiende a ser lento y deja metadatos residuales o referencias huérfanas en árboles complejos.
  3. *`git filter-repo --subdirectory-filter`*: Estándar oficial recomendado por el equipo de Git. Reescribe el árbol de objetos de manera limpia, rápida y segura, moviendo los archivos de la subcarpeta directamente a la raíz y podando commits que no afectaron dicha ruta.
* **Decisión**: Utilizar `git filter-repo --subdirectory-filter <subcarpeta>` sobre un clon espejo fresco del monorepo (`git clone --no-local . /tmp/extract-<repo>`).
* **Protocolo de ejecución**:
  ```bash
  # Para cada componente (ingest, eval, frontend):
  git clone file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk /tmp/extract-ingest
  cd /tmp/extract-ingest
  git filter-repo --subdirectory-filter ingest --force
  # Limpieza de tags del monorepo antiguo para no colisionar con release-please:
  git tag -l | xargs git tag -d
  git remote add origin https://github.com/leandroAmariles/shoplab-ingest.git
  git push -u origin main --force
  ```

### D-02: Adopción de la plantilla y workflows reutilizables de `shoplab-ci`
* **Pregunta**: ¿Cómo deben estructurarse los pipelines de CI en los repositorios extraídos?
* **Decisión**: Cada nuevo repositorio contendrá un workflow mínimo `.github/workflows/ci.yml` que invocará mediante `workflow_call` los flujos versionados de `shoplab-ci@v1` (establecidos en LAB-103):
  * `shoplab-ingest` y `shoplab-eval` invocarán `leandroAmariles/shoplab-ci/.github/workflows/python-service.yml@v1`.
  * `shoplab-frontend` invocará `leandroAmariles/shoplab-ci/.github/workflows/angular-app.yml@v1`.
* **Beneficio**: Garantiza que las puertas de calidad (cobertura ≥80 %, linters, Trivy fs y políticas de seguridad) se apliquen de forma idéntica sin duplicar lógica YAML.

### D-03: Consumo de contratos versionados de `shoplab-contracts` (LAB-108)
* **Pregunta**: ¿Cómo consumirán los tipos de API y clientes sin depender de carpetas locales ni submódulos?
* **Decisión**:
  * **Frontend (Angular)**: Consume `@leandroamariles/shoplab-contracts` publicado en GitHub Packages npm. En `.npmrc` se define `@leandroamariles:registry=https://npm.pkg.github.com` con `NODE_AUTH_TOKEN=${GITHUB_TOKEN}` en CI.
  * **Ingest y Eval (Python)**: Consumen los modelos y esquemas desde `shoplab-contracts` vía `uv`. La dependencia en `pyproject.toml` se declara como:
    ```toml
    dependencies = [
        "shoplab-contracts @ git+https://github.com/leandroAmariles/shoplab-contracts.git@v1.2.0#subdirectory=python",
        # ...
    ]
    ```
  * En CI, `uv sync --frozen` autentica contra GitHub utilizando las credenciales nativas del runner.

### D-04: Estrategia de entrega continua hacia `shoplab-infra`
* **Pregunta**: ¿Cómo se promueven las nuevas versiones de los repositorios hacia el clúster `dev`?
* **Decisión**:
  * Cada repositorio incluirá el job de despliegue que invoca la acción/script con la GitHub App `shoplab-deploy`.
  * Tras compilar y publicar la imagen OCI en `ghcr.io/leandroamariles/<repo>:<tag>`, el job clona `shoplab-infra` y ejecuta:
    ```bash
    kustomize edit set image <servicio>=ghcr.io/leandroamariles/<repo>:<tag>
    ```
    en `k8s/overlays/dev/kustomization.yaml`.
  * Argo CD, configurado con reconciliación automática por pull (ADR-0006), detecta el commit en `shoplab-infra`, despliega el nuevo contenedor en el clúster `dev` y reporta estado `Synced` y `Healthy`.

### D-05: Protocolo de limpieza en `shoplab-helpdesk`
* **Pregunta**: ¿En qué momento y cómo deben retirarse las carpetas del monorepo?
* **Decisión**:
  1. La eliminación en `shoplab-helpdesk` se realiza **únicamente después** de que los tres repositorios independientes hayan sido creados, tengan sus branches `main` con historial verificado y sus pipelines de CI pasando en verde.
  2. Se ejecuta `git rm -r ingest eval frontend` en una rama de feature (`feature/LAB-109-separate-ingest-eval-frontend`).
  3. Se ajustan los archivos `.github/workflows/ci.yml` e `images.yml` de `shoplab-helpdesk` eliminando los filtros de paths y matrices correspondientes a esos tres servicios.
  4. Se actualiza el `README.md` del monorepo documentando la nueva ubicación de los proyectos.

---

## 3. Matriz de Componentes y Destinos

| Componente Original | Repositorio Destino | Stack Tecnológico | Workflow CI (`shoplab-ci`) | Imagen GHCR |
|---|---|---|---|---|
| `ingest/` | `shoplab-ingest` | Python 3.12 / FastAPI / uv | `python-service.yml@v1` | `ghcr.io/leandroamariles/shoplab-ingest` |
| `eval/` | `shoplab-eval` | Python 3.12 / RAGAS / uv | `python-service.yml@v1` | `ghcr.io/leandroamariles/shoplab-eval` |
| `frontend/` | `shoplab-frontend` | Angular 19+ / Node 22 / Nginx | `angular-app.yml@v1` | `ghcr.io/leandroamariles/shoplab-frontend` |
