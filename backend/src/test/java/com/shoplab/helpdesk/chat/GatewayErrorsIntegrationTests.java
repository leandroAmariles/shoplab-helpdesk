package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.support.EmptyKnowledgeContextConfig;
import com.shoplab.helpdesk.shared.constants.GatewayHeaders;
import com.shoplab.helpdesk.support.PostgresTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What the customer gets, end to end, for each thing the model gateway can answer (LAB-84): an exhausted budget, a
 * rejected key, an unknown model and the gateway answering with its fallback model. The gateway's own error text is
 * never in the response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shoplab.chat.rag.no-context-mode=model")
@PostgresTest
@Import(EmptyKnowledgeContextConfig.class)
class GatewayErrorsIntegrationTests {

    private static final String BODY = "{\"message\":\"Hola\"}";
    private static final String SECRET = "token-secreto-123";

    @RegisterExtension
    static WireMockExtension gateway = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void gatewayUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> gateway.baseUrl() + "/v1");
    }

    @LocalServerPort
    private int port;

    @BeforeEach
    void reset() {
        gateway.resetAll();
    }

    @Test
    void anExhaustedBudgetIsTooManyRequestsWithItsOwnCodeAndNoInternalDetail() {
        GatewayStubs.stubAnyChat(gateway, GatewayStubs.budgetExceeded());

        ChatTestClient.Result result = new ChatTestClient(port).post(BODY, "text/event-stream", "req-budget-1");

        assertThat(result.status()).isEqualTo(429);
        assertThat(result.body()).contains("\"code\":\"SLHD_CHAT_00007\"")
                .doesNotContain("SLHD_CHAT_00002").doesNotContain(SECRET).doesNotContain("Budget");
        assertThat(result.events()).isEmpty();
    }

    @Test
    void aRejectedKeyIsModelUnavailableAndThePublicResponseSaysNothingAboutIt() {
        GatewayStubs.stubAnyChat(gateway, GatewayStubs.unauthorized());

        ChatTestClient.Result result = new ChatTestClient(port).post(BODY, "text/event-stream", "req-auth-1");

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.body()).contains("\"code\":\"SLHD_CHAT_00002\"")
                .doesNotContain(SECRET).doesNotContain("Authentication");
    }

    @Test
    void aLogicalModelTheGatewayDoesNotKnowIsModelUnavailable() {
        GatewayStubs.stubAnyChat(gateway, GatewayStubs.unknownModel());

        ChatTestClient.Result result = new ChatTestClient(port).post(BODY, "text/event-stream", "req-model-1");

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.body()).contains("\"code\":\"SLHD_CHAT_00002\"").doesNotContain(SECRET);
    }

    @Test
    void everyModelBehindTheGatewayBeingDownIsModelUnavailable() {
        GatewayStubs.stubAnyChat(gateway, GatewayStubs.error(503, "None", "no healthy deployments " + SECRET));

        ChatTestClient.Result result = new ChatTestClient(port).post(BODY, "text/event-stream", "req-down-9");

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.body()).contains("\"code\":\"SLHD_CHAT_00002\"").doesNotContain(SECRET);
    }

    @Test
    void anAnswerServedByTheFallbackModelReachesTheCustomerCompleteAndWithoutAnError() {
        GatewayStubs.stubAnyChat(gateway, GatewayStubs.streaming(
                GatewayStubs.bodyServedBy(GatewayStubs.FALLBACK_MODEL, "Hola ", "de ", "respaldo")));

        ChatTestClient.Result result = new ChatTestClient(port).post(BODY, "text/event-stream", "req-fb-1");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.text()).isEqualTo("Hola de respaldo");
        assertThat(result.last().name()).isEqualTo("done");
        assertThat(result.events()).extracting(ChatTestClient.Event::name).doesNotContain("error");
    }

    @Test
    void theGatewayIsToldWhichConversationAndWhichRequestTheCallBelongsTo() {
        GatewayStubs.stubAnyChat(gateway, GatewayStubs.streaming(GatewayStubs.body("ok")));
        String conversation = UUID.randomUUID().toString();

        new ChatTestClient(port).post("{\"message\":\"Hola\",\"conversationId\":\"" + conversation + "\"}",
                "text/event-stream", "req-corr-1");

        gateway.verify(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH))
                .withHeader(GatewayHeaders.REQUEST_ID_HEADER, equalTo("req-corr-1"))
                .withRequestBody(matchingJsonPath("$.user", equalTo(conversation))));
    }
}
