package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.retrieval.application.port.in.IndexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.application.port.in.IngestDocumentServicePort;
import com.shoplab.helpdesk.retrieval.domain.model.IngestDocumentCommand;
import com.shoplab.helpdesk.retrieval.support.DeterministicEmbeddingPort;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.support.PostgresTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * A question goes through the whole chat: the knowledge base is searched in a real PostgreSQL with a deterministic
 * embedding model, and the request that reaches the (simulated) Ollama carries the right context, or says there is
 * none (specs/LAB-22, US1, US2 and US4). The contract of the event stream is the one of LAB-12.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresTest
@Import(RagAnsweringIntegrationTests.EmbeddingDouble.class)
@ExtendWith(OutputCaptureExtension.class)
class RagAnsweringIntegrationTests {

    private static final String SOURCE = "rag-test-policy.md";
    private static final String MODEL = "rag-model";
    private static final String QUESTION_JSON = "{\"message\":\"Plazos\\n\\nTreinta dias.\"}";
    private static final String ANSWER = """
            {"document":{"name":"rag-test-policy.md","title":"Politica","format":"markdown"},
             "chunks":[
              {"text":"Plazos\\n\\nTreinta dias.","metadata":{"document":"rag-test-policy.md",
               "section":"Politica > Plazos","position":0}}]}
            """;

    @TestConfiguration
    static class EmbeddingDouble {

        @Bean
        @Primary
        DeterministicEmbeddingPort ragEmbeddingPort() {
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
    static void servers(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
        registry.add("shoplab.retrieval.ingest.base-url", ingest::baseUrl);
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

    @BeforeEach
    void prepare() {
        ollama.resetAll();
        ingest.resetAll();
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(ANSWER)));
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("Treinta ", "dias.")));
        embedding.recover();
        embedding.useModel(MODEL);
        embedding.resetCounters();
    }

    @AfterEach
    void removeTestDocuments() {
        jdbc.update("DELETE FROM retrieval.document WHERE source LIKE 'rag-test-%'");
    }

    private void indexTheDocument() {
        ingestion.ingest(new IngestDocumentCommand(SOURCE, "# Politica\n\n## Plazos\n\nTreinta dias."
                .getBytes(StandardCharsets.UTF_8))).block();
        indexing.indexPending(false).block();
    }

    private ChatTestClient client() {
        return new ChatTestClient(port);
    }

    @Test
    void theRequestToTheModelCarriesTheChunkThatAnswersTheQuestionAndTheEventContractIsUnchanged() {
        indexTheDocument();

        ChatTestClient.Result result = client().post(QUESTION_JSON);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.header("Content-Type")).startsWith("text/event-stream");
        assertThat(result.text()).isEqualTo("Treinta dias.");
        assertThat(result.last().name()).isEqualTo("done");
        assertThat(result.last().data()).isEqualTo("{\"fragments\":2}");
        ollama.verify(exactly(1), postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.messages[0].role", containing("system")))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        containing("solo con la información de la sección CONTEXTO")))
                .withRequestBody(matchingJsonPath("$.messages[1].content",
                        matching("(?s).*\\[" + SOURCE + " › Politica > Plazos]\\s+Plazos\\s+Treinta dias\\..*")))
                .withRequestBody(matchingJsonPath("$.messages[1].content",
                        matching("(?s).*Pregunta del cliente: Plazos\\s+Treinta dias\\..*"))));
    }

    @Test
    void withNothingIndexedTheAnswerIsTheFixedMessageAndOllamaIsNotCalled() {
        ChatTestClient.Result result = client().post("{\"message\":\"¿Venden el modelo X999?\"}");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.text()).isEqualTo(ChatConstants.DEFAULT_NO_CONTEXT_MESSAGE);
        assertThat(result.last().name()).isEqualTo("done");
        assertThat(result.last().data()).isEqualTo("{\"fragments\":1}");
        ollama.verify(exactly(0), postRequestedFor(urlEqualTo("/api/chat")));
        assertThat(prometheus()).containsPattern(
                "shoplab_chat_context_retrieval_seconds_count\\{[^}]*outcome=\"no_context\"[^}]*} [1-9]");
    }

    @Test
    void chunksIndexedWithAnotherModelAreNotUsedAndTheAnswerIsTheFixedMessage() {
        indexTheDocument();
        embedding.useModel("another-rag-model");

        ChatTestClient.Result result = client().post(QUESTION_JSON);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.text()).isEqualTo(ChatConstants.DEFAULT_NO_CONTEXT_MESSAGE);
        ollama.verify(exactly(0), postRequestedFor(urlEqualTo("/api/chat")));
    }

    @Test
    void whenTheSearchFailsTheAnswerIs503WithItsOwnCodeAndTheModelIsNeverCalled() {
        indexTheDocument();
        embedding.failNext(1, ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE);

        ChatTestClient.Result result = client().post(QUESTION_JSON);

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.body()).contains("SLHD_CHAT_00004").contains("The knowledge base is not available")
                .doesNotContain("SLHD_CHAT_00002");
        ollama.verify(exactly(0), postRequestedFor(urlEqualTo("/api/chat")));
        assertThat(prometheus()).containsPattern(
                "shoplab_chat_context_retrieval_seconds_count\\{[^}]*outcome=\"failed\"[^}]*} [1-9]");
    }

    @Test
    void aBlankMessageIsRejectedWithoutCallingTheEmbeddingModel() {
        ChatTestClient.Result result = client().post("{\"message\":\"   \"}");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body()).contains("SLHD_CHAT_00001");
        assertThat(embedding.batches()).isZero();
        ollama.verify(exactly(0), postRequestedFor(urlEqualTo("/api/chat")));
    }

    @Test
    void metricsAndLogsDescribeTheContextWithoutTheQuestionOrTheChunkText(CapturedOutput output) {
        indexTheDocument();

        client().post(QUESTION_JSON.replace("Plazos", "SecretoDelCliente"));
        client().post(QUESTION_JSON);

        assertThat(prometheus()).contains("shoplab_chat_context_fragments_count")
                .containsPattern("shoplab_chat_context_retrieval_seconds_count\\{[^}]*outcome=\"with_context\"");
        assertThat(output.getOut()).contains("Context retrieved")
                .doesNotContain("SecretoDelCliente").doesNotContain("Treinta dias");
    }

    private String prometheus() {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
