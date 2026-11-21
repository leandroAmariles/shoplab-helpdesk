package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of the API of the evaluation history (contracts/eval-runs-api.md); a key that is not defined is refused. */
public record EvaluatorRequest(String library, @JsonProperty("library_version") String libraryVersion,
        @JsonProperty("judgment_instructions_version") String judgmentInstructionsVersion) {

    @JsonAnySetter
    void unknown(String key, Object value) {
        UnknownProperty.reject(key);
    }
}
