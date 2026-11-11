package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.SPANS;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.attr;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.flush;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.span;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.support.RecordedSpansConfig;
import com.shoplab.helpdesk.support.PostgresTest;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * When the model calls a tool during a turn, the trace gets a child {@code tool:*} span with its arguments, result and
 * duration, and the generation tokens sum both rounds (specs/LAB-64, US2 T028).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresTest
@Import({
    RecordedSpansConfig.class,
    TurnTraceIntegrationTests.EmbeddingDouble.class,
    TurnTraceToolCallIntegrationTests.AnotherModuleTools.class
})
class TurnTraceToolCallIntegrationTests {

    private static final String SCENARIO = "tool-call-turn";
    private static final String TOOL_CALLED = "tool-called";
    private static final byte[] FIRST_ROUND_TOOL_CALL = ("""
            {"model":"llama3.2:3b","message":{"role":"assistant","content":"","tool_calls":\
            [{"function":{"name":"lookupSomething","arguments":{"id":"ORD-42"}}}]},\
            "done":true,"done_reason":"stop","prompt_eval_count":10,"eval_count":5}
            """).getBytes(StandardCharsets.UTF_8);

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
        registry.add("shoplab.chat.rag.no-context-mode", () -> "model");
        RecordedSpansConfig.enableTracing(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ChatTracePort chatTrace;

    @BeforeEach
    void prepare() {
        SPANS.reset();
        ollama.resetAll();
    }

    @Test
    void aToolCallProducesAChildToolSpanAndSumsTokensAcrossBothRounds() {
        ollama.stubFor(post(urlEqualTo("/api/chat"))
                .inScenario(SCENARIO)
                .whenScenarioStateIs(STARTED)
                .willReturn(OllamaStubs.streaming(FIRST_ROUND_TOOL_CALL))
                .willSetStateTo(TOOL_CALLED));
        ollama.stubFor(post(urlEqualTo("/api/chat"))
                .inScenario(SCENARIO)
                .whenScenarioStateIs(TOOL_CALLED)
                .willReturn(OllamaStubs.streaming(OllamaStubs.bodyWithUsage(14, 6, "Pedido ", "ORD-42"))));

        ChatTestClient.Result result = new ChatTestClient(port).post(
                "{\"message\":\"Estado del pedido ORD-42\"}", "text/event-stream", "trace-tool-1");
        flush(chatTrace);

        assertThat(result.text()).isEqualTo("Pedido ORD-42");
        SpanData root = span("chat-turn");
        SpanData generation = span("generation");
        SpanData tool = span("tool:lookupSomething");

        assertThat(tool.getTraceId()).isEqualTo(root.getTraceId());
        assertThat(tool.getParentSpanId()).isEqualTo(generation.getSpanId());
        assertThat(attr(tool, "langfuse.observation.type")).isEqualTo("tool");
        assertThat(attr(tool, "langfuse.observation.input")).contains("ORD-42");
        assertThat(attr(tool, "langfuse.observation.output")).contains("found:ORD-42");
        assertThat(tool.getEndEpochNanos()).isGreaterThanOrEqualTo(tool.getStartEpochNanos());

        assertThat(attr(generation, "langfuse.observation.usage_details"))
                .isEqualTo("{\"input\":24,\"output\":11,\"total\":35}");
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
            return "found:" + id;
        }
    }
}
