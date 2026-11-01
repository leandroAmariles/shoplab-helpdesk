package com.shoplab.helpdesk.retrieval.application.usecase;

import com.shoplab.helpdesk.retrieval.application.port.in.IndexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.domain.model.IndexingSummary;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexScope;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class IndexDocumentsUseCase implements IndexDocumentsServicePort {

    private final DocumentEmbedder embedder;

    public IndexDocumentsUseCase(DocumentEmbedder embedder) {
        this.embedder = embedder;
    }

    @Override
    public Mono<IndexingSummary> indexPending(boolean retryFailed) {
        return embedder.run(retryFailed ? IndexScope.RETRY : IndexScope.PENDING);
    }
}
