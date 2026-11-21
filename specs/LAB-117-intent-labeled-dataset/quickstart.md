# Quickstart: validar el dataset de intenciones

Guía para comprobar que la historia funciona de extremo a extremo. Los formatos están en
[contracts/dataset-format.md](contracts/dataset-format.md) y los comandos en [contracts/cli-contract.md](contracts/cli-contract.md);
aquí solo el recorrido y lo que debe verse.

## Prerrequisitos

- `ml/` sincronizado: `cd ml && uv sync`.
- **Solo para construir el dataset** (no para validarlo): `docker compose -f infra/docker-compose.yml up -d --wait` con Olist
  cargado (LAB-17), el modelo `qwen2.5:7b` descargado y `SHOPLAB_ML_LLM_API_KEY` con la clave `eval` del gateway.

## A. Validar lo publicado (lo que corre en CI, sin red ni base de datos)

```bash
cd ml
uv run ruff check .
uv run pytest                                              # cobertura ≥ 80 %, incluye test_real_dataset.py
uv run python -m intent_dataset validate --golden ../eval/golden.jsonl
uv run python -m intent_dataset stats                      # las mismas cifras que el bloque de DATASHEET.md
```

**Debe verse**: `validate` termina con código 0; `stats` muestra ≥ 600 ejemplos, las 5 clases con ≥ 60, cada celda clase-idioma
con ≥ 20, las 3 particiones con las 5 clases y los 2 idiomas, y el kappa de la muestra de 50. (SC-001, SC-002, SC-004, SC-005,
SC-008)

## B. Probar que el validador detecta defectos (SC-009)

`test_validation.py` provoca cada defecto sobre un dataset en memoria y comprueba que `validate` falla con la regla y el
ejemplo. A mano, sobre una copia temporal:

```bash
cp data/intents/intents.jsonl /tmp/bad.jsonl
# 1) cambia una etiqueta a "otra"        → regla `label`
# 2) mueve un ejemplo de un grupo a otra partición → regla `group_split`
# 3) añade "escríbeme a ana@correo.com"   → regla `pii`
uv run python -m intent_dataset validate --dataset /tmp/bad.jsonl --no-docs     # código 1 y las tres incidencias
```

## C. Probar el enmascarado (historia 4)

```bash
uv run pytest tests/intent_dataset/test_masking.py -v
```

Un texto con correo, teléfono, CPF, CNPJ, CEP, identificador de 32 hex y enlace sale con `[EMAIL]`, `[PHONE]`, `[CPF]`,
`[CNPJ]`, `[CEP]`, `[ID]` y `[URL]` y el resto intacto; aplicarlo dos veces no cambia nada.

## D. Reconstruir el dataset desde cero (solo local)

Se hace una vez y es lo que produce los archivos versionados.

```bash
cd ml
# 1. Candidatos del golden y de las reseñas (enmascarados al leer)
uv run python -m intent_dataset candidates --out data/intents/work/candidates.csv
#    → añade semillas a mano al CSV (source=manual_seed) y revisa: decision, label, pii_checked, text_edit
# 2. Primera tanda
uv run python -m intent_dataset build data/intents/work/candidates.csv
# 3. Paráfrasis con el modelo local y su revisión
export SHOPLAB_ML_LLM_API_KEY=...        # clave virtual `eval` del gateway
uv run python -m intent_dataset paraphrase --per-parent 2 --translate
#    → revisa data/intents/work/paraphrases.csv (la persona decide cada fila)
uv run python -m intent_dataset build data/intents/work/paraphrases.csv
# 4. Partición fija
uv run python -m intent_dataset split
# 5. Acuerdo entre anotadores
uv run python -m intent_dataset sample                    # congela los 50 y prepara annotator-b.csv
#    → la segunda persona rellena `label` (o: uv run python -m intent_dataset annotate)
uv run python -m intent_dataset agreement --annotator-b data/intents/work/annotator-b.csv --kind person
#    → rellena `final` y `reason` de los desacuerdos en annotations/agreement-sample.jsonl y corrige las etiquetas
# 6. Ficha y comprobación final
uv run python -m intent_dataset stats --write
uv run python -m intent_dataset validate --golden ../eval/golden.jsonl
```

**Debe verse**: `build` informa de aceptadas, rechazadas y pendientes por origen y clase; `split` terminado con código 0 y dos
ejecuciones seguidas sobre los mismos datos no cambian nada (SC-005); `agreement` imprime el kappa con `n = 50` y la lista de
desacuerdos (SC-003).

## E. Comprobaciones manuales que ningún patrón sustituye

| Qué | Dónde se registra | Historia |
|---|---|---|
| Cada comentario de Olist sin nombres de persona (`pii_checked = si`) | `REVIEW.md` | 4 |
| Cada paráfrasis conserva la intención y no inventa datos | `REVIEW.md` | 1 |
| Ningún `charla` contiene una petición real | `REVIEW.md` | 1 |
| La guía se leyó entera tras resolver los desacuerdos | `REVIEW.md` | 2 |
| Quién fue el anotador B y si fue persona o modelo | `DATASHEET.md` | 2, 5 |

> Hasta que una **persona** firme `REVIEW.md`, el dataset es un **borrador**, igual que el golden en LAB-27: ningún modelo
> puede firmar esas revisiones.

## F. Comprobar que no cambió nada ajeno (FR-025)

```bash
git diff --stat main -- backend ingest frontend infra eval data .github
```

**Debe verse**: vacío. Los únicos archivos fuera de `ml/` y `specs/` son `.gitignore` y `README.md`.

## Resultados a registrar al terminar

`specs/LAB-117-intent-labeled-dataset/validation.md`: totales por clase e idioma, kappa y tipo del anotador B, número de
paráfrasis generadas, descartadas por filtro y rechazadas en la revisión, y la salida de `validate`.
