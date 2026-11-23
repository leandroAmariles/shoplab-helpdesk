package com.shoplab.helpdesk.eval.domain.model;

/** How many runs of a kind come from an origin (a short label such as {@code local} or {@code dev}). */
public record OriginCount(String origin, long count) {
}
