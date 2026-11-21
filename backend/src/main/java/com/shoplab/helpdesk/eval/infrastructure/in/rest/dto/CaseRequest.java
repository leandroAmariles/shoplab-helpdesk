package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Body of the API of the evaluation history (contracts/eval-runs-api.md); a key that is not defined is refused. */
public record CaseRequest(String id, String category, String question,
        @JsonProperty("reference_answer") String referenceAnswer,
        @JsonProperty("expected_documents") List<String> expectedDocuments) {

    @JsonAnySetter
    void unknown(String key, Object value) {
        UnknownProperty.reject(key);
    }
}
