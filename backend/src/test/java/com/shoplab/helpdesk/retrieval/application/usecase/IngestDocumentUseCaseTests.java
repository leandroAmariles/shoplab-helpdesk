package com.shoplab.helpdesk.retrieval.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.retrieval.application.port.out.DocumentParserPort;
import com.shoplab.helpdesk.retrieval.application.port.out.IngestMetricsPort;
import com.shoplab.helpdesk.retrieval.application.port.out.KnowledgeBasePort;
import com.shoplab.helpdesk.retrieval.application.port.out.UploadMetricsPort;
import com.shoplab.helpdesk.retrieval.domain.model.DocumentIngestedEvent;
import com.shoplab.helpdesk.retrieval.domain.model.IngestDocumentCommand;
import com.shoplab.helpdesk.retrieval.domain.model.IngestedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.ParsedChunk;
import com.shoplab.helpdesk.retrieval.domain.model.ParsedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.enums.DocumentFormat;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IngestOutcome;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.exception.TechnicalException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IngestDocumentUseCaseTests {

    private static final byte[] CONTENT = "# Politica".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final ParsedDocument PARSED = new ParsedDocument("politica.md", "Politica", DocumentFormat.MARKDOWN,
            List.of(new ParsedChunk(0, "Politica", "Politica")));
    private static final IngestedDocument STORED = new IngestedDocument(UUID.randomUUID(), "politica.md", "Politica", 1,
            false);

    private final DocumentParserPort parser = mock(DocumentParserPort.class);
    private final KnowledgeBasePort knowledgeBase = mock(KnowledgeBasePort.class);
    private final IngestMetricsPort metrics = mock(IngestMetricsPort.class);
    private final UploadMetricsPort uploadMetrics = mock(UploadMetricsPort.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final IngestDocumentUseCase useCase = new IngestDocumentUseCase(parser, knowledgeBase, metrics,
            uploadMetrics, events);

    @Test
    void anUploadThatIsStoredIsMeasuredAsOk() {
        when(parser.parse("politica.md", CONTENT)).thenReturn(Mono.just(PARSED));
        when(knowledgeBase.replaceDocument(PARSED)).thenReturn(Mono.just(STORED));

        useCase.ingest(new IngestDocumentCommand("politica.md", CONTENT, "req-1")).block();

        verify(uploadMetrics).recordUpload(eq(IngestOutcome.OK), any(Duration.class));
    }

    @Test
    void anUploadOfAnInvalidDocumentIsMeasuredAsRejected() {
        StepVerifier.create(useCase.ingest(new IngestDocumentCommand("politica.md", new byte[0], "req-2")))
                .expectError(BusinessException.class).verify();

        verify(uploadMetrics).recordUpload(eq(IngestOutcome.REJECTED), any(Duration.class));
    }

    @Test
    void anUploadRejectedByTheParserIsMeasuredAsRejected() {
        when(parser.parse("politica.md", CONTENT))
                .thenReturn(Mono.error(ExceptionUtil.business(ResponseCodeEnum.DOCUMENT_UNSUPPORTED_FORMAT)));

        StepVerifier.create(useCase.ingest(new IngestDocumentCommand("politica.md", CONTENT)))
                .expectError(BusinessException.class).verify();

        verify(uploadMetrics).recordUpload(eq(IngestOutcome.REJECTED), any(Duration.class));
    }

    @Test
    void anUploadThatFailsToBeStoredIsMeasuredAsFailed() {
        when(parser.parse("politica.md", CONTENT)).thenReturn(Mono.just(PARSED));
        when(knowledgeBase.replaceDocument(PARSED)).thenReturn(Mono.error(
                ExceptionUtil.technical(ResponseCodeEnum.DATABASE_UNAVAILABLE, new IllegalStateException())));

        StepVerifier.create(useCase.ingest(new IngestDocumentCommand("politica.md", CONTENT)))
                .expectError(TechnicalException.class).verify();

        verify(uploadMetrics).recordUpload(eq(IngestOutcome.FAILED), any(Duration.class));
    }

    @Test
    void theLoggedNameHasNoControlCharactersAndABoundedLength() {
        assertThat(IngestDocumentUseCase.loggable("a\nb\rc.md")).isEqualTo("a_b_c.md");
        assertThat(IngestDocumentUseCase.loggable("x".repeat(300))).hasSize(203).endsWith("...");
        assertThat(IngestDocumentUseCase.loggable(null)).isNull();
    }

    @Test
    void parsesTheDocumentAndStoresWhatTheParserReturned() {
        when(parser.parse("politica.md", CONTENT)).thenReturn(Mono.just(PARSED));
        when(knowledgeBase.replaceDocument(PARSED)).thenReturn(Mono.just(STORED));

        StepVerifier.create(useCase.ingest(new IngestDocumentCommand("politica.md", CONTENT)))
                .expectNext(STORED)
                .verifyComplete();

        verify(metrics).recordParse(eq(IngestOutcome.OK), any(Duration.class));
    }

    @Test
    void announcesTheStoredDocumentSoItsVectorsCanBeComputed() {
        when(parser.parse("politica.md", CONTENT)).thenReturn(Mono.just(PARSED));
        when(knowledgeBase.replaceDocument(PARSED)).thenReturn(Mono.just(STORED));

        useCase.ingest(new IngestDocumentCommand("politica.md", CONTENT)).block();

        verify(events).publishEvent(new DocumentIngestedEvent(STORED.documentId(), "politica.md"));
    }

    @Test
    void announcesNothingWhenTheDocumentWasNotStored() {
        when(parser.parse("politica.md", CONTENT)).thenReturn(Mono.just(PARSED));
        when(knowledgeBase.replaceDocument(PARSED)).thenReturn(Mono.error(
                ExceptionUtil.technical(ResponseCodeEnum.DATABASE_UNAVAILABLE, new IllegalStateException())));

        StepVerifier.create(useCase.ingest(new IngestDocumentCommand("politica.md", CONTENT)))
                .expectError(TechnicalException.class).verify();

        verifyNoInteractions(events);
    }

    @Test
    void nothingIsStoredWhenTheParserRejectsTheDocument() {
        when(parser.parse("politica.md", CONTENT))
                .thenReturn(Mono.error(ExceptionUtil.business(ResponseCodeEnum.DOCUMENT_INVALID)));

        StepVerifier.create(useCase.ingest(new IngestDocumentCommand("politica.md", CONTENT)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(BusinessException.class)
                        .extracting("responseCode").isEqualTo(ResponseCodeEnum.DOCUMENT_INVALID))
                .verify();

        verifyNoInteractions(knowledgeBase);
        verify(metrics).recordParse(eq(IngestOutcome.REJECTED), any(Duration.class));
    }

    @Test
    void nothingIsStoredWhenTheParsingServiceFails() {
        when(parser.parse("politica.md", CONTENT)).thenReturn(Mono.error(
                ExceptionUtil.technical(ResponseCodeEnum.INGEST_SERVICE_UNAVAILABLE, new IllegalStateException())));

        StepVerifier.create(useCase.ingest(new IngestDocumentCommand("politica.md", CONTENT)))
                .expectError(TechnicalException.class)
                .verify();

        verifyNoInteractions(knowledgeBase);
        verify(metrics).recordParse(eq(IngestOutcome.FAILED), any(Duration.class));
    }

    @Test
    void aFailureWhileStoringReachesTheCaller() {
        when(parser.parse("politica.md", CONTENT)).thenReturn(Mono.just(PARSED));
        when(knowledgeBase.replaceDocument(PARSED)).thenReturn(Mono.error(
                ExceptionUtil.technical(ResponseCodeEnum.DATABASE_UNAVAILABLE, new IllegalStateException())));

        StepVerifier.create(useCase.ingest(new IngestDocumentCommand("politica.md", CONTENT)))
                .expectErrorSatisfies(error -> assertThat(error).extracting("responseCode")
                        .isEqualTo(ResponseCodeEnum.DATABASE_UNAVAILABLE))
                .verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "carpeta/politica.md", "carpeta\\politica.md", "../politica.md"})
    void aNameThatIsEmptyOrHasAPathIsRejectedBeforeCallingTheParser(String name) {
        StepVerifier.create(useCase.ingest(new IngestDocumentCommand(name, CONTENT)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(BusinessException.class)
                        .extracting("responseCode").isEqualTo(ResponseCodeEnum.DOCUMENT_INVALID))
                .verify();

        verifyNoInteractions(parser, knowledgeBase);
    }

    @Test
    void aMissingNameOrAnEmptyContentIsRejectedBeforeCallingTheParser() {
        StepVerifier.create(useCase.ingest(new IngestDocumentCommand(null, CONTENT)))
                .expectError(BusinessException.class).verify();
        StepVerifier.create(useCase.ingest(new IngestDocumentCommand("politica.md", new byte[0])))
                .expectError(BusinessException.class).verify();
        StepVerifier.create(useCase.ingest(new IngestDocumentCommand("politica.md", null)))
                .expectError(BusinessException.class).verify();

        verifyNoInteractions(parser, knowledgeBase);
    }

    @Test
    void theParserIsNotCalledUntilSomeoneSubscribes() {
        AtomicBoolean called = new AtomicBoolean();
        when(parser.parse("politica.md", CONTENT)).thenAnswer(invocation -> {
            called.set(true);
            return Mono.just(PARSED);
        });
        when(knowledgeBase.replaceDocument(PARSED)).thenReturn(Mono.just(STORED));

        Mono<IngestedDocument> pipeline = useCase.ingest(new IngestDocumentCommand("politica.md", CONTENT));

        assertThat(called).isFalse();
        StepVerifier.create(pipeline).expectNext(STORED).verifyComplete();
        assertThat(called).isTrue();
    }
}
