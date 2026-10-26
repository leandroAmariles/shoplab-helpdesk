package com.shoplab.helpdesk.blockhound;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Proves that the detection of blocking calls is really active in the test JVM (specs/LAB-111, FR-009). */
class BlockHoundDetectionTests {

    @Test
    void blockingOnANonBlockingSchedulerIsDetected() {
        Mono<Integer> blocking = Mono.fromCallable(() -> {
            Thread.sleep(10);
            return 1;
        }).subscribeOn(Schedulers.parallel());

        StepVerifier.create(blocking)
                .expectErrorSatisfies(error -> assertThat(rootOf(error)).isInstanceOf(BlockingOperationError.class))
                .verify();
    }

    @Test
    void theSameCodeOnTheElasticSchedulerIsAllowed() {
        Mono<Integer> blocking = Mono.fromCallable(() -> {
            Thread.sleep(10);
            return 1;
        }).subscribeOn(Schedulers.boundedElastic());

        StepVerifier.create(blocking).expectNext(1).verifyComplete();
    }

    private static Throwable rootOf(Throwable error) {
        Throwable current = error;
        while (!(current instanceof BlockingOperationError) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
