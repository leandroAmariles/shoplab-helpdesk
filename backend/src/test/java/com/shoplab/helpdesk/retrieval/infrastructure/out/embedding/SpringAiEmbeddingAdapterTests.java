package com.shoplab.helpdesk.retrieval.infrastructure.out.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.web.client.ResourceAccessException;
import reactor.test.StepVerifier;

class SpringAiEmbeddingAdapterTests {

    private static final List<String> TEXTS = List.of("uno", "dos");

    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final SpringAiEmbeddingAdapter adapter = new SpringAiEmbeddingAdapter(model, "bge-m3");

    private static float[] vector(int size) {
        return new float[size];
    }

    private void failsWith(ResponseCodeEnum expected, Throwable cause) {
        when(model.embed(TEXTS)).thenThrow(cause instanceof RuntimeException runtime ? runtime
                : new IllegalStateException(cause));
        StepVerifier.create(adapter.embed(TEXTS))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(HelpdeskException.class);
                    assertThat(((HelpdeskException) error).getResponseCode()).isEqualTo(expected);
                })
                .verify();
    }

    @Test
    void returnsOneVectorPerTextInTheSameOrder() {
        float[] first = vector(1024);
        float[] second = vector(1024);
        first[0] = 1;
        second[1] = 1;
        when(model.embed(TEXTS)).thenReturn(List.of(first, second));

        StepVerifier.create(adapter.embed(TEXTS))
                .assertNext(vectors -> assertThat(vectors).containsExactly(first, second))
                .verifyComplete();
    }

    @Test
    void knowsTheNameOfTheConfiguredModel() {
        assertThat(adapter.modelName()).isEqualTo("bge-m3");
    }

    @Test
    void withoutTextsItDoesNotCallTheModel() {
        StepVerifier.create(adapter.embed(List.of())).expectNext(List.of()).verifyComplete();

        verifyNoInteractions(model);
    }

    @Test
    void aVectorOfAnotherSizeIsRejectedWithTheSizeMismatchError() {
        when(model.embed(TEXTS)).thenReturn(List.of(vector(1024), vector(768)));

        StepVerifier.create(adapter.embed(TEXTS))
                .expectErrorSatisfies(error -> assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.EMBEDDING_DIMENSION_MISMATCH))
                .verify();
    }

    @Test
    void aDifferentNumberOfVectorsThanTextsIsAFailureOfTheModel() {
        when(model.embed(TEXTS)).thenReturn(List.of(vector(1024)));

        StepVerifier.create(adapter.embed(TEXTS))
                .expectErrorSatisfies(error -> assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.EMBEDDING_SERVICE_FAILED))
                .verify();
    }

    @Test
    void aConnectionProblemMeansTheModelIsNotAvailable() {
        failsWith(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE,
                new ResourceAccessException("I/O error", new IOException("refused")));
    }

    @Test
    void aTimeoutHiddenInsideAnotherExceptionMeansTheModelIsNotAvailable() {
        failsWith(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE,
                new IllegalStateException("wrapped", new TimeoutException("slow")));
    }

    @Test
    void anyOtherErrorOfTheModelIsAFailure() {
        failsWith(ResponseCodeEnum.EMBEDDING_SERVICE_FAILED, new IllegalArgumentException("input too long"));
    }
}
