# Quickstart: validar el análisis exploratorio de Olist

Guía de validación; los tipos están en [data-model.md](data-model.md), los formatos en [contracts/](contracts/) y las
decisiones en [research.md](research.md). Cada sección indica la historia o los criterios que prueba.

## Requisitos

- Python 3.12 o superior y [`uv`](https://docs.astral.sh/uv/).
- Para las pruebas automáticas (§1): nada más. **No necesitan base de datos.**
- Para ejecutar el notebook (§2 a §5): la base local con Olist cargado.
  1. `docker compose -f infra/docker-compose.yml up -d --wait`
  2. Arrancar el backend una vez para que Flyway cree los esquemas `orders` y `catalog`.
  3. `cd data/olist && uv sync && uv run olist-loader load` (necesita cuenta de Kaggle o `--source ruta.zip`).

## 1. Pruebas automáticas (CI)

```bash
cd ml
uv sync
uv run ruff check .
uv run pytest              # cobertura ≥ 80 % del paquete; sin red ni base de datos
```

Comprueba, entre otras cosas: perfilado y duplicados sobre DataFrames conocidos; reglas de nulos esperados; huérfanos entre
esquemas; que el corpus real expone 4 tipos de pago y 8 estados; cálculo de retraso y población analizada; que cada tipo de
hallazgo resuelve una acción y que falta una → `MissingActionError`; y que el `.ipynb` guardado tiene las 15 secciones en
orden, ningún bucle por fila, ninguna cadena de 32 caracteres hexadecimales y cifras coherentes con
`olist-eda-summary.json`.

## 2. Ejecutar el notebook de arriba abajo (historias 1 a 4; FR-019, SC-005)

```bash
cd ml
uv run jupyter nbconvert --to notebook --execute --inplace notebooks/olist-eda.ipynb
```

(o abrirlo en Jupyter, VS Code o IntelliJ y «Ejecutar todo»). Resultado esperado:

1. Parámetros y preflight pasan en segundos; el banner indica volumen completo.
2. §3 muestra ocho perfiles (nulos, duplicados, tipos y rangos), sin celdas que recorran filas.
3. §5 a §7 listan todos los valores de `payment_type` y `order_status` con frecuencia y cobertura; los declarados por las
   políticas que no existen en los datos aparecen aparte.
4. §8 a §11 muestran la distribución de tiempos real y estimado, retrasos por estado y por categoría y reseñas.
5. §12 es una tabla de discrepancias donde ninguna fila tiene la acción vacía; §13 y §14 cierran con conclusiones y límites.
6. Termina sin errores en menos de 5 minutos en una máquina modesta.

## 3. Leerlo sin ejecutarlo (SC-006)

Abrir `ml/notebooks/olist-eda.ipynb` en GitHub o en un visor: ya muestra tablas, gráficos y conclusiones. Comprobar que
`ml/notebooks/results/olist-eda-summary.json` tiene `"data_scale": "full"`.

## 4. Preflight (historia 5, escenario 3; FR-018)

- **Base apagada**: `docker compose -f infra/docker-compose.yml down`, ejecutar §2 → falla en la segunda celda de código
  con el mensaje de conexión y el comando para levantarla; no hay resultados parciales.
- **Esquemas sin datos**: apuntar `POSTGRES_URL` a una base recién creada → mensaje de tablas ausentes o vacías con el paso
  para resolverlo.

## 5. Solo lectura (FR-025)

Desde una celda temporal (sin guardarla): intentar `INSERT` o `CREATE TABLE` con la conexión del paquete debe fallar con
`cannot execute … in a read-only transaction`. Confirma que el análisis no puede modificar las tablas.

## 6. Reproducibilidad (SC-008)

Ejecutar §2 dos veces seguidas con los mismos datos y comparar `olist-eda-summary.json` salvo `generated_at`: idénticos.

## 7. CI con volumen pequeño (SC-004)

En un pull request que toque `ml/**`, el job `ml-notebook` debe:

1. Levantar Postgres, aplicar migraciones y cargar las fixtures (`ml/ci/prepare-database.sh`).
2. Ejecutar el notebook con `nbconvert --execute` y `EDA_MIN_GROUP_ORDERS=1`, y terminar en verde.
3. Mostrar la sección de umbral con el aviso «volumen reducido», sin gráficos vacíos ni errores de división por cero.

Comprobación negativa (una vez): introducir `raise RuntimeError("prueba")` en una celda en una rama desechable y ver que
`ml-notebook` y `ci-gate` fallan; revertir.

Para reproducir CI en local:

```bash
export PGHOST=localhost PGPORT=5432 PGUSER=shoplab PGPASSWORD=change-me-local-only PGDATABASE=shoplab_ci
createdb shoplab_ci
ml/ci/prepare-database.sh                                   # migraciones + fixtures
POSTGRES_URL=postgresql://localhost:5432/shoplab_ci EDA_MIN_GROUP_ORDERS=1 \
  uv run --project ml jupyter nbconvert --to notebook --execute --output-dir /tmp/ml-out ml/notebooks/olist-eda.ipynb
```

## 8. No hay CSV ni cambios fuera de alcance (SC-009; FR-023, FR-025)

```bash
git ls-files | grep -i '\.csv$'          # solo las fixtures de data/olist/tests/fixtures
git diff --stat origin/main -- backend ingest data/olist data/kb frontend infra   # vacío
grep -rn "olist_eda\|from ml\|import ml" backend ingest                           # sin resultados
```

## 9. Documentación y constitución

- `ml/README.md` explica en pocos pasos qué necesita, cómo ejecutarlo desde un clon limpio, cómo leer los resultados y cómo
  se valida en CI (FR-021).
- El README del repositorio tiene una fila para `ml/`.
- `.specify/memory/constitution.md` pasa a 1.0.2 y menciona `ml/`.
