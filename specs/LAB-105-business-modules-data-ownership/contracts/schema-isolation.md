# Contrato: aislamiento de esquemas

## Convenciones

- Esquema = nombre del módulo; carpeta = `src/main/resources/db/migration/<modulo>/`; historial =
  `<modulo>.flyway_schema_history`.
- Convención única (FR-015): módulo = esquema = carpeta de migraciones; se documenta también en el ADR 0015.
- Nombres de script: `V<n>__<descripcion>.sql`; el primero de cada módulo es `V1__baseline.sql`.
- Los scripts de un módulo **no califican** objetos con el esquema de otro módulo y no usan `SET search_path`.

## Reglas

| ID | Regla | Detección |
|---|---|---|
| S1 | Sin `REFERENCES <otro_esquema>.…` ni FK que cruce esquemas | Lint de SQL y catálogo `pg_constraint` |
| S2 | Sin nombres calificados de un esquema ajeno en SQL de `src/main` del módulo (scripts y bloques de texto) | Lint de SQL |
| S3 | `CREATE SCHEMA` / `SET search_path` / `ALTER … SET SCHEMA` fuera del propio esquema prohibidos | Lint de SQL |
| S4 | Tras migrar una BD vacía existe un esquema por módulo con su propio historial | Integración (Testcontainers) |
| S5 | Cero FKs entre esquemas distintos en el catálogo | Integración |

## Consulta de S5 (referencia)

```sql
SELECT c.conname, src.nspname AS from_schema, dst.nspname AS to_schema
FROM pg_constraint c
JOIN pg_class srcc ON srcc.oid = c.conrelid
JOIN pg_namespace src ON src.oid = srcc.relnamespace
JOIN pg_class dstc ON dstc.oid = c.confrelid
JOIN pg_namespace dst ON dst.oid = dstc.relnamespace
WHERE c.contype = 'f' AND src.nspname <> dst.nspname;
```

Resultado esperado: 0 filas.

## Pruebas negativas

- Fixtures SQL en `src/test/resources/schema/violations/` (una por S1–S3); la prueba exige que el lint falle con cada una
  y nombre archivo y regla.
- En la integración, un esquema temporal con una FK cruzada debe hacer que la consulta S5 devuelva filas.

## Límite conocido

Un `JOIN` construido por concatenación dinámica de cadenas en Java no se detecta; se cubre en revisión (checklist de
PR) y con S2 sobre literales. Roles de BD por módulo quedan diferidos a F5 (ADR 0015).

## Integridad referencial entre módulos

Por identificador, verificada en `application` consultando la API del módulo dueño (ver `data-model.md`).
