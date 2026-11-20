package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.usecase.PromptValidator;
import com.shoplab.helpdesk.chat.config.PromptProperties;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.infrastructure.support.KeyedWarnThrottle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

/** The start-up load is bounded and the periodic refresh is what carries a change of label to the chat. */
class PromptRefresherTests {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(400);
    private static final Duration INTERVAL = Duration.ofSeconds(30);
    private static final String PROMPTS_PATH = "/api/public/v2/prompts";

    @RegisterExtension
    static WireMockExtension langfuse = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    private final PromptDefaults defaults = new PromptDefaults(new PromptValidator(20_000));
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    @BeforeEach
    void reset() {
        langfuse.resetAll();
    }

    private LangfusePromptClient client(PromptProperties properties) {
        return new LangfusePromptClient(WebClient.builder(), properties);
    }

    private CachedPromptCatalog catalogOf(LangfusePromptClient client) {
        return new CachedPromptCatalog(client, new PromptValidator(20_000), defaults, "production",
                new SimpleMeterRegistry(), new KeyedWarnThrottle(Duration.ofMinutes(5)));
    }

    private PromptRefresher refresher(PromptProperties properties, boolean publishMissing, CachedPromptCatalog catalog) {
        LangfusePromptClient client = client(properties);
        return new PromptRefresher(catalog, new PromptPublisher(client, defaults, "production"), INTERVAL,
                properties.requestTimeout(), publishMissing, scheduler);
    }

    private Runnable scheduledTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(task.capture(), eq(INTERVAL.toMillis()), eq(INTERVAL.toMillis()),
                eq(TimeUnit.MILLISECONDS));
        return task.getValue();
    }

    @Test
    void loadsTheFourPromptsBeforeTheApplicationIsReadyAndSchedulesTheRefresh() {
        LangfuseStubs.allValid(langfuse, 2);
        PromptProperties properties = LangfuseStubs.properties(langfuse, REQUEST_TIMEOUT);
        CachedPromptCatalog catalog = catalogOf(client(properties));

        refresher(properties, false, catalog).run(null);

        for (PromptName name : PromptName.values()) {
            assertThat(catalog.current(name).reference().version()).isEqualTo(2);
        }
        scheduledTask();
    }

    @Test
    void aLangfuseThatIsDownCostsTheStartAtMostOneTimeoutAndTheChatBeginsWithTheDefaults() {
        PromptProperties down = new PromptProperties(true, "http://localhost:1", "pk", "sk", "production", INTERVAL,
                REQUEST_TIMEOUT, null, false, null);
        CachedPromptCatalog catalog = catalogOf(client(down));

        long started = System.nanoTime();
        refresher(down, false, catalog).run(null);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        for (PromptName name : PromptName.values()) {
            assertThat(catalog.current(name).reference().isDefault()).isTrue();
        }
        scheduledTask();
    }

    @Test
    void aSlowLangfuseDoesNotHoldTheStartPastTheTimeout() {
        langfuse.stubFor(get(urlPathMatching(PROMPTS_PATH + "/.*"))
                .willReturn(aResponse().withFixedDelay(5000).withStatus(200).withBody("{}")));
        PromptProperties properties = LangfuseStubs.properties(langfuse, REQUEST_TIMEOUT);
        CachedPromptCatalog catalog = catalogOf(client(properties));

        long started = System.nanoTime();
        refresher(properties, false, catalog).run(null);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().isDefault()).isTrue();
    }

    @Test
    void theScheduledRefreshPicksUpAChangeOfLabelWithoutARestart() {
        LangfuseStubs.allValid(langfuse, 1);
        PromptProperties properties = LangfuseStubs.properties(langfuse, REQUEST_TIMEOUT);
        CachedPromptCatalog catalog = catalogOf(client(properties));
        refresher(properties, false, catalog).run(null);
        Runnable refresh = scheduledTask();
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().version()).isEqualTo(1);

        langfuse.resetAll();
        LangfuseStubs.allValid(langfuse, 2);
        refresh.run();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().version()).isEqualTo(2));
    }

    @Test
    void aFailureOfOnePromptDoesNotStopTheOthers() {
        LangfuseStubs.allValid(langfuse, 1);
        LangfuseStubs.status(langfuse, PromptName.SMALL_TALK, "label", "production", 500);
        PromptProperties properties = LangfuseStubs.properties(langfuse, REQUEST_TIMEOUT);
        CachedPromptCatalog catalog = catalogOf(client(properties));

        refresher(properties, false, catalog).run(null);

        assertThat(catalog.current(PromptName.SMALL_TALK).reference().isDefault()).isTrue();
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().version()).isEqualTo(1);
        assertThat(catalog.current(PromptName.CONTEXT).reference().version()).isEqualTo(1);
    }

    @Test
    void withPublishMissingTheMissingPromptsAreCreatedAfterTheLoadWithoutHoldingTheStart() {
        LangfuseStubs.text(langfuse, PromptName.CHAT_SYSTEM, "label", "production", 1, "Sistema");
        LangfuseStubs.text(langfuse, PromptName.SMALL_TALK, "label", "production", 1, "Hola {topics}");
        LangfuseStubs.text(langfuse, PromptName.CONTEXT, "label", "production", 1, "{context} {query}");
        LangfuseStubs.notFound(langfuse, PromptName.EMPTY_CONTEXT, "label", "production");
        LangfuseStubs.notFound(langfuse, PromptName.EMPTY_CONTEXT, "label", "latest");
        langfuse.stubFor(post(urlPathEqualTo(PROMPTS_PATH)).willReturn(aResponse().withStatus(201)));
        PromptProperties properties = LangfuseStubs.properties(langfuse, REQUEST_TIMEOUT);

        refresher(properties, true, catalogOf(client(properties))).run(null);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                langfuse.verify(1, postRequestedFor(urlPathEqualTo(PROMPTS_PATH))));
    }

    @Test
    void withoutPublishMissingNothingIsCreated() {
        LangfuseStubs.notFound(langfuse, PromptName.CHAT_SYSTEM, "label", "production");
        langfuse.stubFor(post(urlPathEqualTo(PROMPTS_PATH)).willReturn(aResponse().withStatus(201)));
        PromptProperties properties = LangfuseStubs.properties(langfuse, REQUEST_TIMEOUT);

        refresher(properties, false, catalogOf(client(properties))).run(null);

        langfuse.verify(0, postRequestedFor(urlPathEqualTo(PROMPTS_PATH)));
    }

    @Test
    void aFailingPassOfTheRefreshNeverStopsTheNextOnes() {
        PromptProperties properties = LangfuseStubs.properties(langfuse, REQUEST_TIMEOUT);
        CachedPromptCatalog broken = org.mockito.Mockito.mock(CachedPromptCatalog.class,
                invocation -> { throw new IllegalStateException("boom"); });
        PromptRefresher refresher = new PromptRefresher(broken, null, INTERVAL, properties.requestTimeout(), false,
                scheduler);

        refresher.refreshInBackground();
        refresher.refreshInBackground();
    }

    @Test
    void stoppingTheApplicationStopsTheScheduler() {
        PromptProperties properties = LangfuseStubs.properties(langfuse, REQUEST_TIMEOUT);

        refresher(properties, false, catalogOf(client(properties))).destroy();

        verify(scheduler).shutdownNow();
        verify(scheduler, org.mockito.Mockito.never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    }
}
