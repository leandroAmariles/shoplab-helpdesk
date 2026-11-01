package com.shoplab.helpdesk.retrieval.application.usecase;

import com.shoplab.helpdesk.retrieval.application.port.in.ReindexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.domain.model.IndexingSummary;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexScope;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ReindexDocumentsUseCase implements ReindexDocumentsServicePort {

    private final DocumentEmbedder embedder;

    public ReindexDocumentsUseCase(DocumentEmbedder embedder) {
        this.embedder = embedder;
    }

    @Override
    public Mono<IndexingSummary> reindex(boolean force) {
        return embedder.run(force ? IndexScope.ALL : IndexScope.OTHER_MODEL);
    }
}
