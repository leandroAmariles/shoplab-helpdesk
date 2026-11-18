package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.support.PostgresTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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

/** Mixed messages and requests that are not courtesies keep the grounded flow (LAB-114, US2). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresTest
@Import(ChatSmallTalkRoutingIntegrationTests.ShippingKnowledgeConfig.class)
class ChatSmallTalkRoutingIntegrationTests {

    /** The store knows about shipping and nothing else. */
    @TestConfiguration
    static class ShippingKnowledgeConfig {

        @Bean
        @Primary
        KnowledgeContextPort shippingKnowledge() {
            return (question, limit) -> Mono.just(question.contains("envío")
                    ? List.of(new ContextFragment("shipping.md", "Envíos", "Plazos", 0, "Tarda 3 días.", 0.9))
                    : List.of());
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

    @BeforeEach
    void reset() {
        ollama.resetAll();
        GatewayStubs.stubAnyChat(ollama, GatewayStubs.streaming(GatewayStubs.body("Tarda 3 días ", "[1].")));
    }

    @Test
    void aGreetingFollowedByAQuestionIsAnsweredFromTheDocumentsWithSources() {
        ChatTestClient.Result result = new ChatTestClient(port)
                .post("{\"message\":\"hola, ¿cuánto tarda un envío?\"}");

        assertThat(result.events()).extracting(ChatTestClient.Event::name).startsWith("sources").endsWith("done");
        assertThat(result.text()).isEqualTo("Tarda 3 días [1].");
        ollama.verify(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("CONTEXTO"))));
    }

    @Test
    void aCourtesyWithAnotherRequestGetsTheFixedMessageAndNeverReachesTheModel() {
        ChatTestClient.Result result = new ChatTestClient(port).post("{\"message\":\"hola, cuéntame un chiste\"}");

        assertThat(result.text()).isEqualTo(ChatConstants.DEFAULT_NO_CONTEXT_MESSAGE);
        assertThat(result.events()).extracting(ChatTestClient.Event::name).doesNotContain("sources");
        assertThat(ollama.findAll(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH)))).isEmpty();
    }

    @Test
    void aRealQuestionWithoutContextGetsTheFixedMessage() {
        ChatTestClient.Result result = new ChatTestClient(port)
                .post("{\"message\":\"¿cuánto tarda el correo a la luna?\"}");

        assertThat(result.text()).isEqualTo(ChatConstants.DEFAULT_NO_CONTEXT_MESSAGE);
        assertThat(ollama.findAll(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH)))).isEmpty();
    }
}
