package com.shoplab.helpdesk.shared.infrastructure.in.rest.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.shared.config.ReactorContextConfig;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** The correlation id must reach the MDC of every thread a reactive chain hops to (specs/LAB-111, FR-013). */
class RequestIdPropagationTests {

    @BeforeAll
    static void enablePropagation() {
        Hooks.enableAutomaticContextPropagation();
        new ReactorContextConfig().registerMdcAccessor();
    }

    @AfterAll
    static void disablePropagation() {
        Hooks.disableAutomaticContextPropagation();
    }

    @Test
    void theMdcHoldsTheRequestIdOnAnotherThread() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/probe").header(RequestIdFilter.HEADER, "mdc-req-7"));
        AtomicReference<String> mdcOnOtherThread = new AtomicReference<>();
        AtomicReference<String> otherThread = new AtomicReference<>();
        WebFilterChain chain = e -> Mono.just("value")
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(value -> {
                    mdcOnOtherThread.set(MDC.get(RequestIdFilter.MDC_KEY));
                    otherThread.set(Thread.currentThread().getName());
                })
                .then();

        StepVerifier.create(new RequestIdFilter().filter(exchange, chain)).verifyComplete();

        assertThat(otherThread.get()).startsWith("boundedElastic-");
        assertThat(mdcOnOtherThread.get()).isEqualTo("mdc-req-7");
    }
}
