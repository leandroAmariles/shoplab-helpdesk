package com.shoplab.helpdesk.retrieval.application.usecase;

import com.shoplab.helpdesk.retrieval.domain.model.PendingDocument;

/** Shared builders of the use case tests. */
final class IndexTestData {

    private IndexTestData() {
    }

    static PendingDocument document(String source, int chunks) {
        return IndexDocumentsUseCaseTests.document(source, chunks);
    }
}
