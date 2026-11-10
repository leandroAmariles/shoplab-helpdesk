# Quickstart: validar el notebook de estrategias de chunking

Guía de validación; los tipos están en [data-model.md](data-model.md), los formatos de datos en
[contracts/](contracts/) y las decisiones en [research.md](research.md).

## Requisitos

- Python 3.12 o superior y [`uv`](https://docs.astral.sh/uv/).
- Para las pruebas automáticas (§1): nada más. **No necesitan Ollama.**
- Para ejecutar el notebook (§2 a §4): Ollama con `bge-m3`, que levanta el compose del proyecto
  (`docker compose -f infra/docker-compose.yml up -d --wait`; la primera vez descarga los modelos, ~13 GB en total) o una
  instalación local de Ollama con `ollama pull bge-m3`. Por defecto el notebook usa `http://localhost:11434`
  (`OLLAMA_BASE_URL`) y `bge-m3` (`OLLAMA_EMBED_MODEL`).

## 1. Pruebas automáticas (CI)

```bash
cd notebooks
uv sync
uv run ruff check .
uv run pytest              # cobertura ≥ 80 % del paquete; sin red ni Ollama
```

Comprueba, entre otras cosas: que `questions.toml` valida contra `data/kb` (20 preguntas, 4 por tema, pasajes únicos);
que el adaptador de encabezados da los mismos chunks que `ingest_document` en los 24 documentos; el solape y el tope de
tamaño de las tres estrategias; el cálculo de posición, hit rate y MRR; y que el `.ipynb` guardado es coherente con
`results/chunking-results.json`.

## 2. Ejecutar el notebook de arriba abajo (historias 1, 2 y 4)

```bash
cd notebooks
uv sync
uv run jupyter nbconvert --to notebook --execute --inplace chunking-strategies.ipynb
```

(o abrir `chunking-strategies.ipynb` en Jupyter, VS Code o IntelliJ y «Ejecutar todo»). Resultado esperado:

1. La celda de parámetros y la de preflight pasan en segundos.
2. La sección de estrategias muestra, para `fixed`, `headings` y `semantic`: número de chunks y tamaño mínimo, medio y
   máximo; y un mismo pasaje cortado de las tres maneras.
3. La validación de las 20 preguntas termina sin errores.
4. La tabla tiene **20 filas** y, debajo, el resumen con hit rate@4 y MRR de cada estrategia.
5. La sección de sensibilidad (opcional) muestra el efecto del percentil y del tamaño de chunk.
6. La conclusión nombra la estrategia con mejores métricas y cita al menos dos preguntas por `id`.
7. Se actualiza `results/chunking-results.json`.

Con la caché vacía debe tardar menos de 15 minutos sin GPU (SC-003); con la caché llena, segundos.

## 3. Fallo temprano sin Ollama (SC-007)

No hace falta parar Docker: basta apuntar a una URL sin servidor o a un modelo inexistente, y escribir la salida fuera del
notebook guardado.

```bash
cd notebooks
OLLAMA_BASE_URL=http://localhost:1 uv run jupyter nbconvert --to notebook --execute --output-dir /tmp/nbout chunking-strategies.ipynb
OLLAMA_EMBED_MODEL=no-existe uv run jupyter nbconvert --to notebook --execute --output-dir /tmp/nbout chunking-strategies.ipynb
```

Debe fallar en la **segunda celda de código** con `PreflightError` y un mensaje que indica qué falta y cómo arrancarlo
(`docker compose -f infra/docker-compose.yml up -d --wait` en el primer caso, `ollama pull no-existe` en el segundo);
ninguna celda posterior produce resultados. (Verificado el 2026-11-10.)

## 4. Reproducibilidad

```bash
cd notebooks
cp results/chunking-results.json /tmp/before.json
rm -rf .cache                                               # ejecución en frío
uv run jupyter nbconvert --to notebook --execute --inplace chunking-strategies.ipynb
diff <(jq 'del(.meta.generated_at)' /tmp/before.json) <(jq 'del(.meta.generated_at)' results/chunking-results.json)
```

Sin diferencias en `summary` ni en las posiciones de `rows` (SC-004). Los chunks de `fixed` y `headings` son idénticos
siempre; si en otra máquina cambia alguna posición de `semantic` por diferencias numéricas de los embeddings, el notebook
lo declara en su sección de reproducibilidad y el `embed_model_digest` permite saber si fue el modelo.

## 5. Lectura sin ejecutar (SC-005)

Abrir `notebooks/chunking-strategies.ipynb` en GitHub o en cualquier visor: ya muestra las tablas, los gráficos, los
ejemplos y la conclusión de la última ejecución.

## 6. Comprobaciones de alcance

```bash
git diff --stat main -- ingest backend frontend infra       # sin cambios (FR-021, FR-022)
grep -rn "notebooks" backend/src ingest/app                  # sin importaciones desde producción
```

Solo deben cambiar `notebooks/`, `specs/LAB-26-…`, `.github/workflows/ci.yml`, `.gitignore` y `README.md`.
