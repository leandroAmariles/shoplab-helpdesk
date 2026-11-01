package com.shoplab.helpdesk.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.model.enums.NoContextMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A wrong value in the configuration stops the start-up and names the property (US3, FR-004). */
class RagConfigurationBindingTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> { })
            .withConfiguration(UserConfigurations.of(ChatConfig.class));

    @Test
    void withoutConfigurationTheDefaultsApply() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RagProperties rag = context.getBean(RagProperties.class);
            assertThat(rag.topK()).isEqualTo(4);
            assertThat(rag.similarityThreshold()).isEqualTo(0.6);
            assertThat(rag.maxContextCharacters()).isEqualTo(8000);
        });
    }

    @Test
    void theNoContextModeIsBoundFromItsKebabCaseName() {
        runner.withPropertyValues("shoplab.chat.rag.no-context-mode=model").run(context ->
                assertThat(context.getBean(RagProperties.class).noContextMode())
                        .isEqualTo(NoContextMode.MODEL));
        runner.run(context -> assertThat(context.getBean(RagProperties.class).noContextMode())
                .isEqualTo(NoContextMode.FIXED_MESSAGE));
    }

    @Test
    void theValuesInTheConfigurationAreBound() {
        runner.withPropertyValues("shoplab.chat.rag.top-k=7", "shoplab.chat.rag.similarity-threshold=0.55",
                "shoplab.chat.rag.max-context-characters=1200").run(context -> {
                    RagProperties rag = context.getBean(RagProperties.class);
                    assertThat(rag.topK()).isEqualTo(7);
                    assertThat(rag.similarityThreshold()).isEqualTo(0.55);
                    assertThat(rag.maxContextCharacters()).isEqualTo(1200);
                });
    }

    @Test
    void aTopKOfZeroFailsTheStartUpNamingThePropertyAndTheRange() {
        runner.withPropertyValues("shoplab.chat.rag.top-k=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause().hasMessageContaining("shoplab.chat.rag.top-k").hasMessageContaining("between 1 and 20");
        });
    }

    @Test
    void aTopKAboveTwentyFailsTheStartUp() {
        runner.withPropertyValues("shoplab.chat.rag.top-k=21").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void aThresholdAboveOneFailsTheStartUpNamingTheProperty() {
        runner.withPropertyValues("shoplab.chat.rag.similarity-threshold=1.5").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("shoplab.chat.rag.similarity-threshold");
        });
    }
}
