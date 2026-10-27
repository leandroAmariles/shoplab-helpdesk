# olist-loader

Carga el dataset público de Olist en los esquemas `orders` y `catalog` de ShopLab Helpdesk. El reparto de tablas, el
stock sintético y las reglas de descarte están en [`docs/olist-dataset.md`](../../docs/olist-dataset.md).

## Requisitos

- Python 3.12 o superior y [`uv`](https://docs.astral.sh/uv/).
- La base de datos local levantada (`docker compose -f infra/docker-compose.yml up -d --wait`) y el backend
  arrancado al menos una vez: él crea las tablas; este cargador **no ejecuta DDL**.
- Para descargar: una cuenta de Kaggle. Sin ella, usa `--source` con un ZIP o una carpeta ya descargados.

## Uso

```bash
cd data/olist
uv sync
uv run olist-loader load                       # descarga (o reutiliza data/raw/) y carga
uv run olist-loader load --source ruta.zip     # sin red: ZIP o carpeta con los CSV
uv run olist-loader load --dry-run             # valida y resume sin abrir la base de datos
uv run olist-loader load --skip-download       # falla si los CSV no están ya en data/raw/
```

## Variables de entorno

| Variable | Por defecto | Uso |
|---|---|---|
| `POSTGRES_URL` | `postgresql://localhost:5432/shoplab` | Conexión; acepta también la forma `jdbc:postgresql://…` del backend |
| `POSTGRES_USER` | `shoplab` | Usuario |
| `POSTGRES_PASSWORD` | `change-me-local-only` | Contraseña (valor local de `infra/docker-compose.yml`) |
| `KAGGLE_USERNAME` / `KAGGLE_KEY` | — | Solo para descargar; nunca se escriben en logs ni en disco |

Los archivos descargados quedan en `data/raw/` (ignorado por git; el dataset es CC BY-NC-SA 4.0, no se versiona).

## Códigos de salida

| Código | Significado |
|---|---|
| 0 | Carga completa (o `--dry-run` correcto) |
| 2 | Uso incorrecto de la línea de comandos |
| 3 | No se pudo obtener el dataset (sin red, sin credenciales, archivo corrupto o incompleto) |
| 4 | Base de datos no preparada o inalcanzable: arranca el backend para aplicar las migraciones |
| 5 | Falló la carga; el esquema afectado quedó como estaba |

## Pruebas

```bash
uv run ruff check .
uv run pytest        # necesita Docker: levanta una Postgres desechable y le aplica las migraciones reales del backend
```
