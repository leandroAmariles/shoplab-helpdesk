-- Migración inicial V1 para el esquema del microservicio
-- Nota: La tabla se crea automáticamente dentro del esquema soberano configurado en Flyway.

CREATE TABLE IF NOT EXISTS service_entity (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_service_entity_status ON service_entity(status);
