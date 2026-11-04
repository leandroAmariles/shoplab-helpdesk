package com.shoplab.helpdesk.retrieval;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.retrieval.application.port.in.IndexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.application.port.in.IngestDocumentServicePort;
import com.shoplab.helpdesk.retrieval.application.port.in.ReindexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.application.port.in.SearchChunksServicePort;
import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingPort;
import com.shoplab.helpdesk.retrieval.domain.model.IndexingSummary;
import com.shoplab.helpdesk.retrieval.domain.model.IngestDocumentCommand;
import com.shoplab.helpdesk.retrieval.domain.model.IngestedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.SearchResult;
import com.shoplab.helpdesk.retrieval.support.DeterministicEmbeddingPort;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.support.PostgresTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.test.StepVerifier;

/**
 * A document goes from the use case, through a simulated ingest service, to chunks with their vectors in the retrieval
 * schema of a real PostgreSQL, and is found by the search; then the model changes and everything is reindexed without
 * calling the ingest service again (specs/LAB-21, FR-018, SC-001 to SC-005, SC-008 and SC-009). The embedding model is
 * a deterministic double, so the test needs no model server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
@Import(EmbeddingFlowIntegrationTests.EmbeddingDouble.class)
class EmbeddingFlowIntegrationTests {

    private static final String SOURCE = "flow-test-embeddings.md";
    private static final String MODEL_A = "flow-model-a";
    private static final String MODEL_B = "flow-model-b";
    private static final byte[] CONTENT = "# Politica\n\n## Plazos\n\nTreinta dias.".getBytes(StandardCharsets.UTF_8);
    private static final String ANSWER = """
            {"document":{"name":"flow-test-embeddings.md","title":"Politica","format":"markdown"},
             "chunks":[
              {"text":"Politica","metadata":{"document":"flow-test-embeddings.md","section":"Politica","position":0}},
              {"text":"Plazos\\n\\nTreinta dias.","metadata":{"document":"flow-test-embeddings.md",
               "section":"Politica > Plazos","position":1}}]}
            """;

    @TestConfiguration
    static class EmbeddingDouble {

        @Bean
        @Primary
        DeterministicEmbeddingPort deterministicEmbeddingPort() {
            return new DeterministicEmbeddingPort(MODEL_A);
        }
    }

    @RegisterExtension
    static WireMockExtension ingest = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ingestUrl(DynamicPropertyRegistry registry) {
        registry.add("shoplab.retrieval.ingest.base-url", ingest::baseUrl);
    }

    @Autowired
    private IngestDocumentServicePort ingestion;

    @Autowired
    private IndexDocumentsServicePort indexing;

    @Autowired
    private ReindexDocumentsServicePort reindexing;

    @Autowired
    private SearchChunksServicePort search;

    @Autowired
    private DeterministicEmbeddingPort embedding;

    @Autowired
    private EmbeddingPort embeddingPort;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void stubTheIngestService() {
        ingest.resetAll();
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(ANSWER)));
        embedding.recover();
        embedding.useModel(MODEL_A);
        embedding.resetCounters();
    }

    @AfterEach
    void removeTestDocuments() {
        jdbc.update("DELETE FROM retrieval.document WHERE source LIKE 'flow-test-%'");
        jdbc.execute("VACUUM retrieval.chunk");
    }

    private IngestedDocument ingestTheDocument() {
        return ingestion.ingest(new IngestDocumentCommand(SOURCE, CONTENT)).block();
    }

    private String state(UUID documentId) {
        return jdbc.queryForObject("SELECT index_status || '|' || coalesce(index_error, '-') "
                + "FROM retrieval.document WHERE id = ?", String.class, documentId);
    }

    private int chunks(UUID documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM retrieval.chunk WHERE document_id = ?", Integer.class,
                documentId);
    }

    private int chunksWithVector(UUID documentId, String model) {
        return jdbc.queryForObject("SELECT count(*) FROM retrieval.chunk WHERE document_id = ? "
                + "AND embedding IS NOT NULL AND embedding_model = ?", Integer.class, documentId, model);
    }

    @Test
    void theInjectedModelIsTheDeterministicDouble() {
        assertThat(embeddingPort).isSameAs(embedding);
    }

    @Test
    void aDocumentGoesFromTheIngestServiceToChunksWithTheirVectorAndIsIndexed() {
        IngestedDocument stored = ingestTheDocument();
        assertThat(state(stored.documentId())).isEqualTo("PENDING|-");
        assertThat(chunksWithVector(stored.documentId(), MODEL_A)).isZero();

        IndexingSummary summary = indexing.indexPending(false).block();

        assertThat(summary.documents()).isGreaterThanOrEqualTo(1);
        assertThat(state(stored.documentId())).isEqualTo("INDEXED|-");
        assertThat(chunks(stored.documentId())).isEqualTo(2);
        assertThat(chunksWithVector(stored.documentId(), MODEL_A)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT min(vector_dims(embedding)) || '|' || max(vector_dims(embedding)) FROM retrieval.chunk
                WHERE document_id = ?
                """, String.class, stored.documentId())).isEqualTo("1024|1024");
    }

    @Test
    void theSearchFindsTheChunkThatHoldsTheText() {
        IngestedDocument stored = ingestTheDocument();
        indexing.indexPending(false).block();

        List<SearchResult> results = search.search("Plazos\n\nTreinta dias.", 3).block();

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).documentId()).isEqualTo(stored.documentId());
        assertThat(results.get(0).source()).isEqualTo(SOURCE);
        assertThat(results.get(0).title()).isEqualTo("Politica");
        assertThat(results.get(0).section()).isEqualTo("Politica > Plazos");
        assertThat(results.get(0).position()).isEqualTo(1);
    }

    @Test
    void aChunkWithoutVectorIsNotFoundUntilItIsIndexed() {
        ingestTheDocument();

        assertThat(search.search("Plazos\n\nTreinta dias.", 3).block())
                .noneMatch(result -> SOURCE.equals(result.source()));
    }

    @Test
    void changingTheModelAndReindexingMovesEveryChunkWithoutCallingTheIngestServiceAgain() {
        IngestedDocument stored = ingestTheDocument();
        indexing.indexPending(false).block();
        embedding.useModel(MODEL_B);
        assertThat(search.search("Plazos\n\nTreinta dias.", 3).block())
                .noneMatch(result -> SOURCE.equals(result.source()));

        IndexingSummary summary = reindexing.reindex(false).block();

        assertThat(summary.documents()).isGreaterThanOrEqualTo(1);
        assertThat(chunksWithVector(stored.documentId(), MODEL_B)).isEqualTo(2);
        assertThat(chunksWithVector(stored.documentId(), MODEL_A)).isZero();
        assertThat(state(stored.documentId())).isEqualTo("INDEXED|-");
        assertThat(search.search("Plazos\n\nTreinta dias.", 3).block().get(0).source()).isEqualTo(SOURCE);
        ingest.verify(exactly(1), postRequestedFor(urlEqualTo("/ingest")));
    }

    @Test
    void indexingAgainDoesNotCallTheModelForDocumentsAlreadyIndexed() {
        ingestTheDocument();
        indexing.indexPending(false).block();
        embedding.resetCounters();

        indexing.indexPending(true).block();
        reindexing.reindex(false).block();

        assertThat(embedding.batches()).isZero();
    }

    @Test
    void aFailureOfTheModelLeavesTheDocumentInErrorWithItsChunksAndARetryIndexesIt() {
        IngestedDocument stored = ingestTheDocument();
        embedding.failAlways(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE);

        IndexingSummary failed = indexing.indexPending(false).block();

        assertThat(failed.failed()).extracting("source").contains(SOURCE);
        assertThat(state(stored.documentId())).isEqualTo("ERROR|The embedding model is not available");
        assertThat(chunks(stored.documentId())).isEqualTo(2);
        assertThat(chunksWithVector(stored.documentId(), MODEL_A)).isZero();

        embedding.recover();
        assertThat(indexing.indexPending(false).block().documents()).isZero();
        assertThat(state(stored.documentId())).startsWith("ERROR");
        indexing.indexPending(true).block();

        assertThat(state(stored.documentId())).isEqualTo("INDEXED|-");
        assertThat(chunksWithVector(stored.documentId(), MODEL_A)).isEqualTo(2);
    }

    @Test
    void ingestingAnIndexedDocumentAgainLeavesItPendingWithoutTheOldVectors() {
        IngestedDocument stored = ingestTheDocument();
        indexing.indexPending(false).block();

        ingestTheDocument();

        assertThat(state(stored.documentId())).isEqualTo("PENDING|-");
        assertThat(chunksWithVector(stored.documentId(), MODEL_A)).isZero();
    }

    @Test
    void aDocumentIngestedAgainWhileItsVectorsAreComputedKeepsNoVectorOfTheOldContent() {
        IngestedDocument stored = ingestTheDocument();
        embedding.beforeEachBatch(() -> {
            embedding.beforeEachBatch(null);
            ingestTheDocument();
        });

        IndexingSummary summary = indexing.indexPending(false).block();

        assertThat(summary.failed()).isEmpty();
        assertThat(state(stored.documentId())).isEqualTo("PENDING|-");
        assertThat(chunksWithVector(stored.documentId(), MODEL_A)).isZero();
        assertThat(chunks(stored.documentId())).isEqualTo(2);
    }

    @Test
    void aRunStartedWhileAnotherIsInProgressDoesNothingAndTheDocumentIsComputedOnce() {
        IngestedDocument stored = ingestTheDocument();
        List<IndexingSummary> nested = new java.util.ArrayList<>();
        embedding.beforeEachBatch(() -> {
            embedding.beforeEachBatch(null);
            nested.add(indexing.indexPending(false).block());
        });

        indexing.indexPending(false).block();

        assertThat(nested).singleElement().satisfies(summary -> assertThat(summary.skipped()).isTrue());
        assertThat(embedding.textsEmbedded()).isEqualTo(2);
        assertThat(state(stored.documentId())).isEqualTo("INDEXED|-");
    }

    @Test
    void aBlankQueryIsRejectedAndTheModelIsNotCalled() {
        StepVerifier.create(search.search("   ", null))
                .expectErrorSatisfies(error -> assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.SEARCH_QUERY_INVALID))
                .verify();

        assertThat(embedding.batches()).isZero();
    }
}
