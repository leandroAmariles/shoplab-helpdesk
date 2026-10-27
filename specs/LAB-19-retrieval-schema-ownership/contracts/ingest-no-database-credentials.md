# Contrato: `ingest` sin credenciales de base de datos

`ingest` (Python/FastAPI) no se conecta a PostgreSQL. Entrega su salida (documentos troceados y vectorizados) al
módulo `retrieval` por una API que define una historia posterior; solo `retrieval` escribe el esquema.

## Qué se verifica en este repositorio

Prueba `IngestHasNoDatabaseCredentialsTests` (backend, se ejecuta en CI con el resto de pruebas de aislamiento):

| Superficie | Regla |
|---|---|
| Archivos bajo `ingest/` (`Dockerfile`, `pyproject.toml`, `.env*`, código, configuración) | no contienen patrones de conexión a base de datos |
| Servicio `ingest` en `infra/docker-compose.yml` (si existe) | su `environment`, `env_file`, `secrets` y `depends_on` no incluyen la base de datos |

Patrones prohibidos (sin distinguir mayúsculas): `DATABASE_URL`, `POSTGRES_`, `PGHOST`, `PGPASSWORD`,
`SPRING_DATASOURCE`, `DB_HOST`, `DB_PASSWORD`, `DB_USER`, `jdbc:`, `postgres://`, `postgresql://`.

La prueba lleva fixtures propios (un `Dockerfile` con `ENV DATABASE_URL`, un servicio de compose con
`POSTGRES_PASSWORD`) para demostrar que cada patrón se detecta y que el estado actual, sin ninguno, pasa.

## Qué no se verifica aquí

La configuración de despliegue de `ingest` (Kustomize, secretos de Kubernetes, ArgoCD) vive en `shoplab-infra`
(ADR-0006, ADR-0015). Cuando esa configuración añada `ingest`, su revisión debe confirmar que no monta
`SPRING_DATASOURCE_*` ni secretos de base de datos. Queda como nota en la historia que despliegue `ingest`.
