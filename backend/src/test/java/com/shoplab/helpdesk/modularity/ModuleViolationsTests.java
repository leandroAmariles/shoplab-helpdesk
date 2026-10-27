package com.shoplab.helpdesk.modularity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.fixtures.modularity.FixtureApplication;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/** Negative test: Modulith really reports a module that reaches into another one's internals (US4 scenario 3). */
class ModuleViolationsTests {

    @Test
    void usingAnInternalOfAnotherModuleIsReported() {
        ApplicationModules fixture = ApplicationModules.of(
                FixtureApplication.class, new ImportOption.OnlyIncludeTests());

        assertThat(fixture.stream()).hasSize(2);
        assertThatThrownBy(fixture::verify)
                .isInstanceOf(Violations.class)
                .hasMessageContaining("AlphaService")
                .hasMessageContaining("Hidden");
    }
}
