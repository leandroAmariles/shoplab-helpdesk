package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

/**
 * The bodies of this API are decoded without tolerating a key nobody defined: there is nowhere to put the text of an
 * answer or of a fragment, so a body that carries one is refused instead of being quietly ignored (FR-009).
 */
final class UnknownProperty {

    private UnknownProperty() {
    }

    static void reject(String key) {
        throw new IllegalArgumentException("Unknown property: " + key);
    }
}
