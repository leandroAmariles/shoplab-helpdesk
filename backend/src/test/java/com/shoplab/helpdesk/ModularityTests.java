package com.shoplab.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    private static final Set<String> EXPECTED_MODULES = Set.of("chat", "retrieval", "tools", "tickets");

    private final ApplicationModules modules = ApplicationModules.of(ShopLabHelpdeskApplication.class);

    @Test
    void detectsExactlyTheExpectedModules() {
        Set<String> detected = modules.stream()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet());

        assertThat(detected).isEqualTo(EXPECTED_MODULES);
    }

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }
}
