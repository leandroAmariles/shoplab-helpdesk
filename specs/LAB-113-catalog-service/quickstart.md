# Quickstart: shoplab-catalog-service

Guía paso a paso para levantar y probar localmente `catalog-service` y verificar sus contratos.

## Prerrequisitos

- Java 21 (Temurin / GraalVM)
- Docker y Docker Compose
- Maven Wrapper (`./mvnw`)
- `uv` (para carga de datos Olist en Python si se ejecuta el loader local)

## 1. Levantar infraestructura local

En `shoplab-helpdesk`:

```bash
docker compose -f infra/docker-compose.yml up -d postgres
```

Crear la base de datos `catalog` si no existe:

```bash
docker exec -it shoplab-postgres psql -U postgres -c "CREATE USER catalog WITH PASSWORD 'catalog'; CREATE DATABASE catalog OWNER catalog;"
```

## 2. Compilar y ejecutar pruebas de catalog-service

En el directorio del nuevo repositorio `shoplab-catalog-service`:

```bash
./mvnw clean verify
```

Comprobar que JaCoCo reporta cobertura ≥ 80% y las pruebas de Spring Cloud Contract pasan.

## 3. Arrancar el servicio localmente

```bash
POSTGRES_URL=jdbc:postgresql://localhost:5432/catalog \
POSTGRES_USER=catalog \
POSTGRES_PASSWORD=catalog \
./mvnw spring-boot:run
```

El servicio iniciará en el puerto `8080` (o `8084` si se corre junto a los demás servicios).

## 4. Verificar Endpoints REST

```bash
# Health check
curl -s http://localhost:8080/actuator/health | jq .

# Paginación de productos
curl -s "http://localhost:8080/api/catalog/products?page=0&size=5" | jq .

# Detalle de un producto
curl -s "http://localhost:8080/api/catalog/products/1e9e8ef04dbcff4541ed26657ea517e5" | jq .

# Consulta ligera de stock
curl -s "http://localhost:8080/api/catalog/products/1e9e8ef04dbcff4541ed26657ea517e5/stock" | jq .
```

## 5. Verificar Servidor MCP

```bash
# Descubrimiento de herramientas
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}' | jq .

# Ejecución de herramienta de búsqueda
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/call","params":{"name":"searchProducts","arguments":{"query":"reloj"}}}' | jq .
```
