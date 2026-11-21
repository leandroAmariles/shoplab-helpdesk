package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of the API of the evaluation history (contracts/eval-runs-api.md); a key that is not defined is refused. */
public record JudgeRequest(String model, @JsonProperty("underlying_model") String underlyingModel, Double temperature,
        Integer seed, @JsonProperty("max_input_chars") Integer maxInputChars) {

    @JsonAnySetter
    void unknown(String key, Object value) {
        UnknownProperty.reject(key);
    }
}
