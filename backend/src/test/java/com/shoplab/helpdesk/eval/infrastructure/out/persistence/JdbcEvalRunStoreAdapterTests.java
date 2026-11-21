package com.shoplab.helpdesk.eval.infrastructure.out.persistence;

import static com.shoplab.helpdesk.eval.EvalRunFixtures.hashed;
import static com.shoplab.helpdesk.eval.EvalRunFixtures.qualityResult;
import static com.shoplab.helpdesk.eval.EvalRunFixtures.scores;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.eval.EvalRunFixtures;
import com.shoplab.helpdesk.eval.EvalRunFixtures.Builder;
import com.shoplab.helpdesk.eval.application.port.out.EvalRunStorePort;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunAlreadyExistsException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.model.EvalCase;
import com.shoplab.helpdesk.eval.domain.model.EvalRun;
import com.shoplab.helpdesk.eval.domain.model.SavedRun;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import com.shoplab.helpdesk.support.PostgresTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** A real PostgreSQL: a run goes in whole or not at all, and a case keeps one row per revision (specs/LAB-31). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class JdbcEvalRunStoreAdapterTests {

    private static final String RUNS = "adapter-%";
    private static final String CASES = "^G8[0-9]{2}$";

    @Autowired
    private EvalRunStorePort store;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestRows() {
        jdbc.update("DELETE FROM eval.eval_result WHERE run_ref IN (SELECT id FROM eval.eval_run WHERE run_id LIKE ?)",
                RUNS);
        jdbc.update("DELETE FROM eval.eval_run WHERE run_id LIKE ?", RUNS);
        jdbc.update("DELETE FROM eval.eval_case WHERE case_id ~ ?", CASES);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int resultsOf(String runId) {
        return count("SELECT count(*) FROM eval.eval_result r JOIN eval.eval_run u ON u.id = r.run_ref "
                + "WHERE u.run_id = ?", runId);
    }

    private SavedRun save(Builder run) {
        return store.save(hashed(run.build())).block();
    }

    @Test
    void aRunIsSavedWholeWithOneResultPerCase() {
        SavedRun saved = save(EvalRunFixtures.quality("adapter-whole", 8));

        assertThat(saved).isEqualTo(new SavedRun("adapter-whole", RunKind.QUALITY, 3, 3));
        assertThat(count("SELECT count(*) FROM eval.eval_run WHERE run_id = 'adapter-whole'")).isEqualTo(1);
        assertThat(resultsOf("adapter-whole")).isEqualTo(3);
        assertThat(jdbc.queryForMap("SELECT kind, origin, assistant_model, embedding_model, cases_measured, "
                + "cases_excluded, commit_sha FROM eval.eval_run WHERE run_id = 'adapter-whole'"))
                .containsEntry("kind", "QUALITY").containsEntry("assistant_model", "llama3.2:3b")
                .containsEntry("cases_measured", 3).containsEntry("cases_excluded", 24)
                .containsEntry("commit_sha", EvalRunFixtures.COMMIT);
    }

    @Test
    void theParametersOfTheRunAreKeptWithTheVersionOfEveryPrompt() {
        save(EvalRunFixtures.quality("adapter-params", 8));

        assertThat(count("SELECT count(*) FROM eval.eval_run WHERE run_id = 'adapter-params' AND prompts @> "
                + "'[{\"name\":\"chat-context\",\"version\":2,\"source\":\"LANGFUSE\",\"pinned\":true}]'::jsonb"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM eval.eval_run WHERE run_id = 'adapter-params' AND "
                + "prompts @> '[{\"name\":\"chat-empty-context\",\"version\":null,\"source\":\"DEFAULT\"}]'::jsonb"))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT judge ->> 'model' AS judge, evaluator ->> 'library_version' AS library, "
                + "retrieval_config ->> 'top_k' AS top_k, retrieval_config ->> 'similarity_threshold' AS threshold "
                + "FROM eval.eval_run WHERE run_id = 'adapter-params'"))
                .containsEntry("judge", "judge").containsEntry("library", "0.4.4")
                .containsEntry("top_k", "4").containsEntry("threshold", "0.6");
    }

    @Test
    void anEmptyScoreAndAZeroAreDifferentThingsInTheDatabase() {
        Builder run = EvalRunFixtures.quality("adapter-empty", 8);
        run.mapResult(0, result -> qualityResult(result.evalCase(), 0.0, null, 1.0));
        save(run);

        assertThat(jdbc.queryForMap("""
                SELECT jsonb_typeof(scores -> 'faithfulness') AS zero,
                       jsonb_typeof(scores -> 'answer_relevancy') AS empty,
                       (scores ->> 'faithfulness')::numeric AS zero_value,
                       scores ->> 'answer_relevancy' AS empty_value
                FROM eval.eval_result r JOIN eval.eval_case c ON c.id = r.case_ref WHERE c.case_id = 'G801'
                """)).containsEntry("zero", "number").containsEntry("empty", "null")
                .satisfies(row -> assertThat(row.get("empty_value")).isNull())
                .satisfies(row -> assertThat(((Number) row.get("zero_value")).doubleValue()).isZero());
    }

    @Test
    void theMeansRecalculatedFromTheResultsAreTheOnesThatWentIn() {
        save(EvalRunFixtures.quality("adapter-means", 8));

        String sql = "SELECT round(avg((scores ->> ?)::numeric), 4) AS mean, count(scores ->> ?) AS n "
                + "FROM eval.eval_result WHERE run_ref = (SELECT id FROM eval.eval_run WHERE run_id = 'adapter-means')";
        assertThat(jdbc.queryForMap(sql, "faithfulness", "faithfulness")).containsEntry("n", 2L)
                .satisfies(row -> assertThat(((Number) row.get("mean")).doubleValue()).isEqualTo(0.75));
        assertThat(jdbc.queryForMap(sql, "answer_relevancy", "answer_relevancy")).containsEntry("n", 2L)
                .satisfies(row -> assertThat(((Number) row.get("mean")).doubleValue()).isEqualTo(0.65));
        assertThat(jdbc.queryForMap(sql, "context_precision", "context_precision")).containsEntry("n", 2L)
                .satisfies(row -> assertThat(((Number) row.get("mean")).doubleValue()).isEqualTo(0.55));
    }

    @Test
    void theRankAndTheDocumentsOfARetrievalRunAreKeptInOrder() {
        SavedRun saved = save(EvalRunFixtures.retrieval("adapter-retrieval", 8));

        assertThat(saved).isEqualTo(new SavedRun("adapter-retrieval", RunKind.RETRIEVAL, 2, 2));
        assertThat(jdbc.queryForObject("SELECT retrieval_config ->> 'depth' FROM eval.eval_run "
                + "WHERE run_id = 'adapter-retrieval'", String.class)).isEqualTo("50");
        assertThat(count("SELECT count(*) FROM eval.eval_run WHERE run_id = 'adapter-retrieval' "
                + "AND judge IS NULL AND evaluator IS NULL AND prompts = '[]'::jsonb")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM eval.eval_result r JOIN eval.eval_run u ON u.id = r.run_ref "
                + "WHERE u.run_id = 'adapter-retrieval' AND jsonb_exists(r.scores, 'reciprocal_rank') "
                + "AND r.abstained IS NULL AND r.trace_id IS NULL")).isEqualTo(2);
    }

    @Test
    void theSameCaseIsSharedBetweenRunsAndNeedsNoNewRevision() {
        save(EvalRunFixtures.quality("adapter-share-a", 8));

        SavedRun second = save(EvalRunFixtures.quality("adapter-share-b", 8));

        assertThat(second.newCaseRevisions()).isZero();
        assertThat(count("SELECT count(*) FROM eval.eval_case WHERE case_id = 'G801'")).isEqualTo(1);
        assertThat(count("SELECT count(DISTINCT case_ref) FROM eval.eval_result r JOIN eval.eval_case c "
                + "ON c.id = r.case_ref WHERE c.case_id = 'G801'")).isEqualTo(1);
    }

    @Test
    void aCaseWhoseContentChangedIsANewRevisionAndTheOldResultKeepsItsOwn() {
        save(EvalRunFixtures.quality("adapter-edit-a", 8));
        Builder edited = EvalRunFixtures.quality("adapter-edit-b", 8);
        edited.mapCase(0, c -> new EvalCase(c.caseId(), null, c.category(), c.question() + " (reescrita)",
                c.referenceAnswer(), c.expectedDocuments()));

        SavedRun second = save(edited);

        assertThat(second.newCaseRevisions()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM eval.eval_case WHERE case_id = 'G801'")).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT c.question FROM eval.eval_result r JOIN eval.eval_case c ON c.id = r.case_ref
                JOIN eval.eval_run u ON u.id = r.run_ref WHERE u.run_id = 'adapter-edit-a' AND c.case_id = 'G801'
                """, String.class)).singleElement().asString().doesNotContain("reescrita");
    }

    @Test
    void aFailureHalfWayLeavesNothingBehindNotEvenTheCasesAlreadyInserted() {
        Builder run = EvalRunFixtures.quality("adapter-halfway", 8);
        run.withScores(1, scores("faithfulness", 5.0, "answer_relevancy", 0.5, "context_precision", 0.5));

        assertThatThrownBy(() -> save(run)).isInstanceOf(EvalRunInvalidException.class);

        assertThat(count("SELECT count(*) FROM eval.eval_run WHERE run_id = 'adapter-halfway'")).isZero();
        assertThat(resultsOf("adapter-halfway")).isZero();
        assertThat(count("SELECT count(*) FROM eval.eval_case WHERE case_id ~ ?", CASES)).isZero();
    }

    @Test
    void aRunIdThatAlreadyExistsIsAConflictAndChangesNothing() {
        save(EvalRunFixtures.quality("adapter-twice", 8));

        assertThatThrownBy(() -> save(EvalRunFixtures.quality("adapter-twice", 8)))
                .isInstanceOf(EvalRunAlreadyExistsException.class);

        assertThat(count("SELECT count(*) FROM eval.eval_run WHERE run_id = 'adapter-twice'")).isEqualTo(1);
        assertThat(resultsOf("adapter-twice")).isEqualTo(3);
    }

    @Test
    void aSavedRunIsFoundWithItsKindAndItsMoments() {
        save(EvalRunFixtures.quality("adapter-find", 8));

        assertThat(store.find("adapter-find").block()).hasValueSatisfying(found -> {
            assertThat(found.runId()).isEqualTo("adapter-find");
            assertThat(found.kind()).isEqualTo(RunKind.QUALITY);
            assertThat(found.startedAt()).isEqualTo(java.time.Instant.parse("2026-11-21T14:02:11Z"));
            assertThat(found.recordedAt()).isNotNull();
        });
    }

    @Test
    void aRunThatWasNeverSavedIsNotFound() {
        assertThat(store.find("adapter-never").block()).isEmpty();
    }

    @Test
    void twoSavesOfTheSameRunIdAtTheSameTimeLeaveExactlyOneWholeRun() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> attempts = new ArrayList<>();
            for (int base : new int[] {8, 7}) {
                EvalRun run = hashed(EvalRunFixtures.quality("adapter-race", base).build());
                Callable<Object> attempt = () -> {
                    start.await();
                    try {
                        return store.save(run).block();
                    } catch (EvalRunAlreadyExistsException conflict) {
                        return conflict;
                    }
                };
                attempts.add(pool.submit(attempt));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> attempt : attempts) {
                outcomes.add(attempt.get());
            }

            assertThat(outcomes).hasSize(2).hasAtLeastOneElementOfType(SavedRun.class)
                    .hasAtLeastOneElementOfType(EvalRunAlreadyExistsException.class);
            assertThat(count("SELECT count(*) FROM eval.eval_run WHERE run_id = 'adapter-race'")).isEqualTo(1);
            assertThat(resultsOf("adapter-race")).isEqualTo(3);
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM eval.eval_result WHERE run_ref IN (SELECT id FROM eval.eval_run "
                    + "WHERE run_id = 'adapter-race')");
            jdbc.update("DELETE FROM eval.eval_run WHERE run_id = 'adapter-race'");
            jdbc.update("DELETE FROM eval.eval_case WHERE case_id ~ '^G7[0-9]{2}$'");
        }
    }
}
