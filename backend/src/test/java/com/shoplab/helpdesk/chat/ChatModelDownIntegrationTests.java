package com.shoplab.helpdesk.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Ollama is not running at all: the connection is refused before any answer starts (specs/LAB-12, US3). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatModelDownIntegrationTests {

    private static final String UNAVAILABLE_BODY = "\"code\":\"SLHD_CHAT_00002\"";

    @DynamicPropertySource
    static void closedPort(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:1");
    }

    @LocalServerPort
    private int port;

    @Test
    void respondsServiceUnavailableAsJsonWhenTheClientAsksForAnEventStream() {
        SseTestClient.Result result = new SseTestClient(port)
                .post("{\"message\":\"Hola\"}", "text/event-stream", "req-down-1");

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.header("Content-Type")).startsWith("application/json");
        assertThat(result.body()).contains(UNAVAILABLE_BODY);
        assertThat(result.events()).isEmpty();
    }

    @Test
    void respondsServiceUnavailableToAClientThatAcceptsAnything() {
        SseTestClient.Result result = new SseTestClient(port)
                .post("{\"message\":\"Hola\"}", "*/*", null);

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.body()).contains(UNAVAILABLE_BODY);
    }

    @Test
    void aClientThatOnlyAcceptsJsonIsNotAcceptable() {
        SseTestClient.Result result = new SseTestClient(port)
                .post("{\"message\":\"Hola\"}", "application/json", null);

        assertThat(result.status()).isEqualTo(406);
    }

    @Test
    void theRequestIdIsReturnedAlsoOnErrors() {
        SseTestClient.Result result = new SseTestClient(port)
                .post("{\"message\":\"Hola\"}", "text/event-stream", "req-down-2");

        assertThat(result.header("X-Request-Id")).isEqualTo("req-down-2");
    }
}
