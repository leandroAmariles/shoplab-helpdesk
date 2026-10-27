package com.shoplab.helpdesk.architecture.violations.layers.fakemodule.domain;

import com.shoplab.helpdesk.architecture.violations.layers.fakemodule.infrastructure.FakeAdapter;

/** Fixture: the domain depends on an adapter (violates D2). */
public class DomainUsesInfrastructure {

    private final FakeAdapter adapter = new FakeAdapter();

    public FakeAdapter adapter() {
        return adapter;
    }
}
