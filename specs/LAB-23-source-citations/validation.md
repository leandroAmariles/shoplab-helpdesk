# Validación manual de LAB-23 (T045, parcial)

**Fecha**: 2026-11-03. **Rama**: `feature/LAB-23-source-citations` (sin commitear).

**Entorno**: backend local (`./mvnw spring-boot:run`, puerto 8081) con el PostgreSQL + pgvector de `infra/docker-compose.yml`
(24 documentos de `data/kb`, todos `INDEXED` con `bge-m3`, cargados en la validación de LAB-22) y el Ollama de
`docker compose` con `llama3.2:3b` y `bge-m3`. Prompt y plantillas **por defecto** (con la regla de citas de esta historia),
top-k 4, umbral 0,6. Las preguntas se hicieron por HTTP con un script desechable (fuera del repositorio).

**Alcance**: solo lo que se puede comprobar contra la API. **No se hizo** la revisión de la interfaz con teclado, lector de
pantalla ni móvil (quickstart §3, puntos 4 a 7): queda pendiente de una persona.

## Resultados (dos tandas de las mismas 12 preguntas del corpus + 3 ajenas)

El modelo de 3 B no es determinista: las dos tandas difieren.

| Criterio | Objetivo | Tanda 1 | Tanda 2 |
|---|---|---|---|
| SC-001: respuestas **con contexto** con ≥ 1 cita válida | ≥ 90 % | **9/11 (82 %)** | **7/11 (64 %)** |
| SC-002: citas válidas cuyo fragmento contiene el dato | ≥ 80 % | 9/9 por título y sección (no se leyó el texto) | **5/5** (el dato figura en el texto del fragmento citado; 2 respuestas no tenían un dato comprobable) |
| Citas a fuentes inexistentes | 0 | 0 | 0 |
| Primer token (SC-005) | < 5 s | 1,2–4,0 s (52 s la primera, con el modelo sin cargar) | 0,4–3,4 s |
| Preguntas ajenas al corpus (Francia, X999, «Hola») | mensaje fijo, sin fuentes | 3/3, sin evento `sources` | 3/3, sin evento `sources` |

**SC-001 no se cumple con el prompt por defecto y `llama3.2:3b`.** Las respuestas sin cita (tanda 1: «¿Cuánto cuesta el envío?»,
«¿Cómo reclamo la garantía?»; tanda 2: garantía legal, seguimiento, métodos de pago, cookies) caen en «Fuentes consultadas»,
que es el comportamiento previsto (FR-009): el cliente sigue pudiendo abrir los fragmentos, pero no se le atribuye cada
afirmación. Se midió pero **no se ajustó el prompt**: los cambios de prompt sin evaluación quedan para F2 (Constitución V) y
con 12 preguntas no deterministas cualquier ajuste sería adivinar. Si se quiere subir la tasa antes, lo más barato es añadir un
ejemplo a la regla 5 (p. ej. «Tienes 30 días para devolverlo [1].») por configuración
(`SHOPLAB_CHAT_SYSTEM_PROMPT`), sin tocar código, y repetir esta tanda.

Otros hallazgos:

- «¿Cómo se trata mi información personal?» no recuperó contexto en las dos tandas (responde el mensaje fijo): es la
  calibración del umbral de LAB-22 (0,6), no de las citas.
- Una respuesta mezcla formato: «Según la sección "Cómo funciona" [2], …»; la cita se reconoce igualmente.
- Las citas llegan como `[1] [2]` separadas; ambas se reconocen.

## Métricas y registros

`shoplab_chat_citations_total` tras las 22 respuestas con contexto: `valid` 16, `none` 6, `invalid` 0 (coincide con el recuento
a mano: 9 + 7 válidas, 2 + 4 sin cita). Los registros del backend no contienen el texto de las preguntas ni de los fragmentos
(se buscaron las preguntas «Francia», «X999» y «¿Cuántos días tengo…» y no aparecen).

## Pendiente

- Revisión de la interfaz: citas como botones, lista «Fuentes» / «Fuentes consultadas», diálogo, Escape y foco, teclado,
  lector de pantalla y móvil (SC-007).
- Decidir si se refuerza el prompt (ejemplo en la regla 5) y repetir la tanda.
