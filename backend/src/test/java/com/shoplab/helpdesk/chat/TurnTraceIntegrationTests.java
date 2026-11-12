package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.SECRET_KEY;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.SPANS;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.attr;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.flush;
import static com.shoplab.helpdesk.chat.support.RecordedSpansConfig.span;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.DerivedTraceIdGenerator;
import com.shoplab.helpdesk.chat.support.RecordedSpansConfig;
import com.shoplab.helpdesk.retrieval.application.port.in.IndexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.application.port.in.IngestDocumentServicePort;
import com.shoplab.helpdesk.retrieval.domain.model.IngestDocumentCommand;
import com.shoplab.helpdesk.retrieval.support.DeterministicEmbeddingPort;
import com.shoplab.helpdesk.support.PostgresTest;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * One question through the whole chat leaves one trace with the prompt, the context with its scores, the tokens and
 * the cost, and nothing of it reaches the logs (specs/LAB-64, US2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresTest
@Import({RecordedSpansConfig.class, TurnTraceIntegrationTests.EmbeddingDouble.class})
@ExtendWith(OutputCaptureExtension.class)
class TurnTraceIntegrationTests {

    private static final String SOURCE = "trace-test-policy.md";
    private static final String MODEL = "trace-model";
    private static final String QUESTION = "Plazos\\n\\nTreinta dias.";
    private static final String CONVERSATION = "7d1c5a0e-2b7e-4a52-9b3e-3f6f4a0c9d21";
    private static final String ANSWER = """
            {"document":{"name":"trace-test-policy.md","title":"Politica","format":"markdown"},
             "chunks":[
              {"text":"Plazos\\n\\nTreinta dias.","metadata":{"document":"trace-test-policy.md",
               "section":"Politica > Plazos","position":0}}]}
            """;

    @TestConfiguration
    static class EmbeddingDouble {

        @Bean
        @Primary
        DeterministicEmbeddingPort traceEmbeddingPort() {
            return new DeterministicEmbeddingPort(MODEL);
        }
    }

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @RegisterExtension
    static WireMockExtension ingest = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
        registry.add("shoplab.retrieval.ingest.base-url", ingest::baseUrl);
        RecordedSpansConfig.enableTracing(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private IngestDocumentServicePort ingestion;

    @Autowired
    private IndexDocumentsServicePort indexing;

    @Autowired
    private DeterministicEmbeddingPort embedding;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChatTracePort chatTrace;

    @BeforeEach
    void prepare() {
        SPANS.reset();
        ollama.resetAll();
        ingest.resetAll();
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(ANSWER)));
        OllamaStubs.stubAnyChat(ollama,
                OllamaStubs.streaming(OllamaStubs.bodyWithUsage(11, 7, "Treinta ", "dias.")));
        embedding.recover();
        embedding.useModel(MODEL);
        embedding.resetCounters();
        ingestion.ingest(new IngestDocumentCommand(SOURCE, "# Politica\n\n## Plazos\n\nTreinta dias."
                .getBytes(StandardCharsets.UTF_8))).block();
        indexing.indexPending(false).block();
    }

    @AfterEach
    void removeTestDocuments() {
        jdbc.update("DELETE FROM retrieval.document WHERE source LIKE 'trace-test-%'");
    }

    private ChatTestClient client() {
        return new ChatTestClient(port);
    }

    @Test
    void aQuestionWithContextLeavesOneCompleteTraceDerivedFromItsRequestId() {
        ChatTestClient.Result result = client().post("{\"message\":\"" + QUESTION + "\"}", "text/event-stream",
                "trace-req-1");
        flush(chatTrace);

        assertThat(result.text()).isEqualTo("Treinta dias.");
        assertThat(SPANS.getFinishedSpanItems()).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("chat-turn", "retrieval", "generation");
        SpanData root = span("chat-turn");
        assertThat(root.getTraceId()).isEqualTo(DerivedTraceIdGenerator.traceIdOf("trace-req-1"));
        assertThat(attr(root, "langfuse.trace.metadata.request_id")).isEqualTo("trace-req-1");
        assertThat(attr(root, "langfuse.trace.metadata.result")).isEqualTo("completed");
        assertThat(attr(root, "langfuse.environment")).isEqualTo("test");
        assertThat(attr(root, "langfuse.observation.output")).isEqualTo("Treinta dias.");
    }

    @Test
    void theRetrievalShowsTheFragmentWithItsSourceAndScoreAndThePromptCarriesIt() {
        client().post("{\"message\":\"" + QUESTION + "\"}", "text/event-stream", "trace-req-2");
        flush(chatTrace);

        String fragments = attr(span("retrieval"), "langfuse.observation.output");
        assertThat(fragments).contains("\"source\":\"" + SOURCE + "\"", "\"title\":\"Politica\"",
                "\"score\":", "Treinta dias.");
        String prompt = attr(span("generation"), "langfuse.observation.input");
        assertThat(prompt).contains("\\\"role\\\"".replace("\\\"", "\"")).contains("Treinta dias.")
                .contains("CONTEXTO");
    }

    @Test
    void theGenerationHasTheTokensTheModelReportedAndNoCostForALocalModel() {
        client().post("{\"message\":\"" + QUESTION + "\"}", "text/event-stream", "trace-req-3");
        flush(chatTrace);

        SpanData generation = span("generation");
        assertThat(attr(generation, "langfuse.observation.model.name")).isEqualTo("llama3.2:3b");
        assertThat(attr(generation, "langfuse.observation.usage_details"))
                .isEqualTo("{\"input\":11,\"output\":7,\"total\":18}");
        assertThat(attr(generation, "langfuse.observation.cost_details"))
                .isEqualTo("{\"input\":0.0,\"output\":0.0,\"total\":0.0}");
        assertThat(attr(generation, "langfuse.observation.metadata.cost_status")).isEqualTo("priced");
    }

    @Test
    void twoQuestionsOfTheSameConversationShareTheSession() {
        String body = "{\"message\":\"" + QUESTION + "\",\"conversationId\":\"" + CONVERSATION + "\"}";
        client().post(body, "text/event-stream", "trace-req-4a");
        client().post(body, "text/event-stream", "trace-req-4b");
        flush(chatTrace);

        assertThat(SPANS.getFinishedSpanItems().stream()
                .filter(item -> item.getName().equals("chat-turn"))
                .map(item -> attr(item, "langfuse.session.id")).toList())
                .containsExactly(CONVERSATION, CONVERSATION);
        jdbc.update("DELETE FROM chat.conversation_turn WHERE conversation_id = ?::uuid", CONVERSATION);
    }

    @Test
    void neitherTheLogsNorTheSpansCarryTheKeysAndTheLogsDoNotCarryTheContent(CapturedOutput output) {
        client().post("{\"message\":\"" + QUESTION + "\"}", "text/event-stream", "trace-req-5");
        flush(chatTrace);

        assertThat(output.getAll()).doesNotContain(SECRET_KEY, "Treinta dias.");
        SPANS.getFinishedSpanItems().forEach(item -> item.getAttributes().forEach(
                (key, value) -> assertThat(String.valueOf(value)).doesNotContain(SECRET_KEY)));
    }
}
