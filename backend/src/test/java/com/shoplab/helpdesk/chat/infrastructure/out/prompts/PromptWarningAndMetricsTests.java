package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.usecase.PromptValidator;
import com.shoplab.helpdesk.chat.domain.model.constants.PromptConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.infrastructure.support.KeyedWarnThrottle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

/** What the refresh says about itself: a warning per prompt and reason (never the text), and its metrics (FR-010). */
class PromptWarningAndMetricsTests {

    private static final int MAX_CHARACTERS = 300;
    private static final String SECRET_TEXT = "TEXTO-SECRETO-DEL-PROMPT";

    @RegisterExtension
    static WireMockExtension langfuse = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger catalogLogger = (Logger) LoggerFactory.getLogger(CachedPromptCatalog.class);
    private CachedPromptCatalog catalog;

    @BeforeEach
    void setUp() {
        langfuse.resetAll();
        log.start();
        catalogLogger.addAppender(log);
        catalogLogger.setLevel(Level.INFO);
        catalog = new CachedPromptCatalog(
                new LangfusePromptClient(WebClient.builder(), LangfuseStubs.properties(langfuse, Duration.ofSeconds(3))),
                new PromptValidator(MAX_CHARACTERS), new PromptDefaults(new PromptValidator(20_000)), "production",
                meters, new KeyedWarnThrottle(Duration.ofMinutes(5)));
    }

    @AfterEach
    void tearDown() {
        catalogLogger.detachAppender(log);
    }

    private List<String> warnings() {
        return log.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private double count(PromptName name, String result) {
        var counter = meters.find(PromptConstants.REFRESH_METRIC)
                .tags(PromptConstants.PROMPT_TAG, name.langfuseName(), PromptConstants.RESULT_TAG, result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aLangfuseThatKeepsFailingIsToldOncePerPromptAndNotOncePerRefresh() {
        for (PromptName name : PromptName.values()) {
            LangfuseStubs.status(langfuse, name, "label", "production", 503);
        }

        for (int i = 0; i < 6; i++) {
            catalog.refreshAll().block(Duration.ofSeconds(5));
        }

        assertThat(warnings()).hasSize(4);
        assertThat(warnings()).allSatisfy(message -> assertThat(message)
                .contains("reason=failed", "http-503").doesNotContain(LangfuseStubs.SECRET_KEY));
        assertThat(warnings()).anySatisfy(message -> assertThat(message).contains("prompt=chat-system"));
    }

    @Test
    void anInvalidPromptIsToldWithItsNameVersionAndReasonButNeverItsText() {
        LangfuseStubs.text(langfuse, PromptName.CHAT_SYSTEM, "label", "production", 7,
                SECRET_TEXT.repeat(MAX_CHARACTERS));

        catalog.refresh(PromptName.CHAT_SYSTEM).block(Duration.ofSeconds(5));
        catalog.refresh(PromptName.CHAT_SYSTEM).block(Duration.ofSeconds(5));

        assertThat(warnings()).singleElement().satisfies(message -> assertThat(message)
                .contains("prompt=chat-system", "reason=too-long", "version=7")
                .doesNotContain("TEXTO-SECRETO"));
    }

    @Test
    void eachReasonHasItsOwnWarning() {
        LangfuseStubs.text(langfuse, PromptName.CHAT_SYSTEM, "label", "production", 1, " ");
        LangfuseStubs.text(langfuse, PromptName.SMALL_TALK, "label", "production", 1, "sin marcador");
        LangfuseStubs.text(langfuse, PromptName.CONTEXT, "label", "production", 1, "{context} {query} {cliente}");
        LangfuseStubs.notFound(langfuse, PromptName.EMPTY_CONTEXT, "label", "production");

        catalog.refreshAll().block(Duration.ofSeconds(5));

        assertThat(warnings()).hasSize(4);
        assertThat(warnings()).anySatisfy(message -> assertThat(message).contains("chat-system", "reason=blank"));
        assertThat(warnings()).anySatisfy(message -> assertThat(message)
                .contains("chat-small-talk", "reason=missing-placeholder"));
        assertThat(warnings()).anySatisfy(message -> assertThat(message)
                .contains("chat-context", "reason=not-renderable"));
        assertThat(warnings()).anySatisfy(message -> assertThat(message)
                .contains("chat-empty-context", "reason=missing"));
    }

    @Test
    void theRefreshesAreCountedByPromptAndResult() {
        LangfuseStubs.allValid(langfuse, 1);
        catalog.refreshAll().block(Duration.ofSeconds(5));
        catalog.refreshAll().block(Duration.ofSeconds(5));
        langfuse.resetAll();
        LangfuseStubs.status(langfuse, PromptName.CHAT_SYSTEM, "label", "production", 500);
        catalog.refresh(PromptName.CHAT_SYSTEM).block(Duration.ofSeconds(5));

        assertThat(count(PromptName.CHAT_SYSTEM, PromptConstants.RESULT_UPDATED)).isEqualTo(1);
        assertThat(count(PromptName.CHAT_SYSTEM, PromptConstants.RESULT_UNCHANGED)).isEqualTo(1);
        assertThat(count(PromptName.CHAT_SYSTEM, PromptConstants.RESULT_FAILED)).isEqualTo(1);
        assertThat(count(PromptName.CONTEXT, PromptConstants.RESULT_UPDATED)).isEqualTo(1);
        assertThat(count(PromptName.CONTEXT, PromptConstants.RESULT_FAILED)).isZero();
    }

    @Test
    void theLoadedVersionIsAGaugeThatIsZeroWhileTheDefaultIsServed() {
        double before = meters.get(PromptConstants.LOADED_VERSION_METRIC)
                .tag(PromptConstants.PROMPT_TAG, "chat-system").gauge().value();
        LangfuseStubs.allValid(langfuse, 4);
        catalog.refreshAll().block(Duration.ofSeconds(5));
        double after = meters.get(PromptConstants.LOADED_VERSION_METRIC)
                .tag(PromptConstants.PROMPT_TAG, "chat-system").gauge().value();

        assertThat(before).isZero();
        assertThat(after).isEqualTo(4);
    }

    @Test
    void theMetricsHaveNoFreeTextTags() {
        LangfuseStubs.text(langfuse, PromptName.CHAT_SYSTEM, "label", "production", 1, SECRET_TEXT.repeat(400));

        catalog.refresh(PromptName.CHAT_SYSTEM).block(Duration.ofSeconds(5));

        assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getValue()).doesNotContain("TEXTO-SECRETO")));
    }
}
