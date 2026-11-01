package com.shoplab.helpdesk.retrieval.infrastructure.out.persistence;

/** Writes a vector in the text form pgvector reads: {@code [v1,v2,...]} (Float.toString never depends on locale). */
final class VectorLiteral {

    private VectorLiteral() {
    }

    static String of(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("A vector needs at least one value");
        }
        StringBuilder literal = new StringBuilder(vector.length * 10).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }
}
