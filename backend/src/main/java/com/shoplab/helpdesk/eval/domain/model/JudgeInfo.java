package com.shoplab.helpdesk.eval.domain.model;

/** The judge model of a quality measurement and its settings. */
public record JudgeInfo(String model, String underlyingModel, Double temperature, Integer seed,
        Integer maxInputChars) {
}
