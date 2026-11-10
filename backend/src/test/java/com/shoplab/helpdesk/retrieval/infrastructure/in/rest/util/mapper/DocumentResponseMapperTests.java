package com.shoplab.helpdesk.retrieval.infrastructure.in.rest.util.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.retrieval.domain.model.DocumentSummary;
import com.shoplab.helpdesk.retrieval.domain.model.IngestedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexStatus;
import com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto.DocumentSummaryResponse;
import com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto.DocumentUploadResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentResponseMapperTests {

    private static final UUID ID = UUID.fromString("6f1c1c1e-0d6b-4e57-9a6d-3b9a7f0f4a11");

    @Test
    void anUploadedDocumentIsAlwaysPending() {
        DocumentUploadResponse response = DocumentResponseMapper.toUploadResponse(
                new IngestedDocument(ID, "politica.md", "Politica", 14, true));

        assertThat(response).isEqualTo(new DocumentUploadResponse(ID, "politica.md", "Politica", "PENDING", 14, true));
    }

    @Test
    void mapsEveryFieldOfTheSummary() {
        Instant updated = Instant.parse("2026-11-10T14:40:00.100Z");
        DocumentSummaryResponse response = DocumentResponseMapper.toSummaryResponse(new DocumentSummary(ID,
                "envios.pdf", "Envios", IndexStatus.ERROR, 9, "The embedding model failed", updated, null));

        assertThat(response).isEqualTo(new DocumentSummaryResponse(ID, "envios.pdf", "Envios", "ERROR", 9,
                "The embedding model failed", updated, null));
    }
}
