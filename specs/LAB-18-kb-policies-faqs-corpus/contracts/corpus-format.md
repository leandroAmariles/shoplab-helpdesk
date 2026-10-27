# Contrato: formato de los documentos del corpus

Aplica a todo archivo `.md` de `data/kb/` salvo `INDEX.md` y `REVIEW.md`.

## Nombre

`<topic>-<NN>-<slug>.md` · `topic` ∈ `shipping | returns | warranty | payments | privacy` · `NN` = 01..99 · `slug` =
kebab-case en inglés (`[a-z0-9-]+`). El PDF, si existe, se llama igual con extensión `.pdf`.

## Front matter (obligatorio)

```yaml
---
title: Política de devoluciones
topic: returns
type: policy        # policy | faq
language: es
scope: Devoluciones de productos comprados en ShopLab, entregados y dentro del plazo.
---
```

## Cuerpo de una política (`type: policy`)

```markdown
# Política de devoluciones

## Alcance
Quién y qué cubre este documento, en una o dos frases.

## Cómo funciona
Reglas y pasos, en lista numerada o párrafos cortos.

## Plazos y costos
Tabla o lista con cada plazo e importe (valores de corpus-facts.md, sin variar).

## Casos especiales
Excepciones y supuestos límite.

## Contacto
Canal genérico de la tienda (soporte@shoplab.example o formulario de contacto del sitio).
```

El `# título` del cuerpo es igual al `title` del front matter.

## Cuerpo de una FAQ (`type: faq`)

`# título`, `## Alcance`, luego una sección `## ¿Pregunta?` por entrada (5–8 preguntas, cada respuesta de 1–4 frases con
el valor concreto) y `## Contacto`.

## Reglas de contenido

1. Español; sin mezclar idiomas dentro del documento (los nombres de métodos de pago o estados pueden citarse en inglés
   entre comillas de código: `shipped`, `boleto`).
2. Solo caracteres latin-1 (sin comillas tipográficas, guiones largos, emojis ni símbolos fuera de ese rango). Importes
   con formato `R$ 19,90`.
3. Cada valor numérico de plazo, importe o porcentaje coincide con `corpus-facts.md`.
4. Sin datos personales: ni nombres de personas, ni teléfonos, ni documentos de identidad, ni direcciones de personas;
   los correos son solo `@shoplab.example`. Ejemplos con datos ficticios evidentes (`Cliente de ejemplo`, `PED-0001`).
5. Sin referencias a otro documento por ruta; se nombra por título si hace falta.
6. Tablas Markdown simples (sin celdas combinadas ni HTML). Extensión orientativa: 300–600 palabras.

## `INDEX.md`

```markdown
| id | title | topic | type | formats |
|----|-------|-------|------|---------|
| shipping-01-delivery-times | Plazos de entrega | shipping | policy | md, pdf |
```

## `REVIEW.md`

```markdown
| id | reviewer | date | status |
|----|----------|------|--------|
| shipping-01-delivery-times | (nombre del administrador) | 2026-10-28 | reviewed |
```

Mientras no se revise: `reviewer` y `date` vacíos, `status = pending`.
