package com.shoplab.template.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.template.support.TestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class FlywayMigrationIntegrationTest {

    @Test
    void appliesTheMigrationsToTheSchemaOfTheService() {
        var postgres = TestDatabase.instance();

        var result = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("flyway_check")
                .defaultSchema("flyway_check")
                .locations("classpath:db/migration/template")
                .load()
                .migrate();

        assertThat(result.success).isTrue();
        assertThat(result.targetSchemaVersion).isEqualTo("1");
    }
}
