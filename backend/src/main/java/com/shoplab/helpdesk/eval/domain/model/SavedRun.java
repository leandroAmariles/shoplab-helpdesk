package com.shoplab.helpdesk.eval.domain.model;

import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;

/** The outcome of saving a run: how many results it has and how many case revisions were new. */
public record SavedRun(String runId, RunKind kind, int results, int newCaseRevisions) {
}
