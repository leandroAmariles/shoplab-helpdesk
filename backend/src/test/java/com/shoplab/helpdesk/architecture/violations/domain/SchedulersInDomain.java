package com.shoplab.helpdesk.architecture.violations.domain;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Fixture: a domain class that picks a scheduler (violates A1 and A3). */
public class SchedulersInDomain {

    public Mono<String> value() {
        return Mono.just("x").subscribeOn(Schedulers.boundedElastic());
    }
}
