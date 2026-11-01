package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.retrieval.application.port.in.IndexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.application.port.in.IngestDocumentServicePort;
import com.shoplab.helpdesk.retrieval.domain.model.IngestDocumentCommand;
import com.shoplab.helpdesk.retrieval.support.DeterministicEmbeddingPort;
import com.shoplab.helpdesk.support.PostgresTest;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The number of fragments that reach the model is the configured top-k, not a number in the code (US3). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shoplab.chat.rag.top-k=2", "shoplab.chat.rag.similarity-threshold=0.0"})
@PostgresTest
@Import(RagConfigurationIntegrationTests.EmbeddingDouble.class)
class RagConfigurationIntegrationTests {

    private static final String SOURCE = "rag-topk-test-policy.md";
    private static final String MODEL = "rag-topk-model";
    private static final String ANSWER = """
            {"document":{"name":"rag-topk-test-policy.md","title":"Politica","format":"markdown"},
             "chunks":[
              {"text":"Texto uno.","metadata":{"document":"rag-topk-test-policy.md","section":"A","position":0}},
              {"text":"Texto dos.","metadata":{"document":"rag-topk-test-policy.md","section":"B","position":1}},
              {"text":"Texto tres.","metadata":{"document":"rag-topk-test-policy.md","section":"C","position":2}},
              {"text":"Texto cuatro.","metadata":{"document":"rag-topk-test-policy.md","section":"D","position":3}},
              {"text":"Texto cinco.","metadata":{"document":"rag-topk-test-policy.md","section":"E","position":4}}]}
            """;

    @TestConfiguration
    static class EmbeddingDouble {

        @Bean
        @Primary
        DeterministicEmbeddingPort topKEmbeddingPort() {
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
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        ollama.resetAll();
        ingest.resetAll();
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(ANSWER)));
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("ok")));
    }

    @AfterEach
    void removeTestDocuments() {
        jdbc.update("DELETE FROM retrieval.document WHERE source LIKE 'rag-topk-test-%'");
    }

    @Test
    void withFiveCandidatesAndATopKOfTwoExactlyTwoFragmentsReachTheModelAndTheClosestIsOneOfThem() throws Exception {
        ingestion.ingest(new IngestDocumentCommand(SOURCE, "# Politica\n\nTexto.".getBytes(StandardCharsets.UTF_8)))
                .block();
        indexing.indexPending(false).block();

        ChatTestClient.Result result = new ChatTestClient(port).post("{\"message\":\"Texto tres.\"}");

        assertThat(result.status()).isEqualTo(200);
        String requestBody = ollama.findAll(postRequestedFor(urlEqualTo("/api/chat"))).get(0).getBodyAsString();
        Matcher fragments = Pattern.compile("\\[" + Pattern.quote(SOURCE) + " › [A-E]]").matcher(requestBody);
        int count = 0;
        while (fragments.find()) {
            count++;
        }
        assertThat(count).isEqualTo(2);
        assertThat(requestBody).contains("Texto tres.");
    }
}
