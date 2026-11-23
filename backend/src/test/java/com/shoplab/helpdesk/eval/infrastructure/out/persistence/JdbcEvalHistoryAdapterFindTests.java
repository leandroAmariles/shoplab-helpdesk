package com.shoplab.helpdesk.eval.infrastructure.out.persistence;

import static com.shoplab.helpdesk.eval.EvalRunFixtures.hashed;
import static com.shoplab.helpdesk.eval.EvalRunFixtures.scores;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shoplab.helpdesk.eval.EvalRunFixtures;
import com.shoplab.helpdesk.eval.EvalRunFixtures.Builder;
import com.shoplab.helpdesk.eval.application.port.out.EvalHistoryPort;
import com.shoplab.helpdesk.eval.application.port.out.EvalRunStorePort;
import com.shoplab.helpdesk.eval.domain.model.EvalCase;
import com.shoplab.helpdesk.eval.domain.model.MetricStat;
import com.shoplab.helpdesk.eval.domain.model.ScopeSummary;
import com.shoplab.helpdesk.eval.domain.model.StoredResult;
import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import com.shoplab.helpdesk.eval.domain.model.enums.CaseKind;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import com.shoplab.helpdesk.support.PostgresTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One run read whole against a real PostgreSQL (specs/LAB-32): how it was measured, the summary by scope, and every
 * result with the case as it was when it was measured.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class JdbcEvalHistoryAdapterFindTests {

    private static final String RUNS = "fnd-%";
    private static final String CASES = "^G7[0-9]{2}$";

    @Autowired
    private EvalHistoryPort history;

    @Autowired
    private EvalRunStorePort store;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // q1: quality; G701 payments answered, G702 returns answered, G703 payments without answer.
        Builder q1 = EvalRunFixtures.quality("fnd-q1", 7);
        q1.mapCase(1, c -> new EvalCase(c.caseId(), null, "returns", c.question(), c.referenceAnswer(),
                c.expectedDocuments()));
        save(q1);
        // q2: later; the question of G701 was rewritten, so it is another revision of the case.
        Builder q2 = EvalRunFixtures.quality("fnd-q2", 7);
        q2.startedAt = Instant.parse("2026-11-22T10:00:00Z");
        q2.finishedAt = Instant.parse("2026-11-22T10:09:00Z");
        q2.commitSha = null;
        q2.assistantModel = null;
        q2.mapCase(0, c -> new EvalCase(c.caseId(), null, c.category(), "Pregunta reescrita sobre el boleto vencido",
                c.referenceAnswer(), c.expectedDocuments()));
        q2.mapCase(1, c -> new EvalCase(c.caseId(), null, "returns", c.question(), c.referenceAnswer(),
                c.expectedDocuments()));
        save(q2);
        Builder r1 = EvalRunFixtures.retrieval("fnd-r1", 7);
        save(r1);
    }

    @AfterEach
    void removeTestRows() {
        jdbc.update("DELETE FROM eval.eval_result WHERE run_ref IN (SELECT id FROM eval.eval_run WHERE run_id LIKE ?)",
                RUNS);
        jdbc.update("DELETE FROM eval.eval_run WHERE run_id LIKE ?", RUNS);
        jdbc.update("DELETE FROM eval.eval_case WHERE case_id ~ ?", CASES);
    }

    private void save(Builder builder) {
        store.save(hashed(builder.build())).block();
    }

    private StoredRun find(String runId) {
        return history.find(runId).block().orElseThrow();
    }

    private static ScopeSummary scope(StoredRun run, String scope) {
        return run.summary().stream().filter(s -> s.scope().equals(scope)).findFirst().orElseThrow();
    }

    private static StoredResult result(StoredRun run, String caseId) {
        return run.results().stream().filter(r -> r.caseId().equals(caseId)).findFirst().orElseThrow();
    }

    @Test
    void aRunThatDoesNotExistIsEmpty() {
        assertThat(history.find("fnd-nobody").block()).isEmpty();
    }

    @Test
    void theParametersOfAQualityRunComeCompleteWithTheJudgeAndTheEvaluator() {
        StoredRun run = find("fnd-q1");

        assertThat(run.kind()).isEqualTo(RunKind.QUALITY);
        assertThat(run.origin()).isEqualTo("local");
        assertThat(run.commitSha()).isEqualTo(EvalRunFixtures.COMMIT);
        assertThat(run.datasetHash()).isEqualTo(EvalRunFixtures.DATASET_HASH);
        assertThat(run.casesMeasured()).isEqualTo(3);
        assertThat(run.casesExcluded()).isEqualTo(24);
        assertThat(run.assistantModel()).isEqualTo("llama3.2:3b");
        assertThat(run.embeddingModel()).isEqualTo("bge-m3");
        assertThat(run.recordedAt()).isNotNull();
        assertThat(run.finishedAt()).isAfter(run.startedAt());
        assertThat(run.prompts()).hasSize(4);
        assertThat(run.prompts().get(2).name()).isEqualTo("chat-context");
        assertThat(run.prompts().get(2).version()).isEqualTo(2);
        assertThat(run.prompts().get(2).pinned()).isTrue();
        assertThat(run.prompts().get(3).version()).isNull();
        assertThat(run.prompts().get(3).source()).isEqualTo(PromptSource.DEFAULT);
        assertThat(run.judge().model()).isEqualTo("judge");
        assertThat(run.judge().underlyingModel()).isEqualTo("ollama_chat/qwen2.5:7b");
        assertThat(run.judge().temperature()).isEqualTo(0.0);
        assertThat(run.judge().seed()).isEqualTo(7);
        assertThat(run.judge().maxInputChars()).isEqualTo(24000);
        assertThat(run.evaluator().library()).isEqualTo("ragas");
        assertThat(run.evaluator().libraryVersion()).isEqualTo("0.4.4");
        assertThat(run.evaluator().judgmentInstructionsVersion()).isEqualTo("2");
        assertThat(run.retrievalConfig().topK()).isEqualTo(4);
        assertThat(run.retrievalConfig().similarityThreshold()).isEqualTo(0.6);
        assertThat(run.retrievalConfig().maxContextCharacters()).isEqualTo(8000);
        assertThat(run.retrievalConfig().depth()).isNull();
        assertThat(run.retrievalConfig().ks()).isNull();
    }

    @Test
    void aRetrievalRunHasNoJudgeAndKeepsTheDepthAndTheKs() {
        StoredRun run = find("fnd-r1");

        assertThat(run.kind()).isEqualTo(RunKind.RETRIEVAL);
        assertThat(run.judge()).isNull();
        assertThat(run.evaluator()).isNull();
        assertThat(run.prompts()).isEmpty();
        assertThat(run.retrievalConfig().depth()).isEqualTo(50);
        assertThat(run.retrievalConfig().ks()).containsExactly(1, 3, 5);
    }

    @Test
    void whatTheRunDidNotKnowComesAsNullAndIsNeverInvented() {
        StoredRun run = find("fnd-q2");

        assertThat(run.commitSha()).isNull();
        assertThat(run.assistantModel()).isNull();
    }

    @Test
    void theSummaryHasTheWholeSetAndEachCategoryWithTheirCasesAndMeans() {
        StoredRun run = find("fnd-q1");

        assertThat(run.summary().get(0).scope()).isEqualTo("all");
        assertThat(run.summary()).extracting(ScopeSummary::scope).containsExactly("all", "payments", "returns");
        ScopeSummary all = scope(run, "all");
        assertThat(all.cases()).isEqualTo(3);
        // faithfulness: 1.0 and 0.5 (the case without answer is null) -> 0.75 over 2 cases.
        assertThat(all.metrics().get("faithfulness").mean()).isCloseTo(0.75, within(1e-9));
        assertThat(all.metrics().get("faithfulness").n()).isEqualTo(2);
        assertThat(all.metrics().get("answer_relevancy").n()).isEqualTo(2);
        ScopeSummary payments = scope(run, "payments");
        assertThat(payments.cases()).isEqualTo(2);
        assertThat(payments.metrics().get("faithfulness")).isEqualTo(new MetricStat(1.0, 1));
        ScopeSummary returns = scope(run, "returns");
        assertThat(returns.cases()).isEqualTo(1);
        assertThat(returns.metrics().get("faithfulness")).isEqualTo(new MetricStat(0.5, 1));
    }

    @Test
    void aMetricWithoutAnyValueInAScopeIsAGapThere() {
        // The case without answer has faithfulness null: in the scope of its category the value of that metric exists
        // only through the answered case; a scope made only of the case without answer has no value at all.
        Builder only = EvalRunFixtures.quality("fnd-q3", 7);
        only.results.remove(1);
        only.results.remove(0);
        save(only);

        MetricStat faithfulness = scope(find("fnd-q3"), "payments").metrics().get("faithfulness");

        assertThat(faithfulness.mean()).isNull();
        assertThat(faithfulness.n()).isZero();
    }

    @Test
    void theResultsComeOrderedByCaseWithTheScoresAndTheDocumentsAsTheyWereSaved() {
        StoredRun run = find("fnd-q1");

        assertThat(run.results()).extracting(StoredResult::caseId).containsExactly("G701", "G702", "G703");
        StoredResult first = result(run, "G701");
        assertThat(first.caseKind()).isEqualTo(CaseKind.ANSWERED);
        assertThat(first.scores().get("faithfulness")).isEqualTo(1.0);
        assertThat(first.scores().get("answer_relevancy")).isEqualTo(0.9);
        assertThat(first.retrievedDocuments()).containsExactly(EvalRunFixtures.DOCUMENT, EvalRunFixtures.DOCUMENT);
        assertThat(first.expectedDocuments()).containsExactly(EvalRunFixtures.DOCUMENT);
        assertThat(first.abstained()).isFalse();
        assertThat(first.traceId()).isEqualTo("traceg701");
        assertThat(first.revision()).hasSize(64);
    }

    @Test
    void aMetricWithoutValueStaysNullAndAZeroStaysAZero() {
        Builder zeros = EvalRunFixtures.quality("fnd-q4", 7);
        zeros.withScores(0, scores("faithfulness", 0.0, "answer_relevancy", null, "context_precision", 0.0));
        save(zeros);

        StoredResult first = result(find("fnd-q4"), "G701");

        assertThat(first.scores()).containsEntry("faithfulness", 0.0).containsEntry("context_precision", 0.0);
        assertThat(first.scores()).containsKey("answer_relevancy");
        assertThat(first.scores().get("answer_relevancy")).isNull();
    }

    @Test
    void aCaseWithoutAnswerKeepsOnlyTheFaithfulnessAndAbstained() {
        StoredResult unanswered = result(find("fnd-q1"), "G703");

        assertThat(unanswered.caseKind()).isEqualTo(CaseKind.UNANSWERED);
        assertThat(unanswered.scores()).containsOnlyKeys("faithfulness");
        assertThat(unanswered.expectedDocuments()).isEmpty();
        assertThat(unanswered.retrievedDocuments()).isEmpty();
        assertThat(unanswered.abstained()).isTrue();
        assertThat(unanswered.traceId()).isNull();
    }

    @Test
    void aRunKeepsTheCaseAsItWasWhenItWasMeasuredEvenIfTheCaseWasRewrittenLater() {
        StoredResult before = result(find("fnd-q1"), "G701");
        StoredResult after = result(find("fnd-q2"), "G701");

        assertThat(before.question()).contains("Mi boleto venció");
        assertThat(after.question()).isEqualTo("Pregunta reescrita sobre el boleto vencido");
        assertThat(before.revision()).isNotEqualTo(after.revision());
        // The cases that did not change keep the revision.
        assertThat(result(find("fnd-q1"), "G702").revision()).isEqualTo(result(find("fnd-q2"), "G702").revision());
    }

    @Test
    void theResultsOfARetrievalRunCarryTheMetricsOfEachK() {
        StoredResult first = result(find("fnd-r1"), "G701");

        assertThat(first.scores()).containsKeys("hit_at_1", "recall_at_3", "reciprocal_rank");
        assertThat(first.traceId()).isNull();
        assertThat(first.abstained()).isNull();
    }

    @Test
    void theListOfPromptsKeepsTheOrderTheyWereSaved() {
        assertThat(find("fnd-q1").prompts()).extracting(prompt -> prompt.name())
                .containsExactly("chat-system", "chat-small-talk", "chat-context", "chat-empty-context");
        assertThat(List.copyOf(find("fnd-q1").results())).hasSize(3);
    }
}
