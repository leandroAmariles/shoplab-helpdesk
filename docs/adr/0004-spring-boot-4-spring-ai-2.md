# ADR-0004: Spring Boot 4 con Spring AI 2.0 y Spring Modulith para el backend

- Estado: aceptado
- Fecha: 2026-10-23

## Contexto

El backend necesita una plataforma JVM con soporte de primera clase para integrar LLM, embeddings y
almacenes vectoriales, y que permita estructurar un monolito modular verificable (ADR-0001).
Spring AI 2.0 alcanzó disponibilidad general (GA) en junio de 2026 y está construido sobre Spring
Boot 4 y Spring Framework 7, por lo que adoptar ambos a la vez evita migrar más adelante.

## Decisión

Usar Java 21, Spring Boot 4.1.2, Spring AI 2.0.2 (solo su BOM por ahora; los starters de modelo se
añaden cuando haya código de chat) y Spring Modulith 2.1.2, en un único proyecto Maven en
`backend/` con los módulos `chat`, `retrieval`, `tools` y `tickets`. Las fronteras se verifican con
`ApplicationModules.verify()` dentro de la construcción. Las versiones se validaron resolviendo el
árbol de dependencias y ejecutando la construcción completa (Spring Framework 7.0.10).

Alternativas descartadas:

- **Spring Boot 3.5 + Spring AI 1.x**: más madura, pero queda en una línea anterior y obligaría a
  migrar a Boot 4 y a Spring AI 2.0 poco después.
- **LangChain4j**: buena integración con LLM, pero fuera del ecosistema Spring (configuración,
  observabilidad, Modulith) que ya usa el resto del backend.
- **Backend en Python**: la ingesta y la evaluación ya usan Python (`ingest/`, `eval/`), pero el
  backend de producto se mantiene en la JVM por el modelo de módulos, la tipificación y los
  controles de calidad exigidos.

## Consecuencias

- El proyecto depende de versiones recientes: Spring AI 2.0 y Boot 4.1 aún tienen poco recorrido, y
  hay que revisar las notas de versión antes de actualizar.
- Se exige JDK 21 o superior; la construcción falla con un mensaje claro en versiones anteriores.
- Spring Modulith no está gestionado por el BOM de Spring Boot, por lo que se importa su BOM de forma
  explícita y se actualiza junto con Boot.
- El acceso a LLM y embeddings seguirá detrás de puertos (ADR-0003), de modo que Spring AI se pueda
  sustituir sin tocar el dominio.
- Coherente con ADR-0001 (monolito modular) y ADR-0002 (PostgreSQL con pgvector): esta historia no
  añade dependencias de base de datos.
