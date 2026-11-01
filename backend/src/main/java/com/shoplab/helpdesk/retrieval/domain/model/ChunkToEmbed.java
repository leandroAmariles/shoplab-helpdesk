package com.shoplab.helpdesk.retrieval.domain.model;

import java.util.UUID;

/** A stored chunk whose vector has to be computed; the text only lives in memory while it is computed. */
public record ChunkToEmbed(UUID chunkId, int position, String text) {
}
