package com.shoplab.helpdesk.retrieval;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.retrieval.application.port.in.IngestDocumentServicePort;
import com.shoplab.helpdesk.retrieval.domain.model.IngestDocumentCommand;
import com.shoplab.helpdesk.retrieval.domain.model.IngestedDocument;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.support.PostgresTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.test.StepVerifier;

/**
 * A document goes from the use case, through a simulated ingest service, to the retrieval schema of a real PostgreSQL
 * (specs/LAB-20, FR-011 to FR-015, SC-005 and SC-007).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class IngestDocumentFlowIntegrationTests {

    private static final String SOURCE = "flow-test-politica.md";
    private static final byte[] CONTENT = "# Politica\n\n## Plazos\n\nTreinta dias.".getBytes(StandardCharsets.UTF_8);
    private static final String ANSWER = """
            {"document":{"name":"flow-test-politica.md","title":"Politica","format":"markdown"},
             "chunks":[
              {"text":"Politica","metadata":{"document":"flow-test-politica.md","section":"Politica","position":0}},
              {"text":"Plazos\\n\\nTreinta dias.","metadata":{"document":"flow-test-politica.md",
               "section":"Politica > Plazos","position":1}}]}
            """;

    @RegisterExtension
    static WireMockExtension ingest = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ingestUrl(DynamicPropertyRegistry registry) {
        registry.add("shoplab.retrieval.ingest.base-url", ingest::baseUrl);
    }

    @Autowired
    private IngestDocumentServicePort useCase;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void resetStubs() {
        ingest.resetAll();
    }

    @AfterEach
    void removeTestDocuments() {
        jdbc.update("DELETE FROM retrieval.document WHERE source LIKE 'flow-test-%'");
    }

    private int documents() {
        return jdbc.queryForObject("SELECT count(*) FROM retrieval.document WHERE source = ?", Integer.class, SOURCE);
    }

    private int chunks() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM retrieval.chunk c JOIN retrieval.document d ON d.id = c.document_id
                WHERE d.source = ?
                """, Integer.class, SOURCE);
    }

    private double parseCalls(String outcome) {
        var timer = meters.find("retrieval.ingest.parse").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private void stubAnswer(int status, String body) {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void aDocumentEndsUpInTheRetrievalSchemaWithoutVectors() {
        stubAnswer(200, ANSWER);
        double before = parseCalls("ok");

        IngestedDocument stored = useCase.ingest(new IngestDocumentCommand(SOURCE, CONTENT)).block();

        assertThat(stored).isNotNull();
        assertThat(stored.chunkCount()).isEqualTo(2);
        assertThat(documents()).isEqualTo(1);
        assertThat(chunks()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM retrieval.chunk WHERE document_id = ? AND embedding IS NULL
                """, Integer.class, stored.documentId())).isEqualTo(2);
        assertThat(parseCalls("ok")).isEqualTo(before + 1);
    }

    @Test
    void ingestingItASecondTimeLeavesTheSameDocumentsAndChunks() {
        stubAnswer(200, ANSWER);

        IngestedDocument first = useCase.ingest(new IngestDocumentCommand(SOURCE, CONTENT)).block();
        IngestedDocument second = useCase.ingest(new IngestDocumentCommand(SOURCE, CONTENT)).block();

        assertThat(second).isEqualTo(first);
        assertThat(documents()).isEqualTo(1);
        assertThat(chunks()).isEqualTo(2);
    }

    @Test
    void aRejectedDocumentStoresNothingAndIsABusinessError() {
        stubAnswer(422, "{\"code\":\"NO_EXTRACTABLE_TEXT\",\"message\":\"scanned\"}");
        double before = parseCalls("rejected");

        StepVerifier.create(useCase.ingest(new IngestDocumentCommand(SOURCE, CONTENT)))
                .expectErrorSatisfies(error -> assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.DOCUMENT_INVALID))
                .verify();

        assertThat(documents()).isZero();
        assertThat(chunks()).isZero();
        assertThat(parseCalls("rejected")).isEqualTo(before + 1);
    }

    @Test
    void aServiceThatIsDownStoresNothing() {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        double before = parseCalls("failed");

        StepVerifier.create(useCase.ingest(new IngestDocumentCommand(SOURCE, CONTENT)))
                .expectErrorSatisfies(error -> assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INGEST_SERVICE_UNAVAILABLE))
                .verify();

        assertThat(documents()).isZero();
        assertThat(parseCalls("failed")).isEqualTo(before + 1);
    }

    @Test
    void aFailureAfterAnIngestionKeepsWhatWasStoredBefore() {
        stubAnswer(200, ANSWER);
        IngestedDocument stored = useCase.ingest(new IngestDocumentCommand(SOURCE, CONTENT)).block();
        stubAnswer(500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"Unexpected error\"}");

        StepVerifier.create(useCase.ingest(new IngestDocumentCommand(SOURCE, CONTENT)))
                .expectErrorSatisfies(error -> assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INGEST_SERVICE_FAILED))
                .verify();

        assertThat(stored).isNotNull();
        assertThat(documents()).isEqualTo(1);
        assertThat(chunks()).isEqualTo(2);
    }
}
