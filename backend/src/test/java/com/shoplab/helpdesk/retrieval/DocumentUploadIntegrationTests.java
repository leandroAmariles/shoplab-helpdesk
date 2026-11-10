package com.shoplab.helpdesk.retrieval;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.support.PostgresTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shoplab.retrieval.embedding.auto-index=false")
@PostgresTest
class DocumentUploadIntegrationTests {

    private static final String NAME = "upload-test-politica.md";
    private static final byte[] CONTENT = "# Politica\n\n## Plazos\n\nTreinta dias.".getBytes(StandardCharsets.UTF_8);
    private static final String ANSWER = """
            {"document":{"name":"upload-test-politica.md","title":"Politica","format":"markdown"},
             "chunks":[
              {"text":"Politica","metadata":{"document":"upload-test-politica.md","section":"Politica","position":0}},
              {"text":"Plazos\\n\\nTreinta dias.","metadata":{"document":"upload-test-politica.md",
               "section":"Politica > Plazos","position":1}}]}
            """;

    @RegisterExtension
    static WireMockExtension ingest = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ingestUrl(DynamicPropertyRegistry registry) {
        registry.add("shoplab.retrieval.ingest.base-url", ingest::baseUrl);
    }

    @LocalServerPort
    private int port;

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
        jdbc.update("DELETE FROM retrieval.document WHERE source LIKE 'upload-test-%'");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build();
    }

    private WebTestClient.ResponseSpec upload(String filename, byte[] content) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        return client().post().uri("/api/documents").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build())).exchange();
    }

    private void stubAnswer(int status, String body) {
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private double uploads(String outcome) {
        var timer = meters.find("retrieval.upload").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void anUploadedDocumentIsStoredPendingAndListedWithItsChunks() {
        stubAnswer(200, ANSWER);
        double before = uploads("ok");

        upload(NAME, CONTENT)
                .expectStatus().isCreated()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.name").isEqualTo(NAME)
                .jsonPath("$.status").isEqualTo("PENDING")
                .jsonPath("$.chunkCount").isEqualTo(2)
                .jsonPath("$.replaced").isEqualTo(false);

        client().get().uri("/api/documents").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.name == '" + NAME + "')].status").isEqualTo("PENDING")
                .jsonPath("$[?(@.name == '" + NAME + "')].chunkCount").isEqualTo(2);
        assertThat(uploads("ok")).isEqualTo(before + 1);
        assertThat(meters.find("retrieval.documents").tag("status", "PENDING").gauge()).isNotNull();
    }

    @Test
    void uploadingTheSameNameAgainReplacesItAndAnswersOk() {
        stubAnswer(200, ANSWER);
        upload(NAME, CONTENT).expectStatus().isCreated();

        upload(NAME, CONTENT)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.replaced").isEqualTo(true);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval.document WHERE source = ?", Integer.class,
                NAME)).isEqualTo(1);
    }

    @Test
    void aDocumentRejectedByTheIngestServiceStoresNothing() {
        stubAnswer(422, "{\"code\":\"NO_EXTRACTABLE_TEXT\",\"message\":\"scanned\"}");
        double before = uploads("rejected");

        upload(NAME, CONTENT)
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_RETR_00003");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval.document WHERE source = ?", Integer.class,
                NAME)).isZero();
        assertThat(uploads("rejected")).isEqualTo(before + 1);
    }

    @Test
    void aFileOverTheLimitIsRejectedBeforeReachingTheIngestService() {
        upload("upload-test-grande.md", new byte[10 * 1024 * 1024 + 1])
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_RETR_00002");

        ingest.verify(0, postRequestedFor(urlEqualTo("/ingest")));
    }

    @Test
    void aPartOverTheLimitOfTheMultipartReaderIsTooLargeToo() {
        upload("upload-test-enorme.md", new byte[12 * 1024 * 1024])
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_RETR_00002");

        ingest.verify(0, postRequestedFor(urlEqualTo("/ingest")));
    }
}
