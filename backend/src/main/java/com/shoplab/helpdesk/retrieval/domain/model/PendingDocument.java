package com.shoplab.helpdesk.retrieval.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A document whose chunks need a vector.
 *
 * @param documentId identifier of the document
 * @param source     file name that identifies the document
 * @param version    {@code updated_at} when the chunks were read; vectors are stored only while it is unchanged
 * @param chunks     the chunks to compute, in document order
 */
public record PendingDocument(UUID documentId, String source, Instant version, List<ChunkToEmbed> chunks) {

    public PendingDocument {
        chunks = List.copyOf(chunks);
    }
}
