package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

class RetiredPromptVariablesCheckTests {

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RetiredPromptVariablesCheck.class);

    @BeforeEach
    void capture() {
        log.start();
        logger.addAppender(log);
    }

    @AfterEach
    void release() {
        logger.detachAppender(log);
    }

    @Test
    void anEnvironmentWithoutTheRetiredVariablesSaysNothing() {
        assertThat(new RetiredPromptVariablesCheck(new MockEnvironment()).warn()).isEmpty();
        assertThat(log.list).isEmpty();
    }

    @Test
    void eachRetiredVariableStillDefinedIsNamedWithWhereTheTextIsEditedNowAndNeverItsValue() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("SHOPLAB_CHAT_SYSTEM_PROMPT", "TEXTO-SECRETO-DEL-PROMPT")
                .withProperty("SHOPLAB_CHAT_SMALL_TALK_SYSTEM_PROMPT", "OTRO-TEXTO-SECRETO");

        assertThat(new RetiredPromptVariablesCheck(environment).warn())
                .containsExactly("SHOPLAB_CHAT_SYSTEM_PROMPT", "SHOPLAB_CHAT_SMALL_TALK_SYSTEM_PROMPT");

        assertThat(log.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("Langfuse", "chat-system")
                    .doesNotContain("SECRETO");
        });
        assertThat(log.list.get(0).getFormattedMessage()).contains("SHOPLAB_CHAT_SYSTEM_PROMPT");
        assertThat(log.list.get(1).getFormattedMessage()).contains("SHOPLAB_CHAT_SMALL_TALK_SYSTEM_PROMPT");
    }
}
