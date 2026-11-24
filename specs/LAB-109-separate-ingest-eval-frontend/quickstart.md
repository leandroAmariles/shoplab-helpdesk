# Quickstart: Guía Operativa para la Separación de Repositorios

**Feature**: `LAB-109-separate-ingest-eval-frontend`
**Target**: Desarrolladores y DevOps

---

## 1. Guía Paso a Paso para la Extracción con `git filter-repo`

### Requisitos Previos
* Git 2.30+ instalado.
* Python 3 con `git-filter-repo` instalado (`pip install git-filter-repo` o `uv tool install git-filter-repo`).
* Permisos de administrador/creación de repositorios en `github.com/leandroAmariles`.

### Paso 1: Clonar el monorepo en un directorio temporal de trabajo
```bash
# Clonar un mirror fresco sin ligas locales
git clone file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk /tmp/migration-workspace
```

### Paso 2: Extraer `shoplab-ingest`
```bash
cp -r /tmp/migration-workspace /tmp/ingest-repo
cd /tmp/ingest-repo
git filter-repo --subdirectory-filter ingest --force
# Eliminar tags del monorepo para iniciar historial limpio
git tag -l | xargs git tag -d
# Inicializar remote y empujar a GitHub
git remote add origin https://github.com/leandroAmariles/shoplab-ingest.git
git branch -M main
git push -u origin main --force
```

### Paso 3: Extraer `shoplab-eval`
```bash
cp -r /tmp/migration-workspace /tmp/eval-repo
cd /tmp/eval-repo
git filter-repo --subdirectory-filter eval --force
git tag -l | xargs git tag -d
git remote add origin https://github.com/leandroAmariles/shoplab-eval.git
git branch -M main
git push -u origin main --force
```

### Paso 4: Extraer `shoplab-frontend`
```bash
cp -r /tmp/migration-workspace /tmp/frontend-repo
cd /tmp/frontend-repo
git filter-repo --subdirectory-filter frontend --force
git tag -l | xargs git tag -d
git remote add origin https://github.com/leandroAmariles/shoplab-frontend.git
git branch -M main
git push -u origin main --force
```

---

## 2. Verificación Local de los Nuevos Repositorios

### Verificar `shoplab-ingest`
```bash
git clone https://github.com/leandroAmariles/shoplab-ingest.git
cd shoplab-ingest
uv sync
uv run pytest --cov=app --cov-fail-under=80
uv run ruff check .
```

### Verificar `shoplab-eval`
```bash
git clone https://github.com/leandroAmariles/shoplab-eval.git
cd shoplab-eval
uv sync
uv run pytest
```

### Verificar `shoplab-frontend`
```bash
git clone https://github.com/leandroAmariles/shoplab-frontend.git
cd shoplab-frontend
npm install
npm test -- --watch=false
npm run build
```

---

## 3. Retiro de Directorios en `shoplab-helpdesk`

Una vez verificados los tres repositorios en GitHub con sus pipelines en verde:

```bash
cd C:/Users/Leandro/IdeaProjects/shoplab-helpdesk
git checkout feature/LAB-109-separate-ingest-eval-frontend

# Eliminar físicamente del monorepo
git rm -r ingest eval frontend

# Actualizar referencias en .github/workflows/ci.yml e images.yml
# Editar README.md con la tabla de enlaces a repositorios independientes

git commit -m "LAB-109 chore(monorepo): remove ingest, eval and frontend folders post-extraction"
```

---

## 4. Verificación de Convergencia GitOps en `shoplab-infra`

Tras el primer despliegue automatizado:

```bash
# Comprobar estado en Argo CD
kubectl get applications -n argocd

# Verificar estado de pods en dev
kubectl get pods -n dev -l app.kubernetes.io/name=ingest
kubectl get pods -n dev -l app.kubernetes.io/name=frontend
```
Resultado esperado: `Synced` y `Healthy`.
