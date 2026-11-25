# Quickstart: Guía de Desarrollo Local con Varios Repositorios

**Feature**: `LAB-110-multi-repo-local-env`  
**Target**: Desarrolladores y Colaboradores de ShopLab Helpdesk  

---

## Flujo de Trabajo en 4 Pasos

```text
[ 1. Clonar Repositorios ] --> [ 2. Levantar Sistema ] --> [ 3. Cambiar Servicio ] --> [ 4. Probar y Verificar ]
   make clone-all                docker compose up            override / host IDE          curl / E2E / browser
```

---

## Paso 1: Clonación del Ecosistema

Para trabajar con el sistema completo o realizar cambios cruzados, clona los repositorios hermanos en carpetas adyacentes:

### En Linux / macOS / Git Bash:
```bash
make clone-all
```

### En Windows (PowerShell):
```powershell
pwsh scripts/clone-all.ps1
```

*Los repositorios se descargarán en `../shoplab-*` respecto a `shoplab-helpdesk`.*

---

## Paso 2: Configuración y Arranque del Entorno Completo

1. Copia el archivo de variables de entorno si aún no lo tienes:
   ```bash
   cp infra/.env.example infra/.env
   ```
2. Levanta todos los servicios utilizando las imágenes preconstruidas de GHCR:
   ```bash
   docker compose -f infra/docker-compose.yml up -d --wait
   ```
3. Comprueba el estado de salud de los servicios:
   ```bash
   docker compose -f infra/docker-compose.yml ps
   ```
4. Accede a las interfaces:
   - **Frontend (Angular)**: [http://localhost:4200](http://localhost:4200)
   - **Backend API**: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
   - **Ingest API**: [http://localhost:8000/docs](http://localhost:8000/docs)
   - **LiteLLM Gateway**: [http://localhost:4000/health/liveliness](http://localhost:4000/health/liveliness)

---

## Paso 3: Modificar un Servicio Específico

Tienes dos modalidades de desarrollo para trabajar sobre un servicio:

### Opción A: Modo Contenedor con Override (Recomendado para Frontend / Ingest)

1. Crea tu archivo de override local a partir de la plantilla:
   ```bash
   cp infra/docker-compose.override.yml.example infra/docker-compose.override.yml
   ```
2. Abre `infra/docker-compose.override.yml` y descomenta el servicio en el que vas a trabajar (por ejemplo, `frontend`):
   ```yaml
   services:
     frontend:
       build:
         context: ../../shoplab-frontend
       image: shoplab-frontend:local
   ```
3. Reconstruye y levanta únicamente el servicio modificado:
   ```bash
   docker compose -f infra/docker-compose.yml up -d --build frontend
   ```
   *El resto del sistema (`backend`, `ingest`, `postgres`, `litellm`) continuará corriendo sus imágenes de GHCR sin cambios.*

---

### Opción B: Modo Host en IDE (Recomendado para Backend Java)

Si prefieres depurar en tu IDE (IntelliJ IDEA, VS Code):
1. Detén el contenedor de ese servicio en Docker Compose para liberar el puerto:
   ```bash
   docker compose -f infra/docker-compose.yml stop backend
   ```
2. Abre el proyecto en tu IDE o ejecuta desde la terminal:
   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```
3. El proceso local se conectará automáticamente a PostgreSQL (`localhost:5432`), LiteLLM (`localhost:4000`) e Ingest (`localhost:8000`) expuestos por Docker.

---

## Paso 4: Probar y Verificar

1. **Verificación rápida de salud**:
   ```bash
   curl -s http://localhost:8080/actuator/health
   curl -s http://localhost:8000/health
   ```
2. **Prueba de chat interactivo**:
   Accede a [http://localhost:4200](http://localhost:4200) y envía un mensaje en el asistente.
3. **Pruebas integradas E2E**:
   Ejecuta la suite de verificación de integración del repositorio paraguas:
   ```bash
   make test-e2e
   ```
