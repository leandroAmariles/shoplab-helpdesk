package com.shoplab.helpdesk.chat;

import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.SPANS;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.attr;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.flush;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.span;
import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.support.EmptyKnowledgeContextConfig;
import com.shoplab.helpdesk.chat.support.RecordedSpansConfig;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.support.PostgresTest;
import io.opentelemetry.api.trace.StatusCode;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ollama sends two fragments and drops the connection: the trace keeps what was already answered and says the turn
 * failed while generating (specs/LAB-64, US5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shoplab.chat.rag.no-context-mode=model")
@PostgresTest
@Import({EmptyKnowledgeContextConfig.class, RecordedSpansConfig.class})
class TraceMidStreamFailureIntegrationTests {

    private static final RawOllamaServer OLLAMA = new RawOllamaServer(List.of(
            "{\"model\":\"llama3.2:3b\",\"message\":{\"role\":\"assistant\",\"content\":\"uno \"},\"done\":false}",
            "{\"model\":\"llama3.2:3b\",\"message\":{\"role\":\"assistant\",\"content\":\"dos\"},\"done\":false}"),
            200);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", OLLAMA::baseUrl);
        RecordedSpansConfig.enableTracing(registry);
    }

    @AfterAll
    static void stopOllama() throws IOException {
        OLLAMA.close();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ChatTracePort chatTrace;

    @BeforeEach
    void prepare() {
        SPANS.reset();
    }

    @Test
    void aFailureInTheMiddleKeepsThePartialAnswerAndNamesTheStreamErrorAtTheGenerationStage() {
        ChatTestClient.Result response = new ChatTestClient(port).post("{\"message\":\"Hola\"}", "text/event-stream",
                "mid-stream-1");
        flush(chatTrace);

        assertThat(response.text()).isEqualTo("uno dos");
        var root = span("chat-turn");
        assertThat(attr(root, "langfuse.trace.metadata.result")).isEqualTo("error");
        assertThat(attr(root, "langfuse.trace.metadata.error_code"))
                .isEqualTo(ResponseCodeEnum.CHAT_STREAM_FAILED.getCode());
        assertThat(attr(root, "langfuse.trace.metadata.failed_stage")).isEqualTo("generation");
        assertThat(attr(root, "langfuse.observation.output")).isEqualTo("uno dos");
        assertThat(span("generation").getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(SPANS.getFinishedSpanItems().stream().filter(item -> item.getName().equals("chat-turn")).count())
                .isEqualTo(1);
    }
}
