package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.usecase.PromptValidator;
import com.shoplab.helpdesk.chat.domain.exception.PromptOverrideException;
import com.shoplab.helpdesk.chat.domain.model.ResolvedPrompt;
import com.shoplab.helpdesk.chat.domain.model.constants.PromptConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.infrastructure.support.KeyedWarnThrottle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** A version fixed by an evaluation (FR-017, FR-023): read once, never replaced by anything else. */
class PinnedPromptsTests {

    @RegisterExtension
    static WireMockExtension langfuse = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    private CachedPromptCatalog catalog;

    @BeforeEach
    void newCatalog() {
        langfuse.resetAll();
        catalog = new CachedPromptCatalog(
                new LangfusePromptClient(WebClient.builder(), LangfuseStubs.properties(langfuse, Duration.ofSeconds(3))),
                new PromptValidator(500), new PromptDefaults(new PromptValidator(20_000)), "production",
                new SimpleMeterRegistry(), new KeyedWarnThrottle(Duration.ofMinutes(5)));
    }

    @Test
    void readsTheVersionByNumberAndKeepsItWithoutAskingAgain() {
        LangfuseStubs.text(langfuse, PromptName.CHAT_SYSTEM, "version", "3", 3, "Sistema versión tres");

        ResolvedPrompt first = catalog.pinned(PromptName.CHAT_SYSTEM, 3).block(Duration.ofSeconds(5));
        ResolvedPrompt second = catalog.pinned(PromptName.CHAT_SYSTEM, 3).block(Duration.ofSeconds(5));

        assertThat(first.text()).isEqualTo("Sistema versión tres");
        assertThat(first.reference().version()).isEqualTo(3);
        assertThat(first.reference().isDefault()).isFalse();
        assertThat(second).isEqualTo(first);
        langfuse.verify(1, getRequestedFor(urlPathEqualTo(LangfuseStubs.path(PromptName.CHAT_SYSTEM)))
                .withQueryParam("version", equalTo("3")));
    }

    @Test
    void aFixedVersionDoesNotChangeWhatTheChatServesToEveryoneElse() {
        LangfuseStubs.allValid(langfuse, 1);
        catalog.refreshAll().block(Duration.ofSeconds(5));
        LangfuseStubs.text(langfuse, PromptName.CHAT_SYSTEM, "version", "9", 9, "Sistema nueve");

        catalog.pinned(PromptName.CHAT_SYSTEM, 9).block(Duration.ofSeconds(5));

        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().version()).isEqualTo(1);
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).text()).isEqualTo(LangfuseStubs.systemText(1));
    }

    @Test
    void aVersionThatDoesNotExistIsAnErrorNeverTheDefaultNorProduction() {
        LangfuseStubs.allValid(langfuse, 1);
        catalog.refreshAll().block(Duration.ofSeconds(5));
        LangfuseStubs.notFound(langfuse, PromptName.CHAT_SYSTEM, "version", "99");

        StepVerifier.create(catalog.pinned(PromptName.CHAT_SYSTEM, 99))
                .expectError(PromptOverrideException.class).verify();
    }

    @Test
    void anInvalidVersionIsAnError() {
        LangfuseStubs.text(langfuse, PromptName.SMALL_TALK, "version", "2", 2, "Sin el marcador");
        LangfuseStubs.chat(langfuse, PromptName.CHAT_SYSTEM, "version", "2", 2);

        StepVerifier.create(catalog.pinned(PromptName.SMALL_TALK, 2))
                .expectError(PromptOverrideException.class).verify();
        StepVerifier.create(catalog.pinned(PromptName.CHAT_SYSTEM, 2))
                .expectError(PromptOverrideException.class).verify();
    }

    @Test
    void aLangfuseThatDoesNotAnswerIsAnError() {
        LangfuseStubs.status(langfuse, PromptName.CHAT_SYSTEM, "version", "4", 503);

        StepVerifier.create(catalog.pinned(PromptName.CHAT_SYSTEM, 4))
                .expectError(PromptOverrideException.class).verify();
    }

    @Test
    void aFailureIsNotRememberedSoTheNextRequestAsksAgain() {
        LangfuseStubs.status(langfuse, PromptName.CHAT_SYSTEM, "version", "4", 503);
        StepVerifier.create(catalog.pinned(PromptName.CHAT_SYSTEM, 4))
                .expectError(PromptOverrideException.class).verify();

        langfuse.resetAll();
        LangfuseStubs.text(langfuse, PromptName.CHAT_SYSTEM, "version", "4", 4, "Sistema cuatro");

        assertThat(catalog.pinned(PromptName.CHAT_SYSTEM, 4).block(Duration.ofSeconds(5)).text())
                .isEqualTo("Sistema cuatro");
    }

    @Test
    void theCacheIsBoundedAndForgetsTheOldestVersion() {
        int versions = PromptConstants.PINNED_CACHE_SIZE + 1;
        for (int version = 1; version <= versions; version++) {
            LangfuseStubs.text(langfuse, PromptName.CHAT_SYSTEM, "version", String.valueOf(version), version,
                    "Sistema " + version);
            catalog.pinned(PromptName.CHAT_SYSTEM, version).block(Duration.ofSeconds(5));
        }

        catalog.pinned(PromptName.CHAT_SYSTEM, 1).block(Duration.ofSeconds(5));
        catalog.pinned(PromptName.CHAT_SYSTEM, versions).block(Duration.ofSeconds(5));

        langfuse.verify(2, getRequestedFor(urlPathEqualTo(LangfuseStubs.path(PromptName.CHAT_SYSTEM)))
                .withQueryParam("version", equalTo("1")));
        langfuse.verify(1, getRequestedFor(urlPathEqualTo(LangfuseStubs.path(PromptName.CHAT_SYSTEM)))
                .withQueryParam("version", equalTo(String.valueOf(versions))));
    }
}
