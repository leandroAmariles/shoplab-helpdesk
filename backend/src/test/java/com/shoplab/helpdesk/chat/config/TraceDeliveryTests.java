package com.shoplab.helpdesk.chat.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;

/**
 * Whatever Langfuse does, the turns end normally and the pending traces leave when the application stops
 * (specs/LAB-64, US4, FR-021 to FR-023). The turns run straight through {@link ChatTracePort}; the whole chat is
 * covered by {@code TraceResilienceIntegrationTests}.
 */
@ExtendWith(OutputCaptureExtension.class)
class TraceDeliveryTests {

    private static final String TRACES_URL = "/api/public/otel/v1/traces";

    @RegisterExtension
    static WireMockExtension langfuse = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    private final MeterRegistry meters = new SimpleMeterRegistry();

    private ApplicationContextRunner runner(String endpoint, String... more) {
        String[] base = {"shoplab.chat.tracing.enabled=true", "shoplab.chat.tracing.endpoint=" + endpoint,
                "shoplab.chat.tracing.public-key=pk-lf-test", "shoplab.chat.tracing.secret-key=sk-lf-test",
                "shoplab.chat.tracing.export-timeout=500ms"};
        String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return new ApplicationContextRunner().withUserConfiguration(TracingConfig.class)
                .withBean(MeterRegistry.class, () -> meters).withPropertyValues(all);
    }

    private static Flux<ChatEvent> turn(ChatTracePort port, int number) {
        return port.traceTurn(new StreamChatCommand("question " + number, "req-" + number, null), trace -> {
            var generation = trace.startGeneration("model", Map.of(), List.of());
            generation.firstToken();
            generation.complete(new TokenUsage(3, 2), "answer");
            return Flux.just(new TokenEvent("answer"), new DoneEvent(1));
        });
    }

    private static void turnsEndNormally(AssertableApplicationContext context, int turns) {
        ChatTracePort port = context.getBean(ChatTracePort.class);
        IntStream.range(0, turns).forEach(number -> {
            List<ChatEvent> events = turn(port, number).collectList().block(Duration.ofSeconds(5));
            assertThat(events).hasSize(2);
        });
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void anUnreachableLangfuseDoesNotChangeTheTurnsAndLeavesOneFixedWarning(CapturedOutput output)
            throws IOException {
        int port = closedPort();

        runner("http://localhost:" + port + "/api/public/otel", "shoplab.chat.tracing.schedule-delay=50ms")
                .run(context -> {
                    turnsEndNormally(context, 5);

                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(output.getAll())
                            .contains("could not be delivered to Langfuse"));
                    assertThat(meters.counter("shoplab.chat.trace.export.failures").count()).isPositive();
                });

        assertThat(output.getAll()).doesNotContain("sk-lf-test", "question 0");
    }

    @Test
    void aFullQueueDropsTracesCountsThemAndTheTurnsKeepGoing() {
        langfuse.resetAll();
        langfuse.stubFor(post(urlEqualTo(TRACES_URL)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(30_000)));

        runner(langfuse.baseUrl() + "/api/public/otel", "shoplab.chat.tracing.queue-size=2",
                "shoplab.chat.tracing.batch-size=1", "shoplab.chat.tracing.schedule-delay=10ms").run(context -> {
                    turnsEndNormally(context, 40);

                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                            assertThat(meters.counter("shoplab.chat.trace.dropped").count()).isPositive());
                });
    }

    @Test
    void whatIsPendingIsSentWhenTheApplicationStops() {
        langfuse.resetAll();
        langfuse.stubFor(post(urlEqualTo(TRACES_URL)).willReturn(aResponse().withStatus(200)));
        AtomicReference<Long> before = new AtomicReference<>();

        runner(langfuse.baseUrl() + "/api/public/otel", "shoplab.chat.tracing.schedule-delay=60s",
                "shoplab.chat.tracing.shutdown-timeout=5s").run(context -> {
                    turnsEndNormally(context, 2);
                    before.set((long) langfuse.findAll(postRequestedFor(urlEqualTo(TRACES_URL))).size());
                    context.close();
                });

        assertThat(before.get()).isZero();
        langfuse.verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo(TRACES_URL)));
    }
}
