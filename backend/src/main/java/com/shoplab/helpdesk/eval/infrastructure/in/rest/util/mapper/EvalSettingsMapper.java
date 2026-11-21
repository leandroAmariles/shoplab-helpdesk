package com.shoplab.helpdesk.eval.infrastructure.in.rest.util.mapper;

import com.shoplab.helpdesk.eval.domain.model.AssistantSettingsSnapshot;
import com.shoplab.helpdesk.eval.domain.model.StoredRunSummary;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.EvalSettingsResponse;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.StoredRunResponse;

/** Field by field mapping of what the API answers about the assistant and about a saved run. */
public final class EvalSettingsMapper {

    private EvalSettingsMapper() {
    }

    public static EvalSettingsResponse toResponse(AssistantSettingsSnapshot snapshot) {
        return new EvalSettingsResponse(
                new EvalSettingsResponse.RetrievalPart(snapshot.retrieval().topK(),
                        snapshot.retrieval().similarityThreshold(), snapshot.retrieval().maxContextCharacters()),
                snapshot.prompts().stream().map(prompt -> new EvalSettingsResponse.PromptPart(prompt.name(),
                        prompt.version(), prompt.source().name())).toList());
    }

    public static StoredRunResponse toResponse(StoredRunSummary run) {
        return new StoredRunResponse(run.runId(), run.kind().name(), run.startedAt(), run.recordedAt());
    }
}
