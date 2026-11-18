package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.chat.support.EmptyKnowledgeContextConfig;
import com.shoplab.helpdesk.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** With the path turned off the chat behaves exactly as before LAB-114: a greeting is a question like any other. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shoplab.chat.small-talk.enabled=false")
@PostgresTest
@Import(EmptyKnowledgeContextConfig.class)
class ChatSmallTalkDisabledIntegrationTests {

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> ollama.baseUrl() + "/v1");
    }

    @LocalServerPort
    private int port;

    @Test
    void aGreetingGetsTheFixedMessageAndTheModelIsNeverCalled() {
        ChatTestClient.Result result = new ChatTestClient(port).post("{\"message\":\"hola\"}");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.text()).isEqualTo(ChatConstants.DEFAULT_NO_CONTEXT_MESSAGE);
        assertThat(ollama.findAll(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH)))).isEmpty();
    }
}
