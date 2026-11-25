# Contrato de Especificación de Docker Compose

**Feature**: `LAB-110-multi-repo-local-env`  
**Artefacto**: `infra/docker-compose.yml` y `infra/docker-compose.override.yml`  
**Status**: Approved  

---

## 1. Alcance y Convenciones de Servicio

Todo servicio de aplicación integrado en el Compose paraguas debe cumplir las siguientes normas contractuales:

1. **Parámetros de Imagen y Nombres**:
   * Cada servicio debe usar la sintaxis `${NOMBRE_SERVICIO_IMAGE:-ghcr.io/leandroamariles/<repo>:<tag-fijo>}`.
   * Queda terminantemente prohibido el uso del tag `:latest`. Los valores por defecto deben corresponder a versiones semánticas probadas (`:vX.Y.Z`).

2. **Exposición de Puertos en el Host**:
   * Los servicios deben publicar sus puertos canónicos hacia `localhost`:
     * `backend`: `${BACKEND_PORT:-8080}:8080`
     * `frontend`: `${FRONTEND_PORT:-4200}:80` (o `4200`)
     * `ingest`: `${INGEST_PORT:-8000}:8000`
     * `postgres`: `${POSTGRES_PORT:-5432}:5432`
     * `litellm`: `${LITELLM_PORT:-4000}:4000`

3. **Resolución de Red Inter-Contenedores**:
   * Todos los contenedores deben compartir la red por defecto (`default`), permitiendo el enrutamiento por nombre de servicio:
     * Frontend -> Backend: `http://backend:8080`
     * Backend -> Postgres: `postgres:5432`
     * Backend -> LiteLLM: `http://litellm:4000`
     * Backend -> Ingest: `http://ingest:8000`

4. **Healthchecks Requeridos**:
   * Cada servicio debe implementar un bloque `healthcheck` funcional que retorne código 0 cuando el servicio esté listo para admitir tráfico (liveness/readiness).
   * Los servicios dependientes deben usar `depends_on.<servicio>.condition: service_healthy` para garantizar un arranque ordenado.

---

## 2. Contrato de Override para Desarrollo Local

El archivo de personalización `infra/docker-compose.override.yml` debe respetar:

1. **Jerarquía de Directorios**:
   * Ubicación: `infra/docker-compose.override.yml`.
   * El contexto de compilación local (`context`) debe apuntar a la ruta relativa del repositorio hermano correspondiente (`../../<nombre-repo>`).
   * No debe alterar los nombres de servicio, puertos ni variables de entorno esenciales definidas en el archivo base, a menos que el objetivo explícito sea depuración.

2. **Aislamiento en Control de Versiones**:
   * `infra/docker-compose.override.yml` nunca debe versionarse en Git.
   * La plantilla de referencia `infra/docker-compose.override.yml.example` sí debe versionarse y mantenerse sincronizada con los cambios de infraestructura.
