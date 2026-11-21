package com.shoplab.helpdesk.eval.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.eval.EvalRunFixtures;
import com.shoplab.helpdesk.eval.EvalRunFixtures.Builder;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.model.EvalCase;
import com.shoplab.helpdesk.eval.domain.model.EvaluatorInfo;
import com.shoplab.helpdesk.eval.domain.model.JudgeInfo;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.enums.CaseKind;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EvalRunValidatorTests {

    private final EvalRunValidator validator = new EvalRunValidator();

    record Mutation(String name, Consumer<Builder> change, String expected) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static Mutation quality(String name, Consumer<Builder> change, String expected) {
        return new Mutation("quality: " + name, change, expected);
    }

    private static Mutation retrieval(String name, Consumer<Builder> change, String expected) {
        return new Mutation("retrieval: " + name, change, expected);
    }

    private static Map<String, Double> scores(Object... pairs) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1] == null ? null : ((Number) pairs[index + 1]).doubleValue());
        }
        return map;
    }

    private static Builder valid() {
        return EvalRunFixtures.quality("t031-1", 6);
    }

    private static Builder validRetrieval() {
        return EvalRunFixtures.retrieval("t031-2", 6);
    }

    @Test
    void aValidQualityRunPassesEvenWithEmptyScoresAndZeros() {
        Builder run = valid();
        run.mapResult(1, result -> EvalRunFixtures.qualityResult(result.evalCase(), 0.0, null, 0.0));

        assertThatCode(() -> validator.validate(run.build())).doesNotThrowAnyException();
    }

    @Test
    void aValidRetrievalRunPasses() {
        assertThatCode(() -> validator.validate(validRetrieval().build())).doesNotThrowAnyException();
    }

    @Test
    void aMissingRunIsRefused() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(EvalRunInvalidException.class);
    }

    static Stream<Mutation> invalidRuns() {
        String longText = "x".repeat(301);
        return Stream.of(
                quality("a run id with a space", run -> run.runId = "bad id", "run_id"),
                quality("a run id of 33 characters", run -> run.runId = "a".repeat(33), "run_id"),
                quality("no run id", run -> run.runId = null, "run_id"),
                quality("no kind", run -> run.kind = null, "kind"),
                quality("finishing before starting", run -> run.finishedAt = Instant.parse("2026-11-21T14:00:00Z"),
                        "finished_at"),
                quality("no start", run -> run.startedAt = null, "started_at"),
                quality("an origin with a space", run -> run.origin = "my laptop", "origin"),
                quality("a commit that is not hexadecimal", run -> run.commitSha = "XYZ", "commit_sha"),
                quality("a dataset hash that is not sha-256", run -> run.datasetHash = "abc", "dataset_hash"),
                quality("a negative number of excluded cases", run -> run.casesExcluded = -1, "cases_excluded"),
                quality("no embedding model", run -> run.embeddingModel = " ", "embedding_model"),
                quality("a blank assistant model", run -> run.assistantModel = " ", "assistant_model"),
                quality("a count that is not the number of results", run -> run.casesMeasured = 5, "cases_measured"),
                quality("no results", run -> run.results.clear(), "results"),
                quality("three prompts instead of four", run -> run.prompts.remove(3), "prompts"),
                quality("an unknown prompt", run -> run.prompts.set(0,
                        new PromptVersion("chat-other", 1, PromptSource.LANGFUSE, false)), "prompts[0].name"),
                quality("a repeated prompt", run -> run.prompts.set(1,
                        new PromptVersion("chat-system", 1, PromptSource.LANGFUSE, false)), "prompts[1].name"),
                quality("a Langfuse prompt without version", run -> run.prompts.set(0,
                        new PromptVersion("chat-system", null, PromptSource.LANGFUSE, false)), "prompts[0].version"),
                quality("a version 0", run -> run.prompts.set(0,
                        new PromptVersion("chat-system", 0, PromptSource.LANGFUSE, false)), "prompts[0].version"),
                quality("a default text with a version", run -> run.prompts.set(3,
                        new PromptVersion("chat-empty-context", 1, PromptSource.DEFAULT, false)),
                        "prompts[3].version"),
                quality("a prompt without source", run -> run.prompts.set(0,
                        new PromptVersion("chat-system", 3, null, false)), "prompts[0].source"),
                quality("a prompt without the pinned flag", run -> run.prompts.set(0,
                        new PromptVersion("chat-system", 3, PromptSource.LANGFUSE, null)), "prompts[0].pinned"),
                quality("no retrieval config", run -> run.retrievalConfig = null, "retrieval_config"),
                quality("top k 0", run -> run.retrievalConfig = new RetrievalConfig(0, 0.6, 8000, null, null),
                        "top_k"),
                quality("top k 21", run -> run.retrievalConfig = new RetrievalConfig(21, 0.6, 8000, null, null),
                        "top_k"),
                quality("a threshold of 1.5", run -> run.retrievalConfig = new RetrievalConfig(4, 1.5, 8000, null,
                        null), "similarity_threshold"),
                quality("a context of 100 characters", run -> run.retrievalConfig = new RetrievalConfig(4, 0.6, 100,
                        null, null), "max_context_characters"),
                quality("a depth in a quality run", run -> run.retrievalConfig = new RetrievalConfig(4, 0.6, 8000,
                        50, null), "depth and ks"),
                quality("no judge", run -> run.judge = null, "judge"),
                quality("a judge without model", run -> run.judge = new JudgeInfo(" ", "m", 0.0, 7, 1), "judge.model"),
                quality("a temperature of 3", run -> run.judge = new JudgeInfo("j", "m", 3.0, 7, 1),
                        "judge.temperature"),
                quality("no evaluator", run -> run.evaluator = null, "evaluator"),
                quality("an evaluator without version", run -> run.evaluator = new EvaluatorInfo("ragas", " ", "2"),
                        "evaluator.library_version"),
                quality("a case id outside the format", run -> run.mapCase(0, c -> new EvalCase("X1", null,
                        c.category(), c.question(), c.referenceAnswer(), c.expectedDocuments())), "case.id"),
                quality("a conversation category", run -> run.mapCase(0, c -> new EvalCase(c.caseId(), null,
                        "small_talk", c.question(), c.referenceAnswer(), c.expectedDocuments())), "case.category"),
                quality("a question of 301 characters", run -> run.mapCase(0, c -> new EvalCase(c.caseId(), null,
                        c.category(), longText, c.referenceAnswer(), c.expectedDocuments())), "case.question"),
                quality("a question that is too short", run -> run.mapCase(0, c -> new EvalCase(c.caseId(), null,
                        c.category(), "corta", c.referenceAnswer(), c.expectedDocuments())), "case.question"),
                quality("a reference answer that is too short", run -> run.mapCase(0, c -> new EvalCase(c.caseId(),
                        null, c.category(), c.question(), "corta", c.expectedDocuments())), "case.reference_answer"),
                quality("three expected documents", run -> run.mapCase(0, c -> new EvalCase(c.caseId(), null,
                        c.category(), c.question(), c.referenceAnswer(), List.of("a", "b", "c"))),
                        "case.expected_documents"),
                quality("a repeated expected document", run -> run.mapCase(0, c -> new EvalCase(c.caseId(), null,
                        c.category(), c.question(), c.referenceAnswer(), List.of("a", "a"))),
                        "case.expected_documents"),
                quality("an expected document that is a sentence", run -> run.mapCase(0, c -> new EvalCase(
                        c.caseId(), null, c.category(), c.question(), c.referenceAnswer(),
                        List.of("this is not a document id"))), "case.expected_documents"),
                quality("an answered case without documents", run -> run.mapCase(0, c -> new EvalCase(c.caseId(),
                        null, c.category(), c.question(), c.referenceAnswer(), List.of())), "case_kind"),
                quality("no case kind", run -> run.mapResult(0, r -> EvalRunFixtures.withKind(r, null)),
                        "case_kind"),
                quality("a case twice in the run", run -> run.mapCase(1, c -> new EvalCase("G601", null,
                        c.category(), c.question(), c.referenceAnswer(), c.expectedDocuments())), "repeated"),
                quality("a missing metric", run -> run.withScores(0, scores("faithfulness", 1.0)), "scores"),
                quality("an extra metric", run -> run.withScores(2, scores("faithfulness", 1.0,
                        "answer_relevancy", 0.5)), "scores"),
                quality("a metric above 1", run -> run.withScores(0, scores("faithfulness", 1.5,
                        "answer_relevancy", 0.5, "context_precision", 0.5)), "scores.faithfulness"),
                quality("a negative metric", run -> run.withScores(0, scores("faithfulness", -0.1,
                        "answer_relevancy", 0.5, "context_precision", 0.5)), "scores.faithfulness"),
                quality("a retrieved document that is a sentence", run -> run.mapResult(0,
                        r -> EvalRunFixtures.withDocuments(r, List.of("the answer of the assistant was this"))),
                        "retrieved_documents"),
                quality("no abstained flag", run -> run.mapResult(0,
                        r -> EvalRunFixtures.withAbstained(r, null)), "abstained"),
                quality("a trace id with spaces", run -> run.mapResult(0,
                        r -> EvalRunFixtures.withTrace(r, "not a trace")), "trace_id"),
                retrieval("a judge in a retrieval run", run -> run.judge = new JudgeInfo("j", "m", 0.0, 7, 1),
                        "judge"),
                retrieval("an evaluator in a retrieval run", run -> run.evaluator = new EvaluatorInfo("r", "1", "1"),
                        "evaluator"),
                retrieval("a prompt in a retrieval run", run -> run.prompts.add(new PromptVersion("chat-system", 1,
                        PromptSource.LANGFUSE, false)), "prompts"),
                retrieval("no depth", run -> run.retrievalConfig = new RetrievalConfig(4, 0.6, 8000, null,
                        List.of(1)), "depth"),
                retrieval("a depth of 51", run -> run.retrievalConfig = new RetrievalConfig(4, 0.6, 8000, 51,
                        List.of(1)), "depth"),
                retrieval("no ks", run -> run.retrievalConfig = new RetrievalConfig(4, 0.6, 8000, 50, List.of()),
                        "ks"),
                retrieval("a k of 0", run -> run.retrievalConfig = new RetrievalConfig(4, 0.6, 8000, 50,
                        List.of(0, 3)), "ks"),
                retrieval("a repeated k", run -> run.retrievalConfig = new RetrievalConfig(4, 0.6, 8000, 50,
                        List.of(3, 3)), "ks"),
                retrieval("a k above the depth", run -> run.retrievalConfig = new RetrievalConfig(4, 0.6, 8000, 10,
                        List.of(1, 20)), "ks"),
                retrieval("a null score", run -> run.withScores(0, scores("hit_at_1", null, "recall_at_1", 1,
                        "hit_at_3", 1, "recall_at_3", 1, "hit_at_5", 1, "recall_at_5", 1, "reciprocal_rank", 1)),
                        "scores.hit_at_1"),
                retrieval("a metric for a k that was not asked", run -> run.withScores(0, scores("hit_at_2", 1,
                        "recall_at_2", 1, "reciprocal_rank", 1)), "scores"),
                retrieval("an unanswered case", run -> run.mapResult(0,
                        r -> EvalRunFixtures.withKind(r, CaseKind.UNANSWERED)), "case_kind"),
                retrieval("a trace id", run -> run.mapResult(0, r -> EvalRunFixtures.withTrace(r, "abc")), "trace_id"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRuns")
    void aRunBreakingARuleIsRefusedNamingTheFieldAndNeverQuotingText(Mutation mutation) {
        Builder run = mutation.name().startsWith("retrieval") ? validRetrieval() : valid();
        mutation.change().accept(run);

        assertThatThrownBy(() -> validator.validate(run.build()))
                .isInstanceOfSatisfying(EvalRunInvalidException.class, error -> {
                    assertThat(error.publicDetail()).contains(mutation.expected());
                    assertThat(error.publicDetail()).doesNotContain("x".repeat(20))
                            .doesNotContain("the answer of the assistant").doesNotContain("Mi boleto");
                });
    }

}
