# Contrato HTTP de shoplab-catalog-service

Contrato REST formal para `catalog-service`. La definición OpenAPI completa reside en `shoplab-contracts` bajo `openapi/catalog/v1/catalog-api.yaml`.

## Endpoints

| Método y Ruta | Descripción | Parámetros / Query | Éxito | Errores Tipados |
|---|---|---|---|---|
| `GET /api/catalog/products` | Búsqueda y paginación determinista de productos | `page` (default 0), `size` (default 50, max 200), `category`, `query`, `inStock` (bool), `sort` | `200 OK` `ProductPageResponse` | `422 SLHD_CATALOG_00002`<br/>`503 SLHD_CATALOG_00003` |
| `GET /api/catalog/products/{id}` | Ficha técnica y stock completo de un producto | `id` (path) | `200 OK` `ProductDetailResponse` | `404 SLHD_CATALOG_00001`<br/>`503 SLHD_CATALOG_00003` |
| `GET /api/catalog/products/{id}/stock` | Consulta ligera de inventario y precio | `id` (path) | `200 OK` `ProductStockResponse` | `404 SLHD_CATALOG_00001`<br/>`503 SLHD_CATALOG_00003` |
| `GET /api/catalog/categories` | Catálogo de categorías con nombres en EN/PT | ninguno | `200 OK` `List<CategoryResponse>` | `503 SLHD_CATALOG_00003` |

## Modelos de Respuesta JSON

### `ProductPageResponse`
```json
{
  "content": [
    {
      "productId": "1e9e8ef04dbcff4541ed26657ea517e5",
      "categoryName": "perfumaria",
      "categoryNameEn": "perfumery",
      "referencePrice": 59.90,
      "stock": 42,
      "weightG": 225,
      "dimensions": {
        "lengthCm": 16,
        "heightCm": 10,
        "widthCm": 14
      }
    }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 32951,
  "totalPages": 660,
  "last": false
}
```

### `ProductStockResponse`
```json
{
  "productId": "1e9e8ef04dbcff4541ed26657ea517e5",
  "referencePrice": 59.90,
  "stock": 42,
  "available": true,
  "updatedAt": "2026-11-26T12:00:00Z"
}
```

## Códigos de Error Tipados

| Código | HTTP Status | Causa |
|---|---|---|
| `SLHD_CATALOG_00001` | 404 Not Found | Producto con el identificador solicitado no existe en catálogo. |
| `SLHD_CATALOG_00002` | 422 Unprocessable Entity | Parámetros de paginación o búsqueda inválidos (`size < 1`, `page < 0`). |
| `SLHD_CATALOG_00003` | 503 Service Unavailable | Almacén de base de datos no disponible o timeout de conexión. |
