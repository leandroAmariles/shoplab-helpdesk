package com.shoplab.helpdesk.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.support.PostgresTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The three endpoints of the evaluation history through the whole application, against a real PostgreSQL: the
 * contract of contracts/eval-runs-api.md as the tool of eval/ sees it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shoplab.eval.persistence.token=api-token")
@PostgresTest
class EvalApiIntegrationTests {

    private static final String TOKEN = "api-token";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestRows() {
        jdbc.update("DELETE FROM eval.eval_result WHERE run_ref IN (SELECT id FROM eval.eval_run "
                + "WHERE run_id LIKE 'api-%')");
        jdbc.update("DELETE FROM eval.eval_run WHERE run_id LIKE 'api-%'");
        jdbc.update("DELETE FROM eval.eval_case WHERE case_id ~ '^G5[0-9]{2}$'");
    }

    private HttpResponse<String> send(String method, String path, String token, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            request.header("X-Eval-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theSettingsOfTheAssistantComeWithTheShapeOfTheContract() throws Exception {
        HttpResponse<String> response = send("GET", "/api/eval/settings", TOKEN, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"top_k\":4", "\"similarity_threshold\":0.6",
                "\"max_context_characters\":8000", "\"name\":\"chat-system\"", "\"name\":\"chat-small-talk\"",
                "\"name\":\"chat-context\"", "\"name\":\"chat-empty-context\"", "\"source\":\"DEFAULT\"");
    }

    @Test
    void theSettingsNeedTheToken() throws Exception {
        assertThat(send("GET", "/api/eval/settings", null, null).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/eval/settings", "wrong", null).statusCode()).isEqualTo(403);
    }

    @Test
    void aRunIsNotFoundUntilItIsSavedAndThenItIs() throws Exception {
        HttpResponse<String> before = send("GET", "/api/eval/runs/api-flow", TOKEN, null);
        HttpResponse<String> saved = send("POST", "/api/eval/runs", TOKEN, EvalRunFixtures.qualityJson("api-flow", 5));
        HttpResponse<String> after = send("GET", "/api/eval/runs/api-flow", TOKEN, null);
        HttpResponse<String> again = send("POST", "/api/eval/runs", TOKEN, EvalRunFixtures.qualityJson("api-flow", 5));

        assertThat(before.statusCode()).isEqualTo(404);
        assertThat(before.body()).contains("SLHD_EVAL_00004");
        assertThat(saved.statusCode()).isEqualTo(201);
        assertThat(saved.body()).contains("\"run_id\":\"api-flow\"", "\"kind\":\"QUALITY\"", "\"results\":2",
                "\"new_case_revisions\":2");
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(after.body()).contains("\"run_id\":\"api-flow\"", "\"kind\":\"QUALITY\"",
                "\"started_at\":\"2026-11-21T14:02:11Z\"", "\"recorded_at\"");
        assertThat(again.statusCode()).isEqualTo(409);
    }

    @Test
    void aRunIdThatDoesNotFitIsUnprocessableAndNeedsTheTokenToo() throws Exception {
        HttpResponse<String> invalid = send("GET", "/api/eval/runs/con%20espacio", TOKEN, null);

        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(invalid.body()).contains("SLHD_EVAL_00002", "run_id");
        assertThat(send("GET", "/api/eval/runs/api-nothing", null, null).statusCode()).isEqualTo(403);
    }
}
