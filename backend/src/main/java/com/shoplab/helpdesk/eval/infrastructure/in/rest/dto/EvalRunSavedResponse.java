package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What a saved run answers: how many results it has and how many case revisions were new. */
public record EvalRunSavedResponse(@JsonProperty("run_id") String runId, String kind, int results,
        @JsonProperty("new_case_revisions") int newCaseRevisions) {
}
