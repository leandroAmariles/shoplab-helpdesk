package com.shoplab.helpdesk.retrieval.support;

import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingPort;
import com.shoplab.helpdesk.retrieval.domain.model.constants.RetrievalConstants;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

/**
 * Stands in for the embedding model in CI (specs/LAB-21, Constitution IV): the same text always gives the same unit
 * vector of 1024 values, different texts give vectors that are practically orthogonal, so a search finds exactly the
 * text it asks for. It counts its calls and can be told to fail.
 */
public class DeterministicEmbeddingPort implements EmbeddingPort {

    private volatile String modelName;
    private final AtomicInteger batches = new AtomicInteger();
    private final AtomicInteger texts = new AtomicInteger();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private volatile ResponseCodeEnum failureCode = ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE;
    private volatile Runnable beforeEmbedding;

    public DeterministicEmbeddingPort(String modelName) {
        this.modelName = modelName;
    }

    @Override
    public Mono<List<float[]>> embed(List<String> input) {
        return Mono.defer(() -> {
            batches.incrementAndGet();
            Runnable hook = beforeEmbedding;
            if (hook != null) {
                hook.run();
            }
            if (failuresLeft.getAndUpdate(left -> left > 0 ? left - 1 : left) > 0) {
                return Mono.error(ExceptionUtil.technical(failureCode, new IllegalStateException("simulated")));
            }
            texts.addAndGet(input.size());
            return Mono.just(input.stream().map(DeterministicEmbeddingPort::vectorFor).toList());
        });
    }

    @Override
    public String modelName() {
        return modelName;
    }

    public void useModel(String name) {
        this.modelName = name;
    }

    /** The next {@code calls} batches fail with the given code. */
    public void failNext(int calls, ResponseCodeEnum code) {
        this.failureCode = code;
        this.failuresLeft.set(calls);
    }

    public void failAlways(ResponseCodeEnum code) {
        failNext(Integer.MAX_VALUE, code);
    }

    public void recover() {
        failuresLeft.set(0);
        beforeEmbedding = null;
    }

    public int batches() {
        return batches.get();
    }

    public int textsEmbedded() {
        return texts.get();
    }

    public void resetCounters() {
        batches.set(0);
        texts.set(0);
    }

    /** Runs {@code action} at the start of every batch, to make something happen "meanwhile" in a test. */
    public void beforeEachBatch(Runnable action) {
        this.beforeEmbedding = action;
    }

    public static float[] vectorFor(String text) {
        Random random = new Random(text.hashCode());
        float[] vector = new float[RetrievalConstants.EMBEDDING_DIMENSIONS];
        double norm = 0;
        for (int index = 0; index < vector.length; index++) {
            vector[index] = (float) random.nextGaussian();
            norm += vector[index] * vector[index];
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int index = 0; index < vector.length; index++) {
            vector[index] *= scale;
        }
        return vector;
    }
}
