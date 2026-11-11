package com.shoplab.helpdesk.blockhound;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.ChatTestClient;
import com.shoplab.helpdesk.chat.OllamaStubs;
import com.shoplab.helpdesk.retrieval.support.DeterministicEmbeddingPort;
import com.shoplab.helpdesk.support.PostgresTest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A whole traced turn runs under BlockHound, which is installed for the whole test JVM: opening and closing the
 * spans is plain CPU work on the event loop, and the network of the exporter belongs to the thread of the SDK
 * (specs/LAB-64, US4). A blocking call on the event loop would fail the request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresTest
@Import(TracingBlockHoundTests.EmbeddingDouble.class)
class TracingBlockHoundTests {

    private static final String MODEL = "blockhound-trace-model";
    private static final String TRACES_URL = "/api/public/otel/v1/traces";
    private static final String ANSWER = "Respuesta trazada.";

    @TestConfiguration
    static class EmbeddingDouble {

        @Bean
        @Primary
        DeterministicEmbeddingPort blockHoundEmbeddingPort() {
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
        registry.add("shoplab.chat.tracing.public-key", () -> "pk-lf-blockhound");
        registry.add("shoplab.chat.tracing.secret-key", () -> "sk-lf-blockhound");
        registry.add("shoplab.chat.tracing.schedule-delay", () -> "100ms");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DeterministicEmbeddingPort embedding;

    @BeforeEach
    void prepare() {
        ollama.resetAll();
        langfuse.resetAll();
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.bodyWithUsage(4, 2, ANSWER)));
        langfuse.stubFor(post(urlEqualTo(TRACES_URL)).willReturn(aResponse().withStatus(200)));
        embedding.recover();
        embedding.useModel(MODEL);
        embedding.resetCounters();
    }

    @Test
    void aTracedTurnNeverBlocksTheEventLoopAndItsTraceLeavesOnAnotherThread() {
        ChatTestClient.Result result = new ChatTestClient(port)
                .post("{\"message\":\"Una pregunta cualquiera\"}", "text/event-stream", "blockhound-req-1");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.text()).isEqualTo(ANSWER);
        assertThat(result.last().name()).isEqualTo("done");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> langfuse.verify(moreThanOrExactly(1),
                postRequestedFor(urlEqualTo(TRACES_URL))));
    }
}
