# notebooks — comparativo de estrategias de chunking

Experimentos de ShopLab Helpdesk. Hoy contiene un notebook, [`chunking-strategies.ipynb`](chunking-strategies.ipynb)
(LAB-26), que compara tres formas de trocear la base de conocimiento (`data/kb`) y mide cuál recupera mejor la información
correcta:

| Estrategia | Cómo corta |
|---|---|
| **Tamaño fijo** | cada `chunk_size` caracteres, con solape; no mira el contenido |
| **Por encabezados** | por secciones del Markdown; es el troceado de producción de `ingest/` (se reutiliza, no se copia) |
| **Semántica** | donde cambia el significado entre frases consecutivas, medido con embeddings |

Se lanzan **20 preguntas de prueba** (cuatro por tema) contra los chunks de cada estrategia y se calcula, con los 4
chunks más cercanos por pregunta, el **hit rate@k** y el **MRR**. El notebook **mide y concluye**: no cambia el chunking de
producción (cualquier cambio exige la evaluación automática de F2).

El notebook se guarda con sus salidas: puedes **leerlo sin ejecutarlo**, en GitHub o en cualquier visor.

## Requisitos

- Python 3.12 o superior y [`uv`](https://docs.astral.sh/uv/).
- **Ollama con el modelo de embeddings `bge-m3`**, el mismo que usa el sistema. Solo hace falta para ejecutar el
  notebook; las pruebas no lo necesitan. Opciones:
  - `docker compose -f infra/docker-compose.yml up -d --wait` desde la raíz del repositorio (descarga los modelos la
    primera vez), o
  - una instalación local de Ollama con `ollama pull bge-m3`.
- Variables opcionales: `OLLAMA_BASE_URL` (por defecto `http://localhost:11434`) y `OLLAMA_EMBED_MODEL` (por defecto
  `bge-m3`).

## Instalar y ejecutar

```bash
cd notebooks
uv sync                                                     # instala todo en un solo comando
uv run jupyter nbconvert --to notebook --execute --inplace chunking-strategies.ipynb
```

También puedes abrir `chunking-strategies.ipynb` en Jupyter, VS Code o IntelliJ y «Ejecutar todo». Sin editar código ni
rutas, con la caché vacía y sin GPU dedicada tarda menos de un minuto; no debería superar los 15 minutos en una máquina
modesta.

Si Ollama o el modelo no están disponibles, **la segunda celda de código** (el preflight) se detiene con un mensaje que
dice qué falta y cómo arrancarlo; no se calcula nada antes.

Al terminar escribe `results/chunking-results.json` con los parámetros, el resumen por estrategia y las 20 filas.

## Cómo leer la tabla

- Una fila por pregunta; una columna por estrategia con la **posición** (1 a 4) del primer chunk correcto, o
  «no encontrado».
- Un chunk es **correcto** solo si procede del documento esperado **y** contiene el pasaje de referencia de la pregunta.
- **hit rate@4**: fracción de preguntas con algún chunk correcto entre los 4 primeros. **MRR**: media de `1 / posición`
  (0 si no se encontró).
- Las 20 preguntas están en [`data/questions.toml`](data/questions.toml); el formato y las reglas de validación están en
  `specs/LAB-26-chunking-strategies-notebook/contracts/questions-format.md`.

## Parámetros

Todos están en la **primera celda de código**, con una explicación breve cada uno: `chunk_size` (1 000), `chunk_overlap`
(100), `top_k` (4, como el chat), `breakpoint_percentile` (80) y `min_chunk_chars` (200). **Si los cambias o cambias el
corpus, vuelve a leer las conclusiones**: el titular se genera por código, pero la interpretación (secciones 7 a 9) está
escrita sobre la ejecución guardada y cita preguntas concretas.

## Caché de embeddings

Los embeddings se guardan en `notebooks/.cache/embeddings/` (ignorada por git), una por texto y modelo. Repetir el notebook
tarda segundos. Para una ejecución en frío, borra esa carpeta.

## Pruebas

```bash
cd notebooks
uv run ruff check .
uv run pytest        # sin red ni Ollama; cobertura mínima del 80 % del paquete
```

Las pruebas validan, entre otras cosas, que las 20 preguntas son coherentes con `data/kb`, que el troceado por
encabezados es idéntico al de `ingest/` y que el notebook guardado coincide con `results/chunking-results.json`.

## Estructura

```text
notebooks/
├── chunking-strategies.ipynb      # el notebook, con sus salidas
├── data/questions.toml            # las 20 preguntas de prueba
├── results/chunking-results.json  # métricas y filas de la última ejecución
├── src/chunking_lab/              # la lógica (estrategias, embeddings, recuperación, métricas, informe)
└── tests/
```

La lógica vive en `src/chunking_lab/` (probada y con lint); el notebook solo la orquesta y la explica. Nada de
`backend/` ni de `ingest/` importa este proyecto.
