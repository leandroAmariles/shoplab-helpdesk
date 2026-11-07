package com.shoplab.helpdesk.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The memory settings have safe defaults and a wrong value stops the start-up naming the property (FR-008). */
class MemoryPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> { })
            .withConfiguration(UserConfigurations.of(ChatConfig.class));

    @Test
    void withoutConfigurationTheDefaultsApply() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            MemoryProperties memory = context.getBean(MemoryProperties.class);
            assertThat(memory.maxTurns()).isEqualTo(5);
            assertThat(memory.maxHistoryCharacters()).isEqualTo(4000);
            assertThat(memory.retrievalTurns()).isEqualTo(1);
        });
    }

    @Test
    void theValuesInTheConfigurationAreBound() {
        runner.withPropertyValues("shoplab.chat.memory.max-turns=8", "shoplab.chat.memory.max-history-characters=900",
                "shoplab.chat.memory.retrieval-turns=2").run(context -> {
                    MemoryProperties memory = context.getBean(MemoryProperties.class);
                    assertThat(memory.maxTurns()).isEqualTo(8);
                    assertThat(memory.maxHistoryCharacters()).isEqualTo(900);
                    assertThat(memory.retrievalTurns()).isEqualTo(2);
                });
    }

    @Test
    void turnedOffWithZeroTurnsItStillStartsWithTheDefaultRetrievalTurns() {
        runner.withPropertyValues("shoplab.chat.memory.max-turns=0").run(context -> {
            assertThat(context).hasNotFailed();
            MemoryProperties memory = context.getBean(MemoryProperties.class);
            assertThat(memory.enabled()).isFalse();
            assertThat(memory.effectiveRetrievalTurns()).isZero();
        });
    }

    @Test
    void theRetrievalTurnsNeverExceedTheTurnsKept() {
        runner.withPropertyValues("shoplab.chat.memory.max-turns=2", "shoplab.chat.memory.retrieval-turns=4")
                .run(context -> assertThat(context.getBean(MemoryProperties.class).effectiveRetrievalTurns())
                        .isEqualTo(2));
    }

    @Test
    void zeroRetrievalTurnsTurnOffTheComposedQuery() {
        runner.withPropertyValues("shoplab.chat.memory.retrieval-turns=0").run(context ->
                assertThat(context.getBean(MemoryProperties.class).effectiveRetrievalTurns()).isZero());
    }

    @Test
    void aValueOutOfRangeFailsTheStartUpNamingTheProperty() {
        runner.withPropertyValues("shoplab.chat.memory.max-turns=51").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause().hasMessageContaining("shoplab.chat.memory.max-turns");
        });
        runner.withPropertyValues("shoplab.chat.memory.max-turns=-1").run(context ->
                assertThat(context).hasFailed());
        runner.withPropertyValues("shoplab.chat.memory.max-history-characters=199").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("shoplab.chat.memory.max-history-characters");
        });
        runner.withPropertyValues("shoplab.chat.memory.retrieval-turns=51").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("shoplab.chat.memory.retrieval-turns");
        });
        runner.withPropertyValues("shoplab.chat.memory.retrieval-turns=-1").run(context ->
                assertThat(context).hasFailed());
    }
}
