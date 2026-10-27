package com.shoplab.helpdesk.architecture.violations.layers.fakemodule.application;

import com.shoplab.helpdesk.architecture.violations.layers.fakemodule.infrastructure.FakeAdapter;

/** Fixture: a use case depends on an adapter instead of a port (violates D2). */
public class ApplicationUsesInfrastructure {

    private final FakeAdapter adapter = new FakeAdapter();

    public FakeAdapter adapter() {
        return adapter;
    }
}
