# Contract: interfaz del entorno local

La infraestructura no expone endpoints propios; lo que otras historias consumen es esta interfaz.

## Comandos

| Acción | Comando (desde la raíz del repositorio) |
|---|---|
| Levantar `core` | `docker compose -f infra/docker-compose.yml up -d --wait` |
| Levantar con perfil extra | `docker compose -f infra/docker-compose.yml --profile <perfil> up -d --wait` |
| Con GPU NVIDIA | `docker compose -f infra/docker-compose.yml -f infra/docker-compose.gpu.yml up -d --wait` |
| Estado de salud | `docker compose -f infra/docker-compose.yml ps` |
| Detener (conserva datos) | `docker compose -f infra/docker-compose.yml down` |
| Reiniciar desde cero | `docker compose -f infra/docker-compose.yml down -v` |

## Endpoints para consumidores (backend, ingesta)

| Servicio | Dirección desde el host | Dirección dentro de la red de compose |
|---|---|---|
| PostgreSQL | `localhost:${POSTGRES_PORT}` | `postgres:5432` |
| Ollama (API HTTP) | `http://localhost:${OLLAMA_PORT}` | `http://ollama:11434` |

## Garantías

- Un servicio `healthy` está listo para uso (Ollama: ambos modelos descargados).
- Los consumidores pueden declarar `depends_on` con `condition: service_healthy`.
- Los nombres de servicio (`postgres`, `ollama`) y las variables de `.env.example` son estables;
  renombrarlos es un cambio incompatible que requiere actualizar a los consumidores.
