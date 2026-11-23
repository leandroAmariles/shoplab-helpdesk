package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.support.PostgresTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/** The turns metric through the whole chat (LAB-114, US4): one count per kind, and the courtesy has no first token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresTest
@Import(ChatSmallTalkMetricsIntegrationTests.ShippingKnowledgeConfig.class)
class ChatSmallTalkMetricsIntegrationTests {

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

    @Autowired
    private MeterRegistry registry;

    private double turns(String kind) {
        var counter = registry.find("shoplab.chat.turns").tag("kind", kind).counter();
        return counter == null ? 0 : counter.count();
    }

    private long firstTokens() {
        var timer = registry.find("shoplab.chat.first.token").timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void aGreetingAQuestionWithContextAndOneWithoutLeaveOneCountEach() {
        GatewayStubs.stubAnyChat(ollama, GatewayStubs.streaming(GatewayStubs.body("Respuesta ", "[1].")));
        double smallTalkBefore = turns("small_talk");
        double withContextBefore = turns("with_context");
        double noContextBefore = turns("no_context");
        ChatTestClient client = new ChatTestClient(port);

        assertThat(client.post("{\"message\":\"hola\"}").status()).isEqualTo(200);
        long firstTokensAfterGreeting = firstTokens();
        assertThat(client.post("{\"message\":\"¿cuánto tarda un envío?\"}").status()).isEqualTo(200);
        assertThat(client.post("{\"message\":\"¿cuánto tarda el correo a la luna?\"}").status()).isEqualTo(200);

        assertThat(turns("small_talk") - smallTalkBefore).isEqualTo(1);
        assertThat(turns("with_context") - withContextBefore).isEqualTo(1);
        assertThat(turns("no_context") - noContextBefore).isEqualTo(1);
        // The first-token timer measures answers with a model call: the courtesy does not touch it, the question does
        assertThat(firstTokens()).isEqualTo(firstTokensAfterGreeting + 1);
    }
}
