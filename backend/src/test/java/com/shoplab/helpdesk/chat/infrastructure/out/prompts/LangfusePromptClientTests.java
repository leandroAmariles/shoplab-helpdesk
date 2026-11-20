package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.config.PromptProperties;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.LangfusePromptClient.FetchOutcome;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.LangfusePromptClient.FetchResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;

class LangfusePromptClientTests {

    @RegisterExtension
    static WireMockExtension langfuse = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    private static final PromptName NAME = PromptName.CHAT_SYSTEM;

    private final LangfusePromptClient client = new LangfusePromptClient(WebClient.builder(),
            LangfuseStubs.properties(langfuse, Duration.ofMillis(400)));

    private FetchResult byLabel(String label) {
        return client.fetchByLabel(NAME.langfuseName(), label).block(Duration.ofSeconds(5));
    }

    @Test
    void readsATextVersionByLabelWithItsVersionAndText() {
        LangfuseStubs.text(langfuse, NAME, "label", "production", 3, "Eres el asistente");

        FetchResult result = byLabel("production");

        assertThat(result.outcome()).isEqualTo(FetchOutcome.FOUND);
        assertThat(result.type()).isEqualTo("text");
        assertThat(result.version()).isEqualTo(3);
        assertThat(result.text()).isEqualTo("Eres el asistente");
    }

    @Test
    void authenticatesWithTheKeysAsBasicAndNeverFollowsTheRequestWithAnythingElse() {
        LangfuseStubs.text(langfuse, NAME, "label", "production", 1, "x");

        byLabel("production");

        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (LangfuseStubs.PUBLIC_KEY + ":" + LangfuseStubs.SECRET_KEY).getBytes(StandardCharsets.UTF_8));
        langfuse.verify(getRequestedFor(urlPathEqualTo(LangfuseStubs.path(NAME)))
                .withHeader("Authorization", equalTo(expected)));
    }

    @Test
    void readsAVersionByNumber() {
        LangfuseStubs.text(langfuse, NAME, "version", "7", 7, "Versión siete");

        FetchResult result = client.fetchByVersion(NAME.langfuseName(), 7).block(Duration.ofSeconds(5));

        assertThat(result.outcome()).isEqualTo(FetchOutcome.FOUND);
        assertThat(result.version()).isEqualTo(7);
        assertThat(result.text()).isEqualTo("Versión siete");
    }

    @Test
    void aChatTypePromptIsFoundWithoutTextSoTheValidatorCanRejectIt() {
        LangfuseStubs.chat(langfuse, NAME, "label", "production", 2);

        FetchResult result = byLabel("production");

        assertThat(result.outcome()).isEqualTo(FetchOutcome.FOUND);
        assertThat(result.type()).isEqualTo("chat");
        assertThat(result.text()).isNull();
    }

    @Test
    void a404IsMissingWhateverIsMissing() {
        LangfuseStubs.notFound(langfuse, NAME, "label", "production");

        assertThat(byLabel("production").outcome()).isEqualTo(FetchOutcome.MISSING);
    }

    @Test
    void refusedKeysAndServerErrorsFailWithTheStatusAsTheOnlyDetail() {
        for (int status : new int[] {401, 403, 500, 503}) {
            LangfuseStubs.status(langfuse, NAME, "label", "status-" + status, status);

            FetchResult result = byLabel("status-" + status);

            assertThat(result.outcome()).isEqualTo(FetchOutcome.FAILED);
            assertThat(result.detail()).isEqualTo("http-" + status);
        }
    }

    @Test
    void aRedirectIsNotFollowed() {
        langfuse.stubFor(get(urlPathEqualTo(LangfuseStubs.path(NAME)))
                .withQueryParam("label", equalTo("moved"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", langfuse.baseUrl() + "/otro")));

        FetchResult result = byLabel("moved");

        assertThat(result.outcome()).isEqualTo(FetchOutcome.FAILED);
        assertThat(result.detail()).isEqualTo("http-302");
        langfuse.verify(0, getRequestedFor(urlPathEqualTo("/otro")));
    }

    @Test
    void aSlowLangfuseFailsAtTheRequestTimeout() {
        langfuse.stubFor(get(urlPathEqualTo(LangfuseStubs.path(NAME)))
                .withQueryParam("label", equalTo("slow"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(3000).withBody("{}")));

        long started = System.nanoTime();
        FetchResult result = byLabel("slow");

        assertThat(result.outcome()).isEqualTo(FetchOutcome.FAILED);
        assertThat(result.detail()).isEqualTo("timeout");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void aLangfuseThatIsDownFailsAsConnection() {
        PromptProperties properties = new PromptProperties(true, "http://localhost:1", "pk-secreta", "sk-secreta",
                "production", Duration.ofSeconds(60), Duration.ofMillis(400), null, false, null);
        LangfusePromptClient down = new LangfusePromptClient(WebClient.builder(), properties);

        FetchResult result = down.fetchByLabel(NAME.langfuseName(), "production").block(Duration.ofSeconds(5));

        assertThat(result.outcome()).isEqualTo(FetchOutcome.FAILED);
        assertThat(result.detail()).isEqualTo("connection");
        assertThat(result.toString()).doesNotContain("localhost", "pk-secreta", "sk-secreta");
        assertThat(down.toString()).doesNotContain("pk-secreta", "sk-secreta");
    }

    @Test
    void anAnswerThatIsNotAPromptIsUnreadableAndAnEmptyOneIsEmpty() {
        langfuse.stubFor(get(urlPathEqualTo(LangfuseStubs.path(NAME)))
                .withQueryParam("label", equalTo("garbage"))
                .willReturn(aResponse().withStatus(200).withBody("esto no es json")));
        langfuse.stubFor(get(urlPathEqualTo(LangfuseStubs.path(NAME)))
                .withQueryParam("label", equalTo("empty"))
                .willReturn(aResponse().withStatus(200)));
        langfuse.stubFor(get(urlPathEqualTo(LangfuseStubs.path(NAME)))
                .withQueryParam("label", equalTo("noversion"))
                .willReturn(aResponse().withStatus(200).withBody("{\"type\":\"text\",\"prompt\":\"x\"}")));

        assertThat(byLabel("garbage").detail()).isEqualTo("unreadable-body");
        assertThat(byLabel("empty").detail()).isEqualTo("empty-body");
        assertThat(byLabel("noversion").detail()).isEqualTo("unreadable-body");
    }

    @Test
    void createsATextPromptWithTheLabelAndTheCommitMessage() {
        langfuse.stubFor(post(urlPathEqualTo("/api/public/v2/prompts"))
                .willReturn(aResponse().withStatus(201).withBody("{\"version\":1}")));

        Boolean created = client.create("chat-system", "Texto por defecto", "production").block(Duration.ofSeconds(5));

        assertThat(created).isTrue();
        langfuse.verify(postRequestedFor(urlPathEqualTo("/api/public/v2/prompts"))
                .withRequestBody(equalToJson("""
                        {"name":"chat-system","type":"text","prompt":"Texto por defecto","labels":["production"],
                         "commitMessage":"Publicación inicial desde el repositorio (LAB-85)"}""")));
    }

    @Test
    void aCreationThatLangfuseRefusesOrCannotReceiveIsFalse() {
        langfuse.stubFor(post(urlPathEqualTo("/api/public/v2/prompts")).willReturn(aResponse().withStatus(400)));
        assertThat(client.create("chat-system", "x", "production").block(Duration.ofSeconds(5))).isFalse();

        langfuse.resetAll();
        PromptProperties down = new PromptProperties(true, "http://localhost:1", "pk", "sk", "production",
                Duration.ofSeconds(60), Duration.ofMillis(400), null, false, null);
        assertThat(new LangfusePromptClient(WebClient.builder(), down).create("chat-system", "x", "production")
                .block(Duration.ofSeconds(5))).isFalse();
    }
}
