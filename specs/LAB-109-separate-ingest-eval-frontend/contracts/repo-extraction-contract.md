# Contrato de Extracción de Repositorios con `git filter-repo`

**Feature**: `LAB-109-separate-ingest-eval-frontend`
**Target**: Scripts de automatización y migración

---

## 1. Parámetros de Extracción

| Parámetro | Valor para Ingest | Valor para Eval | Valor para Frontend |
|---|---|---|---|
| **Directorio Origen** | `ingest/` | `eval/` | `frontend/` |
| **Comando filtro** | `--subdirectory-filter ingest` | `--subdirectory-filter eval` | `--subdirectory-filter frontend` |
| **Repositorio Destino** | `shoplab-ingest` | `shoplab-eval` | `shoplab-frontend` |
| **URL Remoto** | `https://github.com/leandroAmariles/shoplab-ingest.git` | `https://github.com/leandroAmariles/shoplab-eval.git` | `https://github.com/leandroAmariles/shoplab-frontend.git` |
| **Rama principal** | `main` | `main` | `main` |

---

## 2. Invariantes de Calidad del Historial

1. **Integridad de Autores y Fechas**: Cada commit preservado debe mantener el nombre del autor, correo electrónico original y marca de tiempo ISO-8601 exacta.
2. **Raíz Limpia**: Ningún archivo resultante de la extracción debe quedar prefijado con el nombre del directorio antiguo (e.g. `pyproject.toml` debe estar en `/pyproject.toml`, nunca en `/ingest/pyproject.toml`).
3. **Aislamiento de Tags**: Todos los tags previos del monorepo (`v0.1.0` a `v0.26.0`) deben ser purgados de los nuevos repositorios antes del push inicial, permitiendo que `release-please` comience una numeración limpia (e.g., `v1.0.0` o `v0.1.0`).
4. **Commits no relacionados podados**: Ningún commit que haya modificado exclusivamente `backend/`, `infra/` o `docs/` debe figurar en el `git log` de los repositorios extraídos.
