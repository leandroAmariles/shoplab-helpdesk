package com.shoplab.helpdesk.retrieval.application.port.out;

import com.shoplab.helpdesk.retrieval.domain.model.ChunkEmbedding;
import com.shoplab.helpdesk.retrieval.domain.model.PendingDocument;
import com.shoplab.helpdesk.retrieval.domain.model.SearchResult;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexScope;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface EmbeddingStorePort {

    /** The documents of the scope with their chunks, one document at a time and in document order. */
    Flux<PendingDocument> findDocuments(IndexScope scope, String model);

    /**
     * Stores the vectors of all the chunks of the document with the model's name and marks it indexed, all or
     * nothing. Emits false, and stores nothing, when the document changed since it was read.
     */
    Mono<Boolean> saveEmbeddings(PendingDocument document, List<ChunkEmbedding> vectors, String model);

    /** Marks the document as failed with the reason, if it did not change since it was read. Vectors are untouched. */
    Mono<Void> markFailed(PendingDocument document, String reason);

    /** The closest chunks that have a vector from {@code model}, closest first. */
    Mono<List<SearchResult>> search(float[] query, String model, int limit);

    /** Whether some chunk has a vector from a model other than {@code model} (a model change not reindexed yet). */
    Mono<Boolean> hasChunksFromOtherModels(String model);
}
