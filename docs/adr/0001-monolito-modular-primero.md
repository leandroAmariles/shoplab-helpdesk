# ADR-0001: Monolito modular primero

- Estado: aceptado (parcialmente sustituido por ADR-0031)
- Fecha: 2026-10-23

## Contexto

El objetivo final es una arquitectura de microservicios con Kafka y CQRS, pero las primeras fases se centran en aprender RAG. Empezar con microservicios añadiría complejidad operativa antes de tener algo que funcione.

## Decisión

Las fases F0 a F3 usan un monolito modular con Spring Modulith. Cada módulo publica eventos de dominio en proceso. A partir de F4 los módulos se extraen a servicios y los eventos pasan a Kafka.

## Consecuencias

- Se aprende RAG sin pelear con infraestructura distribuida.
- Los límites entre módulos se validan con los tests de arquitectura de Modulith, lo que facilita la extracción posterior.
- Hay que respetar la disciplina de no compartir tablas entre módulos.
- **Calendario sustituido por ADR-0031**: la extracción de servicios se adelanta a la finalización de F2 (S19–S24) en lugar de esperar a F4, debido al crecimiento asimétrico del backend y para permitir que `catalog`, `order` y `ticket` nazcan directamente como microservicios en F3/F4 sin construirse en el monolito.
