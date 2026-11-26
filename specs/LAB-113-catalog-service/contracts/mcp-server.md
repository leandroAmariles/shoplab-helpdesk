# Contrato Model Context Protocol (MCP) de catalog-service

Especificación del servidor MCP expuesto por `shoplab-catalog-service` para interacción de agentes conversacionales (`chat-service` en LAB-43), siguiendo el estándar fijado en `specs/LAB-128-early-extraction-service-map/contracts/mcp-tool-protocol.md`.

## Transporte y Endpoints

- **Protocolo**: Model Context Protocol (JSON-RPC 2.0).
- **Transporte**: HTTP con Server-Sent Events (SSE) y mensajes JSON-RPC.
- **Rutas**:
  - `GET /mcp/sse`: Canal de eventos SSE para streaming y suscripción.
  - `POST /mcp/messages`: Envío de peticiones JSON-RPC (`tools/list`, `tools/call`).
  - `POST /mcp`: Endpoint HTTP directo JSON-RPC 2.0 unificado.

## Herramientas Publicadas (`tools/list`)

### 1. `searchProducts` (alias `search_products`)

Permite al agente buscar productos en el catálogo filtrando por términos de texto, categoría y disponibilidad en stock.

```json
{
  "name": "searchProducts",
  "description": "Busca productos en el catálogo de ShopLab por término de búsqueda, categoría y disponibilidad en stock. Devuelve precios de referencia y unidades disponibles.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "Texto descriptivo del producto a buscar (ej. 'reloj', 'zapatillas')"
      },
      "category": {
        "type": "string",
        "description": "Nombre de la categoría en portugués o inglés (opcional)"
      },
      "in_stock_only": {
        "type": "boolean",
        "description": "Si es true, sólo devuelve productos con stock > 0 (opcional, default true)"
      },
      "limit": {
        "type": "integer",
        "description": "Cantidad máxima de productos a devolver (opcional, default 5, max 20)"
      }
    },
    "required": ["query"]
  }
}
```

### 2. `getProductDetails` (alias `get_product_details`)

Permite al agente obtener la ficha técnica detallada, dimensiones, peso, precio y stock de un producto específico.

```json
{
  "name": "getProductDetails",
  "description": "Obtiene la ficha técnica, dimensiones, peso, precio de referencia y disponibilidad exacta en stock de un producto por su product_id.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "product_id": {
        "type": "string",
        "description": "Identificador único alfanumérico del producto (ej. '1e9e8ef04dbcff4541ed26657ea517e5')"
      }
    },
    "required": ["product_id"]
  }
}
```

## Formato de Ejecución (`tools/call`)

### Ejemplo de Petición
```json
{
  "jsonrpc": "2.0",
  "id": "call-cat-01",
  "method": "tools/call",
  "params": {
    "name": "searchProducts",
    "arguments": {
      "query": "reloj deportivo",
      "limit": 2
    }
  }
}
```

### Ejemplo de Respuesta Exitosa
```json
{
  "jsonrpc": "2.0",
  "id": "call-cat-01",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "Se encontraron los siguientes productos:\n- ID: 1e9e8ef04dbcff4541ed26657ea517e5 | Categoría: relojes_presentes | Precio: $59.90 | Stock: 42 unidades disponibles.\n- ID: 2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e | Categoría: deporte_ocio | Precio: $89.50 | Stock: 15 unidades disponibles."
      }
    ],
    "isError": false
  }
}
```
