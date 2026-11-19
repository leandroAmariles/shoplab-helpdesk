package com.shoplab.helpdesk.retrieval.application.usecase;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.retrieval.application.port.out.DocumentCatalogPort;
import com.shoplab.helpdesk.retrieval.application.port.out.UploadMetricsPort;
import com.shoplab.helpdesk.retrieval.domain.model.DocumentSummary;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexStatus;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.exception.TechnicalException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ListDocumentsUseCaseTests {

    private final DocumentCatalogPort catalog = mock(DocumentCatalogPort.class);
    private final UploadMetricsPort metrics = mock(UploadMetricsPort.class);
    private final ListDocumentsUseCase useCase = new ListDocumentsUseCase(catalog, metrics);

    private static DocumentSummary document(String name, IndexStatus status) {
        return new DocumentSummary(UUID.nameUUIDFromBytes(name.getBytes()), name, name, status, 3,
                status == IndexStatus.ERROR ? "The embedding model failed" : null, Instant.EPOCH,
                status == IndexStatus.INDEXED ? Instant.EPOCH : null);
    }

    @Test
    void returnsTheDocumentsInTheOrderOfTheCatalog() {
        DocumentSummary first = document("b.md", IndexStatus.PENDING);
        DocumentSummary second = document("a.md", IndexStatus.INDEXED);
        when(catalog.listDocuments()).thenReturn(Flux.just(first, second));

        StepVerifier.create(useCase.list()).expectNext(first, second).verifyComplete();
    }

    @Test
    void publishesHowManyDocumentsAreInEachState() {
        when(catalog.listDocuments()).thenReturn(Flux.just(document("a.md", IndexStatus.PENDING),
                document("b.md", IndexStatus.INDEXED), document("c.md", IndexStatus.INDEXED)));

        StepVerifier.create(useCase.list()).expectNextCount(3).verifyComplete();

        verify(metrics).recordDocumentsByStatus(Map.of(IndexStatus.PENDING, 1L, IndexStatus.INDEXED, 2L));
    }

    @Test
    void anEmptyKnowledgeBaseIsAnEmptyListWithNoDocumentsInAnyState() {
        when(catalog.listDocuments()).thenReturn(Flux.empty());

        StepVerifier.create(useCase.list()).verifyComplete();

        verify(metrics).recordDocumentsByStatus(Map.of());
    }

    @Test
    void aFailureOfTheCatalogReachesTheCallerAndPublishesNothing() {
        when(catalog.listDocuments()).thenReturn(Flux.error(
                ExceptionUtil.technical(ResponseCodeEnum.DATABASE_UNAVAILABLE, new IllegalStateException())));

        StepVerifier.create(useCase.list()).expectError(TechnicalException.class).verify();

        verify(metrics, never()).recordDocumentsByStatus(anyMap());
    }
}
