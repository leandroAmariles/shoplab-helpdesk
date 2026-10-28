package com.shoplab.helpdesk.retrieval.domain.model.enums;

import java.util.Locale;
import java.util.Optional;

/** Format of an ingested document; the external value is the one the ingest service reports. */
public enum DocumentFormat {
    MARKDOWN("markdown"),
    PDF("pdf");

    private final String externalValue;

    DocumentFormat(String externalValue) {
        this.externalValue = externalValue;
    }

    public String getExternalValue() {
        return externalValue;
    }

    public static Optional<DocumentFormat> fromExternalValue(String externalValue) {
        if (externalValue == null) {
            return Optional.empty();
        }
        String normalized = externalValue.toLowerCase(Locale.ROOT);
        for (DocumentFormat format : values()) {
            if (format.externalValue.equals(normalized)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
