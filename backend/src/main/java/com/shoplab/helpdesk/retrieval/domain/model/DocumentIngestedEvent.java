package com.shoplab.helpdesk.retrieval.domain.model;

import java.util.UUID;

/** A document was stored (or stored again) without vectors; published inside the retrieval module only. */
public record DocumentIngestedEvent(UUID documentId, String source) {
}
