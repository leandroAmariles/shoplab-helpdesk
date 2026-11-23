package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import com.shoplab.helpdesk.chat.support.EmptyKnowledgeContextConfig;
import com.shoplab.helpdesk.support.PostgresTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end integration test for LAB-33: a chat turn with {@code conversationId} emits {@code turnId} in {@code done},
 * accepts {@code UP} and {@code DOWN} feedback with optional comment, lists {@code DOWN} feedback for review, promotes
 * it to a golden case {@code G121} with canonical JSONL, blocks re-promotion or editing, and exports safe Prometheus
 * metrics without PII or identifiers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shoplab.chat.rag.no-context-mode=model")
@PostgresTest
@Import(EmptyKnowledgeContextConfig.class)
class ChatFeedbackIntegrationTests {

    private static final String QUESTION = "it-fb-cuantos dias tengo para devolver una compra?";
    private static final String COMMENT = "it-fb-comentario-secreto-del-cliente";

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> ollama.baseUrl() + "/v1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private String conversationId;

    @BeforeEach
    void setUp() {
        ollama.resetAll();
        GatewayStubs.stubAnyChat(ollama, GatewayStubs.streaming(GatewayStubs.body("Tienes ", "15 dias.")));
        conversationId = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM chat.conversation_turn WHERE question LIKE 'it-fb-%'");
    }

    @Test
    void completesTurnRecordsAndUpdatesFeedbackListsPromotesToGoldenAndExportsSafeMetrics() {
        ChatTestClient.Result stream = new ChatTestClient(port).post(
                "{\"message\":\"" + QUESTION + "\",\"conversationId\":\"" + conversationId + "\"}");
        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.last().name()).isEqualTo("done");
        String turnId = JsonPath.read(stream.last().data(), "$.turnId");
        assertThat(turnId).isNotBlank();

        HttpResponse<String> upResponse = sendJson(
                "PUT", "/api/chat/feedback/" + turnId, "{\"rating\":\"UP\"}");
        assertThat(upResponse.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(upResponse.body(), "$.rating")).isEqualTo("UP");

        HttpResponse<String> downResponse = sendJson(
                "PUT",
                "/api/chat/feedback/" + turnId,
                "{\"rating\":\"DOWN\",\"comment\":\"" + COMMENT + "\"}");
        assertThat(downResponse.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(downResponse.body(), "$.rating")).isEqualTo("DOWN");
        assertThat((String) JsonPath.read(downResponse.body(), "$.comment")).isEqualTo(COMMENT);

        HttpResponse<String> listResponse = sendGet("/api/chat/feedback?rating=DOWN&promoted=false");
        assertThat(listResponse.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(listResponse.body(), "$.suggestedNextCaseId")).isEqualTo("G121");
        assertThat(listResponse.body()).contains(turnId).contains(QUESTION);

        String promotePayload = """
                {
                  "caseId": "G121",
                  "question": "¿Cuántos días tengo para devolver una compra en ShopLab?",
                  "referenceAnswer": "Puedes solicitar la devolución dentro de los 30 días naturales desde la entrega.",
                  "expectedDocuments": ["returns-01-return-policy"],
                  "category": "returns"
                }
                """;
        HttpResponse<String> promoteResponse = sendJson(
                "POST", "/api/chat/feedback/" + turnId + "/promote", promotePayload);
        assertThat(promoteResponse.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(promoteResponse.body(), "$.promotedCaseId")).isEqualTo("G121");
        String jsonl = JsonPath.read(promoteResponse.body(), "$.promotedCaseJsonl");
        assertThat(jsonl).startsWith("{\"id\": \"G121\", \"question\": ");

        HttpResponse<String> secondPromote = sendJson(
                "POST", "/api/chat/feedback/" + turnId + "/promote", promotePayload);
        assertThat(secondPromote.statusCode()).isEqualTo(409);
        assertThat((String) JsonPath.read(secondPromote.body(), "$.code")).isEqualTo("SLHD_CHAT_00013");

        HttpResponse<String> editAfterPromote = sendJson(
                "PUT", "/api/chat/feedback/" + turnId, "{\"rating\":\"UP\"}");
        assertThat(editAfterPromote.statusCode()).isEqualTo(409);
        assertThat((String) JsonPath.read(editAfterPromote.body(), "$.code")).isEqualTo("SLHD_CHAT_00013");

        String metrics = sendGet("/actuator/prometheus").body();
        assertThat(metrics)
                .contains("shoplab_chat_feedback_total{")
                .contains("rating=\"up\"")
                .contains("rating=\"down\"")
                .contains("shoplab_chat_feedback_promoted_total{")
                .doesNotContain(turnId)
                .doesNotContain(conversationId)
                .doesNotContain(QUESTION)
                .doesNotContain(COMMENT);
    }

    private HttpResponse<String> sendJson(String method, String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private HttpResponse<String> sendGet(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .GET()
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
