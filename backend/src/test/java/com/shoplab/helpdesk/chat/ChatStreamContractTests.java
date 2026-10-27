package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.support.PostgresTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The wire format of {@code POST /api/chat} (specs/LAB-111, FR-002): the raw bytes of the event stream must equal the
 * recorded golden file. With {@code -Dgolden.record=true} the test records the golden file and the metrics baseline
 * instead of comparing, which is how both were captured before the migration to WebFlux.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresTest
class ChatStreamContractTests {

    private static final Path GOLDEN = Path.of("src/test/resources/chat/golden-stream.txt");
    private static final Path METRICS_BASELINE =
            Path.of("../specs/LAB-111-webflux-reactive-backend/metrics-baseline.txt");
    private static final Pattern SERIES = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(.*)})? \\S+$");
    private static final Pattern LABEL_KEY = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"");

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void theRawEventStreamEqualsTheGoldenFile() throws Exception {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.fixtureBody()));

        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(url("/api/chat"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"Hola\"}")).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        if (Boolean.getBoolean("golden.record")) {
            record(response.body());
            return;
        }
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo(Files.readString(GOLDEN, StandardCharsets.UTF_8));
    }

    private void record(byte[] stream) throws IOException, InterruptedException {
        Files.createDirectories(GOLDEN.getParent());
        Files.write(GOLDEN, stream);
        HttpResponse<String> metrics = client.send(HttpRequest.newBuilder(url("/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());
        Files.write(METRICS_BASELINE, List.copyOf(series(metrics.body())), StandardCharsets.UTF_8);
    }

    /** Series as {@code name{label,keys}} with no values, sorted and without duplicates. */
    private static TreeSet<String> series(String exposition) {
        TreeSet<String> series = new TreeSet<>();
        exposition.lines().filter(line -> !line.startsWith("#")).forEach(line -> {
            Matcher matcher = SERIES.matcher(line);
            if (matcher.matches()) {
                TreeSet<String> keys = new TreeSet<>();
                if (matcher.group(2) != null) {
                    Matcher label = LABEL_KEY.matcher(matcher.group(2));
                    while (label.find()) {
                        keys.add(label.group(1));
                    }
                }
                series.add(matcher.group(1) + "{" + String.join(",", keys) + "}");
            }
        });
        return series;
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
