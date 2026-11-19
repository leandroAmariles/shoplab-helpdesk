package com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto;

import java.time.Instant;
import java.util.UUID;

public record DocumentSummaryResponse(UUID id, String name, String title, String status, int chunkCount,
        String error, Instant updatedAt, Instant indexedAt) {
}
