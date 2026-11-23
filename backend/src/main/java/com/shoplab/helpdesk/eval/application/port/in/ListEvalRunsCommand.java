package com.shoplab.helpdesk.eval.application.port.in;

/**
 * What the request of the list says, as it came: the use case checks it. {@code origin} and {@code limit} are
 * optional.
 */
public record ListEvalRunsCommand(String kind, String origin, String limit) {
}
