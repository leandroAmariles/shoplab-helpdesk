package com.shoplab.helpdesk.retrieval.infrastructure.in.rest.util.mapper;

import com.shoplab.helpdesk.retrieval.domain.model.DocumentSummary;
import com.shoplab.helpdesk.retrieval.domain.model.IngestedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexStatus;
import com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto.DocumentSummaryResponse;
import com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto.DocumentUploadResponse;

public final class DocumentResponseMapper {

    private DocumentResponseMapper() {
    }

    public static DocumentUploadResponse toUploadResponse(IngestedDocument stored) {
        return new DocumentUploadResponse(stored.documentId(), stored.source(), stored.title(),
                IndexStatus.PENDING.getExternalValue(), stored.chunkCount(), stored.replaced());
    }

    public static DocumentSummaryResponse toSummaryResponse(DocumentSummary summary) {
        return new DocumentSummaryResponse(summary.id(), summary.name(), summary.title(),
                summary.status().getExternalValue(), summary.chunkCount(), summary.error(), summary.updatedAt(),
                summary.indexedAt());
    }
}
