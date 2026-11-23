package com.shoplab.helpdesk.eval;

import static com.shoplab.helpdesk.eval.EvalRunFixtures.hashed;
import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.eval.application.port.out.EvalRunStorePort;
import com.shoplab.helpdesk.support.PostgresTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nothing that reads the history can carry the text of an answer or of a fragment, a key or a token (specs/LAB-32,
 * FR-016, SC-008). The fields of every answer are fixed here: whoever adds one has to change this test, so a field
 * with text cannot slip in unnoticed. It runs through the whole application against a real PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shoplab.eval.persistence.token=hist-notext-token",
                "shoplab.eval.history.trace-url-template=http://langfuse.test/trace/{traceId}"})
@PostgresTest
class EvalHistoryNoTextTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> LIST_FIELDS = Set.of("$.kind", "$.shown", "$.total", "$.total_all_origins",
            "$.origins", "$.origins[].origin", "$.origins[].count", "$.runs", "$.runs[].run_id", "$.runs[].kind",
            "$.runs[].started_at", "$.runs[].finished_at", "$.runs[].origin", "$.runs[].commit_sha",
            "$.runs[].assistant_model", "$.runs[].prompts", "$.runs[].prompts[].name",
            "$.runs[].prompts[].version", "$.runs[].prompts[].source", "$.runs[].prompts[].pinned",
            "$.runs[].cases_measured", "$.runs[].metrics", "$.runs[].metrics.*", "$.runs[].metrics.*.mean",
            "$.runs[].metrics.*.n");

    private static final Set<String> DETAIL_FIELDS = Set.of("$.run", "$.run.run_id", "$.run.kind",
            "$.run.started_at", "$.run.finished_at", "$.run.recorded_at", "$.run.origin", "$.run.commit_sha",
            "$.run.dataset_hash", "$.run.cases_measured", "$.run.cases_excluded", "$.run.assistant_model",
            "$.run.embedding_model", "$.run.prompts", "$.run.prompts[].name", "$.run.prompts[].version",
            "$.run.prompts[].source", "$.run.prompts[].pinned", "$.run.judge", "$.run.judge.model",
            "$.run.judge.underlying_model", "$.run.judge.temperature", "$.run.judge.seed",
            "$.run.judge.max_input_chars", "$.run.evaluator", "$.run.evaluator.library",
            "$.run.evaluator.library_version", "$.run.evaluator.judgment_instructions_version",
            "$.run.retrieval_config", "$.run.retrieval_config.top_k", "$.run.retrieval_config.similarity_threshold",
            "$.run.retrieval_config.max_context_characters", "$.run.retrieval_config.depth",
            "$.run.retrieval_config.ks", "$.summary", "$.summary[].scope", "$.summary[].cases",
            "$.summary[].metrics", "$.summary[].metrics.*", "$.summary[].metrics.*.mean",
            "$.summary[].metrics.*.n", "$.results",
            "$.results[].case_id", "$.results[].revision", "$.results[].category", "$.results[].question",
            "$.results[].reference_answer", "$.results[].expected_documents", "$.results[].case_kind",
            "$.results[].scores", "$.results[].scores.*", "$.results[].retrieved_documents",
            "$.results[].abstained", "$.results[].trace_id", "$.results[].trace_url");

    @LocalServerPort
    private int port;

    @Autowired
    private EvalRunStorePort store;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        store.save(hashed(EvalRunFixtures.quality("hnt-q", 8).build())).block();
        store.save(hashed(EvalRunFixtures.retrieval("hnt-r", 8).build())).block();
    }

    @AfterEach
    void removeTestRows() {
        jdbc.update("DELETE FROM eval.eval_result WHERE run_ref IN (SELECT id FROM eval.eval_run "
                + "WHERE run_id LIKE 'hnt-%')");
        jdbc.update("DELETE FROM eval.eval_run WHERE run_id LIKE 'hnt-%'");
        jdbc.update("DELETE FROM eval.eval_case WHERE case_id ~ '^G8[0-9]{2}$'");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Every path of field names of the body, with the names of the maps of metrics and scores as {@code *}. */
    private static Set<String> fieldsOf(String body) {
        Set<String> found = new TreeSet<>();
        walk(JSON.readTree(body), "$", found);
        return found;
    }

    private static void walk(JsonNode node, String path, Set<String> found) {
        if (node.isObject()) {
            boolean dynamic = path.endsWith(".metrics") || path.endsWith(".scores");
            List<String> names = new ArrayList<>();
            node.propertyNames().forEach(names::add);
            for (String name : names) {
                String child = path + "." + (dynamic ? "*" : name);
                found.add(child);
                walk(node.get(name), child, found);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                walk(element, path + "[]", found);
            }
        }
    }

    private static Set<String> unexpected(Set<String> found, Set<String> allowed) {
        Set<String> extra = new TreeSet<>(found);
        extra.removeAll(allowed);
        return extra;
    }

    @Test
    void theFieldsOfTheListAreExactlyTheOnesOfTheContract() throws Exception {
        HttpResponse<String> response = get("/api/eval/history/runs?kind=QUALITY&origin=local");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(unexpected(fieldsOf(response.body()), LIST_FIELDS)).isEmpty();
    }

    @Test
    void theFieldsOfARunAreExactlyTheOnesOfTheContractForQualityAndForRetrieval() throws Exception {
        for (String runId : new String[] {"hnt-q", "hnt-r"}) {
            HttpResponse<String> response = get("/api/eval/history/runs/" + runId);

            assertThat(response.statusCode()).as(runId).isEqualTo(200);
            assertThat(unexpected(fieldsOf(response.body()), DETAIL_FIELDS)).as(runId).isEmpty();
        }
    }

    @Test
    void theTextOfTheDatasetIsReadButNoTextOfAnAnswerExistsToBeRead() throws Exception {
        String body = get("/api/eval/history/runs/hnt-q").body();

        // The question and the reference answer are the golden dataset (synthetic); the answer of the assistant is not.
        assertThat(body).contains("Mi boleto venció").contains("\"reference_answer\"");
        assertThat(body).doesNotContain("\"answer\"", "\"response\"", "\"fragments\"", "\"chunks\"", "\"context\"",
                "\"text\"", "\"content\"");
    }

    @Test
    void theTokenOfSavingNeverComesBackAndIsNotNeededToRead() throws Exception {
        for (String path : new String[] {"/api/eval/history/runs?kind=QUALITY", "/api/eval/history/runs/hnt-q"}) {
            HttpResponse<String> response = get(path);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).doesNotContain("hist-notext-token");
        }
    }

    @Test
    void theLinkToTheTraceComesFromTheTemplateAndOnlyForAResultThatHasATrace() throws Exception {
        JsonNode quality = JSON.readTree(get("/api/eval/history/runs/hnt-q").body());
        JsonNode retrieval = JSON.readTree(get("/api/eval/history/runs/hnt-r").body());

        assertThat(quality.get("results").get(0).get("trace_url").asString())
                .isEqualTo("http://langfuse.test/trace/traceg801");
        assertThat(quality.get("results").get(2).get("trace_id").isNull()).isTrue();
        assertThat(quality.get("results").get(2).get("trace_url").isNull()).isTrue();
        assertThat(retrieval.get("results").get(0).get("trace_url").isNull()).isTrue();
    }
}
