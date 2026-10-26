package com.shoplab.helpdesk.architecture.violations.block;

import java.util.UUID;

/** Fixture: draws an id from the OS entropy source (violates A6). */
public class UsesOsEntropy {

    public String id() {
        return UUID.randomUUID().toString();
    }
}
