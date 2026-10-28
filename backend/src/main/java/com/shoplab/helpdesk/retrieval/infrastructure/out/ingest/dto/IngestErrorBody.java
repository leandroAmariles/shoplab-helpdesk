package com.shoplab.helpdesk.retrieval.infrastructure.out.ingest.dto;

/** Error answer of the ingest service: a stable code and a message (the {@code ErrorResponse} of the contract). */
public record IngestErrorBody(String code, String message) {
}
