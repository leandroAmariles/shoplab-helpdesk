# Contrato de Enrutamiento perimetral en Gateway

Especificación de enrutamiento para `shoplab-gateway` (LAB-107).

## Configuración de Ruta

En `shoplab-gateway/src/main/resources/application.yml`:

```yaml
shoplab:
  gateway:
    routes:
      catalog:
        prefix: /api/catalog
        # En dev/k8s se inyecta SHOPLAB_GATEWAY_ROUTES_CATALOG_URI=http://catalog-service:8080
```

## Políticas de Proxy

1. **Reenvío de Prefijo**: Peticiones hacia `/api/catalog/**` se reenvían directamente a `catalog-service:8080/api/catalog/**`.
2. **Propagación de Cabeceras**:
   - `X-Request-Id`: Se genera en el gateway si falta y se propaga obligatoriamente a `catalog-service`.
   - `traceparent`: Propagada para trazas distribuidas OpenTelemetry.
3. **Timeouts**:
   - Timeout de conexión: 2.000 ms.
   - Timeout de respuesta por defecto: 10.000 ms.
4. **Resiliencia**: En caso de indisponibilidad de `catalog-service`, el gateway responde `503 Service Unavailable` o `504 Gateway Timeout` con formato JSON estándar sin afectar a otras rutas operativas (`/api/chat`, `/api/eval`).
