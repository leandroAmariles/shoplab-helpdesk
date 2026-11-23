# Quickstart & Verification Guide: Extracción temprana y mapa de servicios

**Feature**: `LAB-128-early-extraction-service-map`  
**Date**: 2026-11-23  
**Status**: Draft  

Esta guía describe los pasos necesarios para revisar, validar y verificar la completitud y coherencia de los artefactos producidos en la especificación e implementación de LAB-128.

---

## 1. Verificación del registro del ADR (ADR-0031)

### Pasos de comprobación
1. **Existencia del archivo ADR**:
   Comprobar que existe el archivo `docs/adr/0031-extraccion-temprana-de-servicios.md`.
2. **Encabezado y formato**:
   Verificar que cumple el formato de `docs/adr/0000-plantilla.md`:
   - Título: `# ADR-0031: Extracción temprana de servicios`
   - Estado: `aceptado`
   - Fecha: `2026-11-23`
   - Secciones: Contexto, Decisión, Consecuencias redactadas en español.
   - Nota explícita de equivalencia con Jira LAB-128 (F5-21).
3. **Actualización de ADRs previos**:
   - `docs/adr/0001-monolito-modular-primero.md`: estado actualizado a `aceptado (parcialmente sustituido por ADR-0031)`.
   - `docs/adr/0015-limites-de-contexto-y-repositorios.md`: estado actualizado a `aceptado (parcialmente sustituido por ADR-0031)`.
4. **Índice general**:
   - `docs/adr/README.md`: contiene la fila para `0031` con su enlace relativo y los estados actualizados de `0001` y `0015`.

---

## 2. Verificación del mapa de servicios (`docs/mapa-servicios.md`)

### Pasos de comprobación
1. **Existencia del documento**:
   Comprobar que existe el archivo `docs/mapa-servicios.md`.
2. **Cobertura de los 8 servicios**:
   Verificar que contiene fichas completas e individualizadas para:
   - `chat-service`
   - `retrieval-service`
   - `eval-service`
   - `catalog-service`
   - `order-service`
   - `ticket-service`
   - `gateway`
   - `notification-service`
3. **Elementos obligatorios por servicio**:
   Cada ficha de servicio debe detallar taxativamente:
   - Nombre de repositorio destino en GitHub (`shoplab-...`).
   - Esquema propio de base de datos PostgreSQL (y exclusividad de acceso).
   - APIs expuestas (REST / SSE / servidor MCP).
   - Eventos Kafka producidos y consumidos (conforme a LAB-108).
   - Matriz de dependencias técnicas e inter-servicio.
4. **Validación de reglas clave**:
   - Prohibición explícita de librerías comunes con lógica de dominio.
   - Mandato de uso de la plantilla de servicio de LAB-103 para código técnico base.
   - Exposición de herramientas de agente vía protocolo MCP.
   - Delimitación nítida entre `eval-service` (Java) y `shoplab-eval` (Python).

---

## 3. Comprobación automática de enlaces y formato

Para comprobar que no existen enlaces rotos entre la documentación generada y el resto del repositorio:

```powershell
# Verificar sintaxis y enlaces relativos en PowerShell
Get-ChildItem -Path docs/adr/*.md, docs/*.md | ForEach-Object {
    $content = Get-Content $_.FullName -Raw
    # Validar que los enlaces a ADR-0031 y mapa-servicios son coherentes
    if ($content -match '0031-extraccion-temprana-de-servicios\.md') {
        Write-Host "Enlace válido a ADR-0031 en: $($_.Name)"
    }
}
```

---

## 4. Criterio de aceptación de la suite de pruebas del proyecto

Dado que LAB-128 es una historia de especificación, arquitectura y gobernanza documental (no añade clases en `backend/` ni altera tablas antes de la ejecución de los servicios individuales):

1. **La suite del backend debe permanecer al 100 % en verde**:
   ```bash
   cd backend && ./mvnw test
   ```
2. **Los tests de arquitectura de Modulith deben seguir pasando**:
   ```bash
   cd backend && ./mvnw test -Dtest=ModularityTests,ModuleArchitectureTests
   ```
3. **Los linters y formatos no deben mostrar advertencias**:
   Los archivos Markdown deben cumplir con los saltos de línea LF y el límite de longitud razonable según las convenciones del repositorio.
