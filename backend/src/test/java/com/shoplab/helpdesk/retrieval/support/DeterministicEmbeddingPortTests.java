package com.shoplab.helpdesk.retrieval.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class DeterministicEmbeddingPortTests {

    private final DeterministicEmbeddingPort port = new DeterministicEmbeddingPort("model-a");

    private static double dot(float[] left, float[] right) {
        double sum = 0;
        for (int index = 0; index < left.length; index++) {
            sum += left[index] * right[index];
        }
        return sum;
    }

    @Test
    void theSameTextAlwaysGivesTheSameUnitVectorOf1024Values() {
        float[] first = DeterministicEmbeddingPort.vectorFor("plazo de devolucion");
        float[] second = DeterministicEmbeddingPort.vectorFor("plazo de devolucion");

        assertThat(first).hasSize(1024).isEqualTo(second);
        assertThat(dot(first, first)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void differentTextsGiveVectorsThatAreFarApart() {
        float[] first = DeterministicEmbeddingPort.vectorFor("plazo de devolucion");
        float[] second = DeterministicEmbeddingPort.vectorFor("costo de envio");

        assertThat(dot(first, second)).isLessThan(0.3);
    }

    @Test
    void countsItsCallsAndTheTextsItEmbedded() {
        port.embed(List.of("a", "b", "c")).block();
        port.embed(List.of("d")).block();

        assertThat(port.batches()).isEqualTo(2);
        assertThat(port.textsEmbedded()).isEqualTo(4);
        assertThat(port.modelName()).isEqualTo("model-a");
    }

    @Test
    void failsTheRequestedNumberOfBatchesThenRecovers() {
        port.failNext(1, ResponseCodeEnum.EMBEDDING_SERVICE_FAILED);

        StepVerifier.create(port.embed(List.of("a")))
                .expectErrorSatisfies(error -> assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.EMBEDDING_SERVICE_FAILED))
                .verify();
        StepVerifier.create(port.embed(List.of("a"))).expectNextCount(1).verifyComplete();
    }

    @Test
    void failsForeverUntilItRecovers() {
        port.failAlways(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE);

        StepVerifier.create(port.embed(List.of("a"))).expectError(HelpdeskException.class).verify();
        StepVerifier.create(port.embed(List.of("a"))).expectError(HelpdeskException.class).verify();
        port.recover();
        StepVerifier.create(port.embed(List.of("a"))).expectNextCount(1).verifyComplete();
    }
}
