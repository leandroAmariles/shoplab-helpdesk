# Research: Dataset etiquetado de intenciones del cliente

Decisiones que el spec dejó abiertas o que condicionan el diseño. No queda ningún `NEEDS CLARIFICATION`. Lo marcado
**«revisar»** es una decisión mía que conviene que confirmes.

## R1. Dónde vive y cómo se organiza

- **Decisión**: segundo paquete (`intent_dataset`) en el proyecto uv `ml/` existente, y los datos en `ml/data/intents/`.
- **Razón**: LAB-118 leerá el dataset desde `ml/` (su paquete será `ml/intent`); el proyecto ya tiene la configuración de
  `ruff`, `pytest` y cobertura, y su job de CI (`python (ml)`) corre en cada cambio de `ml/**`, de modo que **no hace falta tocar
  `.github/workflows/`**. Los datos son archivos, no código: viven fuera de `src/`.
- **Alternativas**: un proyecto uv aparte (duplica CI y lockfile sin necesidad); poner los datos en `eval/` (el golden es la
  vara de medir del RAG; mezclar ahí un dataset de entrenamiento invita a confundirlos); `data/` raíz (es el corpus y el cargador).

## R2. Formatos de archivo

- **Decisión**: el dataset es **JSON Lines** UTF-8 sin BOM (`intents.jsonl`), una línea por ejemplo, claves en orden fijo y
  ordenado por `id`, como `eval/golden.jsonl`. La revisión manual se hace en **CSV** (`work/*.csv`, ignorado por git).
- **Razón**: JSONL da diffs línea a línea legibles y se lee con la biblioteca estándar; el CSV es lo que una persona puede
  revisar de verdad en una hoja de cálculo (columnas de decisión, filtros). El CSV se lee con `utf-8-sig` para tolerar el BOM
  que Excel añade.
- **Alternativas**: Parquet o DVC (LAB-122 versionará los datos; hoy sería adelantarse); revisar editando JSONL a mano (propenso
  a errores a 700 filas); YAML (más lento y ambiguo con acentos y dos puntos en los textos).

## R3. Enmascarado de datos personales

- **Hecho verificado**: no existe un enmascarado «de las trazas». LAB-64 lo dejó fuera por escrito (supuesto: datos públicos o de
  prueba) y no hay código de enmascarado en `backend/`.
- **Decisión**: `masking.mask_text` aplica, en este orden, sustituciones por patrón con marcas fijas y **idempotentes**
  (las marcas no casan con ningún patrón):

  | Orden | Dato | Marca | Nota |
  |---|---|---|---|
  | 1 | Enlaces (`http(s)://…`, `www.…`) | `[URL]` | antes que el correo para no partir una dirección |
  | 2 | Correos | `[EMAIL]` | |
  | 3 | Identificadores de 32 hexadecimales (pedido, cliente, reseña de Olist) | `[ID]` | |
  | 4 | CNPJ con formato (`00.000.000/0000-00`) | `[CNPJ]` | |
  | 5 | CPF con formato (`000.000.000-00`) | `[CPF]` | |
  | 6 | Código postal con guion (`00000-000`) | `[CEP]` | |
  | 7 | Teléfonos (con `+55`, paréntesis o separadores) y cualquier racha de 8 o más dígitos | `[PHONE]` | un CPF sin formato es indistinguible de un teléfono: se enmascara igual, con la marca de teléfono |

  `find_personal_data` aplica los mismos patrones al texto y devuelve las coincidencias; el validador falla si encuentra alguna.
  Se prefiere **sobreenmascarar**: una cifra larga que no era personal pierde su valor, un dato personal que se cuela no se puede
  deshacer.
- **Nombres de persona**: ningún patrón fiable los reconoce sin una lista de nombres o un modelo. Se cubren con una **revisión
  manual obligatoria**: `build` rechaza cualquier fila de Olist cuya columna `pii_checked` no sea `si`, y la persona revisora
  sustituye los nombres por `[NAME]` en `text_edit`. Se registra en `REVIEW.md`.
- **Cuándo se aplica**: **en la extracción**. `sources.read_reviews` enmascara cada comentario antes de devolverlo, así que el
  texto original nunca llega a un CSV, a un archivo de trabajo ni al repositorio (FR-020). Las paráfrasis y los textos
  redactados a mano pasan también por `mask_text`; si el enmascarado **cambia** la paráfrasis, el modelo inventó un dato
  personal y se descarta.
- **Alternativas**: Microsoft Presidio o spaCy NER (dependencias pesadas, modelos descargables y no deterministas en CI, y
  reconocen mal CPF/CEP brasileños sin reglas propias); enmascarar solo en el validador (el original ya estaría en disco).
- **Reutilización**: `masking.py` no depende de nada del resto del paquete, así que LAB-124 puede moverlo o importarlo para
  guardar texto enmascarado. Esta historia no lo conecta con las trazas ni con el backend.

## R4. Orígenes y cómo se obtienen los candidatos

- **Golden (`golden`)**: `eval/golden.jsonl` se lee como archivo JSON (no se importa `shoplab_eval`, que es otro proyecto). Mapeo
  **propuesto** por categoría, que la persona revisora confirma o corrige caso a caso:

  | Categoría del golden | Casos | Etiqueta propuesta |
  |---|---|---|
  | `payments`, `privacy`, `returns`, `shipping`, `warranty` | 88 | `politica` (incluye los 6 mixtos de LAB-114, por FR-009) |
  | `small_talk` | 12 | `charla` |
  | `off_topic` | 8 | `charla` (FR-007) |
  | `injection` | 12 | `charla` (FR-007) |

  Si una pregunta de conocimiento trata de un pedido concreto, un producto concreto o pide una persona, la revisión la cambia.
  Todos son español.
- **Reseñas de Olist (`olist_review`)**: lectura **de solo lectura** de `orders.order_reviews` con la conexión de `olist_eda.db`.
  Selección determinista (semilla): comentario no vacío, entre 20 y 280 caracteres, deduplicado por texto normalizado, una sola
  intención dominante (si no, se descarta en la revisión). Para que el trabajo humano no sea buscar una aguja en cien mil
  reseñas, cada candidato lleva una **pista** (`hint`) por palabras clave (p. ej. «entreg|chegou|prazo» → pedido; «defeito|
  qualidade» → producto; «reclam|procon|cancel|atendente» → escalado). **La pista no es una etiqueta**: la etiqueta la pone la
  persona y la columna queda vacía hasta entonces. Su objetivo es repartir los candidatos por clase.
- **Semillas redactadas a mano (`manual_seed`)**: el golden apenas tiene `pedido`, `producto` ni `escalado`, y las reseñas son
  retroalimentación posterior a la compra, no mensajes a un chat. Se redactan semillas (≈ 15–20 por clase débil e idioma) que
  imitan mensajes reales al chat; se añaden como filas nuevas al CSV de revisión.
- **Paráfrasis (`paraphrase`)**: ver R5. Pueden partir de cualquiera de los otros tres orígenes.
- **Objetivo de composición (orientativo, no un requisito)**: ~700 ejemplos para tener margen ante lo que la revisión rechace;
  ≥ 100 por clase y ≥ 40 por combinación clase-idioma, de modo que val y test no queden con 3 ejemplos por celda. El mínimo
  que **sí** se exige es el del spec (600 / 60 / 20).

## R5. Generación de paráfrasis con el modelo local

- **Decisión**: el cliente llama al **gateway** de LAB-84 (API compatible con OpenAI, `http://localhost:4000/v1`) con la clave
  virtual `eval` y el modelo lógico `judge` (por defecto `qwen2.5:7b`). Variables propias:
  `SHOPLAB_ML_LLM_BASE_URL`, `SHOPLAB_ML_LLM_MODEL` y `SHOPLAB_ML_LLM_API_KEY` (**sin valor por defecto**: sin clave, el comando
  se detiene diciendo cuál falta).
- **Por qué `judge` y no `chat`**: `chat` es `llama3.2:3b`; LAB-29 documentó que es flojo siguiendo instrucciones
  estructuradas, y aquí hay que producir español y portugués naturales y devolver JSON. `qwen2.5:7b` es el modelo que el gateway ya
  configura para el juez y es multilingüe; reutilizarlo evita añadir otro. **No he comprobado la calidad de sus paráfrasis en
  portugués**: se mide al generarlas (tasa de descarte y de rechazo en la revisión, que quedan en `validation.md`), y si es
  pobre se cambia `SHOPLAB_ML_LLM_MODEL` sin tocar código.
- **Por qué la clave `eval` (revisar)**: `keys.yaml` da una clave por consumidor y solo `eval` puede llamar a `judge`. Crear una
  clave `ml` exige tocar `keys.yaml`, `.env.example`, compose y el secreto del clúster, para un paso que se ejecuta una vez y
  solo en local. Con `eval` no hay cambios en `infra/`; el coste es que el gasto se imputa a ese consumidor (presupuesto de
  referencia de 5 USD/día, muy por encima de ~500 llamadas cortas). Si prefieres la clave propia, es una historia de infra
  aparte.
- **Cliente HTTP**: `urllib` de la biblioteca estándar, con la función de envío **inyectable**, como `shoplab_eval`. Sin `httpx`.
- **Prompt** (en `paraphrase.py`, versionado en el código): recibe el mensaje, la intención, el idioma de destino y `n`;
  pide **solo** un array JSON de `n` variantes que conserven la intención, cambien palabras y estructura, mantengan el registro
  de un cliente real (informal, con alguna falta) y **no incluyan nombres, correos, teléfonos ni números de pedido**. Para
  traducir, el idioma de destino es el otro; el resultado queda en el mismo grupo.
- **Filtros automáticos (antes de la revisión humana)**: se descarta la variante si el JSON es inválido, si está vacía o fuera
  de 3–280 caracteres, si es igual al original o a otra variante una vez normalizada, si su similitud de tokens con el original
  (Jaccard) es ≥ 0,85 sin cambiar de idioma, o si `mask_text` la cambia (dato personal inventado). Lo que queda va a
  `work/paraphrases.csv` con `decision` vacío.
- **Reproducibilidad**: se fija `temperature` y `seed` en la petición, pero **no se promete regenerar lo mismo**: el dataset
  publicado es la fuente de verdad. La ficha registra modelo, instrucciones (versión del prompt) y fecha.
- **Alternativas**: Ollama directo (`-f docker-compose.ollama-host.yml`; salta el gateway, su presupuesto y su trazabilidad);
  una API de nube (sale de la máquina, cuesta dinero y contradice «LLM local»); plantillas de reescritura sin modelo
  (paráfrasis pobres, sin traducción).

## R6. Grupos y partición estratificada

- **Decisión**: algoritmo propio en `splitting.py`.
  1. **Grupo efectivo**: unión (union-find) de los ejemplos que comparten `group_id` y de los que tienen el mismo texto
     normalizado (minúsculas, sin acentos, sin signos, espacios colapsados). Así un duplicado exacto nunca separa dos
     particiones (FR-014).
  2. **Estratos**: pares (etiqueta, idioma), 10 en total. El objetivo de cada partición por estrato es `round(total × ratio)`,
     con 70/15/15 por defecto y mínimo 1 ejemplo por estrato y partición.
  3. **Asignación**: los grupos ya asignados (algún miembro con `split`) quedan fijos. El resto se ordena por tamaño
     descendente (desempate por una mezcla con `random.Random(semilla)` y luego por clave) y cada grupo va a la partición con
     **mayor déficit relativo** sobre los estratos que contiene.
  4. **Comprobación**: si una partición queda sin alguna de las 5 clases o de los 2 idiomas, el comando falla con un mensaje
     (no se publica una partición con huecos).
- **Estabilidad (FR-015)**: `split` solo asigna lo que no tiene `split`; un ejemplo nuevo de un grupo ya asignado hereda la
  partición del grupo. Como `split` se guarda **en el archivo**, regenerar con la misma semilla da lo mismo y añadir ejemplos no
  mueve a los demás. Semilla por defecto: `117` (número de la historia), guardada como constante.
- **Alternativas**: `StratifiedGroupKFold` de scikit-learn (dependencia aún ausente; produce k pliegues iguales, no 70/15/15,
  y no es estable al añadir datos); calcular la partición en cada lectura a partir de un hash del `group_id` (estable, pero
  sin estratificación: con ~700 ejemplos deja particiones desequilibradas por clase e idioma); separar por hash y rebalancear
  (más complejo que el greedy y menos explicable).

## R7. Acuerdo entre anotadores (kappa de Cohen)

- **Decisión**: κ = (pₒ − pₑ) / (1 − pₑ), con pₒ el acuerdo observado y pₑ = Σₖ (aₖ/n)(bₖ/n). Se implementa a mano
  (≈ 15 líneas) y se prueba contra casos con valor conocido (acuerdo total = 1; el ejemplo clásico de libro de texto; azar ≈ 0);
  si pₑ = 1 se informa «no definido» en lugar de dividir por cero.
- **Muestra**: 50 ejemplos **estratificados** por clase (10 por clase) y, dentro de cada una, repartidos por idioma y origen;
  determinista con semilla; se **congela** en `annotations/agreement-sample.jsonl` con `label_a`, la etiqueta que tenía el
  ejemplo antes de la comparación. Se prefieren ejemplos de origen no `golden` cuando hay elección, y se reserva espacio para
  casos límite (los marca quien redacta la guía).
- **Anotador B**: recibe `work/annotator-b.csv` con `id, text, label` (la etiqueta vacía) y la guía; **no ve** `label_a`.
  Si no hay una segunda persona, el comando `annotate` pide la etiqueta al modelo local con la guía como instrucción
  (`judge`, sin ver `label_a`). El informe registra `annotator_b: person | local_model` y, si es modelo, cuál; la ficha y el
  validador lo muestran: **nunca se presenta un acuerdo con un modelo como acuerdo entre personas**.
- **Sesgo de anclaje**: por eso las etiquetas de Olist y de las semillas **no se preetiquetan con el modelo**; solo se usan
  pistas por palabras clave (R4). Si B fuera el mismo modelo que preetiquetó, el kappa estaría inflado.
- **Resolución**: `agreement` calcula el kappa **antes** de resolver y escribe `agreement.json`; los desacuerdos se resuelven a
  mano rellenando `final` y `reason` en la muestra; el validador exige `final` en los 50, `reason` en cada desacuerdo y que la
  etiqueta del dataset sea la `final`. El kappa guardado se recalcula en el validador para detectar manipulaciones.
- **Referencia**: κ ≥ 0,70 como objetivo, no como puerta. Si no se alcanza, se corrige la guía, se vuelve a etiquetar y se
  conserva el primer valor en la ficha.

## R8. Verificación y ficha

- **Decisión**: `validation.validate_dataset` reúne todas las reglas (contrato en `data-model.md`), **recoge todos los fallos**
  en lugar de parar en el primero (como `shoplab_eval.validation`) y cada `Issue` nombra el ejemplo y la regla. Corre de tres
  formas: `python -m intent_dataset validate`, funciones sueltas en las pruebas unitarias (un defecto provocado por regla,
  SC-009) y `test_real_dataset.py` sobre los archivos reales.
- **Ficha consistente con los datos**: `DATASHEET.md` tiene un bloque entre `<!-- stats:begin -->` y `<!-- stats:end -->` que
  **genera** `stats --write` (tamaño por clase e idioma, por partición, por origen, kappa y tipo de anotador B). El validador
  regenera el bloque y falla si difiere: así las cifras nunca se desactualizan. El resto de la ficha (origen, licencias,
  proceso, limitaciones) es texto escrito a mano; el validador solo comprueba que existan sus apartados obligatorios.
- **Por qué en pytest y no en un job nuevo**: el job `python (ml)` ya corre en cada PR que toque `ml/**`, y la validación no
  necesita red, base de datos ni modelo (FR-024). Un job nuevo solo añadiría tiempo.

## R9. El golden como evaluación del RAG frente al golden como datos de entrenamiento

- **Riesgo**: LAB-121 comparará el clasificador **sobre el golden**. Si las preguntas del golden están en `train`, esa
  comparación está inflada.
- **Decisión**: no se excluyen del dataset (la historia pide partir del golden) pero **quedan marcadas**: cada ejemplo cuyo grupo
  desciende de un caso del golden lleva `golden_id` (también sus paráfrasis), y `stats` publica en la ficha cuántos casos del
  golden hay en cada partición. LAB-121 podrá medir solo con los casos del golden que están en `test`, o excluirlos del
  entrenamiento. Se anota como limitación y como nota para LAB-121; el golden no se modifica (FR-025).

## R10. Licencia y lo que se versiona de Olist

- **Hecho**: el dataset de Olist es CC BY-NC-SA 4.0 (atribución, no comercial, compartir igual).
- **Decisión (revisar)**: se versionan **solo** los comentarios seleccionados, ya enmascarados y revisados (cientos de frases),
  nunca la tabla ni los CSV (FR-020), con atribución a Olist/Kaggle y las condiciones de uso declaradas en la ficha. El uso es
  didáctico y no comercial. Los ejemplos derivados (paráfrasis, traducciones) heredan esas condiciones, y la ficha lo dice.
  Si más adelante se quisiera un uso comercial, habría que sustituir los ejemplos `olist_review` por semillas propias; por eso
  `source` los identifica de forma inequívoca.
- **Alternativa más conservadora**: no versionar los comentarios y publicar solo las semillas y el golden (no hay conflicto de
  licencia, pero el dataset perdería casi todo el portugués real y tardaría más en llegar a 600). No la elijo, pero queda
  documentada.

## R11. Definiciones de las clases y reglas de desempate (borrador para la guía)

`LABELING-GUIDE.md` es la fuente de verdad; este es el punto de partida para escribirla:

- **Prioridad cuando un mensaje encaja en varias** (de mayor a menor): `escalado` > `pedido` > `producto` > `politica` >
  `charla`.
- `escalado`: pide o exige una persona («quiero hablar con un agente»), amenaza (acciones legales, denuncia, cancelar la
  cuenta) o expresa un problema que el bot no debe resolver solo. Gana aunque además hable de un pedido.
- `pedido`: se refiere a **un pedido concreto del propio cliente**, por su estado, su plazo, su contenido o una acción sobre él
  («¿dónde está mi pedido?», «cancelar mi compra de ayer»). Una pregunta de plazos **en general** es `politica`.
- `producto`: pregunta o queja sobre las **características de un artículo** o del catálogo (stock, talla, compatibilidad,
  precio, defecto). Si el defecto motiva una reclamación sobre un pedido concreto, gana `pedido`.
- `politica`: pregunta por una **regla general** de la tienda (pagos, privacidad, devoluciones, envíos, garantías) sin
  referirse a un pedido ni a un producto concreto.
- `charla`: saludos, agradecimientos, despedidas, «¿qué puedes hacer?», y (FR-007) lo ajeno a la tienda y los intentos de
  inyección: todo lo que el chat resuelve **sin recuperar documentos**.
- **Mixtos** (FR-009): una cortesía seguida de una petición se etiqueta por la petición. Una inyección escondida en una
  petición real se etiqueta por la petición; en una cortesía pura, `charla`.
- **Casos límite que la guía debe incluir con ejemplos en ambos idiomas**: `politica`/`pedido` («¿cuánto tarda el envío?» frente
  a «¿cuándo llega mi pedido?»), `producto`/`pedido` («llegó roto»), `pedido`/`escalado` («es la tercera vez que pregunto por
  mi pedido»), `charla`/`politica` en mensajes mixtos, y `charla` frente a una pregunta fuera de alcance.

## R12. Lo que se descarta a propósito

- **Detección automática de idioma** (`langdetect`, `fasttext`): dependencia y falsos positivos en mensajes de dos palabras. El
  idioma lo fija el origen (golden = español, reseñas = portugués) o el idioma pedido al generar; la revisión lo confirma.
- **Un ADR**: no hay decisión arquitectónica del producto (sin servicio, esquema ni dependencia nuevos); las decisiones de
  datos quedan aquí y en la ficha.
- **Cambios en CI, `infra/`, `backend/`, `eval/` o `data/`**: ninguno hace falta.
- **Cola de revisión de producción** (LAB-124), versionado con DVC (LAB-122), entrenamiento (LAB-118) y model card (LAB-125):
  fuera de alcance. El formato de `intents.jsonl` se diseña para que LAB-124 pueda añadir filas sin cambiar nada de lo existente.
