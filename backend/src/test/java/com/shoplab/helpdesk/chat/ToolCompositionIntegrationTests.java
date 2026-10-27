package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.infrastructure.out.ollama.OllamaChatAdapter;
import com.shoplab.helpdesk.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.test.StepVerifier;

/**
 * A tool published by another module reaches the model through {@code chat} without {@code chat} knowing the module
 * (specs/LAB-105, US1 scenario 3). The "module" here is a test configuration that only exposes a provider bean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
@Import(ToolCompositionIntegrationTests.AnotherModuleTools.class)
class ToolCompositionIntegrationTests {

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
    }

    @Autowired
    private OllamaChatAdapter adapter;

    @Test
    void aToolPublishedByAnotherModuleIsOfferedToTheModel() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("ok")));

        StepVerifier.create(adapter.streamAnswer("Hola")).expectNext("ok").verifyComplete();

        ollama.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("lookupSomething"))));
    }

    @TestConfiguration
    static class AnotherModuleTools {

        @Bean
        ToolCallbackProvider anotherModuleToolProvider() {
            return MethodToolCallbackProvider.builder().toolObjects(new Tools()).build();
        }
    }

    static class Tools {

        @Tool(description = "Looks something up")
        public String lookupSomething(String id) {
            return id;
        }
    }
}
