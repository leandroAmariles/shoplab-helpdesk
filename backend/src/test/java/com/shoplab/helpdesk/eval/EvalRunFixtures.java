package com.shoplab.helpdesk.eval;

import com.shoplab.helpdesk.eval.application.usecase.CaseRevisionHasher;
import com.shoplab.helpdesk.eval.domain.model.EvalCase;
import com.shoplab.helpdesk.eval.domain.model.EvalResult;
import com.shoplab.helpdesk.eval.domain.model.EvalRun;
import com.shoplab.helpdesk.eval.domain.model.EvaluatorInfo;
import com.shoplab.helpdesk.eval.domain.model.JudgeInfo;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.enums.CaseKind;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Valid runs, cases and bodies for the tests of the evaluation history; each test breaks one thing on purpose. */
public final class EvalRunFixtures {

    public static final String DATASET_HASH = "d".repeat(64);
    public static final String COMMIT = "5".repeat(40);
    public static final String DOCUMENT = "payments-03-boleto-and-vouchers";

    private EvalRunFixtures() {
    }

    public static EvalCase answeredCase(String id) {
        return new EvalCase(id, null, "payments", "Mi boleto venció sin que lo pagara, ¿qué puedo hacer? " + id,
                "Si el boleto vence sin pago, el pedido pasa a cancelado y puedes volver a hacerlo. " + id,
                List.of(DOCUMENT));
    }

    public static EvalCase unansweredCase(String id) {
        return new EvalCase(id, null, "payments", "¿Qué porcentaje de interés cobran las cuotas? " + id,
                "No tengo información sobre ese porcentaje en los documentos disponibles. " + id, List.of());
    }

    public static EvalResult qualityResult(EvalCase evalCase, Double faithfulness, Double relevancy,
            Double precision) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("faithfulness", faithfulness);
        scores.put("answer_relevancy", relevancy);
        scores.put("context_precision", precision);
        return new EvalResult(evalCase, CaseKind.ANSWERED, scores, List.of(DOCUMENT, DOCUMENT), false,
                "trace" + evalCase.caseId().toLowerCase());
    }

    public static EvalResult unansweredResult(EvalCase evalCase, Double faithfulness) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("faithfulness", faithfulness);
        return new EvalResult(evalCase, CaseKind.UNANSWERED, scores, List.of(), true, null);
    }

    public static EvalResult retrievalResult(EvalCase evalCase, double hit, double recall, double reciprocalRank) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int k : new int[] {1, 3, 5}) {
            scores.put("hit_at_" + k, hit);
            scores.put("recall_at_" + k, recall);
        }
        scores.put("reciprocal_rank", reciprocalRank);
        return new EvalResult(evalCase, CaseKind.ANSWERED, scores, List.of(DOCUMENT), null, null);
    }

    /** The run as the use case hands it to the store: every case with the hash of its revision. */
    public static EvalRun hashed(EvalRun run) {
        CaseRevisionHasher hasher = new CaseRevisionHasher();
        return run.withResults(run.results().stream().map(result -> new EvalResult(
                result.evalCase().withContentHash(hasher.hash(result.evalCase())), result.caseKind(),
                result.scores(), result.retrievedDocuments(), result.abstained(), result.traceId())).toList());
    }

    public static Map<String, Double> scores(Object... pairs) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1] == null ? null : ((Number) pairs[index + 1]).doubleValue());
        }
        return map;
    }

    public static List<PromptVersion> prompts() {
        return new ArrayList<>(List.of(
                new PromptVersion("chat-system", 3, PromptSource.LANGFUSE, false),
                new PromptVersion("chat-small-talk", 1, PromptSource.LANGFUSE, false),
                new PromptVersion("chat-context", 2, PromptSource.LANGFUSE, true),
                new PromptVersion("chat-empty-context", null, PromptSource.DEFAULT, false)));
    }

    /** A quality run of three cases: two answered (G{base}01, G{base}02) and one without answer (G{base}03). */
    public static Builder quality(String runId, int base) {
        Builder builder = new Builder();
        builder.runId = runId;
        builder.kind = RunKind.QUALITY;
        builder.prompts = prompts();
        builder.judge = new JudgeInfo("judge", "ollama_chat/qwen2.5:7b", 0.0, 7, 24000);
        builder.evaluator = new EvaluatorInfo("ragas", "0.4.4", "2");
        builder.retrievalConfig = new RetrievalConfig(4, 0.6, 8000, null, null);
        builder.results.add(qualityResult(answeredCase("G" + base + "01"), 1.0, 0.9, 0.8));
        builder.results.add(qualityResult(answeredCase("G" + base + "02"), 0.5, 0.4, 0.3));
        builder.results.add(unansweredResult(unansweredCase("G" + base + "03"), null));
        return builder;
    }

    /** A retrieval run of two answered cases (G{base}01, G{base}02) with k = 1, 3 and 5. */
    public static Builder retrieval(String runId, int base) {
        Builder builder = new Builder();
        builder.runId = runId;
        builder.kind = RunKind.RETRIEVAL;
        builder.retrievalConfig = new RetrievalConfig(4, 0.6, 8000, 50, List.of(1, 3, 5));
        builder.results.add(retrievalResult(answeredCase("G" + base + "01"), 1, 1, 1.0));
        builder.results.add(retrievalResult(answeredCase("G" + base + "02"), 0, 0, 0.0));
        return builder;
    }

    public static EvalResult withKind(EvalResult result, CaseKind kind) {
        return new EvalResult(result.evalCase(), kind, result.scores(), result.retrievedDocuments(),
                result.abstained(), result.traceId());
    }

    public static EvalResult withDocuments(EvalResult result, List<String> documents) {
        return new EvalResult(result.evalCase(), result.caseKind(), result.scores(), documents, result.abstained(),
                result.traceId());
    }

    public static EvalResult withAbstained(EvalResult result, Boolean abstained) {
        return new EvalResult(result.evalCase(), result.caseKind(), result.scores(), result.retrievedDocuments(),
                abstained, result.traceId());
    }

    public static EvalResult withTrace(EvalResult result, String traceId) {
        return new EvalResult(result.evalCase(), result.caseKind(), result.scores(), result.retrievedDocuments(),
                result.abstained(), traceId);
    }

    /** The mutable shape of a run: tests change a field and build. */
    public static final class Builder {

        public String runId;
        public RunKind kind;
        public Instant startedAt = Instant.parse("2026-11-21T14:02:11Z");
        public Instant finishedAt = Instant.parse("2026-11-21T14:11:03Z");
        public String origin = "local";
        public String commitSha = COMMIT;
        public String datasetHash = DATASET_HASH;
        public Integer casesMeasured;
        public Integer casesExcluded = 24;
        public String assistantModel = "llama3.2:3b";
        public String embeddingModel = "bge-m3";
        public List<PromptVersion> prompts = new ArrayList<>();
        public JudgeInfo judge;
        public EvaluatorInfo evaluator;
        public RetrievalConfig retrievalConfig;
        public List<EvalResult> results = new ArrayList<>();

        public Builder mapResult(int index, UnaryOperator<EvalResult> change) {
            results.set(index, change.apply(results.get(index)));
            return this;
        }

        public Builder mapCase(int index, UnaryOperator<EvalCase> change) {
            return mapResult(index, result -> new EvalResult(change.apply(result.evalCase()), result.caseKind(),
                    result.scores(), result.retrievedDocuments(), result.abstained(), result.traceId()));
        }

        public Builder withScores(int index, Map<String, Double> scores) {
            return mapResult(index, result -> new EvalResult(result.evalCase(), result.caseKind(), scores,
                    result.retrievedDocuments(), result.abstained(), result.traceId()));
        }

        public EvalRun build() {
            return new EvalRun(runId, kind, startedAt, finishedAt, origin, commitSha, datasetHash,
                    casesMeasured == null ? Integer.valueOf(results.size()) : casesMeasured, casesExcluded,
                    assistantModel, embeddingModel, prompts, judge, evaluator, retrievalConfig, results);
        }
    }

    /** The body of {@code POST /api/eval/runs} for a quality run of two cases (G{base}01 answered, G{base}02 not). */
    public static String qualityJson(String runId, int base) {
        return """
                {"run_id":"%s","kind":"QUALITY","started_at":"2026-11-21T14:02:11Z","finished_at":"2026-11-21T14:11:03Z",
                 "origin":"local","commit_sha":"%s","dataset_hash":"%s","cases_measured":2,"cases_excluded":24,
                 "assistant_model":"llama3.2:3b","embedding_model":"bge-m3",
                 "prompts":[
                   {"name":"chat-system","version":3,"source":"LANGFUSE","pinned":false},
                   {"name":"chat-small-talk","version":1,"source":"LANGFUSE","pinned":false},
                   {"name":"chat-context","version":2,"source":"LANGFUSE","pinned":true},
                   {"name":"chat-empty-context","version":null,"source":"DEFAULT","pinned":false}],
                 "judge":{"model":"judge","underlying_model":"ollama_chat/qwen2.5:7b","temperature":0.0,"seed":7,
                          "max_input_chars":24000},
                 "evaluator":{"library":"ragas","library_version":"0.4.4","judgment_instructions_version":"2"},
                 "retrieval_config":{"top_k":4,"similarity_threshold":0.6,"max_context_characters":8000},
                 "results":[
                   {"case":{"id":"G%d01","category":"payments",
                            "question":"Mi boleto venció sin que lo pagara, ¿qué puedo hacer?",
                            "reference_answer":"El boleto vencido cancela el pedido y puedes volver a hacerlo.",
                            "expected_documents":["payments-03-boleto-and-vouchers"]},
                    "case_kind":"answered",
                    "scores":{"faithfulness":1.0,"answer_relevancy":0.6768,"context_precision":1.0},
                    "retrieved_documents":["payments-05-faq-payments","payments-03-boleto-and-vouchers"],
                    "abstained":false,"trace_id":"815a0c4399777df9b9abe61cdcadb9f9"},
                   {"case":{"id":"G%d02","category":"payments",
                            "question":"¿Qué porcentaje de interés cobran las cuotas?",
                            "reference_answer":"No tengo información sobre ese porcentaje en los documentos.",
                            "expected_documents":[]},
                    "case_kind":"unanswered","scores":{"faithfulness":null},"retrieved_documents":[],
                    "abstained":true,"trace_id":null}]}
                """.formatted(runId, COMMIT, DATASET_HASH, base, base);
    }
}
