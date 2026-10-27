package com.shoplab.helpdesk.architecture.violations.domain;

import reactor.core.publisher.Flux;

/** Fixture: a domain class that exposes a reactive type (violates D1; allowed in application and infrastructure). */
public class ReactorInDomain {

    public Flux<String> values() {
        return Flux.just("x");
    }
}
