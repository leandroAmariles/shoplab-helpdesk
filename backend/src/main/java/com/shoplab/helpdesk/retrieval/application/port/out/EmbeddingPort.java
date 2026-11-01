package com.shoplab.helpdesk.retrieval.application.port.out;

import java.util.List;
import reactor.core.publisher.Mono;

public interface EmbeddingPort {

    /** One vector per text, in the same order. A failure is a typed {@code HelpdeskException}. */
    Mono<List<float[]>> embed(List<String> texts);

    /** Name of the model that computes the vectors; it is stored with each chunk. */
    String modelName();
}
