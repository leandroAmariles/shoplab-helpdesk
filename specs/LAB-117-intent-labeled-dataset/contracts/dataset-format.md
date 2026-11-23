# Contrato: formatos de archivo del dataset

Lo que LAB-118 y LAB-124 pueden suponer de los archivos. Un cambio incompatible aquí es un cambio de contrato y se anuncia en
la ficha. Rutas relativas a `ml/data/intents/`.

## `intents.jsonl`

JSON Lines, UTF-8 **sin BOM**, un objeto por línea, sin comentarios ni cabecera, ordenado por `id`, saltos de línea `\n`. Se
toleran líneas vacías al leer, no al escribir. Se rechazan las claves desconocidas.

```json
{"id": "I0001", "text": "Mi boleto venció sin que lo pagara, ¿qué puedo hacer?", "label": "politica", "language": "es", "source": "golden", "group_id": "G001", "parent_id": null, "golden_id": "G001", "split": "train", "reviewed": true}
{"id": "I0412", "text": "meu pedido ainda não chegou e já passou do prazo", "label": "pedido", "language": "pt", "source": "olist_review", "group_id": "R3f9a1c2e", "parent_id": null, "golden_id": null, "split": "val", "reviewed": true}
{"id": "I0413", "text": "mi pedido no ha llegado y ya pasó la fecha prometida", "label": "pedido", "language": "es", "source": "paraphrase", "group_id": "R3f9a1c2e", "parent_id": "I0412", "golden_id": null, "split": "val", "reviewed": true}
```

Las claves van siempre en este orden: `id`, `text`, `label`, `language`, `source`, `group_id`, `parent_id`, `golden_id`, `split`,
`reviewed`. Las reglas de cada campo están en [data-model.md](../data-model.md).

**Garantías para quien lo consume**

- Todo ejemplo tiene `split`; leer `train`, `val` o `test` basta, sin reordenar ni volver a partir.
- Ningún grupo de origen ni texto normalizado cruza particiones: entrenar con `train` y evaluar con `test` no tiene fuga.
- `golden_id` no nulo ⇒ el ejemplo desciende del golden; excluirlos (o medir solo con los de `test`) deja una comparación
  limpia sobre el golden.
- Los `id` no se reutilizan ni se renumeran; las filas nuevas (LAB-124) se añaden con el siguiente `id` y `split` asignado por
  `split`.

## `annotations/agreement-sample.jsonl`

Una línea por caso de la muestra (50), ordenada por `id`:

```json
{"id": "I0207", "label_a": "pedido", "label_b": "escalado", "final": "escalado", "reason": "Pide hablar con una persona: gana escalado (prioridad de la guía)."}
{"id": "I0031", "label_a": "politica", "label_b": "politica", "final": "politica", "reason": null}
```

`label_b` es `null` hasta que se ejecuta `agreement`. `reason` es obligatorio solo si `label_a != label_b`.

## `annotations/agreement.json`

```json
{
  "n": 50,
  "kappa": 0.82,
  "observed_agreement": 0.88,
  "expected_agreement": 0.2,
  "disagreements": 6,
  "annotator_b": "local_model",
  "annotator_b_model": "judge",
  "confusion": {"politica": {"politica": 9, "pedido": 1}, "pedido": {"pedido": 9, "escalado": 1}},
  "computed_on": "2026-11-30",
  "kappa_note": null
}
```

`confusion[a][b]` cuenta los casos con `label_a = a` y `label_b = b`; las celdas en cero se omiten. Los valores del ejemplo son
ilustrativos.

## `work/candidates.csv` y `work/paraphrases.csv` (no versionados)

CSV UTF-8 (se lee con `utf-8-sig`), cabecera obligatoria, separador coma, comillas dobles:

```
candidate_id,source,group_id,parent_id,golden_id,language,text,proposed_label,hint,decision,label,text_edit,pii_checked,note
C0001,golden,G001,,G001,es,"Mi boleto venció sin que lo pagara, ¿qué puedo hacer?",politica,,,,,,
C0412,olist_review,R3f9a1c2e,,,pt,"meu pedido ainda não chegou e já passou do prazo",,pedido,,,,,
```

- `decision` ∈ `accept` | `reject` | vacío (pendiente).
- `label` es obligatoria si `accept` y debe ser una de las cinco.
- `pii_checked` = `si` obligatorio para aceptar una fila `olist_review`.
- `text_edit` no vacío sustituye a `text` y se vuelve a enmascarar.
- Una fila sin `decision` no entra y se cuenta en el resumen de `build`.

## `work/annotator-b.csv` (no versionado)

```
id,text,label
I0207,"es la tercera vez que pregunto por mi pedido",
```

El segundo anotador solo rellena `label`. No contiene ninguna etiqueta del primero.

## Bloque de cifras de `DATASHEET.md`

Entre dos marcas HTML que `stats --write` reescribe y `validate` comprueba byte a byte contra lo que regeneraría:

```markdown
<!-- stats:begin -->
| | es | pt | total |
|---|---|---|---|
| politica | … | … | … |
…
<!-- stats:end -->
```

Todo lo que está fuera de las marcas es texto escrito a mano y no se toca.

## Compatibilidad

- Añadir una **clave** a `intents.jsonl` es un cambio de contrato (el lector rechaza claves desconocidas): se versiona en la
  ficha y actualiza a LAB-118 y LAB-124.
- Añadir una **etiqueta** no es posible sin una historia nueva: las cinco son fijas.
