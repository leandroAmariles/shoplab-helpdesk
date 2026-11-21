package com.shoplab.helpdesk.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.support.PostgresTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The history of evaluation runs lives in the eval schema with the shape of the data model (specs/LAB-31). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class EvalSchemaIntegrationTests {

    private static final String HASH = "a".repeat(64);
    private static final String JUDGE = "{\"model\":\"judge\"}";
    private static final String EVALUATOR = "{\"library\":\"ragas\"}";
    private static final String INSERT_RUN = """
            INSERT INTO eval.eval_run (run_id, kind, started_at, finished_at, origin, commit_sha, dataset_hash,
                cases_measured, cases_excluded, embedding_model, prompts, judge, evaluator, retrieval_config)
            VALUES (?, ?, now(), now() + interval '1 minute', 'local', ?, ?, 1, 0, 'bge-m3', '[]'::jsonb,
                ?::jsonb, ?::jsonb, '{}'::jsonb)
            RETURNING id
            """;
    private static final String INSERT_CASE = """
            INSERT INTO eval.eval_case (case_id, content_hash, category, question, reference_answer, expected_documents)
            VALUES (?, ?, 'payments', 'schema-test question', 'schema-test answer', ARRAY['payments-01'])
            RETURNING id
            """;
    private static final String INSERT_RESULT = """
            INSERT INTO eval.eval_result (run_ref, case_ref, case_kind, scores, retrieved_documents)
            VALUES (?, ?, 'answered', ?::jsonb, ARRAY['payments-01'])
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestRows() {
        jdbc.update("DELETE FROM eval.eval_result WHERE run_ref IN "
                + "(SELECT id FROM eval.eval_run WHERE run_id LIKE 'schema-%')");
        jdbc.update("DELETE FROM eval.eval_case WHERE question = 'schema-test question'");
        jdbc.update("DELETE FROM eval.eval_run WHERE run_id LIKE 'schema-%'");
    }

    private long quality(String runId) {
        return jdbc.queryForObject(INSERT_RUN, Long.class, runId, "QUALITY", null, HASH, JUDGE, EVALUATOR);
    }

    private long caseRevision(String caseId, String hash) {
        return jdbc.queryForObject(INSERT_CASE, Long.class, caseId, hash);
    }

    @Test
    void theThreeTablesExistWithTheirOwnHistory() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'eval'", String.class);

        assertThat(tables).contains("eval_run", "eval_case", "eval_result");
        assertThat(jdbc.queryForList("SELECT version FROM eval.flyway_schema_history WHERE success "
                + "AND version IS NOT NULL ORDER BY installed_rank", String.class)).containsExactly("1", "2");
    }

    @Test
    void noForeignKeyLeavesTheSchema() {
        List<String> crossing = jdbc.queryForList("""
                SELECT c.conname FROM pg_constraint c
                JOIN pg_class child ON child.oid = c.conrelid
                JOIN pg_namespace childNs ON childNs.oid = child.relnamespace
                JOIN pg_class parent ON parent.oid = c.confrelid
                JOIN pg_namespace parentNs ON parentNs.oid = parent.relnamespace
                WHERE c.contype = 'f' AND (childNs.nspname = 'eval' OR parentNs.nspname = 'eval')
                  AND childNs.nspname <> parentNs.nspname
                """, String.class);

        assertThat(crossing).isEmpty();
    }

    @Test
    void aWholeRunWithItsCaseAndResultIsAccepted() {
        long run = quality("schema-ok");
        long caseId = caseRevision("G901", HASH);

        assertThatCode(() -> jdbc.update(INSERT_RESULT, run, caseId, "{\"faithfulness\":0.5}"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "schema bad|QUALITY|| ",
        "schema-this-run-id-is-longer-than-32-characters|QUALITY||",
        "schema-kind|OTHER||",
        "schema-commit|QUALITY|not-a-commit|",
        "schema-commit-short|QUALITY|abc123|"})
    void aRunBreakingAFormatRuleIsRejected(String runId, String kind, String commit) {
        assertThatThrownBy(() -> jdbc.queryForObject(INSERT_RUN, Long.class, runId, kind,
                commit == null || commit.isBlank() ? null : commit, HASH, JUDGE, EVALUATOR))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDatasetHashThatIsNotSha256IsRejected() {
        assertThatThrownBy(() -> jdbc.queryForObject(INSERT_RUN, Long.class, "schema-hash", "QUALITY", null,
                "abc", JUDGE, EVALUATOR)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRunThatFinishesBeforeItStartsIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO eval.eval_run (run_id, kind, started_at, finished_at, origin, dataset_hash, cases_measured,
                    cases_excluded, embedding_model, prompts, retrieval_config)
                VALUES ('schema-time', 'RETRIEVAL', now(), now() - interval '1 minute', 'local', ?, 1, 0, 'bge-m3',
                    '[]'::jsonb, '{}'::jsonb)
                """, HASH)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theJudgeBelongsToAQualityRunOnly() {
        assertThatThrownBy(() -> jdbc.queryForObject(INSERT_RUN, Long.class, "schema-judge-r", "RETRIEVAL", null,
                HASH, JUDGE, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.queryForObject(INSERT_RUN, Long.class, "schema-judge-q", "QUALITY", null,
                HASH, null, EVALUATOR)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> jdbc.queryForObject(INSERT_RUN, Long.class, "schema-retrieval", "RETRIEVAL", null,
                HASH, null, null)).doesNotThrowAnyException();
    }

    @Test
    void aRunIdIsUnique() {
        quality("schema-dup");

        assertThatThrownBy(() -> quality("schema-dup")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRevisionOfACaseIsUniqueButAnotherRevisionOfTheSameCaseIsNot() {
        caseRevision("G902", HASH);

        assertThatThrownBy(() -> caseRevision("G902", HASH)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> caseRevision("G902", "b".repeat(64))).doesNotThrowAnyException();
    }

    @Test
    void aCaseAppearsOncePerRun() {
        long run = quality("schema-once");
        long caseId = caseRevision("G903", HASH);
        jdbc.update(INSERT_RESULT, run, caseId, "{\"faithfulness\":1}");

        assertThatThrownBy(() -> jdbc.update(INSERT_RESULT, run, caseId, "{\"faithfulness\":0}"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"faithfulness\":1.5}", "{\"faithfulness\":-0.1}", "{\"a\":0.5,\"b\":2}", "[0.5]", "0.5"})
    void aScoreOutsideZeroAndOneOrNotAnObjectIsRejected(String scores) {
        long run = quality("schema-score-" + Math.abs(scores.hashCode()));
        long caseId = caseRevision("G904", HASH);

        assertThatThrownBy(() -> jdbc.update(INSERT_RESULT, run, caseId, scores))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"faithfulness\":null}", "{\"faithfulness\":0}", "{\"faithfulness\":1}",
        "{\"faithfulness\":0.25,\"answer_relevancy\":null,\"context_precision\":0.75}", "{}"})
    void aNullAZeroAndAOneAreScores(String scores) {
        long run = quality("schema-valid-" + Math.abs(scores.hashCode()));
        long caseId = caseRevision("G905", HASH);

        assertThatCode(() -> jdbc.update(INSERT_RESULT, run, caseId, scores)).doesNotThrowAnyException();
    }

    @Test
    void aCategoryOutsideTheDatasetIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO eval.eval_case (case_id, content_hash, category, question, reference_answer,
                    expected_documents)
                VALUES ('G906', ?, 'weather', 'schema-test question', 'x', ARRAY[]::text[])
                """, HASH)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
