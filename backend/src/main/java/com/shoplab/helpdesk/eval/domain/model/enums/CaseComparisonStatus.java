package com.shoplab.helpdesk.eval.domain.model.enums;

/** What happened to a case between two runs for one metric (specs/LAB-32, research R9). */
public enum CaseComparisonStatus {
    /** Same revision, both values, and the target dropped more than the tolerance. */
    WORSE,
    /** Same revision, both values, and the target rose more than the tolerance. */
    BETTER,
    /** Same revision, both values, and the change is within the tolerance. */
    SAME,
    /** Same revision but one of the two has no value: nothing is claimed. */
    NO_VALUE,
    /** The case is in both runs but its content changed between them. */
    NOT_COMPARABLE,
    ONLY_BASE,
    ONLY_TARGET
}
