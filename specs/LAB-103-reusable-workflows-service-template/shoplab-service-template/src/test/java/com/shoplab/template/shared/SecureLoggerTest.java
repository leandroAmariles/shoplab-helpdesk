package com.shoplab.template.shared;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shoplab.template.shared.logging.SecureLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SecureLoggerTest {

    private final Logger target = (Logger) LoggerFactory.getLogger(SecureLoggerTest.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final SecureLogger log = new SecureLogger(SecureLoggerTest.class);
    private Level previous;

    @BeforeEach
    void capture() {
        previous = target.getLevel();
        target.setLevel(Level.DEBUG);
        appender.start();
        target.addAppender(appender);
    }

    @AfterEach
    void restore() {
        target.detachAppender(appender);
        target.setLevel(previous);
    }

    @Test
    void masksSecretsInMessagesAndArgumentsAtEveryLevel() {
        log.debug("password=hunter2");
        log.info("header {}", "Bearer aaa.bbb.ccc");
        log.warn("card {}", "4111 1111 1111 1111");
        log.error("value {}", "token=abc123");
        log.error("secret=zzz", new IllegalStateException("boom"));

        assertThat(appender.list).hasSize(5)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.matches(".*(hunter2|aaa\\.bbb|4111 1111|abc123|zzz).*"));
    }

    @Test
    void keepsArgumentsThatAreNotText() {
        log.info("count {}", 7);

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly("count 7");
    }
}
