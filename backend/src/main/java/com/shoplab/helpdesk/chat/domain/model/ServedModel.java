package com.shoplab.helpdesk.chat.domain.model;

/**
 * Who answered, as the gateway reports it, and whether it was not the primary one (fallback, FR-015). The gateway
 * reports the name of the model group that answered ({@code chat} for the primary model, another name for the
 * fallback); the real model is in the gateway's own log.
 *
 * @param name     what the gateway reported, {@value #UNKNOWN} when the response does not say
 * @param fallback {@code true} when {@code name} differs from the name of the primary model
 */
public record ServedModel(String name, boolean fallback) {

    public static final String UNKNOWN = "unknown";

    public ServedModel {
        name = name == null || name.isBlank() ? UNKNOWN : name;
    }

    /** Nothing is known: it is never marked as fallback. */
    public static ServedModel unknown() {
        return new ServedModel(UNKNOWN, false);
    }

    /**
     * @param served  the model name of the response, may be {@code null}
     * @param primary the configured primary model; blank means it is not known and fallback is never reported
     */
    public static ServedModel of(String served, String primary) {
        ServedModel candidate = new ServedModel(served, false);
        boolean known = !UNKNOWN.equals(candidate.name()) && primary != null && !primary.isBlank();
        return new ServedModel(candidate.name(), known && !candidate.name().equals(primary));
    }
}
