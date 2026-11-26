# ADR-0033: Extracción de `retrieval-service`

- Estado: aceptado
- Fecha: 2026-11-26

## Contexto

LAB-129 saca la base de conocimiento del monolito a su propio repositorio, `shoplab-retrieval-service`
([ADR-0031](0031-extraccion-temprana-de-servicios.md)), detrás del gateway
([ADR-0032](0032-gateway-estrangulador.md)). Hay que decidir dónde viven sus datos, qué pasa con la guarda de indexado
que solo valía para una instancia ([ADR-0017](0017-embeddings-solo-en-retrieval.md)), cómo se hace el corte sin perder
datos y cómo se vuelve atrás.

## Decisión

- **Base propia en el mismo servidor.** Base `retrieval` con un rol `retrieval` que es su dueño; `REVOKE CONNECT`
  a `PUBLIC` en las dos bases. La extensión `vector` la crea el superusuario en un paso de un solo disparo
  (`retrieval-db-init` en el compose). En `dev`, el PostgreSQL es el mismo contenedor del host que en local.
- **Migrar copiando, no recalculando.** `pg_dump` del esquema `retrieval` y restauración en la base nueva, incluido
  el historial de Flyway, con verificación por recuento y suma de comprobación de cada tabla. No se calcula ningún
  embedding.
- **Cerrojo de indexado como arriendo en la base.** Una fila en `indexing_lock` con dueño y caducidad (90 s, con el
  reloj de la base), renovada antes de cada lote. Sustituye a la guarda en memoria y al `FOR UPDATE SKIP LOCKED` que
  proponía ADR-0017, que habría retenido una transacción abierta durante la llamada al modelo. La comprobación por
  `updated_at` sigue protegiendo los datos.
- **El chat consulta por HTTP** con el cliente generado del contrato publicado en `shoplab-contracts`, con un tiempo
  máximo de 15 s. Cualquier fallo es el error de recuperación de siempre (`SLHD_CHAT_00004`).
- **Corte en dos releases.** Primero, el módulo intacto más el cliente, elegido por configuración. El corte es un solo
  commit en `shoplab-infra` (rutas `documents` y `knowledge` del gateway y modo `http` del chat). Después, una segunda
  release retira el módulo.
- **El `smoke` de `shoplab-infra` sale de la CI.** `terraform apply` no terminaba en el runner y no se pudo corregir en
  dos intentos; queda `make smoke` para ejecutarlo en local.

## Consecuencias

- La separación de la base es de credenciales, no de recursos: comparte servidor, memoria y copias de seguridad, y el
  rol `shoplab` del monolito es superusuario, así que técnicamente todavía puede leerla. Ningún límite de conexiones
  ni de recursos separa las dos.
- Como `dev` y local comparten el PostgreSQL, un `retrieval-service` local y el de `dev` trabajan sobre los mismos
  documentos; el arriendo evita que indexen a la vez.
- Tras la caída de una instancia, el indexado puede esperar hasta 90 s a que caduque su arriendo.
- La búsqueda del chat añade un salto de red y una dependencia más para estar disponible.
- **Volver atrás.** Antes de retirar el módulo bastaba con revertir el commit del corte. Después exige además la imagen
  anterior del backend (`v0.29.0`). En los dos casos, lo subido al servicio desde el corte no vuelve al esquema
  antiguo: hay que subirlo otra vez. El ensayo de la reversión no se hizo.
- El esquema `retrieval` de la base `shoplab` queda sin uso y se borra a mano, con copia previa (ver
  [F5-mover-rutas-al-gateway.md](../fases/F5-mover-rutas-al-gateway.md#retirar-el-esquema-antiguo-de-retrieval)).
- La CI de `shoplab-infra` ya no prueba un `apply` completo ni la sincronización de Argo CD.
- De ADR-0017 sigue vigente todo salvo la guarda de una sola instancia; lo que allí es del módulo `retrieval` es ahora
  del servicio.
