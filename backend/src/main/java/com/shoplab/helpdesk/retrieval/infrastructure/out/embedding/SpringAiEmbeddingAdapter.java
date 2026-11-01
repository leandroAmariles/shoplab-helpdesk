package com.shoplab.helpdesk.retrieval.infrastructure.out.embedding;

import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingPort;
import com.shoplab.helpdesk.retrieval.domain.model.constants.RetrievalConstants;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.out.blocking.BlockingCalls;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Mono;

/**
 * Computes embeddings with the Spring AI {@link EmbeddingModel} (Ollama). The call blocks, so it runs through
 * {@link BlockingCalls}; every vector is checked against the width of the column before it goes anywhere.
 */
@Component
public class SpringAiEmbeddingAdapter implements EmbeddingPort {

    private final EmbeddingModel model;
    private final String modelName;

    public SpringAiEmbeddingAdapter(EmbeddingModel model,
            @Value("${spring.ai.ollama.embedding.options.model:bge-m3}") String modelName) {
        this.model = model;
        this.modelName = modelName;
    }

    @Override
    public Mono<List<float[]>> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return Mono.just(List.of());
        }
        return BlockingCalls.call(() -> model.embed(texts))
                .map(vectors -> validated(vectors, texts.size()))
                .onErrorMap(error -> !(error instanceof HelpdeskException), SpringAiEmbeddingAdapter::translate);
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private static List<float[]> validated(List<float[]> vectors, int expected) {
        if (vectors == null || vectors.size() != expected) {
            throw ExceptionUtil.technical(ResponseCodeEnum.EMBEDDING_SERVICE_FAILED, new IllegalStateException(
                    "The model returned " + (vectors == null ? 0 : vectors.size()) + " vectors for " + expected
                            + " texts"));
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length != RetrievalConstants.EMBEDDING_DIMENSIONS) {
                throw ExceptionUtil.technical(ResponseCodeEnum.EMBEDDING_DIMENSION_MISMATCH,
                        new IllegalStateException("Expected " + RetrievalConstants.EMBEDDING_DIMENSIONS
                                + " values but the model returned " + (vector == null ? 0 : vector.length)));
            }
        }
        return vectors;
    }

    private static HelpdeskException translate(Throwable error) {
        ResponseCodeEnum code = isTransportFailure(error) ? ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE
                : ResponseCodeEnum.EMBEDDING_SERVICE_FAILED;
        return ExceptionUtil.technical(code, error);
    }

    private static boolean isTransportFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException || cause instanceof IOException
                    || cause instanceof TimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
