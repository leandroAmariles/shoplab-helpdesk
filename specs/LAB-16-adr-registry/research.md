# Research: Registro de decisiones de arquitectura (ADR)

No quedaban `NEEDS CLARIFICATION` en el Technical Context. Esta investigación audita el estado
actual de `docs/adr/` frente a la spec.

## Auditoría del estado actual

| Elemento | Estado encontrado | Brecha frente a la spec |
|----------|-------------------|-------------------------|
| `0000-plantilla.md` | Título `ADR-NNNN`, `Estado: propuesto \| aceptado \| sustituido por ADR-XXXX`, `Fecha`, secciones Contexto/Decisión/Consecuencias | FR-003 cubierto de forma mínima; falta la regla de numeración secuencial/no reutilizable (US1-4) y el modo de enlazar sustituciones (US4-3) |
| `0001-monolito-modular-primero.md` | Estado aceptado, 3 secciones con contenido; cubre contexto, F0–F3/F4 y consecuencias | Cumple FR-004. Sin brecha de contenido |
| `0002-postgres-pgvector.md` | Estado aceptado, 3 secciones; cubre base única, HNSW/GIN y cambio en F5 | Cumple FR-005. Sin brecha de contenido |
| `0003`, `0004` | Estado aceptado, 3 secciones | Coherentes con la plantilla (FR-010) |
| Convención de nombres | `NNNN-titulo-corto.md`, orden alfabético = numérico | Cumple FR-006 |
| README raíz | Línea «Las decisiones de arquitectura se registran como ADR en `docs/adr/`» | Falta indicar cómo añadir uno y la regla de no editar aceptados (FR-008) |
| Índice de ADR | No existe | Brecha: no hay listado ni guía de uso (US4) |
| Fin de línea | Índice Git en LF; en el árbol de trabajo 0000–0002 aparecen con CRLF por `core.autocrlf=true` | Sin acción: `.gitattributes` (`* text=auto`) normaliza en el commit |

## Decisiones

### D1. Dónde va la guía de uso
- **Decisión**: `docs/adr/README.md` con índice (tabla número/título/estado), estados permitidos,
  regla de numeración, procedimiento para añadir y para sustituir, y la regla de inmutabilidad.
- **Razón**: GitHub y los IDE lo muestran al abrir la carpeta; el README raíz solo enlaza.
- **Alternativas**: guía dentro de la plantilla (mezcla instrucciones con el modelo a copiar);
  página en Confluence (se aleja del repositorio, no versiona con el código).

### D2. Cómo se mantiene el índice
- **Decisión**: tabla escrita a mano en el índice, revisada en cada PR que añada un ADR.
- **Razón**: son pocos ADR; la constitución exige simplicidad (VII) y la spec descarta herramientas
  de generación o validación.
- **Alternativas**: script que genere el índice o adr-tools (infraestructura sin retorno a esta
  escala).

### D3. Cómo se registra una sustitución
- **Decisión**: el ADR sustituido cambia solo su línea de estado a `sustituido por ADR-XXXX`; el
  nuevo ADR añade el campo `Sustituye a: ADR-YYYY`. Ningún otro contenido del original se edita.
- **Razón**: cumple FR-008 y el escenario US4-3, y preserva el historial.
- **Alternativas**: borrar o reescribir el original (pierde trazabilidad).

### D4. Número en conflicto entre ramas paralelas
- **Decisión**: el número se confirma al integrar; quien integra después renumera su ADR
  (edge case de la spec). Se documenta en el índice.
- **Alternativas**: reservar números por adelantado en Jira (carga de proceso innecesaria).

### D5. Alcance sobre ADR existentes
- **Decisión**: no se reescribe el contenido de 0001–0004; solo se ajustan diferencias de forma si
  la auditoría final con la plantilla actualizada las detecta.
- **Razón**: FR-010 y la suposición de no reescribir el historial.

### D6. Plantilla: nombre y numeración
- **Decisión**: se conserva `0000-plantilla.md`; el número 0000 queda reservado a la plantilla y los
  ADR reales empiezan en 0001.
- **Alternativas**: renombrar a `plantilla.md` (rompe el orden y enlaces existentes sin beneficio).
