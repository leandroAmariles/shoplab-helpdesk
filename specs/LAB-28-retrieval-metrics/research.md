# Research: Métricas de retrieval

Decisiones que el plan necesitaba resolver. No quedan `NEEDS CLARIFICATION`. Cada una indica qué se decidió, por qué y qué
se descartó.

## R1. Cómo llega `eval/` a la búsqueda real

**Decisión**: un endpoint de solo lectura en `retrieval`, `GET /api/knowledge/search?query=…&limit=…`, que llama a
`SearchChunksServicePort.search` (el caso de uso que ya usa el chat a través de `KnowledgeSearchApi`). `eval/` lo consume por
HTTP.

**Por qué**: FR-002 pide ejercer la búsqueda real y prohíbe aproximarla. Esa búsqueda embebe la consulta con el modelo
configurado (Ollama, `bge-m3`) y compara solo con los vectores de ese modelo (ADR-0017). Un cliente externo solo puede
reproducirla entrando por el backend. Así, cuando la búsqueda cambie (híbrida con `tsvector`, otro modelo, otro troceado), la
medición lo refleja sin tocar `eval/`.

**Alternativas descartadas**:
- *Consultar PostgreSQL desde Python*: obliga a repetir el embedding de la consulta y la SQL de similitud, se desvía en
  silencio de la real y rompe ADR-0015 (un esquema por módulo, sin acceso ajeno).
- *Ejecutor en Java (prueba de integración o `CommandLineRunner` que escribe los rankings)*: usaría la búsqueda real, pero
  no se lanza «con un único comando desde `eval/`» (FR-016) y obligaría a cruzar dos lenguajes y dos procesos para algo que un
  GET resuelve.
- *Pasar por el chat y leer las citas*: mide la búsqueda más el umbral, el presupuesto y el modelo de lenguaje; mezcla lo que
  la historia quiere separar.

## R2. Exposición y seguridad del endpoint

**Decisión**: endpoint público dentro de la API del backend, de solo lectura, con `limit` acotado por el caso de uso (1–50) y
sin parámetros que escriban. Se registra en ADR-0024 como riesgo aceptado hasta que exista autenticación.

**Por qué**: el backend no tiene capa de seguridad todavía (no hay `SecurityWebFilterChain`; la autenticación llega con
Keycloak en fases posteriores). El contenido que devuelve es el del corpus sintético de políticas y FAQs, el mismo que el chat
muestra al responder, y `GET /api/documents` ya lista el catálogo sin autenticación. Una bandera de configuración para
apagarlo añadiría una opción que nadie pidió (Constitución VII); cuando llegue la autenticación, el endpoint hereda su regla
como el resto de `/api/**`.

**Alternativa descartada**: activarlo solo con un perfil de Spring. Evita la exposición en `dev`, pero impide medir contra el
ambiente desplegado, y la puerta de LAB-30 podría necesitarlo allí.

## R3. Unidad de medida: documento, no fragmento

**Decisión**: la lista recuperada se compone de ids de documento distintos (`Path(source).stem`, igual que
`expected_documents`), cada uno en la posición de su fragmento más cercano. `k` cuenta documentos.

**Por qué**: el dataset lista documentos esperados, no fragmentos, y así el troceado puede cambiar sin invalidarlo (LAB-26
mide ese cambio). El `source` de un fragmento es el nombre del archivo (`payments-01-payment-methods.md`); quitar la
extensión unifica Markdown y PDF del mismo documento (edge case de la spec).

**Consecuencia declarada**: hit@k a nivel de documento es más indulgente que a nivel de fragmento, porque varios fragmentos
del mismo documento ocupan una sola posición. El chat consume cuatro **fragmentos**; por eso el README de `eval/` lo explica y
LAB-29 mide si lo recuperado alcanza para responder.

## R4. Profundidad de la búsqueda

**Decisión**: la medición siempre pide `limit = 50` fragmentos (el máximo, `RetrievalConstants.MAX_SEARCH_LIMIT`), configurable
con `--depth` pero nunca mayor de 50, y calcula las métricas sobre los documentos distintos de esa lista.

**Por qué**: FR-008 exige no limitar k con el límite por defecto (5 fragmentos): con 5 fragmentos puede haber solo 2 o 3
documentos distintos, y k = 5 se quedaría sin sentido. Con 50 fragmentos y 24 documentos hay margen para llegar a cinco
documentos distintos casi siempre, y el MRR (FR-005) usa la lista completa. Un valor fijo evita que el resultado dependa de una
heurística de reintento.

**Alternativa descartada**: pedir `k × factor` fragmentos y reintentar si faltan documentos. Más fino, pero hace que dos
ejecuciones consulten cosas distintas y complica la explicación.

## R5. Por qué no se aplica el umbral de similitud del chat

**Decisión**: se mide el orden de la búsqueda, sin umbral, sin eliminación de texto repetido y sin presupuesto de caracteres.

**Por qué**: esa selección (`ContextSelector`, ADR-0018) pertenece al chat y es configurable (`shoplab.chat.rag.*`). Medir el
ranking por separado de la selección es lo que la historia pide («medir el retrieval por separado»); si el umbral descarta el
documento correcto, es un problema de selección que verá LAB-29 en la calidad de respuesta. El detalle incluye la posición del
primer acierto, de modo que quien quiera razonar sobre un umbral tiene el dato.

## R6. Definiciones de las métricas

**Decisión** (sea `E` el conjunto de documentos esperados del caso, `D` la lista recuperada y `D[:k]` sus k primeros):

| Métrica | Por caso | Agregado |
|---|---|---|
| hit rate@k | 1 si `E ∩ D[:k]` no es vacío, 0 si no | media sobre los casos medidos |
| recall@k | `|E ∩ D[:k]| / |E|` | media sobre los casos medidos |
| MRR | `1 / posición` del primer documento de `E` en `D` completo (1-indexada), 0 si ninguno | media sobre los casos medidos |

Los casos sin documentos esperados no tienen `E` y se excluyen (FR-006). Con `k = 1` y dos documentos esperados el recall
máximo es 0,5 (edge case de la spec): por eso se informan varios k.

**Alternativa descartada**: un MRR por documento esperado (rango del último). Es otra métrica (cercana a recall completo) y no
la que pide el criterio.

## R7. Formato y determinismo de los CSV

**Decisión**: dos archivos, `retrieval-summary.csv` y `retrieval-detail.csv`, escritos con el módulo `csv` de la biblioteca
estándar: UTF-8 sin BOM, `\n` como fin de línea, comillas solo cuando hacen falta, cabecera siempre. Los números se escriben con
cuatro decimales fijos (`0.8571`) y punto decimal; las listas de documentos se unen con `|`. Sin marcas de tiempo, sin rutas
absolutas, sin identificadores de ejecución.

**Por qué**: FR-013 pide archivos idénticos byte a byte. Los decimales fijos evitan diferencias de representación entre
plataformas; el orden de filas sale del dataset y de `CATEGORIES`, no de un `dict` o un `set`. `|` no puede aparecer en un id de
documento (`[a-z0-9-]`), así que no hay ambigüedad. Un solo CSV «ancho» por tipo basta para que LAB-30 compare y LAB-31
persista sin transformar (FR-018).

**Excel**: un CSV UTF-8 sin BOM puede mostrar mal las tildes si se abre con doble clic; la documentación indica importarlo
(«Datos → Desde texto/CSV, UTF-8»). Se descarta el BOM porque estropea el nombre de la primera columna en lectores estrictos
(`csv.DictReader` de LAB-30) y el dataset golden de LAB-27 también va sin BOM.

## R8. Fallos que invalidan la medición

**Decisión**: `MeasurementError` (tipada, con mensaje en español para quien ejecuta) en estos casos, todos con código de salida
`1` y **sin escribir CSV** (se escribe al final, solo con la medición completa):

1. Dataset inválido (reutiliza `InvalidGoldenError` de LAB-27 y reproduce sus fallos).
2. Backend no alcanzable o sin responder en el tiempo límite (10 s por búsqueda, configurable).
3. `GET /api/documents` no incluye, con estado `INDEXED`, algún documento esperado por el dataset: el mensaje los lista (con
   su estado si existen: `PENDING`, `ERROR`). Esto es **antes** de buscar (comprobación previa).
4. Una búsqueda devuelve error HTTP (4xx/5xx) o un cuerpo ilegible: se informa el id del caso y el estado.
5. El modelo de embeddings cambia entre respuestas de la misma ejecución (reindexado a mitad de medición).
6. `k` inválido (no entero, ≤ 0 o mayor que la profundidad): error de uso antes de consultar nada.

Una búsqueda correcta que no devuelve el documento esperado **no** es un error: es un cero (FR-015).

## R9. Cliente HTTP

**Decisión**: `urllib.request` de la biblioteca estándar, sin sesiones ni reintentos; la URL base sale de `--base-url`, de la
variable `SHOPLAB_EVAL_BASE_URL` o de `http://localhost:8080` (puerto del backend en `application.yml`).

**Por qué**: son 70 peticiones secuenciales de lectura; `httpx` o `requests` añadirían una dependencia a `eval/` para nada, y
un reintento escondería justo los fallos puntuales que FR-014 quiere ver. Las pruebas levantan un `http.server` en un hilo, sin
`unittest.mock` sobre `urllib`, de modo que se ejerce el código real de red.

**Alternativa descartada**: `httpx` o `requests`. Cada dependencia nueva entra en el lockfile y en el escaneo de CVE de CI
para una funcionalidad trivial.

## R10. Dónde se guardan los resultados

**Decisión**: `eval/results/` por defecto (`--out` para cambiarlo), con `.gitkeep` y el resto ignorado por git.

**Por qué**: es el resultado de una ejecución local contra un índice concreto; versionarlo generaría diffs en cada prueba. La
línea base versionada y los umbrales son de LAB-30, que decidirá qué guardar y dónde. Se descarta `notebooks/results/`: la
Constitución reserva `notebooks/` a experimentos.

## R11. Orden de las consultas y paralelismo

**Decisión**: secuencial, en el orden del dataset.

**Por qué**: 70 búsquedas locales tardan menos de un minuto; la concurrencia añadiría presión sobre Ollama (que embebe una
consulta por petición) y haría el fallo parcial más difícil de explicar. No hay objetivo de latencia que lo justifique.

## R12. Riesgo: búsqueda aproximada

**Riesgo**: el índice HNSW de pgvector es aproximado, así que el orden podría variar entre construcciones del índice.
**Mitigación**: con 24 documentos y unas pocas centenas de fragmentos el recall del índice es prácticamente total y el orden es
estable; el determinismo que se garantiza es el de una misma base indexada (la que la spec pide), y si una reindexación cambia
algo, el CSV lo mostrará, que es lo que se quiere detectar. Si apareciera ruido, LAB-30 puede fijar `hnsw.ef_search` o medir
con tolerancia; no se resuelve aquí.
