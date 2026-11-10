package com.shoplab.helpdesk.retrieval.domain.model;

import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexStatus;
import java.time.Instant;
import java.util.UUID;

public record DocumentSummary(UUID id, String name, String title, IndexStatus status, int chunkCount, String error,
        Instant updatedAt, Instant indexedAt) {
}
