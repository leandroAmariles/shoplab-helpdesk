package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Body of the API of the evaluation history (contracts/eval-runs-api.md); a key that is not defined is refused. */
public record PromptVersionRequest(String name, Integer version, String source, Boolean pinned) {

    @JsonAnySetter
    void unknown(String key, Object value) {
        UnknownProperty.reject(key);
    }
}
