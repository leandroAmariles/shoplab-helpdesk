package com.shoplab.helpdesk.retrieval.application.port.out;

import com.shoplab.helpdesk.retrieval.domain.model.enums.EmbeddingPurpose;
import java.util.List;
import reactor.core.publisher.Mono;

public interface EmbeddingPort {

    /**
     * One vector per text, in the same order. {@code purpose} says why they are computed (indexing or searching), so
     * the model gateway can attribute the cost. A failure is a typed {@code HelpdeskException}.
     */
    Mono<List<float[]>> embed(List<String> texts, EmbeddingPurpose purpose);

    /** Name of the model that computes the vectors; it is stored with each chunk. */
    String modelName();
}
