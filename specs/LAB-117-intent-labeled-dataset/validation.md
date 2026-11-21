# Validación de LAB-117 (parcial)

**Fecha**: 2026-11-21 · **Rama**: `feature/LAB-117-intent-labeled-dataset`

> **Estado: el código está terminado y probado; los datos NO existen todavía.** Lo que sigue separa lo que se ejecutó de lo que
> no se pudo ejecutar y por qué. Nada de lo pendiente se ha dado por hecho.

## Ejecutado y verificado

| Comprobación | Resultado |
|---|---|
| `cd ml && uv run ruff check .` | Sin incidencias |
| `cd ml && uv run pytest` | **444 pruebas en verde** (184 previas de LAB-116 + 260 nuevas); cobertura total **97,93 %** (umbral 80 %) |
| Cobertura de `intent_dataset` por módulo | `__main__` 96 %, `agreement` 98 %, `constants` 100 %, `dataset` 94 %, `datasheet` 100 %, `errors` 100 %, `masking` 100 %, `paraphrase` 91 %, `review` 97 %, `sources` 97 %, `splitting` 100 %, `text` 100 %, `validation` 100 % |
| Humo de la CLI real: `candidates --skip-reviews` sobre el `eval/golden.jsonl` real | 120 candidatos: 88 `politica` y 32 `charla`, todos `es`, con `group_id == golden_id` (coincide con `research.md` R4) |
| Humo de la CLI real: `validate` sin dataset | Código 2 con un mensaje que dice qué falta |
| Regla `pii` / enmascarado | Un caso por tipo de dato (URL, correo, ID de 32 hex, CNPJ, CPF, CEP, teléfono, racha de dígitos), idempotencia y texto limpio intacto |
| Un defecto provocado por regla del validador (SC-009) | Cubierto en `test_validation.py` (schema, id, label, language, source, split, text, reviewed, provenance, min_total, min_label, min_label_language, group_split, duplicates, split_coverage, agreement, datasheet, guide, golden_ids, pii) |
| Kappa con valores conocidos | acuerdo total = 1; tabla clásica = 0,4; azar = 0; desacuerdo sistemático = −1; pₑ = 1 → «no definido» |
| Validación sin red ni base de datos (FR-024) | `test_validate_never_touches_the_network_or_the_database` (se rompe `psycopg.connect` y `urlopen`) |
| FR-025: `git diff --stat main -- backend ingest frontend infra eval data .github` | **Vacío** |
| Archivos fuera de `ml/` y `specs/` | Solo `.gitignore` y `README.md` |
| Búsqueda de secretos en `ml/src`, `ml/data` y `specs/LAB-117…` | Sin coincidencias |
| `ml/data/intents/work/` | Ignorado por git (`git check-ignore`) |

## No se pudo ejecutar

| Qué | Por qué | Tareas |
|---|---|---|
| Extraer reseñas de Olist, generar paráfrasis, `build`, `split` y `sample` sobre datos reales | **Docker estaba apagado**: sin Postgres con Olist ni gateway de modelos | T029, T032–T035, T042, T049 |
| Redactar semillas, revisar candidatos y paráfrasis, revisar nombres de persona | Requiere **una persona**; un modelo no puede firmar la revisión | T030, T031, T034 |
| Segundo anotador y resolución de desacuerdos | Requiere **una segunda persona** (o el modelo, declarado como tal) | T050–T052 |
| Lectura y ajuste de la guía v1; ficha final; firma de `REVIEW.md` | Requiere **una persona** | T013, T059, T063 |
| `stats --write`, `validate` con todas las reglas y la prueba sobre los archivos reales | No existe todavía `intents.jsonl` | T060, T061 |
| Calidad de las paráfrasis en portugués (`research.md` R5) | Sin gateway no se pudo medir | T033 |

**Cifras del dataset** (ejemplos por clase e idioma y por partición, kappa, paráfrasis generadas / descartadas / rechazadas):
_pendientes_; se registran aquí al construir los datos.

## Hallazgos de la implementación

1. **Una prueba de LAB-116 chocaba con el trabajo local de LAB-117.** `test_there_is_no_csv_of_the_dataset_under_ml` busca
   cualquier `.csv` bajo `ml/`, y `ml/data/intents/work/` (ignorado por git) contiene las hojas de revisión. Se excluye **solo**
   ese directorio, con la razón anotada en la prueba; su propósito (que el repositorio no contenga CSV de Olist) se mantiene.
2. **El criterio «el mismo enmascarado que en las trazas» no se puede cumplir tal cual**: no existe (LAB-64 lo dejó fuera). Se
   implementó un enmascarado mínimo propio (`masking.py`) y la ficha lo declara.
3. **Se añadieron opciones a la CLI** que el contrato no listaba: `candidates --skip-golden`, y en `validate` los umbrales
   `--min-*` y `--sample-size`. El contrato (`contracts/cli-contract.md`) se actualizó. Por defecto `validate` **exige** guía,
   ficha, muestra e informe de acuerdo; `--no-docs` los omite.
4. **`LABELING-GUIDE.md` y `DATASHEET.md` son borradores del asistente.** La guía pasa la comprobación de apartados; la ficha
   también, pero con su bloque de cifras en «pendiente».
5. **La decisión sobre `off_topic` e `injection` (→ `charla`) es la opción recomendada, no confirmada expresamente**; está
   anotada en Assumptions del spec y en la guía.
