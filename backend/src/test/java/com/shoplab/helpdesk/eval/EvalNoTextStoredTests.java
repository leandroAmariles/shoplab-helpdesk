package com.shoplab.helpdesk.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.support.PostgresTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The history never holds the text of an answer or of a fragment (FR-009, SC-008), and the API only lets a client in
 * with the token. It runs through the whole application against a real PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shoplab.eval.persistence.token=notext-token")
@PostgresTest
class EvalNoTextStoredTests {

    private static final Map<String, List<String>> COLUMNS = Map.of(
            "eval_run", List.of("id", "run_id", "kind", "started_at", "finished_at", "recorded_at", "origin",
                    "commit_sha", "dataset_hash", "cases_measured", "cases_excluded", "assistant_model",
                    "embedding_model", "prompts", "judge", "evaluator", "retrieval_config"),
            "eval_case", List.of("id", "case_id", "content_hash", "category", "question", "reference_answer",
                    "expected_documents", "created_at"),
            "eval_result", List.of("id", "run_ref", "case_ref", "case_kind", "scores", "retrieved_documents",
                    "abstained", "trace_id"));

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestRows() {
        jdbc.update("DELETE FROM eval.eval_result WHERE run_ref IN (SELECT id FROM eval.eval_run "
                + "WHERE run_id LIKE 'notext-%')");
        jdbc.update("DELETE FROM eval.eval_run WHERE run_id LIKE 'notext-%'");
        jdbc.update("DELETE FROM eval.eval_case WHERE case_id ~ '^G6[0-9]{2}$'");
    }

    private HttpResponse<String> post(String body, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/eval/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            request.header("X-Eval-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void theTablesHaveExactlyTheColumnsOfTheDataModelSoNoTextColumnCanAppearUnnoticed() {
        Map<String, List<String>> found = jdbc.queryForList("""
                SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = 'eval'
                ORDER BY table_name, ordinal_position
                """).stream().filter(row -> COLUMNS.containsKey((String) row.get("table_name")))
                .collect(Collectors.groupingBy(row -> (String) row.get("table_name"),
                        Collectors.mapping(row -> (String) row.get("column_name"), Collectors.toList())));

        COLUMNS.forEach((table, columns) -> assertThat(found.get(table)).as(table)
                .containsExactlyInAnyOrderElementsOf(columns));
    }

    @Test
    void aBodyThatCarriesTheTextOfAnAnswerOrAFragmentIsRefusedAndLeavesNoRow() throws Exception {
        String valid = EvalRunFixtures.qualityJson("notext-answer", 6);
        String withAnswer = valid.replace("\"abstained\":false", "\"abstained\":false,\"answer\":\"La respuesta\"");
        String withFragments = valid.replace("\"abstained\":false",
                "\"abstained\":false,\"fragments\":[\"un fragmento\"],\"context\":\"x\"");

        for (String body : new String[] {withAnswer, withFragments}) {
            HttpResponse<String> response = post(body, "notext-token");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("SLHD_GENE_00002");
        }
        assertThat(count("SELECT count(*) FROM eval.eval_run WHERE run_id LIKE 'notext-%'")).isZero();
        assertThat(count("SELECT count(*) FROM eval.eval_case WHERE case_id ~ '^G6[0-9]{2}$'")).isZero();
    }

    @Test
    void aSavedRunHoldsTheTextOfTheDatasetOnlyInTheCaseTable() throws Exception {
        HttpResponse<String> response = post(EvalRunFixtures.qualityJson("notext-saved", 6), "notext-token");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(count("SELECT count(*) FROM eval.eval_case WHERE question LIKE '%Mi boleto venció%'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM eval.eval_run WHERE row_to_json(eval_run)::text LIKE '%boleto%'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM eval.eval_result WHERE row_to_json(eval_result)::text "
                + "LIKE '%boleto%' AND row_to_json(eval_result)::text NOT LIKE '%payments-03-boleto-and-vouchers%'"))
                .isZero();
    }

    @Test
    void withoutTheRightTokenNothingIsSavedAndTheTokenNeverComesBack() throws Exception {
        String body = EvalRunFixtures.qualityJson("notext-denied", 6);

        for (String token : new String[] {null, "", "wrong", "NOTEXT-TOKEN"}) {
            HttpResponse<String> response = post(body, token);

            assertThat(response.statusCode()).as("token %s", token).isEqualTo(403);
            assertThat(response.body()).contains("SLHD_EVAL_00001").doesNotContain("notext-token");
        }
        assertThat(count("SELECT count(*) FROM eval.eval_run WHERE run_id LIKE 'notext-%'")).isZero();
    }

    @Test
    void aRunIdThatWasSavedCannotBeSavedAgain() throws Exception {
        String body = EvalRunFixtures.qualityJson("notext-again", 6);

        assertThat(post(body, "notext-token").statusCode()).isEqualTo(201);
        HttpResponse<String> second = post(body, "notext-token");

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("SLHD_EVAL_00003");
        assertThat(count("SELECT count(*) FROM eval.eval_run WHERE run_id = 'notext-again'")).isEqualTo(1);
    }

    @Test
    void aRunThatBreaksARuleIsUnprocessableAndNamesTheFieldWithoutQuotingTheText() throws Exception {
        String body = EvalRunFixtures.qualityJson("notext-rule", 6)
                .replace("\"faithfulness\":1.0", "\"faithfulness\":1.5");

        HttpResponse<String> response = post(body, "notext-token");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("SLHD_EVAL_00002", "results[0].scores.faithfulness")
                .doesNotContain("Mi boleto");
        assertThat(count("SELECT count(*) FROM eval.eval_run WHERE run_id LIKE 'notext-%'")).isZero();
    }
}
