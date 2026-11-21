package com.shoplab.helpdesk.eval.application.usecase;

import static com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants.ANSWER_RELEVANCY;
import static com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants.CONTEXT_PRECISION;
import static com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants.FAITHFULNESS;
import static com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants.HIT_AT_PREFIX;
import static com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants.RECALL_AT_PREFIX;
import static com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants.RECIPROCAL_RANK;

import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.model.EvalCase;
import com.shoplab.helpdesk.eval.domain.model.EvalResult;
import com.shoplab.helpdesk.eval.domain.model.EvalRun;
import com.shoplab.helpdesk.eval.domain.model.EvaluatorInfo;
import com.shoplab.helpdesk.eval.domain.model.JudgeInfo;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants;
import com.shoplab.helpdesk.eval.domain.model.enums.CaseKind;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Checks a run against the rules of the data model before anything is written (specs/LAB-31, data-model.md). It stops at
 * the first rule that fails and names the field or the case in the detail, never the text of a question or an answer.
 */
@Component
public class EvalRunValidator {

    private static final double MAX_JUDGE_TEMPERATURE = 2.0;

    public void validate(EvalRun run) {
        require(run != null, "run: required");
        checkIdentity(run);
        checkParameters(run);
        checkResults(run);
    }

    private static void checkIdentity(EvalRun run) {
        require(matches(EvalConstants.RUN_ID, run.runId()), "run_id: must match " + EvalConstants.RUN_ID.pattern());
        require(run.kind() != null, "kind: must be RETRIEVAL or QUALITY");
        require(run.startedAt() != null, "started_at: required");
        require(run.finishedAt() != null && !run.finishedAt().isBefore(run.startedAt()),
                "finished_at: required and not before started_at");
        require(matches(EvalConstants.ORIGIN, run.origin()), "origin: must match " + EvalConstants.ORIGIN.pattern());
        require(run.commitSha() == null || matches(EvalConstants.COMMIT_SHA, run.commitSha()),
                "commit_sha: must be 40 lowercase hexadecimal characters or absent");
        require(matches(EvalConstants.SHA_256, run.datasetHash()),
                "dataset_hash: must be 64 lowercase hexadecimal characters");
        require(run.casesExcluded() != null && run.casesExcluded() >= 0, "cases_excluded: must be zero or more");
        require(notBlank(run.embeddingModel()), "embedding_model: required");
        require(run.assistantModel() == null || !run.assistantModel().isBlank(),
                "assistant_model: must not be blank (leave it out when unknown)");
    }

    private static void checkParameters(EvalRun run) {
        checkPrompts(run);
        checkRetrievalConfig(run);
        if (run.kind() == RunKind.QUALITY) {
            checkJudge(run.judge());
            checkEvaluator(run.evaluator());
        } else {
            require(run.judge() == null, "judge: only a quality run has a judge");
            require(run.evaluator() == null, "evaluator: only a quality run has an evaluator");
        }
    }

    private static void checkPrompts(EvalRun run) {
        List<PromptVersion> prompts = run.prompts();
        require(prompts != null, "prompts: required");
        if (run.kind() == RunKind.RETRIEVAL) {
            require(prompts.isEmpty(), "prompts: a retrieval run uses no prompts and must send an empty list");
            return;
        }
        Set<String> names = new HashSet<>();
        for (int index = 0; index < prompts.size(); index++) {
            PromptVersion prompt = prompts.get(index);
            String at = "prompts[" + index + "]";
            require(prompt != null && EvalConstants.PROMPT_NAMES.contains(prompt.name()),
                    at + ".name: must be one of " + EvalConstants.PROMPT_NAMES);
            require(names.add(prompt.name()), at + ".name: repeated");
            require(prompt.source() != null, at + ".source: must be LANGFUSE or DEFAULT");
            require(prompt.pinned() != null, at + ".pinned: required");
            if (prompt.source() == PromptSource.LANGFUSE) {
                require(prompt.version() != null && prompt.version() >= 1,
                        at + ".version: a Langfuse prompt has a version from 1");
            } else {
                require(prompt.version() == null, at + ".version: the default text has no version");
            }
        }
        require(names.size() == EvalConstants.PROMPT_NAMES.size(),
                "prompts: a quality run must list exactly " + EvalConstants.PROMPT_NAMES);
    }

    private static void checkRetrievalConfig(EvalRun run) {
        RetrievalConfig config = run.retrievalConfig();
        require(config != null, "retrieval_config: required");
        require(config.topK() != null && config.topK() >= EvalConstants.TOP_K_MIN
                && config.topK() <= EvalConstants.TOP_K_MAX,
                "retrieval_config.top_k: must be from " + EvalConstants.TOP_K_MIN + " to " + EvalConstants.TOP_K_MAX);
        require(config.similarityThreshold() != null && inScoreRange(config.similarityThreshold()),
                "retrieval_config.similarity_threshold: must be between 0 and 1");
        require(config.maxContextCharacters() != null
                && config.maxContextCharacters() >= EvalConstants.MIN_CONTEXT_CHARACTERS,
                "retrieval_config.max_context_characters: must be at least " + EvalConstants.MIN_CONTEXT_CHARACTERS);
        if (run.kind() == RunKind.QUALITY) {
            require(config.depth() == null && config.ks() == null,
                    "retrieval_config: depth and ks belong to a retrieval run");
            return;
        }
        require(config.depth() != null && config.depth() >= EvalConstants.DEPTH_MIN
                && config.depth() <= EvalConstants.DEPTH_MAX,
                "retrieval_config.depth: must be from " + EvalConstants.DEPTH_MIN + " to " + EvalConstants.DEPTH_MAX);
        require(config.ks() != null && !config.ks().isEmpty(), "retrieval_config.ks: at least one k");
        Set<Integer> seen = new HashSet<>();
        for (Integer k : config.ks()) {
            require(k != null && k >= 1 && k <= config.depth() && seen.add(k),
                    "retrieval_config.ks: positive, not repeated and not above depth");
        }
    }

    private static void checkJudge(JudgeInfo judge) {
        require(judge != null, "judge: required in a quality run");
        require(notBlank(judge.model()), "judge.model: required");
        require(notBlank(judge.underlyingModel()), "judge.underlying_model: required");
        require(judge.temperature() != null && judge.temperature() >= 0 && judge.temperature() <= MAX_JUDGE_TEMPERATURE,
                "judge.temperature: must be between 0 and " + MAX_JUDGE_TEMPERATURE);
        require(judge.seed() != null, "judge.seed: required");
        require(judge.maxInputChars() != null && judge.maxInputChars() >= 1, "judge.max_input_chars: must be positive");
    }

    private static void checkEvaluator(EvaluatorInfo evaluator) {
        require(evaluator != null, "evaluator: required in a quality run");
        require(notBlank(evaluator.library()), "evaluator.library: required");
        require(notBlank(evaluator.libraryVersion()), "evaluator.library_version: required");
        require(notBlank(evaluator.judgmentInstructionsVersion()), "evaluator.judgment_instructions_version: required");
    }

    private static void checkResults(EvalRun run) {
        List<EvalResult> results = run.results();
        require(results != null && !results.isEmpty(), "results: at least one result");
        require(run.casesMeasured() != null && run.casesMeasured() == results.size(),
                "cases_measured: must equal the number of results");
        Set<String> caseIds = new HashSet<>();
        for (int index = 0; index < results.size(); index++) {
            EvalResult result = results.get(index);
            String at = "results[" + index + "]";
            require(result != null && result.evalCase() != null, at + ".case: required");
            checkCase(result.evalCase(), at + ".case");
            require(caseIds.add(result.evalCase().caseId()), at + ".case.id: repeated in the run");
            checkResult(run, result, at);
        }
    }

    private static void checkCase(EvalCase evalCase, String at) {
        require(matches(EvalConstants.CASE_ID, evalCase.caseId()), at + ".id: must match "
                + EvalConstants.CASE_ID.pattern());
        require(evalCase.category() != null && EvalConstants.MEASURED_CATEGORIES.contains(evalCase.category()),
                at + ".category: must be one of " + EvalConstants.MEASURED_CATEGORIES);
        require(length(evalCase.question(), EvalConstants.QUESTION_MIN, EvalConstants.QUESTION_MAX),
                at + ".question: from " + EvalConstants.QUESTION_MIN + " to " + EvalConstants.QUESTION_MAX
                        + " characters");
        require(length(evalCase.referenceAnswer(), EvalConstants.REFERENCE_ANSWER_MIN,
                EvalConstants.REFERENCE_ANSWER_MAX), at + ".reference_answer: from "
                + EvalConstants.REFERENCE_ANSWER_MIN + " to " + EvalConstants.REFERENCE_ANSWER_MAX + " characters");
        List<String> documents = evalCase.expectedDocuments();
        require(documents != null && documents.size() <= EvalConstants.MAX_EXPECTED_DOCUMENTS,
                at + ".expected_documents: at most " + EvalConstants.MAX_EXPECTED_DOCUMENTS);
        require(documentIds(documents) && new HashSet<>(documents).size() == documents.size(),
                at + ".expected_documents: document ids, not repeated");
    }

    private static void checkResult(EvalRun run, EvalResult result, String at) {
        CaseKind caseKind = result.caseKind();
        require(caseKind != null, at + ".case_kind: must be answered or unanswered");
        boolean hasDocuments = !result.evalCase().expectedDocuments().isEmpty();
        require(hasDocuments == (caseKind == CaseKind.ANSWERED),
                at + ".case_kind: answered needs expected documents and unanswered has none");
        require(result.retrievedDocuments() != null && documentIds(result.retrievedDocuments()),
                at + ".retrieved_documents: a list of document ids");
        Set<String> expected = expectedMetrics(run, caseKind, at);
        require(result.scores() != null && result.scores().keySet().equals(expected),
                at + ".scores: must have exactly the metrics " + new TreeSet<>(expected));
        for (Map.Entry<String, Double> score : result.scores().entrySet()) {
            Double value = score.getValue();
            require(value != null ? inScoreRange(value) : run.kind() == RunKind.QUALITY,
                    at + ".scores." + score.getKey() + ": must be between 0 and 1");
        }
        if (run.kind() == RunKind.RETRIEVAL) {
            require(result.abstained() == null && result.traceId() == null,
                    at + ": a retrieval result has no abstained and no trace_id");
            return;
        }
        require(result.abstained() != null, at + ".abstained: required in a quality result");
        require(result.traceId() == null || matches(EvalConstants.TRACE_ID, result.traceId()),
                at + ".trace_id: must match " + EvalConstants.TRACE_ID.pattern());
    }

    private static Set<String> expectedMetrics(EvalRun run, CaseKind caseKind, String at) {
        if (run.kind() == RunKind.QUALITY) {
            return caseKind == CaseKind.ANSWERED
                    ? Set.of(FAITHFULNESS, ANSWER_RELEVANCY, CONTEXT_PRECISION) : Set.of(FAITHFULNESS);
        }
        require(caseKind == CaseKind.ANSWERED, at + ".case_kind: a retrieval run only measures answered cases");
        Set<String> metrics = new HashSet<>();
        metrics.add(RECIPROCAL_RANK);
        for (Integer k : run.retrievalConfig().ks()) {
            metrics.add(HIT_AT_PREFIX + k);
            metrics.add(RECALL_AT_PREFIX + k);
        }
        return metrics;
    }

    private static boolean documentIds(List<String> documents) {
        return documents != null && documents.stream().allMatch(id -> matches(EvalConstants.DOCUMENT_ID, id));
    }

    private static boolean inScoreRange(double value) {
        return value >= EvalConstants.SCORE_MIN && value <= EvalConstants.SCORE_MAX;
    }

    private static boolean length(String text, int min, int max) {
        return text != null && text.length() >= min && text.length() <= max;
    }

    private static boolean matches(Pattern pattern, String text) {
        return text != null && pattern.matcher(text).matches();
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new EvalRunInvalidException(detail);
        }
    }
}
