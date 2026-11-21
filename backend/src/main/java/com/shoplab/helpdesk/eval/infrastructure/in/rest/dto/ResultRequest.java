package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Body of the API of the evaluation history (contracts/eval-runs-api.md); a key that is not defined is refused. */
public record ResultRequest(@JsonProperty("case") CaseRequest caseData, @JsonProperty("case_kind") String caseKind,
        Map<String, Double> scores, @JsonProperty("retrieved_documents") List<String> retrievedDocuments,
        Boolean abstained, @JsonProperty("trace_id") String traceId) {

    @JsonAnySetter
    void unknown(String key, Object value) {
        UnknownProperty.reject(key);
    }
}
