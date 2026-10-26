package com.shoplab.helpdesk.shared.infrastructure.out.blocking;

import java.util.concurrent.Callable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The one place that moves blocking code (JDBC and the like) off the event loop: it runs on the elastic scheduler
 * and hands the result back as a {@link Mono} or {@link Flux}. Adapters in {@code infrastructure} use it; the domain,
 * the use cases and the controllers never see a scheduler. See {@code docs/adr/0011-web-reactiva-persistencia-jdbc.md}.
 */
public final class BlockingCalls {

    private BlockingCalls() {
    }

    /** Runs {@code blocking} when subscribed; a {@code null} result completes empty. */
    public static <T> Mono<T> call(Callable<? extends T> blocking) {
        return Mono.<T>fromCallable(blocking).subscribeOn(Schedulers.boundedElastic());
    }

    /** Runs {@code blocking} when subscribed and completes without an element. */
    public static Mono<Void> run(Runnable blocking) {
        return Mono.<Void>fromRunnable(blocking).subscribeOn(Schedulers.boundedElastic());
    }

    /** Runs a blocking query when subscribed and emits the elements it returns, in order. */
    public static <T> Flux<T> stream(Callable<? extends Iterable<T>> blockingQuery) {
        return call(blockingQuery).flatMapMany(Flux::fromIterable);
    }
}
