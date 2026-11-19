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

    /** The state stored as {@code externalValue}; the database check constraint only allows these three. */
    public static IndexStatus fromExternalValue(String externalValue) {
        for (IndexStatus status : values()) {
            if (status.externalValue.equals(externalValue)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown index status: " + externalValue);
    }
}
