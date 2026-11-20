package com.shoplab.helpdesk.chat.infrastructure.out.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.GatewayStubs;
import com.shoplab.helpdesk.chat.application.port.out.PromptCatalogPort;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.support.FixedPromptCatalog;
import com.shoplab.helpdesk.support.PostgresTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.test.StepVerifier;

/**
 * The delimiting is done in code (LAB-114, FR-009): an operator who replaces the templates or the system prompt does
 * not remove it, because the text that fills {@code {query}} already arrives between the delimiters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class PromptDelimitingCustomTemplatesTests {

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> ollama.baseUrl() + "/v1");
    }

    @TestConfiguration
    static class Prompts {

        @Bean
        @Primary
        PromptCatalogPort testPrompts() {
            return FixedPromptCatalog.with(Map.of(
                    PromptName.CHAT_SYSTEM, "Custom system prompt",
                PromptName.CONTEXT, "Datos: {context} || Cliente: {query}",
                PromptName.EMPTY_CONTEXT, "Sin datos || Cliente: {query}"));
        }
    }

    @Autowired
    private GatewayChatAdapter adapter;

    @BeforeEach
    void stubTheModel() {
        ollama.resetAll();
        GatewayStubs.stubAnyChat(ollama, GatewayStubs.streaming(GatewayStubs.body("ok")));
    }

    @Test
    void aCustomContextTemplateStillReceivesTheDelimitedMessage() {
        ContextFragment fragment = new ContextFragment("a.md", "Doc", "", 0, "Texto.", 0.9);

        StepVerifier.create(adapter.streamAnswer("¿plazo?", List.of(), List.of(fragment)))
                .expectNext("ok").verifyComplete();

        ollama.verify(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH))
                .withRequestBody(matchingJsonPath("$.messages[0].content", equalTo("Custom system prompt")))
                .withRequestBody(matchingJsonPath("$.messages[1].content",
                        com.github.tomakehurst.wiremock.client.WireMock.containing(
                                "Cliente: <mensaje_del_cliente>"))));
    }

    @Test
    void aCustomEmptyContextTemplateStillReceivesTheDelimitedMessage() {
        StepVerifier.create(adapter.streamAnswer("¿plazo?", List.of(), List.of()))
                .expectNext("ok").verifyComplete();

        ollama.verify(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH))
                .withRequestBody(matchingJsonPath("$.messages[1].content",
                        equalTo("Sin datos || Cliente: <mensaje_del_cliente>\n¿plazo?\n</mensaje_del_cliente>"))));
    }
}
