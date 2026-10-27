package com.shoplab.helpdesk.shared.infrastructure.out.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.shared.exception.ModuleMigrationException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.support.SharedPostgres;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A missing database or a failing script stops the start-up and says which module it was (specs/LAB-105, D-4). */
class ModuleSchemaMigratorFailureTests {

    @AfterEach
    void dropTheBrokenSchema() throws SQLException {
        PostgreSQLContainer postgres = SharedPostgres.instance();
        try (Connection connection = postgres.createConnection("");
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS broken CASCADE");
        }
    }

    @Test
    void anUnreachableDatabaseNamesTheFirstModule() {
        DriverManagerDataSource closedPort = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:1/shoplab?connectTimeout=2", "shoplab", "irrelevant");
        ModuleSchemaMigrator migrator = new ModuleSchemaMigrator(
                List.of(new ModuleSchema("orders"), new ModuleSchema("chat")), closedPort);

        assertThatThrownBy(migrator::migrate)
                .isInstanceOfSatisfying(ModuleMigrationException.class, failure -> {
                    assertThat(failure.getResponseCode()).isEqualTo(ResponseCodeEnum.DATABASE_UNAVAILABLE);
                    assertThat(failure.getModule()).isEqualTo("chat");
                    assertThat(failure).hasMessageContaining("chat");
                });
    }

    @Test
    void aFailingScriptNamesItsModuleAndKeepsTheCause() {
        PostgreSQLContainer postgres = SharedPostgres.instance();
        DriverManagerDataSource database = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ModuleSchemaMigrator migrator = new ModuleSchemaMigrator(List.of(new ModuleSchema("broken")), database);

        assertThatThrownBy(migrator::migrate)
                .isInstanceOfSatisfying(ModuleMigrationException.class, failure -> {
                    assertThat(failure.getResponseCode()).isEqualTo(ResponseCodeEnum.DATABASE_MIGRATION_FAILED);
                    assertThat(failure.getModule()).isEqualTo("broken");
                    assertThat(failure).hasMessageContaining("broken");
                    assertThat(failure.getCause()).isNotNull();
                });
    }
}
