package com.shoplab.helpdesk.eval.infrastructure.out.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.shoplab.contracts.chat.client.AssistantSettingsApi;
import com.shoplab.helpdesk.eval.config.ChatSettingsClientConfig;
import com.shoplab.helpdesk.eval.domain.exception.EvalStoreUnavailableException;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.test.StepVerifier;

/** Unit + WireMock tests for {@link HttpAssistantSettingsClient} (LAB-59). */
class HttpAssistantSettingsClientTests {

    private static WireMockServer wireMock;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void resetServer() {
        wireMock.resetAll();
    }

    private HttpAssistantSettingsClient client(Duration timeout) {
        WebClient webClient = WebClient.builder()
                .baseUrl(wireMock.baseUrl())
                .filter(ChatSettingsClientConfig.propagateRequest())
                .build();
        AssistantSettingsApi api = HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)).build()
                .createClient(AssistantSettingsApi.class);
        return new HttpAssistantSettingsClient(api, timeout);
    }

    @Test
    void mapsSettingsAndPropagatesCorrelationAndTraceHeaders() {
        wireMock.stubFor(get(urlEqualTo("/api/assistant/settings"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "topK": 4,
                                  "similarityThreshold": 0.6,
                                  "maxContextCharacters": 8000,
                                  "prompts": [
                                    {"name": "chat-system", "version": 2, "source": "LANGFUSE"},
                                    {"name": "chat-small-talk", "version": null, "source": "DEFAULT"}
                                  ]
                                }
                                """)));

        StepVerifier.create(client(Duration.ofSeconds(2)).current()
                        .contextWrite(ctx -> ctx
                                .put(RequestIdFilter.MDC_KEY, "req-eval-59")
                                .put(RequestIdFilter.TRACEPARENT_KEY,
                                        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")))
                .assertNext(snapshot -> {
                    assertThat(snapshot.retrieval().topK()).isEqualTo(4);
                    assertThat(snapshot.retrieval().similarityThreshold()).isEqualTo(0.6);
                    assertThat(snapshot.retrieval().maxContextCharacters()).isEqualTo(8000);
                    assertThat(snapshot.prompts()).containsExactly(
                            new PromptVersion("chat-system", 2, PromptSource.LANGFUSE, false),
                            new PromptVersion("chat-small-talk", null, PromptSource.DEFAULT, false));
                })
                .verifyComplete();

        wireMock.verify(getRequestedFor(urlEqualTo("/api/assistant/settings"))
                .withHeader(RequestIdFilter.HEADER, equalTo("req-eval-59"))
                .withHeader("traceparent", equalTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")));
    }

    @Test
    void translatesServiceUnavailableToTypedException() {
        wireMock.stubFor(get(urlEqualTo("/api/assistant/settings"))
                .willReturn(aResponse().withStatus(503)));

        StepVerifier.create(client(Duration.ofSeconds(2)).current())
                .expectError(EvalStoreUnavailableException.class)
                .verify();
    }

    @Test
    void translatesTimeoutToTypedException() {
        wireMock.stubFor(get(urlEqualTo("/api/assistant/settings"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"topK\":4,\"similarityThreshold\":0.6,\"maxContextCharacters\":8000,"
                                + "\"prompts\":[]}")));

        StepVerifier.create(client(Duration.ofMillis(100)).current())
                .expectError(EvalStoreUnavailableException.class)
                .verify();
    }
}
