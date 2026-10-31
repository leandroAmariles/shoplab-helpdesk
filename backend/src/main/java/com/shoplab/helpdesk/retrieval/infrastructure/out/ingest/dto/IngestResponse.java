package com.shoplab.helpdesk.retrieval.infrastructure.out.ingest.dto;

import java.util.List;

/** Answer of {@code POST /ingest} of the ingest service; the names follow {@code ingest/openapi.json}. */
public record IngestResponse(DocumentInfo document, List<Chunk> chunks) {

    public record DocumentInfo(String name, String title, String format) {
    }

    public record Chunk(String text, ChunkMetadata metadata) {
    }

    public record ChunkMetadata(String document, String section, int position) {
    }
}
