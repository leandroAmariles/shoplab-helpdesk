# Contrato: archivos de datos del análisis (TOML)

Dos archivos versionados en `ml/notebooks/data/`, legibles y editables por una persona. Se leen con `tomllib` de la
biblioteca estándar de Python 3.12.

## `order-tool-states.toml`

Los estados de pedido que mostrará la consulta de pedidos (`getOrderStatus`, LAB-41, aún sin implementar). Es lo **único** que
hay que actualizar cuando LAB-41 se implemente.

```toml
source = "Tabla «Estados del pedido» de data/kb/shipping-03-order-tracking.md; LAB-41 no define otro conjunto todavía"
states = [
  "created", "approved", "invoiced", "processing",
  "shipped", "delivered", "unavailable", "canceled",
]
```

| Campo | Regla |
|---|---|
| `source` | texto obligatorio: de dónde sale la lista (la suposición queda a la vista) |
| `states` | lista no vacía de cadenas únicas, en minúsculas y con la forma `[a-z_]+` |

Errores: archivo ausente, `states` vacía o con duplicados → `ValueError` con el campo afectado.

## `discrepancy-actions.toml`

La acción propuesta para cada hallazgo. Dos niveles de clave: **específica** (`"kind:subject"`) y **por tipo** (`"kind"`). La
búsqueda va de lo específico a lo genérico (`findings.resolve_action`).

```toml
# Por tipo: red de seguridad; un hallazgo nuevo nunca queda sin acción.
[value_not_in_policies]
target = "corpus"
action = "Decidir si el valor merece documentarse en la política de pagos o de seguimiento; si es ruido, anotarlo aquí."

[orphan_reference]
target = "new_task"
action = "Abrir una tarea para decidir si el cargador debe descartar o reportar los ítems que apuntan a productos inexistentes."

# Específica: curada a mano tras ver el análisis completo (ejemplo ilustrativo; los valores reales salen del análisis).
["value_not_in_policies:payment_type:not_defined"]
target = "corpus"
action = "Añadir a payments-01 una línea que explique el tipo `not_defined` y qué hacer cuando aparece."
```

| Campo | Regla |
|---|---|
| clave | `kind` (uno del catálogo de [data-model.md](../data-model.md)) o `kind:subject` |
| `target` | uno de `corpus`, `LAB-41`, `loader`, `new_task`, `analysis_note` |
| `action` | cadena no vacía, una o dos frases en español, en imperativo |

Reglas de validación:

- Debe existir una entrada **por tipo** para cada `kind` del catálogo (una prueba lo comprueba), de modo que
  `resolve_action` nunca falle por un tipo conocido.
- Una clave específica cuyo `kind` no exista en el catálogo es un error (evita erratas silenciosas).
- Las acciones específicas del repositorio se escriben **durante la implementación**, mirando los hallazgos reales de la
  ejecución con el dataset completo; el ejemplo de arriba es solo ilustrativo.

## Lo que no es

- No crea tareas en Jira ni edita el corpus: solo propone (spec, Assumptions: «Acción sobre discrepancias»).
