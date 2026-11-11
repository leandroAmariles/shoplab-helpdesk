package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.SPANS;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.attr;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.flush;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.DerivedTraceIdGenerator;
import com.shoplab.helpdesk.chat.support.RecordedSpansConfig;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.support.PostgresTest;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/**
 * The turns that are not a normal answer leave a trace that says what happened: no context, cancelled, model down,
 * failed search and failed tool (specs/LAB-64, US5). The failure in the middle of an answer is in
 * {@code TraceMidStreamFailureIntegrationTests}, which needs a server that drops the connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresTest
@Import({RecordedSpansConfig.class, TraceSpecialTurnsIntegrationTests.ControlledContext.class,
        TraceSpecialTurnsIntegrationTests.FailingTool.class})
class TraceSpecialTurnsIntegrationTests {

    private static final String SCENARIO = "failing-tool-turn";
    private static final String TOOL_CALLED = "failing-tool-called";
    private static final String BODY = "{\"message\":\"Cual es el plazo de devolucion?\"}";
    private static final List<ContextFragment> ONE_FRAGMENT = List.of(
            new ContextFragment("policy.md", "Politica", "Plazos", 0, "Treinta dias.", 0.9));
    private static final byte[] TOOL_CALL = ("""
            {"model":"llama3.2:3b","message":{"role":"assistant","content":"","tool_calls":\
            [{"function":{"name":"failingLookup","arguments":{"id":"ORD-1"}}}]},\
            "done":true,"done_reason":"stop","prompt_eval_count":10,"eval_count":5}
            """).getBytes(StandardCharsets.UTF_8);

    /** What the knowledge base answers in the current test. */
    private static final AtomicReference<Mono<List<ContextFragment>>> CONTEXT = new AtomicReference<>();

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
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
        CONTEXT.set(Mono.just(ONE_FRAGMENT));
    }

    private ChatTestClient.Result ask(String requestId) {
        return new ChatTestClient(port).post(BODY, "text/event-stream", requestId);
    }

    private List<SpanData> spansOf(String requestId) {
        flush(chatTrace);
        String traceId = DerivedTraceIdGenerator.traceIdOf(requestId);
        return SPANS.getFinishedSpanItems().stream().filter(item -> item.getTraceId().equals(traceId)).toList();
    }

    private SpanData spanOf(String requestId, String name) {
        return spansOf(requestId).stream().filter(item -> item.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no span " + name + " for " + requestId + " in "
                        + spansOf(requestId).stream().map(SpanData::getName).toList()));
    }

    private boolean hasSpan(String requestId, String name) {
        return spansOf(requestId).stream().anyMatch(item -> item.getName().equals(name));
    }

    private static String result(SpanData root) {
        return attr(root, "langfuse.trace.metadata.result");
    }

    @Test
    void aQuestionWithoutContextAndAFixedMessageIsATraceWithoutGeneration() {
        CONTEXT.set(Mono.just(List.of()));

        ChatTestClient.Result response = ask("special-no-context");

        SpanData root = spanOf("special-no-context", "chat-turn");
        assertThat(result(root)).isEqualTo("no_context");
        assertThat(attr(root, "langfuse.observation.output")).isEqualTo(response.text()).isNotBlank();
        assertThat(attr(spanOf("special-no-context", "retrieval"), "langfuse.observation.output"))
                .isEqualTo("[]");
        assertThat(hasSpan("special-no-context", "generation")).isFalse();
        assertThat(ollama.getAllServeEvents()).isEmpty();
    }

    @Test
    void aTurnCancelledInTheMiddleKeepsThePartialTextAndIsAWarning() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("uno ", "dos ", "tres ", "cuatro ",
                "cinco ", "seis ", "siete ", "ocho ")).withChunkedDribbleDelay(8, 4_000));

        new ChatTestClient(port).open(BODY).blockFirst(Duration.ofSeconds(10));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            flush(chatTrace);
            SpanData root = SPANS.getFinishedSpanItems().stream()
                    .filter(item -> item.getName().equals("chat-turn")).findFirst().orElseThrow();
            assertThat(result(root)).isEqualTo("cancelled");
            assertThat(attr(root, "langfuse.observation.level")).isEqualTo("WARNING");
            assertThat("uno dos tres cuatro cinco seis siete ocho ")
                    .startsWith(attr(root, "langfuse.observation.output"));
        });
        assertThat(SPANS.getFinishedSpanItems().stream().filter(item -> item.getName().equals("chat-turn"))
                .count()).isEqualTo(1);
    }

    @Test
    void anUnavailableModelEndsTheTurnAsAnErrorAtTheGenerationStage() {
        ollama.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ChatTestClient.Result response = ask("special-model-down");

        assertThat(response.status()).isEqualTo(503);
        SpanData root = spanOf("special-model-down", "chat-turn");
        assertThat(result(root)).isEqualTo("error");
        assertThat(attr(root, "langfuse.trace.metadata.error_code"))
                .isEqualTo(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE.getCode());
        assertThat(attr(root, "langfuse.trace.metadata.failed_stage")).isEqualTo("generation");
        assertThat(attr(root, "langfuse.observation.level")).isEqualTo("ERROR");
        assertThat(spanOf("special-model-down", "generation").getStatus().getStatusCode())
                .isEqualTo(StatusCode.ERROR);
    }

    @Test
    void aFailedSearchEndsTheTurnAtTheRetrievalStageWithoutGeneration() {
        CONTEXT.set(Mono.error(new KnowledgeRetrievalException(new IllegalStateException("embedding down"))));

        ask("special-retrieval");

        SpanData root = spanOf("special-retrieval", "chat-turn");
        assertThat(result(root)).isEqualTo("error");
        assertThat(attr(root, "langfuse.trace.metadata.failed_stage")).isEqualTo("retrieval");
        assertThat(attr(root, "langfuse.trace.metadata.error_code"))
                .isEqualTo(ResponseCodeEnum.CHAT_CONTEXT_UNAVAILABLE.getCode());
        assertThat(spanOf("special-retrieval", "retrieval").getStatus().getStatusCode())
                .isEqualTo(StatusCode.ERROR);
        assertThat(hasSpan("special-retrieval", "generation")).isFalse();
    }

    @Test
    void aToolThatFailsEndsTheTurnAtTheToolStageAndItsSpanIsAnError() {
        ollama.stubFor(post(urlEqualTo("/api/chat")).inScenario(SCENARIO).whenScenarioStateIs(STARTED)
                .willReturn(OllamaStubs.streaming(TOOL_CALL)).willSetStateTo(TOOL_CALLED));
        ollama.stubFor(post(urlEqualTo("/api/chat")).inScenario(SCENARIO).whenScenarioStateIs(TOOL_CALLED)
                .willReturn(OllamaStubs.streaming(OllamaStubs.body("no deberia llegar"))));

        ask("special-tool");

        SpanData root = spanOf("special-tool", "chat-turn");
        assertThat(result(root)).isEqualTo("error");
        assertThat(attr(root, "langfuse.trace.metadata.failed_stage")).isEqualTo("tool");
        assertThat(spanOf("special-tool", "tool:failingLookup").getStatus().getStatusCode())
                .isEqualTo(StatusCode.ERROR);
    }

    @Test
    void everyTurnIsClosedOnceWithOneRootPerRequest() {
        CONTEXT.set(Mono.just(List.of()));
        ask("special-once");

        assertThat(spansOf("special-once").stream().filter(item -> item.getName().equals("chat-turn")).count())
                .isEqualTo(1);
    }

    @TestConfiguration
    static class ControlledContext {

        @Bean
        @Primary
        KnowledgeContextPort controlledKnowledgeContext() {
            return (question, limit) -> Mono.defer(CONTEXT::get);
        }
    }

    @TestConfiguration
    static class FailingTool {

        @Bean
        ToolCallbackProvider failingToolProvider() {
            return MethodToolCallbackProvider.builder().toolObjects(new Tools()).build();
        }
    }

    static class Tools {

        @Tool(description = "Always fails")
        public String failingLookup(String id) {
            throw new IllegalStateException("the lookup failed for " + id);
        }
    }
}
