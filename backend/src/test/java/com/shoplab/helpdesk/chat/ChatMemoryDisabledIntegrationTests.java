package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.support.EmptyKnowledgeContextConfig;
import com.shoplab.helpdesk.support.PostgresTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** With {@code max-turns=0} the memory is off: the identifier is checked, but nothing is read or stored. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "shoplab.chat.rag.no-context-mode=model", "shoplab.chat.memory.max-turns=0"})
@PostgresTest
@Import(EmptyKnowledgeContextConfig.class)
class ChatMemoryDisabledIntegrationTests {

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        ollama.resetAll();
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("Hola ", "mundo")));
    }

    @Test
    void aValidConversationIdIsAcceptedButNothingIsStoredAndTheModelGetsNoHistory() {
        int before = jdbc.queryForObject("SELECT count(*) FROM chat.conversation_turn", Integer.class);

        for (int turn = 0; turn < 2; turn++) {
            ChatTestClient.Result result = new ChatTestClient(port).post(
                    "{\"message\":\"Hola\",\"conversationId\":\"" + UUID.randomUUID() + "\"}");
            assertThat(result.status()).isEqualTo(200);
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat.conversation_turn", Integer.class))
                .isEqualTo(before);
        ollama.verify(2, postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.messages.length()", equalTo("2"))));
    }

    @Test
    void anInvalidIdentifierIsStillRejected() {
        ChatTestClient.Result result = new ChatTestClient(port).post(
                "{\"message\":\"Hola\",\"conversationId\":\"no-es-un-uuid\"}");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body()).contains("SLHD_CHAT_00005");
    }
}
