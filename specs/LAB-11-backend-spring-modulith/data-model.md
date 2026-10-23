# Data Model: Backend modular con Spring Modulith y Spring AI

Esta historia no introduce datos persistentes (sin base de datos). El "modelo" es la estructura
modular.

## Módulo de aplicación

| Campo | Descripción |
|---|---|
| `name` | Nombre del paquete: `chat`, `retrieval`, `tools`, `tickets` |
| `basePackage` | `com.shoplab.helpdesk.<name>` |
| `displayName` | Texto legible declarado en `@ApplicationModule` |
| `allowedDependencies` | Vacío por ahora (ningún módulo depende de otro) |
| `contents` | Solo `package-info.java`; sin lógica |

**Reglas de validación** (las aplica `ApplicationModules.verify()`):

- El conjunto de módulos detectados es exactamente `{chat, retrieval, tools, tickets}`.
- Sin dependencias cíclicas entre módulos.
- Ningún módulo accede a tipos internos de otro (solo a su paquete raíz expuesto).

## Verificación de arquitectura

Resultado binario por ejecución: pasa o falla indicando módulos y dependencias implicados.
Se ejecuta en `mvn verify` (fase de test).

## ADR de plataforma

Documento `docs/adr/0004-...md` con campos de la plantilla: Estado (`aceptado`), Fecha
(2026-10-23), Contexto, Decisión, Consecuencias.

## Transiciones de estado

No aplica. (El ADR nace como `aceptado`; cambios futuros se registran con un ADR que lo sustituya.)
