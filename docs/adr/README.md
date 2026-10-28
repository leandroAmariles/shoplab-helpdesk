# Registro de decisiones de arquitectura (ADR)

Aquí se registran las decisiones de arquitectura significativas del proyecto (base de datos,
mensajería, extracción de módulos, estrategia de recuperación, plataforma). Cada decisión es un
archivo Markdown que explica su contexto, lo que se decidió y sus consecuencias.

## Índice

| Nº | Título | Estado |
| --- | --- | --- |
| 0001 | [Monolito modular primero](0001-monolito-modular-primero.md) | aceptado |
| 0002 | [PostgreSQL con pgvector como única base de datos inicial](0002-postgres-pgvector.md) | aceptado |
| 0003 | [Ollama como servidor local de modelos](0003-ollama-modelos-locales.md) | aceptado |
| 0004 | [Spring Boot 4 con Spring AI 2.0 y Spring Modulith para el backend](0004-spring-boot-4-spring-ai-2.md) | aceptado |
| 0005 | [Terraform sobre Floci para practicar un EKS local](0005-terraform-floci-eks-local.md) | aceptado |
| 0006 | [GitOps pull con repositorio de infraestructura separado](0006-gitops-pull-repo-infra-separado.md) | aceptado (parcialmente sustituido por 0008) |
| 0007 | [Despliegue continuo a dev con una GitHub App con bypass](0007-despliegue-continuo-dev-app-con-bypass.md) | aceptado (parcialmente sustituido por 0008 y 0012) |
| 0008 | [Un solo ambiente dev en shoplab-infra](0008-un-solo-ambiente-dev.md) | aceptado |
| 0009 | [Observabilidad base con una sola fuente de dashboards entre dos repositorios](0009-observabilidad-base-fuente-unica-de-dashboards.md) | aceptado |
| 0010 | [Módulo abierto `shared` para errores y correlación comunes](0010-modulo-shared-errores.md) | aceptado |
| 0011 | [Web reactiva con persistencia JDBC](0011-web-reactiva-persistencia-jdbc.md) | aceptado |
| 0012 | [Dev despliega solo versiones de release](0012-dev-despliega-solo-versiones-de-release.md) | aceptado |
| 0013 | [Angular Material como biblioteca de componentes del frontend](0013-biblioteca-ui-angular-material.md) | aceptado |
| 0014 | [Streaming por fetch y Markdown saneado en el chat](0014-markdown-seguro-en-el-chat.md) | aceptado |
| 0015 | [Límites de contexto y estrategia de repositorios](0015-limites-de-contexto-y-repositorios.md) | aceptado |
| 0016 | [Troceado por encabezados en `ingest` y contrato síncrono con `retrieval`](0016-troceado-por-encabezados-e-ingesta-sincrona.md) | aceptado |

La tabla se actualiza a mano en la misma PR que añade o cambia un ADR. La plantilla
([`0000-plantilla.md`](0000-plantilla.md)) no es una decisión y no figura en el índice.

## Estados

- `propuesto`: en discusión, aún no vigente.
- `aceptado`: vigente.
- `sustituido por ADR-XXXX`: reemplazado por otro ADR; se conserva como historial.

Un ADR con estado `aceptado` debe tener las tres secciones (Contexto, Decisión, Consecuencias) con
contenido.

## Numeración

- Cuatro dígitos, secuencial, y nunca se reutiliza un número.
- El `0000` está reservado a la plantilla; los ADR reales empiezan en `0001`.
- Nombre de archivo: `NNNN-titulo-corto.md`, en minúsculas y con guiones, de modo que el orden
  alfabético coincida con el numérico.
- Si dos ramas crean el mismo número, se confirma al integrar: quien integra después renumera su
  ADR (archivo, título y referencias).

## Cómo añadir un ADR

1. Copiar [`0000-plantilla.md`](0000-plantilla.md) como `NNNN-titulo-corto.md` con el siguiente
   número libre.
2. Rellenar título, estado, fecha (`AAAA-MM-DD`) y las secciones Contexto, Decisión y
   Consecuencias; borrar el comentario de instrucciones.
3. Añadir la fila al índice de este archivo.
4. Escribirlo en español, igual que el resto de la documentación.

## Cómo cambiar una decisión

Un ADR aceptado no se edita ni se borra (salvo erratas). Para cambiarlo:

1. Crear un ADR nuevo con la línea `- Sustituye a: ADR-YYYY`.
2. En el ADR original, cambiar solo la línea de estado a `sustituido por ADR-XXXX`.
3. Actualizar el estado en el índice.
