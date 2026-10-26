package com.shoplab.helpdesk.architecture.violations.block;

import reactor.core.publisher.Mono;

/** Fixture: waits for a publisher (violates A5). */
public class WaitsForPublisher {

    public String value() {
        return Mono.just("x").block();
    }
}
