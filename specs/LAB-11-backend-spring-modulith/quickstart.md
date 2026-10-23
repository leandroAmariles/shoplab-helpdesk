# Quickstart: validar el backend

## Prerrequisitos

- JDK 21 o superior (`java -version`). Maven no es necesario: se usa el wrapper.
- Ninguna infraestructura levantada (ni Postgres ni Ollama).

## 1. Construir y probar (US1, US3, SC-001/002)

```bash
cd backend
./mvnw verify          # Windows: mvnw.cmd verify
```

Esperado: `BUILD SUCCESS`; se ejecutan `ModularityTests` y `ShopLabHelpdeskApplicationTests`;
Checkstyle, PMD y el umbral de JaCoCo (80 %) pasan.

## 2. Arrancar y detener (US1.2, SC-004)

```bash
./mvnw spring-boot:run
curl http://localhost:8080/actuator/health     # {"status":"UP"}
```

Esperado: arranca sin errores sin servicios externos; Ctrl+C lo detiene limpiamente.

## 3. Módulos (US2, SC-003)

Comprobar que `ModularityTests` valida exactamente `chat`, `retrieval`, `tools` y `tickets`.

Prueba negativa: en `chat` crear una clase que importe un tipo de un subpaquete interno de
`tickets` (o un ciclo `chat` <-> `tickets`), ejecutar `./mvnw test`.
Esperado: falla `ModularityTests` indicando los módulos implicados. Revertir el cambio.

## 4. Java antiguo (caso borde)

Con JDK 17: `./mvnw verify` falla con un mensaje del Enforcer que exige Java 21 o superior.

## 5. ADR (US4, SC-005)

Abrir `docs/adr/0004-spring-boot-4-spring-ai-2.md`: contexto, decisión, alternativas,
consecuencias y mención de Spring AI 2.0 GA (junio 2026).

## 6. Limpieza (SC-006)

`git status --short` tras construir: no aparece `backend/target/`.
