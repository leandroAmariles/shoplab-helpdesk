package com.shoplab.helpdesk.architecture.violations.modules.alpha.application;

import com.shoplab.helpdesk.architecture.violations.modules.beta.domain.Secret;

/** Fixture: module alpha reaches into the domain of module beta instead of using its api (violates D5). */
public class Caller {

    public Secret secret() {
        return new Secret();
    }
}
