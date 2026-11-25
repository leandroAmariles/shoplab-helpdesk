# Validación: LAB-129

Evidencia de cada paso de [quickstart.md](quickstart.md). Un paso sin resultado está pendiente.

## Riesgos previos (T003, T004)

- **Paquetes entre repositorios**: run 45678575081 de `shoplab-gateway`, rama temporal ya borrada: `BUILD SUCCESS`.
- **Cliente generado**: proyecto de prueba con Boot 4.1.2 sobre el JAR modificado: 1 prueba, 0 fallos.

## 1. Plataforma (US1, SC-008)

### Plantilla (T005–T008, T012, T013)

- `./mvnw verify` en verde en la plantilla y en `shoplab-retrieval-service` recién renombrado; cobertura 94 %.
- Imagen construida con la credencial como secreto de construcción; arranca como UID 10001, `readiness` 200 y
  `/api/x` sin token 404 (no 401).

### Workflows (T009, T010)

- Rama temporal de `shoplab-retrieval-service` apuntando a la rama de `shoplab-ci` (run 45680327003): compilación,
  pruebas, cobertura, Qodana y Trivy en verde. Publicación y firma de la imagen: sin probar todavía.

### `smoke` (T019–T022, 2026-11-25)

- Intento 1 (run 45680227212): `apply` correcto, `destroy` agotado.
- Run 45686618767: `apply` agotado dos veces pese al espejo inyectado. Intento 2 sin ejecutar y retirado.
- Job quitado en `shoplab-infra#40`; `ci-gate`, `static`, `kustomize`, `trivy` y `plan` en verde. Sin fusionar.

### PostgreSQL del compose (T024, T025, 2026-11-25)

Proyecto aislado `lab129pg` con un archivo de entorno vacío; la configuración resuelta usa `postgres:17.7` en
`litellm-postgres` y `langfuse-postgres`.

- Volúmenes nuevos: los dos sanos.
- Copias de los volúmenes 17 existentes: los dos sanos (116 y 84 tablas). Los originales no se tocaron y los
  volúmenes de prueba se borraron.
- `postgres` (pgvector 16) no cambia: su valor por defecto ya era el de `.env`.

### Servicio (T026–T033, 2026-11-25)

- `./mvnw clean verify`: 272 pruebas, 0 fallos, cobertura de líneas 97 %; stubs generados; `docker build` correcto.
- Antes del arreglo, la consulta de 4000 caracteres respondía **414** y `x-shoplab-request-id` no llegaba al gateway.

## 2. Línea base (FR-023)

## 3. Migración (SC-002)

Scripts probados contra un PostgreSQL desechable (`bash infra/postgres/tests/test-retrieval-migration.sh`,
2026-11-25): provisión repetible; el rol `retrieval` no entra en `shoplab`; copia idéntica con las tablas a su
nombre; sin `--replace` se niega; una fila alterada hace fallar la verificación; el origen no cambia. Envoltorios
probados en Bash y PowerShell. **La migración de los datos reales está pendiente (T046).**

## 4. Paridad en paralelo (US2)

## 5. Corte (US3, SC-001, SC-003)

## 6. Fallos del chat (US4)

## 7. Reversión (SC-007)

## 8. Dos instancias (US5, SC-006)

`IndexingAcrossInstancesIntegrationTest` y `JdbcIndexingLockAdapterTest` (2026-11-25): con dos instancias cada
documento se indexa una vez; otra continúa cuando caduca el arriendo de una muerta; quien pierde el cerrojo se
detiene sin guardar. `./mvnw clean verify`: 281 pruebas, 0 fallos, cobertura 97 %.

## 9. Latencia (SC-005)

## 10. Despliegue independiente y retirada (SC-004, FR-021)
