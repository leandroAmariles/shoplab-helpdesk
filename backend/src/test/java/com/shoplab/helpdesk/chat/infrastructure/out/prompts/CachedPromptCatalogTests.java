package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.usecase.PromptValidator;
import com.shoplab.helpdesk.chat.domain.model.ResolvedPrompt;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.CachedPromptCatalog.RefreshOutcome;
import com.shoplab.helpdesk.chat.infrastructure.support.KeyedWarnThrottle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;

/** The state of the prompts in memory: what is served, what replaces it and what never does. */
class CachedPromptCatalogTests {

    private static final int MAX_CHARACTERS = 500;
    private static final Duration WAIT = Duration.ofSeconds(5);

    @RegisterExtension
    static WireMockExtension langfuse = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private CachedPromptCatalog catalog;

    @BeforeEach
    void newCatalog() {
        langfuse.resetAll();
        catalog = new CachedPromptCatalog(
                new LangfusePromptClient(WebClient.builder(), LangfuseStubs.properties(langfuse, Duration.ofSeconds(3))),
                new PromptValidator(MAX_CHARACTERS), new PromptDefaults(new PromptValidator(20_000)), "production",
                meters, new KeyedWarnThrottle(Duration.ofMinutes(5)));
    }

    private Map<PromptName, RefreshOutcome> refreshAll() {
        return catalog.refreshAll().block(WAIT);
    }

    private RefreshOutcome refresh(PromptName name) {
        return catalog.refresh(name).block(WAIT);
    }

    @Test
    void servesTheDefaultTextWhileNothingHasBeenLoaded() {
        for (PromptName name : PromptName.values()) {
            assertThat(catalog.current(name).reference().isDefault()).isTrue();
        }
    }

    @Test
    void loadsTheFourPromptsInOneRefreshAndServesThemWithTheirVersion() {
        LangfuseStubs.allValid(langfuse, 3);

        Map<PromptName, RefreshOutcome> outcomes = refreshAll();

        assertThat(outcomes).hasSize(4).containsValue(RefreshOutcome.UPDATED).doesNotContainValue(RefreshOutcome.FAILED);
        ResolvedPrompt system = catalog.current(PromptName.CHAT_SYSTEM);
        assertThat(system.text()).isEqualTo(LangfuseStubs.systemText(3));
        assertThat(system.reference().isDefault()).isFalse();
        assertThat(system.reference().version()).isEqualTo(3);
        assertThat(catalog.current(PromptName.CONTEXT).text()).isEqualTo(LangfuseStubs.contextText(3));
    }

    @Test
    void theSameVersionAgainIsUnchanged() {
        LangfuseStubs.allValid(langfuse, 1);
        refreshAll();

        assertThat(refreshAll().values()).containsOnly(RefreshOutcome.UNCHANGED);
    }

    @Test
    void movingTheLabelBackAndForthChangesWhatIsServedAndGoingBackRestoresTheExactText() {
        LangfuseStubs.allValid(langfuse, 1);
        refreshAll();
        String original = catalog.current(PromptName.CHAT_SYSTEM).text();

        langfuse.resetAll();
        LangfuseStubs.allValid(langfuse, 2);
        assertThat(refresh(PromptName.CHAT_SYSTEM)).isEqualTo(RefreshOutcome.UPDATED);
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().version()).isEqualTo(2);

        langfuse.resetAll();
        LangfuseStubs.allValid(langfuse, 1);
        assertThat(refresh(PromptName.CHAT_SYSTEM)).isEqualTo(RefreshOutcome.UPDATED);
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).text()).isEqualTo(original);
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().version()).isEqualTo(1);
    }

    @Test
    void aNewVersionThatIsNotLabelledIsNeverServed() {
        LangfuseStubs.allValid(langfuse, 1);
        refreshAll();
        // Version 2 exists, but only the label production is asked: Langfuse keeps answering version 1.
        LangfuseStubs.text(langfuse, PromptName.CHAT_SYSTEM, "version", "2", 2, "Versión dos sin etiqueta");

        assertThat(refresh(PromptName.CHAT_SYSTEM)).isEqualTo(RefreshOutcome.UNCHANGED);
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).text()).isEqualTo(LangfuseStubs.systemText(1));
    }

    @Test
    void everyBadEditIsDroppedAndTheLastValidVersionStays() {
        LangfuseStubs.allValid(langfuse, 1);
        refreshAll();
        Map<PromptName, String> bad = Map.of(
                PromptName.CHAT_SYSTEM, "   ",
                PromptName.SMALL_TALK, "Saluda sin el marcador de los temas",
                PromptName.CONTEXT, "{context} {query} {cliente}",
                PromptName.EMPTY_CONTEXT, "x".repeat(MAX_CHARACTERS + 1) + " {query}");
        langfuse.resetAll();
        bad.forEach((name, text) -> LangfuseStubs.text(langfuse, name, "label", "production", 9, text));

        Map<PromptName, RefreshOutcome> outcomes = refreshAll();

        assertThat(outcomes.values()).containsOnly(RefreshOutcome.INVALID);
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).text()).isEqualTo(LangfuseStubs.systemText(1));
        assertThat(catalog.current(PromptName.SMALL_TALK).text()).isEqualTo(LangfuseStubs.smallTalkText(1));
        assertThat(catalog.current(PromptName.CONTEXT).text()).isEqualTo(LangfuseStubs.contextText(1));
        assertThat(catalog.current(PromptName.EMPTY_CONTEXT).text()).isEqualTo(LangfuseStubs.emptyContextText(1));
    }

    @Test
    void aChatTypePromptIsDroppedToo() {
        LangfuseStubs.allValid(langfuse, 1);
        refreshAll();
        langfuse.resetAll();
        LangfuseStubs.chat(langfuse, PromptName.CHAT_SYSTEM, "label", "production", 4);

        assertThat(refresh(PromptName.CHAT_SYSTEM)).isEqualTo(RefreshOutcome.INVALID);
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().version()).isEqualTo(1);
    }

    @Test
    void aBadFirstVersionLeavesTheDefaultText() {
        LangfuseStubs.text(langfuse, PromptName.CHAT_SYSTEM, "label", "production", 1, "");

        assertThat(refresh(PromptName.CHAT_SYSTEM)).isEqualTo(RefreshOutcome.INVALID);
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().isDefault()).isTrue();
    }

    @Test
    void aPromptThatDoesNotExistKeepsTheDefaultAndALabelRemovedKeepsTheLastValid() {
        LangfuseStubs.notFound(langfuse, PromptName.CHAT_SYSTEM, "label", "production");
        assertThat(refresh(PromptName.CHAT_SYSTEM)).isEqualTo(RefreshOutcome.MISSING);
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().isDefault()).isTrue();

        langfuse.resetAll();
        LangfuseStubs.allValid(langfuse, 2);
        refreshAll();
        langfuse.resetAll();
        LangfuseStubs.notFound(langfuse, PromptName.CHAT_SYSTEM, "label", "production");

        assertThat(refresh(PromptName.CHAT_SYSTEM)).isEqualTo(RefreshOutcome.MISSING);
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().version()).isEqualTo(2);
    }

    @Test
    void aLangfuseThatStopsAnsweringKeepsTheLastVersionEvenIfItIsOldAndRecoversWhenItComesBack() {
        LangfuseStubs.allValid(langfuse, 1);
        refreshAll();
        langfuse.resetAll();
        for (PromptName name : PromptName.values()) {
            LangfuseStubs.status(langfuse, name, "label", "production", 503);
        }

        assertThat(refreshAll().values()).containsOnly(RefreshOutcome.FAILED);
        assertThat(refreshAll().values()).containsOnly(RefreshOutcome.FAILED);
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().version()).isEqualTo(1);

        langfuse.resetAll();
        LangfuseStubs.allValid(langfuse, 5);
        assertThat(refreshAll().values()).containsOnly(RefreshOutcome.UPDATED);
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().version()).isEqualTo(5);
    }

    @Test
    void readingNeverTouchesTheNetworkEvenWithALangfuseThatIsSlow() {
        LangfuseStubs.allValid(langfuse, 1);
        refreshAll();
        int requestsAfterLoading = langfuse.getAllServeEvents().size();
        langfuse.stubFor(get(urlPathMatching("/api/public/v2/prompts/.*")).atPriority(1)
                .willReturn(aResponse().withFixedDelay(3000).withStatus(200)));

        long started = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            catalog.current(PromptName.values()[i % 4]);
        }

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
        assertThat(langfuse.getAllServeEvents()).hasSize(requestsAfterLoading);
    }

    @Test
    void aReaderNeverSeesAHalfReplacedPrompt() throws Exception {
        LangfuseStubs.allValid(langfuse, 1);
        refreshAll();
        AtomicBoolean running = new AtomicBoolean(true);
        List<String> inconsistent = new ArrayList<>();
        Thread reader = new Thread(() -> {
            while (running.get()) {
                ResolvedPrompt prompt = catalog.current(PromptName.CHAT_SYSTEM);
                if (!prompt.text().equals(LangfuseStubs.systemText(prompt.reference().version()))) {
                    synchronized (inconsistent) {
                        inconsistent.add(prompt.reference().version() + "/" + prompt.text());
                    }
                }
            }
        });
        reader.start();

        for (int version = 2; version <= 12; version++) {
            langfuse.resetAll();
            LangfuseStubs.allValid(langfuse, version);
            refresh(PromptName.CHAT_SYSTEM);
        }
        running.set(false);
        reader.join(2000);

        assertThat(inconsistent).isEmpty();
        langfuse.verify(getRequestedFor(urlPathMatching("/api/public/v2/prompts/chat-system"))
                .withQueryParam("label", equalTo("production")));
    }
}
