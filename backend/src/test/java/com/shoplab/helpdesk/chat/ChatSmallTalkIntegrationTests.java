package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/** A courtesy end to end (LAB-114): SSE with tokens and done, no sources, and no dependency on the search. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresTest
@Import(ChatSmallTalkIntegrationTests.DownKnowledgeConfig.class)
class ChatSmallTalkIntegrationTests {

    /** The search is down: any call to it fails, so a courtesy that answers did not use it. */
    @TestConfiguration
    static class DownKnowledgeConfig {

        @Bean
        @Primary
        KnowledgeContextPort downKnowledgeContext() {
            return (question, limit) -> Mono.error(new KnowledgeRetrievalException(
                    new IllegalStateException("embeddings down")));
        }
    }

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> ollama.baseUrl() + "/v1");
    }

    @LocalServerPort
    private int port;

    private ChatTestClient client() {
        return new ChatTestClient(port);
    }

    @Test
    void aGreetingStreamsTokensAndDoneWithoutSourcesEvenWithTheSearchDown() {
        GatewayStubs.stubAnyChat(ollama, GatewayStubs.streaming(GatewayStubs.fixtureBody()));

        ChatTestClient.Result result = client().post("{\"message\":\"hola\"}");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.header("Content-Type")).startsWith("text/event-stream");
        assertThat(result.events()).extracting(ChatTestClient.Event::name)
                .doesNotContain("sources", "error")
                .last().isEqualTo("done");
        assertThat(result.text()).isEqualTo(GatewayStubs.FIXTURE_TEXT);
        assertThat(result.last().data()).isEqualTo("{\"fragments\":" + GatewayStubs.FIXTURE_FRAGMENTS + "}");
    }

    @Test
    void theGatewayReceivesTheScopePromptAndTheDelimitedMessage() {
        GatewayStubs.stubAnyChat(ollama, GatewayStubs.streaming(GatewayStubs.body("Hola")));

        client().post("{\"message\":\"Muchas gracias, hasta luego\"}");

        ollama.verify(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH))
                .withRequestBody(matchingJsonPath("$.messages.length()", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.messages[1].content",
                        equalTo("<mensaje_del_cliente>\nMuchas gracias, hasta luego\n</mensaje_del_cliente>"))));
    }

    @Test
    void aRealQuestionWithTheSearchDownStillGivesTheTypedRetrievalError() {
        ChatTestClient.Result result = client().post("{\"message\":\"¿Cuánto tarda un envío?\"}");

        assertThat(result.status()).isEqualTo(503);
    }
}
