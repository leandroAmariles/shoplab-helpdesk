package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The chat endpoint end to end (specs/LAB-12): a real HTTP client against the running backend, with Ollama
 * simulated by WireMock so the suite is deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatStreamingIntegrationTests {

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
    }

    @LocalServerPort
    private int port;

    private SseTestClient client() {
        return new SseTestClient(port);
    }

    @Test
    void streamsTheWholeAnswerInOrderAndEndsWithDone() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.fixtureBody()));

        SseTestClient.Result result = client().post("{\"message\":\"Hola\"}");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.header("Content-Type")).startsWith("text/event-stream");
        assertThat(result.events()).hasSize(OllamaStubs.FIXTURE_FRAGMENTS + 1);
        assertThat(result.last().name()).isEqualTo("done");
        assertThat(result.last().data()).isEqualTo("{\"fragments\":" + OllamaStubs.FIXTURE_FRAGMENTS + "}");
        assertThat(result.text()).isEqualTo(OllamaStubs.FIXTURE_TEXT);
    }

    @Test
    void concurrentRequestsNeverMixTheirFragments() throws Exception {
        int requests = 5;
        for (int i = 0; i < requests; i++) {
            ollama.stubFor(OllamaStubs.chatFor("msg-" + i).willReturn(OllamaStubs
                    .streaming(OllamaStubs.body("R" + i + "-a ", "R" + i + "-b ", "R" + i + "-c"))
                    .withChunkedDribbleDelay(3, 300)));
        }

        List<CompletableFuture<SseTestClient.Result>> calls = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                String message = "{\"message\":\"msg-" + i + "\"}";
                calls.add(CompletableFuture.supplyAsync(() -> client().post(message), executor));
            }
            for (int i = 0; i < requests; i++) {
                SseTestClient.Result result = calls.get(i).get();
                assertThat(result.status()).isEqualTo(200);
                assertThat(result.text()).isEqualTo("R" + i + "-a R" + i + "-b R" + i + "-c");
                assertThat(result.last().name()).isEqualTo("done");
            }
        }
    }

    @Test
    void theFirstTokenArrivesWhileTheModelIsStillGenerating() {
        ollama.stubFor(OllamaStubs.chatFor("slow").willReturn(OllamaStubs
                .streaming(OllamaStubs.body("uno ", "dos ", "tres ", "cuatro ", "cinco ", "seis ", "siete ",
                        "ocho ", "nueve ", "diez"))
                .withChunkedDribbleDelay(10, 3000)));

        SseTestClient.Result result = client().post("{\"message\":\"slow\"}");

        long firstTokenMillis = result.events().get(0).at() / 1_000_000;
        long doneMillis = result.last().at() / 1_000_000;
        assertThat(result.events().get(0).name()).isEqualTo("token");
        assertThat(firstTokenMillis).isLessThan(1000);
        assertThat(doneMillis).isGreaterThan(2500);
        assertThat(result.events().stream().filter(e -> "token".equals(e.name())).count())
                .isGreaterThanOrEqualTo(10);
    }

    @Test
    void publishesTheChatMetricsOnThePrometheusEndpoint() throws Exception {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.fixtureBody()));
        client().post("{\"message\":\"Hola\"}");

        HttpResponse<String> metrics = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(metrics.body())
                .contains("shoplab_chat_first_token_seconds_bucket")
                .containsPattern("shoplab_chat_response_seconds_count\\{[^}]*outcome=\"completed\"[^}]*\\}")
                .containsPattern("shoplab_chat_first_token_seconds_count\\{[^}]*application=\"helpdesk-backend\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"message\":\"   \"}", "{\"message\":\"\"}", "{\"message\":null}", "{}"})
    void blankOrMissingMessagesAreRejectedWithoutCallingTheModel(String body) {
        SseTestClient.Result result = client().post(body, "text/event-stream", "req-bad-1");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.header("Content-Type")).startsWith("application/json");
        assertThat(result.body()).contains("\"code\":\"SLHD_CHAT_00001\"");
        assertThat(result.header("X-Request-Id")).isEqualTo("req-bad-1");
        ollama.verify(exactly(0), postRequestedFor(urlEqualTo("/api/chat")));
    }

    @Test
    void aMessageOverTheLimitIsRejectedAndOneOfExactlyTheLimitIsAccepted() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("ok")));

        SseTestClient.Result tooLong = client().post("{\"message\":\"" + "x".repeat(4001) + "\"}");
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.body()).contains("\"code\":\"SLHD_CHAT_00001\"");
        ollama.verify(exactly(0), postRequestedFor(urlEqualTo("/api/chat")));

        SseTestClient.Result atLimit = client().post("{\"message\":\"" + "x".repeat(4000) + "\"}");
        assertThat(atLimit.status()).isEqualTo(200);
        assertThat(atLimit.text()).isEqualTo("ok");
    }

    @Test
    void unreadableJsonIsABadRequestWithTheGenericCode() {
        SseTestClient.Result result = client().post("{\"message\":");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body()).contains("\"code\":\"SLHD_GENE_00002\"");
        ollama.verify(exactly(0), postRequestedFor(urlEqualTo("/api/chat")));
    }

    @Test
    void aMissingModelBeforeTheAnswerStartsIsServiceUnavailableAsJson() {
        OllamaStubs.stubAnyChat(ollama, aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"model not found\"}"));

        SseTestClient.Result result = client().post("{\"message\":\"Hola\"}");

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.header("Content-Type")).startsWith("application/json");
        assertThat(result.body()).contains("\"code\":\"SLHD_CHAT_00002\"");
    }

    @Test
    void theRequestIdSentByTheClientIsEchoedOnSuccess() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("ok")));

        SseTestClient.Result result = client().post("{\"message\":\"Hola\"}", "text/event-stream", "req-ok-9");

        assertThat(result.header("X-Request-Id")).isEqualTo("req-ok-9");
    }

    @Test
    void closingTheConnectionCancelsTheAnswerAndTheNextRequestIsServedNormally() throws Exception {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("ok")));
        ollama.stubFor(OllamaStubs.chatFor("long").willReturn(OllamaStubs
                .streaming(OllamaStubs.body("a ".repeat(40).split("(?<= )")))
                .withChunkedDribbleDelay(40, 8000)));
        double cancelledBefore = cancelledResponses();

        HttpResponse<InputStream> open = client().open("{\"message\":\"long\"}");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(open.body(), StandardCharsets.UTF_8))) {
            assertThat(reader.readLine()).startsWith("event:token");
        }

        await().atMost(Duration.ofSeconds(3)).untilAsserted(
                () -> assertThat(cancelledResponses()).isEqualTo(cancelledBefore + 1));
        SseTestClient.Result next = client().post("{\"message\":\"Hola\"}");
        assertThat(next.status()).isEqualTo(200);
        assertThat(next.events().get(0).at() / 1_000_000).isLessThan(2000);
    }

    private double cancelledResponses() throws Exception {
        String body = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return body.lines()
                .filter(line -> line.startsWith("shoplab_chat_response_seconds_count")
                        && line.contains("outcome=\"cancelled\""))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }
}
