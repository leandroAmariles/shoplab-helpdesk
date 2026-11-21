package com.shoplab.helpdesk.eval.domain.model;

/** The evaluation library of a quality measurement and the version of the judgment instructions. */
public record EvaluatorInfo(String library, String libraryVersion, String judgmentInstructionsVersion) {
}
