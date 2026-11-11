package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.retrieval.support.DeterministicEmbeddingPort;
import com.shoplab.helpdesk.support.PostgresTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With the real OTLP exporter pointed at a stand-in for Langfuse, nothing Langfuse does reaches the chat: the answer
 * is whole, on time and free of errors, and the warnings carry no conversation or key (specs/LAB-64, US4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresTest
@Import(TraceResilienceIntegrationTests.EmbeddingDouble.class)
@ExtendWith(OutputCaptureExtension.class)
class TraceResilienceIntegrationTests {

    private static final String MODEL = "resilience-model";
    private static final String TRACES_URL = "/api/public/otel/v1/traces";
    private static final String PUBLIC_KEY = "pk-lf-resilience";
    private static final String SECRET_KEY = "sk-lf-resilience-secret";
    private static final String QUESTION = "Pregunta secreta sobre plazos de devolucion";
    private static final String ANSWER = "Respuesta confidencial del modelo.";

    @TestConfiguration
    static class EmbeddingDouble {

        @Bean
        @Primary
        DeterministicEmbeddingPort resilienceEmbeddingPort() {
            return new DeterministicEmbeddingPort(MODEL);
        }
    }

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @RegisterExtension
    static WireMockExtension langfuse = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
        registry.add("shoplab.chat.rag.no-context-mode", () -> "model");
        registry.add("shoplab.chat.tracing.enabled", () -> "true");
        registry.add("shoplab.chat.tracing.endpoint", () -> langfuse.baseUrl() + "/api/public/otel");
        registry.add("shoplab.chat.tracing.public-key", () -> PUBLIC_KEY);
        registry.add("shoplab.chat.tracing.secret-key", () -> SECRET_KEY);
        registry.add("shoplab.chat.tracing.schedule-delay", () -> "100ms");
        registry.add("shoplab.chat.tracing.export-timeout", () -> "500ms");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DeterministicEmbeddingPort embedding;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void prepare() {
        ollama.resetAll();
        langfuse.resetAll();
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.bodyWithUsage(5, 3, ANSWER)));
        embedding.recover();
        embedding.useModel(MODEL);
        embedding.resetCounters();
    }

    private ChatTestClient.Result ask() {
        return new ChatTestClient(port).post("{\"message\":\"" + QUESTION + "\"}", "text/event-stream", null);
    }

    private static void langfuseAnswers(int status) {
        langfuse.stubFor(post(urlEqualTo(TRACES_URL)).willReturn(aResponse().withStatus(status)));
    }

    private static void assertWholeAnswer(ChatTestClient.Result result) {
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.text()).isEqualTo(ANSWER);
    }

    @Test
    void theRequestToLangfuseCarriesTheBasicCredentialsAndTheIngestionVersion() {
        langfuseAnswers(200);

        assertWholeAnswer(ask());

        String credentials = Base64.getEncoder().encodeToString((PUBLIC_KEY + ":" + SECRET_KEY)
                .getBytes(StandardCharsets.UTF_8));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> langfuse.verify(moreThanOrExactly(1),
                postRequestedFor(urlEqualTo(TRACES_URL))
                        .withHeader("Authorization", equalTo("Basic " + credentials))
                        .withHeader("x-langfuse-ingestion-version", equalTo("4"))));
    }

    @Test
    void aLangfuseSlowerThanTheExportTimeoutDoesNotSlowDownTheAnswer() {
        langfuse.stubFor(post(urlEqualTo(TRACES_URL)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(5_000)));
        assertWholeAnswer(ask());

        long start = System.nanoTime();
        for (int turn = 0; turn < 3; turn++) {
            assertWholeAnswer(ask());
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMillis).isLessThan(4_000);
    }

    @Test
    void rejectedCredentialsAreCountedAndTheAnswerIsWholeWithoutSecretsInTheLog(CapturedOutput output) {
        double before = meters.counter("shoplab.chat.trace.export.failures").count();
        langfuseAnswers(401);

        assertWholeAnswer(ask());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(meters.counter("shoplab.chat.trace.export.failures").count()).isGreaterThan(before));
        assertThat(output.getAll()).doesNotContain(SECRET_KEY, QUESTION, ANSWER);
    }

    @Test
    void whenLangfuseComesBackTheNextTracesArriveWithoutRestarting() {
        langfuseAnswers(503);
        assertWholeAnswer(ask());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> langfuse.verify(moreThanOrExactly(1),
                postRequestedFor(urlEqualTo(TRACES_URL))));
        int failed = langfuse.findAll(postRequestedFor(urlEqualTo(TRACES_URL))).size();

        langfuse.resetMappings();
        langfuseAnswers(200);
        assertWholeAnswer(ask());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                langfuse.findAll(postRequestedFor(urlEqualTo(TRACES_URL))
                        .withHeader("Authorization", matching("Basic .+"))).size()).isGreaterThan(failed));
    }
}
