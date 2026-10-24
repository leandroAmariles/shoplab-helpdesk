# Data Model: Registro de decisiones de arquitectura (ADR)

No hay almacenamiento ni esquema; las entidades son documentos Markdown.

## ADR

Archivo `docs/adr/NNNN-titulo-corto.md`.

| Campo | Obligatorio | Regla |
|-------|-------------|-------|
| Número `NNNN` | Sí | Cuatro dígitos, secuencial, único, nunca reutilizado; 0000 reservado a la plantilla |
| Título | Sí | Encabezado `# ADR-NNNN: Título`; el nombre de archivo es el título en minúsculas con guiones |
| Estado | Sí | `propuesto`, `aceptado` o `sustituido por ADR-XXXX` |
| Fecha | Sí | `AAAA-MM-DD` de aceptación (o de propuesta mientras esté propuesto) |
| Sustituye a | No | `ADR-YYYY`, solo en el ADR que reemplaza a otro |
| Contexto | Sí | No vacío si el estado es aceptado |
| Decisión | Sí | No vacío si el estado es aceptado |
| Consecuencias | Sí | No vacío si el estado es aceptado |

### Transiciones de estado

```text
propuesto ──► aceptado ──► sustituido por ADR-XXXX
```

- Un ADR `aceptado` no se edita (salvo erratas); si cambia la decisión, se crea uno nuevo.
- Al sustituir: el original cambia únicamente su línea de estado; el nuevo añade `Sustituye a`.
- `sustituido` es terminal; el archivo no se borra.

## Plantilla ADR

`docs/adr/0000-plantilla.md`. Define los campos anteriores y los estados permitidos; no es una
decisión y no figura en el índice de ADR.

## Índice de ADR

Tabla en `docs/adr/README.md` con columnas número, título (enlace) y estado; una fila por ADR real,
en orden numérico.
