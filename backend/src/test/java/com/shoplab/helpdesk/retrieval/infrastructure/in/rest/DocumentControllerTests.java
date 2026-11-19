package com.shoplab.helpdesk.retrieval.infrastructure.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.retrieval.application.port.in.IngestDocumentServicePort;
import com.shoplab.helpdesk.retrieval.application.port.in.ListDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.config.RetrievalUploadConfig;
import com.shoplab.helpdesk.retrieval.domain.model.DocumentSummary;
import com.shoplab.helpdesk.retrieval.domain.model.IngestDocumentCommand;
import com.shoplab.helpdesk.retrieval.domain.model.IngestedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexStatus;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.handler.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


@WebFluxTest(DocumentController.class)
@Import({RequestIdFilter.class, GlobalExceptionHandler.class, RetrievalUploadConfig.class})
@TestPropertySource(properties = "shoplab.retrieval.upload.max-bytes=64")
class DocumentControllerTests {

    private static final UUID ID = UUID.fromString("6f1c1c1e-0d6b-4e57-9a6d-3b9a7f0f4a11");
    private static final byte[] CONTENT = "# Politica\n\nTexto.".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private IngestDocumentServicePort ingestDocument;

    @MockitoBean
    private ListDocumentsServicePort listDocuments;

    private WebTestClient.ResponseSpec upload(String filename, byte[] content) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        return webTestClient.post().uri("/api/documents").header("X-Request-Id", "req-25")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build())).exchange();
    }

    private static IngestedDocument stored(boolean replaced) {
        return new IngestedDocument(ID, "politica.md", "Politica", 14, replaced);
    }

    @Test
    void aNewDocumentIsCreatedAndPending() {
        when(ingestDocument.ingest(any())).thenReturn(Mono.just(stored(false)));

        upload("politica.md", CONTENT)
                .expectStatus().isCreated()
                .expectHeader().valueEquals("X-Request-Id", "req-25")
                .expectBody()
                .jsonPath("$.id").isEqualTo(ID.toString())
                .jsonPath("$.name").isEqualTo("politica.md")
                .jsonPath("$.title").isEqualTo("Politica")
                .jsonPath("$.status").isEqualTo("PENDING")
                .jsonPath("$.chunkCount").isEqualTo(14)
                .jsonPath("$.replaced").isEqualTo(false);
    }

    @Test
    void aDocumentWithAnExistingNameIsReplacedWithOk() {
        when(ingestDocument.ingest(any())).thenReturn(Mono.just(stored(true)));

        upload("politica.md", CONTENT)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.replaced").isEqualTo(true);
    }

    @Test
    void buildsTheCommandWithTheBytesTheNameAndTheRequestId() {
        when(ingestDocument.ingest(any())).thenReturn(Mono.just(stored(false)));

        upload("politica.md", CONTENT).expectStatus().isCreated();

        ArgumentCaptor<IngestDocumentCommand> captor = ArgumentCaptor.forClass(IngestDocumentCommand.class);
        verify(ingestDocument).ingest(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("politica.md");
        assertThat(captor.getValue().content()).isEqualTo(CONTENT);
        assertThat(captor.getValue().requestId()).isEqualTo("req-25");
    }

    @ParameterizedTest
    @CsvSource({"C:\\fakepath\\politica.md,politica.md", "carpeta/sub/politica.md,politica.md",
        "../politica.md,politica.md", "carpeta/,''"})
    void onlyTheLastSegmentOfTheFileNameIsUsed(String filename, String expected) {
        assertThat(DocumentController.lastSegment(filename)).isEqualTo(expected);
        assertThat(DocumentController.lastSegment(null)).isNull();
    }

    @Test
    void anEmptyFileReachesTheUseCaseWithoutContentSoItIsRejected() {
        when(ingestDocument.ingest(any())).thenReturn(
                Mono.error(ExceptionUtil.business(ResponseCodeEnum.DOCUMENT_INVALID)));

        upload("vacio.md", new byte[0])
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_RETR_00003");

        ArgumentCaptor<IngestDocumentCommand> captor = ArgumentCaptor.forClass(IngestDocumentCommand.class);
        verify(ingestDocument).ingest(captor.capture());
        assertThat(captor.getValue().content()).isEmpty();
    }

    @Test
    void aFileOverTheLimitIsRejectedWithoutCallingTheUseCase() {
        upload("grande.md", new byte[65])
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_RETR_00002");

        verifyNoInteractions(ingestDocument);
    }

    @Test
    void aFileOfExactlyTheLimitIsAccepted() {
        when(ingestDocument.ingest(any())).thenReturn(Mono.just(stored(false)));

        upload("justo.md", new byte[64]).expectStatus().isCreated();
    }

    @Test
    void aRequestWithoutTheFilePartIsABadRequest() {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("other", "x");

        webTestClient.post().uri("/api/documents").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build())).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_GENE_00002");

        verifyNoInteractions(ingestDocument);
    }

    @Test
    void aRequestThatIsNotMultipartIsABadRequest() {
        webTestClient.post().uri("/api/documents").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_GENE_00002");
    }

    @ParameterizedTest
    @CsvSource({"DOCUMENT_UNSUPPORTED_FORMAT,415,SLHD_RETR_00001", "DOCUMENT_TOO_LARGE,413,SLHD_RETR_00002",
        "DOCUMENT_INVALID,422,SLHD_RETR_00003"})
    void aRejectedDocumentKeepsTheErrorContract(ResponseCodeEnum code, int status, String expected) {
        when(ingestDocument.ingest(any())).thenReturn(Mono.error(ExceptionUtil.business(code)));

        upload("politica.md", CONTENT)
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.code").isEqualTo(expected).jsonPath("$.message").isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({"INGEST_SERVICE_UNAVAILABLE,503,SLHD_RETR_00004", "INGEST_SERVICE_FAILED,502,SLHD_RETR_00005",
        "DATABASE_UNAVAILABLE,503,SLHD_DBAS_00001"})
    void aTechnicalFailureKeepsTheErrorContractWithoutItsCause(ResponseCodeEnum code, int status, String expected) {
        when(ingestDocument.ingest(any())).thenReturn(Mono.error(
                ExceptionUtil.technical(code, new IllegalStateException("secret internal detail"))));

        upload("politica.md", CONTENT)
                .expectStatus().isEqualTo(status)
                .expectBody(String.class).value(text -> assertThat(text).contains(expected)
                        .doesNotContain("secret internal detail"));
    }

    @Test
    void listsTheDocumentsWithStateChunksAndCause() {
        Instant updated = Instant.parse("2026-11-10T15:02:11.482Z");
        when(listDocuments.list()).thenReturn(Flux.just(
                new DocumentSummary(ID, "politica.md", "Politica", IndexStatus.INDEXED, 14, null, updated,
                        Instant.parse("2026-11-10T15:02:19.003Z")),
                new DocumentSummary(UUID.fromString("0a3e5a9b-4b2a-4a70-8c64-1d0c2a6f9b77"), "envios.pdf", "Envios",
                        IndexStatus.ERROR, 9, "The embedding model is not available", updated, null)));

        webTestClient.get().uri("/api/documents").exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].name").isEqualTo("politica.md")
                .jsonPath("$[0].status").isEqualTo("INDEXED")
                .jsonPath("$[0].chunkCount").isEqualTo(14)
                .jsonPath("$[0].error").isEmpty()
                .jsonPath("$[0].updatedAt").isEqualTo("2026-11-10T15:02:11.482Z")
                .jsonPath("$[0].indexedAt").isEqualTo("2026-11-10T15:02:19.003Z")
                .jsonPath("$[1].status").isEqualTo("ERROR")
                .jsonPath("$[1].error").isEqualTo("The embedding model is not available")
                .jsonPath("$[1].indexedAt").isEmpty();
    }

    @Test
    void anEmptyKnowledgeBaseIsAnEmptyList() {
        when(listDocuments.list()).thenReturn(Flux.empty());

        webTestClient.get().uri("/api/documents").exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");
    }

    @Test
    void theListFailsWithServiceUnavailableWhenTheDatabaseIsDown() {
        when(listDocuments.list()).thenReturn(Flux.error(
                ExceptionUtil.technical(ResponseCodeEnum.DATABASE_UNAVAILABLE, new IllegalStateException("down"))));

        webTestClient.get().uri("/api/documents").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_DBAS_00001");
    }
}
