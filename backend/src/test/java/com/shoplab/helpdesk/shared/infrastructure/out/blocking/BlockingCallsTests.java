package com.shoplab.helpdesk.shared.infrastructure.out.blocking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class BlockingCallsTests {

    private static final String ELASTIC_PREFIX = "boundedElastic-";

    @Test
    void callRunsTheBlockingCodeOnTheElasticSchedulerNotOnTheCallingThread() {
        AtomicReference<String> thread = new AtomicReference<>();

        StepVerifier.create(BlockingCalls.call(() -> {
            thread.set(Thread.currentThread().getName());
            return "result";
        })).expectNext("result").verifyComplete();

        assertThat(thread.get()).startsWith(ELASTIC_PREFIX);
        assertThat(thread.get()).isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    void callIsLazyAndOnlyRunsWhenSubscribed() {
        AtomicBoolean ran = new AtomicBoolean();

        Mono<String> call = BlockingCalls.call(() -> {
            ran.set(true);
            return "x";
        });

        assertThat(ran).isFalse();
        StepVerifier.create(call).expectNext("x").verifyComplete();
        assertThat(ran).isTrue();
    }

    @Test
    void callSignalsTheExceptionOfTheBlockingCodeAsAnError() {
        StepVerifier.create(BlockingCalls.call(() -> {
            throw new IllegalStateException("db down");
        })).expectErrorMessage("db down").verify();
    }

    @Test
    void callMapsANullResultToAnEmptyMono() {
        StepVerifier.create(BlockingCalls.call(() -> null)).verifyComplete();
    }

    @Test
    void runCompletesWithoutAnElementAfterRunningOnTheElasticScheduler() {
        AtomicReference<String> thread = new AtomicReference<>();

        StepVerifier.create(BlockingCalls.run(() -> thread.set(Thread.currentThread().getName())))
                .verifyComplete();

        assertThat(thread.get()).startsWith(ELASTIC_PREFIX);
    }

    @Test
    void streamEmitsTheElementsOfTheBlockingQueryInOrder() {
        StepVerifier.create(BlockingCalls.stream(() -> List.of("a", "b", "c")))
                .expectNext("a", "b", "c")
                .verifyComplete();
    }

    @Test
    void blockingInsideCallIsNotReportedByTheDetector() {
        StepVerifier.create(BlockingCalls.call(() -> {
            Thread.sleep(10);
            return 1;
        })).expectNext(1).verifyComplete();
    }

    @Test
    void theReactorContextStaysVisibleDownstream() {
        StepVerifier.create(BlockingCalls.call(() -> "v")
                        .flatMap(value -> Mono.deferContextual(context -> Mono.just(context.get("requestId") + value)))
                        .contextWrite(context -> context.put("requestId", "req-1")))
                .expectNext("req-1v")
                .verifyComplete();
    }

    @Test
    void theSamePipelineBlockingOnTheParallelSchedulerWouldFail() {
        StepVerifier.create(Mono.fromCallable(() -> {
            Thread.sleep(10);
            return 1;
        }).subscribeOn(Schedulers.parallel())).expectError().verify();
    }
}
