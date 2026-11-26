# Guía de Desarrollo Local con Múltiples Repositorios (F5, LAB-110)

Esta guía establece el flujo de trabajo estándar para desarrolladores en el ecosistema distribuido de **ShopLab Helpdesk**, tras la adopción de la arquitectura multi-repositorio ([ADR-0031](../adr/0031-extraccion-temprana-de-servicios.md)).

---

## 1. El Rol de `shoplab-helpdesk` como Repositorio Paraguas

En una arquitectura de múltiples repositorios, `shoplab-helpdesk` no contiene el código de todos los servicios, sino que asume el rol de **repositorio paraguas** (*umbrella repo*):
- **Gobernanza y Decisiones**: Centraliza los Registros de Decisiones de Arquitectura ([`docs/adr/`](../adr/README.md)) y el mapa de servicios ([`docs/mapa-servicios.md`](../mapa-servicios.md)).
- **Composición del Sistema**: Aloja el archivo [`infra/docker-compose.yml`](../../infra/docker-compose.yml) que integra la totalidad de los servicios a través de imágenes publicadas en GitHub Container Registry (GHCR).
- **Pruebas de Integración y E2E**: Aloja los scripts de verificación de extremo a extremo que validan la interoperabilidad entre los microservicios.
- **Backend Transicional**: Custodia temporalmente el backend modular en [`backend/`](../../backend/) hasta la extracción de `chat-service` (LAB-59) y `eval-service` (LAB-130). La base de conocimiento ya está en `retrieval-service` (LAB-129): el compose lo levanta, con su base de datos, y lo publica en el `8082` para depurar.

---

## 2. Flujo de Trabajo en 4 Pasos

### Paso 1: Clonar el ecosistema con `clone-all`
Todos los repositorios del ecosistema deben residir en carpetas adyacentes a `shoplab-helpdesk` (`../`):
- En Linux / macOS / Git Bash:
  ```bash
  make clone-all
  ```
- En Windows (PowerShell):
  ```powershell
  pwsh scripts/clone-all.ps1
  ```
El comando es **idempotente**: si un repositorio ya está clonado, lo detecta e informa `[SKIP]` sin alterar el árbol local.

### Paso 2: Configurar y levantar el sistema completo con GHCR
1. Copia el archivo de configuración:
   ```bash
   cp infra/.env.example infra/.env
   ```
2. Levanta todos los servicios en segundo plano:
   ```bash
   docker compose -f infra/docker-compose.yml up -d --wait
   ```
   *Todos los servicios (`postgres`, `litellm`, `ingest`, `backend`, `gateway`, `frontend`) arrancan con imágenes publicadas en GHCR con versiones semánticas fijadas en `.env` (prohibido `:latest`). La API entra por el `gateway` en el `8080`; el backend queda en el `8081` para depurar (LAB-107).*

### Paso 3: Modificar un servicio específico

#### Opción A: Modo Contenedor con Override (Frontend o Ingest)
Si vas a modificar código en `shoplab-frontend` o `shoplab-ingest`:
1. Copia la plantilla de override:
   ```bash
   cp infra/docker-compose.override.yml.example infra/docker-compose.override.yml
   ```
2. Descomenta el bloque del servicio que vas a editar (las rutas `context` apuntan a `../../shoplab-<servicio>`).
3. Reconstruye y arranca solo ese servicio:
   ```bash
   docker compose -f infra/docker-compose.yml up -d --build frontend
   ```
   *El resto del sistema sigue corriendo con las imágenes oficiales de GHCR.*

#### Opción B: Modo Host en IDE (Backend Java)
Si vas a depurar el backend en tu entorno local (IntelliJ IDEA, VS Code):
1. Detén el contenedor del backend:
   ```bash
   docker compose -f infra/docker-compose.yml stop backend
   ```
2. Ejecuta el backend localmente en el `8081` (el `8080` es del gateway):
   ```bash
   cd backend && SERVER_PORT=8081 ./mvnw spring-boot:run
   ```
   *Se conectará a PostgreSQL (`localhost:5432`) y LiteLLM (`localhost:4000`) expuestos por Docker.*
3. Apunta el gateway al backend del host:
   ```bash
   GATEWAY_MONOLITH_URI=http://host.docker.internal:8081 docker compose -f infra/docker-compose.yml up -d --no-deps gateway
   ```

### Paso 4: Probar y Validar
1. Comprueba la salud del sistema completo:
   ```bash
   make test-e2e
   # En Windows PowerShell:
   pwsh scripts/test-e2e.ps1
   ```
2. Interactúa con la interfaz web en [http://localhost:4200](http://localhost:4200).
