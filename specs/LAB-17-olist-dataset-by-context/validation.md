# Validación: LAB-17 Carga del dataset Olist

Fecha: 2026-10-27. Rama: `feature/LAB-17-olist-dataset-by-context`.

## Alcance de lo validado

Todo se ejecutó con los **CSV de ejemplo sintéticos** de `data/olist/tests/fixtures/` (3 clientes, 3 pedidos,
6 ítems, etc.), no con el dataset real de Olist. La validación con el dataset real (T035) **sigue pendiente**: requiere
credenciales de Kaggle, que no había en el entorno de esta sesión. Ver «Pendiente» al final.

## Entorno

- Postgres desechable `pgvector/pgvector:0.8.7-pg16` en el puerto 55432 (la misma imagen que `infra/docker-compose.yml`),
  separado del Postgres local del usuario (5432), que no se tocó.
- Backend arrancado con `mvn spring-boot:run` apuntando a esa base (`POSTGRES_URL=jdbc:postgresql://…`, forma JDBC que
  el cargador también acepta). Log: `Schema 'orders' ready: 2 migration(s) applied` y
  `Schema 'catalog' ready: 2 migration(s) applied`.
- Contenedor y backend eliminados al terminar.

## Escenarios de `quickstart.md`

| # | Escenario | Resultado | Cómo se comprobó |
|---|---|---|---|
| V1 | Conteos (SC-002) | OK | Stack real: `orders` 3/3/5/3/4 y `catalog` 3/3/2 filas, iguales al resumen del cargador; descartes con motivo |
| V2 | Pedido completo solo en `orders` (SC-005) | OK | Stack real: `o01` devuelve cliente, 2 ítems, 2 pagos y 2 reseñas con consultas solo sobre `orders` |
| V3 | Producto completo solo en `catalog` (SC-005) | OK | Stack real: `p01` con categoría `beleza_saude`, `health_beauty`, precio de referencia 11.00 y stock 25 |
| V4 | Sin FKs entre esquemas (SC-003) | OK | Stack real: consulta S5 devuelve 0; `order_items` solo tiene `order_items_order_id_fkey → orders`. Pruebas de integración: FK temporal de `order_items` hacia `catalog` hace que S5 devuelva filas |
| V5 | Ítem sin producto en `catalog` | OK | Stack real: 2 huérfanos (`p99`, `s99`) cargados y contados, salida 0. Pruebas: `test_orphans.py` y `ModuleSchemasIntegrationTests` |
| V6 | Idempotencia (SC-004) | OK | Stack real: segunda carga con los mismos conteos y `max(id)` de reseñas = 4 (la identidad se reinicia) |
| V7 | Atomicidad (SC-004) | OK (solo pruebas) | `test_atomicity.py`: un fallo a mitad de `orders` deja ambos esquemas como estaban; repetir con datos correctos completa el conjunto. No se interrumpió manualmente la carga real |
| V8 | Base sin migrar (FR-010) | OK | Stack real: base nueva sin esquemas → salida 4 con «Primero arranca el backend para aplicar las migraciones» |
| V9 | Stock determinista (FR-015) | OK | Stack real: `p01`=25, `p02`=68, `p03`=19 en dos cargas; coinciden con los vectores calculados de forma independiente. Hay productos en 0 en `test_transform.py` (`out-3`) |
| V10 | Reutiliza la descarga (FR-009) | OK | Stack real: `--skip-download` con los CSV en la carpeta de caché, salida 0. Pruebas: segunda ejecución con transporte HTTP que falla ante cualquier petición |

Códigos de salida comprobados en el stack real: 0 (carga y `--dry-run`), 3 (sin credenciales ni `--source`) y 4 (base
sin migrar). Los códigos 2 y 5 se comprueban en `test_cli.py`.

## Pruebas automáticas

| Suite | Resultado |
|---|---|
| `data/olist`: `uv run ruff check .` | Sin avisos |
| `data/olist`: `uv run pytest` | 85 pruebas OK (≈20 s; necesita Docker) |
| `backend`: `mvn test -Dtest='ModuleSchemas*,SchemaIsolation*'` | 26 pruebas OK |
| `backend`: `mvn test` completo | 164 pruebas OK |
| `backend`: `mvn checkstyle:check` | 0 violaciones |

## Hallazgos durante la implementación

- **Conexión colgada**: sin `connect_timeout`, una base inalcanzable dejaba el comando esperando unos 130 s en Windows.
  Se fijó en 5 s (`CONNECT_TIMEOUT_SECONDS` en `config.py`) y lo cubre `test_an_unreachable_database_exits_with_four`.
- **Formato decimal de los enteros**: el dataset real suele escribir las medidas como `40.0`; el cargador acepta
  enteros escritos así y rechaza fracciones (`test_whole_numbers_written_as_decimals_are_accepted`).
- **Formato de `POSTGRES_URL`**: el backend lo exporta con prefijo `jdbc:`; el cargador lo normaliza.
- **Consola de Windows**: en Git Bash los acentos del resumen se ven como `�` por la codificación de la consola;
  el contenido es correcto (se ve bien en PowerShell o con `PYTHONIOENCODING=utf-8`).

## Pendiente

- **T035, carga con el dataset real**: contrastar nombres de columna y conteos de `research.md` (D-4), la repetición de
  `review_id`, el endpoint y la autenticación de descarga de Kaggle (D-3), y que no haya ítems huérfanos. El código de
  descarga está probado solo con `httpx.MockTransport`, **nunca contra Kaggle**.
- **CI**: el cambio en `.github/workflows/ci.yml` no se ha podido ejecutar fuera de GitHub.
