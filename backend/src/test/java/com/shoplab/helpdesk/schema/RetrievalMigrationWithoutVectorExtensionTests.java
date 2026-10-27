package com.shoplab.helpdesk.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.shared.exception.ModuleMigrationException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchema;
import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchemaMigrator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** A database without pgvector stops the start-up and the error names the retrieval module (specs/LAB-19, FR-014). */
class RetrievalMigrationWithoutVectorExtensionTests {

    @Test
    void startingWithoutThePgvectorExtensionAbortsNamingRetrieval() {
        try (PostgreSQLContainer plainPostgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16"))) {
            plainPostgres.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(plainPostgres.getJdbcUrl(),
                    plainPostgres.getUsername(), plainPostgres.getPassword());
            ModuleSchemaMigrator migrator = new ModuleSchemaMigrator(List.of(new ModuleSchema("retrieval")),
                    dataSource);

            assertThatThrownBy(migrator::migrate)
                    .isInstanceOfSatisfying(ModuleMigrationException.class, failure -> {
                        assertThat(failure.getModule()).isEqualTo("retrieval");
                        assertThat(failure.getResponseCode()).isEqualTo(ResponseCodeEnum.DATABASE_MIGRATION_FAILED);
                    });
        }
    }
}
