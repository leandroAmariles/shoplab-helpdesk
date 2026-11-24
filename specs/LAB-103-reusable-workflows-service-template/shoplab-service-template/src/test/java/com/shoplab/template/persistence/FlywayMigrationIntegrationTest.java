package com.shoplab.template.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shoplab")
            .withUsername("shoplab")
            .withPassword("shoplab");

    @Test
    @DisplayName("Debe ejecutar las migraciones de Flyway exitosamente sobre el esquema soberano")
    void should_apply_flyway_migrations_to_dedicated_schema() {
        String schemaName = "template";

        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schemaName)
                .defaultSchema(schemaName)
                .locations("classpath:db/migration/" + schemaName)
                .table("flyway_schema_history")
                .load();

        var result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(1);
        assertThat(result.targetSchemaVersion).isEqualTo("1");
    }
}
