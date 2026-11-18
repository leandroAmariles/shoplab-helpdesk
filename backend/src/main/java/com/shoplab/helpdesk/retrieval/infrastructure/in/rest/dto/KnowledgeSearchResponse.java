package com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto;

import java.util.List;

/**
 * Answer of the knowledge search.
 *
 * @param model   embedding model the search compared with
 * @param results chunks from the closest to the farthest, in the order the search returned them
 */
public record KnowledgeSearchResponse(String model, List<KnowledgeSearchHitResponse> results) {
}
