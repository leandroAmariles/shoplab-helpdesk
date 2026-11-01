package com.shoplab.helpdesk.retrieval.domain.model.enums;

/** Indexing state of a document; the external value is the literal stored in {@code document.index_status}. */
public enum IndexStatus {
    PENDING("PENDING"),
    INDEXED("INDEXED"),
    ERROR("ERROR");

    private final String externalValue;

    IndexStatus(String externalValue) {
        this.externalValue = externalValue;
    }

    public String getExternalValue() {
        return externalValue;
    }
}
