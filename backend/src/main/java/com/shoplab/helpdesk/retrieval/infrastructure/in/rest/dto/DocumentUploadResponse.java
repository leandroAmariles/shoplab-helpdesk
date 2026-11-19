package com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto;

import java.util.UUID;


public record DocumentUploadResponse(UUID id, String name, String title, String status, int chunkCount,
        boolean replaced) {
}
