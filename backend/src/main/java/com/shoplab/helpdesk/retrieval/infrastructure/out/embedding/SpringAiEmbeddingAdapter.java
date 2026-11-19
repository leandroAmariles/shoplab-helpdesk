package com.shoplab.helpdesk.retrieval.infrastructure.out.embedding;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingPort;
import com.shoplab.helpdesk.retrieval.domain.model.constants.RetrievalConstants;
import com.shoplab.helpdesk.retrieval.domain.model.enums.EmbeddingPurpose;
import com.shoplab.helpdesk.shared.constants.GatewayHeaders;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.out.blocking.BlockingCalls;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Mono;

/**
 * Computes embeddings through the model gateway (an OpenAI-compatible API) with the Spring AI {@link EmbeddingModel}.
 * The backend asks for the logical model {@code embeddings}; the real model is the gateway's business. The call
 * blocks, so it runs through {@link BlockingCalls}; every vector is checked against the width of the column before it
 * goes anywhere. Each call says why it is made (the {@code user} of the call) and, when there is one, which chat
 * request it belongs to, so the gateway can attribute its cost (LAB-84).
 */
@Component
public class SpringAiEmbeddingAdapter implements EmbeddingPort {

    private static final Logger LOG = LoggerFactory.getLogger(SpringAiEmbeddingAdapter.class);
    private static final String BUDGET_EXCEEDED_TYPE = "budget_exceeded";
    private static final int SERVER_ERROR = 500;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;
    private static final int TOO_MANY_REQUESTS = 429;

    private final EmbeddingModel model;
    private final String logicalModel;
    private final String modelName;

    public SpringAiEmbeddingAdapter(EmbeddingModel model,
            @Value("${spring.ai.openai.embedding.model:embeddings}") String logicalModel,
            @Value("${shoplab.retrieval.embedding.model-name:bge-m3}") String modelName) {
        this.model = model;
        this.logicalModel = logicalModel;
        this.modelName = modelName;
    }

    @Override
    public Mono<List<float[]>> embed(List<String> texts, EmbeddingPurpose purpose) {
        if (texts.isEmpty()) {
            return Mono.just(List.of());
        }
        return Mono.deferContextual(context -> {
            EmbeddingRequest request = new EmbeddingRequest(texts,
                    options(purpose, context.getOrDefault(GatewayHeaders.REQUEST_ID_CONTEXT_KEY, null)));
            return BlockingCalls.call(() -> model.call(request))
                    .map(response -> validated(vectorsOf(response), texts.size()));
        }).onErrorMap(error -> !(error instanceof HelpdeskException), SpringAiEmbeddingAdapter::translate);
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private OpenAiEmbeddingOptions options(EmbeddingPurpose purpose, String requestId) {
        OpenAiEmbeddingOptions.Builder builder = OpenAiEmbeddingOptions.builder()
                .model(logicalModel)
                .user(purpose.getUserValue());
        if (requestId != null) {
            builder.customHeaders(Map.of(GatewayHeaders.REQUEST_ID_HEADER, requestId));
        }
        return builder.build();
    }

    private static List<float[]> vectorsOf(EmbeddingResponse response) {
        return response == null ? null : response.getResults().stream().map(result -> result.getOutput()).toList();
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

    /**
     * An exhausted budget is its own error; the rest is "unavailable" when the gateway cannot be reached, is down or
     * rejects the call, and "failed" otherwise. Only the status and type of the gateway's error are logged, never its
     * text, which may quote the input.
     */
    private static HelpdeskException translate(Throwable error) {
        OpenAIServiceException service = serviceError(error);
        if (service != null) {
            LOG.warn("The model gateway rejected the embeddings request: status={}, type={}, code={}",
                    service.statusCode(), service.type().orElse(""), service.code().orElse(""));
            if (BUDGET_EXCEEDED_TYPE.equals(service.type().orElse(""))) {
                return ExceptionUtil.technical(ResponseCodeEnum.EMBEDDING_BUDGET_EXCEEDED, error);
            }
        }
        ResponseCodeEnum code = isUnavailable(error) ? ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE
                : ResponseCodeEnum.EMBEDDING_SERVICE_FAILED;
        return ExceptionUtil.technical(code, error);
    }

    private static OpenAIServiceException serviceError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof OpenAIServiceException service) {
                return service;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    private static boolean isUnavailable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException || cause instanceof IOException
                    || cause instanceof TimeoutException || cause instanceof OpenAIIoException) {
                return true;
            }
            if (cause instanceof OpenAIServiceException service && isUnavailableStatus(service.statusCode())) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static boolean isUnavailableStatus(int status) {
        return status >= SERVER_ERROR || status == UNAUTHORIZED || status == FORBIDDEN || status == NOT_FOUND
                || status == TOO_MANY_REQUESTS;
    }
}
