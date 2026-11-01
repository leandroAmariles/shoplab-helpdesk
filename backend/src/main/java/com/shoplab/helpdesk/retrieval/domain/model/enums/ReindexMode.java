package com.shoplab.helpdesk.retrieval.domain.model.enums;

import java.util.Locale;

/** What the start-up does besides indexing the pending documents ({@code reindex-on-startup}: false, true, force). */
public enum ReindexMode {
    NONE,
    CHANGED_MODEL,
    ALL;

    /** Reads {@code false}, {@code true} or {@code force} (any case); anything else is rejected. */
    public static ReindexMode fromSetting(String setting) {
        if (setting == null || setting.isBlank()) {
            return NONE;
        }
        return switch (setting.trim().toLowerCase(Locale.ROOT)) {
            case "false" -> NONE;
            case "true" -> CHANGED_MODEL;
            case "force" -> ALL;
            default -> throw new IllegalArgumentException(
                    "reindex-on-startup must be false, true or force but was: " + setting);
        };
    }
}
