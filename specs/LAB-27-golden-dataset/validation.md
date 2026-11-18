# Validación: dataset golden de preguntas y respuestas

Resultados de ejecutar [quickstart.md](quickstart.md) al implementar LAB-27 (2026-11-16). Lo que depende de una persona queda
**pendiente** y se marca así; no se da por hecho.

## Estado

| Escenario | Estado | Resultado |
|---|---|---|
| §1 Instalar y pasar las comprobaciones | ✅ hecho | `uv sync --frozen`, `ruff check` y `ruff format --check` sin avisos; `pytest`: **95 pruebas**, cobertura **98,7 %** (umbral 80 %) |
| §2 Validar el dataset y leer el resumen | ✅ hecho | Código de salida `0`; dos ejecuciones imprimen exactamente lo mismo (también lo comprueba `test_validation_and_summary_are_deterministic`) |
| §3 Los errores se detectan | ✅ hecho | Ver tabla de abajo |
| §4 Revisión manual (SC-005, US2) | ⏳ **pendiente de la persona revisora** | Borrador y muestra preparados en `eval/REVIEW.md` |
| §5 Ampliar el conjunto con solo la guía (SC-007) | ⏳ **pendiente** | Requiere que lo haga alguien que no conozca el repositorio; la guía está en `eval/README.md` |
| §6 CI | ⏳ **pendiente del PR** | El filtro `eval` ya incluye `data/kb/**`; se comprueba al abrir el PR |

## Resumen del dataset real (`uv run python -m shoplab_eval validate`)

```text
Casos: 90
Por categoría: payments=17, privacy=16, returns=17, shipping=16, warranty=16, off_topic=8
Por tipo: answered=70, unanswered_topic=12, off_topic=8
Casos de dos documentos: 15
Documentos del corpus sin caso: ninguno
Solapamiento léxico bajo: 78.6 % de las preguntas con respuesta (objetivo FR-013: al menos 30 %; informativo)
```

| Criterio | Objetivo | Resultado |
|---|---|---|
| SC-001 Total de casos | 80–100 | 90 |
| SC-002 Casos con respuesta por tema | ≥ 12 cada uno | 14 en cada uno de los cinco temas |
| SC-002 Documentos del corpus esperados | 24 de 24 | 24 de 24 |
| SC-003 Sin respuesta | 15–25 % y ≥ 12; ≥ 5 del tema y ≥ 5 ajenas | 22,2 % (20): 12 del tema y 8 `off_topic` |
| SC-004 Casos con dos documentos | ≥ 8 | 15 (9 combinan políticas distintas; 6 listan una política y su FAQ) |
| FR-013 Poco solapamiento léxico | ≥ 30 % | 78,6 % |

## §3: un error por regla sobre copias del archivo

Script desechable fuera del repositorio. Cada variante falló con código de salida `1` y el código esperado.

| Cambio | Código esperado | Resultado |
|---|---|---|
| Borrar la clave `category` de una línea | `G-FIELDS` | ✅ |
| Sustituir un documento esperado por `no-existe` | `G-DOC-MISSING` | ✅ |
| Repetir una pregunta en otra línea (en mayúsculas) | `G-DUPLICATE` | ✅ |
| Dejar 79 casos | `G-COUNT` | ✅ |
| Escribir una cifra en la respuesta de un caso sin respuesta | `G-NOANSWER-DIGITS` | ✅ |
| Pegar una frase literal de un documento como pregunta | `G-QUESTION-VERBATIM` | ✅ |
| Todos a la vez | los seis códigos, más `G-UNANSWERED` y `G-UNANSWERED-KINDS`, juntos | ✅ |
| Borrar de una **copia** del corpus `payments-03-boleto-and-vouchers` | `G-DOC-MISSING` por cada caso que lo espera | ✅ `G001`, `G003`, `G005`, `G011`, `G012`, `G013` |

Hallazgo durante la comprobación: quitar 10 casos deja justo 80, que es válido; hubo que quitar 11 para llegar a 79. Era un
fallo del script, no de la validación.

## Decisiones tomadas al implementar

- **`G-COUNT` ya no corta las demás reglas de conjunto**: solo se detiene con cero casos. Si no, un archivo con el total mal
  habría ocultado el resto de fallos y habría incumplido «informar todos los fallos juntos» (US3 escenario 5). Lo detectó la
  prueba de orden estable.
- **`load_golden`/`validate_golden` viven en `golden.py`** con importaciones dentro de la función, porque la validación
  necesita `GoldenCase` de ese mismo módulo y una importación directa sería circular.
- **`G-QUESTION-VERBATIM` compara por palabras completas** (se rodea el texto de espacios al buscar la subcadena), de modo que
  una pregunta que solo es el comienzo de una palabra del documento no da un falso positivo.
- **El comando fuerza UTF-8 en la salida** porque la consola de Windows rompía las tildes del resumen.
- **Los casos con dos documentos incluyen respuestas que están en una política y en su FAQ.** El validador cuenta cualquier
  caso con dos documentos; los 9 que combinan políticas distintas cubren el espíritu de FR-008/SC-004 y el resto lista ambos
  documentos para no penalizar la recuperación de cualquiera de los dos.
- **Pregunta sin respuesta sobre la batería (`G081`)**: se cambió por la del producto de reemplazo durante la reparación,
  porque el corpus excluye el «desgaste normal» y una pregunta sobre la batería podía contestarse en parte.
- **Las búsquedas con tilde dentro de corchetes (`[ií]`) no son fiables en este shell**; se repitieron con alternancia
  (`(i|í)`) para no dar por buenos falsos «sin coincidencias».

## Pendiente antes de cerrar la historia

1. **Revisión humana** de los 90 casos (`eval/REVIEW.md` §1) y de la muestra de 15 con semilla 27 (§2); repetir las
   búsquedas de los 12 casos sin respuesta del tema (§3) y leer las 20 respuestas sin respuesta (§4).
2. **§5**: que alguien ajeno añada un caso siguiendo solo `eval/README.md`.
3. **PR** y comprobación de CI (§6), y **validar el primer tag `vX.Y.Z` tras fusionar** antes de cerrar LAB-27 en Jira.
