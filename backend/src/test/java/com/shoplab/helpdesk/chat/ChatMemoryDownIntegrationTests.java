package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.port.out.ConversationMemoryPort;
import com.shoplab.helpdesk.chat.domain.exception.ConversationMemoryException;
import com.shoplab.helpdesk.chat.domain.model.ConversationId;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
import com.shoplab.helpdesk.chat.support.EmptyKnowledgeContextConfig;
import com.shoplab.helpdesk.support.PostgresTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
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

/** A conversation memory that is down is an error of the chat, never an answer given as if nothing had been said. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shoplab.chat.rag.no-context-mode=model")
@PostgresTest
@Import({EmptyKnowledgeContextConfig.class, ChatMemoryDownIntegrationTests.FlakyMemory.class})
class ChatMemoryDownIntegrationTests {

    private static volatile boolean failReads;
    private static volatile boolean failWrites;

    @TestConfiguration
    static class FlakyMemory {

        @Bean
        @Primary
        ConversationMemoryPort flakyMemory() {
            return new ConversationMemoryPort() {
                @Override
                public Mono<List<ConversationTurn>> recent(ConversationId id, int limit) {
                    return failReads ? Mono.error(new ConversationMemoryException(MemoryOperation.READ, null))
                            : Mono.just(List.of());
                }

                @Override
                public Mono<Void> saveTurn(ConversationId id, ConversationTurn turn) {
                    return failWrites ? Mono.error(new ConversationMemoryException(MemoryOperation.WRITE, null))
                            : Mono.empty();
                }
            };
        }
    }

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
    }

    @LocalServerPort
    private int port;

    @BeforeEach
    void prepare() {
        failReads = false;
        failWrites = false;
        ollama.resetAll();
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("Hola ", "mundo")));
    }

    private ChatTestClient.Result ask() {
        return new ChatTestClient(port).post(
                "{\"message\":\"Hola\",\"conversationId\":\"" + UUID.randomUUID() + "\"}");
    }

    private String prometheus() {
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aMemoryThatCannotBeReadIsAServiceUnavailableAndTheModelIsNotCalled() {
        failReads = true;

        ChatTestClient.Result result = ask();

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.body()).contains("SLHD_CHAT_00006");
        assertThat(ollama.findAll(postRequestedFor(urlEqualTo("/api/chat")))).isEmpty();
        assertThat(prometheus()).contains("shoplab_chat_memory_failures_total{")
                .contains("operation=\"read\"");
    }

    @Test
    void aTurnThatCannotBeStoredEndsTheStreamWithAnErrorEventInsteadOfDone() {
        failWrites = true;

        ChatTestClient.Result result = ask();

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.events()).extracting(ChatTestClient.Event::name)
                .containsExactly("token", "token", "error");
        assertThat(result.last().data()).contains("SLHD_CHAT_00006");
        assertThat(prometheus()).contains("operation=\"write\"");
    }

    @Test
    void withAWorkingMemoryTheSameRequestEndsWithDone() {
        ChatTestClient.Result result = ask();

        assertThat(result.events()).extracting(ChatTestClient.Event::name)
                .containsExactly("token", "token", "done");
    }
}
