package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** What the assistant uses now (contracts/eval-runs-api.md). */
public record EvalSettingsResponse(RetrievalPart retrieval, List<PromptPart> prompts) {

    /** How the assistant retrieves context. */
    public record RetrievalPart(@JsonProperty("top_k") int topK,
            @JsonProperty("similarity_threshold") double similarityThreshold,
            @JsonProperty("max_context_characters") int maxContextCharacters) {
    }

    /** The version served of a prompt; the version is {@code null} for the packaged default text. */
    public record PromptPart(String name, Integer version, String source) {
    }
}
