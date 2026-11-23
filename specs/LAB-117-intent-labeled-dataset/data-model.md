# Data Model: Dataset etiquetado de intenciones del cliente

Entidades del spec con sus campos y reglas. El formato exacto de los archivos está en
[contracts/dataset-format.md](contracts/dataset-format.md).

## Constantes (`constants.py`)

| Constante | Valor | De dónde sale |
|---|---|---|
| `LABELS` | `politica`, `producto`, `pedido`, `escalado`, `charla` | FR-001 |
| `LANGUAGES` | `es`, `pt` | FR-002 |
| `SOURCES` | `golden`, `olist_review`, `manual_seed`, `paraphrase` | FR-004 |
| `SPLITS` | `train`, `val`, `test` | FR-013 |
| `DatasetRules.min_examples` | 600 | FR-002 |
| `DatasetRules.min_per_label` | 60 | FR-003 |
| `DatasetRules.min_per_label_language` | 20 | FR-003 |
| `DatasetRules.min_text_chars` / `max_text_chars` | 2 / 500 | límites de sentido común; las reseñas se seleccionan ≤ 280 |
| `DatasetRules.ratios` | 0,70 / 0,15 / 0,15 | supuesto del spec |
| `DatasetRules.seed` | 117 | R6 |
| `DatasetRules.agreement_sample_size` | 50 | FR-011 |
| `DatasetRules.kappa_reference` | 0,70 | R7 (referencia, no puerta) |

## Ejemplo (`Example`)

Una línea de `intents.jsonl`.

| Campo | Tipo | Regla |
|---|---|---|
| `id` | texto `I` + 4 dígitos | Único, **estable**: no cambia nunca aunque se edite el texto o se añadan otros (como `G001` en el golden) |
| `text` | texto | Ya enmascarado; sin saltos de línea; 2–500 caracteres; no vacío tras `strip` |
| `label` | uno de `LABELS` | Exactamente una |
| `language` | `es` \| `pt` | Idioma predominante del texto |
| `source` | uno de `SOURCES` | De dónde viene |
| `group_id` | texto | Grupo de origen: el original y todas sus paráfrasis. `G###` (golden), `R` + 8 hex (reseña; hash del `review_id`, nunca el identificador real), `S###` (semilla) |
| `parent_id` | `id` \| `null` | Solo en `paraphrase`: el ejemplo del que parte. Debe existir y tener el mismo `group_id` |
| `golden_id` | `G###` \| `null` | Caso del golden del que **desciende** el grupo; `source: golden` ⇒ `golden_id == group_id`; una paráfrasis hereda el del padre |
| `split` | `train` \| `val` \| `test` | Asignada por `split`; se conserva |
| `reviewed` | booleano | MUST ser `true` en todo ejemplo publicado (FR-006) |

Reglas entre campos:

- Una paráfrasis tiene **la misma `label` que su padre**. Si al reescribir cambia de clase, se rechaza en la revisión (si es un
  mensaje válido de otra clase, se redacta como `manual_seed`).
- Una paráfrasis puede cambiar de `language` respecto a su padre (traducción); sigue en su grupo.
- `text` no contiene coincidencias de `find_personal_data` (FR-019).
- Dos ejemplos con el mismo texto normalizado pertenecen al mismo grupo efectivo (R6) y por tanto a la misma partición.

## Intención (`label`)

| Valor | Definición corta | Fuente de verdad |
|---|---|---|
| `politica` | regla general de la tienda | `LABELING-GUIDE.md` |
| `producto` | características de un artículo o del catálogo | idem |
| `pedido` | un pedido concreto del propio cliente | idem |
| `escalado` | pide o necesita una persona | idem |
| `charla` | lo que el chat resuelve sin recuperar documentos: cortesías, lo ajeno a la tienda e inyección | idem |

Prioridad entre clases: `escalado` > `pedido` > `producto` > `politica` > `charla` (R11).

## Grupo de origen

Unidad que la partición reparte. **Grupo efectivo** = unión de los ejemplos con el mismo `group_id` y de los que comparten
texto normalizado. Invariante: un grupo efectivo está **entero** en una sola partición.

## Candidato de revisión (`work/*.csv`, no versionado)

Fila de trabajo que una persona acepta, edita o rechaza antes de entrar al dataset.

| Campo | Regla |
|---|---|
| `candidate_id` | Único dentro del archivo |
| `source`, `group_id`, `parent_id`, `golden_id`, `language` | Como en el ejemplo |
| `text` | Ya enmascarado desde la extracción o la generación |
| `proposed_label` | Solo golden (mapeo de categoría) y paráfrasis (la del padre); vacío en reseñas y semillas |
| `hint` | Pista por palabras clave en reseñas; **no es una etiqueta** |
| `decision` | `accept` \| `reject` \| vacío (pendiente: se ignora y se cuenta) |
| `label` | La etiqueta que pone la persona; obligatoria si `accept` |
| `text_edit` | Si no está vacío, sustituye a `text` (corregir una falta, enmascarar un nombre) y vuelve a pasar por `mask_text` |
| `pii_checked` | `si` obligatorio en `olist_review` para aceptar (FR-018) |
| `note` | Libre |

### Estados de un ejemplo

```
candidato ──(revisión: accept)──► ejemplo reviewed=true, sin split
                                    │
                                    ├─(split)──► ejemplo con split
                                    └─(muestra de 50 + acuerdo)──► etiqueta final confirmada
candidato ──(reject / pendiente)──► no entra
```

`build` solo escribe ejemplos aceptados y revisados; un ejemplo sin `split` no pasa `validate` (el paso previo a publicar es
`split`).

## Muestra doble-etiquetada (`annotations/agreement-sample.jsonl`)

| Campo | Regla |
|---|---|
| `id` | Existe en `intents.jsonl`; 50 ids distintos |
| `label_a` | Etiqueta del dataset en el momento de congelar la muestra (antes de resolver) |
| `label_b` | Etiqueta del segundo anotador, a ciegas; la rellena `agreement` |
| `final` | Etiqueta tras resolver; obligatoria en los 50. Si `label_a == label_b`, es esa |
| `reason` | Obligatoria cuando `label_a != label_b`: por qué se eligió `final` (y, si hizo falta, qué regla se añadió a la guía) |

Reglas: la muestra tiene las 5 clases (10 por clase) y los 2 idiomas; la etiqueta de cada ejemplo en `intents.jsonl` coincide
con su `final`.

## Informe de acuerdo (`annotations/agreement.json`)

`n`, `kappa` (o `null` con `kappa_note: "undefined: expected agreement is 1"`), `observed_agreement`, `expected_agreement`,
`confusion` (matriz `label_a × label_b`), `disagreements` (número), `annotator_b` (`person` | `local_model`),
`annotator_b_model` (texto o `null`), `computed_on` (fecha), `guide_revision` (texto libre). El validador recalcula `kappa`
desde `agreement-sample.jsonl` y exige que coincida.

## Ficha del dataset (`DATASHEET.md`)

Apartados obligatorios (el validador comprueba los encabezados): **Origen**, **Composición** (bloque generado), **Licencias**,
**Generación y revisión de paráfrasis**, **Acuerdo entre anotadores**, **Enmascarado**, **Particiones**, **Limitaciones**,
**Uso previsto y no previsto**. El bloque entre `<!-- stats:begin -->` y `<!-- stats:end -->` contiene:

- total, por clase × idioma, por partición × clase, por origen;
- número de casos del golden por partición (R9);
- kappa, `n`, número de desacuerdos y tipo de anotador B.

Las limitaciones incluyen, como mínimo (FR-022): dominio de los comentarios de Olist, peso de las paráfrasis sintéticas,
tamaño del dataset, solape con el golden, y que `charla` es más amplia que la cortesía.

## Guía de etiquetado (`LABELING-GUIDE.md`)

Un apartado por clase (definición, incluye, excluye, ≥ 2 ejemplos por idioma), la **prioridad** entre clases y una sección
«Casos límite» con los cuatro pares de FR-010 y una regla de desempate explícita cada uno. El validador comprueba que existen
los cinco apartados y la sección de casos límite.

## Reglas del validador (`validation.py`)

Cada regla emite `Issue(rule, example_id | None, message)`; `validate` devuelve todas.

| Regla | Comprueba | Requisito |
|---|---|---|
| `schema` | Claves exactas, tipos, sin claves desconocidas | FR-002 |
| `id` | Formato y unicidad | — |
| `label`, `language`, `source`, `split` | Valores permitidos | FR-001, FR-002, FR-004, FR-013 |
| `text` | Longitud y no vacío | — |
| `reviewed` | `true` en todos | FR-006 |
| `provenance` | `golden_id`/`parent_id` coherentes (existen, mismo grupo, misma etiqueta) | FR-005 |
| `min_total`, `min_label`, `min_label_language` | 600 / 60 / 20 | FR-002, FR-003 |
| `group_split` | Ningún grupo efectivo en más de una partición | FR-014 |
| `duplicates` | Ningún texto normalizado en dos particiones | FR-014 |
| `split_coverage` | Cada partición tiene las 5 clases y los 2 idiomas | FR-016 |
| `pii` | Cero coincidencias de `find_personal_data` | FR-019 |
| `agreement` | Muestra íntegra y kappa recalculado coincide | FR-011, FR-012 |
| `datasheet` | Apartados presentes y bloque de cifras igual al regenerado | FR-021, FR-023 |
| `guide` | Apartados presentes | FR-010 |
| `golden_ids` | Cada `golden_id` existe en `eval/golden.jsonl` (solo cuando se pasa la ruta; lo hace la prueba real) | FR-005 |

`charla` sin petición real (FR-009) y la confirmación de que un nombre no se coló **no se pueden comprobar por patrón**: las
cubre la revisión manual y quedan registradas en `REVIEW.md`.
