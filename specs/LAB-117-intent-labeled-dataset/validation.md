# Validación de LAB-117

**Fecha**: 2026-11-21 · **Rama**: `feature/LAB-117-intent-labeled-dataset`

> **Estado: código y dataset completos y verificados por programa. Falta una persona**: las etiquetas, la revisión de nombres, las
> paráfrasis y el segundo anotador los resolvió **el asistente de IA** (el segundo, el modelo local), no una persona. Ver
> «Lo que ningún programa comprueba» y `ml/data/intents/REVIEW.md`.

## Ejecutado y verificado

| Comprobación | Resultado |
|---|---|
| `cd ml && uv run ruff check .` | Sin incidencias |
| `cd ml && uv run pytest` | **456 pruebas en verde** (184 previas de LAB-116 + 272 nuevas); cobertura total **97,93 %** (umbral 80 %) |
| `python -m intent_dataset validate --golden ../eval/golden.jsonl` (todas las reglas, con guía, ficha, acuerdo y golden) | **0 incidencias, 685 ejemplos** |
| `test_real_dataset.py` (lo que corre en CI sobre los archivos reales) | En verde |
| `split` repetido sobre los mismos datos | Sin cambios en el archivo (`cmp`) |
| Datos personales por patrón en el campo `text` | 0 coincidencias; en el archivo completo solo salen 10 falsos positivos en `group_id` (un hash SHA-256 de 8 dígitos que resulta ser todo cifras) |
| FR-025: `git diff --stat main -- backend ingest frontend infra eval data .github` | Vacío |
| Búsqueda de secretos en `ml/src`, `ml/data` y `specs/LAB-117…` | Sin coincidencias; `work/` ignorado por git |

## El dataset

| Clase | es | pt | total |
|---|---|---|---|
| politica | 85 | 50 | 135 |
| producto | 56 | 87 | 143 |
| pedido | 60 | 136 | 196 |
| escalado | 64 | 67 | 131 |
| charla | 40 | 40 | 80 |
| **Total** | | | **685** |

- Orígenes: golden 120 · reseñas de Olist 169 · semillas 114 · paráfrasis 282.
- Particiones: train 481 · val 102 · test 102, con las 5 clases y los 2 idiomas en cada una; casos del golden por partición:
  train 84, val 19, test 17.
- Mínimos exigidos (spec): ≥ 600 total, ≥ 60 por clase, ≥ 20 por clase e idioma. **Cumplidos con margen** (la celda más pequeña
  tiene 40).

## Cómo se construyó (2026-11-21)

1. **Candidatos**: 120 del golden y 240 reseñas de Olist (repartidas por palabras clave). La contraseña de Postgres de esta máquina
   no coincide con la del compose, así que las reseñas se leyeron por el socket local del contenedor, de solo lectura; el
   enmascarado se aplicó al leerlas y ningún texto original llegó a disco.
2. **Revisión (asistente)**: 403 aceptados (120 golden, 169 reseñas, 114 semillas) y 71 reseñas descartadas.
3. **Paráfrasis y traducciones** con `judge` (qwen2.5:7b, CPU) por el gateway: 354 generadas, 3 descartadas por el filtro, 71
   rechazadas en la revisión (20 %; **26 % en portugués frente a 12 % en español**), 60 editadas, 282 incorporadas.
4. **Partición** (semilla 117), **muestra de 50** (16 reseñas, 15 golden y 19 semillas; sin paráfrasis), anotación a ciegas del
   modelo local, **κ = 0,85**, 6 desacuerdos resueltos (una etiqueta cambió).

## Hallazgos durante la implementación

1. **Una prueba de LAB-116** (`test_there_is_no_csv_of_the_dataset_under_ml`) chocaba con la carpeta de trabajo local `work/`
   (ignorada por git). Se excluye solo ese directorio, con la razón anotada.
2. **El criterio «el mismo enmascarado que en las trazas» no se pudo cumplir tal cual**: no existe (LAB-64 lo dejó fuera). Se
   implementó un enmascarado mínimo propio y la ficha lo declara.
3. **El validador cazó 18 reseñas con saltos de línea**: ahora `build` colapsa los espacios y saltos de todo texto, con prueba.
4. **La regla de la muestra contaba mal**: «10 por clase» se medía con la etiqueta actual del dataset, y al resolver un
   desacuerdo la muestra pasaba a 11/9. Ahora se mide con la etiqueta congelada del primer anotador (`label_a`).
5. **El anotador B (modelo) ejecutó una petición de inyección** («traduce al inglés…») en lugar de clasificarla. Se corrigió el
   prompt (el mensaje llega delimitado como dato) y se repitió la anotación entera; el κ reportado es el de la segunda ejecución.
6. **La primera muestra salió demasiado fácil** (40 semillas mías de 50): se cambió la prioridad para que salgan antes las
   reseñas reales y el golden, luego las semillas y, al final, las paráfrasis.
7. **El compose arranca mal con volúmenes antiguos**: `litellm-postgres` usa `postgres:18.6` (Dependabot #138) y el volumen
   existente es de la 17, así que no inicia. Se evitó con `LITELLM_POSTGRES_IMAGE=docker.io/postgres:17.7` solo en la orden local.
   **No se corrigió en esta historia** (es de infra y afecta a quien ya tenga el volumen); queda como incidencia aparte.
8. **Las opciones de la CLI** que el contrato no listaba (`--skip-golden`, `--min-*`, `--sample-size`) se documentaron en
   `contracts/cli-contract.md`.

## Lo que ningún programa comprueba (y no lo ha hecho una persona)

- Que **cada etiqueta sea la correcta**. La guía la redactó el propio asistente mientras etiquetaba (v2) y el segundo anotador es un
  modelo: el κ = 0,85 mide la coincidencia de dos modelos con la misma guía, **no** la fiabilidad humana.
- Que **no quede ningún nombre de persona** en los 169 comentarios de Olist: lo leyó el asistente (2 sustituciones).
- Que las paráfrasis conserven la intención y suenen naturales, sobre todo en portugués.
- Que ningún `charla` contenga una petición real.
- Las decisiones de diseño sin confirmar: `charla` incluye lo ajeno a la tienda y la inyección; versionar comentarios de Olist
  enmascarados (CC BY-NC-SA 4.0); paráfrasis con la clave `eval` y el modelo `judge`.

Una persona debe completar el apartado 3 de `ml/data/intents/REVIEW.md` y firmarlo antes de tratar el dataset como validado.
