package com.shoplab.helpdesk.shared.infrastructure.out.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModuleSchemaTests {

    @ParameterizedTest
    @ValueSource(strings = {"chat", "retrieval", "orders_2", "a"})
    void acceptsLowerCaseNames(String name) {
        assertThat(new ModuleSchema(name).name()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "", "  ", "Chat", "my-module", "1orders", "orders;drop"})
    void rejectsNamesThatAreNotAModuleSchema(String name) {
        assertThatThrownBy(() -> new ModuleSchema(name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("module schema");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new ModuleSchema(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locationIsDerivedFromTheName() {
        assertThat(new ModuleSchema("orders").location()).isEqualTo("classpath:db/migration/orders");
    }
}
