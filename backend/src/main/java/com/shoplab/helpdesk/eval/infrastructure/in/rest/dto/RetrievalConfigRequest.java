package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Body of the API of the evaluation history (contracts/eval-runs-api.md); a key that is not defined is refused. */
public record RetrievalConfigRequest(@JsonProperty("top_k") Integer topK,
        @JsonProperty("similarity_threshold") Double similarityThreshold,
        @JsonProperty("max_context_characters") Integer maxContextCharacters, Integer depth,
        List<Integer> ks) {

    @JsonAnySetter
    void unknown(String key, Object value) {
        UnknownProperty.reject(key);
    }
}
