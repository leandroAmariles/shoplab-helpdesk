package com.shoplab.helpdesk.architecture.violations.domain;

import io.opentelemetry.api.trace.Span;

/** Fixture: a domain class that knows OpenTelemetry (violates D1, LAB-64). */
public class OpenTelemetryInDomain {

    public Span span() {
        return Span.getInvalid();
    }
}
