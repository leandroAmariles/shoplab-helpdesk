# Validación manual de LAB-22 (T043)

**Fecha**: 2026-11-01. **Rama**: `feature/LAB-22-rag-augmented-answers` (sin commitear).

**Entorno**: backend local (`./mvnw spring-boot:run`, puerto 8081) con PostgreSQL + pgvector de `infra/docker-compose.yml`,
`ingest` del clúster local `shoplab-dev` (v0.10.0, por port-forward) y Ollama de `docker compose` con `llama3.2:3b` y
`bge-m3`. Corpus: los 24 `.md` de `data/kb` (138 chunks, todos indexados con `bge-m3`), cargados con un cargador
temporal que ya se borró (el backend no tiene endpoint de subida). El resultado de cada pregunta es una sola ejecución:
el modelo de 3 B no es determinista y dos ejecuciones del mismo conjunto fallaron preguntas distintas.

## Resultados

| Criterio | Objetivo | Umbral 0 (valor anterior) | Umbral 0,6 (ahora por defecto) |
|---|---|---|---|
| SC-001: 10 preguntas con respuesta en el corpus | ≥ 8 correctas | **9/10** | **9/10** |
| SC-002: 5 preguntas sin respuesta en el corpus | 5/5 reconocen que no saben | **3/5** (inventa «X999 está disponible»; el poema responde con datos de privacidad sin relación) | **5/5** (el poema ofrece escribirlo, sin datos de tienda) |
| SC-004: primer token | < 5 s | 0,5–1,8 s (con el modelo cargado) | 0,2–1,5 s |

En el clúster `shoplab-dev` el primer token tardó 11–27 s (2 CPU, sin GPU) y el pod de Ollama murió por `OOMKilled` (ver
hallazgos).

## Puntuación de similitud observada (coseno, `bge-m3`)

- Preguntas con respuesta en el corpus: mejor puntuación **0,67–0,84**.
- Preguntas ajenas al corpus: mejor puntuación **0,27–0,58** (Francia 0,27; Madrid 0,40; criptomonedas 0,45; X999 0,33;
  poema 0,58); el saludo «Hola» 0,48.
- Con el umbral en 0 la búsqueda siempre devuelve 4 fragmentos, relevantes o no, así que el camino «sin contexto» casi
  nunca se activa y el modelo recibe contexto de otro tema. Un umbral de 0,6 separa ambos grupos en esta muestra.

## Hallazgos

1. **Umbral por defecto (0) demasiado permisivo.** Provoca el fallo de SC-002 y que un saludo se conteste con datos de
   privacidad. Con 0,6 desaparecen los dos. El spec lo dejaba en 0 «hasta calibrar»; esta muestra pequeña (18 preguntas, un
   corpus) sugiere 0,6 como valor inicial, a confirmar con la evaluación de F2.
2. **Pregunta mixta** («¿cuántos días para devolver … y cuál es la capital de Francia?»): con umbral 0 respondió «no
   tengo información» (debía dar los 7 días); con 0,6 el contexto quedó vacío (puntuación 0,59) y el modelo contestó
   «La capital de Francia es París», es decir, usó conocimiento general. Con contexto vacío el modelo local no siempre
   respeta la regla; la mitigación (mensaje fijo sin llamar al modelo, D-4) queda como decisión pendiente.
3. **Pod de Ollama en `shoplab-dev` muere por memoria.** Con `llama3.2:3b` y `bge-m3` cargados a la vez el contenedor llegó a
   5,3 GiB con `num-ctx` 8192; el límite de `k8s/base/ollama/statefulset.yaml` es 4Gi → `OOMKilled` (también con 4096).
   Antes de LAB-22 solo se cargaba el modelo de chat. Hace falta subir el límite (propuesta: 8Gi) antes de desplegar.
4. **Deduplicación útil:** en la pregunta del correo de soporte, 3 de los 4 candidatos eran el mismo texto «Contacto» de
   distintos documentos y se entregó uno solo.
5. **Calidad irregular, propia del modelo de 3 B:** a veces añade «Según el CONTEXTO proporcionado…» y en una pregunta
   (flete de devolución) la respuesta dependió de la ejecución. Los 4 fragmentos recuperados contenían la respuesta en ambos
   casos, así que la recuperación no es la causa.

## Resumen

SC-001 y SC-004 se cumplen. SC-002 se cumple solo con un umbral de similitud mayor que 0 (con el valor anterior, 0, falla
2 de 5). Decisión tomada: el valor por defecto del umbral pasó a 0,6. Pendiente: subir a 8Gi el límite de memoria del pod de Ollama (rama `fix/LAB-22-ollama-memory-limit` de `shoplab-infra`).

## Seguimiento: mensaje fijo sin contexto

Tras estos resultados se añadió `shoplab.chat.rag.no-context-mode` (`fixed-message` por defecto). Sin fragmentos
útiles el caso de uso responde `no-context-message` sin llamar al modelo, lo que cierra los fallos de contexto vacío
observados (la pregunta mixta contestada con «París», el poema, el saludo con datos de privacidad con umbral 0). Cubierto por pruebas automáticas; no se repitió la tanda manual con Ollama.
