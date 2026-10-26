package com.shoplab.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract of the metrics endpoint (specs/LAB-102): what Prometheus scrapes and how it is labelled.
 * Uses a real HTTP call so the full actuator and Micrometer wiring is exercised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MetricsEndpointTests.ProbeController.class)
class MetricsEndpointTests {

    private static final String PROMETHEUS_PATH = "/actuator/prometheus";

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void prometheusEndpointExposesJvmAndHttpMetrics() {
        get("/probe/1");

        HttpResponse<String> response = get(PROMETHEUS_PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("jvm_memory_used_bytes")
                .contains("http_server_requests_seconds_count");
    }

    @Test
    void httpDurationIsPublishedAsHistogramBuckets() {
        get("/probe/1");

        assertThat(get(PROMETHEUS_PATH).body()).contains("http_server_requests_seconds_bucket");
    }

    @Test
    void everySeriesCarriesTheApplicationTag() {
        String body = get(PROMETHEUS_PATH).body();

        assertThat(body).containsPattern("jvm_memory_used_bytes\\{[^}]*application=\"helpdesk-backend\"");
        assertThat(body).containsPattern(
                "http_server_requests_seconds_count\\{[^}]*application=\"helpdesk-backend\"");
    }

    @Test
    void healthEndpointKeepsWorking() {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void routesWithVariablesAreGroupedByTemplate() {
        get("/probe/1");
        get("/probe/2");

        String body = get(PROMETHEUS_PATH).body();

        assertThat(body).contains("uri=\"/probe/{id}\"");
        assertThat(body).doesNotContain("uri=\"/probe/1\"").doesNotContain("uri=\"/probe/2\"");
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() {
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling " + path, e);
        }
    }

    /** Test-only route with a path variable, so the suite does not depend on business endpoints. */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/{id}")
        String probe(@PathVariable String id) {
            return id;
        }
    }
}
