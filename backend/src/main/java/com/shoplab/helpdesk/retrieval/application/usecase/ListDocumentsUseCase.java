package com.shoplab.helpdesk.retrieval.application.usecase;

import com.shoplab.helpdesk.retrieval.application.port.in.ListDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.application.port.out.DocumentCatalogPort;
import com.shoplab.helpdesk.retrieval.application.port.out.UploadMetricsPort;
import com.shoplab.helpdesk.retrieval.domain.model.DocumentSummary;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexStatus;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** Lists the knowledge base as {@code retrieval} stores it; the state is never recomputed here (FR-006). */
@Service
public class ListDocumentsUseCase implements ListDocumentsServicePort {

    private final DocumentCatalogPort catalog;
    private final UploadMetricsPort metrics;

    public ListDocumentsUseCase(DocumentCatalogPort catalog, UploadMetricsPort metrics) {
        this.catalog = catalog;
        this.metrics = metrics;
    }

    @Override
    public Flux<DocumentSummary> list() {
        return catalog.listDocuments()
                .collectList()
                .doOnNext(documents -> metrics.recordDocumentsByStatus(countByStatus(documents)))
                .flatMapMany(Flux::fromIterable);
    }

    private static Map<IndexStatus, Long> countByStatus(List<DocumentSummary> documents) {
        Map<IndexStatus, Long> counts = new EnumMap<>(IndexStatus.class);
        for (DocumentSummary document : documents) {
            counts.merge(document.status(), 1L, Long::sum);
        }
        return counts;
    }
}
