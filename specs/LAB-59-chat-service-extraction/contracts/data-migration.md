# Contract: Provisión y Migración de Datos de `chat`

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md)

Replica exactamente el patrón validado en LAB-129 (`retrieval-db-init.sh`, `retrieval-migrate.sh`, `retrieval-verify.sh`),
adaptado a las tablas y secuencias `IDENTITY` del esquema `chat`.

---

## 1. Provisión de base y rol (`infra/postgres/chat-db-init.sh`)

- **Idempotente** y de un solo disparo (`chat-db-init` en `infra/docker-compose.yml`).
- **Acciones**:
  1. Crea el rol `chat` (`LOGIN`) si no existe y actualiza su contraseña con `CHAT_DB_PASSWORD`.
  2. Crea la base `chat` con dueño `chat` si no existe.
  3. Ejecuta `REVOKE CONNECT ON DATABASE chat FROM PUBLIC; GRANT CONNECT ON DATABASE chat TO chat;`.
  4. Mantiene `REVOKE CONNECT ON DATABASE shoplab FROM PUBLIC;`.
- **Sin `pgvector`**: a diferencia de `retrieval`, no instala extensiones adicionales.

---

## 2. Copia del esquema (`infra/postgres/chat-migrate.sh` + `scripts/migrate-chat.{sh,ps1}`)

- **Precondición**: `chat-db-init` completado; ventana controlada de corte (sin escrituras nuevas al monolito).
- **Seguridad ante datos previos**: si `chat.conversation_turn` o `chat.feedback` ya tienen filas en destino, aborta con
  código `3` salvo que se pase `--replace` (solo antes del corte).
- **Procedimiento atómico**:
  1. `pg_dump -U "$DB_USER" -d "$SOURCE_DB" --schema=chat --format=custom --file="$dump"`
  2. Dentro de `psql --single-transaction` sobre `chat`:
     - `DROP SCHEMA IF EXISTS chat CASCADE;`
     - `SET ROLE chat;`
     - `pg_restore --no-owner --no-privileges --file=- "$dump"`
- **Origen intacto**: el esquema `chat` de `shoplab` solo se lee.

---

## 3. Verificación (`infra/postgres/chat-verify.sh` + `scripts/verify-chat-migration.{sh,ps1}`)

Compara `shoplab` (origen) vs. `chat` (destino) y termina con código `0` solo si todas las comprobaciones coinciden:

1. `chat.conversation_turn`: recuento y suma MD5 de todas las filas ordenadas por `id`.
2. `chat.feedback`: recuento y suma MD5 de todas las filas ordenadas por `id`.
3. `chat.flyway_schema_history`: recuento y suma MD5 hasta `max(installed_rank)` del origen.
4. **Secuencias `IDENTITY`**: verifica en destino que:
   - `pg_get_serial_sequence('chat.conversation_turn', 'id')` tiene `last_value >= COALESCE(MAX(id), 0)` (o `is_called = false` si la tabla está vacía).
   - `pg_get_serial_sequence('chat.feedback', 'id')` tiene `last_value >= COALESCE(MAX(id), 0)` (o `is_called = false` si la tabla está vacía).

---

## 4. Reversión y borrado manual del esquema antiguo

- **Reversión (antes de Release B)**: revertir el commit de corte en `shoplab-infra`. Las conversaciones y feedbacks
  registrados en `chat-service` durante el corte no se copian de vuelta al esquema `shoplab.chat`.
- **Borrado posterior (manual, fuera de releases)**: solo tras validar Release B en `dev`, con copia previa:

```bash
docker compose -f infra/docker-compose.yml exec -T postgres pg_dump -U shoplab -Fc shoplab > shoplab-antes-drop-chat.dump
docker compose -f infra/docker-compose.yml exec -T postgres \
  psql -U shoplab -d shoplab -c 'DROP SCHEMA chat CASCADE'
```
