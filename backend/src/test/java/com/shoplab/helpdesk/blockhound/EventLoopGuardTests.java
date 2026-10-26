package com.shoplab.helpdesk.blockhound;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The running server is guarded too, not just isolated reactor chains: a handler that blocks on a Netty thread
 * fails the request, and the same handler isolated on the elastic scheduler works (specs/LAB-111, FR-009).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EventLoopGuardTests.BlockingProbeController.class)
class EventLoopGuardTests {

    @LocalServerPort
    private int port;

    @Test
    void aHandlerThatBlocksTheEventLoopFails() {
        webTestClient().get().uri("/probe/blocking-on-event-loop").exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    void theSameHandlerIsFineWhenTheBlockingCallIsIsolated() {
        webTestClient().get().uri("/probe/blocking-isolated").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).isEqualTo("done"));
    }

    private WebTestClient webTestClient() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /** Test-only routes: one blocks on the thread that serves the request, the other isolates the block. */
    @RestController
    static class BlockingProbeController {

        @GetMapping("/probe/blocking-on-event-loop")
        Mono<String> blockingOnEventLoop() {
            return Mono.fromCallable(() -> {
                Thread.sleep(10);
                return "done";
            });
        }

        @GetMapping("/probe/blocking-isolated")
        Mono<String> blockingIsolated() {
            return Mono.fromCallable(() -> {
                Thread.sleep(10);
                return "done";
            }).subscribeOn(Schedulers.boundedElastic());
        }
    }
}
