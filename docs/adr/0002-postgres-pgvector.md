# ADR-0002: PostgreSQL con pgvector como única base de datos inicial

- Estado: aceptado
- Fecha: 2026-10-23

## Contexto

El sistema necesita datos relacionales (pedidos, tickets), vectores para RAG y búsqueda de texto completo.

## Decisión

Usar PostgreSQL 16 con la extensión pgvector para todo en las fases iniciales: índice HNSW para vectores y `tsvector` con GIN para texto completo.

## Consecuencias

- Una sola base de datos que operar y respaldar.
- La búsqueda híbrida se resuelve con SQL.
- En F5 cada microservicio tendrá su propia base de datos.
