# Contrato: CLI del cargador de Olist

Interfaz que expone `data/olist` a quien la ejecuta. Es la única interfaz externa de la historia (no hay endpoints
HTTP, por lo que no se genera `requests.http`).

## Comando

```text
uv run olist-loader load [--source PATH] [--data-dir PATH] [--dry-run] [--skip-download]
```

| Opción | Por defecto | Efecto |
|---|---|---|
| `--source PATH` | (ninguno) | ZIP o carpeta con los CSV ya descargados; no usa la red |
| `--data-dir PATH` | `data/raw` | Carpeta de caché para el ZIP y los CSV extraídos |
| `--dry-run` | apagado | Descarga/valida y transforma, reporta el resumen y **no abre conexión** con la base de datos (funciona sin ella) |
| `--skip-download` | apagado | Falla si los CSV no están en `--data-dir` en lugar de descargarlos |

## Entorno

| Variable | Obligatoria | Por defecto | Uso |
|---|---|---|---|
| `POSTGRES_URL` | no | `postgresql://localhost:5432/shoplab` | Conexión (sin credenciales en la URL) |
| `POSTGRES_USER` | no | `shoplab` | Usuario |
| `POSTGRES_PASSWORD` | no | `change-me-local-only` | Contraseña (valor local de `infra/docker-compose.yml`) |
| `KAGGLE_USERNAME` / `KAGGLE_KEY` | solo si descarga | — | Credenciales de la descarga; nunca se escriben en logs ni en disco |

## Códigos de salida

| Código | Significado |
|---|---|
| 0 | Carga completa (o `--dry-run` correcto) |
| 2 | Uso incorrecto de la línea de comandos |
| 3 | Descarga imposible: sin red, sin credenciales o archivo corrupto (mensaje indica qué falta) |
| 4 | Base de datos no preparada: faltan esquemas, tablas o migraciones (mensaje: arrancar el backend) |
| 5 | Fallo durante la carga; la transacción del esquema afectado se revirtió |

## Salida (stdout)

Resumen por esquema y tabla, en español, sin valores de fila:

```text
Esquema orders
  customers        leídas 99441  cargadas 99441  descartadas 0
  orders           leídas 99441  cargadas 99441  descartadas 0
  order_items      leídas 112650 cargadas 112650 descartadas 0
  ...
Esquema catalog
  products         leídas 32951  cargadas 32951  descartadas 0
  ...
Referencias huérfanas entre esquemas: 0 (order_items.product_id: 0, order_items.seller_id: 0)
Tiempo total: 00:00:42
```

(Los conteos del ejemplo son orientativos; los reales salen de la descarga.) Los motivos de descarte se listan bajo la
tabla afectada.

## Garantías

- **Idempotente**: dos ejecuciones seguidas dejan el mismo estado (research D-2).
- **Atómico por esquema**: una interrupción no deja datos parciales en el esquema afectado.
- **Sin DDL**: nunca crea ni altera tablas (research D-1).
- **Sin secretos en disco ni en logs**.
