package com.shoplab.helpdesk.shared.infrastructure.out.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ModuleSchemaMigratorTests {

    private final DataSource dataSource = mock(DataSource.class);

    @Test
    void ordersTheSchemasAlphabetically() {
        ModuleSchemaMigrator migrator = new ModuleSchemaMigrator(
                List.of(new ModuleSchema("tickets"), new ModuleSchema("chat"), new ModuleSchema("orders")),
                dataSource);

        assertThat(migrator.orderedSchemas()).extracting(ModuleSchema::name)
                .containsExactly("chat", "orders", "tickets");
    }

    @Test
    void rejectsTwoModulesWithTheSameSchema() {
        ModuleSchemaMigrator migrator = new ModuleSchemaMigrator(
                List.of(new ModuleSchema("chat"), new ModuleSchema("chat")), dataSource);

        assertThatThrownBy(migrator::orderedSchemas)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chat");
    }

    @Test
    void doesNothingWithoutModules() {
        new ModuleSchemaMigrator(List.of(), dataSource).migrate();

        verifyNoInteractions(dataSource);
    }
}
