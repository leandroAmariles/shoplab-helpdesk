package com.shoplab.helpdesk.retrieval.domain.model;

import java.util.UUID;

/** The vector computed for a chunk. */
public record ChunkEmbedding(UUID chunkId, float[] vector) {
}
