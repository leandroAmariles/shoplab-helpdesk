# Contrato: CLI de `intent_dataset`

`uv run python -m intent_dataset <comando>` desde `ml/`. Los comandos de preparación (`candidates`, `paraphrase`, `annotate`)
son locales y no se ejecutan en CI; `split`, `sample`, `agreement`, `stats`, `build` y `validate` son deterministas.

## Códigos de salida

| Código | Significado |
|---|---|
| `0` | Hecho (o, en `validate`, sin incidencias) |
| `1` | El dataset incumple reglas (`validate`), o una revisión está incompleta (`build`); lista cada incidencia con su regla y ejemplo |
| `2` | Uso o entorno: argumento inválido, archivo ausente, base de datos o gateway inaccesibles, variable obligatoria sin definir |

Los mensajes de error están en español, dicen qué falta y cómo resolverlo, y **nunca** incluyen la clave del gateway ni la
contraseña de la base.

## Comandos

### `candidates`

Extrae candidatos y los enmascara al leerlos.

```
candidates [--golden eval/golden.jsonl] [--reviews 400] [--seed 117] [--out work/candidates.csv]
           [--skip-golden] [--skip-reviews] [--force]
```

- Golden: 120 filas con `proposed_label` por categoría (R4).
- Reseñas: hasta `--reviews` comentarios seleccionados de forma determinista con `hint`; usa `POSTGRES_URL`, `POSTGRES_USER` y
  `POSTGRES_PASSWORD` (los de `olist_eda`). `--skip-reviews` evita tocar la base.
- No pisa un `--out` existente salvo con `--force` (puede contener decisiones de revisión).
- Salida: el CSV y un resumen por origen y pista.

### `paraphrase`

Genera variantes con el modelo local a partir de ejemplos ya publicados.

```
paraphrase [--per-parent 2] [--translate] [--labels politica,pedido,…] [--limit N] [--seed 117] [--out work/paraphrases.csv]
```

- Padres: los ejemplos de `intents.jsonl` de `golden`, `olist_review` y `manual_seed` (una paráfrasis no se parafrasea).
- `--translate` pide además la variante en el otro idioma.
- Entorno: `SHOPLAB_ML_LLM_API_KEY` (obligatoria), `SHOPLAB_ML_LLM_BASE_URL` (def. `http://localhost:4000/v1`),
  `SHOPLAB_ML_LLM_MODEL` (def. `judge`).
- Aplica los filtros de R5 y escribe `decision` vacío; informa de cuántas variantes se descartaron y por qué.

### `build`

Incorpora al dataset las filas aceptadas de uno o más CSV revisados.

```
build work/candidates.csv [work/paraphrases.csv …] [--dataset intents.jsonl] [--dry-run]
```

- Solo entran filas `accept` con `label` válida, y, en `olist_review`, `pii_checked = si`.
- Vuelve a enmascarar `text`/`text_edit`; rechaza una fila cuyo texto normalizado ya existe en el dataset.
- Asigna `id` consecutivos (`I0001`…), `reviewed = true`; los ejemplos nuevos quedan **sin `split`**.
- Falla con código `1` y no escribe nada si alguna fila aceptada es inválida (se listan todas).
- Resume: aceptadas, rechazadas, pendientes, duplicadas, por origen y clase.

### `split`

Asigna la partición a los ejemplos que no la tienen.

```
split [--dataset intents.jsonl] [--seed 117] [--ratios 70/15/15]
```

- No cambia ningún `split` existente (FR-015); un ejemplo nuevo de un grupo ya asignado hereda su partición.
- Falla (`1`) sin escribir si una partición quedaría sin alguna clase o idioma, o si un grupo efectivo ya tiene miembros en
  particiones distintas.
- Misma entrada y misma semilla ⇒ mismo resultado.

### `sample`

Congela la muestra de acuerdo y prepara la hoja del segundo anotador.

```
sample [--size 50] [--seed 117] [--force]
```

- Escribe `annotations/agreement-sample.jsonl` con `label_a` y `label_b = null`, y `work/annotator-b.csv` sin etiquetas.
- Se niega a sobrescribir una muestra existente sin `--force` (cambiarla invalida el acuerdo ya medido).

### `annotate`

Alternativa cuando no hay una segunda persona: el modelo local rellena `annotator-b.csv`.

```
annotate [--sheet work/annotator-b.csv] [--guide LABELING-GUIDE.md]
```

- Usa la guía como instrucción y **no** recibe `label_a`; mismo entorno que `paraphrase`.
- Marca el resultado como `local_model` para que `agreement` lo registre.

### `agreement`

Calcula el kappa **antes** de resolver los desacuerdos.

```
agreement --annotator-b work/annotator-b.csv --kind person|local_model [--model judge]
```

- Rellena `label_b` en la muestra, escribe `annotations/agreement.json` y lista cada desacuerdo (`id`, `label_a`, `label_b`).
- Si ya hay `final` en la muestra, no recalcula el kappa original: se niega sin `--force`.
- Tras resolver, quien revisa rellena `final` y `reason`; `validate` comprueba el resto.

### `stats`

```
stats [--write]
```

- Sin `--write` imprime las cifras (Markdown). Con `--write` reescribe el bloque entre las marcas de `DATASHEET.md`.

### `validate`

```
validate [--dataset …] [--golden eval/golden.jsonl] [--no-docs] [--sample-path …] [--report …] [--guide …]
         [--datasheet …] [--min-examples 600] [--min-per-label 60] [--min-per-label-language 20] [--sample-size 50]
```

- Ejecuta todas las reglas de [data-model.md](../data-model.md) y devuelve `0` o `1`, imprimiendo **todas** las incidencias
  ordenadas (regla, ejemplo, mensaje) por la salida estándar y el recuento por la de error. Sin red, sin base de datos, sin
  modelo (FR-024). Un dataset que no existe devuelve `2`.
- Por defecto **exige** la guía, la ficha, la muestra y el informe de acuerdo: si falta alguno, es una incidencia (`docs`).
  `--no-docs` los omite a todos (útil mientras se construye).
- `--golden` activa `golden_ids`. Los `--min-*` y `--sample-size` existen para validar un dataset a medio construir o con
  datos de prueba; los valores por defecto son los del spec y son los que usa CI.

## Variables de entorno

| Variable | Defecto | Uso |
|---|---|---|
| `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | los de `olist_eda` | `candidates` (lectura de reseñas) |
| `SHOPLAB_ML_LLM_API_KEY` | **sin valor** | `paraphrase`, `annotate`; obligatoria, nunca se imprime |
| `SHOPLAB_ML_LLM_BASE_URL` | `http://localhost:4000/v1` | Gateway de modelos |
| `SHOPLAB_ML_LLM_MODEL` | `judge` | Modelo lógico del gateway |
