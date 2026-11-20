package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.usecase.PromptValidator;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;

/** The seeding creates only what does not exist and is safe to repeat (FR-005). */
class PromptPublisherTests {

    private static final String PROMPTS_PATH = "/api/public/v2/prompts";
    private static final Duration WAIT = Duration.ofSeconds(5);

    @RegisterExtension
    static WireMockExtension langfuse = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    private final PromptDefaults defaults = new PromptDefaults(new PromptValidator(20_000));
    private PromptPublisher publisher;

    @BeforeEach
    void setUp() {
        langfuse.resetAll();
        LangfusePromptClient client = new LangfusePromptClient(WebClient.builder(),
                LangfuseStubs.properties(langfuse, Duration.ofSeconds(3)));
        publisher = new PromptPublisher(client, defaults, "production");
        langfuse.stubFor(post(urlPathEqualTo(PROMPTS_PATH))
                .willReturn(aResponse().withStatus(201).withBody("{\"version\":1}")));
    }

    private Collection<PromptName> publish(PromptName... names) {
        return publisher.publishMissing(List.of(names)).block(WAIT);
    }

    @Test
    void createsAPromptThatDoesNotExistWithItsDefaultTextTypeTextAndTheLabel() {
        LangfuseStubs.notFound(langfuse, PromptName.CHAT_SYSTEM, "label", "latest");

        assertThat(publish(PromptName.CHAT_SYSTEM)).containsExactly(PromptName.CHAT_SYSTEM);

        String expected = defaults.get(PromptName.CHAT_SYSTEM).text();
        langfuse.verify(1, postRequestedFor(urlPathEqualTo(PROMPTS_PATH)).withRequestBody(equalToJson(
                "{\"name\":\"chat-system\",\"type\":\"text\",\"labels\":[\"production\"],\"prompt\":"
                        + new tools.jackson.databind.json.JsonMapper().writeValueAsString(expected)
                        + ",\"commitMessage\":\"Publicación inicial desde el repositorio (LAB-85)\"}")));
    }

    @Test
    void neverCreatesAVersionOfAPromptThatExistsWhateverItsTextOrLabels() {
        // The prompt exists (it has a latest version) but the label production was removed on purpose.
        LangfuseStubs.text(langfuse, PromptName.CHAT_SYSTEM, "label", "latest", 5, "Otro texto del repositorio");

        assertThat(publish(PromptName.CHAT_SYSTEM)).isEmpty();

        langfuse.verify(0, postRequestedFor(urlPathEqualTo(PROMPTS_PATH)));
    }

    @Test
    void runningItTwiceCreatesTheVersionOnlyOnce() {
        LangfuseStubs.notFound(langfuse, PromptName.SMALL_TALK, "label", "latest");
        assertThat(publish(PromptName.SMALL_TALK)).hasSize(1);

        // Now it exists.
        langfuse.resetAll();
        langfuse.stubFor(post(urlPathEqualTo(PROMPTS_PATH)).willReturn(aResponse().withStatus(201)));
        LangfuseStubs.text(langfuse, PromptName.SMALL_TALK, "label", "latest", 1, "Texto");
        assertThat(publish(PromptName.SMALL_TALK)).isEmpty();

        langfuse.verify(0, postRequestedFor(urlPathEqualTo(PROMPTS_PATH)));
    }

    @Test
    void createsOnlyTheMissingOnesOfSeveral() {
        LangfuseStubs.notFound(langfuse, PromptName.CONTEXT, "label", "latest");
        LangfuseStubs.text(langfuse, PromptName.EMPTY_CONTEXT, "label", "latest", 2, "existe {query}");

        assertThat(publish(PromptName.CONTEXT, PromptName.EMPTY_CONTEXT)).containsExactly(PromptName.CONTEXT);

        langfuse.verify(1, postRequestedFor(urlPathEqualTo(PROMPTS_PATH)));
    }

    @Test
    void doesNothingWhenLangfuseDoesNotAnswerTheCheckBecauseItCannotKnowIfThePromptExists() {
        LangfuseStubs.status(langfuse, PromptName.CHAT_SYSTEM, "label", "latest", 503);

        assertThat(publish(PromptName.CHAT_SYSTEM)).isEmpty();

        langfuse.verify(0, postRequestedFor(urlPathEqualTo(PROMPTS_PATH)));
    }

    @Test
    void aCreationThatFailsIsNotAnErrorAndIsNotReportedAsCreated() {
        LangfuseStubs.notFound(langfuse, PromptName.CHAT_SYSTEM, "label", "latest");
        langfuse.stubFor(post(urlPathEqualTo(PROMPTS_PATH)).willReturn(aResponse().withStatus(500)));

        assertThat(publish(PromptName.CHAT_SYSTEM)).isEmpty();
    }

    @Test
    void theCheckAsksForTheLatestLabelBecauseTheApiAnswers404ForAMissingLabelToo() {
        LangfuseStubs.notFound(langfuse, PromptName.CHAT_SYSTEM, "label", "latest");

        publish(PromptName.CHAT_SYSTEM);

        langfuse.verify(getRequestedFor(urlPathEqualTo(LangfuseStubs.path(PromptName.CHAT_SYSTEM)))
                .withQueryParam("label", equalTo("latest")));
        langfuse.verify(0, getRequestedFor(urlPathEqualTo(LangfuseStubs.path(PromptName.CHAT_SYSTEM)))
                .withQueryParam("label", equalTo("production")));
        assertThat(langfuse.findAll(getRequestedFor(urlPathEqualTo(LangfuseStubs.path(PromptName.CHAT_SYSTEM)))))
                .hasSize(1);
    }
}
