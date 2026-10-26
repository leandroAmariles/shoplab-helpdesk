package com.shoplab.helpdesk.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.chat.infrastructure.out.ollama.OllamaChatAdapter;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.test.StepVerifier;

/** Ollama answers two fragments and then drops the connection (specs/LAB-12, US3 scenario 4). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatMidStreamFailureIntegrationTests {

    private static final RawOllamaServer OLLAMA = new RawOllamaServer(List.of(
            "{\"model\":\"llama3.2:3b\",\"message\":{\"role\":\"assistant\",\"content\":\"uno \"},\"done\":false}",
            "{\"model\":\"llama3.2:3b\",\"message\":{\"role\":\"assistant\",\"content\":\"dos\"},\"done\":false}"),
            200);

    @DynamicPropertySource
    static void rawOllama(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", OLLAMA::baseUrl);
    }

    @AfterAll
    static void stopOllama() throws IOException {
        OLLAMA.close();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private OllamaChatAdapter adapter;

    @Test
    void theAdapterSignalsAModelExceptionAfterTheFragmentsAlreadyEmitted() {
        StepVerifier.create(adapter.streamAnswer("Hola"))
                .expectNext("uno ", "dos")
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ChatModelException.class))
                .verify();
    }

    @Test
    void theClientGetsTheTokensThenAFinalErrorEventAndTheConnectionCloses() {
        ChatTestClient.Result result = new ChatTestClient(port).post("{\"message\":\"Hola\"}");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.text()).isEqualTo("uno dos");
        assertThat(result.last().name()).isEqualTo("error");
        assertThat(result.last().data()).contains("\"code\":\"SLHD_CHAT_00003\"").contains("\"message\":");
        assertThat(result.events().stream().filter(e -> "done".equals(e.name()))).isEmpty();
    }
}
