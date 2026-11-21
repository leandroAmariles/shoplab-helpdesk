package com.shoplab.helpdesk.eval.infrastructure.in.rest.util.mapper;

import com.shoplab.helpdesk.eval.domain.model.EvalCase;
import com.shoplab.helpdesk.eval.domain.model.EvalResult;
import com.shoplab.helpdesk.eval.domain.model.EvalRun;
import com.shoplab.helpdesk.eval.domain.model.EvaluatorInfo;
import com.shoplab.helpdesk.eval.domain.model.JudgeInfo;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.SavedRun;
import com.shoplab.helpdesk.eval.domain.model.enums.CaseKind;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.CaseRequest;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.EvalRunRequest;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.EvalRunSavedResponse;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.EvaluatorRequest;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.JudgeRequest;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.PromptVersionRequest;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.ResultRequest;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.RetrievalConfigRequest;
import java.util.List;
import java.util.function.Function;

/**
 * Field by field mapping with no rule in it: a text that is not a known kind, case kind or source becomes {@code null}
 * and the use case refuses the run, naming the field.
 */
public final class EvalRunRequestMapper {

    private EvalRunRequestMapper() {
    }

    public static EvalRun toRun(EvalRunRequest request) {
        return new EvalRun(request.runId(), request.kind() == null ? null : RunKind.fromValue(request.kind())
                .orElse(null), request.startedAt(), request.finishedAt(), request.origin(), request.commitSha(),
                request.datasetHash(), request.casesMeasured(), request.casesExcluded(), request.assistantModel(),
                request.embeddingModel(), mapAll(request.prompts(), EvalRunRequestMapper::toPrompt),
                toJudge(request.judge()), toEvaluator(request.evaluator()), toConfig(request.retrievalConfig()),
                mapAll(request.results(), EvalRunRequestMapper::toResult));
    }

    public static EvalRunSavedResponse toResponse(SavedRun saved) {
        return new EvalRunSavedResponse(saved.runId(), saved.kind().name(), saved.results(),
                saved.newCaseRevisions());
    }

    private static PromptVersion toPrompt(PromptVersionRequest prompt) {
        return prompt == null ? null : new PromptVersion(prompt.name(), prompt.version(),
                prompt.source() == null ? null : PromptSource.fromValue(prompt.source()).orElse(null),
                prompt.pinned());
    }

    private static JudgeInfo toJudge(JudgeRequest judge) {
        return judge == null ? null : new JudgeInfo(judge.model(), judge.underlyingModel(), judge.temperature(),
                judge.seed(), judge.maxInputChars());
    }

    private static EvaluatorInfo toEvaluator(EvaluatorRequest evaluator) {
        return evaluator == null ? null : new EvaluatorInfo(evaluator.library(), evaluator.libraryVersion(),
                evaluator.judgmentInstructionsVersion());
    }

    private static RetrievalConfig toConfig(RetrievalConfigRequest config) {
        return config == null ? null : new RetrievalConfig(config.topK(), config.similarityThreshold(),
                config.maxContextCharacters(), config.depth(), config.ks());
    }

    private static EvalResult toResult(ResultRequest result) {
        return result == null ? null : new EvalResult(toCase(result.caseData()),
                result.caseKind() == null ? null : CaseKind.fromValue(result.caseKind()).orElse(null),
                result.scores(), result.retrievedDocuments(), result.abstained(), result.traceId());
    }

    private static EvalCase toCase(CaseRequest evalCase) {
        return evalCase == null ? null : new EvalCase(evalCase.id(), null, evalCase.category(),
                evalCase.question(), evalCase.referenceAnswer(), evalCase.expectedDocuments());
    }

    private static <S, T> List<T> mapAll(List<S> source, Function<S, T> mapper) {
        return source == null ? null : source.stream().map(mapper).toList();
    }
}
